package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 注册表契约：声明只有一份、名字唯一、schema 与设计文档一致、越界入参在动手前被拒。
 *
 * <p>用真的 {@link TodoItemService} + 假的 {@link TodoItemMapper}：参数校验与异常语义
 * 是被测行为的一部分，mock 掉 service 就等于把它们一并 mock 掉了。
 */
class ToolRegistryTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final TodoItemMapper mapper = mock(TodoItemMapper.class);
    private final TodoAssistantTools tools = new TodoAssistantTools(new TodoItemService(mapper));
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.todoCreateTool(), tools.todoCompleteTool(),
                    tools.todoDeleteTool(), tools.todoUpdateTool()),
            new ToolInputValidator(), new ObjectMapper());

    @Test
    void registersTheFourTodoToolsInDeclarationOrder() {
        assertThat(registry.list()).extracting(ToolDefinition::name).containsExactly(
                TodoAssistantTools.TOOL_CREATE,
                TodoAssistantTools.TOOL_COMPLETE,
                TodoAssistantTools.TOOL_DELETE,
                TodoAssistantTools.TOOL_UPDATE);
    }

    @Test
    void everyToolCarriesTitleDescriptionAndSchema() {
        for (ToolDefinition definition : registry.list()) {
            McpSchema.Tool tool = definition.tool();
            assertThat(tool.title()).as(tool.name() + " 的 title").isNotBlank();
            assertThat(tool.description()).as(tool.name() + " 的 description").isNotBlank();
            assertThat(tool.inputSchema()).as(tool.name() + " 的 schema").isNotNull();
        }
    }

    @Test
    void createSchemaMatchesTheDesignDocument() {
        Map<String, Object> schema = schema(TodoAssistantTools.TOOL_CREATE);

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("title", "date"));
        assertThat(schema.get("additionalProperties")).as("身份防线的硬机制，不是风格选项").isEqualTo(false);
        assertThat(properties(schema)).containsOnlyKeys("title", "date", "time", "note");
        assertThat(properties(schema).get("title")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("maxLength", 200);
    }

    @Test
    void completeSchemaRequiresTodoIdAndCompleted() {
        Map<String, Object> schema = schema(TodoAssistantTools.TOOL_COMPLETE);

        assertThat(schema.get("required")).isEqualTo(List.of("todoId", "completed"));
        assertThat(properties(schema)).containsOnlyKeys("todoId", "completed");
    }

    @Test
    void deleteSchemaRequiresOnlyTodoId() {
        assertThat(schema(TodoAssistantTools.TOOL_DELETE).get("required")).isEqualTo(List.of("todoId"));
    }

    @Test
    void updateSchemaRequiresTodoIdAndAtLeastOneChangeableField() {
        Map<String, Object> schema = schema(TodoAssistantTools.TOOL_UPDATE);

        assertThat(schema.get("required")).isEqualTo(List.of("todoId"));
        assertThat(properties(schema)).containsOnlyKeys("todoId", "title", "date", "time", "note");
        // 「只有 todoId」不构成一次修改：光靠 required 表达不了这件事，
        // 所以用一个自定义关键字把它写进同一份声明里（提示词与校验共用）。
        assertThat(schema.get(ToolInputValidator.REQUIRES_AT_LEAST_ONE_OF))
                .isEqualTo(List.of("title", "date", "time", "note"));
    }

    @Test
    void updateWithOnlyTheTodoIdIsRejectedBeforeItBecomesAConfirmationCard() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", "33333333-3333-4333-8333-333333333333");

        ToolResult result = registry.invoke(TodoAssistantTools.TOOL_UPDATE, USER, arguments);

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("至少要给出一个要修改的字段");
        verifyNoInteractions(mapper);
    }

    /**
     * 这条是安全断言，不是风格检查：schema 里一旦出现身份字段，模型就有了指定身份的表达方式，
     * 整条授权链就从「服务端按认证上下文决定」变成「模型说了算」。
     */
    @Test
    void noToolSchemaExposesAnyIdentityField() {
        List<String> identityFields = List.of(
                "userId", "user_id", "tenantId", "tenant_id", "ownerId", "owner_id",
                "installationId", "authCorpId", "openId");

        for (ToolDefinition definition : registry.list()) {
            assertThat(properties(definition.tool().inputSchema()))
                    .doesNotContainKeys(identityFields.toArray(new String[0]));
        }
    }

    @Test
    void identityFieldInArgumentsIsRejectedBeforeAnythingIsWritten() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", "和张总确认报价");
        arguments.put("date", "2026-09-22");
        arguments.put("userId", "22222222-2222-2222-2222-222222222222");

        ToolResult result = registry.invoke(TodoAssistantTools.TOOL_CREATE, USER, arguments);

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("userId");
        verifyNoInteractions(mapper);
    }

    @Test
    void unknownToolNameIsRejectedWithoutTouchingStorage() {
        ToolResult result = registry.invoke("todo.archiveEverything", USER, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.UNKNOWN_TOOL);
        verifyNoInteractions(mapper);
    }

    @Test
    void missingRequiredArgumentIsReportedAsSuch() {
        ToolResult result = registry.invoke(TodoAssistantTools.TOOL_CREATE, USER, Map.of("title", "只有标题"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.MISSING_ARGUMENT);
        assertThat(result.message()).contains("date");
        verifyNoInteractions(mapper);
    }

    @Test
    void nullUserIdIsARefusalNotAnErrorResult() {
        // 身份缺失不能被包装成"这个动作执行失败了" —— 那会让模型看到失败后换个说法重试，
        // 掩盖掉真正的问题（这个请求没有认证身份）。必须是抛，由上层变 401。
        assertThatThrownBy(() -> registry.invoke(TodoAssistantTools.TOOL_CREATE, null, Map.of("title", "x", "date", "2026-09-22")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少当前用户");
    }

    @Test
    void renderForPromptCarriesNamesSchemasAndNormalisedAnnotations() {
        String catalogue = registry.renderForPrompt();

        assertThat(catalogue)
                .contains("todo.create").contains("todo.complete")
                .contains("todo.delete").contains("todo.update")
                .contains("inputSchema").contains("additionalProperties")
                .contains("destructiveHint").contains("readOnlyHint");
        assertThat(catalogue).doesNotContain("null");
    }

    @Test
    void rejectsDeclarationWithoutAdditionalPropertiesFalse() {
        ToolDefinition unsafe = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("todo.unsafe")
                        .title("不安全的声明")
                        .description("故意漏掉 additionalProperties: false")
                        .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(unsafe), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("additionalProperties");
    }

    /**
     * 「一组引用」忘记绑定候选，也必须起不来 —— 这是把复数后缀加进命名约定的<b>全部意义</b>。
     *
     * <p>改之前 {@code isReferenceShaped} 只看 {@code *Id} / {@code *Ref}，于是
     * {@code topicRefs} 这类参数<b>连这道自检都不触发</b>，而它恰恰是最需要绑定的形状：
     * 解析器对数组值是逐元素比对候选的，声明一旦漏掉，那层防护就一点都不存在
     * （单值那路至少还有 {@code *Id} 这条命名约定兜着）。
     */
    @Test
    void rejectsUnboundPluralReferenceField() {
        ToolDefinition unbound = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("contact.topics_merge")
                        .title("未绑定的合并声明")
                        .description("topicRefs 以 Refs 结尾，却没有声明候选集")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("topicRefs", Map.of("type", "array",
                                        "items", Map.of("type", "string"))),
                                "required", List.of("topicRefs"),
                                "additionalProperties", false))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(unbound), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topicRefs")
                .hasMessageContaining(ToolInputValidator.CANDIDATE_SET);
    }

    @Test
    void rejectsDuplicateToolNames() {        List<ToolDefinition> duplicated = List.of(
                tools.todoCreateTool(), tools.todoCreateTool());

        assertThatThrownBy(() -> new ToolRegistry(duplicated, new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名重复");
    }

    @Test
    void rejectsEmptyRegistryInsteadOfSilentlyDoingNothing() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具注册表为空");
    }

    @Test
    void rejectsRequiredFieldThatIsNotDeclaredInProperties() {
        ToolDefinition broken = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("todo.broken")
                        .title("坏的声明")
                        .description("required 里有一个没声明的字段")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("title", Map.of("type", "string")),
                                "required", List.of("title", "date"),
                                "additionalProperties", false))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(broken), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("date");
    }

    /**
     * 声明了校验器不认识的关键字，必须起不来 —— 这是这份白名单的<b>全部意义</b>。
     *
     * <p>没有这道门禁时，{@code pattern} 这种写法既不报错也不生效：写它的人以为约束在、
     * 读代码的人以为约束在，而模型可以随便越界。这类缺失在运行期和普通单测里都看不出来
     * （工具照常工作），所以宁可起不来。同 {@code rejectsUnboundPluralReferenceField} 的理由。
     */
    @Test
    void rejectsPropertyKeywordThatTheValidatorWouldSilentlyIgnore() {
        ToolDefinition withPattern = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("todo.withPattern")
                        .title("写了却不生效的约束")
                        .description("pattern 不在校验器的支持清单里")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("title", Map.of("type", "string", "pattern", "\\S+")),
                                "required", List.of("title"),
                                "additionalProperties", false))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(withPattern), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pattern")
                .hasMessageContaining("不支持的关键字");
    }

    /**
     * {@code format} 的<b>取值</b>也要在启动期拦：key 认得、值不认得时，约束同样等于不存在。
     *
     * <p>只做 key 白名单会漏掉这一半 —— {@code format} 被放行了，于是 {@code format: "email"}
     * 看起来完全合法，而它和「写了个 {@code pattern}」是同一个错误。
     */
    @Test
    void rejectsFormatValueTheValidatorDoesNotImplement() {
        ToolDefinition withEmailFormat = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("contact.withEmailFormat")
                        .title("不支持的 format 取值")
                        .description("校验器只实现了 date / time")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("email", Map.of("type", "string", "format", "email")),
                                "required", List.of("email"),
                                "additionalProperties", false))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(withEmailFormat), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format")
                .hasMessageContaining("email");
    }

    /**
     * 数组元素 schema 内要单独再查一次：{@code items} 支持的关键字集合比字段级更小。
     *
     * <p>漏掉这次检查的形状很具体 —— {@code pattern} 写在 {@code items} 里，
     * 外层字段的 key 集合看起来完全正常，于是元素级那条假保证一路通过启动。
     */
    @Test
    void rejectsUnsupportedKeywordInsideArrayItems() {
        ToolDefinition badItems = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name("contact.withBadTags")
                        .title("元素级声明写错")
                        .description("items 里用了元素级不支持的 pattern")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("tags", Map.of(
                                        "type", "array",
                                        "minItems", 1,
                                        "items", Map.of("type", "string", "pattern", "\\S+"))),
                                "required", List.of("tags"),
                                "additionalProperties", false))
                        .build(),
                (userId, arguments) -> ToolResult.ok("never", Map.of()));

        assertThatThrownBy(() -> new ToolRegistry(List.of(badItems), new ToolInputValidator(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("properties.tags.items")
                .hasMessageContaining("pattern");
    }

    /**
     * 真实的 todo 声明必须过得了这道新门禁 —— 它们正是触发这次改动的样本：
     * {@code date} / {@code time} 从第一天起就写着 {@code format}，而校验器当时不认这个关键字。
     *
     * <p>这条断言防的是「门禁上线把既有工具一起拦死」。放行 {@code format} 的前提是校验器
     * 真的实现了它（{@code ToolInputValidatorTest} 里有对应的 date/time 用例），
     * 而不是把白名单放宽成「什么都放」—— 后者会让这道门禁变成纯装饰。
     */
    @Test
    void theRealTodoDeclarationsPassTheKeywordGate() {
        // registry 字段本身就是这四个工具构成的注册表：构造没抛异常 = 门禁放行了它们。
        assertThat(registry.find(TodoAssistantTools.TOOL_CREATE)).isPresent();
        assertThat(registry.find(TodoAssistantTools.TOOL_UPDATE)).isPresent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private Map<String, Object> schema(String toolName) {
        return registry.find(toolName).orElseThrow().tool().inputSchema();
    }
}
