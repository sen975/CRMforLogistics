package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_facts")
public class ContactMemoryFactEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private String category;
    private String normalizedKey;
    private String normalizedValue;
    private String displayValue;
    private String polarity;
    private String status;
    private java.math.BigDecimal confidence;
    private Integer evidenceCount;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant lastConfirmedAt;
    private Instant staleAt;
    private Instant invalidatedAt;
    private UUID generationBatchId;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String value) { normalizedKey = value; }
    public String getNormalizedValue() { return normalizedValue; }
    public void setNormalizedValue(String value) { normalizedValue = value; }
    public String getDisplayValue() { return displayValue; }
    public void setDisplayValue(String value) { displayValue = value; }
    public String getPolarity() { return polarity; }
    public void setPolarity(String value) { polarity = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public java.math.BigDecimal getConfidence() { return confidence; }
    public void setConfidence(java.math.BigDecimal value) { confidence = value; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer value) { evidenceCount = value; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant value) { firstSeenAt = value; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant value) { lastSeenAt = value; }
    public Instant getLastConfirmedAt() { return lastConfirmedAt; }
    public void setLastConfirmedAt(Instant value) { lastConfirmedAt = value; }
    public Instant getStaleAt() { return staleAt; }
    public void setStaleAt(Instant value) { staleAt = value; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(Instant value) { invalidatedAt = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
