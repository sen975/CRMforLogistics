package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface WeComGroupNameRefreshJobMapper extends BaseMapper<WeComGroupNameRefreshJobEntity> {
    @Insert("insert into wecom_group_name_refresh_jobs (id, source_conversation_id, trigger_source, requested_by_user_id, status, attempt_count, next_attempt_at, created_at, updated_at) values (gen_random_uuid(), #{sourceConversationId}::uuid, 'MANUAL', #{userId}::uuid, 'PENDING', 0, #{now}, #{now}, #{now}) on conflict do nothing")
    int insertManualIfAbsent(@Param("sourceConversationId") UUID sourceConversationId, @Param("userId") UUID userId,
                             @Param("now") Instant now);

    @Insert("insert into wecom_group_name_refresh_jobs (id, source_conversation_id, trigger_source, status, attempt_count, next_attempt_at, created_at, updated_at) select gen_random_uuid(), sc.id, 'TOPIC_UPDATED', 'PENDING', 0, #{now}, #{now}, #{now} from wecom_source_conversations sc where sc.id=#{sourceConversationId}::uuid and sc.conversation_type='GROUP' and sc.name_resolution_status <> 'UNAVAILABLE' and (sc.last_name_checked_at is null or sc.last_name_checked_at <= #{cooldownBefore}) on conflict do nothing")
    int insertAutomaticIfDue(@Param("sourceConversationId") UUID sourceConversationId,
                              @Param("cooldownBefore") Instant cooldownBefore, @Param("now") Instant now);

    @Select("select * from wecom_group_name_refresh_jobs where source_conversation_id=#{sourceConversationId}::uuid and status in ('PENDING','PROCESSING','RETRY_WAIT') order by created_at desc limit 1")
    WeComGroupNameRefreshJobEntity findActive(@Param("sourceConversationId") UUID sourceConversationId);

    @Select("select * from wecom_group_name_refresh_jobs where ((status in ('PENDING','RETRY_WAIT') and next_attempt_at <= #{now}) or (status='PROCESSING' and lease_until < #{now})) order by next_attempt_at, created_at, id limit #{limit}")
    List<WeComGroupNameRefreshJobEntity> listRunnable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("update wecom_group_name_refresh_jobs set status='PROCESSING', attempt_count=attempt_count+1, lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=now() where id=#{id}::uuid and (status in ('PENDING','RETRY_WAIT') or (status='PROCESSING' and lease_until < now()))")
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseUntil") Instant leaseUntil);

    @Update("update wecom_group_name_refresh_jobs set status=#{status}, error_code=#{errorCode}, error_diagnostic=#{diagnostic}, next_attempt_at=#{nextAttemptAt}, completed_at=#{completedAt}, lease_owner=null, lease_until=null, updated_at=now() where id=#{id}::uuid and status='PROCESSING' and lease_owner=#{owner}")
    int finish(@Param("id") UUID id, @Param("owner") String owner, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("diagnostic") String diagnostic,
               @Param("nextAttemptAt") Instant nextAttemptAt, @Param("completedAt") Instant completedAt);
}
