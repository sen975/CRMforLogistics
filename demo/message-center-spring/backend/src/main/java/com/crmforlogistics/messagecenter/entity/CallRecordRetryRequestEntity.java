package com.crmforlogistics.messagecenter.entity;

import java.time.Instant;
import java.util.UUID;

public class CallRecordRetryRequestEntity {
    private UUID ownerId;
    private UUID callRecordId;
    private String clientRequestId;
    private Instant createdAt;

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public UUID getCallRecordId() { return callRecordId; }
    public void setCallRecordId(UUID callRecordId) { this.callRecordId = callRecordId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
