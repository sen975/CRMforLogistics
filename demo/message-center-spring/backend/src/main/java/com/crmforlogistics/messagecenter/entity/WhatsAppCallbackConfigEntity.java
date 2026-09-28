package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("whatsapp_cams_callback_configs")
public class WhatsAppCallbackConfigEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID providerScopeId;
    private UUID channelAccountId;
    private String level;
    private String desiredUpCallbackUrl;
    private String desiredStatusCallbackUrl;
    private String httpFlag;
    private String queueFlag;
    private String providerState;
    private String lastApplyStatus;
    private UUID applyToken;
    private Instant applyStartedAt;
    private String lastProviderRequestId;
    private String lastErrorCode;
    private Instant lastAppliedAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProviderScopeId() { return providerScopeId; }
    public void setProviderScopeId(UUID providerScopeId) { this.providerScopeId = providerScopeId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getDesiredUpCallbackUrl() { return desiredUpCallbackUrl; }
    public void setDesiredUpCallbackUrl(String desiredUpCallbackUrl) { this.desiredUpCallbackUrl = desiredUpCallbackUrl; }
    public String getDesiredStatusCallbackUrl() { return desiredStatusCallbackUrl; }
    public void setDesiredStatusCallbackUrl(String desiredStatusCallbackUrl) { this.desiredStatusCallbackUrl = desiredStatusCallbackUrl; }
    public String getHttpFlag() { return httpFlag; }
    public void setHttpFlag(String httpFlag) { this.httpFlag = httpFlag; }
    public String getQueueFlag() { return queueFlag; }
    public void setQueueFlag(String queueFlag) { this.queueFlag = queueFlag; }
    public String getProviderState() { return providerState; }
    public void setProviderState(String providerState) { this.providerState = providerState; }
    public String getLastApplyStatus() { return lastApplyStatus; }
    public void setLastApplyStatus(String lastApplyStatus) { this.lastApplyStatus = lastApplyStatus; }
    public UUID getApplyToken() { return applyToken; }
    public void setApplyToken(UUID applyToken) { this.applyToken = applyToken; }
    public Instant getApplyStartedAt() { return applyStartedAt; }
    public void setApplyStartedAt(Instant applyStartedAt) { this.applyStartedAt = applyStartedAt; }
    public String getLastProviderRequestId() { return lastProviderRequestId; }
    public void setLastProviderRequestId(String lastProviderRequestId) { this.lastProviderRequestId = lastProviderRequestId; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public Instant getLastAppliedAt() { return lastAppliedAt; }
    public void setLastAppliedAt(Instant lastAppliedAt) { this.lastAppliedAt = lastAppliedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
