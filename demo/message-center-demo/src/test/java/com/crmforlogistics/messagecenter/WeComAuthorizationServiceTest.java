package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void handlesTicketCreateChangeReplayAndCancellation() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        try (WeComAuthorizationService service = new WeComAuthorizationService(config(4), store, gateway)) {
            assertTrue(service.handle(callback("suite_ticket", "", "", "ticket-1")).success());
            assertEquals("ticket-1", gateway.suiteTicket);

            WeComCallbackCodec.DecodedCallback create = callback("create_auth", "ww-corp", "auth-code", "");
            assertTrue(service.handle(create).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            assertEquals(1, gateway.permanentCodeCalls.get());
            assertTrue(service.handle(create).success());
            Thread.sleep(50);
            assertEquals(1, gateway.permanentCodeCalls.get());

            gateway.agentId = "1000003";
            assertTrue(service.handle(callback("change_auth", "ww-corp", "", "")).success());
            waitUntil(() -> store.requireActive("dk-suite", "ww-corp").version() == 2);
            assertEquals("1000003", store.requireActive("dk-suite", "ww-corp").agentId());

            assertTrue(service.handle(callback("cancel_auth", "ww-corp", "", "")).success());
            assertEquals(WeComAuthorizationStore.AuthStatus.REVOKED,
                    store.find("dk-suite", "ww-corp").orElseThrow().authStatus());
        }
    }

    @Test
    void releasesFailedEventSoOfficialRedeliveryCanRetry() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        gateway.failPermanentCodeOnce = true;
        WeComCallbackCodec.DecodedCallback create = callback("create_auth", "ww-corp", "auth-code", "");
        try (WeComAuthorizationService service = new WeComAuthorizationService(config(2), store, gateway)) {
            assertTrue(service.handle(create).success());
            waitUntil(() -> gateway.permanentCodeCalls.get() == 1);
            waitUntil(() -> service.pendingCount() == 0);
            assertTrue(service.handle(create).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            assertEquals(2, gateway.permanentCodeCalls.get());
        }
    }

    @Test
    void appliesBackpressureAndCloseDrainsAcceptedQueue() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        gateway.blockFirstCall = true;
        WeComAuthorizationService service = new WeComAuthorizationService(config(1), store, gateway);
        try {
            assertTrue(service.handle(callback("create_auth", "ww-one", "code-one", "")).success());
            assertTrue(gateway.firstCallEntered.await(1, TimeUnit.SECONDS));
            assertTrue(service.handle(callback("create_auth", "ww-two", "code-two", "")).success());
            assertFalse(service.handle(callback("create_auth", "ww-three", "code-three", "")).success());
            gateway.releaseFirstCall.countDown();
            service.close();
            assertTrue(store.find("dk-suite", "ww-one").isPresent());
            assertTrue(store.find("dk-suite", "ww-two").isPresent());
            assertFalse(service.handle(callback("create_auth", "ww-three", "code-three", "")).success());
        } finally {
            gateway.releaseFirstCall.countDown();
            service.close();
        }
    }

    @Test
    void failedChangeCanRecoverButCancellationCannotBeOverwritten() throws Exception {
        WeComAuthorizationStore store = store();
        store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
        FakeGateway gateway = new FakeGateway();
        gateway.failAuthInfoOnce = true;
        WeComCallbackCodec.DecodedCallback change = callback("change_auth", "ww-corp", "", "");
        try (WeComAuthorizationService service = new WeComAuthorizationService(config(2), store, gateway)) {
            assertTrue(service.handle(change).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").orElseThrow().authStatus()
                    == WeComAuthorizationStore.AuthStatus.FAILED);
            gateway.agentId = "1000003";
            assertTrue(service.handle(change).success());
            waitUntil(() -> {
                WeComAuthorizationStore.Installation installation =
                        store.find("dk-suite", "ww-corp").orElseThrow();
                return installation.authStatus() == WeComAuthorizationStore.AuthStatus.ACTIVE
                        && installation.agentId().equals("1000003");
            });

            gateway.blockAuthInfo = true;
            WeComCallbackCodec.DecodedCallback laterChange = new WeComCallbackCodec.DecodedCallback(
                    "dk-suite", "change_auth", "ww-corp", "", "", "", NOW.plusSeconds(1));
            assertTrue(service.handle(laterChange).success());
            assertTrue(gateway.authInfoEntered.await(1, TimeUnit.SECONDS));
            WeComCallbackCodec.DecodedCallback cancellation = new WeComCallbackCodec.DecodedCallback(
                    "dk-suite", "cancel_auth", "ww-corp", "", "", "", NOW.plusSeconds(2));
            assertTrue(service.handle(cancellation).success());
            gateway.releaseAuthInfo.countDown();
            waitUntil(() -> service.pendingCount() == 0);
            assertEquals(WeComAuthorizationStore.AuthStatus.REVOKED,
                    store.find("dk-suite", "ww-corp").orElseThrow().authStatus());
        } finally {
            gateway.releaseAuthInfo.countDown();
        }
    }

    private Config config(int capacity) {
        return new Config(Map.of(
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_AUTHORIZATION_QUEUE_CAPACITY", Integer.toString(capacity)));
    }

    private WeComAuthorizationStore store() {
        byte[] key = new byte[32];
        byte[] seed = "wecom-authorization-service".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(seed, 0, key, 0, seed.length);
        return WeComAuthorizationStore.forTests(tempDir.resolve("installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WeComCallbackCodec.DecodedCallback callback(String type, String corpId,
                                                                String authCode, String ticket) {
        return new WeComCallbackCodec.DecodedCallback("dk-suite", type, corpId, authCode, ticket, "", NOW);
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

    private static final class FakeGateway implements WeComAuthorizationClient {
        private final AtomicInteger permanentCodeCalls = new AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);
        private final CountDownLatch authInfoEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAuthInfo = new CountDownLatch(1);
        private volatile String suiteTicket = "";
        private volatile String agentId = "1000002";
        private volatile boolean failPermanentCodeOnce;
        private volatile boolean blockFirstCall;
        private volatile boolean failAuthInfoOnce;
        private volatile boolean blockAuthInfo;

        @Override
        public void acceptSuiteTicket(String suiteId, String ticket, Instant receivedAt) {
            suiteTicket = ticket;
        }

        @Override
        public WeComAuthorizationGateway.PermanentCodeResponse getPermanentCode(String authCode)
                throws WeComAuthorizationException {
            int call = permanentCodeCalls.incrementAndGet();
            if (blockFirstCall && call == 1) {
                firstCallEntered.countDown();
                try {
                    if (!releaseFirstCall.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "interrupted");
                }
            }
            if (failPermanentCodeOnce) {
                failPermanentCodeOnce = false;
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "temporary failure");
            }
            String corpId = switch (authCode) {
                case "code-one" -> "ww-one";
                case "code-two" -> "ww-two";
                case "code-three" -> "ww-three";
                default -> "ww-corp";
            };
            return new WeComAuthorizationGateway.PermanentCodeResponse(corpId, "permanent-" + authCode);
        }

        @Override
        public WeComAuthorizationGateway.AuthorizationInfo getAuthInfo(String authCorpId, String permanentCode)
                throws WeComAuthorizationException {
            if (failAuthInfoOnce) {
                failAuthInfoOnce = false;
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "temporary failure");
            }
            if (blockAuthInfo) {
                authInfoEntered.countDown();
                try {
                    if (!releaseAuthInfo.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "interrupted");
                }
            }
            return new WeComAuthorizationGateway.AuthorizationInfo(authCorpId,
                    List.of(new WeComAuthorizationGateway.AuthorizedAgent(agentId)));
        }
    }
}
