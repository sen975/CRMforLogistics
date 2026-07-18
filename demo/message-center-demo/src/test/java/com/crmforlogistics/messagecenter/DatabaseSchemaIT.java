package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class DatabaseSchemaIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private Config config;

    @BeforeEach
    void migrateDatabase() throws Exception {
        config = databaseConfig(POSTGRES);
        try (Database database = Database.open(config)) {
            database.migrate();
        }
    }

    @Test
    void migratesEveryRequiredTable() throws Exception {
        try (Database database = Database.open(config)) {
            assertTables(database,
                    "users", "roles", "user_roles", "user_sessions", "teams", "team_members",
                    "companies", "company_tags", "company_taggings", "contacts", "contact_tags",
                    "contact_taggings", "company_contacts", "contact_identities", "phone_notes",
                    "channel_accounts", "channel_sync_cursors", "message_templates", "conversations",
                    "conversation_access_grants", "conversation_read_states", "messages",
                    "message_participants", "message_status_events", "attachments", "channel_events",
                    "outbox_jobs", "audit_logs", "data_import_batches", "data_import_errors");
        }
    }

    @Test
    void rejectsDuplicateIdentityAndProviderMessageAndAccountDrift() throws Exception {
        try (Database database = Database.open(config)) {
            Fixture fixture = database.transaction(this::createFixture);

            Exception duplicateIdentity = assertThrows(Exception.class, () -> database.transaction(connection -> {
                insertIdentity(connection, UUID.randomUUID(), fixture.secondContactId(),
                        "whatsapp", "global", "8613800000000");
                return null;
            }));
            assertSqlState("23505", duplicateIdentity);

            database.transaction(connection -> {
                insertMessage(connection, fixture.conversationId(), fixture.firstAccountId(),
                        "provider-message-1", 1L);
                return null;
            });
            Exception duplicateProviderMessage = assertThrows(Exception.class,
                    () -> database.transaction(connection -> {
                        insertMessage(connection, fixture.conversationId(), fixture.firstAccountId(),
                                "provider-message-1", 2L);
                        return null;
                    }));
            assertSqlState("23505", duplicateProviderMessage);

            Exception accountDrift = assertThrows(Exception.class, () -> database.transaction(connection -> {
                insertMessage(connection, fixture.conversationId(), fixture.secondAccountId(),
                        "provider-message-2", 2L);
                return null;
            }));
            assertSqlState("23503", accountDrift);
        }
    }

    @Test
    void commitsSuccessfulWorkAndRollsBackWithOriginalCause() throws Exception {
        try (Database database = Database.open(config)) {
            String committedRole = "test_commit_" + UUID.randomUUID();
            database.transaction(connection -> {
                insertRole(connection, committedRole);
                return null;
            });
            assertEquals(1L, countRole(database, committedRole));

            String rolledBackRole = "test_rollback_" + UUID.randomUUID();
            IllegalStateException original = new IllegalStateException("expected transaction failure");
            Exception failure = assertThrows(Exception.class, () -> database.transaction(connection -> {
                insertRole(connection, rolledBackRole);
                throw original;
            }));

            assertSame(original, failure.getCause());
            assertEquals(0L, countRole(database, rolledBackRole));
            assertFalse(failure.getMessage().contains("ApplicationName="));
            assertFalse(failure.getMessage().contains(POSTGRES.getPassword()));
        }
    }

    @Test
    void acceptsEveryPlannedChannelAccountType() throws Exception {
        try (Database database = Database.open(config)) {
            database.transaction(connection -> {
                insertAccount(connection, "email", "email-primary");
                insertAccount(connection, "chatapp", "chatapp-primary");
                insertAccount(connection, "wecom", "wecom-primary");
                return null;
            });
        }
    }

    @Test
    void createsTrigramIndexesForCompanyAndContactTags() throws Exception {
        try (Database database = Database.open(config)) {
            assertIndex(database, "ix_company_tags_name_trgm");
            assertIndex(database, "ix_contact_tags_name_trgm");
        }
    }

    private Fixture createFixture(Connection connection) throws Exception {
        UUID firstContactId = insertContact(connection, "Buyer");
        UUID secondContactId = insertContact(connection, "Buyer WA");
        UUID identityId = UUID.randomUUID();
        insertIdentity(connection, identityId, firstContactId,
                "whatsapp", "global", "8613800000000");
        UUID firstAccountId = insertAccount(connection, "chatapp", "wa-primary");
        UUID secondAccountId = insertAccount(connection, "chatapp", "wa-secondary");
        UUID conversationId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into conversations (id, channel_account_id, contact_identity_id)
                values (?, ?, ?)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, firstAccountId);
            statement.setObject(3, identityId);
            statement.executeUpdate();
        }
        return new Fixture(firstContactId, secondContactId, firstAccountId, secondAccountId, conversationId);
    }

    private UUID insertContact(Connection connection, String displayName) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into contacts (id, display_name) values (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, displayName);
            statement.executeUpdate();
        }
        return id;
    }

    private void insertIdentity(Connection connection, UUID id, UUID contactId, String channelType,
                                String scope, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into contact_identities
                    (id, contact_id, channel_type, identity_scope, identity_value, normalized_value)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, contactId);
            statement.setString(3, channelType);
            statement.setString(4, scope);
            statement.setString(5, value);
            statement.setString(6, value);
            statement.executeUpdate();
        }
    }

    private UUID insertAccount(Connection connection, String channelType, String identifier) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into channel_accounts
                    (id, channel_type, name, account_identifier, account_identifier_normalized,
                     encrypted_config)
                values (?, ?, ?, ?, ?, '{}'::jsonb)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, channelType);
            statement.setString(3, identifier);
            statement.setString(4, identifier);
            statement.setString(5, identifier);
            statement.executeUpdate();
        }
        return id;
    }

    private void insertMessage(Connection connection, UUID conversationId, UUID accountId,
                               String providerMessageId, long sequence) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into messages
                    (conversation_id, channel_account_id, provider_message_id, direction,
                     message_kind, body_text, occurred_at, ingest_sequence, counts_as_unread,
                     current_status, current_status_at)
                values (?, ?, ?, 'inbound', 'text', 'hello', ?, ?, true, 'delivered', ?)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, accountId);
            statement.setString(3, providerMessageId);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.setLong(5, sequence);
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertRole(Connection connection, String code) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into roles (code, display_name) values (?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, code);
            statement.executeUpdate();
        }
    }

    private long countRole(Database database, String code) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from roles where code = ?")) {
                statement.setString(1, code);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            }
        });
    }

    private static Config databaseConfig(PostgreSQLContainer<?> postgres) throws Exception {
        Path passwordFile = Files.createTempFile("message-center-db-password", ".txt");
        Files.writeString(passwordFile, postgres.getPassword(), StandardCharsets.UTF_8);
        passwordFile.toFile().deleteOnExit();
        String jdbcUrl = postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "ApplicationName=message-center-schema-it";
        return new Config(Map.of(
                "DATABASE_URL", jdbcUrl,
                "DATABASE_USER", postgres.getUsername(),
                "DATABASE_PASSWORD_FILE", passwordFile.toString()
        ));
    }

    private static void assertTables(Database database, String... tableNames) throws Exception {
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select to_regclass(?)")) {
                for (String tableName : tableNames) {
                    statement.setString(1, "public." + tableName);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertNotNull(result.getString(1), tableName);
                    }
                }
            }
            return null;
        });
    }

    private static void assertIndex(Database database, String indexName) throws Exception {
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select 1 from pg_indexes
                    where schemaname = 'public' and indexname = ?
                    """)) {
                statement.setString(1, indexName);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), indexName);
                }
            }
            return null;
        });
    }

    private static void assertSqlState(String expected, Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && expected.equals(sqlException.getSQLState())) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected SQL state " + expected, failure);
    }

    private record Fixture(UUID firstContactId, UUID secondContactId, UUID firstAccountId,
                           UUID secondAccountId, UUID conversationId) {}
}
