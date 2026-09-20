package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecentertest.PostgresTestSchema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContactMemoryConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String schema = "contact_memory_concurrency_" + UUID.randomUUID().toString().replace("-", "");
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
    void staleWorkerCannotCommitAfterLeaseExpiresAndNewWorkerClaims() {
        UUID ownerId = insertUser("owner");
        UUID contactId = insertContact(ownerId);
        UUID stateId = UUID.randomUUID();
        jdbc.update("""
                insert into contact_memory_states (id, contact_id, owner_user_id, status, retry_count)
                values (?, ?, ?, 'DIRTY', 0)
                """, stateId, contactId, ownerId);

        UUID firstToken = claimState(stateId, "worker-a", Instant.now().plusSeconds(60));
        // Rewind both lease columns together: ck_contact_memory_state_lease_order requires
        // lease_expires_at > lease_acquired_at, so expiry cannot be simulated by moving one alone.
        jdbc.update("""
                update contact_memory_states
                set lease_acquired_at = now() - interval '2 minutes',
                    lease_expires_at = now() - interval '1 minute'
                where id = ?
                """, stateId);

        assertThat(completeState(stateId, firstToken, "old-cursor")).isZero();

        UUID secondToken = claimState(stateId, "worker-b", Instant.now().plusSeconds(60));
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(completeState(stateId, secondToken, "new-cursor")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select last_success_cursor from contact_memory_states where id = ?",
                String.class, stateId)).isEqualTo("new-cursor");
    }

    @Test
    void firstRunEvidenceRemainsAvailableForSecondRunPromotion() {
        UUID ownerId = insertUser("evidence-owner");
        UUID contactId = insertContact(ownerId);
        UUID observationId = UUID.randomUUID();
        UUID firstMessageId = UUID.randomUUID();
        UUID secondMessageId = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-09-15T01:00:00Z");

        jdbc.update("""
                insert into contact_memory_observations
                    (id, contact_id, owner_user_id, category, normalized_key, observed_value,
                     polarity, confidence, status, source_cursor, generation_batch_id,
                     observed_at, expires_at)
                values (?, ?, ?, 'PRODUCT_INTEREST', 'product_interest', '海运', 'POSITIVE',
                        0.9, 'CANDIDATE', 'cursor-1', ?, ?, ?)
                """, observationId, contactId, ownerId, UUID.randomUUID(),
                Timestamp.from(observedAt), Timestamp.from(observedAt.plusSeconds(3600)));
        insertObservationEvidence(observationId, contactId, ownerId, firstMessageId);

        insertObservationEvidence(observationId, contactId, ownerId, secondMessageId);
        UUID factId = UUID.randomUUID();
        jdbc.update("""
                insert into contact_memory_facts
                    (id, contact_id, owner_user_id, category, normalized_key, normalized_value,
                     display_value, polarity, status, confidence, evidence_count,
                     first_seen_at, last_seen_at, last_confirmed_at, generation_batch_id)
                values (?, ?, ?, 'PRODUCT_INTEREST', 'product_interest', '海运', '海运',
                        'POSITIVE', 'ACTIVE', 0.9, 2, ?, ?, ?, ?)
                """, factId, contactId, ownerId, Timestamp.from(observedAt),
                Timestamp.from(observedAt.plusSeconds(1)), Timestamp.from(observedAt.plusSeconds(1)),
                UUID.randomUUID());
        copyObservationEvidenceToFact(observationId, factId, contactId, ownerId);
        jdbc.update("""
                update contact_memory_observations
                set status = 'PROMOTED', promoted_fact_id = ?
                where id = ? and contact_id = ? and owner_user_id = ?
                """, factId, observationId, contactId, ownerId);

        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_observation_evidence where observation_id = ?",
                Integer.class, observationId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_fact_evidence where fact_id = ?",
                Integer.class, factId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select promoted_fact_id from contact_memory_observations where id = ?",
                UUID.class, observationId)).isEqualTo(factId);
    }

    @Test
    void failedTriggerReplayCanBeClaimedAgainAndAppliedIdempotently() {
        UUID ownerId = insertUser("trigger-owner");
        UUID contactId = insertContact(ownerId);
        Instant receivedAt = Instant.parse("2026-09-15T02:00:00Z");
        UUID messageId = insertInboundMessage(ownerId, contactId, receivedAt);
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into contact_memory_trigger_events
                    (id, message_id, contact_id, owner_user_id, ingest_sequence,
                     occurred_at, received_at, status, next_attempt_at)
                values (?, ?, ?, ?, 1, ?, ?, 'PENDING', ?)
                """, eventId, messageId, contactId, ownerId,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt), Timestamp.from(receivedAt));

        UUID firstToken = claimEvent(eventId, "trigger-a", Instant.now().plusSeconds(60));
        jdbc.update("""
                update contact_memory_trigger_events
                set status = 'PENDING', attempt_count = 1, next_attempt_at = now(),
                    last_failure_code = 'TRANSIENT', lease_owner = null,
                    lease_token = null, lease_acquired_at = null, lease_expires_at = null
                where id = ? and lease_token = ?
                """, eventId, firstToken);

        UUID secondToken = claimEvent(eventId, "trigger-b", Instant.now().plusSeconds(60));
        jdbc.update("""
                insert into contact_memory_states
                    (id, contact_id, owner_user_id, status, last_inbound_at, retry_count)
                values (gen_random_uuid(), ?, ?, 'DIRTY', ?, 0)
                on conflict (contact_id, owner_user_id) do update
                set status = 'DIRTY', last_inbound_at = greatest(
                    coalesce(contact_memory_states.last_inbound_at, excluded.last_inbound_at),
                    excluded.last_inbound_at), next_retry_at = null
                """, contactId, ownerId, Timestamp.from(receivedAt));
        assertThat(markEventApplied(eventId, secondToken)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "select status from contact_memory_trigger_events where id = ?",
                String.class, eventId)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject(
                "select status from contact_memory_states where contact_id = ? and owner_user_id = ?",
                String.class, contactId, ownerId)).isEqualTo("DIRTY");
        assertThat(jdbc.queryForObject(
                "select attempt_count from contact_memory_trigger_events where id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    private UUID claimState(UUID stateId, String worker, Instant leaseUntil) {
        return jdbc.queryForObject("""
                with claimed as (
                    update contact_memory_states
                    set status = 'PROCESSING', lease_owner = ?, lease_token = gen_random_uuid(),
                        lease_acquired_at = now(), lease_expires_at = ?, updated_at = now()
                    where id = ? and (
                        (status in ('DIRTY', 'RETRY_WAIT') and (next_retry_at is null or next_retry_at <= now()))
                        or (status = 'PROCESSING' and lease_expires_at < now())
                    )
                    returning lease_token
                ) select lease_token from claimed
                """, UUID.class, worker, Timestamp.from(leaseUntil), stateId);
    }

    private int completeState(UUID stateId, UUID leaseToken, String cursor) {
        return jdbc.update("""
                update contact_memory_states
                set status = 'CLEAN', last_success_cursor = ?, lease_owner = null,
                    lease_token = null, lease_acquired_at = null, lease_expires_at = null,
                    updated_at = now()
                where id = ? and lease_token = ? and status = 'PROCESSING'
                  and lease_expires_at > now()
                """, cursor, stateId, leaseToken);
    }

    private UUID claimEvent(UUID eventId, String worker, Instant leaseUntil) {
        return jdbc.queryForObject("""
                with claimed as (
                    update contact_memory_trigger_events
                    set status = 'PROCESSING', lease_owner = ?, lease_token = gen_random_uuid(),
                        lease_acquired_at = now(), lease_expires_at = ?
                    where id = ? and status = 'PENDING' and next_attempt_at <= now()
                    returning lease_token
                ) select lease_token from claimed
                """, UUID.class, worker, Timestamp.from(leaseUntil), eventId);
    }

    private int markEventApplied(UUID eventId, UUID leaseToken) {
        return jdbc.update("""
                update contact_memory_trigger_events
                set status = 'APPLIED', applied_at = now(), lease_owner = null,
                    lease_token = null, lease_acquired_at = null, lease_expires_at = null
                where id = ? and lease_token = ? and status = 'PROCESSING'
                  and lease_expires_at > now()
                """, eventId, leaseToken);
    }

    private void insertObservationEvidence(UUID observationId, UUID contactId,
                                           UUID ownerId, UUID evidenceId) {
        jdbc.update("""
                insert into contact_memory_observation_evidence
                    (observation_id, contact_id, owner_user_id, evidence_type, evidence_id,
                     evidence_excerpt, generation_batch_id)
                values (?, ?, ?, 'MESSAGE', ?, '', ?)
                on conflict (observation_id, evidence_type, evidence_id) do nothing
                """, observationId, contactId, ownerId, evidenceId, UUID.randomUUID());
    }

    private void copyObservationEvidenceToFact(UUID observationId, UUID factId,
                                               UUID contactId, UUID ownerId) {
        jdbc.update("""
                insert into contact_memory_fact_evidence
                    (fact_id, contact_id, owner_user_id, evidence_type, evidence_id,
                     evidence_excerpt, generation_batch_id)
                select ?, contact_id, owner_user_id, evidence_type, evidence_id,
                       evidence_excerpt, ?
                from contact_memory_observation_evidence
                where observation_id = ? and contact_id = ? and owner_user_id = ?
                on conflict (fact_id, evidence_type, evidence_id) do nothing
                """, factId, UUID.randomUUID(), observationId, contactId, ownerId);
    }

    private UUID insertInboundMessage(UUID ownerId, UUID contactId, Instant receivedAt) {
        UUID channelAccountId = UUID.randomUUID();
        jdbc.update("""
                insert into channel_accounts
                    (id, owner_user_id, channel_type, name, account_identifier,
                     account_identifier_normalized, auth_status, encrypted_config)
                values (?, ?, 'email', 'Trigger Email', ?, ?, 'active', '{}'::jsonb)
                """, channelAccountId, ownerId, "trigger-" + channelAccountId,
                "trigger-" + channelAccountId);
        UUID identityId = UUID.randomUUID();
        jdbc.update("""
                insert into contact_identities
                    (id, contact_id, channel_type, identity_scope, identity_value,
                     normalized_value, display_name, is_primary, verify_status, source)
                values (?, ?, 'email', ?, 'trigger@example.com', 'trigger@example.com',
                        'Trigger Customer', true, 'verified', 'manual')
                """, identityId, contactId, ownerId.toString());
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                insert into conversations
                    (id, channel_account_id, contact_identity_id, status, next_ingest_sequence)
                values (?, ?, ?, 'open', 0)
                """, conversationId, channelAccountId, identityId);
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into messages
                    (id, conversation_id, channel_account_id, channel_account_version, direction,
                     message_kind, occurred_at, ingest_sequence, current_status, current_status_at)
                values (?, ?, ?, 0, 'inbound', 'text', ?, 1, 'delivered', ?)
                """, messageId, conversationId, channelAccountId,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt));
        return messageId;
    }

    private UUID insertUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users
                    (id, username, username_normalized, password_hash, display_name, status)
                values (?, ?, ?, 'test-hash', ?, 'active')
                """, id, "memory-" + suffix + "-" + id, id.toString(), suffix);
        return id;
    }

    private UUID insertContact(UUID ownerId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        jdbc.update("""
                insert into contacts (id, display_name, status, created_by, created_at, updated_at)
                values (?, 'Memory Contact', 'active', ?, ?, ?)
                """, id, ownerId, Timestamp.from(now), Timestamp.from(now));
        return id;
    }
}
