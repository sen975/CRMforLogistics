package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 4 个待办动作的行为与越权边界。
 *
 * <p>装配的是真 {@link TodoItemService} + 假 {@link TodoItemMapper}：这样参数校验、日期解析、
 * 归属判定走的都是生产代码，只有存储是假的。反过来 mock 掉 service 就等于把这些行为一起
 * mock 掉，测试会全绿而线上照旧出错。
 *
 * <p><b>越权用例都同时断言「没写库」</b>：只断言返回了错误码是不够的 ——
 * 一个先写库再报错的实现同样能让错误码断言通过。
 */
class TodoAssistantToolsTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TODO_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final TodoItemMapper mapper = mock(TodoItemMapper.class);
    private final TodoAssistantTools tools = new TodoAssistantTools(new TodoItemService(mapper));
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.todoCreateTool(), tools.todoCompleteTool(),
                    tools.todoDeleteTool(), tools.todoUpdateTool()),
            new ToolInputValidator(), new ObjectMapper());

    // ---------- todo.create ----------

    @Test
    void createNormalisesAndReturnsTheNewTodo() {
        when(mapper.insert(any())).thenReturn(1);

        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "  和张总确认报价  ", "date", "2026-09-22", "time", "15:00"));

        assertThat(result.isError()).isFalse();
        assertThat(result.data())
                .containsEntry("title", "和张总确认报价")
                .containsEntry("date", "2026-09-22")
                .containsEntry("time", "15:00")
                .containsEntry("completed", false);
        assertThat(result.data().get("todoId")).isNotNull();
        assertThat(result.message()).contains("已创建待办").contains("和张总确认报价");
    }

    @Test
    void createWithoutTimeLeavesTimeNull() {
        when(mapper.insert(any())).thenReturn(1);

        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "整理上周报价单", "date", "2026-09-23"));

        assertThat(result.isError()).isFalse();
        assertThat(result.data()).containsEntry("time", null);
    }

    @Test
    void createRejectsBlankTitleWithoutTouchingStorage() {
        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "   ", "date", "2026-09-22"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(mapper);
    }

    @Test
    void createRejectsOverlongTitleAtSchemaLevel() {
        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "标".repeat(201), "date", "2026-09-22"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("200");
        verifyNoInteractions(mapper);
    }

    @Test
    void createRejectsUnparseableDateInsteadOfGuessing() {
        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "和张总确认报价", "date", "下周三"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("下周三");
        verifyNoInteractions(mapper);
    }

    @Test
    void createRejectsUnparseableTime() {
        ToolResult result = call(TodoAssistantTools.TOOL_CREATE, Map.of(
                "title", "和张总确认报价", "date", "2026-09-22", "time", "下午三点"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(mapper);
    }

    // ---------- todo.complete ----------

    @Test
    void completeMarksTheTodoAndReportsItsTitle() {
        when(mapper.findById(TODO_ID, USER))
                .thenReturn(todo(TODO_ID, USER, "和张总确认报价", LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), false));
        when(mapper.setCompleted(TODO_ID, USER, true)).thenReturn(1);

        ToolResult result = call(TodoAssistantTools.TOOL_COMPLETE, Map.of(
                "todoId", TODO_ID.toString(), "completed", true));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已标记完成").contains("和张总确认报价");
        assertThat(result.data()).containsEntry("completed", true);
        verify(mapper).setCompleted(TODO_ID, USER, true);
    }

    @Test
    void completeCanAlsoMarkATodoBackAsUnfinished() {
        when(mapper.findById(TODO_ID, USER))
                .thenReturn(todo(TODO_ID, USER, "和张总确认报价", LocalDate.of(2026, 9, 22), null, true));
        when(mapper.setCompleted(TODO_ID, USER, false)).thenReturn(1);

        ToolResult result = call(TodoAssistantTools.TOOL_COMPLETE, Map.of(
                "todoId", TODO_ID.toString(), "completed", false));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已标回未完成");
        assertThat(result.data()).containsEntry("completed", false);
    }

    /** 别人的待办：必须报「不存在」，且一个字都不能写。 */
    @Test
    void completeRejectsSomeoneElsesTodoWithoutWriting() {
        when(mapper.findById(TODO_ID, USER)).thenReturn(null);

        ToolResult result = call(TodoAssistantTools.TOOL_COMPLETE, Map.of(
                "todoId", TODO_ID.toString(), "completed", true));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
        assertThat(result.message()).as("不区分「不存在」与「不是你的」，避免变成探测信道").isEqualTo("待办不存在");
        verify(mapper, never()).setCompleted(any(), any(), anyBoolean());
    }

    @Test
    void completeRejectsMalformedTodoId() {
        ToolResult result = call(TodoAssistantTools.TOOL_COMPLETE, Map.of(
                "todoId", "第三条那个", "completed", true));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(mapper);
    }

    // ---------- todo.delete ----------

    @Test
    void deleteRemovesTheTodoAndReportsItsTitle() {
        when(mapper.findById(TODO_ID, USER))
                .thenReturn(todo(TODO_ID, USER, "和张总确认报价", LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), false));
        when(mapper.delete(TODO_ID, USER)).thenReturn(1);

        ToolResult result = call(TodoAssistantTools.TOOL_DELETE, Map.of("todoId", TODO_ID.toString()));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已删除待办").contains("和张总确认报价");
        verify(mapper).delete(TODO_ID, USER);
    }

    @Test
    void deleteRejectsSomeoneElsesTodoWithoutWriting() {
        when(mapper.findById(TODO_ID, USER)).thenReturn(null);

        ToolResult result = call(TodoAssistantTools.TOOL_DELETE, Map.of("todoId", TODO_ID.toString()));

        assertThat(result.code()).isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
        verify(mapper, never()).delete(any(), any());
    }

    // ---------- todo.update ----------

    @Test
    void updateChangesOnlyTheGivenFields() {
        // findById 只被调用一次：service.update 写完后的回读。
        TodoItemEntity after = todo(TODO_ID, USER, "和张总确认报价", LocalDate.of(2026, 9, 23), LocalTime.of(9, 30), false);
        when(mapper.findById(TODO_ID, USER)).thenReturn(after);
        when(mapper.update(eq(TODO_ID), eq(USER), isNull(), eq(LocalDate.of(2026, 9, 23)),
                eq(LocalTime.of(9, 30)), isNull())).thenReturn(1);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", TODO_ID.toString());
        arguments.put("date", "2026-09-23");
        arguments.put("time", "09:30");

        ToolResult result = call(TodoAssistantTools.TOOL_UPDATE, arguments);

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("已修改待办");
        assertThat(result.data()).containsEntry("date", "2026-09-23").containsEntry("time", "09:30");
        verify(mapper).update(TODO_ID, USER, null, LocalDate.of(2026, 9, 23), LocalTime.of(9, 30), null);
    }

    @Test
    void updateWithNoActualChangeIsRejected() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", TODO_ID.toString());

        ToolResult result = call(TodoAssistantTools.TOOL_UPDATE, arguments);

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        // 文案由入参校验器给出（schema 的 x-requiresAtLeastOneOf），不再是 service 的那句：
        // 约束前移到 schema 之后，这个调用在进入 handler 之前就被拒了 ——
        // 因此下面断言的「一个字都没写」是必然的，而不是靠 handler 自觉。
        assertThat(result.message()).contains("至少要给出一个要修改的字段");
        verifyNoInteractions(mapper);
    }

    /**
     * 越权由 SQL 的 {@code where user_id} 挡下（影响 0 行），所以这里断言的是「结果为不存在」，
     * 而不是「没调用 update」—— 真正「数据没变」的验证在 {@code TodoItemMapperSqlTest} 里对真库做，
     * 在 mock 上断言"没调用"只会把实现细节焊死（比如以后改成先查后写就会假失败）。
     */
    @Test
    void updateRejectsSomeoneElsesTodo() {
        when(mapper.update(eq(TODO_ID), eq(USER), isNull(), eq(LocalDate.of(2026, 9, 23)), isNull(), isNull()))
                .thenReturn(0);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", TODO_ID.toString());
        arguments.put("date", "2026-09-23");

        ToolResult result = call(TodoAssistantTools.TOOL_UPDATE, arguments);

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
        assertThat(result.data()).isEmpty();
    }

    @Test
    void updateRejectsBlankTitleInsteadOfSilentlyIgnoringIt() {
        // 「只设置、不置空」不提供清空语义。若把空串当成"不改"，就会产出最糟的结果：
        // 模型回话"已改好"，而数据一动没动。
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", TODO_ID.toString());
        arguments.put("title", "   ");

        ToolResult result = call(TodoAssistantTools.TOOL_UPDATE, arguments);

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(mapper);
    }

    // ---------- 身份铁律 ----------

    @Test
    void theSameTodoIdOwnedByAnotherUserIsAlwaysNotFound() {
        // 同一个 id，换成"属于别人"的存储语义（findById 限定 user_id 所以返回空）。
        when(mapper.findById(eq(TODO_ID), eq(OTHER_USER))).thenReturn(
                todo(TODO_ID, OTHER_USER, "别人的待办", LocalDate.of(2026, 9, 22), null, false));
        when(mapper.findById(eq(TODO_ID), eq(USER))).thenReturn(null);

        assertThat(call(TodoAssistantTools.TOOL_DELETE, Map.of("todoId", TODO_ID.toString())).code())
                .isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
        assertThat(call(TodoAssistantTools.TOOL_COMPLETE,
                Map.of("todoId", TODO_ID.toString(), "completed", true)).code())
                .isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
    }

    private ToolResult call(String name, Map<String, Object> arguments) {
        return registry.invoke(name, USER, arguments);
    }

    private static TodoItemEntity todo(UUID id, UUID userId, String title,
                                       LocalDate date, LocalTime time, boolean completed) {
        TodoItemEntity item = new TodoItemEntity();
        item.setId(id);
        item.setUserId(userId);
        item.setTitle(title);
        item.setDueDate(date);
        item.setDueTime(time);
        item.setCompleted(completed);
        return item;
    }
}
