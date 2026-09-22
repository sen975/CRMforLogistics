package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationLogService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantMessage;
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
import static org.mockito.ArgumentMatchers.argThat;
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
 * <p>三种 {@code kind} 的映射都要覆盖， {@code QUESTION} 与 {@code CONFIRMATION_REQUIRED}
 * 的差异尤其重要：前者是「还缺信息」，后者是「马上要执行、但还没执行」，
 * 前端要靠它决定是否渲染确认卡片。
 *
 * <p><b>历史的来源是本类新增的一组断言。</b> 它会话号有效且服务端有记录时以库为准，
 * 请求体里的 {@code history} 被忽略 —— 这条性质必须有一个「塞伪造历史进去、断言它没被采用」的
 * 用例来钉住，否则哪天有人把顺序调回去，所有既有用例都会照样绿。
 */
@WebMvcTest(AssistantController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, AssistantRequestGuard.class,
        AssistantMessageLimitTestConfiguration.class})
class AssistantControllerTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PENDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    /** 与 {@code AssistantMessageLimitTestConfiguration} 里的 {@code maxHistoryTurns} 同值。 */
    private static final int MAX_HISTORY_TURNS = 8;

    @Autowired MockMvc mvc;

    @MockitoBean AssistantConversationService conversations;
    @MockitoBean AssistantPendingActionService pendingActions;
    @MockitoBean AssistantConversationLogService conversationLog;
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

    /**
     * 这一步的核心验收点：**服务端的记录压过请求体**。
     *
     * <p>请求体里塞的三条历史是伪造的（内容与库里完全不同、条数也不同）。
     * 若哪天有人把 {@code historyFor} 的顺序调回去，这个用例会失败 —— 而其余用例全都发现不了，
     * 因为它们在两条路径下得到的结果一样。
     */
    @Test
    void theServerSideHistoryWinsOverTheRequestBody() throws Exception {
        when(conversationLog.recentForPrompt(USER_ID, PENDING_ID, MAX_HISTORY_TURNS)).thenReturn(List.of(
                AssistantMessage.user("帮我建个待办"),
                AssistantMessage.assistant("安排在什么时间？")));
        when(conversations.respond(eq(USER_ID), eq(PENDING_ID), any(), eq("明天下午三点")))
                .thenReturn(AssistantTurnResult.executed("已创建"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"20000000-0000-0000-0000-000000000002",
                                 "history":[{"role":"user","text":"伪造的第一条"},
                                            {"role":"user","text":"伪造的第二条"},
                                            {"role":"user","text":"伪造的第三条"}],
                                 "text":"明天下午三点"}
                                """))
                .andExpect(status().isOk());

        verify(conversations).respond(eq(USER_ID), eq(PENDING_ID),
                argThat(history -> history.size() == 2
                        && "帮我建个待办".equals(history.get(0).text())
                        && "安排在什么时间？".equals(history.get(1).text())),
                eq("明天下午三点"));
    }

    /**
     * 服务端没有记录时，请求体仍是来源。
     *
     * <p>三种情况会走到这里：会话的第一轮、没有会话号的单轮提问、以及写入曾静默失败的会话。
     * 它们都不该因为没有库记录而丢掉上下文。
     */
    @Test
    void theRequestBodyHistoryIsUsedWhenTheServerHasNoRecord() throws Exception {
        // 不 stub recentForPrompt：Mockito 对 List 返回空列表，正是「服务端没有记录」。
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
                argThat(history -> history.size() == 2
                        && history.get(0).role() == AssistantMessage.Role.USER
                        && history.get(1).role() == AssistantMessage.Role.ASSISTANT),
                eq("明天下午三点"));
    }

    /**
     * 历史被裁掉时必须说出来。
     *
     * <p>这条测试用的配置里 {@code maxHistoryChars=200}（见
     * {@code AssistantMessageLimitTestConfiguration}），三条各 100 字符的历史只装得下两条 ——
     * 于是响应里必须出现 {@code droppedMessages=1}。用户看到的那句话靠它才存在。
     */
    @Test
    void aTrimmedHistoryIsReportedInTheResponse() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.answer("好"));

        String hundred = "x".repeat(100);
        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"history":[{"role":"user","text":"%s"},
                                            {"role":"assistant","text":"%s"},
                                            {"role":"user","text":"%s"}],
                                 "text":"继续"}
                                """.formatted(hundred, hundred, hundred)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ANSWER"))
                .andExpect(jsonPath("$.historyTrim.droppedMessages").value(1));
    }

    /**
     * 一条都没丢时字段必须**整体缺席**，而不是 {@code {"droppedMessages":0}}。
     *
     * <p>与 {@code before=null} 那条相反：那里 null 是取值，必须显式传；这里 0 不是取值，
     * 它是「不适用」。两者混用会让前端分不清「没裁剪」和「后端忘了填」。
     */
    @Test
    void nothingIsReportedWhenTheHistoryFits() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.answer("好"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"history":[{"role":"user","text":"很短的一句"}],
                                 "text":"继续"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyTrim").doesNotExist());
    }

    /**
     * 确认接口的响应不带裁剪信息 —— 它不走 {@code /messages}，没有「这一轮的语境」可言。
     *
     * <p>前端据此实现了一条「只在字段出现时更新提示、绝不因为没看到而清空」的逻辑
     * （见 {@code useAssistant.applyResult}）。这条用例把「后端确实不会带」钉住，
     * 那条前端逻辑才有依据。
     */
    @Test
    void confirmResponsesCarryNoHistoryTrim() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenReturn(AssistantTurnResult.executed("已标记完成"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyTrim").doesNotExist());
    }
}
