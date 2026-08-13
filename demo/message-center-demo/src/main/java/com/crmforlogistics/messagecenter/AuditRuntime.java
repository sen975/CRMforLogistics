package com.crmforlogistics.messagecenter;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

final class AuditRuntime implements AutoCloseable {
    @FunctionalInterface
    interface CatalogScanner {
        AuditFileCatalog.AuditFileSnapshot scan(AuditFileSettings settings, Clock clock)
                throws AuditStorageException;
    }

    private final BoundedAuditFile viewerWriter;
    private final WeComViewerAuditTrail viewerTrail;

    private AuditRuntime(BoundedAuditFile viewerWriter, WeComViewerAuditTrail viewerTrail) {
        this.viewerWriter = viewerWriter;
        this.viewerTrail = viewerTrail;
    }

    static AuditRuntime openViewer(Config config) throws AuditStorageException {
        return openViewer(config, Clock.systemUTC(), AuditDiskSpaceProbe.system(),
                line -> System.err.println(line));
    }

    static AuditRuntime openViewer(Config config, Clock clock,
                                   AuditDiskSpaceProbe diskSpaceProbe,
                                   Consumer<String> warningSink) throws AuditStorageException {
        return openViewer(config, clock, diskSpaceProbe, warningSink,
                AuditFileCatalog::scanStable);
    }

    static AuditRuntime openViewer(Config config, Clock clock,
                                   AuditDiskSpaceProbe diskSpaceProbe,
                                   Consumer<String> warningSink,
                                   CatalogScanner catalogScanner) throws AuditStorageException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(diskSpaceProbe, "diskSpaceProbe");
        Objects.requireNonNull(warningSink, "warningSink");
        Objects.requireNonNull(catalogScanner, "catalogScanner");
        config.validateAuditConfiguration();
        AuditFileSettings settings = config.viewerAuditSettings();
        BoundedAuditFile writer = BoundedAuditFile.acquire(
                "wecom-viewer", settings, clock, diskSpaceProbe);
        try {
            writer.prepareForAuthorizationRecovery();
            AuditFileCatalog.AuditFileSnapshot snapshot = catalogScanner.scan(settings, clock);
            if (!snapshot.issueCodes().isEmpty()) {
                String issue = snapshot.issueCodes().stream().sorted().findFirst()
                        .orElse("AUDIT_CATALOG_SCAN_FAILED");
                throw new AuditStorageException(issue, "wecom-viewer", null);
            }
            writer.enableRetentionCleanup();
            AuditWarningReporter warnings = new AuditWarningReporter(
                    clock, settings.warningInterval(), warningSink);
            return new AuditRuntime(writer, new WeComViewerAuditTrail(clock, writer::append, warnings));
        } catch (AuditStorageException | RuntimeException failure) {
            try {
                writer.close();
            } catch (AuditStorageException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    ViewerAuditSink viewerTrail() {
        return viewerTrail;
    }

    BoundedAuditFile.StartupState viewerStartupState() {
        return viewerWriter.startupState();
    }

    @Override
    public void close() throws AuditStorageException {
        viewerWriter.close();
    }
}
