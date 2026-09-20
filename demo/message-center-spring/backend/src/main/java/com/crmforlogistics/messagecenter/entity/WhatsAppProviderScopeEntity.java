package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "whatsapp_provider_scopes", autoResultMap = true)
public class WhatsAppProviderScopeEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String provider;
    private String externalScopeId;
    private String wabaId;
    private String scopeType;
    private UUID ownerUserId;
    private String identityStatus;
    @TableField(value = "encrypted_config", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String encryptedConfig;
    private String status;
    private String displayName;
    private Instant lastTestedAt;
    private String lastTestStatus;
    private String lastTestErrorCode;
    private Instant lastSyncedAt;
    private String lastSyncStatus;
    private String lastSyncErrorCode;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalScopeId() { return externalScopeId; }
    public void setExternalScopeId(String externalScopeId) { this.externalScopeId = externalScopeId; }
    public String getWabaId() { return wabaId; }
    public void setWabaId(String wabaId) { this.wabaId = wabaId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getIdentityStatus() { return identityStatus; }
    public void setIdentityStatus(String identityStatus) { this.identityStatus = identityStatus; }
    public String getEncryptedConfig() { return encryptedConfig; }
    public void setEncryptedConfig(String encryptedConfig) { this.encryptedConfig = encryptedConfig; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Instant lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastTestStatus() { return lastTestStatus; }
    public void setLastTestStatus(String lastTestStatus) { this.lastTestStatus = lastTestStatus; }
    public String getLastTestErrorCode() { return lastTestErrorCode; }
    public void setLastTestErrorCode(String lastTestErrorCode) { this.lastTestErrorCode = lastTestErrorCode; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }
    public String getLastSyncErrorCode() { return lastSyncErrorCode; }
    public void setLastSyncErrorCode(String lastSyncErrorCode) { this.lastSyncErrorCode = lastSyncErrorCode; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
