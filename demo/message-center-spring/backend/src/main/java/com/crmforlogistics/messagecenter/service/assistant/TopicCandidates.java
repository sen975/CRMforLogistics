package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.UUID;

/**
 * AI 话题候选集：第四组候选，{@code contact.topics_read} 的产物。
 *
 * <h2>它为什么不是「又一组联系人」</h2>
 * 话题是<b>联系人的子对象</b>：同一个人下可以有多条话题，跨联系人还会重名（「报价」在十个客户身上都有）。
 * 因此它的 id 空间必须与联系人分开 —— 拿一个 {@code CONTACT:<uuid>} 去改话题标题，
 * 或者反过来拿话题 id 去查联系人画像，都是「一处改错、另一处静默接受」的典型形状。
 * 候选集分成两组之后，解析器按字段级 {@code x-candidateSet} 比对，这类错配在提问路径就断了。
 *
 * <h2>窗口是「最近一次读取的结果」，不是「最近活跃的话题」</h2>
 * 联系人／会话的候选窗口由 provider 按「最近往来」预先算好，因为它们的总量是几千条。
 * 话题不同：它是<b>按联系人取的</b>，一个用户的话题总量等于「他所有联系人的话题之和」，
 * 预先算出「最近 30 条」既不便宜也几乎没有意义（模型要改的总是它刚看的那个人的话题）。
 * 所以这里<b>不注册初始窗口</b>：候选集由 {@code contact.topics_read} 的结果替换进来，
 * 在它被调用之前 {@code topicRef} 一律校验不过 —— 这正是「先查再改」该有的样子。
 *
 * <h2>字段白名单：只放消歧必需的三项</h2>
 * {@code id} / {@code title} / {@code version}。摘要（{@code summary}）<b>不</b>进候选：
 * 它已经随工具结果进了同一条 observation，再抄进候选等于让同一段文本占两份提示词预算
 * （与 {@code ContactAssistantTools.describe} 刻意只做「点名 + 说有哪些节」同一条理由）。
 *
 * <p>{@code version} 进来不是冗余：{@code contact.topic_update} 要求带上它做乐观锁，
 * 而模型唯一能拿到它的地方就是这里。少了它，那个参数只能靠猜。
 *
 * <p>{@code title} 是<b>用户与模型共同决定的自由文本</b>（人工可编辑），与待办标题同一等级：
 * 只搬运、不转义，进提示词时整体声明为不可信数据。
 */
public record TopicCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "topic";

    /**
     * 一次读取最多带出的话题条数，同时也是 {@code contact.topics_read} 的返回上限。
     *
     * <p>与联系人／会话的 20 不同，取 30：话题是「一个人下的一批」，
     * 而「这个人最近聊了哪些事」天然要看全一点才答得准；同时它仍是个硬上限，
     * 保证候选不会把一个联系人的全部历史话题搬进提示词。
     */
    public static final int LIMIT = 30;

    private static final char TYPE_SEPARATOR = ':';
    private static final String PREFIX = "TOPIC";

    public TopicCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        // 说清来源：这一组不是预置窗口，而是上一次读取的产物 —— 模型据此知道没读过就用不了 topicRef。
        return "AI 话题清单（来自最近一次 contact.topics_read）";
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
     * 一条话题候选。
     *
     * <p>{@code version} 用 {@code long} 而不是 {@code Long}：它能进候选就说明这条话题是被读出来的，
     * 版本号必然有值。留一个可空的包装类型只会让调用方再写一次 {@code null} 分支。
     */
    public record Item(String id, String title, long version) {
    }

    /** 话题 id → 候选 id。 */
    public static String idOf(UUID topicId) {
        return PREFIX + TYPE_SEPARATOR + topicId;
    }

    /**
     * 候选 id → 话题 id。
     *
     * <p>形状不对、或前缀不是 {@code TOPIC}（例如有人把 {@code CONTACT:<uuid>} 塞进来）
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
