package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppTemplatePermissionMigrationTest {
    @Test
    void v19AddsPermissionIntentWithoutRewritingProviderActualState() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V19__whatsapp_template_permission_intent.sql"));
        String normalized = sql.toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("DESIRED_ALLOW_SEND BOOLEAN NOT NULL DEFAULT TRUE")
                .contains("PERMISSION_SYNC_STATUS VARCHAR(20) NOT NULL DEFAULT 'IDLE'")
                .contains("PERMISSION_SYNC_ATTEMPT_COUNT INTEGER NOT NULL DEFAULT 0")
                .contains("UPPER(STATUS) = 'APPROVED'")
                .contains("DESIRED_ALLOW_SEND <> ALLOW_SEND")
                .doesNotContain("UPDATE MESSAGE_TEMPLATES SET ALLOW_SEND")
                .doesNotContain("DELETE FROM MESSAGE_TEMPLATES")
                .doesNotContain("DROP TABLE");
    }
}
