package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComDailySummaryEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDailySummaryJobEntity;
import com.crmforlogistics.messagecenter.mapper.WeComDailySummaryMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class MyBatisWeComDailySummaryRepository implements WeComDailySummaryRepository {
    private static final int MAX_SUMMARY_BYTES = 65_536;
    private final WeComDailySummaryMapper mapper;

    public MyBatisWeComDailySummaryRepository(WeComDailySummaryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void ensureDailyJob(DailySummaryKey key, List<String> msgidDigests,
                               Instant deadline, Instant now) {
        validateKey(key);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(now, "now");
        if (!deadline.isAfter(now) || msgidDigests == null
                || msgidDigests.size() != key.sliceEnd() - key.sliceStart()) {
            throw new IllegalArgumentException("daily summary job input invalid");
        }
        String messageDigest = aggregateDigest(msgidDigests);
        WeComDailySummaryJobEntity entity = new WeComDailySummaryJobEntity();
        entity.setInstallationId(key.installationId());
        entity.setAuthCorpId(key.authCorpId());
        entity.setSummaryDay(key.day());
        entity.setUserId(key.userId());
        entity.setExternalUserId(key.externalUserId());
        entity.setSliceStart(key.sliceStart());
        entity.setSliceEnd(key.sliceEnd());
        entity.setMessageCount(msgidDigests.size());
        entity.setMessageDigest(messageDigest);
        entity.setNextAttemptAt(now);
        entity.setDeadlineAt(deadline);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insertJob(entity);
        WeComDailySummaryJobEntity verified = mapper.selectJobIdentity(key.installationId(),
                key.authCorpId(), key.day(), key.userId(), key.externalUserId(),
                key.sliceStart(), key.sliceEnd());
        if (verified == null || !messageDigest.equals(verified.getMessageDigest())
                || verified.getMessageCount() == null
                || verified.getMessageCount() != msgidDigests.size()
                || !deadline.equals(verified.getDeadlineAt())) {
            throw new IllegalStateException("daily summary job identity drift");
        }
    }

    @Override
    @Transactional
    public Optional<LeasedSummaryJob> leaseNext(String owner, Instant now, Duration lease) {
        requireText(owner, 100, "owner");
        Objects.requireNonNull(now, "now");
        if (lease == null || lease.isZero() || lease.isNegative()
                || lease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("lease invalid");
        }
        UUID jobId = mapper.leaseCandidate(now);
        if (jobId == null) return Optional.empty();
        mapper.updateLease(jobId, owner, now.plus(lease), now);
        return Optional.of(readJob(jobId));
    }

    @Override
    @Transactional
    public void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt) {
        Objects.requireNonNull(jobId, "jobId");
        requireText(wecomJobId, 256, "wecomJobId");
        Objects.requireNonNull(nextPollAt, "nextPollAt");
        if (mapper.updateSubmitted(jobId, wecomJobId, nextPollAt) != 1) {
            throw new IllegalStateException("daily summary submit transition rejected");
        }
    }

    @Override
    @Transactional
    public void markCompleted(UUID jobId, String summary, Coverage coverage, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        validateSummary(summary);
        validateCoverage(coverage);
        Objects.requireNonNull(now, "now");
        JobGroup group = lockJobGroup(jobId);
        if ("COMPLETED".equals(group.status())) return;
        if (!"SUBMITTED".equals(group.status())) {
            throw new IllegalStateException("daily summary complete transition rejected");
        }
        if (mapper.updateCompleted(jobId, summary, now) != 1) {
            throw new IllegalStateException("daily summary complete transition rejected");
        }
        finalizeGroup(group, now);
    }

    @Override
    @Transactional
    public void markRetry(UUID jobId, String code, Instant nextAttemptAt) {
        Objects.requireNonNull(jobId, "jobId");
        requireText(code, 100, "code");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (mapper.updateRetry(jobId, code, nextAttemptAt) != 1) {
            throw new IllegalStateException("daily summary retry transition rejected");
        }
    }

    @Override
    @Transactional
    public void markFailed(UUID jobId, String code, String state, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        requireText(code, 100, "code");
        requireText(state, 32, "state");
        Objects.requireNonNull(now, "now");
        JobGroup group = lockJobGroup(jobId);
        if (mapper.updateFailed(jobId, code, state, now) != 1) {
            throw new IllegalStateException("daily summary failure transition rejected");
        }
        finalizeGroup(group, now);
    }

    @Override
    @Transactional
    public void replaceWithSplit(UUID jobId, List<String> leftMsgidDigests,
                                 List<String> rightMsgidDigests, int maxBatches, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(now, "now");
        if (maxBatches < 2 || maxBatches > 32 || leftMsgidDigests == null
                || leftMsgidDigests.isEmpty() || rightMsgidDigests == null
                || rightMsgidDigests.isEmpty()) {
            throw new IllegalArgumentException("daily summary split invalid");
        }
        String leftDigest = aggregateDigest(leftMsgidDigests);
        String rightDigest = aggregateDigest(rightMsgidDigests);
        WeComDailySummaryJobEntity parentEntity = mapper.selectParentForUpdate(jobId);
        if (parentEntity == null) throw new IllegalStateException("daily summary split parent missing");
        SplitParent parent = new SplitParent(parentEntity.getInstallationId(),
                parentEntity.getAuthCorpId(), parentEntity.getSummaryDay(),
                parentEntity.getUserId(), parentEntity.getExternalUserId(),
                parentEntity.getSliceStart(), parentEntity.getSliceEnd(),
                parentEntity.getStatus(), parentEntity.getWecomJobId(),
                parentEntity.getDeadlineAt());
        if (!("PENDING".equals(parent.status()) || "RETRY_WAIT".equals(parent.status()))
                || parent.wecomJobId() != null
                || leftMsgidDigests.size() + rightMsgidDigests.size()
                != parent.sliceEnd() - parent.sliceStart()) {
            throw new IllegalStateException("daily summary split transition rejected");
        }
        int activeCount = mapper.countActive(parent.installationId(), parent.authCorpId(),
                parent.day(), parent.userId(), parent.externalUserId());
        if (activeCount + 1 > maxBatches) {
            throw new IllegalStateException("daily summary batch limit exceeded");
        }
        mapper.updateSuperseded(jobId, now);
        int midpoint = parent.sliceStart() + leftMsgidDigests.size();
        insertSplitChild(parent, parent.sliceStart(), midpoint,
                leftMsgidDigests.size(), leftDigest, now);
        insertSplitChild(parent, midpoint, parent.sliceEnd(),
                rightMsgidDigests.size(), rightDigest, now);
    }

    @Override
    public Optional<DailySummary> findSummary(DailyConversationKey key) {
        validateConversationKey(key);
        WeComDailySummaryEntity entity = mapper.selectSummary(key.installationId(),
                key.authCorpId(), key.day(), key.userId(), key.externalUserId());
        if (entity == null) return Optional.empty();
        return Optional.of(new DailySummary(key, entity.getSummary(),
                entity.getMessageCount(), entity.getCompletedMessageCount(),
                entity.getBatchCount(), entity.getCompletedBatchCount(),
                entity.getCompleteness(), entity.getGeneratedAt()));
    }

    private LeasedSummaryJob readJob(UUID jobId) {
        WeComDailySummaryJobEntity entity = mapper.selectJobById(jobId);
        if (entity == null) throw new IllegalStateException("leased summary job missing");
        DailySummaryKey key = new DailySummaryKey(entity.getInstallationId(),
                entity.getAuthCorpId(), entity.getSummaryDay(), entity.getUserId(),
                entity.getExternalUserId(), entity.getSliceStart(), entity.getSliceEnd());
        return new LeasedSummaryJob(jobId, key, entity.getMessageDigest(),
                entity.getMessageCount(), entity.getStatus(), entity.getWecomJobId(),
                entity.getAttemptCount(), entity.getDeadlineAt());
    }

    private JobGroup lockJobGroup(UUID jobId) {
        WeComDailySummaryJobEntity entity = mapper.lockJobGroup(jobId);
        if (entity == null) throw new IllegalStateException("daily summary job missing");
        return new JobGroup(entity.getInstallationId(), entity.getAuthCorpId(),
                entity.getSummaryDay(), entity.getUserId(), entity.getExternalUserId(),
                entity.getStatus());
    }

    private void finalizeGroup(JobGroup group, Instant now) {
        Map<String, Object> aggregate = mapper.aggregateGroup(group.installationId(),
                group.authCorpId(), group.day(), group.userId(), group.externalUserId());
        int nonTerminal = intValue(aggregate.get("non_terminal"));
        int batchCount = intValue(aggregate.get("batch_count"));
        int messageCount = intValue(aggregate.get("message_count"));
        int completedBatches = intValue(aggregate.get("completed_batches"));
        int completedMessageCount = intValue(aggregate.get("completed_message_count"));
        int failedBatches = intValue(aggregate.get("failed_batches"));
        String combinedSummary = (String) aggregate.get("combined_summary");
        if (nonTerminal != 0 || completedBatches == 0 || combinedSummary == null) return;
        WeComDailySummaryEntity summary = new WeComDailySummaryEntity();
        summary.setInstallationId(group.installationId());
        summary.setAuthCorpId(group.authCorpId());
        summary.setSummaryDay(group.day());
        summary.setUserId(group.userId());
        summary.setExternalUserId(group.externalUserId());
        summary.setSummary(combinedSummary);
        summary.setMessageCount(messageCount);
        summary.setCompletedMessageCount(completedMessageCount);
        summary.setBatchCount(batchCount);
        summary.setCompletedBatchCount(completedBatches);
        summary.setCompleteness(failedBatches == 0 ? "COMPLETE" : "PARTIAL");
        summary.setGeneratedAt(now);
        mapper.insertSummary(summary);
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void insertSplitChild(SplitParent parent, int start, int end, int messageCount,
                                  String messageDigest, Instant now) {
        WeComDailySummaryJobEntity entity = new WeComDailySummaryJobEntity();
        entity.setInstallationId(parent.installationId());
        entity.setAuthCorpId(parent.authCorpId());
        entity.setSummaryDay(parent.day());
        entity.setUserId(parent.userId());
        entity.setExternalUserId(parent.externalUserId());
        entity.setSliceStart(start);
        entity.setSliceEnd(end);
        entity.setMessageCount(messageCount);
        entity.setMessageDigest(messageDigest);
        entity.setNextAttemptAt(now);
        entity.setDeadlineAt(parent.deadline());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insertJob(entity);
    }

    private static String aggregateDigest(List<String> digests) {
        MessageDigest hash = sha256();
        for (String digest : digests) {
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("msgid digest invalid");
            }
            hash.update(digest.getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) '\n');
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void validateKey(DailySummaryKey key) {
        if (key == null || key.day() == null || key.sliceStart() < 0
                || key.sliceEnd() <= key.sliceStart()) {
            throw new IllegalArgumentException("daily summary key invalid");
        }
        requireText(key.installationId(), 128, "installationId");
        requireText(key.authCorpId(), 128, "authCorpId");
        requireText(key.userId(), 128, "userId");
        requireText(key.externalUserId(), 128, "externalUserId");
    }

    private static void validateConversationKey(DailyConversationKey key) {
        if (key == null || key.day() == null) {
            throw new IllegalArgumentException("daily conversation key invalid");
        }
        requireText(key.installationId(), 128, "installationId");
        requireText(key.authCorpId(), 128, "authCorpId");
        requireText(key.userId(), 128, "userId");
        requireText(key.externalUserId(), 128, "externalUserId");
    }

    private static void validateCoverage(Coverage coverage) {
        if (coverage == null || coverage.messageCount() < 1
                || coverage.completedMessageCount() < 1
                || coverage.completedMessageCount() > coverage.messageCount()
                || coverage.batchCount() < 1
                || !("COMPLETE".equals(coverage.completeness())
                || "PARTIAL".equals(coverage.completeness()))) {
            throw new IllegalArgumentException("coverage invalid");
        }
    }

    private static void validateSummary(String summary) {
        requireText(summary, Integer.MAX_VALUE, "summary");
        if (summary.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
            throw new IllegalArgumentException("summary too large");
        }
    }

    private static void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " invalid");
        }
    }

    private record JobGroup(String installationId, String authCorpId, LocalDate day,
                            String userId, String externalUserId, String status) {}
    private record SplitParent(String installationId, String authCorpId, LocalDate day,
                               String userId, String externalUserId, int sliceStart, int sliceEnd,
                               String status, String wecomJobId, Instant deadline) {}
}
