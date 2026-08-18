package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerHttpGateway;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComViewerServiceTest {
    @Test
    void concurrentViewerBootstrapNeverExceedsTheGlobalTokenBound() throws Exception {
        AppConfig config = viewerConfig();
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp"))
                .thenReturn(new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                        "installation", "suite", "corp", "agent", "ignored", 1L));
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce",
                mock(WeComViewerHttpGateway.class), installations, noOpAudit(),
                mock(WeComChatDataMessageMapper.class), mock(WeComCredentialProtector.class), openGate());
        WeComLoginAttemptService.InstallationBinding binding =
                new WeComLoginAttemptService.InstallationBinding(
                        "installation", 1L, "suite", "corp", "agent");
        for (int index = 0; index < 500; index++) {
            service.issueViewerAuth("prefill-user-" + index, binding);
        }
        int calls = 128;
        CountDownLatch ready = new CountDownLatch(calls);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(calls);
        try {
            for (int index = 0; index < calls; index++) {
                int user = index;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.issueViewerAuth("wecom-user-" + user, binding);
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(service.activeViewerAuthTokenCount()).isLessThanOrEqualTo(512);
    }

    @Test
    void issueViewerAuthReusesBoundIdentityWithoutAnotherCodeExchange() {
        AppConfig config = viewerConfig();
        WeComViewerHttpGateway gateway = mock(WeComViewerHttpGateway.class);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp"))
                .thenReturn(new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                        "installation", "suite", "corp", "agent", "ignored", 1L));
        WeComLoginAttemptService.InstallationBinding binding =
                new WeComLoginAttemptService.InstallationBinding("installation", 1L, "suite", "corp", "agent");
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce", gateway, installations,
                noOpAudit(), mock(WeComChatDataMessageMapper.class), mock(WeComCredentialProtector.class),
                openGate());

        WeComViewerService.LoginExchangeResponse response = service.issueViewerAuth("wecom-user", binding);

        assertThat(response.wecomUserId()).isEqualTo("wecom-user");
        assertThat(service.requireViewerActor(response.viewerAuthToken())).isEqualTo("wecom-user");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).exchangeLoginCode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unreadSessionReferencesAreLeasedAndReleasedOnRead() {
        AppConfig config = viewerConfig();
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setMsgid("message-1");
        entity.setExternalUserid("contact-1");
        entity.setUserid("wecom-user");
        entity.setSendTime(1L);
        entity.setSecretKey("encrypted");
        when(mapper.findByExternalUserid("contact-1")).thenReturn(List.of(entity));
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        when(protector.revealSecretKey("encrypted")).thenReturn("plain");
        WeComViewerHttpGateway gateway = mock(WeComViewerHttpGateway.class);
        when(gateway.exchangeLoginCode("code")).thenReturn("wecom-user");
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce", gateway,
                mock(WeComInstallationService.class), noOpAudit(), mapper, protector, openGate());
        String token = service.exchangeLoginCode("code").viewerAuthToken();

        String sessionId = service.createViewerSession("wecom:contact-1", token, List.of("message-1"))
                .viewerSessionId();
        assertThat(service.leasedMessageIds()).containsExactly("message-1");
        service.viewerSession(sessionId, token);
        assertThat(service.leasedMessageIds()).isEmpty();
    }

    @Test
    void replacingSessionForSameTokenReleasesPreviousReferences() {
        ViewerFixture fixture = viewerFixture(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 10);
        String token = fixture.service.exchangeLoginCode("code").viewerAuthToken();

        fixture.service.createViewerSession("wecom:contact-1", token, List.of("message-1"));
        fixture.service.createViewerSession("wecom:contact-1", token, List.of("message-2"));

        assertThat(fixture.service.leasedMessageIds()).containsExactly("message-2");
    }

    @Test
    void expiredSessionReleasesReferences() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        ViewerFixture fixture = viewerFixture(clock, 10);
        String token = fixture.service.exchangeLoginCode("code").viewerAuthToken();
        fixture.service.createViewerSession("wecom:contact-1", token, List.of("message-1"));

        clock.advanceSeconds(61);

        assertThat(fixture.service.leasedMessageIds()).isEmpty();
    }

    @Test
    void capacityEvictionReleasesOldestReferences() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        WeComViewerReferenceLeaseRegistry registry =
                new WeComViewerReferenceLeaseRegistry(clock, 2, 15);

        registry.acquire("session-1", List.of("message-1"), 60);
        registry.acquire("session-2", List.of("message-2"), 60);
        registry.acquire("session-3", List.of("message-3"), 60);

        assertThat(registry.leasedMessageIds()).containsExactlyInAnyOrder("message-2", "message-3");
    }

    @Test
    void viewerTokenIsExpiredAndSessionIsSingleUse() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        ViewerFixture fixture = viewerFixture(clock, 10);
        String token = fixture.service.exchangeLoginCode("code").viewerAuthToken();
        String sessionId = fixture.service.createViewerSession(
                "wecom:contact-1", token, List.of("message-1")).viewerSessionId();

        fixture.service.viewerSession(sessionId, token);
        assertThatThrownBy(() -> fixture.service.viewerSession(sessionId, token))
                .isInstanceOf(IllegalArgumentException.class);

        clock.advanceSeconds(61);
        assertThatThrownBy(() -> fixture.service.requireViewerActor(token))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void sessionCreationRateLimitIsEnforced() {
        ViewerFixture fixture = viewerFixture(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        String token = fixture.service.exchangeLoginCode("code").viewerAuthToken();
        fixture.service.createViewerSession("wecom:contact-1", token, List.of("message-1"));

        assertThatThrownBy(() -> fixture.service.createViewerSession(
                "wecom:contact-1", token, List.of("message-2")))
                .isInstanceOf(WeComViewerService.RateLimitException.class);
    }

    @Test
    void viewerAuditFailureDoesNotBlockBoundTokenIssuance() {
        AppConfig config = viewerConfig();
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp"))
                .thenReturn(new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                        "installation", "suite", "corp", "agent", "ignored", 1L));
        ViewerAuditSink failingAudit = mock(ViewerAuditSink.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(failingAudit).record(any(), any(), any(), any(), any());
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce", mock(WeComViewerHttpGateway.class),
                installations, failingAudit, mock(WeComChatDataMessageMapper.class),
                mock(WeComCredentialProtector.class), openGate());

        WeComViewerService.LoginExchangeResponse response = service.issueViewerAuth("wecom-user",
                new WeComLoginAttemptService.InstallationBinding(
                        "installation", 1L, "suite", "corp", "agent"));

        assertThat(response.viewerAuthToken()).isNotBlank();
    }

    @Test
    void localSessionIsNotConsumedByAnotherValidToken() {
        AppConfig config = mock(AppConfig.class);
        when(config.localDevMode()).thenReturn(true);
        when(config.localWeComDataSource()).thenReturn("fixture");
        when(config.wecomViewerAuthTtlSeconds()).thenReturn(60);
        when(config.wecomViewerSessionTtlSeconds()).thenReturn(60);
        when(config.wecomViewerMaxMessages()).thenReturn(15);
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        LocalWeComDevelopmentService local = new LocalWeComDevelopmentService(
                config, new ObjectMapper(), mapper, mock(WeComCredentialProtector.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        String firstToken = local.issueViewerAuth("local-wecom-user", null).viewerAuthToken();
        String secondToken = local.issueViewerAuth("local-wecom-user", null).viewerAuthToken();
        String sessionId = local.createSession("wecom:local-contact-001", firstToken,
                List.of("local-msg-001")).viewerSessionId();

        assertThatThrownBy(() -> local.readSession(sessionId, secondToken))
                .isInstanceOf(SecurityException.class);
        assertThat(local.readSession(sessionId, firstToken).viewerSessionId()).isEqualTo(sessionId);
    }

    @Test
    void loginExchangeFailureDoesNotWriteToOrdinaryErrorLog() {
        AppConfig config = viewerConfig();
        WeComViewerHttpGateway gateway = mock(WeComViewerHttpGateway.class);
        when(gateway.exchangeLoginCode("authorization-code")).thenThrow(
                new WeComException("WECOM_LOGIN_EXCHANGE_FAILED", 502, "登录换码失败"));
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce", gateway,
                mock(WeComInstallationService.class), noOpAudit(), mock(WeComChatDataMessageMapper.class),
                mock(WeComCredentialProtector.class), openGate());
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured));
            assertThatThrownBy(() -> service.exchangeLoginCode("authorization-code"))
                    .isInstanceOf(WeComException.class);
        } finally {
            System.setErr(original);
        }

        assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8)).isEmpty();
    }

    private static ViewerFixture viewerFixture(Clock clock, int rateLimit) {
        AppConfig config = viewerConfig();
        when(config.wecomViewerSessionRateLimit()).thenReturn(rateLimit);
        WeComViewerHttpGateway gateway = mock(WeComViewerHttpGateway.class);
        when(gateway.exchangeLoginCode("code")).thenReturn("wecom-user");
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        when(mapper.findByExternalUserid("contact-1")).thenReturn(List.of(
                message("message-1"), message("message-2"), message("message-3")));
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        when(protector.revealSecretKey(any())).thenReturn("plain");
        WeComViewerService service = WeComViewerService.forTests(config, clock, () -> "nonce", gateway,
                mock(WeComInstallationService.class), noOpAudit(), mapper, protector, openGate());
        return new ViewerFixture(service);
    }

    private static WeComChatDataMessageEntity message(String messageId) {
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setMsgid(messageId);
        entity.setExternalUserid("contact-1");
        entity.setUserid("wecom-user");
        entity.setSendTime(1L);
        entity.setSecretKey("encrypted-" + messageId);
        return entity;
    }

    private static AppConfig viewerConfig() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomViewerAuthTtlSeconds()).thenReturn(60);
        when(config.wecomViewerSessionTtlSeconds()).thenReturn(60);
        when(config.wecomViewerMaxMessages()).thenReturn(15);
        when(config.wecomViewerSessionRateLimit()).thenReturn(10);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("corp");
        return config;
    }

    private static WeComStartupGate openGate() {
        WeComStartupGate gate = new WeComStartupGate();
        gate.open();
        return gate;
    }

    private static ViewerAuditSink noOpAudit() {
        return new ViewerAuditSink() {
            @Override public void record(String action, String result, String userId,
                                         String contactPointId, String sessionId) { }
            @Override public void recordDiagnostic(String action, String result, String userId,
                                                   String contactPointId, String sessionId,
                                                   String errorCode, Integer upstreamErrcode,
                                                   String upstreamPath, Integer upstreamHttpStatus,
                                                   String upstreamHint) { }
        };
    }

    private record ViewerFixture(WeComViewerService service) {}

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
