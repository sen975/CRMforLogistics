package com.crmforlogistics.messagecenter.service.whatsapp.template;

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

    public WhatsAppTemplateReconciliationScheduler(WhatsAppTemplateReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${app.chatapp-template-reconcile-interval-ms:300000}")
    public void reconcileUnknownOperations() {
        reconciliationService.reconcileUnknown("chatapp-template-reconcile-" + UUID.randomUUID());
    }
}
