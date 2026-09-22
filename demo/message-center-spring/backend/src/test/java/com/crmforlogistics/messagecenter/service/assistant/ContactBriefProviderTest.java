package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryModels;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简报的取数与裁剪。
 *
 * <h2>四条被验的事，各对应一类「不会报错的坏」</h2>
 * <ol>
 *   <li><b>记忆只对归属人可见</b>。记忆表以联系人的 {@code created_by} 为归属人，
 *       而联系人的可见性口径更宽（管理员 / 团队分配 / 授权表）。两者混起来不会报错，
 *       只会让管理员看到别人的客户画像 —— 那是数据事故，不是显示问题。</li>
 *   <li><b>每一节都有上限</b>。画像正文可能几千字、事实可能几十条。没有上限时功能照常工作，
 *       只是某天模型开始收到一条被提示词静默截断的 observation。</li>
 *   <li><b>白名单之外没有出口</b>。{@code toData()} 的键集合就是「发到模型供应商的东西」。
 *       多一个键就是多一类数据出边界，而没有任何断言会因此失败。</li>
 *   <li><b>加总仍在预算内</b>（最后一个用例）。各节各自有上限、加总却越过 observation 上限，
 *       是一种很自然的失误 —— 单看每个常量都合理。</li>
 * </ol>
 *
 * <p>{@code inboundMessages} / {@code transcripts}（消息原文与通话转写）在这条链路上
 * <b>连取数入口都没有</b>：{@link ContactBriefProvider} 只注入了 {@code ContactMapper} /
 * {@code ContactMemoryMapper} / {@code ContactTagMapper} 三个依赖，而
 * {@code listStableContext} 返回的是画像 + 事实 + 标签 + 话题四节。
 * 所以这里没有「过滤掉正文」的测试 —— 没有可过滤的东西才是更强的保证
 * （理由详见 {@link ContactBrief} 的类注释）。
 */
class ContactBriefProviderTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID OTHER_USER = AssistantFixtures.OTHER_USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;

    private final ContactMapper contacts = mock(ContactMapper.class);
    private final ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
    private final ContactTagMapper humanTags = mock(ContactTagMapper.class);
    private final ContactBriefProvider provider = new ContactBriefProvider(contacts, memory, humanTags);

    // ---------- 授权 ----------

    @Test
    void anInaccessibleContactIsReportedAsNotFound() {
        when(contacts.findAccessibleById(eq(CONTACT), eq(USER), anyBoolean())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.brief(USER, CONTACT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memory, never()).listStableContext(any(), any(), anyInt());
    }

    @Test
    void aMissingUserIdFailsBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> provider.brief(null, CONTACT)).isInstanceOf(IllegalArgumentException.class);
        verify(contacts, never()).findAccessibleById(any(), any(), anyBoolean());
    }

    // ---------- 记忆的可见性 ----------

    /**
     * 别人录入的联系人：看得到人，看不到画像 —— 而且要<b>真的没去查</b>。
     *
     * <p>{@code never()} 是关键的一半：如果只是「查了但不返回」，画像还是进过 JVM、
     * 进过连接池的查询结果；更糟的是某天有人把返回值接回去就静默泄漏了。
     */
    @Test
    void memoryIsInvisibleAndNotEvenQueriedWhenTheContactWasCreatedBySomeoneElse() {
        givenAccessible(contact(OTHER_USER, "周明", "采购经理", "张江物流 对接人"));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.memoryVisible()).isFalse();
        assertThat(brief.name()).isEqualTo("周明");
        assertThat(brief.roleTitle()).isEqualTo("采购经理");
        assertThat(brief.hasMemory()).isFalse();
        verify(memory, never()).listStableContext(any(), any(), anyInt());
        verify(humanTags, never()).findActiveByContactIdAndOwner(any(), any());
    }

    @Test
    void theCreatorGetsTheFourMemorySections() {
        givenAccessible(contact(USER, "周明", "采购经理", null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable());
        when(humanTags.findActiveByContactIdAndOwner(eq(CONTACT), eq(USER)))
                .thenReturn(List.of(new ContactTagResponse(UUID.randomUUID(), "老客户", "#fff")));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.memoryVisible()).isTrue();
        assertThat(brief.profile()).isEqualTo("周明是张江物流的采购经理，负责东南亚线。");
        assertThat(brief.facts()).extracting(ContactBrief.Fact::category)
                .containsExactly("决策角色", "业务关注");
        assertThat(brief.facts()).extracting(ContactBrief.Fact::value)
                .containsExactly("采购决策人", "关注东南亚线运价");
        assertThat(brief.aiLabels()).containsExactly("价格敏感");
        assertThat(brief.humanTags()).containsExactly("老客户");
        assertThat(brief.topics()).extracting(ContactBrief.Topic::title).containsExactly("运价谈判");
        // 人工确认过的小结优先于模型小结：会前准备要的是「上次到底谈成什么」。
        assertThat(brief.topics().get(0).summary()).isEqualTo("已确认：下季度按新价目表执行。");
    }

    /** 人工标签为空时不能拖垮整份简报 —— 它是一个可选的节，不是前提。 */
    @Test
    void anEmptyManualTagListIsNotAFailure() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable());
        when(humanTags.findActiveByContactIdAndOwner(eq(CONTACT), eq(USER))).thenReturn(List.of());

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.humanTags()).isEmpty();
        assertThat(brief.memoryVisible()).isTrue();
    }

    @Test
    void aContactWithNoMemoryAtAllStillYieldsABrief() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(null);

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.profile()).isNull();
        assertThat(brief.facts()).isEmpty();
        assertThat(brief.topics()).isEmpty();
        assertThat(brief.hasMemory()).isFalse();
        assertThat(brief.memoryVisible()).isTrue();
    }

    /**
     * 一条事实的两个值字段都是空的：跳过它，而不是造一个空字符串占位。
     *
     * <p>「跳过」与「编造」是同一件事的两面 —— 留着一条没有值的事实，模型会把它读成
     * 「这个人有一条不知道内容的事实」，那是在替数据捏造意义。
     */
    @Test
    void aFactWithoutAnyValueIsSkipped() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                null,
                List.of(fact("决策角色", "采购决策人"), blankFact()),
                List.of(),
                List.of()));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.facts()).extracting(ContactBrief.Fact::value).containsExactly("采购决策人");
    }

    // ---------- 上限 ----------

    @Test
    void everySectionIsBounded() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                "画".repeat(2000),
                facts(ContactBriefProvider.FACT_LIMIT + 5,
                        "类".repeat(ContactBriefProvider.FACT_CATEGORY_MAX_CHARS + 20),
                        "值".repeat(ContactBriefProvider.FACT_VALUE_MAX_CHARS + 100)),
                labels(ContactBriefProvider.LABEL_LIMIT + 5, "标".repeat(ContactBriefProvider.NAMED_MAX_CHARS + 50)),
                topics(ContactBriefProvider.TOPIC_LIMIT + 3,
                        "题".repeat(ContactBriefProvider.TOPIC_TITLE_MAX_CHARS + 30),
                        "要".repeat(ContactBriefProvider.TOPIC_SUMMARY_MAX_CHARS + 100))));
        when(humanTags.findActiveByContactIdAndOwner(eq(CONTACT), eq(USER)))
                .thenReturn(tags(ContactBriefProvider.TAG_LIMIT + 5, "签".repeat(ContactBriefProvider.NAMED_MAX_CHARS + 50)));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.profile()).hasSize(ContactBriefProvider.PROFILE_MAX_CHARS);
        assertThat(brief.facts()).hasSize(ContactBriefProvider.FACT_LIMIT);
        assertThat(brief.facts().get(0).value()).hasSize(ContactBriefProvider.FACT_VALUE_MAX_CHARS);
        assertThat(brief.facts().get(0).category()).hasSize(ContactBriefProvider.FACT_CATEGORY_MAX_CHARS);
        assertThat(brief.aiLabels()).hasSize(1)
                .as("同名标签去重后才截断 —— 否则清单会被重复项占满")
                .allSatisfy(name -> assertThat(name).hasSize(ContactBriefProvider.NAMED_MAX_CHARS));
        assertThat(brief.humanTags()).hasSize(1)
                .allSatisfy(name -> assertThat(name).hasSize(ContactBriefProvider.NAMED_MAX_CHARS));
        assertThat(brief.topics()).hasSize(ContactBriefProvider.TOPIC_LIMIT);
        assertThat(brief.topics().get(0).title()).hasSize(ContactBriefProvider.TOPIC_TITLE_MAX_CHARS);
        assertThat(brief.topics().get(0).summary()).hasSize(ContactBriefProvider.TOPIC_SUMMARY_MAX_CHARS);
    }

    /** 备注与显示名也是自由文本，同样要有上限；空值收敛成 {@code null} 而不是空串。 */
    @Test
    void namesAndRemarksAreBoundedAndBlanksBecomeNull() {
        givenAccessible(contact(USER, "周明", null, "备".repeat(1000)));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.remark()).hasSize(ContactBriefProvider.REMARK_MAX_CHARS);
        assertThat(brief.name()).as("显示名非空时就不该被备注顶替").isEqualTo("周明");

        givenAccessible(contact(USER, "名".repeat(1000), null, null));
        assertThat(provider.brief(USER, CONTACT).name())
                .as("显示名可以是一整句公司全称，因此它也必须有上限")
                .hasSize(ContactBriefProvider.NAME_MAX_CHARS);

        givenAccessible(contact(USER, null, null, "张江物流"));
        assertThat(provider.brief(USER, CONTACT).name())
                .as("显示名为空时才退回备注 —— 退回同一行的数据，不是编一个名字")
                .isEqualTo("张江物流");

        givenAccessible(contact(USER, "周明", null, "   "));
        assertThat(provider.brief(USER, CONTACT).remark()).isNull();
    }

    // ---------- 白名单 ----------

    /**
     * {@code toData()} 的键集合就是「发到模型供应商的东西」。这条断言是白名单的守卫：
     * 多一个键（例如有人顺手把联系方式或消息正文加进来）必须在这里显式改一次 ——
     * 那一步正是让人停下来问「这条内容出系统边界了吗」的地方。
     */
    @Test
    void thePayloadContainsExactlyTheWhitelistedKeys() {
        givenAccessible(contact(USER, "周明", "采购经理", "张江物流"));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable());
        when(humanTags.findActiveByContactIdAndOwner(eq(CONTACT), eq(USER))).thenReturn(List.of());

        Map<String, Object> data = provider.brief(USER, CONTACT).toData();

        assertThat(data.keySet()).containsExactlyInAnyOrder(
                "contactRef", "name", "roleTitle", "remark",
                "memoryVisible", "profile", "facts", "aiLabels", "humanTags", "topics");
        assertThat(data.get("contactRef")).isEqualTo(ContactCandidates.idOf(CONTACT));
        assertThat(data.toString())
                .as("候选 id 是模型回引用所必需的；除它之外不该出现别的内部标识")
                .doesNotContain("createdBy").doesNotContain("ownerUserId").doesNotContain("identityValue")
                .doesNotContain("email").doesNotContain("phone");
    }

    // ---------- 预算 ----------

    /**
     * 最坏情况：各节都顶到上限时，整条 observation 仍不该被截断。
     *
     * <p>走的是<b>真实链路</b>（真 provider → 真工具 → {@code AssistantPromptBuilder.withReadResult}），
     * 而不是自己造一个 ToolResult：只有这样才同时覆盖了「工具的消息行有没有重复抄一遍明细」
     * 与「{@code toData()} 的包装开销」这两处会吃预算的地方。
     *
     * <p>提示词对单条 observation 的上限是私有的实现细节，因此这里不引用那个数字，
     * 而是断言它的<b>可观察后果</b>：那条「已截断」的提示不出现。各节上限一旦被人调大，
     * 这个用例就会失败 —— 而不是在线上让模型收到半份数据却以为自己看全了。
     */
    @Test
    void aWorstCaseBriefStillFitsInASingleObservation() {
        givenAccessible(contact(USER,
                "名".repeat(ContactBriefProvider.NAME_MAX_CHARS * 3),
                "职".repeat(ContactBriefProvider.NAMED_MAX_CHARS * 3),
                "注".repeat(ContactBriefProvider.REMARK_MAX_CHARS * 3)));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                "画".repeat(ContactBriefProvider.PROFILE_MAX_CHARS * 3),
                facts(ContactBriefProvider.FACT_LIMIT,
                        "类".repeat(ContactBriefProvider.FACT_CATEGORY_MAX_CHARS * 3),
                        "值".repeat(ContactBriefProvider.FACT_VALUE_MAX_CHARS * 3)),
                labels(ContactBriefProvider.LABEL_LIMIT,
                        "标".repeat(ContactBriefProvider.NAMED_MAX_CHARS * 3)),
                topics(ContactBriefProvider.TOPIC_LIMIT,
                        "题".repeat(ContactBriefProvider.TOPIC_TITLE_MAX_CHARS * 3),
                        "要".repeat(ContactBriefProvider.TOPIC_SUMMARY_MAX_CHARS * 3))));
        when(humanTags.findActiveByContactIdAndOwner(eq(CONTACT), eq(USER)))
                .thenReturn(tags(ContactBriefProvider.TAG_LIMIT,
                        "签".repeat(ContactBriefProvider.NAMED_MAX_CHARS * 3)));

        ContactAssistantTools tools = new ContactAssistantTools(
                mock(ContactCandidateProvider.class), provider);
        ToolRegistry registry = new ToolRegistry(List.of(tools.contactBriefTool()),
                new ToolInputValidator(), AssistantFixtures.objectMapper());
        ToolResult result = registry.invoke(ContactAssistantTools.TOOL_BRIEF, USER,
                Map.of("contactRef", ContactCandidates.idOf(CONTACT)));
        assertThat(result.isError()).as("最坏情况也必须是一次成功的调用").isFalse();

        AssistantPromptBuilder prompts = new AssistantPromptBuilder(
                registry, AssistantFixtures.config(), AssistantFixtures.objectMapper());
        List<Map<String, String>> messages = prompts.withReadResult(
                prompts.buildMessages(AssistantFixtures.emptyContext(), List.of(), "帮我准备一下明天和周明的会"),
                "{\"decision\":\"call\"}", ContactAssistantTools.TOOL_BRIEF, result);

        String observation = messages.get(messages.size() - 1).get("content");
        assertThat(observation)
                .as("截断会让模型拿到半份数据却不知道被截断过")
                .doesNotContain("已截断")
                .contains("近期话题");
    }

    // ---------- 夹具 ----------

    private void givenAccessible(ContactEntity entity) {
        when(contacts.findAccessibleById(eq(CONTACT), eq(USER), anyBoolean()))
                .thenReturn(Optional.of(entity));
    }

    private static ContactEntity contact(UUID createdBy, String displayName, String roleTitle, String remark) {
        ContactEntity entity = new ContactEntity();
        entity.setId(CONTACT);
        entity.setCreatedBy(createdBy);
        entity.setDisplayName(displayName);
        entity.setRoleTitle(roleTitle);
        entity.setRemark(remark);
        return entity;
    }

    private static ContactMemoryModels.StableContext stable() {
        return stable("周明是张江物流的采购经理，负责东南亚线。",
                List.of(fact("决策角色", "采购决策人"), fact("业务关注", "关注东南亚线运价")),
                labels(1, "价格敏感"),
                topics(1, "运价谈判", "已确认：下季度按新价目表执行。"));
    }

    private static ContactMemoryModels.StableContext stable(String profile,
                                                            List<ContactMemoryFactEntity> facts,
                                                            List<ContactAiLabelEntity> labels,
                                                            List<AiTopicEntity> topics) {
        ContactProfileVersionEntity version = new ContactProfileVersionEntity();
        version.setContent(profile);
        version.setVersion(3L);
        return new ContactMemoryModels.StableContext(version, facts, labels, topics);
    }

    private static ContactMemoryFactEntity fact(String category, String value) {
        ContactMemoryFactEntity fact = new ContactMemoryFactEntity();
        fact.setCategory(category);
        fact.setDisplayValue(value);
        return fact;
    }

    /** 两个值字段都为空的事实：用于验证「跳过而不是造一个空串」。 */
    private static ContactMemoryFactEntity blankFact() {
        ContactMemoryFactEntity fact = new ContactMemoryFactEntity();
        fact.setCategory("决策角色");
        return fact;
    }

    private static List<ContactMemoryFactEntity> facts(int count, String category, String value) {
        List<ContactMemoryFactEntity> facts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            facts.add(fact(category, value));
        }
        return facts;
    }

    private static List<ContactAiLabelEntity> labels(int count, String name) {
        List<ContactAiLabelEntity> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ContactAiLabelEntity label = new ContactAiLabelEntity();
            label.setDisplayName(name);
            labels.add(label);
        }
        return labels;
    }

    private static List<ContactTagResponse> tags(int count, String name) {
        List<ContactTagResponse> tags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tags.add(new ContactTagResponse(UUID.randomUUID(), name, "#fff"));
        }
        return tags;
    }

    private static List<AiTopicEntity> topics(int count, String title, String summary) {
        List<AiTopicEntity> topics = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AiTopicEntity topic = new AiTopicEntity();
            topic.setTitle(title);
            topic.setAiSummary(summary);
            topics.add(topic);
        }
        return topics;
    }
}
