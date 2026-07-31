package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
    }

    @Test
    void startsAsynchronouslyPublishesOnlyChangesAndStopsAfterClose() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
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
    }

    @Test
    void closeInterruptsAnInFlightSyncBeforeReturning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
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
    }

    @Test
    void unchangedSyncDoesNotPublishARefreshEvent() throws Exception {
        AtomicInteger publishes = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);
        ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
                true, 300,
                () -> {
                    invoked.countDown();
                    return new ChatAppTemplateSynchronizer.Outcome(
                            ChatAppTemplateSynchronizer.Status.UNCHANGED, 2, 0, 2, 1, 1);
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
    }

    @Test
    void repeatedStartSchedulesOnlyOnePeriodicTask() {
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

        try {
            runtime.start();
            runtime.start();
            assertEquals(1, schedules.get());
        } finally {
            runtime.close();
        }
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
}
