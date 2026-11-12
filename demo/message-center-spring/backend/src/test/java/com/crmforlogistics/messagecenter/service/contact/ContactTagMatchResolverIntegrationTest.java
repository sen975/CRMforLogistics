package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecentertest.mapper.ContactTagMapperTestConfiguration;
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
@ContextConfiguration(classes = ContactTagMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ContactTagMatchResolverIntegrationTest {

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

    @Autowired ContactTagMapper tagMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table contact_taggings, contact_tags, contacts, users cascade");
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        insertUser(ownerId, "owner");
        insertUser(otherUserId, "other");
    }

    @Test
    void returnsOnlyMatchedActiveTagsOfTheCurrentOwner() {
        // 标签名刻意用「A级客户」「B类客户」这种 ASCII 前缀：命中顺序断言要依赖
        // order by lower(t.name)，而中文与拉丁字母的相对顺序随数据库 collation 变化，
        // 用 ASCII 前缀才能在任何 collation 下稳定。
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "A级客户", "active"));
        attachTag(contactId, insertTag(ownerId, "B类客户", "active"));
        attachTag(contactId, insertTag(ownerId, "上海", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.TAG))
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of(contactId, List.of("A级客户", "B类客户")));
    }

    @Test
    void returnsNothingOutsideTagModeAndForBlankOrMissingInput() {
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "VIP客户", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.CONTACT))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "   ", SearchMode.TAG))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), null, SearchMode.TAG))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(), "客户", SearchMode.TAG))
                .isEmpty();
    }

    @Test
    void ignoresDisabledTagsAndTagsOwnedByAnotherUser() {
        UUID contactId = insertContact("李四", ownerId);
        attachTag(contactId, insertTag(ownerId, "停用客户", "disabled"));
        attachTag(contactId, insertTag(otherUserId, "客户", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.TAG))
                .isEmpty();
    }

    @Test
    void returnsMatchedAiLabelsAlongsideManualTags() {
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "A级客户", "active"));
        insertAiLabel(contactId, ownerId, "B类客户需求", "ACTIVE");
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.TAG))
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of(contactId, List.of("A级客户", "B类客户需求")));
    }

    @Test
    void collapsesAiLabelThatDuplicatesAManualTagName() {
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "VIP客户", "active"));
        insertAiLabel(contactId, ownerId, "VIP客户", "ACTIVE");
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "VIP", SearchMode.TAG))
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of(contactId, List.of("VIP客户")));
    }

    @Test
    void ignoresNonActiveAndForeignAiLabels() {
        UUID contactId = insertContact("李四", ownerId);
        insertAiLabel(contactId, ownerId, "停用AI标签", "STALE");
        insertAiLabel(contactId, otherUserId, "外部AI标签", "ACTIVE");
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "AI标签", SearchMode.TAG))
                .isEmpty();
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

    private void insertUser(UUID id, String name) {
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, name + id, name + id, name);
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
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
