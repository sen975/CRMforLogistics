package com.crmforlogistics.messagecenter;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class ChatAppMessageSynchronizer implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final Path messageFile;
    private final SyncAction syncAction;
    private final CloseAction closeAction;

    private Lifecycle lifecycle = Lifecycle.OPEN;
    private Thread activeThread;
    private boolean running;

    public record Outcome(Status status, SyncResult result) {
    }

    public enum Status {
        SUCCEEDED,
        LOCK_BUSY
    }

    @FunctionalInterface
    interface SyncAction {
        SyncResult run(ChatAppHistorySyncService.CommitGate commitGate) throws Exception;
    }

    @FunctionalInterface
    interface CloseAction {
        void close();
    }

    enum Lifecycle {
        OPEN,
        CLOSING,
        CLOSED
    }

    static final class SyncFailure extends IllegalStateException {
        private final String stage;

        SyncFailure(String stage, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }

    ChatAppMessageSynchronizer(Path messageFile, SyncAction syncAction, CloseAction closeAction) {
        this.messageFile = Objects.requireNonNull(messageFile).toAbsolutePath().normalize();
        this.syncAction = Objects.requireNonNull(syncAction);
        this.closeAction = Objects.requireNonNull(closeAction);
    }

    public static ChatAppMessageSynchronizer create(
            Config config, ChatAppHistorySyncService syncService) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(syncService);
        return new ChatAppMessageSynchronizer(config.chatappDataFile(),
                syncService::syncMessages, syncService::close);
    }

    public Outcome sync() throws Exception {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running) {
                return lockBusy();
            }
            running = true;
            activeThread = Thread.currentThread();
        }

        try {
            Optional<ChatAppMessageSyncLock.Handle> acquired =
                    ChatAppMessageSyncLock.tryAcquire(messageFile);
            if (acquired.isEmpty()) {
                return lockBusy();
            }
            try (ChatAppMessageSyncLock.Handle ignored = acquired.orElseThrow()) {
                SyncResult result = syncAction.run(this::commitIfOpen);
                return new Outcome(Status.SUCCEEDED, result);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cancelled(exception);
        } finally {
            synchronized (lifecycleLock) {
                running = false;
                activeThread = null;
                lifecycleLock.notifyAll();
            }
        }
    }

    private void commitIfOpen(ChatAppHistorySyncService.CommitAction action) throws Exception {
        Objects.requireNonNull(action);
        synchronized (lifecycleLock) {
            ensureOpen();
            action.run();
        }
    }

    private void ensureOpen() {
        if (lifecycle != Lifecycle.OPEN) {
            throw cancelled(null);
        }
    }

    private static Outcome lockBusy() {
        SyncResult result = new SyncResult("chatapp");
        result.message = "lock_busy";
        return new Outcome(Status.LOCK_BUSY, result);
    }

    private static SyncFailure cancelled(Throwable cause) {
        return new SyncFailure("cancelled", "chatapp_message_sync_cancelled", cause);
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            if (lifecycle == Lifecycle.CLOSING) {
                while (lifecycle != Lifecycle.CLOSED) {
                    try {
                        lifecycleLock.wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            lifecycle = Lifecycle.CLOSING;
            if (activeThread != null) {
                activeThread.interrupt();
            }
            while (running) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        try {
            closeAction.close();
        } finally {
            synchronized (lifecycleLock) {
                lifecycle = Lifecycle.CLOSED;
                lifecycleLock.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
