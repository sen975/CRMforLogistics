package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

@Component
@EnableScheduling
public class ContactMemoryScheduler {
    private final ContactMemoryWorker worker;
    private final ContactMemoryTriggerService triggers;
    private final ContactMemoryConfig config;
    private final Clock clock;

    public ContactMemoryScheduler(ContactMemoryWorker worker,
                                  ContactMemoryTriggerService triggers,
                                  ContactMemoryConfig config) {
        this(worker, triggers, config, Clock.systemUTC());
    }

    ContactMemoryScheduler(ContactMemoryWorker worker,
                           ContactMemoryTriggerService triggers,
                           ContactMemoryConfig config,
                           Clock clock) {
        this.worker = worker;
        this.triggers = triggers;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${contact-memory.poll-interval-seconds:600}000")
    public void run() {
        Instant now = clock.instant();
        ZoneId zone = ZoneId.of(config.timeZone());
        LocalTime start = LocalTime.of(config.startHour(), config.startMinute());
        LocalTime localTime = now.atZone(zone).toLocalTime();
        LocalTime end = start.plusSeconds(config.pollIntervalSeconds());
        boolean insideWindow = end.isAfter(start)
                ? !localTime.isBefore(start) && localTime.isBefore(end)
                : !localTime.isBefore(start) || localTime.isBefore(end);
        if (!insideWindow) {
            return;
        }
        triggers.replayDue(now, config.batchSize());
        worker.runOnce(now);
    }
}
