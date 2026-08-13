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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComViewerServiceTest {
    private static final ViewerAuditSink NO_OP_AUDIT = new ViewerAuditSink() {
        @Override public void record(String action, String result, String userId,
                                      String contactPointId, String sessionId) { }
        @Override public void recordDiagnostic(String action, String result, String userId,
                                               String contactPointId, String sessionId,
                                               String errorCode, Integer upstreamErrcode,
                                               String upstreamPath, Integer upstreamHttpStatus,
                                               String upstreamHint) { }
    };

    @TempDir
    Path tempDir;

    @Test
    void resolvesOnlyLiveViewerTokenToActor() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000));
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUTH_TTL_SECONDS", "300",
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit.jsonl").toString()
        ));
        WeComViewerService service = WeComViewerService.forTests(config, clock,
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "zhangsan"),
                null, NO_OP_AUDIT);
        String token = service.exchangeLoginCode("code").viewerAuthToken();

        assertEquals("zhangsan", service.requireViewerActor(token));
        clock.advance(Duration.ofSeconds(301));
        assertThrows(SecurityException.class, () -> service.requireViewerActor(token));
    }

    @Test
    void matchesExternalUserIdExactlyAndUsesTheSharedMsgidLimit() throws Exception {
        String longMsgid = "m".repeat(200);
        Path messages = tempDir.resolve("wecom-messages.jsonl");
        Files.writeString(messages, """
                {"msgid":"%s","secret_key":"secret-exact","external_userid":"Ext-1","userid":"employee-1","send_time":1,"msgtype":"2"}
                {"msgid":"other","secret_key":"secret-other","external_userid":"ext-2","userid":"employee-1","send_time":2,"msgtype":"2"}
                """.formatted(longMsgid), StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_DATA_FILE", messages.toString(),
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit.jsonl").toString()
        ));
        WeComViewerService service = WeComViewerService.forTests(config, Clock.systemUTC(),
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "employee-1"),
                null, NO_OP_AUDIT);
        WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("code");

        WeComViewerService.ViewerSessionResponse created = service.createViewerSession(
                "wecom:Ext-1", login.viewerAuthToken(), List.of(longMsgid));
        WeComViewerService.ViewerSessionDetail detail = service.viewerSession(
                created.viewerSessionId(), login.viewerAuthToken());

        assertEquals(1, detail.messages().size());
        assertEquals(longMsgid, detail.messages().get(0).msgid());
    }

    @Test
    void matchesNormalizedWeComPointAgainstCasePreservingExternalUserId() throws Exception {
        Path messages = tempDir.resolve("wecom-messages-case.jsonl");
        Files.writeString(messages, ""
                + "{\"msgid\":\"mixed-case-msg\",\"secret_key\":\"secret\","
                + "\"external_userid\":\"wmiNTkcAAAkoTdBFAxhR0xWapwRoNdGA\","
                + "\"userid\":\"employee-1\",\"send_time\":1,\"msgtype\":\"1\"}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_DATA_FILE", messages.toString(),
                "WECOM_VIEWER_AUTH_TTL_SECONDS", "28800",
                "WECOM_VIEWER_SESSION_TTL_SECONDS", "300",
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("case-audit.jsonl").toString()
        ));
        WeComViewerService service = WeComViewerService.forTests(config, Clock.systemUTC(),
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "employee-1"),
                null, NO_OP_AUDIT);
        WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("code");

        WeComViewerService.ViewerSessionResponse created = service.createViewerSession(
                "wecom:wmintkcaaakotdbfaxhr0xwapwrondga", login.viewerAuthToken(), List.of("mixed-case-msg"));

        assertEquals(28800, login.expiresIn());
        assertEquals(300, created.expiresIn());
        assertEquals(1, service.viewerSession(created.viewerSessionId(), login.viewerAuthToken()).messages().size());
    }

    @Test
    void returnsOnlyRequestedMessagesAndRejectsInvalidBatchMembership() throws Exception {
        Path messages = tempDir.resolve("wecom-messages-batch.jsonl");
        Files.writeString(messages,
                "{\"msgid\":\"m1\",\"secret_key\":\"s1\",\"external_userid\":\"ext-1\",\"userid\":\"employee-1\",\"send_time\":1}\n"
                        + "{\"msgid\":\"m2\",\"secret_key\":\"s2\",\"external_userid\":\"ext-1\",\"userid\":\"employee-1\",\"send_time\":2}\n"
                        + "{\"msgid\":\"other-owner\",\"secret_key\":\"s3\",\"external_userid\":\"ext-1\",\"userid\":\"employee-2\",\"send_time\":3}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_DATA_FILE", messages.toString(),
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("batch-audit.jsonl").toString()));
        WeComViewerService service = WeComViewerService.forTests(config, Clock.systemUTC(),
                () -> "nonce", new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "employee-1"),
                null, NO_OP_AUDIT);
        String token = service.exchangeLoginCode("code").viewerAuthToken();

        var created = service.createViewerSession("wecom:ext-1", token, List.of("m2"));
        assertEquals(List.of("m2"), service.viewerSession(created.viewerSessionId(), token)
                .messages().stream().map(WeComViewerService.ViewerMessage::msgid).toList());

        assertThrows(IllegalArgumentException.class,
                () -> service.createViewerSession("wecom:ext-1", token, List.of("m1", "m1")));
        assertThrows(SecurityException.class,
                () -> service.createViewerSession("wecom:ext-1", token, List.of("other-owner")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createViewerSession("wecom:ext-1", token,
                        java.util.stream.IntStream.range(0, 16).mapToObj(i -> "m" + i).toList()));
    }

    @Test
    void rejectsServiceAppIdentityFromAnotherAuthorizedEnterprise() throws Exception {
        Instant now = Instant.ofEpochSecond(1000);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("corp-mismatch-audit.jsonl").toString()));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 9);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("corp-mismatch-installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(now, ZoneOffset.UTC));
        WeComAuthorizationStore.Installation installation = store.upsertActive(
                "dk-suite", "ww-authorized-corp", "2000001", "permanent-code");
        WeComViewerService.WeComHttpGateway gateway = new WeComViewerService.StaticGateway(
                "corp-ticket", "agent-ticket", "login-user") {
            @Override public WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(
                    String code, WeComAuthorizationStore.ResolvedInstallation ignored) {
                return new WeComAuthorizationGateway.LoginIdentity("ww-other-corp", "login-user");
            }
        };
        WeComViewerService service = WeComViewerService.forTests(config, Clock.fixed(now, ZoneOffset.UTC),
                () -> "nonce", gateway, store, NO_OP_AUDIT);
        WeComLoginAttemptService.InstallationBinding binding = new WeComLoginAttemptService.InstallationBinding(
                installation.installationId(), installation.version(), installation.suiteId(),
                installation.authCorpId(), installation.agentId());

        WeComAuthorizationException error = assertThrows(WeComAuthorizationException.class,
                () -> service.exchangeLoginCode("one-time-code", binding));
        assertEquals("WECOM_LOGIN_CORP_MISMATCH", error.code());
        assertEquals(403, error.httpStatus());
    }

    @Test
    void boundLoginFailsClosedWithoutServiceAppAuthorizationGateway() throws Exception {
        Instant now = Instant.ofEpochSecond(2000);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("missing-login-gateway-audit.jsonl").toString()));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 5);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("missing-login-gateway-installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(now, ZoneOffset.UTC));
        WeComAuthorizationStore.Installation installation = store.upsertActive(
                "dk-suite", "ww-authorized-corp", "2000001", "permanent-code");
        WeComViewerService.JdkWeComHttpGateway gateway = new WeComViewerService.JdkWeComHttpGateway(config);
        WeComViewerService service = WeComViewerService.forTests(config, Clock.fixed(now, ZoneOffset.UTC),
                () -> "nonce", gateway, store, NO_OP_AUDIT);
        WeComLoginAttemptService.InstallationBinding binding = new WeComLoginAttemptService.InstallationBinding(
                installation.installationId(), installation.version(), installation.suiteId(),
                installation.authCorpId(), installation.agentId());

        WeComAuthorizationException error = assertThrows(WeComAuthorizationException.class,
                () -> service.exchangeLoginCode("one-time-code", binding));
        assertEquals("WECOM_LOGIN_SUITE_NOT_CONFIGURED", error.code());
    }

    @Test
    void recordsBoundLoginExchangeFailureWhenUpstreamRejectsCode() throws Exception {
        Instant now = Instant.ofEpochSecond(3000);
        Path auditFile = tempDir.resolve("bound-login-failure-audit.jsonl");
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString()));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        WeComAuthorizationStore store = WeComAuthorizationStore.forTests(
                tempDir.resolve("bound-login-failure-installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(now, ZoneOffset.UTC));
        WeComAuthorizationStore.Installation installation = store.upsertActive(
                "dk-suite", "ww-authorized-corp", "2000001", "permanent-code");
        WeComViewerService.StaticGateway gateway = new WeComViewerService.StaticGateway(
                "corp-ticket", "agent-ticket", "login-user") {
            @Override public WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(
                    String code, WeComAuthorizationStore.ResolvedInstallation ignored)
                    throws WeComAuthorizationException {
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信上游服务暂时不可用", 40085,
                        "/cgi-bin/service/get_suite_token", null);
            }
        };
        RecordingViewerAudit audit = new RecordingViewerAudit();
        WeComViewerService service = WeComViewerService.forTests(config, Clock.fixed(now, ZoneOffset.UTC),
                () -> "nonce", gateway, store, audit);
        WeComLoginAttemptService.InstallationBinding binding = new WeComLoginAttemptService.InstallationBinding(
                installation.installationId(), installation.version(), installation.suiteId(),
                installation.authCorpId(), installation.agentId());

        assertThrows(WeComAuthorizationException.class,
                () -> service.exchangeLoginCode("one-time-code", binding));

        assertEquals("wecom.viewer.login_exchange", audit.action);
        assertEquals("failed", audit.result);
        assertEquals("WECOM_UPSTREAM_UNAVAILABLE", audit.errorCode);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }

    private static final class RecordingViewerAudit implements ViewerAuditSink {
        private String action;
        private String result;
        private String errorCode;

        @Override public void record(String action, String result, String userId,
                                     String contactPointId, String sessionId) {
            this.action = action;
            this.result = result;
        }

        @Override public void recordDiagnostic(String action, String result, String userId,
                                               String contactPointId, String sessionId,
                                               String errorCode, Integer upstreamErrcode,
                                               String upstreamPath, Integer upstreamHttpStatus,
                                               String upstreamHint) {
            record(action, result, userId, contactPointId, sessionId);
            this.errorCode = errorCode;
        }
    }
}
