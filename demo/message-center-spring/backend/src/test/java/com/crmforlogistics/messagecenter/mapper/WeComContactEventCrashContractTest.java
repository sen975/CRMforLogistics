package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAppEventCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactEventService;
import com.crmforlogistics.messagecentertest.mapper.WeComContactEventMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 崩溃契约：**只有已提交（或幂等冲突）的事件才允许 ack**。
 *
 * <p>这是本能力的核心可靠性约定。回包超 1 秒企微会屏蔽该事件一段时间，所以很想先入内存队列
 * 立刻返回；但入队后崩溃就等于永久丢失（企微不会为已 ack 的事件重推）。这里用真实的
 * PostgreSQL 验证三件事：
 * <ol>
 *   <li>提交前失败 → 不 ack，且库里没有半条记录；</li>
 *   <li>已 ack 的事件在「进程重启」（换一个新的 service 实例）后仍能从库中恢复；</li>
 *   <li>未 ack 的事件被企微重推后能正常落库，且最终只有一行。</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WeComContactEventMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeComContactEventCrashContractTest {
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
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    private static final UUID INSTALLATION_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private FlakyMapper flakyMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table wecom_contact_events, wecom_installations cascade");
        jdbc.update("insert into wecom_installations (id, suite_id, auth_corp_id, agent_id, "
                        + "permanent_code, auth_status, authorized_at, created_at, updated_at, version) "
                        + "values (?::uuid, ?, ?, '100001', 'permanent-code', 'ACTIVE', now(), now(), now(), 0)",
                INSTALLATION_ID, SUITE, CORP);
        flakyMapper = new FlakyMapper(mapper);
    }

    @Test
    void preCommitFailureNeverAcksAndLeavesNoRow() {
        flakyMapper.failNextInsert();

        assertThatThrownBy(() -> service().ingest(event("add_external_contact")))
                .isInstanceOf(WeComException.class)
                .satisfies(thrown -> assertThat(((WeComException) thrown).httpStatus()).isEqualTo(503));
        assertThat(count()).as("没提交就不能留下半条记录").isZero();
    }

    @Test
    void acknowledgedEventSurvivesProcessRestart() {
        assertThat(service().ingest(event("add_external_contact")))
                .isEqualTo(WeComContactEventService.IngestResult.ACCEPTED);

        // 「重启」：全新的 service 实例 + 全新的 mapper 代理，除了数据库之外没有任何共享状态。
        WeComContactEventService afterRestart =
                new WeComContactEventService(new FlakyMapper(mapper), installationService(), config(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(jdbc.queryForObject("select ingest_status from wecom_contact_events", String.class))
                .as("已提交的事件落在 RECEIVED，后续动作可以按 durable 行领取")
                .isEqualTo("RECEIVED");
        assertThat(afterRestart.ingest(event("add_external_contact")))
                .as("企微在这期间重推同一事件仍应被 ack，且不产生第二行")
                .isEqualTo(WeComContactEventService.IngestResult.DUPLICATE);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void unacknowledgedEventIsStoredExactlyOnceOnProviderRetry() {
        flakyMapper.failNextInsert();
        assertThatThrownBy(() -> service().ingest(event("add_external_contact")))
                .isInstanceOf(WeComException.class);

        // 企微重推（同一个事件，同一 dedupe_key）
        assertThat(service().ingest(event("add_external_contact")))
                .isEqualTo(WeComContactEventService.IngestResult.ACCEPTED);
        assertThat(service().ingest(event("add_external_contact")))
                .isEqualTo(WeComContactEventService.IngestResult.DUPLICATE);
        assertThat(count()).as("重推不会写第二条").isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private WeComContactEventService service() {
        return new WeComContactEventService(flakyMapper, installationService(), config(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AppConfig config() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn(SUITE);
        when(config.wecomContactEventEnabled()).thenReturn(true);
        return config;
    }

    private static WeComInstallationService installationService() {
        WeComInstallationService service = mock(WeComInstallationService.class);
        WeComInstallationEntity installation = new WeComInstallationEntity();
        installation.setId(INSTALLATION_ID);
        installation.setSuiteId(SUITE);
        installation.setAuthCorpId(CORP);
        when(service.find(anyString(), anyString())).thenReturn(installation);
        return service;
    }

    private static WeComAppEventCodec.DecodedAppEvent event(String changeType) {
        return new WeComAppEventCodec.DecodedAppEvent(CORP, "sys", "change_external_contact",
                changeType, "zhangsan", "wmZZZZZZZZ", null, "state-a", "WELCOME", null, null,
                Instant.ofEpochSecond(1_403_610_513L));
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from wecom_contact_events", Integer.class);
    }

    /** 可注入故障的 mapper：用来在 SQL 落盘前那一瞬间模拟进程崩溃 / 数据库连接断开。 */
    private static final class FlakyMapper implements WeComContactEventMapper {
        private final WeComContactEventMapper delegate;
        private boolean failNext;

        private FlakyMapper(WeComContactEventMapper delegate) {
            this.delegate = delegate;
        }

        private void failNextInsert() {
            this.failNext = true;
        }

        @Override
        public int insertIgnore(WeComContactEventEntity event) {
            if (failNext) {
                failNext = false;
                throw new DataAccessResourceFailureException("simulated pre-commit crash");
            }
            return delegate.insertIgnore(event);
        }

        @Override
        public List<WeComContactEventEntity> listTimeline(UUID installationId, Instant since,
                                                          String changeType, int limit) {
            return delegate.listTimeline(installationId, since, changeType, limit);
        }
    }
}
