package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_fact_evidence")
public class ContactMemoryFactEvidenceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID factId;
    private UUID contactId;
    private UUID ownerUserId;
    private String evidenceType;
    private UUID evidenceId;
    private String evidenceExcerpt;
    private UUID generationBatchId;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getFactId() { return factId; }
    public void setFactId(UUID value) { factId = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String value) { evidenceType = value; }
    public UUID getEvidenceId() { return evidenceId; }
    public void setEvidenceId(UUID value) { evidenceId = value; }
    public String getEvidenceExcerpt() { return evidenceExcerpt; }
    public void setEvidenceExcerpt(String value) { evidenceExcerpt = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
