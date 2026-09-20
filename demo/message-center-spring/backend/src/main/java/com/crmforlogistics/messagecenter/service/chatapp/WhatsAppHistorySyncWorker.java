package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.entity.WhatsAppHistorySyncJobEntity;
import com.crmforlogistics.messagecenter.mapper.WhatsAppHistorySyncJobMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
public class WhatsAppHistorySyncWorker {
    private static final int MAX_ATTEMPTS = 3;
    private final WhatsAppHistorySyncJobMapper jobs;
    private final ChatAppMessageSyncService sync;
    private final String workerId;

    @Autowired
    public WhatsAppHistorySyncWorker(WhatsAppHistorySyncJobMapper jobs, ChatAppMessageSyncService sync) {
        this(jobs, sync, "whatsapp-history-" + UUID.randomUUID());
    }

    WhatsAppHistorySyncWorker(WhatsAppHistorySyncJobMapper jobs, ChatAppMessageSyncService sync, String workerId) {
        this.jobs = jobs;
        this.sync = sync;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledRun() { runOnce(Instant.now(), 10); }

    public int runOnce(Instant now, int limit) {
        int processed = 0;
        for (WhatsAppHistorySyncJobEntity job : jobs.listRunnable(now, Math.min(10, Math.max(1, limit)))) {
            if (jobs.claim(job.getId(), workerId, now.plus(2, ChronoUnit.MINUTES)) == 0) continue;
            process(job, now);
            processed++;
        }
        return processed;
    }

    private void process(WhatsAppHistorySyncJobEntity job, Instant now) {
        try {
            Instant end = now;
            sync.runOwnedAccount(job.getOwnerUserId(), job.getChannelAccountId(),
                    end.minus(30, ChronoUnit.DAYS), end, 50, false);
            jobs.finish(job.getId(), workerId, "COMPLETED", null, now, now);
        } catch (RuntimeException error) {
            int attempts = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
            String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "RETRY_WAIT";
            Instant next = status.equals("FAILED") ? now : now.plusSeconds(30L << Math.min(4, attempts - 1));
            jobs.finish(job.getId(), workerId, status, "WHATSAPP_HISTORY_SYNC_FAILED", next,
                    status.equals("FAILED") ? now : null);
        }
    }
}
