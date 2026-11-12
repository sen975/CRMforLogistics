package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import com.crmforlogistics.messagecentertest.mapper.WeComContactEventMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 客户联系事件落库行为的**真实落库**验证。
 *
 * <p>刻意不走 mock：幂等（{@code on conflict do nothing}）、外键、CHECK 约束、部分索引、
 * 时间线排序全部是 SQL 层语义，语法写错时单测全绿也发现不了，要等线上第一次收到事件才炸。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WeComContactEventMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeComContactEventMapperSqlTest {
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

    @Autowired WeComContactEventMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String SUITE = "wwsuite";
    private static final String CORP = "wpxxxxxxxxcorpid";
    private static final Instant CREATED = Instant.parse("2026-09-21T01:00:00Z");

    private UUID installationId;
    private UUID otherSuiteInstallationId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table wecom_contact_events, wecom_installations cascade");
        installationId = insertInstallation(SUITE, CORP);
        // 同一个密文 corpid 出现在另一个 suite 下 —— 用来验证查询按 installation 隔离。
        otherSuiteInstallationId = insertInstallation("wwothersuite", CORP);
    }

    @Test
    void migrationCreatesTheEventStreamWithItsConstraintsAndIndexes() {
        assertThat(columnExists("wecom_contact_events", "dedupe_key")).isTrue();
        assertThat(columnExists("wecom_contact_events", "provider_source")).isTrue();
        assertThat(columnExists("wecom_contact_events", "ingest_status")).isTrue();
        assertThat(indexExists("uq_wecom_contact_events_dedupe")).isTrue();
        assertThat(indexExists("ix_wecom_contact_events_external_user")).isTrue();
        assertThat(indexExists("ix_wecom_contact_events_recent")).isTrue();
        assertThat(foreignKeyTarget("wecom_contact_events", "installation_id"))
                .isEqualTo("wecom_installations");
    }

    @Test
    void insertReturnsOneOnFirstDeliveryAndZeroOnRedelivery() {
        WeComContactEventEntity event = event(installationId, "add_external_contact", "wmAAAA");

        assertThat(mapper.insertIgnore(event)).as("首次投递真的写入").isEqualTo(1);
        assertThat(mapper.insertIgnore(copyWithoutId(event)))
                .as("企微重推撞 dedupe_key 唯一约束，受影响行数为 0").isZero();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void distinctEventsInTheSameSecondAreBothStored() {
        mapper.insertIgnore(event(installationId, "add_external_contact", "wmAAAA"));
        mapper.insertIgnore(event(installationId, "del_external_contact", "wmAAAA"));

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void persistsEveryProviderField() {
        WeComContactEventEntity event = event(installationId, "transfer_fail", "wmZZZZZZZZ");
        event.setState("baidu-channel");
        event.setWelcomeCode("WELCOMECODE");
        event.setFailReason("customer_refused");
        event.setProviderSource("DELETE_BY_TRANSFER");
        event.setChatId("CHAT_ID");

        mapper.insertIgnore(event);

        WeComContactEventEntity stored = mapper.listTimeline(installationId, null, null, 10).get(0);
        assertThat(stored.getId()).isEqualTo(event.getId());
        assertThat(stored.getInstallationId()).isEqualTo(installationId);
        assertThat(stored.getSuiteId()).isEqualTo(SUITE);
        assertThat(stored.getAuthCorpId()).isEqualTo(CORP);
        assertThat(stored.getEvent()).isEqualTo("change_external_contact");
        assertThat(stored.getChangeType()).isEqualTo("transfer_fail");
        assertThat(stored.getExternalUserId()).isEqualTo("wmZZZZZZZZ");
        assertThat(stored.getChatId()).isEqualTo("CHAT_ID");
        assertThat(stored.getState()).isEqualTo("baidu-channel");
        assertThat(stored.getWelcomeCode()).isEqualTo("WELCOMECODE");
        assertThat(stored.getFailReason()).isEqualTo("customer_refused");
        assertThat(stored.getProviderSource()).isEqualTo("DELETE_BY_TRANSFER");
        assertThat(stored.getProviderCreatedAt()).isEqualTo(CREATED);
        assertThat(stored.getReceivedAt()).isEqualTo(CREATED.plusSeconds(1));
        assertThat(stored.getIngestStatus()).isEqualTo("RECEIVED");
        assertThat(stored.getAttemptCount()).isZero();
        assertThat(stored.getProcessedAt()).isNull();
    }

    @Test
    void checkConstraintRejectsEventsOutsideTheFirstPhaseContract() {
        WeComContactEventEntity chatEvent = event(installationId, "add_external_contact", "wmAAAA");
        chatEvent.setEvent("change_external_chat");

        assertThatThrownBy(() -> mapper.insertIgnore(chatEvent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintRejectsUnknownIngestStatus() {
        WeComContactEventEntity event = event(installationId, "add_external_contact", "wmAAAA");
        event.setIngestStatus("WHATEVER");

        assertThatThrownBy(() -> mapper.insertIgnore(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeyRejectsEventsWithoutAnInstallation() {
        WeComContactEventEntity event = event(UUID.randomUUID(), "add_external_contact", "wmAAAA");

        assertThatThrownBy(() -> mapper.insertIgnore(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void timelineIsNewestFirstAndHonoursSinceAndChangeType() {
        WeComContactEventEntity older = event(installationId, "add_external_contact", "wmAAAA");
        older.setProviderCreatedAt(CREATED);
        WeComContactEventEntity newer = event(installationId, "del_external_contact", "wmAAAA");
        newer.setProviderCreatedAt(CREATED.plusSeconds(60));

        mapper.insertIgnore(older);
        mapper.insertIgnore(newer);

        assertThat(mapper.listTimeline(installationId, null, null, 10))
                .extracting(WeComContactEventEntity::getChangeType)
                .containsExactly("del_external_contact", "add_external_contact");
        assertThat(mapper.listTimeline(installationId, null, "del_external_contact", 10))
                .hasSize(1);
        assertThat(mapper.listTimeline(installationId, CREATED.plusSeconds(30), null, 10))
                .extracting(WeComContactEventEntity::getChangeType)
                .containsExactly("del_external_contact");
        assertThat(mapper.listTimeline(installationId, null, null, 1)).hasSize(1);
    }

    @Test
    void timelineIsScopedToTheInstallationSoIdenticalCorpIdsDoNotCrossSuites() {
        mapper.insertIgnore(event(installationId, "add_external_contact", "wmAAAA"));
        mapper.insertIgnore(event(otherSuiteInstallationId, "del_external_contact", "wmAAAA"));

        assertThat(mapper.listTimeline(installationId, null, null, 10))
                .extracting(WeComContactEventEntity::getChangeType)
                .containsExactly("add_external_contact");
    }

    // ---------- 夹具 ----------

    private static WeComContactEventEntity event(UUID installationId, String changeType,
                                                 String externalUserId) {
        WeComContactEventEntity event = new WeComContactEventEntity();
        event.setId(UUID.randomUUID());
        event.setInstallationId(installationId);
        event.setSuiteId(SUITE);
        event.setAuthCorpId(CORP);
        event.setEvent("change_external_contact");
        event.setChangeType(changeType);
        event.setWecomUserId("zhangsan");
        event.setExternalUserId(externalUserId);
        event.setProviderCreatedAt(CREATED);
        event.setReceivedAt(CREATED.plusSeconds(1));
        event.setDedupeKey(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        event.setIngestStatus("RECEIVED");
        event.setAttemptCount(0);
        return event;
    }

    /** 同一条事件换一个自增主键再投一次，模拟企微重推。 */
    private static WeComContactEventEntity copyWithoutId(WeComContactEventEntity source) {
        WeComContactEventEntity copy = event(source.getInstallationId(), source.getChangeType(),
                source.getExternalUserId());
        copy.setSuiteId(source.getSuiteId());
        copy.setAuthCorpId(source.getAuthCorpId());
        copy.setEvent(source.getEvent());
        copy.setWecomUserId(source.getWecomUserId());
        copy.setDedupeKey(source.getDedupeKey());
        copy.setProviderCreatedAt(source.getProviderCreatedAt());
        return copy;
    }

    private UUID insertInstallation(String suiteId, String authCorpId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into wecom_installations (id, suite_id, auth_corp_id, agent_id, "
                        + "permanent_code, auth_status, authorized_at, created_at, updated_at, version) "
                        + "values (?::uuid, ?, ?, '100001', 'permanent-code', 'ACTIVE', now(), now(), now(), 0)",
                id, suiteId, authCorpId);
        return id;
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from wecom_contact_events", Integer.class);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name = ? and column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean indexExists(String index) {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = ?", Integer.class, index);
        return count != null && count > 0;
    }

    private String foreignKeyTarget(String table, String column) {
        List<String> targets = jdbc.queryForList(
                "select ccu.table_name from information_schema.table_constraints tc "
                        + "join information_schema.constraint_column_usage ccu "
                        + "on ccu.constraint_name = tc.constraint_name "
                        + "join information_schema.key_column_usage kcu "
                        + "on kcu.constraint_name = tc.constraint_name "
                        + "where tc.constraint_type = 'FOREIGN KEY' and tc.table_name = ? "
                        + "and kcu.column_name = ?",
                String.class, table, column);
        return targets.isEmpty() ? null : targets.get(0);
    }
}
