package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppTemplateReconciliationSchedulerTest {
    @Test
    void scheduledRunReconcilesUnknownOperationsAndDesiredPermissions() {
        WhatsAppTemplateReconciliationService lifecycle =
                mock(WhatsAppTemplateReconciliationService.class);
        WhatsAppTemplatePermissionReconciliationService permissions =
                mock(WhatsAppTemplatePermissionReconciliationService.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ChannelAccountEntity first = account(UUID.randomUUID());
        ChannelAccountEntity second = account(UUID.randomUUID());
        when(accounts.selectActiveChatAppAccountsForSync()).thenReturn(List.of(first, second));
        WhatsAppTemplateReconciliationScheduler scheduler =
                new WhatsAppTemplateReconciliationScheduler(lifecycle, permissions, accounts);

        scheduler.reconcileUnknownOperations();

        verify(lifecycle).reconcileUnknown(anyString());
        verify(permissions).reconcileDueTemplates(eq(first.getId()), anyString());
        verify(permissions).reconcileDueTemplates(eq(second.getId()), anyString());
    }

    private static ChannelAccountEntity account(UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        return account;
    }
}
