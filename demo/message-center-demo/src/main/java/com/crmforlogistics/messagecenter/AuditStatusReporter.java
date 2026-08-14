package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** 只读审计状态报告；不获取 writer，也不修改审计目录。 */
final class AuditStatusReporter {
    private static final Comparator<Issue> ISSUE_ORDER = Comparator
            .comparingInt((Issue issue) -> "failed".equals(issue.severity()) ? 0 : 1)
            .thenComparing(Issue::stream)
            .thenComparing(Issue::code);

    @FunctionalInterface
    interface CurrentScanObserver {
        void afterValidation(Path path, int attempt) throws IOException;
    }

    record Issue(String severity, String stream, String code) { }

    record StreamStatus(String name, String currentFile, long currentBytes, int archiveCount,
                        long archiveBytes, int recoveryFileCount, int unknownFileCount,
                        int retentionDays, long streamMaxBytes, long remainingBudgetBytes,
                        int openAttemptCount, int staleOpenAttemptCount,
                        String oldestStaleOpenAttemptAt) { }

    record Report(String status, String checkedAt, List<StreamStatus> streams,
                  List<Issue> issues) {
        Report {
            streams = List.copyOf(streams);
            issues = List.copyOf(issues);
        }

        int exitCode() {
            return switch (status) {
                case "healthy" -> 0;
                case "degraded" -> 2;
                default -> 3;
            };
        }
    }

    private final AuditFileSettings viewer;
    private final AuditFileSettings authorization;
    private final Clock clock;
    private final AuditDiskSpaceProbe diskSpaceProbe;
    private final AuditFileCatalog.CurrentScanObserver scanObserver;

    AuditStatusReporter(AuditFileSettings viewer, AuditFileSettings authorization,
                        Clock clock, AuditDiskSpaceProbe diskSpaceProbe,
                        CurrentScanObserver scanObserver) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diskSpaceProbe = Objects.requireNonNull(diskSpaceProbe, "diskSpaceProbe");
        CurrentScanObserver observer = Objects.requireNonNull(scanObserver, "scanObserver");
        this.scanObserver = observer::afterValidation;
    }

    static AuditStatusReporter from(Config config, Clock clock,
                                    AuditDiskSpaceProbe diskSpaceProbe) {
        return new AuditStatusReporter(config.viewerAuditSettings(), config.authorizationAuditSettings(),
                clock, diskSpaceProbe, (path, attempt) -> { });
    }

    Report report() {
        Instant checkedAt = clock.instant();
        List<StreamStatus> streams = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        AuditFileCatalog.AuditFileSnapshot viewerSnapshot = scan("wecom-viewer", viewer, issues);
        streams.add(status("wecom-viewer", viewer, viewerSnapshot, issues, false));
        AuditFileCatalog.AuditFileSnapshot authSnapshot = scan(
                "wecom-authorization", authorization, issues);
        StreamStatus authorizationStatus = status("wecom-authorization", authorization,
                authSnapshot, issues, true);
        streams.add(authorizationStatus);
        addBudgetAndDiskIssues("wecom-viewer", viewer, viewerSnapshot, issues);
        addBudgetAndDiskIssues("wecom-authorization", authorization, authSnapshot, issues);
        List<Issue> ordered = normalizeIssues(issues);
        String overall = ordered.stream().anyMatch(issue -> "failed".equals(issue.severity()))
                ? "failed" : ordered.isEmpty() ? "healthy" : "degraded";
        return new Report(overall, checkedAt.toString(), streams, ordered);
    }

    private AuditFileCatalog.AuditFileSnapshot scan(String name, AuditFileSettings settings,
                                                    List<Issue> issues) {
        try {
            return AuditFileCatalog.scanStable(settings, clock, scanObserver);
        } catch (AuditStorageException exception) {
            issues.add(new Issue("failed", name, exception.code()));
            return new AuditFileCatalog.AuditFileSnapshot(List.of(), List.of(), List.of(),
                    List.of(), 0L, Set.of(exception.code()));
        }
    }

    private StreamStatus status(String name, AuditFileSettings settings,
                                AuditFileCatalog.AuditFileSnapshot snapshot,
                                List<Issue> issues, boolean authorizationStream) {
        for (String code : snapshot.issueCodes()) {
            issues.add(new Issue(severityFor(code), name, code));
        }
        long currentBytes = snapshot.current().stream().mapToLong(AuditFileCatalog.AuditFileEntry::bytes).sum();
        long archiveBytes = snapshot.archives().stream().mapToLong(AuditFileCatalog.AuditFileEntry::bytes).sum();
        int open = 0;
        int stale = 0;
        Instant oldest = null;
        if (authorizationStream) {
            OpenAttemptSummary summary = openAttempts(snapshot, issues);
            open = summary.openCount();
            stale = summary.staleCount();
            oldest = summary.oldestStaleAt();
            if (stale > 0) issues.add(new Issue("degraded", name, "AUDIT_OPEN_ATTEMPT_STALE"));
        }
        return new StreamStatus(name, settings.file().toString(), currentBytes,
                snapshot.archives().size(), archiveBytes, snapshot.recovery().size(),
                snapshot.unknown().size(), settings.retentionDays(), settings.streamMaxBytes(),
                Math.max(0L, settings.streamMaxBytes() - snapshot.totalBytes()), open, stale,
                oldest == null ? null : oldest.toString());
    }

    private void addBudgetAndDiskIssues(String name, AuditFileSettings settings,
                                        AuditFileCatalog.AuditFileSnapshot snapshot,
                                        List<Issue> issues) {
        if (snapshot.totalBytes() >= settings.streamMaxBytes()) {
            issues.add(new Issue("failed", name, "AUDIT_STREAM_BUDGET_EXCEEDED"));
        }
        Path directory = writableProbeDirectory(settings.file().getParent());
        if (directory == null) {
            issues.add(new Issue("failed", name, "AUDIT_DIRECTORY_NOT_WRITABLE"));
            return;
        }
        try {
            if (diskSpaceProbe.usableBytes(directory) < settings.minFreeDiskBytes()) {
                issues.add(new Issue("failed", name, "AUDIT_DISK_SPACE_LOW"));
            }
        } catch (IOException | RuntimeException exception) {
            issues.add(new Issue("failed", name, "AUDIT_DISK_SPACE_UNAVAILABLE"));
        }
    }

    private static Path writableProbeDirectory(Path directory) {
        Path candidate = directory;
        while (candidate != null) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Files.isDirectory(candidate) && Files.isWritable(candidate)
                        ? candidate : null;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private OpenAttemptSummary openAttempts(AuditFileCatalog.AuditFileSnapshot snapshot,
                                            List<Issue> issues) {
        Map<String, OpenAttemptState> attempts = new HashMap<>();
        Set<String> malformed = new HashSet<>();
        if (snapshot.issueCodes().contains("AUDIT_SCAN_BUSY")) {
            return new OpenAttemptSummary(0, 0, null);
        }
        List<AuditFileCatalog.AuditFileEntry> entries = new ArrayList<>(snapshot.archives());
        entries.sort(Comparator.comparing(AuditFileCatalog.AuditFileEntry::utcDate)
                .thenComparingLong(AuditFileCatalog.AuditFileEntry::sequence));
        entries.addAll(snapshot.current());
        for (AuditFileCatalog.AuditFileEntry entry : entries) {
            if (entry.issueCode() != null) continue;
            try (InputStream raw = entry.kind() == AuditFileCatalog.Kind.ARCHIVE
                    ? new GZIPInputStream(Files.newInputStream(entry.path()))
                    : Files.newInputStream(entry.path());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                        boolean hasEventId = event.has("eventId");
                        boolean hasAttempt = event.has("attempt");
                        if (!hasEventId && !hasAttempt) continue;
                        if (!hasEventId || !hasAttempt || !event.has("result")
                                || !event.has("occurredAt")) {
                            throw new IllegalArgumentException("authorization entry is incomplete");
                        }
                        String eventId = event.get("eventId").getAsString();
                        int attempt = event.get("attempt").getAsInt();
                        if (!eventId.matches("sha256:[0-9a-f]{64}") || attempt < 1) {
                            throw new IllegalArgumentException("authorization entry identity is invalid");
                        }
                        String key = eventId + "\u0000" + attempt;
                        String result = event.get("result").getAsString();
                        Instant occurredAt = Instant.parse(event.get("occurredAt").getAsString());
                        switch (result) {
                            case "accepted" -> {
                                if (attempts.putIfAbsent(key,
                                        new OpenAttemptState(occurredAt, AttemptPhase.ACCEPTED)) != null) {
                                    throw new IllegalArgumentException("accepted attempt is already open");
                                }
                            }
                            case "pending" -> {
                                OpenAttemptState state = attempts.get(key);
                                if (state == null || state.phase() != AttemptPhase.ACCEPTED) {
                                    throw new IllegalArgumentException("pending attempt is out of order");
                                }
                                attempts.put(key, new OpenAttemptState(
                                        state.acceptedAt(), AttemptPhase.PENDING));
                            }
                            case "succeeded" -> {
                                OpenAttemptState state = attempts.get(key);
                                if (state == null || state.phase() != AttemptPhase.PENDING) {
                                    throw new IllegalArgumentException("succeeded attempt is out of order");
                                }
                                attempts.put(key, new OpenAttemptState(
                                        state.acceptedAt(), AttemptPhase.SUCCEEDED));
                            }
                            case "failed" -> {
                                OpenAttemptState state = attempts.get(key);
                                if (state == null || state.phase() == AttemptPhase.SUCCEEDED
                                        || state.phase() == AttemptPhase.FAILED) {
                                    throw new IllegalArgumentException("failed attempt is not open");
                                }
                                attempts.put(key, new OpenAttemptState(
                                        state.acceptedAt(), AttemptPhase.FAILED));
                            }
                            default -> throw new IllegalArgumentException("authorization result is invalid");
                        }
                    } catch (RuntimeException exception) {
                        malformed.add("AUDIT_AUTHORIZATION_ENTRY_INVALID");
                    }
                }
            } catch (IOException exception) {
                malformed.add("AUDIT_AUTHORIZATION_ENTRY_INVALID");
            }
        }
        for (String code : malformed) issues.add(new Issue("failed", "wecom-authorization", code));
        Instant now = clock.instant();
        int open = 0;
        int stale = 0;
        Instant oldest = null;
        for (OpenAttemptState state : attempts.values()) {
            if (state.phase() == AttemptPhase.SUCCEEDED
                    || state.phase() == AttemptPhase.FAILED) {
                continue;
            }
            open++;
            Instant occurredAt = state.acceptedAt();
            if (occurredAt.plusSeconds(60).isBefore(now)) {
                stale++;
                if (oldest == null || occurredAt.isBefore(oldest)) oldest = occurredAt;
            }
        }
        return new OpenAttemptSummary(open, stale, oldest);
    }

    private static String severityFor(String code) {
        return switch (code) {
            case "AUDIT_CURRENT_CORRUPTED", "AUDIT_STREAM_BUDGET_EXCEEDED",
                    "AUDIT_DISK_SPACE_LOW", "AUDIT_DISK_SPACE_UNAVAILABLE",
                    "AUDIT_CATALOG_SCAN_FAILED" -> "failed";
            default -> "degraded";
        };
    }

    private static List<Issue> normalizeIssues(List<Issue> issues) {
        Map<String, Issue> unique = new HashMap<>();
        for (Issue issue : issues) {
            String key = issue.stream() + "\u0000" + issue.code();
            unique.merge(key, issue, (existing, candidate) ->
                    "failed".equals(existing.severity()) ? existing : candidate);
        }
        return unique.values().stream().sorted(ISSUE_ORDER).toList();
    }

    private record OpenAttemptSummary(int openCount, int staleCount, Instant oldestStaleAt) { }

    private enum AttemptPhase { ACCEPTED, PENDING, SUCCEEDED, FAILED }

    private record OpenAttemptState(Instant acceptedAt, AttemptPhase phase) { }
}
