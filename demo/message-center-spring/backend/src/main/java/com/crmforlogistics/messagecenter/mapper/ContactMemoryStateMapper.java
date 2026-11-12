package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactMemoryStateMapper extends BaseMapper<ContactMemoryStateEntity> {

    @Select("""
            select *
            from contact_memory_states
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
            limit 1
            """)
    Optional<ContactMemoryStateEntity> findByOwnerAndContact(@Param("ownerUserId") UUID ownerUserId,
                                                              @Param("contactId") UUID contactId);

    @Insert("""
            insert into contact_memory_states
                (id, contact_id, owner_user_id, status, last_inbound_at, retry_count, updated_at)
            values (gen_random_uuid(), #{contactId}::uuid, #{ownerUserId}::uuid, 'DIRTY',
                    #{inboundAt}, 0, now())
            on conflict (contact_id, owner_user_id) do update
            set status = case
                             when contact_memory_states.status = 'PROCESSING' then 'PROCESSING'
                             else 'DIRTY'
                         end,
                last_inbound_at = greatest(
                    coalesce(contact_memory_states.last_inbound_at, #{inboundAt}),
                    #{inboundAt}),
                retry_count = case
                                  when contact_memory_states.status = 'PROCESSING'
                                  then contact_memory_states.retry_count
                                  else 0
                              end,
                next_retry_at = null,
                last_failure_code = null,
                last_failure_message = null,
                updated_at = now()
            """)
    int markDirty(@Param("contactId") UUID contactId,
                  @Param("ownerUserId") UUID ownerUserId,
                  @Param("inboundAt") Instant inboundAt);

    @Select("""
            <script>
            select *
            from contact_memory_states
            where (
                (status = 'DIRTY' and (#{after}::timestamptz is null or updated_at &gt;= #{after}::timestamptz))
                or (status = 'RETRY_WAIT' and next_retry_at &lt;= #{now})
                or (status = 'PROCESSING' and lease_expires_at &lt; #{now})
            )
            order by updated_at, id
            limit #{limit}
            </script>
            """)
    List<ContactMemoryStateEntity> listRunnable(@Param("now") Instant now,
                                                @Param("after") Instant after,
                                                @Param("limit") int limit);

    /**
     * Marks contacts whose stored inbound messages are newer than the memory that has already
     * been derived from them, so a run picks them up even when no trigger event was ever
     * persisted for those messages. The owner is {@code contacts.created_by} because that is the
     * identity {@link com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryContextService}
     * validates against; {@code contacts.owner_user_id} may be null.
     */
    @Insert("""
            with stale as (
                select c.id as contact_id,
                       c.created_by as owner_user_id,
                       max(m.received_at) as last_inbound_at
                from messages m
                join conversations cv on cv.id = m.conversation_id
                join contact_identities ci on ci.id = cv.contact_identity_id
                join contacts c on c.id = ci.contact_id
                where c.created_by is not null
                  and c.deleted_at is null
                  and ci.deleted_at is null
                  and m.direction = 'inbound'
                  and m.received_at <= #{now}::timestamptz
                group by c.id, c.created_by
            )
            insert into contact_memory_states
                (id, contact_id, owner_user_id, status, last_inbound_at, retry_count, updated_at)
            select gen_random_uuid(), s.contact_id, s.owner_user_id, 'DIRTY', s.last_inbound_at, 0, now()
            from stale s
            left join contact_memory_states st
                   on st.contact_id = s.contact_id
                  and st.owner_user_id = s.owner_user_id
            where st.id is null
               or (st.status = 'CLEAN'
                   and (st.last_success_cursor is null
                        or s.last_inbound_at > split_part(st.last_success_cursor, '|', 1)::timestamptz))
            order by s.last_inbound_at, s.contact_id
            limit #{limit}
            on conflict (contact_id, owner_user_id) do update
            set status = 'DIRTY',
                last_inbound_at = greatest(
                    coalesce(contact_memory_states.last_inbound_at, excluded.last_inbound_at),
                    excluded.last_inbound_at),
                retry_count = 0,
                next_retry_at = null,
                last_failure_code = null,
                last_failure_message = null,
                updated_at = now()
            where contact_memory_states.status = 'CLEAN'
            """)
    int markStaleDirty(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 手动重算请求：只有当这位联系人存在<b>比已处理游标更新的入站消息</b>时才把他标脏。
     *
     * <p>与 {@link #markStaleDirty} 是<b>同一条判据</b>（{@code last_inbound_at > 游标里的时间戳}），
     * 只是限定到单个联系人，并额外放行 {@code FAILED}（自动路径刻意不重试终态失败，
     * 「人主动要求再来一次」正是它存在的理由）。改这里等于改「什么叫有东西可算」——
     * 两处必须同时改。
     *
     * <h2>为什么 {@code last_inbound_at} 只能由 SQL 自己算</h2>
     * 调用方<b>不</b>传这个时间，是刻意的：它必须是「最后一条入站消息的真实时间」，不能是 {@code now}。
     * 写成 {@code now} 会踩一个静默死循环 —— {@code complete()} 里
     * {@code last_inbound_at > processedAt ⇒ 状态回到 DIRTY}，于是每处理完一轮立刻又变脏，
     * 每轮白烧一次 LLM 并把画像反复改写。把参数从签名里去掉，这个错误就<b>写不出来</b>。
     *
     * @return 受影响行数。{@code 1} = 已标脏；{@code 0} = 没有新内容（或状态不允许，见 upsert 的 where）
     */
    @Insert("""
            with latest as (
                select c.id as contact_id,
                       c.created_by as owner_user_id,
                       max(m.received_at) as last_inbound_at
                from contacts c
                join contact_identities ci on ci.contact_id = c.id
                join conversations cv on cv.contact_identity_id = ci.id
                join messages m on m.conversation_id = cv.id
                where c.id = #{contactId}::uuid
                  and c.created_by = #{ownerUserId}::uuid
                  and c.deleted_at is null
                  and ci.deleted_at is null
                  and m.direction = 'inbound'
                  and m.received_at <= #{now}::timestamptz
                group by c.id, c.created_by
            )
            insert into contact_memory_states
                (id, contact_id, owner_user_id, status, last_inbound_at, retry_count, updated_at)
            select gen_random_uuid(), latest.contact_id, latest.owner_user_id, 'DIRTY',
                   latest.last_inbound_at, 0, now()
            from latest
            left join contact_memory_states st
                   on st.contact_id = latest.contact_id
                  and st.owner_user_id = latest.owner_user_id
            where st.id is null
               or (st.status = 'CLEAN'
                   and (st.last_success_cursor is null
                        or latest.last_inbound_at > split_part(st.last_success_cursor, '|', 1)::timestamptz))
               or st.status = 'FAILED'
            on conflict (contact_id, owner_user_id) do update
            set status = 'DIRTY',
                last_inbound_at = greatest(
                    coalesce(contact_memory_states.last_inbound_at, excluded.last_inbound_at),
                    excluded.last_inbound_at),
                retry_count = 0,
                next_retry_at = null,
                last_failure_code = null,
                last_failure_message = null,
                updated_at = now()
            where contact_memory_states.status in ('CLEAN', 'FAILED')
            """)
    int markDirtyForRecompute(@Param("ownerUserId") UUID ownerUserId,
                              @Param("contactId") UUID contactId,
                              @Param("now") Instant now);

    @Select("""
            with claimed as (
                update contact_memory_states
                set status = 'PROCESSING',
                    lease_owner = #{leaseOwner},
                    lease_token = gen_random_uuid(),
                    lease_acquired_at = now(),
                    lease_expires_at = #{leaseUntil},
                    updated_at = now()
                where id = #{id}::uuid
                  and (
                      (status in ('DIRTY', 'RETRY_WAIT')
                       and (next_retry_at is null or next_retry_at <= now()))
                      or (status = 'PROCESSING' and lease_expires_at < now())
                  )
                returning lease_token
            )
            select lease_token from claimed
            """)
    Optional<UUID> claim(@Param("id") UUID id,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            update contact_memory_states
            set status = case
                             when last_inbound_at is not null
                                  and last_inbound_at > #{processedAt}
                             then 'DIRTY'
                             else 'CLEAN'
                         end,
                last_success_cursor = #{cursor},
                retry_count = 0,
                next_retry_at = null,
                last_failure_code = null,
                last_failure_message = null,
                current_profile_version_id = coalesce(#{profileId}::uuid, current_profile_version_id),
                lease_owner = null,
                lease_token = null,
                lease_acquired_at = null,
                lease_expires_at = null,
                updated_at = now()
            where id = #{id}::uuid
              and lease_token = #{leaseToken}::uuid
              and status = 'PROCESSING'
              and lease_expires_at > now()
            """)
    int complete(@Param("id") UUID id,
                 @Param("leaseToken") UUID leaseToken,
                 @Param("cursor") String cursor,
                 @Param("profileId") UUID profileId,
                 @Param("processedAt") Instant processedAt);

    @Update("""
            update contact_memory_states
            set status = case when #{terminal} then 'FAILED' else 'RETRY_WAIT' end,
                retry_count = #{retryCount},
                next_retry_at = #{retryAt},
                last_failure_code = #{code},
                last_failure_message = #{message},
                lease_owner = null,
                lease_token = null,
                lease_acquired_at = null,
                lease_expires_at = null,
                updated_at = now()
            where id = #{id}::uuid
              and lease_token = #{leaseToken}::uuid
              and status = 'PROCESSING'
              and lease_expires_at > now()
            """)
    int fail(@Param("id") UUID id,
             @Param("leaseToken") UUID leaseToken,
             @Param("code") String code,
             @Param("message") String message,
             @Param("retryCount") int retryCount,
             @Param("retryAt") Instant retryAt,
             @Param("terminal") boolean terminal);
}
