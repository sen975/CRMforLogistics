package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComMessageSummaryJobEntity;
import com.crmforlogistics.messagecenter.mapper.WeComMessageSummaryJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComMessageSummaryRepositoryTest {
    private final WeComMessageSummaryJobMapper mapper = mock(WeComMessageSummaryJobMapper.class);
    private MyBatisWeComMessageSummaryRepository repository;
    private final UUID installationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-31T09:00:00Z");

    @BeforeEach
    void setUp() {
        repository = new MyBatisWeComMessageSummaryRepository(mapper);
    }

    @Test
    void enqueueReturnsTrueOnlyWhenMapperInserts() {
        when(mapper.insertIfAbsent(any())).thenReturn(1, 0);
        var command = new WeComMessageSummaryRepository.EnqueueCommand(
                installationId, "corp", null, "m-1", 1L, "{\"operation\":\"submit\"}", now);
        assertTrue(repository.enqueueIfAbsent(command));
        assertTrue(!repository.enqueueIfAbsent(command));
        verify(mapper, times(2)).insertIfAbsent(any(WeComMessageSummaryJobEntity.class));
    }

    @Test
    void leaseMapsEntityAndRejectsInvalidLease() {
        UUID jobId = UUID.randomUUID();
        when(mapper.leaseCandidate(now)).thenReturn(jobId);
        when(mapper.updateLease(eq(jobId), eq("worker-1"), any(), eq(now))).thenReturn(1);
        WeComMessageSummaryJobEntity entity = new WeComMessageSummaryJobEntity();
        entity.setId(jobId);
        entity.setInstallationId(installationId);
        entity.setAuthCorpId("corp");
        entity.setMsgid("m-1");
        entity.setSendTime(1L);
        entity.setStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(now);
        when(mapper.selectById(jobId)).thenReturn(entity);

        var leased = repository.leaseNext("worker-1", now, Duration.ofSeconds(30));
        assertEquals("m-1", leased.orElseThrow().msgid());
        assertThrows(IllegalArgumentException.class,
                () -> repository.leaseNext("worker-1", now, Duration.ZERO));
    }

    @Test
    void transitionWritesRawResponseAndValidationStage() {
        UUID jobId = UUID.randomUUID();
        when(mapper.updateCompleted(jobId, "摘要", "{}", "RESPONSE_DATA", now)).thenReturn(1);
        repository.markCompleted(jobId, "摘要", "{}", "RESPONSE_DATA", now);
        verify(mapper).updateCompleted(jobId, "摘要", "{}", "RESPONSE_DATA", now);
    }

    @Test
    void rejectsOversizedResponseBeforeDatabaseCall() {
        String oversized = "x".repeat(1_048_577);
        assertThrows(IllegalArgumentException.class, () -> repository.markRetry(
                UUID.randomUUID(), "AI_RESPONSE_INVALID", oversized, "RESPONSE_DATA", now));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsRequestAuditSnapshotContainingSecretKey() {
        var command = new WeComMessageSummaryRepository.EnqueueCommand(
                installationId, "corp", null, "m-1", 1L,
                "{\"msgid\":\"m-1\",\"secret_key\":\"must-not-persist\"}", now);
        assertThrows(IllegalArgumentException.class, () -> repository.enqueueIfAbsent(command));
        verifyNoInteractions(mapper);
    }
}
