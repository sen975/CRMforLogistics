package com.crmforlogistics.messagecenter;

import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs bounded WeCom chat-data sync independently from browser actions. */
public final class WeComChatDataSyncRuntime implements AutoCloseable {
    @FunctionalInterface
    interface ContextProvider {
        WeComViewerService.ViewerSyncContext resolve() throws Exception;
    }

    @FunctionalInterface
    interface SyncAction {
        WeComChatDataSyncService.SyncResult run(WeComViewerService.ViewerSyncContext context)
                throws Exception;
    }

    private final boolean enabled;
    private final long delay;
    private final TimeUnit delayUnit;
    private final ContextProvider contextProvider;
    private final SyncAction syncAction;
    private final ScheduledExecutorService executor;
    private final Object lifecycleLock = new Object();
    private Lifecycle lifecycle = Lifecycle.NEW;
    private int operationsInFlight;
    private boolean notConfiguredReported;

    public static WeComChatDataSyncRuntime open(Config config,
                                                 WeComAuthorizationStore authorizationStore,
                                                 WeComChatDataSyncService syncService) {
        boolean requested = config.wecomChatDataAutoSyncEnabled();
        boolean configured = !config.localDevMode() && authorizationStore != null && syncService != null
                && !config.wecomSuiteId().isBlank() && !config.wecomLoginAuthCorpId().isBlank()
                && !config.wecomChatDataProgramId().isBlank()
                && !config.wecomChatDataAbilityId().isBlank()
                && Files.isRegularFile(config.wecomChatDataPrivateKeyFile());
        boolean enabled = requested && configured;
        if (requested && !configured && !config.localDevMode()) {
            System.err.println("wecom.chatdata_auto_sync event=skipped_not_configured stage=config");
        }
        ScheduledExecutorService executor = enabled
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wecom-chatdata-auto-sync");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        ContextProvider provider = () -> new WeComViewerService.ViewerSyncContext(
                "system:auto-sync",
                authorizationStore.resolveActive(config.wecomSuiteId(), config.wecomLoginAuthCorpId()));
        return new WeComChatDataSyncRuntime(enabled,
                config.wecomChatDataAutoSyncIntervalSeconds(), TimeUnit.SECONDS,
                provider, context -> syncService.sync(context), executor);
    }

    WeComChatDataSyncRuntime(boolean enabled, long delay, TimeUnit delayUnit,
                             ContextProvider contextProvider, SyncAction syncAction,
                             ScheduledExecutorService executor) {
        this.enabled = enabled;
        this.delay = delay;
        this.delayUnit = Objects.requireNonNull(delayUnit);
        this.contextProvider = Objects.requireNonNull(contextProvider);
        this.syncAction = Objects.requireNonNull(syncAction);
        this.executor = executor;
    }

    public boolean enabled() {
        return enabled;
    }

    public void start() {
        if (!enabled) return;
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.NEW) return;
            lifecycle = Lifecycle.STARTED;
        }
        try {
            executor.scheduleWithFixedDelay(this::runOnce, 0, delay, delayUnit);
        } catch (RejectedExecutionException exception) {
            synchronized (lifecycleLock) {
                if (lifecycle == Lifecycle.STARTED) lifecycle = Lifecycle.NEW;
            }
            throw exception;
        }
    }

    private void runOnce() {
        if (!tryBeginOperation()) return;
        long started = System.nanoTime();
        log("started", null, null, started);
        try {
            WeComChatDataSyncService.SyncResult result = syncAction.run(contextProvider.resolve());
            notConfiguredReported = false;
            log("succeeded", result, null, started);
        } catch (WeComAuthorizationException exception) {
            if (!notConfiguredReported) {
                notConfiguredReported = true;
                log("skipped_not_configured", null, exception, started);
            }
        } catch (Exception exception) {
            log("failed", null, exception, started);
        } finally {
            endOperation();
        }
    }

    private boolean tryBeginOperation() {
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.STARTED) return false;
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

    private static void log(String event, WeComChatDataSyncService.SyncResult result,
                            Exception exception, long started) {
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.err.println("wecom.chatdata_auto_sync event=" + event
                + " durationMillis=" + durationMillis
                + " pages=" + (result == null ? 0 : result.pages())
                + " stored=" + (result == null ? 0 : result.stored())
                + " skipped=" + (result == null ? 0 : result.skipped())
                + " errorType=" + (exception == null ? "none" : exception.getClass().getSimpleName()));
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) return;
            if (lifecycle == Lifecycle.NEW) {
                lifecycle = Lifecycle.CLOSED;
                return;
            }
            lifecycle = Lifecycle.CLOSING;
        }
        if (executor != null) executor.shutdownNow();
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
        if (interrupted) Thread.currentThread().interrupt();
    }

    private enum Lifecycle { NEW, STARTED, CLOSING, CLOSED }
}
