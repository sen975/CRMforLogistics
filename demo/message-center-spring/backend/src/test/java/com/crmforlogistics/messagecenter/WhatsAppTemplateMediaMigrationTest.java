package com.crmforlogistics.messagecenter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WhatsAppTemplateMediaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v10BackfillsLegacyRowsAndEnforcesAccountScopedRequestIds() {
        String schema = "media_v10";
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("9")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID accountId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".channel_accounts "
                + "(id, channel_type, name, account_identifier, account_identifier_normalized, auth_status, encrypted_config) "
                + "values (?, 'chatapp', 'Media test', ?, ?, 'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, provider_object_key, provider_url, media_format, content_type, "
                + "size_bytes, sha256, asset_status, created_at) values (?, ?, 'legacy/key', "
                + "'https://provider.invalid/legacy', 'IMAGE', 'image/png', 4, ?, 'UPLOADED', ?)",
                assetId, accountId, "0".repeat(64), Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")));

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        Map<String, Object> row = jdbc.queryForMap("select client_request_id, started_at, updated_at "
                + "from " + schema + ".template_media_assets where id = ?", assetId);
        assertThat(row.get("client_request_id")).isEqualTo("legacy:" + assetId);
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                + "asset_status, started_at, created_at, updated_at) values (?, ?, ?, 'IMAGE', 'image/png', 4, ?, "
                + "'PROCESSING', now(), now(), now())", UUID.randomUUID(), accountId,
                "safe._~:-123", "1".repeat(64));
        jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                + "asset_status, started_at, created_at, updated_at) values (?, ?, ?, 'IMAGE', 'image/png', 4, ?, "
                + "'PROCESSING', now(), now(), now())", UUID.randomUUID(), accountId,
                "legacy:" + UUID.randomUUID(), "2".repeat(64));
        assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                + "asset_status, started_at, created_at, updated_at) values (?, ?, ?, 'IMAGE', 'image/png', 4, ?, "
                + "'PROCESSING', now(), now(), now())", UUID.randomUUID(), accountId,
                "order/123", "3".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
