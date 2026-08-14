package com.crmforlogistics.messagecenter.service.wecom;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeComDailySummaryRepository {
    void ensureDailyJob(DailySummaryKey key, List<String> msgidDigests,
                        Instant deadline, Instant now);

    Optional<LeasedSummaryJob> leaseNext(String owner, Instant now, Duration lease);

    void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt);

    void markCompleted(UUID jobId, String summary, Coverage coverage, Instant now);

    void markRetry(UUID jobId, String code, Instant nextAttemptAt);

    void markFailed(UUID jobId, String code, String state, Instant now);

    void replaceWithSplit(UUID jobId, List<String> leftMsgidDigests,
                          List<String> rightMsgidDigests, int maxBatches, Instant now);

    Optional<DailySummary> findSummary(DailyConversationKey key);

    record DailySummaryKey(String installationId, String authCorpId, LocalDate day,
                           String userId, String externalUserId, int sliceStart, int sliceEnd) {}

    record DailyConversationKey(String installationId, String authCorpId, LocalDate day,
                                String userId, String externalUserId) {}

    record LeasedSummaryJob(UUID jobId, DailySummaryKey key, String messageDigest,
                            int messageCount, String status, String wecomJobId,
                            int attemptCount, Instant deadline) {}

    record Coverage(int messageCount, int completedMessageCount,
                    int batchCount, String completeness) {}

    record DailySummary(DailyConversationKey key, String summary, int messageCount,
                        int completedMessageCount, int batchCount,
                        int completedBatchCount, String completeness, Instant generatedAt) {}
}
