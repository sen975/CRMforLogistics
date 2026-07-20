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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcMessageRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;
    private static UUID userId;
    private static UUID accountId;
    private static UUID identityId;
    private static UUID contactId;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-message-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        database = Database.open(new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString())));
        database.migrate();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into users (id,username,username_normalized,password_hash,display_name)
                    values (?,?,?,?,?)
                    """)) {
                statement.setObject(1, userId);
                statement.setString(2, "message-it");
                statement.setString(3, "message-it");
                statement.setString(4, "fixture-only");
                statement.setString(5, "Message IT");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_accounts
                        (id,channel_type,name,account_identifier,account_identifier_normalized,encrypted_config)
                    values (?, 'chatapp', 'Fixture', 'fixture', 'fixture', '{}'::jsonb)
                    """)) {
                statement.setObject(1, accountId);
                statement.executeUpdate();
            }
            return null;
        });
        ContactRepository contacts = new JdbcContactRepository(database);
        contactId = contacts.create("Buyer", userId);
        identityId = contacts.attachIdentity(contactId,
                new ContactIdentityDraft("whatsapp", "global", "8613800000000", "Buyer"));
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void providerAndClientIdentifiersAreIdempotentAndOldStatusCannotRegress() throws Exception {
        MessageRepository messages = new JdbcMessageRepository(database);
        UUID conversation = messages.getOrCreateConversation(accountId, identityId);
        assign(conversation, userId);
        Instant occurredAt = Instant.parse("2026-07-01T01:00:00Z");
        MessageDraft firstDraft = new MessageDraft(conversation, accountId, null,
                "provider-1", null, "inbound", "text", null,
                "hello", null, occurredAt, true, null);

        MessageWriteResult first = messages.insert(firstDraft);
        MessageWriteResult duplicate = messages.insert(firstDraft);
        MessageWriteResult clientOne = messages.insert(new MessageDraft(conversation, accountId, null,
                null, "client-1", "outbound", "text", null, "one", null,
                occurredAt.plusSeconds(1), false, userId));
        MessageWriteResult clientTwo = messages.insert(new MessageDraft(conversation, accountId, null,
                null, "client-2", "outbound", "text", null, "two", null,
                occurredAt.plusSeconds(2), false, userId));
        MessageWriteResult clientOneReplay = messages.insert(new MessageDraft(conversation, accountId, null,
                null, "client-1", "outbound", "text", null, "changed", null,
                occurredAt.plusSeconds(30), false, userId));

        assertEquals(first.messageId(), duplicate.messageId());
        assertFalse(duplicate.inserted());
        assertFalse(clientOne.messageId().equals(clientTwo.messageId()));
        assertEquals(clientOne.messageId(), clientOneReplay.messageId());
        assertFalse(clientOneReplay.inserted());

        messages.appendStatus(clientOne.messageId(),
                new MessageStatusEvent("delivered", occurredAt.plusSeconds(10), "status-new", null, null));
        messages.appendStatus(clientOne.messageId(),
                new MessageStatusEvent("sent", occurredAt.plusSeconds(5), "status-old", null, null));
        messages.appendStatus(clientOne.messageId(),
                new MessageStatusEvent("read", occurredAt.plusSeconds(20), "status-new", null, null));

        List<UnifiedMessage> thread = messages.thread(userId, conversation, null, 20);
        assertEquals(List.of("hello", "one", "two"), thread.stream().map(message -> message.bodyText).toList());
        UnifiedMessage outbound = thread.stream().filter(message -> clientOne.messageId().toString().equals(message.id)).findFirst().orElseThrow();
        assertEquals("delivered", outbound.status);
        assertFalse(outbound.countsAsUnread);

        setReadSequence(conversation, userId, 1L);
        MessageWriteResult late = messages.insert(new MessageDraft(conversation, accountId, null,
                "provider-late", null, "inbound", "text", null, "late", null,
                occurredAt.minusSeconds(60), true, null));
        UnifiedMessage lateProjection = messages.thread(userId, conversation, null, 20).stream()
                .filter(message -> late.messageId().toString().equals(message.id)).findFirst().orElseThrow();
        assertEquals(4L, lateProjection.ingestSequence);
        assertTrue(lateProjection.unread);

        JdbcContactRepository contactRepository = new JdbcContactRepository(database);
        contactRepository.updateProfile(contactId, "Buyer Alias", "keep remark", List.of("VIP"), userId);
        UnifiedMessageStore store = new UnifiedMessageStore(contactRepository, messages, userId);
        assertEquals(4, store.thread(contactId.toString()).size());
        assertEquals(clientOne.messageId().toString(), store.findMessage(clientOne.messageId().toString()).id);
        UnifiedContact projectedContact = store.contacts().getFirst();
        assertEquals(contactId.toString(), projectedContact.id);
        assertEquals("two", projectedContact.lastText);
        assertEquals(4, projectedContact.messageCount);
        assertEquals(List.of("VIP"), projectedContact.tags);
        assertEquals(identityId.toString(), projectedContact.points.getFirst().id);
        assertTrue(store.contactGroup(contactId.toString()).contains(identityId.toString()));

        store.updateContactRemark(contactId.toString(), "new remark");
        store.updateContactProfile(contactId.toString(), "Buyer Updated", List.of("VIP", "Hot"));
        UnifiedContact updated = store.contacts().getFirst();
        assertEquals("Buyer Updated", updated.displayName);
        assertEquals("new remark", updated.remark);
        assertEquals(List.of("Hot", "VIP"), updated.tags);

        List<UnifiedMessage> latestTwo = messages.thread(userId, conversation, null, 2);
        assertEquals(List.of("one", "two"), latestTwo.stream().map(message -> message.bodyText).toList());
        UnifiedMessage firstLatest = latestTwo.getFirst();
        List<UnifiedMessage> older = messages.thread(userId, conversation,
                new MessageCursor(Instant.parse(firstLatest.timestamp), UUID.fromString(firstLatest.id)), 2);
        assertEquals(List.of("late", "hello"), older.stream().map(message -> message.bodyText).toList());

        UUID newerContact = contactRepository.create("Newer Buyer", userId);
        UUID newerIdentity = contactRepository.attachIdentity(newerContact,
                new ContactIdentityDraft("whatsapp", "global", "8613900000000", "Newer Buyer"));
        UUID newerConversation = messages.getOrCreateConversation(accountId, newerIdentity);
        assign(newerConversation, userId);
        messages.insert(new MessageDraft(newerConversation, accountId, null,
                "provider-newer-contact", null, "inbound", "text", null, "newest", null,
                occurredAt.plusSeconds(100), true, null));
        UnifiedContact firstContactPage = contactRepository.listForUser(userId,
                new ContactQuery("", null, null, 1)).getFirst();
        assertEquals(newerContact.toString(), firstContactPage.id);
        UnifiedContact secondContactPage = contactRepository.listForUser(userId,
                new ContactQuery("", Instant.parse(firstContactPage.lastTime), UUID.fromString(firstContactPage.id), 1)).getFirst();
        assertEquals(contactId.toString(), secondContactPage.id);
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

    private static void setReadSequence(UUID conversationId, UUID readerId, long sequence) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into conversation_read_states (conversation_id,user_id,last_read_sequence)
                    values (?,?,?) on conflict (conversation_id,user_id)
                    do update set last_read_sequence=excluded.last_read_sequence
                    """)) {
                statement.setObject(1, conversationId);
                statement.setObject(2, readerId);
                statement.setLong(3, sequence);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
