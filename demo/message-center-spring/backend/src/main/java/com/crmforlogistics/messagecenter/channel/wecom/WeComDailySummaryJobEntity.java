package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@TableName("wecom_daily_summary_jobs")
public class WeComDailySummaryJobEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String installationId;
    private String authCorpId;
    private LocalDate summaryDay;
    private String userId;
    private String externalUserId;
    private Integer sliceStart;
    private Integer sliceEnd;
    private Integer messageCount;
    private String messageDigest;
    private String status;
    private String wecomJobId;
    private String batchSummary;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private Instant deadlineAt;
    private String leaseOwner;
    private Instant leaseUntil;
    private String lastErrorCode;
    private String failureState;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getInstallationId() { return installationId; }
    public void setInstallationId(String installationId) { this.installationId = installationId; }

    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }

    public LocalDate getSummaryDay() { return summaryDay; }
    public void setSummaryDay(LocalDate summaryDay) { this.summaryDay = summaryDay; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }

    public Integer getSliceStart() { return sliceStart; }
    public void setSliceStart(Integer sliceStart) { this.sliceStart = sliceStart; }

    public Integer getSliceEnd() { return sliceEnd; }
    public void setSliceEnd(Integer sliceEnd) { this.sliceEnd = sliceEnd; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public String getMessageDigest() { return messageDigest; }
    public void setMessageDigest(String messageDigest) { this.messageDigest = messageDigest; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getWecomJobId() { return wecomJobId; }
    public void setWecomJobId(String wecomJobId) { this.wecomJobId = wecomJobId; }

    public String getBatchSummary() { return batchSummary; }
    public void setBatchSummary(String batchSummary) { this.batchSummary = batchSummary; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }

    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }

    public String getFailureState() { return failureState; }
    public void setFailureState(String failureState) { this.failureState = failureState; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
