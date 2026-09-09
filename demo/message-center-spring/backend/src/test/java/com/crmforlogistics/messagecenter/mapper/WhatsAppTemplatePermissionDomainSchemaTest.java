package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppTemplatePermissionDomainSchemaTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V58__whatsapp_template_permission_domains.sql");
    private static final Path MAPPER = Path.of("src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java");

    @Test
    void permissionDomainMigrationSeparatesSharedAndPrivateTemplateIdentity() throws Exception {
        assertThat(Files.exists(MIGRATION)).isTrue();
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("template_domain")
                .contains("ENTERPRISE_API")
                .contains("EMPLOYEE_BUSINESS_APP")
                .contains("channel_account_id")
                .contains("ux_message_templates_private_identity")
                .contains("ux_message_templates_shared_identity");
    }

    @Test
    void templateEntityAndMapperExposeThePermissionDomain() throws Exception {
        assertThat(TemplateEntity.class.getDeclaredField("templateDomain").getType()).isEqualTo(String.class);
        assertThat(Files.readString(MAPPER)).contains("template_domain")
                .contains("EMPLOYEE_BUSINESS_APP");
    }
}
