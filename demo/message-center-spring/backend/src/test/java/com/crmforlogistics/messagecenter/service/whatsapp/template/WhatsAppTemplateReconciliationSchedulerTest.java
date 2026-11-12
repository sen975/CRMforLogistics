package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WhatsAppTemplateReconciliationSchedulerTest {
    @Test
    void scheduledRunReconcilesUnknownOperations() {
        WhatsAppTemplateReconciliationService lifecycle =
                mock(WhatsAppTemplateReconciliationService.class);
        WhatsAppTemplateReconciliationScheduler scheduler =
                new WhatsAppTemplateReconciliationScheduler(lifecycle);

        scheduler.reconcileUnknownOperations();

        verify(lifecycle).reconcileUnknown(anyString());
    }
}
