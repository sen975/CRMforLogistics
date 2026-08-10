package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallRecordStateMachineTest {
    private static final Instant NOW = Instant.parse("2026-08-07T07:00:00Z");

    @Test
    void completesTranscriptWhenProviderHasNoSegments() {
        CallRecordEntity processing = new CallRecordEntity();
        processing.setTranscriptionState("processing");
        processing.setTranscriptionLeaseId("lease-1");
        processing.setTranscriptionLeaseExpiresAt(NOW.plusSeconds(60));
        processing.setVersion(4L);
        CallRecordStateMachine.TranscriptionResult result =
                new CallRecordStateMachine.TranscriptionResult(
                        "sensevoice", 62.54, "完整转录文本", List.of(), NOW);

        CallRecordEntity completed = CallRecordStateMachine.complete(
                processing, "lease-1", result, NOW);

        assertThat(completed.getTranscriptionState()).isEqualTo("completed");
        assertThat(completed.getTranscriptionResultDurationSeconds()).isEqualTo(62.54);
        assertThat(completed.getTranscriptionResultOriginalText()).isEqualTo("完整转录文本");
        assertThat(completed.getTranscriptionResultSegments()).isEqualTo("[]");
        assertThat(completed.getVersion()).isEqualTo(5L);
    }

    @Test
    void requeuesCompletedTranscriptWithoutSegmentsAndPreservesRevision() {
        CallRecordEntity completed = completedRecord("[]");
        UUID revisionId = UUID.randomUUID();
        completed.setCurrentRevisionId(revisionId);
        completed.setTranscriptionResultOriginalText("旧机器原文");

        CallRecordEntity queued = CallRecordStateMachine.manualRetry(completed, NOW);

        assertThat(queued.getTranscriptionState()).isEqualTo("queued");
        assertThat(queued.getTranscriptionNextAttemptAt()).isEqualTo(NOW);
        assertThat(queued.getTranscriptionAttempts()).isZero();
        assertThat(queued.getCurrentRevisionId()).isEqualTo(revisionId);
        assertThat(queued.getTranscriptionResultOriginalText()).isEqualTo("旧机器原文");
    }

    @Test
    void rejectsCompletedTranscriptThatAlreadyHasRealSegments() {
        CallRecordEntity completed = completedRecord(
                "[{\"startSeconds\":0.0,\"endSeconds\":1.0,\"text\":\"你好。\"}]");

        assertThatThrownBy(() -> CallRecordStateMachine.manualRetry(completed, NOW))
                .isInstanceOf(CallRecordException.class)
                .extracting(error -> ((CallRecordException) error).code())
                .isEqualTo("CALL_RECORD_STATE_INVALID");
    }

    private static CallRecordEntity completedRecord(String segments) {
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setTranscriptionState("completed");
        record.setTranscriptionAttempts(1);
        record.setTranscriptionResultSegments(segments);
        record.setVersion(6L);
        return record;
    }
}
