package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_topic_owner_activity")
public class AiTopicOwnerActivityEntity {
    @TableId
    private String ownerKey;
    private String ownerType;
    private UUID ownerId;
    private Instant latestEventAt;
    private Instant quietDeadline;
    private Long activityVersion;
    private String status;
    private String leaseOwner;
    private Instant leaseUntil;
    private UUID linkedGenerationJobId;
    private Instant updatedAt;

    public String getOwnerKey() { return ownerKey; }
    public void setOwnerKey(String value) { this.ownerKey = value; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String value) { this.ownerType = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { this.ownerId = value; }
    public Instant getLatestEventAt() { return latestEventAt; }
    public void setLatestEventAt(Instant value) { this.latestEventAt = value; }
    public Instant getQuietDeadline() { return quietDeadline; }
    public void setQuietDeadline(Instant value) { this.quietDeadline = value; }
    public Long getActivityVersion() { return activityVersion; }
    public void setActivityVersion(Long value) { this.activityVersion = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { this.leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { this.leaseUntil = value; }
    public UUID getLinkedGenerationJobId() { return linkedGenerationJobId; }
    public void setLinkedGenerationJobId(UUID value) { this.linkedGenerationJobId = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
