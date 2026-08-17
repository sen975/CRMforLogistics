package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppBroadcastMessageProjectionMigrationTest {

    @Test
    void v20AddsProjectionLinkAndBoundedReconciliationEvidence() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V20__chatapp_broadcast_message_projection.sql"));
        String normalized = sql.toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("ADD COLUMN TEMPLATE_BODY_SNAPSHOT TEXT")
                .contains("ADD COLUMN MESSAGE_ID UUID")
                .contains("REFERENCES MESSAGES(ID) ON DELETE SET NULL")
                .contains("CREATE UNIQUE INDEX UX_CHATAPP_BROADCAST_RECIPIENT_MESSAGE")
                .contains("CREATE TABLE CHATAPP_BROADCAST_RECONCILIATION_EVIDENCE")
                .contains("UNIQUE (JOB_ID, PAGE_NUMBER, ROW_NUMBER)")
                .contains("FAILURE_REASON VARCHAR(1000)")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM MESSAGES");
    }
}
