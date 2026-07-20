package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcContactRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-contact-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        database = Database.open(new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString())));
        database.migrate();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void mergeMovesIdentityWithoutRewritingContactIdentityIdAndSplitRestoresOwnership() throws Exception {
        ContactRepository contacts = new JdbcContactRepository(database);
        UUID actor = null;
        UUID target = contacts.create("Buyer", actor);
        UUID source = contacts.create("Buyer WA", actor);
        UUID identity = contacts.attachIdentity(source,
                new ContactIdentityDraft("whatsapp", "global", "8613800000000", "Buyer WA"));
        UUID account = insertAccount();
        MessageRepository messages = new JdbcMessageRepository(database);
        UUID conversation = messages.getOrCreateConversation(account, identity);
        UUID messageId = messages.insert(new MessageDraft(conversation, account, null,
                "merge-message", null, "inbound", "text", null, "hello", null,
                Instant.parse("2026-07-01T01:00:00Z"), true, null)).messageId();

        contacts.merge(target, source, actor);
        assertEquals(target, contacts.findIdentity(identity).contactId());
        assertTrue(messageExists(messageId));

        UUID split = contacts.splitIdentity(identity, "Buyer WA", actor);
        assertEquals(split, contacts.findIdentity(identity).contactId());
        assertTrue(messageExists(messageId));
        assertEquals(1, auditCount("contact.merge"));
        assertEquals(1, auditCount("contact.split"));
    }

    private static UUID insertAccount() throws Exception {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_accounts
                        (id,channel_type,name,account_identifier,account_identifier_normalized,encrypted_config)
                    values (?, 'chatapp', 'Merge Fixture', ?, ?, '{}'::jsonb)
                    """)) {
                statement.setObject(1, id);
                statement.setString(2, id.toString());
                statement.setString(3, id.toString());
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private static boolean messageExists(UUID messageId) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select exists(select 1 from messages where id=?)")) {
                statement.setObject(1, messageId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getBoolean(1);
                }
            }
        });
    }

    private static long auditCount(String action) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from audit_logs where action=?")) {
                statement.setString(1, action);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
}
