package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

@Mapper
public interface ChannelEventMapper extends BaseMapper<ChannelEventEntity> {
    @Insert("insert into channel_events (id, channel_account_id, provider_event_id, event_type, occurred_at, " +
            "received_at, payload_jsonb, payload_hash, processing_status, attempt_count, next_attempt_at, trace_id) " +
            "values (#{id}::uuid, #{channelAccountId}::uuid, #{providerEventId}, #{eventType}, #{occurredAt}, " +
            "#{receivedAt}, cast(#{payloadJsonb} as jsonb), #{payloadHash}, #{processingStatus}, " +
            "#{attemptCount}, #{nextAttemptAt}, #{traceId}) on conflict do nothing")
    int insertIgnore(ChannelEventEntity event);

    @Update("update channel_events set processing_status = 'processed', processed_at = #{processedAt}, " +
            "lease_owner = null, lease_until = null where id = #{id}::uuid")
    int markProcessed(@Param("id") UUID id, @Param("processedAt") Instant processedAt);

    @Update("update channel_events set processing_status = 'retry_wait', attempt_count = attempt_count + 1, " +
            "next_attempt_at = #{nextAttemptAt}, last_error_code = #{code}, last_error_message = #{message}, " +
            "lease_owner = null, lease_until = null where id = #{id}::uuid")
    int markRetry(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("code") String code, @Param("message") String message);

    @Select("with picked as (" +
            "select id from channel_events where processing_status in ('received', 'retry_wait', 'processing') " +
            "and next_attempt_at <= now() and attempt_count < 5 " +
            "and (lease_until is null or lease_until < now()) " +
            "order by next_attempt_at, received_at for update skip locked limit #{batchSize}" +
            ") update channel_events e set processing_status = 'processing', lease_owner = #{workerId}, " +
            "lease_until = #{leaseUntil} from picked where e.id = picked.id returning e.*")
    List<ChannelEventEntity> claimDue(@Param("workerId") String workerId,
                                      @Param("leaseUntil") Instant leaseUntil,
                                      @Param("batchSize") int batchSize);

    @Update("update channel_events set processing_status = 'dead', attempt_count = attempt_count + 1, " +
            "last_error_code = #{code}, last_error_message = #{message}, lease_owner = null, " +
            "lease_until = null where id = #{id}::uuid")
    int markDead(@Param("id") UUID id, @Param("code") String code, @Param("message") String message);
}
