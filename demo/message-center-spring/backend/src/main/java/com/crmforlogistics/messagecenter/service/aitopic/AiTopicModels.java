package com.crmforlogistics.messagecenter.service.aitopic;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AiTopicModels {
    private AiTopicModels() {}

    public enum SourceType { MESSAGE, CALL_RECORD, WECOM_SUMMARY }
    public enum OwnerType { CONTACT, WECOM_GROUP }
    public enum GenerationStatus { NOT_STARTED, GENERATING, READY, FAILED }
    public enum TopicOperationKind { EDIT, MERGE, STORE, RESTORE, REJECT_STORE }
    public enum TopicOperationStatus { PENDING, PROCESSING, COMPLETED, FAILED }
    public enum TopicReviewOrigin { MERGE_SOURCE, SPLIT_SOURCE, MANUAL_SELECTION }

    public record SourceItem(UUID id, SourceType sourceType, String channelType, Instant occurredAt,
                             String direction, String subject, String text) {}

    public record InputBatch(List<SourceItem> items, String fingerprint, boolean hasMore) {}
    public record AssignedSourceIds(Set<UUID> messageIds, Set<UUID> callRecordIds) {}

    public record TopicAssignment(String topicKey, String title, String summary, double relevance,
                                  List<UUID> sourceIds) {}

    public record GenerationInput(AiTopicOwnerService.OwnerRef owner, List<SourceItem> sources,
                                  List<TopicContext> existingTopics, boolean incremental) {}
    public record TopicContext(UUID id, String title, String summary, Instant firstOccurredAt,
                               Instant lastOccurredAt, List<UUID> sourceIds) {}
    public record GenerationOutput(List<TopicAssignment> assignments) {}

    public record TopicSourceItem(UUID id, SourceType sourceType, Instant occurredAt, String channelType) {}
    public record TopicProjection(UUID id, String title, String summary, String summarySource,
                                  Instant firstOccurredAt, Instant lastOccurredAt, List<String> channels,
                                  int sourceCount, List<TopicSourceItem> sourceItems, long version, UUID contactId,
                                  String contactName, String contactRemark, String contactChannelType,
                                  String contactChannelNickname, OwnerType ownerType, UUID ownerId,
                                  String ownerLabel, boolean isReferencedGroupTopic,
                                  TopicReviewOrigin reviewOrigin, String reviewSourceTopicTitle) {}
    public record GenerationProjection(GenerationStatus status, UUID jobId, String errorCode, Instant updatedAt) {}
    public record TopicOperationProjection(UUID id, TopicOperationKind kind, TopicOperationStatus status,
                                           String errorCode, Instant createdAt, Instant completedAt) {}
    public record TopicInboxRequestProjection(UUID id, UUID topicId, String topicTitle, OwnerType ownerType,
                                              UUID ownerId, String ownerLabel, UUID requestedByUserId,
                                              Instant createdAt) {}
    public record TopicTimelineResponse(UUID contactId, GenerationProjection generation,
                                        List<TopicProjection> topics, boolean weComUnsupported) {}
    public record GroupTopicTimelineResponse(UUID sourceConversationId, GenerationProjection generation,
                                             List<TopicProjection> topics, boolean weComUnsupported) {}
}
