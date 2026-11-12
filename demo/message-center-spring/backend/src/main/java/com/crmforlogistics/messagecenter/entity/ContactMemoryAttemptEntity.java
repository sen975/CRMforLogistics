package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("contact_memory_attempts")
public class ContactMemoryAttemptEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID contactId;
    private UUID ownerUserId;
    private UUID generationBatchId;
    private String inputCursor;
    private String outputCursor;
    private String status;
    private String outcome;
    private String failureCode;
    private String failureMessage;
    private String model;
    private Long durationMs;
    private Integer inputMessageCount;
    private Integer outputLabelChangeCount;
    private Boolean profileChanged;
    private Integer retryCount;
    private Instant createdAt;
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { contactId = value; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID value) { ownerUserId = value; }
    public UUID getGenerationBatchId() { return generationBatchId; }
    public void setGenerationBatchId(UUID value) { generationBatchId = value; }
    public String getInputCursor() { return inputCursor; }
    public void setInputCursor(String value) { inputCursor = value; }
    public String getOutputCursor() { return outputCursor; }
    public void setOutputCursor(String value) { outputCursor = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String value) { failureMessage = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public Integer getInputMessageCount() { return inputMessageCount; }
    public void setInputMessageCount(Integer value) { inputMessageCount = value; }
    public Integer getOutputLabelChangeCount() { return outputLabelChangeCount; }
    public void setOutputLabelChangeCount(Integer value) { outputLabelChangeCount = value; }
    public Boolean getProfileChanged() { return profileChanged; }
    public void setProfileChanged(Boolean value) { profileChanged = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { retryCount = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
}
