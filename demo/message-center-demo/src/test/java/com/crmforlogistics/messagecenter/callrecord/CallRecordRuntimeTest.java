package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRecordRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final String ANCHOR = "phone:8613800000000";

    @TempDir
    Path tempDir;

    @Test
    void lockConflictDegradesOnlyTheCallRecordSubsystem() throws Exception {
        Config config = config(tempDir);
        try (FileCallRecordRepository ignored =
                     FileCallRecordRepository.open(config, fixedClock());
             CallRecordRuntime runtime = CallRecordRuntime.open(
                     config, new UnifiedMessageStore(config), event -> {})) {
            assertFalse(runtime.available());
            assertEquals("CALL_RECORD_STORE_BUSY", runtime.startupFailure().code());
            assertNull(runtime.service());
            assertEquals(0, new UnifiedMessageStore(config).contacts().size());
        }
    }

    @Test
    void missingOptionalPhoneDependencyDegradesOnlyTheCallRecordSubsystem() throws Exception {
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "CALL_RECORD_DATA_DIR", tempDir.toString(),
                "FUNASR_BASE_URL", "http://funasr:8000")) {
            @Override public long callRecordMaxAudioBytes() {
                throw new NoClassDefFoundError("com/mpatric/mp3agic/InvalidDataException");
            }
        };

        try (CallRecordRuntime runtime = CallRecordRuntime.open(
                config, new UnifiedMessageStore(config), event -> {})) {
            assertFalse(runtime.available());
            assertEquals("CALL_RECORD_DEPENDENCY_MISSING", runtime.startupFailure().code());
            assertEquals(503, runtime.startupFailure().httpStatus());
        }
    }

    @Test
    void recoversProcessingBeforeStartingWorkerAndReleasesLockOnClose() throws Exception {
        Config config = config(tempDir);
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440200");
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(processingRecord(tempDir, id));
        }
        CountDownLatch completed = new CountDownLatch(1);
        CallRecordRuntime runtime = CallRecordRuntime.openForTests(
                config, contactId -> List.of(ANCHOR), (path, model) -> result(),
                fixedClock(), event -> {
                    if ("completed".equals(event.state())) completed.countDown();
                });
        assertTrue(runtime.available());
        assertNull(runtime.startupFailure());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        CallRecord completedRecord = runtime.service().detail(id, Set.of(ANCHOR));
        assertEquals("completed", completedRecord.transcription().state());
        assertEquals(2, completedRecord.transcription().attempts());
        runtime.close();

        try (FileCallRecordRepository reopened =
                     FileCallRecordRepository.open(config, fixedClock())) {
            assertEquals("completed", reopened.find(id).orElseThrow()
                    .transcription().state());
        }
    }

    @Test
    void corruptStoreReturnsStableStartupFailureWithoutLeakingExceptionText() throws Exception {
        Config config = config(tempDir);
        Files.createDirectories(tempDir.resolve("records"));
        Files.writeString(tempDir.resolve("records")
                        .resolve("550e8400-e29b-41d4-a716-446655440201.json"),
                "{sensitive-broken-json", StandardCharsets.UTF_8);

        try (CallRecordRuntime runtime = CallRecordRuntime.open(
                config, new UnifiedMessageStore(config), event -> {})) {
            assertFalse(runtime.available());
            assertEquals("CALL_RECORD_STORE_CORRUPT", runtime.startupFailure().code());
            assertFalse(runtime.startupFailure().getMessage().contains("sensitive"));
        }
    }

    @Test
    void closeTimeoutKeepsRepositoryLockUntilWorkerActuallyStops() throws Exception {
        Config config = config(tempDir);
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440202");
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(processingRecord(tempDir, id));
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CallRecordRuntime runtime = CallRecordRuntime.openForTests(
                config, contactId -> List.of(ANCHOR), (path, model) -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a dependency that does not honor cancellation.
                        }
                    }
                    return result();
                }, fixedClock(), ignored -> {});
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        assertEquals("CALL_RECORD_WORKER_STOP_TIMEOUT",
                assertThrows(CallRecordException.class, runtime::close).code());
        assertEquals("CALL_RECORD_STORE_BUSY", assertThrows(CallRecordException.class,
                () -> FileCallRecordRepository.open(config, fixedClock())).code());

        release.countDown();
        runtime.close();
        try (FileCallRecordRepository ignored =
                     FileCallRecordRepository.open(config, fixedClock())) {
            assertEquals("processing", ignored.find(id).orElseThrow()
                    .transcription().state());
        }
    }

    private static Config config(Path root) {
        return new Config(Map.ofEntries(
                Map.entry("DATA_DIR", root.toString()),
                Map.entry("EMAIL_DATA_DIR", root.resolve("email").toString()),
                Map.entry("CHATAPP_DATA_FILE", root.resolve("chatapp.jsonl").toString()),
                Map.entry("CHATAPP_TEMPLATE_FILE", root.resolve("templates.json").toString()),
                Map.entry("WECOM_DATA_FILE", root.resolve("wecom.jsonl").toString()),
                Map.entry("CALL_RECORD_DATA_DIR", root.toString()),
                Map.entry("CALL_RECORD_WORKER_CONCURRENCY", "1"),
                Map.entry("CALL_RECORD_QUEUE_CAPACITY", "8"),
                Map.entry("CALL_RECORD_LEASE_SECONDS", "60"),
                Map.entry("CALL_RECORD_MAX_RECORDS", "100"),
                Map.entry("FUNASR_BASE_URL", "http://funasr:8000")));
    }

    private static CallRecord processingRecord(Path root, UUID id) throws Exception {
        Path audio = root.resolve("audio").resolve(id + ".mp3");
        Files.createDirectories(audio.getParent());
        Files.write(audio, new byte[]{1, 2, 3});
        return new CallRecord(
                id, ANCHOR, ANCHOR, "inbound", NOW, NOW, "zhangsan", "request-1",
                new AudioAsset("audio/" + id + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 1.0),
                new Transcription("processing", "sensevoice", 1,
                        new CallRecordLease("dead-lease", "dead-worker", NOW.minusSeconds(1)),
                        null, null, null), List.of(), null, 1);
    }

    private static TranscriptionResult result() {
        return new TranscriptionResult(
                "sensevoice", 1.0, "你好",
                List.of(new TranscriptSegment(0.0, 1.0, "你好")), NOW);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
