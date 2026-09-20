package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.entity.WhatsAppHistorySyncJobEntity;
import com.crmforlogistics.messagecenter.mapper.WhatsAppHistorySyncJobMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppHistorySyncWorkerTest {
    @Test
    void springCanConstructWorkerWithItsPrimaryConstructor() {
        new ApplicationContextRunner()
                .withBean(WhatsAppHistorySyncJobMapper.class,
                        () -> mock(WhatsAppHistorySyncJobMapper.class))
                .withBean(ChatAppMessageSyncService.class,
                        () -> mock(ChatAppMessageSyncService.class))
                .withUserConfiguration(WhatsAppHistorySyncWorker.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(WhatsAppHistorySyncWorker.class));
    }

    @Test
    void claimsAndCompletesOwnerScopedHistoryJob() {
        WhatsAppHistorySyncJobMapper jobs = mock(WhatsAppHistorySyncJobMapper.class);
        ChatAppMessageSyncService sync = mock(ChatAppMessageSyncService.class);
        WhatsAppHistorySyncJobEntity job = job(0);
        when(jobs.listRunnable(any(), eq(10))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(), any())).thenReturn(1);
        WhatsAppHistorySyncWorker worker = new WhatsAppHistorySyncWorker(jobs, sync, "worker-1");

        assertThat(worker.runOnce(Instant.parse("2026-09-09T08:00:00Z"), 10)).isEqualTo(1);

        verify(sync).runOwnedAccount(eq(job.getOwnerUserId()), eq(job.getChannelAccountId()),
                any(), any(), eq(50), eq(false));
        verify(jobs).finish(eq(job.getId()), eq("worker-1"), eq("COMPLETED"), eq(null), any(), any());
    }

    @Test
    void failedJobIsBoundedAndRecordsStableFailureCode() {
        WhatsAppHistorySyncJobMapper jobs = mock(WhatsAppHistorySyncJobMapper.class);
        ChatAppMessageSyncService sync = mock(ChatAppMessageSyncService.class);
        WhatsAppHistorySyncJobEntity job = job(2);
        when(jobs.listRunnable(any(), eq(10))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(), any())).thenReturn(1);
        when(sync.runOwnedAccount(eq(job.getOwnerUserId()), eq(job.getChannelAccountId()),
                any(), any(), eq(50), eq(false))).thenThrow(new IllegalStateException("provider secret detail"));
        WhatsAppHistorySyncWorker worker = new WhatsAppHistorySyncWorker(jobs, sync, "worker-1");

        worker.runOnce(Instant.parse("2026-09-09T08:00:00Z"), 10);

        verify(jobs).finish(eq(job.getId()), eq("worker-1"), eq("FAILED"),
                eq("WHATSAPP_HISTORY_SYNC_FAILED"), any(), any());
    }

    private static WhatsAppHistorySyncJobEntity job(int attempts) {
        WhatsAppHistorySyncJobEntity job = new WhatsAppHistorySyncJobEntity();
        job.setId(UUID.randomUUID());
        job.setChannelAccountId(UUID.randomUUID());
        job.setOwnerUserId(UUID.randomUUID());
        job.setAttemptCount(attempts);
        job.setStatus("PENDING");
        return job;
    }
}
