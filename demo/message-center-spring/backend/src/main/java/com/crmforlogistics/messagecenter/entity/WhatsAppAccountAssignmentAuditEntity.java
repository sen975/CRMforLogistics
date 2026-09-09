package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("whatsapp_account_assignment_audits")
public class WhatsAppAccountAssignmentAuditEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID channelAccountId;
    private UUID previousOwnerUserId;
    private UUID nextOwnerUserId;
    private UUID actorUserId;
    private String action;
    private String reason;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getPreviousOwnerUserId() { return previousOwnerUserId; }
    public void setPreviousOwnerUserId(UUID previousOwnerUserId) { this.previousOwnerUserId = previousOwnerUserId; }
    public UUID getNextOwnerUserId() { return nextOwnerUserId; }
    public void setNextOwnerUserId(UUID nextOwnerUserId) { this.nextOwnerUserId = nextOwnerUserId; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
