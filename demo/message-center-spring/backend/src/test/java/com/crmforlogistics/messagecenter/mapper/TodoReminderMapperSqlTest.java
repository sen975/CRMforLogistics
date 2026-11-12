package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecentertest.mapper.TodoReminderMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待办提醒相关 SQL 的真实落库验证。
 *
 * <p>这里刻意不走 mock：待办提醒的幂等全部压在 SQL 里 ——
 * {@code insert … on conflict do update … where …  returning} 的抢占用意、
 * {@code (due_date + due_time)} 的窗口比较、部分索引是否真的建出来，
 * 都只有在真 PostgreSQL 上跑一遍才算验过。语法写错的话单测全绿也发现不了，
 * 要等到线上第一次 tick 才炸。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TodoReminderMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TodoReminderMapperSqlTest {
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

    @Autowired TodoItemMapper todoItemMapper;
    @Autowired TodoDailyReminderMapper dailyReminderMapper;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);

    private UUID boundUserId;
    private UUID unboundUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table todo_daily_reminders, todo_items, wecom_user_bindings, users cascade");
        boundUserId = insertUser("bound");
        unboundUserId = insertUser("unbound");
        jdbc.update("insert into wecom_user_bindings "
                        + "(user_id, suite_id, auth_corp_id, wecom_user_id, provisioning_source) "
                        + "values (?::uuid, 'suite', 'corp', 'wecom-bound', 'BOUND_EXISTING')",
                boundUserId);
    }

    @Test
    void migrationAddsLeadMarkerAndDailyLedger() {
        assertThat(columnExists("todo_items", "lead_reminder_sent_at")).isTrue();
        assertThat(columnExists("todo_daily_reminders", "attempt_count")).isTrue();
        assertThat(indexExists("ix_todo_items_pending_due"))
                .as("提前提醒要按 due_date 单独过滤，user_id 打头的旧索引用不上")
                .isTrue();
    }

    @Test
    void dailyCandidatesKeepOnlyPendingTodosOfBoundUsers() {
        UUID expected = insertTodo(boundUserId, DATE, LocalTime.of(9, 30), "确认青岛仓出库时间", false);
        insertTodo(boundUserId, DATE, LocalTime.of(13, 0), "已经做完了", true);
        insertTodo(unboundUserId, DATE, LocalTime.of(10, 0), "账号没绑企微", false);
        insertTodo(boundUserId, DATE.plusDays(1), LocalTime.of(9, 0), "不是今天", false);

        List<TodoItemEntity> candidates = todoItemMapper.listDailyReminderCandidates(DATE);

        assertThat(candidates).extracting(TodoItemEntity::getId).containsExactly(expected);
    }

    @Test
    void dailyClaimBlocksDuplicatesAndAllowsBoundedRetries() {
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 3, 2, "claim-1", 300))
                .as("首个 tick 抢到发送权").isNotNull();
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 3, 2, "claim-2", 300))
                .as("PENDING 期间重复 tick 抢不到").isNull();

        dailyReminderMapper.markFailed(boundUserId, DATE, "claim-1", "企微接口 502", 2);
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 3, 2, "claim-3", 300))
                .as("失败且未到上限时允许重试").isNotNull();

        dailyReminderMapper.markSent(boundUserId, DATE, "claim-3", "msg-1");
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 3, 2, "claim-4", 300))
                .as("已发送后永远不再返回发送权").isNull();
        assertThat(jdbc.queryForObject(
                "select message_id from todo_daily_reminders where user_id = ?::uuid and reminder_date = ?",
                String.class, boundUserId, DATE)).isEqualTo("msg-1");
    }

    @Test
    void dailyClaimGivesUpAfterAttemptsAreExhausted() {
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 2, "claim-a", 300)).isNotNull();
        dailyReminderMapper.markFailed(boundUserId, DATE, "claim-a", "第一次失败", 2);
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 2, "claim-b", 300)).isNotNull();
        dailyReminderMapper.markFailed(boundUserId, DATE, "claim-b", "第二次失败", 2);

        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 2, "claim-c", 300))
                .as("用尽重试次数后不再重发，避免整点刷屏").isNull();
        assertThat(jdbc.queryForObject(
                "select status from todo_daily_reminders where user_id = ?::uuid and reminder_date = ?",
                String.class, boundUserId, DATE)).isEqualTo("ABANDONED");
    }

    @Test
    void dailyClaimCanTakeOverExpiredLeaseButOldTokenCannotWriteBack() {
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 3, "daily-old", 300))
                .isEqualTo("daily-old");
        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 3, "daily-live", 300))
                .as("活跃租约不能被第二个 worker 抢走").isNull();

        jdbc.update("update todo_daily_reminders set claimed_at = now() - interval '301 seconds' "
                + "where user_id = ?::uuid and reminder_date = ?", boundUserId, DATE);

        assertThat(dailyReminderMapper.claim(boundUserId, DATE, 1, 3, "daily-new", 300))
                .as("租约过期后允许接管").isEqualTo("daily-new");
        assertThat(dailyReminderMapper.markSent(boundUserId, DATE, "daily-old", "stale-message"))
                .as("旧 worker 不能覆盖新 worker 的结果").isZero();
        assertThat(dailyReminderMapper.markSent(boundUserId, DATE, "daily-new", "fresh-message"))
                .isEqualTo(1);
    }

    @Test
    void leadCandidatesRespectTheOpenWindowAndSkipRemindedOrFinishedOnes() {
        UUID inside = insertTodo(boundUserId, DATE, LocalTime.of(9, 30), "窗口内", false);
        UUID atDeadline = insertTodo(boundUserId, DATE, LocalTime.of(9, 45), "正好压在右边界", false);
        insertTodo(boundUserId, DATE, LocalTime.of(12, 0), "还早，窗口外", false);
        insertTodo(boundUserId, DATE, LocalTime.of(6, 30), "已经开始了", false);
        insertTodo(boundUserId, DATE, null, "没有开始时间", false);
        insertTodo(boundUserId, DATE, LocalTime.of(9, 0), "已完成", true);
        insertTodo(unboundUserId, DATE, LocalTime.of(9, 0), "账号没绑企微", false);
        UUID alreadyReminded = insertTodo(boundUserId, DATE, LocalTime.of(9, 15), "已经提醒过", false);
        jdbc.update("update todo_items set lead_reminder_sent_at = now() where id = ?::uuid", alreadyReminded);

        List<TodoItemEntity> candidates = todoItemMapper.listLeadReminderCandidates(
                DATE, LocalDateTime.of(2026, 9, 20, 6, 45), LocalDateTime.of(2026, 9, 20, 9, 45), 300);

        assertThat(candidates).extracting(TodoItemEntity::getId).containsExactly(inside, atDeadline);
    }

    @Test
    void leadMarkerClaimsOnceAndCanBeReleasedForRetry() {
        UUID id = insertTodo(boundUserId, DATE, LocalTime.of(9, 30), "确认青岛仓出库时间", false);

        assertThat(todoItemMapper.claimLeadReminder(id, "lead-1", 300)).isEqualTo(1);
        assertThat(todoItemMapper.claimLeadReminder(id, "lead-2", 300)).as("同一 tick 的重复候选不会连发").isZero();

        todoItemMapper.releaseLeadReminder(id, "lead-1");
        assertThat(todoItemMapper.claimLeadReminder(id, "lead-3", 300))
                .as("发送失败退回标记后，下个 tick 还能重试").isEqualTo(1);
    }

    @Test
    void leadClaimCanTakeOverExpiredLeaseButOldTokenCannotReleaseOrMarkSent() {
        UUID id = insertTodo(boundUserId, DATE, LocalTime.of(9, 30), "租约接管", false);

        assertThat(todoItemMapper.claimLeadReminder(id, "lead-old", 300)).isEqualTo(1);
        assertThat(todoItemMapper.claimLeadReminder(id, "lead-live", 300)).isZero();
        jdbc.update("update todo_items set lead_reminder_claimed_at = now() - interval '301 seconds' "
                + "where id = ?::uuid", id);

        assertThat(todoItemMapper.claimLeadReminder(id, "lead-new", 300)).isEqualTo(1);
        assertThat(todoItemMapper.releaseLeadReminder(id, "lead-old"))
                .as("旧 worker 不能释放新 worker 的租约").isZero();
        assertThat(todoItemMapper.markLeadReminderSent(id, "lead-old"))
                .as("旧 worker 不能确认新 worker 的发送").isZero();
        assertThat(todoItemMapper.markLeadReminderSent(id, "lead-new")).isEqualTo(1);
    }

    @Test
    void completedTodoCannotClaimALeadMarker() {
        UUID id = insertTodo(boundUserId, DATE, LocalTime.of(9, 30), "已经做完了", true);

        assertThat(todoItemMapper.claimLeadReminder(id, "lead-1", 300)).isZero();
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?::uuid, ?, ?, 'x', ?)",
                id, name, name, name);
        return id;
    }

    private UUID insertTodo(UUID userId, LocalDate date, LocalTime time, String title, boolean completed) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into todo_items (id, user_id, due_date, title, due_time, completed) "
                        + "values (?::uuid, ?::uuid, ?::date, ?, ?::time, ?)",
                id, userId, date, title, time == null ? null : time.toString(), completed);
        return id;
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
}
