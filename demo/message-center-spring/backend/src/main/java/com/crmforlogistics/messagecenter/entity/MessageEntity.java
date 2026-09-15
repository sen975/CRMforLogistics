package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("messages")
public class MessageEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID conversationId;
    private UUID channelAccountId;
    private Long channelAccountVersion;
    private UUID sourceEventId;
    private String providerMessageId;
    private String clientRequestId;
    private String direction;
    private String messageKind;
    private String subject;
    private String bodyText;
    private String bodyHtml;
    private Instant occurredAt;
    private Instant receivedAt;
    private Long ingestSequence;
    private Boolean countsAsUnread;
    private String currentStatus;
    private Instant currentStatusAt;
    private UUID createdByUserId;
    private String metadataJsonb;
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public void setChannelAccountId(UUID channelAccountId) {
        this.channelAccountId = channelAccountId;
    }

    public Long getChannelAccountVersion() {
        return channelAccountVersion;
    }

    public void setChannelAccountVersion(Long channelAccountVersion) {
        this.channelAccountVersion = channelAccountVersion;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(UUID sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getMessageKind() {
        return messageKind;
    }

    public void setMessageKind(String messageKind) {
        this.messageKind = messageKind;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public void setBodyHtml(String bodyHtml) {
        this.bodyHtml = bodyHtml;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Long getIngestSequence() {
        return ingestSequence;
    }

    public void setIngestSequence(Long ingestSequence) {
        this.ingestSequence = ingestSequence;
    }

    public Boolean getCountsAsUnread() {
        return countsAsUnread;
    }

    public void setCountsAsUnread(Boolean countsAsUnread) {
        this.countsAsUnread = countsAsUnread;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public Instant getCurrentStatusAt() {
        return currentStatusAt;
    }

    public void setCurrentStatusAt(Instant currentStatusAt) {
        this.currentStatusAt = currentStatusAt;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getMetadataJsonb() {
        return metadataJsonb;
    }

    public void setMetadataJsonb(String metadataJsonb) {
        this.metadataJsonb = metadataJsonb;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
