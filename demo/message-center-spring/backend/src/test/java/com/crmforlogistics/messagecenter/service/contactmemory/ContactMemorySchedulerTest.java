package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ContactMemorySchedulerTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void doesNotRunBeforeConfiguredMidnightWindow() {
        ContactMemoryWorker worker = mock(ContactMemoryWorker.class);
        ContactMemoryScheduler scheduler = scheduler(worker, "2026-09-11T15:59:59Z");

        scheduler.run();

        verifyNoInteractions(worker);
    }

    @Test
    void runsAfterConfiguredMidnightWindow() {
        ContactMemoryWorker worker = mock(ContactMemoryWorker.class);
        ContactMemoryScheduler scheduler = scheduler(worker, "2026-09-11T16:00:00Z");

        scheduler.run();

        verify(worker).runOnce(Instant.parse("2026-09-11T16:00:00Z"));
    }

    private static ContactMemoryScheduler scheduler(ContactMemoryWorker worker, String now) {
        ContactMemoryConfig config = new ContactMemoryConfig(
                50, 4000, 50000, 20, 1000, 10, 4000,
                100, 100, 100, 500, 30, 7, 300, 3, 60, 900,
                0, 0, ZONE.getId());
        return new ContactMemoryScheduler(
                worker,
                config,
                Clock.fixed(Instant.parse(now), ZONE));
    }
}
