package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicManualReviewVersionSchemaContractTest {
    @Test
    void migrationAllowsVersionEntriesCreatedWhenApplyingManualReview() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V44__ai_topic_manual_review_applied_version.sql"));

        assertThat(sql).contains("ck_ai_topic_versions_change");
        assertThat(sql).contains("'MANUAL_REVIEW_APPLIED'");
        assertThat(sql).contains("'MERGED_INTO'");
    }
}
