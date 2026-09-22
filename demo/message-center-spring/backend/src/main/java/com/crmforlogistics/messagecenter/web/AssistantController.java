package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationLogService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantConversationService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantMessage;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPendingActionService;
import com.crmforlogistics.messagecenter.service.assistant.AssistantRequestGuard;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import org.springframework.beans.factory.ObjectProvider;
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
 * 三个端点都只通过 {@link SecurityUtil#currentUserId()} 取用户。<b>请求体里没有任何身份字段</b>，
 * 即使有人塞 {@code userId} 进来，它也不会被读取（{@link MessagesRequest} 里根本没有这个分量）。
 * {@code /api/assistant/**} 落在既有 {@code SecurityConfig} 的
 * {@code .requestMatchers("/api/**").authenticated()} 之下，无需改动安全配置。
 *
 * <h2>确认接口只接受 pendingActionId</h2>
 * 不接受工具名或参数的重放：参数以服务端落库的那一份为准，并在执行前重新校验。
 * 若允许前端回传参数，确认就退化成「前端说了算」，而落库授权这件事就白做了。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    /** 一次回放的消息条数上限。前端只用来「接上上次」，不需要全量历史。 */
    private static final int REPLAY_LIMIT = 100;

    private final ObjectProvider<AssistantConversationService> conversations;
    private final ObjectProvider<AssistantPendingActionService> pendingActions;
    private final ObjectProvider<AssistantConversationLogService> conversationLog;
    private final AssistantRequestGuard guard;

    public AssistantController(ObjectProvider<AssistantConversationService> conversations,
                               ObjectProvider<AssistantPendingActionService> pendingActions,
                               ObjectProvider<AssistantConversationLogService> conversationLog,
                               AssistantRequestGuard guard) {
        this.conversations = conversations;
        this.pendingActions = pendingActions;
        this.conversationLog = conversationLog;
        this.guard = guard;
    }

    /** 一轮对话：解析 → 追问 / 直接执行 / 落待确认。 */
    @PostMapping("/messages")
    public AssistantTurnResult messages(@RequestBody(required = false) MessagesRequest request) {
        AssistantConversationService service = require(conversations);
        UUID userId = SecurityUtil.currentUserId();
        AssistantRequestGuard.NormalisedRequest normalised = guard.normalise(
                historyOf(request), request == null ? null : request.text());
        return service.respond(userId, request == null ? null : request.conversationId(),
                normalised.history(), normalised.text());
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
     * <p>{@code history} 可空（单轮提问）；{@code conversationId} 可空（前端还没有会话号时
     * 不该阻塞对话，只是审计串不起来）。
     */
    public record MessagesRequest(UUID conversationId, List<HistoryTurn> history, String text) {
    }

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
