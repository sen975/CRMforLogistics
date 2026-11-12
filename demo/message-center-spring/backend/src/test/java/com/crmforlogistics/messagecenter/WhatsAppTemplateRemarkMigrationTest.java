package com.crmforlogistics.messagecenter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WhatsAppTemplateRemarkMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v11AddsBoundedNullableRemarkThatSyncUpsertDoesNotOverwrite() {
        String schema = "template_remark_v11";
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("10")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID accountId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        Instant providerUpdatedAt = Instant.parse("2026-08-12T00:00:00Z");
        Instant lastSyncedAt = Instant.parse("2026-08-12T00:01:00Z");

        jdbc.update("insert into " + schema + ".channel_accounts "
                        + "(id, channel_type, name, account_identifier, account_identifier_normalized, "
                        + "auth_status, encrypted_config) values (?, 'whatsapp', 'Remark test', ?, ?, "
                        + "'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        jdbc.update("insert into " + schema + ".message_templates "
                        + "(id, channel_account_id, provider_template_id, language_code, name, body, status, "
                        + "provider_updated_at, last_synced_at) "
                        + "values (?, ?, 'shipping_notice', 'zh_CN', 'Shipping Notice', 'Old body', 'APPROVED', "
                        + "?, ?)",
                templateId, accountId, Timestamp.from(providerUpdatedAt), Timestamp.from(lastSyncedAt));

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        assertThat(jdbc.queryForObject("select remark from " + schema
                + ".message_templates where id = ?", String.class, templateId)).isNull();
        String maxLengthRemark = "x".repeat(120);
        jdbc.update("update " + schema + ".message_templates set remark = ? where id = ?",
                maxLengthRemark, templateId);
        assertThat(jdbc.queryForObject("select remark from " + schema
                + ".message_templates where id = ?", String.class, templateId)).isEqualTo(maxLengthRemark);
        assertThatThrownBy(() -> jdbc.update("update " + schema
                        + ".message_templates set remark = ? where id = ?", "x".repeat(121), templateId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update " + schema + ".message_templates set remark = ? where id = ?", "发货提醒", templateId);

        jdbc.update("insert into " + schema + ".message_templates "
                        + "(id, channel_account_id, provider_template_id, language_code, name, body, status, "
                        + "provider_updated_at, last_synced_at, created_at, updated_at) "
                        + "values (gen_random_uuid(), ?, 'shipping_notice', 'zh_CN', 'Shipping Notice Updated', "
                        + "'New body', 'APPROVED', ?, ?, now(), now()) "
                        + "on conflict (channel_account_id, provider_template_id, language_code) do update set "
                        + "name = excluded.name, body = coalesce(nullif(excluded.body, ''), " + schema
                        + ".message_templates.body), status = excluded.status, "
                        + "provider_updated_at = excluded.provider_updated_at, "
                        + "last_synced_at = excluded.last_synced_at, "
                        + "updated_at = now()",
                accountId, Timestamp.from(providerUpdatedAt.plusSeconds(60)),
                Timestamp.from(lastSyncedAt.plusSeconds(60)));

        assertThat(jdbc.queryForObject("select remark from " + schema
                + ".message_templates where id = ?", String.class, templateId)).isEqualTo("发货提醒");
    }
}
