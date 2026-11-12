package com.crmforlogistics.messagecenter.entity;

import java.time.Instant;
import java.util.UUID;

/** 助手会话里的一条消息（用户原话或助手回复）。追加写，不更新。 */
public class AssistantConversationMessageEntity {
    private UUID id;
    private UUID conversationId;
    private UUID userId;
    private String role;
    /** 仅 {@code role=assistant}：那一轮的终点（{@code QUESTION} / {@code EXECUTED} / …）。 */
    private String kind;
    private String text;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
