package com.crmforlogistics.messagecenter.mapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AssistantContactCandidateWindowMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center").withUsername("test").withPassword("test");

    @Test
    void migrationBoundsOneWindowPerOwnerAndRejectsOversizedReferences() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        for (UUID id : new UUID[]{owner, other}) {
            jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                    + "values (?::uuid, ?, ?, 'x', 'test')", id, id.toString(), id.toString());
        }
        String insert = "insert into assistant_contact_candidate_windows "
                + "(user_id, conversation_id, references_json, saved_at, expires_at) "
                + "values (?::uuid, ?::uuid, ?::jsonb, now(), now() + interval '30 minutes')";
        jdbc.update(insert, owner, conversation, "[\"CONTACT:11111111-1111-4111-8111-111111111111\"]");
        jdbc.update(insert, other, conversation, "[\"CONTACT:22222222-2222-4222-8222-222222222222\"]");
        assertThat(jdbc.queryForObject("select count(*) from assistant_contact_candidate_windows "
                + "where conversation_id=?::uuid", Integer.class, conversation)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(insert, owner, UUID.randomUUID(), "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, UUID.randomUUID(), "[" + "\"x\",".repeat(20) + "\"x\"]"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, conversation,
                "[\"CONTACT:33333333-3333-4333-8333-333333333333\"]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
