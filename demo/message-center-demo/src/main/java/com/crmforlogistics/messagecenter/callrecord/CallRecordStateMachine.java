package com.crmforlogistics.messagecenter.callrecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class CallRecordStateMachine {
    private static final int MAX_RESULT_TEXT_LENGTH = 1_000_000;
    private static final int MAX_SEGMENT_TEXT_LENGTH = 100_000;
    private static final int MAX_SEGMENTS = 20_000;
    private static final double MAX_DURATION_SECONDS = 7_200.0;

    private CallRecordStateMachine() {}

    static CallRecord lease(CallRecord current, String workerId, Instant now, Instant expiresAt)
            throws CallRecordException {
        requireState(current, "queued");
        if (invalidText(workerId, 128)) {
            throw new CallRecordException(
                    "CALL_RECORD_LEASE_INVALID", 409, "workerId is invalid", false);
        }
        requireTime(now, "now");
        requireTime(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw invalid("CALL_RECORD_LEASE_INVALID", "Lease expiry must be after now");
        }
        Instant nextAttemptAt = current.transcription().nextAttemptAt();
        if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            throw invalid("CALL_RECORD_NOT_RUNNABLE", "Call record is not runnable yet");
        }
        Transcription next = new Transcription(
                "processing",
                current.transcription().model(),
                current.transcription().attempts() + 1,
                new CallRecordLease(UUID.randomUUID().toString(), workerId.trim(), expiresAt),
                null,
                null,
                null);
        return replaceTranscription(current, next);
    }

    static CallRecord complete(CallRecord current, String leaseId,
                               TranscriptionResult result, Instant completedAt)
            throws CallRecordException {
        requireState(current, "processing");
        requireTime(completedAt, "completedAt");
        requireLease(current, leaseId, completedAt);
        validateResult(result);
        TranscriptionResult completed = new TranscriptionResult(
                result.model(), result.durationSeconds(), result.originalText(),
                result.segments(), completedAt);
        Transcription next = new Transcription(
                "completed", result.model(), current.transcription().attempts(),
                null, null, completed, null);
        return replaceTranscription(current, next);
    }

    static CallRecord fail(CallRecord current, String leaseId, CallRecordError error,
                           Instant now, int maxAttempts) throws CallRecordException {
        requireState(current, "processing");
        requireTime(now, "now");
        requireLease(current, leaseId, now);
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw invalid("CALL_RECORD_STATE_INVALID", "maxAttempts is outside its bounds");
        }
        validateError(error);
        int attempts = current.transcription().attempts();
        boolean retry = error.retryable() && attempts < maxAttempts;
        Instant nextAttempt = retry ? now.plusSeconds(attempts <= 1 ? 5 : 30) : null;
        Transcription next = new Transcription(
                retry ? "queued" : "failed",
                current.transcription().model(),
                attempts,
                null,
                nextAttempt,
                null,
                error);
        return replaceTranscription(current, next);
    }

    static CallRecord manualRetry(CallRecord current, Instant now) throws CallRecordException {
        requireState(current, "failed");
        requireTime(now, "now");
        Transcription next = new Transcription(
                "queued", current.transcription().model(), 0,
                null, now, null, null);
        return replaceTranscription(current, next);
    }

    static CallRecord appendRevision(CallRecord current, String text, String actor,
                                     Instant editedAt, int maxRevisions)
            throws CallRecordException {
        requireState(current, "completed");
        requireRevisionText(text, MAX_SEGMENT_TEXT_LENGTH, "text");
        requireRevisionText(actor, 128, "actor");
        requireTime(editedAt, "editedAt");
        if (maxRevisions < 1 || maxRevisions > 20) {
            throw invalid("TRANSCRIPT_REVISION_INVALID", "maxRevisions is outside its bounds");
        }
        if (current.revisions().size() >= maxRevisions) {
            throw new CallRecordException(
                    "TRANSCRIPT_REVISION_LIMIT", 409,
                    "Transcript revision limit reached", false);
        }
        TranscriptRevision revision = new TranscriptRevision(
                UUID.randomUUID(), text, editedAt, actor.trim());
        List<TranscriptRevision> revisions = new ArrayList<>(current.revisions());
        revisions.add(revision);
        return new CallRecord(
                current.id(), current.contactAnchorPointId(), current.phonePointId(),
                current.direction(), current.occurredAt(), current.createdAt(), current.createdBy(),
                current.clientRequestId(), current.audio(), current.transcription(), revisions,
                revision.id(), current.version() + 1);
    }

    static CallRecord recover(CallRecord current, Instant now) throws CallRecordException {
        requireState(current, "processing");
        requireTime(now, "now");
        Transcription next = new Transcription(
                "queued", current.transcription().model(), current.transcription().attempts(),
                null, now, null, current.transcription().error());
        return replaceTranscription(current, next);
    }

    private static CallRecord replaceTranscription(CallRecord current, Transcription transcription) {
        return new CallRecord(
                current.id(), current.contactAnchorPointId(), current.phonePointId(),
                current.direction(), current.occurredAt(), current.createdAt(), current.createdBy(),
                current.clientRequestId(), current.audio(), transcription, current.revisions(),
                current.currentRevisionId(), current.version() + 1);
    }

    private static void requireState(CallRecord current, String expected)
            throws CallRecordException {
        if (current == null || current.transcription() == null
                || !expected.equals(current.transcription().state())) {
            throw new CallRecordException(
                    "CALL_RECORD_STATE_INVALID", 409,
                    "Call record state does not allow this transition", false);
        }
    }

    private static void requireLease(CallRecord current, String leaseId, Instant at)
            throws CallRecordException {
        CallRecordLease lease = current.transcription().lease();
        if (lease == null || leaseId == null || !Objects.equals(lease.id(), leaseId)
                || lease.expiresAt() == null || !at.isBefore(lease.expiresAt())) {
            throw new CallRecordException(
                    "CALL_RECORD_LEASE_INVALID", 409,
                    "Call record lease is stale or invalid", false);
        }
    }

    private static void validateResult(TranscriptionResult result) throws CallRecordException {
        if (result == null || invalidText(result.model(), 128)
                || invalidText(result.originalText(), MAX_RESULT_TEXT_LENGTH)
                || !Double.isFinite(result.durationSeconds())
                || result.durationSeconds() <= 0
                || result.durationSeconds() > MAX_DURATION_SECONDS
                || result.segments() == null || result.segments().isEmpty()
                || result.segments().size() > MAX_SEGMENTS) {
            throw invalidResult();
        }
        double previousStart = -1;
        for (TranscriptSegment segment : result.segments()) {
            if (segment == null || invalidText(segment.text(), MAX_SEGMENT_TEXT_LENGTH)
                    || !Double.isFinite(segment.startSeconds())
                    || !Double.isFinite(segment.endSeconds())
                    || segment.startSeconds() < 0
                    || segment.endSeconds() < segment.startSeconds()
                    || segment.endSeconds() > result.durationSeconds() + 1.0
                    || segment.startSeconds() < previousStart) {
                throw invalidResult();
            }
            previousStart = segment.startSeconds();
        }
    }

    private static void validateError(CallRecordError error) throws CallRecordException {
        if (error == null || invalidText(error.code(), 128) || invalidText(error.message(), 2_048)) {
            throw invalid("CALL_RECORD_ERROR_INVALID", "Call record error is invalid");
        }
    }

    private static void requireRevisionText(String value, int maximum, String field)
            throws CallRecordException {
        if (invalidText(value, maximum)) {
            throw invalid("TRANSCRIPT_REVISION_INVALID", field + " is invalid");
        }
    }

    private static boolean invalidText(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static void requireTime(Instant value, String field) throws CallRecordException {
        if (value == null) {
            throw invalid("CALL_RECORD_STATE_INVALID", field + " is required");
        }
    }

    private static CallRecordException invalid(String code, String message) {
        return new CallRecordException(code, 409, message, false);
    }

    private static CallRecordException invalidResult() {
        return new CallRecordException(
                "TRANSCRIPTION_RESULT_INVALID", 422,
                "Transcription result is invalid", false);
    }
}
