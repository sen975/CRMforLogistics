package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAccountAssignmentAuditMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWhatsAppPhoneNumberServiceTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_A = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_B = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void assignsOnlyUnassignedAccountWithVersionHistoryGrantAndAudit() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserMapper users = mock(UserMapper.class);
        RoleMapper roles = adminRoles();
        when(accounts.findWhatsAppByIdForUpdate(ACCOUNT_ID)).thenReturn(account(null, 4L));
        when(users.findAssignableSalesUser(OWNER_A)).thenReturn(Optional.of(user(OWNER_A)));
        when(accounts.countActiveWhatsAppByOwner(OWNER_A)).thenReturn(0);
        when(accounts.assignWhatsAppOwner(ACCOUNT_ID, OWNER_A, 4L)).thenReturn(1);
        when(conversations.grantAccountHistory(ACCOUNT_ID, OWNER_A, ACTOR)).thenReturn(1);
        when(audits.insert(any(WhatsAppAccountAssignmentAuditEntity.class))).thenReturn(1);

        AdminWhatsAppPhoneNumberService service = service(accounts, audits, conversations, users, roles);

        AdminWhatsAppPhoneNumberService.AccountProjection result =
                service.assign(ACTOR, ACCOUNT_ID, OWNER_A, "首次分配", 4L);

        assertThat(result.ownerUserId()).isEqualTo(OWNER_A);
        assertThat(result.version()).isEqualTo(5L);
        verify(accounts).findWhatsAppByIdForUpdate(ACCOUNT_ID);
        verify(accounts).assignWhatsAppOwner(ACCOUNT_ID, OWNER_A, 4L);
        verify(conversations).grantAccountHistory(ACCOUNT_ID, OWNER_A, ACTOR);
        verify(audits).insert(any(WhatsAppAccountAssignmentAuditEntity.class));
    }

    @Test
    void transferGrantsNewOwnerAndKeepsPreviousOwnerInAudit() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserMapper users = mock(UserMapper.class);
        RoleMapper roles = adminRoles();
        when(accounts.findWhatsAppByIdForUpdate(ACCOUNT_ID)).thenReturn(account(OWNER_A, 7L));
        when(users.findAssignableSalesUser(OWNER_B)).thenReturn(Optional.of(user(OWNER_B)));
        when(accounts.countActiveWhatsAppByOwner(OWNER_B)).thenReturn(0);
        when(accounts.transferWhatsAppOwner(ACCOUNT_ID, OWNER_B, 7L)).thenReturn(1);
        when(conversations.grantAccountHistory(ACCOUNT_ID, OWNER_B, ACTOR)).thenReturn(1);
        when(audits.insert(any(WhatsAppAccountAssignmentAuditEntity.class))).thenReturn(1);

        AdminWhatsAppPhoneNumberService.AccountProjection result =
                service(accounts, audits, conversations, users, roles)
                        .transfer(ACTOR, ACCOUNT_ID, OWNER_B, "交接销售", 7L);

        assertThat(result.ownerUserId()).isEqualTo(OWNER_B);
        assertThat(result.version()).isEqualTo(8L);
        org.mockito.ArgumentCaptor<WhatsAppAccountAssignmentAuditEntity> audit =
                org.mockito.ArgumentCaptor.forClass(WhatsAppAccountAssignmentAuditEntity.class);
        verify(audits).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("TRANSFER");
        assertThat(audit.getValue().getPreviousOwnerUserId()).isEqualTo(OWNER_A);
        assertThat(audit.getValue().getNextOwnerUserId()).isEqualTo(OWNER_B);
    }

    @Test
    void reclaimClearsOwnerButNeverClearsEncryptedCredentials() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserMapper users = mock(UserMapper.class);
        RoleMapper roles = adminRoles();
        when(accounts.findWhatsAppByIdForUpdate(ACCOUNT_ID)).thenReturn(account(OWNER_A, 9L));
        when(accounts.reclaimWhatsAppOwner(ACCOUNT_ID, 9L)).thenReturn(1);
        when(audits.insert(any(WhatsAppAccountAssignmentAuditEntity.class))).thenReturn(1);

        AdminWhatsAppPhoneNumberService.AccountProjection result =
                service(accounts, audits, conversations, users, roles)
                        .reclaim(ACTOR, ACCOUNT_ID, "暂时收回", 9L);

        assertThat(result.ownerUserId()).isNull();
        assertThat(result.version()).isEqualTo(10L);
        verify(accounts).reclaimWhatsAppOwner(ACCOUNT_ID, 9L);
        verify(accounts, never()).updateEncryptedConfig(any(), any());
        org.mockito.ArgumentCaptor<WhatsAppAccountAssignmentAuditEntity> audit =
                org.mockito.ArgumentCaptor.forClass(WhatsAppAccountAssignmentAuditEntity.class);
        verify(audits).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("RECLAIM");
    }

    @Test
    void rejectsNonAdminAndStaleVersionBeforeMutatingAccount() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserMapper users = mock(UserMapper.class);
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(ACTOR, "admin")).thenReturn(false);
        AdminWhatsAppPhoneNumberService service = service(accounts, audits, conversations, users, roles);

        assertThatThrownBy(() -> service.reclaim(ACTOR, ACCOUNT_ID, "越权", 1L))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ADMIN_REQUIRED");
        verify(accounts, never()).findWhatsAppByIdForUpdate(any());

        when(roles.userHasRole(ACTOR, "admin")).thenReturn(true);
        when(accounts.findWhatsAppByIdForUpdate(ACCOUNT_ID)).thenReturn(account(OWNER_A, 3L));
        assertThatThrownBy(() -> service.reclaim(ACTOR, ACCOUNT_ID, "旧版本", 2L))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ASSIGNMENT_CONFLICT");
        verify(accounts, never()).reclaimWhatsAppOwner(any(), any(Long.class));
    }

    @Test
    void rejectsTargetWithExistingWhatsAppAccount() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserMapper users = mock(UserMapper.class);
        RoleMapper roles = adminRoles();
        when(accounts.findWhatsAppByIdForUpdate(ACCOUNT_ID)).thenReturn(account(null, 1L));
        when(users.findAssignableSalesUser(OWNER_A)).thenReturn(Optional.of(user(OWNER_A)));
        when(accounts.countActiveWhatsAppByOwner(OWNER_A)).thenReturn(1);
        AdminWhatsAppPhoneNumberService service = service(accounts, audits, conversations, users, roles);

        assertThatThrownBy(() -> service.assign(ACTOR, ACCOUNT_ID, OWNER_A, "重复占用", 1L))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_TARGET_ACCOUNT_ALREADY_EXISTS");
        verify(accounts, never()).assignWhatsAppOwner(any(), any(), any(Long.class));
        verify(audits, never()).insert(any(WhatsAppAccountAssignmentAuditEntity.class));
    }

    private static AdminWhatsAppPhoneNumberService service(ChannelAccountMapper accounts,
                                                            WhatsAppAccountAssignmentAuditMapper audits,
                                                            ConversationMapper conversations,
                                                            UserMapper users,
                                                            RoleMapper roles) {
        return new AdminWhatsAppPhoneNumberService(accounts, audits, conversations, users, roles);
    }

    private static RoleMapper adminRoles() {
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(ACTOR, "admin")).thenReturn(true);
        return roles;
    }

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus("active");
        user.setDisplayName("销售");
        return user;
    }

    private static ChannelAccountEntity account(UUID owner, long version) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setOwnerUserId(owner);
        account.setChannelType("chatapp");
        account.setName("WhatsApp");
        account.setAccountIdentifier("60111111111");
        account.setProviderPhoneStatus("ACTIVE");
        account.setPhoneVerificationStatus("VERIFIED");
        account.setAuthStatus("active");
        account.setEncryptedConfig("encrypted-secret");
        account.setVersion(version);
        return account;
    }
}
