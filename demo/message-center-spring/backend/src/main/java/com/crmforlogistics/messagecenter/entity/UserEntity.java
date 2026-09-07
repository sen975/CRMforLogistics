package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("users")
public class UserEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String username;
    private String usernameNormalized;
    private String passwordHash;
    private String displayName;
    private String avatarObjectKey;
    private String avatarMimeType;
    private Long avatarSizeBytes;
    private Instant avatarUpdatedAt;
    private String status;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsernameNormalized() {
        return usernameNormalized;
    }

    public void setUsernameNormalized(String usernameNormalized) {
        this.usernameNormalized = usernameNormalized;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarObjectKey() { return avatarObjectKey; }
    public void setAvatarObjectKey(String avatarObjectKey) { this.avatarObjectKey = avatarObjectKey; }
    public String getAvatarMimeType() { return avatarMimeType; }
    public void setAvatarMimeType(String avatarMimeType) { this.avatarMimeType = avatarMimeType; }
    public Long getAvatarSizeBytes() { return avatarSizeBytes; }
    public void setAvatarSizeBytes(Long avatarSizeBytes) { this.avatarSizeBytes = avatarSizeBytes; }
    public Instant getAvatarUpdatedAt() { return avatarUpdatedAt; }
    public void setAvatarUpdatedAt(Instant avatarUpdatedAt) { this.avatarUpdatedAt = avatarUpdatedAt; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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
}
