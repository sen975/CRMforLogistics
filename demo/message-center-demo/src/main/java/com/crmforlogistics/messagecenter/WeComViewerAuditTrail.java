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
