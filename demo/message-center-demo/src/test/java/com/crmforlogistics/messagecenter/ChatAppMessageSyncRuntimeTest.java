package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAppMessageSyncRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void openDisablesSchedulingWhenRequestedOffOrNotConfigured() throws Exception {
        AtomicInteger syncCalls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        String stderr = captureStderr(() -> {
            Config disabledConfig = config(Map.of(
                    "CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", "false",
                    "CUST_SPACE_ID", "space-1"));
            ChatAppMessageSynchronizer disabledSynchronizer = synchronizer(
                    disabledConfig, syncCalls, closes);
            try (ChatAppMessageSyncRuntime runtime = ChatAppMessageSyncRuntime.open(
                    disabledConfig, disabledSynchronizer)) {
                runtime.start();
                assertFalse(runtime.enabled());
            }

            Config missingConfig = config(Map.of());
            ChatAppMessageSynchronizer missingSynchronizer = synchronizer(
                    missingConfig, syncCalls, closes);
            try (ChatAppMessageSyncRuntime runtime = ChatAppMessageSyncRuntime.open(
                    missingConfig, missingSynchronizer)) {
                runtime.start();
                assertFalse(runtime.enabled());
            }
        });

        assertEquals(0, syncCalls.get());
        assertEquals(2, closes.get());
        assertEquals("chatapp.message_sync event=skipped_not_configured stage=config"
                + System.lineSeparator(), stderr);
    }

    @Test
    void productionSchedulingUsesExactFixedDelayAndRepeatedStartIsIdempotent() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS,
                () -> {
                    calls.incrementAndGet();
                    return outcome("done");
                }, closes::incrementAndGet, executor);
        try {
            runtime.start();
            runtime.start();

            assertEquals(1, executor.fixedDelayCalls.get());
            assertEquals(0L, executor.initialDelay);
            assertEquals(5L, executor.delay);
            assertEquals(TimeUnit.SECONDS, executor.unit);
            assertEquals(0, executor.fixedRateCalls.get());
        } finally {
            runtime.close();
        }

        executor.command.run();
        assertEquals(0, calls.get());
        assertEquals(1, closes.get());
    }

    @Test
    void closeBeforeStartShutsDownAllocatedExecutorAndPreventsLaterStart() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS,
                () -> {
                    calls.incrementAndGet();
                    return outcome("unexpected");
                }, closes::incrementAndGet, executor);

        runtime.close();
        runtime.start();

        assertEquals(0, calls.get());
        assertEquals(1, closes.get());
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.fixedDelayCalls.get());
        assertNull(executor.command);
    }

    @Test
    void firstRoundRunsAsynchronouslyOnExecutorThread() throws Exception {
        Thread startThread = Thread.currentThread();
        AtomicReference<Thread> actionThread = new AtomicReference<>();
        CountDownLatch actionCalled = new CountDownLatch(1);
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS, () -> {
                    actionThread.set(Thread.currentThread());
                    actionCalled.countDown();
                    return outcome("done");
                }, () -> { }, Executors.newSingleThreadScheduledExecutor());

        captureStderr(() -> {
            try {
                runtime.start();
                assertTrue(actionCalled.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertNotSame(startThread, actionThread.get());
    }

    @Test
    void nextRoundStartsOnlyAfterPriorRoundEndsAndFullDelayPasses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong firstEnd = new AtomicLong();
        AtomicLong secondStart = new AtomicLong();
        CountDownLatch secondCalled = new CountDownLatch(1);
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 80, TimeUnit.MILLISECONDS, () -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        Thread.sleep(150);
                        firstEnd.set(System.nanoTime());
                    } else if (call == 2) {
                        secondStart.set(System.nanoTime());
                        secondCalled.countDown();
                    }
                    return outcome("done");
                }, () -> { }, Executors.newSingleThreadScheduledExecutor());

        captureStderr(() -> {
            try {
                runtime.start();
                assertTrue(secondCalled.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertTrue(firstEnd.get() > 0);
        assertTrue(secondStart.get() - firstEnd.get() >= TimeUnit.MILLISECONDS.toNanos(80));
    }

    @Test
    void failedRoundAlsoWaitsTheFullDelayBeforeRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong firstEnd = new AtomicLong();
        AtomicLong secondStart = new AtomicLong();
        CountDownLatch secondCalled = new CountDownLatch(1);
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 80, TimeUnit.MILLISECONDS, () -> {
                    if (calls.incrementAndGet() == 1) {
                        firstEnd.set(System.nanoTime());
                        throw new IOException("first failed");
                    }
                    secondStart.set(System.nanoTime());
                    secondCalled.countDown();
                    return outcome("done");
                }, () -> { }, Executors.newSingleThreadScheduledExecutor());

        String stderr = captureStderr(() -> {
            try {
                runtime.start();
                assertTrue(secondCalled.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertTrue(secondStart.get() - firstEnd.get() >= TimeUnit.MILLISECONDS.toNanos(80));
        assertTrue(stderr.contains("chatapp.message_sync event=failed"), stderr);
        assertTrue(stderr.contains("errorType=IOException"), stderr);
    }

    @Test
    void closeInterruptsInFlightRoundAndPreventsRestart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS, () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                    return outcome("unexpected");
                }, closes::incrementAndGet, Executors.newSingleThreadScheduledExecutor());

        captureStderr(() -> {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.close();
            runtime.start();
        });

        assertTrue(interrupted.get());
        assertEquals(1, calls.get());
        assertEquals(1, closes.get());
    }

    @Test
    void closeWaitsUntilActiveRoundHasCompleted() throws Exception {
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch allowActionToFinish = new CountDownLatch(1);
        CountDownLatch actionCompleted = new CountDownLatch(1);
        CountDownLatch closeActionCalled = new CountDownLatch(1);
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS, () -> {
                    actionEntered.countDown();
                    awaitIgnoringInterrupt(allowActionToFinish);
                    actionCompleted.countDown();
                    return outcome("done");
                }, closeActionCalled::countDown,
                Executors.newSingleThreadScheduledExecutor());
        Thread closingThread = new Thread(runtime::close);

        try {
            captureStderr(() -> {
                runtime.start();
                assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
                closingThread.start();
                assertTrue(closeActionCalled.await(2, TimeUnit.SECONDS));

                closingThread.join(200);
                assertTrue(closingThread.isAlive());
                assertEquals(1L, actionCompleted.getCount());

                allowActionToFinish.countDown();
                closingThread.join(2_000);
                assertFalse(closingThread.isAlive());
                assertEquals(0L, actionCompleted.getCount());
            });
        } finally {
            allowActionToFinish.countDown();
            runtime.close();
            closingThread.join(2_000);
        }
    }

    @Test
    void startRacingCloseDoesNotReviveTheRuntimeOrThrow() throws Exception {
        BlockingScheduleExecutor executor = new BlockingScheduleExecutor();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS,
                () -> {
                    calls.incrementAndGet();
                    return outcome("unexpected");
                }, closes::incrementAndGet, executor);
        Thread startThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        });
        try {
            startThread.start();
            assertTrue(executor.scheduling.await(2, TimeUnit.SECONDS));
            runtime.close();
            executor.allowScheduling.countDown();
            startThread.join(2_000);

            assertFalse(startThread.isAlive());
            assertNull(startFailure.get());
            assertEquals(0, calls.get());
            assertEquals(1, closes.get());
        } finally {
            executor.allowScheduling.countDown();
            runtime.close();
            startThread.join(2_000);
        }
    }

    @Test
    void concurrentCloseCallsWaitForOneCloseActionAndRestoreInterruption() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean secondInterrupted = new AtomicBoolean();
        ChatAppMessageSyncRuntime runtime = new ChatAppMessageSyncRuntime(
                true, 5, TimeUnit.SECONDS, () -> outcome("unused"), () -> {
                    closeEntered.countDown();
                    awaitIgnoringInterrupt(allowClose);
                    closes.incrementAndGet();
                }, Executors.newSingleThreadScheduledExecutor());
        Thread first = new Thread(runtime::close);
        Thread second = new Thread(() -> {
            runtime.close();
            secondInterrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            first.start();
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            second.start();
            second.interrupt();
            first.join(200);
            second.join(200);
            assertTrue(first.isAlive());
            assertTrue(second.isAlive());

            allowClose.countDown();
            first.join(2_000);
            second.join(2_000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(secondInterrupted.get());
            assertEquals(1, closes.get());
        } finally {
            allowClose.countDown();
            runtime.close();
            first.join(2_000);
            second.join(2_000);
        }
    }

    private Config config(Map<String, String> extra) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>(extra);
        values.put("CHATAPP_DATA_FILE", tempDir.resolve("messages.jsonl").toString());
        return new Config(values);
    }

    private static ChatAppMessageSynchronizer synchronizer(
            Config config, AtomicInteger calls, AtomicInteger closes) {
        return new ChatAppMessageSynchronizer(config.chatappDataFile(), gate -> {
            calls.incrementAndGet();
            return result("unexpected");
        }, closes::incrementAndGet);
    }

    private static ChatAppMessageSynchronizer.Outcome outcome(String message) {
        return new ChatAppMessageSynchronizer.Outcome(
                ChatAppMessageSynchronizer.Status.SUCCEEDED, result(message));
    }

    private static SyncResult result(String message) {
        SyncResult result = new SyncResult("chatapp");
        result.message = message;
        return result;
    }

    private static String captureStderr(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                action.run();
            } finally {
                System.setErr(original);
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // This fixture keeps the close action inside its lifecycle boundary.
            }
        }
    }

    private static class RecordingExecutor extends ScheduledThreadPoolExecutor {
        final AtomicInteger fixedDelayCalls = new AtomicInteger();
        final AtomicInteger fixedRateCalls = new AtomicInteger();
        Runnable command;
        long initialDelay;
        long delay;
        TimeUnit unit;

        RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            fixedDelayCalls.incrementAndGet();
            this.command = command;
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            return super.scheduleWithFixedDelay(() -> { }, 1, 1, TimeUnit.DAYS);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            fixedRateCalls.incrementAndGet();
            return super.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
    }

    private static final class BlockingScheduleExecutor extends RecordingExecutor {
        final CountDownLatch scheduling = new CountDownLatch(1);
        final CountDownLatch allowScheduling = new CountDownLatch(1);

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            scheduling.countDown();
            try {
                allowScheduling.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
