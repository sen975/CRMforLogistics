package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallRecordServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-07T07:00:00Z");

    @Test
    void retryUsesPersistedVersionBeforeStateMachineIncrementsIt() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        CallRecordEntity current = failedRecord(3L);
        UUID id = current.getId();
        when(mapper.findById(id)).thenReturn(Optional.of(current));
        when(mapper.countPending()).thenReturn(0);
        when(mapper.replace(any(CallRecordEntity.class), eq(3L))).thenReturn(1);

        CallRecordService service = service(mapper, revisionMapper);

        CallRecordEntity retried = service.retry(id, "user-1", "client-request-1");

        assertThat(retried.getTranscriptionState()).isEqualTo("queued");
        assertThat(retried.getVersion()).isEqualTo(4L);
        verify(mapper).replace(retried, 3L);
    }

    @Test
    void retryCompletedTranscriptWithoutSegmentsUsesPersistedVersion() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        CallRecordEntity current = completedRecord(6L);
        current.setTranscriptionResultSegments("[]");
        UUID id = current.getId();
        when(mapper.findById(id)).thenReturn(Optional.of(current));
        when(mapper.countPending()).thenReturn(0);
        when(mapper.replace(any(CallRecordEntity.class), eq(6L))).thenReturn(1);

        CallRecordEntity retried = service(mapper, revisionMapper)
                .retry(id, "user-1", "client-request-1");

        assertThat(retried.getTranscriptionState()).isEqualTo("queued");
        assertThat(retried.getVersion()).isEqualTo(7L);
        verify(mapper).replace(retried, 6L);
    }

    @Test
    void retryRejectsCompletedTranscriptThatAlreadyHasSegments() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        CallRecordEntity current = completedRecord(6L);
        current.setTranscriptionResultSegments(
                "[{\"startSeconds\":0.0,\"endSeconds\":1.0,\"text\":\"你好。\"}]");
        UUID id = current.getId();
        when(mapper.findById(id)).thenReturn(Optional.of(current));
        when(mapper.countPending()).thenReturn(0);

        assertThatThrownBy(() -> service(mapper, revisionMapper)
                .retry(id, "user-1", "client-request-1"))
                .isInstanceOf(CallRecordException.class)
                .extracting(error -> ((CallRecordException) error).code())
                .isEqualTo("CALL_RECORD_STATE_INVALID");
        verify(mapper, never()).replace(any(CallRecordEntity.class), any(Long.class));
    }

    @Test
    void reviseIsTransactionalSoRevisionInsertRollsBackWithVersionConflict() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                CallRecordService.class.getMethod(
                        "revise", UUID.class, String.class, String.class, long.class),
                Transactional.class)).isTrue();
    }

    @Test
    void reviseUsesPersistedVersionAndReturnsIncrementedVersion() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        CallRecordEntity current = completedRecord(6L);
        UUID id = current.getId();
        when(mapper.findById(id)).thenReturn(Optional.of(current));
        when(revisionMapper.listByCallRecordId(id)).thenReturn(java.util.List.of());
        when(revisionMapper.insert(any(CallTranscriptRevisionEntity.class))).thenReturn(1);
        when(mapper.replace(any(CallRecordEntity.class), eq(6L))).thenReturn(1);
        CallRecordService service = service(mapper, revisionMapper);

        CallRecordEntity revised = service.revise(id, "人工修订", "user-1", 6L);

        assertThat(revised.getVersion()).isEqualTo(7L);
        assertThat(revised.getCurrentRevisionId()).isNotNull();
        verify(mapper).replace(revised, 6L);
        verify(revisionMapper).insert(any(CallTranscriptRevisionEntity.class));
    }

    @Test
    void reviseNoteRecordsTopicActivityAtOriginalCallTimeAfterPersisting() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        AiTopicActivityRecorder topicActivityRecorder = mock(AiTopicActivityRecorder.class);
        CallRecordEntity current = completedRecord(6L);
        current.setContactAnchorPointId("phone:60123456789");
        current.setOccurredAt(NOW.minusSeconds(120));
        when(mapper.findById(current.getId())).thenReturn(Optional.of(current));
        when(mapper.updateNote(current.getId(), "已确认报价", 6L)).thenReturn(1);

        CallRecordEntity revised = service(mapper, revisionMapper, topicActivityRecorder)
                .reviseNote(current.getId(), "已确认报价", 6L);

        assertThat(revised.getNote()).isEqualTo("已确认报价");
        verify(topicActivityRecorder).recordCall(
                current.getContactAnchorPointId(), current.getOccurredAt());
    }

    private static CallRecordService service(CallRecordMapper mapper,
                                             CallTranscriptRevisionMapper revisionMapper) {
        return service(mapper, revisionMapper, null);
    }

    private static CallRecordService service(CallRecordMapper mapper,
                                             CallTranscriptRevisionMapper revisionMapper,
                                             AiTopicActivityRecorder topicActivityRecorder) {
        return new CallRecordService(
                mapper,
                revisionMapper,
                mock(MinioAudioStore.class),
                mock(ContactIdentityMapper.class),
                config(),
                new FunAsrConfig(
                        "http://127.0.0.1:8000", "sensevoice",
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                topicActivityRecorder);
    }

    private static CallRecordEntity completedRecord(long version) {
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setTranscriptionState("completed");
        record.setVersion(version);
        return record;
    }

    private static CallRecordEntity failedRecord(long version) {
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setTranscriptionState("failed");
        record.setTranscriptionAttempts(1);
        record.setTranscriptionErrorCode("FUNASR_INVALID_RESPONSE");
        record.setTranscriptionErrorMessage("invalid response");
        record.setTranscriptionErrorRetryable(false);
        record.setVersion(version);
        return record;
    }

    private static CallRecordConfig config() {
        return new CallRecordConfig(
                "data/call-records", 104_857_600L, 7_200,
                10_737_418_240L, 10_000, 64, 1, 2_100, 3,
                10_485_760L, 20_000, 20, 300, 8, 256);
    }
}
