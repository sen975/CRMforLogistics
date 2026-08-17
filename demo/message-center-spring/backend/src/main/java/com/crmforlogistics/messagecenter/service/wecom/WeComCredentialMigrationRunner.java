package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWeComEnabled
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class WeComCredentialMigrationRunner implements ApplicationRunner {
    private final WeComCredentialMigrationService migrationService;
    private final WeComAuthorizationRecovery authorizationRecovery;
    private final WeComStartupGate startupGate;

    public WeComCredentialMigrationRunner(WeComCredentialMigrationService migrationService,
                                          WeComAuthorizationRecovery authorizationRecovery,
                                          WeComStartupGate startupGate) {
        this.migrationService = migrationService;
        this.authorizationRecovery = authorizationRecovery;
        this.startupGate = startupGate;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrationService.migrateAll();
        try {
            authorizationRecovery.reconcile();
        } catch (com.crmforlogistics.messagecenter.channel.wecom.WeComException failure) {
            startupGate.fail(failure.code(), failure.getMessage());
            return;
        }
        startupGate.open();
    }
}
