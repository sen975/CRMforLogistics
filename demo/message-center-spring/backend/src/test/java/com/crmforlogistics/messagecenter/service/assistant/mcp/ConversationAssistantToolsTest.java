package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidates;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话域两个工具的行为，重点是<b>只读检索投影了哪些字段</b>。
 *
 * <h2>为什么「少给字段」值得单独一组测试</h2>
 * 合规口径（2026-09-22 选项 b）是：只允许结构化事实与摘要进模型，客户消息原文与通话转写
 * 一律不出系统边界。而统一会话查询<b>会</b>返回 {@code last_text}（最后一条消息正文），
 * 候选与 observation 又都会进提示词 —— 也就都会进模型供应商的接口。
 *
 * <p>所以这里用真的 {@link ConversationCandidateProvider} 加假的 Mapper（而不是自己拼一组
 * 现成候选）：被验的正是那段「行 → 候选」的白名单投影。这类泄漏一旦发生，功能
 * <b>看起来完全正常</b>，没有任何断言会失败，只有数据出去了 —— 它是本类存在的主要理由。
 *
 * <h2>分层：候选比对不在这里</h2>
 * 工具本身<b>不</b>判断 {@code conversationRef} 是否来自候选清单 —— 那是解析层的职责
 * （{@code AssistantDecisionParser}，只有它手里有本轮的候选集）。工具侧的兜底是
 * service 的 {@code authorize}：即便有人绕过解析层，改的也只会是自己有权限的会话。
 * 下面「越权会话被报成参数错误」的用例验的就是这条兜底。
 */
class ConversationAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT_ZHANG = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID GROUP_SEA = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String REF_ZHANG =
            ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_CONTACT, CONTACT_ZHANG);
    private static final String REF_SEA =
            ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA);

    /** 一句刻意「像正文」的文本：它绝不允许出现在工具的返回里。 */
    private static final String MESSAGE_BODY = "客户说价格太高，要我们再降 5% 才考虑签约";

    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final ConversationCandidateProvider provider = new ConversationCandidateProvider(conversationMapper);
    private final ConversationPreferenceService preferences = mock(ConversationPreferenceService.class);
    private final ConversationAssistantTools tools = new ConversationAssistantTools(provider, preferences);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.conversationSearchTool(), tools.conversationPinTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 只读检索 ----------

    @Test
    void searchNeverReturnsTheMessageBodyEvenThoughTheQueryProvidesIt() {
        givenRows("张总", row("CONTACT", CONTACT_ZHANG, "张总", MESSAGE_BODY));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_SEARCH, USER, Map.of("query", "张总"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("找到 1 个会话").contains("张总").doesNotContain(MESSAGE_BODY);
        // 结构化投影：只有白名单里的键。
        assertThat(result.data()).containsOnlyKeys("count", "items");
        assertThat(result.data().toString())
                .as("最后一条消息正文绝不能进返回 —— 返回会被渲染进提示词发给模型供应商")
                .doesNotContain(MESSAGE_BODY)
                .doesNotContain("lastText").doesNotContain("last_text")
                .doesNotContain("avatarUrl").doesNotContain("providerConversationKey")
                .doesNotContain("avatar.example");
    }

    @Test
    void searchHandsBackTheFoundConversationsSoTheNextRoundCanReferenceThem() {
        givenRows("张总", row("CONTACT", CONTACT_ZHANG, "张总", null));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_SEARCH, USER, Map.of("query", "张总"));

        assertThat(result.candidates())
                .as("只读结果必须带回候选集，否则下一轮的写动作引用不到检索到的东西")
                .isInstanceOf(ConversationCandidates.class);
        assertThat(result.candidates().contains(REF_ZHANG)).isTrue();
    }

    @Test
    void searchWithBlankQueryFallsBackToTheRecentWindowInsteadOfReturningNothing() {
        givenRows(null, row("CONTACT", CONTACT_ZHANG, "张总", null));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_SEARCH, USER, Map.of("query", "  "));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("张总");
    }

    /** 查不到不是错误：空结果是一个正常结论，模型应当据此回话。 */
    @Test
    void anEmptySearchIsASuccessWithNoCandidateReplacement() {
        givenRows("查无此人");

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_SEARCH, USER, Map.of("query", "查无此人"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("没有找到");
        assertThat(result.candidates())
                .as("一次没命中的检索不该把用户本来能引用的候选窗口抹掉")
                .isNull();
    }

    /** 截断必须说出来，否则模型会把「只给了前 N 条」读成「一共只有 N 条」。 */
    @Test
    void searchSaysSoWhenItHitItsLimit() {
        List<ConversationMapper.UnifiedConversationRow> many = new ArrayList<>();
        for (int i = 0; i < ConversationCandidateProvider.LIMIT + 1; i++) {
            many.add(row("CONTACT", UUID.randomUUID(), "客户" + i, null));
        }
        givenRows("客户", many.toArray(ConversationMapper.UnifiedConversationRow[]::new));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_SEARCH, USER, Map.of("query", "客户"));

        assertThat(result.data().get("count")).isEqualTo(ConversationCandidateProvider.LIMIT);
        assertThat(result.message()).contains("只显示了前 " + ConversationCandidateProvider.LIMIT + " 条");
    }

    // ---------- 写动作 ----------

    @Test
    void pinParsesTheCompositeRefIntoTheTypeAndTargetThePreferenceServiceExpects() {
        when(preferences.setPinned(USER, ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA, true))
                .thenReturn(new ConversationPreferenceResponse(
                        ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA, true, false, "守望出海群"));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_PIN, USER,
                Map.of("conversationRef", REF_SEA, "pinned", true));

        assertThat(result.isError()).isFalse();
        // 回话说的是会话名。原先这里拼的是 conversationRef，于是用户在确认卡片上读到
        // 「悦为小森」、紧接着的回话却是「CONTACT:d526bde8-…」，两句话对不上 ——
        // 走查时在真实浏览器里复现过，这条断言就是它的回归守卫。
        assertThat(result.message()).contains("已置顶").contains("守望出海群");
        assertThat(result.message())
                .as("候选 id 是内部标识，不该出现在给用户看的回话里")
                .doesNotContain(REF_SEA);
        assertThat(result.data()).containsEntry("name", "守望出海群");
        verify(preferences).setPinned(USER, ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA, true);
    }

    /**
     * 拿不到名字时退回候选 id：难看，但比编一个名字安全。
     *
     * <p>走的是「授权通过、名字却为空」这条窄路。真实数据里罕见，但分支必须存在 ——
     * 否则要么在 {@code displayName()} 上 NPE，要么有人顺手写个兜底名字，
     * 而那句兜底会被用户当成事实去核对。
     */
    @Test
    void pinFallsBackToTheReferenceWhenTheServiceHasNoName() {
        when(preferences.setPinned(USER, ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA, true))
                .thenReturn(new ConversationPreferenceResponse(
                        ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA, true, false, null));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_PIN, USER,
                Map.of("conversationRef", REF_SEA, "pinned", true));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains(REF_SEA);
        assertThat(result.data()).containsEntry("name", REF_SEA);
    }

    @Test
    void pinRejectsARefThatIsNotShapedLikeACandidateId() {
        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_PIN, USER,
                Map.of("conversationRef", "88888888-8888-4888-8888-888888888888", "pinned", true));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(preferences, never()).setPinned(any(), any(), any(), anyBoolean());
    }

    /**
     * 越权兜底：{@code authorize} 抛的 {@code IllegalArgumentException} 被收敛成参数错误。
     *
     * <p>措辞刻意不区分「不存在」与「不属于你」—— 对用户来说指引是一样的（重新挑一条），
     * 而区分开就等于告诉另一个账号「这条 id 是存在的」。
     */
    @Test
    void pinReportsAnOutOfScopeConversationWithoutLeakingWhy() {
        when(preferences.setPinned(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("WeCom group not found"));

        ToolResult result = registry.invoke(ConversationAssistantTools.TOOL_PIN, USER,
                Map.of("conversationRef", REF_ZHANG, "pinned", false));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE).doesNotContain("not found");
    }

    // ---------- 声明 ----------

    @Test
    void searchIsDeclaredReadOnlyAndPinIsNot() {
        ToolDefinition search = registry.find(ConversationAssistantTools.TOOL_SEARCH).orElseThrow();
        ToolDefinition pin = registry.find(ConversationAssistantTools.TOOL_PIN).orElseThrow();

        assertThat(search.tool().annotations().readOnlyHint()).isTrue();
        assertThat(pin.tool().annotations().readOnlyHint()).isFalse();
        // 置顶是可逆的内部偏好：声明为非破坏性，但它仍然要确认（策略由编排层的清单持有权威）。
        assertThat(pin.tool().annotations().destructiveHint()).isFalse();
        assertThat(search.tool().annotations().openWorldHint()).isFalse();
        assertThat(pin.tool().annotations().openWorldHint()).isFalse();
    }

    @Test
    void theReferenceBindingIsDeclaredOnTheFieldItself() {
        ToolDefinition pin = registry.find(ConversationAssistantTools.TOOL_PIN).orElseThrow();

        assertThat(pin.referenceBindings())
                .containsExactly(Map.entry("conversationRef", ConversationCandidates.NAME));
    }

    // ---------- 夹具 ----------

    private void givenRows(String search, ConversationMapper.UnifiedConversationRow... rows) {
        when(conversationMapper.listUnified(eq(USER), search == null ? isNull() : eq(search), eq(false),
                isNull(), isNull(), isNull(), isNull(), anyInt())).thenReturn(List.of(rows));
    }

    private static ConversationMapper.UnifiedConversationRow row(String type, UUID id, String name, String lastText) {
        return new ConversationMapper.UnifiedConversationRow(type, id, name, null, "https://avatar.example/x.png",
                "wechat,wecom", Instant.parse("2026-09-21T03:00:00Z"), lastText,
                12, 3, "provider-key-should-not-leak", 4, false, null,
                Instant.parse("2026-09-21T03:00:00Z"), type + ":" + id);
    }
}
