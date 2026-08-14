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
    private final BoundedAuditFile authorizationWriter;
    private final WeComAuthorizationAuditTrail authorizationTrail;

    private AuditRuntime(BoundedAuditFile viewerWriter, WeComViewerAuditTrail viewerTrail,
                         BoundedAuditFile authorizationWriter,
                         WeComAuthorizationAuditTrail authorizationTrail) {
        this.viewerWriter = viewerWriter;
        this.viewerTrail = viewerTrail;
        this.authorizationWriter = authorizationWriter;
        this.authorizationTrail = authorizationTrail;
    }

    static AuditRuntime open(Config config) throws AuditStorageException {
        return open(config, Clock.systemUTC(), AuditDiskSpaceProbe.system(),
                line -> System.err.println(line));
    }

    static AuditRuntime open(Config config, Clock clock,
                             AuditDiskSpaceProbe diskSpaceProbe,
                             Consumer<String> warningSink) throws AuditStorageException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(diskSpaceProbe, "diskSpaceProbe");
        Objects.requireNonNull(warningSink, "warningSink");
        config.validateAuditConfiguration();
        AuditRuntime viewer = openViewer(config, clock, diskSpaceProbe, warningSink);
        BoundedAuditFile authorizationWriter = null;
        try {
            AuditFileSettings settings = config.authorizationAuditSettings();
            authorizationWriter = BoundedAuditFile.acquire(
                    "wecom-authorization", settings, clock, diskSpaceProbe);
            authorizationWriter.prepareForAuthorizationRecovery();
            BoundedAuditFile finalWriter = authorizationWriter;
            WeComAuthorizationAuditTrail authorizationTrail =
                    WeComAuthorizationAuditTrail.open(settings, clock,
                            line -> finalWriter.append(
                                    line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    BoundedAuditFile.Durability.REQUIRED));
            return new AuditRuntime(viewer.viewerWriter, viewer.viewerTrail,
                    authorizationWriter, authorizationTrail);
        } catch (AuditStorageException | RuntimeException failure) {
            if (authorizationWriter != null) {
                try {
                    authorizationWriter.close();
                } catch (AuditStorageException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                viewer.close();
            } catch (AuditStorageException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
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
            return new AuditRuntime(writer,
                    new WeComViewerAuditTrail(clock, writer::append, warnings), null, null);
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

    WeComAuthorizationAuditTrail authorizationTrail() {
        if (authorizationTrail == null) {
            throw new IllegalStateException("authorization audit trail is unavailable");
        }
        return authorizationTrail;
    }

    void enableAuthorizationRetentionCleanup() {
        if (authorizationWriter == null) {
            throw new IllegalStateException("authorization audit writer is unavailable");
        }
        authorizationWriter.enableRetentionCleanup();
    }

    BoundedAuditFile.StartupState viewerStartupState() {
        return viewerWriter.startupState();
    }

    @Override
    public void close() throws AuditStorageException {
        AuditStorageException failure = null;
        if (authorizationWriter != null) {
            try {
                authorizationWriter.close();
            } catch (AuditStorageException closeFailure) {
                failure = closeFailure;
            }
        }
        try {
            viewerWriter.close();
        } catch (AuditStorageException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
