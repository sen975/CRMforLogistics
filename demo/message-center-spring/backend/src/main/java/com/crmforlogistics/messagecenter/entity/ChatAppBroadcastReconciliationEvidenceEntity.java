package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("chatapp_broadcast_reconciliation_evidence")
public class ChatAppBroadcastReconciliationEvidenceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID broadcastId;
    private UUID jobId;
    private String providerRequestId;
    private Integer pageNumber;
    private Integer rowNumber;
    private String userNumber;
    private String providerMessageId;
    private String providerUniqueMessageId;
    private String providerStatus;
    private String failureReason;
    private UUID matchedRecipientId;
    private String diagnosticCode;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBroadcastId() { return broadcastId; }
    public void setBroadcastId(UUID broadcastId) { this.broadcastId = broadcastId; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
    public String getUserNumber() { return userNumber; }
    public void setUserNumber(String userNumber) { this.userNumber = userNumber; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getProviderUniqueMessageId() { return providerUniqueMessageId; }
    public void setProviderUniqueMessageId(String providerUniqueMessageId) {
        this.providerUniqueMessageId = providerUniqueMessageId;
    }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public UUID getMatchedRecipientId() { return matchedRecipientId; }
    public void setMatchedRecipientId(UUID matchedRecipientId) { this.matchedRecipientId = matchedRecipientId; }
    public String getDiagnosticCode() { return diagnosticCode; }
    public void setDiagnosticCode(String diagnosticCode) { this.diagnosticCode = diagnosticCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
