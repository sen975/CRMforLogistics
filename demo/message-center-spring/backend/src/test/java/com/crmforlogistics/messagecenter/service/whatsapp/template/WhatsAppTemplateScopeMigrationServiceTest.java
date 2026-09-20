package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppTemplateMigrationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateScopeMigrationServiceTest {
    private static final UUID SCOPE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Mock ChannelAccountMapper accountMapper;
    @Mock WhatsAppProviderScopeMapper scopeMapper;
    @Mock TemplateMapper templateMapper;
    @Mock WhatsAppTemplateMigrationMapper migrationMapper;
    @Mock WhatsAppProviderScopeService providerScopeService;
    @Mock WhatsAppTemplateReconciliationService reconciliation;
    @Mock WhatsAppTemplateScopeGate gate;

    @Test
    void migrateKeepsOneCanonicalTemplateAndOpensGateAfterOneScopeSync() {
        WhatsAppProviderScopeEntity scope = scope(SCOPE_ID, "space-1");
        ChannelAccountEntity account = account(ACCOUNT_ID, SCOPE_ID);
        TemplateEntity first = template(UUID.fromString("60000000-0000-0000-0000-000000000006"), null);
        TemplateEntity duplicate = template(UUID.fromString("70000000-0000-0000-0000-000000000007"), null);

        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of(scope));
        when(accountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account));
        when(templateMapper.findTemplatesForScopeMigration(SCOPE_ID)).thenReturn(List.of(first, duplicate));
        when(reconciliation.syncScope(SCOPE_ID, ACCOUNT_ID))
                .thenReturn(new WhatsAppTemplateReconciliationService.SyncResult(1, 0, 1, true));

        WhatsAppTemplateScopeMigrationService service = service();
        WhatsAppTemplateScopeMigrationService.MigrationReport report = service.migrate();

        assertThat(report.status()).isEqualTo(WhatsAppTemplateScopeMigrationService.MigrationStatus.READY);
        assertThat(report.scopeCount()).isEqualTo(1);
        assertThat(report.canonicalTemplates()).isEqualTo(1);
        verify(templateMapper).retireDuplicate(eq(duplicate.getId()), any(Instant.class));
        verify(templateMapper).assignProviderScope(eq(first.getId()), eq(SCOPE_ID), any(Instant.class));
        verify(reconciliation).syncScope(SCOPE_ID, ACCOUNT_ID);
        verify(gate).open(SCOPE_ID);
    }

    @Test
    void migrateBlocksWhenNoProviderScopeExists() {
        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of());
        when(accountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of());

        WhatsAppTemplateScopeMigrationService.MigrationReport report = service().migrate();

        assertThat(report.status()).isEqualTo(WhatsAppTemplateScopeMigrationService.MigrationStatus.BLOCKED);
        verify(gate).fail(eq("WHATSAPP_ACCOUNT_REQUIRED"), any(String.class));
    }

    /**
     * A second CAMS space must not take the template APIs down. Nothing is merged or re-scoped, so the
     * template-owning space keeps its rows and the other space is simply left alone.
     */
    @Test
    void migrateOpensTheGateOnTheTemplateOwningScopeWithoutRewritingOtherSpaces() {
        UUID otherScopeId = UUID.fromString("51000000-0000-0000-0000-000000000005");
        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of(
                scope(SCOPE_ID, "space-1"), scope(otherScopeId, "space-2")));
        when(accountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account(ACCOUNT_ID, otherScopeId)));
        when(templateMapper.countLiveSharedForScope(SCOPE_ID)).thenReturn(3L);
        when(templateMapper.countLiveSharedForScope(otherScopeId)).thenReturn(0L);

        WhatsAppTemplateScopeMigrationService.MigrationReport report = service().migrate();

        assertThat(report.status()).isEqualTo(WhatsAppTemplateScopeMigrationService.MigrationStatus.READY);
        assertThat(report.scopeCount()).isEqualTo(2);
        verify(gate).open(SCOPE_ID);
        verify(templateMapper, never()).assignProviderScope(any(), any(), any());
        verify(templateMapper, never()).retireDuplicate(any(), any());
        verify(reconciliation, never()).syncScope(any(), any());
    }

    @Test
    void migrateFallsBackToTheSpaceHoldingActiveAccountsWhenNoSpaceOwnsTemplates() {
        UUID otherScopeId = UUID.fromString("51000000-0000-0000-0000-000000000005");
        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of(
                scope(SCOPE_ID, "space-1"), scope(otherScopeId, "space-2")));
        when(accountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account(ACCOUNT_ID, otherScopeId)));
        when(templateMapper.countLiveSharedForScope(SCOPE_ID)).thenReturn(0L);
        when(templateMapper.countLiveSharedForScope(otherScopeId)).thenReturn(0L);

        service().migrate();

        verify(gate).open(otherScopeId);
    }

    private WhatsAppTemplateScopeMigrationService service() {
        return new WhatsAppTemplateScopeMigrationService(accountMapper, scopeMapper, templateMapper,
                migrationMapper, providerScopeService, reconciliation, gate,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static WhatsAppProviderScopeEntity scope(UUID id, String externalId) {
        WhatsAppProviderScopeEntity value = new WhatsAppProviderScopeEntity();
        value.setId(id);
        value.setProvider("ALIYUN_CAMS");
        value.setExternalScopeId(externalId);
        value.setStatus("READY");
        return value;
    }

    private static ChannelAccountEntity account(UUID id, UUID scopeId) {
        ChannelAccountEntity value = new ChannelAccountEntity();
        value.setId(id);
        value.setProviderScopeId(scopeId);
        value.setChannelType("chatapp");
        value.setAuthStatus("active");
        return value;
    }

    private static TemplateEntity template(UUID id, UUID accountId) {
        TemplateEntity value = new TemplateEntity();
        value.setId(id);
        value.setChannelAccountId(accountId);
        value.setProviderTemplateId("shipping_notice");
        value.setLanguageCode("en_US");
        value.setUpdatedAt(Instant.EPOCH);
        return value;
    }
}
