package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPendingActionService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantRequestGuard;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecentertest.assistant.AssistantMessageLimitTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 助手端点的 HTTP 契约。
 *
 * <p>装配真的 {@link SecurityConfig}（不是 {@code addFilters = false}）：401 这件事只能由
 * 真实的安全链来保证 —— 关掉过滤器再断言「未认证返回 401」是在测一个不存在的机制。
 *
 * <p>三种 {@code kind} 的映射都要覆盖，{@code QUESTION} 与 {@code CONFIRMATION_REQUIRED}
 * 的差异尤其重要：前者是「还缺信息」，后者是「马上要执行、但还没执行」，
 * 前端要靠它决定是否渲染确认卡片。
 */
@WebMvcTest(AssistantController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, AssistantRequestGuard.class,
        AssistantMessageLimitTestConfiguration.class})
class AssistantControllerTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PENDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;

    @MockitoBean AssistantConversationService conversations;
    @MockitoBean AssistantPendingActionService pendingActions;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/assistant/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我建个待办\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/actions/{id}/cancel", PENDING_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aQuestionMapsToTheQuestionKindWithItsMissingFields() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), eq("帮我建一个关于张总的待办")))
                .thenReturn(AssistantTurnResult.question("这条待办安排在什么时间？", List.of("date")));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我建一个关于张总的待办\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.message").value("这条待办安排在什么时间？"))
                .andExpect(jsonPath("$.missing[0]").value("date"))
                .andExpect(jsonPath("$.proposal").doesNotExist());
    }

    @Test
    void aConfirmationCarriesTheProposalWithItsSummaryAndChanges() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.confirmationRequired(new AssistantTurnResult.Proposal(
                        PENDING_ID, "todo.complete", "标记完成：「和张总确认报价」 2026-09-22 15:00",
                        List.of(new AssistantTurnResult.Proposal.Change("completed", "状态", null, "已完成")),
                        Map.of("todoId", "11111111-1111-4111-8111-111111111111", "completed", true))));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我标记完成和张总确认报价那条\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.proposal.pendingActionId").value(PENDING_ID.toString()))
                .andExpect(jsonPath("$.proposal.tool").value("todo.complete"))
                .andExpect(jsonPath("$.proposal.summary").value(
                        "标记完成：「和张总确认报价」 2026-09-22 15:00"))
                // 「改前」拿不到证据时必须原样传出一个 **null**，不能被 NON_NULL 吞成「字段缺席」：
                // 缺席会被前端当成「后端忘了填」，而这里的意思恰恰是「后端明确不知道」。
                .andExpect(jsonPath("$.proposal.changes[0].field").value("completed"))
                .andExpect(jsonPath("$.proposal.changes[0].label").value("状态"))
                .andExpect(jsonPath("$.proposal.changes[0].before")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.proposal.changes[0].after").value("已完成"))
                .andExpect(jsonPath("$.missing").doesNotExist());
    }

    @Test
    void anExecutedTurnReportsWhatWasDone() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.executed("已创建待办：9月22日 15:00 和张总确认报价"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"明天下午三点和张总确认报价，帮我建个待办\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("EXECUTED"))
                .andExpect(jsonPath("$.message").value("已创建待办：9月22日 15:00 和张总确认报价"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void anErrorCarriesItsCode() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.error("TODO_NOT_FOUND", "待办不存在"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"标记完成那条\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    void oversizeInputIsRejectedBeforeAnyOrchestration() throws Exception {
        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(AssistantMessageLimitTestConfiguration.MAX_MESSAGE_CHARS + 1) + "\"}"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(conversations);
    }

    @Test
    void confirmUsesTheAuthenticatedUserAndThePathId() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenReturn(AssistantTurnResult.executed("已标记完成：和张总确认报价"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("EXECUTED"));

        verify(pendingActions).confirm(USER_ID, PENDING_ID);
    }

    @Test
    void cancelUsesTheAuthenticatedUserAndThePathId() throws Exception {
        when(pendingActions.cancel(USER_ID, PENDING_ID))
                .thenReturn(AssistantTurnResult.answer("已取消，未做任何改动"));

        mvc.perform(post("/api/assistant/actions/{id}/cancel", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ANSWER"));

        verify(pendingActions).cancel(USER_ID, PENDING_ID);
    }

    /**
     * 404 与 410 拆成两个方法，不是风格问题而是必须的：
     * 在同一个方法里对<b>同一个调用</b>再写一次 {@code when(mock.confirm(...))}，
     * 那次调用会真的打到 mock 上，于是第一次设好的异常当场抛出，
     * 测试在发出第二个请求之前就红了（报的还是第一个异常）。
     * 想在一个方法里改桩必须用 {@code doThrow().when(mock).confirm(...)}。
     */
    @Test
    void aMissingPendingActionBecomes404() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenThrow(new AssistantException(AssistantException.PENDING_NOT_FOUND, "不存在"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(AssistantException.PENDING_NOT_FOUND));
    }

    @Test
    void anExpiredPendingActionBecomes410() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenThrow(new AssistantException(AssistantException.PENDING_EXPIRED, "已过期"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(AssistantException.PENDING_EXPIRED));
    }

    @Test
    void aProviderFailureBecomes503RatherThanAFabricatedAnswer() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenThrow(new AssistantException(AssistantException.PROVIDER_UNAVAILABLE, "AI 服务暂时不可用，请稍后再试"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我建个待办\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AssistantException.PROVIDER_UNAVAILABLE));
    }

    @Test
    void historyIsPassedThroughWithRolesNormalisedToUserAndAssistant() throws Exception {
        when(conversations.respond(eq(USER_ID), eq(PENDING_ID), any(), eq("明天下午三点")))
                .thenReturn(AssistantTurnResult.executed("已创建"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"20000000-0000-0000-0000-000000000002",
                                 "history":[{"role":"user","text":"帮我建个待办"},
                                            {"role":"assistant","text":"安排在什么时间？"}],
                                 "text":"明天下午三点"}
                                """))
                .andExpect(status().isOk());

        verify(conversations).respond(eq(USER_ID), eq(PENDING_ID),
                org.mockito.ArgumentMatchers.argThat(history -> history.size() == 2
                        && history.get(0).role() == com.crmforlogistics.messagecenter.service.assistant.AssistantMessage.Role.USER
                        && history.get(1).role() == com.crmforlogistics.messagecenter.service.assistant.AssistantMessage.Role.ASSISTANT),
                eq("明天下午三点"));
    }
}
