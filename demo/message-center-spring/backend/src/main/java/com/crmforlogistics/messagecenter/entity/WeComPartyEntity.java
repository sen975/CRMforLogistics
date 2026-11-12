package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_parties")
public class WeComPartyEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID installationId;
    private String partyType;
    private String providerPartyId;
    private String displayName;
    private String avatarUrl;
    private String profileStatus;
    private String profileErrorCode;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }
    public String getPartyType() { return partyType; }
    public void setPartyType(String partyType) { this.partyType = partyType; }
    public String getProviderPartyId() { return providerPartyId; }
    public void setProviderPartyId(String providerPartyId) { this.providerPartyId = providerPartyId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getProfileStatus() { return profileStatus; }
    public void setProfileStatus(String profileStatus) { this.profileStatus = profileStatus; }
    public String getProfileErrorCode() { return profileErrorCode; }
    public void setProfileErrorCode(String profileErrorCode) { this.profileErrorCode = profileErrorCode; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
