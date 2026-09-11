package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_observations")
public class ContactMemoryObservationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private String category;
    private String normalizedKey;
    private String observedValue;
    private String polarity;
    private java.math.BigDecimal confidence;
    private String status;
    private String sourceCursor;
    private UUID generationBatchId;
    private Instant observedAt;
    private Instant expiresAt;
    private UUID promotedFactId;
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
    public String getObservedValue() { return observedValue; }
    public void setObservedValue(String value) { observedValue = value; }
    public String getPolarity() { return polarity; }
    public void setPolarity(String value) { polarity = value; }
    public java.math.BigDecimal getConfidence() { return confidence; }
    public void setConfidence(java.math.BigDecimal value) { confidence = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getSourceCursor() { return sourceCursor; }
    public void setSourceCursor(String value) { sourceCursor = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant value) { observedAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public UUID getPromotedFactId() { return promotedFactId; }
    public void setPromotedFactId(UUID value) { promotedFactId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
