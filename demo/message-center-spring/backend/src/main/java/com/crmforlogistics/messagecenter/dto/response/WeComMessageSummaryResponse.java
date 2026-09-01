package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.UUID;

public record WeComMessageSummaryResponse(
        boolean messageExists,
        UUID id,
        UUID installationId,
        String authCorpId,
        UUID sourceConversationId,
        String msgid,
        long sendTime,
        String status,
        String wecomJobId,
        String summary,
        String rawRequestJson,
        String rawResponseJson,
        String validationStage,
        String lastErrorCode,
        String failureState,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        Instant completedAt) {
}
