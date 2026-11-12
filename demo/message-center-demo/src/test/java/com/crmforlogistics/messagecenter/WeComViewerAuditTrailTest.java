package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComViewerAuditTrailTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsSafeUpstreamDiagnosticsInConfiguredAuditFile() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        AuditWarningReporter reporter = new AuditWarningReporter(
                Clock.fixed(Instant.parse("2026-07-31T05:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(1), ignored -> { });
        List<byte[]> writes = new ArrayList<>();
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(
                Clock.fixed(Instant.parse("2026-07-31T05:00:00Z"), ZoneOffset.UTC),
                (bytes, durability) -> writes.add(bytes), reporter);

        trail.recordDiagnostic("wecom.viewer.login_exchange", "failed", "", "", "",
                "WECOM_UPSTREAM_UNAVAILABLE", 48002, "/cgi-bin/gettoken", 200,
                "abc123");

        String line = new String(writes.get(0));
        assertTrue(line.contains("\"upstreamErrcode\":48002"));
        assertTrue(line.contains("/cgi-bin/gettoken"));
        assertTrue(line.contains("\"upstreamHint\":\"abc123\""));
        assertFalse(line.contains("sensitive-ticket"));
        assertFalse(line.contains("suite-secret"));
    }

    @Test
    void storageFailureIsBestEffortAndRecoveryIsReportedOnce() {
        List<String> warnings = new ArrayList<>();
        AuditWarningReporter reporter = new AuditWarningReporter(
                Clock.systemUTC(), Duration.ofHours(1), warnings::add);
        AtomicBoolean failing = new AtomicBoolean(true);
        WeComViewerAuditTrail.AuditWriter writer = (bytes, durability) -> {
            if (failing.get()) {
                throw new AuditStorageException("AUDIT_DISK_SPACE_LOW", "wecom-viewer", null);
            }
        };
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(
                Clock.systemUTC(), writer, reporter);

        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        assertEquals(1, warnings.size());
        failing.set(false);
        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        assertEquals(2, warnings.size());
    }

    @Test
    void unknownStorageCodeStillDoesNotBreakViewerOperation() {
        List<String> warnings = new ArrayList<>();
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(
                Clock.systemUTC(), (bytes, durability) -> {
                    throw new AuditStorageException("UNEXPECTED_STORAGE_FAILURE", "wecom-viewer", null);
                }, new AuditWarningReporter(Clock.systemUTC(), Duration.ofHours(1), warnings::add));

        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("AUDIT_ROTATION_FAILED"));
    }

    @Test
    void successfulWriteReportsRecoveryForEachFailedStorageCode() {
        List<String> warnings = new ArrayList<>();
        AuditWarningReporter reporter = new AuditWarningReporter(
                Clock.systemUTC(), Duration.ofHours(1), warnings::add);
        ArrayDeque<String> failures = new ArrayDeque<>(List.of(
                "AUDIT_DISK_SPACE_LOW", "AUDIT_STREAM_BUDGET_EXCEEDED"));
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(
                Clock.systemUTC(), (bytes, durability) -> {
                    String code = failures.pollFirst();
                    if (code != null) throw new AuditStorageException(code, "wecom-viewer", null);
                }, reporter);

        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");
        trail.record("wecom.viewer.component_error", "failed", "user-1", "", "");

        assertEquals(4, warnings.size());
        assertEquals(2, warnings.stream().filter(line -> line.contains("\"status\":\"recovered\"")).count());
    }

    @Test
    void runtimeRecoversPendingViewerArchiveBeforeEnablingCleanup() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Path rotating = tempDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.rotating");
        Files.writeString(rotating,
                "{\"occurredAt\":\"2026-08-12T05:00:00Z\",\"action\":\"viewer\"}\n");
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString(),
                "AUDIT_FILE_MAX_BYTES", "4096",
                "AUDIT_STREAM_MAX_BYTES", "131072",
                "AUDIT_MIN_FREE_DISK_BYTES", "4096"));

        try (AuditRuntime runtime = AuditRuntime.openViewer(
                config,
                Clock.fixed(Instant.parse("2026-08-13T05:00:00Z"), ZoneOffset.UTC),
                ignored -> Long.MAX_VALUE,
                ignored -> { })) {
            assertEquals(BoundedAuditFile.StartupState.RETENTION_CLEANUP_ENABLED,
                    runtime.viewerStartupState());
        }

        Path archive = tempDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.gz");
        assertTrue(Files.exists(archive));
        assertFalse(Files.exists(rotating));
    }

    @Test
    void runtimeReleasesWriterWhenStartupRecoveryFails() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Path rotating = tempDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.rotating");
        Files.writeString(rotating,
                "{\"occurredAt\":\"2026-08-12T05:00:00Z\",\"action\":\"viewer\"}\n");
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString(),
                "AUDIT_FILE_MAX_BYTES", "4096",
                "AUDIT_STREAM_MAX_BYTES", "131072",
                "AUDIT_MIN_FREE_DISK_BYTES", "4096"));
        Clock firstClock = Clock.fixed(Instant.parse("2026-08-13T05:00:00Z"), ZoneOffset.UTC);

        assertThrows(AuditStorageException.class, () -> AuditRuntime.openViewer(
                config, firstClock, ignored -> 0L, ignored -> { }));

        try (AuditRuntime ignored = AuditRuntime.openViewer(
                config,
                Clock.fixed(Instant.parse("2026-08-13T05:00:01Z"), ZoneOffset.UTC),
                path -> Long.MAX_VALUE,
                line -> { })) {
            assertTrue(Files.exists(auditFile));
        }
    }

    @Test
    void runtimeRejectsCorruptCurrentAndReleasesWriter() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Files.writeString(auditFile, "not-json\n");
        Config config = runtimeConfig(auditFile);

        AuditStorageException failure = assertThrows(AuditStorageException.class,
                () -> AuditRuntime.openViewer(config));
        assertEquals("AUDIT_CURRENT_CORRUPTED", failure.code());

        Files.delete(auditFile);
        try (AuditRuntime ignored = AuditRuntime.openViewer(config)) {
            assertTrue(Files.exists(auditFile));
        }
    }

    @Test
    void runtimeRejectsCorruptArchiveAndReleasesWriter() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Path archive = tempDir.resolve("wecom-viewer-audit.2026-08-12.001.jsonl.gz");
        Files.writeString(archive, "not-gzip");
        Config config = runtimeConfig(auditFile);

        AuditStorageException failure = assertThrows(AuditStorageException.class,
                () -> AuditRuntime.openViewer(config));
        assertEquals("AUDIT_ARCHIVE_CORRUPTED", failure.code());

        Files.delete(archive);
        try (AuditRuntime ignored = AuditRuntime.openViewer(config)) {
            assertTrue(Files.exists(auditFile));
        }
    }

    @Test
    void runtimeRejectsBusyCatalogBeforeEnablingCleanup() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Config config = runtimeConfig(auditFile);
        AuditFileCatalog.AuditFileSnapshot busy = new AuditFileCatalog.AuditFileSnapshot(
                List.of(), List.of(), List.of(), List.of(), 0L, Set.of("AUDIT_SCAN_BUSY"));

        AuditStorageException failure = assertThrows(AuditStorageException.class,
                () -> AuditRuntime.openViewer(config, Clock.systemUTC(), ignored -> Long.MAX_VALUE,
                        ignored -> { }, (settings, clock) -> busy));

        assertEquals("AUDIT_SCAN_BUSY", failure.code());
        try (AuditRuntime ignored = AuditRuntime.openViewer(config)) {
            assertTrue(Files.exists(auditFile));
        }
    }

    private Config runtimeConfig(Path auditFile) {
        return new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString(),
                "AUDIT_FILE_MAX_BYTES", "4096",
                "AUDIT_STREAM_MAX_BYTES", "131072",
                "AUDIT_MIN_FREE_DISK_BYTES", "4096"));
    }
}
