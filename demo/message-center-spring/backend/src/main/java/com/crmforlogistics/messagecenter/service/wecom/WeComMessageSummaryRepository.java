package com.crmforlogistics.messagecenter.service.wecom;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;

public interface WeComMessageSummaryRepository {
    boolean enqueueIfAbsent(EnqueueCommand command);

    Optional<LeasedJob> leaseNext(String owner, Instant now, java.time.Duration lease);

    void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt);

    void markCompleted(UUID jobId, String summary, String rawResponseJson,
                       String validationStage, Instant now);

    void markRetry(UUID jobId, String code, String rawResponseJson, String errorDiagnostic,
                   String validationStage, Instant nextAttemptAt);

    void markFailed(UUID jobId, String code, String state, String rawResponseJson, String errorDiagnostic,
                    String validationStage, Instant now);

    boolean exists(UUID installationId, String msgid);

    Set<String> countNonTerminalByMsgids(java.util.List<String> msgids);

    Optional<JobView> findByMsgid(UUID installationId, String msgid);

    PageResult find(PageQuery query);

    record PageQuery(UUID installationId, UUID sourceConversationId, String status,
                     Long fromSendTime, Long toSendTime, int page, int size) {}

    record PageResult(List<JobView> items, long total, int page, int size) {}

    record JobView(boolean messageExists, UUID id, UUID installationId, String authCorpId,
                   UUID sourceConversationId, String msgid, long sendTime, String status,
                   String wecomJobId, String summary, String rawRequestJson, String rawResponseJson,
                   String validationStage, String lastErrorCode, String lastErrorDiagnostic, String failureState,
                   int attemptCount, Instant nextAttemptAt, Instant createdAt, Instant updatedAt,
                   Instant submittedAt, Instant completedAt) {}

    record EnqueueCommand(UUID installationId, String authCorpId, UUID sourceConversationId,
                          String msgid, long sendTime, String rawRequestJson, Instant now) {}

    record LeasedJob(UUID id, UUID installationId, String authCorpId, UUID sourceConversationId,
                     String msgid, long sendTime, String status, String wecomJobId,
                     int attemptCount, Instant nextAttemptAt) {}
}
