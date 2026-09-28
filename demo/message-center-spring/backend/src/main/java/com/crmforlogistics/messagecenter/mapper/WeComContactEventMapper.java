package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 客户联系事件的落库与查询。
 *
 * <p>幂等刻意压在 SQL 上而不是调用方：{@code on conflict (dedupe_key) do nothing} 让
 * 「企微重推」与「并发重复投递」在数据库这一层收敛，调用方不必自己记忆收到过什么。
 * 返回 {@code 1} 表示本次真的写入，{@code 0} 表示已经存在同一条事件。
 *
 * <p>SQL 里显式写 {@code ::uuid} / {@code ::timestamptz} 转型：可空参数在 JDBC 上不携带类型，
 * 不转型 PostgreSQL 会报 "could not determine data type of parameter"。
 */
@Mapper
public interface WeComContactEventMapper {

    @Insert("insert into wecom_contact_events (id, installation_id, suite_id, auth_corp_id, event, "
            + "change_type, wecom_user_id, external_user_id, chat_id, state, welcome_code, fail_reason, "
            + "provider_source, provider_created_at, received_at, dedupe_key, ingest_status, attempt_count) "
            + "values (#{event.id}::uuid, #{event.installationId}::uuid, #{event.suiteId}, #{event.authCorpId}, "
            + "#{event.event}, #{event.changeType}, #{event.wecomUserId}, #{event.externalUserId}, "
            + "#{event.chatId}, #{event.state}, #{event.welcomeCode}, #{event.failReason}, "
            + "#{event.providerSource}, #{event.providerCreatedAt}, #{event.receivedAt}, #{event.dedupeKey}, "
            + "#{event.ingestStatus}, #{event.attemptCount}) "
            + "on conflict (dedupe_key) do nothing")
    int insertIgnore(@Param("event") WeComContactEventEntity event);

    /**
     * 某安装下的动态时间线。
     *
     * <p>单条 SQL 完成，不逐条回查企微：一页 50 条就会变成 50 个外部请求，且必然撞上 1 秒预算。
     * {@code installation_id} 是必填条件 —— 同一 {@code auth_corp_id} 在不同 suite 下是不同的安装，
     * 只按 corpid 过筛会串读。
     */
    @Select("select * from wecom_contact_events where installation_id = #{installationId}::uuid "
            + "and (#{since,jdbcType=TIMESTAMP}::timestamptz is null "
            + "or provider_created_at >= #{since,jdbcType=TIMESTAMP}::timestamptz) "
            + "and (#{changeType,jdbcType=VARCHAR}::varchar is null or change_type = #{changeType}) "
            + "order by provider_created_at desc, id desc limit #{limit}")
    List<WeComContactEventEntity> listTimeline(@Param("installationId") UUID installationId,
                                               @Param("since") Instant since,
                                               @Param("changeType") String changeType,
                                               @Param("limit") int limit);
}
