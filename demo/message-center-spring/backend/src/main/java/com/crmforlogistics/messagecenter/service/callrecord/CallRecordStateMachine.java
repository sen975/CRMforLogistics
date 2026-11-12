package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CallRecordStateMachine {
    private static final int MAX_RESULT_TEXT_LENGTH = 1_000_000;
    private static final int MAX_SEGMENT_TEXT_LENGTH = 100_000;
    private static final int MAX_SEGMENTS = 20_000;
    private static final double MAX_DURATION_SECONDS = 7_200.0;

    private CallRecordStateMachine() {}

    static CallRecordEntity lease(CallRecordEntity current, String workerId, Instant now, Instant expiresAt) {
        requireState(current, "queued");
        if (invalidText(workerId, 128)) {
            throw new CallRecordException("CALL_RECORD_LEASE_INVALID", 409, "workerId is invalid", false);
        }
        requireTime(now, "now");
        requireTime(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw invalid("CALL_RECORD_LEASE_INVALID", "Lease expiry must be after now");
        }
        Instant nextAttemptAt = current.getTranscriptionNextAttemptAt();
        if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            throw invalid("CALL_RECORD_NOT_RUNNABLE", "Call record is not runnable yet");
        }
        current.setTranscriptionState("processing");
        current.setTranscriptionAttempts(current.getTranscriptionAttempts() + 1);
        current.setTranscriptionLeaseId(UUID.randomUUID().toString());
        current.setTranscriptionLeaseWorkerId(workerId.trim());
        current.setTranscriptionLeaseExpiresAt(expiresAt);
        current.setTranscriptionNextAttemptAt(null);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    static CallRecordEntity complete(CallRecordEntity current, String leaseId,
                                     TranscriptionResult result, Instant completedAt) {
        requireState(current, "processing");
        requireTime(completedAt, "completedAt");
        requireLease(current, leaseId, completedAt);
        validateResult(result);
        current.setTranscriptionState("completed");
        current.setTranscriptionResultModel(result.model());
        current.setTranscriptionResultDurationSeconds(result.durationSeconds());
        current.setTranscriptionResultOriginalText(result.originalText());
        current.setTranscriptionResultSegments(segmentsToJson(result.segments()));
        current.setTranscriptionResultCompletedAt(completedAt);
        current.setTranscriptionLeaseId(null);
        current.setTranscriptionLeaseWorkerId(null);
        current.setTranscriptionLeaseExpiresAt(null);
        current.setTranscriptionErrorCode(null);
        current.setTranscriptionErrorMessage(null);
        current.setTranscriptionErrorRetryable(null);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    static CallRecordEntity fail(CallRecordEntity current, String leaseId, CallRecordError error,
                                 Instant now, int maxAttempts) {
        requireState(current, "processing");
        requireTime(now, "now");
        requireLease(current, leaseId, now);
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw invalid("CALL_RECORD_STATE_INVALID", "maxAttempts is outside its bounds");
        }
        validateError(error);
        int attempts = current.getTranscriptionAttempts();
        boolean retry = error.retryable() && attempts < maxAttempts;
        Instant nextAttempt = retry ? now.plusSeconds(attempts <= 1 ? 5 : 30) : null;
        current.setTranscriptionState(retry ? "queued" : "failed");
        current.setTranscriptionNextAttemptAt(nextAttempt);
        current.setTranscriptionLeaseId(null);
        current.setTranscriptionLeaseWorkerId(null);
        current.setTranscriptionLeaseExpiresAt(null);
        current.setTranscriptionErrorCode(error.code());
        current.setTranscriptionErrorMessage(error.message());
        current.setTranscriptionErrorRetryable(error.retryable());
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    static CallRecordEntity manualRetry(CallRecordEntity current, Instant now) {
        if (!canManualRetry(current)) {
            throw new CallRecordException(
                    "CALL_RECORD_STATE_INVALID", 409,
                    "Call record state does not allow this transition", false);
        }
        requireTime(now, "now");
        current.setTranscriptionState("queued");
        current.setTranscriptionAttempts(0);
        current.setTranscriptionNextAttemptAt(now);
        current.setTranscriptionLeaseId(null);
        current.setTranscriptionLeaseWorkerId(null);
        current.setTranscriptionLeaseExpiresAt(null);
        current.setTranscriptionErrorCode(null);
        current.setTranscriptionErrorMessage(null);
        current.setTranscriptionErrorRetryable(null);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    static boolean canManualRetry(CallRecordEntity current) {
        if (current == null) return false;
        if ("failed".equals(current.getTranscriptionState())) return true;
        if (!"completed".equals(current.getTranscriptionState())) return false;
        String segments = current.getTranscriptionResultSegments();
        return segments == null || segments.isBlank() || "[]".equals(segments.trim());
    }

    static CallRecordEntity appendRevision(CallRecordEntity current, String text, String actor,
                                           Instant editedAt, int maxRevisions) {
        requireState(current, "completed");
        requireRevisionText(text, MAX_SEGMENT_TEXT_LENGTH, "text");
        requireRevisionText(actor, 128, "actor");
        requireTime(editedAt, "editedAt");
        if (maxRevisions < 1 || maxRevisions > 20) {
            throw invalid("TRANSCRIPT_REVISION_INVALID", "maxRevisions is outside its bounds");
        }
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    static CallRecordEntity recover(CallRecordEntity current, Instant now) {
        requireState(current, "processing");
        requireTime(now, "now");
        current.setTranscriptionState("queued");
        current.setTranscriptionNextAttemptAt(now);
        current.setTranscriptionLeaseId(null);
        current.setTranscriptionLeaseWorkerId(null);
        current.setTranscriptionLeaseExpiresAt(null);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    private static void requireState(CallRecordEntity current, String expected) {
        if (current == null || current.getTranscriptionState() == null
                || !expected.equals(current.getTranscriptionState())) {
            throw new CallRecordException(
                    "CALL_RECORD_STATE_INVALID", 409,
                    "Call record state does not allow this transition", false);
        }
    }

    private static void requireLease(CallRecordEntity current, String leaseId, Instant at) {
        String currentLeaseId = current.getTranscriptionLeaseId();
        Instant expiresAt = current.getTranscriptionLeaseExpiresAt();
        if (currentLeaseId == null || leaseId == null || !Objects.equals(currentLeaseId, leaseId)
                || expiresAt == null || !at.isBefore(expiresAt)) {
            throw new CallRecordException(
                    "CALL_RECORD_LEASE_INVALID", 409,
                    "Call record lease is stale or invalid", false);
        }
    }

    private static void validateResult(TranscriptionResult result) {
        if (result == null || invalidText(result.model(), 128)
                || invalidText(result.originalText(), MAX_RESULT_TEXT_LENGTH)
                || !Double.isFinite(result.durationSeconds())
                || result.durationSeconds() <= 0
                || result.durationSeconds() > MAX_DURATION_SECONDS
                || result.segments() == null
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

    private static void validateError(CallRecordError error) {
        if (error == null || invalidText(error.code(), 128) || invalidText(error.message(), 2_048)) {
            throw invalid("CALL_RECORD_ERROR_INVALID", "Call record error is invalid");
        }
    }

    private static void requireRevisionText(String value, int maximum, String field) {
        if (invalidText(value, maximum)) {
            throw invalid("TRANSCRIPT_REVISION_INVALID", field + " is invalid");
        }
    }

    private static boolean invalidText(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static void requireTime(Instant value, String field) {
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

    static String segmentsToJson(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"startSeconds\":").append(s.startSeconds())
              .append(",\"endSeconds\":").append(s.endSeconds())
              .append(",\"text\":\"").append(escapeJson(s.text())).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record TranscriptionResult(String model, double durationSeconds, String originalText,
                                      List<TranscriptSegment> segments, Instant completedAt) {}

    public record TranscriptSegment(double startSeconds, double endSeconds, String text) {}

    public record CallRecordError(String code, String message, boolean retryable) {}
}
