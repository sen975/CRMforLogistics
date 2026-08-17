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
    private final WeComStartupGate startupGate;

    public WeComCredentialMigrationRunner(WeComCredentialMigrationService migrationService,
                                          WeComStartupGate startupGate) {
        this.migrationService = migrationService;
        this.startupGate = startupGate;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrationService.migrateAll();
        startupGate.open();
    }
}
