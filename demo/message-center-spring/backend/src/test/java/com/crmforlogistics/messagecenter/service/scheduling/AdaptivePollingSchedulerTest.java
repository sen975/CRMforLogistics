package com.crmforlogistics.messagecenter.service.scheduling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePollingSchedulerTest {

    private AdaptivePollingScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.shutdown();
    }

    @Test
    void backoffDoublesUntilCeilingAndResetsOnWork() {
        AdaptivePollingScheduler.Backoff backoff = new AdaptivePollingScheduler.Backoff(1000, 5000);

        assertEquals(1000, backoff.currentMs());
        assertEquals(2000, backoff.onIdle());
        assertEquals(4000, backoff.onIdle());
        assertEquals(5000, backoff.onIdle());
        assertEquals(5000, backoff.onIdle(), "到上限后不再增长");
        assertEquals(1000, backoff.onWork(), "有活立即回基线");
        assertEquals(1000, backoff.reset());
    }

    @Test
    void ceilingNeverFallsBelowBaseline() {
        AdaptivePollingScheduler.Backoff backoff = new AdaptivePollingScheduler.Backoff(1000, 200);

        assertEquals(1000, backoff.onIdle());
        assertEquals(1000, backoff.onIdle());
    }

    @Test
    void taskFailureDoesNotStopTheScheduleChain() throws Exception {
        scheduler = new AdaptivePollingScheduler(1, 50);
        CountDownLatch twoCalls = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        scheduler.register(task("failing", () -> {
            calls.incrementAndGet();
            twoCalls.countDown();
            throw new IllegalStateException("boom");
        }), Duration.ofMillis(20));

        assertTrue(twoCalls.await(2, TimeUnit.SECONDS),
                "任务抛异常后调度链不能断，实际只跑了 " + calls.get() + " 轮");
    }

    @Test
    void wakeTriggersNextRoundWithoutWaitingForTheStretchedInterval() throws Exception {
        scheduler = new AdaptivePollingScheduler(1, 5000);
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch secondCall = new CountDownLatch(2);
        scheduler.register(task("wakeable", () -> {
            firstCall.countDown();
            secondCall.countDown();
            return 0;
        }), Duration.ofMillis(1000));

        assertTrue(firstCall.await(2, TimeUnit.SECONDS), "首轮应在基线后执行");
        scheduler.wake("wakeable");

        assertTrue(secondCall.await(1500, TimeUnit.MILLISECONDS),
                "wake 后应立即触发，而不是等满空转拉长出来的 2s 间隔");
    }

    @Test
    void busyTaskKeepsTheBaselineInterval() throws Exception {
        scheduler = new AdaptivePollingScheduler(1, 5000);
        CountDownLatch threeCalls = new CountDownLatch(3);
        scheduler.register(task("busy", () -> {
            threeCalls.countDown();
            return 1;
        }), Duration.ofMillis(10));

        assertTrue(threeCalls.await(1, TimeUnit.SECONDS), "有活时不应退避");
        assertEquals(Duration.ofMillis(10), scheduler.currentInterval("busy"));
    }

    private static PollingTask task(String name, IntSupplier body) {
        return new PollingTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int pollOnce() {
                return body.getAsInt();
            }
        };
    }
}
