package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComLoginApplicationServiceTest {
    @Test
    void exchangeConsumesStateAndIssuesCrmSession() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("corp");
        when(config.wecomLoginRedirectUri()).thenReturn("http://localhost/");
        when(config.wecomLoginAttemptTtlSeconds()).thenReturn(300);
        when(config.wecomLoginMaxPending()).thenReturn(10);
        when(config.wecomLoginAttemptRateLimitPerMinute()).thenReturn(20);
        when(config.wecomApiTimeoutSeconds()).thenReturn(10);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComAuthorizationGateway gateway = mock(WeComAuthorizationGateway.class);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        WeComUserBindingService bindings = mock(WeComUserBindingService.class);
        AuthSessionService sessions = mock(AuthSessionService.class);
        ViewerAuditSink audit = mock(ViewerAuditSink.class);
        var attempt = WeComLoginAttemptService.forTests(config, installations,
                Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC), () -> "state-123456789012", audit);
        when(installations.resolveInstallation(any(), any())).thenReturn(
                new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation("i", "suite", "corp", "agent", "pc", 1));
        WeComLoginApplicationService service = new WeComLoginApplicationService(
                config, attempt, gateway, accessTokens, bindings, sessions, installations);
        when(bindings.resolveOrCreate(any())).thenReturn(new WeComUserBindingService.BoundIdentity(
                java.util.UUID.randomUUID(), "suite", "corp", "user", "AUTO_CREATED", null, "wecom_user"));
        when(sessions.issue(any(), any(), any())).thenReturn("crm-token");
        var created = attempt.createAttempt();
        when(accessTokens.accessToken(any(), any())).thenReturn("access-token");
        when(gateway.getLoginIdentity(eq("corp"), eq("access-token"), eq("code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity("corp", "user"));

        var response = service.exchange(new WeComLoginApplicationService.ExchangeRequest("code", created.state()),
                new WeComLoginApplicationService.RequestMetadata("127.0.0.1", "test"));
        var replay = service.exchange(new WeComLoginApplicationService.ExchangeRequest("code", created.state()),
                new WeComLoginApplicationService.RequestMetadata("127.0.0.1", "test"));
        assertThat(response.token()).isEqualTo("crm-token");
        assertThat(replay).isEqualTo(response);
        verify(sessions).issue(any(), eq("127.0.0.1"), eq("test"));
        verify(gateway).getLoginIdentity(eq("corp"), eq("access-token"), eq("code"), any());
        verify(bindings).resolveOrCreate(any());
    }
}
