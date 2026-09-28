package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ContactBrief;
import com.crmforlogistics.messagecenter.service.assistant.ContactBriefProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 联系人域的两个能力，也是<b>第三个域的样板</b>：
 *
 * <table>
 *   <caption>两个工具的分工</caption>
 *   <tr><th></th><th>{@code contact.search}</th><th>{@code contact.brief}</th></tr>
 *   <tr><td>性质</td><td><b>只读</b>（免确认、可循环）</td><td><b>只读</b>（免确认、可循环）</td></tr>
 *   <tr><td>候选</td><td>检索结果<b>替换</b>「联系人」候选窗口</td><td>引用候选 id，不新增候选</td></tr>
 *   <tr><td>回答</td><td>「系统里有没有这个人」</td><td>「这个人的画像、事实、标签、话题」</td></tr>
 * </table>
 *
 * <h2>为什么是两个工具而不是计划里的一个</h2>
 * 计划 §6 B2 只列了 {@code contact.brief}（那时设想「一组候选 + 一个工具」）。落地时补上了
 * {@code contact.search}，理由是一条具体的坏路径：候选窗口是「最近有往来的 20 个联系人」，
 * 而用户的三类真实诉求（会前准备 / 判断成交意愿 / 发邮件）都以<b>点名一个人</b>开头 ——
 * 「帮我准备一下明天和张总的会」里的张总很可能不在最近 20 个里面。
 *
 * <p>没有检索工具时，这个域会以「我找不到张总」的形式失败，而系统里明明有他 ——
 * 那正是本项目一直在避免的「看起来正常、其实是坏的」。会话域当初选的就是
 * 「search + act」两个工具，这里保持一致：<b>只读检索负责突破候选窗口，动作只负责引用窗口内的对象</b>。
 *
 * <h2>为什么不用 {@code conversation.search} 代替它</h2>
 * 两者都能按名字找到「张总」，但返回的候选集不同：会话检索返回 {@code conversation} 组
 * （含企微群），而 {@code contact.brief} 声明的是 {@code contact} 组。拿会话的 ref 去 brief
 * 会被解析层以「不在当前候选联系人中」拒绝 —— 这不是多余的严格，而是「一个群 id 不是一个人」。
 * 分工写在两边的 description 里，让模型知道「要问某个人的情况」该调哪一个。
 *
 * <h2>合规口径落在数据层，不落在这两个类里</h2>
 * 两个工具都只回结构化字段与摘要：候选是姓名/备注，简报是画像/事实/标签/话题。
 * 消息原文与通话转写<b>不在这条链路上</b>（见 {@link ContactBrief} 的类注释），
 * 所以这里没有「记得过滤掉正文」的代码 —— 那种代码一旦有人重构就会静默失效。
 */
@Configuration(proxyBeanMethods = false)
public class ContactAssistantTools {

    public static final String TOOL_SEARCH = "contact.search";
    public static final String TOOL_BRIEF = "contact.brief";

    private final ContactCandidateProvider candidates;
    private final ContactBriefProvider briefs;

    public ContactAssistantTools(ContactCandidateProvider candidates, ContactBriefProvider briefs) {
        this.candidates = candidates;
        this.briefs = briefs;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition contactSearchTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", stringSpec("检索词，一般是联系人的姓名或备注片段；留空表示查看最近有往来的联系人",
                "maxLength", 60));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SEARCH)
                        .title("检索联系人")
                        .description("在当前登录用户可查看的联系人（含手工录入但还没有往来的）里按姓名或备注检索，"
                                + "返回有界的联系人列表。这是只读操作，不会改动任何数据；检索结果会替换候选联系人清单，"
                                + "之后可以直接引用其中的 contactRef 去查询这个人的情况。"
                                + "要问「某个人的画像/近况」时先用它找到人，再用 contact.brief。")
                        .inputSchema(objectSchema(properties, List.of()))
                        .annotations(readOnly())
                        .build(),
                this::search);
    }

    @Bean
    public ToolDefinition contactBriefTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_BRIEF)
                        .title("查询联系人情况")
                        .description("查询一个联系人的画像摘要、已确认的结构化事实、AI 标签、人工标签与近期话题。"
                                + "适合「会前准备」「这人最近在关心什么」「判断成交意愿」这类问题。"
                                + "这是只读操作；contactRef 只能来自候选联系人清单，清单里找不到对应联系人时不要调用，"
                                + "改为向用户说明没找到。返回里 memoryVisible=false 表示画像与标签由他人录入、"
                                + "当前用户看不到，此时如实说明而不是当作「他没有画像」。"
                                + "memoryState 与 memoryFailureCode 是系统内部状态，用来解释「他的标签为什么"
                                + "没更新」；回话时把它们说成人话，不要原样念出这些代码。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(readOnly())
                        .build(),
                this::brief);
    }

    // ---------- 执行 ----------

    /**
     * 只读检索。返回的是<b>有界投影</b>：姓名与备注，不含角色（见 {@link ContactCandidates} 的说明），
     * 不含联系方式，也不含任何消息正文。
     */
    private ToolResult search(UUID userId, Map<String, Object> arguments) {
        ContactCandidates found = candidates.search(userId, optionalText(arguments, "query"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", found.items().size());
        data.put("items", found.items().stream().map(ContactAssistantTools::itemData).toList());

        if (found.items().isEmpty()) {
            // 查不到不是错误：空结果是一个正常结论，模型应当据此回话而不是重试。
            // 也不替换候选窗口 —— 一次没命中的检索不该把用户本来能引用的窗口抹掉。
            return ToolResult.ok("没有找到匹配的联系人", data);
        }

        List<String> names = new ArrayList<>();
        for (ContactCandidates.Item item : found.items()) {
            names.add(item.name() + (item.remark() == null ? "" : "（" + item.remark() + "）"));
        }
        StringBuilder message = new StringBuilder("找到 ").append(found.items().size()).append(" 个联系人：")
                .append(String.join("、", names));
        if (found.items().size() >= found.limit()) {
            // 截断必须说出来。不说的话模型会把「只给了前 20 条」当成「一共只有 20 条」。
            message.append("。只显示了前 ").append(found.limit()).append(" 条，如需更精确的结果请缩小检索词");
        }
        // 结果<b>替换</b>候选窗口：这样下一轮的 contact.brief 才引用得到窗口外的联系人。
        return ToolResult.discovered(message.toString(), data, found);
    }

    private ToolResult brief(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "contactRef");
        UUID contactId = ContactCandidates.targetOf(reference);
        if (contactId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "联系人标识格式不正确，只能取自候选联系人清单里的 contactRef");
        }
        return guarded(() -> {
            ContactBrief brief = briefs.brief(userId, contactId);
            return ToolResult.ok(describe(brief), brief.toData());
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

    /**
     * 把 service 层异常收敛成带错误码的 {@link ToolExecutionException}。
     *
     * <p>措辞刻意不区分「不存在」与「不属于你」（同 {@code ConversationAssistantTools}）：
     * 对用户来说指引一样（重新挑一个人），而区分开就等于告诉另一个账号「这条 id 是存在的」。
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

    private static Map<String, Object> itemData(ContactCandidates.Item item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contactRef", item.id());
        data.put("name", item.name());
        data.put("remark", item.remark());
        data.put("channels", item.channels().stream().map(channel -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("channelType", channel.channelType());
            value.put("identityValue", channel.identityValue());
            value.put("displayName", channel.displayName());
            value.put("accountLabel", channel.accountLabel());
            return value;
        }).toList());
        return data;
    }

    /**
     * 给模型看的一句话。
     *
     * <p>刻意只做「点名 + 说有哪些节」，明细全交给 {@code data}：这两段会被序列化进
     * <b>同一条</b> observation，而那条 observation 有字符上限。把画像内容在这里再抄一遍，
     * 等于让明细占两份预算，先撞到上限的反而会把明细截掉。
     */
    private static String describe(ContactBrief brief) {
        StringBuilder text = new StringBuilder(brief.name());
        if (brief.roleTitle() != null) {
            text.append('（').append(brief.roleTitle()).append('）');
        }
        if (!brief.memoryVisible()) {
            // 这是正常结果而不是失败：用户看得到这个人，只是画像不在他的名下。
            return text.append("：画像与标签由他人录入，对你不可见；基础信息已列出").toString();
        }
        if (!brief.hasMemory()) {
            // 状态在这条路上同样要说：一个 FAILED 的联系人正是「一条都没有」的常见原因，
            // 只回「暂无画像」会让用户以为系统里本来就没人可提炼。
            return text.append("：暂无画像、事实、标签与话题").append(appendMemoryState(brief)).toString();
        }
        text.append("：");
        List<String> sections = new ArrayList<>();
        if (brief.profile() != null) {
            sections.add("画像摘要");
        }
        if (!brief.facts().isEmpty()) {
            sections.add(brief.facts().size() + " 条事实");
        }
        if (!brief.aiLabels().isEmpty()) {
            sections.add(brief.aiLabels().size() + " 个 AI 标签");
        }
        if (!brief.humanTags().isEmpty()) {
            sections.add(brief.humanTags().size() + " 个人工标签");
        }
        if (!brief.topics().isEmpty()) {
            sections.add(brief.topics().size() + " 条近期话题");
        }
        return text.append(String.join("、", sections)).append(appendMemoryState(brief)).toString();
    }

    /**
     * 记忆处理状态的一句话补充。
     *
     * <p>为什么它必须进这一句，而不是只躺在 {@code data} 里：模型是拿这一句当骨架回话的，
     * 而上面那句「3 条事实、2 个 AI 标签」读起来是<b>一切正常</b>——「有一批新内容还在排队」
     * 与「上一次重算失败了」恰恰是「看着正常、其实没更新」的原因；漏掉它，模型就只能编一个理由。
     *
     * <p>{@code CLEAN} 不加：它是最常见的状态，加进去只会变成噪声
     * （同 {@code AiTopicAssistantTools.appendStatus} 只补「生成中 / 失败」两种）。
     *
     * <p>失败<b>码</b>刻意不出现在这句里 —— 那是内部标识（{@code INVALID_OUTPUT} 之类），
     * 只进 {@code data} 供模型判断原因。面向用户的回话不许吐内部标识。
     */
    private static String appendMemoryState(ContactBrief brief) {
        String state = brief.memoryState();
        if (state == null) {
            return "";
        }
        return switch (state) {
            case "DIRTY", "RETRY_WAIT" -> "。另外，他还有一批往来内容排在重算队列里";
            case "PROCESSING" -> "。另外，他的画像与标签正在重新生成中";
            case "FAILED" -> "。另外，上一次重算失败了，可以让他重算一次";
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

    /** 引用类字段：绑定写在字段自己的 schema 上，与描述同一行（见 {@code ToolInputValidator.CANDIDATE_SET}）。 */
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
}
