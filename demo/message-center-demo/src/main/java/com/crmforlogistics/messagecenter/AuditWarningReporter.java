package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class AuditWarningReporter {
    private static final Gson GSON = new Gson();
    private static final Set<String> STREAMS = Set.of("wecom-viewer", "wecom-authorization");
    private static final Set<String> ERROR_CODES = Set.of(
            "AUDIT_CATALOG_SCAN_FAILED",
            "AUDIT_CURRENT_CORRUPTED",
            "AUDIT_DISK_SPACE_LOW",
            "AUDIT_EVENT_TOO_LARGE",
            "AUDIT_ROTATION_FAILED",
            "AUDIT_SCAN_BUSY",
            "AUDIT_SETTINGS_CONFLICT",
            "AUDIT_STREAM_BUDGET_EXCEEDED",
            "AUDIT_WRITER_CLOSED",
            "AUDIT_WRITER_LOCKED");
    private final Clock clock;
    private final Duration interval;
    private final Consumer<String> sink;
    private final Map<Key, State> states = new LinkedHashMap<>();

    AuditWarningReporter(Clock clock, Duration interval, Consumer<String> sink) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.interval = java.util.Objects.requireNonNull(interval, "interval");
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
    }

    synchronized void reportFailure(String stream, String errorCode) {
        validate(stream, errorCode);
        Instant now = clock.instant();
        Key key = new Key(stream, errorCode);
        State state = states.get(key);
        if (state == null || !state.failed || !now.isBefore(state.last.plus(interval))) {
            emit(stream, "wecom-authorization".equals(stream) ? "failed" : "degraded", errorCode);
            states.put(key, new State(now, true));
        }
    }

    synchronized void reportRecovered(String stream, String errorCode) {
        validate(stream, errorCode);
        Key key = new Key(stream, errorCode);
        State state = states.get(key);
        if (state != null && state.failed) {
            emit(stream, "recovered", errorCode);
            states.put(key, new State(clock.instant(), false));
        }
    }

    private void emit(String stream, String status, String errorCode) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("event", "wecom.audit.write");
        event.put("stream", stream);
        event.put("status", status);
        event.put("errorCode", errorCode);
        sink.accept(GSON.toJson(event));
    }

    private static void validate(String stream, String errorCode) {
        if (!STREAMS.contains(stream)) throw new IllegalArgumentException("unsupported audit stream");
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,128}")
                || !ERROR_CODES.contains(errorCode)) {
            throw new IllegalArgumentException("invalid audit error code");
        }
    }

    static String normalizeStorageErrorCode(String errorCode) {
        return ERROR_CODES.contains(errorCode) ? errorCode : "AUDIT_ROTATION_FAILED";
    }

    private record Key(String stream, String errorCode) {}
    private record State(Instant last, boolean failed) {}
}
