package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiTopicManualReviewResponse {
    private AiTopicManualReviewResponse() {}
    public record SourceOption(UUID id, UUID contactIdentityId, SourceType sourceType,
                               String channelType, Instant occurredAt, String direction,
                               String subject, String text, boolean selectable, String excludedReason,
                               UUID assignedTopicId, String assignedTopicTitle) {
        public SourceOption(UUID id, UUID contactIdentityId, SourceType sourceType,
                            String channelType, Instant occurredAt, String direction,
                            String subject, String text, boolean selectable, String excludedReason) {
            this(id, contactIdentityId, sourceType, channelType, occurredAt, direction, subject, text,
                    selectable, excludedReason, null, null);
        }
        public SourceOption(UUID id, UUID contactIdentityId, SourceType sourceType,
                            String channelType, Instant occurredAt, String direction,
                            String subject, String text, boolean selectable) {
            this(id, contactIdentityId, sourceType, channelType, occurredAt, direction, subject, text,
                    selectable, null, null, null);
        }
        public SourceOption(UUID id, SourceType sourceType, String channelType, Instant occurredAt,
                            String subject, String text, boolean selectable) {
            this(id, null, sourceType, channelType, occurredAt, "", subject, text, selectable, null, null, null);
        }
    }
    public record SourceListResponse(UUID contactId, List<SourceOption> items, boolean hasMore,
                                     String wecomExcludedReason) {}
    public record PreviewResponse(UUID previewId, UUID contactId, String sourceFingerprint,
                                  List<Assignment> assignments, Map<UUID, Long> expectedVersions,
                                  Instant expiresAt) {}
    public record Assignment(String topicKey, String title, String summary, double relevance,
                             List<UUID> sourceIds) {
        public Assignment(String topicKey, String title, String summary, List<UUID> sourceIds) {
            this(topicKey, title, summary, 0d, sourceIds);
        }
    }
    public record ApplyResponse(UUID previewId, List<UUID> topicIds, boolean applied) {}
    public record FusionPreviewResponse(UUID previewId, List<UUID> topicIds, String title,
                                        String summary, int sourceCount,
                                        Map<UUID, Long> expectedVersions, Instant expiresAt) {}
}
