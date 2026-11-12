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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComLoginApplicationServiceTest {
    @Test
    void bindingStatusProjectsNicknameAndEnterpriseNameWithoutUsingProviderIdsAsLabels() {
        AppConfig config = mock(AppConfig.class);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComAuthorizationGateway gateway = mock(WeComAuthorizationGateway.class);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        WeComUserBindingService bindings = mock(WeComUserBindingService.class);
        AuthSessionService sessions = mock(AuthSessionService.class);
        WeComPartyProfileService profiles = mock(WeComPartyProfileService.class);
        WeComLoginApplicationService service = new WeComLoginApplicationService(
                config, mock(WeComLoginAttemptService.class), gateway, accessTokens,
                bindings, sessions, installations, profiles);

        UUID userId = UUID.randomUUID();
        var installation = new com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity();
        installation.setCorpName("示例企业");
        var resolved = new com.crmforlogistics.messagecenter.service.wecom.WeComLoginAttemptService.InstallationBinding(
                UUID.randomUUID().toString(), 1, "suite", "corp-id", "agent");
        var bound = new WeComUserBindingService.BoundIdentity(
                userId, "suite", "corp-id", "employee-id", "BOUND_EXISTING", resolved, "user");
        when(bindings.requireByUserId(userId)).thenReturn(bound);
        when(installations.find("suite", "corp-id")).thenReturn(installation);
        when(profiles.displayNameFor(resolved.installationId(), "EMPLOYEE", "employee-id")).thenReturn("张三");

        var response = service.bindingStatus(userId);

        assertThat(response.wecomDisplayName()).isEqualTo("张三");
        assertThat(response.corpName()).isEqualTo("示例企业");
        assertThat(response.authCorpId()).isEqualTo("corp-id");
        assertThat(response.wecomUserId()).isEqualTo("employee-id");
    }

    @Test
    void bindingStatusRefreshesCurrentEmployeeProfileForExistingBindings() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomApiTimeoutSeconds()).thenReturn(10);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComAuthorizationGateway gateway = mock(WeComAuthorizationGateway.class);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        WeComUserBindingService bindings = mock(WeComUserBindingService.class);
        AuthSessionService sessions = mock(AuthSessionService.class);
        WeComPartyProfileService profiles = mock(WeComPartyProfileService.class);
        WeComLoginApplicationService service = new WeComLoginApplicationService(
                config, mock(WeComLoginAttemptService.class), gateway, accessTokens,
                bindings, sessions, installations, profiles);

        UUID userId = UUID.randomUUID();
        var resolved = new WeComLoginAttemptService.InstallationBinding(
                UUID.randomUUID().toString(), 1, "suite", "corp-id", "agent");
        var bound = new WeComUserBindingService.BoundIdentity(
                userId, "suite", "corp-id", "employee-id", "BOUND_EXISTING", resolved, "user");
        when(bindings.requireByUserId(userId)).thenReturn(bound);
        when(installations.find("suite", "corp-id")).thenReturn(new com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity());
        when(installations.resolveInstallation("suite", "corp-id")).thenReturn(
                new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                        resolved.installationId(), resolved.suiteId(), resolved.authCorpId(),
                        resolved.agentId(), "permanent-code", resolved.version()));
        when(profiles.displayNameFor(resolved.installationId(), "EMPLOYEE", "employee-id")).thenReturn("张三");

        service.bindingStatus(userId);

        verify(profiles).syncEmployee(any(), eq("employee-id"), any());
    }

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
        WeComPartyProfileService profiles = mock(WeComPartyProfileService.class);
        ViewerAuditSink audit = mock(ViewerAuditSink.class);
        var attempt = WeComLoginAttemptService.forTests(config, installations,
                Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC), () -> "state-123456789012", audit);
        when(installations.resolveInstallation(any(), any())).thenReturn(
                new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation("i", "suite", "corp", "agent", "pc", 1));
        WeComLoginApplicationService service = new WeComLoginApplicationService(
                config, attempt, gateway, accessTokens, bindings, sessions, installations, profiles);
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

    @Test
    void exchangeFallsBackToDirectoryProfileWhenLoginHasNoUserTicket() {
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
        WeComPartyProfileService profiles = mock(WeComPartyProfileService.class);
        ViewerAuditSink audit = mock(ViewerAuditSink.class);
        var attempt = WeComLoginAttemptService.forTests(config, installations,
                Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC), () -> "state-fallback-123", audit);
        var resolvedInstallation = new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                "installation-id", "suite", "corp", "agent", "pc", 1);
        when(installations.resolveInstallation(any(), any())).thenReturn(resolvedInstallation);
        when(accessTokens.accessToken(any(), any())).thenReturn("access-token");
        when(gateway.getLoginIdentity(eq("corp"), eq("access-token"), eq("code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity("corp", "employee-1"));
        when(bindings.resolveOrCreate(any())).thenReturn(new WeComUserBindingService.BoundIdentity(
                UUID.randomUUID(), "suite", "corp", "employee-1", "AUTO_CREATED", null, "wecom_user"));
        when(sessions.issue(any(), any(), any())).thenReturn("crm-token");

        var created = attempt.createAttempt();
        service(config, attempt, gateway, accessTokens, bindings, sessions, installations, profiles)
                .exchange(new WeComLoginApplicationService.ExchangeRequest("code", created.state()),
                        new WeComLoginApplicationService.RequestMetadata("127.0.0.1", "test"));

        verify(profiles).syncEmployee(eq(resolvedInstallation), eq("employee-1"), any());
        verify(gateway, never()).getUserDetail(any(), any(), any());
    }

    private static WeComLoginApplicationService service(
            AppConfig config, WeComLoginAttemptService attempt, WeComAuthorizationGateway gateway,
            WeComAccessTokenService accessTokens, WeComUserBindingService bindings,
            AuthSessionService sessions, WeComInstallationService installations,
            WeComPartyProfileService profiles) {
        return new WeComLoginApplicationService(config, attempt, gateway, accessTokens,
                bindings, sessions, installations, profiles);
    }
}
