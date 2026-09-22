package com.crmforlogistics.messagecenter.service.assistant.mcp;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 进程内路径上的入参 schema 校验。
 *
 * <h2>为什么必须自己写这一层</h2>
 * MCP SDK 的 JSON Schema 校验发生在 {@code McpServer} 处理 {@code tools/call} 的链路上。
 * 本期助手走<b>进程内适配层</b>，不注册 {@code /mcp}、不经过 SDK 的 server，所以那条链路上的
 * 校验<b>根本不会执行</b>。而 {@code additionalProperties: false} 是身份防线的硬机制
 * （阶段 0.1 实测：它正是拒掉模型偷塞 {@code userId} 的那道门），
 * 一旦以为"SDK 会校验"而省掉这块，防线就静默消失了。
 *
 * <h2>它不是通用 JSON Schema 实现</h2>
 * 只覆盖本注册表实际用到的关键字：{@code type(object) / properties / required /
 * additionalProperties / type(string|boolean|integer) / maxLength / enum}。
 * <b>新增关键字必须同步扩展这里并补测试</b> —— 静默忽略一个约束比直接报不支持更危险。
 * 因此遇到不认识的关键字类型会报 {@code INTERNAL}（那是服务端声明写错），而不是放过。
 */
@Component
public class ToolInputValidator {

    private static final String TYPE_OBJECT = "object";

    /**
     * 注册表自定义的 schema 关键字：所列字段至少要给出一个。
     *
     * <p>用 {@code x-} 前缀是 JSON Schema 的惯例（自定义关键字用 {@code x-} 命名空间，
     * 不与标准关键字冲突，别的实现会安全忽略它）。它不是通用能力，
     * 只为「一次修改必须有内容可改」这一条服务 —— 加新关键字必须同时改这里并补测试，
     * 这是本类的既定契约（静默忽略一个约束比直接报不支持更危险）。
     */
    static final String REQUIRES_AT_LEAST_ONE_OF = "x-requiresAtLeastOneOf";

    /**
     * 字段级关键字：这个参数引用哪一组候选（值就是候选集的名字，如 {@code todo} / {@code conversation}）。
     *
     * <h2>它取代了原来那份硬编码的引用参数名清单</h2>
     * 原来 {@code AssistantDecisionParser} 里写死 {@code REFERENCE_ARGUMENTS = {"todoId"}}，
     * 对<b>所有</b>工具生效。那在只有一个域时没问题，加第二个域就有两个坏处：
     * 一是新工具若用了别的参数名（如 {@code conversation}），它会<b>静默地</b>不被比对；
     * 二是「这个参数必须命中候选」这件事离声明它的地方太远，加工具的人看不到。
     *
     * <p>改成字段级声明之后，绑定与字段在同一行代码上，且 {@link ToolRegistry#selfCheck}
     * 会拒绝「形如 {@code *Id} 却没有绑定」的字段 —— 忘记声明会起不来，而不是防线消失。
     */
    static final String CANDIDATE_SET = "x-candidateSet";

    /**
     * schema 级关键字：显式的「这些 id 不来自候选」白名单。
     *
     * <p>存在的理由是上面那条检查必须有一个<b>可审查的</b>出口：某些 id 天然不来自候选
     * （例如「按工单号查询」这种外部标识）。给它一个具名出口，比让人用改名（{@code orderNo}）
     * 绕过检查要好 —— 绕过的痕迹是看不见的，出口是一行 diff。
     */
    static final String UNBOUND_IDS = "x-unboundIds";

    /**
     * 校验一次调用。所有失败都抛 {@link ToolExecutionException}，且都已带上对用户可读的措辞。
     *
     * @param arguments 调用方给出的参数；{@code null} 等价于空 map
     */
    public void validate(ToolDefinition definition, Map<String, Object> arguments) {
        String toolName = definition.name();
        Map<String, Object> schema = definition.tool().inputSchema();
        if (schema == null) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 未声明入参 schema");
        }
        if (!TYPE_OBJECT.equals(schema.get("type"))) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的入参 schema 类型必须是 object");
        }

        Map<String, Object> properties = asMap(schema.get("properties"), toolName, "properties");
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        // 1) 越界字段。这条是安全关键：身份字段若从这里进来就等于模型能指定身份。
        for (String key : args.keySet()) {
            if (!properties.containsKey(key)) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "参数 " + key + " 不是 " + toolName + " 支持的参数");
            }
        }

        // 2) 必填。
        List<String> missing = new ArrayList<>();
        for (String key : definition.requiredArguments()) {
            if (!args.containsKey(key) || args.get(key) == null) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT,
                    "缺少必填参数：" + String.join("、", missing));
        }

        // 2b) 自定义关键字 x-requiresAtLeastOneOf：「只有标识、没有一个要改的字段」不算一次有效调用。
        //     它存在的理由是一条具体的坏路径：`todo.update` 的 required 只有 todoId，
        //     因此「改一下和张总确认报价那条」会被解析成一次合法调用，然后弹出一张
        //     「修改待办：… → （没有要改的字段）」的确认卡片，用户点确认才失败。
        //     把约束放进 schema，提示词渲染与调用校验就共用同一份声明。
        Object atLeastOneOf = schema.get(REQUIRES_AT_LEAST_ONE_OF);
        if (atLeastOneOf instanceof List<?> candidates && !candidates.isEmpty()) {
            boolean satisfied = candidates.stream().map(String::valueOf)
                    .anyMatch(key -> args.get(key) != null);
            if (!satisfied) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "至少要给出一个要修改的字段：" + String.join("、", candidates.stream().map(String::valueOf).toList()));
            }
        }

        // 3) 逐字段约束。
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                // 显式传 null 与不传等价：schema 里没有 nullable 语义，不该被当成"给了一个值"。
                continue;
            }
            checkProperty(toolName, entry.getKey(), asMap(properties.get(entry.getKey()), toolName, "properties." + entry.getKey()), value);
        }
    }

    private void checkProperty(String toolName, String key, Map<String, Object> propertySchema, Object value) {
        Object declaredType = propertySchema.get("type");
        if (declaredType == null) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的参数 " + key + " 未声明 type");
        }
        String type = String.valueOf(declaredType);
        boolean matched = switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number number && number.longValue() == number.doubleValue();
            default -> throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的参数 " + key + " 声明了本校验器不支持的类型：" + type);
        };
        if (!matched) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 应为 " + describeType(type) + "，收到 " + describeType(value));
        }

        if (value instanceof String text) {
            Object maxLength = propertySchema.get("maxLength");
            if (maxLength instanceof Number limit && text.length() > limit.intValue()) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "参数 " + key + " 不能超过 " + limit.intValue() + " 个字符，收到 " + text.length() + " 个");
            }
        }

        Object allowed = propertySchema.get("enum");
        if (allowed instanceof List<?> values && !values.isEmpty() && !values.contains(value)) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 只能是 " + values + " 之一，收到 " + value);
        }
    }

    private static String describeType(String jsonType) {
        return switch (jsonType) {
            case "string" -> "文本";
            case "boolean" -> "布尔值";
            case "integer" -> "整数";
            default -> jsonType;
        };
    }

    private static String describeType(Object value) {
        if (value instanceof String) return "文本";
        if (value instanceof Boolean) return "布尔值";
        if (value instanceof Number) return "数字";
        if (value instanceof List<?>) return "数组";
        if (value instanceof Map<?, ?>) return "对象";
        return value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String toolName, String path) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                "工具 " + toolName + " 的 schema 字段 " + path + " 应为对象");
    }
}
