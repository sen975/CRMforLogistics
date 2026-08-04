package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WeComViewerAuditTrail {
    private static final Gson GSON = new Gson();
    private static final List<String> RESULTS = List.of("success", "denied", "failed", "rate_limited");

    private final Path file;
    private final long maxBytes;
    private final Clock clock;

    WeComViewerAuditTrail(Config config, Clock clock) {
        this.file = config.wecomViewerAuditFile();
        this.maxBytes = config.wecomViewerAuditMaxBytes();
        this.clock = clock;
    }

    synchronized void record(String action, String result, String wecomUserId,
                             String contactPointId, String viewerSessionId) throws IOException {
        if (action == null || action.isBlank() || action.length() > 100 || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("WeCom viewer audit event is invalid");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("action", action);
        event.put("result", result);
        putBounded(event, "wecomUserId", wecomUserId, 128);
        putBounded(event, "contactPointId", contactPointId, 256);
        putBounded(event, "viewerSessionId", viewerSessionId, 64);
        writeEvent(event);
    }

    synchronized void recordDiagnostic(String action, String result, String wecomUserId,
                                        String contactPointId, String viewerSessionId,
                                        String errorCode, Integer upstreamErrcode,
                                        String upstreamPath) throws IOException {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, null);
    }

    synchronized void recordDiagnostic(String action, String result, String wecomUserId,
                                        String contactPointId, String viewerSessionId,
                                        String errorCode, Integer upstreamErrcode,
                                        String upstreamPath, Integer upstreamHttpStatus) throws IOException {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, upstreamHttpStatus, null);
    }

    synchronized void recordDiagnostic(String action, String result, String wecomUserId,
                                        String contactPointId, String viewerSessionId,
                                        String errorCode, Integer upstreamErrcode,
                                        String upstreamPath, Integer upstreamHttpStatus,
                                        String upstreamHint) throws IOException {
        if (action == null || action.isBlank() || action.length() > 100 || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("WeCom viewer audit event is invalid");
        }
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,128}")) {
            throw new IllegalArgumentException("WeCom viewer diagnostic code is invalid");
        }
        if (upstreamErrcode != null && upstreamErrcode < 0) {
            throw new IllegalArgumentException("WeCom upstream error code is invalid");
        }
        if (upstreamPath != null && (upstreamPath.isBlank() || upstreamPath.length() > 256
                || !upstreamPath.startsWith("/"))) {
            throw new IllegalArgumentException("WeCom upstream path is invalid");
        }
        if (upstreamHttpStatus != null && (upstreamHttpStatus < 100 || upstreamHttpStatus > 599)) {
            throw new IllegalArgumentException("WeCom upstream HTTP status is invalid");
        }
        if (upstreamHint != null && !upstreamHint.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("WeCom upstream hint is invalid");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("action", action);
        event.put("result", result);
        putBounded(event, "wecomUserId", wecomUserId, 128);
        putBounded(event, "contactPointId", contactPointId, 256);
        putBounded(event, "viewerSessionId", viewerSessionId, 64);
        event.put("errorCode", errorCode);
        if (upstreamErrcode != null) event.put("upstreamErrcode", upstreamErrcode);
        if (upstreamPath != null) event.put("upstreamPath", upstreamPath);
        if (upstreamHttpStatus != null) event.put("upstreamHttpStatus", upstreamHttpStatus);
        if (upstreamHint != null) event.put("upstreamHint", upstreamHint);
        writeEvent(event);
    }

    private void writeEvent(Map<String, Object> event) throws IOException {
        byte[] bytes = (GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long existingSize = Files.exists(file) ? Files.size(file) : 0L;
        if (existingSize + bytes.length > maxBytes) {
            throw new IOException("WeCom viewer audit trail reached its configured size limit");
        }
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void putBounded(Map<String, Object> event, String key, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return;
        }
        event.put(key, value.length() <= maxLength ? value : value.substring(0, maxLength));
    }
}
