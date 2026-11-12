package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("chatapp_broadcast_jobs")
public class ChatAppBroadcastJobEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID broadcastId;
    private String jobType;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextAttemptAt;
    private String leaseId;
    private String leaseWorkerId;
    private Instant leaseExpiresAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBroadcastId() { return broadcastId; }
    public void setBroadcastId(UUID broadcastId) { this.broadcastId = broadcastId; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public String getLeaseWorkerId() { return leaseWorkerId; }
    public void setLeaseWorkerId(String leaseWorkerId) { this.leaseWorkerId = leaseWorkerId; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
