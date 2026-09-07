package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicFusionMergedIntoSchemaContractTest {
    @Test
    void migrationAllowsVersionEntriesForArchivedFusionSources() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V43__ai_topic_merged_into_version.sql"));
        assertThat(sql).contains("ck_ai_topic_versions_change");
        assertThat(sql).contains("'MERGED_INTO'");
    }
}
