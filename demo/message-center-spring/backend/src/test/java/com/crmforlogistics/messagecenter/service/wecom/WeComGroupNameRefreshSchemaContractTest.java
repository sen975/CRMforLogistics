package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WeComGroupNameRefreshSchemaContractTest {
    @Test
    void migrationDefinesNameResolutionProjectionAndLeasedRefreshJobs() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V34__wecom_group_name_refresh_jobs.sql"));

        assertThat(sql).contains("name_resolution_status", "last_name_checked_at", "name_next_retry_at",
                "wecom_group_name_refresh_jobs", "trigger_source", "lease_owner", "lease_until",
                "TOPIC_UPDATED", "MANUAL", "UNAVAILABLE", "RETRY_WAIT",
                "ux_wecom_group_name_refresh_active");
    }

    @Test
    void migrationSeparatesGroupKindFromTheDisplayNameAndClearsInternalKeys() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V36__wecom_group_kind.sql"));

        assertThat(sql).contains(
                "group_kind",
                "INTERNAL",
                "EXTERNAL",
                "UNKNOWN",
                "display_name ~ '^group:'",
                "group_kind = 'EXTERNAL'");
    }
}
