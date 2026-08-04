package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCallRecordRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final String ANCHOR = "phone:8613800000000";

    @TempDir
    Path tempDir;

    @Test
    void savesReadsIndexesAndCompareAndSwapsRecords() throws Exception {
        Config config = config(tempDir, 10);
        CallRecord record = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                ANCHOR, "request-1", NOW.minusSeconds(30), NOW.minusSeconds(30));
        createAudio(tempDir, record.id());

        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(record);

            assertEquals(record, repository.find(record.id()).orElseThrow());
            assertEquals(record.id(), repository.findByIdempotency(
                    ANCHOR, "request-1").orElseThrow().id());
            assertEquals(List.of(record.id()), repository.listByAnchors(Set.of(ANCHOR))
                    .stream().map(CallRecord::id).toList());
            assertEquals(1, repository.countPending());

            CallRecord leased = CallRecordStateMachine.lease(
                    record, "worker-1", NOW, NOW.plusSeconds(60));
            CallRecordException stale = assertThrows(CallRecordException.class,
                    () -> repository.replace(leased, record.version() + 1));
            assertEquals("CALL_RECORD_VERSION_CONFLICT", stale.code());

            assertEquals(leased, repository.replace(leased, record.version()));
            assertEquals(leased, repository.find(record.id()).orElseThrow());
        }

        Path recordFile = tempDir.resolve("records").resolve(record.id() + ".json");
        Path indexFile = tempDir.resolve("indexes").resolve(sha256(ANCHOR) + ".json");
        assertTrue(Files.isRegularFile(recordFile));
        assertTrue(Files.isRegularFile(indexFile));
        assertTrue(Files.readString(indexFile).contains(ANCHOR));
    }

    @Test
    void persistsNoteInRecordSnapshotAndReloadsIt() throws Exception {
        Config config = config(tempDir, 10);
        UUID recordId = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
        CallRecord record = new CallRecord(
                recordId, ANCHOR, ANCHOR, "inbound", NOW, NOW,
                "zhangsan", "request-note",
                new AudioAsset("audio/" + recordId + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 2.0),
                new Transcription("queued", "sensevoice", 0, null,
                        NOW, null, null),
                List.of(), null, 1, "录音备注");
        createAudio(tempDir, record.id());

        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(record);
        }

        Path snapshot = tempDir.resolve("records").resolve(record.id() + ".json");
        assertTrue(Files.readString(snapshot).contains("录音备注"));
        try (FileCallRecordRepository restarted =
                     FileCallRecordRepository.open(config, fixedClock())) {
            assertEquals("录音备注", restarted.find(record.id()).orElseThrow().note());
        }
    }

    @Test
    void preservesTheFirstIdempotencyWinner() throws Exception {
        CallRecord winner = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
                ANCHOR, "same-request", NOW.minusSeconds(20), NOW.minusSeconds(20));
        CallRecord contender = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
                ANCHOR, "same-request", NOW.minusSeconds(10), NOW.minusSeconds(10));
        createAudio(tempDir, winner.id());
        createAudio(tempDir, contender.id());

        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config(tempDir, 10), fixedClock())) {
            repository.saveNew(winner);
            CallRecordException conflict = assertThrows(CallRecordException.class,
                    () -> repository.saveNew(contender));

            assertEquals("CALL_RECORD_IDEMPOTENCY_CONFLICT", conflict.code());
            assertEquals(winner.id(), repository.findByIdempotency(
                    ANCHOR, "same-request").orElseThrow().id());
            assertTrue(repository.find(contender.id()).isEmpty());
            assertFalse(Files.exists(tempDir.resolve("records")
                    .resolve(contender.id() + ".json")));
        }
    }

    @Test
    void refusesSecondWriterAndRecoversEveryProcessingRecordAfterRestart() throws Exception {
        Config config = config(tempDir, 10);
        FileCallRecordRepository first = FileCallRecordRepository.open(config, fixedClock());
        try {
            CallRecordException busy = assertThrows(CallRecordException.class,
                    () -> FileCallRecordRepository.open(config, fixedClock()));
            assertEquals("CALL_RECORD_STORE_BUSY", busy.code());

            CallRecord one = processingRecord(
                    UUID.fromString("550e8400-e29b-41d4-a716-446655440003"), "request-1");
            CallRecord two = processingRecord(
                    UUID.fromString("550e8400-e29b-41d4-a716-446655440004"), "request-2");
            createAudio(tempDir, one.id());
            createAudio(tempDir, two.id());
            first.saveNew(one);
            first.saveNew(two);
        } finally {
            first.close();
        }

        try (FileCallRecordRepository restarted =
                     FileCallRecordRepository.open(config, fixedClock())) {
            List<CallRecord> recovered = restarted.recoverProcessing(NOW);
            assertEquals(2, recovered.size());
            assertTrue(recovered.stream().allMatch(
                    record -> "queued".equals(record.transcription().state())));
            assertTrue(recovered.stream().allMatch(
                    record -> record.transcription().lease() == null));
            assertTrue(recovered.stream().allMatch(
                    record -> record.transcription().nextAttemptAt().equals(NOW)));
        }
    }

    @Test
    void returnsOnlyDueQueuedRecordsInDeterministicBoundedOrder() throws Exception {
        CallRecord later = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440010"),
                ANCHOR, "later", NOW.minusSeconds(30), NOW.minusSeconds(5));
        CallRecord sameDueLaterCreated = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440011"),
                ANCHOR, "same-2", NOW.minusSeconds(10), NOW.minusSeconds(20));
        CallRecord sameDueEarlierCreated = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440012"),
                ANCHOR, "same-1", NOW.minusSeconds(20), NOW.minusSeconds(20));
        CallRecord future = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440013"),
                ANCHOR, "future", NOW, NOW.plusSeconds(1));

        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config(tempDir, 10), fixedClock())) {
            for (CallRecord record : List.of(later, sameDueLaterCreated, sameDueEarlierCreated, future)) {
                createAudio(tempDir, record.id());
                repository.saveNew(record);
            }

            assertEquals(List.of(sameDueEarlierCreated.id(), sameDueLaterCreated.id()),
                    repository.listRunnable(NOW, 2).stream().map(CallRecord::id).toList());
            assertEquals(4, repository.countPending());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.listRunnable(NOW, 0));
        }
    }

    @Test
    void rebuildsMissingAndCorruptDerivedIndexesFromRecordTruth() throws Exception {
        Config config = config(tempDir, 10);
        CallRecord record = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440020"),
                ANCHOR, "request-index", NOW, NOW);
        createAudio(tempDir, record.id());
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(record);
        }

        Path index = tempDir.resolve("indexes").resolve(sha256(ANCHOR) + ".json");
        Files.delete(index);
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            assertEquals(record.id(), repository.listByAnchors(Set.of(ANCHOR)).get(0).id());
        }
        assertTrue(Files.isRegularFile(index));

        Files.writeString(index, "{broken", StandardCharsets.UTF_8);
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            assertEquals(record.id(), repository.listByAnchors(Set.of(ANCHOR)).get(0).id());
        }
        assertTrue(Files.readString(index).contains(record.id().toString()));
    }

    @Test
    void failsClosedForCorruptRecordJsonAndMissingReferencedAudio() throws Exception {
        Config config = config(tempDir, 10);
        Files.createDirectories(tempDir.resolve("records"));
        Path corrupt = tempDir.resolve("records")
                .resolve("550e8400-e29b-41d4-a716-446655440030.json");
        Files.writeString(corrupt, "{broken", StandardCharsets.UTF_8);

        CallRecordException corruptError = assertThrows(CallRecordException.class,
                () -> FileCallRecordRepository.open(config, fixedClock()));
        assertEquals("CALL_RECORD_STORE_CORRUPT", corruptError.code());

        Files.delete(corrupt);
        CallRecord record = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440031"),
                ANCHOR, "request-audio", NOW, NOW);
        createAudio(tempDir, record.id());
        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(config, fixedClock())) {
            repository.saveNew(record);
        }
        Files.delete(tempDir.resolve(record.audio().relativePath()));

        CallRecordException missingAudio = assertThrows(CallRecordException.class,
                () -> FileCallRecordRepository.open(config, fixedClock()));
        assertEquals("CALL_RECORD_STORE_CORRUPT", missingAudio.code());
    }

    @Test
    void reconcilesOnlyStaleOrphanAudioAndTemporaryFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("audio"));
        Files.createDirectories(tempDir.resolve("tmp"));
        Path oldAudio = tempDir.resolve("audio")
                .resolve("550e8400-e29b-41d4-a716-446655440040.mp3");
        Path recentAudio = tempDir.resolve("audio")
                .resolve("550e8400-e29b-41d4-a716-446655440041.mp3");
        Path oldTemp = tempDir.resolve("tmp").resolve("old.upload");
        Path recentTemp = tempDir.resolve("tmp").resolve("recent.upload");
        for (Path file : List.of(oldAudio, recentAudio, oldTemp, recentTemp)) {
            Files.write(file, new byte[]{1});
        }
        Files.setLastModifiedTime(oldAudio, FileTime.from(NOW.minusSeconds(3_601)));
        Files.setLastModifiedTime(oldTemp, FileTime.from(NOW.minusSeconds(3_601)));
        Files.setLastModifiedTime(recentAudio, FileTime.from(NOW.minusSeconds(3_599)));
        Files.setLastModifiedTime(recentTemp, FileTime.from(NOW.minusSeconds(3_599)));

        try (FileCallRecordRepository ignored =
                     FileCallRecordRepository.open(config(tempDir, 10), fixedClock())) {
            assertFalse(Files.exists(oldAudio));
            assertFalse(Files.exists(oldTemp));
            assertTrue(Files.exists(recentAudio));
            assertTrue(Files.exists(recentTemp));
        }
    }

    @Test
    void enforcesTheConfiguredRecordLimitOnWriteAndStartup() throws Exception {
        Config oneRecord = config(tempDir, 1);
        CallRecord first = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440050"),
                ANCHOR, "request-1", NOW, NOW);
        CallRecord second = queuedRecord(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440051"),
                ANCHOR, "request-2", NOW, NOW);
        createAudio(tempDir, first.id());
        createAudio(tempDir, second.id());

        try (FileCallRecordRepository repository =
                     FileCallRecordRepository.open(oneRecord, fixedClock())) {
            repository.saveNew(first);
            CallRecordException limit = assertThrows(CallRecordException.class,
                    () -> repository.saveNew(second));
            assertEquals("CALL_RECORD_LIMIT_REACHED", limit.code());
        }

        try (FileCallRecordRepository repository = FileCallRecordRepository.open(
                config(tempDir, 2), fixedClock())) {
            repository.saveNew(second);
        }
        CallRecordException startupLimit = assertThrows(CallRecordException.class,
                () -> FileCallRecordRepository.open(oneRecord, fixedClock()));
        assertEquals("CALL_RECORD_STORE_CORRUPT", startupLimit.code());
    }

    private static Config config(Path root, int maxRecords) {
        return new Config(Map.of(
                "CALL_RECORD_DATA_DIR", root.toString(),
                "CALL_RECORD_MAX_RECORDS", Integer.toString(maxRecords)));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static CallRecord queuedRecord(UUID id, String anchor, String requestId,
                                           Instant createdAt, Instant nextAttemptAt) {
        return new CallRecord(
                id, anchor, anchor, "inbound", createdAt, createdAt,
                "zhangsan", requestId,
                new AudioAsset("audio/" + id + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 2.0),
                new Transcription("queued", "sensevoice", 0, null,
                        nextAttemptAt, null, null),
                List.of(), null, 1);
    }

    private static CallRecord processingRecord(UUID id, String requestId) {
        CallRecord queued = queuedRecord(id, ANCHOR, requestId, NOW.minusSeconds(60), NOW);
        return new CallRecord(
                queued.id(), queued.contactAnchorPointId(), queued.phonePointId(),
                queued.direction(), queued.occurredAt(), queued.createdAt(), queued.createdBy(),
                queued.clientRequestId(), queued.audio(),
                new Transcription("processing", "sensevoice", 1,
                        new CallRecordLease("dead-lease-" + id, "worker-1",
                                NOW.minusSeconds(1)),
                        null, null, null),
                queued.revisions(), queued.currentRevisionId(), queued.version());
    }

    private static void createAudio(Path root, UUID id) throws Exception {
        Path audio = root.resolve("audio").resolve(id + ".mp3");
        Files.createDirectories(audio.getParent());
        Files.write(audio, new byte[]{1, 2, 3});
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
