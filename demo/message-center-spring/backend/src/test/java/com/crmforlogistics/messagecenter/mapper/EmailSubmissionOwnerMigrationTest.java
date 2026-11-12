package com.crmforlogistics.messagecenter.mapper;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class EmailSubmissionOwnerMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center").withUsername("test").withPassword("test");

    @Test
    void backfillsOwnerForSubmissionsCreatedBeforeOwnerColumn() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("93")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) " +
                "values (?::uuid, 'owner', 'owner', 'x', 'owner')", ownerId);
        jdbc.update("insert into channel_accounts " +
                        "(id, owner_user_id, channel_type, name, account_identifier, " +
                        "account_identifier_normalized, auth_status, sync_status, encrypted_config) " +
                        "values (?::uuid, ?::uuid, 'email', 'email', 'owner@example.test', " +
                        "'owner@example.test', 'active', 'idle', '{}'::jsonb)", accountId, ownerId);
        jdbc.update("insert into email_submissions " +
                        "(id, channel_account_id, recipient, subject, status) " +
                        "values (?::uuid, ?::uuid, 'recipient@example.test', 'subject', 'UNKNOWN')",
                submissionId, accountId);

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();

        assertThat(jdbc.queryForObject("select owner_user_id from email_submissions where id=?::uuid",
                UUID.class, submissionId)).isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("select lease_token from email_submissions where id=?::uuid",
                UUID.class, submissionId)).isNotNull();
        assertThat(jdbc.queryForObject("select lease_expires_at from email_submissions where id=?::uuid",
                java.time.OffsetDateTime.class, submissionId)).isNotNull();
    }
}
