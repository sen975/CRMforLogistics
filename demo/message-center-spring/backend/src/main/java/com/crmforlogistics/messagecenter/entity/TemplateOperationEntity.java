package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("template_operations")
public class TemplateOperationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID channelAccountId;
    private UUID templateId;
    private UUID changeRequestId;
    private String idempotencyKey;
    private String operationType;
    private String providerTemplateId;
    private String languageCode;
    private String requestedSnapshotJsonb;
    private String operationStatus;
    private String providerRequestId;
    private String providerCode;
    private String errorCode;
    private String errorMessage;
    private Instant nextReconcileAt;
    private Integer reconcileAttemptCount;
    private UUID actorUserId;
    private String traceId;
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant startedAt;
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
    public UUID getChangeRequestId() { return changeRequestId; }
    public void setChangeRequestId(UUID changeRequestId) { this.changeRequestId = changeRequestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getProviderTemplateId() { return providerTemplateId; }
    public void setProviderTemplateId(String providerTemplateId) { this.providerTemplateId = providerTemplateId; }
    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
    public String getRequestedSnapshotJsonb() { return requestedSnapshotJsonb; }
    public void setRequestedSnapshotJsonb(String requestedSnapshotJsonb) { this.requestedSnapshotJsonb = requestedSnapshotJsonb; }
    public String getOperationStatus() { return operationStatus; }
    public void setOperationStatus(String operationStatus) { this.operationStatus = operationStatus; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getNextReconcileAt() { return nextReconcileAt; }
    public void setNextReconcileAt(Instant nextReconcileAt) { this.nextReconcileAt = nextReconcileAt; }
    public Integer getReconcileAttemptCount() { return reconcileAttemptCount; }
    public void setReconcileAttemptCount(Integer reconcileAttemptCount) { this.reconcileAttemptCount = reconcileAttemptCount; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
