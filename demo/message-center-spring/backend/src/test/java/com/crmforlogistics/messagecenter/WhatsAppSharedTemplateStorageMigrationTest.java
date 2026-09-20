package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppSharedTemplateStorageMigrationTest {

    @Test
    void migrationLetsSharedTemplatesBeStoredWithoutAChannelAccount() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V78__message_templates_shared_channel_account_nullable.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("alter table message_templates")
                .contains("alter column channel_account_id drop not null");
    }
}
