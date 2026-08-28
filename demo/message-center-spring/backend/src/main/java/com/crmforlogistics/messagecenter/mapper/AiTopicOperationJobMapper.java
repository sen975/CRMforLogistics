package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface AiTopicOperationJobMapper extends BaseMapper<AiTopicOperationJobEntity> {
    @Insert("insert into ai_topic_operation_jobs (id, contact_id, created_by_user_id, operation_kind, request_payload, expected_versions, idempotency_key, status, attempt_count, next_attempt_at) values (gen_random_uuid(), #{contactId}::uuid, #{userId}::uuid, #{operationKind}, cast(#{requestPayload} as jsonb), cast(#{expectedVersions} as jsonb), #{idempotencyKey}, 'PENDING', 0, #{now}) on conflict (created_by_user_id, idempotency_key) do nothing")
    int insertIfAbsent(@Param("contactId") UUID contactId, @Param("userId") UUID userId,
                       @Param("operationKind") String operationKind, @Param("requestPayload") String requestPayload,
                       @Param("expectedVersions") String expectedVersions, @Param("idempotencyKey") String idempotencyKey,
                       @Param("now") Instant now);

    @Select("select * from ai_topic_operation_jobs where created_by_user_id=#{userId}::uuid and idempotency_key=#{idempotencyKey} limit 1")
    AiTopicOperationJobEntity findByIdempotencyKey(@Param("userId") UUID userId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    default AiTopicOperationJobEntity findOrCreate(UUID contactId, UUID userId, String operationKind,
                                                    String requestPayload, String expectedVersions,
                                                    String idempotencyKey, Instant now) {
        insertIfAbsent(contactId, userId, operationKind, requestPayload, expectedVersions, idempotencyKey, now);
        return findByIdempotencyKey(userId, idempotencyKey);
    }
}
