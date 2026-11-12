package com.crmforlogistics.messagecenter.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiTopicManualReviewRequest(List<UUID> sourceIds, String sourceFingerprint,
                                         Instant from, Instant to, UUID contactIdentityId,
                                         List<Assignment> assignments) {
    public AiTopicManualReviewRequest(List<UUID> sourceIds, String sourceFingerprint,
                                      Instant from, Instant to) {
        this(sourceIds, sourceFingerprint, from, to, null, null);
    }

    public record Assignment(String topicKey, String title, String summary, double relevance,
                             List<UUID> sourceIds) {}
}
