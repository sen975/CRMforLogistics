package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作策略：白名单持有权威，注解只作声明（设计文档 §9）。
 *
 * <p>两个方向都要验：白名单外的动作**必须**要确认（防止删除被静默执行）；
 * 白名单内但注解说「破坏性」的**也必须**要确认（fail-closed —— 任何情况下都不会因为
 * 服务端注解说「无害」就静默执行）。
 */
class AssistantActionPolicyTest {

    private final AssistantActionPolicy policy = new AssistantActionPolicy();

    private final TodoAssistantTools tools =
            new TodoAssistantTools(new com.crmforlogistics.messagecenter.service.todo.TodoItemService(
                    org.mockito.Mockito.mock(com.crmforlogistics.messagecenter.mapper.TodoItemMapper.class)));

    @Test
    void createIsTheOnlyAutoExecutedAction() {
        assertThat(AssistantActionPolicy.AUTO_EXECUTE_ALLOWLIST).containsExactly(TodoAssistantTools.TOOL_CREATE);
    }

    @Test
    void createRunsWithoutConfirmation() {
        assertThat(policy.decide(tools.todoCreateTool())).isEqualTo(AssistantActionPolicy.Decision.AUTO);
    }

    @Test
    void completeDeleteAndUpdateAllRequireConfirmation() {
        assertThat(policy.decide(tools.todoCompleteTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(tools.todoDeleteTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(tools.todoUpdateTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void aDestructiveToolOutsideTheAllowlistNeverRunsAutomaticallyEvenIfDeclaredHarmless() {
        assertThat(policy.decide(tool("todo.archive", false, false)))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    /** 配置错误：白名单内却被声明为破坏性 → 按更保守的一边（仍要确认）。 */
    @Test
    void anAllowlistedButDestructiveToolFallsBackToConfirmation() {
        ToolDefinition contradictory = tool(TodoAssistantTools.TOOL_CREATE, true, false);

        assertThat(policy.decide(contradictory)).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    /**
     * 注解缺省的保守解读：规范里未声明 {@code destructiveHint} 按 {@code true} 算。
     * 若这里按 Java 的 {@code boolean} 默认值（false）去判，一个忘了写注解的删除动作
     * 就会变成免确认执行 —— 恰好把「未知」报成「安全」。
     */
    @Test
    void missingAnnotationsAreInterpretedAsDestructiveNotAsSafe() {
        ToolDefinition withoutAnnotations = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TodoAssistantTools.TOOL_CREATE)
                        .title("新建待办")
                        .description("描述")
                        .inputSchema(objectSchema())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));

        assertThat(AssistantActionPolicy.declaredDestructive(withoutAnnotations)).isTrue();
        assertThat(policy.decide(withoutAnnotations)).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void declaredDestructiveReadsTheExplicitHintWhenPresent() {
        assertThat(AssistantActionPolicy.declaredDestructive(tool("todo.create", false, false))).isFalse();
        assertThat(AssistantActionPolicy.declaredDestructive(tool("todo.create", true, false))).isTrue();
    }

    // ---------- 夹具 ----------

    private static ToolDefinition tool(String name, boolean destructive, boolean readOnly) {
        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(name)
                        .title(name)
                        .description(name + " 的描述")
                        .inputSchema(objectSchema())
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(readOnly)
                                .destructiveHint(destructive)
                                .idempotentHint(true)
                                .openWorldHint(false)
                                .build())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));
    }

    private static Map<String, Object> objectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("todoId", Map.of("type", "string")));
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }
}
