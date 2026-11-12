package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("whatsapp_authorization_attempts")
public class WhatsAppAuthorizationAttemptEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private String stateHash;
    private String status;
    private String onboardingMode;
    private String accountName;
    private String accountRemark;
    private Instant expiresAt;
    private Instant consumedAt;
    private String completedWabaId;
    private String completedPhoneNumber;
    private String completedPhoneNumberId;
    private UUID completedAccountId;
    private String failureStage;
    private String failureCode;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String stateHash) { this.stateHash = stateHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOnboardingMode() { return onboardingMode; }
    public void setOnboardingMode(String onboardingMode) { this.onboardingMode = onboardingMode; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAccountRemark() { return accountRemark; }
    public void setAccountRemark(String accountRemark) { this.accountRemark = accountRemark; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public String getCompletedWabaId() { return completedWabaId; }
    public void setCompletedWabaId(String completedWabaId) { this.completedWabaId = completedWabaId; }
    public String getCompletedPhoneNumber() { return completedPhoneNumber; }
    public void setCompletedPhoneNumber(String completedPhoneNumber) { this.completedPhoneNumber = completedPhoneNumber; }
    public String getCompletedPhoneNumberId() { return completedPhoneNumberId; }
    public void setCompletedPhoneNumberId(String completedPhoneNumberId) { this.completedPhoneNumberId = completedPhoneNumberId; }
    public UUID getCompletedAccountId() { return completedAccountId; }
    public void setCompletedAccountId(UUID completedAccountId) { this.completedAccountId = completedAccountId; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
