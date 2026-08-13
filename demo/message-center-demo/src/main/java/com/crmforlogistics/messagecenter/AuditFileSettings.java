package com.crmforlogistics.messagecenter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

record AuditFileSettings(Path file, int retentionDays, long fileMaxBytes,
                         long streamMaxBytes, long minFreeDiskBytes,
                         Duration warningInterval) {
    AuditFileSettings {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        warningInterval = Objects.requireNonNull(warningInterval, "warningInterval");
        if (!file.getFileName().toString().endsWith(".jsonl")) {
            throw new IllegalArgumentException("audit file must end with .jsonl");
        }
        if (retentionDays < 1 || retentionDays > 365
                || fileMaxBytes < 4_096L || fileMaxBytes > 20_971_520L
                || streamMaxBytes < fileMaxBytes || streamMaxBytes > 1_073_741_824L
                || minFreeDiskBytes < fileMaxBytes || minFreeDiskBytes > 1_099_511_627_776L
                || warningInterval.compareTo(Duration.ofSeconds(60)) < 0
                || warningInterval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("audit settings are invalid");
        }
    }
}
