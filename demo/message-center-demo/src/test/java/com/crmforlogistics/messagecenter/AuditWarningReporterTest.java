package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditWarningReporterTest {
    @Test
    void limitsEachStreamAndCodeThenReportsOneRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        List<String> lines = new ArrayList<>();
        AuditWarningReporter reporter = new AuditWarningReporter(clock, Duration.ofHours(1), lines::add);
        reporter.reportFailure("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
        reporter.reportFailure("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"status\":\"degraded\""));
        reporter.reportRecovered("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
        reporter.reportRecovered("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
        assertEquals(2, lines.size());
        assertFalse(String.join("", lines).contains("user"));
    }

    @Test
    void requiredAuthorizationFailureUsesFailedStatus() {
        List<String> lines = new ArrayList<>();
        AuditWarningReporter reporter = new AuditWarningReporter(
                Clock.systemUTC(), Duration.ofHours(1), lines::add);

        reporter.reportFailure("wecom-authorization", "AUDIT_DISK_SPACE_LOW");

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"status\":\"failed\""));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
