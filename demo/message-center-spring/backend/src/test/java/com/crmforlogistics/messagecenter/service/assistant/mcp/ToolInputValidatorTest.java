package com.crmforlogistics.messagecenter.service.assistant.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入参校验器：它<b>不是</b>通用 JSON Schema 实现，只覆盖注册表实际用到的关键字。
 *
 * <h2>为什么数组这一组用例是必要的</h2>
 * 这个类的既定契约是「新增关键字必须同步扩展这里并补测试 —— 静默忽略一个约束比直接报不支持更危险」。
 * {@code array} 是后加的（联系人标签需要"把一组自由文本交给服务端"），而它有两种极易写错的失败：
 *
 * <ol>
 *   <li><b>只判外层是 List</b>：那样 {@code [{"name":"x"}]} 也会通过，然后一路走到服务层的
 *       {@code List<String>} 签名上，变成一个与用户输入无关的 500。</li>
 *   <li><b>外层 {@code maxLength} 冒充元素上限</b>：数组没有"长度"，真正需要设限的是<b>元素</b>，
 *       而元素是自由文本 —— 不设限就等于把写进库里的长度交给模型决定。</li>
 * </ol>
 *
 * <p>两种情况都不会让任何东西报错，只会让约束不存在。所以这里逐条钉住。
 */
class ToolInputValidatorTest {

    private final ToolInputValidator validator = new ToolInputValidator();
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void anArrayOfStringsIsAccepted() {
        ToolDefinition definition = toolWithTags(3, 10);

        assertThatCode(() -> validator.validate(definition, Map.of("tags", List.of("潜在客户", "华东"))))
                .doesNotThrowAnyException();
    }

    /** 元素类型不对：外层是 List，但里面不是文本。必须在校验层挡住，不能留给服务层扔 ClassCastException。 */
    @Test
    void anArrayWhoseElementsAreNotStringsIsRejected() {
        ToolDefinition definition = toolWithTags(3, 10);

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of(Map.of("name", "x")))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("每一项都应为文本");
    }

    @Test
    void anArrayWithTooManyItemsIsRejected() {
        ToolDefinition definition = toolWithTags(2, 10);

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of("a", "b", "c"))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("最多 2 项");
    }

    /** 元素的长度上限走 {@code items.maxLength}，不是外层的 {@code maxLength}。 */
    @Test
    void theLengthLimitAppliesToEachElement() {
        ToolDefinition definition = toolWithTags(5, 4);

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of("ok", "这个标签太长了"))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能超过 4 个字符");
    }

    /** 没有 {@code items} 的数组等于"元素是什么都行"，属声明写错 —— 报 INTERNAL 而不是放过。 */
    @Test
    void anArrayWithoutItemsIsADeclarationError() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("type", "array");
        tags.put("description", "标签");
        properties.put("tags", tags);
        ToolDefinition definition = definition(objectSchema(properties), "tags");

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of("a"))))
                .isInstanceOf(ToolExecutionException.class)
                .satisfies(e -> assertThatCode(() -> {
                    if (!ToolExecutionException.INTERNAL.equals(((ToolExecutionException) e).code())) {
                        throw new AssertionError("应报 INTERNAL");
                    }
                }).doesNotThrowAnyException())
                .hasMessageContaining("没有声明 items");
    }

    /** {@code items.type=object} 明确不支持：半支持的声明比不支持的更危险。 */
    @Test
    void objectElementsAreDeclaredUnsupported() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("type", "array");
        tags.put("description", "标签");
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "object");
        tags.put("items", items);
        properties.put("tags", tags);
        ToolDefinition definition = definition(objectSchema(properties), "tags");

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of("a"))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("只支持 string");
    }

    /**
     * 标量路径没有被这次改动带坏：字符串超长仍然照旧被挡。
     *
     * <p>「恰好等于上限」单列一条断言：{@code maxLength} 是「不超过」而不是「小于」，
     * 边界写错的后果是可用长度比声明少一个字，而那种 bug 只在有人输入到边界时才现形 ——
     * 恰好在限上的值被拒等于把声明的额度悄悄缩减，所以这里正反两面都钉住。
     */
    @Test
    void scalarStringLengthIsStillEnforced() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> remark = new LinkedHashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        remark.put("maxLength", 3);
        properties.put("remark", remark);
        ToolDefinition definition = definition(objectSchema(properties), "remark");

        assertThatThrownBy(() -> validator.validate(definition, Map.of("remark", "太长了啊")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能超过 3 个字符")
                .hasMessageContaining("收到 4 个");

        assertThatCode(() -> validator.validate(definition, Map.of("remark", "太长了")))
                .doesNotThrowAnyException();
    }

    /**
     * {@code minItems} 必须真的被执行。
     *
     * <p>它是后加的关键字（「合并话题至少要两条」），而这类新增约束有一个共同的坑：
     * 这个校验器<b>不扫未知关键字</b> —— 声明里写一个它不认的约束既不会报错也不会生效，
     * 于是声明看起来有约束、实际上没有。所以这里正反两面都钉：低于下限必拒、恰好等于下限必放。
     */
    @Test
    void tooFewItemsIsRejected() {
        ToolDefinition definition = toolWithTagsBetween(2, 5, 10);

        assertThatThrownBy(() -> validator.validate(definition, Map.of("tags", List.of("潜在客户"))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("至少 2 项")
                .hasMessageContaining("收到 1 项");

        assertThatCode(() -> validator.validate(definition, Map.of("tags", List.of("潜在客户", "华东"))))
                .doesNotThrowAnyException();
    }

    /**
     * {@code minimum} / {@code maximum} 必须真的被执行。
     *
     * <p>这一组存在的理由与 {@code minItems} 那条完全相同，而且更尖锐：它们是 2026-09-23
     * 第一次被写进 schema（{@code wecom.summary_read} 的「回看多少天」）时才补进校验器的。
     * 补之前，一个写着 {@code maximum: 30} 的声明<b>等于没有约束</b> ——
     * 而这个校验器不扫未知关键字，所以那件事既不会报错、也不会被任何测试发现，
     * 表现是模型给个 1000 天的时间窗就真的去查 1000 天。
     *
     * <p>正反两面都钉：越界必拒、恰好等于边界必放。边界写成「小于」而不是「不超过」
     * 会让声明的额度悄悄缩水一格，而那种 bug 只在有人正好用到边界时才现形。
     */
    @Test
    void anIntegerOutsideTheDeclaredRangeIsRejected() {
        ToolDefinition definition = toolWithDays(1, 30);

        assertThatThrownBy(() -> validator.validate(definition, Map.of("days", 31)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能大于 30")
                .as("报错话术里不该出现 30.0 这种形状：它会原样进工具结果并被写进审计")
                .hasMessageContaining("收到 31");
        assertThatThrownBy(() -> validator.validate(definition, Map.of("days", 0)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能小于 1");

        assertThatCode(() -> validator.validate(definition, Map.of("days", 1))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(definition, Map.of("days", 30))).doesNotThrowAnyException();
    }

    /** 没写上下界时不该凭空产生约束（少写一个关键字与写错一个关键字是两回事）。 */
    @Test
    void anIntegerWithoutDeclaredBoundsIsAcceptedAsIs() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> days = new LinkedHashMap<>();
        days.put("type", "integer");
        days.put("description", "天数");
        properties.put("days", days);

        assertThatCode(() -> validator.validate(definition(objectSchema(properties), "days"),
                Map.of("days", 9999))).doesNotThrowAnyException();
    }

    // ---------- format（date / time）----------

    /**
     * {@code format} 必须真的拦住不合规的值 —— 它在此之前<b>只是装饰</b>。
     *
     * <p>{@code todo.create} 的 {@code date} / {@code time} 从第一天起就声明着 {@code format}，
     * 而校验器当时不认这个关键字，那两条声明的实际效果是零（真正拦住越界值的是服务层的
     * {@code LocalDate.parse}）。这条测试把「声明」与「生效」重新绑在一起。
     */
    @Test
    void anIsoDateIsEnforcedNotJustDeclared() {
        ToolDefinition definition = toolWithFormat("date", "date");

        assertThatThrownBy(() -> validator.validate(definition, Map.of("date", "明天下午")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("YYYY-MM-DD")
                .as("报错要带上原值：只说「格式不对」，模型会拿同一个值再试一次")
                .hasMessageContaining("明天下午");
        assertThatThrownBy(() -> validator.validate(definition, Map.of("date", "2026-13-45")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("YYYY-MM-DD");

        assertThatCode(() -> validator.validate(definition, Map.of("date", "2026-09-23")))
                .doesNotThrowAnyException();
    }

    /**
     * 格式判断取<b>宽松侧</b>：先 {@code trim} 再解析。
     *
     * <p>两边代价不对称 —— 校验层漏一个「带空格但合法」的值，最坏结果只是它在服务层被
     * 同一个解析器拒掉（错误码一样，话术不同）；而校验层多拒一个服务层本来接受的值，
     * 就是「原来能过的输入现在过不了」，那等于在校验层偷偷改业务规则。
     * 所以这里 {@code trim}，与 {@code TodoItemService.parseTime} 的判据一致。
     */
    @Test
    void theDateFormatCheckTrimsBeforeParsing() {
        assertThatCode(() -> validator.validate(toolWithFormat("date", "date"), Map.of("date", "  2026-09-23  ")))
                .doesNotThrowAnyException();
    }

    @Test
    void anIsoTimeIsEnforcedNotJustDeclared() {
        ToolDefinition definition = toolWithFormat("time", "time");

        assertThatThrownBy(() -> validator.validate(definition, Map.of("time", "25:00")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> validator.validate(definition, Map.of("time", "9:00")))
                .as("HH:mm 要求两位小时 —— 与服务层 LocalTime.parse 同一判据")
                .isInstanceOf(ToolExecutionException.class);

        assertThatCode(() -> validator.validate(definition, Map.of("time", "09:00"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(definition, Map.of("time", "23:59"))).doesNotThrowAnyException();
    }

    /** 没声明 {@code format} 的字符串不该凭空产生格式约束（少写一个关键字 ≠ 写错一个关键字）。 */
    @Test
    void aStringWithoutDeclaredFormatIsAcceptedAsIs() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "string");
        note.put("description", "补充说明");
        properties.put("note", note);

        assertThatCode(() -> validator.validate(definition(objectSchema(properties), "note"),
                Map.of("note", "随便什么，明天下午也行"))).doesNotThrowAnyException();
    }

    // ---------- 夹具 ----------

    private static ToolDefinition toolWithFormat(String field, String format) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "string");
        value.put("description", "日期或时间");
        value.put("format", format);
        properties.put(field, value);
        return definition(objectSchema(properties), field);
    }

    private static ToolDefinition toolWithDays(int minimum, int maximum) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> days = new LinkedHashMap<>();
        days.put("type", "integer");
        days.put("description", "天数");
        days.put("minimum", minimum);
        days.put("maximum", maximum);
        properties.put("days", days);
        return definition(objectSchema(properties), "days");
    }

    private static ToolDefinition toolWithTagsBetween(int minItems, int maxItems, int itemMaxLength) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("type", "array");
        tags.put("description", "标签");
        tags.put("minItems", minItems);
        tags.put("maxItems", maxItems);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("maxLength", itemMaxLength);
        tags.put("items", items);
        properties.put("tags", tags);
        return definition(objectSchema(properties), "tags");
    }

    private static ToolDefinition toolWithTags(int maxItems, int itemMaxLength) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("type", "array");
        tags.put("description", "标签");
        tags.put("maxItems", maxItems);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("maxLength", itemMaxLength);
        tags.put("items", items);
        properties.put("tags", tags);
        return definition(objectSchema(properties), "tags");
    }

    private static ToolDefinition definition(Map<String, Object> schema, String requiredField) {
        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("test.tool")
                        .title("测试工具")
                        .description("描述")
                        .inputSchema(schema)
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(true)
                                .destructiveHint(false)
                                .idempotentHint(true)
                                .openWorldHint(false)
                                .build())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }
}
