package com.crmforlogistics.messagecenter.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 助手的一轮决策与执行流水。追加写，不更新。
 *
 * <p>{@code turnIndex} 可空，且**不是**「迟早都会有值」：它只在一次
 * {@code POST /api/assistant/messages} 内部有意义（第几次向模型发问，从 0 起）。
 * 确认 / 取消 / 过期发生在**之后的另一次请求**里，不属于任何轮次序列，那里留 null。
 */
public class AssistantActionAuditEntity {
    private UUID id;
    private UUID userId;
    private UUID conversationId;
    private String utterance;
    private String decision;
    private String toolName;
    private String argumentsJson;
    private String argumentsDigest;
    private String policy;
    private String outcome;
    private String errorCode;
    private String model;
    private Integer latencyMs;
    private Integer turnIndex;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getUtterance() { return utterance; }
    public void setUtterance(String utterance) { this.utterance = utterance; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(String argumentsJson) { this.argumentsJson = argumentsJson; }
    public String getArgumentsDigest() { return argumentsDigest; }
    public void setArgumentsDigest(String argumentsDigest) { this.argumentsDigest = argumentsDigest; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getTurnIndex() { return turnIndex; }
    public void setTurnIndex(Integer turnIndex) { this.turnIndex = turnIndex; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
