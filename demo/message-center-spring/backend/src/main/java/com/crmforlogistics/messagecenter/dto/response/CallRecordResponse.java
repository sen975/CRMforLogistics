package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;

public record CallRecordResponse(
        String id,
        String contactAnchorPointId,
        String phonePointId,
        String direction,
        Instant occurredAt,
        Instant createdAt,
        String createdBy,
        String clientRequestId,
        String note,
        AudioInfo audio,
        TranscriptionInfo transcription,
        List<RevisionInfo> revisions,
        String currentRevisionId,
        long version
) {
    public record AudioInfo(
            String originalFileName,
            long sizeBytes,
            String sha256,
            String contentType,
            double durationSeconds
    ) {}

    public record TranscriptionInfo(
            String state,
            String model,
            int attempts,
            Instant nextAttemptAt,
            ResultInfo result,
            ErrorInfo error
    ) {}

    public record ResultInfo(
            String model,
            double durationSeconds,
            String originalText,
            List<SegmentInfo> segments,
            Instant completedAt
    ) {}

    public record SegmentInfo(
            double startSeconds,
            double endSeconds,
            String text
    ) {}

    public record ErrorInfo(
            String code,
            String message,
            boolean retryable
    ) {}

    public record RevisionInfo(
            String id,
            String text,
            Instant editedAt,
            String editedBy
    ) {}
}
