package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecentertest.mapper.ContactMapperChatAppFilterTestConfiguration;
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
@ContextConfiguration(classes = ContactMapperChatAppFilterTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ContactMapperTagSearchIntegrationTest {

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

    @Autowired ContactMapper contactMapper;
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
    void addressBookTagModeMatchesOnlyTaggedContacts() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP客户", userId);

        List<UUID> tagMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "客户", true, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();
        List<UUID> contactMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "客户", false, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();

        assertThat(tagMode).containsExactly(tagged);
        assertThat(contactMode).containsExactly(namedLikeTag);
    }

    @Test
    void contactListTagModeMatchesOnlyTaggedContacts() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP客户", userId);

        List<UUID> tagMode = idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "客户", true, null, null, false, null, null));
        List<UUID> contactMode = idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "客户", false, null, null, false, null, null));

        assertThat(tagMode).containsExactly(tagged);
        assertThat(contactMode).containsExactly(namedLikeTag);
    }

    @Test
    void addressBookTagModeIgnoresDisabledAndForeignTags() {
        UUID target = insertContact("李四", userId);
        attachTag(target, insertTag(userId, "停用标签", "disabled"));

        assertThat(contactMapper.listAddressBookByOwner(
                userId, "email", "标签", true, 20, 0)).isEmpty();

        UUID otherUser = insertUser("other");
        attachTag(target, insertTag(otherUser, "外部标签", "active"));

        assertThat(contactMapper.listAddressBookByOwner(
                userId, "email", "标签", true, 20, 0)).isEmpty();
    }

    @Test
    void contactListTagModeIgnoresDisabledAndForeignTags() {
        UUID target = insertContact("李四", userId);
        attachTag(target, insertTag(userId, "停用标签", "disabled"));

        assertThat(idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "标签", true, null, null, false, null, null))).isEmpty();

        UUID otherUser = insertUser("other");
        attachTag(target, insertTag(otherUser, "外部标签", "active"));

        assertThat(idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "标签", true, null, null, false, null, null))).isEmpty();
    }

    @Test
    void addressBookTagModeMatchesAiLabels() {
        UUID aiLabelled = insertContact("张经理", userId);
        insertAiLabel(aiLabelled, userId, "软件定制需求", "ACTIVE");
        UUID namedLikeLabel = insertContact("软件定制需求", userId);

        List<UUID> tagMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "定制", true, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();
        List<UUID> contactMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "定制", false, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();

        assertThat(tagMode).containsExactly(aiLabelled);
        assertThat(contactMode).containsExactly(namedLikeLabel);
    }

    @Test
    void contactListTagModeMatchesAiLabels() {
        UUID aiLabelled = insertContact("张经理", userId);
        insertAiLabel(aiLabelled, userId, "软件定制需求", "ACTIVE");

        assertThat(idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "定制", true, null, null, false, null, null))).containsExactly(aiLabelled);
    }

    @Test
    void tagModeIgnoresNonActiveAndForeignAiLabels() {
        UUID target = insertContact("李四", userId);
        insertAiLabel(target, userId, "停用AI标签", "STALE");
        insertAiLabel(target, userId, "作废AI标签", "INACTIVE");

        assertThat(contactMapper.listAddressBookByOwner(
                userId, "email", "AI标签", true, 20, 0)).isEmpty();

        UUID otherUser = insertUser("other");
        insertAiLabel(target, otherUser, "外部AI标签", "ACTIVE");

        assertThat(contactMapper.listAddressBookByOwner(
                userId, "email", "AI标签", true, 20, 0)).isEmpty();
    }

    private static List<UUID> idsOf(com.baomidou.mybatisplus.core.metadata.IPage<ContactEntity> page) {
        return page.getRecords().stream().map(ContactEntity::getId).toList();
    }

    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, prefix + id, prefix + id, prefix);
        return id;
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                + "identity_value, normalized_value) values (?, ?, 'email', 'global', ?, ?)",
                UUID.randomUUID(), id, name + id + "@example.com", name + id + "@example.com");
        return id;
    }

    private UUID insertTag(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_tags (id, owner_user_id, name, color, status) "
                + "values (?, ?, ?, 'blue', ?)", id, ownerId, name, status);
        return id;
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

    private void attachTag(UUID contactId, UUID tagId) {
        jdbc.update("insert into contact_taggings (contact_id, tag_id) values (?, ?)", contactId, tagId);
    }
}
