package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_states")
public class ContactMemoryStateEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private String status;
    private Instant lastInboundAt;
    private String lastSuccessCursor;
    private UUID currentProfileVersionId;
    private Integer retryCount;
    private Instant nextRetryAt;
    private String lastFailureCode;
    private String lastFailureMessage;
    private String leaseOwner;
    private UUID leaseToken;
    private Instant leaseAcquiredAt;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Instant getLastInboundAt() { return lastInboundAt; }
    public void setLastInboundAt(Instant value) { lastInboundAt = value; }
    public String getLastSuccessCursor() { return lastSuccessCursor; }
    public void setLastSuccessCursor(String value) { lastSuccessCursor = value; }
    public UUID getCurrentProfileVersionId() { return currentProfileVersionId; }
    public void setCurrentProfileVersionId(UUID value) { currentProfileVersionId = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { retryCount = value; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant value) { nextRetryAt = value; }
    public String getLastFailureCode() { return lastFailureCode; }
    public void setLastFailureCode(String value) { lastFailureCode = value; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public void setLastFailureMessage(String value) { lastFailureMessage = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public UUID getLeaseToken() { return leaseToken; }
    public void setLeaseToken(UUID value) { leaseToken = value; }
    public Instant getLeaseAcquiredAt() { return leaseAcquiredAt; }
    public void setLeaseAcquiredAt(Instant value) { leaseAcquiredAt = value; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant value) { leaseExpiresAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
