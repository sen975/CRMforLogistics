package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppAdminAssignmentSchemaContractTest {

    @Test
    void migrationCanonicalizesAssignmentActionsAndBackfillsHistoryAccess() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V66__whatsapp_admin_account_assignment.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("drop constraint if exists ck_whatsapp_account_assignment_action")
                .contains("action in ('assign', 'reclaim', 'transfer')")
                .contains("update whatsapp_account_assignment_audits")
                .contains("set action = 'reclaim'")
                .contains("insert into whatsapp_account_assignment_audits")
                .contains("insert into conversation_access_grants")
                .contains("assignment_history")
                .contains("on conflict (conversation_id, user_id) where revoked_at is null do nothing");
    }

    @Test
    void accountMapperUsesVersionedOwnerChangesWithoutDisablingOrClearingCredentials() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java"));
        String normalized = mapper.toLowerCase().replaceAll("\\s+", " ");

        assertThat(mapper)
                .contains("findWhatsAppByIdForUpdate")
                .contains("assignWhatsAppOwner")
                .contains("reclaimWhatsAppOwner");
        assertThat(normalized)
                .contains("version = #{expectedversion}")
                .contains("owner_user_id = null");
        int reclaimMethod = normalized.indexOf("int reclaimwhatsappowner");
        assertThat(reclaimMethod).isPositive();
        String reclaimSql = normalized.substring(Math.max(0, reclaimMethod - 700), reclaimMethod);
        assertThat(reclaimSql)
                .doesNotContain("encrypted_config = '{}'::jsonb")
                .doesNotContain("auth_status = 'disabled'");
    }

    @Test
    void conversationMapperCanGrantAllExistingAccountConversationsIdempotently() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java"));
        String normalized = mapper.toLowerCase().replaceAll("\\s+", " ");

        assertThat(mapper).contains("grantAccountHistory");
        assertThat(normalized)
                .contains("insert into conversation_access_grants")
                .contains("from conversations")
                .contains("channel_account_id = #{accountid}::uuid")
                .contains("on conflict (conversation_id, user_id) where revoked_at is null do nothing");
    }

    @Test
    void accountVersionMigrationsBackfillAndRequireSnapshotsForBroadcastsAndMessages() throws Exception {
        String broadcastSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V68__chatapp_broadcast_account_version.sql"));
        String messageSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V69__message_account_version.sql"));

        assertVersionSnapshotMigration(broadcastSql, "chatapp_broadcasts");
        assertVersionSnapshotMigration(messageSql, "messages");
    }

    private static void assertVersionSnapshotMigration(String sql, String table) {
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("alter table " + table + " add column channel_account_version bigint")
                .contains("update " + table)
                .contains("set channel_account_version = coalesce(ca.version, 0)")
                .contains("set channel_account_version = 0")
                .contains("alter column channel_account_version set not null");
    }
}
