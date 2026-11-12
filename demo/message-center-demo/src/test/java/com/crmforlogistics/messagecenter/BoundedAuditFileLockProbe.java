package com.crmforlogistics.messagecenter;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public final class BoundedAuditFileLockProbe {
    private BoundedAuditFileLockProbe() {
    }

    public static void main(String[] args) {
        Path file = Path.of(args[0]);
        AuditFileSettings settings = new AuditFileSettings(
                file, 30, 4_096L, 32_768L, 4_096L, Duration.ofMinutes(5));
        try (BoundedAuditFile ignored = BoundedAuditFile.acquire(
                "lock-probe", settings, Clock.systemUTC(), AuditDiskSpaceProbe.system())) {
            System.exit(0);
        } catch (AuditStorageException exception) {
            System.exit("AUDIT_WRITER_LOCKED".equals(exception.code()) ? 3 : 4);
        }
    }
}
