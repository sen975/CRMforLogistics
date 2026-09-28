package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicException;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TopicCandidates;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * AI 话题域的三个代表：读话题、重算话题、改话题标题。
 *
 * <h2>为什么这个域是全场性价比最高的一组</h2>
 * 它是本项目<b>唯一一套已经跑通的「AI 计算 + 人工复核」链路</b>：话题由后台 worker 从
 * 消息与通话中生成，落 {@code ai_topics}，用户可以在页面上改标题、确认摘要、合并、归档。
 * 也就是说这里加工具<b>不是新建能力</b>，而是把页面上的按钮翻译成模型能调的动作 ——
 * 这与「改备注」同一性质，而用户的诉求（"帮我触发 AI 标签"）正好落在这条链上。
 *
 * <h2>四分工</h2>
 * <table>
 *   <caption>四个工具的分工</caption>
 *   <tr><th></th><th>{@code contact.topics_read}</th><th>{@code contact.topics_retry}</th>
 *       <th>{@code contact.topic_update}</th><th>{@code contact.topics_merge}</th></tr>
 *   <tr><td>性质</td><td>只读（免确认、可循环）</td><td>写（触发重算，需确认）</td>
 *       <td>写（覆盖标题/摘要，需确认）</td><td>写（合并，需确认）</td></tr>
 *   <tr><td>引用</td><td>contactRef</td><td>contactRef</td><td>topicRef</td><td><b>topicRefs</b>（一组）</td></tr>
 *   <tr><td>候选</td><td><b>产出</b> topic 候选窗口</td><td>不产出</td><td>引用 topic 候选</td>
 *       <td>引用 topic 候选（逐元素比对）</td></tr>
 * </table>
 *
 * <h2>{@code topicRef} 从哪来 —— 这是本域唯一的顺序约束</h2>
 * topic 候选<b>没有预置窗口</b>（话题是按联系人取的，预置一个「最近 30 条」既贵又没意义），
 * 所以 {@code contact.topic_update} 的 topicRef 只能由 {@link #TOOL_TOPICS_READ} 的结果带出来。
 * 没读过就想改，会在提问路径被「不在当前候选清单中」挡下 —— 这正是「先查再改」该有的形状，
 * 而不是一个需要靠提示词提醒的约定。
 *
 * <h2>改标题为什么不需要模型给版本号</h2>
 * 页面侧的 {@code PATCH /topics/{id}} 要求带上 {@code expectedVersion} 做乐观锁，
 * 理由是同一个话题可能在多个标签页里被打开。助手这条路不同：<b>它执行前会自己读一次</b>
 * （见 {@link AiTopicService#getTopic}），拿到的就是当前版本，于是也不需要模型去背一个数字。
 *
 * <p>那个读还有第二个、更要紧的作用：{@code updateEmployee} 的 SQL 是无条件
 * {@code confirmed_summary=#{confirmedSummary}}，模型只说「改标题」时若把摘要传成 null，
 * 会<b>静默清掉</b>已人工确认的摘要。所以摘要的取值规则是「没给就用当前值」，
 * 而"当前值"只能来自这次读。
 *
 * <p>代价是助手侧是 last-write-wins（没有乐观锁）。这个取舍是有意的：
 * 模型背错版本号是一个<b>会经常发生</b>的失败，而两个入口在同一秒改同一条话题极其罕见；
 * 用一个高频失败去换一个低频保护不划算。真需要时把 {@code expectedVersion} 加成参数即可，
 * 读路径已经具备（{@code TopicProjection.version()}）。
 *
 * <h2>合并为什么也只能靠这次读拿版本号</h2>
 * {@code mergeTopics} 需要<b>每一条</b>的 {@code expectedVersion}（缺失即 {@code TOPIC_MERGE_INVALID}），
 * 让模型背 N 个数字比背一个更不现实，所以同样是执行前逐条读。
 * 合并后的标题与摘要由 ai-topic 自己的 LLM 网关生成（{@code generateFusionAssignment}），
 * <b>不接受调用方指定</b> —— 所以这里刻意没有 title/summary 参数：
 * 加一个会被服务层忽略的参数，比不加更糟。
 */
@Configuration(proxyBeanMethods = false)
public class AiTopicAssistantTools {

    public static final String TOOL_TOPICS_READ = "contact.topics_read";
    public static final String TOOL_TOPICS_RETRY = "contact.topics_retry";
    public static final String TOOL_TOPIC_UPDATE = "contact.topic_update";
    public static final String TOOL_TOPICS_MERGE = "contact.topics_merge";

    /** 标题与摘要的长度上限，与 {@code AiTopicService.updateTopic} 的校验逐字对齐。 */
    static final int TITLE_MAX_CHARS = 200;
    static final int SUMMARY_MAX_CHARS = 4000;

    /** 一组引用的上下限，与 {@code AiTopicService.requireFusionTopics} 的校验逐字对齐。 */
    static final int MERGE_MIN_TOPICS = 2;
    static final int MERGE_MAX_TOPICS = 20;

    /** 一个 {@code TOPIC:<uuid>} 的长度上界（6 + 1 + 36），留余量。 */
    static final int TOPIC_REF_MAX_CHARS = 64;

    private final AiTopicService topics;

    public AiTopicAssistantTools(AiTopicService topics) {
        this.topics = topics;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition contactTopicsReadTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TOPICS_READ)
                        .title("查询联系人的 AI 话题")
                        .description("列出系统从往来记录里为某个联系人归纳出的 AI 话题（每条含标题、摘要、"
                                + "涉及渠道与最后发生时间），以及话题生成任务的当前状态。"
                                + "适合「他最近在聊什么事」「他的话题生成了吗」这类问题。"
                                + "这是只读操作；结果会替换候选话题清单，之后可以直接引用其中的 topicRef "
                                + "去修改话题标题。contactRef 只能来自候选联系人清单，找不到时不要调用。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(readOnly())
                        .build(),
                this::readTopics);
    }

    @Bean
    public ToolDefinition contactTopicsRetryTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TOPICS_RETRY)
                        .title("重新生成联系人的 AI 话题")
                        .description("触发一次该联系人的 AI 话题重新生成（增量）：系统会读取他尚未纳入话题的"
                                + "往来记录，交给模型归纳后更新话题列表。已有话题可能因此被改写，"
                                + "所以它是一次会改动数据的操作，执行前需要用户确认。"
                                + "contactRef 只能来自候选联系人清单；没有可用于生成的内容时不会有任何变化。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(write())
                        .build(),
                this::retryTopics);
    }

    @Bean
    public ToolDefinition contactTopicUpdateTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("topicRef", referenceTo(TopicCandidates.NAME,
                "话题标识，只能取自 contact.topics_read 返回的候选话题清单里的 topicRef（形如 TOPIC:<uuid>）"));
        properties.put("title", stringSpec("话题的新标题", "maxLength", TITLE_MAX_CHARS));
        properties.put("summary", stringSpec(
                "话题的新摘要（人工确认版本）。<b>只在用户明确要求改摘要时才传</b>；"
                        + "不传表示保持当前摘要不变", "maxLength", SUMMARY_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TOPIC_UPDATE)
                        .title("修改 AI 话题标题")
                        .description("修改一条 AI 话题的标题，可选同时改写它的摘要。"
                                + "如果要改的那条话题不在候选话题清单里，说明还没调用 contact.topics_read —— "
                                + "先读一次，不要凭印象拼一个 topicRef。"
                                + "摘要字段留空表示不动它（不要把当前摘要抄回来，那没有意义）。"
                                + "这是会改动数据的写操作，执行前需要用户确认。")
                        .inputSchema(objectSchema(properties, List.of("topicRef", "title")))
                        .annotations(write())
                        .build(),
                this::updateTopic);
    }

    @Bean
    public ToolDefinition contactTopicsMergeTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("topicRefs", referenceArray(TopicCandidates.NAME,
                "要合并的那几条话题，只能取自 contact.topics_read 返回的候选话题清单里的 topicRef"
                        + "（形如 TOPIC:<uuid>）。至少 " + MERGE_MIN_TOPICS + " 条、最多 " + MERGE_MAX_TOPICS
                        + " 条，必须属于同一个联系人。",
                MERGE_MIN_TOPICS, MERGE_MAX_TOPICS, TOPIC_REF_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TOPICS_MERGE)
                        .title("合并 AI 话题")
                        .description("把同一个联系人下重复的几条 AI 话题合并成一条：系统会读取这几条话题的来源记录，"
                                + "重新归纳出一个标题与摘要。原来的几条会因此消失，所以这是不可逆的写操作，"
                                + "执行前需要用户确认。合并后的标题由系统生成，无法由调用方指定。"
                                + "topicRefs 只能来自候选话题清单 —— 没调用过 contact.topics_read 就先读一次，"
                                + "不要凭印象拼 topicRef。至少要两条。")
                        .inputSchema(objectSchema(properties, List.of("topicRefs")))
                        .annotations(write())
                        .build(),
                this::mergeTopics);
    }

    // ---------- 执行 ----------

    /**
     * 只读：一次取回该联系人的话题，并把结果<b>替换</b>进 topic 候选窗口。
     *
     * <p>截断取 {@link TopicCandidates#LIMIT}：{@code getTopics} 返回该联系人全部 READY 话题
     * （无上限），而候选窗口是有界的。截断必须说出来，否则模型会把「只给了 30 条」
     * 当成「一共只有 30 条」。
     */
    private ToolResult readTopics(UUID userId, Map<String, Object> arguments) {
        ContactRef target = contactRef(arguments);
        AiTopicModels.TopicTimelineResponse response;
        try {
            // getTopics 自己会做归属校验（contactService.getById），越权在这里就断了。
            response = topics.getTopics(userId, target.id());
        } catch (AiTopicException e) {
            throw translate(e);
        } catch (IllegalArgumentException e) {
            // 「联系人不在你的范围内」由 ContactService 抛 IllegalArgumentException，
            // 与话题域的异常码不同族。两条都要转译 —— 漏掉这条的话它会冒到 ToolRegistry
            // 的兜底 catch 里，变成一句「执行失败，请稍后再试」，用户会以为是系统故障。
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }

        List<AiTopicModels.TopicProjection> all = response.topics();
        List<AiTopicModels.TopicProjection> page = all.size() > TopicCandidates.LIMIT
                ? all.subList(0, TopicCandidates.LIMIT) : all;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contactRef", target.ref());
        data.put("generationStatus", response.generation().status().name());
        if (response.generation().errorCode() != null) {
            data.put("generationErrorCode", response.generation().errorCode());
        }
        data.put("weComUnsupported", response.weComUnsupported());
        data.put("topicCount", page.size());
        data.put("topics", page.stream().map(AiTopicAssistantTools::topicData).toList());

        TopicCandidates candidates = new TopicCandidates(TopicCandidates.LIMIT,
                page.stream().map(t -> new TopicCandidates.Item(
                        TopicCandidates.idOf(t.id()), t.title(), t.version())).toList());

        if (page.isEmpty()) {
            // 空结果<b>不是</b>错误，而且仍要交回候选（一个空的 topic 集合）——
            // 这样"没读过"与"读过但确实没有话题"在下一轮是两种不同的状态。
            return ToolResult.discovered(describeEmpty(response), data, candidates);
        }
        return ToolResult.discovered(describe(response, page, all), data, candidates);
    }

    private ToolResult retryTopics(UUID userId, Map<String, Object> arguments) {
        ContactRef target = contactRef(arguments);
        return guarded(() -> {
            AiTopicModels.GenerationProjection projection = topics.retryGeneration(userId, target.id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contactRef", target.ref());
            data.put("status", projection.status().name());
            if (projection.errorCode() != null) {
                data.put("errorCode", projection.errorCode());
            }
            String message = switch (projection.status()) {
                case GENERATING -> "已提交重新生成，稍后可以再问我一次话题";
                case NOT_STARTED -> "WECOM_AI_UNSUPPORTED".equals(projection.errorCode())
                        ? "这个联系人只有企业微信渠道，暂时无法生成 AI 话题"
                        : "他还没有可用于生成话题的往来内容，这次不会产生变化";
                case READY -> "这个话题已经是可用的状态，没有需要重算的部分";
                case FAILED -> "上次生成失败了，可以稍后再试一次";
            };
            return ToolResult.ok(message, data);
        });
    }

    /**
     * 改话题。
     *
     * <p>先读再写：读到的当前摘要与版本，正是"模型没给摘要就不要动它"这条规则的依据
     * （见类注释里 {@code updateEmployee} 无条件赋值那一段）。
     */
    private ToolResult updateTopic(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "topicRef");
        UUID topicId = TopicCandidates.targetOf(reference);
        if (topicId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "话题标识格式不正确，只能取自候选话题清单里的 topicRef");
        }
        String title = requiredText(arguments, "title");
        String summary = optionalText(arguments, "summary");

        return guarded(() -> {
            AiTopicModels.TopicProjection current;
            try {
                current = topics.getTopic(userId, topicId);
            } catch (AiTopicException e) {
                throw translate(e);
            }
            // summary 缺席 = "别动它"。传 null 会清空已人工确认的摘要（updateEmployee 是无条件赋值）。
            String effectiveSummary = summary == null || summary.isBlank() ? current.summary() : summary;

            AiTopicModels.TopicProjection updated;
            try {
                updated = topics.updateTopic(userId, topicId, title, effectiveSummary, current.version());
            } catch (AiTopicException e) {
                throw translate(e);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("topicRef", reference);
            data.put("title", updated.title());
            data.put("previousTitle", current.title());
            return ToolResult.ok("已把话题标题「" + current.title() + "」改为「" + updated.title() + "」", data);
        });
    }

    /**
     * 合并话题。
     *
     * <p>先逐条读一次取当前 version —— {@code mergeTopics} 要求<b>每一条</b>都在
     * {@code expectedVersions} 里，缺一条就是 {@code TOPIC_MERGE_INVALID}。
     * 让模型背 N 个数字不现实，所以这个读是必须的（与 {@link #updateTopic} 同一条理由）。
     *
     * <p><b>重复引用直接拒，不做去重</b>：确认卡片是按参数渲染的，若这里悄悄把重复项折叠掉，
     * 用户看到的「选中的三条」与真正被合并的两条就不是一回事了 —— 卡片与动作必须同一份输入。
     */
    private ToolResult mergeTopics(UUID userId, Map<String, Object> arguments) {
        List<String> references = requiredReferences(arguments, "topicRefs");

        return guarded(() -> {
            Map<UUID, Long> expectedVersions = new LinkedHashMap<>();
            List<String> titles = new ArrayList<>();
            for (String reference : references) {
                UUID topicId = TopicCandidates.targetOf(reference);
                if (topicId == null) {
                    // 格式错在写之前就说清楚：留到 mergeTopics 里会变成一句笼统的「不能合并」。
                    throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                            "话题标识格式不正确，只能取自候选话题清单里的 topicRef");
                }
                if (expectedVersions.containsKey(topicId)) {
                    throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                            "同一条话题不能重复出现，请每条只选一次");
                }
                AiTopicModels.TopicProjection current = topics.getTopic(userId, topicId);
                expectedVersions.put(topicId, current.version());
                titles.add(current.title());
            }

            List<AiTopicModels.TopicProjection> merged =
                    topics.mergeTopics(userId, List.copyOf(expectedVersions.keySet()), expectedVersions);
            AiTopicModels.TopicProjection fused = merged.get(0);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("topicRef", TopicCandidates.idOf(fused.id()));
            data.put("title", fused.title());
            data.put("mergedFrom", List.copyOf(titles));
            return ToolResult.ok("已把「" + String.join("」「", titles) + "」合并为「" + fused.title() + "」", data);
        });
    }

    // ---------- 参数读取 ----------

    private static ContactRef contactRef(Map<String, Object> arguments) {
        String reference = requiredText(arguments, "contactRef");
        UUID contactId = ContactCandidates.targetOf(reference);
        if (contactId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "联系人标识格式不正确，只能取自候选联系人清单里的 contactRef");
        }
        return new ContactRef(reference, contactId);
    }

    /** 一次调用的联系人目标：原始 ref（回话与 data 用）与解析后的 id（服务调用用）。 */
    private record ContactRef(String ref, UUID id) {
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    private static String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text.strip() : null;
    }

    /**
     * 一组必填引用（如 {@code topicRefs}）。
     *
     * <p>这里只负责「拿到了一个字符串列表」：条数由 schema 的 {@code minItems}/{@code maxItems}
     * 在更前面管（所以不重复报「至少要两条」），引用格式与重复由调用点逐个转 id 时判 ——
     * 那两个判断需要 {@code targetOf} 和已收集的集合，放在这里会重复一遍。
     */
    private static List<String> requiredReferences(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof List<?> elements)) {
            throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
        }
        List<String> references = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (!(element instanceof String text)) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "参数 " + key + " 的每一项都应为字符串引用");
            }
            references.add(text.strip());
        }
        return references;
    }

    // ---------- 结果与异常 ----------

    /**
     * 把话题域的异常码翻译成工具错误码。
     *
     * <p>归属类（{@code TOPIC_NOT_FOUND} / {@code TOPIC_FORBIDDEN}）合并成一句 ——
     * 与别处同口径，不区分「不存在」与「不属于你」：区分开就等于告诉另一个账号
     * 「这条 id 是存在的」。版本冲突单独说，因为它的处置不同（重新读一次再来）。
     */
    private static ToolExecutionException translate(AiTopicException e) {
        String message = switch (e.code()) {
            case "TOPIC_NOT_FOUND", "TOPIC_FORBIDDEN" -> ToolExecutionException.ACCESS_DENIED_MESSAGE;
            case "TOPIC_VERSION_CONFLICT" -> "这条话题在你确认之前被改过了，请先重新读一次再改";
            case "TOPIC_INPUT_INVALID" -> "话题标题或摘要不合法（标题不能为空且不超过 200 字）";
            case "TOPIC_REVIEW_CONFLICT" -> "这条话题正在复核中，暂时不能修改";
            case "TOPIC_MERGE_INVALID" -> "这几条话题现在不能合并：要么不属于同一个联系人，要么其中一条还没有生成完成";
            case "TOPIC_FUSION_INPUT_TOO_LARGE" -> "这几条话题的来源内容太多，装不下一次合并 —— 试着少选几条";
            case "TOPIC_FUSION_UNAVAILABLE" -> "话题合并服务暂时不可用";
            case "TOPIC_FUSION_RESPONSE_INVALID" -> "合并结果生成得不完整；这次没有改动任何数据，请稍后再试";
            default -> "执行失败，请稍后再试";
        };
        // 「归属类」与「参数或状态已不成立」对用户都是可讲的，用 INVALID_ARGUMENT 而不是 INTERNAL ——
        // 后者会被上层当成"系统故障"，把一次用户自己能纠正的失败说成"系统坏了"。
        String code = switch (e.code()) {
            case "TOPIC_NOT_FOUND", "TOPIC_FORBIDDEN" -> ToolExecutionException.FORBIDDEN_OR_NOT_FOUND;
            case "TOPIC_VERSION_CONFLICT", "TOPIC_INPUT_INVALID",
                 "TOPIC_MERGE_INVALID", "TOPIC_FUSION_INPUT_TOO_LARGE" -> ToolExecutionException.INVALID_ARGUMENT;
            default -> ToolExecutionException.INTERNAL;
        };
        return new ToolExecutionException(code, message, e);
    }

    private static ToolResult guarded(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (AiTopicException e) {
            // 话题域的异常码自带语义（版本冲突、输入不合法、无权限），一律翻译后再往上抛；
            // 不翻译的话它会落进 ToolRegistry 的兜底 catch，变成一句没有信息量的「执行失败」。
            throw translate(e);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }
    }

    // ---------- 渲染 ----------

    /**
     * 一条话题的结构化投影。
     *
     * <p>{@code summary} 是 AI 摘要或人工确认过的摘要 —— 按 2026-09-22 的口径 b，
     * <b>摘要可以进模型，原文不行</b>，所以这里带上是刻意的。
     * 刻意<b>不带</b> {@code sourceItems}（每条来源的 messageId / callRecordId）：
     * 模型用不上，而它们会把一份内部 id 清单送进提示词。
     *
     * <p>时间戳一律转字符串：提示词渲染用的 {@code ObjectMapper} 不一定注册了 JavaTimeModule，
     * 放 {@code Instant} 会让整条 observation 序列化失败（同 {@code ConversationCandidates.Item}）。
     */
    private static Map<String, Object> topicData(AiTopicModels.TopicProjection topic) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topicRef", TopicCandidates.idOf(topic.id()));
        data.put("title", topic.title());
        data.put("summary", topic.summary());
        data.put("summarySource", topic.summarySource());
        data.put("channels", topic.channels());
        data.put("sourceCount", topic.sourceCount());
        data.put("lastOccurredAt", topic.lastOccurredAt() == null ? null : topic.lastOccurredAt().toString());
        data.put("version", topic.version());
        return data;
    }

    /**
     * 给模型看的一句话：<b>只点名 + 说条数</b>，明细全在 {@code data}。
     *
     * <p>与 {@code ContactAssistantTools.describe} 同一条理由：两段会进<b>同一条</b> observation，
     * 而那条 observation 有字符上限。把摘要在这里再抄一遍，等于让明细占两份预算，
     * 先撞到上限的反而会把明细截掉。标题短、且是"有哪些话题"的唯一线索，所以放进来。
     */
    private static String describe(AiTopicModels.TopicTimelineResponse response,
                                   List<AiTopicModels.TopicProjection> page,
                                   List<AiTopicModels.TopicProjection> all) {
        List<String> titles = new ArrayList<>();
        for (AiTopicModels.TopicProjection topic : page) {
            titles.add(topic.title());
        }
        StringBuilder text = new StringBuilder("共 ").append(page.size()).append(" 条话题：")
                .append(String.join("、", titles));
        if (all.size() > page.size()) {
            text.append("。只显示了前 ").append(page.size()).append(" 条（共 ").append(all.size()).append(" 条）");
        }
        return text.append(appendStatus(response)).toString();
    }

    private static String describeEmpty(AiTopicModels.TopicTimelineResponse response) {
        return switch (response.generation().status()) {
            case GENERATING -> "AI 话题正在生成中，稍后再问我一次";
            case FAILED -> "还没有话题，而且上次生成失败了";
            case NOT_STARTED -> response.weComUnsupported()
                    ? "这个联系人只有企业微信渠道，暂时无法生成 AI 话题"
                    : "还没有话题 —— 系统还没有收集到足够的往来内容";
            case READY -> "还没有话题";
        };
    }

    /** 生成状态的一句话补充。只在与"有话题"不矛盾时出现，避免回答自相矛盾。 */
    private static String appendStatus(AiTopicModels.TopicTimelineResponse response) {
        return switch (response.generation().status()) {
            case GENERATING -> "。另外，新一批话题正在生成中";
            case FAILED -> "。另外，上一次话题生成失败了";
            default -> "";
        };
    }

    // ---------- 声明构造 ----------

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // 安全配置，不是风格选项：身份防线就靠这一条（见 ToolRegistry 的启动自检）。
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringSpec(String description, Object... extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", description);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            field.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /**
     * 「一组引用」字段：外层是 array，元素是绑定到某个候选组的字符串引用。
     *
     * <p>{@code x-candidateSet} 必须挂在<b>数组字段</b>上，不能挂在 {@code items} 上：
     * {@link ToolDefinition#referenceBindings()} 读的正是字段级的那一个，解析器据此
     * 对<b>每个元素</b>比对候选（{@code AssistantDecisionParser.referencesOf}）。
     * 挂错位置会让绑定查不到，而字段名以 {@code Refs} 结尾仍会被启动自检要求绑定 —— 直接起不来。
     * 这个失败是响的，但错误信息只说「没有声明 x-candidateSet」，指不出「声明在了 items 上」。
     */
    private static Map<String, Object> referenceArray(String candidateSet, String description,
                                                      int minItems, int maxItems, int itemMaxLength) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("maxLength", itemMaxLength);

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "array");
        field.put("description", description);
        field.put("minItems", minItems);
        field.put("maxItems", maxItems);
        field.put("items", items);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /** 只读注解。{@code readOnlyHint=true} 是「进只读清单」的<b>声明</b>；权威在 {@code AssistantActionPolicy}。 */
    private static McpSchema.ToolAnnotations readOnly() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();
    }

    /**
     * 写动作注解。
     *
     * <p>{@code topics_retry} 与 {@code topic_update} 都声明 {@code destructiveHint=true}：
     * 前者会让已有话题被改写，后者覆盖标题与摘要（摘要即使"没动"，写入的也是覆盖语义）。
     * 按规范未声明即按破坏性解释，这里显式写出来，让"它是有损的"在声明里可读。
     */
    private static McpSchema.ToolAnnotations write() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(false)
                .build();
    }
}
