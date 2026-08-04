package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAppMessageSynchronizerTest {
    private static final long TIMEOUT_MILLIS = 2_000;

    @TempDir
    Path tempDir;

    @Test
    void historyServiceDoesNotExposeUngatedFullMessageSync() {
        assertThrows(NoSuchMethodException.class,
                () -> ChatAppHistorySyncService.class.getMethod("syncMessages"));
    }

    @Test
    void concurrentTriggersCallServiceOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(gate -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return result("done");
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ChatAppMessageSynchronizer.Outcome> running = pool.submit(synchronizer::sync);
            assertTrue(entered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

            ChatAppMessageSynchronizer.Outcome busy = synchronizer.sync();

            assertEquals(ChatAppMessageSynchronizer.Status.LOCK_BUSY, busy.status());
            assertEquals("lock_busy", busy.result().message);
            assertEquals(1, calls.get());
            release.countDown();
            assertEquals(ChatAppMessageSynchronizer.Status.SUCCEEDED,
                    running.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).status());
        } finally {
            release.countDown();
            synchronizer.close();
            pool.shutdownNow();
        }
    }

    @Test
    void heldCrossProcessLockReturnsBusyWithoutCallingService() throws Exception {
        Path messageFile = tempDir.resolve("messages.jsonl");
        Path lockFile = tempDir.resolve("messages.jsonl.sync.lock");
        Files.createDirectories(lockFile.getParent());
        AtomicInteger calls = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(messageFile, gate -> {
            calls.incrementAndGet();
            return result("unexpected");
        }, () -> { });
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {

            ChatAppMessageSynchronizer.Outcome outcome = synchronizer.sync();

            assertEquals(ChatAppMessageSynchronizer.Status.LOCK_BUSY, outcome.status());
            assertEquals("lock_busy", outcome.result().message);
            assertEquals(0, calls.get());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    void failedRoundReleasesSingleFlightAndFileLock() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(gate -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("first failed");
            }
            return result("second");
        });
        try {
            assertThrows(IOException.class, synchronizer::sync);

            ChatAppMessageSynchronizer.Outcome second = synchronizer.sync();

            assertEquals(ChatAppMessageSynchronizer.Status.SUCCEEDED, second.status());
            assertEquals(2, calls.get());
            try (ChatAppMessageSyncLock.Handle ignored =
                         ChatAppMessageSyncLock.tryAcquire(tempDir.resolve("messages.jsonl")).orElseThrow()) {
                assertTrue(Files.exists(tempDir.resolve("messages.jsonl.sync.lock")));
            }
        } finally {
            synchronizer.close();
        }
    }

    @Test
    void closeInterruptsActiveRoundAndRejectsLateCommit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(
                tempDir.resolve("messages.jsonl"), gate -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    gate.commit(writes::incrementAndGet);
                    return result("late");
                }, closes::incrementAndGet);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ChatAppMessageSynchronizer.Outcome> running = pool.submit(synchronizer::sync);
            assertTrue(entered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

            Future<?> closing = pool.submit(synchronizer::close);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> running.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            closing.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

            ChatAppMessageSynchronizer.SyncFailure cancelled = assertInstanceOf(
                    ChatAppMessageSynchronizer.SyncFailure.class, failure.getCause());
            assertEquals("cancelled", cancelled.stage());
            assertTrue(interrupted.get());
            assertEquals(0, writes.get());
            assertEquals(1, closes.get());
        } finally {
            synchronizer.close();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCloseCallsWaitForTheSameRoundAndCloseOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(
                tempDir.resolve("messages.jsonl"), gate -> {
                    entered.countDown();
                    awaitAfterInterrupt(interrupted, allowExit);
                    return result("done");
                }, closes::incrementAndGet);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<ChatAppMessageSynchronizer.Outcome> running = pool.submit(synchronizer::sync);
            assertTrue(entered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            Future<?> firstClose = pool.submit(synchronizer::close);
            assertTrue(interrupted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            Future<?> secondClose = pool.submit(synchronizer::close);

            assertThrows(TimeoutException.class,
                    () -> firstClose.get(200, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,
                    () -> secondClose.get(200, TimeUnit.MILLISECONDS));
            allowExit.countDown();
            running.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            firstClose.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            secondClose.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            assertEquals(1, closes.get());
        } finally {
            allowExit.countDown();
            synchronizer.close();
            pool.shutdownNow();
        }
    }

    @Test
    void interruptedCloseStillCompletesAndRestoresInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch activeInterrupted = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(
                tempDir.resolve("messages.jsonl"), gate -> {
                    entered.countDown();
                    awaitAfterInterrupt(activeInterrupted, allowExit);
                    return result("done");
                }, closes::incrementAndGet);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ChatAppMessageSynchronizer.Outcome> running = pool.submit(synchronizer::sync);
            assertTrue(entered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            Future<?> release = pool.submit(() -> {
                try {
                    if (!activeInterrupted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("active sync was not interrupted");
                    }
                    allowExit.countDown();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("release helper interrupted", exception);
                }
            });

            Thread.currentThread().interrupt();
            try {
                synchronizer.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }

            running.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            release.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            assertEquals(1, closes.get());
        } finally {
            allowExit.countDown();
            synchronizer.close();
            pool.shutdownNow();
        }
    }

    @Test
    void syncAfterCloseReturnsStructuredCancellationWithoutCallingService() {
        AtomicInteger calls = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = synchronizer(gate -> {
            calls.incrementAndGet();
            return result("unexpected");
        });
        synchronizer.close();

        ChatAppMessageSynchronizer.SyncFailure failure = assertThrows(
                ChatAppMessageSynchronizer.SyncFailure.class, synchronizer::sync);

        assertEquals("cancelled", failure.stage());
        assertEquals(0, calls.get());
    }

    @Test
    void lockHandleCloseIsIdempotentAndReleasesLock() throws Exception {
        Path messages = tempDir.resolve("messages.jsonl");
        ChatAppMessageSyncLock.Handle handle =
                ChatAppMessageSyncLock.tryAcquire(messages).orElseThrow();

        handle.close();
        handle.close();

        try (ChatAppMessageSyncLock.Handle ignored =
                     ChatAppMessageSyncLock.tryAcquire(messages).orElseThrow()) {
            assertTrue(Files.exists(tempDir.resolve("messages.jsonl.sync.lock")));
        }
    }

    private ChatAppMessageSynchronizer synchronizer(ChatAppMessageSynchronizer.SyncAction action) {
        return synchronizer(tempDir.resolve("messages.jsonl"), action, () -> { });
    }

    private static ChatAppMessageSynchronizer synchronizer(
            Path messageFile, ChatAppMessageSynchronizer.SyncAction action,
            ChatAppMessageSynchronizer.CloseAction closeAction) {
        return new ChatAppMessageSynchronizer(messageFile, action, closeAction);
    }

    private static SyncResult result(String message) {
        SyncResult result = new SyncResult("chatapp");
        result.message = message;
        return result;
    }

    private static void awaitAfterInterrupt(CountDownLatch interrupted, CountDownLatch allowExit) {
        boolean observed = false;
        while (true) {
            try {
                allowExit.await();
                return;
            } catch (InterruptedException exception) {
                if (!observed) {
                    observed = true;
                    interrupted.countDown();
                }
            }
        }
    }
}
