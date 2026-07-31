package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ChatAppTemplateSyncRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void disabledOrUnconfiguredRuntimeDoesNotScheduleWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String stderr = captureStderr(() -> {
            Config disabledConfig = new Config(Map.of(
                    "CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "false", "CUST_SPACE_ID", "space-1",
                    "CHATAPP_TEMPLATE_FILE", tempDir.resolve("disabled.json").toString()));
            try (ChatAppTemplateSyncRuntime disabled = ChatAppTemplateSyncRuntime.open(
                    disabledConfig, synchronizerThatFailsIfCalled(disabledConfig, calls),
                    count -> fail("must not publish"))) {
                disabled.start();
                assertFalse(disabled.enabled());
                assertEquals(0, calls.get());
            }
            Config missingConfig = new Config(Map.of(
                    "CHATAPP_TEMPLATE_FILE", tempDir.resolve("missing.json").toString()));
            try (ChatAppTemplateSyncRuntime missingSpace = ChatAppTemplateSyncRuntime.open(
                    missingConfig, synchronizerThatFailsIfCalled(missingConfig, calls),
                    count -> fail("must not publish"))) {
                missingSpace.start();
                assertFalse(missingSpace.enabled());
            }
        });

        assertEquals("chatapp.template_sync event=skipped_not_configured stage=config"
                + System.lineSeparator(), stderr);
    }

    @Test
    void startsAsynchronouslyPublishesOnlyChangesAndStopsAfterClose() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
        String stderr = captureStderr(() -> {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            ChatAppTemplateSyncRuntime.SyncAction action = () -> {
                invoked.countDown();
                return new ChatAppTemplateSynchronizer.Outcome(
                        ChatAppTemplateSynchronizer.Status.CHANGED, 2, 1, 2, 1, 1);
            };
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300, action, count -> {
                        publishes.incrementAndGet();
                        published.countDown();
                    }, executor);

            try {
                runtime.start();
                assertTrue(invoked.await(2, TimeUnit.SECONDS));
                assertTrue(published.await(2, TimeUnit.SECONDS));
                runtime.close();
                runtime.start();
                assertEquals(1, publishes.get());
            } finally {
                runtime.close();
            }
        });

        assertStructuredLog(stderr, "changed", "none", 2, 1, 2, "none");
    }

    @Test
    void closeInterruptsAnInFlightSyncBeforeReturning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        String stderr = captureStderr(() -> {
            ChatAppTemplateSyncRuntime.SyncAction action = () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("sync must be interrupted");
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            };
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300, action, count -> fail("must not publish"),
                    Executors.newSingleThreadScheduledExecutor());

            try {
                runtime.start();
                assertTrue(started.await(2, TimeUnit.SECONDS));
                runtime.close();
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertStructuredLog(stderr, "failed", "unknown", 0, 0, 0, "InterruptedException");
    }

    @Test
    void unchangedSyncDoesNotPublishARefreshEvent() throws Exception {
        AtomicInteger publishes = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);
        String stderr = captureStderr(() -> {
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300,
                    () -> {
                        invoked.countDown();
                        return new ChatAppTemplateSynchronizer.Outcome(
                                ChatAppTemplateSynchronizer.Status.UNCHANGED,
                                2, 0, 2, 1, 1);
                    },
                    count -> publishes.incrementAndGet(),
                    Executors.newSingleThreadScheduledExecutor());
            try {
                runtime.start();
                assertTrue(invoked.await(2, TimeUnit.SECONDS));
                assertEquals(0, publishes.get());
            } finally {
                runtime.close();
            }
        });

        assertStructuredLog(stderr, "unchanged", "none", 2, 0, 2, "none");
    }

    @Test
    void repeatedStartSchedulesOnlyOnePeriodicTask() throws Exception {
        AtomicInteger schedules = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(
                    Runnable command, long initialDelay, long period, TimeUnit unit) {
                schedules.incrementAndGet();
                return super.scheduleAtFixedRate(command, initialDelay, period, unit);
            }
        };
        ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                true, 300,
                () -> new ChatAppTemplateSynchronizer.Outcome(
                        ChatAppTemplateSynchronizer.Status.UNCHANGED, 0, 0, 0, 0, 0),
                count -> fail("must not publish"), executor);

        captureStderr(() -> {
            try {
                runtime.start();
                runtime.start();
                assertEquals(1, schedules.get());
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void startRacingCloseDoesNotThrowOrPublish() throws Exception {
        CountDownLatch scheduling = new CountDownLatch(1);
        CountDownLatch allowScheduling = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(
                    Runnable command, long initialDelay, long period, TimeUnit unit) {
                scheduling.countDown();
                try {
                    allowScheduling.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return super.scheduleAtFixedRate(command, initialDelay, period, unit);
            }
        };
        ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                true, 300,
                () -> new ChatAppTemplateSynchronizer.Outcome(
                        ChatAppTemplateSynchronizer.Status.CHANGED, 1, 1, 1, 1, 0),
                count -> publishes.incrementAndGet(), executor);
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread startThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        });

        captureStderr(() -> {
            try {
                startThread.start();
                assertTrue(scheduling.await(2, TimeUnit.SECONDS));
                runtime.close();
                allowScheduling.countDown();
                startThread.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(startThread.isAlive());
                assertNull(startFailure.get());
                assertEquals(0, publishes.get());
            } finally {
                allowScheduling.countDown();
                runtime.close();
                startThread.join(TimeUnit.SECONDS.toMillis(2));
            }
        });
    }

    @Test
    void closeCannotTransitionRuntimeWhileChangedPublicationIsInProgress() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch shutdownNowCalled = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public List<Runnable> shutdownNow() {
                shutdownNowCalled.countDown();
                return super.shutdownNow();
            }
        };
        ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                true, 300,
                () -> new ChatAppTemplateSynchronizer.Outcome(
                        ChatAppTemplateSynchronizer.Status.CHANGED, 1, 1, 1, 1, 0),
                count -> {
                    publishing.countDown();
                    awaitIgnoringInterrupt(allowPublication);
                    publishes.incrementAndGet();
                }, executor);
        Thread closeThread = new Thread(() -> {
            closeStarted.countDown();
            runtime.close();
        });

        try {
            captureStderr(() -> {
                runtime.start();
                assertTrue(publishing.await(2, TimeUnit.SECONDS));
                closeThread.start();
                assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
                assertFalse(shutdownNowCalled.await(250, TimeUnit.MILLISECONDS),
                        "close must not transition to CLOSED during publication");
                allowPublication.countDown();
                assertTrue(shutdownNowCalled.await(2, TimeUnit.SECONDS));
                closeThread.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(closeThread.isAlive());
                assertEquals(1, publishes.get());
            });
        } finally {
            allowPublication.countDown();
            runtime.close();
            closeThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void changedActionReturningAfterShutdownNowDoesNotPublish() throws Exception {
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch allowActionReturn = new CountDownLatch(1);
        CountDownLatch shutdownNowCalled = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public List<Runnable> shutdownNow() {
                List<Runnable> pending = super.shutdownNow();
                shutdownNowCalled.countDown();
                return pending;
            }
        };
        ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                true, 300,
                () -> {
                    actionStarted.countDown();
                    while (allowActionReturn.getCount() > 0) {
                        try {
                            allowActionReturn.await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                        }
                    }
                    return new ChatAppTemplateSynchronizer.Outcome(
                            ChatAppTemplateSynchronizer.Status.CHANGED, 1, 1, 1, 1, 0);
                },
                count -> publishes.incrementAndGet(), executor);
        Thread closeThread = new Thread(runtime::close);

        try {
            String stderr = captureStderr(() -> {
                runtime.start();
                assertTrue(actionStarted.await(2, TimeUnit.SECONDS));
                closeThread.start();
                assertTrue(shutdownNowCalled.await(2, TimeUnit.SECONDS));
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
                allowActionReturn.countDown();
                closeThread.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(closeThread.isAlive());
                assertEquals(0, publishes.get());
            });
            assertStructuredLog(stderr, "changed", "none", 1, 1, 1, "none");
        } finally {
            allowActionReturn.countDown();
            runtime.close();
            closeThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void lockBusySyncDoesNotPublishAndLogsStructuredOutcome() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();

        String stderr = captureStderr(() -> {
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300,
                    () -> {
                        invoked.countDown();
                        return new ChatAppTemplateSynchronizer.Outcome(
                                ChatAppTemplateSynchronizer.Status.LOCK_BUSY,
                                0, 0, 0, 0, 0);
                    },
                    count -> publishes.incrementAndGet(),
                    Executors.newSingleThreadScheduledExecutor());
            try {
                runtime.start();
                assertTrue(invoked.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertEquals(0, publishes.get());
        assertStructuredLog(stderr, "lock_busy", "none", 0, 0, 0, "none");
    }

    @Test
    void failedSyncDoesNotPublishAndLogsStructuredFailure() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();

        String stderr = captureStderr(() -> {
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300,
                    () -> {
                        invoked.countDown();
                        throw new ChatAppTemplateSynchronizer.SyncFailure(
                                "list", "template_sync_failed",
                                new IllegalStateException("must not be logged"));
                    },
                    count -> publishes.incrementAndGet(),
                    Executors.newSingleThreadScheduledExecutor());
            try {
                runtime.start();
                assertTrue(invoked.await(2, TimeUnit.SECONDS));
            } finally {
                runtime.close();
            }
        });

        assertEquals(0, publishes.get());
        assertStructuredLog(stderr, "failed", "list", 0, 0, 0, "SyncFailure");
    }

    @Test
    void publisherFailureDoesNotPreventIdempotentClose() throws Exception {
        CountDownLatch publisherCalled = new CountDownLatch(1);

        String stderr = captureStderr(() -> {
            ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                    true, 300,
                    () -> new ChatAppTemplateSynchronizer.Outcome(
                            ChatAppTemplateSynchronizer.Status.CHANGED, 1, 1, 1, 1, 0),
                    count -> {
                        publisherCalled.countDown();
                        throw new IllegalStateException("must not be logged");
                    },
                    Executors.newSingleThreadScheduledExecutor());
            runtime.start();
            assertTrue(publisherCalled.await(2, TimeUnit.SECONDS));
            runtime.close();
            runtime.close();
        });

        assertStructuredLog(stderr, "failed", "unknown", 0, 0, 0,
                "IllegalStateException");
    }

    private ChatAppTemplateSynchronizer synchronizerThatFailsIfCalled(
            Config config, AtomicInteger calls) {
        return new ChatAppTemplateSynchronizer(config,
                new TemplateStore(config.chatappTemplateFile()),
                () -> {
                    calls.incrementAndGet();
                    throw new AssertionError("gateway must not open");
                }, Clock.systemUTC(), Duration.ofMillis(50));
    }

    private static String captureStderr(ThrowingRunnable action) throws Exception {
        PrintStream originalStderr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                action.run();
            } finally {
                System.setErr(originalStderr);
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void assertStructuredLog(String stderr, String event, String stage,
                                            int fetched, int changed, int count,
                                            String errorType) {
        assertTrue(stderr.matches("chatapp\\.template_sync event=" + event
                + " stage=" + stage + " durationMillis=\\d+ fetched=" + fetched
                + " changed=" + changed + " count=" + count
                + " errorType=" + errorType + "\\R"), stderr);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // This fixture deliberately keeps the publisher in its lifecycle boundary.
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
