package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicMixedScopeSchemaContractTest {
    @Test
    void schemaContractRequiresMixedScopeAndWecomSourceColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V32__ai_topic_mixed_scope.sql"));
        assertThat(sql).contains("owner_type", "owner_id", "WECOM_GROUP", "STORED",
                "wecom_message_summary_job_id", "quiet_deadline", "ai_topic_inbox_requests");
    }
}
