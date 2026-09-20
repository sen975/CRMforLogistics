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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
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
class ContactMapperChatAppFilterIntegrationTest {

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
    private UUID foreignUserId;
    private UUID accountId;
    private UUID otherAccountId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table messages, conversation_access_grants, conversations, "
                + "contact_identities, contacts, channel_accounts, team_members, teams, user_roles, users cascade");
        userId = UUID.randomUUID();
        foreignUserId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        otherAccountId = UUID.randomUUID();
        insertUser(userId, "agent");
        insertUser(foreignUserId, "foreign");
        insertAccount(accountId, "60111111111");
        insertAccount(otherAccountId, "60122222222");
    }

    @Test
    void listPreservesGeneralContactsAndFiltersChatAppByScopeDeletionAndVisibility() {
        UUID phoneContact = insertContact("Phone", userId);
        insertIdentity(phoneContact, "phone", "global", "60100000001", false);
        UUID emailContact = insertContact("Email", userId);
        insertIdentity(emailContact, "email", "global", "email@example.com", false);
        UUID currentChatApp = insertContact("Current ChatApp", userId);
        insertIdentity(currentChatApp, "chatapp", accountId.toString(), "60100000002", false);
        UUID wrongScope = insertContact("Wrong Scope", userId);
        insertIdentity(wrongScope, "chatapp", otherAccountId.toString(), "60100000003", false);
        UUID deletedIdentity = insertContact("Deleted Identity", userId);
        insertIdentity(deletedIdentity, "chatapp", accountId.toString(), "60100000004", true);
        UUID inaccessible = insertContact("Inaccessible", foreignUserId);
        insertIdentity(inaccessible, "chatapp", accountId.toString(), "60100000005", false);

        List<UUID> generalIds = contactMapper.listForUser(new Page<>(1, 20), userId,
                        null, false, null, null, false, null, null)
                .getRecords().stream().map(ContactEntity::getId).toList();
        List<UUID> chatAppIds = contactMapper.listForUser(new Page<>(1, 20), userId,
                        null, false, null, null, false, "chatapp", accountId)
                .getRecords().stream().map(ContactEntity::getId).toList();

        assertThat(generalIds).contains(phoneContact, emailContact, currentChatApp,
                wrongScope, deletedIdentity).doesNotContain(inaccessible);
        assertThat(chatAppIds).containsExactly(currentChatApp);
    }

    @Test
    void sendAccessUsesAnyAccessibleConversationWithoutRequiringTargetConversation() {
        UUID contactId = insertContact("Shared contact", foreignUserId);
        UUID chatAppIdentityId = insertIdentity(contactId, "chatapp", accountId.toString(),
                "60100000006", false);
        UUID emailIdentityId = insertIdentity(contactId, "email", "global",
                "shared@example.com", false);
        insertConversation(otherAccountId, emailIdentityId, userId);

        assertThat(contactMapper.findAccessibleForChatAppSend(
                contactId, chatAppIdentityId, accountId, userId)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from conversations where "
                + "channel_account_id = ? and contact_identity_id = ?", Long.class,
                accountId, chatAppIdentityId)).isZero();
    }

    @Test
    void sendAccessRejectsContactWithoutOwnershipConversationGrantOrAdmin() {
        UUID contactId = insertContact("Foreign", foreignUserId);
        UUID chatAppIdentityId = insertIdentity(contactId, "chatapp", accountId.toString(),
                "60100000007", false);

        assertThat(contactMapper.findAccessibleForChatAppSend(
                contactId, chatAppIdentityId, accountId, userId)).isEmpty();
    }

    private void insertUser(UUID id, String name) {
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, name + id, name + id, name);
    }

    private void insertAccount(UUID id, String identifier) {
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'chatapp', 'ChatApp', ?, ?, 'active', '{}'::jsonb)",
                id, identifier, identifier);
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        return id;
    }

    private UUID insertIdentity(UUID contactId, String channelType, String scope,
                                String value, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value, deleted_at) values (?, ?, ?, ?, ?, ?, "
                        + "case when ? then now() else null end)",
                id, contactId, channelType, scope, value, value, deleted);
        return id;
    }

    private void insertConversation(UUID channelAccountId, UUID identityId, UUID assignedUserId) {
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id, "
                        + "assigned_user_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), channelAccountId, identityId, assignedUserId);
    }
}
