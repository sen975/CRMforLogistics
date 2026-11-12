package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_group_name_refresh_jobs")
public class WeComGroupNameRefreshJobEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID sourceConversationId;
    private String triggerSource;
    private UUID requestedByUserId;
    private String status;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private String leaseOwner;
    private Instant leaseUntil;
    private String errorCode;
    private String errorDiagnostic;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID value) { sourceConversationId = value; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String value) { triggerSource = value; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(UUID value) { requestedByUserId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorDiagnostic() { return errorDiagnostic; }
    public void setErrorDiagnostic(String value) { errorDiagnostic = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
}
