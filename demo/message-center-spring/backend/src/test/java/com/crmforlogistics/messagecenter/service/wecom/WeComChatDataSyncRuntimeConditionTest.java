package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComChatDataSyncRuntimeConditionTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(AppConfig.class, () -> mock(AppConfig.class))
            .withBean(WeComInstallationService.class, () -> mock(WeComInstallationService.class))
            .withBean(WeComChatDataSyncService.class, () -> mock(WeComChatDataSyncService.class))
            .withBean(WeComStartupGate.class, () -> mock(WeComStartupGate.class))
            .withPropertyValues("app.wecom-enabled=true", "app.wecom-suite-id=suite")
            .withUserConfiguration(WeComChatDataSyncRuntime.class);

    @Test
    void autoSyncIsEnabledWhenPropertyIsMissing() {
        context.run(result -> assertThat(result).hasSingleBean(WeComChatDataSyncRuntime.class));
    }

    @Test
    void autoSyncCanBeExplicitlyEnabled() {
        context.withPropertyValues("app.wecom-chatdata-auto-sync-enabled=true")
                .run(result -> assertThat(result).hasSingleBean(WeComChatDataSyncRuntime.class));
    }

    @Test
    void autoSyncCanBeExplicitlyDisabled() {
        context.withPropertyValues("app.wecom-chatdata-auto-sync-enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(WeComChatDataSyncRuntime.class));
    }
}
