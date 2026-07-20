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
    private static UUID otherUserId;

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
        otherUserId = UUID.randomUUID();
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
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into users (id,username,username_normalized,password_hash,display_name)
                    values (?,?,?,?,?)
                    """)) {
                statement.setObject(1, otherUserId);
                statement.setString(2, "other-it");
                statement.setString(3, "other-it");
                statement.setString(4, "fixture-only");
                statement.setString(5, "Other IT");
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
        assertTrue(notes.listByContact(otherUserId, contact, null, 20).isEmpty());
        companies.unlinkContact(company, contact, userId);

        assertTrue(companies.detailsForUser(userId, company).contacts().isEmpty());
        assertTrue(companies.detailsForUser(userId, company).conversationIds().isEmpty());
        assertEquals(noteId, notes.listByContact(userId, contact, null, 20).getFirst().id());

        UUID olderCompany = companies.create(new CompanyDraft("Older", null, null, null,
                null, userId, null), userId);
        UUID newerCompany = companies.create(new CompanyDraft("Newer", null, null, null,
                null, userId, null), userId);
        updateCompanyTime(company, Instant.parse("2026-07-01T00:00:00Z"));
        updateCompanyTime(olderCompany, Instant.parse("2026-07-02T00:00:00Z"));
        updateCompanyTime(newerCompany, Instant.parse("2026-07-03T00:00:00Z"));
        Company firstCompanyPage = companies.listForUser(userId,
                new CompanyQuery("", null, null, 1)).getFirst();
        assertEquals(newerCompany, firstCompanyPage.id());
        Company secondCompanyPage = companies.listForUser(userId,
                new CompanyQuery("", Instant.parse("2026-07-03T00:00:00Z"), newerCompany, 1)).getFirst();
        assertEquals(olderCompany, secondCompanyPage.id());

        UUID sameTimeA = companies.create(new CompanyDraft("Same A", null, null, null,
                null, userId, null), userId);
        UUID sameTimeB = companies.create(new CompanyDraft("Same B", null, null, null,
                null, userId, null), userId);
        Instant sameTime = Instant.parse("2026-07-04T00:00:00Z");
        updateCompanyTime(sameTimeA, sameTime);
        updateCompanyTime(sameTimeB, sameTime);
        Company sameTimeFirst = companies.listForUser(userId,
                new CompanyQuery("Same", null, null, 1)).getFirst();
        Company sameTimeSecond = companies.listForUser(userId,
                new CompanyQuery("Same", sameTime, sameTimeFirst.id(), 1)).getFirst();
        assertTrue(!sameTimeFirst.id().equals(sameTimeSecond.id()));

        assertNotNull(companies.create(new CompanyDraft("Unassigned", null, null, null,
                null, null, null), null));
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

    private static void updateCompanyTime(UUID companyId, Instant updatedAt) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "update companies set updated_at=? where id=?")) {
                statement.setTimestamp(1, java.sql.Timestamp.from(updatedAt));
                statement.setObject(2, companyId);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
