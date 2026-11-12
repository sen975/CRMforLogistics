package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStatusReporterTest {
    private static final Instant NOW = Instant.parse("2026-08-13T01:00:00Z");
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void reportsHealthyMissingStreamsWithoutCreatingDirectories() {
        Path dataDir = tempDir.resolve("missing").resolve("data");
        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(dataDir.resolve("wecom-authorization-audit.jsonl")),
                directory -> 1_000_000L).report();

        assertEquals("healthy", report.status());
        assertEquals(0, report.exitCode());
        assertEquals(NOW.toString(), report.checkedAt());
        assertEquals(2, report.streams().size());
        assertTrue(report.issues().isEmpty());
        assertFalse(Files.exists(dataDir));
        for (AuditStatusReporter.StreamStatus stream : report.streams()) {
            assertEquals(0L, stream.currentBytes());
            assertEquals(0, stream.archiveCount());
            assertEquals(0L, stream.archiveBytes());
            assertEquals(0, stream.recoveryFileCount());
            assertEquals(0, stream.unknownFileCount());
            assertEquals(8_192L, stream.remainingBudgetBytes());
        }
        AuditStatusReporter.StreamStatus viewer = report.streams().get(0);
        assertEquals("wecom-viewer", viewer.name());
        assertEquals(0, viewer.openAttemptCount());
        assertEquals(0, viewer.staleOpenAttemptCount());
        assertNull(viewer.oldestStaleOpenAttemptAt());
    }

    @Test
    void countsFreshAndStaleAuthorizationAttemptsUsingSixtySecondGrace() throws Exception {
        Path dataDir = tempDir.resolve("attempts");
        Files.createDirectories(dataDir);
        Path authorization = dataDir.resolve("wecom-authorization-audit.jsonl");
        Files.writeString(authorization,
                accepted("a", NOW.minusSeconds(60), "corp-id-fresh")
                        + accepted("b", NOW.minusSeconds(61), "corp-id-stale"),
                StandardCharsets.UTF_8);

        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(authorization), directory -> 1_000_000L).report();

        AuditStatusReporter.StreamStatus stream = report.streams().get(1);
        assertEquals(2, stream.openAttemptCount());
        assertEquals(1, stream.staleOpenAttemptCount());
        assertEquals(NOW.minusSeconds(61).toString(), stream.oldestStaleOpenAttemptAt());
        assertEquals("degraded", report.status());
        assertEquals(2, report.exitCode());
        assertEquals(List.of(new AuditStatusReporter.Issue(
                "degraded", "wecom-authorization", "AUDIT_OPEN_ATTEMPT_STALE")),
                report.issues());
    }

    @Test
    void pendingStageKeepsTheAcceptedTimeForStaleness() throws Exception {
        Path dataDir = tempDir.resolve("pending-age");
        Files.createDirectories(dataDir);
        Path authorization = dataDir.resolve("wecom-authorization-audit.jsonl");
        String eventId = "sha256:" + "e".repeat(64);
        Files.writeString(authorization,
                authorizationStage(eventId, NOW.minusSeconds(120), "accepted")
                        + authorizationStage(eventId, NOW.minusSeconds(10), "pending"),
                StandardCharsets.UTF_8);

        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(authorization), directory -> 1_000_000L).report();

        AuditStatusReporter.StreamStatus stream = report.streams().get(1);
        assertEquals(1, stream.openAttemptCount());
        assertEquals(1, stream.staleOpenAttemptCount());
        assertEquals(NOW.minusSeconds(120).toString(), stream.oldestStaleOpenAttemptAt());
    }

    @Test
    void rejectsSucceededBeforePending() throws Exception {
        String eventId = "sha256:" + "f".repeat(64);
        assertInvalidAuthorizationSequence("succeeded-before-pending",
                authorizationStage(eventId, NOW.minusSeconds(2), "accepted")
                        + authorizationStage(eventId, NOW.minusSeconds(1), "succeeded"));
    }

    @Test
    void rejectsDuplicatePendingStage() throws Exception {
        String eventId = "sha256:" + "1".repeat(64);
        assertInvalidAuthorizationSequence("duplicate-pending",
                authorizationStage(eventId, NOW.minusSeconds(3), "accepted")
                        + authorizationStage(eventId, NOW.minusSeconds(2), "pending")
                        + authorizationStage(eventId, NOW.minusSeconds(1), "pending"));
    }

    @Test
    void rejectsFinalStageWithoutAccepted() throws Exception {
        String succeeded = "sha256:" + "2".repeat(64);
        assertInvalidAuthorizationSequence("succeeded-without-accepted",
                authorizationStage(succeeded, NOW.minusSeconds(1), "succeeded"));
        String failed = "sha256:" + "3".repeat(64);
        assertInvalidAuthorizationSequence("failed-without-accepted",
                authorizationStage(failed, NOW.minusSeconds(1), "failed"));
    }

    @Test
    void rejectsAcceptedAfterAttemptReachedFinalStage() throws Exception {
        String succeeded = "sha256:" + "4".repeat(64);
        assertInvalidAuthorizationSequence("accepted-after-succeeded",
                authorizationStage(succeeded, NOW.minusSeconds(4), "accepted")
                        + authorizationStage(succeeded, NOW.minusSeconds(3), "pending")
                        + authorizationStage(succeeded, NOW.minusSeconds(2), "succeeded")
                        + authorizationStage(succeeded, NOW.minusSeconds(1), "accepted"));
        String failed = "sha256:" + "5".repeat(64);
        assertInvalidAuthorizationSequence("accepted-after-failed",
                authorizationStage(failed, NOW.minusSeconds(3), "accepted")
                        + authorizationStage(failed, NOW.minusSeconds(2), "failed")
                        + authorizationStage(failed, NOW.minusSeconds(1), "accepted"));
    }

    @Test
    void reportsStableSanitizedJsonWithoutMutatingFiles() throws Exception {
        Path dataDir = tempDir.resolve("sanitized");
        Files.createDirectories(dataDir);
        Path viewer = dataDir.resolve("wecom-viewer-audit.jsonl");
        Path authorization = dataDir.resolve("wecom-authorization-audit.jsonl");
        Files.writeString(viewer,
                "{\"occurredAt\":\"2026-08-13T00:00:00Z\",\"session\":\"session-secret\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(authorization, accepted("c", NOW.minusSeconds(10), "corp-id-secret"),
                StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.gz"),
                "not-gzip", StandardCharsets.UTF_8);
        Map<String, FileFingerprint> before = fingerprintTree(dataDir);

        AuditStatusReporter.Report report = reporter(
                settings(viewer), settings(authorization), directory -> 1_000_000L).report();
        String json = GSON.toJson(report);

        assertEquals("degraded", report.status());
        assertEquals(2, report.exitCode());
        assertFalse(json.contains("eventId"));
        assertFalse(json.contains("corp-id-secret"));
        assertFalse(json.contains("session-secret"));
        assertEquals(before, fingerprintTree(dataDir));
        assertEquals(List.of(new AuditStatusReporter.Issue(
                "degraded", "wecom-viewer", "AUDIT_ARCHIVE_CORRUPTED")),
                report.issues());
    }

    @Test
    void classifiesBadCurrentAndExhaustedBudgetAsFailed() throws Exception {
        Path dataDir = tempDir.resolve("failed");
        Files.createDirectories(dataDir);
        Path viewer = dataDir.resolve("wecom-viewer-audit.jsonl");
        Path authorization = dataDir.resolve("wecom-authorization-audit.jsonl");
        Files.writeString(viewer, "broken", StandardCharsets.UTF_8);
        Files.write(dataDir.resolve("wecom-authorization-audit.notes"), new byte[8_192]);

        AuditStatusReporter.Report report = reporter(
                settings(viewer), settings(authorization), directory -> 1_000_000L).report();

        assertEquals("failed", report.status());
        assertEquals(3, report.exitCode());
        assertEquals(List.of(
                new AuditStatusReporter.Issue(
                        "failed", "wecom-authorization", "AUDIT_STREAM_BUDGET_EXCEEDED"),
                new AuditStatusReporter.Issue(
                        "failed", "wecom-viewer", "AUDIT_CURRENT_CORRUPTED"),
                new AuditStatusReporter.Issue(
                        "degraded", "wecom-authorization", "AUDIT_UNKNOWN_FILE")),
                report.issues());
        assertEquals(0L, report.streams().get(1).remainingBudgetBytes());
    }

    @Test
    void reportsLowDiskAsFailedForEachStream() {
        Path dataDir = tempDir.resolve("low-disk");
        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(dataDir.resolve("wecom-authorization-audit.jsonl")),
                directory -> 4_095L).report();

        assertEquals("failed", report.status());
        assertEquals(List.of(
                new AuditStatusReporter.Issue(
                        "failed", "wecom-authorization", "AUDIT_DISK_SPACE_LOW"),
                new AuditStatusReporter.Issue(
                        "failed", "wecom-viewer", "AUDIT_DISK_SPACE_LOW")),
                report.issues());
    }

    @Test
    void reportsNonDirectoryAncestorAsFailedWithoutProbingDisk() throws Exception {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not-a-directory", StandardCharsets.UTF_8);
        Path dataDir = blocked.resolve("data");

        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(dataDir.resolve("wecom-authorization-audit.jsonl")),
                directory -> {
                    throw new AssertionError("disk probe must not run for an unusable directory");
                }).report();

        assertEquals("failed", report.status());
        assertEquals(List.of(
                new AuditStatusReporter.Issue(
                        "failed", "wecom-authorization", "AUDIT_DIRECTORY_NOT_WRITABLE"),
                new AuditStatusReporter.Issue(
                        "failed", "wecom-viewer", "AUDIT_DIRECTORY_NOT_WRITABLE")),
                report.issues());
    }

    @Test
    void reportsOnlyBusyWhenCurrentChangesDuringAllThreeAttempts() throws Exception {
        Path dataDir = tempDir.resolve("busy");
        Files.createDirectories(dataDir);
        Path viewer = dataDir.resolve("wecom-viewer-audit.jsonl");
        Files.writeString(viewer, viewerLine(0), StandardCharsets.UTF_8);
        int[] attempts = {0};
        AuditStatusReporter reporter = new AuditStatusReporter(
                settings(viewer), settings(dataDir.resolve("wecom-authorization-audit.jsonl")),
                clock(), directory -> 1_000_000L,
                (path, attempt) -> {
                    if (path.equals(viewer)) {
                        attempts[0]++;
                        Files.writeString(path, viewerLine(attempt + 1),
                                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                });

        AuditStatusReporter.Report report = reporter.report();

        assertEquals(3, attempts[0]);
        assertEquals("degraded", report.status());
        assertEquals(List.of(new AuditStatusReporter.Issue(
                "degraded", "wecom-viewer", "AUDIT_SCAN_BUSY")), report.issues());
        assertFalse(GSON.toJson(report).contains("AUDIT_CURRENT_CORRUPTED"));
    }

    @Test
    void deduplicatesIssuesAndSortsFailedBeforeDegradedThenStreamAndCode() throws Exception {
        Path dataDir = tempDir.resolve("ordered");
        Files.createDirectories(dataDir);
        Path viewer = dataDir.resolve("wecom-viewer-audit.jsonl");
        Files.writeString(dataDir.resolve("wecom-viewer-audit.2026-08-11.001.jsonl.gz"),
                "bad-one", StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.gz"),
                "bad-two", StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("wecom-authorization-audit.jsonl"),
                accepted("d", NOW.minusSeconds(61), "corp-id"), StandardCharsets.UTF_8);

        AuditStatusReporter.Report report = reporter(settings(viewer),
                settings(dataDir.resolve("wecom-authorization-audit.jsonl")),
                directory -> 4_095L).report();

        assertEquals(List.of(
                new AuditStatusReporter.Issue(
                        "failed", "wecom-authorization", "AUDIT_DISK_SPACE_LOW"),
                new AuditStatusReporter.Issue(
                        "failed", "wecom-viewer", "AUDIT_DISK_SPACE_LOW"),
                new AuditStatusReporter.Issue(
                        "degraded", "wecom-authorization", "AUDIT_OPEN_ATTEMPT_STALE"),
                new AuditStatusReporter.Issue(
                        "degraded", "wecom-viewer", "AUDIT_ARCHIVE_CORRUPTED")),
                report.issues());
    }

    private AuditStatusReporter reporter(AuditFileSettings viewer,
                                         AuditFileSettings authorization,
                                         AuditDiskSpaceProbe diskSpaceProbe) {
        return new AuditStatusReporter(viewer, authorization, clock(), diskSpaceProbe,
                (path, attempt) -> { });
    }

    private void assertInvalidAuthorizationSequence(String directoryName, String content)
            throws Exception {
        Path dataDir = tempDir.resolve(directoryName);
        Files.createDirectories(dataDir);
        Path authorization = dataDir.resolve("wecom-authorization-audit.jsonl");
        Files.writeString(authorization, content, StandardCharsets.UTF_8);

        AuditStatusReporter.Report report = reporter(
                settings(dataDir.resolve("wecom-viewer-audit.jsonl")),
                settings(authorization), directory -> 1_000_000L).report();

        assertEquals("failed", report.status());
        assertEquals(List.of(new AuditStatusReporter.Issue(
                "failed", "wecom-authorization", "AUDIT_AUTHORIZATION_ENTRY_INVALID")),
                report.issues());
    }

    private AuditFileSettings settings(Path path) {
        return new AuditFileSettings(path, 7, 4_096L, 8_192L,
                4_096L, Duration.ofHours(1));
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String viewerLine(int sequence) {
        return "{\"occurredAt\":\"2026-08-13T00:00:0" + sequence + "Z\"}\n";
    }

    private static String accepted(String suffix, Instant callbackTimestamp, String corpId) {
        return "{\"occurredAt\":\"" + callbackTimestamp + "\","
                + "\"eventId\":\"sha256:" + suffix.repeat(64) + "\","
                + "\"attempt\":1,\"action\":\"wecom.authorization.create_auth\","
                + "\"result\":\"accepted\",\"suiteId\":\"suite-id\","
                + "\"authCorpId\":\"" + corpId + "\","
                + "\"callbackTimestamp\":\"" + callbackTimestamp + "\"}\n";
    }

    private static String authorizationStage(String eventId, Instant occurredAt, String result) {
        String pending = "pending".equals(result)
                ? ",\"targetStatus\":\"ACTIVE\",\"expectedVersion\":0" : "";
        return "{\"occurredAt\":\"" + occurredAt + "\","
                + "\"eventId\":\"" + eventId + "\",\"attempt\":1,"
                + "\"action\":\"wecom.authorization.create_auth\","
                + "\"result\":\"" + result + "\",\"suiteId\":\"suite-id\","
                + "\"authCorpId\":\"corp-id\","
                + "\"callbackTimestamp\":\"2026-08-13T00:58:00Z\"" + pending + "}\n";
    }

    private static Map<String, FileFingerprint> fingerprintTree(Path root) throws IOException {
        Map<String, FileFingerprint> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                result.put(root.relativize(path).toString(), new FileFingerprint(
                        attributes.isDirectory(), attributes.size(), attributes.lastModifiedTime().toMillis()));
            }
        }
        return result;
    }

    private record FileFingerprint(boolean directory, long bytes, long modifiedAtMillis) { }
}
