package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAppChatGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComAppChatServiceTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
    private static final WeComApiActor ACTOR = new WeComApiActor(
            UUID.fromString("d6c6baaa-a40b-4de4-8324-25788d103cbe"), "trace-1");

    @Test
    void createRequiresOwnerInDeduplicatedMemberList() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(new WeComAppChatService.CreateCommand(
                "corp", null, "项目群", "owner", List.of("member", "member")), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");

        verify(fixture.gateway, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulCreateClosesRequiredAudit() {
        Fixture fixture = fixture();
        WeComApiAuditTrail.Attempt attempt = mock(WeComApiAuditTrail.Attempt.class);
        when(fixture.audit.begin(eq(ACTOR.userId()), eq(INSTALLATION),
                eq("wecom.api.appchat.create"), eq("/cgi-bin/appchat/create"), eq("trace-1")))
                .thenReturn(attempt);

        fixture.service.create(new WeComAppChatService.CreateCommand(
                "corp", "chat-1", "项目群", "owner", List.of("owner", "member")), ACTOR);

        verify(fixture.gateway).create(eq(INSTALLATION), eq("chat-1"), eq("项目群"), eq("owner"),
                eq(List.of("owner", "member")), any());
        verify(fixture.audit).success(attempt);
    }

    @Test
    void sendRejectsUnknownMessageTypeAndOversizedContent() {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> fixture.service.send(new WeComAppChatService.SendCommand(
                "corp", "chat-1", "html", mapper.createObjectNode().put("content", "x"), false), ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.send(new WeComAppChatService.SendCommand(
                "corp", "chat-1", "text",
                mapper.createObjectNode().put("content", "x".repeat(256 * 1024)), false), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("large");
    }

    private static Fixture fixture() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomApiTimeoutSeconds()).thenReturn(3);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp")).thenReturn(INSTALLATION);
        WeComAppChatGateway gateway = mock(WeComAppChatGateway.class);
        WeComApiAuditTrail audit = mock(WeComApiAuditTrail.class);
        return new Fixture(gateway, audit, new WeComAppChatService(
                config, installations, gateway, audit, new ObjectMapper()));
    }

    private record Fixture(WeComAppChatGateway gateway, WeComApiAuditTrail audit,
                           WeComAppChatService service) {}
}
