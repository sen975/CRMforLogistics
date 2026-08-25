package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@EnableScheduling
@ConditionalOnProperty(
        name = {"app.chatapp-sync-enabled", "app.chatapp-template-reconcile-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class WhatsAppTemplateReconciliationScheduler {
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final WhatsAppTemplatePermissionReconciliationService permissionReconciliationService;
    private final ChannelAccountMapper channelAccountMapper;

    public WhatsAppTemplateReconciliationScheduler(
            WhatsAppTemplateReconciliationService reconciliationService,
            WhatsAppTemplatePermissionReconciliationService permissionReconciliationService,
            ChannelAccountMapper channelAccountMapper) {
        this.reconciliationService = reconciliationService;
        this.permissionReconciliationService = permissionReconciliationService;
        this.channelAccountMapper = channelAccountMapper;
    }

    @Scheduled(fixedDelayString = "${app.chatapp-template-reconcile-interval-ms:300000}")
    public void reconcileUnknownOperations() {
        String workerId = "chatapp-template-reconcile-" + UUID.randomUUID();
        reconciliationService.reconcileUnknown(workerId);
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            permissionReconciliationService.reconcileDueTemplates(
                    account.getId(), workerId + "-" + account.getId());
        }
    }
}
