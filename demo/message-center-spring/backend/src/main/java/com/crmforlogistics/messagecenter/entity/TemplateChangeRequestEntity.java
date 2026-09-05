package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "template_change_requests", autoResultMap = true)
public class TemplateChangeRequestEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID templateId;
    private String changeType;
    @TableField(value = "requested_payload_jsonb", jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String requestedPayloadJsonb;
    private Long baseVersion;
    private UUID requestedByUserId;
    private UUID requestedViaAccountId;
    private String status;
    private String idempotencyKey;
    private UUID reviewedByUserId;
    private String reviewReason;
    private Instant reviewedAt;
    private Instant executionStartedAt;
    private Instant executionCompletedAt;
    private String executionErrorCode;
    private String executionErrorMessage;
    private String providerRequestId;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getRequestedPayloadJsonb() { return requestedPayloadJsonb; }
    public void setRequestedPayloadJsonb(String requestedPayloadJsonb) { this.requestedPayloadJsonb = requestedPayloadJsonb; }
    public Long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Long baseVersion) { this.baseVersion = baseVersion; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(UUID requestedByUserId) { this.requestedByUserId = requestedByUserId; }
    public UUID getRequestedViaAccountId() { return requestedViaAccountId; }
    public void setRequestedViaAccountId(UUID requestedViaAccountId) { this.requestedViaAccountId = requestedViaAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(UUID reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getExecutionStartedAt() { return executionStartedAt; }
    public void setExecutionStartedAt(Instant executionStartedAt) { this.executionStartedAt = executionStartedAt; }
    public Instant getExecutionCompletedAt() { return executionCompletedAt; }
    public void setExecutionCompletedAt(Instant executionCompletedAt) { this.executionCompletedAt = executionCompletedAt; }
    public String getExecutionErrorCode() { return executionErrorCode; }
    public void setExecutionErrorCode(String executionErrorCode) { this.executionErrorCode = executionErrorCode; }
    public String getExecutionErrorMessage() { return executionErrorMessage; }
    public void setExecutionErrorMessage(String executionErrorMessage) { this.executionErrorMessage = executionErrorMessage; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
