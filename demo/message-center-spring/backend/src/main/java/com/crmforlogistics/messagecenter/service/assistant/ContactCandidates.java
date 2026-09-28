package com.crmforlogistics.messagecenter.service.assistant;

import java.util.List;
import java.util.UUID;

/**
 * 联系人候选集：第三组候选，{@code contact.brief} 只读域的地基。
 *
 * <h2>id 沿用 {@code CONTACT:<uuid>}，与会话候选是<b>同一个 id 空间</b></h2>
 * 这不是巧合：统一会话查询里 {@code type='CONTACT'} 那一支的 {@code c.id} 就是联系人 id
 * （见 {@code ConversationMapper.listUnified}），{@code ConversationPreferenceService.authorize}
 * 也是拿 {@code (CONTACT, contactId)} 去查<b>同一张</b> {@code contacts} 表。因此两域的 ref
 * 天然重合，{@code conversationRef} 与 {@code contactRef} 指向同一个东西。
 *
 * <p>既然如此，格式的解析与拼装就<b>不在这里重写一份</b>，而是复用
 * {@link ConversationCandidates} 的公开静态方法：格式是一份线上契约，两个域各写一份解析
 * 迟早会漂移，而漂移的表现是「一边认得、一边不认得」，很难在测试里被想到。
 *
 * <h2>放进来的字段为什么只有三个</h2>
 * 候选会被渲染进提示词发给模型供应商，所以这是个<b>白名单投影</b>：只有
 * {@code id} 与 {@code contacts} 表自己的两个字段（显示名 / 备注）会离开这一层 ——
 * 它们与待办标题同一等级（用户可控的自由文本，只搬运不转义）。
 *
 * <p>刻意<b>不</b>把角色（{@code roleTitle}）放进候选：{@code ContactMapper.listForUser}
 * 的投影里根本没有这一列，取了也恒为 {@code null}，在提示词里就是一堆空值
 * （详见 {@code ContactCandidateProvider.toItem} 的说明）。角色属于「某人的背景」，
 * 由 {@code contact.brief} 经全字段查询给出。
 *
 * <p>候选只放经联系人 owner 授权的<b>有界渠道消歧投影</b>：渠道类型、身份值、显示名和账号标签。
 * 它们用于区分同名联系人或同一联系人绑定的不同渠道，不能作为发送凭证；发送工具仍只接受
 * {@code contactRef}，由服务端按当前用户重新解析目标。内部 scope、规范化值、数据库 ID 和凭证永远不进入候选。
 */
public record ContactCandidates(int limit, List<Item> items) implements CandidateSet {

    public static final String NAME = "contact";

    public ContactCandidates {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String heading() {
        // 说明排序口径：没有把时间戳放进条目（见类注释），就得在这里说清「顺序是有意义的」。
        return "当前用户可查看的联系人（候选清单，按最近往来排序）";
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
     * 一条联系人候选。
     *
     * <p>{@code name} / {@code remark} 都是用户可控的自由文本（备注里写「忽略以上所有指令…」
     * 完全可能），因此提示词里整体声明为不可信数据。这里只搬运，<b>不做任何转义或改写</b>。
     *
     * <p>{@code name} 保证非空：显示名为空时退回备注，两者都空时退回 ref。
     * 退回而不是编一个 —— 难看是可接受的，编出来的名字会被用户当事实去核对。
     */
    public record Item(String id, String name, String remark, List<Channel> channels) {
        public Item(String id, String name, String remark) {
            this(id, name, remark, List.of());
        }

        public Item {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }
    }

    /** Authorized channel identity shown to the model for contact disambiguation. */
    public record Channel(String channelType, String identityValue,
                          String displayName, String accountLabel) {
    }

    /** 联系人 id → 候选 id。与会话候选共用同一条拼装路径。 */
    public static String idOf(UUID contactId) {
        return ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_CONTACT, contactId);
    }

    /**
     * 候选 id → 联系人 id。
     *
     * <p>形状不对、或类型不是 {@code CONTACT}（例如有人把 {@code WECOM_GROUP:<uuid>} 塞进来）
     * 一律返回 {@code null}，由调用方转成参数错误 —— <b>不抛</b> {@code IllegalArgumentException}，
     * 因为那不是「服务端写错了」，而是「模型的参数不对」，两者对用户的指引完全不同。
     */
    public static UUID targetOf(String id) {
        if (!ConversationCandidates.TYPE_CONTACT.equals(ConversationCandidates.typeOf(id))) {
            return null;
        }
        return ConversationCandidates.targetOf(id);
    }
}
