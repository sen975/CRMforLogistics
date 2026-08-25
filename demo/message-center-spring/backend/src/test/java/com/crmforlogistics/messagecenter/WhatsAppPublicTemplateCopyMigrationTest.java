package com.crmforlogistics.messagecenter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppPublicTemplateCopyMigrationTest {

    @Test
    void v18RetiresHistoricalCopyOperationsWithoutRewritingTheirHistory() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
                .withDatabaseName("message_center")
                .withUsername("test")
                .withPassword("test")) {
            postgres.start();
            String schema = "public_template_copy_v18";
            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("17")).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID accountId = UUID.randomUUID();
            Instant startedAt = Instant.parse("2026-08-15T01:02:03.123456Z");
            Instant completedAt = Instant.parse("2026-08-15T01:03:04.654321Z");
            Instant nextReconcileAt = Instant.parse("2026-08-15T02:00:00Z");
            Instant leaseUntil = Instant.parse("2026-08-15T02:05:00Z");

            jdbc.update("insert into " + schema + ".channel_accounts "
                        + "(id, channel_type, name, account_identifier, account_identifier_normalized, "
                        + "auth_status, encrypted_config) values (?, 'chatapp', 'Copy migration test', ?, ?, "
                        + "'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());

            UUID preservedOperationId = UUID.randomUUID();
            jdbc.update("insert into " + schema + ".template_operations "
                        + "(id, channel_account_id, idempotency_key, operation_type, language_code, "
                        + "requested_snapshot_jsonb, operation_status, provider_request_id, provider_code, "
                        + "error_code, error_message, next_reconcile_at, lease_owner, lease_until, started_at, "
                        + "completed_at) values (?, ?, 'copy-history-preserved', 'COPY', 'zh_CN', "
                        + "'{\"source\":\"public-template\",\"version\":7}'::jsonb, 'SUBMISSION_UNKNOWN', "
                        + "'provider-request-42', 'UPSTREAM_PENDING', 'EXISTING_ERROR', 'Existing error detail', "
                        + "?, 'copy-worker', ?, ?, ?)",
                preservedOperationId, accountId, Timestamp.from(nextReconcileAt), Timestamp.from(leaseUntil),
                Timestamp.from(startedAt), Timestamp.from(completedAt));
            UUID emptyErrorOperationId = UUID.randomUUID();
            jdbc.update("insert into " + schema + ".template_operations "
                        + "(id, channel_account_id, idempotency_key, operation_type, language_code, "
                        + "requested_snapshot_jsonb, operation_status, started_at) "
                        + "values (?, ?, 'copy-null-error-history', 'COPY', 'zh_CN', "
                        + "'{\"source\":\"public-template\"}'::jsonb, 'FAILED', ?)",
                emptyErrorOperationId, accountId, Timestamp.from(startedAt));

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

            Map<String, Object> preserved = jdbc.queryForMap("select operation_type, operation_status, "
                        + "provider_request_id, provider_code, error_code, error_message, next_reconcile_at, "
                        + "lease_owner, lease_until, started_at, completed_at, requested_snapshot_jsonb::text, "
                        + "idempotency_key from " + schema + ".template_operations where id = ?",
                preservedOperationId);
            assertThat(preserved)
                .containsEntry("operation_type", "RETIRED")
                .containsEntry("operation_status", "SUBMISSION_UNKNOWN")
                .containsEntry("provider_request_id", "provider-request-42")
                .containsEntry("provider_code", "UPSTREAM_PENDING")
                .containsEntry("error_code", "EXISTING_ERROR")
                .containsEntry("error_message", "Existing error detail")
                .containsEntry("requested_snapshot_jsonb", "{\"source\": \"public-template\", \"version\": 7}")
                .containsEntry("idempotency_key", "copy-history-preserved")
                .containsEntry("next_reconcile_at", null)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_until", null);
            assertThat(((Timestamp) preserved.get("started_at")).toInstant()).isEqualTo(startedAt);
            assertThat(((Timestamp) preserved.get("completed_at")).toInstant()).isEqualTo(completedAt);

            Map<String, Object> nullError = jdbc.queryForMap("select operation_type, error_code, error_message "
                        + "from " + schema + ".template_operations where id = ?", emptyErrorOperationId);
            assertThat(nullError)
                .containsEntry("operation_type", "RETIRED")
                .containsEntry("error_code", "OPERATION_RETIRED")
                .containsEntry("error_message", "旧公共模板复制路径已退役");

            Set<String> expectedOperationTypes = Set.of(
                    "CREATE", "MODIFY", "SET_SEND_PERMISSION", "DELETE", "RECONCILE", "RETIRED");
            for (String operationType : expectedOperationTypes) {
                assertThat(jdbc.update("insert into " + schema + ".template_operations "
                            + "(channel_account_id, idempotency_key, operation_type, language_code, "
                            + "requested_snapshot_jsonb, operation_status) values (?, ?, ?, 'zh_CN', "
                            + "'{}'::jsonb, 'PROCESSING')",
                        accountId, "allowed-" + operationType, operationType)).isEqualTo(1);
            }
            String constraintDefinition = jdbc.queryForObject(
                    "select pg_get_constraintdef(oid) from pg_constraint "
                            + "where conname = 'ck_template_operation_type' and conrelid = ?::regclass",
                    String.class, schema + ".template_operations");
            Matcher matcher = Pattern.compile("'([^']+)'").matcher(constraintDefinition == null ? "" : constraintDefinition);
            Set<String> constrainedTypes = new LinkedHashSet<>();
            while (matcher.find()) constrainedTypes.add(matcher.group(1));
            assertThat(constrainedTypes).containsExactlyInAnyOrderElementsOf(expectedOperationTypes);

            assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".template_operations "
                        + "(channel_account_id, idempotency_key, operation_type, language_code, "
                        + "requested_snapshot_jsonb, operation_status) "
                        + "values (?, 'copy-request-after-v18', 'COPY', 'zh_CN', "
                        + "'{}'::jsonb, 'PROCESSING')", accountId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".template_operations "
                        + "(channel_account_id, idempotency_key, operation_type, language_code, "
                        + "requested_snapshot_jsonb, operation_status) "
                        + "values (?, 'invalid-request-after-v18', 'UNKNOWN_OPERATION', 'zh_CN', "
                        + "'{}'::jsonb, 'PROCESSING')", accountId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void v18DoesNotContainDestructiveDataRemovalOrTableRewrite() throws IOException {
        String migrationSql = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V18__retire_whatsapp_public_template_copy.sql"));
        String normalizedSql = migrationSql.toUpperCase(Locale.ROOT);

        assertThat(normalizedSql)
                .doesNotMatch("(?s).*\\bDELETE\\s+FROM\\b.*")
                .doesNotMatch("(?s).*\\bTRUNCATE\\b.*")
                .doesNotMatch("(?s).*\\bVACUUM\\s+FULL\\b.*")
                .doesNotMatch("(?s).*\\bDROP\\s+TABLE\\b.*")
                .doesNotMatch("(?s).*\\bALTER\\s+TABLE\\s+TEMPLATE_OPERATIONS\\s+DROP\\s+COLUMN\\b.*")
                .doesNotMatch("(?s).*\\bALTER\\s+TABLE\\s+TEMPLATE_OPERATIONS\\s+RENAME\\b.*")
                .doesNotMatch("(?s).*\\bCREATE\\s+TABLE\\s+TEMPLATE_OPERATIONS\\b.*")
                .doesNotMatch("(?s).*\\bCREATE\\s+TABLE\\s+TEMPLATE_OPERATIONS\\s+AS\\b.*");
    }
}
