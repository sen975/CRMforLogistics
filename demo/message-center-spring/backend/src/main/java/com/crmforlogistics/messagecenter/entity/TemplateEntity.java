package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("message_templates")
public class TemplateEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID channelAccountId;
    private String providerTemplateId;
    private String languageCode;
    private String name;
    private String body;
    private String status;
    private Instant providerUpdatedAt;
    private String metadataJsonb;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;

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
}
