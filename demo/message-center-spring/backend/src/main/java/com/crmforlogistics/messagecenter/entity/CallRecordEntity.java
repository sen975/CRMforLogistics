package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("call_records")
public class CallRecordEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String contactAnchorPointId;
    private String phonePointId;
    private String direction;
    private Instant occurredAt;
    private Instant createdAt;
    private String createdBy;
    private String clientRequestId;
    private String note;

    private String audioRelativePath;
    private String audioOriginalFileName;
    private Long audioSizeBytes;
    private String audioSha256;
    private String audioContentType;
    private Double audioDurationSeconds;
    private String audioObjectKey;

    private String transcriptionState;
    private String transcriptionModel;
    private Integer transcriptionAttempts;
    private String transcriptionLeaseId;
    private String transcriptionLeaseWorkerId;
    private Instant transcriptionLeaseExpiresAt;
    private Instant transcriptionNextAttemptAt;
    private String transcriptionResultModel;
    private Double transcriptionResultDurationSeconds;
    private String transcriptionResultOriginalText;
    private String transcriptionResultSegments;
    private Instant transcriptionResultCompletedAt;
    private String transcriptionErrorCode;
    private String transcriptionErrorMessage;
    private Boolean transcriptionErrorRetryable;

    private UUID currentRevisionId;
    private Long version;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getContactAnchorPointId() { return contactAnchorPointId; }
    public void setContactAnchorPointId(String contactAnchorPointId) { this.contactAnchorPointId = contactAnchorPointId; }

    public String getPhonePointId() { return phonePointId; }
    public void setPhonePointId(String phonePointId) { this.phonePointId = phonePointId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getAudioRelativePath() { return audioRelativePath; }
    public void setAudioRelativePath(String audioRelativePath) { this.audioRelativePath = audioRelativePath; }

    public String getAudioOriginalFileName() { return audioOriginalFileName; }
    public void setAudioOriginalFileName(String audioOriginalFileName) { this.audioOriginalFileName = audioOriginalFileName; }

    public Long getAudioSizeBytes() { return audioSizeBytes; }
    public void setAudioSizeBytes(Long audioSizeBytes) { this.audioSizeBytes = audioSizeBytes; }

    public String getAudioSha256() { return audioSha256; }
    public void setAudioSha256(String audioSha256) { this.audioSha256 = audioSha256; }

    public String getAudioContentType() { return audioContentType; }
    public void setAudioContentType(String audioContentType) { this.audioContentType = audioContentType; }

    public Double getAudioDurationSeconds() { return audioDurationSeconds; }
    public void setAudioDurationSeconds(Double audioDurationSeconds) { this.audioDurationSeconds = audioDurationSeconds; }

    public String getAudioObjectKey() { return audioObjectKey; }
    public void setAudioObjectKey(String audioObjectKey) { this.audioObjectKey = audioObjectKey; }

    public String getTranscriptionState() { return transcriptionState; }
    public void setTranscriptionState(String transcriptionState) { this.transcriptionState = transcriptionState; }

    public String getTranscriptionModel() { return transcriptionModel; }
    public void setTranscriptionModel(String transcriptionModel) { this.transcriptionModel = transcriptionModel; }

    public Integer getTranscriptionAttempts() { return transcriptionAttempts; }
    public void setTranscriptionAttempts(Integer transcriptionAttempts) { this.transcriptionAttempts = transcriptionAttempts; }

    public String getTranscriptionLeaseId() { return transcriptionLeaseId; }
    public void setTranscriptionLeaseId(String transcriptionLeaseId) { this.transcriptionLeaseId = transcriptionLeaseId; }

    public String getTranscriptionLeaseWorkerId() { return transcriptionLeaseWorkerId; }
    public void setTranscriptionLeaseWorkerId(String transcriptionLeaseWorkerId) { this.transcriptionLeaseWorkerId = transcriptionLeaseWorkerId; }

    public Instant getTranscriptionLeaseExpiresAt() { return transcriptionLeaseExpiresAt; }
    public void setTranscriptionLeaseExpiresAt(Instant transcriptionLeaseExpiresAt) { this.transcriptionLeaseExpiresAt = transcriptionLeaseExpiresAt; }

    public Instant getTranscriptionNextAttemptAt() { return transcriptionNextAttemptAt; }
    public void setTranscriptionNextAttemptAt(Instant transcriptionNextAttemptAt) { this.transcriptionNextAttemptAt = transcriptionNextAttemptAt; }

    public String getTranscriptionResultModel() { return transcriptionResultModel; }
    public void setTranscriptionResultModel(String transcriptionResultModel) { this.transcriptionResultModel = transcriptionResultModel; }

    public Double getTranscriptionResultDurationSeconds() { return transcriptionResultDurationSeconds; }
    public void setTranscriptionResultDurationSeconds(Double transcriptionResultDurationSeconds) { this.transcriptionResultDurationSeconds = transcriptionResultDurationSeconds; }

    public String getTranscriptionResultOriginalText() { return transcriptionResultOriginalText; }
    public void setTranscriptionResultOriginalText(String transcriptionResultOriginalText) { this.transcriptionResultOriginalText = transcriptionResultOriginalText; }

    public String getTranscriptionResultSegments() { return transcriptionResultSegments; }
    public void setTranscriptionResultSegments(String transcriptionResultSegments) { this.transcriptionResultSegments = transcriptionResultSegments; }

    public Instant getTranscriptionResultCompletedAt() { return transcriptionResultCompletedAt; }
    public void setTranscriptionResultCompletedAt(Instant transcriptionResultCompletedAt) { this.transcriptionResultCompletedAt = transcriptionResultCompletedAt; }

    public String getTranscriptionErrorCode() { return transcriptionErrorCode; }
    public void setTranscriptionErrorCode(String transcriptionErrorCode) { this.transcriptionErrorCode = transcriptionErrorCode; }

    public String getTranscriptionErrorMessage() { return transcriptionErrorMessage; }
    public void setTranscriptionErrorMessage(String transcriptionErrorMessage) { this.transcriptionErrorMessage = transcriptionErrorMessage; }

    public Boolean getTranscriptionErrorRetryable() { return transcriptionErrorRetryable; }
    public void setTranscriptionErrorRetryable(Boolean transcriptionErrorRetryable) { this.transcriptionErrorRetryable = transcriptionErrorRetryable; }

    public UUID getCurrentRevisionId() { return currentRevisionId; }
    public void setCurrentRevisionId(UUID currentRevisionId) { this.currentRevisionId = currentRevisionId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
