package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppHistorySyncJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface WhatsAppHistorySyncJobMapper extends BaseMapper<WhatsAppHistorySyncJobEntity> {
    @Insert("insert into whatsapp_history_sync_jobs (id, channel_account_id, owner_user_id, status, attempt_count, next_attempt_at, created_at, updated_at) " +
            "values (gen_random_uuid(), #{accountId}::uuid, #{ownerId}::uuid, 'PENDING', 0, #{now}, #{now}, #{now}) on conflict (channel_account_id) where status in ('PENDING','PROCESSING','RETRY_WAIT') do nothing")
    int insertPendingIfAbsent(@Param("accountId") UUID accountId, @Param("ownerId") UUID ownerId, @Param("now") Instant now);

    @Select("select * from whatsapp_history_sync_jobs where channel_account_id = #{accountId}::uuid and owner_user_id = #{ownerId}::uuid order by created_at desc limit 1")
    WhatsAppHistorySyncJobEntity findLatestByAccountAndOwner(@Param("accountId") UUID accountId, @Param("ownerId") UUID ownerId);

    @Select("select * from whatsapp_history_sync_jobs where ((status in ('PENDING','RETRY_WAIT') and next_attempt_at <= #{now}) or (status='PROCESSING' and lease_until < #{now})) order by next_attempt_at, created_at, id limit #{limit}")
    List<WhatsAppHistorySyncJobEntity> listRunnable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("update whatsapp_history_sync_jobs set status='PROCESSING', attempt_count=attempt_count+1, lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=now() where id=#{id}::uuid and (status in ('PENDING','RETRY_WAIT') or (status='PROCESSING' and lease_until < now()))")
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseUntil") Instant leaseUntil);

    @Update("update whatsapp_history_sync_jobs set status=#{status}, error_code=#{errorCode}, next_attempt_at=#{nextAttemptAt}, completed_at=#{completedAt}, lease_owner=null, lease_until=null, updated_at=now() where id=#{id}::uuid and status='PROCESSING' and lease_owner=#{owner}")
    int finish(@Param("id") UUID id, @Param("owner") String owner, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("nextAttemptAt") Instant nextAttemptAt,
               @Param("completedAt") Instant completedAt);
}
