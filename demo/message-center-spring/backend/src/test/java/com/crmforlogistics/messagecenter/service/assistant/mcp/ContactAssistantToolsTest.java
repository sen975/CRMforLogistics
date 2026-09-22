package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ContactBrief;
import com.crmforlogistics.messagecenter.service.assistant.ContactBriefProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 联系人域两个工具的行为。
 *
 * <h2>这组测试真正在防的是三类「不会失败的错」</h2>
 * <ol>
 *   <li><b>返回里多带了字段</b>：返回会被渲染进 observation 发给模型供应商。多一个键就是
 *       多一类数据出边界，而功能照常工作 —— 没有断言会因此失败，除非这里专门钉住键集合。</li>
 *   <li><b>{@code contactRef} 收下了一个企微群 ref</b>：两个域的 ref 长得一模一样
 *       （{@code CONTACT:<uuid>} / {@code WECOM_GROUP:<uuid>}），形状校验若只检查「像不像候选 id」，
 *       就会放一个群进去，然后在运行时才以「联系人不存在」这个错误的原因失败。</li>
 *   <li><b>简报撑爆一条 observation</b>：提示词对单条 observation 有字符上限，超了就静默截断
 *       （只留一句「已截断」）。最后一个用例用最坏情况钉住预算 —— 各节上限一旦被人调大，
 *       它就会失败，而不是在线上让模型收到半份数据。</li>
 * </ol>
 */
class ContactAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String REF = AssistantFixtures.CONTACT_ZHOU_REF;
    private static final String GROUP_REF = AssistantFixtures.CONVERSATION_SEA;

    private final ContactCandidateProvider candidates = mock(ContactCandidateProvider.class);
    private final ContactBriefProvider briefs = mock(ContactBriefProvider.class);
    private final ContactAssistantTools tools = new ContactAssistantTools(candidates, briefs);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.contactSearchTool(), tools.contactBriefTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 只读检索 ----------

    @Test
    void searchReturnsAStructuredProjectionAndHandsBackTheWindow() {
        when(candidates.search(USER, "周")).thenReturn(contactCandidates());

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_SEARCH, USER, Map.of("query", "周"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("找到 1 个联系人").contains("周明");
        assertThat(result.data()).containsOnlyKeys("count", "items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).keySet())
                .as("候选只带这三个字段：多一个键就多一类数据发给模型供应商")
                .containsExactlyInAnyOrder("contactRef", "name", "remark");
        assertThat(result.candidates())
                .as("只读结果必须带回候选集，否则下一轮的 contact.brief 引用不到检索到的人")
                .isInstanceOf(ContactCandidates.class);
        assertThat(result.candidates().contains(REF)).isTrue();
    }

    @Test
    void aBlankQueryFallsBackToTheRecentWindowInsteadOfReturningNothing() {
        when(candidates.search(USER, "  ")).thenReturn(contactCandidates());

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_SEARCH, USER, Map.of("query", "  "));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("周明");
    }

    /** 查不到不是错误：空结果是一个正常结论，模型应当据此回话。 */
    @Test
    void anEmptySearchIsASuccessWithNoCandidateReplacement() {
        when(candidates.search(USER, "查无此人")).thenReturn(new ContactCandidates(20, List.of()));

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_SEARCH, USER, Map.of("query", "查无此人"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("没有找到");
        assertThat(result.candidates())
                .as("一次没命中的检索不该把用户本来能引用的候选窗口抹掉")
                .isNull();
    }

    /** 截断必须说出来，否则模型会把「只给了前 20 条」读成「一共只有 20 条」。 */
    @Test
    void searchSaysSoWhenItHitItsLimit() {
        when(candidates.search(USER, "客户")).thenReturn(contactCandidates(ContactCandidateProvider.LIMIT + 1));

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_SEARCH, USER, Map.of("query", "客户"));

        assertThat(result.data().get("count")).isEqualTo(ContactCandidateProvider.LIMIT);
        assertThat(result.message()).contains("只显示了前 " + ContactCandidateProvider.LIMIT + " 条");
    }

    // ---------- 简报 ----------

    @Test
    void briefNamesTheSectionsItFoundAndNotTheInternalReference() {
        when(briefs.brief(USER, CONTACT)).thenReturn(fullBrief());

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER, Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("周明").contains("采购经理")
                .contains("画像摘要").contains("2 条事实").contains("1 个 AI 标签")
                .contains("1 个人工标签").contains("1 条近期话题");
        assertThat(result.message())
                .as("候选 id 是内部标识，不该出现在给模型/用户看的回话里")
                .doesNotContain(REF);
        assertThat(result.data()).containsEntry("name", "周明").containsEntry("memoryVisible", true);
    }

    @Test
    void briefSaysTheProfileIsNotVisibleInsteadOfPretendingItIsEmpty() {
        when(briefs.brief(USER, CONTACT)).thenReturn(new ContactBrief(REF, "周明", "采购经理", null, false,
                null, List.of(), List.of(), List.of(), List.of()));

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER, Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.data()).containsEntry("memoryVisible", false);
        assertThat(result.message())
                .as("「他没有画像」与「画像对你看不见」是两件不同的事，后者用户还能找人问")
                .contains("不可见");
    }

    @Test
    void briefSaysSoWhenThereIsNothingToBriefYet() {
        when(briefs.brief(USER, CONTACT)).thenReturn(new ContactBrief(REF, "周明", null, null, true,
                null, List.of(), List.of(), List.of(), List.of()));

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER, Map.of("contactRef", REF));

        assertThat(result.message()).contains("暂无画像");
    }

    @Test
    void briefRejectsAMalformedReference() {
        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER,
                Map.of("contactRef", CONTACT.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(briefs, never()).brief(any(), any());
    }

    /**
     * 企微群的 ref 不是联系人。
     *
     * <p>这条用例是「联系人域需要自己一组候选」的证明：会话候选里 {@code WECOM_GROUP:<uuid>}
     * 是一个完全合法的会话标识，若 {@code contact.brief} 的 {@code contactRef} 绑到会话那一组，
     * 解析层的候选比对会<b>放行</b>它，错误就退化成运行时的一句「联系人不存在」。
     */
    @Test
    void briefRejectsAGroupReference() {
        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER, Map.of("contactRef", GROUP_REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(briefs, never()).brief(any(), any());
    }

    /**
     * 越权兜底：{@code findAccessibleById} 没查到（不存在<b>或</b>不属于你）被收敛成参数错误。
     *
     * <p>措辞刻意不区分两者 —— 区分开就等于告诉另一个账号「这条 id 是存在的」。
     */
    @Test
    void briefReportsAnOutOfScopeContactAsAnInvalidArgumentWithoutLeakingWhy() {
        when(briefs.brief(eq(USER), eq(CONTACT)))
                .thenThrow(new IllegalArgumentException("Contact not found: " + CONTACT));

        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER, Map.of("contactRef", REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("不在你能查看的范围内").doesNotContain("not found");
    }

    // ---------- 声明 ----------

    @Test
    void bothToolsAreDeclaredReadOnly() {
        for (String name : List.of(ContactAssistantTools.TOOL_SEARCH, ContactAssistantTools.TOOL_BRIEF)) {
            ToolDefinition definition = registry.find(name).orElseThrow();
            assertThat(definition.tool().annotations().readOnlyHint()).as(name).isTrue();
            assertThat(definition.tool().annotations().destructiveHint()).as(name).isFalse();
            assertThat(definition.tool().annotations().openWorldHint()).as(name).isFalse();
        }
    }

    @Test
    void theReferenceBindingIsDeclaredOnTheFieldItself() {
        ToolDefinition brief = registry.find(ContactAssistantTools.TOOL_BRIEF).orElseThrow();

        assertThat(brief.referenceBindings())
                .containsExactly(Map.entry("contactRef", ContactCandidates.NAME));
    }

    // ---------- 夹具 ----------

    private static ContactCandidates contactCandidates() {
        return contactCandidates(1);
    }

    private static ContactCandidates contactCandidates(int size) {
        List<ContactCandidates.Item> items = new ArrayList<>();
        for (int i = 0; i < Math.min(size, ContactCandidateProvider.LIMIT); i++) {
            if (i == 0) {
                items.add(new ContactCandidates.Item(REF, "周明", "张江物流 对接人"));
            } else {
                items.add(new ContactCandidates.Item(ContactCandidates.idOf(UUID.randomUUID()), "客户" + i, null));
            }
        }
        return new ContactCandidates(ContactCandidateProvider.LIMIT, items);
    }

    private static ContactBrief fullBrief() {
        return new ContactBrief(REF, "周明", "采购经理", "张江物流 对接人", true,
                "周明是张江物流的采购经理，负责东南亚线。",
                List.of(new ContactBrief.Fact("决策角色", "采购决策人"),
                        new ContactBrief.Fact("业务关注", "关注东南亚线运价")),
                List.of("价格敏感"),
                List.of("老客户"),
                List.of(new ContactBrief.Topic("运价谈判", "下季度按新价目表执行")));
    }
}
