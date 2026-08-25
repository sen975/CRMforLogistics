package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDirectoryGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComDirectoryServiceTest {
    private static final WeComApiActor ACTOR = new WeComApiActor(
            UUID.fromString("d6c6baaa-a40b-4de4-8324-25788d103cbe"), "trace-1");

    @Test
    void rejectsInvalidDirectoryIdentifiersBeforeUpstreamCall() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.getMember("corp", "", ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.listMembers("corp", -1, false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.getTag("corp", 0, ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fixture.gateway, never()).getMember(any(), any(), any());
    }

    @Test
    void listMembersAcceptsBoundedDepartmentAndWritesAudit() {
        Fixture fixture = fixture();
        WeComApiAuditTrail.Attempt attempt = mock(WeComApiAuditTrail.Attempt.class);
        when(fixture.audit.begin(eq(ACTOR.userId()), any(),
                eq("wecom.api.directory.member_list"), eq("/cgi-bin/user/simplelist"), eq("trace-1")))
                .thenReturn(attempt);

        fixture.service.listMembers("corp", 7, true, ACTOR);

        verify(fixture.gateway).listMembers(eq(fixture.installation), eq(7L), eq(true), any());
        verify(fixture.audit).success(attempt);
    }

    private static Fixture fixture() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomApiTimeoutSeconds()).thenReturn(3);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        ResolvedInstallation installation = new ResolvedInstallation(
                "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
        when(installations.resolveInstallation("suite", "corp")).thenReturn(installation);
        WeComDirectoryGateway gateway = mock(WeComDirectoryGateway.class);
        WeComApiAuditTrail audit = mock(WeComApiAuditTrail.class);
        return new Fixture(installation, gateway, audit,
                new WeComDirectoryService(config, installations, gateway, audit));
    }

    private record Fixture(ResolvedInstallation installation, WeComDirectoryGateway gateway,
                           WeComApiAuditTrail audit, WeComDirectoryService service) {}
}
