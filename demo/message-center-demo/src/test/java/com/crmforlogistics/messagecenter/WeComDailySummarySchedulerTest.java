package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeComDailySummarySchedulerTest {
    @Test
    void triggersDailyCreationOnceAfterBeijingScheduleAndScansWorkEveryTick() {
        AtomicInteger dailyRuns = new AtomicInteger();
        AtomicInteger workerRuns = new AtomicInteger();
        Config config = new Config(Map.of(
                "WECOM_DAILY_SUMMARY_HOUR", "0",
                "WECOM_DAILY_SUMMARY_MINUTE", "5"));
        WeComDailySummaryScheduler scheduler = WeComDailySummaryScheduler.forTests(
                config, ignored -> dailyRuns.incrementAndGet(), ignored -> workerRuns.incrementAndGet());

        scheduler.tick(Instant.parse("2026-07-29T16:04:59Z"));
        scheduler.tick(Instant.parse("2026-07-29T16:05:00Z"));
        scheduler.tick(Instant.parse("2026-07-29T16:06:00Z"));
        scheduler.tick(Instant.parse("2026-07-30T16:05:00Z"));
        scheduler.close();

        assertEquals(2, dailyRuns.get());
        assertEquals(4, workerRuns.get());
    }
}
