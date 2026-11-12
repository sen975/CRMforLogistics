package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.UUID;

/**
 * 消息候选集：第五组候选，{@code contact.timeline} 的产物，{@code message.read} 的引用目标。
 *
 * <h2>它为什么必须单独一组，而不是复用会话候选</h2>
 * 消息是<b>会话/联系人的子对象</b>：一条会话下有几千条消息，同一个人的消息还会横跨多个渠道。
 * 更关键的是两者的「引用」是不同性质的东西：
 *
 * <ul>
 *   <li>{@code conversationRef}（{@code CONTACT:<uuid>} / {@code WECOM_GROUP:<uuid>}）指的是
 *       <b>一个会话容器</b>，可以反复操作（置顶、隐藏）；</li>
 *   <li>{@code messageRef}（{@code MESSAGE:<uuid>}）指的是<b>一条不可变的历史记录</b>。</li>
 * </ul>
 *
 * <p>混成一组会让「拿一个会话 id 去读消息」和「拿一条消息 id 去置顶」都能通过候选比对，
 * 然后各自在服务层以不同的方式失败。分成两组之后，这类错配在提问路径就断了 ——
 * 与 {@code TopicCandidates} 从联系人候选里分出来的理由完全一致。
 *
 * <h2>没有预置窗口，窗口只能来自 {@code contact.timeline}</h2>
 * 消息总量是十万级，预先算「最近 N 条」既贵又没有意义 —— 用户问「把那条消息调出来」时，
 * 他心里的「那条」必然是他<b>刚看到过</b>的那一条（时间线上的一条，或助手上一轮列出来的一条）。
 * 所以这里刻意不注册初始窗口：在 {@code contact.timeline} 被调用之前，
 * {@code messageRef} 一律校验不过。这正是「先查再读」该有的形状，
 * 而不是靠提示词提醒模型「别编 id」。
 *
 * <h2>字段白名单：只放消歧必需的三项</h2>
 * {@code id} / {@code direction} / {@code occurredAt}（外加 {@code status}）。
 * <b>消息正文不进候选</b>：候选会被渲染进提示词发给模型供应商，而正文是这次读操作要去取的东西 ——
 * 提前抄进候选等于让同一段文本占两份提示词预算（{@code TopicCandidates} 不抄摘要、同一条理由）。
 * 用户若要看内容，模型应当调用 {@code message.read}，而不是从候选里读一段缩略。
 */
public record MessageCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "message";

    /**
     * 一次读取最多带出的消息条数，同时也是 {@code contact.timeline} 单轮能产出的上界。
     *
     * <p>与联系人/会话的 20 同值：时间线本身就只回最近 {@code 20} 条（含通话），
     * 所以这个上限在当前实现下不会被撞到 —— 它不是节流阀，而是「万一将来有人放宽时间线，
     * 候选不会跟着无界」的兜底。上界与来源同量级，超出的部分也不会静默消失（见渲染里的截断说明）。
     */
    public static final int LIMIT = 20;

    private static final char TYPE_SEPARATOR = ':';
    private static final String PREFIX = "MESSAGE";

    public MessageCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        // 说清来源：这一组不是预置窗口，而是上一次读取的产物 —— 模型据此知道没读过就用不了 messageRef。
        return "消息清单（来自最近一次 contact.timeline）";
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
     * 一条消息候选。
     *
     * <p>时间戳用 ISO-8601 字符串而不是 {@code Instant}：提示词渲染用的是注入的 {@code ObjectMapper}，
     * 它不一定注册了 JavaTimeModule，存 {@code Instant} 会让序列化抛异常、候选被静默降级成空清单
     * —— 一个查不出原因的失忆（同 {@code ConversationCandidates.Item}）。
     */
    public record Item(String id, String direction, String status, String occurredAt) {
    }

    /** 消息 id → 候选 id。 */
    public static String idOf(UUID messageId) {
        return PREFIX + TYPE_SEPARATOR + messageId;
    }

    /**
     * 候选 id → 消息 id。
     *
     * <p>形状不对、或前缀不是 {@code MESSAGE}（例如有人把 {@code CONTACT:<uuid>} 塞进来）
     * 一律返回 {@code null}，由调用方转成参数错误 —— <b>不抛</b> {@code IllegalArgumentException}，
     * 因为那不是「服务端写错了」，而是「模型的参数不对」，两者对用户的指引完全不同。
     */
    public static UUID targetOf(String id) {
        if (id == null) return null;
        int at = id.indexOf(TYPE_SEPARATOR);
        if (at <= 0 || !PREFIX.equals(id.substring(0, at))) {
            return null;
        }
        try {
            return UUID.fromString(id.substring(at + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
