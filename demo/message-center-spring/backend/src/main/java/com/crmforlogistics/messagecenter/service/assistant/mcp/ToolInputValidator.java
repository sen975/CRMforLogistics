package com.crmforlogistics.messagecenter.service.assistant.mcp;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * additionalProperties / type(string|boolean|integer|array) / maxLength / format(date|time) /
 * minimum / maximum / minItems / maxItems / items(type=string, maxLength) / enum}。
 * <b>新增关键字必须同步扩展这里并补测试</b> —— 静默忽略一个约束比直接报不支持更危险。
 * 因此遇到不认识的关键字类型会报 {@code INTERNAL}（那是服务端声明写错），而不是放过。
 *
 * <p>这份注释说的是「本类懂哪些关键字」，而 {@link #SUPPORTED_PROPERTY_KEYS} 是它的
 * <b>可执行</b>版本：{@code ToolRegistry} 在启动期逐个字段比对，写了清单外的 key 就起不来。
 * 两者必须一致 —— 所以「加一个关键字」其实是三件事：改清单、真实现、补测试。
 *
 * <p>{@code array} 是给「把一组自由文本交给服务端」用的（如联系人标签的整体替换）。
 * 它的元素约束（类型、长度）由 {@link #checkArray} 单独校验 —— 外层 {@code maxLength}
 * 对数组没有意义，只判「它是个 List」会让 {@code [{"name":"x"}]} 一路走到服务层变成 500。
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
     * 字段（property）级允许出现的关键字全集。
     *
     * <h2>为什么需要这份白名单，而不是「认得的就处理、不认得的忽略」</h2>
     * 本类是进程内路径上<b>唯一</b>的入参检查（见类注释：SDK 那条 {@code tools/call} 的校验
     * 根本不执行）。所以某个工具声明了这里不实现的关键字时，校验器不会报错 ——
     * 它只是把那句话<b>当作不存在</b>。于是声明方以为约束在生效、读代码的人以为约束在生效，
     * 而模型可以随便越界，直到有人从真实使用里复现出问题。
     *
     * <p>这不是假设：{@code todo.create} 的 {@code date} / {@code time} 从第一天起就写着
     * {@code format}，而本类当时并不认识这个关键字 —— 那两条声明<b>从来没有生效过</b>
     * （真正拦住越界值的是服务层的 {@code LocalDate.parse}，见 {@link #checkFormat}）。
     * 一份「看起来在生效」的约束比没有约束更坏，因为它会让人不再去别处设防。
     *
     * <p>所以把「支持哪些关键字」变成一份可执行清单，由 {@link ToolRegistry#selfCheck}
     * 在<b>启动期</b>比对：出现清单外的 key 就起不来。与「忘记给引用参数绑定候选集就起不来」
     * 是同一个哲学 —— 这类缺失在运行期和测试里都看不出来（工具照常工作）。
     *
     * <p><b>加关键字 = 改这份清单 + 在 {@link #checkProperty} 里真的实现 + 补测试</b>，
     * 三件事一起做。只改清单，等于给一个永远不生效的声明发通行证。
     *
     * <p>这份清单是<b>刻意保守</b>的：它也会拒掉 {@code title} / {@code examples} 这类
     * <b>纯注释</b>关键字 —— 它们没有约束语义，写了不生效也不会造成假保证。代价是
     * 「想写 title」的人得改这一行；收益是不会漏掉任何一个约束类关键字。两个方向的代价
     * 不对称：漏掉一个约束类是<b>静默</b>的（模型越界没人知道），误拒一个注释类是<b>响亮</b>的
     * （启动就报错、且错误信息直接说了怎么办），所以宁严勿宽。
     */
    static final Set<String> SUPPORTED_PROPERTY_KEYS = Set.of(
            "type", "description", "maxLength", "format", "minimum", "maximum",
            "minItems", "maxItems", "items", "enum", CANDIDATE_SET);

    /**
     * 数组元素 schema（{@code items}）内允许出现的关键字。
     *
     * <p>刻意比字段级小得多：元素目前只有「一段自由文本」这一种形态，元素级数值边界与枚举
     * 都没有实际用途。真需要时再加，并同步补测试 —— 加一个「看起来支持、其实只判了外层」
     * 的关键字，比不支持它更糟。
     */
    static final Set<String> SUPPORTED_ITEM_KEYS = Set.of("type", "description", "maxLength", "format");

    /**
     * {@code format} 目前支持的取值。
     *
     * <p>{@link #SUPPORTED_PROPERTY_KEYS} 只放行「能不能写 {@code format} 这个 key」，
     * 放行不了「写什么值」—— {@code format: "email"} 同样是「声明了却不生效」，
     * 所以取值也要在启动期拦下（见 {@link #rejectUnsupportedKeys}）。
     */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("date", "time");

    /**
     * 拒绝声明了本校验器不认识的关键字。由 {@link ToolRegistry#selfCheck} 在启动期调用。
     *
     * <p>抛 {@code IllegalStateException} 而不是 {@link ToolExecutionException}：这是
     * <b>服务端声明写错</b>，与某一次调用无关，所以要在启动时就暴露，而不是等第一次有人
     * 用到那个字段。与 {@code selfCheck} 里其余检查的处置一致。
     *
     * @param path 出错时用来定位的字段路径，如 {@code properties.days} 或 {@code properties.tags.items}
     */
    static void rejectUnsupportedKeys(String toolName, String path, Map<String, Object> schema,
                                      Set<String> supportedKeys) {
        for (String key : schema.keySet()) {
            if (!supportedKeys.contains(key)) {
                throw new IllegalStateException("工具 " + toolName + " 的 " + path + " 声明了本校验器不支持的关键字："
                        + key + "。声明了却不会被校验，等于那条约束<b>不存在</b> —— "
                        + "要用它请先在 ToolInputValidator 里实现并补测试，否则请删掉这个声明");
            }
        }
        Object format = schema.get("format");
        if (format != null && !SUPPORTED_FORMATS.contains(String.valueOf(format))) {
            // 与上一条同理：key 认得、取值不认得时，约束同样等于不存在。
            throw new IllegalStateException("工具 " + toolName + " 的 " + path + " 声明了本校验器不支持的 format："
                    + format + "（目前只支持 " + SUPPORTED_FORMATS + "）");
        }
    }

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
            case "array" -> value instanceof List<?>;
            default -> throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的参数 " + key + " 声明了本校验器不支持的类型：" + type);
        };
        if (!matched) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 应为 " + describeType(type) + "，收到 " + describeType(value));
        }

        if (value instanceof String text) {
            checkLength(toolName, key, text, propertySchema.get("maxLength"));
            checkFormat(key, text, propertySchema.get("format"));
        }

        if (value instanceof Number number) {
            checkRange(key, number, propertySchema);
        }

        if (value instanceof List<?> elements) {
            checkArray(toolName, key, elements, propertySchema);
        }

        Object allowed = propertySchema.get("enum");
        if (allowed instanceof List<?> values && !values.isEmpty() && !values.contains(value)) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 只能是 " + values + " 之一，收到 " + value);
        }
    }

    /**
     * 数组参数的元素级校验。
     *
     * <h2>为什么必须做，而不是「检查它是个 List 就够了」</h2>
     * 数组参数在本项目里唯一的用途是<b>把一个自由文本集合交给服务端</b>（如联系人标签）。
     * 只判外层是 {@code List} 会漏掉最要紧的两件事：
     *
     * <ul>
     *   <li><b>元素类型</b>：{@code [{"name":"x"}]} 也过得了外层检查，但它到不了
     *       {@code List<String>} 的签名上，会在服务层变成一个与用户输入无关的
     *       {@code ClassCastException}（500），而不是一句「参数不合法」。</li>
     *   <li><b>元素长度</b>：外层 {@code maxLength} 对数组没有意义，而元素是自由文本，
     *       无上限就等于把「渲染进提示词／写进库里」的长度交给模型决定。</li>
     * </ul>
     *
     * <p>刻意<b>不</b>支持 {@code items.type=object}：本注册表还没有这种参数，
     * 而"看起来支持、其实只判了外层"会让下一个加工具的人写出静默失效的声明。
     * 真需要时再连同元素字段一起实现，并补测试。
     */
    private void checkArray(String toolName, String key, List<?> elements, Map<String, Object> propertySchema) {
        Object minItems = propertySchema.get("minItems");
        if (minItems instanceof Number floor && elements.size() < floor.intValue()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 至少 " + floor.intValue() + " 项，收到 " + elements.size() + " 项");
        }

        Object maxItems = propertySchema.get("maxItems");
        if (maxItems instanceof Number limit && elements.size() > limit.intValue()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 最多 " + limit.intValue() + " 项，收到 " + elements.size() + " 项");
        }

        Object items = propertySchema.get("items");
        if (items == null) {
            // 没有 items 的数组等于「元素是什么都行」，与上面的理由冲突，视为声明写错。
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的参数 " + key + " 是数组但没有声明 items");
        }
        Map<String, Object> itemSchema = asMap(items, toolName, "properties." + key + ".items");
        Object itemType = itemSchema.get("type");
        if (!"string".equals(itemType)) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "工具 " + toolName + " 的参数 " + key + " 的 items.type 目前只支持 string，声明为：" + itemType);
        }

        for (Object element : elements) {
            if (!(element instanceof String text)) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "参数 " + key + " 的每一项都应为" + describeType("string")
                                + "，收到" + describeType(element));
            }
            // 长度检查下沉到元素：外层 maxLength 对数组不适用，而元素才是自由文本。
            checkLength(toolName, key, text, itemSchema.get("maxLength"));
        }
    }

    private static void checkLength(String toolName, String key, String text, Object maxLength) {
        if (maxLength instanceof Number limit && text.length() > limit.intValue()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 不能超过 " + limit.intValue() + " 个字符，收到 " + text.length() + " 个");
        }
    }

    /**
     * 字符串格式（{@code format}）：本注册表只支持 {@code date} 与 {@code time}。
     *
     * <h2>为什么这两个值必须真的实现，而不是让它继续当装饰</h2>
     * {@code todo.create} / {@code todo.update} 的 {@code date} / {@code time} 一直声明着
     * {@code format}，而本类当时不认这个关键字（见 {@link #SUPPORTED_PROPERTY_KEYS} 的注释）。
     * 真正拦住「2026-13-45」和「明天下午」的是服务层的 {@code LocalDate.parse} /
     * {@code LocalTime.parse}，外加工具侧把 {@code DateTimeParseException} 收成
     * {@code INVALID_ARGUMENT} 的兜底 —— 约束在，但它在<b>下一层</b>。
     *
     * <p>实现出来的收益是<b>失败点前移</b>（省掉一次服务调用，且错在参数上而不是业务上），
     * 而<b>不是</b>「更严格」。这一点是刻意的：这里与服务层用同一个解析器、同样先 {@code trim}，
     * 判据完全等价。校验层若比服务层更严，就会变成「原来能过的输入现在过不了」——
     * 那是在校验层偷偷改业务规则，而它看起来只是一次「加了点校验」。
     */
    private static void checkFormat(String key, String text, Object format) {
        if (format == null) {
            return;
        }
        String declared = String.valueOf(format);
        String value = text.trim();
        try {
            switch (declared) {
                case "date" -> LocalDate.parse(value);
                case "time" -> LocalTime.parse(value);
                default -> throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                        "参数 " + key + " 声明了本校验器不支持的 format：" + declared);
            }
        } catch (DateTimeParseException e) {
            // 带上原值：模型看到「收到 明天下午」才知道该改成什么形状，只说「格式不对」它会重试同一个。
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 的格式应为 " + describeFormat(declared) + "，收到 " + text);
        }
    }

    private static String describeFormat(String format) {
        return "date".equals(format) ? "YYYY-MM-DD" : "HH:mm";
    }

    /**
     * 数值参数的上下界（{@code minimum} / {@code maximum}）。
     *
     * <h2>为什么必须实现，而不是「schema 里写了 minimum 就够了」</h2>
     * 本校验器是进程内路径上<b>唯一</b>的入参检查 —— SDK 那条 {@code tools/call} 的 schema 校验
     * 根本不会执行（见类注释）。也就是说 schema 里写了 {@code minimum} 而这里不认识它，
     * 等于<b>那条约束不存在</b>：模型给一个「回看 1000 天」的时间窗，它会被原样送到服务层，
     * 最后以「查出来一大片」或一个又贵又难解释的结果收场，而不是一句「参数不合法」。
     *
     * <p>这正是类注释里那条契约要防的形状（静默忽略一个约束比直接报不支持更危险），
     * 而它与「未知 {@code type} 报 INTERNAL」不同：那个是声明写错，这个是<b>模型的输入越界</b>，
     * 所以码是 {@code INVALID_ARGUMENT}，用户看到的是一句能自己纠正的话。
     */
    private static void checkRange(String key, Number value, Map<String, Object> propertySchema) {
        double actual = value.doubleValue();
        if (propertySchema.get("minimum") instanceof Number floor && actual < floor.doubleValue()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 不能小于 " + renderBound(floor) + "，收到 " + renderBound(value));
        }
        if (propertySchema.get("maximum") instanceof Number ceiling && actual > ceiling.doubleValue()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数 " + key + " 不能大于 " + renderBound(ceiling) + "，收到 " + renderBound(value));
        }
    }

    /**
     * 把边界值渲染成整数形状（{@code 30} 而不是 {@code 30.0}）。
     *
     * <p>只是为了报错话术可读：{@code "不能大于 30.0，收到 45.0"} 不像人说的话，
     * 而这条消息会原样出现在工具结果里、并被写进审计。
     */
    private static String renderBound(Number value) {
        double number = value.doubleValue();
        return number == Math.floor(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number)
                : String.valueOf(number);
    }

    private static String describeType(String jsonType) {
        return switch (jsonType) {
            case "string" -> "文本";
            case "boolean" -> "布尔值";
            case "integer" -> "整数";
            case "array" -> "数组";
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
