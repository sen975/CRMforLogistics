package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进程内适配层：MCP 的 {@code tools/list} / {@code tools/call} 语义在本进程里成立，
 * 且<b>身份只从认证上下文来</b>。
 *
 * <p>最后一条是重点。这层是"模型输出"与"工具执行"之间的接缝，
 * 一旦有人为了图方便在这里加一个"从 arguments 里读 userId 兜底"的分支，
 * 整套授权就交给模型了。所以这里用一个正向用例（认证存在时用上下文里的身份）
 * 与一个反向用例（没有认证就抛，而不是退回匿名或空身份）把这个接缝钉住。
 */
class InProcessToolAdapterTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final TodoItemMapper mapper = mock(TodoItemMapper.class);
    private final TodoAssistantTools tools = new TodoAssistantTools(new TodoItemService(mapper));
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.todoCreateTool(), tools.todoCompleteTool(),
                    tools.todoDeleteTool(), tools.todoUpdateTool()),
            new ToolInputValidator(), new ObjectMapper());
    private final InProcessToolAdapter adapter = new InProcessToolAdapter(registry, new ObjectMapper());

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listToolsExposesEveryRegisteredDeclaration() {
        assertThat(adapter.listTools().tools())
                .extracting(McpSchema.Tool::name)
                .containsExactly(TodoAssistantTools.TOOL_CREATE, TodoAssistantTools.TOOL_COMPLETE,
                        TodoAssistantTools.TOOL_DELETE, TodoAssistantTools.TOOL_UPDATE);
    }

    @Test
    void callToolReturnsTheStructuredResultAsJsonText() {
        when(mapper.insert(any())).thenReturn(1);

        McpSchema.CallToolResult result = adapter.callTool(
                TodoAssistantTools.TOOL_CREATE, USER, Map.of("title", "和张总确认报价", "date", "2026-09-22"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("\"ok\":true")
                .contains("todoId")
                .contains("和张总确认报价");
    }

    @Test
    void callToolReportsFailuresWithACodeAndErrorFlag() {
        McpSchema.CallToolResult result = adapter.callTool("todo.archiveEverything", USER, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains(ToolExecutionException.UNKNOWN_TOOL);
    }

    @Test
    void callToolWithoutExplicitUserTakesIdentityFromTheAuthenticationContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER.toString(), "", List.of()));
        when(mapper.insert(any())).thenReturn(1);

        McpSchema.CallToolResult result = adapter.callTool(
                TodoAssistantTools.TOOL_CREATE, Map.of("title", "和张总确认报价", "date", "2026-09-22"));

        assertThat(result.isError()).isFalse();
        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat(
                (TodoItemEntity item) -> USER.equals(item.getUserId())));
    }

    @Test
    void callToolWithoutAuthenticationRefusesInsteadOfFallingBack() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> adapter.callTool(
                TodoAssistantTools.TOOL_CREATE, Map.of("title", "和张总确认报价", "date", "2026-09-22")))
                .as("没有身份就该拒绝，不能退回匿名或空身份 —— 那是静默降级")
                .isInstanceOf(SecurityException.class);
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
