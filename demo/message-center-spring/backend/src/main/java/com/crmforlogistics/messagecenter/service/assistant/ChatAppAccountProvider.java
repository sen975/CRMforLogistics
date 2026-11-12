package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 产出 {@link ChatAppAccountCandidates}：当前用户名下的 chatapp 账号。
 *
 * <h2>它为什么与「模板列表」是两个服务</h2>
 * 候选集的职责是「本轮模型<b>能引用</b>哪些账号」，模板列表的职责是「这些账号下已有哪些模板」。
 * 前者必须在<b>申请之前</b>就能拿到，后者是可选的查重。合成一个就会出现
 * 「想申请但还没查过模板 ⇒ 连账号候选都没有 ⇒ 申请工具填不出 accountRef」的死结。
 *
 * <h2>归属判据在 SQL 里，不在这里</h2>
 * {@code where owner_user_id = #{ownerId}} 由 {@link ChannelAccountMapper#findByOwnerAndChannelType}
 * 承担。这里不做二次过滤 —— 二次过滤会让人以为防线在 Java 里，从而在改动 SQL 时不再谨慎。
 *
 * <p>注意：本类刻意<b>不</b>用 {@code channel_accounts.owner_user_id} 之外的任何「归属」线索。
 * 项目里 {@code contacts.owner_user_id} 是死字段（从没人写过），但
 * {@code channel_accounts.owner_user_id} 不是 —— 它的写入路径在
 * {@code insertOwned} / {@code rebindOwned}，是活的。
 */
@Component
public class ChatAppAccountProvider {

    /**
     * 模板域认的两个 {@code channel_type} 取值。
     *
     * <p>权威定义在 {@code ChatAppChannelType}：{@code key()} 是 {@code chatapp}，
     * {@code aliases()} 是 {@code whatsapp}（历史命名，同一套 CAMS 凭证与网关）。
     * 这里写第二份是因为 {@code ChannelAccountMapper} 的查询按<b>单值</b>匹配，
     * 而两个值在库里都真实存在 —— 不是规则的第二份，是取值的穷举，两者都要查。
     *
     * <p>包级可见：{@link TemplateMediaProvider} 也要按同样两个值找账号，
     * 而「模板域认哪两个 channel_type」只该有一份。
     */
    static final List<String> TEMPLATE_CHANNEL_TYPES = List.of("chatapp", "whatsapp");

    private final ChannelAccountMapper accounts;

    public ChatAppAccountProvider(ChannelAccountMapper accounts) {
        this.accounts = accounts;
    }

    /** 该用户名下可申请模板的 chatapp 账号，最多 {@link ChatAppAccountCandidates#LIMIT} 条。 */
    public ChatAppAccountCandidates available(UUID userId) {
        // LinkedHashMap 去重：同一个账号不可能同时是 chatapp 与 whatsapp（channel_type 是单列），
        // 但两条查询结果合并时按 id 去重仍然是对的 —— 库里的历史数据曾经两种值并存过。
        Map<UUID, ChannelAccountEntity> found = new LinkedHashMap<>();
        for (String type : TEMPLATE_CHANNEL_TYPES) {
            for (ChannelAccountEntity account : accounts.findByOwnerAndChannelType(userId, type)) {
                found.putIfAbsent(account.getId(), account);
            }
        }
        List<ChatAppAccountCandidates.Item> items = new ArrayList<>();
        for (ChannelAccountEntity account : found.values()) {
            if (items.size() >= ChatAppAccountCandidates.LIMIT) break;
            items.add(new ChatAppAccountCandidates.Item(
                    ChatAppAccountCandidates.idOf(account.getId()),
                    nameOf(account),
                    account.getProviderScopeId() != null));
        }
        return new ChatAppAccountCandidates(ChatAppAccountCandidates.LIMIT, items);
    }

    /** 账号名：{@code name} → 退回首尾去空的 {@code account_identifier}（那通常是号码）。 */
    private static String nameOf(ChannelAccountEntity account) {
        String name = account.getName();
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        String identifier = account.getAccountIdentifier();
        return identifier == null || identifier.isBlank() ? account.getId().toString() : identifier.strip();
    }
}
