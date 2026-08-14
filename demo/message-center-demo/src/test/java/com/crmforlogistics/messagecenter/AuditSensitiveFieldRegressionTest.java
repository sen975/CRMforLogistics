package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSensitiveFieldRegressionTest {
    private static final Instant NOW = Instant.parse("2026-08-13T01:00:00Z");
    private static final List<String> FORBIDDEN = List.of(
            "access-token-value", "suite-secret-value", "corp-secret-value",
            "permanent-code-value", "suite-ticket-value", "auth-code-value",
            "secret-key-value", "viewer-auth-token-value", "request-body-value",
            "response-body-value", "message-body-value", "modal-url-value");

    @TempDir
    Path tempDir;

    @Test
    void noAuditSurfaceContainsCredentialOrMessageFixtures() throws Exception {
        String allSurfaces = produceAndCollectAllAuditSurfaces();

        FORBIDDEN.forEach(value -> assertFalse(allSurfaces.contains(value), value));
    }

    private String produceAndCollectAllAuditSurfaces() throws Exception {
        Path viewerFile = tempDir.resolve("wecom-viewer-audit.jsonl");
        Path authorizationFile = tempDir.resolve("wecom-authorization-audit.jsonl");
        Path messageFile = tempDir.resolve("wecom-messages.jsonl");
        Files.writeString(messageFile, ""
                + "{\"msgid\":\"message-body-value\",\"secret_key\":\"secret-key-value\","
                + "\"external_userid\":\"external-1\",\"userid\":\"viewer-user\","
                + "\"send_time\":1,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"message-body-value\"}}\n"
                + "{\"msgid\":\"modal-url-value\",\"secret_key\":\"secret-key-value\","
                + "\"external_userid\":\"external-1\",\"userid\":\"viewer-user\","
                + "\"send_time\":2,\"msgtype\":\"text\"}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.ofEntries(
                Map.entry("DATA_DIR", tempDir.toString()),
                Map.entry("WECOM_DATA_FILE", messageFile.toString()),
                Map.entry("WECOM_VIEWER_AUDIT_FILE", viewerFile.toString()),
                Map.entry("WECOM_AUTHORIZATION_AUDIT_FILE", authorizationFile.toString()),
                Map.entry("WECOM_SUITE_SECRET", "suite-secret-value"),
                Map.entry("WECOM_SECRET", "corp-secret-value"),
                Map.entry("AUDIT_RETENTION_DAYS", "7"),
                Map.entry("AUDIT_FILE_MAX_BYTES", "4096"),
                Map.entry("AUDIT_STREAM_MAX_BYTES", "131072"),
                Map.entry("AUDIT_MIN_FREE_DISK_BYTES", "4096"),
                Map.entry("AUDIT_WARNING_INTERVAL_SECONDS", "3600")));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8);
        String sensitiveFailureText = String.join("|", FORBIDDEN);
        WeComAuthorizationException sensitiveFailure = new WeComAuthorizationException(
                "WECOM_UPSTREAM_UNAVAILABLE", 503, sensitiveFailureText, 40085,
                "/cgi-bin/service/get_suite_token", 200, "safe_hint",
                new IllegalStateException("request-body-value|response-body-value"));

        PrintStream originalStderr = System.err;
        try {
            System.setErr(stderr);
            try (AuditRuntime runtime = AuditRuntime.open(
                    config, clock, ignored -> Long.MAX_VALUE, stderr::println)) {
                produceViewerSurfaces(config, clock, runtime.viewerTrail(), sensitiveFailure);
                produceAuthorizationSurfaces(runtime.authorizationTrail(), sensitiveFailure);
                runtime.enableAuthorizationRetentionCleanup();
                forceViewerArchive(runtime.viewerTrail());
            }
            produceAuthorizationServiceFailureSurface(config, clock);
        } finally {
            System.setErr(originalStderr);
        }

        produceWarningSurface(clock, stderr, sensitiveFailureText);
        int statusExit = App.run(new String[]{"audit-status"}, config, stdout, stderr);
        assertEquals(0, statusExit, "healthy audit surfaces must produce exit code 0");

        String viewerCurrent = Files.readString(viewerFile, StandardCharsets.UTF_8);
        String authorizationCurrent = Files.readString(authorizationFile, StandardCharsets.UTF_8);
        String archives = readGzipArchives(tempDir);
        String stdoutSurface = stdoutBytes.toString(StandardCharsets.UTF_8);
        String stderrSurface = stderrBytes.toString(StandardCharsets.UTF_8);
        String fileSurfaces = viewerCurrent + authorizationCurrent + archives;

        assertTrue(fileSurfaces.contains("\"action\":\"wecom.viewer.login_exchange\""));
        assertTrue(authorizationCurrent.contains("\"result\":\"accepted\""));
        assertTrue(authorizationCurrent.contains("\"result\":\"pending\""));
        assertTrue(authorizationCurrent.contains("\"result\":\"failed\""));
        assertFalse(archives.isBlank(), "viewer size rotation must produce a readable gzip archive");
        assertTrue(stderrSurface.contains("\"event\":\"wecom.viewer.login_exchange\""));
        assertTrue(stderrSurface.contains("\"event\":\"wecom.audit.write\""));
        assertTrue(stderrSurface.contains("\"stream\":\"wecom-authorization\""));
        JsonObject status = JsonParser.parseString(stdoutSurface).getAsJsonObject();
        assertEquals("healthy", status.get("status").getAsString());
        assertEquals(2, status.getAsJsonArray("streams").size());

        return fileSurfaces + stdoutSurface + stderrSurface;
    }

    private void produceViewerSurfaces(Config config, Clock clock, ViewerAuditSink auditTrail,
                                       WeComAuthorizationException sensitiveFailure) throws Exception {
        WeComViewerService.WeComHttpGateway failingGateway = new WeComViewerService.WeComHttpGateway() {
            @Override
            public WeComViewerService.TicketResponse fetchCorpJsapiTicket() {
                return new WeComViewerService.TicketResponse(0, "ok", "corp-ticket", 7200);
            }

            @Override
            public WeComViewerService.TicketResponse fetchAgentJsapiTicket() {
                return new WeComViewerService.TicketResponse(0, "ok", "agent-ticket", 7200);
            }

            @Override
            public String exchangeLoginCode(String code) throws Exception {
                throw sensitiveFailure;
            }
        };
        WeComViewerService failingService = WeComViewerService.forTests(
                config, clock, () -> "nonce", failingGateway, null, auditTrail);
        assertThrows(WeComAuthorizationException.class,
                () -> failingService.exchangeLoginCode("request-body-value"));
        failingService.recordAccessDenied("wecom:external-1", "viewer-auth-token-value");

        WeComViewerService service = WeComViewerService.forTests(
                config, clock, () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "viewer-user"),
                null, auditTrail);
        WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("safe-code");
        WeComViewerService.ViewerSessionResponse session = service.createViewerSession(
                "wecom:external-1", login.viewerAuthToken(),
                List.of("message-body-value", "modal-url-value"));
        WeComViewerService.ViewerSessionDetail detail = service.viewerSession(
                session.viewerSessionId(), login.viewerAuthToken());
        assertEquals(2, detail.messages().size());
        assertEquals("secret-key-value", detail.messages().get(0).secretKey());
    }

    private static void produceAuthorizationSurfaces(
            WeComAuthorizationAuditTrail auditTrail,
            WeComAuthorizationException sensitiveFailure) throws Exception {
        WeComCallbackCodec.DecodedCallback callback = new WeComCallbackCodec.DecodedCallback(
                "suite-id", "create_auth", "corp-id", "auth-code-value",
                "suite-ticket-value", "response-body-value", NOW);
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt =
                auditTrail.begin(callback).attempt();
        auditTrail.pending(attempt, "corp-id", WeComAuthorizationStore.AuthStatus.ACTIVE, 0L);
        auditTrail.failed(attempt, "corp-id", sensitiveFailure);
    }

    private void produceAuthorizationServiceFailureSurface(Config config, Clock clock)
            throws Exception {
        byte[] key = new byte[32];
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("authorization-service-installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)), clock);
        WeComAuthorizationAuditTrail auditTrail = WeComAuthorizationAuditTrail.forTests(
                clock, line -> {
                    String result = JsonParser.parseString(line).getAsJsonObject()
                            .get("result").getAsString();
                    if ("succeeded".equals(result)) {
                        throw new AuditStorageException("access-token-value",
                                "wecom-authorization",
                                new IllegalStateException(String.join("|", FORBIDDEN)));
                    }
                });
        WeComAuthorizationClient gateway = new WeComAuthorizationClient() {
            @Override
            public void acceptSuiteTicket(String suiteId, String ticket, Instant receivedAt) {
            }

            @Override
            public WeComAuthorizationGateway.PermanentCodeResponse getPermanentCode(String authCode) {
                throw new AssertionError("unexpected permanent code request");
            }

            @Override
            public WeComAuthorizationGateway.AuthorizationInfo getAuthInfo(
                    String authCorpId, String permanentCode) {
                throw new AssertionError("unexpected authorization info request");
            }
        };
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config, store, gateway, () -> { }, auditTrail)) {
            assertFalse(service.handle(new WeComCallbackCodec.DecodedCallback(
                    "suite-id", "noop", "corp-id", "auth-code-value",
                    "suite-ticket-value", "request-body-value", NOW)).success());
        }
    }

    private static void forceViewerArchive(ViewerAuditSink auditTrail) {
        for (int index = 0; index < 16; index++) {
            auditTrail.recordDiagnostic("wecom.viewer.sync", "failed",
                    "u".repeat(128), "wecom:" + "c".repeat(200), "s".repeat(64),
                    "WECOM_UPSTREAM_UNAVAILABLE", 40085,
                    "/cgi-bin/program/get_session", 200, "safe_hint_" + index);
        }
    }

    private static void produceWarningSurface(Clock clock, PrintStream stderr,
                                              String sensitiveFailureText) {
        AuditWarningReporter warnings = new AuditWarningReporter(
                clock, Duration.ofHours(1), stderr::println);
        WeComViewerAuditTrail warningTrail = new WeComViewerAuditTrail(
                clock,
                (bytes, durability) -> {
                    throw new AuditStorageException("AUDIT_DISK_SPACE_LOW", "wecom-viewer",
                            new IllegalStateException(sensitiveFailureText));
                },
                warnings);
        warningTrail.record("wecom.viewer.component_error", "failed", "viewer-user", "", "");
    }

    private static String readGzipArchives(Path root) throws Exception {
        StringBuilder content = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path archive : paths.filter(path -> path.getFileName().toString()
                    .endsWith(".jsonl.gz")).sorted().toList()) {
                try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(archive))) {
                    content.append(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return content.toString();
    }
}
