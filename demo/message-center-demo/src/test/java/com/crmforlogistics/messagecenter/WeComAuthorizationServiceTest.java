package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void neverMutatesStoreWhenAcceptedOrPendingCannotPersist() throws Exception {
        WeComCallbackCodec.DecodedCallback create =
                callback("create_auth", "ww-corp", "auth-code", "");

        WeComAuthorizationStore acceptedStore = store(tempDir.resolve("accepted-installations.jsonl"));
        RecordingAppender acceptedAppender = new RecordingAppender();
        acceptedAppender.failResultOnce("accepted");
        try (WeComAuthorizationService service = service(
                acceptedStore, new FakeGateway(), acceptedAppender, 2)) {
            assertFalse(service.handle(create).success());
            assertTrue(acceptedStore.find("dk-suite", "ww-corp").isEmpty());
            assertEquals(0, service.pendingCount());
        }

        WeComAuthorizationStore pendingStore = store(tempDir.resolve("pending-installations.jsonl"));
        RecordingAppender pendingAppender = new RecordingAppender();
        pendingAppender.failResultOnce("pending");
        try (WeComAuthorizationService service = service(
                pendingStore, new FakeGateway(), pendingAppender, 2)) {
            assertTrue(service.handle(create).success());
            waitUntil(() -> service.pendingCount() == 0);
            assertTrue(pendingStore.find("dk-suite", "ww-corp").isEmpty());
            assertTrue(pendingAppender.hasResult("failed"));
        }
    }

    @Test
    void closesFinalWriteFailureFromStoreMarkerOnRestart() throws Exception {
        Path auditFile = tempDir.resolve("restart-audit.jsonl");
        Config config = config(2, auditFile);
        WeComAuthorizationStore store = store();
        FileAppender firstAppender = new FileAppender(auditFile);
        firstAppender.failResultOnce("succeeded");
        WeComAuthorizationAuditTrail firstAudit = openAudit(config, firstAppender);

        try (WeComAuthorizationService service = service(
                config, store, new FakeGateway(), firstAudit)) {
            assertTrue(service.handle(callback("create_auth", "ww-corp", "auth-code", "")).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            waitUntil(() -> service.pendingCount() == 0);
            assertTrue(firstAudit.index().openAttempts().stream()
                    .anyMatch(open -> open.phase() == AuthorizationAuditIndex.Phase.PENDING));
        }

        FileAppender recoveredAppender = new FileAppender(auditFile);
        WeComAuthorizationAuditTrail recoveredAudit = openAudit(config, recoveredAppender);
        try (WeComAuthorizationService ignored = service(
                config, store, new FakeGateway(), recoveredAudit)) {
            assertTrue(recoveredAudit.index().openAttempts().isEmpty());
            assertTrue(Files.readString(auditFile).contains("\"result\":\"succeeded\""));
        }
    }

    @Test
    void startupClosesAcceptedAndSuiteTicketPendingAsInterrupted() throws Exception {
        Path acceptedFile = tempDir.resolve("accepted-recovery.jsonl");
        Config acceptedConfig = config(2, acceptedFile);
        FileAppender acceptedAppender = new FileAppender(acceptedFile);
        WeComAuthorizationAuditTrail acceptedAudit = openAudit(acceptedConfig, acceptedAppender);
        acceptedAudit.begin(callback("create_auth", "ww-corp", "auth-code", ""));
        WeComAuthorizationAuditTrail acceptedReloaded = openAudit(
                acceptedConfig, new FileAppender(acceptedFile));
        try (WeComAuthorizationService ignored = service(
                acceptedConfig, store(tempDir.resolve("accepted-recovery-store.jsonl")),
                new FakeGateway(), acceptedReloaded)) {
            assertTrue(acceptedReloaded.index().openAttempts().isEmpty());
            assertTrue(Files.readString(acceptedFile)
                    .contains("WECOM_AUTHORIZATION_PROCESS_INTERRUPTED"));
        }

        Path ticketFile = tempDir.resolve("ticket-recovery.jsonl");
        Config ticketConfig = config(2, ticketFile);
        FileAppender ticketAppender = new FileAppender(ticketFile);
        WeComAuthorizationAuditTrail ticketAudit = openAudit(ticketConfig, ticketAppender);
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt ticketAttempt = ticketAudit
                .begin(callback("suite_ticket", "", "", "ticket-1")).attempt();
        ticketAudit.pending(ticketAttempt, "", WeComAuthorizationStore.AuthStatus.ACTIVE, 0);
        WeComAuthorizationAuditTrail ticketReloaded = openAudit(
                ticketConfig, new FileAppender(ticketFile));
        FakeGateway restartedGateway = new FakeGateway();
        try (WeComAuthorizationService ignored = service(
                ticketConfig, store(tempDir.resolve("ticket-recovery-store.jsonl")),
                restartedGateway, ticketReloaded)) {
            assertTrue(ticketReloaded.index().openAttempts().isEmpty());
            assertEquals("", restartedGateway.suiteTicket);
            assertTrue(Files.readString(ticketFile)
                    .contains("WECOM_AUTHORIZATION_PROCESS_INTERRUPTED"));
        }
    }

    @Test
    void pendingStoreConflictKeepsGateClosedToDifferentEvents() throws Exception {
        Path auditFile = tempDir.resolve("conflict-audit.jsonl");
        Config config = config(2, auditFile);
        FileAppender appender = new FileAppender(auditFile);
        WeComAuthorizationAuditTrail audit = openAudit(config, appender);
        WeComCallbackCodec.DecodedCallback pendingCallback =
                callback("change_auth", "ww-corp", "", "");
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt =
                audit.begin(pendingCallback).attempt();
        audit.pending(attempt, "ww-corp", WeComAuthorizationStore.AuthStatus.ACTIVE, 1);

        WeComAuthorizationStore store = store();
        store.upsertActiveForEvent("dk-suite", "ww-corp", "1000002", "permanent-code",
                "sha256:" + "1".repeat(64), NOW.minusSeconds(1));
        try (WeComAuthorizationService service = service(config, store, new FakeGateway(),
                openAudit(config, new FileAppender(auditFile)))) {
            assertFalse(service.handle(new WeComCallbackCodec.DecodedCallback(
                    "dk-suite", "cancel_auth", "ww-corp", "", "", "", NOW.plusSeconds(1))).success());
            assertEquals(WeComAuthorizationStore.AuthStatus.ACTIVE,
                    store.find("dk-suite", "ww-corp").orElseThrow().authStatus());
        }
    }

    @Test
    void replayUsesPersistedSuccessWithoutIncrementingStoreVersion() throws Exception {
        RecordingAppender appender = new RecordingAppender();
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        WeComCallbackCodec.DecodedCallback create =
                callback("create_auth", "ww-corp", "auth-code", "");
        try (WeComAuthorizationService service = service(store, gateway, appender, 2)) {
            assertTrue(service.handle(create).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            waitUntil(() -> service.pendingCount() == 0);
            long version = store.find("dk-suite", "ww-corp").orElseThrow().version();
            assertTrue(service.handle(create).success());
            Thread.sleep(50);
            assertEquals(version, store.find("dk-suite", "ww-corp").orElseThrow().version());
            assertEquals(1, gateway.permanentCodeCalls.get());
        }
    }

    @Test
    void queueFullClosesAcceptedAttemptWithFailed() throws Exception {
        RecordingAppender appender = new RecordingAppender();
        FakeGateway gateway = new FakeGateway();
        gateway.blockFirstCall = true;
        try (WeComAuthorizationService service = service(store(), gateway, appender, 1)) {
            assertTrue(service.handle(callback("create_auth", "ww-one", "code-one", "")).success());
            assertTrue(gateway.firstCallEntered.await(1, TimeUnit.SECONDS));
            assertTrue(service.handle(callback("create_auth", "ww-two", "code-two", "")).success());
            assertFalse(service.handle(callback("create_auth", "ww-three", "code-three", "")).success());
            assertTrue(appender.contains("\"errorCode\":\"WECOM_AUTHORIZATION_QUEUE_FULL\""));
            gateway.releaseFirstCall.countDown();
        } finally {
            gateway.releaseFirstCall.countDown();
        }
    }

    @Test
    void oneTimeAuthCodeFailureBeforePendingDoesNotPersistCredential() throws Exception {
        RecordingAppender appender = new RecordingAppender();
        appender.failResultOnce("pending");
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        try (WeComAuthorizationService service = service(store, gateway, appender, 2)) {
            assertTrue(service.handle(callback(
                    "create_auth", "ww-corp", "one-time-auth-code", "")).success());
            waitUntil(() -> service.pendingCount() == 0);
            assertEquals(1, gateway.permanentCodeCalls.get());
            assertTrue(store.find("dk-suite", "ww-corp").isEmpty());
            assertTrue(appender.hasResult("failed"));
            assertFalse(appender.joined().contains("one-time-auth-code"));
            assertFalse(appender.joined().contains("permanent-one-time-auth-code"));
        }
    }

    @Test
    void handlesTicketCreateChangeReplayAndCancellation() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        AtomicInteger registrationRequests = new AtomicInteger();
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config(4), store, gateway, registrationRequests::incrementAndGet)) {
            assertTrue(service.handle(callback("suite_ticket", "", "", "ticket-1")).success());
            assertEquals("ticket-1", gateway.suiteTicket);
            assertEquals(1, registrationRequests.get());
            String ticketAudit = Files.readString(tempDir.resolve("authorization-audit.jsonl"));
            assertTrue(ticketAudit.contains("\"action\":\"wecom.authorization.suite_ticket\""));
            assertTrue(ticketAudit.contains("\"result\":\"succeeded\""));
            assertFalse(ticketAudit.contains("ticket-1"));

            WeComCallbackCodec.DecodedCallback create = callback("create_auth", "ww-corp", "auth-code", "");
            assertTrue(service.handle(create).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            waitUntil(() -> registrationRequests.get() == 2);
            assertEquals(1, gateway.permanentCodeCalls.get());
            assertTrue(service.handle(create).success());
            Thread.sleep(50);
            assertEquals(1, gateway.permanentCodeCalls.get());

            gateway.agentId = "1000003";
            assertTrue(service.handle(callback("change_auth", "ww-corp", "", "")).success());
            waitUntil(() -> store.requireActive("dk-suite", "ww-corp").version() == 2);
            waitUntil(() -> registrationRequests.get() == 3);
            assertEquals("1000003", store.requireActive("dk-suite", "ww-corp").agentId());

            assertTrue(service.handle(callback("cancel_auth", "ww-corp", "", "")).success());
            assertEquals(WeComAuthorizationStore.AuthStatus.REVOKED,
                    store.find("dk-suite", "ww-corp").orElseThrow().authStatus());
        }
    }

    @Test
    void registrationTriggerFailureCannotRejectCallbackOrPoisonInstallation() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config(2), store, gateway, () -> {
                    throw new IllegalStateException("registration trigger unavailable");
                })) {
            assertTrue(service.handle(callback("suite_ticket", "", "", "ticket-1")).success());
            assertTrue(service.handle(callback("create_auth", "ww-corp", "auth-code", "")).success());
            waitUntil(() -> store.find("dk-suite", "ww-corp").isPresent());
            assertEquals(WeComAuthorizationStore.AuthStatus.ACTIVE,
                    store.requireActive("dk-suite", "ww-corp").authStatus());
        }
    }

    @Test
    void resetPermanentCodeReplacesExistingEncryptedCredential() throws Exception {
        WeComAuthorizationStore store = store();
        WeComAuthorizationStore.Installation original =
                store.upsertActive("dk-suite", "ww-corp", "1000002", "old-permanent-code");
        FakeGateway gateway = new FakeGateway();
        AtomicInteger registrationRequests = new AtomicInteger();
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config(2), store, gateway, registrationRequests::incrementAndGet)) {
            WeComCallbackCodec.DecodedCallback reset =
                    callback("reset_permanent_code", "", "reset-code", "");

            assertTrue(service.handle(reset).success());
            waitUntil(() -> store.requireActive("dk-suite", "ww-corp").version() == original.version() + 1);

            WeComAuthorizationStore.Installation updated = store.requireActive("dk-suite", "ww-corp");
            assertEquals(original.installationId(), updated.installationId());
            assertEquals("permanent-reset-code",
                    store.resolveActive("dk-suite", "ww-corp").permanentCode());
            assertEquals("permanent-reset-code", gateway.lastAuthInfoPermanentCode);
            waitUntil(() -> registrationRequests.get() == 1);
            assertEquals(1, registrationRequests.get());
        }
    }

    @Test
    void rejectsInvalidResetAndDoesNotCreateUnknownInstallation() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        try (WeComAuthorizationService service = new WeComAuthorizationService(config(2), store, gateway)) {
            assertFalse(service.handle(callback("reset_permanent_code", "", "", "")).success());
            WeComCallbackCodec.DecodedCallback wrongSuite = new WeComCallbackCodec.DecodedCallback(
                    "ww-login-suite", "reset_permanent_code", "", "reset-code", "", "", NOW);
            assertFalse(service.handle(wrongSuite).success());
            assertEquals(0, gateway.permanentCodeCalls.get());

            assertTrue(service.handle(callback("reset_permanent_code", "", "reset-code", "")).success());
            waitUntil(() -> service.pendingCount() == 0);
            assertTrue(store.find("dk-suite", "ww-corp").isEmpty());
            assertEquals(1, gateway.permanentCodeCalls.get());
            assertEquals(0, gateway.authInfoCalls.get());
        }
    }

    @Test
    void acceptsLoginSuiteTicketWithoutTriggeringChatdataRegistration() throws Exception {
        WeComAuthorizationStore store = store();
        FakeGateway gateway = new FakeGateway();
        AtomicInteger registrationRequests = new AtomicInteger();
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                "WECOM_LOGIN_SUITE_SECRET", "login-secret",
                "WECOM_AUTHORIZATION_QUEUE_CAPACITY", "2"));
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config, store, gateway, registrationRequests::incrementAndGet)) {
            WeComCallbackCodec.DecodedCallback callback = new WeComCallbackCodec.DecodedCallback(
                    "ww-login-suite", "suite_ticket", "", "", "login-ticket", "", NOW);
            assertTrue(service.handle(callback).success());
            assertEquals("login-ticket", gateway.suiteTicket);
            assertEquals(0, registrationRequests.get());
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
    void recordsSanitizedUpstreamFailureForPermanentCodeReset() throws Exception {
        WeComAuthorizationStore store = store();
        WeComAuthorizationStore.Installation original =
                store.upsertActive("dk-suite", "ww-corp", "1000002", "old-permanent-code");
        FakeGateway gateway = new FakeGateway();
        gateway.failAuthInfoWithUpstreamDetails = true;
        RecordingAppender appender = new RecordingAppender();
        try (WeComAuthorizationService service = service(store, gateway, appender, 2)) {
            assertTrue(service.handle(callback("reset_permanent_code", "", "sensitive-auth-code", "")).success());
            waitUntil(() -> service.pendingCount() == 0);

            WeComAuthorizationStore.Installation after =
                    store.find("dk-suite", "ww-corp").orElseThrow();
            assertEquals(original.version(), after.version());
            assertEquals(WeComAuthorizationStore.AuthStatus.ACTIVE, after.authStatus());
            String entries = appender.joined();
            assertTrue(entries.contains("\"action\":\"wecom.authorization.reset_permanent_code\""));
            assertTrue(entries.contains("\"authCorpId\":\"ww-corp\""));
            assertTrue(entries.contains("\"errorCode\":\"WECOM_UPSTREAM_UNAVAILABLE\""));
            assertTrue(entries.contains("\"upstreamErrcode\":40085"));
            assertTrue(entries.contains("\"upstreamPath\":\"/cgi-bin/service/v2/get_auth_info\""));
            assertFalse(entries.contains("sensitive-auth-code"));
            assertFalse(entries.contains("permanent-reset-code"));
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
        WeComAuthorizationStore.Installation original =
                store.upsertActive("dk-suite", "ww-corp", "1000002", "permanent-code");
        FakeGateway gateway = new FakeGateway();
        gateway.failAuthInfoOnce = true;
        WeComCallbackCodec.DecodedCallback change = callback("change_auth", "ww-corp", "", "");
        try (WeComAuthorizationService service = new WeComAuthorizationService(config(2), store, gateway)) {
            assertTrue(service.handle(change).success());
            waitUntil(() -> service.pendingCount() == 0);
            assertEquals(original.version(),
                    store.find("dk-suite", "ww-corp").orElseThrow().version());
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
        return config(capacity, tempDir.resolve("authorization-audit.jsonl"));
    }

    private Config config(int capacity, Path auditFile) {
        return new Config(Map.of(
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_AUTHORIZATION_QUEUE_CAPACITY", Integer.toString(capacity),
                "WECOM_AUTHORIZATION_AUDIT_FILE", auditFile.toString()));
    }

    private WeComAuthorizationStore store() {
        return store(tempDir.resolve("installations.jsonl"));
    }

    private WeComAuthorizationStore store(Path file) {
        byte[] key = new byte[32];
        byte[] seed = "wecom-authorization-service".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(seed, 0, key, 0, seed.length);
        return WeComAuthorizationStore.forTests(file,
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WeComAuthorizationService service(WeComAuthorizationStore store, FakeGateway gateway,
                                              RecordingAppender appender, int capacity)
            throws Exception {
        Config config = config(capacity);
        return service(config, store, gateway,
                WeComAuthorizationAuditTrail.forTests(Clock.fixed(NOW, ZoneOffset.UTC), appender));
    }

    private WeComAuthorizationService service(Config config, WeComAuthorizationStore store,
                                              FakeGateway gateway,
                                              WeComAuthorizationAuditTrail auditTrail) {
        return new WeComAuthorizationService(config, store, gateway, () -> {}, auditTrail);
    }

    private WeComAuthorizationAuditTrail openAudit(Config config,
                                                   WeComAuthorizationAuditTrail.RequiredLineAppender appender)
            throws AuditStorageException {
        return WeComAuthorizationAuditTrail.open(config.authorizationAuditSettings(),
                Clock.fixed(NOW, ZoneOffset.UTC), appender);
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

    private static class RecordingAppender implements WeComAuthorizationAuditTrail.RequiredLineAppender {
        private final List<String> lines = new ArrayList<>();
        private final Set<String> failOnce = new HashSet<>();

        synchronized void failResultOnce(String result) {
            failOnce.add(result);
        }

        @Override
        public synchronized void append(String line) throws AuditStorageException {
            String result = JsonParser.parseString(line).getAsJsonObject()
                    .get("result").getAsString();
            if (failOnce.remove(result)) {
                throw new AuditStorageException("AUDIT_ROTATION_FAILED",
                        "wecom-authorization", null);
            }
            lines.add(line);
        }

        synchronized boolean hasResult(String result) {
            return lines.stream().map(JsonParser::parseString)
                    .map(element -> element.getAsJsonObject().get("result").getAsString())
                    .anyMatch(result::equals);
        }

        synchronized boolean contains(String fragment) {
            return joined().contains(fragment);
        }

        synchronized String joined() {
            return String.join("", lines);
        }
    }

    private static final class FileAppender extends RecordingAppender {
        private final Path file;

        private FileAppender(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void append(String line) throws AuditStorageException {
            super.append(line);
            try {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new AuditStorageException("AUDIT_ROTATION_FAILED",
                        "wecom-authorization", exception);
            }
        }
    }

    private static final class FakeGateway implements WeComAuthorizationClient {
        private final AtomicInteger permanentCodeCalls = new AtomicInteger();
        private final AtomicInteger authInfoCalls = new AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);
        private final CountDownLatch authInfoEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAuthInfo = new CountDownLatch(1);
        private volatile String suiteTicket = "";
        private volatile String agentId = "1000002";
        private volatile String lastAuthInfoPermanentCode = "";
        private volatile boolean failPermanentCodeOnce;
        private volatile boolean blockFirstCall;
        private volatile boolean failAuthInfoOnce;
        private volatile boolean failAuthInfoWithUpstreamDetails;
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
            authInfoCalls.incrementAndGet();
            lastAuthInfoPermanentCode = permanentCode;
            if (failAuthInfoOnce) {
                failAuthInfoOnce = false;
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503, "temporary failure");
            }
            if (failAuthInfoWithUpstreamDetails) {
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游服务暂时不可用", 40085,
                        "/cgi-bin/service/v2/get_auth_info", 200, null, null);
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
