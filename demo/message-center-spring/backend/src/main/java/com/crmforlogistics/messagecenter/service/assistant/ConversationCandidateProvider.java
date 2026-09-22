package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 会话候选的来源。两处用它，用的是<b>同一套</b>投影与上限：
 *
 * <ul>
 *   <li>{@link #recent(UUID)} —— 提问前注入的候选窗口（「最近在跟谁聊」）；</li>
 *   <li>{@link #search(UUID, String)} —— {@code conversation.search} 只读工具的检索，
 *       关键词可以命中候选窗口<b>之外</b>的会话，结果替换掉候选窗口，供下一轮引用。</li>
 * </ul>
 *
 * <h2>为什么投影写在这里而不是各写一遍</h2>
 * 「哪些字段能出系统边界」是一条合规约束（2026-09-22 口径 b）。如果候选注入与只读检索
 * 各写一份映射，早晚会有一边多带一个字段 —— 而那种漏法在测试里看不出来（两边都「正常工作」）。
 * 因此类型判定、渠道拆分、{@code last_text} 的排除，全部收口在这里。
 *
 * <h2>越权防线在 SQL 里，不在这里</h2>
 * 走 {@link ConversationMapper#listUnified} —— 归属、可见性（隐藏偏好）、授权（团队/授权表）
 * 都在那条 SQL 的 {@code where} 里。这里只做形状转换，不重复也不放宽任何一条。
 */
@Component
public class ConversationCandidateProvider {

    /**
     * 候选窗口与检索返回共用同一个上限。
     *
     * <p>刻意不让两者不同：若检索上限大于窗口上限，「查到了但下一轮引用不了」会成为一个
     * 只在多轮里才暴露的怪现象。同值则「检索结果能引用」是结构性成立的。
     */
    public static final int LIMIT = 20;

    private final ConversationMapper conversations;

    public ConversationCandidateProvider(ConversationMapper conversations) {
        this.conversations = conversations;
    }

    /** 提问前注入的候选：最近 {@value #LIMIT} 个会话（隐藏的按既有口径排除）。 */
    public ConversationCandidates recent(UUID userId) {
        return load(userId, null);
    }

    /** 按关键词在自己的会话里检索。空白关键词退化为「最近」，而不是返回空 —— 空结果会被模型读成「你没有会话」。 */
    public ConversationCandidates search(UUID userId, String query) {
        String trimmed = query == null ? "" : query.strip();
        return load(userId, trimmed.isEmpty() ? null : trimmed);
    }

    private ConversationCandidates load(UUID userId, String search) {
        if (userId == null) {
            return new ConversationCandidates(LIMIT, List.of());
        }
        List<ConversationMapper.UnifiedConversationRow> rows = conversations.listUnified(
                userId, search, false, null, null, null, null, LIMIT);
        return new ConversationCandidates(LIMIT,
                rows.size() > LIMIT ? rows.subList(0, LIMIT).stream().map(ConversationCandidateProvider::toItem).toList()
                        : rows.stream().map(ConversationCandidateProvider::toItem).toList());
    }

    /**
     * 行 → 候选条目。<b>这是个白名单投影</b>：只有下面出现的字段会离开这一层。
     *
     * <p>刻意不投影 {@code last_text}（最后一条消息正文）—— 候选会被渲染进提示词发给模型供应商，
     * 正文一旦进来就出了系统边界。同理不投影 {@code avatar_url}、{@code provider_conversation_key}
     * 这类与判断无关、却会稳定标识第三方的东西。
     */
    private static ConversationCandidates.Item toItem(ConversationMapper.UnifiedConversationRow row) {
        List<String> channels = row.channelTypes() == null || row.channelTypes().isBlank()
                ? List.of()
                : Arrays.stream(row.channelTypes().split(","))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        return new ConversationCandidates.Item(
                ConversationCandidates.Item.idOf(row.type(), row.id()),
                row.type(),
                row.displayName(),
                channels,
                row.lastMessageAt() == null ? null : row.lastMessageAt().toString(),
                row.unreadCount(),
                row.pinned());
    }
}
