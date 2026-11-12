package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDirectoryGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComExternalContactGateway;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComPartyProfileServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WeComDirectoryGateway directory = mock(WeComDirectoryGateway.class);
    private final WeComExternalContactGateway external = mock(WeComExternalContactGateway.class);
    private final WeComPartyMapper parties = mock(WeComPartyMapper.class);
    private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
    private final ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
    private final ContactMapper contacts = mock(ContactMapper.class);
    private final WeComPartyProfileService service = new WeComPartyProfileService(
            directory, external, parties, accounts, identities, contacts);
    private final ResolvedInstallation installation = new ResolvedInstallation(
            "6f5a3e35-8d31-4f0a-9ed8-9f0cc8cc6e8e", "suite", "corp", "agent", "permanent", 1L);

    @Test
    void employeeWithNameAndAvatarIsReady() throws Exception {
        when(directory.getMember(any(), eq("member-1"), any())).thenReturn(json.readTree(
                "{\"name\":\"Alice\",\"avatar\":\"https://avatar.example/a.png\"}"));
        when(parties.selectOne(any())).thenReturn(null);

        var result = service.syncEmployee(installation, "member-1", Duration.ofSeconds(1));

        assertThat(result.profileStatus()).isEqualTo("READY");
        assertThat(result.displayName()).isEqualTo("Alice");
        verify(parties).insert(any(WeComPartyEntity.class));
    }

    @Test
    void missingAvatarLeavesProfilePartialInsteadOfUsingProviderIdAsName() throws Exception {
        when(directory.getMember(any(), eq("member-2"), any())).thenReturn(json.readTree("{\"name\":\"Bob\"}"));
        when(parties.selectOne(any())).thenReturn(null);

        var result = service.syncEmployee(installation, "member-2", Duration.ofSeconds(1));

        assertThat(result.profileStatus()).isEqualTo("PARTIAL");
        assertThat(result.displayName()).isEqualTo("Bob");
        assertThat(result.displayName()).isNotEqualTo("member-2");
        assertThat(result.profileErrorCode()).isEqualTo("WECOM_PROFILE_AVATAR_EMPTY");
    }

    @Test
    void usesThumbAvatarWhenDirectoryDoesNotReturnFullAvatar() throws Exception {
        when(directory.getMember(any(), eq("member-thumb"), any())).thenReturn(json.readTree(
                "{\"name\":\"头像成员\",\"thumb_avatar\":\"https://avatar.example/thumb.png\"}"));
        when(parties.selectOne(any())).thenReturn(null);

        var result = service.syncEmployee(installation, "member-thumb", Duration.ofSeconds(1));

        assertThat(result.avatarUrl()).isEqualTo("https://avatar.example/thumb.png");
        assertThat(result.profileStatus()).isEqualTo("READY");
    }

    @Test
    void directoryPartialResponseDoesNotEraseExistingAvatar() throws Exception {
        WeComPartyEntity existing = new WeComPartyEntity();
        existing.setId(UUID.randomUUID());
        existing.setInstallationId(UUID.fromString(installation.installationId()));
        existing.setPartyType("EMPLOYEE");
        existing.setProviderPartyId("member-existing-avatar");
        existing.setDisplayName("已有成员");
        existing.setAvatarUrl("https://avatar.example/existing.png");
        when(directory.getMember(any(), eq("member-existing-avatar"), any())).thenReturn(json.readTree(
                "{\"name\":\"已有成员\"}"));
        when(parties.selectOne(any())).thenReturn(existing);

        var result = service.syncEmployee(installation, "member-existing-avatar", Duration.ofSeconds(1));

        assertThat(result.avatarUrl()).isEqualTo("https://avatar.example/existing.png");
        assertThat(result.profileStatus()).isEqualTo("READY");
    }

    @Test
    void directoryFailureDoesNotEraseExistingProfile() {
        WeComPartyEntity existing = new WeComPartyEntity();
        existing.setId(UUID.randomUUID());
        existing.setInstallationId(UUID.fromString(installation.installationId()));
        existing.setPartyType("EMPLOYEE");
        existing.setProviderPartyId("member-transient-failure");
        existing.setDisplayName("已有成员");
        existing.setAvatarUrl("https://avatar.example/existing.png");
        when(directory.getMember(any(), eq("member-transient-failure"), any()))
                .thenThrow(new RuntimeException("upstream timeout"));
        when(parties.selectOne(any())).thenReturn(existing);

        var result = service.syncEmployee(installation, "member-transient-failure", Duration.ofSeconds(1));

        assertThat(result.profileStatus()).isEqualTo("DEGRADED");
        assertThat(result.displayName()).isEqualTo("已有成员");
        assertThat(result.avatarUrl()).isEqualTo("https://avatar.example/existing.png");
    }

    @Test
    void employeeProfileRefreshesExistingProviderIdentityAndUnresolvedContactName() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setDisplayName("member-3");
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setDisplayName("member-3");
        when(directory.getMember(any(), eq("member-3"), any())).thenReturn(json.readTree(
                "{\"name\":\"张三\",\"avatar\":\"https://avatar.example/3.png\"}"));
        when(parties.selectOne(any())).thenReturn(null);
        when(accounts.selectActiveWeComAccount("corp")).thenReturn(account);
        when(identities.findByNormalizedValueInScope("wecom", accountId.toString(), "member-3"))
                .thenReturn(Optional.of(identity));
        when(contacts.selectById(contactId)).thenReturn(contact);

        service.syncEmployee(installation, "member-3", Duration.ofSeconds(1));

        verify(identities).updateDisplayName(identity.getId(), "张三");
        verify(contacts).updateDisplayName(contactId, "张三");
    }

    @Test
    void directorySyncDiscoversDepartmentsAndHydratesEachMemberProfile() throws Exception {
        when(directory.listDepartments(any(), any(), any())).thenReturn(json.readTree(
                "{\"department\":[{\"id\":1},{\"id\":2}]}"));
        when(directory.listMembers(any(), eq(1L), eq(true), any())).thenReturn(json.readTree(
                "{\"userlist\":[{\"userid\":\"alice\"}]}"));
        when(directory.listMembers(any(), eq(2L), eq(true), any())).thenReturn(json.readTree(
                "{\"userlist\":[{\"userid\":\"bob\"},{\"userid\":\"alice\"}]}"));
        when(directory.getMember(any(), anyString(), any())).thenAnswer(invocation ->
                json.readTree("{\"name\":\"" + invocation.getArgument(1) + "\",\"avatar\":\"https://a/" + invocation.getArgument(1) + "\"}"));
        when(parties.selectOne(any())).thenReturn(null);

        var result = service.syncDirectory(installation, Duration.ofSeconds(1));

        assertThat(result.discovered()).isEqualTo(2);
        assertThat(result.ready()).isEqualTo(2);
        verify(directory).listDepartments(installation, null, Duration.ofSeconds(1));
        verify(directory, times(2)).listMembers(eq(installation), anyLong(), eq(true), eq(Duration.ofSeconds(1)));
        verify(directory, times(2)).getMember(eq(installation), anyString(), eq(Duration.ofSeconds(1)));
    }

    @Test
    void observedGroupProfileDoesNotEraseExistingFieldsWhenSnapshotIsPartial() {
        WeComPartyEntity existing = new WeComPartyEntity();
        existing.setId(UUID.randomUUID());
        existing.setInstallationId(UUID.fromString(installation.installationId()));
        existing.setPartyType("EMPLOYEE");
        existing.setProviderPartyId("member-4");
        existing.setDisplayName("已有昵称");
        existing.setAvatarUrl("https://avatar.example/existing.png");
        when(parties.selectOne(any())).thenReturn(existing);

        service.syncObservedProfile(installation, "EMPLOYEE", "member-4", "", "");

        verify(parties).updateById(existing);
        assertThat(existing.getDisplayName()).isEqualTo("已有昵称");
        assertThat(existing.getAvatarUrl()).isEqualTo("https://avatar.example/existing.png");
    }

    @Test
    void authorizedEmployeeProfileUpdatesOnlyReturnedAvatar() throws Exception {
        WeComPartyEntity existing = new WeComPartyEntity();
        existing.setId(UUID.randomUUID());
        existing.setInstallationId(UUID.fromString(installation.installationId()));
        existing.setPartyType("EMPLOYEE");
        existing.setProviderPartyId("member-authorized");
        existing.setDisplayName("已有姓名");
        existing.setAvatarUrl("");
        when(parties.selectOne(any())).thenReturn(existing);

        var result = service.syncAuthorizedEmployee(
                installation, "member-authorized",
                json.readTree("{\"userid\":\"member-authorized\",\"avatar\":\"https://avatar/a.png\"}"));

        assertThat(result.displayName()).isEqualTo("已有姓名");
        assertThat(result.avatarUrl()).isEqualTo("https://avatar/a.png");
        verify(parties).updateById(existing);
    }

    @Test
    void authorizedEmployeeProfilePersistsNicknameReturnedByWeCom() throws Exception {
        when(parties.selectOne(any())).thenReturn(null);

        var result = service.syncAuthorizedEmployee(
                installation, "member-authorized",
                json.readTree("{\"userid\":\"member-authorized\",\"name\":\"授权成员\"}"));

        assertThat(result.displayName()).isEqualTo("授权成员");
    }
}
