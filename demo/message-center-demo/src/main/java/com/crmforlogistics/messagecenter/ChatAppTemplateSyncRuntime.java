package com.crmforlogistics.messagecenter;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

public final class ChatAppTemplateSyncRuntime implements AutoCloseable {
    @FunctionalInterface
    interface SyncAction {
        ChatAppTemplateSynchronizer.Outcome run() throws Exception;
    }

    private final SyncAction syncAction;
    private final IntConsumer changedPublisher;
    private final ScheduledExecutorService executor;
    private final int intervalSeconds;
    private final boolean enabled;
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.NEW);

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
                config.chatappTemplateSyncIntervalSeconds(), synchronizer::sync, changedPublisher,
                enabled ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chatapp-template-sync");
                    thread.setDaemon(true);
                    return thread;
                }) : null);
    }

    ChatAppTemplateSyncRuntime(boolean enabled, int intervalSeconds,
                               SyncAction syncAction, IntConsumer changedPublisher,
                               ScheduledExecutorService executor) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.syncAction = Objects.requireNonNull(syncAction);
        this.changedPublisher = Objects.requireNonNull(changedPublisher);
        this.executor = executor;
    }

    public void start() {
        if (!enabled || !lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STARTED)) {
            return;
        }
        try {
            executor.scheduleAtFixedRate(this::runOnce, 0, intervalSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            if (lifecycle.get() != Lifecycle.CLOSED) {
                lifecycle.compareAndSet(Lifecycle.STARTED, Lifecycle.NEW);
                throw exception;
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    private void runOnce() {
        if (lifecycle.get() == Lifecycle.CLOSED) {
            return;
        }
        long started = System.nanoTime();
        try {
            ChatAppTemplateSynchronizer.Outcome outcome = syncAction.run();
            if (lifecycle.get() != Lifecycle.CLOSED
                    && outcome.status() == ChatAppTemplateSynchronizer.Status.CHANGED) {
                changedPublisher.accept(outcome.count());
            }
            log(outcome.status().name().toLowerCase(Locale.ROOT), outcome, null, started);
        } catch (Exception exception) {
            log("failed", null, exception, started);
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
        if (lifecycle.getAndSet(Lifecycle.CLOSED) == Lifecycle.CLOSED || executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                System.err.println("chatapp.template_sync event=shutdown_timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private enum Lifecycle {
        NEW,
        STARTED,
        CLOSED
    }
}
