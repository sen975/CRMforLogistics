package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import com.crmforlogistics.messagecentertest.AppConfigTestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AppConfigTestConfiguration.class);

    @Test
    void exposesBoundedWeComAuditRetentionDefaults() {
        context.run(application -> {
            AppConfig config = application.getBean(AppConfig.class);

            assertThat(config.wecomAuditRetentionDays()).isEqualTo(7);
            assertThat(config.wecomAuditCleanupIntervalSeconds()).isEqualTo(3_600);
            assertThat(config.wecomAuditCleanupBatchSize()).isEqualTo(500);
            assertThat(config.wecomAuditCleanupMaxBatches()).isEqualTo(32);
        });
    }

    @Test
    void bindsExplicitWeComAuditRetentionSettings() {
        context.withPropertyValues(
                        "app.wecom-audit-retention-days=30",
                        "app.wecom-audit-cleanup-interval-seconds=600",
                        "app.wecom-audit-cleanup-batch-size=250",
                        "app.wecom-audit-cleanup-max-batches=8")
                .run(application -> {
                    AppConfig config = application.getBean(AppConfig.class);

                    assertThat(config.wecomAuditRetentionDays()).isEqualTo(30);
                    assertThat(config.wecomAuditCleanupIntervalSeconds()).isEqualTo(600);
                    assertThat(config.wecomAuditCleanupBatchSize()).isEqualTo(250);
                    assertThat(config.wecomAuditCleanupMaxBatches()).isEqualTo(8);
                });
    }

    @Test
    void usesProviderDefaultsWhenImapHostAndPortAreExplicitlyBlank() {
        context.withPropertyValues(
                        "app.imap-host=",
                        "app.imap-port=",
                        "app.mail-provider=")
                .run(application -> {
                    AppConfig config = application.getBean(AppConfig.class);

                    assertThat(config.imapHost()).isEqualTo("imap.139.com");
                    assertThat(config.imapPort()).isEqualTo("993");
                    assertThat(config.mailProvider()).isEqualTo("139");
                });
    }

    @Test
    void weComUserNotificationIsOffByDefaultWithBoundedWindow() {
        context.run(application -> {
            AppConfig config = application.getBean(AppConfig.class);

            assertThat(config.wecomUserNotificationEnabled()).isFalse();
            assertThat(config.wecomUserNotificationWindowMs()).isEqualTo(90_000L);
            assertThat(config.wecomUserNotificationWorkerIntervalMs()).isEqualTo(1_000L);
            assertThat(config.wecomUserNotificationWorkerInitialDelayMs()).isEqualTo(1_000L);
        });
    }

    @Test
    void bindsExplicitWeComUserNotificationSettings() {
        context.withPropertyValues(
                        "app.wecom-user-notification-enabled=true",
                        "app.wecom-user-notification-window-ms=30000")
                .run(application -> {
                    AppConfig config = application.getBean(AppConfig.class);

                    assertThat(config.wecomUserNotificationEnabled()).isTrue();
                    assertThat(config.wecomUserNotificationWindowMs()).isEqualTo(30_000L);
                });
    }

}
