package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具注册表：AI 能做的每一件事在这里<b>声明一次</b>。
 *
 * <h2>它是这套设计的中心，而不是 MCP 传输</h2>
 * MCP 真正值钱的是它的模型（名字 + JSON Schema + 行为注解可被发现、可被调用），不是它的字节。
 * 所以提示词里的工具清单、{@code tools/list} 的返回、以及将来的 {@code /mcp} 端点，
 * 全部从这一份声明生成：<b>加一个能力只改这里，编排层与提示词不用动。</b>
 *
 * <h2>为什么不引 mcp-spring-webmvc / 聚合构件</h2>
 * Servlet 传输在 {@code mcp-core} 里就有，不需要 Spring AI、也不需要升 Boot；
 * 而聚合构件 {@code mcp} 会拖入 Jackson 3，与 Boot 3.4.5 管理的 Jackson 2.18.x 冲突。
 * 本期走进程内适配层，连 wire 序列化都不需要，所以只引 {@code mcp-core}。
 *
 * <h2>启动时自检，而不是等第一次调用</h2>
 * 构造器逐条检查声明的合法性，任一不合法直接让应用启动失败。理由：这些错误全都落在
 * "运行时才暴露"与"永远不暴露"之间 —— 一个写错的 {@code additionalProperties}
 * 不会报错，只会让身份防线静默消失。宁可起不来。
 *
 * <h2>顺序是稳定的</h2>
 * 内部按装配顺序保存在 {@link LinkedHashMap} 里，且刻意不用 {@code Map.copyOf}
 * （它不保证迭代顺序）。提示词内容与快照测试都依赖这个顺序稳定。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> byName;
    private final ToolInputValidator validator;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<ToolDefinition> definitions, ToolInputValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
        if (definitions == null || definitions.isEmpty()) {
            // 不是"没工具也能跑"的情况：注册表为空意味着模型永远只能聊天，
            // 而调用方会以为自己在用一套工具系统。静默降级比启动失败更难查。
            throw new IllegalStateException("工具注册表为空：请确认工具声明 bean 已被装配");
        }
        Map<String, ToolDefinition> indexed = new LinkedHashMap<>();
        for (ToolDefinition definition : definitions) {
            selfCheck(definition);
            if (indexed.put(definition.name(), definition) != null) {
                throw new IllegalStateException("工具名重复：" + definition.name());
            }
        }
        this.byName = Collections.unmodifiableMap(indexed);
        log.info("已注册 {} 个助手工具：{}", indexed.size(), String.join(", ", indexed.keySet()));
    }

    /** 按名查找。找不到返回空，由调用方决定是"报给用户"还是"整轮作废"。 */
    public Optional<ToolDefinition> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    /** 全部声明，顺序与装配顺序一致。 */
    public List<ToolDefinition> list() {
        return List.copyOf(byName.values());
    }

    /**
     * 渲染成给模型看的工具清单。
     *
     * <p>注解在这里<b>按 MCP 规范的默认值补齐</b>：规范规定未声明时
     * {@code destructiveHint} 按 {@code true}、{@code readOnlyHint} 按 {@code false}、
     * {@code openWorldHint} 按 {@code true} 解释（默认值刻意保守）。
     * 提示词里必须呈现补全后的值 —— 若原样输出 {@code null} 或干脆省略，
     * 模型看不到"这是可能破坏数据的操作"，这条声明就白写了。
     */
    public String renderForPrompt() {
        List<Map<String, Object>> catalogue = new ArrayList<>();
        for (ToolDefinition definition : byName.values()) {
            McpSchema.Tool tool = definition.tool();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("title", tool.title());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            entry.put("annotations", normaliseAnnotations(tool.annotations()));
            catalogue.add(entry);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(catalogue);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具清单无法序列化为 JSON", e);
        }
    }

    /**
     * 统一入口：查表 → 校验参数 → 执行 → 把异常收敛成错误码。
     *
     * <p><b>身份不在这里解析</b>，只作为参数接收。取不到用户是调用方的事，
     * 而且必须"拒绝"而不是"降级"（见 {@link ToolHandler}）。
     */
    public ToolResult invoke(String name, UUID userId, Map<String, Object> arguments) {
        ToolDefinition definition = byName.get(name);
        if (definition == null) {
            return ToolResult.failure(ToolExecutionException.UNKNOWN_TOOL, "不支持的操作：" + name);
        }
        if (userId == null) {
            // 不返回 error 结果：那会让模型看到"操作失败"并可能换个说法再试，
            // 掩盖掉真正的问题（这个请求没有认证身份）。抛出，交由上层变 401。
            throw new IllegalArgumentException("执行工具 " + name + " 时缺少当前用户：工具必须在已认证的请求里执行");
        }
        try {
            validator.validate(definition, arguments);
            ToolResult result = definition.handler().execute(userId, arguments == null ? Map.of() : arguments);
            if (result == null) {
                throw new ToolExecutionException(ToolExecutionException.INTERNAL, "工具 " + name + " 未返回结果");
            }
            return result;
        } catch (ToolExecutionException e) {
            log.debug("工具 {} 执行失败：{} {}", name, e.code(), e.getMessage());
            return ToolResult.failure(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            // 未预期的异常不把底层消息透给用户：它可能含 SQL 片段或内部路径。
            log.warn("工具 {} 执行出现未预期异常", name, e);
            return ToolResult.failure(ToolExecutionException.INTERNAL, "执行失败，请稍后再试");
        }
    }

    /** 把注解补齐为规范的保守默认值；未设置的字段一律按默认解释，不保留"未知"。 */
    static Map<String, Object> normaliseAnnotations(McpSchema.ToolAnnotations annotations) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("readOnlyHint", defaultIfNull(annotations == null ? null : annotations.readOnlyHint(), false));
        rendered.put("destructiveHint", defaultIfNull(annotations == null ? null : annotations.destructiveHint(), true));
        rendered.put("idempotentHint", defaultIfNull(annotations == null ? null : annotations.idempotentHint(), false));
        rendered.put("openWorldHint", defaultIfNull(annotations == null ? null : annotations.openWorldHint(), true));
        return rendered;
    }

    private static boolean defaultIfNull(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 把 schema 的 {@code Map<?, ?>} 视图收成 {@code Map<String, Object>}。
     *
     * <p>这个不检查的强转是安全的：输入的 schema 全部由各工具类的 helper 构造
     * （{@code LinkedHashMap<String, Object>}），没有反序列化路径能塞进别的 key 类型。
     * 先把值拆出来断言类型、再转，是为了让编译期签名与 {@code ToolInputValidator} 对齐
     * （那边的方法收 {@code Map<String, Object>}）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<?, ?> schema) {
        return (Map<String, Object>) schema;
    }

    private void selfCheck(ToolDefinition definition) {
        String name = definition.name();
        McpSchema.Tool tool = definition.tool();
        if (tool.description() == null || tool.description().isBlank()) {
            // 描述是模型决定"何时该用这个工具"的唯一依据，缺了它工具等于不存在。
            throw new IllegalStateException("工具 " + name + " 缺少 description");
        }
        Map<String, Object> schema = tool.inputSchema();
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalStateException("工具 " + name + " 的 inputSchema 必须是 type=object 的对象");
        }
        if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            // 安全关键，不是风格问题：这道约束正是拒掉模型偷塞身份字段的机制
            // （阶段 0.1 实测：SDK 据此拒绝 arguments 里的 userId）。
            // 缺了它不会报错，只会让防线静默失效，所以宁可起不来。
            throw new IllegalStateException(
                    "工具 " + name + " 的 inputSchema 必须显式声明 additionalProperties: false —— 这是身份防线的硬机制，不能省");
        }
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            throw new IllegalStateException("工具 " + name + " 的 inputSchema 缺少 properties");
        }
        for (String required : definition.requiredArguments()) {
            if (!properties.containsKey(required)) {
                throw new IllegalStateException("工具 " + name + " 的 required 里有未声明的字段：" + required);
            }
        }

        // 逐个字段校验「声明里有没有本校验器不认识的关键字」。
        //
        // 这条检查防的是「声明了却不生效」：{@code ToolInputValidator} 只处理它认得的 key，
        // 其余一律忽略，所以 {@code pattern} / {@code minLength} / 拼错的 {@code maxlength}
        // 既不报错也不生效 —— 而写它的人以为约束在。已有一个真实例子：{@code todo.create} 的
        // {@code date} / {@code time} 从第一天起就写着 {@code format}，而校验器当时不认这个
        // 关键字，那两条声明从来没有生效过（详见 {@code ToolInputValidator.SUPPORTED_PROPERTY_KEYS}）。
        //
        // 与「引用参数忘记绑定候选集」同类：运行期看不出来，测试里也看不出来（工具照常工作），
        // 所以宁可起不来。items 内（元素 schema）单独再查一次 —— 它的关键字集合比字段级更小。
        for (Object property : properties.keySet()) {
            String field = String.valueOf(property);
            Object rawPropertySchema = properties.get(property);
            if (!(rawPropertySchema instanceof Map<?, ?> propertySchema)) {
                // 结构错误也在这里拦：下层 validate 会把它报成 INTERNAL（用户可见的 500），
                // 但这是声明写错，本该在启动期就暴露。
                throw new IllegalStateException("工具 " + name + " 的参数 " + field + " 的 schema 必须是对象");
            }
            Map<String, Object> declared = stringKeyed(propertySchema);
            ToolInputValidator.rejectUnsupportedKeys(name, "properties." + field,
                    declared, ToolInputValidator.SUPPORTED_PROPERTY_KEYS);
            if (declared.get("items") instanceof Map<?, ?> itemSchema) {
                ToolInputValidator.rejectUnsupportedKeys(name, "properties." + field + ".items",
                        stringKeyed(itemSchema), ToolInputValidator.SUPPORTED_ITEM_KEYS);
            }
        }

        Object atLeastOneOf = schema.get(ToolInputValidator.REQUIRES_AT_LEAST_ONE_OF);
        if (atLeastOneOf instanceof List<?> candidates) {
            for (Object candidate : candidates) {
                if (!properties.containsKey(String.valueOf(candidate))) {
                    // 声明了一个不存在的字段：校验器会永远认为它没被给出，于是这个工具
                    // 无论怎么调都失败。起不来比"永远调不动"更容易查。
                    throw new IllegalStateException("工具 " + name + " 的 "
                            + ToolInputValidator.REQUIRES_AT_LEAST_ONE_OF + " 里有未声明的字段：" + candidate);
                }
            }
        }

        // 引用类参数必须声明「引用哪一组候选」，或显式豁免。
        //
        // 这条检查的全部价值在于「忘记声明会起不来」：解析器只对**声明了绑定**的字段做候选比对，
        // 所以一个忘了绑定的引用字段不会报错，只会让「模型编造 id」的第一道拦截静默消失 ——
        // 那时剩下的唯一防线是 SQL 里的 where user_id，而它只挡越权、不挡编造。
        // 这类缺失在测试里也看不出来（工具照常工作），所以宁可起不来。
        //
        // 命名约定是 `*Id` / `*Ref`（含复数 `*Ids` / `*Refs`）：约定本身是这道检查能成立的前提，
        // 因此写进错误信息里。
        //
        // 复数形式必须一起认，不能只认单数：`topicRefs` 这类「一组引用」的参数以 Refs 结尾，
        // 只认单数时它连这道自检都不触发 —— 而它恰恰是最需要绑定的形状（见 AssistantDecisionParser
        // 的逐元素比对）。少认一个后缀，就等于给最危险的那类参数开了一个静默通道。
        Map<String, String> bindings = definition.referenceBindings();
        List<String> exemptions = definition.unboundIdExemptions();
        for (Object property : properties.keySet()) {
            String field = String.valueOf(property);
            if (!isReferenceShaped(field) || bindings.containsKey(field) || exemptions.contains(field)) {
                continue;
            }
            throw new IllegalStateException("工具 " + name + " 的参数 " + field
                    + " 形如引用类参数（以 Id / Ids / Ref / Refs 结尾），但既没有在字段上声明 "
                    + ToolInputValidator.CANDIDATE_SET + "（候选集名），也没有在 schema 里用 "
                    + ToolInputValidator.UNBOUND_IDS + " 声明它不来自候选。"
                    + "漏声明会让「模型编造 id」的比对静默失效");
        }
        for (String exempted : exemptions) {
            if (!properties.containsKey(exempted)) {
                throw new IllegalStateException("工具 " + name + " 的 "
                        + ToolInputValidator.UNBOUND_IDS + " 里有未声明的字段：" + exempted);
            }
        }
    }

    /**
     * 引用类参数的命名约定。改这里等于改约定，必须同时改所有工具声明。
     *
     * <p>含<b>复数</b>形式：`*Refs` / `*Ids` 表示「一组引用」，解析器会逐元素与候选比对
     * （见 {@code AssistantDecisionParser.referencesOf}）。只认单数会让这类参数静默逃过自检。
     */
    static boolean isReferenceShaped(String field) {
        return field.endsWith("Id") || field.endsWith("Ref")
                || field.endsWith("Ids") || field.endsWith("Refs");
    }
}
