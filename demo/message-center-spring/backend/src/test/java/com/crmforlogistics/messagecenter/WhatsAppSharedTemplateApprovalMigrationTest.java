package com.crmforlogistics.messagecenter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppSharedTemplateApprovalMigrationTest {

    @Test
    void v49SqlOwnsTheSharedTemplateApprovalSchemaWithoutDestructiveCleanup() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V49__whatsapp_shared_template_approval.sql"));
        String normalized = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("CREATE TABLE WHATSAPP_PROVIDER_SCOPES")
                .contains("CREATE TABLE TEMPLATE_CHANGE_REQUESTS")
                .contains("CREATE TABLE TEMPLATE_MEDIA_BINDINGS")
                .contains("CREATE TABLE WHATSAPP_TEMPLATE_MIGRATION_STATE")
                .contains("CREATE TABLE WHATSAPP_TEMPLATE_MIGRATION_EXCEPTIONS")
                .contains("OCTET_LENGTH(REQUESTED_PAYLOAD_JSONB::TEXT) <= 65536")
                .contains("UNIQUE (REQUESTED_BY_USER_ID, IDEMPOTENCY_KEY)")
                .doesNotMatch("(?s).*\\bDELETE\\s+FROM\\b.*")
                .doesNotMatch("(?s).*\\bTRUNCATE\\b.*")
                .doesNotMatch("(?s).*\\bDROP\\s+TABLE\\b.*");
    }

    @Test
    void v49DefinesSharedTemplateApprovalAndMigrationGuardContracts() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
                .withDatabaseName("message_center")
                .withUsername("test")
                .withPassword("test")) {
            postgres.start();
            String schema = "whatsapp_shared_template_v49";
            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("48")).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            UUID userId = insertUser(jdbc, schema, "template-requester");
            UUID otherUserId = insertUser(jdbc, schema, "template-requester-two");
            // ux_channel_accounts_owner_unique_active allows one active chatapp account per owner,
            // so the second account belongs to a different user.
            UUID accountOneId = insertAccount(jdbc, schema, userId, "whatsapp-one");
            UUID accountTwoId = insertAccount(jdbc, schema, otherUserId, "whatsapp-two");
            UUID templateOneId = insertTemplate(jdbc, schema, accountOneId, "provider-template", "zh_CN");
            UUID templateTwoId = insertTemplate(jdbc, schema, accountTwoId, "provider-template", "zh_CN");

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("49")).load().migrate();

            assertThat(columns(jdbc, schema, "channel_accounts")).contains("provider_scope_id");
            assertThat(columns(jdbc, schema, "message_templates"))
                    .contains("provider_scope_id", "created_by_user_id");
            assertThat(columns(jdbc, schema, "template_operations"))
                    .contains("template_id", "change_request_id");
            assertThat(columns(jdbc, schema, "template_change_requests"))
                    .contains("requested_via_account_id", "base_version", "requested_payload_jsonb");

            UUID scopeId = jdbc.queryForObject("insert into " + schema
                    + ".whatsapp_provider_scopes (provider, external_scope_id) "
                    + "values ('ALIYUN_CAMS', 'cust-space-one') returning id", UUID.class);
            jdbc.update("update " + schema + ".channel_accounts set provider_scope_id = ? where id in (?, ?)",
                    scopeId, accountOneId, accountTwoId);
            jdbc.update("update " + schema + ".message_templates set provider_scope_id = ? where id = ?",
                    scopeId, templateOneId);

            assertThatThrownBy(() -> jdbc.update("update " + schema
                    + ".message_templates set provider_scope_id = ? where id = ?", scopeId, templateTwoId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("delete from " + schema + ".message_templates where id = ?", templateTwoId);

            assertThatThrownBy(() -> insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "UNKNOWN", "PENDING_APPROVAL", "{}", "invalid-type", null))
                    .isInstanceOf(DataIntegrityViolationException.class);

            UUID firstRequestId = insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "MODIFY", "PENDING_APPROVAL", "{}", "same-key", null);
            assertThatThrownBy(() -> insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "DELETE", "PENDING_APPROVAL", "{}", "same-key", null))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("update " + schema + ".template_change_requests set status = 'EXECUTING' where id = ?",
                    firstRequestId);
            assertThatThrownBy(() -> insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "SET_SEND_PERMISSION", "EXECUTING", "{}", "second-execution", null))
                    .isInstanceOf(DataIntegrityViolationException.class);

            insertOperation(jdbc, schema, templateOneId, accountOneId, "processing-one");
            assertThatThrownBy(() -> insertOperation(jdbc, schema, templateOneId, accountOneId, "processing-two"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            String oversizedPayload = "{\"value\":\"" + "x".repeat(65_536) + "\"}";
            assertThatThrownBy(() -> insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "BIND_MEDIA", "PENDING_APPROVAL", oversizedPayload, "oversized", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertChangeRequest(jdbc, schema, templateOneId, userId, accountOneId,
                    "DELETE", "REJECTED", "{}", "missing-reason", "  "))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("insert into " + schema + ".whatsapp_template_migration_state "
                    + "(migration_key, status, provider_scope_id) values ('shared-template-v1', 'BLOCKED', ?)",
                    scopeId);
            UUID exceptionId = jdbc.queryForObject("insert into " + schema
                    + ".whatsapp_template_migration_exceptions "
                    + "(migration_key, resource_type, resource_id, reason_code) "
                    + "values ('shared-template-v1', 'MESSAGE_TEMPLATE', ?, 'DUPLICATE_CONFLICT') returning id",
                    UUID.class, templateOneId);
            jdbc.update("update " + schema + ".whatsapp_template_migration_state "
                    + "set status = 'READY' where migration_key = 'shared-template-v1'");
            assertThat(jdbc.queryForObject("select count(*) from " + schema
                    + ".whatsapp_template_migration_exceptions where id = ?", Integer.class, exceptionId))
                    .isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("delete from " + schema
                    + ".whatsapp_template_migration_state where migration_key = 'shared-template-v1'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private UUID insertUser(JdbcTemplate jdbc, String schema, String username) {
        return jdbc.queryForObject("insert into " + schema + ".users "
                        + "(username, username_normalized, password_hash, display_name) "
                        + "values (?, ?, 'hash', 'Template User') returning id",
                UUID.class, username, username);
    }

    private UUID insertAccount(JdbcTemplate jdbc, String schema, UUID ownerId, String identifier) {
        return jdbc.queryForObject("insert into " + schema + ".channel_accounts "
                        + "(channel_type, name, account_identifier, account_identifier_normalized, "
                        + "auth_status, encrypted_config, owner_user_id) "
                        + "values ('chatapp', ?, ?, ?, 'active', '{}'::jsonb, ?) returning id",
                UUID.class, identifier, identifier, identifier, ownerId);
    }

    private UUID insertTemplate(JdbcTemplate jdbc, String schema, UUID accountId,
                                String providerTemplateId, String languageCode) {
        return jdbc.queryForObject("insert into " + schema + ".message_templates "
                        + "(channel_account_id, provider_template_id, language_code, name, body, status) "
                        + "values (?, ?, ?, 'shared-template', 'Hello', 'APPROVED') returning id",
                UUID.class, accountId, providerTemplateId, languageCode);
    }

    private UUID insertChangeRequest(JdbcTemplate jdbc, String schema, UUID templateId, UUID userId,
                                     UUID accountId, String changeType, String status, String payload,
                                     String idempotencyKey, String reviewReason) {
        return jdbc.queryForObject("insert into " + schema + ".template_change_requests "
                        + "(template_id, change_type, requested_payload_jsonb, base_version, "
                        + "requested_by_user_id, requested_via_account_id, status, idempotency_key, "
                        + "review_reason) values (?, ?, ?::jsonb, 0, ?, ?, ?, ?, ?) returning id",
                UUID.class, templateId, changeType, payload, userId, accountId, status, idempotencyKey,
                reviewReason);
    }

    private void insertOperation(JdbcTemplate jdbc, String schema, UUID templateId, UUID accountId,
                                 String idempotencyKey) {
        jdbc.update("insert into " + schema + ".template_operations "
                        + "(channel_account_id, idempotency_key, operation_type, language_code, "
                        + "requested_snapshot_jsonb, operation_status, template_id) "
                        + "values (?, ?, 'MODIFY', 'zh_CN', '{}'::jsonb, 'PROCESSING', ?)",
                accountId, idempotencyKey, templateId);
    }

    private List<String> columns(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForList("select column_name from information_schema.columns "
                        + "where table_schema = ? and table_name = ? order by ordinal_position",
                String.class, schema, table);
    }
}
