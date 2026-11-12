package com.crmforlogistics.messagecenter;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public final class ChatAppTemplateSyncRuntime implements AutoCloseable {
    @FunctionalInterface
    interface SyncAction {
        ChatAppTemplateSynchronizer.Outcome run() throws Exception;
    }

    @FunctionalInterface
    interface GatedSyncAction {
        ChatAppTemplateSynchronizer.Outcome run(
                ChatAppTemplateSynchronizer.CommitGate commitGate) throws Exception;
    }

    private final GatedSyncAction syncAction;
    private final IntConsumer changedPublisher;
    private final ScheduledExecutorService executor;
    private final int intervalSeconds;
    private final boolean enabled;
    private final Object lifecycleLock = new Object();
    private Lifecycle lifecycle = Lifecycle.NEW;
    private int operationsInFlight;

    public static ChatAppTemplateSyncRuntime open(Config config,
                                                   ChatAppTemplateSynchronizer synchronizer,
                                                   IntConsumer changedPublisher) {
        boolean requested = config.chatappTemplateAutoSyncEnabled();
        boolean configured = config.hasChatAppTemplateSyncConfiguration();
        boolean enabled = requested && configured;
        if (requested && !configured) {
            System.err.println("chatapp.template_sync event=skipped_not_configured stage=config");
        }
        return new ChatAppTemplateSyncRuntime(enabled,
                config.chatappTemplateSyncIntervalSeconds(),
                commitGate -> synchronizer.syncWithCommitGate(commitGate), changedPublisher,
                enabled ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chatapp-template-sync");
                    thread.setDaemon(true);
                    return thread;
                }) : null);
    }

    ChatAppTemplateSyncRuntime(boolean enabled, int intervalSeconds,
                               SyncAction syncAction, IntConsumer changedPublisher,
                               ScheduledExecutorService executor) {
        this(enabled, intervalSeconds, commitGate -> syncAction.run(), changedPublisher, executor);
    }

    ChatAppTemplateSyncRuntime(boolean enabled, int intervalSeconds,
                               GatedSyncAction syncAction, IntConsumer changedPublisher,
                               ScheduledExecutorService executor) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.syncAction = Objects.requireNonNull(syncAction);
        this.changedPublisher = Objects.requireNonNull(changedPublisher);
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
            executor.scheduleAtFixedRate(this::runOnce, 0, intervalSeconds, TimeUnit.SECONDS);
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
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.STARTED) {
                return;
            }
        }
        long started = System.nanoTime();
        log("started", null, null, started);
        try {
            ChatAppTemplateSynchronizer.Outcome outcome = syncAction.run(this::commitIfOpen);
            if (outcome.status() == ChatAppTemplateSynchronizer.Status.CHANGED) {
                publishIfOpen(outcome.count());
            }
            String event = outcome.status() == ChatAppTemplateSynchronizer.Status.CHANGED
                    ? "succeeded" : outcome.status().name().toLowerCase(Locale.ROOT);
            log(event, outcome, null, started);
        } catch (Exception exception) {
            log("failed", null, exception, started);
        }
    }

    private TemplateStore.ReplaceResult commitIfOpen(
            ChatAppTemplateSynchronizer.CommitAction action) throws Exception {
        if (!tryBeginOperation()) {
            throw new ChatAppTemplateSynchronizer.SyncFailure(
                    "cancelled", "template_sync_cancelled", null);
        }
        try {
            return action.run();
        } finally {
            endOperation();
        }
    }

    private void publishIfOpen(int count) {
        if (!tryBeginOperation()) {
            return;
        }
        try {
            changedPublisher.accept(count);
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

    private static void log(String event, ChatAppTemplateSynchronizer.Outcome outcome,
                            Exception exception, long started) {
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String stage = exception instanceof ChatAppTemplateSynchronizer.SyncFailure failure
                ? failure.stage() : exception == null ? "none" : "unknown";
        String errorType = exception == null ? "none" : exception.getClass().getSimpleName();
        int fetched = outcome == null ? 0 : outcome.fetched();
        int changed = outcome == null ? 0 : outcome.changed();
        int count = outcome == null ? 0 : outcome.count();
        System.err.println("chatapp.template_sync event=" + event
                + " stage=" + stage + " durationMillis=" + durationMillis
                + " fetched=" + fetched + " changed=" + changed + " count=" + count
                + " errorType=" + errorType);
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
        boolean timedOut = false;
        if (executor != null) {
            executor.shutdownNow();
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!executor.isTerminated()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    timedOut = true;
                    break;
                }
                try {
                    if (!executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                        timedOut = true;
                        break;
                    }
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
        if (timedOut) {
            System.err.println("chatapp.template_sync event=shutdown_timeout");
        }
    }

    private enum Lifecycle {
        NEW,
        STARTED,
        CLOSING,
        CLOSED
    }
}
