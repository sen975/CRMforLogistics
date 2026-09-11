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
                (status = 'DIRTY' and (#{after} is null or updated_at &gt;= #{after}))
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

    @Update("""
            update contact_memory_states
            set status = 'PROCESSING',
                lease_owner = #{leaseOwner},
                lease_acquired_at = now(),
                lease_expires_at = #{leaseUntil},
                updated_at = now()
            where id = #{id}::uuid
              and (
                  (status in ('DIRTY', 'RETRY_WAIT')
                   and (next_retry_at is null or next_retry_at &lt;= now()))
                  or (status = 'PROCESSING' and lease_expires_at &lt; now())
              )
            """)
    int claim(@Param("id") UUID id,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            update contact_memory_states
            set status = case
                             when last_inbound_at is not null
                                  and last_inbound_at &gt; #{processedAt}
                             then 'DIRTY'
                             else 'CLEAN'
                         end,
                last_success_cursor = #{cursor},
                retry_count = 0,
                next_retry_at = null,
                last_failure_code = null,
                last_failure_message = null,
                lease_owner = null,
                lease_acquired_at = null,
                lease_expires_at = null,
                updated_at = now()
            where id = #{id}::uuid
              and lease_owner = #{leaseOwner}
              and status = 'PROCESSING'
            """)
    int complete(@Param("id") UUID id,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("cursor") String cursor,
                 @Param("processedAt") Instant processedAt);

    @Update("""
            update contact_memory_states
            set status = case when #{terminal} then 'FAILED' else 'RETRY_WAIT' end,
                retry_count = #{retryCount},
                next_retry_at = #{retryAt},
                last_failure_code = #{code},
                last_failure_message = #{message},
                lease_owner = null,
                lease_acquired_at = null,
                lease_expires_at = null,
                updated_at = now()
            where id = #{id}::uuid
              and lease_owner = #{leaseOwner}
              and status = 'PROCESSING'
            """)
    int fail(@Param("id") UUID id,
             @Param("leaseOwner") String leaseOwner,
             @Param("code") String code,
             @Param("message") String message,
             @Param("retryCount") int retryCount,
             @Param("retryAt") Instant retryAt,
             @Param("terminal") boolean terminal);
}
