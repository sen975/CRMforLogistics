package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.mapper.ConversationMapperAssignmentTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ConversationMapperAssignmentTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ConversationMapperTagSearchIntegrationTest {

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

    @Autowired ConversationMapper conversationMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table contact_taggings, contact_tags, contact_identities, contacts, "
                + "users cascade");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'agent')", userId, "agent" + userId, "agent" + userId);
    }

    @Test
    void tagModeMatchesOnlyTaggedContactsAndIgnoresNameMatches() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP", userId);

        List<UUID> tagModeIds = idsOf(conversationMapper.listUnified(
                userId, "VIP", true, null, null, null, null, 20));
        List<UUID> contactModeIds = idsOf(conversationMapper.listUnified(
                userId, "VIP", false, null, null, null, null, 20));

        assertThat(tagModeIds).containsExactly(tagged);
        assertThat(contactModeIds).containsExactly(namedLikeTag);
    }

    @Test
    void tagModeIgnoresDisabledTagsAndTagsOwnedByAnotherUser() {
        UUID target = insertContact("李四", userId);
        attachTag(target, insertTag(userId, "停用客户", "disabled"));

        assertThat(idsOf(conversationMapper.listUnified(
                userId, "客户", true, null, null, null, null, 20))).isEmpty();

        UUID otherUser = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'other')", otherUser, "other" + otherUser,
                "other" + otherUser);
        attachTag(target, insertTag(otherUser, "客户", "active"));

        assertThat(idsOf(conversationMapper.listUnified(
                userId, "客户", true, null, null, null, null, 20))).isEmpty();
    }

    @Test
    void tagModeMatchesAiLabels() {
        UUID aiLabelled = insertContact("张经理", userId);
        insertAiLabel(aiLabelled, userId, "软件定制需求", "ACTIVE");
        UUID namedLikeLabel = insertContact("软件定制需求", userId);

        List<UUID> tagModeIds = idsOf(conversationMapper.listUnified(
                userId, "定制", true, null, null, null, null, 20));
        List<UUID> contactModeIds = idsOf(conversationMapper.listUnified(
                userId, "定制", false, null, null, null, null, 20));

        assertThat(tagModeIds).containsExactly(aiLabelled);
        assertThat(contactModeIds).containsExactly(namedLikeLabel);
    }

    @Test
    void tagModeIgnoresNonActiveAndForeignAiLabels() {
        UUID target = insertContact("李四", userId);
        insertAiLabel(target, userId, "停用AI标签", "STALE");

        UUID otherUser = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'other')", otherUser, "other" + otherUser,
                "other" + otherUser);
        insertAiLabel(target, otherUser, "外部AI标签", "ACTIVE");

        assertThat(idsOf(conversationMapper.listUnified(
                userId, "AI标签", true, null, null, null, null, 20))).isEmpty();
    }

    private UUID insertAiLabel(UUID contactId, UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_ai_labels (id, contact_id, owner_user_id, category, "
                + "normalized_name, display_name, color_token, status, confidence, first_seen_at, "
                + "last_seen_at, last_evidence_at, generation_batch_id) "
                + "values (?, ?, ?, 'NEED', ?, ?, 'blue', ?, 0.600, now(), now(), now(), "
                + "gen_random_uuid())",
                id, contactId, ownerId, name.toLowerCase(), name, status);
        return id;
    }

    private List<UUID> idsOf(List<ConversationMapper.UnifiedConversationRow> rows) {
        return rows.stream()
                .filter(row -> "CONTACT".equals(row.type()))
                .map(ConversationMapper.UnifiedConversationRow::id)
                .toList();
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                + "identity_value, normalized_value) values (?, ?, 'email', 'global', ?, ?)",
                UUID.randomUUID(), id, name + "@example.com", name + "@example.com");
        return id;
    }

    private UUID insertTag(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_tags (id, owner_user_id, name, color, status) "
                + "values (?, ?, ?, 'blue', ?)", id, ownerId, name, status);
        return id;
    }

    private void attachTag(UUID contactId, UUID tagId) {
        jdbc.update("insert into contact_taggings (contact_id, tag_id) values (?, ?)", contactId, tagId);
    }
}
