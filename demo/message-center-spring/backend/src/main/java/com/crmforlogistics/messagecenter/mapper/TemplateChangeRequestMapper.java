package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateChangeRequestEntity;
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
public interface TemplateChangeRequestMapper extends BaseMapper<TemplateChangeRequestEntity> {
    @Insert("insert into template_change_requests (id, template_id, change_type, requested_payload_jsonb, base_version, "
            + "requested_by_user_id, requested_via_account_id, status, idempotency_key, created_at, updated_at) values "
            + "(#{id}::uuid, #{templateId}::uuid, #{changeType}, cast(#{requestedPayloadJsonb} as jsonb), #{baseVersion}, "
            + "#{requestedByUserId}::uuid, #{requestedViaAccountId}::uuid, #{status}, #{idempotencyKey}, #{createdAt}, #{updatedAt}) "
            + "on conflict (requested_by_user_id, idempotency_key) do nothing")
    int insertIgnore(TemplateChangeRequestEntity entity);

    @Select("select * from template_change_requests where requested_by_user_id = #{userId}::uuid "
            + "and idempotency_key = #{idempotencyKey} limit 1")
    Optional<TemplateChangeRequestEntity> findByRequesterAndIdempotency(@Param("userId") UUID userId,
                                                                         @Param("idempotencyKey") String idempotencyKey);

    @Select("select * from template_change_requests where requested_by_user_id = #{userId}::uuid "
            + "order by created_at desc, id desc limit #{limit} offset #{offset}")
    List<TemplateChangeRequestEntity> listByRequester(@Param("userId") UUID userId,
                                                       @Param("offset") long offset, @Param("limit") int limit);

    @Select("select count(*) from template_change_requests where requested_by_user_id = #{userId}::uuid")
    long countByRequester(@Param("userId") UUID userId);

    @Select("select * from template_change_requests where id = #{requestId}::uuid limit 1")
    Optional<TemplateChangeRequestEntity> findByIdForUpdate(@Param("requestId") UUID requestId);

    @Select("select * from template_change_requests order by created_at desc, id desc limit #{limit} offset #{offset}")
    List<TemplateChangeRequestEntity> listForReview(@Param("offset") long offset, @Param("limit") int limit);

    @Select("select count(*) from template_change_requests")
    long countForReview();

    @Update("update template_change_requests request set status = 'EXECUTING', reviewed_by_user_id = #{reviewerId}::uuid, "
            + "reviewed_at = #{now}, execution_started_at = #{now}, execution_error_code = null, "
            + "execution_error_message = null, updated_at = #{now} "
            + "where request.id = #{requestId}::uuid and request.status = 'PENDING_APPROVAL' "
            + "and exists (select 1 from message_templates template where template.id = request.template_id "
            + "and template.version = request.base_version and template.deleted_at is null)")
    int claimApproval(@Param("requestId") UUID requestId, @Param("reviewerId") UUID reviewerId,
                      @Param("now") Instant now);

    @Update("update template_change_requests set status = 'STALE', reviewed_by_user_id = #{reviewerId}::uuid, "
            + "reviewed_at = #{now}, execution_completed_at = #{now}, updated_at = #{now} "
            + "where id = #{requestId}::uuid and status = 'PENDING_APPROVAL'")
    int markStale(@Param("requestId") UUID requestId, @Param("reviewerId") UUID reviewerId,
                  @Param("now") Instant now);

    @Update("update template_change_requests set status = 'SUCCEEDED', provider_request_id = #{providerRequestId}, "
            + "execution_completed_at = #{now}, updated_at = #{now} where id = #{requestId}::uuid "
            + "and status = 'EXECUTING'")
    int markSucceeded(@Param("requestId") UUID requestId, @Param("providerRequestId") String providerRequestId,
                      @Param("now") Instant now);

    @Update("update template_change_requests set status = 'EXECUTION_FAILED', execution_error_code = #{code}, "
            + "execution_error_message = #{message}, execution_completed_at = #{now}, updated_at = #{now} "
            + "where id = #{requestId}::uuid and status = 'EXECUTING'")
    int markExecutionFailed(@Param("requestId") UUID requestId, @Param("code") String code,
                            @Param("message") String message, @Param("now") Instant now);

    @Update("update template_change_requests set status = 'REJECTED', reviewed_by_user_id = #{reviewerId}::uuid, "
            + "review_reason = #{reason}, reviewed_at = #{now}, updated_at = #{now} "
            + "where id = #{requestId}::uuid and status = 'PENDING_APPROVAL'")
    int reject(@Param("requestId") UUID requestId, @Param("reviewerId") UUID reviewerId,
               @Param("reason") String reason, @Param("now") Instant now);

    @Update("update template_change_requests set status = 'PENDING_APPROVAL', execution_error_code = null, "
            + "execution_error_message = null, execution_completed_at = null, updated_at = #{now} "
            + "where id = #{requestId}::uuid and status = 'EXECUTION_FAILED'")
    int retry(@Param("requestId") UUID requestId, @Param("now") Instant now);
}
