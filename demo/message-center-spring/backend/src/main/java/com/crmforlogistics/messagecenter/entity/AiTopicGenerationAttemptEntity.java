package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_topic_generation_attempts")
public class AiTopicGenerationAttemptEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID generationJobId;
    private UUID contactId;
    private String ownerType;
    private UUID ownerId;
    private Integer attemptNumber;
    private String providerHost;
    private String model;
    private String requestPayload;
    private Boolean requestTruncated;
    private Integer responseStatus;
    private String responseHeaders;
    private String rawResponseBody;
    private Boolean responseTruncated;
    private String parsedResponse;
    private String stage;
    private String status;
    private String errorCode;
    private String errorDiagnostic;
    private Long durationMs;
    private Instant createdAt;
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGenerationJobId() { return generationJobId; }
    public void setGenerationJobId(UUID value) { this.generationJobId = value; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID value) { this.contactId = value; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String value) { this.ownerType = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { this.ownerId = value; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer value) { this.attemptNumber = value; }
    public String getProviderHost() { return providerHost; }
    public void setProviderHost(String value) { this.providerHost = value; }
    public String getModel() { return model; }
    public void setModel(String value) { this.model = value; }
    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String value) { this.requestPayload = value; }
    public Boolean getRequestTruncated() { return requestTruncated; }
    public void setRequestTruncated(Boolean value) { this.requestTruncated = value; }
    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer value) { this.responseStatus = value; }
    public String getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(String value) { this.responseHeaders = value; }
    public String getRawResponseBody() { return rawResponseBody; }
    public void setRawResponseBody(String value) { this.rawResponseBody = value; }
    public Boolean getResponseTruncated() { return responseTruncated; }
    public void setResponseTruncated(Boolean value) { this.responseTruncated = value; }
    public String getParsedResponse() { return parsedResponse; }
    public void setParsedResponse(String value) { this.parsedResponse = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { this.stage = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { this.errorCode = value; }
    public String getErrorDiagnostic() { return errorDiagnostic; }
    public void setErrorDiagnostic(String value) { this.errorDiagnostic = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { this.durationMs = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { this.completedAt = value; }
}
