package com.crmforlogistics.messagecenter.service.aitopic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiTopicModels {
    private AiTopicModels() {}

    public enum SourceType { MESSAGE, CALL_RECORD }
    public enum GenerationStatus { NOT_STARTED, GENERATING, READY, FAILED }
    public enum TopicOperationKind { EDIT, MERGE, DISCARD, RESTORE }
    public enum TopicOperationStatus { PENDING, PROCESSING, COMPLETED, FAILED }

    public record SourceItem(UUID id, SourceType sourceType, String channelType, Instant occurredAt,
                             String direction, String subject, String text) {}

    public record InputBatch(List<SourceItem> items, String fingerprint, boolean hasMore) {}

    public record TopicAssignment(String topicKey, String title, String summary, double relevance,
                                  List<UUID> sourceIds) {}

    public record GenerationInput(List<SourceItem> sources, List<TopicContext> existingTopics, boolean incremental) {}
    public record TopicContext(UUID id, String title, String summary, Instant firstOccurredAt,
                               Instant lastOccurredAt, List<UUID> sourceIds) {}
    public record GenerationOutput(List<TopicAssignment> assignments) {}

    public record TopicSourceItem(UUID id, SourceType sourceType, Instant occurredAt, String channelType) {}
    public record TopicProjection(UUID id, String title, String summary, String summarySource,
                                  Instant firstOccurredAt, Instant lastOccurredAt, List<String> channels,
                                  int sourceCount, List<TopicSourceItem> sourceItems, long version) {}
    public record GenerationProjection(GenerationStatus status, UUID jobId, String errorCode, Instant updatedAt) {}
    public record TopicOperationProjection(UUID id, TopicOperationKind kind, TopicOperationStatus status,
                                           String errorCode, Instant createdAt, Instant completedAt) {}
    public record TopicTimelineResponse(UUID contactId, GenerationProjection generation,
                                        List<TopicProjection> topics, boolean weComUnsupported) {}
}
