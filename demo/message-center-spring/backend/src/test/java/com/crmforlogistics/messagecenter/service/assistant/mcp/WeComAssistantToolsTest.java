package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidates;
import com.crmforlogistics.messagecenter.service.wecom.WeComSelfPushService;
import com.crmforlogistics.messagecenter.service.wecom.WeComSummaryReadService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code wecom.summary_read}：把 {@code WeComSummaryReadService} 的三种结局翻译成模型能处置的话。
 *
 * <h1>这个类主要在验"三种失败不能混成一种"</h1>
 * <ul>
 *   <li><b>参数错</b>（拿了个联系人 ref）：模型该换个引用再试；</li>
 *   <li><b>不可见</b>（群不在你的范围内）：模型该如实说看不到，不该重试；</li>
 *   <li><b>功能没装配</b>：模型该让用户找管理员，重试一万次也不会有结果。</li>
 * </ul>
 *
 * <p>三者一旦合并成一个码，用户的处置就只剩"再试一次" —— 而那对后两种是纯浪费。
 *
 * <h1>推送那几条为什么断 {@code ToolResult} 而不是「期望抛异常」</h1>
 * {@link ToolRegistry#invoke} 把 {@link ToolExecutionException} <b>收成</b>
 * {@code ToolResult.failure(code, message)}（见该方法体），所以失败在工具层表现为
 * 「一个带错误码的结果」。写 {@code assertThatThrownBy} 会得到「期望抛异常、什么都没发生」
 * 的假失败 —— 这条踩过，记在这里。
 *
 * <h1>另外两件事</h1>
 * 「空结果」必须区分「还在生成」与「确实没有内容」：只有空列表的话，模型只能猜其中一个，
 * 而猜错的代价是用户以为功能坏了（或以为群里没聊过）。以及截断必须说出来，
 * 否则「只给了 20 条」会被读成「一共只有 20 条」。
 */
class WeComAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final String GROUP_REF = AssistantFixtures.CONVERSATION_SEA;

    private final WeComSummaryReadService summaries = mock(WeComSummaryReadService.class);
    private final WeComSelfPushService pushService = mock(WeComSelfPushService.class);
    private final WeComAssistantTools tools =
            new WeComAssistantTools(providerOf(summaries), pushProviderOf(pushService));

    /** 只装摘要工具的注册表：上面那二十来个用例的事实与推送无关，不该被它带进来。 */
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.wecomSummaryReadTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    /** 只装推送工具的注册表。 */
    private final ToolRegistry pushRegistry = new ToolRegistry(
            List.of(tools.wecomPushSelfTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    /** 企微整块关掉时的注册表：工具仍在注册表里，只是摘要服务与推送门面都取不到。 */
    private final ToolRegistry registryWithoutWeCom = new ToolRegistry(
            List.of(new WeComAssistantTools(providerOf(null), pushProviderOf(null)).wecomSummaryReadTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    /**
     * {@code ObjectProvider} 桩。
     *
     * <p>刻意不起真上下文（{@code ApplicationContextRunner}）：这里要断言的事实就是
     * 「取不到服务时回什么话」，它在 Spring 里等价于 {@code getIfAvailable() == null}
     * 一个分支。起真上下文只会把这条被测事实藏进条件装配里 —— 装配真错了的时候，
     * 这条用例会以"上下文起不来"的形式红，而不是以"回话不对"的形式红。
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WeComSummaryReadService> providerOf(WeComSummaryReadService service) {
        ObjectProvider<WeComSummaryReadService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    /** 同上，给推送门面。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WeComSelfPushService> pushProviderOf(WeComSelfPushService service) {
        ObjectProvider<WeComSelfPushService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    void theHappyPathNamesTheGroupAndCarriesTheSummaries() {
        when(summaries.read(eq(USER), any(), anyInt())).thenReturn(window(
                "海运客户群", List.of(summary("2026-09-22T02:00:00Z", "客户问了 20 尺柜的运价")), false, true));

        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.data()).containsEntry("groupName", "海运客户群").containsEntry("count", 1);
        assertThat(result.message()).contains("海运客户群").contains("1 条聊天摘要");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0))
                .containsEntry("occurredAt", "2026-09-22T02:00:00Z")
                .containsEntry("summary", "客户问了 20 尺柜的运价");
    }

    @Test
    void aContactReferenceIsRejectedWithGuidanceNotWithASilentFailure() {
        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", AssistantFixtures.CONVERSATION_ZHOU));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message())
                .as("要说清该用哪一种 ref，否则模型会反复重试同一个错引用")
                .contains("WECOM_GROUP");
        verify(summaries, org.mockito.Mockito.never()).read(any(), any(), anyInt());
    }

    @Test
    void anInaccessibleGroupIsAnAccessFailure() {
        when(summaries.read(eq(USER), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("WECOM_GROUP_NOT_ACCESSIBLE"));

        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    @Test
    void aDisabledFeatureIsUnavailableNotAForbiddenGroup() {
        when(summaries.read(eq(USER), any(), anyInt()))
                .thenThrow(new IllegalStateException("WECOM_SUMMARY_UNAVAILABLE"));

        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.code())
                .as("说成 INVALID_ARGUMENT 会告诉用户「换个群试试」，而换成哪个群都一样")
                .isEqualTo(ToolExecutionException.UNAVAILABLE);
        assertThat(result.message()).contains("没有启用");
    }

    @Test
    void aDeploymentWithoutTheWeComModuleStillAnswersInsteadOfFailingToAssemble() {
        // 企微整块关掉：WeComSummaryReadService 是模块 Bean，压根没被装配。
        // 本工具自己必须仍在（tools/list 的形状要稳定，且只读清单里写着它的名字），
        // 所以这条路径的出口是"一句话的 UNAVAILABLE"，而不是让调用炸在装配缺失上。
        ToolResult result = registryWithoutWeCom.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.UNAVAILABLE);
        assertThat(result.message())
                .as("与「仓储没装配」同一句话：对用户是同一件事，都要管理员去开配置")
                .isEqualTo(WeComAssistantTools.UNAVAILABLE_MESSAGE);
    }

    @Test
    void daysIsOptionalAndDefaultsToOneWeek() {
        when(summaries.read(eq(USER), any(), anyInt())).thenReturn(window("海运客户群", List.of(), false, false));

        registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER, Map.of("groupRef", GROUP_REF));

        verify(summaries).read(USER, ConversationCandidates.targetOf(GROUP_REF), WeComAssistantTools.DEFAULT_DAYS);
    }

    @Test
    void anExplicitDayCountIsPassedThrough() {
        when(summaries.read(eq(USER), any(), anyInt())).thenReturn(window("海运客户群", List.of(), false, false));

        registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF, "days", 1));

        verify(summaries).read(USER, ConversationCandidates.targetOf(GROUP_REF), 1);
    }

    /**
     * 上下界由 schema 上的 {@code minimum} / {@code maximum} 拦，而且<b>必须真的拦得住</b>。
     *
     * <p>这条用例同时是 {@code ToolInputValidator} 那条「新增关键字必须同步实现」契约的回归：
     * 在 2026-09-23 之前，校验器不认 {@code minimum}/{@code maximum}，
     * 也就是说这里写着的 30 天<b>当时等于没写</b>，越界值会被原样送到服务层。
     */
    @Test
    void aDayCountOutsideTheDeclaredRangeIsRejectedAtTheSchemaLayer() {
        ToolResult tooBig = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF, "days", WeComSummaryReadService.MAX_DAYS + 1));
        ToolResult tooSmall = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF, "days", 0));

        assertThat(tooBig.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(tooBig.message()).contains("不能大于 " + WeComSummaryReadService.MAX_DAYS);
        assertThat(tooSmall.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(tooSmall.message()).contains("不能小于 " + WeComSummaryReadService.MIN_DAYS);
        verify(summaries, org.mockito.Mockito.never()).read(any(), any(), anyInt());
    }

    @Test
    void anEmptyWindowDistinguishesStillGeneratingFromNothingToSummarize() {
        when(summaries.read(eq(USER), any(), anyInt()))
                .thenReturn(window("海运客户群", List.of(), false, true));
        assertThat(registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER, Map.of("groupRef", GROUP_REF))
                .message())
                .as("有任务没完成 → 说「可能还在生成中」，而不是让用户以为这个群没聊过事")
                .contains("还没有生成完成").contains("还在生成中");

        when(summaries.read(eq(USER), any(), anyInt()))
                .thenReturn(window("海运客户群", List.of(), false, false));
        assertThat(registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER, Map.of("groupRef", GROUP_REF))
                .message())
                .contains("没有找到可总结的聊天内容");
    }

    @Test
    void truncationIsStatedOutLoud() {
        when(summaries.read(eq(USER), any(), anyInt())).thenReturn(window(
                "海运客户群", List.of(summary("2026-09-22T02:00:00Z", "摘要")), true, true));

        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.message())
                .as("不说的话模型会把「只给了 20 条」读成「一共只有 20 条」")
                .contains("还有更早的摘要");
        assertThat(result.data()).containsEntry("truncated", true);
    }

    @Test
    void anAnonymousGroupFallsBackToTheReferenceInsteadOfInventingAName() {
        when(summaries.read(eq(USER), any(), anyInt())).thenReturn(window(null, List.of(), false, false));

        ToolResult result = registry.invoke(WeComAssistantTools.TOOL_SUMMARY_READ, USER,
                Map.of("groupRef", GROUP_REF));

        assertThat(result.data()).containsEntry("groupName", GROUP_REF);
    }

    @Test
    void theDeclarationIsReadOnlyAndBindsTheConversationCandidateSet() {
        ToolDefinition definition = registry.find(WeComAssistantTools.TOOL_SUMMARY_READ).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isTrue();
        assertThat(definition.tool().annotations().destructiveHint()).isFalse();
        assertThat(definition.referenceBindings())
                .as("群引用复用会话候选：再建一组「企微群候选」会得到一个形状相同、类型更少的东西")
                .containsExactly(Map.entry("groupRef", ConversationCandidates.NAME));
        assertThat(definition.requiredArguments()).containsExactly("groupRef");
    }

    // ---------- wecom.push_self ----------

    /**
     * 成功路径：把门面给的 msgid 原样带出来，并说清「推送成功」。
     *
     * <p>措辞与 WhatsApp 那条刻意不同：企微是同步接口、回了 {@code errcode=0} 才返回，
     * 所以这里可以说「已推送」；而 WhatsApp 只是入队，只能说「已提交」。
     * 两个渠道的机制不同，措辞也不该被统一 —— 统一会把其中一个说成假话。
     */
    @Test
    void pushingToSelfReturnsTheMessageIdAndSaysItWasPushed() {
        when(pushService.push(eq(USER), any()))
                .thenReturn(new WeComSelfPushService.PushOutcome("msg-1"));

        ToolResult result = pushRegistry.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER,
                Map.of("text", "会议报告：9 月 28 日客户沟通要点……"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已把这条消息推送到你的企业微信");
        assertThat(result.data()).containsEntry("status", "sent").containsEntry("messageId", "msg-1");
        verify(pushService).push(eq(USER), eq("会议报告：9 月 28 日客户沟通要点……"));
    }

    /**
     * 钉住「模型编不出收件人」。
     *
     * <p>这是这个工具能进免确认档的<b>唯一理由</b>：收件人由 userId 解析，不是参数。
     * 一旦有人给它加一个 {@code toUser}（看起来很自然的需求），这条用例会红 ——
     * 而它红的意义不是「多了一个非法参数」，而是「免确认的前提消失了，
     * 请同时把它移出 {@code AUTO_EXECUTE_ALLOWLIST}」。
     */
    @Test
    void theToolRefusesAnyRecipientParameter() {
        ToolResult result = pushRegistry.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER,
                Map.of("text", "x", "toUser", "zhangsan"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("toUser");
    }

    /** 没绑定企微：要告诉用户「去绑定」，而不是让他重试（重试一万次也一样）。 */
    @Test
    void anUnboundUserIsToldToBindInsteadOfBeingAskedToRetry() {
        when(pushService.push(eq(USER), any()))
                .thenThrow(new IllegalStateException(WeComSelfPushService.UNAVAILABLE_MESSAGE));

        ToolResult result = pushRegistry.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.UNAVAILABLE);
    }

    /**
     * 门面缺席（企微没启用／没配 suite-id）时，工具仍在注册表里、仍然可调用，
     * 只是回一句「不可用」—— 而不是让 {@code tools/list} 的形状随部署变化。
     */
    @Test
    void whenThePushFacadeIsAbsentTheToolStillSaysUnavailableRatherThanInternal() {
        ToolRegistry noWeCom = new ToolRegistry(
                List.of(new WeComAssistantTools(providerOf(summaries), pushProviderOf(null)).wecomPushSelfTool()),
                new ToolInputValidator(), AssistantFixtures.objectMapper());

        ToolResult result = noWeCom.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.UNAVAILABLE);
    }

    /**
     * 结果未知是唯一一个「重试 = 第二条真实消息」的失败，必须有自己的码。
     *
     * <p>报成 {@code INTERNAL} 的话，模型会照着「请稍后再试」自己去重试，用户收到两条一样的推送。
     */
    @Test
    void anUnknownOutcomeIsNeverRetried() {
        when(pushService.push(eq(USER), any()))
                .thenThrow(new WeComSelfPushService.OutcomeUnknown("可能已经发出去了，请不要重发", null));

        ToolResult result = pushRegistry.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.SEND_OUTCOME_UNKNOWN);
        assertThat(result.message()).contains("不要重发");
    }

    /** 上游明确拒绝：原因要带给模型（它可能能改），且不能报成「系统故障」。 */
    @Test
    void aRejectedPushCarriesTheUpstreamReason() {
        when(pushService.push(eq(USER), any()))
                .thenThrow(new WeComSelfPushService.PushRejected("企业微信拒绝了这次推送：invalid user", null));

        ToolResult result = pushRegistry.invoke(WeComAssistantTools.TOOL_PUSH_SELF, USER, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.message()).contains("invalid user");
    }

    /**
     * 声明：可免确认的写动作。
     *
     * <p>{@code destructiveHint=false} 是<b>进 AUTO 档的必要条件</b> ——
     * 策略类对未声明的注解按保守值（=破坏性）解释，漏写会静默退回确认档，
     * 而那种退化在别处看不出来。这条断言就是那道绊线。
     */
    @Test
    void thePushDeclarationIsAWriteThatIsNotDestructive() {
        ToolDefinition definition = pushRegistry.find(WeComAssistantTools.TOOL_PUSH_SELF).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isFalse();
        assertThat(definition.tool().annotations().destructiveHint()).isFalse();
        assertThat(definition.tool().annotations().idempotentHint()).isFalse();
        assertThat(definition.requiredArguments()).containsExactly("text");
        assertThat(definition.referenceBindings()).isEmpty();
    }

    /** 长度闸门与门面里的字节闸门同源：声明侧改宽了而门面没改（或反之）会让这条红。 */
    @Test
    void theDeclaredTextLimitComesFromTheFacade() {
        ToolDefinition definition = pushRegistry.find(WeComAssistantTools.TOOL_PUSH_SELF).orElseThrow();

        assertThat(propertyOf(definition, "text")).containsEntry("maxLength", WeComSelfPushService.MAX_TEXT_CHARS);
    }

    // ---------- 夹具 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(ToolDefinition definition, String name) {
        Map<String, Object> properties =
                (Map<String, Object>) definition.tool().inputSchema().get("properties");
        return (Map<String, Object>) properties.get(name);
    }

    private static WeComSummaryReadService.SummaryWindow window(String groupName,
                                                                List<WeComSummaryReadService.Summary> items,
                                                                boolean truncated, boolean hasAnyJob) {
        return new WeComSummaryReadService.SummaryWindow(groupName, items, truncated, hasAnyJob);
    }

    private static WeComSummaryReadService.Summary summary(String occurredAt, String text) {
        return new WeComSummaryReadService.Summary(occurredAt, text);
    }
}
