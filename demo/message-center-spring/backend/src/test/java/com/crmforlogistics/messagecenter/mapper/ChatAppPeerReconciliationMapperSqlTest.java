package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.mapper.ChatAppPeerReconciliationMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ChatAppPeerReconciliationMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ChatAppPeerReconciliationMapperSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired MessageMapper messageMapper;
    @Autowired ConversationMapper conversationMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;
    private UUID sourceConversationId;
    private UUID targetConversationId;
    private UUID movedMessageId;
    private UUID targetLatestMessageId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table message_status_events, attachments, messages, conversations, "
                + "contact_identities, contacts, channel_accounts cascade");
        accountId = insertAccount();
        UUID sourceIdentityId = insertIdentity("8613266259485");
        UUID targetIdentityId = insertIdentity("8613428277520");
        sourceConversationId = insertConversation(sourceIdentityId, 1L);
        targetConversationId = insertConversation(targetIdentityId, 1L);
        movedMessageId = insertMessage(sourceConversationId, 1L,
                "provider-moved", Instant.parse("2026-07-10T08:00:00Z"));
        targetLatestMessageId = insertMessage(targetConversationId, 1L,
                "provider-target", Instant.parse("2026-07-11T08:00:00Z"));
        jdbc.update("update conversations set last_message_id = ?, last_message_at = ? where id = ?",
                movedMessageId, Timestamp.from(Instant.parse("2026-07-10T08:00:00Z")), sourceConversationId);
        jdbc.update("update conversations set last_message_id = ?, last_message_at = ? where id = ?",
                targetLatestMessageId, Timestamp.from(Instant.parse("2026-07-11T08:00:00Z")), targetConversationId);
    }

    @Test
    void movesMessageAndRecomputesBothConversationProjections() {
        long targetSequence = conversationMapper.allocateNextIngestSequence(targetConversationId);

        assertThat(targetSequence).isEqualTo(2L);
        assertThat(messageMapper.updateConversationAndSequence(
                movedMessageId, sourceConversationId, targetConversationId, targetSequence))
                .isEqualTo(1);
        conversationMapper.recomputeProjection(sourceConversationId);
        conversationMapper.recomputeProjection(targetConversationId);

        Map<String, Object> moved = jdbc.queryForMap(
                "select conversation_id, ingest_sequence, provider_message_id, current_status "
                        + "from messages where id = ?", movedMessageId);
        assertThat(moved.get("conversation_id")).isEqualTo(targetConversationId);
        assertThat(((Number) moved.get("ingest_sequence")).longValue()).isEqualTo(2L);
        assertThat(moved.get("provider_message_id")).isEqualTo("provider-moved");
        assertThat(moved.get("current_status")).isEqualTo("delivered");

        Map<String, Object> source = jdbc.queryForMap(
                "select next_ingest_sequence, last_message_id, last_message_at "
                        + "from conversations where id = ?", sourceConversationId);
        assertThat(((Number) source.get("next_ingest_sequence")).longValue()).isEqualTo(1L);
        assertThat(source.get("last_message_id")).isNull();
        assertThat(source.get("last_message_at")).isNull();

        Map<String, Object> target = jdbc.queryForMap(
                "select next_ingest_sequence, last_message_id from conversations where id = ?",
                targetConversationId);
        assertThat(((Number) target.get("next_ingest_sequence")).longValue()).isEqualTo(2L);
        assertThat(target.get("last_message_id")).isEqualTo(targetLatestMessageId);
    }

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'chatapp', 'ChatApp', '8613266259485', "
                        + "'8613266259485', 'active', '{}'::jsonb)", id);
        return id;
    }

    private UUID insertIdentity(String number) {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name) values (?, ?)", contactId, number);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value, display_name, source) "
                        + "values (?, ?, 'chatapp', ?, ?, ?, ?, 'synced')",
                identityId, contactId, accountId.toString(), number, number, number);
        return identityId;
    }

    private UUID insertConversation(UUID identityId, long nextSequence) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id, "
                        + "next_ingest_sequence) values (?, ?, ?, ?)",
                id, accountId, identityId, nextSequence);
        return id;
    }

    private UUID insertMessage(UUID conversationId, long sequence, String providerMessageId,
                               Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into messages (id, conversation_id, channel_account_id, "
                        + "channel_account_version, provider_message_id, direction, message_kind, "
                        + "body_text, occurred_at, ingest_sequence, counts_as_unread, current_status, "
                        + "current_status_at) "
                        + "values (?, ?, ?, (select version from channel_accounts where id = ?), ?, "
                        + "'outbound', 'text', 'body', ?, ?, false, 'delivered', ?)",
                id, conversationId, accountId, accountId, providerMessageId,
                Timestamp.from(occurredAt), sequence, Timestamp.from(occurredAt));
        return id;
    }
}
