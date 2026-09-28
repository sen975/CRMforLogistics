package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 客户联系事件流水（表 {@code wecom_contact_events}）。
 *
 * <p>这是「客户关系变化」的唯一真源，也是回调在 1 秒内回包前的**落库对象** ——
 * 只有它提交成功或撞上 {@code dedupe_key} 唯一约束，才会向企微 ack。
 *
 * <p>字段语义见 V81 迁移注释；所有 {@code *Id} 值都是**密文**，大小写敏感，
 * 任何地方都不得做 {@code toLowerCase()} 之类的归一化。
 */
@TableName("wecom_contact_events")
public class WeComContactEventEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private UUID installationId;
    private String suiteId;
    private String authCorpId;
    private String event;
    private String changeType;
    private String wecomUserId;
    private String externalUserId;
    private String chatId;
    private String state;
    private String welcomeCode;
    private String failReason;
    private String providerSource;
    private Instant providerCreatedAt;
    private Instant receivedAt;
    private String dedupeKey;
    private String ingestStatus;
    private Integer attemptCount;
    private String lastError;
    private Instant processedAt;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }

    public String getSuiteId() { return suiteId; }
    public void setSuiteId(String suiteId) { this.suiteId = suiteId; }

    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public String getWecomUserId() { return wecomUserId; }
    public void setWecomUserId(String wecomUserId) { this.wecomUserId = wecomUserId; }

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getWelcomeCode() { return welcomeCode; }
    public void setWelcomeCode(String welcomeCode) { this.welcomeCode = welcomeCode; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }

    public String getProviderSource() { return providerSource; }
    public void setProviderSource(String providerSource) { this.providerSource = providerSource; }

    public Instant getProviderCreatedAt() { return providerCreatedAt; }
    public void setProviderCreatedAt(Instant providerCreatedAt) { this.providerCreatedAt = providerCreatedAt; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public String getIngestStatus() { return ingestStatus; }
    public void setIngestStatus(String ingestStatus) { this.ingestStatus = ingestStatus; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
