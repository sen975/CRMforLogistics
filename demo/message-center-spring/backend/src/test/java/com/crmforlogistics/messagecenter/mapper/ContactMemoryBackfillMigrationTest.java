package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.PostgresTestSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContactMemoryBackfillMigrationTest {
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID PROFILE_CONTACT_ID = UUID.randomUUID();
    private static final UUID NO_PROFILE_CONTACT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID OLD_EVENT_ID = UUID.randomUUID();
    private static final UUID NEW_EVENT_ID = UUID.randomUUID();
    private static final UUID NO_PROFILE_EVENT_ID = UUID.randomUUID();
    private static final UUID OLD_MESSAGE_ID = UUID.randomUUID();
    private static final UUID NEW_MESSAGE_ID = UUID.randomUUID();
    private static final UUID NO_PROFILE_MESSAGE_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID GENERATION_BATCH_ID = UUID.randomUUID();
    private static final Instant CURSOR_TIME = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeedPreviousSchema() {
        String schema = "contact_memory_backfill_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = PostgresTestSchema.dataSource(POSTGRES, schema);
        PostgresTestSchema.resetTrigramExtension(dataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("98"))
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedPreviousSchema();
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("99"))
                .load()
                .migrate();
    }

    @Test
    void migrationAddsBackfillAndReceiptContractsWithoutRewritingMemoryOrTriggerState() {
        assertThat(columnExists("contact_memory_states", "history_backfill_status")).isTrue();
        assertThat(columnExists("contact_memory_states", "history_backfill_cursor")).isTrue();
        assertThat(columnExists("contact_memory_states", "history_backfill_target_cursor")).isTrue();
        assertThat(columnExists("contact_memory_attempts", "outcome")).isTrue();
        assertThat(columnExists("contact_memory_trigger_events", "memory_applied_at")).isTrue();
        assertThat(indexExists("ix_contact_memory_trigger_events_memory_pending")).isTrue();

        assertThat(jdbc.queryForObject(
                "select history_backfill_status from contact_memory_states where contact_id = ?",
                String.class, PROFILE_CONTACT_ID)).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject(
                "select history_backfill_status from contact_memory_states where contact_id = ?",
                String.class, NO_PROFILE_CONTACT_ID)).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject(
                "select last_success_cursor from contact_memory_states where contact_id = ?",
                String.class, NO_PROFILE_CONTACT_ID)).isEqualTo(cursor(CURSOR_TIME, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")));
        assertThat(jdbc.queryForObject(
                "select history_backfill_cursor is null and history_backfill_target_cursor is null "
                        + "from contact_memory_states where contact_id = ?",
                Boolean.class, PROFILE_CONTACT_ID)).isTrue();

        assertThat(jdbc.queryForObject(
                "select memory_applied_at is not null from contact_memory_trigger_events where id = ?",
                Boolean.class, OLD_EVENT_ID)).isTrue();
        assertThat(jdbc.queryForObject(
                "select memory_applied_at is null from contact_memory_trigger_events where id = ?",
                Boolean.class, NEW_EVENT_ID)).isTrue();
        assertThat(jdbc.queryForObject(
                "select memory_applied_at is null from contact_memory_trigger_events where id = ?",
                Boolean.class, NO_PROFILE_EVENT_ID)).isTrue();
        assertThat(jdbc.queryForObject(
                "select status || ':' || (applied_at is not null)::text "
                        + "from contact_memory_trigger_events where id = ?",
                String.class, OLD_EVENT_ID)).isEqualTo("APPLIED:true");
        assertThat(jdbc.queryForObject(
                "select outcome is null from contact_memory_attempts where id = ?",
                Boolean.class, ATTEMPT_ID)).isTrue();
    }

    @Test
    void newMemoryStateDefaultsToCompleteUnlessMemoryTriggerExplicitlySetsNotStarted() {
        UUID contactId = UUID.randomUUID();
        insertContact(contactId);

        jdbc.update("insert into contact_memory_states (contact_id, owner_user_id, status) "
                        + "values (?, ?, 'CLEAN')", contactId, OWNER_ID);

        assertThat(jdbc.queryForObject(
                "select history_backfill_status from contact_memory_states where contact_id = ?",
                String.class, contactId)).isEqualTo("COMPLETE");
    }

    private static void seedPreviousSchema() {
        Instant createdAt = Instant.parse("2026-09-19T00:00:00Z");
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name, "
                        + "status, created_at, updated_at) values (?, ?, ?, 'hash', 'Owner', 'active', ?, ?)",
                OWNER_ID, "memory-backfill-" + OWNER_ID, OWNER_ID.toString(), timestamp(createdAt), timestamp(createdAt));
        insertContact(PROFILE_CONTACT_ID);
        insertContact(NO_PROFILE_CONTACT_ID);
        jdbc.update("insert into contact_profile_versions (id, contact_id, owner_user_id, version, content, "
                        + "source_cursor, generation_batch_id, model, input_message_count, evidence_count, "
                        + "is_current, created_at) values (?, ?, ?, 1, 'existing profile', 'old', ?, 'test', 1, 1, true, ?)",
                PROFILE_ID, PROFILE_CONTACT_ID, OWNER_ID, GENERATION_BATCH_ID, timestamp(createdAt));
        jdbc.update("insert into contact_memory_states (contact_id, owner_user_id, status, last_success_cursor, "
                        + "current_profile_version_id) values (?, ?, 'CLEAN', ?, ?)",
                PROFILE_CONTACT_ID, OWNER_ID, cursor(CURSOR_TIME, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")), PROFILE_ID);
        jdbc.update("insert into contact_memory_states (contact_id, owner_user_id, status, last_success_cursor) "
                        + "values (?, ?, 'CLEAN', ?)",
                NO_PROFILE_CONTACT_ID, OWNER_ID,
                cursor(CURSOR_TIME, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")));
        jdbc.update("insert into contact_memory_attempts (id, contact_id, owner_user_id, generation_batch_id, "
                        + "status) values (?, ?, ?, ?, 'SUCCEEDED')",
                ATTEMPT_ID, PROFILE_CONTACT_ID, OWNER_ID, GENERATION_BATCH_ID);

        UUID accountId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, encrypted_config) values (?, 'whatsapp', 'test', ?, ?, '{}'::jsonb)",
                accountId, "account-" + accountId, "account-" + accountId);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'whatsapp', 'global', 'peer', 'peer')",
                identityId, PROFILE_CONTACT_ID);
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id) values (?, ?, ?)",
                conversationId, accountId, identityId);
        insertMessage(accountId, conversationId, OLD_MESSAGE_ID, CURSOR_TIME.minusSeconds(10), 1);
        insertMessage(accountId, conversationId, NEW_MESSAGE_ID, CURSOR_TIME.plusSeconds(10), 2);
        insertEvent(OLD_EVENT_ID, PROFILE_CONTACT_ID, OLD_MESSAGE_ID, CURSOR_TIME.minusSeconds(10));
        insertEvent(NEW_EVENT_ID, PROFILE_CONTACT_ID, NEW_MESSAGE_ID, CURSOR_TIME.plusSeconds(10));

        UUID noProfileIdentityId = UUID.randomUUID();
        UUID noProfileConversationId = UUID.randomUUID();
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'whatsapp', 'global', 'peer-2', 'peer-2')",
                noProfileIdentityId, NO_PROFILE_CONTACT_ID);
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id) values (?, ?, ?)",
                noProfileConversationId, accountId, noProfileIdentityId);
        insertMessage(accountId, noProfileConversationId, NO_PROFILE_MESSAGE_ID, CURSOR_TIME.minusSeconds(20), 1);
        insertEvent(NO_PROFILE_EVENT_ID, NO_PROFILE_CONTACT_ID, NO_PROFILE_MESSAGE_ID, CURSOR_TIME.minusSeconds(20));
    }

    private static void insertContact(UUID contactId) {
        Instant createdAt = Instant.parse("2026-09-19T00:00:00Z");
        jdbc.update("insert into contacts (id, display_name, status, created_by, created_at, updated_at) "
                        + "values (?, 'contact', 'active', ?, ?, ?)",
                contactId, OWNER_ID, timestamp(createdAt), timestamp(createdAt));
    }

    private static void insertMessage(UUID accountId, UUID conversationId, UUID messageId, Instant receivedAt,
                                      long ingestSequence) {
        jdbc.update("insert into messages (id, conversation_id, channel_account_id, channel_account_version, direction, message_kind, "
                        + "occurred_at, received_at, ingest_sequence, current_status, current_status_at) "
                        + "values (?, ?, ?, 0, 'inbound', 'text', ?, ?, ?, 'sent', ?)",
                messageId, conversationId, accountId, timestamp(receivedAt), timestamp(receivedAt), ingestSequence,
                timestamp(receivedAt));
    }

    private static void insertEvent(UUID eventId, UUID contactId, UUID messageId, Instant receivedAt) {
        jdbc.update("insert into contact_memory_trigger_events (id, message_id, contact_id, owner_user_id, "
                        + "ingest_sequence, occurred_at, received_at, status, applied_at) "
                        + "values (?, ?, ?, ?, 1, ?, ?, 'APPLIED', ?)",
                eventId, messageId, contactId, OWNER_ID, timestamp(receivedAt), timestamp(receivedAt), timestamp(receivedAt));
    }

    private static String cursor(Instant receivedAt, UUID messageId) {
        return receivedAt + "|" + messageId;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static boolean columnExists(String table, String column) {
        return jdbc.queryForObject("select exists (select 1 from information_schema.columns "
                + "where table_schema = current_schema() and table_name = ? and column_name = ?)",
                Boolean.class, table, column);
    }

    private static boolean indexExists(String index) {
        return jdbc.queryForObject("select exists (select 1 from pg_indexes "
                + "where schemaname = current_schema() and indexname = ?)", Boolean.class, index);
    }
}
