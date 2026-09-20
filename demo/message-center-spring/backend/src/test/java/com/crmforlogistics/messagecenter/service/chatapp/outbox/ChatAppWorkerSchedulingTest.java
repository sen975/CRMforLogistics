package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookProjector;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookRetryWorker;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationScheduler;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.mockito.Mockito.mock;

class ChatAppWorkerSchedulingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MessageOutboxWorker.class, () -> mock(MessageOutboxWorker.class))
            .withBean(ChannelEventMapper.class, () -> mock(ChannelEventMapper.class))
            .withBean(ChannelAccountMapper.class, () -> mock(ChannelAccountMapper.class))
            .withBean(ChatAppWebhookProjector.class, () -> mock(ChatAppWebhookProjector.class))
            .withBean(WhatsAppTemplateReconciliationService.class,
                    () -> mock(WhatsAppTemplateReconciliationService.class))
            .withUserConfiguration(MessageOutboxScheduler.class, ChatAppWebhookRetryWorker.class,
                    WhatsAppTemplateReconciliationScheduler.class);

    @Test
    void disablingHistorySyncDoesNotDisableDeliveryWorkers() {
        contextRunner
                .withPropertyValues("app.chatapp-sync-enabled=false")
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context)
                            .hasSingleBean(MessageOutboxScheduler.class)
                            .hasSingleBean(ChatAppWebhookRetryWorker.class);
                });
    }

    @Test
    void disablingHistorySyncStillEnablesScheduledDelivery() {
        contextRunner
                .withPropertyValues("app.chatapp-sync-enabled=false")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void eachDeliveryWorkerHasItsOwnDisableSwitch() {
        contextRunner
                .withPropertyValues("app.chatapp-outbox-enabled=false")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(MessageOutboxScheduler.class)
                        .hasSingleBean(ChatAppWebhookRetryWorker.class));

        contextRunner
                .withPropertyValues("app.chatapp-webhook-worker-enabled=false")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(MessageOutboxScheduler.class)
                        .doesNotHaveBean(ChatAppWebhookRetryWorker.class));
    }

    @Test
    void templateReconciliationHasItsOwnDisableSwitch() {
        contextRunner
                .withPropertyValues("app.chatapp-template-reconcile-enabled=false")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(WhatsAppTemplateReconciliationScheduler.class));

        contextRunner
                .withPropertyValues("app.chatapp-template-reconcile-enabled=true")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(WhatsAppTemplateReconciliationScheduler.class));
    }

    @Test
    void disablingChatAppSyncAlsoDisablesTemplateReconciliation() {
        contextRunner
                .withPropertyValues(
                        "app.chatapp-sync-enabled=false",
                        "app.chatapp-template-reconcile-enabled=true")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(WhatsAppTemplateReconciliationScheduler.class));
    }
}
