package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 渠道类型注册项 —— 「新增一个渠道要改哪些核心代码」的答案。
 *
 * <h2>为什么需要它</h2>
 * 改造前，渠道知识散落在 {@link ChannelAccountService} 里：
 * {@code CREDENTIAL_KEYS} 凭证字段表、{@code switch(channelType)} 同步分派、
 * {@code normalizeIdentifier} 的 if-else、以及写死的 {@code "chatapp"} 判断。
 * 结果是 <b>新增一个渠道必须回头修改核心服务</b>，且改动分散在四五处。
 *
 * 现在每个渠道把自己这部分知识实现成一个 Spring bean，核心只通过
 * {@link ChannelTypeRegistry} 按 {@link #key()}/{@link #aliases()} 查表，
 * 新增渠道 = 新增一个实现类，核心零改动。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>实现类必须是 Spring bean（{@code @Service}），由 {@link ChannelTypeRegistry}
 *       按 {@code List<ChannelType>} 注入装配。</li>
 *   <li>{@link #key()} 与 {@link #aliases()} 在所有实现之间必须唯一，
 *       重复会在启动时快速失败（见 {@link ChannelTypeRegistry}）。</li>
 *   <li>{@code assert*} 钩子在违规时抛 {@link ChannelAccountException}，
 *       错误码由渠道自己拥有 —— 核心不该知道任何具体渠道的错误码。</li>
 * </ul>
 */
public interface ChannelType {

    /** 规范类型名，即落库值（{@code channel_accounts.channel_type}）。 */
    String key();

    /**
     * 受支持的别名，大小写不敏感，与 {@link #key()} 一起参与入参归一化。
     * 例如 chatapp 的 {@code whatsapp} —— 历史命名差异，底层是同一供应商。
     */
    default Set<String> aliases() {
        return Set.of();
    }

    /** 允许接受、且创建时必须全部提供的凭证字段。 */
    Set<String> credentialFields();

    /**
     * 明文凭证中需要打码的字段。用于凭证回显掩码，以及「前端回传 {@code ***}
     * 时不覆盖已保存密钥」的判断。
     *
     * <p>核心取所有已注册渠道的<b>并集</b>来打码，所以新增渠道只要在这里声明
     * 自己的敏感字段，就不会出现密钥被明文回显的漏口。
     */
    default Set<String> secretFields() {
        return Set.of();
    }

    /**
     * 账号标识归一化，结果落到 {@code channel_accounts.account_identifier_normalized}。
     *
     * <p>入参是用户提交的<b>原始值</b>（未 trim），实现自行处理首尾空白 ——
     * 例如邮箱大小写不敏感、手机号忽略空格与括号。
     */
    String normalizeIdentifier(String identifier);

    /**
     * 校验该渠道是否允许通过通用设置入口（{@code POST /api/channel-accounts}）
     * 自助绑定。默认允许；需要专属入驻流程的渠道覆盖此方法并在其中抛异常。
     */
    default void assertBindableFromSettings() {
    }

    /** 写入凭证前的范围校验（如租户空间是否匹配）。默认无。 */
    default void assertScope(Map<String, String> credentials) {
    }

    /** 凭证落库后的范围关联。默认无。 */
    default void bindScope(ChannelAccountEntity account) {
    }

    /** 账号级手动同步（{@code POST /api/channel-accounts/{id}/sync}）。 */
    Object syncAccount(UUID ownerId, UUID accountId) throws Exception;
}
