package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TranscriptionWorker implements AutoCloseable {
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final CallRecordRepository repository;
    private final LocalAudioStore audioStore;
    private final Transcriber transcriber;
    private final Clock clock;
    private final Consumer<CallRecordEvent> events;
    private final int queueCapacity;
    private final int concurrency;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, FutureTask<Void>> inFlight =
            new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean signalScheduled = new AtomicBoolean();
    private final String workerId = "call-worker-" + UUID.randomUUID();

    public TranscriptionWorker(CallRecordRepository repository,
                               LocalAudioStore audioStore,
                               Transcriber transcriber,
                               Config config,
                               Clock clock,
                               Consumer<CallRecordEvent> events) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.audioStore = Objects.requireNonNull(audioStore, "audioStore");
        this.transcriber = Objects.requireNonNull(transcriber, "transcriber");
        Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = events == null ? ignored -> {} : events;
        this.queueCapacity = config.callRecordQueueCapacity();
        this.concurrency = config.callRecordWorkerConcurrency();
        this.leaseSeconds = config.callRecordLeaseSeconds();
        this.maxAttempts = config.callRecordMaxAttempts();
        this.workers = new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, concurrency)),
                namedThreads("call-transcription-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(
                1, namedThreads("call-transcription-scheduler-"));
        scheduled.setRemoveOnCancelPolicy(true);
        this.scheduler = scheduled;
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("worker is closed");
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(
                    this::dispatchSafely, 0, 1, TimeUnit.SECONDS);
        }
    }

    public void signal() {
        if (!started.get() || closed.get()
                || !signalScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                signalScheduled.set(false);
                dispatchSafely();
            });
        } catch (RejectedExecutionException exception) {
            signalScheduled.set(false);
        }
    }

    private void dispatchSafely() {
        if (closed.get()) return;
        try {
            dispatch();
        } catch (CallRecordException | RuntimeException ignored) {
            // The repository remains the queue truth; a later bounded tick retries discovery.
        }
    }

    private void dispatch() throws CallRecordException {
        int available = concurrency - inFlight.size();
        if (available <= 0) return;
        Instant now = clock.instant();
        List<CallRecord> runnable = repository.listRunnable(now, queueCapacity);
        for (CallRecord current : runnable) {
            if (available <= 0 || closed.get()) break;
            if (inFlight.containsKey(current.id())) continue;
            final CallRecord leased;
            try {
                CallRecord candidate = CallRecordStateMachine.lease(
                        current, workerId, now, now.plusSeconds(leaseSeconds));
                leased = repository.replace(candidate, current.version());
            } catch (CallRecordException exception) {
                if (isConcurrentChange(exception)) continue;
                throw exception;
            }
            publish(leased);
            FutureTask<Void> task = new FutureTask<>(() -> {
                process(leased);
                return null;
            });
            if (inFlight.putIfAbsent(leased.id(), task) != null) continue;
            try {
                workers.execute(task);
                available--;
            } catch (RejectedExecutionException exception) {
                inFlight.remove(leased.id(), task);
                if (!closed.get()) throw exception;
            }
        }
    }

    private void process(CallRecord leased) {
        try {
            Path path = audioStore.path(leased.audio());
            TranscriptionResult result = transcriber.transcribe(
                    path, leased.transcription().model());
            if (closed.get()) return;
            Instant completedAt = clock.instant();
            CallRecord completed = CallRecordStateMachine.complete(
                    leased, leased.transcription().lease().id(), result, completedAt);
            CallRecord saved = repository.replace(completed, leased.version());
            publish(saved);
        } catch (Exception exception) {
            if (closed.get() && Thread.currentThread().isInterrupted()) return;
            persistFailure(leased, failureFor(exception));
        } finally {
            inFlight.remove(leased.id());
            if (!closed.get()) signal();
        }
    }

    private void persistFailure(CallRecord leased, CallRecordError error) {
        try {
            CallRecord failed = CallRecordStateMachine.fail(
                    leased, leased.transcription().lease().id(), error,
                    clock.instant(), maxAttempts);
            CallRecord saved = repository.replace(failed, leased.version());
            publish(saved);
        } catch (CallRecordException ignored) {
            // A stale lease or store failure must never overwrite newer durable state.
        }
    }

    private static CallRecordError failureFor(Exception exception) {
        if (exception instanceof CallRecordException structured) {
            String message = structured.getMessage();
            if (message == null || message.isBlank() || message.length() > 2_048) {
                message = "Transcription failed";
            }
            return new CallRecordError(
                    structured.code(), message, structured.retryable());
        }
        return new CallRecordError(
                "FUNASR_UNAVAILABLE", "FunASR is unavailable", true);
    }

    private void publish(CallRecord record) {
        try {
            events.accept(new CallRecordEvent(
                    record.id().toString(), record.contactAnchorPointId(),
                    record.transcription().state(), record.version()));
        } catch (RuntimeException ignored) {
            // Events are best effort and are emitted only after the durable state change.
        }
    }

    private static boolean isConcurrentChange(CallRecordException exception) {
        return "CALL_RECORD_VERSION_CONFLICT".equals(exception.code())
                || "CALL_RECORD_STATE_INVALID".equals(exception.code())
                || "CALL_RECORD_NOT_RUNNABLE".equals(exception.code());
    }

    @Override
    public void close() throws CallRecordException {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            inFlight.values().forEach(task -> task.cancel(true));
            workers.shutdownNow();
        }
        try {
            boolean schedulerStopped = scheduler.awaitTermination(
                    STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean workersStopped = workers.awaitTermination(
                    STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!schedulerStopped || !workersStopped) {
                throw new CallRecordException(
                        "CALL_RECORD_WORKER_STOP_TIMEOUT", 500,
                        "Transcription worker did not stop within its bound", true);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CallRecordException(
                    "CALL_RECORD_WORKER_STOP_INTERRUPTED", 500,
                    "Interrupted while stopping transcription worker", true, exception);
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    public interface Transcriber {
        TranscriptionResult transcribe(Path audioPath, String model) throws Exception;
    }
}
