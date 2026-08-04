package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataPublicKeyRegistrarTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final WeComChatDataCrypto.PublicKeyMaterial MATERIAL =
            new WeComChatDataCrypto.PublicKeyMaterial(
                    "-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----\n",
                    1, "a".repeat(64), 2048);

    @TempDir
    Path tempDir;

    @Test
    void openDoesNotBlockRuntimeWhenPrivateKeyIsUnavailable() throws Exception {
        Config config = config("missing-key.json");
        WeComAuthorizationStore store = store("missing-key-installations.jsonl");
        WeComAccessTokenService accessTokens = WeComAccessTokenService.forTests(
                config, Clock.fixed(NOW, ZoneOffset.UTC),
                (authCorpId, permanentCode, timeout) ->
                        new WeComAuthorizationGateway.CorpTokenResponse("corp-token", 7200));

        try (WeComChatDataPublicKeyRegistrar ignored = assertDoesNotThrow(
                () -> WeComChatDataPublicKeyRegistrar.open(config, store, accessTokens))) {
            // Opening the optional registrar must not make the web runtime depend on the key file.
        }
    }

    @Test
    void openDegradesWhenAuthorizationOwnersAreUnavailable() {
        Config config = config("missing-owners.json");

        try (WeComChatDataPublicKeyRegistrar ignored = assertDoesNotThrow(
                () -> WeComChatDataPublicKeyRegistrar.open(config, null, null))) {
            // Optional automatic registration cannot own the web runtime lifecycle.
        }
    }

    @Test
    void retriesFailedKeyLoadAndCachesTheFirstSuccessfulMaterial() throws Exception {
        Config config = config("lazy-key.json");
        WeComAuthorizationStore store = store("lazy-key-installations.jsonl");
        store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
        AtomicBoolean keyReady = new AtomicBoolean();
        AtomicInteger keyLoads = new AtomicInteger();
        AtomicInteger gatewayCalls = new AtomicInteger();
        AtomicReference<String> failureEvent = new AtomicReference<>();
        WeComChatDataPublicKeyRegistrationStore state = state(config);

        try (WeComChatDataPublicKeyRegistrar registrar = WeComChatDataPublicKeyRegistrar.forTests(
                config, store, installation -> "sensitive-token",
                () -> {
                    keyLoads.incrementAndGet();
                    if (!keyReady.get()) {
                        throw new WeComChatDataException("WECOM_CHATDATA_DECRYPT_FAILED", 500,
                                "sensitive private key path and contents");
                    }
                    return MATERIAL;
                }, state, (token, material) -> gatewayCalls.incrementAndGet(), failureEvent::set)) {
            assertEquals(0, keyLoads.get());

            registrar.requestRegistration();
            waitUntil(() -> registrar.pendingCount() == 0);

            assertEquals(1, keyLoads.get());
            assertEquals(0, gatewayCalls.get());
            assertFalse(state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
            assertEquals(WeComAuthorizationStore.AuthStatus.ACTIVE,
                    store.requireActive("dk-suite", "ww-corp").authStatus());
            assertTrue(failureEvent.get().contains("WECOM_CHATDATA_DECRYPT_FAILED"));
            assertFalse(failureEvent.get().contains("sensitive"));

            keyReady.set(true);
            registrar.requestRegistration();
            waitUntil(() -> state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
            assertEquals(2, keyLoads.get());
            assertEquals(1, gatewayCalls.get());

            registrar.requestRegistration();
            waitUntil(() -> registrar.pendingCount() == 0);
            assertEquals(2, keyLoads.get());
            assertEquals(1, gatewayCalls.get());
        }
    }

    @Test
    void requestIsNonBlockingAndCoalescesSignalsWhileRegistrationRuns() throws Exception {
        Config config = config("coalesced.json");
        WeComAuthorizationStore store = store("coalesced-installations.jsonl");
        store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger gatewayCalls = new AtomicInteger();
        WeComChatDataPublicKeyRegistrationStore state = state(config);
        try (WeComChatDataPublicKeyRegistrar registrar = WeComChatDataPublicKeyRegistrar.forTests(
                config, store, installation -> "corp-token", MATERIAL, state,
                (token, material) -> {
                    gatewayCalls.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                }, ignored -> {})) {
            registrar.requestRegistration();
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            for (int index = 0; index < 100; index++) registrar.requestRegistration();
            assertEquals(2, registrar.pendingCount());
            release.countDown();
            waitUntil(() -> registrar.pendingCount() == 0);

            assertEquals(1, gatewayCalls.get());
            assertTrue(state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void retriesWhenInstallationAndTicketBecomeReadyInEitherOrder() throws Exception {
        Config config = config("ordered.json");
        WeComAuthorizationStore store = store("ordered-installations.jsonl");
        AtomicBoolean ticketReady = new AtomicBoolean();
        AtomicInteger gatewayCalls = new AtomicInteger();
        WeComChatDataPublicKeyRegistrationStore state = state(config);
        try (WeComChatDataPublicKeyRegistrar registrar = WeComChatDataPublicKeyRegistrar.forTests(
                config, store, installation -> {
                    if (!ticketReady.get()) {
                        throw new WeComAuthorizationException("WECOM_SUITE_TICKET_NOT_READY", 503,
                                "ticket unavailable");
                    }
                    return "corp-token";
                }, MATERIAL, state,
                (token, material) -> gatewayCalls.incrementAndGet(), ignored -> {})) {
            registrar.requestRegistration();
            waitUntil(() -> registrar.pendingCount() == 0);
            assertFalse(state.isRegistered("ww-corp", 1, MATERIAL.sha256()));

            store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
            registrar.requestRegistration();
            waitUntil(() -> registrar.pendingCount() == 0);
            assertEquals(0, gatewayCalls.get());

            ticketReady.set(true);
            registrar.requestRegistration();
            waitUntil(() -> state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
            assertEquals(1, gatewayCalls.get());
        }
    }

    @Test
    void failedRegistrationWaitsForNextSignalAndKeepsInstallationActive() throws Exception {
        Config config = config("retry.json");
        WeComAuthorizationStore store = store("retry-installations.jsonl");
        store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
        AtomicInteger gatewayCalls = new AtomicInteger();
        AtomicReference<String> failureEvent = new AtomicReference<>();
        WeComChatDataPublicKeyRegistrationStore state = state(config);
        try (WeComChatDataPublicKeyRegistrar registrar = WeComChatDataPublicKeyRegistrar.forTests(
                config, store, installation -> "sensitive-token", MATERIAL, state,
                (token, material) -> {
                    if (gatewayCalls.incrementAndGet() == 1) {
                        throw new WeComChatDataException(
                                "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", 503,
                                "safe failure", 600001,
                                null, null, "hint-600001",
                                new IllegalStateException("sensitive-token permanent-code"));
                    }
                }, failureEvent::set)) {
            registrar.requestRegistration();
            waitUntil(() -> registrar.pendingCount() == 0);

            assertFalse(state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
            assertEquals(WeComAuthorizationStore.AuthStatus.ACTIVE,
                    store.requireActive("dk-suite", "ww-corp").authStatus());
            assertTrue(failureEvent.get().contains("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED"));
            assertTrue(failureEvent.get().contains("\"upstreamErrcode\":600001"));
            assertTrue(failureEvent.get().contains("\"upstreamHint\":\"hint-600001\""));
            assertFalse(failureEvent.get().contains("sensitive-token"));
            assertFalse(failureEvent.get().contains("permanent-code"));

            registrar.requestRegistration();
            waitUntil(() -> state.isRegistered("ww-corp", 1, MATERIAL.sha256()));
            assertEquals(2, gatewayCalls.get());
        }
    }

    private Config config(String stateFile) {
        return new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_LOGIN_AUTH_CORP_ID", "ww-corp",
                "WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER", "true",
                "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE", tempDir.resolve(stateFile).toString()));
    }

    private WeComAuthorizationStore store(String filename) {
        byte[] key = new byte[32];
        byte[] seed = filename.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(seed, 0, key, 0, Math.min(seed.length, key.length));
        return WeComAuthorizationStore.forTests(tempDir.resolve(filename),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WeComChatDataPublicKeyRegistrationStore state(Config config) {
        return WeComChatDataPublicKeyRegistrationStore.forTests(config, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void waitUntil(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.matches()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition was not met");
            Thread.sleep(10);
        }
    }

    private interface CheckedCondition {
        boolean matches() throws Exception;
    }
}
