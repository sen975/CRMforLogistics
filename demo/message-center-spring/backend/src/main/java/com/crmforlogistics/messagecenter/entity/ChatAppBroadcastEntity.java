package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("chatapp_broadcasts")
public class ChatAppBroadcastEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID channelAccountId;
    private Long channelAccountVersion;
    private String name;
    private String templateCode;
    private String templateName;
    private String templateBodySnapshot;
    private String languageCode;
    private Integer recipientCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer processingCount;
    private String status;
    private String clientRequestId;
    private String requestFingerprint;
    private String providerGroupMessageId;
    private String providerRequestId;
    private String providerCode;
    private String lastReconciliationRequestId;
    private String lastReconciliationProviderCode;
    private String errorCode;
    private String errorMessage;
    private UUID retriesBroadcastId;
    private UUID createdByUserId;
    private Instant submittedAt;
    private Instant reconciledAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public Long getChannelAccountVersion() { return channelAccountVersion; }
    public void setChannelAccountVersion(Long channelAccountVersion) { this.channelAccountVersion = channelAccountVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getTemplateBodySnapshot() { return templateBodySnapshot; }
    public void setTemplateBodySnapshot(String templateBodySnapshot) { this.templateBodySnapshot = templateBodySnapshot; }
    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
    public Integer getRecipientCount() { return recipientCount; }
    public void setRecipientCount(Integer recipientCount) { this.recipientCount = recipientCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getProcessingCount() { return processingCount; }
    public void setProcessingCount(Integer processingCount) { this.processingCount = processingCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getProviderGroupMessageId() { return providerGroupMessageId; }
    public void setProviderGroupMessageId(String providerGroupMessageId) { this.providerGroupMessageId = providerGroupMessageId; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getLastReconciliationRequestId() { return lastReconciliationRequestId; }
    public void setLastReconciliationRequestId(String lastReconciliationRequestId) {
        this.lastReconciliationRequestId = lastReconciliationRequestId;
    }
    public String getLastReconciliationProviderCode() { return lastReconciliationProviderCode; }
    public void setLastReconciliationProviderCode(String lastReconciliationProviderCode) {
        this.lastReconciliationProviderCode = lastReconciliationProviderCode;
    }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getRetriesBroadcastId() { return retriesBroadcastId; }
    public void setRetriesBroadcastId(UUID retriesBroadcastId) { this.retriesBroadcastId = retriesBroadcastId; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(UUID createdByUserId) { this.createdByUserId = createdByUserId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Instant reconciledAt) { this.reconciledAt = reconciledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
