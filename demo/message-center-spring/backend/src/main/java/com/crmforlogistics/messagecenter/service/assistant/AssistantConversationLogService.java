package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 助手对话的落地：把「用户说了什么、助手回了什么」写成追加行，使面板能跨刷新/重开接上。
 *
 * <h2>它和审计表的分工</h2>
 * {@link AssistantAuditService} 回答「AI 做了什么」（决策、参数、策略、结果、模型、耗时），
 * 这张表回答「我们互相说了什么」。两者刻意不合并：一轮里被拒的模型输出不该出现在对话里，
 * 而用户的一句追问也可能根本没产生决策。混成一张表会让两个问题都变模糊。
 *
 * <h2>写失败不抛</h2>
 * 与 {@link AssistantAuditService} 同一条理由：工具可能已经执行成功，
 * 此时因为「日志写不进去」而告诉用户失败，比少一条记录严重得多。
 * 代价同样要写清楚 —— <b>极端情况下会丢一条对话记录</b>，因此失败日志用 ERROR 并带全字段。
 *
 * <h2>没有会话号就不写</h2>
 * {@code conversationId} 来自前端。缺失时（老版本前端、或调用方没传）直接跳过：
 * 把不同会话混进同一个 NULL 分组，会让「回放」给出一次杂糅的、看起来像真的的历史，
 * 那比没有历史更危险。
 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantConversationLogService {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationLogService.class);

    /** 单次回放的上限。更长的历史对「接上上次」没有价值，只会撑大响应体。 */
    private static final int MAX_REPLAY = 200;

    /** 正文的防御性上限。正常路径不会触发 —— 触发说明上游出了问题，值得在日志里看见。 */
    private static final int TEXT_MAX = 8000;

    private final AssistantConversationMessageMapper mapper;

    public AssistantConversationLogService(AssistantConversationMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 记一条用户原话。 */
    public void appendUser(UUID userId, UUID conversationId, String text) {
        append(userId, conversationId, "user", null, text);
    }

    /** 记一条助手回复。{@code kind} 是这一轮的终点，与 {@link AssistantTurnResult.Kind} 同源。 */
    public void appendAssistant(UUID userId, UUID conversationId, AssistantTurnResult.Kind kind, String text) {
        append(userId, conversationId, "assistant", kind == null ? null : kind.name(), text);
    }

    private void append(UUID userId, UUID conversationId, String role, String kind, String text) {
        if (userId == null || conversationId == null) {
            return;
        }
        if (text == null || text.isBlank()) {
            // 空白回话不写：它在界面上是一个空气泡，在回放里是一条无信息的记录。
            return;
        }
        try {
            AssistantConversationMessageEntity entity = new AssistantConversationMessageEntity();
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setRole(role);
            entity.setKind(kind);
            entity.setText(truncate(text, TEXT_MAX));
            mapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("event=assistant.conversation_log_write_failed userId={} conversationId={} role={} kind={}",
                    userId, conversationId, role, kind, e);
        }
    }

    /**
     * 回放某会话的历史，时间正序。
     *
     * <p>跨用户的会话读不到 —— 归属条件在 SQL 里，不靠调用方记得校验。
     */
    public List<Message> replay(UUID userId, UUID conversationId, int limit) {
        if (userId == null || conversationId == null) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, MAX_REPLAY));
        return mapper.listByConversation(userId, conversationId, bounded).stream()
                .map(row -> new Message(row.getRole(), row.getKind(), row.getText(), row.getCreatedAt()))
                .toList();
    }

    /**
     * 回放用的一条消息。
     *
     * <p>{@code kind} 对用户行恒为 {@code null} —— 用户说的话没有「终点」这回事。
     * 刻意<b>不</b>带 {@code proposal} / {@code pendingActionId}：那张卡片能不能点取决于
     * 服务端待确认动作的**当前状态**，靠历史恢复它等于诱导用户点一个大概率已失效的按钮。
     */
    public record Message(String role, String kind, String text, Instant createdAt) {
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
