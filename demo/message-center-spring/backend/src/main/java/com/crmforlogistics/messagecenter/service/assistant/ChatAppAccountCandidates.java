package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.UUID;

/**
 * 第六组候选集：<b>当前用户名下的 chatapp（WhatsApp）账号</b>。
 *
 * <h2>为什么需要它，而不是让工具自己去找账号</h2>
 * 「申请模板」这个动作在服务层的签名是
 * {@code createPrivate(accountId, command, actorUserId, traceId)} —— 它<b>要一个账号 id</b>，
 * 而账号 id 不该由模型凭空写出来。本项目的既定做法是：凡是要模型指一个对象的工具，
 * 那个对象就必须先出现在一组<b>有界候选</b>里（见 {@link CandidateSet}），
 * 工具在入参 schema 上用 {@code x-candidateSet} 声明引用哪一组，解析器逐项比对。
 *
 * <p>所以这里新建第六组，而不是把账号 id 当成一个普通字符串参数 —— 后者等于把
 * 「编造一个 uuid」当成合法输入，而它恰好会命中服务层的
 * {@code requireOwnedBusinessAppAccount} 才被拒，属于「先放进来再拦」，
 * 与 {@code message.send_email} 刻意不给收件地址参数是同一个取舍得出的相反结论。
 *
 * <h2>id 为什么带类型前缀</h2>
 * 与 {@link ConversationCandidates} 同理：候选 id 进提示词、又被解析器比对，
 * 加前缀让「拿会话 ref 去当账号 ref」这类错配在提问路径就断开，而不是等到服务层报类型错。
 */
public record ChatAppAccountCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "chatapp_account";

    public static final String TYPE = "CHATAPP_ACCOUNT";

    /**
     * 条数上限。
     *
     * <p>4 而不是 20：一个人名下的 chatapp 账号是个位数（实测库里两个 owner 各 1 个），
     * 而同组候选还要和其余五组共享提示词预算。数量本身不是防线 ——
     * 防线是 {@link com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper}
     * 那条 {@code where owner_user_id = ?}。
     */
    public static final int LIMIT = 4;

    private static final char TYPE_SEPARATOR = ':';

    public ChatAppAccountCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        return "当前用户的 WhatsApp（chatapp）账号（候选清单）";
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
     * 一条账号候选。
     *
     * <p>{@code name} 是账号名（用户可改的自由文本，如「悦为」），与联系人备注同一等级：
     * 只搬运、不转义，进提示词时整体声明为不可信数据。
     *
     * <p>{@code templateScopeReady} 是「这个账号在我们库里绑了模板空间没有」
     * （{@code provider_scope_id != null}）。它<b>不是</b>「这个账号能不能用」：
     * 空间由账号凭证里的 {@code custSpaceId} 决定，申请时服务层会先补绑一次
     * （见 {@code WhatsAppTemplateApplicationService#createForActor}），
     * 所以未绑空间的账号照样能申请，只有凭证不可用才会失败。
     *
     * <p>把它暴露出来的用途是让模型在申请之前能说清「这个账号还没绑空间，
     * 申请时会先按它的凭证绑定」，而不是发起一次它无从解释的调用、再把服务层的
     * 错误码原样转述给用户 —— 那句话对用户没有下一步。
     */
    public record Item(String id, String name, boolean templateScopeReady) {
    }

    public static String idOf(UUID accountId) {
        return TYPE + TYPE_SEPARATOR + accountId;
    }

    /** 从候选 id 里取账号 uuid；形状不对返回 {@code null}（由调用方转成参数错误）。 */
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
}
