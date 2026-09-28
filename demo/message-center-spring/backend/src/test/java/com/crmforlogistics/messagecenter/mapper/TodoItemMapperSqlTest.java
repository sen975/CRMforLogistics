package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecentertest.mapper.TodoReminderMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手新增的两个待办 SQL 的真实落库验证。
 *
 * <p>为什么必须打真 PostgreSQL（而不是 mock）：这里要验的三件事全都在 SQL 里 ——
 * 动态 {@code <set>} 到底拼成了哪几列、{@code where user_id} 是否真的挡住了别人的待办、
 * 以及排序能否<b>由索引直接提供</b>。语法或列序写错时 mock 测试会全绿，
 * 要到线上第一次调用才炸。
 *
 * <p>复用 {@link TodoReminderMapperTestConfiguration} 的最小上下文：只装数据源 + MyBatis。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TodoReminderMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TodoItemMapperSqlTest {

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
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate DATE = LocalDate.of(2026, 9, 22);

    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table todo_daily_reminders, todo_items, wecom_user_bindings, users cascade");
        userId = insertUser("owner");
        otherUserId = insertUser("other");
    }

    // ---------- update ----------

    @Test
    void updateChangesOnlyTheGivenColumns() {
        UUID id = insertTodo(userId, DATE, LocalTime.of(15, 0), "和张总确认报价", false);
        jdbc.update("update todo_items set note = '带上去年的报价单' where id = ?::uuid", id);

        assertThat(todoItemMapper.update(id, userId, null, LocalDate.of(2026, 9, 23), null, null)).isEqualTo(1);

        assertThat(text("select due_date::text from todo_items where id = ?::uuid", id)).isEqualTo("2026-09-23");
        assertThat(text("select due_time::text from todo_items where id = ?::uuid", id))
                .as("没传 time 就不该动它").isEqualTo("15:00:00");
        assertThat(text("select title from todo_items where id = ?::uuid", id))
                .as("没传 title 就不该动它").isEqualTo("和张总确认报价");
        assertThat(text("select note from todo_items where id = ?::uuid", id))
                .as("没传 note 就不该动它").isEqualTo("带上去年的报价单");
    }

    @Test
    void updateWritesEveryColumnWhenAllAreGiven() {
        UUID id = insertTodo(userId, DATE, LocalTime.of(15, 0), "旧标题", false);

        assertThat(todoItemMapper.update(id, userId, "新标题", LocalDate.of(2026, 10, 1),
                LocalTime.of(9, 30), "新备注")).isEqualTo(1);

        assertThat(text("select title from todo_items where id = ?::uuid", id)).isEqualTo("新标题");
        assertThat(text("select due_date::text from todo_items where id = ?::uuid", id)).isEqualTo("2026-10-01");
        assertThat(text("select due_time::text from todo_items where id = ?::uuid", id)).isEqualTo("09:30:00");
        assertThat(text("select note from todo_items where id = ?::uuid", id)).isEqualTo("新备注");
    }

    @Test
    void updateRefreshesUpdatedAt() {
        UUID id = insertTodo(userId, DATE, LocalTime.of(15, 0), "原样", false);
        String before = text("select updated_at::text from todo_items where id = ?::uuid", id);

        jdbc.update("update todo_items set updated_at = updated_at - interval '1 hour' where id = ?::uuid", id);
        String aged = text("select updated_at::text from todo_items where id = ?::uuid", id);

        todoItemMapper.update(id, userId, "改标题", null, null, null);

        assertThat(text("select updated_at::text from todo_items where id = ?::uuid", id))
                .as("改过就必须刷新 updated_at（前后对比而不是跟 now() 比，避免时钟精度造成的假失败）")
                .isNotEqualTo(aged)
                .isNotEqualTo(before);
    }

    @Test
    void updateRefusesToTouchAnotherUsersTodo() {
        UUID id = insertTodo(userId, DATE, LocalTime.of(15, 0), "原样", false);

        assertThat(todoItemMapper.update(id, otherUserId, "被别人改了", null, null, null)).isZero();

        assertThat(text("select title from todo_items where id = ?::uuid", id)).isEqualTo("原样");
    }

    @Test
    void updateNeverNullsTheDueDateOrTime() {
        UUID id = insertTodo(userId, DATE, LocalTime.of(9, 0), "原样", false);

        assertThat(todoItemMapper.update(id, userId, "只改标题", null, null, null)).isEqualTo(1);

        assertThat(text("select due_date::text from todo_items where id = ?::uuid", id))
                .as("due_date 是 NOT NULL，不提供置空语义").isEqualTo(DATE.toString());
        assertThat(text("select due_time::text from todo_items where id = ?::uuid", id))
                .as("time 同样只设置不清空").isEqualTo("09:00:00");
    }

    @Test
    void updateOnMissingTodoAffectsNoRows() {
        assertThat(todoItemMapper.update(UUID.randomUUID(), userId, "改标题", null, null, null)).isZero();
    }

    // ---------- listOpenForAssistant ----------

    @Test
    void listOpenKeepsOnlyPendingTodosOfThatUserAndHonoursTheLimit() {
        UUID first = insertTodo(userId, DATE, LocalTime.of(9, 0), "今天最早", false);
        UUID second = insertTodo(userId, DATE, LocalTime.of(10, 0), "今天中间", false);
        insertTodo(userId, DATE, LocalTime.of(11, 0), "今天最晚", false);
        insertTodo(userId, DATE, LocalTime.of(8, 0), "已完成", true);
        insertTodo(otherUserId, DATE, LocalTime.of(8, 0), "别人的", false);
        insertTodo(userId, DATE.plusDays(30), null, "很久以后", false);

        assertThat(todoItemMapper.listOpenForAssistant(userId, 2))
                .extracting(TodoItemEntity::getId)
                .as("limit 必须真的作用在 SQL 上，而不是查回来再截断")
                .containsExactly(first, second);

        assertThat(todoItemMapper.listOpenForAssistant(userId, 10))
                .extracting(TodoItemEntity::getId)
                .containsExactly(first, second, insertIdOfTitle("今天最晚"), insertIdOfTitle("很久以后"));
    }

    @Test
    void listOpenOrdersByDateThenTimeNullsLastThenCreatedAt() {
        UUID laterDay = insertTodo(userId, DATE.plusDays(1), null, "明天，没时间", false);
        UUID todayNoTime = insertTodo(userId, DATE, null, "今天，没时间", false);
        UUID todayLate = insertTodo(userId, DATE, LocalTime.of(18, 0), "今天 18 点", false);
        UUID todayEarly = insertTodo(userId, DATE, LocalTime.of(9, 0), "今天 9 点", false);

        assertThat(todoItemMapper.listOpenForAssistant(userId, 10))
                .extracting(TodoItemEntity::getId)
                .as("按日期升序；同一天里没时间的排在有时间之后（NULLS LAST）；再按创建时间")
                .containsExactly(todayEarly, todayLate, todayNoTime, laterDay);
    }

    /**
     * 排序与 {@code ix_todo_items_user_date} 的列序逐列对齐，所以排序可以随索引扫描一起完成。
     * 关掉 seqscan 后若计划里出现 {@code Sort} 节点，就说明这份对齐被破坏了 ——
     * 例如有人往排序列里插了一个新列，或改了 NULLS 顺序。
     */
    @Test
    void listOpenCanServeItsOrderingFromTheIndex() {
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set enable_seqscan = off");
                try (ResultSet rs = statement.executeQuery(
                        "explain (format json) select id from todo_items "
                                + "where user_id = '" + userId + "'::uuid and completed = false "
                                + "order by due_date, due_time nulls last, created_at limit 70")) {
                    rs.next();
                    return rs.getString(1);
                } finally {
                    statement.execute("set enable_seqscan = on");
                }
            }
        });

        assertThat(plan)
                .as("出现 Sort 节点说明排序没法由索引提供，候选查询会退化成全表扫描 + 外部排序")
                .doesNotContain("\"Node Type\": \"Sort\"");
    }

    @Test
    void listOpenReturnsNothingForAUserWithoutPendingTodos() {
        insertTodo(otherUserId, DATE, LocalTime.of(9, 0), "别人的", false);

        assertThat(todoItemMapper.listOpenForAssistant(userId, 70)).isEmpty();
    }

    // ---------- fixtures ----------

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?::uuid, ?, ?, 'x', ?)",
                id, name, name, name);
        return id;
    }

    private UUID insertTodo(UUID owner, LocalDate date, LocalTime time, String title, boolean completed) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into todo_items (id, user_id, due_date, title, due_time, completed) "
                        + "values (?::uuid, ?::uuid, ?::date, ?, ?::time, ?)",
                id, owner, date, title, time == null ? null : time.toString(), completed);
        return id;
    }

    private UUID insertIdOfTitle(String title) {
        return jdbc.queryForObject("select id from todo_items where title = ?", UUID.class, title);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
}
