package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_topics")
public class AiTopicEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private String ownerType;
    private UUID ownerId;
    private UUID wecomGroupSourceConversationId;
    @TableField(exist = false)
    private String ownerLabel;
    @TableField(exist = false)
    private String contactDisplayName;
    @TableField(exist = false)
    private String contactRemark;
    @TableField(exist = false)
    private String contactChannelType;
    @TableField(exist = false)
    private String contactChannelNickname;
    private String title;
    private String aiSummary;
    private String confirmedSummary;
    private String status;
    private String reviewOrigin;
    private UUID reviewSourceContactId;
    private UUID reviewSourceTopicId;
    @TableField(exist = false)
    private String reviewSourceTopicTitle;
    private UUID reviewOperationId;
    private Instant firstOccurredAt;
    private Instant lastOccurredAt;
    private String inputFingerprint;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public UUID getWecomGroupSourceConversationId() { return wecomGroupSourceConversationId; }
    public void setWecomGroupSourceConversationId(UUID value) { this.wecomGroupSourceConversationId = value; }
    public String getOwnerLabel() { return ownerLabel; }
    public void setOwnerLabel(String value) { this.ownerLabel = value; }
    public String getContactDisplayName() { return contactDisplayName; }
    public void setContactDisplayName(String contactDisplayName) { this.contactDisplayName = contactDisplayName; }
    public String getContactRemark() { return contactRemark; }
    public void setContactRemark(String contactRemark) { this.contactRemark = contactRemark; }
    public String getContactChannelType() { return contactChannelType; }
    public void setContactChannelType(String contactChannelType) { this.contactChannelType = contactChannelType; }
    public String getContactChannelNickname() { return contactChannelNickname; }
    public void setContactChannelNickname(String contactChannelNickname) { this.contactChannelNickname = contactChannelNickname; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public String getConfirmedSummary() { return confirmedSummary; }
    public void setConfirmedSummary(String confirmedSummary) { this.confirmedSummary = confirmedSummary; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReviewOrigin() { return reviewOrigin; }
    public void setReviewOrigin(String reviewOrigin) { this.reviewOrigin = reviewOrigin; }
    public UUID getReviewSourceContactId() { return reviewSourceContactId; }
    public void setReviewSourceContactId(UUID reviewSourceContactId) { this.reviewSourceContactId = reviewSourceContactId; }
    public UUID getReviewSourceTopicId() { return reviewSourceTopicId; }
    public void setReviewSourceTopicId(UUID reviewSourceTopicId) { this.reviewSourceTopicId = reviewSourceTopicId; }
    public String getReviewSourceTopicTitle() { return reviewSourceTopicTitle; }
    public void setReviewSourceTopicTitle(String reviewSourceTopicTitle) { this.reviewSourceTopicTitle = reviewSourceTopicTitle; }
    public UUID getReviewOperationId() { return reviewOperationId; }
    public void setReviewOperationId(UUID reviewOperationId) { this.reviewOperationId = reviewOperationId; }
    public Instant getFirstOccurredAt() { return firstOccurredAt; }
    public void setFirstOccurredAt(Instant firstOccurredAt) { this.firstOccurredAt = firstOccurredAt; }
    public Instant getLastOccurredAt() { return lastOccurredAt; }
    public void setLastOccurredAt(Instant lastOccurredAt) { this.lastOccurredAt = lastOccurredAt; }
    public String getInputFingerprint() { return inputFingerprint; }
    public void setInputFingerprint(String inputFingerprint) { this.inputFingerprint = inputFingerprint; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
