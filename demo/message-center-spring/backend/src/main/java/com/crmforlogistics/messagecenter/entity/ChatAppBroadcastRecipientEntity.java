package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "chatapp_broadcast_recipients", autoResultMap = true)
public class ChatAppBroadcastRecipientEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID broadcastId;
    private UUID contactId;
    private UUID contactIdentityId;
    private String recipientNameSnapshot;
    private String recipientNumberSnapshot;
    @TableField(value = "template_params_jsonb", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String templateParamsJsonb;
    private UUID messageId;
    private String providerMessageId;
    private String providerUniqueMessageId;
    private String status;
    private String failureReason;
    private Instant providerSentAt;
    private Instant lastReconciledAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBroadcastId() { return broadcastId; }
    public void setBroadcastId(UUID broadcastId) { this.broadcastId = broadcastId; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public UUID getContactIdentityId() { return contactIdentityId; }
    public void setContactIdentityId(UUID contactIdentityId) { this.contactIdentityId = contactIdentityId; }
    public String getRecipientNameSnapshot() { return recipientNameSnapshot; }
    public void setRecipientNameSnapshot(String recipientNameSnapshot) { this.recipientNameSnapshot = recipientNameSnapshot; }
    public String getRecipientNumberSnapshot() { return recipientNumberSnapshot; }
    public void setRecipientNumberSnapshot(String recipientNumberSnapshot) { this.recipientNumberSnapshot = recipientNumberSnapshot; }
    public String getTemplateParamsJsonb() { return templateParamsJsonb; }
    public void setTemplateParamsJsonb(String templateParamsJsonb) { this.templateParamsJsonb = templateParamsJsonb; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getProviderUniqueMessageId() { return providerUniqueMessageId; }
    public void setProviderUniqueMessageId(String providerUniqueMessageId) { this.providerUniqueMessageId = providerUniqueMessageId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getProviderSentAt() { return providerSentAt; }
    public void setProviderSentAt(Instant providerSentAt) { this.providerSentAt = providerSentAt; }
    public Instant getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(Instant lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
