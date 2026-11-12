package com.crmforlogistics.messagecenter.service.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 第七组候选集：<b>用户在这一轮消息里亲手带上来的模板素材</b>。
 *
 * <h2>它和其它六组不是同一种东西</h2>
 * 前六组（联系人、会话、消息、待办、主题、chatapp 账号）都是<b>服务端检索出来的</b> ——
 * 「候选从哪来」那一层在 provider 里，靠 SQL 的 {@code where user_id} 划定归属。
 * 这一组不一样：{@link #items()} 里的东西是<b>用户在请求体里指定的素材 id</b>，
 * 服务端只做归属校验（{@code findByIdAndChannelAccountId}）。
 *
 * <p>这个差别不改变 {@link CandidateSet} 的接口形状 —— 它本来就不声明来源
 * （见该类注释「刻意不提供 {@code resolve(userId, text)}」），于是「用户随手递来的东西」
 * 和「检索出来的东西」可以是同一种候选，工具的 {@code x-candidateSet} 声明也照旧。这是这套
 * 抽象<b>唯一</b>一次被用在「非检索来源」上，所以特地写在这里。
 *
 * <h2>为什么图片必须走候选，而不是让模型写一个 uuid</h2>
 * {@code WhatsAppTemplateApplicationService.prepareMedia} 只认<b>内部素材 id</b>：
 * 它把 {@code mediaAssetId} 当 UUID 解析，再要求这条素材属于同一个账号、
 * 状态是 {@code UPLOADED}、格式和 header 对得上。模型既能编出 uuid、也读不到素材库，
 * 所以这里把「素材从哪来」收紧成一句话：<b>只有用户当轮发来的那一张能进候选</b>。
 *
 * <h2>为什么不给「从素材库里挑一张」这条路</h2>
 * 数据层的素材库是现成的（{@code template_media_assets} 有 {@code ORPHANED} 状态，
 * 还有一条 {@code (channel_account_id, asset_status, created_at desc)} 的专用索引），
 * 但 {@link com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper} 里
 * <b>没有按账号列素材的方法</b>，前端也没有任何独立于模板编辑器的素材入口。
 * 也就是说「素材库」目前只是数据层的一个形状，没有可用的读取路径。
 * 要让助手从库里挑，得先补那条查询与它的界面，属于独立批次 ——
 * 本轮先把「用户当面给图」这条最短路径打通。
 */
public record TemplateMediaCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "template_media";

    public static final String TYPE = "TEMPLATE_MEDIA";

    /**
     * 条数上限。
     *
     * <p>1 而不是更多：一个模板只有一个 HEADER，一张图对应一个模板。用户要建第二个模板，
     * 再发一张即可。<b>要放开到多张时改这里</b>，同时前端「附件直到用户移除才清空」那条约定
     * 也要跟着看一遍 —— 多张会让「哪个素材配哪个模板」变成模型要判断的事。
     */
    public static final int LIMIT = 1;

    /**
     * 从<b>素材库里检索</b>出来的候选条数上限 —— 与上面的 {@link #LIMIT} 是两件事。
     *
     * <p>{@code limit} 是这个 record 的<b>实例</b>字段（不是只能取 {@link #LIMIT}），
     * 所以同一个形状可以承载两种来源：用户当轮发来的那一张（1 条）与库里翻出来的一批（见下）。
     * 载体相同是刻意的 —— 模型的 {@code mediaRef} 只认一种 id 形状，而这两种来源产出的
     * 素材在服务层看来完全一样（同一个账号、同样 {@code UPLOADED}）。
     *
     * <p>为什么不是 1：从库里挑的前提就是「有几张可选」。而它也不能无界 ——
     * 候选清单整段进提示词，条数直接是一次请求的上下文成本。10 条够挑。
     */
    public static final int LIBRARY_LIMIT = 10;

    private static final char TYPE_SEPARATOR = ':';

    public TemplateMediaCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        return "用户这一轮发来的素材（候选清单）";
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
     * 一条素材候选。
     *
     * <p>{@code format} 是 {@code IMAGE} / {@code VIDEO} / {@code DOCUMENT}，与服务层的
     * {@code HeaderFormat} 同名 —— 模型要拿它去填工具的 {@code headerFormat}，
     * 所以这里不能自造词。
     *
     * <p>{@code contentType} 与 {@code sizeBytes} 是给模型判断用的：平台对图片有 5MB 上限，
     * 说出来比让它在一次注定失败的调用之后转述错误码要好。
     */
    public record Item(String id, String format, String contentType, long sizeBytes) {
    }

    public static String idOf(UUID assetId) {
        return TYPE + TYPE_SEPARATOR + assetId;
    }

    /** 从候选 id 里取素材 uuid；形状不对返回 {@code null}（由调用方转成参数错误）。 */
    public static UUID targetOf(String id) {
        int at = id == null ? -1 : id.indexOf(TYPE_SEPARATOR);
        if (at <= 0) return null;
        try {
            return UUID.fromString(id.substring(at + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 该 id 是不是本集合的形状（前缀对）。只判形状，不判存在 —— 存在由 {@link #contains} 判。 */
    public static boolean looksLikeReference(String id) {
        int at = id == null ? -1 : id.indexOf(TYPE_SEPARATOR);
        return at > 0 && TYPE.equals(id.substring(0, at));
    }

    /**
     * 并上另一组素材候选：<b>用户当轮发来的那张</b>与<b>工具刚从库里翻出来的一批</b>都要留着。
     *
     * <h2>为什么不能像其它候选那样直接替换</h2>
     * {@code AssistantContext#withCandidateSet} 的语义是「同名替换」，对检索类候选是对的
     * （见那个方法的注释：追加会让候选随轮次无限增长）。素材这里是另一回事：
     * 两组候选来自<b>同一轮里两件独立的事</b> —— 用户在消息里贴了一张图，同时又问了「库里有什么」。
     * 直接替换会让前一张悄悄失效，而此时模型手上那个 {@code mediaRef} 刚刚还在候选里 ——
     * 它不会得到「引用已失效」，只会得到「这个 id 不在候选里」，于是把一句
     * 无从解释的话转述给用户。合并保住的正是这条链。
     *
     * <p>去重按 id：同一张素材既可能是附件、也可能在库里翻到，重复一条会让模型以为有两张图。
     * 截断到两条来源的容量之和 —— 合并后的集合仍然有界。
     */
    public TemplateMediaCandidates mergedWith(TemplateMediaCandidates other) {
        if (other == null || other.items().isEmpty()) return this;
        if (items.isEmpty()) return other;
        Map<String, Item> merged = new LinkedHashMap<>();
        for (Item item : items) {
            merged.put(item.id(), item);
        }
        for (Item item : other.items()) {
            merged.putIfAbsent(item.id(), item);
        }
        List<Item> combined = new ArrayList<>(merged.values());
        int capacity = limit + other.limit();
        if (combined.size() > capacity) {
            combined = combined.subList(0, capacity);
        }
        return new TemplateMediaCandidates(capacity, combined);
    }
}
