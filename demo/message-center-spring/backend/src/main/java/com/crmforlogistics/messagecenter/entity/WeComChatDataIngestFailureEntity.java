package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_chatdata_ingest_failures")
public class WeComChatDataIngestFailureEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID installationId;
    private String cursorKey;
    private String cursorFrom;
    private String cursorTo;
    private String msgidDigest;
    private String failureStage;
    private String errorCode;
    private Integer retryCount;
    private Instant firstFailedAt;
    private Instant lastFailedAt;
    private Instant resolvedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }
    public String getCursorKey() { return cursorKey; }
    public void setCursorKey(String cursorKey) { this.cursorKey = cursorKey; }
    public String getCursorFrom() { return cursorFrom; }
    public void setCursorFrom(String cursorFrom) { this.cursorFrom = cursorFrom; }
    public String getCursorTo() { return cursorTo; }
    public void setCursorTo(String cursorTo) { this.cursorTo = cursorTo; }
    public String getMsgidDigest() { return msgidDigest; }
    public void setMsgidDigest(String msgidDigest) { this.msgidDigest = msgidDigest; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public void setFirstFailedAt(Instant firstFailedAt) { this.firstFailedAt = firstFailedAt; }
    public Instant getLastFailedAt() { return lastFailedAt; }
    public void setLastFailedAt(Instant lastFailedAt) { this.lastFailedAt = lastFailedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
