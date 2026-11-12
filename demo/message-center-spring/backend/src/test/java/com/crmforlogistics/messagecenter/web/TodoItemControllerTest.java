package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 待办既有 HTTP 接口的回归保护。
 *
 * <p>写这个类的直接原因：阶段 1 把 {@code TodoItemService.setCompleted} / {@code delete} 抛的
 * {@code IllegalArgumentException} 换成了它的子类 {@code TodoItemNotFoundException}
 * （为了让工具层能区分「待办不存在」与「参数不合法」）。虽然子类在 Java 里对所有既有调用方
 * 都兼容，但"兼容"是推理出来的，不是验出来的 —— 而这个类原来<b>没有任何测试</b>，
 * 所以这里补上，把"既有行为不变"变成可执行的断言。
 *
 * <p>另外三条断言锁定的是「身份只能来自认证上下文」：每个端点都必须把
 * {@code SecurityUtil.currentUserId()} 交给 service，而不是从请求体里读任何用户字段。
 */
@WebMvcTest(TodoItemController.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoItemControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TodoItemService service;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "", List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsTheCurrentUsersTodos() throws Exception {
        when(service.list(userId)).thenReturn(List.of(
                todo(UUID.randomUUID(), LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), "和张总确认报价", false)));

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("和张总确认报价"))
                .andExpect(jsonPath("$[0].date").value("2026-09-22"))
                .andExpect(jsonPath("$[0].time").value("15:00"))
                .andExpect(jsonPath("$[0].completed").value(false));

        verify(service).list(userId);
    }

    @Test
    void listSerialisesAMissingTimeAsNullRatherThanFailing() throws Exception {
        when(service.list(userId)).thenReturn(List.of(
                todo(UUID.randomUUID(), LocalDate.of(2026, 9, 22), null, "没有时间", false)));

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].time").doesNotExist());
    }

    @Test
    void createPassesTheCurrentUserAndEchoesTheCreatedTodo() throws Exception {
        TodoItemEntity created = todo(UUID.randomUUID(), LocalDate.of(2026, 9, 22), LocalTime.of(15, 0),
                "和张总确认报价", false);
        when(service.create(eq(userId), eq("2026-09-22"), eq("和张总确认报价"), eq("15:00"), isNull()))
                .thenReturn(created);

        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-22\",\"title\":\"和张总确认报价\",\"time\":\"15:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.title").value("和张总确认报价"));
    }

    @Test
    void createIgnoresAnyUserIdSuppliedInTheBody() throws Exception {
        when(service.create(any(), any(), any(), any(), any()))
                .thenReturn(todo(UUID.randomUUID(), LocalDate.of(2026, 9, 22), null, "张总", false));

        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-22\",\"title\":\"和张总确认报价\","
                                + "\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated());

        verify(service).create(eq(userId), eq("2026-09-22"), eq("和张总确认报价"), isNull(), isNull());
    }

    @Test
    void completeMarksTheTodoOfTheCurrentUser() throws Exception {
        UUID todoId = UUID.randomUUID();

        mvc.perform(patch("/api/todos/{id}", todoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNoContent());

        verify(service).setCompleted(userId, todoId, true);
    }

    @Test
    void completeCanAlsoResetToUnfinished() throws Exception {
        UUID todoId = UUID.randomUUID();

        mvc.perform(patch("/api/todos/{id}", todoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":false}"))
                .andExpect(status().isNoContent());

        verify(service).setCompleted(userId, todoId, false);
    }

    @Test
    void deleteRemovesTheTodoOfTheCurrentUser() throws Exception {
        UUID todoId = UUID.randomUUID();

        mvc.perform(delete("/api/todos/{id}", todoId))
                .andExpect(status().isNoContent());

        verify(service).delete(userId, todoId);
    }

    private static TodoItemEntity todo(UUID id, LocalDate date, LocalTime time, String title, boolean completed) {
        TodoItemEntity item = new TodoItemEntity();
        item.setId(id);
        item.setUserId(UUID.randomUUID());
        item.setDueDate(date);
        item.setDueTime(time);
        item.setTitle(title);
        item.setCompleted(completed);
        item.setCreatedAt(Instant.parse("2026-09-21T00:00:00Z"));
        return item;
    }
}
