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
class AssistantConversationSummaryMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center").withUsername("test").withPassword("test");

    @Test
    void flywayCreatesOwnerScopedSummaryAndRejectsCrossOwnerCursor() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        for (UUID id : new UUID[]{owner, other}) {
            jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                    + "values (?::uuid, ?, ?, 'x', 'test')", id, id.toString(), id.toString());
        }
        jdbc.update("insert into assistant_conversation_messages "
                + "(id, user_id, conversation_id, role, text) values (?::uuid, ?::uuid, ?::uuid, 'user', 'hello')",
                message, owner, conversation);

        assertThatThrownBy(() -> jdbc.update("insert into assistant_conversation_summaries "
                        + "(user_id, conversation_id, summary, through_message_id, through_created_at, covered_message_count) "
                        + "values (?::uuid, ?::uuid, 'wrong owner', ?::uuid, now(), 1)",
                other, conversation, message)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into assistant_conversation_summaries "
                        + "(user_id, conversation_id, summary, through_message_id, through_created_at, covered_message_count) "
                        + "select user_id, conversation_id, 'goal', id, created_at, 1 "
                        + "from assistant_conversation_messages where id=?::uuid", message);
        assertThat(jdbc.queryForObject("select version from assistant_conversation_summaries "
                + "where user_id=?::uuid and conversation_id=?::uuid", Long.class, owner, conversation))
                .isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("insert into assistant_conversation_summaries "
                        + "(user_id, conversation_id, summary, through_message_id, through_created_at, covered_message_count) "
                        + "select user_id, conversation_id, 'duplicate', id, created_at, 1 "
                        + "from assistant_conversation_messages where id=?::uuid", message))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
