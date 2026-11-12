package com.crmforlogistics.messagecenter;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ChatAppMessageSyncRuntime implements AutoCloseable {
    @FunctionalInterface
    interface SyncAction {
        ChatAppMessageSynchronizer.Outcome run() throws Exception;
    }

    @FunctionalInterface
    interface CloseAction {
        void close();
    }

    private final boolean enabled;
    private final long delay;
    private final TimeUnit delayUnit;
    private final SyncAction syncAction;
    private final CloseAction closeAction;
    private final ScheduledExecutorService executor;
    private final Object lifecycleLock = new Object();

    private Lifecycle lifecycle = Lifecycle.NEW;
    private int operationsInFlight;

    public static ChatAppMessageSyncRuntime open(
            Config config, ChatAppMessageSynchronizer synchronizer) {
        boolean requested = config.chatappMessageAutoSyncEnabled();
        boolean configured = config.hasChatAppMessageSyncConfiguration();
        boolean enabled = requested && configured;
        if (requested && !configured) {
            System.err.println("chatapp.message_sync event=skipped_not_configured stage=config");
        }
        ScheduledExecutorService executor = enabled
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chatapp-message-sync");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        return new ChatAppMessageSyncRuntime(enabled, 5, TimeUnit.SECONDS,
                synchronizer::sync, synchronizer::close, executor);
    }

    ChatAppMessageSyncRuntime(boolean enabled, long delay, TimeUnit delayUnit,
                              SyncAction syncAction, CloseAction closeAction,
                              ScheduledExecutorService executor) {
        this.enabled = enabled;
        this.delay = delay;
        this.delayUnit = Objects.requireNonNull(delayUnit);
        this.syncAction = Objects.requireNonNull(syncAction);
        this.closeAction = Objects.requireNonNull(closeAction);
        this.executor = executor;
    }

    public void start() {
        if (!enabled) {
            return;
        }
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.NEW) {
                return;
            }
            lifecycle = Lifecycle.STARTED;
        }
        try {
            executor.scheduleWithFixedDelay(this::runOnce, 0, delay, delayUnit);
        } catch (RejectedExecutionException exception) {
            synchronized (lifecycleLock) {
                if (lifecycle == Lifecycle.STARTED) {
                    lifecycle = Lifecycle.NEW;
                    throw exception;
                }
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    private void runOnce() {
        if (!tryBeginOperation()) {
            return;
        }
        long started = System.nanoTime();
        log("started", null, null, started);
        try {
            ChatAppMessageSynchronizer.Outcome outcome = syncAction.run();
            String event = outcome.status() == ChatAppMessageSynchronizer.Status.LOCK_BUSY
                    ? "lock_busy" : "succeeded";
            log(event, outcome.result(), null, started);
        } catch (Exception exception) {
            log("failed", null, exception, started);
        } finally {
            endOperation();
        }
    }

    private boolean tryBeginOperation() {
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.STARTED) {
                return false;
            }
            operationsInFlight++;
            return true;
        }
    }

    private void endOperation() {
        synchronized (lifecycleLock) {
            operationsInFlight--;
            lifecycleLock.notifyAll();
        }
    }

    private static void log(String event, SyncResult result, Exception exception, long started) {
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String stage;
        if (exception instanceof ChatAppMessageSynchronizer.SyncFailure failure) {
            stage = failure.stage();
        } else if (exception instanceof InterruptedException) {
            stage = "cancelled";
        } else {
            stage = exception == null ? "none" : "unknown";
        }
        String errorType = exception == null ? "none" : exception.getClass().getSimpleName();
        System.err.println("chatapp.message_sync event=" + event
                + " stage=" + stage + " durationMillis=" + durationMillis
                + " pages=" + value(result, value -> value.pages)
                + " fetched=" + value(result, value -> value.fetched)
                + " saved=" + value(result, value -> value.saved)
                + " updated=" + value(result, value -> value.updated)
                + " skipped=" + value(result, value -> value.skipped)
                + " mediaQueued=" + value(result, value -> value.mediaQueued)
                + " mediaFailed=" + value(result, value -> value.mediaFailed)
                + " errorType=" + errorType);
    }

    private static int value(SyncResult result, java.util.function.ToIntFunction<SyncResult> reader) {
        return result == null ? 0 : reader.applyAsInt(result);
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            while (lifecycle == Lifecycle.CLOSING) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (lifecycle == Lifecycle.CLOSED) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            lifecycle = Lifecycle.CLOSING;
        }

        Throwable closeFailure = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            closeAction.close();
        } catch (Throwable failure) {
            closeFailure = failure;
        }
        if (executor != null) {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        synchronized (lifecycleLock) {
            while (operationsInFlight > 0) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            lifecycle = Lifecycle.CLOSED;
            lifecycleLock.notifyAll();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (closeFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (closeFailure instanceof Error error) {
            throw error;
        }
    }

    private enum Lifecycle {
        NEW,
        STARTED,
        CLOSING,
        CLOSED
    }
}
