package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("conversations")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID channelAccountId;
    private UUID contactIdentityId;
    private UUID sourceConversationId;
    private String status;
    private UUID assignedTeamId;
    private UUID assignedUserId;
    private Long nextIngestSequence;
    private UUID lastMessageId;
    private Instant lastMessageAt;
    private Instant createdAt;
    private Instant updatedAt;

    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public void setChannelAccountId(UUID channelAccountId) {
        this.channelAccountId = channelAccountId;
    }

    public UUID getContactIdentityId() {
        return contactIdentityId;
    }

    public void setContactIdentityId(UUID contactIdentityId) {
        this.contactIdentityId = contactIdentityId;
    }

    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID sourceConversationId) { this.sourceConversationId = sourceConversationId; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getAssignedTeamId() {
        return assignedTeamId;
    }

    public void setAssignedTeamId(UUID assignedTeamId) {
        this.assignedTeamId = assignedTeamId;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(UUID assignedUserId) {
        this.assignedUserId = assignedUserId;
    }

    public Long getNextIngestSequence() {
        return nextIngestSequence;
    }

    public void setNextIngestSequence(Long nextIngestSequence) {
        this.nextIngestSequence = nextIngestSequence;
    }

    public UUID getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(UUID lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
