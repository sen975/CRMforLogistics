package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes bounded, credential-free diagnostics for official Suite callbacks. */
final class WeComAuthorizationAuditTrail {
    private static final Gson GSON = new Gson();
    private final Path file;
    private final long maxBytes;
    private final Clock clock;

    WeComAuthorizationAuditTrail(Config config) {
        this(config.authorizationAuditSettings().file(), config.authorizationAuditSettings().fileMaxBytes(), Clock.systemUTC());
    }

    WeComAuthorizationAuditTrail(Path file, long maxBytes, Clock clock) {
        this.file = java.util.Objects.requireNonNull(file, "file");
        if (maxBytes < 4_096L || maxBytes > 20_971_520L) {
            throw new IllegalArgumentException("authorization audit size is invalid");
        }
        this.maxBytes = maxBytes;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    synchronized void record(WeComCallbackCodec.DecodedCallback callback, String authCorpId, String result,
                             WeComAuthorizationException failure) throws IOException {
        if (callback == null || !result.matches("accepted|succeeded|failed")) {
            throw new IllegalArgumentException("authorization audit event is invalid");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("action", "wecom.authorization." + bounded(callback.infoType(), 64));
        event.put("result", result);
        event.put("suiteId", bounded(callback.suiteId(), 128));
        String resolvedCorpId = authCorpId == null || authCorpId.isBlank() ? callback.authCorpId() : authCorpId;
        if (!resolvedCorpId.isBlank()) event.put("authCorpId", bounded(resolvedCorpId, 128));
        if (failure != null) {
            event.put("errorCode", failure.code());
            if (failure.upstreamErrcode() != null) event.put("upstreamErrcode", failure.upstreamErrcode());
            if (failure.upstreamPath() != null) event.put("upstreamPath", failure.upstreamPath());
            if (failure.upstreamHttpStatus() != null) event.put("upstreamHttpStatus", failure.upstreamHttpStatus());
            if (failure.upstreamHint() != null) event.put("upstreamHint", failure.upstreamHint());
        }
        byte[] encoded = (GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        long existing = Files.exists(file) ? Files.size(file) : 0L;
        if (existing + encoded.length > maxBytes) {
            throw new IOException("WeCom authorization audit trail reached its configured size limit");
        }
        Files.write(file, encoded, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("authorization audit field is invalid");
        }
        return value;
    }
}
