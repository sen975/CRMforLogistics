package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WeComExternalGroupSyncSchemaContractTest {
    @Test
    void migrationDefinesLeasedPagedRunsAndBoundedStagingItems() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V37__wecom_external_group_sync.sql"));

        assertThat(sql).contains(
                "wecom_external_group_syncs",
                "wecom_external_group_sync_items",
                "installation_id",
                "cursor",
                "page_count",
                "lease_owner",
                "lease_until",
                "next_attempt_at",
                "page_count <= 1000",
                "ux_wecom_external_group_sync_active",
                "ON DELETE CASCADE");
    }
}
