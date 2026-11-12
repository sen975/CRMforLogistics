package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_trigger_events")
public class ContactMemoryTriggerEventEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID messageId;
    private UUID contactId;
    private UUID ownerUserId;
    private Long ingestSequence;
    private Instant occurredAt;
    private Instant receivedAt;
    private String status;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private String leaseOwner;
    private UUID leaseToken;
    private Instant leaseAcquiredAt;
    private Instant leaseExpiresAt;
    private String lastFailureCode;
    private String lastFailureMessage;
    private Instant createdAt;
    private Instant appliedAt;
    private Instant memoryAppliedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID value) { messageId = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public Long getIngestSequence() { return ingestSequence; }
    public void setIngestSequence(Long value) { ingestSequence = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { occurredAt = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant value) { receivedAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public UUID getLeaseToken() { return leaseToken; }
    public void setLeaseToken(UUID value) { leaseToken = value; }
    public Instant getLeaseAcquiredAt() { return leaseAcquiredAt; }
    public void setLeaseAcquiredAt(Instant value) { leaseAcquiredAt = value; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant value) { leaseExpiresAt = value; }
    public String getLastFailureCode() { return lastFailureCode; }
    public void setLastFailureCode(String value) { lastFailureCode = value; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public void setLastFailureMessage(String value) { lastFailureMessage = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant value) { appliedAt = value; }
    public Instant getMemoryAppliedAt() { return memoryAppliedAt; }
    public void setMemoryAppliedAt(Instant value) { memoryAppliedAt = value; }
}
