package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppHistorySyncJobEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAccountAssignmentAuditMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppHistorySyncJobMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppAccountLifecycleServiceTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Test
    void listReturnsOwnerOnlyMaskedWhatsAppProjectionWithoutCredentials() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        ChannelAccountEntity account = account(OWNER, "BUSINESS_APP_COEXISTENCE");
        when(accounts.findAllByOwner(OWNER)).thenReturn(List.of(account));
        WhatsAppAccountLifecycleService service = service(accounts, audits, mock(WhatsAppHistorySyncJobMapper.class));

        List<WhatsAppAccountLifecycleService.AccountProjection> result = service.listMine(OWNER);

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.accountId()).isEqualTo(ACCOUNT);
            assertThat(value.maskedPhone()).isEqualTo("*******1111");
            assertThat(value.name()).isEqualTo("销售账号");
            assertThat(value.templateDomain()).isEqualTo("PRIVATE_BUSINESS_APP");
            assertThat(value.toString()).doesNotContain("60111111111").doesNotContain("secret");
        });
    }

    @Test
    void unlinkIsOwnerOnlyAuditedAndDoesNotDeleteProviderResources() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT, OWNER)).thenReturn(account(OWNER, "API_ONLY"));
        when(accounts.disableOwned(OWNER, ACCOUNT)).thenReturn(1);
        when(audits.insert(any(WhatsAppAccountAssignmentAuditEntity.class))).thenReturn(1);
        WhatsAppAccountLifecycleService service = service(accounts, audits, mock(WhatsAppHistorySyncJobMapper.class));

        service.unlink(OWNER, ACCOUNT, "员工主动解绑");

        verify(accounts).disableOwned(OWNER, ACCOUNT);
        verify(audits).insert(any(WhatsAppAccountAssignmentAuditEntity.class));
    }

    @Test
    void otherOwnerCannotUnlinkAccount() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT, OTHER)).thenReturn(null);
        WhatsAppAccountLifecycleService service = service(accounts, audits, mock(WhatsAppHistorySyncJobMapper.class));

        assertThatThrownBy(() -> service.unlink(OTHER, ACCOUNT, "越权"))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ACCOUNT_NOT_FOUND");
        verify(accounts, never()).disableOwned(any(), any());
        verify(audits, never()).insert(any(WhatsAppAccountAssignmentAuditEntity.class));
    }

    @Test
    void historySyncIsAllowedOnlyForBusinessAppOwnerAccount() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppAccountAssignmentAuditMapper audits = mock(WhatsAppAccountAssignmentAuditMapper.class);
        WhatsAppHistorySyncJobMapper jobs = mock(WhatsAppHistorySyncJobMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT, OWNER)).thenReturn(account(OWNER, "BUSINESS_APP_COEXISTENCE"));
        when(jobs.insertPendingIfAbsent(eq(ACCOUNT), eq(OWNER), any())).thenReturn(1);
        WhatsAppHistorySyncJobEntity pending = new WhatsAppHistorySyncJobEntity();
        pending.setId(UUID.randomUUID());
        pending.setChannelAccountId(ACCOUNT);
        pending.setStatus("PENDING");
        when(jobs.findLatestByAccountAndOwner(ACCOUNT, OWNER)).thenReturn(pending);
        WhatsAppAccountLifecycleService service = service(accounts, audits, jobs);

        WhatsAppAccountLifecycleService.HistorySyncProjection result = service.requestHistorySync(OWNER, ACCOUNT);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.jobId()).isEqualTo(pending.getId());
        verify(jobs).insertPendingIfAbsent(eq(ACCOUNT), eq(OWNER), any());
    }

    @Test
    void apiOnlyAccountCannotStartBusinessAppHistorySync() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT, OWNER)).thenReturn(account(OWNER, "API_ONLY"));
        WhatsAppAccountLifecycleService service = service(accounts, mock(WhatsAppAccountAssignmentAuditMapper.class),
                mock(WhatsAppHistorySyncJobMapper.class));

        assertThatThrownBy(() -> service.requestHistorySync(OWNER, ACCOUNT))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_HISTORY_SYNC_NOT_ALLOWED");
    }

    private static WhatsAppAccountLifecycleService service(ChannelAccountMapper accounts,
                                                            WhatsAppAccountAssignmentAuditMapper audits,
                                                            WhatsAppHistorySyncJobMapper jobs) {
        return new WhatsAppAccountLifecycleService(accounts, audits, jobs);
    }

    private static ChannelAccountEntity account(UUID owner, String mode) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT);
        account.setOwnerUserId(owner);
        account.setChannelType("chatapp");
        account.setName("销售账号");
        account.setRemark("东南亚客户");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        account.setProviderPhoneStatus("ACTIVE");
        account.setPhoneVerificationStatus("VERIFIED");
        account.setOnboardingMode(mode);
        account.setEncryptedConfig("secret");
        return account;
    }
}
