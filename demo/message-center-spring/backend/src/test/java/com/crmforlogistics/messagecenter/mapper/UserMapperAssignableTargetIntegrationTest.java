package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.mapper.UserMapperAssignableTargetTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule for who may hold a WhatsApp number lives entirely in the SQL of
 * {@link UserMapper#findAssignableUser}, so a Mockito test cannot pin it. Admins were excluded by an
 * earlier revision, which contradicted both the assignment migration (it hands numbers to whichever
 * owner the legacy column names) and the admin workbench, where an admin holding a number could not
 * hand it to another admin.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserMapperAssignableTargetTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class UserMapperAssignableTargetIntegrationTest {

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

    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table user_roles, users cascade");
    }

    @Test
    void activeAdminCanHoldANumber() {
        UUID adminId = insertUser("admin_holder");
        grantRole(adminId, "admin");

        assertThat(users.findAssignableUser(adminId)).isPresent();
    }

    @Test
    void activeAgentCanHoldANumber() {
        UUID agentId = insertUser("agent_holder");
        grantRole(agentId, "agent");

        assertThat(users.findAssignableUser(agentId)).isPresent();
    }

    @Test
    void nonActiveUserCannotHoldANumber() {
        UUID userId = insertUser("locked_holder");
        grantRole(userId, "agent");
        jdbc.update("update users set status = 'locked' where id = ?", userId);

        assertThat(users.findAssignableUser(userId)).isEmpty();
    }

    @Test
    void softDeletedUserCannotHoldANumber() {
        UUID userId = insertUser("deleted_holder");
        grantRole(userId, "agent");
        jdbc.update("update users set deleted_at = now() where id = ?", userId);

        assertThat(users.findAssignableUser(userId)).isEmpty();
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, name + id, name + id, name);
        return id;
    }

    private void grantRole(UUID userId, String code) {
        jdbc.update("insert into user_roles (user_id, role_id) "
                + "select ?, id from roles where code = ?", userId, code);
    }
}
