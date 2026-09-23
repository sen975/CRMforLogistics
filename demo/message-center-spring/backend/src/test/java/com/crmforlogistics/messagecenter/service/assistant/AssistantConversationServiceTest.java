package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 一轮编排的完整行为。
 *
 * <p>这里装配的是<b>真的</b>解析器、策略、注册表、待办服务与待确认服务，只把
 * 「模型」与「两张存储表」换成假的。理由：被测行为的主体是「谁来判定」与「判定之后发生了什么」，
 * mock 掉策略或解析器就等于把被测行为本身 mock 掉。
 *
 * <p>每个动作类用例都同时断言「<b>另一条路径没有发生</b>」：只断言返回值是不够的 ——
 * 「弹了确认卡片」与「弹了确认卡片并且什么都没执行」是两件事，
 * 而后者才是这套设计里真正值钱的那部分。
 */
class AssistantConversationServiceTest {

    private static final AssistantConfig CONFIG = AssistantFixtures.config();

    private final TodoItemMapper todoMapper = mock(TodoItemMapper.class);
    private final AssistantPendingActionMapper pendingMapper = mock(AssistantPendingActionMapper.class);
    private final AssistantContextBuilder contextBuilder = mock(AssistantContextBuilder.class);
    private final AssistantModelClient modelClient = mock(AssistantModelClient.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    /** 对话落地在单元测试里是 mock：它写库，而这里要验的是「有没有记、记了什么」。 */
    private final AssistantConversationLogService conversationLog = mock(AssistantConversationLogService.class);

    private final ToolRegistry registry = AssistantFixtures.registry(new TodoItemService(todoMapper));
    private final AssistantDecisionParser parser =
            new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());
    private final AssistantActionPolicy policy = new AssistantActionPolicy();

    private AssistantConversationService service;

    @BeforeEach
    void setUp() {
        when(contextBuilder.build(eq(AssistantFixtures.USER), anyInt())).thenReturn(AssistantFixtures.context());
        AssistantPendingActionService pending = new AssistantPendingActionService(
                pendingMapper, parser, registry, audit, CONFIG,
                AssistantFixtures.objectMapper(),
                Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC));
        service = new AssistantConversationService(
                contextBuilder,
                new AssistantPromptBuilder(registry, CONFIG, AssistantFixtures.objectMapper()),
                modelClient, parser, policy, pending, audit, conversationLog,
                new AssistantRequestGuard(CONFIG), registry, CONFIG);
    }

    // ---------- ask / reply：不碰任何存储 ----------

    @Test
    void missingParametersBecomeAQuestionAndWriteNothing() {
        modelReplies("""
                {"decision":"ask","question":"这条待办安排在什么时间？","missing":["date"]}
                """);

        AssistantTurnResult result = turn("帮我建一个关于张总的待办");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.QUESTION);
        assertThat(result.message()).isEqualTo("这条待办安排在什么时间？");
        assertThat(result.missing()).containsExactly("date");
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    @Test
    void anUnrelatedRequestIsAnsweredWithoutAnyAction() {
        modelReplies("{\"decision\":\"reply\",\"reply\":\"我只能帮你管理待办。\"}");

        AssistantTurnResult result = turn("今天天气怎么样");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(result.message()).isEqualTo("我只能帮你管理待办。");
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    // ---------- 白名单内：直接执行 ----------

    @Test
    void createIsExecutedImmediately() {
        when(todoMapper.insert(any())).thenReturn(1);
        modelReplies("""
                {"decision":"call","tool":"todo.create",
                 "arguments":{"title":"和张总确认报价","date":"2026-09-22","time":"15:00"}}
                """);

        AssistantTurnResult result = turn("明天下午三点和张总确认报价，帮我建个待办");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.EXECUTED);
        assertThat(result.message()).contains("已创建待办").contains("和张总确认报价");

        ArgumentCaptor<TodoItemEntity> inserted = ArgumentCaptor.forClass(TodoItemEntity.class);
        verify(todoMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).as("身份只来自认证上下文").isEqualTo(AssistantFixtures.USER);
        assertThat(inserted.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(inserted.getValue().getDueTime()).isEqualTo(LocalTime.of(15, 0));
        verifyNoInteractions(pendingMapper);
    }

    // ---------- 白名单外：确认卡片，且没有执行 ----------

    @Test
    void completeOnlyProducesAConfirmationCardAndWritesNothingElse() {
        modelReplies("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"%s","completed":true}}
                """.formatted(AssistantFixtures.TODO_QUOTE));

        AssistantTurnResult result = turn("帮我标记完成和张总确认报价那条");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);
        assertThat(result.proposal()).isNotNull();
        assertThat(result.proposal().tool()).isEqualTo("todo.complete");
        // 确认卡片上的摘要必须带标题与日期 —— 这是歧义消解的唯一手段。
        assertThat(result.proposal().summary()).contains("和张总确认报价").contains("2026-09-22");

        // 关键断言：只落了待确认行，**没有碰待办表**。
        ArgumentCaptor<AssistantPendingActionEntity> pending =
                ArgumentCaptor.forClass(AssistantPendingActionEntity.class);
        verify(pendingMapper).insert(pending.capture());
        assertThat(pending.getValue().getUserId()).isEqualTo(AssistantFixtures.USER);
        assertThat(pending.getValue().getToolName()).isEqualTo("todo.complete");
        assertThat(pending.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(pending.getValue().getExpiresAt())
                .isAfter(Instant.parse("2026-09-21T02:00:00Z"));
        verifyNoInteractions(todoMapper);
    }

    @Test
    void deleteAndUpdateAlsoRequireConfirmation() {
        modelReplies("""
                {"decision":"call","tool":"todo.delete",
                 "arguments":{"todoId":"%s"}}
                """.formatted(AssistantFixtures.TODO_MINUTES));
        assertThat(turn("删掉整理上周会议纪要那条").kind())
                .isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);

        modelReplies("""
                {"decision":"call","tool":"todo.update",
                 "arguments":{"todoId":"%s","date":"2026-09-23"}}
                """.formatted(AssistantFixtures.TODO_QUOTE));
        AssistantTurnResult updated = turn("把和张总确认报价改到 9 月 23 号");

        assertThat(updated.kind()).isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);
        assertThat(updated.proposal().summary()).contains("和张总确认报价").contains("2026-09-23");
        verify(todoMapper, never()).update(any(), any(), any(), any(), any(), any());
    }

    // ---------- 拒绝路径 ----------

    @Test
    void aFabricatedTodoIdIsRejectedWithoutWritingAnything() {
        modelReplies("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"99999999-9999-4999-8999-999999999999","completed":true}}
                """);

        AssistantTurnResult result = turn("帮我标记完成去年那个项目复盘");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(AssistantException.REQUEST_INVALID);
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    @Test
    void anInventedToolNameIsRejectedAndIsNotRetriedIntoSomeOtherTool() {
        modelReplies("{\"decision\":\"call\",\"tool\":\"wechat.send\",\"arguments\":{}}");

        AssistantTurnResult result = turn("帮我给张总发个微信");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        verifyNoInteractions(todoMapper, pendingMapper);
        // 不可重试：进入 call 分支后的失败不给第二次机会，避免被拒的诉求被改写成合法工具。
        verify(modelClient, times(1)).complete(any());
    }

    @Test
    void aStructuralFailureIsRetriedExactlyOnce() {
        modelReplies("我不确定，能再说一次吗？", "{\"decision\":\"reply\",\"reply\":\"好的\"}");

        AssistantTurnResult result = turn("随便说点什么");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        verify(modelClient, times(2)).complete(any());
    }

    @Test
    void twoStructuralFailuresInARowEndTheTurnWithoutAnyAction() {
        modelReplies("不是 JSON", "仍然不是 JSON");

        AssistantTurnResult result = turn("帮我建个待办");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        verify(modelClient, times(2)).complete(any());
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    // ---------- 失败与身份 ----------

    @Test
    void aFailingToolIsReportedAsAnErrorNotAsSuccess() {
        when(todoMapper.insert(any())).thenThrow(new IllegalArgumentException("待办标题不能为空"));
        modelReplies("""
                {"decision":"call","tool":"todo.create","arguments":{"title":"x","date":"2026-09-22"}}
                """);

        AssistantTurnResult result = turn("建一条");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENT");

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo("FAILED");
    }

    @Test
    void providerFailuresPropagateInsteadOfBeingRenderedAsAMisunderstanding() {
        when(modelClient.complete(any())).thenThrow(
                new AssistantException(AssistantException.PROVIDER_UNAVAILABLE, "AI 服务暂时不可用，请稍后再试"));

        assertThatThrownBy(() -> turn("帮我建个待办"))
                .isInstanceOf(AssistantException.class)
                .hasMessageContaining("暂时不可用");
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    @Test
    void aMissingUserIsRefusedRatherThanDegraded() {
        assertThatThrownBy(() -> service.respond(null, AssistantFixtures.CONVERSATION, List.of(), "帮我建个待办"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 超长的原话在**任何模型调用之前**被拒绝。
     *
     * <p>这条用例原来住在 web 切片（{@code AssistantControllerTest}），断言的是
     * {@code verifyNoInteractions(conversations)}。入参整理搬进编排层之后它跟着搬过来，
     * 断言的对象换成更有分量的那个：<b>一次模型调用都没发生</b> —— 超长输入首先是一条成本边界。
     *
     * <p>与历史超限的处置刻意不同：历史是语境，丢掉最旧的不影响本轮意图；原话是这一次请求的
     * 全部意图，静默截断会让模型按半个诉求去执行。所以一个裁剪、一个报错。
     */
    @Test
    void anOversizeUtteranceIsRejectedBeforeAnyModelCall() {
        assertThatThrownBy(() -> turn("x".repeat(CONFIG.maxMessageChars() + 1)))
                .isInstanceOf(AssistantException.class)
                .hasMessageContaining("太长");

        verifyNoInteractions(modelClient);
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    /** 空话同样在编排之前就被挡下：模型没有机会为一句「什么都没说」编一个动作。 */
    @Test
    void aBlankUtteranceIsRejectedBeforeAnyModelCall() {
        assertThatThrownBy(() -> turn("   "))
                .isInstanceOf(AssistantException.class)
                .hasMessageContaining("请先输入");

        verifyNoInteractions(modelClient);
        verifyNoInteractions(todoMapper, pendingMapper);
    }

    // ---------- 审计 ----------

    @Test
    void everyTurnLeavesAnAuditRowWithTheUserUtterance() {
        modelReplies("{\"decision\":\"reply\",\"reply\":\"好\"}");

        turn("今天天气怎么样");

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().userId()).isEqualTo(AssistantFixtures.USER);
        assertThat(entry.getValue().utterance()).isEqualTo("今天天气怎么样");
        assertThat(entry.getValue().decision()).isEqualTo("reply");
        assertThat(entry.getValue().outcome()).isEqualTo("ANSWERED");
        assertThat(entry.getValue().conversationId()).isEqualTo(AssistantFixtures.CONVERSATION);
        assertThat(entry.getValue().model()).isEqualTo("deepseek-chat");
        assertThat(entry.getValue().latencyMs()).isNotNull();
    }

    @Test
    void aConfirmationCardIsAuditedAsPendingNotAsRejected() {
        modelReplies("""
                {"decision":"call","tool":"todo.delete","arguments":{"todoId":"%s"}}
                """.formatted(AssistantFixtures.TODO_BUDGET));

        turn("删掉提交季度预算初稿那条");

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().policy()).isEqualTo("CONFIRM");
        assertThat(entry.getValue().outcome()).isEqualTo("PENDING");
        assertThat(entry.getValue().toolName()).isEqualTo("todo.delete");
    }

    @Test
    void anInvalidTurnIsAuditedAsInvalid() {
        modelReplies("{\"decision\":\"call\",\"tool\":\"todo.nope\",\"arguments\":{}}");

        turn("随便");

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().decision()).isEqualTo("invalid");
        assertThat(entry.getValue().outcome()).isEqualTo("INVALID");
    }

    // ---------- 夹具 ----------

    private AssistantTurnResult turn(String text) {
        return service.respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, List.of(), text);
    }

    private void modelReplies(String... contents) {
        org.mockito.stubbing.OngoingStubbing<AssistantModelClient.ModelReply> stub =
                when(modelClient.complete(any()));
        for (String content : contents) {
            stub = stub.thenReturn(new AssistantModelClient.ModelReply(content, "deepseek-chat", 900));
        }
    }
}
