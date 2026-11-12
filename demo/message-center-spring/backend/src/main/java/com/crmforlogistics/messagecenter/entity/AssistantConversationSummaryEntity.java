package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("assistant_conversation_summaries")
public class AssistantConversationSummaryEntity {
    private UUID id;
    private UUID userId;
    private UUID conversationId;
    private String summary;
    private UUID throughMessageId;
    private Instant throughCreatedAt;
    private int coveredMessageCount;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public UUID getThroughMessageId() { return throughMessageId; }
    public void setThroughMessageId(UUID throughMessageId) { this.throughMessageId = throughMessageId; }
    public Instant getThroughCreatedAt() { return throughCreatedAt; }
    public void setThroughCreatedAt(Instant throughCreatedAt) { this.throughCreatedAt = throughCreatedAt; }
    public int getCoveredMessageCount() { return coveredMessageCount; }
    public void setCoveredMessageCount(int coveredMessageCount) { this.coveredMessageCount = coveredMessageCount; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
