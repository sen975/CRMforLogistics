package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.time.Instant;
import java.util.UUID;

@TableName(value = "channel_accounts", autoResultMap = true)
public class ChannelAccountEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID ownerUserId;
    private String channelType;
    private String name;
    private String remark;
    private String accountIdentifier;
    private String accountIdentifierNormalized;
    private String authStatus;
    private String syncStatus;
    private String onboardingMode;
    private String phoneVerificationStatus;
    private String providerPhoneStatus;
    @TableField(value = "encrypted_config", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String encryptedConfig;
    private UUID providerScopeId;
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

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
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

    public String getAccountIdentifier() {
        return accountIdentifier;
    }

    public void setAccountIdentifier(String accountIdentifier) {
        this.accountIdentifier = accountIdentifier;
    }

    public String getAccountIdentifierNormalized() {
        return accountIdentifierNormalized;
    }

    public void setAccountIdentifierNormalized(String accountIdentifierNormalized) {
        this.accountIdentifierNormalized = accountIdentifierNormalized;
    }

    public String getAuthStatus() {
        return authStatus;
    }

    public void setAuthStatus(String authStatus) {
        this.authStatus = authStatus;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getOnboardingMode() {
        return onboardingMode;
    }

    public void setOnboardingMode(String onboardingMode) {
        this.onboardingMode = onboardingMode;
    }

    public String getPhoneVerificationStatus() {
        return phoneVerificationStatus;
    }

    public void setPhoneVerificationStatus(String phoneVerificationStatus) {
        this.phoneVerificationStatus = phoneVerificationStatus;
    }

    public String getProviderPhoneStatus() {
        return providerPhoneStatus;
    }

    public void setProviderPhoneStatus(String providerPhoneStatus) {
        this.providerPhoneStatus = providerPhoneStatus;
    }

    public String getEncryptedConfig() {
        return encryptedConfig;
    }

    public void setEncryptedConfig(String encryptedConfig) {
        this.encryptedConfig = encryptedConfig;
    }

    public UUID getProviderScopeId() {
        return providerScopeId;
    }

    public void setProviderScopeId(UUID providerScopeId) {
        this.providerScopeId = providerScopeId;
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
