package com.crmforlogistics.messagecenter.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 助手的一条「待确认动作」。
 *
 * <p>{@code argumentsJson} 保持 JSON 原文而不是解析成 {@code Map}：写入时不做规范化、
 * 读取时也不预解析，确认那一步才能拿到与「模型当时给出的」完全一致的内容重新校验。
 * 一旦在存取层做了规范化，确认就会静默地基于一份被改写过的参数执行。
 *
 * <p>{@code changesJson} 是<b>给用户看的</b>那一面（「改前 → 改后」），与
 * {@code argumentsJson}（给执行用的那一面）刻意分开：前者可以在确认卡片上被重新排版、
 * 甚至有一天换一种呈现，后者一旦被规范化就会让确认基于被改写过的参数执行。
 * 两者同时落库，是为了让「卡片当时显示了什么」在事后也能复原 —— 用户在卡片上按下的同意，
 * 针对的是他看到的那份文字。
 */
public class AssistantPendingActionEntity {
    private UUID id;
    private UUID userId;
    private UUID conversationId;
    private String toolName;
    private String argumentsJson;
    private String summary;
    private String changesJson;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant decidedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(String argumentsJson) { this.argumentsJson = argumentsJson; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getChangesJson() { return changesJson; }
    public void setChangesJson(String changesJson) { this.changesJson = changesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
