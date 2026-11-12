package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastReconciliationEvidenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ChatAppBroadcastReconciliationEvidenceMapper {

    @Insert("insert into chatapp_broadcast_reconciliation_evidence (id, broadcast_id, job_id, "
            + "provider_request_id, page_number, row_number, user_number, provider_message_id, "
            + "provider_unique_message_id, provider_status, failure_reason, matched_recipient_id, "
            + "diagnostic_code, created_at) values (#{id}::uuid, #{broadcastId}::uuid, #{jobId}::uuid, "
            + "#{providerRequestId}, #{pageNumber}, #{rowNumber}, #{userNumber}, #{providerMessageId}, "
            + "#{providerUniqueMessageId}, #{providerStatus}, left(#{failureReason}, 1000), "
            + "#{matchedRecipientId}::uuid, #{diagnosticCode}, #{createdAt}) "
            + "on conflict (job_id, page_number, row_number) do update set "
            + "provider_request_id = excluded.provider_request_id, user_number = excluded.user_number, "
            + "provider_message_id = excluded.provider_message_id, "
            + "provider_unique_message_id = excluded.provider_unique_message_id, "
            + "provider_status = excluded.provider_status, failure_reason = excluded.failure_reason, "
            + "matched_recipient_id = excluded.matched_recipient_id, "
            + "diagnostic_code = excluded.diagnostic_code, created_at = excluded.created_at")
    int upsert(ChatAppBroadcastReconciliationEvidenceEntity evidence);

    @Select("select count(*) from chatapp_broadcast_reconciliation_evidence "
            + "where broadcast_id = #{broadcastId}::uuid")
    long countByBroadcastId(@Param("broadcastId") UUID broadcastId);

    @Select("select count(*) from chatapp_broadcast_reconciliation_evidence evidence "
            + "where evidence.broadcast_id = #{broadcastId}::uuid and evidence.row_number > 0 "
            + "and evidence.matched_recipient_id is not null and evidence.job_id = ("
            + "select job.id from chatapp_broadcast_jobs job "
            + "where job.broadcast_id = #{broadcastId}::uuid and job.job_type = 'RECONCILE' "
            + "order by job.created_at desc, job.id desc limit 1)")
    long countMatched(@Param("broadcastId") UUID broadcastId);

    @Select("select count(*) from chatapp_broadcast_reconciliation_evidence evidence "
            + "where evidence.broadcast_id = #{broadcastId}::uuid and evidence.row_number > 0 "
            + "and evidence.matched_recipient_id is null and evidence.job_id = ("
            + "select job.id from chatapp_broadcast_jobs job "
            + "where job.broadcast_id = #{broadcastId}::uuid and job.job_type = 'RECONCILE' "
            + "order by job.created_at desc, job.id desc limit 1)")
    long countUnmatched(@Param("broadcastId") UUID broadcastId);

    @Select("select * from chatapp_broadcast_reconciliation_evidence "
            + "where broadcast_id = #{broadcastId}::uuid "
            + "and diagnostic_code is not null and diagnostic_code <> '' "
            + "order by created_at desc, id desc limit 1")
    Optional<ChatAppBroadcastReconciliationEvidenceEntity> findLatestDiagnostic(
            @Param("broadcastId") UUID broadcastId);

    @Select("select * from chatapp_broadcast_reconciliation_evidence "
            + "where broadcast_id = #{broadcastId}::uuid order by created_at desc, id desc limit #{limit}")
    List<ChatAppBroadcastReconciliationEvidenceEntity> findLatest(
            @Param("broadcastId") UUID broadcastId, @Param("limit") int limit);
}
