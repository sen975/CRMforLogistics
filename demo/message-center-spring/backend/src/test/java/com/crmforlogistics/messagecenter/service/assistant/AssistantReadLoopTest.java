package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 只读轨的循环行为（L1）。
 *
 * <p>要被证明的事只有五条，但每一条都对应一个具体的失败模式：
 *
 * <ol>
 *   <li>只读搜索之后<b>还能落一个写动作</b>（「先查再改」真的走得通）；</li>
 *   <li>只读检索到的对象<b>可以被下一轮引用</b>（否则候选边界一卡，整个链就断了）；</li>
 *   <li>写动作在只读轮之后<b>仍然要确认</b>（循环不得成为绕过确认的通道）；</li>
 *   <li>只读轮数<b>用完就诚实终止</b>，不再问模型（否则它会凑一个答案交差）；</li>
 *   <li>只读工具<b>失败即终止</b>，不给模型「换条路继续」或「假装成功」的机会。</li>
 * </ol>
 *
 * <p>装配的是真的注册表、解析器、策略与工具，只把「模型」「两张存储表」「会话检索来源」
 * 换成假的。理由是这五条全都发生在「判定与编排」这一层，mock 掉它们就等于把被测行为 mock 掉。
 */
class AssistantReadLoopTest {

    /** 只读轮数上限设成 2：小到能让「用尽」这条路径在几步内跑到，又不至于一步就撞上限。 */
    private static final AssistantConfig CONFIG = AssistantFixtures.config(2);

    private final TodoItemMapper todoMapper = mock(TodoItemMapper.class);
    private final AssistantPendingActionMapper pendingMapper = mock(AssistantPendingActionMapper.class);
    private final AssistantContextBuilder contextBuilder = mock(AssistantContextBuilder.class);
    private final AssistantModelClient modelClient = mock(AssistantModelClient.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    private final AssistantConversationLogService conversationLog = mock(AssistantConversationLogService.class);
    private final ConversationCandidateProvider conversations = mock(ConversationCandidateProvider.class);
    private final ConversationPreferenceService preferences = mock(ConversationPreferenceService.class);

    private final TodoAssistantTools todoTools = new TodoAssistantTools(new TodoItemService(todoMapper));
    private final ConversationAssistantTools conversationTools =
            new ConversationAssistantTools(conversations, preferences);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(todoTools.todoCreateTool(), todoTools.todoCompleteTool(),
                    todoTools.todoDeleteTool(), todoTools.todoUpdateTool(),
                    conversationTools.conversationSearchTool(), conversationTools.conversationPinTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());
    private final AssistantDecisionParser parser =
            new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());

    /** 检索命中的会话：刻意<b>不在</b>初始候选窗口里，用来证明结果确实替换了候选。 */
    private static final UUID CONTACT_ZHANG = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String FOUND_ZHANG = ConversationCandidates.Item.idOf(
            ConversationCandidates.TYPE_CONTACT, CONTACT_ZHANG);

    private AssistantConversationService service;

    /** 提为字段：有一条用例要跨过「落卡片」继续走「点确认」，那一步属于这个服务。 */
    private AssistantPendingActionService pendingActions;

    @BeforeEach
    void setUp() {
        when(contextBuilder.build(eq(AssistantFixtures.USER), anyInt())).thenReturn(AssistantFixtures.context());
        pendingActions = new AssistantPendingActionService(
                pendingMapper, parser, registry, audit, CONFIG,
                AssistantFixtures.objectMapper(),
                Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC));
        service = new AssistantConversationService(
                contextBuilder,
                new AssistantPromptBuilder(registry, CONFIG, AssistantFixtures.objectMapper()),
                modelClient, parser, new AssistantActionPolicy(), pendingActions, audit, conversationLog, registry, CONFIG);
        when(conversations.search(any(), any())).thenReturn(searchHitsZhang());
    }

    // ---------- ① 先查再改 ----------

    @Test
    void aReadRoundThenAWriteActionBothHappenInTheSameRequest() {
        when(todoMapper.insert(any())).thenReturn(1);
        modelReplies(searchCall("张总"), """
                {"decision":"call","tool":"todo.create",
                 "arguments":{"title":"跟进张总报价","date":"2026-09-23"}}
                """);

        AssistantTurnResult result = turn("看看有没有张总的会话，有的话记一条明天跟进报价的待办");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.EXECUTED);
        assertThat(result.message()).contains("已创建待办").contains("跟进张总报价");
        // 真的问了两次模型：第一轮检索、第二轮动手。
        verify(modelClient, times(2)).complete(any());
        verify(conversations).search(AssistantFixtures.USER, "张总");
        verify(todoMapper).insert(any());
    }

    /**
     * 只读轮之后仍然要确认 —— 这是约束 4 的直接验证：循环不得成为绕过确认的通道。
     * 同时它证明「检索到的对象能被下一轮引用」：{@code FOUND_ZHANG} 不在初始候选窗口里，
     * 只可能来自检索结果替换进来的那一组。
     */
    @Test
    void anObjectFoundBySearchCanBeWrittenToButOnlyAfterConfirmation() {
        modelReplies(searchCall("张总"), """
                {"decision":"call","tool":"conversation.pin",
                 "arguments":{"conversationRef":"%s","pinned":true}}
                """.formatted(FOUND_ZHANG));

        AssistantTurnResult result = turn("搜一下张总，把那个会话置顶");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);
        assertThat(result.proposal().tool()).isEqualTo(ConversationAssistantTools.TOOL_PIN);
        // 卡片上必须出现可核对的标识（名称），而不是只给一个 id。
        assertThat(result.proposal().summary()).contains("张总").contains("置顶");

        // 关键断言：只落了待确认行，**没有真的改任何偏好**。
        ArgumentCaptor<AssistantPendingActionEntity> pending =
                ArgumentCaptor.forClass(AssistantPendingActionEntity.class);
        verify(pendingMapper).insert(pending.capture());
        assertThat(pending.getValue().getToolName()).isEqualTo(ConversationAssistantTools.TOOL_PIN);
        assertThat(pending.getValue().getStatus()).isEqualTo("PENDING");
        verify(preferences, never()).setPinned(any(), any(), any(), anyBoolean());
    }

    /**
     * <b>跨过「确认」这一步</b>的完整链路：检索发现窗口外对象 → 引用它下写动作 → 落卡片 →
     * 用户点确认 → 真的执行。
     *
     * <p>真实浏览器走查里发现的缺陷就死在最后一跳：卡片能正常显示（摘要由服务端渲染），
     * 但确认时拿<b>重建</b>出来的候选窗口（退回 recent() 的 20 条，不含检索结果）一比，
     * 判对方「已失效」，返回 {@code ASSISTANT_STALE_REFERENCE}。于是 L1 的核心能力
     * 「先检索、再对检索结果动手」端到端不可用。
     *
     * <p>上一条用例与这一条只差最后一跳 —— 而那一跳正是此前完全没被覆盖的地方
     * （断言停在 {@code CONFIRMATION_REQUIRED} 就结束了，没人走到 confirm）。
     */
    @Test
    void aReferenceOnlySearchCouldRevealIsExecutedAfterTheUserConfirms() {
        modelReplies(searchCall("张总"), """
                {"decision":"call","tool":"conversation.pin",
                 "arguments":{"conversationRef":"%s","pinned":true}}
                """.formatted(FOUND_ZHANG));

        AssistantTurnResult proposed = turn("搜一下张总，把那个会话置顶");
        assertThat(proposed.kind()).isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);

        // 待确认行的 id 是 create 时随机生成的，只能从落库调用里捕获。
        ArgumentCaptor<AssistantPendingActionEntity> inserted =
                ArgumentCaptor.forClass(AssistantPendingActionEntity.class);
        verify(pendingMapper).insert(inserted.capture());
        AssistantPendingActionEntity stored = inserted.getValue();
        when(pendingMapper.findById(stored.getId(), AssistantFixtures.USER)).thenReturn(stored);
        when(pendingMapper.markDecided(stored.getId(), AssistantFixtures.USER, "CONFIRMED")).thenReturn(1);
        // 置顶工具现在会拿服务端顺带带回来的显示名来回话，所以这里要给一个响应；
        // 顺带断言这条链的末端说的是人话（而不是 CONTACT:<uuid>）。
        when(preferences.setPinned(AssistantFixtures.USER, ConversationCandidates.TYPE_CONTACT,
                CONTACT_ZHANG, true))
                .thenReturn(new ConversationPreferenceResponse(ConversationCandidates.TYPE_CONTACT,
                        CONTACT_ZHANG, true, false, "张总"));

        AssistantTurnResult confirmed = pendingActions.confirm(AssistantFixtures.USER, stored.getId());

        assertThat(confirmed.kind())
                .as("窗口外的引用在确认时必须真的落地")
                .isEqualTo(AssistantTurnResult.Kind.EXECUTED);
        assertThat(confirmed.message())
                .as("执行回话要说人会说的话：跨过检索边界的这一整条链，末端不能吐一个候选 id")
                .contains("张总").doesNotContain(CONTACT_ZHANG.toString());
        verify(preferences).setPinned(AssistantFixtures.USER, ConversationCandidates.TYPE_CONTACT,
                CONTACT_ZHANG, true);
    }

    @Test
    void aConversationRefThatIsInNoCandidateGroupIsRejected() {
        modelReplies("""
                {"decision":"call","tool":"conversation.pin",
                 "arguments":{"conversationRef":"CONTACT:99999999-9999-4999-8999-999999999999","pinned":true}}
                """);

        AssistantTurnResult result = turn("把那个会话置顶");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(AssistantException.REQUEST_INVALID);
        verifyNoInteractions(pendingMapper, preferences);
        // 不可重试：编造的引用不给第二次机会改写成别的工具。
        verify(modelClient, times(1)).complete(any());
    }

    // ---------- ④ 轮次用尽 ----------

    @Test
    void whenTheReadBudgetRunsOutTheTurnEndsHonestlyInsteadOfAskingAgain() {
        // 模型每次都要求检索：它会撞上上限。
        modelReplies(searchCall("张总"), searchCall("张总"), searchCall("张总"));

        AssistantTurnResult result = turn("帮我查清楚张总的所有情况");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(AssistantConversationService.READ_TURNS_EXHAUSTED);
        assertThat(result.message()).contains("没能").contains("2");
        // 上限 2：两轮检索都执行了，第三次请求不再问模型 —— 直接终止。
        verify(modelClient, times(3)).complete(any());
        verify(conversations, times(2)).search(any(), any());
    }

    @Test
    void theExhaustedTurnIsAuditedAsARejectedReadNotAsSomethingThatHappened() {
        modelReplies(searchCall("张总"), searchCall("张总"), searchCall("张总"));

        turn("帮我查清楚张总的所有情况");

        ArgumentCaptor<AssistantAuditService.Entry> entries =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit, times(3)).record(entries.capture());
        List<AssistantAuditService.Entry> captured = entries.getAllValues();

        assertThat(captured).extracting(AssistantAuditService.Entry::policy)
                .as("三轮都走只读档").containsExactly("READ", "READ", "READ");
        assertThat(captured).extracting(AssistantAuditService.Entry::outcome)
                .as("前两轮真的执行了，第三轮是被拒的")
                .containsExactly("EXECUTED", "EXECUTED", "REJECTED");
        assertThat(captured.get(2).errorCode())
                .isEqualTo(AssistantConversationService.READ_TURNS_EXHAUSTED);
        assertThat(captured).extracting(AssistantAuditService.Entry::turnIndex)
                .as("轮次在库里必须可复原：这三行按 0/1/2 排才读得出「模型依次做了什么」。"
                        + "只靠 created_at 排不了 —— 同一轮内的几行可能落在同一毫秒")
                .containsExactly(0, 1, 2);
    }

    /** 回滚开关：{@code max-read-turns=0} 时一次检索都不执行，直接给诚实的未完成终态。 */
    @Test
    void zeroReadTurnsDisablesTheReadTrackEntirely() {
        AssistantConversationService noRead = new AssistantConversationService(
                contextBuilder,
                new AssistantPromptBuilder(registry, AssistantFixtures.config(0), AssistantFixtures.objectMapper()),
                modelClient, parser, new AssistantActionPolicy(),
                new AssistantPendingActionService(pendingMapper, parser, registry, audit,
                        AssistantFixtures.config(0), AssistantFixtures.objectMapper(),
                        Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC)),
                audit, conversationLog, registry, AssistantFixtures.config(0));
        modelReplies(searchCall("张总"));

        AssistantTurnResult result = noRead.respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                List.of(), "看看张总的会话");

        assertThat(result.errorCode()).isEqualTo(AssistantConversationService.READ_TURNS_EXHAUSTED);
        assertThat(result.message()).contains("只读检索当前是关闭的");
        verify(conversations, never()).search(any(), any());
    }

    // ---------- ⑤ 只读工具失败 ----------

    /**
     * 失败即终止，<b>不再问模型</b>：{@code complete} 只被调用一次就是这条规则的结构性证据 ——
     * 模型根本没有机会把一次失败的检索说成「我查到了」。
     */
    @Test
    void aFailingReadToolEndsTheTurnSoTheModelNeverGetsToSpinItAsSuccess() {
        when(conversations.search(any(), any())).thenThrow(new IllegalStateException("检索服务不可用"));
        modelReplies(searchCall("张总"), """
                {"decision":"reply","reply":"我查到了，张总最近有三条会话。"}
                """);

        AssistantTurnResult result = turn("看看张总的会话");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo("INTERNAL");
        assertThat(result.message()).contains("我没能查到需要的信息");
        assertThat(result.message()).doesNotContain("我查到了");
        verify(modelClient, times(1)).complete(any());
    }

    @Test
    void anEmptySearchResultIsNotAnErrorAndTheModelStillGetsToAnswer() {
        when(conversations.search(any(), any())).thenReturn(new ConversationCandidates(20, List.of()));
        modelReplies(searchCall("不存在的人"), "{\"decision\":\"reply\",\"reply\":\"没有找到相关会话。\"}");

        AssistantTurnResult result = turn("看看有没有和不存在的人的会话");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(result.message()).contains("没有找到");
    }

    // ---------- 重试边界与落地次数 ----------

    @Test
    void theEnvelopeRetryOnlyHappensBeforeAnyReadRound() {
        // 第一轮检索成功，第二轮输出坏了 → 不重试，直接作废。
        modelReplies(searchCall("张总"), "这不是 JSON");

        AssistantTurnResult result = turn("看看张总的会话");

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(AssistantException.REQUEST_INVALID);
        verify(modelClient, times(2)).complete(any());
    }

    @Test
    void theConversationIsLoggedExactlyOncePerRequestNotOncePerRound() {
        when(todoMapper.insert(any())).thenReturn(1);
        modelReplies(searchCall("张总"), """
                {"decision":"call","tool":"todo.create",
                 "arguments":{"title":"跟进张总报价","date":"2026-09-23"}}
                """);

        turn("看看张总的会话，然后记一条待办");

        // 用户只说了那句话一次 —— 多轮是内部实现细节，不该在会话历史里被记多遍。
        verify(conversationLog, times(1)).appendUser(any(), any(), eq("看看张总的会话，然后记一条待办"));
        verify(conversationLog, times(1)).appendAssistant(any(), any(), any(), any());
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

    private static String searchCall(String query) {
        return """
                {"decision":"call","tool":"conversation.search","arguments":{"query":"%s"}}
                """.formatted(query);
    }

    private static ConversationCandidates searchHitsZhang() {
        return new ConversationCandidates(20, List.of(
                new ConversationCandidates.Item(FOUND_ZHANG, ConversationCandidates.TYPE_CONTACT,
                        "张总", List.of("wechat"), "2026-09-21T03:00:00Z", 0, false)));
    }
}
