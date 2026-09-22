package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolExecutionException;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析并校验模型输出。<b>永不信任模型输出</b>（设计文档 §7.5）。
 *
 * <h2>以 {@code decision} 为唯一分支依据</h2>
 * 阶段 0.3 实测发现：{@code decision=ask} 时 {@code tool} / {@code arguments} **可能非空** ——
 * 模型会用 {@code tool} 表达「我打算调哪个」。实测原文是
 * {@code {"decision":"ask","question":"…","missing":["date"],"tool":"todo.update","arguments":{}}}。
 * 因此<b>绝不能</b>「见到 {@code tool} 就当成 {@code call}」：那会把一次澄清变成一次写操作，
 * 而写操作里包含删除。<b>只有 {@code decision == "call"} 才会走到执行。</b>
 *
 * <h2>重试的边界：只有「还没形成意图」才重试</h2>
 * 实施文档 §0.3 提到「输出不是 JSON、或结构不符 → 回灌校验错误重试 1 次」，但 §7.5 的
 * 第 1~4 条（工具名不在注册表、schema 不符、{@code todoId} 不在候选、日期不可解析）全都是
 * <b>整轮作废</b>。两者的区别不是偶然的，因此这里把它固化成一个明确规则：
 *
 * <ul>
 *   <li><b>可重试</b>（{@code retryable=true}）：输出不是一个 JSON 对象；{@code decision}
 *       缺失或不在 {@code ask/call/reply} 里；{@code ask} 既没给问题也没给回话；
 *       {@code reply} 的回话是空的。<br>
 *       共同点：**模型还没有形成可审查的意图**，重试只是让它把信封写对，
 *       没有任何「已表达但被拒的诉求」可以被重新映射。</li>
 *   <li><b>不可重试</b>（{@code retryable=false}）：一旦进入 {@code call} 分支，
 *       从工具名到参数的一切失败都当场作废。<br>
 *       理由是一条具体的攻击路径：被拒的诉求如果获得第二次机会，模型完全可能把它
 *       换成某个**被允许**的工具 —— 「帮我给张总发微信」重试成 {@code todo.create}
 *       是模型很自然的「帮忙」倾向。重试在这里不是纠错，而是给越界开一扇后门。</li>
 * </ul>
 *
 * <h2>两条领域约定：一条跟着工具声明走，一条是通用格式约束</h2>
 * {@code date} / {@code time} 必须能被 {@code LocalDate.parse} / {@code LocalTime.parse} 解析，
 * 这是所有工具共有的格式约定，写在这里。
 *
 * <p>另一条是「引用类参数必须命中本轮候选」，它<b>不再</b>由这里的参数名清单决定 ——
 * 原来写死 {@code REFERENCE_ARGUMENTS = {"todoId"}}，对<b>所有</b>工具生效。在只有一个域时没问题，
 * 加第二个域就有两个坏处：新工具换个参数名会<b>静默地</b>不被比对；而且「这个参数必须命中候选」
 * 离声明它的地方太远。现在改用工具自己在入参 schema 上声明的绑定
 * （字段级 {@code x-candidateSet}，见 {@link ToolDefinition#referenceBindings()}），
 * 「忘记声明」由 {@code ToolRegistry} 的启动自检拦下 —— 那时的症状是起不来，不是防线消失。
 *
 * <p>这条规则<b>只管提问路径</b>；确认路径不比对候选清单，理由见 {@link #validateConfirmedCall}。
 */
@Component
public class AssistantDecisionParser {

    private static final Set<String> DECISIONS = Set.of("ask", "call", "reply");

    private final ToolRegistry registry;
    private final ToolInputValidator validator;
    private final ObjectMapper objectMapper;

    public AssistantDecisionParser(ToolRegistry registry, ToolInputValidator validator, ObjectMapper objectMapper) {
        this.registry = registry;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public Outcome parse(String rawOutput, AssistantContext context) {
        String json = extractJsonObject(rawOutput);
        if (json == null) {
            return new Rejected("输出不是一个 JSON 对象", true);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return new Rejected("输出不是合法 JSON", true);
        }
        if (root == null || !root.isObject()) {
            return new Rejected("输出不是一个 JSON 对象", true);
        }

        String decision = text(root, "decision");
        if (decision == null || !DECISIONS.contains(decision)) {
            return new Rejected("decision 必须是 ask / call / reply 之一", true);
        }

        return switch (decision) {
            case "ask" -> parseAsk(root);
            case "reply" -> parseReply(root);
            default -> readCall(root, context);
        };
    }

    // ---------- 三个分支 ----------

    /**
     * {@code ask} 分支。<b>刻意忽略 {@code tool} 与 {@code arguments}</b> ——
     * 实测里模型会在 {@code ask} 时填它们（表示「打算调哪个」），把它当调用就是设计文档
     * 明确禁止的那个错误。{@code missing} 按原样保留，允许为空数组：
     * 实测 B 格（两条都含「张总」）就是「不缺参数，而是指代有歧义」，此时 {@code missing} 为 {@code []}。
     */
    private Outcome parseAsk(JsonNode root) {
        String question = firstNonBlank(text(root, "question"), text(root, "reply"));
        if (question == null) {
            return new Rejected("decision=ask 但既没有 question 也没有 reply", true);
        }
        List<String> missing = new ArrayList<>();
        JsonNode node = root.get("missing");
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    missing.add(item.asText());
                }
            }
        }
        return new Ask(question, List.copyOf(missing));
    }

    private Outcome parseReply(JsonNode root) {
        String reply = firstNonBlank(text(root, "reply"), text(root, "question"));
        if (reply == null) {
            return new Rejected("decision=reply 但没有给出 reply", true);
        }
        return new Reply(reply);
    }

    private Outcome readCall(JsonNode root, AssistantContext context) {
        String toolName = text(root, "tool");
        if (toolName == null) {
            return new Rejected("decision=call 但没有给出 tool", false);
        }
        JsonNode argumentsNode = root.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argumentsNode != null && !argumentsNode.isNull()) {
            if (!argumentsNode.isObject()) {
                return new Rejected("arguments 必须是一个 JSON 对象", false);
            }
            arguments = objectMapper.convertValue(argumentsNode, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        }
        return validateCall(toolName, arguments, context);
    }

    /**
     * 一次「调用意图」的完整校验，<b>提问路径专用</b>：引用类参数必须命中本轮候选清单。
     *
     * <p>确认路径走 {@link #validateConfirmedCall}。两者共用下面的 {@code validate}，
     * 差别只有「比不比对候选清单」这一项 —— 为什么确认时不比，见那边。
     *
     * <p>为什么要把校验抽成公开方法而不是让确认方自己写一遍：确认与首次解析必须共用
     * <b>结构校验</b>。如果确认侧另写一份，两份规则迟早分叉，而分叉的表现形式是
     * 「某条路径少校验了一项」——这种缺失在测试里很难被想到。
     *
     * <p>确认时还会被 {@code ToolRegistry.invoke} 再校验一次 schema，那是刻意的重复：
     * 这里挡的是「模型给的参数有问题」，那里挡的是「任何调用方给的参数有问题」。
     */
    public Outcome validateCall(String toolName, Map<String, Object> arguments, AssistantContext context) {
        return validate(toolName, arguments, context);
    }

    /**
     * 确认执行前的校验。<b>结构照旧校验，但跳过候选清单比对。</b>
     *
     * <h2>为什么确认时不能比对候选清单</h2>
     * 候选清单是「这个用户最近能看到什么」的<b>窗口</b>（待办 70 条、会话 20 条），
     * 它既不是「对象是否存在」，也不是「我有没有权限」。而只读检索的价值恰恰是
     * <b>突破这个窗口</b>：模型先 {@code conversation.search} 拿到窗口外的 ref，再对它下写动作。
     * 拿窗口去判存续，等于把「先检索、再对检索结果动手」整条路判死 ——
     * 卡片显示得出来（摘要由服务端渲染），用户一点确认却必然得到「引用已失效」。
     *
     * <p>确认路径上真正该问的两个问题是「这东西还在不在」与「我还有没有权限」，
     * 而这两件事的<b>执行路径本来就各自负责</b>：{@code TodoItemService.require/delete/update}
     * 全部带 {@code where user_id}，{@code ConversationPreferenceService.authorize}
     * 查 {@code findAccessibleById(userId)}。所以这里不重复它们 ——
     * 重复才是分叉的来源，而分叉的表现就是这条路上的那次假失败。
     *
     * <p><b>这里仍要拦住的东西</b>：schema 不符（部署后工具契约变了）与日期/时间不可解析 ——
     * 也就是「这份存下来的参数本身已经不成立」。这类失败返回 {@code Rejected}，
     * 由调用方转成 {@code ASSISTANT_STALE_REFERENCE}。
     *
     * <p><b>安全性没有下降</b>：模型编造 id 的第一道拦截在提问路径
     * （{@link #validateCall} 的候选比对），而确认路径上模型已经不在场 ——
     * 用户看到的卡片摘要由服务端从候选渲染并落库，执行时还有第二道 {@code where user_id}。
     * 要绕开第一道，得先骗过用户点下确认。
     */
    public Outcome validateConfirmedCall(String toolName, Map<String, Object> arguments) {
        return validate(toolName, arguments, null);
    }

    /**
     * 两条路径共用的结构校验。{@code context} 为 {@code null} <b>只表示</b>「不做候选比对」，
     * 由 {@link #validateConfirmedCall} 传入；其余调用方一律传真实上下文。
     */
    private Outcome validate(String toolName, Map<String, Object> arguments, AssistantContext context) {
        ToolDefinition definition = registry.find(toolName).orElse(null);
        if (definition == null) {
            // 不把模型给的名字原样送回给用户看：它多半是编的，写进回复只会造成「系统里有这个能力」的误解。
            return new Rejected("模型请求了一个不存在的操作：" + toolName, false);
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        // 1) 入参 schema。走的是进程内那一层校验器（SDK 的校验在进程内路径上不执行，见 §7.5 第 2 条）。
        //    含 additionalProperties:false —— 模型往 arguments 里偷塞身份字段会在这里被拒。
        try {
            validator.validate(definition, args);
        } catch (ToolExecutionException e) {
            return new Rejected(e.getMessage(), false);
        }

        // 2) 引用类参数必须命中本轮候选清单。这是「模型编造 id」的第一道拦截；
        //    第二道是 SQL 里的 where user_id（即便清单比对被绕过，也动不了别人的数据）。
        //
        //    比对范围跟着**工具自己的声明**走（字段级 x-candidateSet），不再是一份全局参数名清单。
        //    集合缺席时拒绝而不是放行：那意味着工具声明了一个本轮没有提供的候选组，
        //    属于服务端与上下文的契约破损，fail-closed 比"放它过去再靠 SQL 兜"更诚实。
        //    **只在提问路径做**：context 为 null 表示确认路径，理由见 validateConfirmedCall。
        if (context != null) {
            for (Map.Entry<String, String> binding : definition.referenceBindings().entrySet()) {
                Object value = args.get(binding.getKey());
                if (!(value instanceof String reference) || reference.isBlank()) {
                    // 缺席由 schema 的 required 管，这里不重复报「缺少必填参数」，以免把两种错混成一类。
                    continue;
                }
                CandidateSet candidates = context.candidateSet(binding.getValue());
                if (candidates == null) {
                    return new Rejected("这次请求需要「" + binding.getValue()
                            + "」候选清单才能校验 " + binding.getKey() + "，但本轮没有提供该清单", false);
                }
                if (!candidates.contains(reference)) {
                    return new Rejected("请求里的 " + binding.getKey()
                            + " 不在当前候选" + candidates.heading() + "中", false);
                }
            }
        }

        // 3) 日期 / 时间必须真的能解析。schema 里的 format 只是给模型看的提示，不是校验。
        Outcome invalidDate = requireParsable(args, "date", value -> LocalDate.parse(value));
        if (invalidDate != null) return invalidDate;
        Outcome invalidTime = requireParsable(args, "time", value -> LocalTime.parse(value));
        if (invalidTime != null) return invalidTime;

        return new Call(definition.name(),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(args)));
    }

    private static Outcome requireParsable(Map<String, Object> arguments, String key, java.util.function.Consumer<String> parser) {
        Object value = arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            parser.accept(text.strip());
            return null;
        } catch (DateTimeParseException e) {
            return new Rejected(key + " 不是合法的" + (key.equals("date") ? "日期" : "时间") + "：" + text, false);
        }
    }

    // ---------- 工具 ----------

    /**
     * 从模型输出里取出那个 JSON 对象。
     *
     * <p>容忍 markdown 代码块围栏与前后杂字：0.3 实测 25/25 都不需要剥围栏，但这是**降级路径**
     * 而不是校验放宽 —— 取出来的文本仍要按 JSON 解析、结构仍要逐条校验，
     * 只是不必因为模型多写了一个 ``` 就把整轮判死。
     */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0) {
                return null;
            }
            text = text.substring(newline + 1).strip();
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).strip();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    // ---------- 结果 ----------

    /** 解析结果。**分支依据只有一个：{@code decision}。** */
    public sealed interface Outcome permits Ask, Call, Reply, Rejected {
    }

    /** 需要向用户追问。{@code missing} 可能为空 —— 那表示指代有歧义，而不是缺参数。 */
    public record Ask(String question, List<String> missing) implements Outcome {
    }

    /** 一次工具调用意图。最多一个：单轮不允许改多条待办。 */
    public record Call(String tool, Map<String, Object> arguments) implements Outcome {
    }

    /** 直接回话，不执行任何动作。 */
    public record Reply(String reply) implements Outcome {
    }

    /** 拒绝。{@code retryable} 的含义见类注释；{@code reason} 会回灌给模型（仅重试时）并写进审计。 */
    public record Rejected(String reason, boolean retryable) implements Outcome {
    }
}
