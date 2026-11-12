package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final String ANCHOR = "phone:8613800000000";

    @TempDir
    Path tempDir;

    @Test
    void completesQueuedRecordAndPublishesOnlyAfterDurableSave() throws Exception {
        Config config = config(tempDir, 1);
        MutableClock clock = new MutableClock(NOW);
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, clock)) {
            CallRecord queued = queuedRecord(tempDir,
                    UUID.fromString("550e8400-e29b-41d4-a716-446655440100"), "request-1");
            repository.saveNew(queued);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> eventFailure = new AtomicReference<>();
            TranscriptionWorker worker = new TranscriptionWorker(
                    repository, new LocalAudioStore(config),
                    (path, model) -> result(), config, clock, event -> {
                        if ("completed".equals(event.state())) {
                            try {
                                assertEquals("completed", repository.find(queued.id())
                                        .orElseThrow().transcription().state());
                                completed.countDown();
                            } catch (Exception exception) {
                                eventFailure.set(exception.getMessage());
                                completed.countDown();
                            }
                        }
                    });
            worker.start();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            worker.close();

            assertEquals(null, eventFailure.get());
            assertEquals("completed", repository.find(queued.id())
                    .orElseThrow().transcription().state());
        }
    }

    @Test
    void retriesAtFiveAndThirtySecondsThenStopsAfterThirdAttempt() throws Exception {
        Config config = config(tempDir, 1);
        MutableClock clock = new MutableClock(NOW);
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, clock)) {
            CallRecord queued = queuedRecord(tempDir,
                    UUID.fromString("550e8400-e29b-41d4-a716-446655440101"), "request-retry");
            repository.saveNew(queued);
            BlockingQueue<CallRecordEvent> events = new LinkedBlockingQueue<>();
            TranscriptionWorker worker = new TranscriptionWorker(
                    repository, new LocalAudioStore(config), (path, model) -> {
                throw new CallRecordException(
                        "FUNASR_TIMEOUT", 504, "FunASR timed out", true);
            }, config, clock, events::add);
            worker.start();

            CallRecord first = awaitState(repository, events, queued.id(), "queued");
            assertEquals(1, first.transcription().attempts());
            assertEquals(NOW.plusSeconds(5), first.transcription().nextAttemptAt());

            clock.set(NOW.plusSeconds(5));
            worker.signal();
            CallRecord second = awaitState(repository, events, queued.id(), "queued");
            assertEquals(2, second.transcription().attempts());
            assertEquals(NOW.plusSeconds(35), second.transcription().nextAttemptAt());

            clock.set(NOW.plusSeconds(35));
            worker.signal();
            CallRecord failed = awaitState(repository, events, queued.id(), "failed");
            assertEquals(3, failed.transcription().attempts());
            worker.close();
        }
    }

    @Test
    void neverRunsMoreThanConfiguredConcurrency() throws Exception {
        Config config = config(tempDir, 2);
        MutableClock clock = new MutableClock(NOW);
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, clock)) {
            for (int index = 0; index < 3; index++) {
                repository.saveNew(queuedRecord(tempDir,
                        UUID.fromString("550e8400-e29b-41d4-a716-44665544011" + index),
                        "request-" + index));
            }
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch firstTwoStarted = new CountDownLatch(2);
            CountDownLatch thirdStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(3);
            TranscriptionWorker worker = new TranscriptionWorker(
                    repository, new LocalAudioStore(config), (path, model) -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                int call = calls.incrementAndGet();
                if (call <= 2) firstTwoStarted.countDown();
                else thirdStarted.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                    return result();
                } finally {
                    active.decrementAndGet();
                }
            }, config, clock, event -> {
                if ("completed".equals(event.state())) completed.countDown();
            });
            worker.start();

            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            assertFalse(thirdStarted.await(150, TimeUnit.MILLISECONDS));
            assertEquals(2, maximum.get());
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            worker.close();
        }
    }

    @Test
    void closeInterruptsInFlightWorkAndRestartRecoversItsLease() throws Exception {
        Config config = config(tempDir, 1);
        MutableClock clock = new MutableClock(NOW);
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440120");
        FileCallRecordRepository repository = FileCallRecordRepository.open(config, clock);
        repository.saveNew(queuedRecord(tempDir, id, "request-close"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        TranscriptionWorker worker = new TranscriptionWorker(
                repository, new LocalAudioStore(config), (path, model) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
                throw new CallRecordException(
                        "FUNASR_INTERRUPTED", 503, "Interrupted", true, exception);
            }
            throw new IllegalStateException("unreachable");
        }, config, clock, ignored -> {});
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        worker.close();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals("processing", repository.find(id).orElseThrow()
                .transcription().state());
        repository.close();

        try (FileCallRecordRepository restarted =
                     FileCallRecordRepository.open(config, clock)) {
            assertEquals("queued", restarted.recoverProcessing(clock.instant())
                    .get(0).transcription().state());
        }
    }

    @Test
    void closeDoesNotCommitResultWhenTranscriberSwallowsInterrupt() throws Exception {
        Config config = config(tempDir, 1);
        MutableClock clock = new MutableClock(NOW);
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440121");
        FileCallRecordRepository repository = FileCallRecordRepository.open(config, clock);
        repository.saveNew(queuedRecord(tempDir, id, "request-swallow-interrupt"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch swallowed = new CountDownLatch(1);
        TranscriptionWorker worker = new TranscriptionWorker(
                repository, new LocalAudioStore(config), (path, model) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
                swallowed.countDown();
                return result();
            }
            throw new IllegalStateException("unreachable");
        }, config, clock, ignored -> {});
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        worker.close();
        assertTrue(swallowed.await(2, TimeUnit.SECONDS));
        assertEquals("processing", repository.find(id).orElseThrow()
                .transcription().state());
        repository.close();
    }

    private static CallRecord awaitState(FileCallRecordRepository repository,
                                         BlockingQueue<CallRecordEvent> events,
                                         UUID id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            CallRecordEvent event = events.poll(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            if (event == null) break;
            if (id.toString().equals(event.callRecordId()) && state.equals(event.state())) {
                return repository.find(id).orElseThrow();
            }
        }
        throw new AssertionError("Timed out waiting for state " + state);
    }

    private static Config config(Path root, int concurrency) {
        return new Config(Map.of(
                "CALL_RECORD_DATA_DIR", root.toString(),
                "CALL_RECORD_WORKER_CONCURRENCY", Integer.toString(concurrency),
                "CALL_RECORD_QUEUE_CAPACITY", "8",
                "CALL_RECORD_LEASE_SECONDS", "60",
                "CALL_RECORD_MAX_ATTEMPTS", "3",
                "CALL_RECORD_MAX_RECORDS", "100"));
    }

    private static CallRecord queuedRecord(Path root, UUID id, String requestId)
            throws Exception {
        Path audio = root.resolve("audio").resolve(id + ".mp3");
        Files.createDirectories(audio.getParent());
        Files.write(audio, new byte[]{1, 2, 3});
        return new CallRecord(
                id, ANCHOR, ANCHOR, "inbound", NOW, NOW, "zhangsan", requestId,
                new AudioAsset("audio/" + id + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 1.0),
                new Transcription("queued", "sensevoice", 0,
                        null, NOW, null, null), List.of(), null, 1);
    }

    private static TranscriptionResult result() {
        return new TranscriptionResult(
                "sensevoice", 1.0, "你好",
                List.of(new TranscriptSegment(0.0, 1.0, "你好")), NOW);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
