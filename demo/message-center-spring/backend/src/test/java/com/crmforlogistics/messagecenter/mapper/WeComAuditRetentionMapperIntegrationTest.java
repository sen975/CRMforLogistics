package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuditRetentionStateEntity;
import com.crmforlogistics.messagecentertest.mapper.WeComAuditRetentionMapperTestConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WeComAuditRetentionMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeComAuditRetentionMapperIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired WeComAuditRetentionMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.parse("2026-08-18T00:00:00Z");
    private final Instant cutoff = now.minus(7, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table wecom_audit_retention_state, "
                + "wecom_api_audit, wecom_authorization_audit, wecom_viewer_audit");
    }

    @Test
    void deletesExpiredViewerRowsInStableLimitedBatches() {
        insertViewer("old-1", cutoff.minusSeconds(3));
        insertViewer("old-2", cutoff.minusSeconds(2));
        insertViewer("old-3", cutoff.minusSeconds(1));
        insertViewer("current", cutoff);

        assertThat(mapper.deleteExpiredViewer(cutoff, 2)).isEqualTo(2);
        assertThat(count("wecom_viewer_audit", "occurred_at < ?", cutoff)).isEqualTo(1);
        assertThat(count("wecom_viewer_audit", "occurred_at >= ?", cutoff)).isEqualTo(1);
    }

    @Test
    void deletesClosedAndLegacyAuthorizationRowsButPreservesOpenAttempt() {
        insertAuthorization("closed", 1, "accepted", cutoff.minusSeconds(6));
        insertAuthorization("closed", 1, "pending", cutoff.minusSeconds(5));
        insertAuthorization("closed", 1, "succeeded", cutoff.minusSeconds(4));
        insertAuthorization("open", 1, "accepted", cutoff.minusSeconds(3));
        insertAuthorization("open", 1, "pending", cutoff.minusSeconds(2));
        insertAuthorization(null, null, "succeeded", cutoff.minusSeconds(1));
        insertAuthorization("current", 1, "succeeded", cutoff);

        assertThat(mapper.deleteExpiredAuthorization(cutoff, 20)).isEqualTo(4);
        assertThat(count("wecom_authorization_audit", "event_id = ?", "open")).isEqualTo(2);
        assertThat(count("wecom_authorization_audit", "event_id = ?", "closed")).isZero();
        assertThat(count("wecom_authorization_audit", "occurred_at >= ?", cutoff)).isEqualTo(1);
    }

    @Test
    void upsertsOneStatusRowPerStream() {
        WeComAuditRetentionStateEntity viewer = state("viewer", "running", 0);
        mapper.upsertState(viewer);
        viewer.setStatus("success");
        viewer.setDeletedCount(12);
        viewer.setLastCompletedAt(now);
        mapper.upsertState(viewer);
        mapper.upsertState(state("authorization", "success", 5));
        mapper.upsertState(state("api", "success", 3));

        assertThat(count("wecom_audit_retention_state", "stream = ?", "viewer")).isEqualTo(1);
        assertThat(mapper.findState("viewer").getDeletedCount()).isEqualTo(12);
        assertThat(count("wecom_audit_retention_state", "1 = 1")).isEqualTo(3);
    }

    @Test
    void deletesExpiredApiRowsInStableLimitedBatches() {
        insertApi(cutoff.minusSeconds(2));
        insertApi(cutoff.minusSeconds(1));
        insertApi(cutoff);

        assertThat(mapper.deleteExpiredApi(cutoff, 1)).isEqualTo(1);
        assertThat(count("wecom_api_audit", "occurred_at < ?", cutoff)).isEqualTo(1);
        assertThat(count("wecom_api_audit", "occurred_at >= ?", cutoff)).isEqualTo(1);
    }

    private void insertViewer(String action, Instant occurredAt) {
        jdbc.update("insert into wecom_viewer_audit "
                        + "(id, occurred_at, action, result) values (?, ?, ?, 'success')",
                UUID.randomUUID(), Timestamp.from(occurredAt), action);
    }

    private void insertAuthorization(String eventId, Integer attempt, String result,
                                     Instant occurredAt) {
        jdbc.update("insert into wecom_authorization_audit "
                        + "(id, occurred_at, action, result, suite_id, event_id, attempt) "
                        + "values (?, ?, 'wecom.authorization.create_auth', ?, 'suite', ?, ?)",
                UUID.randomUUID(), Timestamp.from(occurredAt), result, eventId, attempt);
    }

    private void insertApi(Instant occurredAt) {
        UUID installationId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?, ?, ?, 'hash', 'Retention Actor')",
                actorUserId, actorUserId.toString(), actorUserId.toString());
        jdbc.update("insert into wecom_installations "
                        + "(id, suite_id, auth_corp_id, agent_id, permanent_code) "
                        + "values (?, 'suite', ?, '1000001', 'encrypted-code')",
                installationId, installationId.toString());
        jdbc.update("insert into wecom_api_audit "
                        + "(id, operation_id, occurred_at, installation_id, actor_user_id, action, result, "
                        + "upstream_path, trace_id) values (?, ?, ?, ?, ?, 'wecom.api.test', 'success', "
                        + "'/cgi-bin/tag/list', 'retention-test')",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(occurredAt), installationId, actorUserId);
    }

    private WeComAuditRetentionStateEntity state(String stream, String status, long deleted) {
        WeComAuditRetentionStateEntity state = new WeComAuditRetentionStateEntity();
        state.setStream(stream);
        state.setLastStartedAt(now.minusSeconds(1));
        state.setDeletedCount(deleted);
        state.setStatus(status);
        state.setUpdatedAt(now);
        return state;
    }

    private long count(String table, String predicate, Object... args) {
        Object[] jdbcArgs = args.clone();
        for (int index = 0; index < jdbcArgs.length; index++) {
            if (jdbcArgs[index] instanceof Instant instant) {
                jdbcArgs[index] = Timestamp.from(instant);
            }
        }
        return jdbc.queryForObject("select count(*) from " + table + " where " + predicate,
                Long.class, jdbcArgs);
    }
}
