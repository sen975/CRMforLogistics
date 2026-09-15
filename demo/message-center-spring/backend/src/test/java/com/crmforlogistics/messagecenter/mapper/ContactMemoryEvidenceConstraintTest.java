package com.crmforlogistics.messagecenter.mapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ContactMemoryEvidenceConstraintTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private UUID ownerOne;
    private UUID ownerTwo;
    private UUID contactOne;
    private UUID contactTwo;
    private UUID factOne;
    private UUID factTwo;
    private UUID labelOne;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "contact_memory_evidence_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("set search_path to " + schema);

        ownerOne = UUID.randomUUID();
        ownerTwo = UUID.randomUUID();
        contactOne = insertContact(ownerOne, "one");
        contactTwo = insertContact(ownerTwo, "two");
        factOne = insertFact(contactOne, ownerOne, "sea");
        factTwo = insertFact(contactTwo, ownerTwo, "air");
        labelOne = insertLabel(contactOne, ownerOne, "sea");
    }

    @Test
    void evidenceCannotReferenceFactFromAnotherContactOrOwner() {
        UUID evidenceId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "insert into contact_memory_fact_evidence "
                        + "(id, fact_id, contact_id, owner_user_id, evidence_type, evidence_id, "
                        + "evidence_excerpt, generation_batch_id) values (?, ?, ?, ?, 'MESSAGE', ?, '', ?)",
                evidenceId, factOne, contactTwo, ownerTwo, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void labelEvidenceCannotMixLabelAndFactOwnership() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into contact_ai_label_evidence "
                        + "(id, label_id, fact_id, contact_id, owner_user_id, evidence_type, evidence_id, "
                        + "evidence_excerpt, generation_batch_id) values (?, ?, ?, ?, ?, 'LONG_TERM_FACT', ?, '', ?)",
                UUID.randomUUID(), labelOne, factTwo, contactOne, ownerOne, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID insertContact(UUID ownerId, String suffix) {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        jdbc.update("insert into users "
                        + "(id, username, username_normalized, password_hash, display_name, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'test-hash', ?, 'active', ?, ?)",
                ownerId, "memory-owner-" + suffix + ownerId, ownerId.toString(), "Owner " + suffix,
                Timestamp.from(now), Timestamp.from(now));
        UUID contactId = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, status, created_by, created_at, updated_at) "
                        + "values (?, ?, 'active', ?, ?, ?)",
                contactId, "Contact " + suffix, ownerId, Timestamp.from(now), Timestamp.from(now));
        return contactId;
    }

    private UUID insertFact(UUID contactId, UUID ownerId, String value) {
        UUID factId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        jdbc.update("insert into contact_memory_facts "
                        + "(id, contact_id, owner_user_id, category, normalized_key, normalized_value, "
                        + "display_value, polarity, status, confidence, evidence_count, first_seen_at, "
                        + "last_seen_at, last_confirmed_at, generation_batch_id) "
                        + "values (?, ?, ?, 'PRODUCT_INTEREST', 'product', ?, ?, 'POSITIVE', 'ACTIVE', "
                        + "0.9, 0, ?, ?, ?, ?)",
                factId, contactId, ownerId, value, value, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), UUID.randomUUID());
        return factId;
    }

    private UUID insertLabel(UUID contactId, UUID ownerId, String value) {
        UUID labelId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        jdbc.update("insert into contact_ai_labels "
                        + "(id, contact_id, owner_user_id, category, normalized_name, display_name, color_token, "
                        + "status, confidence, first_seen_at, last_seen_at, last_evidence_at, generation_batch_id) "
                        + "values (?, ?, ?, 'PRODUCT_INTEREST', ?, ?, 'green', 'ACTIVE', 0.9, ?, ?, ?, ?)",
                labelId, contactId, ownerId, value, value, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), UUID.randomUUID());
        return labelId;
    }
}
