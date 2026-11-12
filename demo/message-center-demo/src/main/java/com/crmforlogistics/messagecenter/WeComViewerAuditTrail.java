package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WeComViewerAuditTrail implements ViewerAuditSink {
    private static final Gson GSON = new Gson();
    private static final List<String> RESULTS = List.of("success", "denied", "failed", "rate_limited");
    private final Clock clock;
    private final AuditWriter writer;
    private final AuditWarningReporter warnings;
    private final Set<String> failedStorageCodes = new LinkedHashSet<>();

    WeComViewerAuditTrail(Clock clock, AuditWriter writer, AuditWarningReporter warnings) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
        this.warnings = java.util.Objects.requireNonNull(warnings, "warnings");
    }

    @FunctionalInterface
    interface AuditWriter {
        void append(byte[] bytes, BoundedAuditFile.Durability durability) throws AuditStorageException;
    }

    @Override
    public synchronized void record(String action, String result, String wecomUserId,
                                    String contactPointId, String viewerSessionId) {
        Map<String, Object> event = base(action, result, wecomUserId, contactPointId, viewerSessionId);
        write(event);
    }

    @Override
    public synchronized void recordDiagnostic(String action, String result, String wecomUserId,
                                               String contactPointId, String viewerSessionId,
                                               String errorCode, Integer upstreamErrcode,
                                               String upstreamPath, Integer upstreamHttpStatus,
                                               String upstreamHint) {
        Map<String, Object> event = base(action, result, wecomUserId, contactPointId, viewerSessionId);
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,128}")) {
            throw new IllegalArgumentException("WeCom viewer diagnostic code is invalid");
        }
        if (upstreamErrcode != null && upstreamErrcode < 0) throw new IllegalArgumentException("WeCom upstream error code is invalid");
        if (upstreamPath != null && (upstreamPath.isBlank() || upstreamPath.length() > 256 || !upstreamPath.startsWith("/"))) {
            throw new IllegalArgumentException("WeCom upstream path is invalid");
        }
        if (upstreamHttpStatus != null && (upstreamHttpStatus < 100 || upstreamHttpStatus > 599)) {
            throw new IllegalArgumentException("WeCom upstream HTTP status is invalid");
        }
        if (upstreamHint != null && !upstreamHint.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("WeCom upstream hint is invalid");
        }
        event.put("errorCode", errorCode);
        if (upstreamErrcode != null) event.put("upstreamErrcode", upstreamErrcode);
        if (upstreamPath != null) event.put("upstreamPath", upstreamPath);
        if (upstreamHttpStatus != null) event.put("upstreamHttpStatus", upstreamHttpStatus);
        if (upstreamHint != null) event.put("upstreamHint", upstreamHint);
        write(event);
    }

    private Map<String, Object> base(String action, String result, String user, String contact, String session) {
        if (action == null || action.isBlank() || action.length() > 100 || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("WeCom viewer audit event is invalid");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("action", action);
        event.put("result", result);
        putBounded(event, "wecomUserId", user, 128);
        putBounded(event, "contactPointId", contact, 256);
        putBounded(event, "viewerSessionId", session, 64);
        return event;
    }

    private void write(Map<String, Object> event) {
        byte[] bytes = (GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            writer.append(bytes, BoundedAuditFile.Durability.BEST_EFFORT);
            for (String errorCode : List.copyOf(failedStorageCodes)) {
                warnings.reportRecovered("wecom-viewer", errorCode);
                failedStorageCodes.remove(errorCode);
            }
        } catch (AuditStorageException failure) {
            String errorCode = AuditWarningReporter.normalizeStorageErrorCode(failure.code());
            failedStorageCodes.add(errorCode);
            warnings.reportFailure("wecom-viewer", errorCode);
        }
    }

    private static void putBounded(Map<String, Object> event, String key, String value, int max) {
        if (value != null && !value.isBlank()) event.put(key, value.length() <= max ? value : value.substring(0, max));
    }
}
