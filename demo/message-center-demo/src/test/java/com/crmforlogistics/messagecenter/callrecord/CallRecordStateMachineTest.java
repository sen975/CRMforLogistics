package com.crmforlogistics.messagecenter.callrecord;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallRecordStateMachineTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

    @Test
    void leasesQueuedRecordAndRejectsStaleCompletion() throws Exception {
        CallRecord queued = queuedRecord();
        CallRecord processing = CallRecordStateMachine.lease(
                queued, "worker-1", NOW, NOW.plusSeconds(2_100));

        assertEquals("processing", processing.transcription().state());
        assertEquals(1, processing.transcription().attempts());
        assertNotNull(processing.transcription().lease().id());
        assertEquals("worker-1", processing.transcription().lease().workerId());
        assertEquals(queued.version() + 1, processing.version());

        CallRecordException error = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.complete(
                        processing, "wrong-lease", validResult(), NOW.plusSeconds(10)));
        assertEquals("CALL_RECORD_LEASE_INVALID", error.code());
    }

    @Test
    void rejectsBlankWorkerAndCompletionAfterLeaseExpiry() {
        CallRecordException workerError = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.lease(
                        queuedRecord(), " ", NOW, NOW.plusSeconds(60)));
        assertEquals("CALL_RECORD_LEASE_INVALID", workerError.code());

        CallRecord processing = processingRecord(1);
        CallRecordException expired = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.complete(
                        processing, processing.transcription().lease().id(),
                        validResult(), processing.transcription().lease().expiresAt().plusSeconds(1)));
        assertEquals("CALL_RECORD_LEASE_INVALID", expired.code());
    }

    @Test
    void completesOnlyWithOrderedBoundedSegments() throws Exception {
        CallRecord processing = processingRecord(1);
        CallRecord completed = CallRecordStateMachine.complete(
                processing, processing.transcription().lease().id(),
                validResult(), NOW.plusSeconds(10));

        assertEquals("completed", completed.transcription().state());
        assertNull(completed.transcription().lease());
        assertNull(completed.transcription().error());
        assertEquals("你好", completed.transcription().result().originalText());
        assertEquals(processing.version() + 1, completed.version());

        TranscriptionResult unordered = new TranscriptionResult(
                "sensevoice", 2.0, "bad",
                List.of(
                        new TranscriptSegment(1.0, 1.5, "later"),
                        new TranscriptSegment(0.2, 0.8, "earlier")),
                NOW);
        CallRecordException error = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.complete(
                        processing, processing.transcription().lease().id(), unordered, NOW));
        assertEquals("TRANSCRIPTION_RESULT_INVALID", error.code());
    }

    @Test
    void retryableFailureUsesFiniteBackoffAndFinalAttemptStops() throws Exception {
        CallRecord firstAttempt = processingRecord(1);
        CallRecord retry = CallRecordStateMachine.fail(
                firstAttempt, firstAttempt.transcription().lease().id(),
                new CallRecordError("FUNASR_TIMEOUT", "FunASR timed out", true), NOW, 3);
        assertEquals("queued", retry.transcription().state());
        assertEquals(NOW.plusSeconds(5), retry.transcription().nextAttemptAt());
        assertNull(retry.transcription().lease());

        CallRecord secondAttempt = processingRecord(2);
        CallRecord secondRetry = CallRecordStateMachine.fail(
                secondAttempt, secondAttempt.transcription().lease().id(),
                new CallRecordError("FUNASR_UNAVAILABLE", "FunASR unavailable", true), NOW, 3);
        assertEquals(NOW.plusSeconds(30), secondRetry.transcription().nextAttemptAt());

        CallRecord finalAttempt = processingRecord(3);
        CallRecord failed = CallRecordStateMachine.fail(
                finalAttempt, finalAttempt.transcription().lease().id(),
                new CallRecordError("FUNASR_TIMEOUT", "FunASR timed out", true), NOW, 3);
        assertEquals("failed", failed.transcription().state());
        assertNull(failed.transcription().nextAttemptAt());

        CallRecord permanentAttempt = processingRecord(1);
        CallRecord permanentFailure = CallRecordStateMachine.fail(
                permanentAttempt, permanentAttempt.transcription().lease().id(),
                new CallRecordError("FUNASR_REJECTED", "FunASR rejected audio", false), NOW, 3);
        assertEquals("failed", permanentFailure.transcription().state());
    }

    @Test
    void manualRetryResetsAttemptBudgetAndRecoveryPreservesIt() throws Exception {
        CallRecord processing = processingRecord(2);
        CallRecord recovered = CallRecordStateMachine.recover(processing, NOW);
        assertEquals("queued", recovered.transcription().state());
        assertEquals(2, recovered.transcription().attempts());
        assertEquals(NOW, recovered.transcription().nextAttemptAt());
        assertNull(recovered.transcription().lease());

        CallRecord failed = withTranscription(processing,
                new Transcription("failed", "sensevoice", 3, null, null, null,
                        new CallRecordError("FUNASR_TIMEOUT", "timeout", true)));
        CallRecord retried = CallRecordStateMachine.manualRetry(failed, NOW);
        assertEquals("queued", retried.transcription().state());
        assertEquals(0, retried.transcription().attempts());
        assertEquals(NOW, retried.transcription().nextAttemptAt());
        assertNull(retried.transcription().error());
    }

    @Test
    void appendsAtMostTwentyAuditedRevisionsWithoutChangingOriginal() throws Exception {
        CallRecord current = completedRecord();
        String original = current.transcription().result().originalText();
        for (int index = 1; index <= 20; index++) {
            current = CallRecordStateMachine.appendRevision(
                    current, "修订版本 " + index, "zhangsan", NOW.plusSeconds(index), 20);
        }

        assertEquals(20, current.revisions().size());
        assertEquals(current.revisions().get(19).id(), current.currentRevisionId());
        assertEquals(original, current.transcription().result().originalText());
        assertNotEquals(current.revisions().get(0).id(), current.currentRevisionId());

        CallRecord full = current;
        CallRecordException error = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.appendRevision(
                        full, "第二十一版", "zhangsan", NOW.plusSeconds(21), 20));
        assertEquals("TRANSCRIPT_REVISION_LIMIT", error.code());
    }

    @Test
    void rejectsTransitionsFromTheWrongState() {
        CallRecordException leaseError = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.lease(
                        completedRecord(), "worker", NOW, NOW.plusSeconds(60)));
        assertEquals("CALL_RECORD_STATE_INVALID", leaseError.code());

        CallRecordException revisionError = assertThrows(CallRecordException.class,
                () -> CallRecordStateMachine.appendRevision(
                        queuedRecord(), "text", "actor", NOW, 20));
        assertEquals("CALL_RECORD_STATE_INVALID", revisionError.code());
    }

    private static CallRecord queuedRecord() {
        return new CallRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "phone:8613800000000", "phone:8613800000000", "inbound",
                CREATED_AT, CREATED_AT, "zhangsan", "request-1",
                new AudioAsset("audio/550e8400-e29b-41d4-a716-446655440000.mp3",
                        "call.mp3", 1_024, "a".repeat(64), "audio/mpeg", 2.0),
                new Transcription("queued", "sensevoice", 0, null, CREATED_AT,
                        null, null),
                new ArrayList<>(), null, 1);
    }

    private static CallRecord processingRecord(int attempts) {
        CallRecord queued = queuedRecord();
        return withTranscription(queued,
                new Transcription("processing", "sensevoice", attempts,
                        new CallRecordLease("lease-" + attempts, "worker-1", NOW.plusSeconds(60)),
                        null, null, null));
    }

    private static CallRecord completedRecord() {
        CallRecord queued = queuedRecord();
        return withTranscription(queued,
                new Transcription("completed", "sensevoice", 1, null, null,
                        validResult(), null));
    }

    private static TranscriptionResult validResult() {
        return new TranscriptionResult(
                "sensevoice", 2.0, "你好",
                List.of(
                        new TranscriptSegment(0.0, 1.0, "你"),
                        new TranscriptSegment(1.0, 2.0, "好")),
                NOW);
    }

    private static CallRecord withTranscription(CallRecord source, Transcription transcription) {
        return new CallRecord(
                source.id(), source.contactAnchorPointId(), source.phonePointId(),
                source.direction(), source.occurredAt(), source.createdAt(), source.createdBy(),
                source.clientRequestId(), source.audio(), transcription, source.revisions(),
                source.currentRevisionId(), source.version());
    }
}
