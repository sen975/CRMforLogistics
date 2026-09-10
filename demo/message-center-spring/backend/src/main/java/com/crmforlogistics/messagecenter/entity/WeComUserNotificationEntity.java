package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_user_notifications")
public class WeComUserNotificationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID conversationId;
    private UUID channelAccountId;
    private UUID recipientUserId;
    private String recipientWecomUserId;
    private String authCorpId;
    private String agentId;
    private String channelType;
    private String contactLabel;
    private Integer messageCount;
    private String lastPreview;
    private Instant firstMessageAt;
    private Instant sendAfter;
    private String status;
    private Integer attemptCount;
    private String lastError;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(UUID recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getRecipientWecomUserId() { return recipientWecomUserId; }
    public void setRecipientWecomUserId(String recipientWecomUserId) { this.recipientWecomUserId = recipientWecomUserId; }
    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getContactLabel() { return contactLabel; }
    public void setContactLabel(String contactLabel) { this.contactLabel = contactLabel; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getLastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
    public Instant getFirstMessageAt() { return firstMessageAt; }
    public void setFirstMessageAt(Instant firstMessageAt) { this.firstMessageAt = firstMessageAt; }
    public Instant getSendAfter() { return sendAfter; }
    public void setSendAfter(Instant sendAfter) { this.sendAfter = sendAfter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
