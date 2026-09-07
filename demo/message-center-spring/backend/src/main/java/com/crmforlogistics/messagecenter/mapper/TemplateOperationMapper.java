package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
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
public interface TemplateOperationMapper extends BaseMapper<TemplateOperationEntity> {

    @Insert("insert into template_operations (id, channel_account_id, idempotency_key, operation_type, "
            + "provider_template_id, language_code, requested_snapshot_jsonb, operation_status, "
            + "provider_request_id, provider_code, error_code, error_message, next_reconcile_at, "
            + "reconcile_attempt_count, actor_user_id, trace_id, lease_owner, lease_until, started_at, completed_at) "
            + "values (#{id}::uuid, #{channelAccountId}::uuid, #{idempotencyKey}, #{operationType}, "
            + "#{providerTemplateId}, #{languageCode}, cast(#{requestedSnapshotJsonb} as jsonb), "
            + "#{operationStatus}, #{providerRequestId}, #{providerCode}, #{errorCode}, #{errorMessage}, "
            + "#{nextReconcileAt}, coalesce(#{reconcileAttemptCount}, 0), #{actorUserId}::uuid, #{traceId}, "
            + "#{leaseOwner}, #{leaseUntil}, coalesce(#{startedAt}, now()), #{completedAt}) "
            + "on conflict (channel_account_id, idempotency_key) do nothing")
    int insertIgnore(TemplateOperationEntity operation);

    @Select("select * from template_operations where channel_account_id = #{accountId}::uuid "
            + "and idempotency_key = #{idempotencyKey} limit 1")
    Optional<TemplateOperationEntity> findByIdempotency(@Param("accountId") UUID accountId,
                                                        @Param("idempotencyKey") String idempotencyKey);

    @Select("select * from template_operations where id = #{operationId}::uuid for update")
    Optional<TemplateOperationEntity> findByIdForUpdate(@Param("operationId") UUID operationId);

    @Update("update template_operations set operation_status = 'SUCCEEDED', "
            + "provider_template_id = #{providerTemplateId}, provider_request_id = #{providerRequestId}, "
            + "completed_at = #{completedAt}, next_reconcile_at = null, lease_owner = null, lease_until = null "
            + "where id = #{id}::uuid")
    int markSucceeded(@Param("id") UUID id, @Param("providerTemplateId") String providerTemplateId,
                      @Param("providerRequestId") String providerRequestId,
                      @Param("completedAt") Instant completedAt);

    @Update("update template_operations set operation_status = 'SUBMISSION_UNKNOWN', "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "next_reconcile_at = #{nextReconcileAt}, lease_owner = null, lease_until = null "
            + "where id = #{id}::uuid")
    int markUnknown(@Param("id") UUID id, @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("nextReconcileAt") Instant nextReconcileAt);

    @Update("update template_operations set operation_status = 'FAILED', "
            + "provider_request_id = #{providerRequestId}, error_code = #{errorCode}, "
            + "error_message = #{errorMessage}, completed_at = #{completedAt}, "
            + "next_reconcile_at = null, lease_owner = null, lease_until = null where id = #{id}::uuid")
    int markFailed(@Param("id") UUID id, @Param("providerRequestId") String providerRequestId,
                   @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
                   @Param("completedAt") Instant completedAt);

    @Select("with picked as ("
            + "select id from template_operations where operation_status = 'SUBMISSION_UNKNOWN' "
            + "and operation_type <> 'RETIRED' "
            + "and next_reconcile_at <= #{now} and reconcile_attempt_count < 10 "
            + "and (lease_until is null or lease_until < #{now}) "
            + "order by next_reconcile_at, started_at for update skip locked limit #{limit}"
            + ") update template_operations operation set lease_owner = #{workerId}, lease_until = #{leaseUntil}, "
            + "reconcile_attempt_count = reconcile_attempt_count + 1 from picked "
            + "where operation.id = picked.id returning operation.*")
    List<TemplateOperationEntity> claimUnknown(@Param("workerId") String workerId, @Param("now") Instant now,
                                               @Param("leaseUntil") Instant leaseUntil, @Param("limit") int limit);
}
