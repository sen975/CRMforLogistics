package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComProfileBackfillServiceTest {
    private final AppConfig config = mock(AppConfig.class);
    private final WeComInstallationService installations = mock(WeComInstallationService.class);
    private final WeComPartyMapper parties = mock(WeComPartyMapper.class);
    private final WeComSourceConversationMapper conversations = mock(WeComSourceConversationMapper.class);
    private final WeComPartyProfileService profiles = mock(WeComPartyProfileService.class);
    private final WeComExternalContactService externalContacts = mock(WeComExternalContactService.class);
    private final UUID installationId = UUID.randomUUID();
    private final ResolvedInstallation installation = new ResolvedInstallation(
            installationId.toString(), "suite", "corp", "agent", "permanent", 1L);

    @Test
    void backfillsHistoricalPartyProfilesAndCustomerGroupNames() {
        when(config.wecomSuiteId()).thenReturn("suite");
        when(installations.resolveInstallation("suite", "corp")).thenReturn(installation);
        when(parties.listProfileBackfillCandidates(installationId, 100)).thenReturn(List.of(
                party("EMPLOYEE", "employee-1"), party("EXTERNAL_CONTACT", "external-1")));
        WeComSourceConversationEntity group = group("group:wr-group");
        when(conversations.listGroupBackfillCandidates(installationId, 100)).thenReturn(List.of(group));
        when(externalContacts.groupMembersForSync(installation, "wr-group")).thenReturn(
                new WeComExternalContactService.GroupMemberSnapshot(true, "客户群 A", List.of(
                        new WeComExternalContactService.GroupMember("EMPLOYEE", "employee-2", "员工二", "https://img/employee-2"),
                        new WeComExternalContactService.GroupMember("EXTERNAL_CONTACT", "external-2", "客户二", "https://img/external-2")
                ), ""));
        WeComProfileBackfillService service = new WeComProfileBackfillService(
                config, installations, parties, conversations, profiles, externalContacts);

        var result = service.backfill("corp", 100);

        assertThat(result.partiesAttempted()).isEqualTo(2);
        assertThat(result.groupsNamed()).isEqualTo(1);
        assertThat(result.failures()).isZero();
        verify(profiles).syncEmployee(eq(installation), eq("employee-1"), any());
        verify(profiles).syncExternalContact(eq(installation), eq("external-1"), any());
        verify(profiles).syncObservedProfile(installation, "EMPLOYEE", "employee-2", "员工二", "https://img/employee-2");
        verify(profiles).syncObservedProfile(installation, "EXTERNAL_CONTACT", "external-2", "客户二", "https://img/external-2");
        verify(conversations).updateDisplayName(group.getId(), "客户群 A");
    }

    @Test
    void continuesWhenOneHistoricalProfileOrGroupLookupFails() {
        when(config.wecomSuiteId()).thenReturn("suite");
        when(installations.resolveInstallation("suite", "corp")).thenReturn(installation);
        when(parties.listProfileBackfillCandidates(installationId, 100)).thenReturn(List.of(
                party("EMPLOYEE", "employee-1"), party("EXTERNAL_CONTACT", "external-1")));
        doThrow(new IllegalStateException("directory unavailable"))
                .when(profiles).syncEmployee(eq(installation), eq("employee-1"), any());
        when(conversations.listGroupBackfillCandidates(installationId, 100)).thenReturn(List.of(
                group("group:internal"), group("group:customer")));
        when(externalContacts.groupMembersForSync(installation, "internal")).thenReturn(
                new WeComExternalContactService.GroupMemberSnapshot(false, "", List.of(), "WECOM_API_PERMISSION_DENIED"));
        when(externalContacts.groupMembersForSync(installation, "customer")).thenReturn(
                new WeComExternalContactService.GroupMemberSnapshot(true, "客户群", List.of(), ""));
        WeComProfileBackfillService service = new WeComProfileBackfillService(
                config, installations, parties, conversations, profiles, externalContacts);

        var result = service.backfill("corp", 100);

        assertThat(result.partiesAttempted()).isEqualTo(2);
        assertThat(result.groupsAttempted()).isEqualTo(2);
        assertThat(result.groupsNamed()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(2);
        verify(profiles).syncExternalContact(eq(installation), eq("external-1"), any());
    }

    @Test
    void skipsMalformedHistoricalGroupKeysWithoutCallingTheProvider() {
        when(config.wecomSuiteId()).thenReturn("suite");
        when(installations.resolveInstallation("suite", "corp")).thenReturn(installation);
        when(parties.listProfileBackfillCandidates(installationId, 100)).thenReturn(List.of());
        when(conversations.listGroupBackfillCandidates(installationId, 100)).thenReturn(List.of(
                group("direct:member-1"), group("group:")));
        WeComProfileBackfillService service = new WeComProfileBackfillService(
                config, installations, parties, conversations, profiles, externalContacts);

        var result = service.backfill("corp", 100);

        assertThat(result.groupsAttempted()).isZero();
        assertThat(result.failures()).isZero();
        verify(externalContacts, never()).groupMembersForSync(any(), any());
    }

    private static WeComPartyEntity party(String type, String providerId) {
        WeComPartyEntity party = new WeComPartyEntity();
        party.setPartyType(type);
        party.setProviderPartyId(providerId);
        return party;
    }

    private static WeComSourceConversationEntity group(String key) {
        WeComSourceConversationEntity group = new WeComSourceConversationEntity();
        group.setId(UUID.randomUUID());
        group.setProviderConversationKey(key);
        return group;
    }
}
