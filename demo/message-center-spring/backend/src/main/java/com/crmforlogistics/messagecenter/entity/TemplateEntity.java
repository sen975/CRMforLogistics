package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "message_templates", autoResultMap = true)
public class TemplateEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID channelAccountId;
    private UUID providerScopeId;
    private UUID createdByUserId;
    private String providerTemplateId;
    private String languageCode;
    private String name;
    private String remark;
    private String body;
    private String status;
    private String category;
    private String templateType;
    @TableField(value = "components_jsonb", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String componentsJsonb;
    @TableField(value = "examples_jsonb", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String examplesJsonb;
    private Integer messageSendTtlSeconds;
    private Boolean allowSend;
    private String providerAuditStatus;
    private String rejectionReason;
    private String qualityScore;
    private Instant providerUpdatedAt;
    @TableField(value = "metadata_jsonb", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String metadataJsonb;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public void setChannelAccountId(UUID channelAccountId) {
        this.channelAccountId = channelAccountId;
    }

    public UUID getProviderScopeId() {
        return providerScopeId;
    }

    public void setProviderScopeId(UUID providerScopeId) {
        this.providerScopeId = providerScopeId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getProviderTemplateId() {
        return providerTemplateId;
    }

    public void setProviderTemplateId(String providerTemplateId) {
        this.providerTemplateId = providerTemplateId;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTemplateType() {
        return templateType;
    }

    public void setTemplateType(String templateType) {
        this.templateType = templateType;
    }

    public String getComponentsJsonb() {
        return componentsJsonb;
    }

    public void setComponentsJsonb(String componentsJsonb) {
        this.componentsJsonb = componentsJsonb;
    }

    public String getExamplesJsonb() {
        return examplesJsonb;
    }

    public void setExamplesJsonb(String examplesJsonb) {
        this.examplesJsonb = examplesJsonb;
    }

    public Integer getMessageSendTtlSeconds() {
        return messageSendTtlSeconds;
    }

    public void setMessageSendTtlSeconds(Integer messageSendTtlSeconds) {
        this.messageSendTtlSeconds = messageSendTtlSeconds;
    }

    public Boolean getAllowSend() {
        return allowSend;
    }

    public void setAllowSend(Boolean allowSend) {
        this.allowSend = allowSend;
    }

    public String getProviderAuditStatus() {
        return providerAuditStatus;
    }

    public void setProviderAuditStatus(String providerAuditStatus) {
        this.providerAuditStatus = providerAuditStatus;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(String qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Instant getProviderUpdatedAt() {
        return providerUpdatedAt;
    }

    public void setProviderUpdatedAt(Instant providerUpdatedAt) {
        this.providerUpdatedAt = providerUpdatedAt;
    }

    public String getMetadataJsonb() {
        return metadataJsonb;
    }

    public void setMetadataJsonb(String metadataJsonb) {
        this.metadataJsonb = metadataJsonb;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
