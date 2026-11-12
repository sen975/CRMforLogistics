package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("whatsapp_cams_callback_audits")
public class WhatsAppCallbackAuditEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID providerScopeId;
    private UUID channelAccountId;
    private UUID actorUserId;
    private String level;
    private String action;
    private String desiredUrlHostHash;
    private String desiredUrlPathHash;
    private boolean desiredUrlHttps;
    private long expectedVersion;
    private String result;
    private String errorCode;
    private String providerRequestId;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProviderScopeId() { return providerScopeId; }
    public void setProviderScopeId(UUID providerScopeId) { this.providerScopeId = providerScopeId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDesiredUrlHostHash() { return desiredUrlHostHash; }
    public void setDesiredUrlHostHash(String value) { this.desiredUrlHostHash = value; }
    public String getDesiredUrlPathHash() { return desiredUrlPathHash; }
    public void setDesiredUrlPathHash(String value) { this.desiredUrlPathHash = value; }
    public boolean isDesiredUrlHttps() { return desiredUrlHttps; }
    public void setDesiredUrlHttps(boolean value) { this.desiredUrlHttps = value; }
    public long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(long value) { this.expectedVersion = value; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String value) { this.providerRequestId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
