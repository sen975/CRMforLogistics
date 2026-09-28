package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicException;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TopicCandidates;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 话题域三个工具。
 *
 * <h2>最要紧的一条：只改标题不能把人工确认过的摘要清掉</h2>
 * {@code AiTopicMapper.updateEmployee} 的 SQL 是 {@code set title=..., confirmed_summary=#{confirmedSummary}}
 * —— <b>无条件赋值</b>。模型说「把标题改成 X」时若把摘要传成 {@code null}，那个人工确认过的摘要
 * 会被一起清掉，而调用方、用户、日志三边都不会有任何提示。所以工具必须<b>先读一次</b>，
 * 摘要缺席时用当前值。这里的用例把这条规则钉死。
 *
 * <h2>第二条：topicRef 只能来自 contact.topics_read</h2>
 * topic 候选<b>没有预置窗口</b>（话题是按联系人取的）。所以"没读过就想改"必须失败，
 * 而失败点应当在提问路径的候选比对，而不是运行时的"话题不存在"。这里验的是工具自己那一半：
 * 形状不对的 ref 在动手前就被挡下。
 */
class AiTopicAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String CONTACT_REF = AssistantFixtures.CONTACT_ZHOU_REF;
    private static final UUID TOPIC_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TOPIC_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String TOPIC_A_REF = TopicCandidates.idOf(TOPIC_A);
    private static final String TOPIC_B_REF = TopicCandidates.idOf(TOPIC_B);

    private final AiTopicService topics = mock(AiTopicService.class);
    private final AiTopicAssistantTools tools = new AiTopicAssistantTools(topics);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.contactTopicsReadTool(), tools.contactTopicsRetryTool(),
                    tools.contactTopicUpdateTool(), tools.contactTopicsMergeTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 读话题 ----------

    @Test
    void readTopicsHandsBackATopicWindowSoTheNextRoundCanReferenceIt() {
        when(topics.getTopics(USER, CONTACT)).thenReturn(timeline(AiTopicModels.GenerationStatus.READY,
                List.of(topic(TOPIC_A, "运价谈判", "下季度按新价目表执行", 3L)), false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_READ, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("共 1 条话题").contains("运价谈判");
        assertThat(result.candidates())
                .as("这是本域唯一的顺序约束：topicRef 只能从这条结果里来")
                .isInstanceOf(TopicCandidates.class);
        assertThat(result.candidates().contains(TOPIC_A_REF)).isTrue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("topics");
        assertThat(items.get(0)).containsEntry("topicRef", TOPIC_A_REF)
                .containsEntry("title", "运价谈判")
                .containsEntry("version", 3L)
                // 摘要是允许进模型的（口径 b 放行的正是"结构化字段与摘要"），所以这里必须有。
                .containsEntry("summary", "下季度按新价目表执行")
                .containsEntry("summarySource", "AI");
        assertThat(items.get(0).keySet())
                .as("sourceItems 之类的内部 id 清单不该进提示词")
                .containsExactlyInAnyOrder("topicRef", "title", "summary", "summarySource",
                        "channels", "sourceCount", "lastOccurredAt", "version");
    }

    @Test
    void readTopicsTruncatesAtTheWindowSizeAndSaysSo() {
        when(topics.getTopics(USER, CONTACT)).thenReturn(
                timeline(AiTopicModels.GenerationStatus.READY, manyTopics(TopicCandidates.LIMIT + 4), false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_READ, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.data()).containsEntry("topicCount", TopicCandidates.LIMIT);
        assertThat(((TopicCandidates) result.candidates()).items()).hasSize(TopicCandidates.LIMIT);
        assertThat(result.message())
                .as("不说的话模型会把「只给了 30 条」当成「一共只有 30 条」")
                .contains("共 " + (TopicCandidates.LIMIT + 4) + " 条");
    }

    /** 读过但确实没有话题 —— 空结果仍要交回（空的）候选，这样"没读过"与"读过没有"是两种状态。 */
    @Test
    void readTopicsReturnsAnEmptyWindowRatherThanNoWindow() {
        when(topics.getTopics(USER, CONTACT)).thenReturn(
                timeline(AiTopicModels.GenerationStatus.NOT_STARTED, List.of(), false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_READ, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("还没有话题");
        assertThat(result.candidates()).isNotNull();
        assertThat(result.candidates().items()).isEmpty();
    }

    @Test
    void readTopicsExplainsTheWeComOnlyCaseInsteadOfBlamingTheUser() {
        when(topics.getTopics(USER, CONTACT)).thenReturn(
                timeline(AiTopicModels.GenerationStatus.NOT_STARTED, List.of(), true));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_READ, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.message()).contains("只有企业微信渠道");
    }

    @Test
    void readTopicsReportsAnOutOfScopeContactAsAccessFailure() {
        when(topics.getTopics(USER, CONTACT)).thenThrow(new IllegalArgumentException("Contact not found"));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_READ, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    // ---------- 重算话题 ----------

    @Test
    void retryReportsTheGenerationStatusInPlainWords() {
        when(topics.retryGeneration(USER, CONTACT)).thenReturn(
                new AiTopicModels.GenerationProjection(AiTopicModels.GenerationStatus.GENERATING,
                        UUID.randomUUID(), null, Instant.now()));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_RETRY, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已提交重新生成");
        assertThat(result.data()).containsEntry("status", "GENERATING");
    }

    @Test
    void retrySaysNothingHappenedWhenThereIsNothingToGenerateFrom() {
        when(topics.retryGeneration(USER, CONTACT)).thenReturn(
                new AiTopicModels.GenerationProjection(AiTopicModels.GenerationStatus.NOT_STARTED,
                        null, null, Instant.now()));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_RETRY, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.isError())
                .as("没内容可算不是失败：用户重试也不会变，必须如实说")
                .isFalse();
        assertThat(result.message()).contains("还没有可用于生成话题的往来内容");
    }

    @Test
    void retryExplainsTheUnsupportedChannelCase() {
        when(topics.retryGeneration(USER, CONTACT)).thenReturn(
                new AiTopicModels.GenerationProjection(AiTopicModels.GenerationStatus.NOT_STARTED,
                        null, "WECOM_AI_UNSUPPORTED", Instant.now()));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_RETRY, USER,
                Map.of("contactRef", CONTACT_REF));

        assertThat(result.message()).contains("只有企业微信渠道");
    }

    // ---------- 改话题 ----------

    @Test
    void changingOnlyTheTitleKeepsTheExistingSummary() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "人工确认过的摘要", 3L));
        when(topics.updateTopic(eq(USER), eq(TOPIC_A), any(), any(), anyLong()))
                .thenReturn(topic(TOPIC_A, "运价与账期", "人工确认过的摘要", 4L));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", TOPIC_A_REF, "title", "运价与账期"));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        // 版本号不放进 captor：ArgumentCaptor.capture() 返回 null，而 updateTopic 的第 5 个参数是基元 long，
        // 拆箱会当场 NPE。直接断言字面量更省事，也更清楚。
        verify(topics).updateTopic(eq(USER), eq(TOPIC_A), eq("运价与账期"), summary.capture(), eq(3L));

        assertThat(summary.getValue())
                .as("摘要缺席时必须回填当前值 —— 传 null 会把人工确认过的摘要静默清掉")
                .isEqualTo("人工确认过的摘要");
        assertThat(result.message()).contains("运价谈判").contains("运价与账期");
        assertThat(result.data()).containsEntry("previousTitle", "运价谈判").containsEntry("title", "运价与账期");
    }

    @Test
    void anExplicitSummaryOverridesTheCurrentOne() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "旧摘要", 3L));
        when(topics.updateTopic(eq(USER), eq(TOPIC_A), any(), any(), anyLong()))
                .thenReturn(topic(TOPIC_A, "运价谈判", "新摘要", 4L));

        registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", TOPIC_A_REF, "title", "运价谈判", "summary", "新摘要"));

        verify(topics).updateTopic(eq(USER), eq(TOPIC_A), eq("运价谈判"), eq("新摘要"), eq(3L));
    }

    @Test
    void updateTopicRequiresATitleBecauseTheServiceDoes() {
        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", TOPIC_A_REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.MISSING_ARGUMENT);
        verify(topics, never()).updateTopic(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aContactReferenceIsNotATopicReference() {
        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", CONTACT_REF, "title", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(topics, never()).getTopic(any(), any());
    }

    /**
     * 版本冲突要单独说，而且不能报成系统故障。
     *
     * <p>它的处置与"没权限"完全不同：用户该做的是重新读一次话题再来，而不是重挑一个人。
     * 报成 INTERNAL 会让前端把它渲染成"系统出错了"。
     */
    @Test
    void aVersionConflictIsExplainedAsSuchRatherThanAsASystemFailure() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要", 3L));
        when(topics.updateTopic(eq(USER), eq(TOPIC_A), any(), any(), anyLong()))
                .thenThrow(new AiTopicException("TOPIC_VERSION_CONFLICT", false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", TOPIC_A_REF, "title", "新标题"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("被改过了");
    }

    @Test
    void anOutOfScopeTopicIsReportedWithoutLeakingWhy() {
        when(topics.getTopic(USER, TOPIC_A)).thenThrow(new AiTopicException("TOPIC_NOT_FOUND", false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPIC_UPDATE, USER,
                Map.of("topicRef", TOPIC_A_REF, "title", "新标题"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message())
                .as("不区分「不存在」与「不属于你」：区分开就等于告诉另一个账号这条 id 存在")
                .isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    // ---------- 合并话题（B4） ----------

    /**
     * 每一条的版本号都必须来自「执行前读一次」，而且必须逐条给全。
     *
     * <p>{@code requireFusionTopics} 对缺失的版本号直接判 {@code TOPIC_MERGE_INVALID}，
     * 而模型没有地方拿到这组数字 —— 连一个都不现实，何况 N 个。
     * 所以这个读不是优化，是这个工具能工作的前提。
     */
    @Test
    @SuppressWarnings("unchecked")
    void mergeReadsEveryVersionAndHandsThemToTheService() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要A", 3L));
        when(topics.getTopic(USER, TOPIC_B)).thenReturn(topic(TOPIC_B, "运价与账期", "摘要B", 5L));
        when(topics.mergeTopics(eq(USER), any(), any()))
                .thenReturn(List.of(topic(TOPIC_A, "运价与账期安排", "合并后的摘要", 6L)));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF, TOPIC_B_REF)));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("运价谈判").contains("运价与账期安排");
        assertThat(result.data()).containsEntry("title", "运价与账期安排")
                .containsEntry("mergedFrom", List.of("运价谈判", "运价与账期"));

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<UUID, Long>> versions = ArgumentCaptor.forClass(Map.class);
        verify(topics).mergeTopics(eq(USER), ids.capture(), versions.capture());
        assertThat(ids.getValue()).containsExactly(TOPIC_A, TOPIC_B);
        assertThat(versions.getValue())
                .as("读出来的当前版本，逐条给全")
                .containsExactly(Map.entry(TOPIC_A, 3L), Map.entry(TOPIC_B, 5L));
    }

    /**
     * 重复引用直接拒，<b>不静默去重</b>。
     *
     * <p>去重看起来更友好，但它会让确认卡片与实际动作不是同一份输入：卡片按参数渲染（列三条），
     * 执行时只剩两条。用户核对过的东西必须就是将要发生的事，这条优先级高于"少一次往返"。
     */
    @Test
    void aRepeatedTopicIsRejectedRatherThanSilentlyDeduplicated() {
        // 必须 stub 第一遍的读：重复是在第二遍才发现的，第一遍会正常读到。
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要A", 3L));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF, TOPIC_A_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("重复");
        verify(topics, never()).mergeTopics(any(), any(), any());
    }

    @Test
    void mergeRefusesASingleTopicBeforeTouchingAnything() {
        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("至少 2 项");
        verifyNoInteractions(topics);
    }

    @Test
    void mergeRefusesMoreTopicsThanTheServiceAccepts() {
        List<String> references = new ArrayList<>();
        for (int i = 0; i <= AiTopicAssistantTools.MERGE_MAX_TOPICS; i++) {
            references.add(TopicCandidates.idOf(UUID.randomUUID()));
        }

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.copyOf(references)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("最多 " + AiTopicAssistantTools.MERGE_MAX_TOPICS + " 项");
        verifyNoInteractions(topics);
    }

    /** 一个联系人 ref 混进一组话题里，必须在动手之前就被挡下 —— 两个域的 id 空间是分开的。 */
    @Test
    void aContactReferenceIsNotATopicReferenceWhenMerging() {
        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(CONTACT_REF, TOPIC_A_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("话题标识格式不正确");
        verifyNoInteractions(topics);
    }

    /**
     * 一组里只要有一条不在范围内，整组都要停 —— 与解析器的逐元素比对是同一条规则。
     * 「合并」是不可逆动作，不该把一半有证据、一半没证据的一组推给用户去点确认。
     */
    @Test
    void anOutOfScopeTopicInTheSetStopsTheWholeMerge() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要A", 3L));
        when(topics.getTopic(USER, TOPIC_B)).thenThrow(new AiTopicException("TOPIC_NOT_FOUND", false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF, TOPIC_B_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE)
                .doesNotContain(TOPIC_B.toString());
        verify(topics, never()).mergeTopics(any(), any(), any());
    }

    /** 跨联系人的一组是「参数已不成立」，不是系统故障 —— 报成 INTERNAL 会让模型反复重试。 */
    @Test
    void topicsFromDifferentOwnersAreExplainedRatherThanBlamedOnTheSystem() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要A", 3L));
        when(topics.getTopic(USER, TOPIC_B)).thenReturn(topic(TOPIC_B, "运价与账期", "摘要B", 5L));
        when(topics.mergeTopics(eq(USER), any(), any()))
                .thenThrow(new AiTopicException("TOPIC_MERGE_INVALID", false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF, TOPIC_B_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("同一个联系人");
    }

    /**
     * 合并要过 ai-topic 自己的 LLM 网关，网关没配好时是<b>服务不可用</b>，
     * 不能报成「你的参数不对」—— 后者会让模型换个说法反复重试一个永远不会成功的事。
     */
    @Test
    void aMissingFusionGatewayIsAnInternalFailureNotAParameterProblem() {
        when(topics.getTopic(USER, TOPIC_A)).thenReturn(topic(TOPIC_A, "运价谈判", "摘要A", 3L));
        when(topics.getTopic(USER, TOPIC_B)).thenReturn(topic(TOPIC_B, "运价与账期", "摘要B", 5L));
        when(topics.mergeTopics(eq(USER), any(), any()))
                .thenThrow(new AiTopicException("TOPIC_FUSION_UNAVAILABLE", false));

        ToolResult result = registry.invoke(AiTopicAssistantTools.TOOL_TOPICS_MERGE, USER,
                Map.of("topicRefs", List.of(TOPIC_A_REF, TOPIC_B_REF)));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INTERNAL);
        assertThat(result.message()).contains("暂时不可用");
    }

    // ---------- 声明 ----------

    @Test
    void theReadToolIsReadOnlyAndTheOtherTwoAreNot() {
        ToolDefinition read = registry.find(AiTopicAssistantTools.TOOL_TOPICS_READ).orElseThrow();
        assertThat(read.tool().annotations().readOnlyHint()).isTrue();
        assertThat(read.tool().annotations().destructiveHint()).isFalse();
        assertThat(read.referenceBindings()).containsExactly(Map.entry("contactRef", ContactCandidates.NAME));

        for (String name : List.of(AiTopicAssistantTools.TOOL_TOPICS_RETRY,
                AiTopicAssistantTools.TOOL_TOPIC_UPDATE)) {
            ToolDefinition definition = registry.find(name).orElseThrow();
            assertThat(definition.tool().annotations().readOnlyHint()).as(name).isFalse();
            assertThat(definition.tool().annotations().destructiveHint()).as(name).isTrue();
        }

        assertThat(registry.find(AiTopicAssistantTools.TOOL_TOPIC_UPDATE).orElseThrow().referenceBindings())
                .containsExactly(Map.entry("topicRef", TopicCandidates.NAME));
    }

    /**
     * 合并是<b>不可逆</b>的写动作（原来那几条会消失），所以必须声明成破坏性、落确认卡片 ——
     * 权威在 {@code AssistantActionPolicy}，这里验的是声明这一半。
     *
     * <p>绑定必须落在 {@code topicRefs} 上而且是复数：解析器对数组值是<b>逐元素</b>比对的，
     * 绑错了位置（比如挂到 items 上）会让 {@code referenceBindings()} 查不到 ——
     * 那时字段名仍以 Refs 结尾、自检仍会要求绑定，所以它会起不来，但"看起来对"的声明
     * （挂在 items 上）值得有一条用例盯着。
     */
    @Test
    void theMergeToolIsDestructiveAndBindsThePluralReference() {
        ToolDefinition merge = registry.find(AiTopicAssistantTools.TOOL_TOPICS_MERGE).orElseThrow();

        assertThat(merge.tool().annotations().readOnlyHint()).isFalse();
        assertThat(merge.tool().annotations().destructiveHint()).isTrue();
        assertThat(merge.requiredArguments()).containsExactly("topicRefs");
        assertThat(merge.referenceBindings())
                .containsExactly(Map.entry("topicRefs", TopicCandidates.NAME));

        @SuppressWarnings("unchecked")
        Map<String, Object> field = (Map<String, Object>) ((Map<String, Object>)
                merge.tool().inputSchema().get("properties")).get("topicRefs");
        assertThat(field).containsEntry("type", "array")
                .containsEntry("minItems", AiTopicAssistantTools.MERGE_MIN_TOPICS)
                .containsEntry("maxItems", AiTopicAssistantTools.MERGE_MAX_TOPICS);
    }

    // ---------- 夹具 ----------

    private static AiTopicModels.TopicTimelineResponse timeline(AiTopicModels.GenerationStatus status,
                                                               List<AiTopicModels.TopicProjection> topics,
                                                               boolean weComUnsupported) {
        return new AiTopicModels.TopicTimelineResponse(CONTACT,
                new AiTopicModels.GenerationProjection(status, null, null, Instant.now()),
                topics, weComUnsupported);
    }

    private static List<AiTopicModels.TopicProjection> manyTopics(int count) {
        List<AiTopicModels.TopicProjection> topics = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            topics.add(topic(UUID.randomUUID(), "话题 " + i, "摘要 " + i, 1L));
        }
        return topics;
    }

    private static AiTopicModels.TopicProjection topic(UUID id, String title, String summary, long version) {
        return new AiTopicModels.TopicProjection(
                id, title, summary, "AI",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"),
                List.of("wechat"), 3, List.of(), version,
                CONTACT, "周明", "张江物流 对接人", null, null,
                AiTopicModels.OwnerType.CONTACT, CONTACT, "周明", false,
                null, null);
    }
}
