package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantActionAuditEntity;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecentertest.mapper.AssistantMapperTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 助手两张表的真实落库验证（真 PostgreSQL）。
 *
 * <p>为什么必须打真库而不是 mock：这里要验的东西全在 SQL 与 DDL 里 ——
 * jsonb 的往返、{@code where user_id} 是否真的挡住别人的行、
 * 以及 {@code and status = 'PENDING'} 这个原子抢占条件是否生效。
 * 语法写错时 mock 测试会全绿，要到线上第一次点确认才炸。
 *
 * <p>同时覆盖 V82 / V83 两个迁移真的能跑起来（Flyway 在 {@link #migrate()} 里全量执行），
 * 以及两条 {@code CHECK} 约束真的在拦。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AssistantMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AssistantActionStoreSqlTest {

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

    @Autowired AssistantPendingActionMapper pendingMapper;
    @Autowired AssistantActionAuditMapper auditMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID user;
    private UUID otherUser;

    @BeforeEach
    void seedUsers() {
        // 用户名必须每个测试方法都不同：这个类里 8 个方法共用同一个容器里的同一个库，
        // 而 seedUsers 是 @BeforeEach —— 写固定名字会让第二个方法撞 ux_users_username_normalized
        // （第 2 个方法起全军覆没，症状还很像「迁移没跑起来」）。这里只求唯一，不求可读。
        user = insertUser("assistant-sql-a-" + UUID.randomUUID());
        otherUser = insertUser("assistant-sql-b-" + UUID.randomUUID());
    }

    // ---------- assistant_pending_actions ----------

    @Test
    void pendingActionRoundTripsIncludingJsonbArguments() {
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.complete",
                Map.of("todoId", "11111111-1111-4111-8111-111111111111", "completed", true)));

        AssistantPendingActionEntity loaded = pendingMapper.findById(id, user);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getToolName()).isEqualTo("todo.complete");
        assertThat(loaded.getStatus()).isEqualTo("PENDING");
        assertThat(loaded.getSummary()).isEqualTo("标记完成：「和张总确认报价」 2026-09-22 15:00");
        assertThat(loaded.getArgumentsJson())
                .as("jsonb 读出的是 JSON 原文，确认时要拿它重新校验")
                .contains("11111111-1111-4111-8111-111111111111")
                .contains("\"completed\"");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getDecidedAt()).isNull();
    }

    @Test
    void theCardFrontIsReadBackIncludingItsChangesAndAnHonestNullInside() {
        UUID id = UUID.randomUUID();
        AssistantPendingActionEntity rich = pending(id, user, "todo.update",
                Map.of("todoId", "11111111-1111-4111-8111-111111111111", "date", "2026-09-24"));
        rich.setChangesJson("[{\"field\":\"when\",\"label\":\"时间\",\"before\":\"2026-09-22 15:00\","
                + "\"after\":\"2026-09-24\"}]");
        pendingMapper.insert(rich);

        assertThat(pendingMapper.findById(id, user).getChangesJson())
                .as("卡片上「给用户看的那一面」要能读回来：用户按下确认时同意的是他当时看到的那份文字")
                .isNotNull();
        // 断言语义而不是文本形状：jsonb 会在存储时规范化 JSON（键序、冒号后的空格），
        // 读回来的字符串与写进去的**不**逐字相等。任何按字符串比对的地方都会在这里翻车。
        JsonNode changes = readJson(pendingMapper.findById(id, user).getChangesJson());
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("field").asText()).isEqualTo("when");
        assertThat(changes.get(0).get("label").asText()).isEqualTo("时间");
        assertThat(changes.get(0).get("before").asText()).isEqualTo("2026-09-22 15:00");
        assertThat(changes.get(0).get("after").asText()).isEqualTo("2026-09-24");

        // 内层的 before=null 必须原样往返。它在前端会被渲染成「当前未知」——
        // 若在存取层被吞掉或改写成空串，「不知道」就变成了「没有变化」。
        UUID withNull = UUID.randomUUID();
        AssistantPendingActionEntity plain = pending(withNull, user, "todo.update", Map.of("note", "已确认报价"));
        plain.setChangesJson("[{\"field\":\"note\",\"label\":\"备注\",\"before\":null,\"after\":\"已确认报价\"}]");
        pendingMapper.insert(plain);

        JsonNode nullBefore = readJson(pendingMapper.findById(withNull, user).getChangesJson());
        assertThat(nullBefore.get(0).get("before").isNull())
                .as("before=null 本身就是「未知」这个取值，不能被吞成字段缺席、也不能变成空串")
                .isTrue();
        assertThat(nullBefore.get(0).get("after").asText()).isEqualTo("已确认报价");
    }

    @Test
    void aPendingActionWithoutABeforeStoresSqlNullNotAnEmptyArray() {
        // 「没有『改前』可言」（新建 / 删除）与「有这个概念但这次为空」是两件事：
        // 用 '[]' 把前者压成后者，事后就分不出这张卡片到底是哪一种。
        // 这一格同时钉住 cast(null as jsonb) 真的能写进去 —— 那是 mapper 里最容易写错的一处。
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.create",
                Map.of("title", "新待办", "date", "2026-09-23")));

        assertThat(pendingMapper.findById(id, user).getChangesJson()).isNull();
    }

    @Test
    void anotherUsersPendingActionIsInvisible() {
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.delete", Map.of("todoId", "x")));

        assertThat(pendingMapper.findById(id, otherUser)).isNull();
        assertThat(pendingMapper.markDecided(id, otherUser, "CONFIRMED"))
                .as("改动别人的待确认动作必须影响 0 行").isZero();
        assertThat(pendingMapper.findById(id, user).getStatus()).isEqualTo("PENDING");
    }

    /**
     * 原子抢占：{@code and status = 'PENDING'} 让第二次确认影响 0 行。
     * 这是「双击确认只执行一次」的实现方式 —— 没有它，两个并发请求都会读到 PENDING。
     */
    @Test
    void thePendingClaimOnlySucceedsOnce() {
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.delete", Map.of("todoId", "x")));

        assertThat(pendingMapper.markDecided(id, user, "CONFIRMED")).isEqualTo(1);
        assertThat(pendingMapper.markDecided(id, user, "CONFIRMED")).isZero();
        assertThat(pendingMapper.markDecided(id, user, "CANCELLED")).isZero();
        assertThat(pendingMapper.markDecided(id, user, "EXPIRED")).isZero();

        assertThat(pendingMapper.findById(id, user).getDecidedAt()).isNotNull();
    }

    @Test
    void statusIsConstrainedByTheCheckConstraint() {
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.delete", Map.of("todoId", "x")));

        assertThatThrownBy(() -> jdbc.update(
                "update assistant_pending_actions set status = 'SOMETHING_ELSE' where id = ?::uuid",
                id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assistant_pending_actions_status");
    }

    @Test
    void deletingTheUserCascadesTheirPendingActions() {
        UUID id = UUID.randomUUID();
        pendingMapper.insert(pending(id, user, "todo.delete", Map.of("todoId", "x")));

        jdbc.update("delete from users where id = ?::uuid", user.toString());

        assertThat(jdbc.queryForObject(
                "select count(*) from assistant_pending_actions where id = ?::uuid", Integer.class,
                id.toString())).isZero();
    }

    // ---------- assistant_action_audit ----------

    @Test
    void auditRowsRoundTripIncludingJsonbArgumentsAndANullWhenThereAreNone() {
        auditMapper.insert(audit(user, "第一轮", "reply", null, null, "ANSWERED"));
        auditMapper.insert(audit(user, "第二轮", "call", "todo.complete",
                Map.of("todoId", "11111111-1111-4111-8111-111111111111", "completed", true), "EXECUTED"));

        List<AssistantActionAuditEntity> rows = auditMapper.listByUser(user, 10);
        assertThat(rows).hasSize(2);

        AssistantActionAuditEntity call = rows.stream()
                .filter(row -> "call".equals(row.getDecision())).findFirst().orElseThrow();
        assertThat(call.getOutcome()).isEqualTo("EXECUTED");
        assertThat(call.getArgumentsJson()).contains("completed").contains("11111111-1111-4111-8111-111111111111");
        assertThat(call.getModel()).isEqualTo("deepseek-chat");
        assertThat(call.getLatencyMs()).isEqualTo(900);

        AssistantActionAuditEntity answered = rows.stream()
                .filter(row -> "reply".equals(row.getDecision())).findFirst().orElseThrow();
        assertThat(answered.getArgumentsJson()).as("没有参数的一轮存 NULL 而不是 '{}'").isNull();
    }

    /**
     * 排序用一个显式的 {@code created_at} 来验，而不是依赖两次插入的时间戳差异 ——
     * 后者在同一个毫秒里就会变成偶发失败。
     */
    @Test
    void auditIsReadNewestFirstAndHonoursTheLimit() {
        insertAuditAt(user, "最旧", "now() - interval '2 hours'");
        insertAuditAt(user, "中间", "now() - interval '1 hour'");
        insertAuditAt(user, "最新", "now()");

        assertThat(auditMapper.listByUser(user, 10))
                .extracting(AssistantActionAuditEntity::getUtterance)
                .containsExactly("最新", "中间", "最旧");
        assertThat(auditMapper.listByUser(user, 2))
                .extracting(AssistantActionAuditEntity::getUtterance)
                .containsExactly("最新", "中间");
    }

    @Test
    void auditOnlyReturnsTheCurrentUsersRows() {
        auditMapper.insert(audit(user, "我的", "reply", null, null, "ANSWERED"));
        auditMapper.insert(audit(otherUser, "别人的", "reply", null, null, "ANSWERED"));

        List<AssistantActionAuditEntity> rows = auditMapper.listByUser(user, 10);

        assertThat(rows).extracting(AssistantActionAuditEntity::getUtterance).containsExactly("我的");
    }

    @Test
    void theTrailIsReadablePerConversationInTurnOrderWithDecisionsLast() {
        UUID conversation = UUID.randomUUID();
        // 四行的 created_at 都取数据库的 now()：它们会落在同一毫秒附近 ——
        // 这正是「只按 created_at 排不出轮次」的现场，顺序只能靠 turn_index 给。
        insertAuditAtTurn(user, conversation, "turn 2", 2);
        insertAuditAtTurn(user, conversation, "turn 0", 0);
        insertAuditAtTurn(user, conversation, "turn 1", 1);
        // 用户在稍后的**另一次请求**里点了确认：不属于任何一轮，turn_index 留 null。
        insertAuditAtTurn(user, conversation, "confirmed later", null);

        List<AssistantActionAuditEntity> trail = auditMapper.listByConversation(user, conversation);

        assertThat(trail).extracting(AssistantActionAuditEntity::getUtterance)
                .as("按轮次正序；确认行发生在轮次序列之外，排在最后")
                .containsExactly("turn 0", "turn 1", "turn 2", "confirmed later");
        assertThat(trail).extracting(AssistantActionAuditEntity::getTurnIndex)
                .containsExactly(0, 1, 2, null);

        assertThat(auditMapper.listByConversation(otherUser, conversation))
                .as("会话号只是分组标签、不是凭证：换个人来查必须是空的")
                .isEmpty();
    }

    @Test
    void auditOutcomeAndPolicyAreConstrained() {
        assertThatThrownBy(() -> auditMapper.insert(audit(user, "坏的", "reply", null, null, "NOT_A_STATE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assistant_action_audit_outcome");

        AssistantActionAuditEntity bogusPolicy = audit(user, "坏的", "reply", null, null, "ANSWERED");
        bogusPolicy.setPolicy("NOT_A_POLICY");
        assertThatThrownBy(() -> auditMapper.insert(bogusPolicy))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assistant_action_audit_policy");

        AssistantActionAuditEntity badDecision = audit(user, "坏的", "not_a_decision", null, null, "ANSWERED");
        assertThatThrownBy(() -> auditMapper.insert(badDecision))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assistant_action_audit_decision");
    }

    @Test
    void pendingAndConfirmedPoliciesAreBothAcceptedBecauseAPendingActionHasNoOutcomeYet() {
        AssistantActionAuditEntity pendingTurn = audit(user, "帮我标记完成", "call", "todo.complete",
                Map.of("todoId", "11111111-1111-4111-8111-111111111111"), "PENDING");
        pendingTurn.setPolicy("CONFIRM");

        assertThat(auditMapper.insert(pendingTurn)).isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private UUID insertUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?::uuid, ?, ?, 'x', ?)",
                id.toString(), username, username, username);
        return id;
    }

    private void insertAuditAt(UUID userId, String utterance, String createdAtExpression) {
        jdbc.update("insert into assistant_action_audit (user_id, utterance, decision, outcome, created_at) "
                        + "values (?::uuid, ?, 'reply', 'ANSWERED', " + createdAtExpression + ")",
                userId.toString(), utterance);
    }

    /** 一次 respond 内的一轮：写一行带 {@code turn_index} 的审计。{@code null} 表示「轮次序列之外」。 */
    private void insertAuditAtTurn(UUID userId, UUID conversationId, String utterance, Integer turnIndex) {
        jdbc.update("insert into assistant_action_audit "
                        + "(user_id, conversation_id, utterance, decision, outcome, turn_index) "
                        + "values (?::uuid, ?::uuid, ?, 'call', 'EXECUTED', ?)",
                userId.toString(), conversationId.toString(), utterance, turnIndex);
    }

    private static AssistantPendingActionEntity pending(UUID id, UUID userId, String tool,
                                                       Map<String, Object> arguments) {
        AssistantPendingActionEntity entity = new AssistantPendingActionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setConversationId(UUID.randomUUID());
        entity.setToolName(tool);
        entity.setArgumentsJson(writeJson(arguments));
        entity.setSummary("标记完成：「和张总确认报价」 2026-09-22 15:00");
        entity.setStatus("PENDING");
        entity.setExpiresAt(Instant.parse("2026-09-21T02:10:00Z"));
        return entity;
    }

    private static AssistantActionAuditEntity audit(UUID userId, String utterance, String decision,
                                                    String tool, Map<String, Object> arguments, String outcome) {
        AssistantActionAuditEntity entity = new AssistantActionAuditEntity();
        entity.setUserId(userId);
        entity.setConversationId(UUID.randomUUID());
        entity.setUtterance(utterance);
        entity.setDecision(decision);
        entity.setToolName(tool);
        entity.setArgumentsJson(arguments == null ? null : writeJson(arguments));
        entity.setArgumentsDigest(arguments == null ? null : "a".repeat(64));
        entity.setPolicy(null);
        entity.setOutcome(outcome);
        entity.setModel("deepseek-chat");
        entity.setLatencyMs(900);
        return entity;
    }

    private static String writeJson(Map<String, Object> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(new LinkedHashMap<>(arguments));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** jsonb 读回来的是**被规范化过的** JSON 文本，所以比语义、不比字符串。 */
    private static JsonNode readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
