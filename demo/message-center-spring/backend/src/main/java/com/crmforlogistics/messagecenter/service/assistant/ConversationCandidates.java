package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.UUID;

/**
 * 会话候选集：第二组候选，也是「只读检索如何突破候选集边界」的落点。
 *
 * <h2>id 是复合键，因为「会话」在这个系统里不是一张表</h2>
 * 统一会话列表由两个来源 {@code union} 而成：{@code CONTACT}（联系人维度的会话）与
 * {@code WECOM_GROUP}（企微群）。两者 id 空间可能重合，因此候选 id 必须带上类型，
 * 形如 {@code CONTACT:<uuid>} / {@code WECOM_GROUP:<uuid>} —— 与
 * {@code ConversationPreferenceService.authorize(...)} 的 {@code (targetType, targetId)}
 * 二元组一一对应，工具拿到之后可以直接拆开，不需要再猜。
 *
 * <h2>字段白名单：{@code last_text} 刻意不在投影里</h2>
 * 统一会话查询同时返回 {@code last_text}（最后一条消息正文）。按 2026-09-22 定下的合规口径
 * （选项 b：<b>只允许结构化事实与摘要进模型，原文与通话转写一律不出边界</b>），
 * 这里<b>不</b>把它放进候选：候选会被渲染进提示词发给模型供应商，正文一旦进来就出去了。
 *
 * <p>这是个显式排除，不是遗漏 —— 加字段时请回答同一个问题：这条内容出系统边界了吗？
 */
public record ConversationCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "conversation";

    public static final String TYPE_CONTACT = "CONTACT";
    public static final String TYPE_WECOM_GROUP = "WECOM_GROUP";

    /** 群名在渲染前已由 SQL 侧的掩码表达式处理过，这里不再二次加工（会掩盖而不消除风险）。 */
    private static final char TYPE_SEPARATOR = ':';

    public ConversationCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        return "当前用户的会话（候选清单）";
    }

    @Override
    public boolean contains(String id) {
        return id != null && items.stream().anyMatch(item -> item.id().equals(id));
    }

    /** 按候选 id 取一条。取不到返回 {@code null}。 */
    public Item find(String id) {
        if (id == null) return null;
        return items.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * 一条会话候选。
     *
     * <p>{@code name} 是联系人显示名或群名（用户可控的自由文本，如联系人备注），
     * 与待办标题同一等级：只搬运、不转义，进提示词时整体声明为不可信数据。
     * {@code lastMessageAt} 用 ISO-8601 字符串而不是 {@code Instant}：
     * 提示词渲染用的是注入的 {@code ObjectMapper}，它不一定注册了 JavaTimeModule，
     * 存 {@code Instant} 会让序列化抛异常、候选被静默降级成空清单 —— 一个查不出原因的失忆。
     */
    public record Item(String id, String type, String name, List<String> channels,
                       String lastMessageAt, int unread, boolean pinned) {

        public Item {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }

        public static String idOf(String type, UUID targetId) {
            return type + TYPE_SEPARATOR + targetId;
        }
    }

    /** 从候选 id 里取类型（{@code CONTACT} / {@code WECOM_GROUP}）；形状不对返回 {@code null}。 */
    public static String typeOf(String id) {
        int at = id == null ? -1 : id.indexOf(TYPE_SEPARATOR);
        if (at <= 0) return null;
        String type = id.substring(0, at);
        return TYPE_CONTACT.equals(type) || TYPE_WECOM_GROUP.equals(type) ? type : null;
    }

    /** 从候选 id 里取目标 id；形状不对返回 {@code null}（由调用方转成参数错误，不抛 IllegalArgumentException）。 */
    public static UUID targetOf(String id) {
        int at = id == null ? -1 : id.indexOf(TYPE_SEPARATOR);
        if (at <= 0) return null;
        try {
            return UUID.fromString(id.substring(at + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 类型的中文名。
     *
     * <p>放在这里而不是各调用点：它出现在两处面向用户/模型的文本里（工具回话、确认卡片摘要），
     * 分头写必然会出现「一处改了另一处没改」，而那种不一致没人会注意到。
     */
    public static String typeLabel(String type) {
        return switch (type) {
            case TYPE_CONTACT -> "联系人";
            case TYPE_WECOM_GROUP -> "企业微信群";
            default -> type;
        };
    }
}
