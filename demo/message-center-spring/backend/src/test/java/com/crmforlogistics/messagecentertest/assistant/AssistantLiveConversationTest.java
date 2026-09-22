package com.crmforlogistics.messagecentertest.assistant;

import com.crmforlogistics.messagecenter.App;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecentertest.ApplicationIntegrationTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * 实施文档 §2.5 验收第 2、3 条的<b>可重跑</b>版本：三轮真实对话端到端。
 *
 * <h2>为什么这条验收不能只用 mock 测</h2>
 * 单元测试把每一环都测了，但**没有一条测试同时经过**：真实提示词（含真候选清单）→
 * 真模型（真温度、真输出形态）→ 解析器 → 白名单策略 → 落库的待确认动作 →
 * 真数据库（含 V82/V83 迁移、jsonb、CHECK）→ 真安全链。这一整条链上的缝只有这里能兜住：
 * YAML 里少写一个环境变量、条件注解判错、控制器漏配、jsonb 写反 —— 每一样都能让单元测试全绿。
 *
 * <h2>为什么默认跳过</h2>
 * 它要打外网、要真凭据、耗时数十秒（几次模型调用），不适合放进每次构建。
 * 门控两层：没有 {@code AI_API_KEY} 直接跳过；没有 Docker 也跳过（与其它集成测试同口径）。
 * 想跑它：
 * <pre>
 *   source /tmp/ai-03/env.sh   # AI_BASE_URL / AI_API_KEY / AI_MODEL
 *   mvn -o test -Dtest=AssistantLiveConversationTest
 * </pre>
 *
 * <h2>为什么不用 {@code @WebMvcTest}</h2>
 * {@code @WebMvcTest} 会把编排链路的 bean 全部 mock 掉，那就正好绕过了这条测试要验的东西。
 * 这里用真实的 {@code App} 上下文 + MockMvc：安全链是真的（{@code SecurityConfig} 生效），
 * 身份仍走 {@code SecurityUtil.currentUserId()}（靠 {@code .with(user(...))} 提供）。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        classes = {App.class, ApplicationIntegrationTestConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.profiles.active=test",
                "app.chatapp-sync-enabled=false",
                "app.chatapp-outbox-enabled=false",
                "app.chatapp-webhook-worker-enabled=false",
                "app.chatapp-broadcast-worker-enabled=false",
                "app.chatapp-template-reconcile-enabled=false",
                // 这三行是这个测试与 AppIntegrationTest 唯一的差别：把助手打开。
                "assistant.enabled=true",
                // 提示词回归（0.3 / 阶段 2）验的就是 temperature=0.1；这里保持一致，
                // 否则「模型行为」这一格在测试里和生产里不是同一个东西。
                "assistant.timeout-seconds=60"
        })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")
class AssistantLiveConversationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定 id：每次 {@code @BeforeEach} 先删后插，避免多次运行撞唯一索引。 */
    private static final UUID USER = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000a1a1");
    private static final UUID SUMMARY_TODO = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000b1");
    private static final UUID BUDGET_TODO = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000b2");
    private static final UUID CONVERSATION = UUID.fromString("cccccccc-0000-4000-8000-0000000000c1");

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
        // 与 AppIntegrationTest 同口径：MinIO 只给假地址，启动阶段不会去连。
        registry.add("minio.endpoint", () -> "http://localhost:9999");
        registry.add("minio.access-key", () -> "test");
        registry.add("minio.secret-key", () -> "test");
        registry.add("minio.bucket", () -> "test");
        registry.add("credential.master-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // 助手凭据走环境变量，不落盘（与阶段 0.3 探针同一份来源）。
        registry.add("assistant.base-url", () -> env("AI_BASE_URL", "https://api.deepseek.com"));
        registry.add("assistant.api-key", () -> env("AI_API_KEY", ""));
        registry.add("assistant.model", () -> env("AI_MODEL", "deepseek-chat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    /**
     * 每次从零开始：先删用户（V75/V82/V83 都是 {@code on delete cascade}，子行一起走），
     * 再插一个用户与两条<b>未完成</b>待办 —— 只有未完成的待办才会进候选清单，
     * 而「引用类参数必须命中候选」是解析器的硬校验。
     */
    @BeforeEach
    void seed() {
        jdbc.update("delete from users where id = ?::uuid", USER.toString());
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?::uuid, ?, ?, 'x', '助手活测用户')",
                USER.toString(), "assistant-live-" + USER, "assistant-live-" + USER);

        LocalDate today = LocalDate.now(ZONE);
        seedTodo(SUMMARY_TODO, "整理上周会议纪要", today.plusDays(3), LocalTime.of(10, 0));
        seedTodo(BUDGET_TODO, "提交季度预算初稿", today.plusDays(17), LocalTime.of(9, 30));
    }

    private void seedTodo(UUID id, String title, LocalDate date, LocalTime time) {
        jdbc.update("insert into todo_items (id, user_id, due_date, title, due_time, completed) "
                        + "values (?::uuid, ?::uuid, ?, ?, ?, false)",
                id.toString(), USER.toString(), date, title, time);
    }

    /**
     * §2.5 第 2 条：三轮真实对话。第 3 条（审计可查）一并在这里断言。
     *
     * <p>关于第三轮的表述：文档写的是「同一条再问一次 → 出确认卡片 → 取消，待办状态不变」。
     * 这里换成另一条未完成待办，原因是第二轮的确认已经把「同一条」标记完成了，
     * 它随即离开候选清单 —— 再问一次会（正确地）得到「没找到」，那样就验不到「取消」这条路。
     * 要验的行为（出卡片 → 取消 → 状态不变）与文档一致。
     */
    @Test
    void threeRealConversationsEndToEnd() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);

        // ---------- ①「帮我建个关于张总的待办」→ 被追问 → 补齐 → 创建成功 ----------
        JsonNode asked = post("/api/assistant/messages",
                Map.of("conversationId", CONVERSATION, "text", "帮我建一个关于张总的待办"));
        assertThat(asked.get("kind").asText())
                .as("只给了标题没给日期，必须追问而不是编一个日期；实际响应=%s", asked)
                .isEqualTo("QUESTION");

        JsonNode created = post("/api/assistant/messages", conversation(
                "明天下午三点和张总确认报价，帮我建个待办",
                Map.of("role", "user", "text", "帮我建一个关于张总的待办"),
                Map.of("role", "assistant", "text", asked.get("message").asText())));
        assertThat(created.get("kind").asText())
                .as("todo.create 在白名单里，补全参数后应当直接执行；实际响应=%s", created)
                .isEqualTo("EXECUTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from todo_items where user_id = ?::uuid and title like '%张总%' "
                        + "and due_date = ? and due_time = ?",
                Integer.class, USER.toString(), tomorrow, LocalTime.of(15, 0)))
                .as("模型说的「明天下午三点」必须真的落到 %s 15:00", tomorrow)
                .isEqualTo(1);

        // ---------- ②「标记完成 xx」→ 出确认卡片 → 确认后成功 ----------
        JsonNode card = post("/api/assistant/messages",
                Map.of("conversationId", CONVERSATION, "text", "帮我标记完成整理上周会议纪要那条"));
        assertThat(card.get("kind").asText())
                .as("todo.complete 不在白名单，必须先出确认卡片；实际响应=%s", card)
                .isEqualTo("CONFIRMATION_REQUIRED");
        JsonNode proposal = card.get("proposal");
        assertThat(proposal.get("tool").asText()).isEqualTo("todo.complete");
        assertThat(proposal.get("summary").asText())
                .as("确认卡片的摘要必须带标题与日期，否则用户无法判断模型认的是哪一条")
                .contains("整理上周会议纪要")
                .contains(tomorrow.plusDays(2).toString());
        assertThat(completed(SUMMARY_TODO))
                .as("卡片阶段绝不能已经执行")
                .isFalse();

        JsonNode confirmed = post("/api/assistant/actions/" + proposal.get("pendingActionId").asText() + "/confirm", null);
        assertThat(confirmed.get("kind").asText())
                .as("确认后应当真的执行；实际响应=%s", confirmed)
                .isEqualTo("EXECUTED");
        assertThat(completed(SUMMARY_TODO)).isTrue();

        // ---------- ③ 再出一次卡片 → 取消 → 状态不变 ----------
        JsonNode cardAgain = post("/api/assistant/messages",
                Map.of("conversationId", CONVERSATION, "text", "帮我标记完成提交季度预算初稿那条"));
        assertThat(cardAgain.get("kind").asText())
                .as("实际响应=%s", cardAgain)
                .isEqualTo("CONFIRMATION_REQUIRED");
        String cancelledId = cardAgain.get("proposal").get("pendingActionId").asText();

        JsonNode cancelled = post("/api/assistant/actions/" + cancelledId + "/cancel", null);
        assertThat(cancelled.get("kind").asText()).isEqualTo("ANSWER");
        assertThat(completed(BUDGET_TODO))
                .as("取消之后待办状态必须一动不动")
                .isFalse();
        assertThat(jdbc.queryForObject("select status from assistant_pending_actions where id = ?::uuid",
                String.class, cancelledId))
                .isEqualTo("CANCELLED");

        // ---------- §2.5 第 3 条：库里能回答「AI 做了什么」 ----------
        List<Map<String, Object>> audit = jdbc.queryForList(
                "select decision, tool_name, policy, outcome, utterance, model "
                        + "from assistant_action_audit where user_id = ?::uuid", USER.toString());
        assertThat(audit).as("每一轮都要留下流水").hasSizeGreaterThanOrEqualTo(5);
        assertThat(audit).as("白名单直接执行的那一轮要能看出是 AUTO 且成功了")
                .anySatisfy(row -> {
                    assertThat(row.get("decision")).isEqualTo("call");
                    assertThat(row.get("tool_name")).isEqualTo("todo.create");
                    assertThat(row.get("policy")).isEqualTo("AUTO");
                    assertThat(row.get("outcome")).isEqualTo("EXECUTED");
                });
        assertThat(audit).as("发确认卡片的那一轮要能看出「还没执行」")
                .anySatisfy(row -> {
                    assertThat(row.get("policy")).isEqualTo("CONFIRM");
                    assertThat(row.get("outcome")).isEqualTo("PENDING");
                });
        assertThat(audit).as("取消也要留痕，且不是 EXECUTED")
                .anySatisfy(row -> {
                    assertThat(row.get("policy")).isEqualTo("CANCELLED");
                    assertThat(row.get("outcome")).isEqualTo("CANCELLED");
                });
        assertThat(audit).as("原话必须留着 —— 自然语言匹配的争议只能靠它复盘")
                .anySatisfy(row -> assertThat(String.valueOf(row.get("utterance"))).contains("标记完成"));
        // 模型名只该出现在**模型轮次**上（有原话的那几行）。确认 / 取消那两行是
        // AssistantPendingActionService 写的服务端流水 —— 那一轮没有调模型，本来就没有模型名可记，
        // 硬填一个「当前配置的模型名」反而是编造。
        assertThat(audit).filteredOn(row -> row.get("utterance") != null)
                .as("模型轮次必须记录实际使用的模型，便于换模型后回溯")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("model")).isNotNull());
        assertThat(audit).filteredOn(row -> row.get("utterance") == null)
                .as("服务端自己写的流水不该带模型名")
                .allSatisfy(row -> assertThat(row.get("model")).isNull());
    }

    /**
     * 顺带把「确认接口不接受参数重放」钉在真链路上：卡片上的参数改了也没用，
     * 因为确认只按 {@code pendingActionId} 取服务端落库的那一份。
     */
    @Test
    void confirmationOnlyCarriesTheIdSoArgumentsCannotBeReplayedByTheClient() throws Exception {
        JsonNode card = post("/api/assistant/messages",
                Map.of("conversationId", CONVERSATION, "text", "帮我标记完成整理上周会议纪要那条"));
        assertThat(card.get("kind").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        String id = card.get("proposal").get("pendingActionId").asText();

        // 带上一个伪造的 body（试图把这次确认指到别的待办/别的参数上）。
        JsonNode result = post("/api/assistant/actions/" + id + "/confirm",
                Map.of("todoId", BUDGET_TODO.toString(), "completed", false));

        assertThat(result.get("kind").asText()).isEqualTo("EXECUTED");
        assertThat(completed(SUMMARY_TODO)).as("按服务端落库的那一份执行").isTrue();
        assertThat(completed(BUDGET_TODO)).as("请求体里的 todoId 必须被忽略").isFalse();
    }

    /** 转发给 {@link TodoItemMapper} 之外的最小查询：直接读库，避免用被测代码验被测代码。 */
    private boolean completed(UUID todoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select completed from todo_items where id = ?::uuid", Boolean.class, todoId.toString()));
    }

    private Map<String, Object> conversation(String text, Map<String, String>... history) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", CONVERSATION);
        body.put("history", List.of(history));
        body.put("text", text);
        return body;
    }

    private JsonNode post(String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path).with(user(USER.toString()));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .characterEncoding(StandardCharsets.UTF_8.name())
                    .content(objectMapper.writeValueAsBytes(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("%s -> HTTP %d, body=%s", path, response.getStatus(), text).isEqualTo(200);
        return objectMapper.readTree(text);
    }
}
