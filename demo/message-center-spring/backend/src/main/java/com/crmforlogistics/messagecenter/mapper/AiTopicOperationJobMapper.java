package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface AiTopicOperationJobMapper extends BaseMapper<AiTopicOperationJobEntity> {
    @Insert("insert into ai_topic_operation_jobs (id, contact_id, owner_type, owner_id, wecom_group_source_conversation_id, created_by_user_id, operation_kind, request_payload, expected_versions, idempotency_key, status, attempt_count, next_attempt_at) values (gen_random_uuid(), #{contactId}::uuid, #{ownerType}, #{ownerId}::uuid, #{groupConversationId}::uuid, #{userId}::uuid, #{operationKind}, cast(#{requestPayload} as jsonb), cast(#{expectedVersions} as jsonb), #{idempotencyKey}, 'PENDING', 0, #{now}) on conflict (created_by_user_id, idempotency_key) do nothing")
    int insertIfAbsent(@Param("contactId") UUID contactId, @Param("ownerType") String ownerType,
                       @Param("ownerId") UUID ownerId, @Param("groupConversationId") UUID groupConversationId, @Param("userId") UUID userId,
                       @Param("operationKind") String operationKind, @Param("requestPayload") String requestPayload,
                       @Param("expectedVersions") String expectedVersions, @Param("idempotencyKey") String idempotencyKey,
                       @Param("now") Instant now);

    @Select("select * from ai_topic_operation_jobs where created_by_user_id=#{userId}::uuid and idempotency_key=#{idempotencyKey} limit 1")
    AiTopicOperationJobEntity findByIdempotencyKey(@Param("userId") UUID userId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    default AiTopicOperationJobEntity findOrCreate(UUID contactId, String ownerType, UUID ownerId,
                                                    UUID groupConversationId, UUID userId, String operationKind,
                                                    String requestPayload, String expectedVersions,
                                                    String idempotencyKey, Instant now) {
        insertIfAbsent(contactId, ownerType, ownerId, groupConversationId, userId, operationKind,
                requestPayload, expectedVersions, idempotencyKey, now);
        return findByIdempotencyKey(userId, idempotencyKey);
    }

    @Select("select * from ai_topic_operation_jobs where (status='PENDING' and next_attempt_at <= #{now}) or (status='PROCESSING' and lease_until < #{now}) order by created_at limit #{limit}")
    List<AiTopicOperationJobEntity> listRunnable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("update ai_topic_operation_jobs set status='PROCESSING', attempt_count=attempt_count+1, lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=now() where id=#{id}::uuid and (status='PENDING' or (status='PROCESSING' and lease_until < now()))")
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseUntil") Instant leaseUntil);

    @Update("update ai_topic_operation_jobs set status=#{status}, last_error_code=#{errorCode}, last_error_message=#{errorMessage}, completed_at=#{completedAt}, lease_owner=null, lease_until=null, updated_at=now() where id=#{id}::uuid and lease_owner=#{owner} and status='PROCESSING'")
    int finish(@Param("id") UUID id, @Param("owner") String owner, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
               @Param("completedAt") Instant completedAt);
}
