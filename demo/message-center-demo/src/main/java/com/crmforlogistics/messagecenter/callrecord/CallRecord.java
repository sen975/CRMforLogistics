package com.crmforlogistics.messagecenter.callrecord;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CallRecord(
        UUID id,
        String contactAnchorPointId,
        String phonePointId,
        String direction,
        Instant occurredAt,
        Instant createdAt,
        String createdBy,
        String clientRequestId,
        AudioAsset audio,
        Transcription transcription,
        List<TranscriptRevision> revisions,
        UUID currentRevisionId,
        long version) {
    public CallRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contactAnchorPointId, "contactAnchorPointId");
        phonePointId = phonePointId == null ? "" : phonePointId;
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(transcription, "transcription");
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
    }
}

record AudioAsset(
        String relativePath,
        String originalFileName,
        long sizeBytes,
        String sha256,
        String contentType,
        double durationSeconds) {}

record Transcription(
        String state,
        String model,
        int attempts,
        CallRecordLease lease,
        Instant nextAttemptAt,
        TranscriptionResult result,
        CallRecordError error) {}

record CallRecordLease(String id, String workerId, Instant expiresAt) {}

record TranscriptionResult(
        String model,
        double durationSeconds,
        String originalText,
        List<TranscriptSegment> segments,
        Instant completedAt) {
    TranscriptionResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}

record TranscriptSegment(double startSeconds, double endSeconds, String text) {}

record TranscriptRevision(UUID id, String text, Instant editedAt, String editedBy) {}

record CallRecordError(String code, String message, boolean retryable) {}
