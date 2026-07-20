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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcCompanyPhoneNoteRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;
    private static UUID userId;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-company-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        database = Database.open(new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString())));
        database.migrate();
        userId = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into users (id,username,username_normalized,password_hash,display_name)
                    values (?,?,?,?,?)
                    """)) {
                statement.setObject(1, userId);
                statement.setString(2, "company-it");
                statement.setString(3, "company-it");
                statement.setString(4, "fixture-only");
                statement.setString(5, "Company IT");
                statement.executeUpdate();
            }
            return null;
        });
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void companyRelationshipControlsProjectionWithoutDeletingNotes() throws Exception {
        ContactRepository contacts = new JdbcContactRepository(database);
        CompanyRepository companies = new JdbcCompanyRepository(database);
        PhoneNoteRepository notes = new JdbcPhoneNoteRepository(database);
        UUID contact = contacts.create("Buyer", userId);
        UUID phoneIdentity = contacts.attachIdentity(contact,
                new ContactIdentityDraft("phone", "global", "+86 138 0000 0000", "Buyer"));
        UUID account = insertAccount();
        UUID conversation = new JdbcMessageRepository(database).getOrCreateConversation(account, phoneIdentity);
        assign(conversation, userId);
        UUID company = companies.create(new CompanyDraft("Acme", "customer", "CN", "Shanghai",
                null, userId, "priority"), userId);

        assertTrue(companies.detailsForUser(userId, company).contacts().isEmpty());
        assertTrue(companies.detailsForUser(userId, company).conversationIds().isEmpty());
        companies.linkContact(company, contact, "buyer", true, "primary", userId);
        CompanyDetails linked = companies.detailsForUser(userId, company);
        assertEquals(contact, linked.contacts().getFirst().contactId());
        assertEquals(conversation, linked.conversationIds().getFirst());
        assertNotNull(companies.findForUser(userId, company));

        UUID noteId = notes.create(new PhoneNoteDraft(contact, company, phoneIdentity,
                Instant.parse("2026-07-01T01:00:00Z"), "Called buyer", "Send quote"), userId);
        companies.unlinkContact(company, contact, userId);

        assertTrue(companies.detailsForUser(userId, company).contacts().isEmpty());
        assertTrue(companies.detailsForUser(userId, company).conversationIds().isEmpty());
        assertEquals(noteId, notes.listByContact(userId, contact, null, 20).getFirst().id());
    }

    private static UUID insertAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_accounts
                        (id,channel_type,name,account_identifier,account_identifier_normalized,encrypted_config)
                    values (?, 'chatapp', 'Company Fixture', ?, ?, '{}'::jsonb)
                    """)) {
                statement.setObject(1, accountId);
                statement.setString(2, accountId.toString());
                statement.setString(3, accountId.toString());
                statement.executeUpdate();
            }
            return null;
        });
        return accountId;
    }

    private static void assign(UUID conversationId, UUID assigneeId) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "update conversations set assigned_user_id=? where id=?")) {
                statement.setObject(1, assigneeId);
                statement.setObject(2, conversationId);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
