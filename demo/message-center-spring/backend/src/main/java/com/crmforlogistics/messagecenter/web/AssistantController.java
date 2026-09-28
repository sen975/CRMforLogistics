package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationLogService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantMessage;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPendingActionService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 助手端点。
 *
 * <h2>为什么用 {@link ObjectProvider} 而不是直接注入服务</h2>
 * 编排链路的 bean 带 {@code @ConditionalOnAssistantEnabled}（要显式开启且凭据齐备才装配）。
 * 若这里直接注入，功能关闭时应用会**启动失败**；若把控制器也标成条件装配，关闭时端点会变成
 * 404 —— 而 404 会让前端以为是自己请求的路径写错了。用 {@link ObjectProvider} 拿到「可能不存在」
 * 的依赖并显式返回 503「功能未开启」，是唯一同时保住「能启动」与「原因清楚」的写法。
 *
 * <h2>身份只来自认证上下文</h2>
 * 所有端点都只通过 {@link SecurityUtil#currentUserId()} 取用户。<b>请求体里没有任何身份字段</b>，
 * 即使有人塞 {@code userId} 进来，它也不会被读取（{@link MessagesRequest} 里根本没有这个分量）。
 * {@code /api/assistant/**} 落在既有 {@code SecurityConfig} 的
 * {@code .requestMatchers("/api/**").authenticated()} 之下，无需改动安全配置。
 *
 * <h2>会话语境由服务端提供，但那条规则<b>不</b>住在这一层</h2>
 * {@code POST /messages} 的 {@code history} 不是权威来源 —— 会话号有效且服务端有记录时，
 * 历史从库里读。这条规则此前写在这里（一个私有方法），现已收进
 * {@link AssistantConversationService#respond}：「跑一轮」只有一个入口，规则就无处可跳。
 * 摆在控制器里的规则没有类型与测试的保护，第二个入口（语音、定时触发、内部调用）
 * 一旦忘了先读库，就会静默退回「请求体说了算」。
 *
 * <p>这一层因此只剩两件事：把线上的形状归一成领域对象（{@code role} 是小写字符串，
 * 见 {@link #historyOf}），以及判定「依赖不存在时返回 503」。
 * {@code history} 的裁剪与「丢了几条」的报告都在编排层 —— 只有那里同时握着
 * 「选中的那份历史」与「裁剪结果」。
 *
 * <h2>会话号仍由前端持有，服务端只是兜底</h2>
 * {@code GET /conversations/latest} 回答「我上次在哪个会话里」，但<b>不改变</b>会话号的归属：
 * 它是分组标签而不是凭证（归属一律按登录用户判定），生成、保存、换新都留在前端。
 * 这个端点只补一条路 —— 浏览器忘了那个号的时候（清缓存、换设备），别让用户
 * 看起来像丢了整段对话，而记录明明还在库里。
 * <h2>确认接口只接受 pendingActionId</h2>
 * 不接受工具名或参数的重放：参数以服务端落库的那一份为准，并在执行前重新校验。
 * 若允许前端回传参数，确认就退化成「前端说了算」，而落库授权这件事就白做了。
 *
 * <h2>{@code /messages} 是 SSE，其余端点仍是普通 JSON</h2>
 * 只有这一条端点会「边跑边说」—— 一轮对话最多要串 3 轮只读工具，中间几十秒没有任何
 * 用户可见的动静。其余端点（确认、取消、读会话）本来就是一次查或一次写，把它们也改成
 * 流式只是把同一个形状写两遍。<b>刻意不保留 {@code /messages} 的非流式版本</b>：
 * 两条语义相同的路径，后来人不知道该用哪条，而它们迟早会走偏。
 *
 * <p>事件协议，以及「响应头一刷出去就只能靠 {@code final} 报错」这条约束，见
 * {@link AssistantEventStream}。
 *
 * <p><b>一个必须记住的坑：前端不要给这个请求设 {@code Accept: text/event-stream}。</b>
 * 端点声明的 {@code produces} 只管成功路径；失败时异常处理器要写的是 JSON 错误体，
 * 而内容协商看的是请求的 {@code Accept} —— 只写 {@code text/event-stream} 会让
 * 「功能没开」的 503 退化成 406 加一个空响应体。浏览器默认的 Accept 允许任意媒体类型，
 * 在成功与失败两条路上都通。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    /** 一次回放的消息条数上限。前端只用来「接上上次」，不需要全量历史。 */
    private static final int REPLAY_LIMIT = 100;

    private final ObjectProvider<AssistantConversationService> conversations;
    private final ObjectProvider<AssistantPendingActionService> pendingActions;
    private final ObjectProvider<AssistantConversationLogService> conversationLog;
    private final ObjectMapper objectMapper;

    public AssistantController(ObjectProvider<AssistantConversationService> conversations,
                               ObjectProvider<AssistantPendingActionService> pendingActions,
                               ObjectProvider<AssistantConversationLogService> conversationLog,
                               ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.pendingActions = pendingActions;
        this.conversationLog = conversationLog;
        this.objectMapper = objectMapper;
    }

    /**
     * 一轮对话：解析 → 追问 / 直接执行 / 落待确认。<b>以 SSE 边跑边报。</b>
     *
     * <p>控制器不做任何校验与选源 —— 原话的长度上限、历史的来源与裁剪，全都发生在
     * {@link AssistantConversationService#respond} 里。这里只把请求体翻译成领域入参：
     * 身份取认证上下文，历史 {@link #historyOf} 归一，其余原样交出去。
     *
     * <p>{@code require(...)} 与取身份都刻意留在 {@link AssistantEventStream#open} <b>之前</b>：
     * 那时响应还没被碰过，「功能没开」答 503、「没登录」答 401，而不是把这两件事
     * 写成一个 200 的流内错误。开流之后这条路就没了（理由见 {@link AssistantEventStream}）。
     */
    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void messages(@RequestBody(required = false) MessagesRequest request,
                         HttpServletResponse response) {
        // 「功能没开启」必须先于一切：它要能答出 503，而不是在入参校验里变成 400。
        AssistantConversationService service = require(conversations);
        UUID userId = SecurityUtil.currentUserId();
        AssistantEventStream stream = AssistantEventStream.open(response, objectMapper);
        stream.run(() -> service.respond(userId,
                request == null ? null : request.conversationId(),
                historyOf(request),
                request == null ? null : request.text(),
                stream));
    }

    /** 确认并执行一条待确认动作。 */
    @PostMapping("/actions/{id}/confirm")
    public AssistantTurnResult confirm(@PathVariable UUID id) {
        AssistantPendingActionService service = require(pendingActions);
        return service.confirm(SecurityUtil.currentUserId(), id);
    }

    /** 取消一条待确认动作，不执行任何东西。 */
    @PostMapping("/actions/{id}/cancel")
    public AssistantTurnResult cancel(@PathVariable UUID id) {
        AssistantPendingActionService service = require(pendingActions);
        return service.cancel(SecurityUtil.currentUserId(), id);
    }

    /**
     * 回放某会话的历史消息，时间正序。面板打开时用它接上上次的对话。
     *
     * <p>{@code conversationId} <b>不是凭证</b>：归属条件在 SQL 的 {@code user_id} 上，
     * 拿别人的会话号来试只会读回空列表。这也意味着前端可以自由生成会话号，无须向服务端申领。
     */
    @GetMapping("/conversations/{id}/messages")
    public List<AssistantConversationLogService.Message> conversation(@PathVariable UUID id) {
        AssistantConversationLogService service = require(conversationLog);
        return service.replay(SecurityUtil.currentUserId(), id, REPLAY_LIMIT);
    }

    /**
     * 我最近说过话的那个会话号，没有则 {@code {"conversationId": null}}。
     *
     * <p>它存在的唯一理由：会话号原本只活在浏览器里（前端 {@code localStorage}），
     * 于是清缓存、换设备、换浏览器都会让用户凭空丢掉整段对话 —— 而记录一直在库里。
     * 这个端点把号交回去，前端在「本地那个号读不出任何东西」时用它兜底。
     *
     * <p><b>它不改变会话号的归属。</b> 会话号是分组标签而不是凭证（归属一律按登录用户判定），
     * 生成、保存、换新都仍然留在前端。这里既不能指定「用我的某个号」，也不接受任何会话号参数 ——
     * 谁在问由认证上下文决定，回答的永远是「你自己的」那一个。<b>没有路径参数不是省略，
     * 而是刻意的</b>：一旦允许传号进来，它就从「查询」变成了「用别人的号探路」的入口。
     */
    @GetMapping("/conversations/latest")
    public LatestConversation latestConversation() {
        AssistantConversationLogService service = require(conversationLog);
        return new LatestConversation(service.latestConversationId(SecurityUtil.currentUserId()));
    }

    private static <T> T require(ObjectProvider<T> provider) {
        T service = provider.getIfAvailable();
        if (service == null) {
            throw new AssistantException(AssistantException.DISABLED,
                    "助手功能未开启，请联系管理员配置");
        }
        return service;
    }

    /**
     * 请求体的历史条目。
     *
     * <p>刻意<b>不</b>直接用它，而是映射成 {@link AssistantMessage}：{@code role} 在线上是
     * 小写字符串，映射时按 {@link AssistantMessage.Role#fromWire(String)} 归一，
     * 未识别的角色一律当用户内容（不能伪装成 {@code system}）。
     */
    public record HistoryTurn(String role, String text) {
    }

    /**
     * {@code POST /messages} 的请求体。
     *
     * <p>{@code history} 可空（单轮提问），且**只在服务端没有该会话的记录时才会被采用**；
     * {@code conversationId} 可空（前端还没有会话号时不该阻塞对话，只是审计串不起来）。
     */
    public record MessagesRequest(UUID conversationId, List<HistoryTurn> history, String text) {
    }

    /**
     * {@code GET /conversations/latest} 的响应体。
     *
     * <p>为什么用一个可能装着 {@code null} 的对象，而不是裸 UUID，也不是 204：
     * <b>「没有」必须和「有一个号」走同一条 200 路径。</b> 用 204 / 404 表达「没有」，
     * 会让前端把「服务端明确说没有」与「这次请求出错了」归成同一类，而这两件事的处置相反 ——
     * 前者应当安安静静地开一段新对话，后者必须让用户看见。
     * 在响应体里用 {@code null} 表达「没有」，正好是这个区别的载体。
     */
    public record LatestConversation(UUID conversationId) {
    }

    /**
     * 线上形状 → 领域对象。
     *
     * <p>这是这一层唯一还留着的历史相关代码，因为它处理的是<b>形状</b>而不是<b>来源</b>：
     * 大小写、未知角色、空元素都只在这里出现。来源与裁剪归编排层。
     */
    private static List<AssistantMessage> historyOf(MessagesRequest request) {
        if (request == null || request.history() == null) {
            return List.of();
        }
        return request.history().stream()
                .filter(turn -> turn != null)
                .map(turn -> new AssistantMessage(AssistantMessage.Role.fromWire(turn.role()), turn.text()))
                .toList();
    }
}
