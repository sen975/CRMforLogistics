package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TranscriptionWorker {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final CallRecordMapper mapper;
    private final MinioAudioStore audioStore;
    private final FunAsrClient transcriber;
    private final Clock clock;
    private final int concurrency;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final AiTopicActivityRecorder topicActivityRecorder;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, FutureTask<Void>> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String workerId = "call-worker-" + UUID.randomUUID();

    public TranscriptionWorker(CallRecordMapper mapper,
                               MinioAudioStore audioStore,
                               FunAsrClient transcriber,
                               CallRecordConfig config,
                               Clock clock) {
        this(mapper, audioStore, transcriber, config, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TranscriptionWorker(CallRecordMapper mapper,
                               MinioAudioStore audioStore,
                               FunAsrClient transcriber,
                               CallRecordConfig config,
                               Clock clock,
                               AiTopicActivityRecorder topicActivityRecorder) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.audioStore = Objects.requireNonNull(audioStore, "audioStore");
        this.transcriber = Objects.requireNonNull(transcriber, "transcriber");
        Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.concurrency = config.workerConcurrency();
        this.leaseSeconds = config.leaseSeconds();
        this.maxAttempts = config.maxAttempts();
        this.topicActivityRecorder = topicActivityRecorder;
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

    @PostConstruct
    public void start() {
        if (closed.get()) throw new IllegalStateException("worker is closed");
        if (started.compareAndSet(false, true)) {
            recoverProcessing();
            scheduler.scheduleWithFixedDelay(
                    this::dispatchSafely, 0, 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
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
                log.warn("Transcription worker did not stop within {}s", STOP_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recoverProcessing() {
        try {
            int recovered = mapper.recoverProcessing(clock.instant());
            if (recovered > 0) {
                log.info("Recovered {} stranded processing records", recovered);
            }
        } catch (Exception e) {
            log.error("Failed to recover processing records", e);
        }
    }

    private void dispatchSafely() {
        if (closed.get()) return;
        try {
            dispatch();
        } catch (Exception e) {
            log.debug("Dispatch iteration failed", e);
        }
    }

    private void dispatch() {
        int available = concurrency - inFlight.size();
        if (available <= 0) return;
        Instant now = clock.instant();
        List<CallRecordEntity> runnable = mapper.listRunnable(now, Math.min(available, 64));
        for (CallRecordEntity current : runnable) {
            if (available <= 0 || closed.get()) break;
            if (inFlight.containsKey(current.getId())) continue;
            CallRecordEntity leased;
            try {
                long expectedVersion = current.getVersion();
                CallRecordEntity candidate = CallRecordStateMachine.lease(
                        current, workerId, now, now.plusSeconds(leaseSeconds));
                int rows = mapper.replace(candidate, expectedVersion);
                if (rows == 0) continue;
                leased = candidate;
            } catch (CallRecordException e) {
                if (isConcurrentChange(e)) continue;
                throw e;
            }
            FutureTask<Void> task = new FutureTask<>(() -> {
                process(leased);
                return null;
            });
            if (inFlight.putIfAbsent(leased.getId(), task) != null) continue;
            try {
                workers.execute(task);
                available--;
            } catch (RejectedExecutionException e) {
                inFlight.remove(leased.getId(), task);
                if (!closed.get()) throw e;
            }
        }
    }

    private void process(CallRecordEntity leased) {
        try {
            java.nio.file.Path path = audioStore.path(new MinioAudioStore.AudioAsset(
                    leased.getAudioRelativePath(),
                    leased.getAudioOriginalFileName(),
                    leased.getAudioSizeBytes(),
                    leased.getAudioSha256(),
                    leased.getAudioContentType(),
                    leased.getAudioDurationSeconds(),
                    leased.getAudioObjectKey()));
            CallRecordStateMachine.TranscriptionResult result = transcriber.transcribe(
                    path, leased.getTranscriptionModel(), leased.getAudioDurationSeconds());
            if (closed.get()) return;
            Instant completedAt = clock.instant();
            long expectedVersion = leased.getVersion();
            CallRecordEntity completed = CallRecordStateMachine.complete(
                    leased, leased.getTranscriptionLeaseId(), result, completedAt);
            int rows = mapper.replace(completed, expectedVersion);
            if (rows > 0) {
                if (topicActivityRecorder != null) {
                    topicActivityRecorder.recordCall(
                            completed.getContactAnchorPointId(), completed.getOccurredAt());
                }
                log.info("Transcription completed for call record {}", leased.getId());
            }
        } catch (Exception e) {
            if (closed.get() && Thread.currentThread().isInterrupted()) return;
            persistFailure(leased, failureFor(e));
        } finally {
            inFlight.remove(leased.getId());
        }
    }

    private void persistFailure(CallRecordEntity leased, CallRecordStateMachine.CallRecordError error) {
        try {
            long expectedVersion = leased.getVersion();
            CallRecordEntity failed = CallRecordStateMachine.fail(
                    leased, leased.getTranscriptionLeaseId(), error,
                    clock.instant(), maxAttempts);
            mapper.replace(failed, expectedVersion);
        } catch (Exception e) {
            log.debug("Failed to persist transcription failure for {}", leased.getId(), e);
        }
    }

    private static CallRecordStateMachine.CallRecordError failureFor(Exception e) {
        if (e instanceof CallRecordException structured) {
            String message = structured.getMessage();
            if (message == null || message.isBlank() || message.length() > 2_048) {
                message = "Transcription failed";
            }
            return new CallRecordStateMachine.CallRecordError(
                    structured.code(), message, structured.retryable());
        }
        return new CallRecordStateMachine.CallRecordError(
                "FUNASR_UNAVAILABLE", "FunASR is unavailable", true);
    }

    private static boolean isConcurrentChange(CallRecordException e) {
        return "CALL_RECORD_VERSION_CONFLICT".equals(e.code())
                || "CALL_RECORD_STATE_INVALID".equals(e.code())
                || "CALL_RECORD_NOT_RUNNABLE".equals(e.code());
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
