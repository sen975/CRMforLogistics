package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationLogService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPendingActionService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnSink;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * <h2>{@code /messages} 自 2026-09-24 起是 SSE（其余端点不变）</h2>
 * 所以这个类里「一轮对话」的断言分成两半，各钉一半，互不重复：
 * <ul>
 *   <li><b>这里</b>钉「编排层说了什么 → 线上长什么样」，即 {@link AssistantTurnSink} 的调用
 *       如何变成帧、结果如何变成 {@code final}、失败如何落到流里；</li>
 *   <li><b>编排层</b>（{@code AssistantConversationServiceTest} / {@code AssistantReadLoopTest}）
 *       钉「什么时候该说」—— 什么时候 {@code thinking}、哪一轮才允许推片段、重试前为何必须
 *       {@code reset}。</li>
 * </ul>
 * 两半的接口就是 {@link AssistantTurnSink} 本身。
 */
@WebMvcTest(AssistantController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AssistantControllerTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PENDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    /** 只用来解析我们自己写出去的帧，不需要与运行时的 ObjectMapper 共用配置。 */
    private static final ObjectMapper JSON = new ObjectMapper();

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

    // ---------- 流本身：形状、顺序、以及「失败也得说出来」 ----------

    /**
     * 端点确实换了协议：响应是 {@code text/event-stream}，而<b>不是</b>一份 JSON 体。
     *
     * <p>这条断言看着浅，却在钉一个**删除**：非流式的那份响应体已经不存在了。
     * 没有它，日后有人「顺手」把返回类型改回 {@code AssistantTurnResult} 时，
     * 不会有任何东西当场报错，只会在前端表现为「一句话都不显示」。
     *
     * <p>两个响应头也一并钉住，它们不是装饰：{@code no-transform} 阻止中间层压缩，
     * {@code X-Accel-Buffering: no} 阻止 nginx 把整条流缓冲到结束 —— 少了后者，
     * 前端会在几十秒后一次性看到全文，「逐字」当场失效，而后端日志一切正常。
     */
    @Test
    void theTurnIsAnEventStreamWithHeadersThatKeepItFromBeingBuffered() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenReturn(AssistantTurnResult.answer("好"));

        MvcResult result = mvc.perform(turn("继续")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache, no-transform");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(finalOf(result).path("kind").asText()).isEqualTo("ANSWER");
    }

    /**
     * 协议全貌：{@code status} → {@code delta} → {@code final}，顺序即节奏。
     *
     * <p>用 {@link AssistantTurnSink} 的调用驱动，而不是真的去跑一轮模型 —— 控制器只负责
     * 「把这些调用翻译成帧」，不负责「什么时候调用」。把两件事分开测，任何一边坏了都能一眼看出是谁。
     */
    @Test
    void sinkCallsBecomeFramesInOrder() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // respond 的第 6 个参数才是 sink（第 5 个是用户这一轮带的素材 id）
                    AssistantTurnSink sink = invocation.getArgument(5);
                    sink.thinking();
                    sink.reading("todo.list");
                    sink.answerDelta("你");
                    sink.answerDelta("好");
                    return AssistantTurnResult.answer("你好");
                });

        MvcResult result = mvc.perform(turn("继续")).andExpect(status().isOk()).andReturn();

        List<JsonNode> frames = framesOf(result);
        assertThat(frames).extracting(frame -> frame.path("type").asText())
                .containsExactly("status", "status", "delta", "delta", "final");
        assertThat(frames.get(0).path("state").asText()).isEqualTo("thinking");
        assertThat(frames.get(1).path("state").asText()).isEqualTo("reading");
        // 工具名不进用户视野也行，但它必须在线上 —— 否则「卡在哪一步」只能靠猜。
        assertThat(frames.get(1).path("tool").asText()).isEqualTo("todo.list");
        assertThat(frames.get(2).path("text").asText()).isEqualTo("你");
        assertThat(frames.get(3).path("text").asText()).isEqualTo("好");
    }

    /**
     * {@code reset} 是一条独立事件，不是「另一个 status」。
     *
     * <p>它是唯一的「撤回」信号，只能在重试前发一次。用户看不到它，就会看到答案自己改写自己 ——
     * 那不是 bug，而是「信封被截断后重问」的正常路径，但看起来像胡说八道。
     */
    @Test
    void aResetBecomesItsOwnFrame() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // respond 的第 6 个参数才是 sink（第 5 个是用户这一轮带的素材 id）
                    AssistantTurnSink sink = invocation.getArgument(5);
                    sink.answerDelta("你好，我");
                    sink.reset();
                    sink.answerDelta("我不确定");
                    return AssistantTurnResult.answer("我不确定");
                });

        MvcResult result = mvc.perform(turn("继续")).andExpect(status().isOk()).andReturn();

        assertThat(framesOf(result)).extracting(frame -> frame.path("type").asText())
                .containsExactly("delta", "reset", "delta", "final");
    }

    /**
     * 空片段不占一帧。展示层不必为「追加了零个字」写一条分支。
     *
     * <p>空片段不是假想：提取器在「还没读到 reply 的值」时每次都返回空串，
     * 而那期间模型可能已经吐了几十个 chunk。
     */
    @Test
    void emptyDeltasAreNotSentAsFrames() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // respond 的第 6 个参数才是 sink（第 5 个是用户这一轮带的素材 id）
                    AssistantTurnSink sink = invocation.getArgument(5);
                    sink.answerDelta("");
                    sink.answerDelta(null);
                    sink.answerDelta("好");
                    return AssistantTurnResult.answer("好");
                });

        MvcResult result = mvc.perform(turn("继续")).andExpect(status().isOk()).andReturn();

        assertThat(framesOf(result)).extracting(frame -> frame.path("type").asText())
                .containsExactly("delta", "final");
    }

    // ---------- kind 的映射：三种语义各一条，形状不变 ----------

    @Test
    void aQuestionMapsToTheQuestionKindWithItsMissingFields() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), eq("帮我建一个关于张总的待办"), any(), any()))
                .thenReturn(AssistantTurnResult.question("这条待办安排在什么时间？", List.of("date")));

        MvcResult result = mvc.perform(turn("帮我建一个关于张总的待办"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("QUESTION");
        assertThat(finalEvent.path("message").asText()).isEqualTo("这条待办安排在什么时间？");
        assertThat(finalEvent.path("missing").get(0).asText()).isEqualTo("date");
        assertThat(finalEvent.has("proposal")).isFalse();
    }

    @Test
    void aConfirmationCarriesTheProposalWithItsSummaryAndChanges() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenReturn(AssistantTurnResult.confirmationRequired(new AssistantTurnResult.Proposal(
                        PENDING_ID, "todo.complete", "标记完成：「和张总确认报价」 2026-09-22 15:00",
                        List.of(new AssistantTurnResult.Proposal.Change("completed", "状态", null, "已完成")),
                        Map.of("todoId", "11111111-1111-4111-8111-111111111111", "completed", true))));

        MvcResult result = mvc.perform(turn("帮我标记完成和张总确认报价那条"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode finalEvent = finalOf(result);
        JsonNode proposal = finalEvent.path("proposal");
        assertThat(finalEvent.path("kind").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(proposal.path("pendingActionId").asText()).isEqualTo(PENDING_ID.toString());
        assertThat(proposal.path("tool").asText()).isEqualTo("todo.complete");
        assertThat(proposal.path("summary").asText())
                .isEqualTo("标记完成：「和张总确认报价」 2026-09-22 15:00");
        JsonNode change = proposal.path("changes").get(0);
        assertThat(change.path("field").asText()).isEqualTo("completed");
        assertThat(change.path("label").asText()).isEqualTo("状态");
        // 「改前」拿不到证据时必须原样传出一个 **null**，不能被 NON_NULL 吞成「字段缺席」：
        // 缺席会被前端当成「后端忘了填」，而这里的意思恰恰是「后端明确不知道」。
        // 所以先断言键存在，再断言值就是 null —— 只断言后者的话，字段缺席也会通过。
        assertThat(change.has("before")).as("before 必须显式出现在线上").isTrue();
        assertThat(change.path("before").isNull()).isTrue();
        assertThat(change.path("after").asText()).isEqualTo("已完成");
        assertThat(finalEvent.has("missing")).isFalse();
    }

    @Test
    void anExecutedTurnReportsWhatWasDone() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenReturn(AssistantTurnResult.executed("已创建待办：9月22日 15:00 和张总确认报价"));

        MvcResult result = mvc.perform(turn("明天下午三点和张总确认报价，帮我建个待办"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("EXECUTED");
        assertThat(finalEvent.path("message").asText())
                .isEqualTo("已创建待办：9月22日 15:00 和张总确认报价");
        assertThat(finalEvent.has("errorCode")).isFalse();
    }

    @Test
    void anErrorCarriesItsCode() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenReturn(AssistantTurnResult.error("TODO_NOT_FOUND", "待办不存在"));

        MvcResult result = mvc.perform(turn("标记完成那条")).andExpect(status().isOk()).andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("ERROR");
        assertThat(finalEvent.path("errorCode").asText()).isEqualTo("TODO_NOT_FOUND");
    }

    // ---------- 失败：流一旦打开，就只剩「流内事件」这一条通道 ----------

    /**
     * 入参不合法 → 一条 {@code final} 错误事件，{@code errorCode} 原样带出。
     *
     * <p>它<b>不再</b>是 400（2026-09-24 改流式之前是）。这不是「状态码不重要」，
     * 而是这条端点的响应头在第一步就发出去了 —— 响应一旦提交，就没有 HTTP 状态码这条通道。
     * 代价写在 {@code AssistantEventStream} 的类注释里：失败不再体现在 4xx/5xx 上。
     *
     * <p>原话超长/为空的具体判定仍在编排层，
     * 见 {@code AssistantConversationServiceTest.anOversizeUtteranceIsRejectedBeforeAnyModelCall} ——
     * 那里的断言更有分量（一次模型调用都没发生）。这里补的是另一半：那条 {@link AssistantException}
     * 会变成一条形状正确、码值不变的 {@code final}。
     */
    @Test
    void aRequestInvalidExceptionArrivesAsAFinalErrorFrame() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenThrow(new AssistantException(AssistantException.REQUEST_INVALID, "这条消息太长了"));

        MvcResult result = mvc.perform(turn("随便一句")).andExpect(status().isOk()).andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("ERROR");
        assertThat(finalEvent.path("errorCode").asText()).isEqualTo(AssistantException.REQUEST_INVALID);
        assertThat(finalEvent.path("message").asText()).isEqualTo("这条消息太长了");
    }

    /**
     * 供应商故障走同一条通道，但它<b>绝不能</b>变成一个看起来像答案的结果。
     *
     * <p>（这是原来那条「as 503」用例的意图。状态码从 503 变成 200 是流式的代价，
     * 但「不许编一个答案」这条底线没有变 —— 所以断言落在 {@code kind} 上。）
     */
    @Test
    void aProviderFailureStaysAProviderFailureAndNeverBecomesAnAnswer() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenThrow(new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                        "AI 服务暂时不可用，请稍后再试"));

        MvcResult result = mvc.perform(turn("帮我建个待办")).andExpect(status().isOk()).andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("ERROR");
        assertThat(finalEvent.path("errorCode").asText())
                .isEqualTo(AssistantException.PROVIDER_UNAVAILABLE);
        assertThat(finalEvent.has("proposal")).isFalse();
    }

    /**
     * 意料之外的异常也必须以 {@code final} 收场，且不许把内部细节抖出去。
     *
     * <p>两个断言缺一不可。少了前者，前端会把「后端炸了」显示成「连接断了，请重试」——
     * 而重试对前者毫无用处。少了后者，一句 JDBC 报错就把库的主机名和端口送给了用户，
     * 而且{"没有 final"}这条路径会被误诊成网络问题。
     */
    @Test
    void anUnexpectedFailureStillEndsTheStreamWithAFinalFrameAndNoInternals() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc: connection refused to db-host:5432"));

        MvcResult result = mvc.perform(turn("随便一句")).andExpect(status().isOk()).andReturn();

        JsonNode finalEvent = finalOf(result);
        assertThat(finalEvent.path("kind").asText()).isEqualTo("ERROR");
        assertThat(finalEvent.path("errorCode").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(finalEvent.path("message").asText()).doesNotContain("db-host");
    }

    /**
     * {@code DISABLED} 若从流内冒出来，也仍然以自己的码值出现，不会被改写。
     *
     * <p>「功能没开 = 503」这条真正由 {@code AssistantDisabledControllerTest} 钉住 ——
     * 那里压根没有替身，{@code require(...)} 会真的走到异常映射，于是时序（require 在
     * open 之前）也被一并验到。这里补的是另一半：码值在流内不会被统一成 INTERNAL_ERROR，
     * 否则前端就再也分不出「没开」和「炸了」。
     */
    @Test
    void aDisabledFeatureKeepsItsOwnCodeWhenItSurfacesInsideTheStream() throws Exception {
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenThrow(new AssistantException(AssistantException.DISABLED, "助手功能未开启"));

        MvcResult result = mvc.perform(turn("帮我建个待办")).andExpect(status().isOk()).andReturn();

        assertThat(finalOf(result).path("errorCode").asText())
                .isEqualTo(AssistantException.DISABLED);
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
        when(conversations.respond(eq(USER_ID), isNull(), any(), any(), any(), any()))
                .thenReturn(AssistantTurnResult.answer("好").withTrimmedHistory(2));

        MvcResult result = mvc.perform(turn("继续")).andExpect(status().isOk()).andReturn();

        assertThat(finalOf(result).path("historyTrim").path("droppedMessages").asInt()).isEqualTo(2);
    }

    // ---------- 其余端点：普通 JSON，一个字节没变 ----------

    @Test
    void confirmUsesTheAuthenticatedUserAndThePathId() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenReturn(AssistantTurnResult.executed("已标记完成：和张总确认报价"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
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
     * 错误响应即使被要求 {@code Accept: text/event-stream} 也仍然是一份 JSON。
     *
     * <p>这条不是假想：一个用 {@code /messages} 的前端很容易顺手给<b>所有</b>助手请求都加上
     * 这个 Accept（对成功的流式请求来说是完全正确的写法）。若不显式声明错误体的类型，
     * 内容协商会因为「Jackson 转换器产不出 text/event-stream」而把状态码变成
     * <b>406 加一个空响应体</b> —— 于是「这条动作过期了」看起来像「接口写错了」，前端会去改代码。
     *
     * <p>功能关闭时的同一条性质见
     * {@code AssistantDisabledControllerTest#messagesStillAnswer503JsonWhenTheCallerAsksForAnEventStream}。
     */
    @Test
    void anErrorResponseIsJsonEvenWhenTheCallerAsksForAnEventStream() throws Exception {
        when(pendingActions.confirm(USER_ID, PENDING_ID))
                .thenThrow(new AssistantException(AssistantException.PENDING_EXPIRED, "已过期"));

        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString()))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(AssistantException.PENDING_EXPIRED));
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

    // ---------- 辅助 ----------

    /**
     * 一次「已被接受的一轮」的标准请求。
     *
     * <p><b>刻意不设 {@code Accept}。</b> 端点的 {@code produces} 只管成功路径；失败时异常
     * 处理器要写 JSON，而内容协商看的是请求的 {@code Accept}。浏览器默认的 Accept
     * 允许任意媒体类型，两条路都通；而写成 {@code text/event-stream} 会让 503 退化成 406（见
     * {@link #anErrorResponseIsJsonEvenWhenTheCallerAsksForAnEventStream}）。
     */
    private static MockHttpServletRequestBuilder turn(String text) {
        return post("/api/assistant/messages")
                .with(user(USER_ID.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}");
    }

    /**
     * 取响应体里<b>那一帧</b>{@code final} 的载荷。
     *
     * <p>顺带钉住两条协议不变量，因为它们是前端判断「这一轮到底怎么了」的全部依据：
     * 有且仅有一帧 {@code final}（两帧意味着后端在一条流里说了两次结论），
     * 且它是最后一帧（不是最后一帧，就意味着后面还有东西要覆盖它）。
     */
    private static JsonNode finalOf(MvcResult result) throws Exception {
        List<JsonNode> frames = framesOf(result);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(frames).as("SSE 响应体：%s", body).isNotEmpty();
        List<JsonNode> finals = frames.stream()
                .filter(frame -> "final".equals(frame.path("type").asText()))
                .toList();
        assertThat(finals).as("有且仅有一帧 final：%s", body).hasSize(1);
        assertThat(frames.get(frames.size() - 1).path("type").asText())
                .as("final 必须是最后一帧：%s", body)
                .isEqualTo("final");
        return finals.get(0);
    }

    /** 把响应体拆成帧。只认 {@code data:} 行 —— 协议里没有别的行类型。 */
    private static List<JsonNode> framesOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> frames = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            for (String line : block.split("\n")) {
                if (line.startsWith("data: ")) {
                    frames.add(JSON.readTree(line.substring("data: ".length())));
                }
            }
        }
        return frames;
    }
}
