package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComLoginAttemptServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsConsumesExpiresAndBoundsAttemptsWithoutAuditingState() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1000));
        Config config = config("2");
        WeComAuthorizationStore store = store(clock);
        store.upsertActive("dk-test-suite", "ww-test-corp", "1000247", "permanent-code");
        Queue<String> states = states(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccccccccccccccc");
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(
                config, store, clock, states::remove);

        WeComLoginAttemptService.LoginAttemptResponse first = service.createAttempt();
        assertEquals("ww-test-corp", first.corpId());
        assertEquals("1000247", first.agentId());
        assertEquals("https://crm.example.com/", first.redirectUri());
        assertEquals(30, first.expiresIn());
        service.createAttempt();
        assertThrows(WeComLoginAttemptService.PendingLimitException.class, service::createAttempt);

        WeComLoginAttemptService.InstallationBinding binding = service.consume(first.state());
        assertEquals("dk-test-suite", binding.suiteId());
        assertEquals("ww-test-corp", binding.authCorpId());
        assertEquals("1000247", binding.agentId());
        assertEquals(1, binding.version());
        assertThrows(SecurityException.class, () -> service.consume(first.state()));

        WeComLoginAttemptService.LoginAttemptResponse expiring = service.createAttempt();
        clock.advanceSeconds(30);
        assertThrows(SecurityException.class, () -> service.consume(expiring.state()));

        String audit = Files.readString(config.wecomViewerAuditFile(), StandardCharsets.UTF_8);
        assertTrue(audit.contains("wecom.viewer.login_attempt_create"));
        assertTrue(audit.contains("wecom.viewer.login_attempt_consume"));
        assertTrue(audit.contains("rate_limited"));
        assertFalse(audit.contains(first.state()));
        assertFalse(audit.contains(expiring.state()));
        assertFalse(audit.contains("permanent-code"));
    }

    @Test
    void rejectsAttemptWhenInstallationVersionChangesOrIsRevoked() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(2000));
        Config config = config("4");
        WeComAuthorizationStore store = store(clock);
        store.upsertActive("dk-test-suite", "ww-test-corp", "1000247", "permanent-code-1");
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, store, clock, states(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")::remove);

        WeComLoginAttemptService.LoginAttemptResponse changed = service.createAttempt();
        store.upsertActive("dk-test-suite", "ww-test-corp", "1000248", "permanent-code-2");
        WeComAuthorizationException changedError = assertThrows(WeComAuthorizationException.class,
                () -> service.consume(changed.state()));
        assertEquals("WECOM_INSTALLATION_CHANGED", changedError.code());
        assertEquals(403, changedError.httpStatus());

        WeComLoginAttemptService.LoginAttemptResponse revoked = service.createAttempt();
        store.updateStatus("dk-test-suite", "ww-test-corp", WeComAuthorizationStore.AuthStatus.REVOKED);
        WeComAuthorizationException revokedError = assertThrows(WeComAuthorizationException.class,
                () -> service.consume(revoked.state()));
        assertEquals("WECOM_INSTALLATION_INACTIVE", revokedError.code());
    }

    @Test
    void failsClosedWithoutConfiguredOrActiveInstallation() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(3000));
        Config missingSelection = new Config(Map.of(
                "WECOM_SUITE_ID", "dk-test-suite",
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/",
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit-missing.jsonl").toString()));
        WeComAuthorizationStore store = store(clock);
        WeComLoginAttemptService missingSelectionService = WeComLoginAttemptService.forTests(
                missingSelection, store, clock, () -> "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals("WECOM_LOGIN_INSTALLATION_NOT_SELECTED",
                assertThrows(WeComAuthorizationException.class,
                        missingSelectionService::createAttempt).code());

        WeComLoginAttemptService noStoreService = new WeComLoginAttemptService(config("2"));
        assertEquals("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE",
                assertThrows(WeComAuthorizationException.class, noStoreService::createAttempt).code());

        WeComLoginAttemptService missingInstallationService = WeComLoginAttemptService.forTests(
                config("2"), store, clock, () -> "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertEquals("WECOM_INSTALLATION_NOT_FOUND",
                assertThrows(WeComAuthorizationException.class,
                        missingInstallationService::createAttempt).code());
    }

    private Config config(String maxPending) {
        return new Config(Map.of(
                "WECOM_SUITE_ID", "dk-test-suite",
                "WECOM_LOGIN_AUTH_CORP_ID", "ww-test-corp",
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/",
                "WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "30",
                "WECOM_LOGIN_MAX_PENDING", maxPending,
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit-" + maxPending + ".jsonl").toString()));
    }

    private WeComAuthorizationStore store(Clock clock) {
        byte[] key = new byte[32];
        byte[] seed = "wecom-login-attempt-test".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(seed, 0, key, 0, seed.length);
        CredentialCipher cipher = CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
        return WeComAuthorizationStore.forTests(tempDir.resolve("installations.jsonl"), cipher, clock);
    }

    private static Queue<String> states(String... values) {
        Queue<String> states = new ArrayDeque<>();
        java.util.Collections.addAll(states, values);
        return states;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
