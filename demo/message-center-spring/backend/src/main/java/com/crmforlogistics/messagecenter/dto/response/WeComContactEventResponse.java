package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * 客户动态里的一条「客户关系变化」。
 *
 * <p>刻意**不含 displayName**：列表一次最多 200 条，若每条都去回查企微换昵称，
 * 一个列表请求会变成 200 个外部调用，既撞接口频控也撞前端超时。名称补齐另立批量接口，
 * 并且必须先定义并发、总耗时与失败预算。前端在拿不到名称时显示「未获取昵称」，不伪造。
 *
 * @param id                事件行 ID，前端列表的稳定 key
 * @param changeType        {@code add_external_contact} / {@code del_follow_user} 等原始值
 * @param externalUserId    外部联系人密文 ID（注意不是企业成员账号）
 * @param wecomUserId       企业服务人员的密文 userid
 * @param state             渠道标识（「联系我」配置的 state 或获客链接的 customer_channel）
 * @param failReason        仅 {@code transfer_fail}：接替失败原因
 * @param providerSource    仅 {@code del_external_contact}：{@code DELETE_BY_TRANSFER} 表示
 *                          该客户是因在职继承被自动转接删除，不是成员主动删除
 * @param providerCreatedAt 事件发生时间（企微 XML 的 {@code CreateTime}），不是入库时间
 */
public record WeComContactEventResponse(
        UUID id,
        String changeType,
        String externalUserId,
        String wecomUserId,
        String state,
        String failReason,
        String providerSource,
        Instant providerCreatedAt
) {}
