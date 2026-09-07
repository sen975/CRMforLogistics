package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicFusionPreviewSchemaContractTest {
    @Test
    void migrationKeepsFusionPreviewBoundedVersionedAndIdempotent() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V42__ai_topic_fusion_previews.sql"));
        assertThat(sql).contains("topic_ids jsonb NOT NULL");
        assertThat(sql).contains("expected_versions jsonb NOT NULL");
        assertThat(sql).contains("expires_at timestamptz NOT NULL");
        assertThat(sql).contains("ux_ai_topic_fusion_preview_idempotency");
        assertThat(sql).contains("result_topic_id uuid REFERENCES ai_topics(id)");
    }
}
