package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ContactMemoryTriggerEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ContactMemoryTriggerEventMapper extends BaseMapper<ContactMemoryTriggerEventEntity> {

    @Insert("""
            insert into contact_memory_trigger_events
                (id, message_id, contact_id, owner_user_id, ingest_sequence, occurred_at, received_at)
            select gen_random_uuid(), m.id, c.id, c.created_by,
                   coalesce(cast(#{ingestSequence} as bigint), m.ingest_sequence),
                   coalesce(cast(#{occurredAt} as timestamptz), m.occurred_at),
                   coalesce(cast(#{receivedAt} as timestamptz), m.received_at)
            from messages m
            join conversations cv on cv.id = m.conversation_id
            join contact_identities ci on ci.id = cv.contact_identity_id
            join contacts c on c.id = ci.contact_id
            where m.id = #{messageId}::uuid
              and c.id = #{contactId}::uuid
              and m.direction = 'inbound'
              and c.created_by is not null
            on conflict (message_id) do nothing
            """)
    int enqueue(@Param("contactId") UUID contactId,
                @Param("messageId") UUID messageId,
                @Param("ingestSequence") Long ingestSequence,
                @Param("occurredAt") Instant occurredAt,
                @Param("receivedAt") Instant receivedAt);

    @Select("""
            <script>
            with candidates as (
                select id
                from contact_memory_trigger_events
                where (status = 'PENDING' and next_attempt_at &lt;= #{now})
                   or (status = 'PROCESSING' and lease_expires_at &lt; #{now})
                order by created_at, id
                for update skip locked
                limit #{limit}
            ), claimed as (
                update contact_memory_trigger_events event
                set status = 'PROCESSING',
                    lease_owner = #{leaseOwner},
                    lease_token = gen_random_uuid(),
                    lease_acquired_at = now(),
                    lease_expires_at = #{leaseUntil}
                from candidates
                where event.id = candidates.id
                returning event.*
            )
            select * from claimed
            </script>
            """)
    List<ContactMemoryTriggerEventEntity> claimDue(@Param("now") Instant now,
                                                    @Param("leaseOwner") String leaseOwner,
                                                    @Param("leaseUntil") Instant leaseUntil,
                                                    @Param("limit") int limit);

    @Update("""
            update contact_memory_trigger_events
            set status = 'APPLIED',
                applied_at = now(),
                lease_owner = null,
                lease_token = null,
                lease_acquired_at = null,
                lease_expires_at = null
            where id = #{id}::uuid
              and lease_token = #{leaseToken}::uuid
              and status = 'PROCESSING'
              and lease_expires_at > now()
            """)
    int markApplied(@Param("id") UUID id, @Param("leaseToken") UUID leaseToken);

    @Update("""
            update contact_memory_trigger_events
            set status = case when #{terminal} then 'FAILED' else 'PENDING' end,
                attempt_count = #{attemptCount},
                next_attempt_at = #{nextAttemptAt},
                last_failure_code = #{code},
                last_failure_message = #{message},
                lease_owner = null,
                lease_token = null,
                lease_acquired_at = null,
                lease_expires_at = null
            where id = #{id}::uuid
              and lease_token = #{leaseToken}::uuid
              and status = 'PROCESSING'
              and lease_expires_at > now()
            """)
    int markFailed(@Param("id") UUID id,
                   @Param("leaseToken") UUID leaseToken,
                   @Param("code") String code,
                   @Param("message") String message,
                   @Param("attemptCount") int attemptCount,
                   @Param("nextAttemptAt") Instant nextAttemptAt,
                   @Param("terminal") boolean terminal);
}
