package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 构造不含凭据的授权审计事件；required 写入失败必须返回调用者。 */
final class WeComAuthorizationAuditTrail {
    private static final Gson GSON = new Gson();
    private final Clock clock;
    private final RequiredLineAppender requiredAppender;
    private final AuthorizationAuditIndex index;
    private final Path legacyFile;
    private final long legacyMaxBytes;

    @FunctionalInterface
    interface RequiredLineAppender {
        void append(String line) throws AuditStorageException;
    }

    WeComAuthorizationAuditTrail(Config config) {
        this(config.authorizationAuditSettings().file(),
                config.authorizationAuditSettings().fileMaxBytes(), Clock.systemUTC());
    }

    WeComAuthorizationAuditTrail(Path file, long maxBytes, Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.legacyFile = java.util.Objects.requireNonNull(file, "file");
        if (maxBytes < 4_096L || maxBytes > 20_971_520L) {
            throw new IllegalArgumentException("authorization audit size is invalid");
        }
        this.legacyMaxBytes = maxBytes;
        this.requiredAppender = null;
        this.index = null;
    }

    private WeComAuthorizationAuditTrail(Clock clock, RequiredLineAppender requiredAppender,
                                         AuthorizationAuditIndex index) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.requiredAppender = java.util.Objects.requireNonNull(requiredAppender, "requiredAppender");
        this.index = java.util.Objects.requireNonNull(index, "index");
        this.legacyFile = null;
        this.legacyMaxBytes = 0L;
    }

    static WeComAuthorizationAuditTrail forTests(Clock clock, RequiredLineAppender appender) {
        return new WeComAuthorizationAuditTrail(clock, appender, AuthorizationAuditIndex.empty());
    }

    static WeComAuthorizationAuditTrail open(AuditFileSettings settings, Clock clock,
                                             RequiredLineAppender appender)
            throws AuditStorageException {
        return new WeComAuthorizationAuditTrail(
                clock, appender, AuthorizationAuditIndex.load(settings, clock));
    }

    synchronized BeginResult begin(WeComCallbackCodec.DecodedCallback callback)
            throws AuditStorageException {
        requireCallback(callback);
        String eventId = eventId(callback);
        if (index.succeeded(eventId)) {
            return new BeginResult(BeginDisposition.ALREADY_SUCCEEDED, null);
        }
        AuthorizationAuditIndex.OpenAttempt open = index.open(eventId);
        if (open != null) {
            return new BeginResult(BeginDisposition.OPEN_ALREADY_ACCEPTED, open.attempt());
        }
        AuthorizationAuditAttempt attempt = new AuthorizationAuditAttempt(
                eventId, index.nextAttempt(eventId), action(callback), callback.timestamp());
        Map<String, Object> event = base(attempt, callback.suiteId(), callback.authCorpId(), "accepted");
        appendRequired(event);
        index.accepted(attempt, callback.suiteId(), callback.authCorpId());
        return new BeginResult(BeginDisposition.NEW_ATTEMPT, attempt);
    }

    synchronized void pending(AuthorizationAuditAttempt attempt, String authCorpId,
                              WeComAuthorizationStore.AuthStatus targetStatus,
                              long expectedVersion) throws AuditStorageException {
        requireAttempt(attempt);
        if (targetStatus == null || expectedVersion < 0) {
            throw new IllegalArgumentException("authorization pending fields are invalid");
        }
        AuthorizationAuditIndex.OpenAttempt open = requireOpen(attempt);
        Map<String, Object> event = base(attempt, open.suiteId(), authCorpId, "pending");
        event.put("targetStatus", targetStatus.name());
        event.put("expectedVersion", expectedVersion);
        appendRequired(event);
        index.pending(attempt, open.suiteId(), authCorpId, targetStatus, expectedVersion);
    }

    synchronized void succeeded(AuthorizationAuditAttempt attempt, String authCorpId)
            throws AuditStorageException {
        requireAttempt(attempt);
        AuthorizationAuditIndex.OpenAttempt open = requireOpen(attempt);
        index.requirePending(attempt);
        appendRequired(base(attempt, open.suiteId(), authCorpId, "succeeded"));
        index.succeeded(attempt);
    }

    synchronized void failed(AuthorizationAuditAttempt attempt, String authCorpId,
                             WeComAuthorizationException failure) throws AuditStorageException {
        requireAttempt(attempt);
        if (failure == null) throw new IllegalArgumentException("authorization failure is required");
        AuthorizationAuditIndex.OpenAttempt open = requireOpen(attempt);
        Map<String, Object> event = base(attempt, open.suiteId(), authCorpId, "failed");
        putFailure(event, failure);
        appendRequired(event);
        index.failed(attempt);
    }

    AuthorizationAuditIndex index() {
        if (index == null) throw new IllegalStateException("authorization index is unavailable");
        return index;
    }

    synchronized void record(WeComCallbackCodec.DecodedCallback callback, String authCorpId,
                             String result, WeComAuthorizationException failure) throws IOException {
        if (legacyFile == null || callback == null
                || !result.matches("accepted|succeeded|failed")) {
            throw new IllegalArgumentException("authorization audit event is invalid");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("action", action(callback));
        event.put("result", result);
        event.put("suiteId", bounded(callback.suiteId(), 128));
        String resolvedCorpId = authCorpId == null || authCorpId.isBlank()
                ? callback.authCorpId() : authCorpId;
        if (resolvedCorpId != null && !resolvedCorpId.isBlank()) {
            event.put("authCorpId", bounded(resolvedCorpId, 128));
        }
        if (failure != null) putFailure(event, failure);
        byte[] encoded = encode(event);
        Path parent = legacyFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        long existing = Files.exists(legacyFile) ? Files.size(legacyFile) : 0L;
        if (existing + encoded.length > legacyMaxBytes) {
            throw new IOException("WeCom authorization audit trail reached its configured size limit");
        }
        Files.write(legacyFile, encoded, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private AuthorizationAuditIndex.OpenAttempt requireOpen(AuthorizationAuditAttempt attempt) {
        AuthorizationAuditIndex.OpenAttempt open = index.open(attempt.eventId());
        if (open == null || open.attempt().attempt() != attempt.attempt()) {
            throw new IllegalStateException("authorization audit attempt is not open");
        }
        return open;
    }

    private void appendRequired(Map<String, Object> event) throws AuditStorageException {
        requiredAppender.append(GSON.toJson(event) + "\n");
    }

    private Map<String, Object> base(AuthorizationAuditAttempt attempt, String suiteId,
                                     String authCorpId, String result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("occurredAt", clock.instant().toString());
        event.put("eventId", attempt.eventId());
        event.put("attempt", attempt.attempt());
        event.put("action", attempt.action());
        event.put("result", result);
        event.put("suiteId", bounded(suiteId, 128));
        if (authCorpId != null && !authCorpId.isBlank()) {
            event.put("authCorpId", bounded(authCorpId, 128));
        }
        event.put("callbackTimestamp", attempt.callbackTimestamp().toString());
        return event;
    }

    private static void putFailure(Map<String, Object> event,
                                   WeComAuthorizationException failure) {
        event.put("errorCode", bounded(failure.code(), 96));
        if (failure.upstreamErrcode() != null) event.put("upstreamErrcode", failure.upstreamErrcode());
        if (failure.upstreamPath() != null) event.put("upstreamPath", failure.upstreamPath());
        if (failure.upstreamHttpStatus() != null) event.put("upstreamHttpStatus", failure.upstreamHttpStatus());
        if (failure.upstreamHint() != null) event.put("upstreamHint", failure.upstreamHint());
    }

    private static byte[] encode(Map<String, Object> event) {
        return (GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String eventId(WeComCallbackCodec.DecodedCallback callback) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, callback.infoType());
            update(digest, callback.suiteId());
            update(digest, callback.authCorpId());
            update(digest, callback.timestamp().toString());
            update(digest, sensitiveDigest(callback.authCode()));
            update(digest, sensitiveDigest(callback.suiteTicket()));
            update(digest, sensitiveDigest(callback.state()));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sensitiveDigest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String action(WeComCallbackCodec.DecodedCallback callback) {
        return "wecom.authorization." + bounded(callback.infoType(), 64);
    }

    private static void requireCallback(WeComCallbackCodec.DecodedCallback callback) {
        if (callback == null || callback.timestamp() == null) {
            throw new IllegalArgumentException("authorization callback is invalid");
        }
        bounded(callback.suiteId(), 128);
        bounded(callback.infoType(), 64);
        if (callback.authCorpId() != null && !callback.authCorpId().isBlank()) {
            bounded(callback.authCorpId(), 128);
        }
    }

    private static void requireAttempt(AuthorizationAuditAttempt attempt) {
        if (attempt == null || !attempt.eventId().matches("sha256:[0-9a-f]{64}")
                || attempt.attempt() < 1 || attempt.callbackTimestamp() == null
                || !attempt.action().matches("wecom\\.authorization\\.[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("authorization audit attempt is invalid");
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("authorization audit field is invalid");
        }
        return value;
    }

    enum BeginDisposition { NEW_ATTEMPT, OPEN_ALREADY_ACCEPTED, ALREADY_SUCCEEDED }

    record AuthorizationAuditAttempt(String eventId, int attempt, String action,
                                     Instant callbackTimestamp) {}

    record BeginResult(BeginDisposition disposition, AuthorizationAuditAttempt attempt) {}
}
