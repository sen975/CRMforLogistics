package com.crmforlogistics.messagecenter.service.assistant;

/**
 * 助手域内的文本截断：代理对安全，且区分「给人看」与「给模型看」。
 *
 * <h2>为什么值得单独一个类</h2>
 * 这个域里原本有 7 处「超过 N 个字符就切掉」，各自写着 {@code value.substring(0, N)}。
 * 分散实现带来两个问题，都不是风格问题：
 *
 * <ul>
 *   <li><b>代理对会切一半</b>：{@code substring} 按 UTF-16 码元切，切在 emoji 或生僻字上会
 *       留下一个孤立代理项，序列化后变成替换字符。用户只要发个表情就可能触发。</li>
 *   <li><b>静默截断</b>：给<b>模型</b>看的文本被截断时，「我只看到一半」和「就这么长」
 *       在它眼里一模一样。项目已经在 observation 那处想通了这件事（补了「结果过长已截断，
 *       如需更精确的结果请缩小检索范围」），但这个结论没推广到联系人画像那几处 ——
 *       于是模型会把半句画像当作完整画像来回答用户。</li>
 * </ul>
 *
 * <p>所以这里只留两个出口，调用方必须二选一，不再有「顺手 substring」这条路：
 * {@link #truncate} 用于人看或排障的文本，{@link #truncateForModel} 用于模型会当作完整
 * 事实消费的文本。
 *
 * <h2>哪些地方刻意<b>不</b>加标记</h2>
 * 标识类字段（显示名、职务、标签名、话题标题、候选清单里的备注）用裸截断：它们的用途是
 * <b>指认对象</b>，加个「已截断」既不改变指认结果，又会让清单变得难读。
 * 判据是一句话：<b>模型会不会把它当作一条完整的事实用来回答用户</b> —— 会则加，否则不加。
 */
final class Texts {

    /**
     * 默认截断标记。
     *
     * <p>刻意写成一句可读的话而不是「…」：省略号只会被模型照着念，而「已截断」这三个字
     * 能让它推断出「我看到的不是全部，应该缩小范围再查一次」。
     */
    private static final String TRUNCATION_MARKER = "…（已截断）";

    private Texts() {
    }

    /**
     * 裸截断：超出上限就切掉，不加任何标记。用于日志、审计、确认卡片、回放等<b>人看</b>的文本。
     *
     * <p>代理对安全：切点落在一个高代理项上时退一位 —— 宁可少一个字，也不产生半个字符。
     * {@code null} 原样返回（上游大量使用「取不到就是 null」的语义，返回空串会让两者混淆）。
     *
     * <p>上限 {@code <= 0} 时得到空串，这与改动前那 7 处 {@code substring(0, max)} 的行为一致
     * （区别只在负数：原来会抛 {@code StringIndexOutOfBoundsException}，现在按 0 处理）。
     */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 给模型看的截断：超出上限时切掉尾部并追加 {@link #TRUNCATION_MARKER}。
     *
     * <p><b>标记计入上限</b>，这一点是刻意的：这些上限是「给模型的预算」（各节之和决定
     * 提示词体积），不该因为多了句说明就突破。副作用是正文少几个字，可忽略；
     * 而反过来（标记额外追加）会让每一条「上限」都变成一个软约束。
     */
    static String truncateForModel(String value, int max) {
        return truncateForModel(value, max, TRUNCATION_MARKER);
    }

    /**
     * 同 {@link #truncateForModel(String, int)}，但用调用方自己的标记。
     *
     * <p>存在理由是 observation 那处的标记不只是「已截断」，它还告诉模型<b>下一步怎么办</b>
     * （「如需更精确的结果请缩小检索范围」）。那句可操作指引比标记本身更有价值，
     * 所以不能为了统一措辞把它降级成一句通用的「已截断」。
     */
    static String truncateForModel(String value, int max, String marker) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int body = max - marker.length();
        if (body <= 0) {
            // 上限连标记都放不下。正常配置不会走到这里（各节上限都在 20 以上），
            // 但真到了这一步要降级成裸截断，而不是拼出一个比上限还长的结果 ——
            // 「上限可以被突破」比「这里没有标记」更坏。
            return truncate(value, max);
        }
        return truncate(value, body) + marker;
    }
}
