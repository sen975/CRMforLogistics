package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComViewerAuditTrailTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsSafeUpstreamDiagnosticsInConfiguredAuditFile() throws Exception {
        Path auditFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString()));
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(
                config, Clock.fixed(Instant.parse("2026-07-31T05:00:00Z"), ZoneOffset.UTC));

        trail.recordDiagnostic("wecom.viewer.login_exchange", "failed", "", "", "",
                "WECOM_UPSTREAM_UNAVAILABLE", 48002, "/cgi-bin/gettoken", 200,
                "abc123");

        String line = Files.readString(auditFile);
        assertTrue(line.contains("\"upstreamErrcode\":48002"));
        assertTrue(line.contains("/cgi-bin/gettoken"));
        assertTrue(line.contains("\"upstreamHint\":\"abc123\""));
        assertFalse(line.contains("sensitive-ticket"));
        assertFalse(line.contains("suite-secret"));
    }
}
