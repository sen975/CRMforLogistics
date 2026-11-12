package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.PostgresTestSchema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ContactMemorySchemaContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String schema = "contact_memory_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = PostgresTestSchema.dataSource(POSTGRES, schema);
        PostgresTestSchema.resetTrigramExtension(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsAllMemoryTablesAndCriticalConstraints() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = current_schema() and table_name in "
                        + "('contact_memory_states','contact_memory_observations',"
                        + "'contact_memory_observation_evidence','contact_memory_facts',"
                        + "'contact_memory_fact_evidence','contact_profile_versions',"
                        + "'contact_ai_labels','contact_ai_label_evidence','contact_memory_attempts')",
                Integer.class)).isEqualTo(9);
    }

    @Test
    void createsOwnerScopedStateAndSemanticUniquenessConstraints() {
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_constraint "
                        + "where connamespace = current_schema()::regnamespace "
                        + "and conname in ('uq_contact_memory_state_contact_owner', "
                        + "'uq_contact_memory_fact_semantic', 'uq_contact_ai_label_semantic')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void permitsOnlyOneCurrentProfilePerContactOwner() {
        UUID contactId = insertContact();
        UUID ownerId = ownerOf(contactId);

        insertProfile(contactId, ownerId, true);

        assertThatThrownBy(() -> insertProfile(contactId, ownerId, true))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidMemoryLifecycleState() {
        UUID contactId = insertContact();
        UUID ownerId = ownerOf(contactId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into contact_memory_states "
                        + "(contact_id, owner_user_id, status) values (?, ?, 'UNKNOWN')",
                contactId, ownerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aiLabelStorageIsSeparateFromManualTagStorage() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = current_schema() and table_name='contact_ai_labels' "
                        + "and column_name='source'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = current_schema() and table_name='contact_taggings'",
                Integer.class)).isEqualTo(1);
    }

    private UUID insertContact() {
        UUID ownerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        jdbc.update("insert into users "
                        + "(id, username, username_normalized, password_hash, display_name, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, 'active', ?, ?)",
                ownerId, "memory-owner-" + ownerId, ownerId.toString(), "test-hash",
                "Memory Owner", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into contacts "
                        + "(id, display_name, status, created_by, created_at, updated_at) "
                        + "values (?, 'Memory Contact', 'active', ?, ?, ?)",
                contactId, ownerId, Timestamp.from(now), Timestamp.from(now));
        return contactId;
    }

    private UUID ownerOf(UUID contactId) {
        return jdbc.queryForObject(
                "select created_by from contacts where id = ?", UUID.class, contactId);
    }

    private void insertProfile(UUID contactId, UUID ownerId, boolean current) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        jdbc.update("insert into contact_profile_versions "
                        + "(id, contact_id, owner_user_id, version, content, source_cursor, "
                        + "generation_batch_id, model, input_message_count, evidence_count, is_current, created_at) "
                        + "values (?, ?, ?, 1, '稳定客户画像', 'cursor-1', ?, 'test-model', 1, 1, ?, ?)",
                UUID.randomUUID(), contactId, ownerId, UUID.randomUUID(), current, Timestamp.from(now));
    }
}
