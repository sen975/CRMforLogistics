package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.chatapp-template-shared-migration-enabled", havingValue = "true",
        matchIfMissing = true)
public class WhatsAppTemplateScopeMigrationRunner implements ApplicationRunner {
    private final WhatsAppTemplateScopeMigrationService migrationService;
    private final WhatsAppTemplateScopeGate gate;

    public WhatsAppTemplateScopeMigrationRunner(WhatsAppTemplateScopeMigrationService migrationService,
                                                WhatsAppTemplateScopeGate gate) {
        this.migrationService = migrationService;
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrationService.migrate();
        } catch (RuntimeException exception) {
            gate.fail("WHATSAPP_TEMPLATE_MIGRATION_FAILED", "共享 WhatsApp 模板初始化失败");
        }
    }
}
