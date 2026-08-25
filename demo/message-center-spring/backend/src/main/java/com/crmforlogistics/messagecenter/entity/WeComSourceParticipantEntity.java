package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_source_conversation_participants")
public class WeComSourceParticipantEntity {
    private UUID sourceConversationId;
    private UUID partyId;
    private String participantStatus;
    private Instant firstObservedAt;
    private Instant lastObservedAt;

    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID sourceConversationId) { this.sourceConversationId = sourceConversationId; }
    public UUID getPartyId() { return partyId; }
    public void setPartyId(UUID partyId) { this.partyId = partyId; }
    public String getParticipantStatus() { return participantStatus; }
    public void setParticipantStatus(String participantStatus) { this.participantStatus = participantStatus; }
    public Instant getFirstObservedAt() { return firstObservedAt; }
    public void setFirstObservedAt(Instant firstObservedAt) { this.firstObservedAt = firstObservedAt; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant lastObservedAt) { this.lastObservedAt = lastObservedAt; }
}
