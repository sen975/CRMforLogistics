package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataSyncRuntimeTest {
    @Test
    void localDevelopmentCanOpenDisabledRuntimeWithoutProductionDependencies() {
        WeComChatDataSyncRuntime runtime = WeComChatDataSyncRuntime.open(
                new Config(Map.of("LOCAL_DEV_MODE", "true")), null, null);

        runtime.start();
        runtime.close();

        assertFalse(runtime.enabled());
    }

    @Test
    void productionWithoutChatDataConfigurationOpensDisabledRuntime() {
        WeComChatDataSyncRuntime runtime = WeComChatDataSyncRuntime.open(
                new Config(Map.of("WECOM_CHATDATA_AUTO_SYNC_ENABLED", "true")), null, null);

        runtime.start();
        runtime.close();

        assertFalse(runtime.enabled());
    }

    @Test
    void runsImmediatelyThenWaitsForTheFullDelayWithoutOverlap() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong firstEnd = new AtomicLong();
        AtomicLong secondStart = new AtomicLong();
        AtomicReference<String> actor = new AtomicReference<>();
        CountDownLatch secondCall = new CountDownLatch(1);
        WeComChatDataSyncRuntime runtime = new WeComChatDataSyncRuntime(
                true, 80, TimeUnit.MILLISECONDS,
                () -> new WeComViewerService.ViewerSyncContext("system:auto-sync", null),
                context -> {
                    actor.set(context.wecomUserId());
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        Thread.sleep(120);
                        firstEnd.set(System.nanoTime());
                    } else {
                        secondStart.set(System.nanoTime());
                        secondCall.countDown();
                    }
                    return new WeComChatDataSyncService.SyncResult(1, 0, 0);
                },
                Executors.newSingleThreadScheduledExecutor());

        try {
            runtime.start();
            assertTrue(secondCall.await(2, TimeUnit.SECONDS));
        } finally {
            runtime.close();
        }

        assertTrue(secondStart.get() - firstEnd.get() >= TimeUnit.MILLISECONDS.toNanos(80));
        assertEquals("system:auto-sync", actor.get());
    }

    @Test
    void disabledRuntimeDoesNotSchedule() {
        AtomicInteger calls = new AtomicInteger();
        WeComChatDataSyncRuntime runtime = new WeComChatDataSyncRuntime(
                false, 1, TimeUnit.MILLISECONDS,
                () -> null,
                context -> {
                    calls.incrementAndGet();
                    return new WeComChatDataSyncService.SyncResult(0, 0, 0);
                },
                null);

        runtime.start();
        runtime.close();

        assertFalse(runtime.enabled());
        assertEquals(0, calls.get());
    }
}
