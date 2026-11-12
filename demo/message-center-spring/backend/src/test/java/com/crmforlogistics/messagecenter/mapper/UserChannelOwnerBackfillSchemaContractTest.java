package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserChannelOwnerBackfillSchemaContractTest {

    @Test
    void migrationBackfillsOnlyAuditableOwnerSignals() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__backfill_user_channel_owners.sql"));
        String compact = sql.replaceAll("\\s+", " ").toLowerCase();

        assertThat(compact).contains("created_by_user_id");
        assertThat(compact).contains("owner_user_id");
        assertThat(compact).contains("channel_type in ('chatapp', 'email')");
        assertThat(compact).contains("count(distinct m.created_by_user_id) = 1");
        assertThat(compact).contains("contact_identities");
        assertThat(compact).contains("identity_scope");
        assertThat(compact).contains("contact_taggings");
        assertThat(compact).contains("call_records");
        assertThat(compact).contains("created_by ~");
    }

    @Test
    void migrationPreservesAmbiguousAndWeComData() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__backfill_user_channel_owners.sql"));
        String compact = sql.replaceAll("\\s+", " ").toLowerCase();

        assertThat(compact).contains("owner_user_id is null");
        assertThat(compact).contains("ca.channel_type in ('chatapp', 'email')");
        assertThat(compact).contains("preserves_wecom_contact");
        assertThat(compact).doesNotContain("ca.channel_type = 'wecom'");
        assertThat(compact).doesNotContain("display_name ilike");
        assertThat(compact).doesNotContain("remark ilike");
    }

    @Test
    void migrationAvoidsUnsupportedUuidMinAggregates() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__backfill_user_channel_owners.sql"));
        String compact = sql.replaceAll("\\s+", " ").toLowerCase();

        assertThat(compact).doesNotContain("min(m.created_by_user_id)");
        assertThat(compact).doesNotContain("min(identity.contact_id)");
        assertThat(compact).contains("(array_agg(m.created_by_user_id))[1] as owner_user_id");
        assertThat(compact).contains("(array_agg(identity.contact_id))[1] as contact_id");
    }

    @Test
    void verificationScriptOnlyReportsAggregatesAndDigests() throws Exception {
        String sql = Files.readString(Path.of(
                "../scripts/verify-user-channel-owners.sql"));
        String compact = sql.replaceAll("\\s+", " ").toLowerCase();

        assertThat(compact).contains("count(*)");
        assertThat(compact).contains("md5(");
        assertThat(compact).doesNotContain("body_text");
        assertThat(compact).doesNotContain("encrypted_config");
        assertThat(compact).doesNotContain("password");
    }
}
