package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationLogService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPendingActionService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * <h2>这个类里<strong>不</strong>再有「历史来源」的断言（2026-09-23 迁出）</h2>
 * 「服务端记录压过请求体」与「裁剪要报出来」两条性质原先在这里，现在归
 * {@code AssistantPromptHistoryTest}。原因是落点变了：控制器不再选源、不再裁剪，
 * 它只把线上的形状归一成领域对象（{@code role} 小写字符串 → {@link
 * com.crmforlogistics.messagecenter.service.assistant.AssistantMessage}）。
 * 留在这里的断言会变成「控制器把一份历史原样转交给 mock」——那是在测传参，不是在测规则。
 * 判据见 {@code AssistantConversationService} 的类注释：**模型看到哪一份历史由编排层决定。**
 *
 * <p>相应地，这个类不再需要 {@code AssistantRequestGuard} 与那份「把上限压小」的
 * {@code AssistantConfig} 替身 —— 校验、裁剪、选源都已经不在这一层了。
 */
@WebMvcTest(AssistantController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AssistantControllerTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PENDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

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

    /**
     * 入参不合法 → 400（不是 500、不是 200）。
     *
     * <p>这条断言<b>只</b>覆盖状态码映射。原话超长/为空的具体判定在编排层，
     * 见 {@code AssistantConversationServiceTest.anOversizeUtteranceIsRejectedBeforeAnyModelCall} ——
     * 那里的断言更有分量（一次模型调用都没发生）。这里补的是另一半：
     * 那条 {@link AssistantException} 走到 HTTP 边界时会变成 400，且 {@code code} 原样带出。
     */
    @Test
    void aRequestInvalidExceptionBecomes400() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenThrow(new AssistantException(AssistantException.REQUEST_INVALID, "这条消息太长了"));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"随便一句\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AssistantException.REQUEST_INVALID));
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
     * 确认接口的响应不带裁剪信息 —— 它不走 {@code /messages}，没有「这一轮的语境」可言。
     *
     * <p>前端据此实现了一条「只在字段出现时更新提示、绝不因为没看到而清空」的逻辑
     * （见 {@code useAssistant.applyResult}）。这条用例把「后端确实不会带」钉住，
     * 那条前端逻辑才有依据。裁剪信息整体由编排层附加（见 {@code AssistantPromptHistoryTest}），
     * 确认路径没有编排层参与，所以这里天然为空。
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

    /**
     * 「历史被裁剪」这件事由编排层附加，控制器<b>不</b>增删 —— 它原样转交服务返回的结果。
     *
     * <p>这条用例取代了原来那条「三条 100 字符的历史被裁掉一条」：裁剪算得对不对归编排层
     * （{@code AssistantPromptHistoryTest.aTrimmedHistoryIsReportedOnTheTurnResult}），
     * 这里只钉住「控制器不吞掉它、也不自己造一个」。
     */
    @Test
    void theTrimReportedByTheServiceReachesTheResponseUnchanged() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(AssistantTurnResult.answer("好").withTrimmedHistory(2));

        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"继续\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyTrim.droppedMessages").value(2));
    }

    // ---------- 会话号的兜底：浏览器忘了它时，服务端能交回来 ----------

    /**
     * 端点没有路径参数、没有请求参数，问的永远是**认证上下文里的那个人**。
     *
     * <p>这条用例故意在 URL 上挂两个伪造的身份参数（{@code userId} / {@code conversationId}）：
     * 它们必须被完全忽略。一旦哪天有人「顺手支持」了其中一个，这个端点就从查询变成了
     * 「用别人的号探路」的入口 —— 而它返回的正是别人的会话号，前端会拿它去回放。
     */
    @Test
    void theLatestConversationIsAlwaysTheCallersOwn() throws Exception {
        UUID other = UUID.fromString("30000000-0000-0000-0000-000000000003");
        when(conversationLog.latestConversationId(USER_ID)).thenReturn(PENDING_ID);

        mvc.perform(get("/api/assistant/conversations/latest")
                        .with(user(USER_ID.toString()))
                        .param("userId", other.toString())
                        .param("conversationId", other.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(PENDING_ID.toString()));

        verify(conversationLog).latestConversationId(USER_ID);
        verify(conversationLog, org.mockito.Mockito.never()).latestConversationId(other);
    }

    /**
     * 还没有任何对话时是 200 + {@code null}，不是 204、不是 404。
     *
     * <p>前端靠这个区别分两件事：{@code null} = 「服务端明确说没有」→ 安静地开一段新对话；
     * 非 2xx = 「这次没问出来」→ 必须提示。混成同一个形状，面板就会在
     * 「新用户第一次打开」时弹一个假的错误，或者在真出错时假装没事。
     */
    @Test
    void noConversationYetIsAnAnswerNotAnError() throws Exception {
        // 不 stub：Mockito 对 UUID 返回 null，正是「库里还没有这个用户的任何消息」。
        mvc.perform(get("/api/assistant/conversations/latest")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 认证先于一切：未认证的请求连「我有没有会话」都不该问得出来。 */
    @Test
    void theLatestConversationRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/assistant/conversations/latest"))
                .andExpect(status().isUnauthorized());
    }
}
