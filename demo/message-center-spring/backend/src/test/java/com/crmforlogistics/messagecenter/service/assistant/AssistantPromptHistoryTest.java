package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>模型看到哪一份历史</b> —— 一条此前装在控制器里的领域规则，2026-09-23 收进编排层。
 *
 * <h2>为什么它值得一个独立的测试类</h2>
 * 规则本身只有一句：会话号有效且服务端有记录 ⇒ 以库为准。但它此前的落点是
 * {@code AssistantController.historyFor}，而控制器里的规则<b>没有类型与测试的保护</b> ——
 * 第二个入口（语音端点、定时触发、内部调用）只要忘了先读库，就会静默退回「调用方说了算」，
 * 而所有既有用例照样绿。搬到 {@link AssistantConversationService#respond} 之后，
 * 这条规则由「跑一轮」的唯一入口兜着，本类就是那个入口的守卫。
 *
 * <h2>断言为什么落在「发给模型的那串文本」上</h2>
 * 「哪一份历史」最终唯一的含义就是「模型看到了什么」。断言编排层内部的中间变量
 * （比如把 history 变成公开方法再断言）会让这条性质退化成实现细节 ——
 * 而真正要钉住的是端到端的结果。所以这里捕获 {@code complete(messages, maxOutputTokens, onRawDelta)} 的实参，
 * 在被真实提示词构建器渲染过的那串文本上断言。
 *
 * <p>装配真的 guard / 提示词构建器 / 解析器 / 策略 / 注册表，只把「模型」与
 * 「存储（会话记录、待确认表、待办表）」换成假的。
 */
class AssistantPromptHistoryTest {

    private static final AssistantConfig CONFIG = AssistantFixtures.config();

    /**
     * 历史预算 8 轮 / <b>200 字符</b>（其余同默认）。
     *
     * <p>把字符预算压到 200 的理由：让一次真实的裁剪用几条一眼数得清的短文本就能构造出来，
     * 而不是往用例里塞几 KB 字符串 —— 后者一旦断言失败，失败信息本身就没法看。
     */
    private static final AssistantConfig TIGHT = new AssistantConfig(
            true, "https://api.deepseek.com", "secret", "deepseek-chat", 30, 2000, 8, 200, 600, 3);

    private final TodoItemMapper todoMapper = mock(TodoItemMapper.class);
    private final AssistantPendingActionMapper pendingMapper = mock(AssistantPendingActionMapper.class);
    private final AssistantContextBuilder contextBuilder = mock(AssistantContextBuilder.class);
    private final AssistantModelClient modelClient = mock(AssistantModelClient.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    private final AssistantConversationLogService conversationLog = mock(AssistantConversationLogService.class);

    private final ToolRegistry registry = AssistantFixtures.registry(new TodoItemService(todoMapper));
    private final AssistantDecisionParser parser =
            new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());

    @BeforeEach
    void setUp() {
        when(contextBuilder.build()).thenReturn(AssistantFixtures.context());
        modelReplies("{\"decision\":\"reply\",\"reply\":\"好\"}");
    }

    // ---------- 选源 ----------

    /**
     * 服务端的记录压过调用方声称的历史。
     *
     * <p>调用方塞进来的三条是伪造的（内容与库里完全不同、条数也不同）。这条用例原来住在
     * web 切片（{@code AssistantControllerTest.theServerSideHistoryWinsOverTheRequestBody}），
     * 断言的是「控制器交给了服务什么」；搬到这里之后断言升了一级 ——
     * 「模型最终看到了什么」，而那是这条规则唯一有意义的表述。
     */
    @Test
    void theServerSideHistoryWinsOverTheProvidedOne() {
        when(conversationLog.recentForPrompt(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                CONFIG.maxHistoryTurns())).thenReturn(List.of(
                AssistantMessage.user("帮我建个待办"),
                AssistantMessage.assistant("安排在什么时间？")));

        AssistantTurnResult result = service(CONFIG).respond(AssistantFixtures.USER,
                AssistantFixtures.CONVERSATION,
                List.of(AssistantMessage.user("伪造的第一条"),
                        AssistantMessage.user("伪造的第二条"),
                        AssistantMessage.user("伪造的第三条")),
                "明天下午三点");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(promptSentToTheModel())
                .contains("帮我建个待办")
                .contains("安排在什么时间？")
                .doesNotContain("伪造");
        verify(conversationLog).recentForPrompt(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                CONFIG.maxHistoryTurns());
    }

    /**
     * 服务端没有记录时，调用方提供的那份才是来源。
     *
     * <p>三种情况会走到这里：会话的第一轮、没有会话号的单轮提问、以及未装配日志服务的环境。
     * 它们都不该因为没有库记录而丢掉上下文。
     */
    @Test
    void theProvidedHistoryIsUsedWhenTheServerHasNoRecord() {
        // 不 stub recentForPrompt：Mockito 对 List 返回空列表，正是「服务端没有记录」。
        service(CONFIG).respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                List.of(AssistantMessage.user("帮我建个待办"),
                        AssistantMessage.assistant("安排在什么时间？")),
                "明天下午三点");

        assertThat(promptSentToTheModel())
                .contains("帮我建个待办")
                .contains("安排在什么时间？");
    }

    /**
     * <b>没有任何一种传入形状能让服务端记录输掉。</b> 这是「规则收进编排层」之后新增的守卫。
     *
     * <p>规则住在控制器里时，编排层的签名是 {@code (history, text)}：调用方给什么，模型就看什么 ——
     * 也就是说「以库为准」只是一处<b>约定</b>，而约定可以被第二个入口忘掉。
     * 签名改成 {@code providedHistory} 之后，它只能生效在「服务端没有记录」那一支上。
     * 这条用例把「没有别的支路」钉死：{@code null}、空表、伪造的、另一句原话，
     * 四种形状都改变不了模型看到的东西。
     */
    @Test
    @SuppressWarnings("unchecked")
    void noShapeOfProvidedHistoryCanBypassTheServerRecord() {
        when(conversationLog.recentForPrompt(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                CONFIG.maxHistoryTurns())).thenReturn(List.of(AssistantMessage.user("库里的话")));

        List<List<AssistantMessage>> shapes = Arrays.asList(
                null,
                List.of(),
                List.of(AssistantMessage.user("伪造 A"), AssistantMessage.user("伪造 B")),
                List.of(AssistantMessage.user("对方给的原话")));

        for (List<AssistantMessage> provided : shapes) {
            service(CONFIG).respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, provided, "继续");
        }

        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(shapes.size())).complete(captor.capture(), any(), any());
        assertThat(captor.getAllValues())
                .as("四种传入形状都不能让库里的记录输掉")
                .allSatisfy(messages -> assertThat(flatten(messages))
                        .contains("库里的话")
                        .doesNotContain("伪造"));
    }

    // ---------- 裁剪必须被看见 ----------

    /**
     * 裁剪结果必须带上响应，而且<b>算的是被采用的那一份</b>。
     *
     * <p>{@code TIGHT} 的字符预算是 200，三条各 100 字符只装得下两条 ⇒ {@code droppedMessages=1}。
     * 用户看到的那句「我丢掉了前面几条」靠它才存在。这条用例原来在 web 切片里
     * （{@code AssistantControllerTest.aTrimmedHistoryIsReportedInTheResponse}）——
     * 它当初就不该在那里：裁剪与否跟 HTTP 无关，只跟历史有多长有关。
     */
    @Test
    void aTrimmedHistoryIsReportedOnTheTurnResult() {
        String hundred = "x".repeat(100);

        AssistantTurnResult result = service(TIGHT).respond(AssistantFixtures.USER,
                AssistantFixtures.CONVERSATION,
                List.of(AssistantMessage.user(hundred),
                        AssistantMessage.assistant(hundred),
                        AssistantMessage.user(hundred)),
                "继续");

        assertThat(result.historyTrim()).isNotNull();
        assertThat(result.historyTrim().droppedMessages()).isEqualTo(1);
    }

    /**
     * 一条都没丢时字段必须**整体缺席**（{@code null}），而不是 {@code {"droppedMessages":0}}。
     *
     * <p>与确认卡片里 {@code before=null} 那条相反：那里 null 是取值，必须显式传；
     * 这里 0 不是取值，它是「不适用」。混用会让前端分不清「没裁剪」和「后端忘了填」，
     * 而前端的提示逻辑正是按「字段出现才更新、缺席绝不清空」写的。
     */
    @Test
    void nothingIsReportedAsTrimmedWhenEverythingFits() {
        AssistantTurnResult result = service(CONFIG).respond(AssistantFixtures.USER,
                AssistantFixtures.CONVERSATION,
                List.of(AssistantMessage.user("很短的一句")), "继续");

        assertThat(result.historyTrim()).isNull();
    }

    // ---------- 夹具 ----------

    private AssistantConversationService service(AssistantConfig config) {
        AssistantPendingActionService pending = new AssistantPendingActionService(
                pendingMapper, parser, registry, audit, config, AssistantFixtures.objectMapper(),
                Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC),
                mock(OutboundMessageService.class), mock(ContactMapper.class));
        return new AssistantConversationService(
                contextBuilder,
                new AssistantPromptBuilder(registry, config, AssistantFixtures.objectMapper()),
                modelClient, parser, new AssistantActionPolicy(), pending, audit, conversationLog,
                new AssistantRequestGuard(config), registry, config);
    }

    /** 这一轮发给模型的提示词（历史在这里面，所以它就是「模型看到了什么」）。 */
    @SuppressWarnings("unchecked")
    private String promptSentToTheModel() {
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(captor.capture(), any(), any());
        return flatten(captor.getValue());
    }

    private static String flatten(List<Map<String, String>> messages) {
        return messages.stream()
                .map(message -> message.getOrDefault("content", ""))
                .collect(Collectors.joining("\n"));
    }

    private void modelReplies(String... contents) {
        org.mockito.stubbing.OngoingStubbing<AssistantModelClient.ModelReply> stub =
                when(modelClient.complete(any(), any(), any()));
        for (String content : contents) {
            stub = stub.thenReturn(new AssistantModelClient.ModelReply(content, "deepseek-chat", 900));
        }
    }
}
