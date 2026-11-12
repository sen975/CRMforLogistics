package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComExternalContactGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComExternalContactServiceTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp", "1", "code", 1);
    private static final WeComApiActor ACTOR = new WeComApiActor(
            UUID.fromString("d6c6baaa-a40b-4de4-8324-25788d103cbe"), "trace-1");

    @Test
    void batchQueryBoundsUsersAndLimit() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.batchGet(new WeComExternalContactService.BatchGetCommand(
                "corp", List.of(), "", 10), ACTOR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.batchGet(new WeComExternalContactService.BatchGetCommand(
                "corp", List.of("member"), "", 101), ACTOR)).isInstanceOf(IllegalArgumentException.class);
        verify(fixture.gateway, never()).batchGet(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void remarkValidatesFieldsAndClosesAudit() {
        Fixture fixture = fixture();
        WeComApiAuditTrail.Attempt attempt = mock(WeComApiAuditTrail.Attempt.class);
        when(fixture.audit.begin(eq(ACTOR.userId()), eq(INSTALLATION),
                eq("wecom.api.external_contact.remark"), eq("/cgi-bin/externalcontact/remark"), eq("trace-1")))
                .thenReturn(attempt);

        fixture.service.remark(new WeComExternalContactService.RemarkCommand(
                "corp", "member", "external", "重点客户", "描述", "Acme", List.of("13800000000")), ACTOR);

        verify(fixture.gateway).remark(eq(INSTALLATION), eq("member"), eq("external"),
                eq("重点客户"), eq("描述"), eq("Acme"), eq(List.of("13800000000")), any());
        verify(fixture.audit).success(attempt);
    }

    @Test
    void groupListBoundsOwnerFilterAndLimit() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.groupList(new WeComExternalContactService.GroupListCommand(
                "corp", 0, List.of("owner"), "", 1001), ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupMemberSyncReturnsEmployeeAndExternalRosterFromGroupGet() throws Exception {
        Fixture fixture = fixture();
        when(fixture.gateway.groupGet(eq(INSTALLATION), eq("wr-group"), eq(true), any()))
                .thenReturn(new ObjectMapper().readTree("""
                        {"errcode":0,"group_chat":{"chat_id":"wr-group","name":"客户群 A","member_list":[
                          {"type":1,"userid":"employee-1","name":"员工一","avatar":"https://img/1"},
                          {"type":2,"external_userid":"external-1","name":"客户一"}]}}
                        """));

        var result = fixture.service.groupMembersForSync(INSTALLATION, "wr-group");

        assertThat(result.available()).isTrue();
        assertThat(result.displayName()).isEqualTo("客户群 A");
        assertThat(result.members()).extracting(WeComExternalContactService.GroupMember::partyType)
                .containsExactly("EMPLOYEE", "EXTERNAL_CONTACT");
        assertThat(result.members().get(0).displayName()).isEqualTo("员工一");
        assertThat(result.members().get(0).avatarUrl()).isEqualTo("https://img/1");
    }

    @Test
    void externalGroupSyncParsesOneStrictBoundedPage() throws Exception {
        Fixture fixture = fixture();
        when(fixture.gateway.groupList(eq(INSTALLATION), isNull(), eq(List.of()), eq("cursor-1"), eq(1000), any()))
                .thenReturn(new ObjectMapper().readTree("""
                        {"errcode":0,"group_chat_list":[
                          {"chat_id":"chat-a"},{"chat_id":"chat-b"},{"chat_id":"chat-a"}],
                         "next_cursor":"cursor-2"}
                        """));

        var page = fixture.service.externalGroupPageForSync(INSTALLATION, "cursor-1");

        assertThat(page.chatIds()).containsExactly("chat-a", "chat-b");
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
    }

    private static Fixture fixture() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomApiTimeoutSeconds()).thenReturn(3);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp")).thenReturn(INSTALLATION);
        WeComExternalContactGateway gateway = mock(WeComExternalContactGateway.class);
        WeComApiAuditTrail audit = mock(WeComApiAuditTrail.class);
        return new Fixture(gateway, audit, new WeComExternalContactService(
                config, installations, gateway, audit));
    }

    private record Fixture(WeComExternalContactGateway gateway, WeComApiAuditTrail audit,
                           WeComExternalContactService service) {}
}
