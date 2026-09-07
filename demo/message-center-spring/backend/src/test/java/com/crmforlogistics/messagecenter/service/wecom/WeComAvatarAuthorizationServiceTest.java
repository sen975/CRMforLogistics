package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeComAvatarAuthorizationServiceTest {
    @Mock AppConfig config;
    @Mock WeComUserBindingService bindings;
    @Mock WeComInstallationService installations;
    @Mock WeComAuthorizationGateway gateway;
    @Mock WeComAccessTokenService accessTokens;
    @Mock WeComPartyProfileService profiles;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-04T06:00:00Z"));
    private UUID userId;
    private ResolvedInstallation installation;
    private WeComAvatarAuthorizationService service;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        when(config.wecomLoginRedirectUri()).thenReturn("https://www.blindac.com/login?from=wecom#fragment");
        when(config.wecomLoginMaxPending()).thenReturn(2);
        when(config.wecomLoginAttemptRateLimitPerMinute()).thenReturn(20);
        lenient().when(config.wecomApiTimeoutSeconds()).thenReturn(10);
        when(bindings.requireByUserId(userId)).thenReturn(new WeComUserBindingService.BoundIdentity(
                userId, "suite", "corp-id", "employee-1", "BOUND_EXISTING", null, "agent"));
        installation = new ResolvedInstallation(
                "11111111-1111-1111-1111-111111111111", "suite", "corp-id", "1000014", "pc", 7);
        when(installations.resolveInstallation("suite", "corp-id")).thenReturn(installation);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var nonces = new java.util.ArrayDeque<>(java.util.List.of(
                "authorization-aaaaaaaaaaaaaaaaaaaaaaaa",
                "state-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        service = WeComAvatarAuthorizationService.forTests(config, bindings, installations,
                gateway, accessTokens, profiles, clock, nonces::removeFirst);
    }

    @Test
    void createsPrivateInfoOauthUrlFromConfiguredOrigin() {
        var created = service.create(userId, "127.0.0.1");

        assertThat(created.authorizationId()).isEqualTo("authorization-aaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(created.status()).isEqualTo(WeComAvatarAuthorizationService.Status.PENDING);
        assertThat(created.expiresIn()).isEqualTo(300);
        URI uri = URI.create(created.authorizationUrl());
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("open.weixin.qq.com");
        assertThat(uri.getPath()).isEqualTo("/connect/oauth2/authorize");
        assertThat(uri.getFragment()).isEqualTo("wechat_redirect");
        assertThat(query(uri)).containsEntry("appid", "corp-id")
                .containsEntry("response_type", "code")
                .containsEntry("scope", "snsapi_privateinfo")
                .containsEntry("agentid", "1000014")
                .containsEntry("state", "state-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .containsEntry("redirect_uri",
                        "https://www.blindac.com/api/public/wecom-avatar/oauth/callback");
    }

    @Test
    void statusIsOwnedByTheCreatingCrmUserAndExpiresAfterFiveMinutes() {
        var created = service.create(userId, "127.0.0.1");

        assertThat(service.status(userId, created.authorizationId()).status())
                .isEqualTo(WeComAvatarAuthorizationService.Status.PENDING);
        assertThatThrownBy(() -> service.status(UUID.randomUUID(), created.authorizationId()))
                .isInstanceOf(WeComException.class)
                .extracting(error -> ((WeComException) error).code())
                .isEqualTo("WECOM_AVATAR_AUTH_STATE_INVALID");

        now.set(now.get().plusSeconds(301));
        assertThat(service.status(userId, created.authorizationId()).status())
                .isEqualTo(WeComAvatarAuthorizationService.Status.EXPIRED);
    }

    @Test
    void completesPrivateInfoAuthorizationAndPersistsOnlyTheBoundEmployee() throws Exception {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        JsonNode detail = json.readTree("{\"userid\":\"employee-1\",\"avatar\":\"https://avatar.example/1.png\"}");
        when(accessTokens.accessToken(eq(installation), any())).thenReturn("corp-access-token");
        when(gateway.getLoginIdentity(eq("corp-id"), eq("corp-access-token"), eq("oauth-code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity(
                        "corp-id", "employee-1", "private-user-ticket"));
        when(gateway.getUserDetail(eq("corp-access-token"), eq("private-user-ticket"), any()))
                .thenReturn(detail);
        when(profiles.syncAuthorizedEmployee(installation, "employee-1", detail))
                .thenReturn(new WeComPartyProfileService.ProfileResult(
                        UUID.randomUUID(), "EMPLOYEE", "employee-1", "员工",
                        "https://avatar.example/1.png", "READY", ""));

        var result = service.complete("oauth-code", state);

        assertThat(result.status()).isEqualTo(WeComAvatarAuthorizationService.Status.SUCCEEDED);
        assertThat(result.errorCode()).isNull();
        assertThat(service.status(userId, created.authorizationId()).status())
                .isEqualTo(WeComAvatarAuthorizationService.Status.SUCCEEDED);
        verify(profiles).syncAuthorizedEmployee(installation, "employee-1", detail);
    }

    @Test
    void missingPrivateTicketFailsWithoutCallingProfileExchangeOrDirectoryFallback() {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        when(accessTokens.accessToken(eq(installation), any())).thenReturn("corp-access-token");
        when(gateway.getLoginIdentity(eq("corp-id"), eq("corp-access-token"), eq("oauth-code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity("corp-id", "employee-1", ""));

        var result = service.complete("oauth-code", state);

        assertThat(result.status()).isEqualTo(WeComAvatarAuthorizationService.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_TICKET_MISSING");
        verify(gateway, never()).getUserDetail(any(), any(), any());
        verify(profiles, never()).syncAuthorizedEmployee(any(), any(), any());
        verify(profiles, never()).syncEmployee(any(), any(), any());
    }

    @Test
    void identityMismatchFailsBeforeSensitiveProfileExchange() {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        when(accessTokens.accessToken(eq(installation), any())).thenReturn("corp-access-token");
        when(gateway.getLoginIdentity(eq("corp-id"), eq("corp-access-token"), eq("oauth-code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity(
                        "corp-id", "another-employee", "private-user-ticket"));

        var result = service.complete("oauth-code", state);

        assertThat(result.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_IDENTITY_MISMATCH");
        verify(gateway, never()).getUserDetail(any(), any(), any());
        verify(profiles, never()).syncAuthorizedEmployee(any(), any(), any());
    }

    @Test
    void installationVersionChangeFailsBeforeCallingUpstream() {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        when(installations.resolveInstallation("suite", "corp-id")).thenReturn(new ResolvedInstallation(
                installation.installationId(), "suite", "corp-id", "1000014", "new-pc", 8));

        var result = service.complete("oauth-code", state);

        assertThat(result.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_INSTALLATION_CHANGED");
        verify(accessTokens, never()).accessToken(any(), any());
        verify(gateway, never()).getLoginIdentity(any(), any(), any(), any());
    }

    @Test
    void mismatchedDetailMemberAndEmptyAvatarAreRejectedBeforeSuccess() throws Exception {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        JsonNode detail = json.readTree("{\"userid\":\"another-employee\",\"avatar\":\"https://avatar.example/x.png\"}");
        stubIdentityAndDetail(detail);

        var mismatched = service.complete("oauth-code", state);

        assertThat(mismatched.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_IDENTITY_MISMATCH");
        verify(profiles, never()).syncAuthorizedEmployee(any(), any(), any());
    }

    @Test
    void emptyPersistedAvatarFailsAuthorization() throws Exception {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        JsonNode detail = json.readTree(
                "{\"userid\":\"employee-1\",\"name\":\"员工\",\"avatar\":\"https://avatar.example/new.png\"}");
        stubIdentityAndDetail(detail);
        when(profiles.syncAuthorizedEmployee(installation, "employee-1", detail))
                .thenReturn(new WeComPartyProfileService.ProfileResult(
                        UUID.randomUUID(), "EMPLOYEE", "employee-1", "员工", "", "PARTIAL",
                        "WECOM_PROFILE_AVATAR_EMPTY"));

        var result = service.complete("oauth-code", state);

        assertThat(result.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_PROFILE_EMPTY");
    }

    @Test
    void upstreamFailureIsTerminalAndReplayDoesNotCallUpstreamAgain() {
        var created = service.create(userId, "127.0.0.1");
        String state = query(URI.create(created.authorizationUrl())).get("state");
        when(accessTokens.accessToken(eq(installation), any())).thenThrow(
                new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503, "unavailable"));

        var first = service.complete("oauth-code", state);
        var replay = service.complete("another-code", state);

        assertThat(first.errorCode()).isEqualTo("WECOM_AVATAR_AUTH_UPSTREAM_FAILED");
        assertThat(replay).isEqualTo(first);
        verify(accessTokens).accessToken(eq(installation), any());
    }

    private void stubIdentityAndDetail(JsonNode detail) {
        when(accessTokens.accessToken(eq(installation), any())).thenReturn("corp-access-token");
        when(gateway.getLoginIdentity(eq("corp-id"), eq("corp-access-token"), eq("oauth-code"), any()))
                .thenReturn(new WeComAuthorizationGateway.LoginIdentity(
                        "corp-id", "employee-1", "private-user-ticket"));
        when(gateway.getUserDetail(eq("corp-access-token"), eq("private-user-ticket"), any()))
                .thenReturn(detail);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        Arrays.stream(uri.getRawQuery().split("&")).forEach(pair -> {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        });
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
