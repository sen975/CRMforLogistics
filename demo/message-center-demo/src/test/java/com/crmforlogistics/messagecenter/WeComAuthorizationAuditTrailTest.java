package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComAuthorizationAuditTrailTest {
    private static final Instant NOW = Instant.parse("2026-08-13T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void reusesOpenAttemptAndAllocatesNextOnlyAfterFailure() throws Exception {
        List<String> lines = new ArrayList<>();
        WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.forTests(
                Clock.fixed(NOW, ZoneOffset.UTC), lines::add);
        WeComCallbackCodec.DecodedCallback callback = callback(
                "reset_permanent_code", "", "one-time-auth-code", "");

        WeComAuthorizationAuditTrail.BeginResult first = trail.begin(callback);
        WeComAuthorizationAuditTrail.BeginResult duplicate = trail.begin(callback);

        assertEquals(WeComAuthorizationAuditTrail.BeginDisposition.NEW_ATTEMPT,
                first.disposition());
        assertEquals(WeComAuthorizationAuditTrail.BeginDisposition.OPEN_ALREADY_ACCEPTED,
                duplicate.disposition());
        assertEquals(first.attempt(), duplicate.attempt());
        assertEquals(1, lines.size());

        trail.failed(first.attempt(), "ww-corp", temporaryFailure());
        WeComAuthorizationAuditTrail.BeginResult retry = trail.begin(callback);
        assertEquals(2, retry.attempt().attempt());
        trail.pending(retry.attempt(), "ww-corp",
                WeComAuthorizationStore.AuthStatus.ACTIVE, 1L);
        trail.succeeded(retry.attempt(), "ww-corp");
        assertEquals(WeComAuthorizationAuditTrail.BeginDisposition.ALREADY_SUCCEEDED,
                trail.begin(callback).disposition());

        String all = String.join("", lines);
        assertFalse(all.contains("one-time-auth-code"));
        assertFalse(all.contains("permanent-code"));
        assertTrue(all.contains("\"result\":\"accepted\""));
        assertTrue(all.contains("\"result\":\"failed\""));
        assertTrue(all.contains("\"result\":\"succeeded\""));
    }

    @Test
    void recordsPendingWhitelistWithoutCredentials() throws Exception {
        List<String> lines = new ArrayList<>();
        WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.forTests(
                Clock.fixed(NOW, ZoneOffset.UTC), lines::add);
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt = trail.begin(
                callback("create_auth", "ww-corp", "sensitive-auth-code", "")).attempt();

        trail.pending(attempt, "ww-corp", WeComAuthorizationStore.AuthStatus.ACTIVE, 0L);

        String pending = lines.get(1);
        assertTrue(pending.contains("\"targetStatus\":\"ACTIVE\""));
        assertTrue(pending.contains("\"expectedVersion\":0"));
        assertFalse(pending.contains("sensitive-auth-code"));
        assertFalse(pending.contains("permanent-code"));
    }

    @Test
    void rejectsSucceededBeforePendingWithoutAppendingFinalStage() throws Exception {
        List<String> lines = new ArrayList<>();
        WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.forTests(
                Clock.fixed(NOW, ZoneOffset.UTC), lines::add);
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt = trail.begin(
                callback("create_auth", "ww-corp", "sensitive-auth-code", "")).attempt();

        assertThrows(IllegalStateException.class,
                () -> trail.succeeded(attempt, "ww-corp"));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"result\":\"accepted\""));
    }

    @Test
    void rejectsPersistedSucceededBeforePendingDuringIndexRebuild() throws Exception {
        Path current = tempDir.resolve("wecom-authorization-audit.jsonl");
        String eventId = "sha256:" + "a".repeat(64);
        String common = "\"eventId\":\"" + eventId + "\",\"attempt\":1,"
                + "\"action\":\"wecom.authorization.create_auth\","
                + "\"suiteId\":\"dk-suite\",\"authCorpId\":\"ww-corp\","
                + "\"callbackTimestamp\":\"2026-08-13T01:00:00Z\"";
        Files.writeString(current,
                "{\"occurredAt\":\"2026-08-13T01:00:00Z\"," + common
                        + ",\"result\":\"accepted\"}\n"
                        + "{\"occurredAt\":\"2026-08-13T01:00:01Z\"," + common
                        + ",\"result\":\"succeeded\"}\n",
                StandardCharsets.UTF_8);

        AuditStorageException failure = assertThrows(AuditStorageException.class,
                () -> WeComAuthorizationAuditTrail.open(settings(current),
                        Clock.fixed(NOW, ZoneOffset.UTC), ignored -> { }));

        assertEquals("AUDIT_AUTHORIZATION_ENTRY_INVALID", failure.code());
    }

    @Test
    void computesStableDistinctEventIdsUnderConcurrency() throws Exception {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.forTests(
                Clock.fixed(NOW, ZoneOffset.UTC), lines::add);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                int id = index;
                futures.add(executor.submit(() -> trail.begin(callback(
                        "create_auth", "ww-corp-" + id, "auth-code-" + id, "")).attempt().eventId()));
            }
            Set<String> eventIds = new HashSet<>();
            for (var future : futures) eventIds.add(future.get(2, TimeUnit.SECONDS));
            assertEquals(16, eventIds.size());
            assertTrue(eventIds.stream().allMatch(value -> value.matches("sha256:[0-9a-f]{64}")));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(16, lines.size());
    }

    @Test
    void rebuildsOpenIndexWhileIgnoringLegacyRows() throws Exception {
        Path current = tempDir.resolve("wecom-authorization-audit.jsonl");
        String legacy = "{\"occurredAt\":\"2026-08-12T00:00:00Z\","
                + "\"action\":\"wecom.authorization.create_auth\",\"result\":\"accepted\","
                + "\"suiteId\":\"dk-suite\",\"authCorpId\":\"ww-legacy\"}\n";
        Files.writeString(current, legacy, StandardCharsets.UTF_8);
        AuditFileSettings settings = settings(current);
        List<String> firstRun = new ArrayList<>();
        WeComAuthorizationAuditTrail first = WeComAuthorizationAuditTrail.open(
                settings, Clock.fixed(NOW, ZoneOffset.UTC), firstRun::add);
        WeComCallbackCodec.DecodedCallback callback = callback(
                "change_auth", "ww-corp", "", "");
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt = first.begin(callback).attempt();
        Files.writeString(current, legacy + String.join("", firstRun), StandardCharsets.UTF_8);

        List<String> restartedWrites = new ArrayList<>();
        WeComAuthorizationAuditTrail restarted = WeComAuthorizationAuditTrail.open(
                settings, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), restartedWrites::add);
        WeComAuthorizationAuditTrail.BeginResult replay = restarted.begin(callback);

        assertEquals(WeComAuthorizationAuditTrail.BeginDisposition.OPEN_ALREADY_ACCEPTED,
                replay.disposition());
        assertEquals(attempt, replay.attempt());
        assertTrue(restartedWrites.isEmpty());
        assertEquals(1, restarted.index().openAttempts().size());
    }

    @Test
    void failedRequiredAppendDoesNotAdvanceAttemptIndex() throws Exception {
        List<String> lines = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failing =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.forTests(
                Clock.fixed(NOW, ZoneOffset.UTC), line -> {
                    if (failing.get()) {
                        throw new AuditStorageException(
                                "AUDIT_DISK_SPACE_LOW", "wecom-authorization", null);
                    }
                    lines.add(line);
                });
        WeComCallbackCodec.DecodedCallback callback = callback(
                "create_auth", "ww-corp", "auth-code", "");

        assertEquals("AUDIT_DISK_SPACE_LOW",
                assertThrows(AuditStorageException.class, () -> trail.begin(callback)).code());
        assertTrue(trail.index().openAttempts().isEmpty());

        failing.set(false);
        WeComAuthorizationAuditTrail.BeginResult retry = trail.begin(callback);
        assertEquals(1, retry.attempt().attempt());
        assertEquals(1, lines.size());
    }

    @Test
    void rejectsNewFormatEntryWithMissingStageFields() throws Exception {
        Path current = tempDir.resolve("wecom-authorization-audit.jsonl");
        Files.writeString(current,
                "{\"occurredAt\":\"2026-08-13T00:00:00Z\","
                        + "\"eventId\":\"sha256:" + "a".repeat(64) + "\","
                        + "\"attempt\":1,\"action\":\"wecom.authorization.create_auth\","
                        + "\"result\":\"accepted\"}\n",
                StandardCharsets.UTF_8);

        AuditStorageException failure = assertThrows(AuditStorageException.class,
                () -> WeComAuthorizationAuditTrail.open(settings(current),
                        Clock.fixed(NOW, ZoneOffset.UTC), ignored -> { }));

        assertEquals("AUDIT_AUTHORIZATION_ENTRY_INVALID", failure.code());
    }

    private AuditFileSettings settings(Path file) {
        return new AuditFileSettings(file, 7, 4_096L, 131_072L,
                4_096L, Duration.ofHours(1));
    }

    private static WeComCallbackCodec.DecodedCallback callback(
            String type, String corpId, String authCode, String ticket) {
        return new WeComCallbackCodec.DecodedCallback(
                "dk-suite", type, corpId, authCode, ticket, "", NOW);
    }

    private static WeComAuthorizationException temporaryFailure() {
        return new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 502,
                "企业微信上游服务暂不可用", 40085,
                "/cgi-bin/service/v2/get_auth_info", 200, "safe_hint", null);
    }
}
