package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_topic_inbox_requests")
public class AiTopicInboxRequestEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID topicId;
    private UUID requestedByUserId;
    private UUID reviewedByUserId;
    private String status;
    private String reason;
    private Instant createdAt;
    private Instant reviewedAt;
    @TableField(exist = false)
    private String topicTitle;
    @TableField(exist = false)
    private String ownerType;
    @TableField(exist = false)
    private UUID ownerId;
    @TableField(exist = false)
    private String ownerLabel;

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public UUID getTopicId() { return topicId; }
    public void setTopicId(UUID value) { this.topicId = value; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(UUID value) { this.requestedByUserId = value; }
    public UUID getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(UUID value) { this.reviewedByUserId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant value) { this.reviewedAt = value; }
    public String getTopicTitle() { return topicTitle; }
    public void setTopicTitle(String value) { this.topicTitle = value; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String value) { this.ownerType = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { this.ownerId = value; }
    public String getOwnerLabel() { return ownerLabel; }
    public void setOwnerLabel(String value) { this.ownerLabel = value; }
}
