package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface AiTopicGenerationJobMapper extends BaseMapper<AiTopicGenerationJobEntity> {
    @Insert("insert into ai_topic_generation_jobs (id, contact_id, owner_type, owner_id, wecom_group_source_conversation_id, created_by_user_id, trigger_source, job_kind, input_fingerprint, status, attempt_count, next_attempt_at) "
            + "values (gen_random_uuid(), #{contactId}::uuid, #{ownerType}, #{ownerId}::uuid, #{groupConversationId}::uuid, null, 'AUTO', #{jobKind}, #{fingerprint}, 'PENDING', 0, #{now}) "
            + "on conflict (owner_type, owner_id, input_fingerprint) do nothing")
    int insertAutomaticIfAbsent(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
                                @Param("contactId") UUID contactId, @Param("groupConversationId") UUID groupConversationId,
                                @Param("jobKind") String jobKind, @Param("fingerprint") String fingerprint,
                                @Param("now") Instant now);

    @Select("select * from ai_topic_generation_jobs where owner_type=#{ownerType} and owner_id=#{ownerId}::uuid and input_fingerprint=#{fingerprint} limit 1")
    AiTopicGenerationJobEntity findByOwnerFingerprint(@Param("ownerType") String ownerType,
                                                      @Param("ownerId") UUID ownerId,
                                                      @Param("fingerprint") String fingerprint);

    @Insert("insert into ai_topic_generation_jobs (id, contact_id, created_by_user_id, job_kind, input_fingerprint, status, attempt_count, next_attempt_at) values (gen_random_uuid(), #{contactId}::uuid, #{userId}::uuid, #{jobKind}, #{fingerprint}, 'PENDING', 0, #{now}) on conflict (contact_id, input_fingerprint) do nothing")
    int insertIfAbsent(UUID contactId, UUID userId, String jobKind, String fingerprint, Instant now);

    @Select("select * from ai_topic_generation_jobs where contact_id=#{contactId}::uuid and input_fingerprint=#{fingerprint} limit 1")
    AiTopicGenerationJobEntity findByFingerprint(UUID contactId, String fingerprint);

    @Select("select * from ai_topic_generation_jobs where ((status in ('PENDING','RETRY_WAIT') and next_attempt_at <= #{now}) or (status='PROCESSING' and lease_until < #{now})) order by created_at limit #{limit}")
    List<AiTopicGenerationJobEntity> listRunnable(Instant now, int limit);

    @Update("update ai_topic_generation_jobs set status='PROCESSING', attempt_count=attempt_count+1, lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=now() where id=#{id}::uuid and (status in ('PENDING','RETRY_WAIT') or (status='PROCESSING' and lease_until < now()))")
    int claim(UUID id, String owner, Instant leaseUntil);

    @Update("update ai_topic_generation_jobs set status=#{status}, last_error_code=#{errorCode}, last_error_message=#{errorMessage}, next_attempt_at=#{nextAttemptAt}, completed_at=#{completedAt}, lease_owner=null, lease_until=null, updated_at=now() where id=#{id}::uuid and lease_owner=#{owner} and status='PROCESSING'")
    int finish(UUID id, String owner, String status, String errorCode, String errorMessage, Instant nextAttemptAt, Instant completedAt);

    @Update("update ai_topic_generation_jobs set status='PENDING', last_error_code=null, last_error_message=null, next_attempt_at=#{now}, completed_at=null, attempt_count=0, updated_at=now() where id=#{id}::uuid and status='FAILED'")
    int requeueFailed(UUID id, Instant now);
}
