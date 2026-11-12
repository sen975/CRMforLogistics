package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuditRetentionStateEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComAuditRetentionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComAuditRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void processesAllStreamsInBoundedShortTransactions() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        when(mapper.tryAcquireBatchLock()).thenReturn(true, true);
        when(mapper.deleteExpiredViewer(any(), eq(2))).thenReturn(2, 0);
        when(mapper.deleteExpiredAuthorization(any(), eq(2))).thenReturn(1, 0);
        when(mapper.deleteExpiredApi(any(), eq(2))).thenReturn(2, 0);
        WeComAuditRetentionService service = service(mapper, config(2, 2));

        WeComAuditRetentionService.RetentionRunResult result = service.enforce(NOW);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.viewerDeleted()).isEqualTo(2);
        assertThat(result.authorizationDeleted()).isEqualTo(1);
        assertThat(result.apiDeleted()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(2);
        verify(mapper, org.mockito.Mockito.times(2))
                .deleteExpiredViewer(NOW.minusSeconds(7 * 86400L), 2);
        verify(mapper, org.mockito.Mockito.times(2))
                .deleteExpiredAuthorization(NOW.minusSeconds(7 * 86400L), 2);
        verify(mapper, org.mockito.Mockito.times(2))
                .deleteExpiredApi(NOW.minusSeconds(7 * 86400L), 2);
        var states = forClass(WeComAuditRetentionStateEntity.class);
        verify(mapper, org.mockito.Mockito.times(6)).upsertState(states.capture());
        assertThat(states.getAllValues()).extracting(WeComAuditRetentionStateEntity::getStatus)
                .containsExactly("running", "running", "running", "success", "success", "success");
    }

    @Test
    void stopsAtMaxBatchesAndReportsRemainingBudget() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        when(mapper.tryAcquireBatchLock()).thenReturn(true, true, true);
        when(mapper.deleteExpiredViewer(any(), eq(1))).thenReturn(1);
        when(mapper.deleteExpiredAuthorization(any(), eq(1))).thenReturn(1);
        when(mapper.deleteExpiredApi(any(), eq(1))).thenReturn(1);
        WeComAuditRetentionService service = service(mapper, config(1, 2));

        WeComAuditRetentionService.RetentionRunResult result = service.enforce(NOW);

        assertThat(result.status()).isEqualTo("budget_remaining");
        assertThat(result.viewerDeleted()).isEqualTo(2);
        assertThat(result.authorizationDeleted()).isEqualTo(2);
        assertThat(result.apiDeleted()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(2);
    }

    @Test
    void reportsSuccessWhenTheFinalAllowedBatchIsNotFull() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        when(mapper.tryAcquireBatchLock()).thenReturn(true);
        when(mapper.deleteExpiredViewer(any(), eq(10))).thenReturn(1);
        when(mapper.deleteExpiredAuthorization(any(), eq(10))).thenReturn(0);
        WeComAuditRetentionService service = service(mapper, config(10, 1));

        WeComAuditRetentionService.RetentionRunResult result = service.enforce(NOW);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.viewerDeleted()).isEqualTo(1);
        assertThat(result.batches()).isEqualTo(1);
    }

    @Test
    void skipsRunWhenAnotherInstanceOwnsTheAdvisoryLock() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        when(mapper.tryAcquireBatchLock()).thenReturn(false);
        WeComAuditRetentionService service = service(mapper, config(10, 2));

        WeComAuditRetentionService.RetentionRunResult result = service.enforce(NOW);

        assertThat(result.status()).isEqualTo("skipped_locked");
        assertThat(result.batches()).isZero();
        verifyNoInteractionsAfterLock(mapper);
    }

    @Test
    void recordsFailureAndKeepsExceptionInsideRetentionBoundary() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        when(mapper.tryAcquireBatchLock()).thenThrow(new IllegalStateException("db down"));
        WeComAuditRetentionService service = service(mapper, config(10, 2));

        WeComAuditRetentionService.RetentionRunResult result = service.enforce(NOW);

        assertThat(result.status()).isEqualTo("failed");
        verify(mapper, org.mockito.Mockito.atLeastOnce())
                .upsertState(any(WeComAuditRetentionStateEntity.class));
    }

    @Test
    void rejectsOutOfRangeCleanupIntervalBeforeAccessingTheDatabase() {
        WeComAuditRetentionMapper mapper = mock(WeComAuditRetentionMapper.class);
        AppConfig config = config(10, 2);
        when(config.wecomAuditCleanupIntervalSeconds()).thenReturn(59);
        WeComAuditRetentionService service = service(mapper, config);

        assertThatThrownBy(() -> service.enforce(NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wecomAuditCleanupIntervalSeconds");
        verifyNoInteractions(mapper);
    }

    private static WeComAuditRetentionService service(WeComAuditRetentionMapper mapper,
                                                        AppConfig config) {
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        return new WeComAuditRetentionService(config, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private static AppConfig config(int batchSize, int maxBatches) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomAuditRetentionDays()).thenReturn(7);
        when(config.wecomAuditCleanupIntervalSeconds()).thenReturn(3600);
        when(config.wecomAuditCleanupBatchSize()).thenReturn(batchSize);
        when(config.wecomAuditCleanupMaxBatches()).thenReturn(maxBatches);
        return config;
    }

    private static void verifyNoInteractionsAfterLock(WeComAuditRetentionMapper mapper) {
        verify(mapper, org.mockito.Mockito.never()).deleteExpiredViewer(any(), any(Integer.class));
        verify(mapper, org.mockito.Mockito.never()).deleteExpiredAuthorization(any(), any(Integer.class));
        verify(mapper, org.mockito.Mockito.never()).deleteExpiredApi(any(), any(Integer.class));
    }
}
