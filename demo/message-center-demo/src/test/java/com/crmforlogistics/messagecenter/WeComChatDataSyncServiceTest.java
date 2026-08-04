package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataSyncServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void synchronizesPagesFromStoredCursorAndPublishesEachWholePage() throws Exception {
        Config config = config(Map.of());
        AtomicReference<String> cursor = new AtomicReference<>("");
        List<String> requestedCursors = new ArrayList<>();
        List<String> publishedCursors = new ArrayList<>();
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> {
                    requestedCursors.add(requestedCursor);
                    return requestedCursor.isBlank()
                            ? page(true, "cursor-1", "msg-1")
                            : page(false, "cursor-2", "msg-2");
                },
                (version, encrypted) -> "secret-" + encrypted,
                new WeComChatDataSyncService.StoreAccess() {
                    @Override public String cursor(WeComChatDataStore.SyncKey key) {
                        return cursor.get();
                    }

                    @Override public WeComChatDataStore.PublishResult publish(
                            WeComChatDataStore.SyncKey key, String nextCursor,
                            List<WeComChatDataStore.DecryptedMessage> messages) {
                        cursor.set(nextCursor);
                        publishedCursors.add(nextCursor);
                        return new WeComChatDataStore.PublishResult(messages.size(), 0);
                    }
                },
                (action, result, userId) -> { });

        WeComChatDataSyncService.SyncResult result = service.sync(context());

        assertEquals(List.of("", "cursor-1"), requestedCursors);
        assertEquals(List.of("cursor-1", "cursor-2"), publishedCursors);
        assertEquals(2, result.pages());
        assertEquals(2, result.stored());
    }

    @Test
    void stopsAtConfiguredPageLimitWithoutCreatingFalseSuccess() throws Exception {
        Config config = config(Map.of("WECOM_CHATDATA_SYNC_MAX_PAGES", "2"));
        AtomicReference<String> cursor = new AtomicReference<>("");
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> page(true,
                        requestedCursor.isBlank() ? "cursor-1" : "cursor-2", "msg"),
                (version, encrypted) -> "secret",
                store(cursor),
                (action, result, userId) -> { });

        WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                () -> service.sync(context()));

        assertEquals("WECOM_CHATDATA_SYNC_INCOMPLETE", exception.code());
        assertEquals(409, exception.httpStatus());
        assertEquals("cursor-2", cursor.get());
    }

    @Test
    void rejectsConcurrentSyncForSameInstallation() throws Exception {
        Config config = config(Map.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> {
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return page(false, "cursor-1", "msg-1");
                },
                (version, encrypted) -> "secret",
                store(new AtomicReference<>("")),
                (action, result, userId) -> { });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.sync(context()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            WeComChatDataException busy = assertThrows(WeComChatDataException.class,
                    () -> service.sync(context()));

            assertEquals("WECOM_CHATDATA_SYNC_BUSY", busy.code());
            assertEquals(429, busy.httpStatus());
            release.countDown();
            assertEquals(1, first.get(2, TimeUnit.SECONDS).pages());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rateLimitsSyncBeforeCallingZoneProgram() throws Exception {
        Config config = config(Map.of("WECOM_VIEWER_SESSION_RATE_LIMIT", "10"));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> {
                    calls.incrementAndGet();
                    return page(false, "cursor-" + calls.get(), "msg-" + calls.get());
                },
                (version, encrypted) -> "secret",
                store(new AtomicReference<>("")),
                (action, result, userId) -> { });

        for (int attempt = 0; attempt < 10; attempt++) service.sync(context());
        WeComChatDataException limited = assertThrows(WeComChatDataException.class,
                () -> service.sync(context()));

        assertEquals("WECOM_CHATDATA_SYNC_BUSY", limited.code());
        assertEquals(429, limited.httpStatus());
        assertEquals(10, calls.get());
    }

    @Test
    void viewerTokenResolvesOnlyItsBoundInstallationContext() throws Exception {
        Config config = config(Map.of(
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_LOGIN_AUTH_CORP_ID", "ww-corp"
        ));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 3);
        WeComAuthorizationStore authorizationStore = WeComAuthorizationStore.forTests(
                tempDir.resolve("installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
        WeComAuthorizationStore.Installation installation = authorizationStore.upsertActive(
                "dk-suite", "ww-corp", "1000001", "permanent-code");
        WeComViewerService viewer = WeComViewerService.forTests(config,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "employee-1"),
                authorizationStore);
        WeComViewerService.LoginExchangeResponse login = viewer.exchangeLoginCode("code",
                new WeComLoginAttemptService.InstallationBinding(installation.installationId(),
                        installation.version(), installation.suiteId(), installation.authCorpId(),
                        installation.agentId()));

        WeComViewerService.ViewerSyncContext context = viewer.viewerSyncContext(login.viewerAuthToken());

        assertEquals("employee-1", context.wecomUserId());
        assertEquals(installation.installationId(), context.installation().installation().installationId());
        assertEquals("permanent-code", context.installation().permanentCode());
    }

    @Test
    void doesNotPublishWhenDecryptionConsumesTheDeadline() throws Exception {
        Config config = config(Map.of("WECOM_CHATDATA_SYNC_TIMEOUT_SECONDS", "1"));
        AtomicBoolean published = new AtomicBoolean();
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> page(false, "cursor-1", "msg-1"),
                (version, encrypted) -> {
                    Thread.sleep(1_100);
                    return "secret";
                },
                new WeComChatDataSyncService.StoreAccess() {
                    @Override public String cursor(WeComChatDataStore.SyncKey key) {
                        return "";
                    }

                    @Override public WeComChatDataStore.PublishResult publish(
                            WeComChatDataStore.SyncKey key, String nextCursor,
                            List<WeComChatDataStore.DecryptedMessage> messages) {
                        published.set(true);
                        return new WeComChatDataStore.PublishResult(messages.size(), 0);
                    }
                },
                (action, result, userId) -> { });

        WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                () -> service.sync(context()));

        assertEquals("WECOM_CHATDATA_TIMEOUT", exception.code());
        assertTrue(!published.get());
    }

    @Test
    void preservesSafeProgramDiagnosticsInFailureAudit() throws Exception {
        Config config = config(Map.of());
        AtomicReference<WeComChatDataException> auditedFailure = new AtomicReference<>();
        WeComChatDataSyncService service = WeComChatDataSyncService.forTests(config,
                (installation, requestedCursor, limit, timeout) -> {
                    throw new WeComChatDataException("WECOM_CHATDATA_PROGRAM_ERROR", 502,
                            "企业微信专区程序调用失败", 48002,
                            "/cgi-bin/chatdata/sync_call_program", 200, "abc123", null);
                },
                (version, encrypted) -> "secret",
                store(new AtomicReference<>("")),
                new WeComChatDataSyncService.AuditSink() {
                    @Override public void record(String action, String result, String userId) { }

                    @Override public void recordFailure(String action, String result, String userId,
                                                        WeComChatDataException failure) {
                        auditedFailure.set(failure);
                    }
                });

        WeComChatDataException thrown = assertThrows(WeComChatDataException.class,
                () -> service.sync(context()));

        assertEquals(48002, thrown.upstreamErrcode());
        assertEquals("/cgi-bin/chatdata/sync_call_program", thrown.upstreamPath());
        assertEquals(200, thrown.upstreamHttpStatus());
        assertEquals("abc123", thrown.upstreamHint());
        assertEquals(thrown, auditedFailure.get());
    }

    private Config config(Map<String, String> extra) throws Exception {
        Path keyFile = tempDir.resolve("private-key.pem");
        if (!Files.exists(keyFile)) Files.writeString(keyFile, "test-key");
        java.util.HashMap<String, String> values = new java.util.HashMap<>(extra);
        values.put("DATA_DIR", tempDir.toString());
        values.put("WECOM_CHATDATA_PROGRAM_ID", "program-1");
        values.put("WECOM_CHATDATA_ABILITY_ID", "ability-1");
        values.put("WECOM_CHATDATA_PRIVATE_KEY_FILE", keyFile.toString());
        values.put("WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit.jsonl").toString());
        return new Config(values);
    }

    private WeComChatDataSyncService.StoreAccess store(AtomicReference<String> cursor) {
        return new WeComChatDataSyncService.StoreAccess() {
            @Override public String cursor(WeComChatDataStore.SyncKey key) {
                return cursor.get();
            }

            @Override public WeComChatDataStore.PublishResult publish(
                    WeComChatDataStore.SyncKey key, String nextCursor,
                    List<WeComChatDataStore.DecryptedMessage> messages) {
                cursor.set(nextCursor);
                return new WeComChatDataStore.PublishResult(messages.size(), 0);
            }
        };
    }

    private static WeComChatDataGateway.ProgramPage page(boolean hasMore, String cursor, String msgid) {
        return new WeComChatDataGateway.ProgramPage(hasMore, cursor, List.of(
                new WeComChatDataGateway.EncryptedMessage(msgid,
                        new WeComChatDataGateway.Party(1, "employee-1"),
                        List.of(new WeComChatDataGateway.Party(2, "external-1")),
                        "", 100, 2, "cipher", 1)));
    }

    private static WeComViewerService.ViewerSyncContext context() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        WeComAuthorizationStore.Installation installation = new WeComAuthorizationStore.Installation(
                "installation-1", "dk-suite", "ww-corp", "1000001", "encrypted",
                WeComAuthorizationStore.AuthStatus.ACTIVE, now, now, now, 1);
        return new WeComViewerService.ViewerSyncContext("employee-1",
                new WeComAuthorizationStore.ResolvedInstallation(installation, "permanent-code"));
    }
}
