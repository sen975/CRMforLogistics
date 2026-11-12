package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** 授权审计阶段索引；只保存幂等和恢复所需的非敏感状态。 */
final class AuthorizationAuditIndex {
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "occurredAt", "action", "result", "suiteId", "authCorpId", "errorCode",
            "upstreamErrcode", "upstreamPath", "upstreamHttpStatus", "upstreamHint");
    enum Phase { ACCEPTED, PENDING }

    record OpenAttempt(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
                       String suiteId, String authCorpId, Phase phase,
                       WeComAuthorizationStore.AuthStatus targetStatus,
                       Long expectedVersion) {}

    private final Map<String, EventState> events = new LinkedHashMap<>();

    static AuthorizationAuditIndex empty() {
        return new AuthorizationAuditIndex();
    }

    static AuthorizationAuditIndex load(AuditFileSettings settings, java.time.Clock clock)
            throws AuditStorageException {
        AuditFileCatalog.AuditFileSnapshot snapshot = AuditFileCatalog.scanStable(settings, clock);
        if (!snapshot.issueCodes().isEmpty()) {
            throw failure(snapshot.issueCodes().stream().sorted().findFirst()
                    .orElse("AUDIT_CATALOG_SCAN_FAILED"), null);
        }
        if (snapshot.totalBytes() > settings.streamMaxBytes()) {
            throw failure("AUDIT_STREAM_BUDGET_EXCEEDED", null);
        }
        AuthorizationAuditIndex index = empty();
        ScanBudget budget = new ScanBudget(settings.streamMaxBytes(), settings.fileMaxBytes());
        List<AuditFileCatalog.AuditFileEntry> archives = new ArrayList<>(snapshot.archives());
        archives.sort(Comparator.comparing(AuditFileCatalog.AuditFileEntry::utcDate)
                .thenComparingLong(AuditFileCatalog.AuditFileEntry::sequence));
        for (AuditFileCatalog.AuditFileEntry entry : archives) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(entry.path()))) {
                index.applyLines(input, budget);
            } catch (AuditStorageException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure("AUDIT_CATALOG_SCAN_FAILED", exception);
            }
        }
        for (AuditFileCatalog.AuditFileEntry entry : snapshot.current()) {
            try (InputStream input = Files.newInputStream(entry.path())) {
                index.applyLines(input, budget);
            } catch (AuditStorageException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure("AUDIT_CATALOG_SCAN_FAILED", exception);
            }
        }
        return index;
    }

    synchronized int nextAttempt(String eventId) {
        EventState state = events.get(eventId);
        return state == null ? 1 : Math.addExact(state.maximumAttempt, 1);
    }

    synchronized OpenAttempt open(String eventId) {
        EventState state = events.get(eventId);
        return state == null ? null : state.open;
    }

    synchronized boolean succeeded(String eventId) {
        EventState state = events.get(eventId);
        return state != null && state.succeeded;
    }

    synchronized List<OpenAttempt> openAttempts() {
        return events.values().stream().map(state -> state.open)
                .filter(java.util.Objects::nonNull).toList();
    }

    synchronized void accepted(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
                               String suiteId, String authCorpId) {
        EventState state = events.computeIfAbsent(attempt.eventId(), ignored -> new EventState());
        if (state.succeeded || state.open != null
                || attempt.attempt() != state.maximumAttempt + 1) {
            throw new IllegalStateException("authorization accepted stage is out of order");
        }
        state.maximumAttempt = attempt.attempt();
        state.action = attempt.action();
        state.callbackTimestamp = attempt.callbackTimestamp();
        state.suiteId = suiteId;
        state.open = new OpenAttempt(attempt, suiteId, emptyToNull(authCorpId),
                Phase.ACCEPTED, null, null);
    }

    synchronized void pending(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
                              String suiteId, String authCorpId,
                              WeComAuthorizationStore.AuthStatus targetStatus,
                              long expectedVersion) {
        EventState state = requireAttempt(attempt);
        if (state.open.phase() != Phase.ACCEPTED) {
            throw new IllegalStateException("authorization pending stage is out of order");
        }
        state.open = new OpenAttempt(attempt, suiteId, emptyToNull(authCorpId),
                Phase.PENDING, targetStatus, expectedVersion);
    }

    synchronized void succeeded(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        EventState state = requireAttempt(attempt);
        if (state.open.phase() != Phase.PENDING) {
            throw new IllegalStateException("authorization succeeded stage is out of order");
        }
        state.succeeded = true;
        state.open = null;
    }

    synchronized void requirePending(
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        EventState state = requireAttempt(attempt);
        if (state.open.phase() != Phase.PENDING) {
            throw new IllegalStateException("authorization succeeded stage is out of order");
        }
    }

    synchronized void failed(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        EventState state = requireAttempt(attempt);
        state.open = null;
    }

    private EventState requireAttempt(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        EventState state = events.get(attempt.eventId());
        if (state == null || state.open == null
                || state.open.attempt().attempt() != attempt.attempt()) {
            throw new IllegalStateException("authorization audit attempt is not open");
        }
        return state;
    }

    private void applyLines(InputStream raw, ScanBudget budget) throws AuditStorageException {
        try (BufferedInputStream input = new BufferedInputStream(raw)) {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                budget.consume();
                if (value == '\n') {
                    if (line.size() == 0) {
                        throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
                    }
                    applyLine(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else {
                    if (value == '\r' || line.size() >= budget.maximumLineBytes) {
                        throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
                    }
                    line.write(value);
                }
            }
            if (line.size() != 0) {
                throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
            }
        } catch (AuditStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("AUDIT_CATALOG_SCAN_FAILED", exception);
        }
    }

    private void applyLine(String line) throws AuditStorageException {
        try {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            boolean hasEventId = event.has("eventId");
            boolean hasAttempt = event.has("attempt");
            if (!hasEventId && !hasAttempt) {
                validateLegacy(event);
                return;
            }
            if (!hasEventId || !hasAttempt || !event.has("action") || !event.has("result")
                    || !event.has("suiteId") || !event.has("callbackTimestamp")) {
                throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
            }
            String eventId = event.get("eventId").getAsString();
            int attemptNumber = event.get("attempt").getAsInt();
            String action = event.get("action").getAsString();
            String result = event.get("result").getAsString();
            String suiteId = event.get("suiteId").getAsString();
            String authCorpId = event.has("authCorpId") ? event.get("authCorpId").getAsString() : "";
            Instant callbackTimestamp = Instant.parse(event.get("callbackTimestamp").getAsString());
            if (!eventId.matches("sha256:[0-9a-f]{64}") || attemptNumber < 1
                    || !action.matches("wecom\\.authorization\\.[A-Za-z0-9_]{1,64}")) {
                throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
            }
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt =
                    new WeComAuthorizationAuditTrail.AuthorizationAuditAttempt(
                            eventId, attemptNumber, action, callbackTimestamp);
            EventState existing = events.get(eventId);
            if (existing != null && (existing.action != null && !existing.action.equals(action)
                    || existing.callbackTimestamp != null
                    && !existing.callbackTimestamp.equals(callbackTimestamp)
                    || existing.suiteId != null && !existing.suiteId.equals(suiteId))) {
                throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
            }
            switch (result) {
                case "accepted" -> accepted(attempt, suiteId, authCorpId);
                case "pending" -> {
                    if (!event.has("targetStatus") || !event.has("expectedVersion")) {
                        throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
                    }
                    pending(attempt, suiteId, authCorpId,
                            WeComAuthorizationStore.AuthStatus.valueOf(
                                    event.get("targetStatus").getAsString()),
                            event.get("expectedVersion").getAsLong());
                }
                case "succeeded" -> succeeded(attempt);
                case "failed" -> failed(attempt);
                default -> throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
            }
        } catch (AuditStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", exception);
        }
    }

    private static void validateLegacy(JsonObject event) throws AuditStorageException {
        if (!event.keySet().stream().allMatch(LEGACY_FIELDS::contains)
                || !event.has("occurredAt") || !event.has("action")
                || !event.has("result") || !event.has("suiteId")
                || !event.get("action").getAsString()
                .matches("wecom\\.authorization\\.[A-Za-z0-9_]{1,64}")
                || !event.get("result").getAsString().matches("accepted|succeeded|failed")) {
            throw failure("AUDIT_AUTHORIZATION_ENTRY_INVALID", null);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static AuditStorageException failure(String code, Throwable cause) {
        return new AuditStorageException(code, "wecom-authorization", cause);
    }

    private static final class EventState {
        private int maximumAttempt;
        private boolean succeeded;
        private OpenAttempt open;
        private String action;
        private Instant callbackTimestamp;
        private String suiteId;
    }

    private static final class ScanBudget {
        private long remaining;
        private final long maximumLineBytes;

        private ScanBudget(long remaining, long maximumLineBytes) {
            this.remaining = remaining;
            this.maximumLineBytes = maximumLineBytes;
        }

        private void consume() throws AuditStorageException {
            if (remaining-- <= 0) {
                throw failure("AUDIT_STREAM_BUDGET_EXCEEDED", null);
            }
        }
    }
}
