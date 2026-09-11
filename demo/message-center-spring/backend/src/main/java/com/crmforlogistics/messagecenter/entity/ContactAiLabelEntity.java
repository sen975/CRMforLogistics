package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_ai_labels")
public class ContactAiLabelEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private String category;
    private String normalizedName;
    private String displayName;
    private String colorToken;
    private String status;
    private java.math.BigDecimal confidence;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant lastEvidenceAt;
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
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String value) { normalizedName = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getColorToken() { return colorToken; }
    public void setColorToken(String value) { colorToken = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public java.math.BigDecimal getConfidence() { return confidence; }
    public void setConfidence(java.math.BigDecimal value) { confidence = value; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant value) { firstSeenAt = value; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant value) { lastSeenAt = value; }
    public Instant getLastEvidenceAt() { return lastEvidenceAt; }
    public void setLastEvidenceAt(Instant value) { lastEvidenceAt = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
