package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuditRetentionStateEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComAuditRetentionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnWeComEnabled
public class WeComAuditRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(WeComAuditRetentionService.class);
    private static final String FAILURE_CODE = "WECOM_AUDIT_RETENTION_FAILED";

    private final AppConfig config;
    private final WeComAuditRetentionMapper mapper;
    private final Clock clock;
    private final TransactionOperations transactions;

    @Autowired
    public WeComAuditRetentionService(AppConfig config,
                                      WeComAuditRetentionMapper mapper,
                                      PlatformTransactionManager transactionManager) {
        this(config, mapper, Clock.systemUTC(), new TransactionTemplate(transactionManager));
    }

    WeComAuditRetentionService(AppConfig config, WeComAuditRetentionMapper mapper,
                               Clock clock, TransactionOperations transactions) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public RetentionRunResult enforce(Instant now) {
        Instant startedAt = Objects.requireNonNull(now, "now");
        validateSettings();
        Instant cutoff = startedAt.minus(config.wecomAuditRetentionDays(), ChronoUnit.DAYS);
        int maxBatches = config.wecomAuditCleanupMaxBatches();
        int batchSize = config.wecomAuditCleanupBatchSize();
        long viewerDeleted = 0;
        long authorizationDeleted = 0;
        long apiDeleted = 0;
        int batches = 0;
        boolean lastBatchSaturated = false;
        String status = "success";

        recordState(startedAt, "running", 0, 0, 0, null);
        try {
            while (batches < maxBatches) {
                BatchResult batch = transactions.execute(transaction -> runBatch(cutoff, batchSize));
                if (batch == null || !batch.lockAcquired()) {
                    status = "skipped_locked";
                    break;
                }
                batches++;
                viewerDeleted += batch.viewerDeleted();
                authorizationDeleted += batch.authorizationDeleted();
                apiDeleted += batch.apiDeleted();
                lastBatchSaturated = batch.viewerDeleted() == batchSize
                        || batch.authorizationDeleted() == batchSize
                        || batch.apiDeleted() == batchSize;
                if (batch.viewerDeleted() == 0 && batch.authorizationDeleted() == 0
                        && batch.apiDeleted() == 0) {
                    break;
                }
            }
            if ("success".equals(status) && batches == maxBatches
                    && lastBatchSaturated) {
                status = "budget_remaining";
            }
            recordState(startedAt, status, viewerDeleted, authorizationDeleted, apiDeleted, null);
            return new RetentionRunResult(status, viewerDeleted, authorizationDeleted,
                    apiDeleted, batches);
        } catch (RuntimeException exception) {
            recordState(startedAt, "failed", viewerDeleted, authorizationDeleted,
                    apiDeleted, FAILURE_CODE);
            return new RetentionRunResult("failed", viewerDeleted, authorizationDeleted,
                    apiDeleted, batches);
        }
    }

    private BatchResult runBatch(Instant cutoff, int batchSize) {
        if (!mapper.tryAcquireBatchLock()) {
            return new BatchResult(false, 0, 0, 0);
        }
        int viewerDeleted = mapper.deleteExpiredViewer(cutoff, batchSize);
        int authorizationDeleted = mapper.deleteExpiredAuthorization(cutoff, batchSize);
        int apiDeleted = mapper.deleteExpiredApi(cutoff, batchSize);
        return new BatchResult(true, viewerDeleted, authorizationDeleted, apiDeleted);
    }

    private void recordState(Instant startedAt, String status, long viewerDeleted,
                             long authorizationDeleted, long apiDeleted, String errorCode) {
        Instant updatedAt = clock.instant();
        try {
            transactions.execute(transaction -> {
                mapper.upsertState(state("viewer", startedAt, status, viewerDeleted,
                        errorCode, updatedAt));
                mapper.upsertState(state("authorization", startedAt, status,
                        authorizationDeleted, errorCode, updatedAt));
                mapper.upsertState(state("api", startedAt, status,
                        apiDeleted, errorCode, updatedAt));
                return null;
            });
        } catch (RuntimeException exception) {
            LOG.warn("event=wecom.audit_retention_state status=write_failed errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static WeComAuditRetentionStateEntity state(String stream, Instant startedAt,
                                                         String status, long deletedCount,
                                                         String errorCode, Instant updatedAt) {
        WeComAuditRetentionStateEntity state = new WeComAuditRetentionStateEntity();
        state.setStream(stream);
        state.setLastStartedAt(startedAt);
        state.setLastCompletedAt("running".equals(status) || "failed".equals(status)
                ? null : updatedAt);
        state.setDeletedCount(deletedCount);
        state.setStatus(status);
        state.setErrorCode(errorCode);
        state.setUpdatedAt(updatedAt);
        return state;
    }

    private void validateSettings() {
        requireRange("wecomAuditRetentionDays", config.wecomAuditRetentionDays(), 1, 365);
        requireRange("wecomAuditCleanupIntervalSeconds",
                config.wecomAuditCleanupIntervalSeconds(), 60, 86400);
        requireRange("wecomAuditCleanupBatchSize", config.wecomAuditCleanupBatchSize(), 1, 2000);
        requireRange("wecomAuditCleanupMaxBatches", config.wecomAuditCleanupMaxBatches(), 1, 64);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum
                    + " and " + maximum);
        }
    }

    private record BatchResult(boolean lockAcquired, int viewerDeleted,
                               int authorizationDeleted, int apiDeleted) {}

    public record RetentionRunResult(String status, long viewerDeleted,
                                     long authorizationDeleted, long apiDeleted, int batches) {}
}
