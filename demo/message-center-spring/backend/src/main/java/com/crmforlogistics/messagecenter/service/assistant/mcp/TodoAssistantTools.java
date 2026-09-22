package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.service.assistant.TodoCandidates;
import com.crmforlogistics.messagecenter.service.todo.TodoItemNotFoundException;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 首批 4 个待办能力，以统一声明的方式注册。
 *
 * <h2>声明与执行写在一起，但都不碰策略</h2>
 * 这里只管「这个动作叫什么、要什么参数、怎么执行」。<b>能不能直接执行由编排层的白名单判定</b>
 * （{@code AUTO_EXECUTE_ALLOWLIST}），不在这里、也不由 {@code annotations} 决定 ——
 * MCP 规范明确要求客户端把注解当不可信输入，而白名单是 fail-closed。
 *
 * <h2>全部经由 {@link TodoItemService}</h2>
 * 不直接碰 Mapper：越权防线（{@code where user_id = ?}）与参数校验都在 service 层，
 * 绕过它就等于在工具里重写一遍授权逻辑，那种重复迟早会分叉出错。
 *
 * <h2>注解取值（对照设计文档 §5.1）</h2>
 * {@code openWorldHint} 一律 {@code false}：这 4 个动作只碰本系统的 {@code todo_items}，
 * 不触外部世界。{@code todo.create} 是 {@code destructiveHint=false} 的唯一一个
 * （只增不改，最坏情况也多出一条可删的记录，不影响已有数据）。
 */
@Configuration(proxyBeanMethods = false)
public class TodoAssistantTools {

    public static final String TOOL_CREATE = "todo.create";
    public static final String TOOL_COMPLETE = "todo.complete";
    public static final String TOOL_DELETE = "todo.delete";
    public static final String TOOL_UPDATE = "todo.update";

    private final TodoItemService todoService;

    public TodoAssistantTools(TodoItemService todoService) {
        this.todoService = todoService;
    }

    @Bean
    public ToolDefinition todoCreateTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", spec("string", "待办内容，一句话说清要做什么", "maxLength", 200));
        properties.put("date", spec("string", "日期，格式 YYYY-MM-DD。用户没给或给不出确定日期时不要猜", "format", "date"));
        properties.put("time", spec("string", "时间，格式 HH:mm。用户没提到具体时间就不要传这个字段", "format", "time"));
        properties.put("note", spec("string", "补充说明", "maxLength", 1000));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_CREATE)
                        .title("新建待办")
                        .description("为当前登录用户新建一条待办。只有在待办内容与日期都确定时才调用；"
                                + "缺任何一个都不要调用，改为向用户追问缺失的那一项。")
                        .inputSchema(objectSchema(properties, List.of("title", "date")))
                        .annotations(annotations(false, false, false))
                        .build(),
                this::create);
    }

    @Bean
    public ToolDefinition todoCompleteTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todoId", referenceTo(TodoCandidates.NAME, "待办标识，只能取自提供的候选待办清单"));
        properties.put("completed", spec("boolean", "true 表示标记为已完成，false 表示标回未完成"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_COMPLETE)
                        .title("标记待办完成状态")
                        .description("把一条已有待办标记为已完成或未完成。todoId 只能来自候选待办清单；"
                                + "清单里找不到对应待办时不要调用，改为向用户说明没找到。")
                        .inputSchema(objectSchema(properties, List.of("todoId", "completed")))
                        .annotations(annotations(false, true, true))
                        .build(),
                this::complete);
    }

    @Bean
    public ToolDefinition todoDeleteTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todoId", referenceTo(TodoCandidates.NAME, "待办标识，只能取自提供的候选待办清单"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_DELETE)
                        .title("删除待办")
                        .description("删除一条已有待办，删除后无法恢复。todoId 只能来自候选待办清单；"
                                + "清单里找不到对应待办时不要调用，改为向用户说明没找到。")
                        .inputSchema(objectSchema(properties, List.of("todoId")))
                        .annotations(annotations(false, true, true))
                        .build(),
                this::delete);
    }

    @Bean
    public ToolDefinition todoUpdateTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todoId", referenceTo(TodoCandidates.NAME, "待办标识，只能取自提供的候选待办清单"));
        properties.put("title", spec("string", "新的待办内容", "maxLength", 200));
        properties.put("date", spec("string", "新的日期，格式 YYYY-MM-DD", "format", "date"));
        properties.put("time", spec("string", "新的时间，格式 HH:mm", "format", "time"));
        properties.put("note", spec("string", "新的补充说明", "maxLength", 1000));

        // 只给 todoId 不构成一次修改。用 schema 关键字表达而不是写进 handler 里判：
        // 提示词渲染与调用校验共用这一份声明，写在 handler 里模型就看不到这条约束
        // （它只会看到 required=[todoId]，于是「改一下那条」会被它理解成一个合法调用）。
        Map<String, Object> schema = objectSchema(properties, List.of("todoId"));
        schema.put("x-requiresAtLeastOneOf", List.of("title", "date", "time", "note"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_UPDATE)
                        .title("修改待办")
                        .description("修改一条已有待办的标题、日期、时间或备注。"
                                + "只传需要改的字段；至少要传一个。todoId 只能来自候选待办清单。")
                        .inputSchema(schema)
                        .annotations(annotations(false, true, true))
                        .build(),
                this::update);
    }

    // ---------- 执行 ----------

    private ToolResult create(UUID userId, Map<String, Object> arguments) {
        return guarded(() -> {
            TodoItemEntity created = todoService.create(
                    userId,
                    requiredText(arguments, "date"),
                    requiredText(arguments, "title"),
                    optionalText(arguments, "time"),
                    optionalText(arguments, "note"));
            return ToolResult.ok("已创建待办：" + describe(created), itemData(created));
        });
    }

    private ToolResult complete(UUID userId, Map<String, Object> arguments) {
        UUID todoId = requiredUuid(arguments, "todoId");
        boolean completed = requiredBoolean(arguments, "completed");
        return guarded(() -> {
            // 先读一次：既拿到标题用于回话，也让"这不是你的待办"在动手前就被挡下。
            TodoItemEntity target = todoService.require(userId, todoId);
            todoService.setCompleted(userId, todoId, completed);
            return ToolResult.ok(
                    (completed ? "已标记完成：" : "已标回未完成：") + target.getTitle(),
                    itemData(target, completed));
        });
    }

    private ToolResult delete(UUID userId, Map<String, Object> arguments) {
        UUID todoId = requiredUuid(arguments, "todoId");
        return guarded(() -> {
            TodoItemEntity target = todoService.require(userId, todoId);
            todoService.delete(userId, todoId);
            return ToolResult.ok("已删除待办：" + target.getTitle(), itemData(target));
        });
    }

    private ToolResult update(UUID userId, Map<String, Object> arguments) {
        UUID todoId = requiredUuid(arguments, "todoId");
        return guarded(() -> {
            // 这里刻意<b>不</b>预先 require：参数错误（如"一个字段都没给"）与目标是否存在无关，
            // 应该先报出来；反过来先查存储会让"参数写错了"被"待办不存在"盖住，用户按提示去重挑待办，
            // 而真正的问题在参数上。归属校验由 service.update 的 where user_id 兜底 —— 影响 0 行即视为不存在。
            // complete / delete 之所以仍要预读，是因为它们需要标题来回话，不是为了校验归属。
            TodoItemEntity updated = todoService.update(
                    userId,
                    todoId,
                    optionalText(arguments, "title"),
                    optionalText(arguments, "date"),
                    optionalText(arguments, "time"),
                    optionalText(arguments, "note"));
            return ToolResult.ok("已修改待办：" + describe(updated), itemData(updated));
        });
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text) {
            return text;
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

    private static UUID requiredUuid(Map<String, Object> arguments, String key) {
        String raw = requiredText(arguments, key);
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "待办标识格式不正确", e);
        }
    }

    // ---------- 结果与异常 ----------

    /**
     * 把 service 层的异常收敛成带错误码的 {@link ToolExecutionException}。
     *
     * <p>catch 顺序是有意的：{@link TodoItemNotFoundException} 是
     * {@code IllegalArgumentException} 的子类，必须排在前面，否则"待办不存在"
     * 会被误报成"参数不合法" —— 而这两者对用户的指引完全相反（一个该重挑待办，一个该改参数）。
     */
    private static ToolResult guarded(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (TodoItemNotFoundException e) {
            throw new ToolExecutionException(ToolExecutionException.TODO_NOT_FOUND, e.getMessage(), e);
        } catch (DateTimeParseException e) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "日期或时间的格式不对：" + e.getParsedString(), e);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT, e.getMessage(), e);
        }
    }

    private static Map<String, Object> itemData(TodoItemEntity item) {
        return itemData(item, item.isCompleted());
    }

    /** 序列化给上游的待办摘要；{@code time} 允许为 {@code null}（待办可以只有日期没有时间）。 */
    private static Map<String, Object> itemData(TodoItemEntity item, boolean completed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("todoId", item.getId().toString());
        data.put("title", item.getTitle());
        data.put("date", item.getDueDate().toString());
        data.put("time", item.getDueTime() == null ? null : item.getDueTime().toString());
        data.put("completed", completed);
        return data;
    }

    private static String describe(TodoItemEntity item) {
        StringBuilder text = new StringBuilder()
                .append(item.getDueDate().getMonthValue()).append('月')
                .append(item.getDueDate().getDayOfMonth()).append('日');
        if (item.getDueTime() != null) {
            text.append(' ').append(item.getDueTime());
        }
        return text.append(' ').append(item.getTitle()).toString();
    }

    // ---------- 声明构造 ----------

    /**
     * 入参 schema。
     *
     * <p>{@code additionalProperties: false} 是<b>安全配置，不是风格选项</b> ——
     * 阶段 0.1 实测：SDK 正是据此拒掉模型往 {@code arguments} 里偷塞 {@code userId} 的。
     * {@link ToolRegistry} 在启动时会强制检查这一条。
     *
     * <p>用 {@link LinkedHashMap} 而非 {@code Map.of}：前者保序，让提示词渲染与快照测试稳定。
     */
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 一个字段的 schema；{@code extra} 是成对的额外关键字（如 {@code "maxLength", 200}）。 */
    private static Map<String, Object> spec(String type, String description, Object... extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("description", description);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            field.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return field;
    }

    /**
     * 引用类字段：值必须命中 {@code candidateSet} 那一组候选。
     *
     * <p>绑定写在字段自己的 schema 上（{@code x-candidateSet}），与描述同一行 ——
     * 这样「加一个引用参数」和「声明它引用哪一组」是同一个动作。
     * 原来那份「参数名硬编码在解析器里」的清单之所以被替换掉，正是因为加第二个域时
     * 它会静默不生效：新工具换个参数名就绕过了候选比对，而没有任何东西会报错。
     */
    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = spec("string", description);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    private static McpSchema.ToolAnnotations annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(false)
                .build();
    }
}
