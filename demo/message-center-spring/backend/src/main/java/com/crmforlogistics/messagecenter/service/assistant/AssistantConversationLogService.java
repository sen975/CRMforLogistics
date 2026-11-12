package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 助手对话的落地与读回：把「用户说了什么、助手回了什么」写成追加行，使面板能跨刷新/重开接上，
 * 并让**服务端**能装配下一轮的会话语境。
 *
 * <h2>三个读方法，三个消费者</h2>
 * <ul>
 *   <li>{@link #replay} —— 给**用户**看：从会话开头正序读，带 {@code kind} 与时间戳。</li>
 *   <li>{@link #recentForPrompt} —— 给**模型**看：从会话尾部取最近若干条，只留角色与正文。</li>
 *   <li>{@link #latestConversationId} —— 给**前端**用：把「上次在哪个会话」交回去，让它接得上。</li>
 * </ul>
 * 不合并成一个方法，因为三者要的东西不同：时间戳对模型没有意义，而只给用户最近几条会让
 * 面板缺失开头。共用的是 mapper 里那一份归属谓词 —— 归属条件不会写第二遍。
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
    private final AssistantConversationLifecycleService lifecycle;

    public AssistantConversationLogService(AssistantConversationMessageMapper mapper) {
        this(mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantConversationLogService(AssistantConversationMessageMapper mapper,
                                           AssistantConversationLifecycleService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
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
        if (lifecycle != null) {
            lifecycle.requireActive(userId, conversationId);
        }
        try {
            AssistantConversationMessageEntity entity = new AssistantConversationMessageEntity();
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setRole(role);
            entity.setKind(kind);
            entity.setText(Texts.truncate(text, TEXT_MAX));
            mapper.insert(entity);
            if (lifecycle != null) {
                lifecycle.touch(userId, conversationId);
            }
        } catch (RuntimeException e) {
            log.error("event=assistant.conversation_log_write_failed userId={} conversationId={} role={} kind={}",
                    userId, conversationId, role, kind, e);
        }
    }

    /**
     * 回放某会话的历史，时间正序。面板打开时用它接上上次的对话。
     *
     * <p>跨用户的会话读不到 —— 归属条件在 SQL 里，不靠调用方记得校验。
     */
    public List<Message> replay(UUID userId, UUID conversationId, int limit) {
        if (userId == null || conversationId == null) {
            return List.of();
        }
        if (lifecycle != null) {
            lifecycle.requireReadable(userId, conversationId);
        }
        int bounded = Math.max(1, Math.min(limit, MAX_REPLAY));
        return mapper.listByConversation(userId, conversationId, bounded).stream()
                .map(row -> new Message(row.getRole(), row.getKind(), row.getText(), row.getCreatedAt()))
                .toList();
    }

    /**
     * 装配下一轮请求所需的会话语境：**最近**的若干条，时间正序，只含角色与正文。
     *
     * <p>为什么从尾部取而不是从头部：语境是用来理解指代的（「那条」「改成后天」），
     * 指代永远指向刚说过的话。长会话里从头部取会把刚说过的话全部丢掉 ——
     * 而那时模型看到的是一个开头就断掉的对话，且它无从察觉。
     *
     * <p>{@code limit} 由调用方给（通常等于 {@code assistant.max-history-turns}）：
     * 「需要多少语境」是提示词预算的知识，不属于这一层。这里只保证不越过 {@link #MAX_REPLAY}。
     *
     * <p>空文本行不会出现 —— {@link #append} 写入前就过滤了。因此返回条数通常就等于
     * {@code limit} 与库中条数的较小者。
     */
    public List<AssistantMessage> recentForPrompt(UUID userId, UUID conversationId, int limit) {
        if (userId == null || conversationId == null) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, MAX_REPLAY));
        List<AssistantMessage> recent = new ArrayList<>();
        for (AssistantConversationMessageEntity row : mapper.listRecentByConversation(userId, conversationId, bounded)) {
            // role 在库里是 'user' / 'assistant' 两个字面量（见 V85 的 CHECK 约束），
            // fromWire 未识别时落 USER —— 这个兜底在这里不会触发，但保持一致比依赖约束更稳。
            recent.add(new AssistantMessage(AssistantMessage.Role.fromWire(row.getRole()), row.getText()));
        }
        // 上面是倒序（最新在前），交出去要正序：提示词里对话从旧到新读。
        Collections.reverse(recent);
        return List.copyOf(recent);
    }

    /** Recent persisted rows with stable cursor metadata for context compaction. */
    public List<AssistantConversationMessageEntity> recentRowsForPrompt(UUID userId, UUID conversationId, int limit) {
        if (userId == null || conversationId == null) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, MAX_REPLAY));
        List<AssistantConversationMessageEntity> rows = new ArrayList<>(
                mapper.listRecentByConversation(userId, conversationId, bounded));
        Collections.reverse(rows);
        return List.copyOf(rows);
    }

    /**
     * 用户最近说过话的那个会话号，一个都没有时返回 {@code null}。
     *
     * <h2>它补的是哪一个洞</h2>
     * 会话号原本只活在浏览器里（前端 {@code localStorage}）。于是清缓存、换浏览器、换设备、
     * 开无痕窗口都会让用户「凭空丢掉整段对话」—— 而记录一直在库里，丢的只是那个号。
     * 这个方法让服务端把号交回去：<b>库里查得到的东西，不该只因为浏览器忘了就问不回来。</b>
     *
     * <h2>为什么不建一张「用户当前在哪个会话」的表</h2>
     * 那需要一次迁移，而这个问题在既有数据里已经有答案。代价是它有一种情况答不出来 ——
     * <b>用户点了「新会话」却一句话都还没说</b>：那条新会话在库里没有任何行，服务端看不见它，
     * 问它只会得到上一个会话（用户刚主动丢掉的那段）。所以「刚开、还没说话」这个意图必须由
     * 前端自己记着（见前端 {@code useAssistant} 里的新会话标记）——
     * <b>不要试图在这里兜住它</b>：服务端手里没有能区分「用户主动开了新会话」与
     * 「这是台新设备」的信息，而这两件事要的处置相反。
     *
     * <p>返回 {@code null} 与「查不到」是同一件事：这个用户还没有任何对话。
     * 调用方据此知道「没什么可接的」，而不是「服务出错了」—— 前者要安静地开一段新对话，
     * 后者必须说出来。
     */
    public UUID latestConversationId(UUID userId) {
        if (userId == null) {
            return null;
        }
        if (lifecycle != null) {
            return lifecycle.latestVisibleId(userId);
        }
        return mapper.latestConversationId(userId);
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
}
