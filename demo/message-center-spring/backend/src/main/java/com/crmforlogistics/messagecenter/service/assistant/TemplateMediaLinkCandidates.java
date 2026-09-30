package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;

/**
 * 第八组候选集：<b>用户在这一轮消息里亲手写下的图片地址</b>。
 *
 * <h2>它解决的是一件具体的事：让助手能把一张网上的图收进素材库</h2>
 * 模板的图片头只认内部素材 id（{@code WhatsAppTemplateApplicationService#prepareMedia}
 * 把它当 UUID 解析），而素材只能由上传产生。此前上传只有一条路：用户在助手面板里
 * <b>贴一张图</b>，前端先传到服务商、再把素材 id 带进请求（见 {@link TemplateMediaCandidates}）。
 * 用户直接<b>写一个地址</b>的场景（「用 https://…/quote.png 建个模板」）走不通。
 *
 * <h2>为什么地址必须来自用户的原话，而不是工具的一个自由参数</h2>
 * 上传要由服务端<b>主动去下载那个地址</b>，所以「地址从哪来」直接决定了这一层的暴露面。
 * 若 {@code chatapp.template_media_upload} 收一个自由的 {@code imageUrl}，模型就能指向任意位置 ——
 * 它编得出一个不存在的地址（失败是噪声），也编得出一个存在的地址（那用户会得到一张
 * 自己没要过的图，而这张图会跟着模板一起提交给平台、撤不回）。
 *
 * <p>所以地址的来源<b>收紧成一句话</b>：只有用户这一轮原话里出现过的链接能进候选。
 * 这与 {@code chatapp.template_apply} 的 {@code mediaRef} 是同一套手法、同一条理由 ——
 * 模型编不出可用的引用，因为引用不在它手里，而在用户的输入里。
 *
 * <h2>候选 id 就是地址本身，刻意不做哈希或加密</h2>
 * {@code TemplateMediaCandidates} 那边用 {@code TEMPLATE_MEDIA:<uuid>}，因为素材 id 是库里的
 * 内部编号；这里的长串是用户自己刚写下的东西，模型照抄一遍即可 ——
 * 换成哈希会让模型抄错（长 hex 逐字复制是它最容易出错的地方），而错的代价是一次
 * 走到工具里才发现「这不是个地址」的调用。id 里带的是明文地址，模型看得见，
 * 但看得见没有额外坏处：它拿这个地址做不了别的事（{@code mediaRef} 只认 {@code TEMPLATE_MEDIA:}）。
 *
 * <h2>为什么上限是 3</h2>
 * 一条消息里写三个以上图片地址且都要求建模板是极罕见的情况，而候选清单是整段进提示词的成本。
 * 超出部分静默丢弃即可（同 {@link TemplateMediaCandidates} 的取舍）：用户真要四张，
 * 再发一条消息说清楚就行。
 */
public record TemplateMediaLinkCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "template_media_link";

    public static final String TYPE = "TEMPLATE_MEDIA_LINK";

    /** 一条消息里最多认几个地址。 */
    public static final int LIMIT = 3;

    private static final char TYPE_SEPARATOR = ':';

    public TemplateMediaLinkCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        return "用户这一轮写下的图片地址（候选清单）";
    }

    @Override
    public boolean contains(String id) {
        return id != null && items.stream().anyMatch(item -> item.id().equals(id));
    }

    /**
     * 一条候选地址。
     *
     * <p>只有 {@code id} 一个字段，而 id 就是 {@code TEMPLATE_MEDIA_LINK:<地址>} ——
     * 它是模型要照抄进 {@code linkRef} 的那个字符串。候选清单会逐字段序列化进提示词
     * （见 {@code AssistantPromptBuilder#renderItems}），所以这里多一个字段就是多一段
     * 让模型猜「这个字段能不能用」的空白。
     */
    public record Item(String id) {
    }

    public static String idOf(String url) {
        return TYPE + TYPE_SEPARATOR + url;
    }

    /** 从候选 id 里取出地址；形状不对返回 {@code null}（由调用方转成参数错误）。 */
    public static String urlOf(String id) {
        if (!looksLikeReference(id)) return null;
        String url = id.substring(TYPE.length() + 1).strip();
        return url.isEmpty() ? null : url;
    }

    /**
     * 该 id 是不是本集合的形状（前缀对）。
     *
     * <p>只判形状，不判「这个地址是不是合法」——后者是 {@code TemplateMediaLinkFetcher}
     * 的判据，而且是唯一一份：在这里再判一次，就会出现「形状检查比下载器松」这种
     * 能过入口、却在下载时才炸的分层。
     */
    public static boolean looksLikeReference(String id) {
        return id != null && id.startsWith(TYPE + TYPE_SEPARATOR);
    }
}
