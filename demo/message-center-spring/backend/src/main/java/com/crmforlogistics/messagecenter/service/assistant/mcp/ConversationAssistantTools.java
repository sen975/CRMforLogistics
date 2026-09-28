package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidates;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话域的两个能力，也是<b>第二个域的样板</b>：
 *
 * <table>
 *   <caption>两个工具的分工</caption>
 *   <tr><th></th><th>{@code conversation.search}</th><th>{@code conversation.pin}</th></tr>
 *   <tr><td>性质</td><td><b>只读</b>（{@code readOnlyHint=true}，进只读清单，免确认、可循环）</td>
 *       <td>写（改一条偏好，走确认卡片）</td></tr>
 *   <tr><td>候选</td><td>检索结果<b>替换</b>候选窗口，供下一轮引用</td><td>引用候选 id，不新增候选</td></tr>
 *   <tr><td>可逆</td><td>—</td><td>可逆（置顶 / 取消置顶）</td></tr>
 * </table>
 *
 * <h2>为什么第一个只读工具选会话而不是联系人</h2>
 * 会话候选的字段全是<b>结构化元数据</b>（类型、名称、渠道、未读数、是否置顶、最后消息时间），
 * 一条客户消息正文都不含。于是「只读工具如何突破候选集边界」「只读轮如何回灌 observation」
 * 这两件事可以先在<b>不触碰合规边界</b>的前提下打通；联系人域（含画像、事实、标签）等这套机制
 * 稳定之后再接，那时要做的只是再加一组候选与一个工具。
 *
 * <h2>{@code conversationRef} 为什么叫这个名字</h2>
 * 它的值是<b>候选 id</b>（{@code CONTACT:<uuid>} / {@code WECOM_GROUP:<uuid>}），
 * 不是 {@code conversations.id}。叫 {@code conversationId} 会让下一个写代码的人
 * 传一个真正的 conversation UUID 进去，而两者看起来一模一样。
 * 另外「以 {@code Ref} 结尾的引用字段必须声明 {@code x-candidateSet}」是
 * {@link ToolRegistry} 启动自检的一部分 —— 名字同时也是被检查的凭据。
 */
@Configuration(proxyBeanMethods = false)
public class ConversationAssistantTools {

    public static final String TOOL_SEARCH = "conversation.search";
    public static final String TOOL_PIN = "conversation.pin";

    private final ConversationCandidateProvider candidates;
    private final ConversationPreferenceService preferences;

    public ConversationAssistantTools(ConversationCandidateProvider candidates,
                                      ConversationPreferenceService preferences) {
        this.candidates = candidates;
        this.preferences = preferences;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition conversationSearchTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", stringSpec("检索词，一般是联系人或群的名称片段；留空表示查看最近会话",
                "maxLength", 60));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SEARCH)
                        .title("检索会话")
                        .description("在当前登录用户有权限的会话（联系人与企业微信）里按名称检索，返回有界的会话列表。"
                                + "这是只读操作，不会改动任何数据；检索结果会替换候选会话清单，"
                                + "之后可以直接引用其中的 conversationRef。")
                        .inputSchema(objectSchema(properties, List.of()))
                        .annotations(readOnly())
                        .build(),
                this::search);
    }

    @Bean
    public ToolDefinition conversationPinTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("conversationRef", referenceTo(ConversationCandidates.NAME,
                "会话标识，只能取自候选会话清单里的 conversationRef（形如 CONTACT:<uuid> 或 WECOM_GROUP:<uuid>）"));
        properties.put("pinned", booleanSpec("true 表示置顶，false 表示取消置顶"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_PIN)
                        .title("置顶或取消置顶会话")
                        .description("把一条会话设为置顶或取消置顶。conversationRef 只能来自候选会话清单；"
                                + "清单里找不到对应会话时不要调用，改为向用户说明没找到。")
                        .inputSchema(objectSchema(properties, List.of("conversationRef", "pinned")))
                        .annotations(write("置顶偏好可由用户再次操作改回"))
                        .build(),
                this::pin);
    }

    // ---------- 执行 ----------

    /**
     * 只读检索。返回的是<b>有界投影</b>（见 {@link ConversationCandidateProvider#toItem}）：
     * 不含消息正文，所以回灌进下一轮不会把客户隐私再送一次给模型供应商。
     */
    private ToolResult search(UUID userId, Map<String, Object> arguments) {
        ConversationCandidates found = candidates.search(userId, optionalText(arguments, "query"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", found.items().size());
        data.put("items", found.items().stream().map(ConversationAssistantTools::itemData).toList());

        if (found.items().isEmpty()) {
            // 查不到<b>不是</b>错误：只读工具的空结果是一个正常结论，模型应当据此回话而不是重试。
            // 也不替换候选窗口 —— 一次没命中的检索不该把用户本来能引用的窗口抹掉。
            return ToolResult.ok("没有找到匹配的会话", data);
        }

        StringBuilder message = new StringBuilder("找到 ").append(found.items().size()).append(" 个会话：");
        List<String> names = new ArrayList<>();
        for (ConversationCandidates.Item item : found.items()) {
            names.add(item.name() + "（" + ConversationCandidates.typeLabel(item.type()) + "）");
        }
        message.append(String.join("、", names));
        if (found.items().size() >= found.limit()) {
            // 截断必须说出来。不说的话模型会把「只给了前 20 条」当成「一共只有 20 条」。
            message.append("。只显示了前 ").append(found.limit()).append(" 条，如需更精确的结果请缩小检索词");
        }
        // 结果<b>替换</b>候选窗口：这是「只读检索突破候选集边界」的落点 ——
        // 命中窗口外的会话之后，下一轮的写动作才有东西可引用。
        return ToolResult.discovered(message.toString(), data, found);
    }

    private ToolResult pin(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "conversationRef");
        boolean pinned = requiredBoolean(arguments, "pinned");
        String type = ConversationCandidates.typeOf(reference);
        UUID targetId = ConversationCandidates.targetOf(reference);
        if (type == null || targetId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "会话标识格式不正确，只能取自候选会话清单里的 conversationRef");
        }
        return guarded(() -> {
            // setPinned 顺带把目标的权威显示名带回来（同一次授权查询里读到的），
            // 因此这条回话可以说人话。原先拼的是 conversationRef，用户在确认卡片上读到的是
            // 「悦为小森」、紧接着的回话却是「CONTACT:d526bde8-…」，两句话对不上。
            var result = preferences.setPinned(userId, type, targetId, pinned);
            String name = result.displayName();
            String label = name == null || name.isBlank() ? reference : name;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationRef", reference);
            data.put("pinned", pinned);
            data.put("name", label);
            return ToolResult.ok((pinned ? "已置顶会话：" : "已取消置顶：") + label, data);
        });
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    private static String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text : null;
    }

    private static boolean requiredBoolean(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    /**
     * 把 service 层异常收敛成带错误码的 {@link ToolExecutionException}。
     *
     * <p>这里没有「目标不存在」这种码：{@code authorize} 抛的 {@code IllegalArgumentException}
     * 统一落成参数不合法，对用户来说指引一致（重新挑一条会话）—— 而且措辞刻意不区分
     * 「不存在」与「不属于你」，避免把「这条 id 存在」这件事漏给另一个账号。
     */
    private static ToolResult guarded(java.util.function.Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }
    }

    // ---------- 渲染 ----------

    private static Map<String, Object> itemData(ConversationCandidates.Item item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationRef", item.id());
        data.put("type", item.type());
        data.put("name", item.name());
        data.put("channels", item.channels());
        data.put("lastMessageAt", item.lastMessageAt());
        data.put("unread", item.unread());
        data.put("pinned", item.pinned());
        return data;
    }

    // ---------- 声明构造 ----------
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
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

    private static Map<String, Object> booleanSpec(String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "boolean");
        field.put("description", description);
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
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

    private static McpSchema.ToolAnnotations write(String title) {
        return McpSchema.ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(false)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();
    }
}
