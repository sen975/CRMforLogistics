package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
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
    void ownerRetryStoresRequestKeyAndReplaysWithoutAdvancingAgain() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        UUID owner = UUID.randomUUID();
        CallRecordEntity current = failedRecord(3L);
        when(mapper.findByIdAndOwner(current.getId(), owner)).thenReturn(Optional.of(current));
        when(mapper.findRetryRequest(owner, current.getId(), "retry-1")).thenReturn(Optional.empty());
        when(mapper.insertRetryRequest(owner, current.getId(), "retry-1")).thenReturn(1);
        when(mapper.countPending()).thenReturn(0);
        when(mapper.replace(any(CallRecordEntity.class), eq(3L))).thenReturn(1);
        CallRecordService service = service(mapper, revisionMapper);

        CallRecordEntity first = service.retry(owner, current.getId(), owner.toString(), "retry-1");

        assertThat(first.getVersion()).isEqualTo(4L);
        verify(mapper).insertRetryRequest(owner, current.getId(), "retry-1");

        CallRecordEntity replay = failedRecord(4L);
        replay.setId(current.getId());
        replay.setTranscriptionState("queued");
        when(mapper.findByIdAndOwner(current.getId(), owner)).thenReturn(Optional.of(replay));
        when(mapper.findRetryRequest(owner, current.getId(), "retry-1"))
                .thenReturn(Optional.of(new com.crmforlogistics.messagecenter.entity.CallRecordRetryRequestEntity()));

        assertThat(service.retry(owner, current.getId(), owner.toString(), "retry-1")).isSameAs(replay);
        verify(mapper, org.mockito.Mockito.times(1)).insertRetryRequest(owner, current.getId(), "retry-1");
        verify(mapper, org.mockito.Mockito.times(1)).replace(any(CallRecordEntity.class), eq(3L));
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

    @Test
    void createAllowsMissingContactAndUsesPhoneResolver() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        when(addressBooks.resolvePhone(owner, null, "8613800138000", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(contact, identity, true));
        when(mapper.countPending()).thenReturn(0);
        MinioAudioStore.StagedAudio staged = mock(MinioAudioStore.StagedAudio.class);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                "audio/test.mp3", "test.mp3", 10L, "sha", "audio/mpeg", 1.0, "call-records/test.mp3");
        when(audioStore.stage(any(), eq("test.mp3"), eq("audio/mpeg"))).thenReturn(staged);
        when(audioStore.publish(any(), eq(staged))).thenReturn(asset);

        CallRecordService service = service(mapper, revisionMapper, null, addressBooks, audioStore);
        CallRecordEntity created = service.create(owner, new CallRecordService.CreateCallRecordCommand(
                "", "phone:8613800138000", "inbound", NOW, "request-1", "test.mp3",
                "audio/mpeg", owner.toString(), ""), new java.io.ByteArrayInputStream(new byte[]{1}));

        assertThat(created.getContactId()).isEqualTo(contact);
        assertThat(created.getOwnerUserId()).isEqualTo(owner);
        assertThat(created.getContactAnchorPointId()).isEqualTo("phone:8613800138000");
        verify(addressBooks).resolvePhone(owner, null, "8613800138000", null);
        verify(mapper).insert(created);
    }

    @Test
    void createDeletesPublishedAudioWhenDatabaseInsertFails() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        when(addressBooks.resolvePhone(owner, null, "8613800138000", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(contact, UUID.randomUUID(), true));
        when(mapper.countPending()).thenReturn(0);
        MinioAudioStore.StagedAudio staged = mock(MinioAudioStore.StagedAudio.class);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                "audio/test.mp3", "test.mp3", 10L, "a".repeat(64), "audio/mpeg", 1.0,
                "call-records/test.mp3");
        when(audioStore.stage(any(), eq("test.mp3"), eq("audio/mpeg"))).thenReturn(staged);
        when(audioStore.publish(any(), eq(staged))).thenReturn(asset);
        when(mapper.insert(any(CallRecordEntity.class))).thenThrow(new RuntimeException("duplicate"));

        CallRecordService service = service(mapper, revisionMapper, null, addressBooks, audioStore);

        assertThatThrownBy(() -> service.create(owner, new CallRecordService.CreateCallRecordCommand(
                "", "phone:8613800138000", "inbound", NOW, "request-1", "test.mp3",
                "audio/mpeg", owner.toString(), ""), new java.io.ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(CallRecordException.class)
                .extracting(error -> ((CallRecordException) error).code())
                .isEqualTo("CALL_RECORD_PERSIST_FAILED");
        verify(audioStore).delete(asset);
    }

    @Test
    void cleanupFailureDoesNotReplaceOriginalPersistenceFailure() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        when(addressBooks.resolvePhone(owner, null, "8613800138000", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(UUID.randomUUID(), UUID.randomUUID(), true));
        when(mapper.countPending()).thenReturn(0);
        MinioAudioStore.StagedAudio staged = mock(MinioAudioStore.StagedAudio.class);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                "audio/test.mp3", "test.mp3", 10L, "a".repeat(64), "audio/mpeg", 1.0,
                "call-records/test.mp3");
        when(audioStore.stage(any(), eq("test.mp3"), eq("audio/mpeg"))).thenReturn(staged);
        when(audioStore.publish(any(), eq(staged))).thenReturn(asset);
        when(mapper.insert(any(CallRecordEntity.class))).thenThrow(new RuntimeException("db down"));
        org.mockito.Mockito.doThrow(new RuntimeException("minio down")).when(audioStore).delete(asset);

        CallRecordException failure;
        try {
            service(mapper, revisionMapper, null, addressBooks, audioStore).create(owner,
                    new CallRecordService.CreateCallRecordCommand(
                            "", "phone:8613800138000", "inbound", NOW, "request-2", "test.mp3",
                            "audio/mpeg", owner.toString(), ""),
                    new java.io.ByteArrayInputStream(new byte[]{1}));
            throw new AssertionError("expected persistence failure");
        } catch (CallRecordException expected) {
            failure = expected;
        }
        assertThat(failure.code()).isEqualTo("CALL_RECORD_PERSIST_FAILED");
        assertThat(failure.getCause().getSuppressed()).hasSize(1);
    }

    private static CallRecordService service(CallRecordMapper mapper,
                                             CallTranscriptRevisionMapper revisionMapper) {
        return service(mapper, revisionMapper, null);
    }

    private static CallRecordService service(CallRecordMapper mapper,
                                             CallTranscriptRevisionMapper revisionMapper,
                                             AiTopicActivityRecorder topicActivityRecorder) {
        return service(mapper, revisionMapper, topicActivityRecorder, null, mock(MinioAudioStore.class));
    }

    private static CallRecordService service(CallRecordMapper mapper,
                                             CallTranscriptRevisionMapper revisionMapper,
                                             AiTopicActivityRecorder topicActivityRecorder,
                                             ChannelAddressBookService addressBooks,
                                             MinioAudioStore audioStore) {
        return new CallRecordService(
                mapper,
                revisionMapper,
                audioStore,
                mock(ContactIdentityMapper.class),
                config(),
                new FunAsrConfig(
                        "http://127.0.0.1:8000", "sensevoice",
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30), 10_485_760L),
                Clock.fixed(NOW, ZoneOffset.UTC),
                topicActivityRecorder,
                addressBooks);
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
                10_485_760L, 20_000, 20, 300, 8, 256, 105_906_176L);
    }
}
