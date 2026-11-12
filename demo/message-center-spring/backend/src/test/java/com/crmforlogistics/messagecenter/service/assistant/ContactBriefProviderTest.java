package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryModels;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * <b>连取数入口都没有</b>：{@link ContactBriefProvider} 注入的是 {@code ContactMapper} /
 * {@code ContactMemoryMapper} / {@code ContactTagMapper} / {@code ContactMemoryStateMapper} 四个，
 * 而前三个里唯一的内容来源 {@code listStableContext} 只回画像 + 事实 + 标签 + 话题四节；
 * 第四个读的 {@code contact_memory_states} 是纯元数据表（没有内容字段、也没有消息外键）。
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
    private final ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
    private final ContactService contactService = mock(ContactService.class);
    private final ContactBriefProvider provider =
            new ContactBriefProvider(contacts, memory, humanTags, states, contactService);

    // ---------- 授权 ----------

    @Test
    void anInaccessibleContactIsReportedAsNotFound() {
        when(contacts.findAccessibleById(eq(CONTACT), eq(USER), anyBoolean())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.brief(USER, CONTACT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memory, never()).listStableContext(any(), any(), anyInt());
        verify(contactService, never()).listAuthorizedChannelTypes(any(), any());
    }

    @Test
    void briefCarriesAuthorizedChannelProfilesAndNoInternalIdentityFields() {
        givenAccessible(contact(OTHER_USER, "周明", null, null));
        when(contactService.listAuthorizedChannelProfiles(USER, CONTACT)).thenReturn(List.of(
                new ContactService.AuthorizedChannelProfile("email", "buyer@example.invalid", "采购邮箱", null),
                new ContactService.AuthorizedChannelProfile("chatapp", "15550001111", "王经理", "北美账号")));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.toData()).containsEntry("channelTypes", List.of("chatapp", "email"));
        assertThat(brief.toData().get("channels").toString())
                .contains("buyer@example.invalid", "15550001111", "北美账号")
                .doesNotContain("normalizedValue", "identityScope", "private-account-id");
        assertThat(brief.toData()).doesNotContainKeys("normalizedValue", "identityScope", "identities");
        verify(contactService).listAuthorizedChannelProfiles(USER, CONTACT);
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
        // 状态同理：那是别人名下那条流水线的状态，既无用也不该给。
        verify(states, never()).findByOwnerAndContact(any(), any());
        assertThat(brief.memoryState()).isNull();
        assertThat(brief.memoryFailureCode()).isNull();
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
        assertThat(brief.aiLabels()).extracting(ContactBrief.AiLabel::name).containsExactly("价格敏感");
        // 分类与置信度是 2026-09-23 补的：只有名字时「价格敏感」是一条孤立结论，
        // 补上这两项，模型才知道它在销售语境里属于哪一类看法、系统有多确定。
        assertThat(brief.aiLabels()).extracting(ContactBrief.AiLabel::category)
                .containsExactly("DECISION_FACTOR");
        assertThat(brief.aiLabels()).extracting(ContactBrief.AiLabel::confidence)
                .as("DB 标度是 3（0.870），给模型的应是 0.87 —— 尾零会把「大致确定」讲成精确测量")
                .containsExactly(0.87);
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

    // ---------- 处理状态 ----------

    /**
     * 「他的标签为什么没更新」必须答得出来。
     *
     * <p>补这两个字段之前，这个问题只能靠「标签看着有点旧」去猜；而状态只有五种，
     * 猜错的代价是用户按一个假原因去处理（例如以为系统坏了，其实是那批消息还在排队）。
     */
    @Test
    void theOwnersViewCarriesTheMemoryStateAndTheFailureCode() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable());
        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setStatus("FAILED");
        state.setLastFailureCode("INVALID_OUTPUT");
        when(states.findByOwnerAndContact(USER, CONTACT)).thenReturn(Optional.of(state));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.memoryState()).isEqualTo("FAILED");
        assertThat(brief.memoryFailureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(brief.toData())
                .containsEntry("memoryState", "FAILED")
                .containsEntry("memoryFailureCode", "INVALID_OUTPUT");
    }

    /**
     * 没有状态行 = 这条流水线从没被标脏过，读作 {@code CLEAN}，而<b>不是</b>「状态未知」。
     *
     * <p>这条归一与页面（{@code ContactMemoryQueryService}）走同一个方法。两处各判一次，
     * 早晚会一边说「干净」、另一边说「不知道」—— 而用户看到的差别是「不用管」与「是不是坏了」。
     */
    @Test
    void aContactWithoutAnyStateRowReadsAsClean() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable());
        when(states.findByOwnerAndContact(USER, CONTACT)).thenReturn(Optional.empty());

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.memoryState()).isEqualTo("CLEAN");
        assertThat(brief.memoryFailureCode()).isNull();
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
                .allSatisfy(label -> assertThat(label.name()).hasSize(ContactBriefProvider.NAMED_MAX_CHARS));
        assertThat(brief.humanTags()).hasSize(1)
                .allSatisfy(name -> assertThat(name).hasSize(ContactBriefProvider.NAMED_MAX_CHARS));
        assertThat(brief.topics()).hasSize(ContactBriefProvider.TOPIC_LIMIT);
        assertThat(brief.topics().get(0).title()).hasSize(ContactBriefProvider.TOPIC_TITLE_MAX_CHARS);
        assertThat(brief.topics().get(0).summary()).hasSize(ContactBriefProvider.TOPIC_SUMMARY_MAX_CHARS);
    }

    /**
     * 标签的<b>条数</b>上限。
     *
     * <p>{@link #everySectionIsBounded} 用的夹具是同名标签，会被去重成一条 —— 那验的是去重与截断，
     * 验不到 {@link ContactBriefProvider#LABEL_LIMIT} 本身。这里用互不相同的名字把条数上限单独钉住：
     * 标签条数直接决定 observation 的大小，而这件事没有别的地方会失败。
     */
    @Test
    void distinctLabelsAreCappedAtTheLimitAndEachCarriesItsCategory() {
        givenAccessible(contact(USER, "周明", null, null));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                null, List.of(),
                distinctLabels(ContactBriefProvider.LABEL_LIMIT + 5, "标签"),
                List.of()));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.aiLabels()).hasSize(ContactBriefProvider.LABEL_LIMIT);
        assertThat(brief.aiLabels()).allSatisfy(label -> {
            assertThat(label.name()).hasSizeLessThanOrEqualTo(ContactBriefProvider.NAMED_MAX_CHARS);
            assertThat(label.category()).isEqualTo("PRODUCT_INTEREST");
        });
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
                "contactRef", "name", "roleTitle", "remark", "channelTypes",
                "channels",
                "memoryVisible", "memoryState", "memoryFailureCode",
                "profile", "facts", "aiLabels", "humanTags", "topics");
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
     * 而是断言它的<b>可观察后果</b>：{@code AssistantPromptBuilder} 那句「结果过长」的提示不出现。
     * 各节上限一旦被人调大，这个用例就会失败 —— 而不是在线上让模型收到半份数据却以为自己看全了。
     *
     * <p>判据刻意<b>不</b>用「已截断」这三个字：各节字段自己也开始带这个标记了（见 {@code Texts}），
     * 拿它当代理会让这条预算断言变成恒假。这里改的是判据而不是文案 ——
     * 「结果过长」是 {@code AssistantPromptBuilder} 独有的措辞，指哪打哪。
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
                .as("整条 observation 被截断会让模型拿到半份数据却不知道")
                .doesNotContain("结果过长")
                .contains("近期话题");
    }

    /**
     * 画像被截断时，模型必须能看出「这不是全部」。
     *
     * <p>这是本轮那条发现里最要紧的一处：{@code ContactBriefProvider} 的画像原本是裸截断的，
     * 而模型正是拿画像回答「他的画像是什么」—— 400 字后戛然而止，它无从知道后面还有。
     * 论证与 observation 那处完全相同（静默截断会让模型把「只看到一半」当成「就这么多」），
     * 只是这个结论原先没有推广到这一处。
     */
    @Test
    void aTruncatedProfileCarriesAVisibleMarker() {
        givenAccessible(contact(USER, "周明", "采购经理", "备注"));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                "画".repeat(ContactBriefProvider.PROFILE_MAX_CHARS * 3), List.of(), List.of(), List.of()));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.profile())
                .as("没有标记，模型就会把半句画像当作完整画像来回答")
                .endsWith("…（已截断）");
        assertThat(brief.profile())
                .as("标记计入上限：各节上限之和才是提示词的预算，不能因为多了句说明就突破")
                .hasSize(ContactBriefProvider.PROFILE_MAX_CHARS);
    }

    /**
     * 标识类字段<b>不</b>加标记。
     *
     * <p>判据是「模型会不会把它当作一条完整的事实用来回答」：显示名、职务的用途是<b>指认</b>，
     * 截断后加个「已截断」既不改变指认结果，又会让清单变难读。
     * 这条断言把「不是所有截断都加标记」钉住 —— 否则下一个人会顺手把标记铺到每一处。
     */
    @Test
    void identityFieldsAreTruncatedWithoutAMarker() {
        givenAccessible(contact(USER,
                "名".repeat(ContactBriefProvider.NAME_MAX_CHARS * 3),
                "职".repeat(ContactBriefProvider.NAMED_MAX_CHARS * 3),
                "备注"));
        when(memory.listStableContext(eq(USER), eq(CONTACT), anyInt())).thenReturn(stable(
                "画".repeat(ContactBriefProvider.PROFILE_MAX_CHARS * 3), List.of(), List.of(), List.of()));

        ContactBrief brief = provider.brief(USER, CONTACT);

        assertThat(brief.name()).as("显示名是指认用的").doesNotContain("已截断")
                .hasSize(ContactBriefProvider.NAME_MAX_CHARS);
        assertThat(brief.roleTitle()).as("职务同理").doesNotContain("已截断")
                .hasSize(ContactBriefProvider.NAMED_MAX_CHARS);
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
                List.of(label("价格敏感", "DECISION_FACTOR", "0.870")),
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

    private static ContactAiLabelEntity label(String name, String category, String confidence) {
        ContactAiLabelEntity label = new ContactAiLabelEntity();
        label.setDisplayName(name);
        label.setCategory(category);
        label.setConfidence(confidence == null ? null : new BigDecimal(confidence));
        return label;
    }

    /** 同名标签（用于验证去重语义：清单不该被重复项占满）。 */
    private static List<ContactAiLabelEntity> labels(int count, String name) {
        List<ContactAiLabelEntity> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            labels.add(label(name, null, null));
        }
        return labels;
    }

    /** 互不相同的标签（用于验证条数上限 —— 同名会被去重，那就测不到上限了）。 */
    private static List<ContactAiLabelEntity> distinctLabels(int count, String prefix) {
        List<ContactAiLabelEntity> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            labels.add(label(prefix + i, "PRODUCT_INTEREST", "0.800"));
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
