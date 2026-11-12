package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_profile_versions")
public class ContactProfileVersionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private Long version;
    private String content;
    private String sourceCursor;
    private UUID generationBatchId;
    private String model;
    private Integer inputMessageCount;
    private Integer evidenceCount;
    private Boolean isCurrent;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getSourceCursor() { return sourceCursor; }
    public void setSourceCursor(String value) { sourceCursor = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public Integer getInputMessageCount() { return inputMessageCount; }
    public void setInputMessageCount(Integer value) { inputMessageCount = value; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer value) { evidenceCount = value; }
    public Boolean getIsCurrent() { return isCurrent; }
    public void setIsCurrent(Boolean value) { isCurrent = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
