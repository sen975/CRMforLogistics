package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_source_conversations")
public class WeComSourceConversationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID installationId;
    private String providerConversationKey;
    private String conversationType;
    private String groupKind;
    private String displayName;
    private String avatarUrl;
    private String nameResolutionStatus;
    private Instant lastNameCheckedAt;
    private Instant nameNextRetryAt;
    private String nameErrorCode;
    private UUID contactIdentityId;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }
    public String getProviderConversationKey() { return providerConversationKey; }
    public void setProviderConversationKey(String providerConversationKey) { this.providerConversationKey = providerConversationKey; }
    public String getConversationType() { return conversationType; }
    public void setConversationType(String conversationType) { this.conversationType = conversationType; }
    public String getGroupKind() { return groupKind; }
    public void setGroupKind(String groupKind) { this.groupKind = groupKind; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getNameResolutionStatus() { return nameResolutionStatus; }
    public void setNameResolutionStatus(String nameResolutionStatus) { this.nameResolutionStatus = nameResolutionStatus; }
    public Instant getLastNameCheckedAt() { return lastNameCheckedAt; }
    public void setLastNameCheckedAt(Instant lastNameCheckedAt) { this.lastNameCheckedAt = lastNameCheckedAt; }
    public Instant getNameNextRetryAt() { return nameNextRetryAt; }
    public void setNameNextRetryAt(Instant nameNextRetryAt) { this.nameNextRetryAt = nameNextRetryAt; }
    public String getNameErrorCode() { return nameErrorCode; }
    public void setNameErrorCode(String nameErrorCode) { this.nameErrorCode = nameErrorCode; }
    public UUID getContactIdentityId() { return contactIdentityId; }
    public void setContactIdentityId(UUID contactIdentityId) { this.contactIdentityId = contactIdentityId; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
