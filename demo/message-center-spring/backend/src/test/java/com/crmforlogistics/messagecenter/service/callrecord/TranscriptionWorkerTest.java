package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;

class TranscriptionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-07T07:00:00Z");

    @Test
    void successfulTranscriptionUsesPersistedVersionForEachOptimisticUpdate() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        FunAsrClient transcriber = mock(FunAsrClient.class);
        CallRecordEntity record = queuedRecord(7L);
        when(mapper.listRunnable(any(), anyInt()))
                .thenReturn(List.of(record))
                .thenReturn(List.of());
        when(audioStore.path(any())).thenReturn(Path.of("recording.mp3"));
        when(transcriber.transcribe(any(), eq("sensevoice"), eq(10.0)))
                .thenReturn(transcriptionResult());
        VersionCapture versions = captureSuccessfulUpdates(mapper, 2);
        TranscriptionWorker worker = worker(mapper, audioStore, transcriber);

        try {
            worker.start();

            assertThat(versions.await()).isTrue();
            assertThat(versions.values()).containsExactly(7L, 8L);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void completedTranscriptionRecordsTopicActivityAtCallTime() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        FunAsrClient transcriber = mock(FunAsrClient.class);
        AiTopicActivityRecorder topicActivityRecorder = mock(AiTopicActivityRecorder.class);
        CallRecordEntity record = queuedRecord(7L);
        record.setContactAnchorPointId("contact:" + UUID.randomUUID());
        record.setOccurredAt(NOW.minusSeconds(120));
        when(mapper.listRunnable(any(), anyInt())).thenReturn(List.of(record)).thenReturn(List.of());
        when(audioStore.path(any())).thenReturn(Path.of("recording.mp3"));
        when(transcriber.transcribe(any(), eq("sensevoice"), eq(10.0))).thenReturn(transcriptionResult());
        VersionCapture versions = captureSuccessfulUpdates(mapper, 2);
        TranscriptionWorker worker = worker(mapper, audioStore, transcriber, topicActivityRecorder);

        try {
            worker.start();
            assertThat(versions.await()).isTrue();
            verify(topicActivityRecorder, timeout(2_000)).recordCall(
                    eq(record.getContactAnchorPointId()), eq(record.getOccurredAt()));
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void failedTranscriptionUsesLeasedVersionWhenSchedulingRetry() throws Exception {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        FunAsrClient transcriber = mock(FunAsrClient.class);
        CallRecordEntity record = queuedRecord(11L);
        when(mapper.listRunnable(any(), anyInt()))
                .thenReturn(List.of(record))
                .thenReturn(List.of());
        when(audioStore.path(any())).thenReturn(Path.of("recording.mp3"));
        when(transcriber.transcribe(any(), eq("sensevoice"), eq(10.0)))
                .thenThrow(new CallRecordException(
                        "FUNASR_UNAVAILABLE", 503, "FunASR is unavailable", true));
        VersionCapture versions = captureSuccessfulUpdates(mapper, 2);
        TranscriptionWorker worker = worker(mapper, audioStore, transcriber);

        try {
            worker.start();

            assertThat(versions.await()).isTrue();
            assertThat(versions.values()).containsExactly(11L, 12L);
        } finally {
            worker.shutdown();
        }
    }

    private static TranscriptionWorker worker(CallRecordMapper mapper,
                                              MinioAudioStore audioStore,
                                              FunAsrClient transcriber) {
        return worker(mapper, audioStore, transcriber, null);
    }

    private static TranscriptionWorker worker(CallRecordMapper mapper,
                                              MinioAudioStore audioStore,
                                              FunAsrClient transcriber,
                                              AiTopicActivityRecorder topicActivityRecorder) {
        CallRecordConfig config = new CallRecordConfig(
                "data/call-records", 104_857_600L, 7_200,
                10_737_418_240L, 10_000, 64, 1, 2_100, 3,
                10_485_760L, 20_000, 20, 300, 8, 256, 105_906_176L);
        return new TranscriptionWorker(
                mapper, audioStore, transcriber, config,
                Clock.fixed(NOW, ZoneOffset.UTC), topicActivityRecorder);
    }

    private static CallRecordEntity queuedRecord(long version) {
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setAudioRelativePath("audio/recording.mp3");
        record.setAudioOriginalFileName("recording.mp3");
        record.setAudioSizeBytes(1_024L);
        record.setAudioSha256("a".repeat(64));
        record.setAudioContentType("audio/mpeg");
        record.setAudioDurationSeconds(10.0);
        record.setAudioObjectKey("call-records/recording.mp3");
        record.setTranscriptionState("queued");
        record.setTranscriptionModel("sensevoice");
        record.setTranscriptionAttempts(0);
        record.setVersion(version);
        record.setCreatedAt(NOW.minusSeconds(60));
        record.setOccurredAt(NOW.minusSeconds(60));
        record.setContactAnchorPointId("contact:" + UUID.randomUUID());
        return record;
    }

    private static CallRecordStateMachine.TranscriptionResult transcriptionResult() {
        return new CallRecordStateMachine.TranscriptionResult(
                "sensevoice", 10.0, "hello",
                List.of(new CallRecordStateMachine.TranscriptSegment(0.0, 10.0, "hello")),
                NOW);
    }

    private static VersionCapture captureSuccessfulUpdates(CallRecordMapper mapper, int count) {
        VersionCapture capture = new VersionCapture(count);
        when(mapper.replace(any(CallRecordEntity.class), anyLong())).thenAnswer(invocation -> {
            capture.add(invocation.getArgument(1));
            return 1;
        });
        return capture;
    }

    private static final class VersionCapture {
        private final Queue<Long> versions = new ConcurrentLinkedQueue<>();
        private final CountDownLatch latch;

        private VersionCapture(int count) {
            this.latch = new CountDownLatch(count);
        }

        private void add(long version) {
            versions.add(version);
            latch.countDown();
        }

        private boolean await() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }

        private List<Long> values() {
            return List.copyOf(versions);
        }
    }
}
