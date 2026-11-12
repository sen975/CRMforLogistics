package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecentertest.mapper.ConversationMapperAssignmentTestConfiguration;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ConversationMapperAssignmentTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ConversationMapperAssignmentIntegrationTest {

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

    private UUID accountId;
    private UUID identityId;
    private UUID actorId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table messages, conversation_access_grants, conversations, "
                + "contact_identities, contacts, channel_accounts, team_members, teams, "
                + "user_roles, users cascade");
        actorId = insertUser("actor");
        otherUserId = insertUser("other");
        accountId = insertAccount();
        identityId = insertIdentity();
    }

    @Test
    void createsConversationAssignedToSenderAndMakesItAccessible() {
        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                accountId, identityId, actorId);

        assertThat(conversation.getAssignedUserId()).isEqualTo(actorId);
        assertThat(conversation.getAssignedTeamId()).isNull();
        assertThat(conversationMapper.findAccessibleForMessage(
                conversation.getId(), accountId, actorId, false)).isNotNull();
    }

    @Test
    void claimsAnExistingFullyUnassignedConversation() {
        UUID conversationId = insertConversation(null, null);

        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                accountId, identityId, actorId);

        assertThat(conversation.getId()).isEqualTo(conversationId);
        assertThat(conversation.getAssignedUserId()).isEqualTo(actorId);
        assertThat(conversationMapper.findAccessibleForMessage(
                conversationId, accountId, actorId, false)).isNotNull();
    }

    @Test
    void preservesAnExistingUserAssignment() {
        UUID conversationId = insertConversation(otherUserId, null);

        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                accountId, identityId, actorId);

        assertThat(conversation.getId()).isEqualTo(conversationId);
        assertThat(conversation.getAssignedUserId()).isEqualTo(otherUserId);
        assertThat(conversationMapper.findAccessibleForMessage(
                conversationId, accountId, actorId, false)).isNull();
        assertThat(conversationMapper.findAccessibleForMessage(
                conversationId, accountId, otherUserId, false)).isNotNull();
    }

    @Test
    void preservesAnExistingTeamAssignment() {
        UUID teamId = insertTeam(actorId);
        UUID conversationId = insertConversation(null, teamId);

        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                accountId, identityId, actorId);

        assertThat(conversation.getId()).isEqualTo(conversationId);
        assertThat(conversation.getAssignedUserId()).isNull();
        assertThat(conversation.getAssignedTeamId()).isEqualTo(teamId);
        assertThat(conversationMapper.findAccessibleForMessage(
                conversationId, accountId, actorId, false)).isNotNull();
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, name + id, name + id, name);
        return id;
    }

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        String identifier = "601" + Math.abs(id.hashCode());
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'chatapp', 'ChatApp', ?, ?, 'active', '{}'::jsonb)",
                id, identifier, identifier);
        return id;
    }

    private UUID insertIdentity() {
        UUID contactId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, 'Contact', ?)",
                contactId, actorId);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'chatapp', ?, "
                        + "'60123456789', '60123456789')",
                id, contactId, accountId.toString());
        return id;
    }

    private UUID insertTeam(UUID memberId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into teams (id, name) values (?, 'Support')", id);
        jdbc.update("insert into team_members (team_id, user_id) values (?, ?)", id, memberId);
        return id;
    }

    private UUID insertConversation(UUID assignedUserId, UUID assignedTeamId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id, "
                        + "assigned_user_id, assigned_team_id) values (?, ?, ?, ?, ?)",
                id, accountId, identityId, assignedUserId, assignedTeamId);
        return id;
    }
}
