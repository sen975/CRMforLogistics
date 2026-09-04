package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface CallRecordMapper extends BaseMapper<CallRecordEntity> {

    @Select("SELECT * FROM call_records WHERE id = #{id}::uuid " +
            "AND owner_user_id = #{ownerId}::uuid")
    Optional<CallRecordEntity> findByIdAndOwner(@Param("id") UUID id,
                                                @Param("ownerId") UUID ownerId);

    @Select("SELECT * FROM call_records WHERE id = #{id}::uuid")
    Optional<CallRecordEntity> findById(@Param("id") UUID id);

    @Select("SELECT * FROM call_records WHERE contact_anchor_point_id = #{anchor} AND client_request_id = #{requestId}")
    Optional<CallRecordEntity> findByIdempotency(@Param("anchor") String anchor, @Param("requestId") String requestId);

    @Select("<script>"
            + "SELECT * FROM call_records WHERE contact_anchor_point_id IN "
            + "<foreach item='a' collection='anchors' open='(' separator=',' close=')'>#{a}</foreach>"
            + "</script>")
    List<CallRecordEntity> listByAnchors(@Param("anchors") java.util.Set<String> anchors);

    @Select("<script>"
            + "SELECT cr.* FROM call_records cr WHERE cr.contact_anchor_point_id IN "
            + "<foreach item='a' collection='anchors' open='(' separator=',' close=')'>#{a}</foreach> "
            + "AND NOT EXISTS (SELECT 1 FROM ai_topic_items assigned WHERE assigned.call_record_id = cr.id)"
            + "</script>")
    List<CallRecordEntity> listUnassignedByAnchors(@Param("anchors") java.util.Set<String> anchors);

    @Select("select cr.* from call_records cr "
            + "join ai_topic_items i on i.call_record_id=cr.id "
            + "join ai_topics t on t.id=i.topic_id and t.owner_type='CONTACT' and t.status='ARCHIVED' "
            + "join contacts source_contact on source_contact.id=t.owner_id "
            + "where source_contact.status='merged' and source_contact.merged_to_id=#{targetContactId}::uuid "
            + "order by cr.occurred_at, cr.id limit #{limit}")
    List<CallRecordEntity> listArchivedMergedContactCalls(@Param("targetContactId") UUID targetContactId,
                                                          @Param("limit") int limit);

    @Select("SELECT count(*) FROM call_records WHERE transcription_state IN ('queued', 'processing')")
    int countPending();

    @Select("SELECT * FROM call_records WHERE transcription_state = 'processing'")
    List<CallRecordEntity> findProcessing();

    @Select("<script>"
            + "SELECT * FROM call_records WHERE transcription_state = 'queued' "
            + "AND (transcription_next_attempt_at IS NULL OR transcription_next_attempt_at &lt;= #{now}) "
            + "ORDER BY created_at ASC LIMIT #{limit}"
            + "</script>")
    List<CallRecordEntity> listRunnable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE call_records SET "
            + "transcription_state = #{entity.transcriptionState}, "
            + "transcription_attempts = #{entity.transcriptionAttempts}, "
            + "transcription_lease_id = #{entity.transcriptionLeaseId}::uuid, "
            + "transcription_lease_worker_id = #{entity.transcriptionLeaseWorkerId}, "
            + "transcription_lease_expires_at = #{entity.transcriptionLeaseExpiresAt}, "
            + "transcription_next_attempt_at = #{entity.transcriptionNextAttemptAt}, "
            + "transcription_error_code = #{entity.transcriptionErrorCode}, "
            + "transcription_error_message = #{entity.transcriptionErrorMessage}, "
            + "transcription_error_retryable = #{entity.transcriptionErrorRetryable}, "
            + "transcription_result_model = #{entity.transcriptionResultModel}, "
            + "transcription_result_duration_seconds = #{entity.transcriptionResultDurationSeconds}, "
            + "transcription_result_original_text = #{entity.transcriptionResultOriginalText}, "
            + "transcription_result_segments = #{entity.transcriptionResultSegments}::jsonb, "
            + "transcription_result_completed_at = #{entity.transcriptionResultCompletedAt}, "
            + "current_revision_id = #{entity.currentRevisionId}::uuid, "
            + "version = #{entity.version}, "
            + "updated_at = now() "
            + "WHERE id = #{entity.id}::uuid AND version = #{expectedVersion}")
    int replace(@Param("entity") CallRecordEntity entity, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE call_records SET "
            + "note = #{note}, version = version + 1, updated_at = now() "
            + "WHERE id = #{id}::uuid AND version = #{expectedVersion}")
    int updateNote(@Param("id") UUID id, @Param("note") String note, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE call_records SET "
            + "current_revision_id = #{revisionId}::uuid, version = version + 1, updated_at = now() "
            + "WHERE id = #{id}::uuid AND version = #{expectedVersion}")
    int updateCurrentRevision(@Param("id") UUID id, @Param("revisionId") UUID revisionId, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE call_records SET transcription_state = 'queued', "
            + "transcription_lease_id = NULL, transcription_lease_worker_id = NULL, "
            + "transcription_lease_expires_at = NULL, "
            + "transcription_next_attempt_at = #{now}, version = version + 1, updated_at = now() "
            + "WHERE transcription_state = 'processing'")
    int recoverProcessing(@Param("now") Instant now);

    @Select("<script>"
            + "SELECT cr.* FROM call_records cr "
            + "WHERE cr.phone_point_id IS NOT NULL AND cr.phone_point_id LIKE 'phone:%' "
            + "<if test='query != null and query != \"\"'>"
            + "  AND (cr.phone_point_id LIKE CONCAT('%', #{query}, '%') "
            + "       OR cr.note ILIKE CONCAT('%', #{query}, '%'))"
            + "</if>"
            + "ORDER BY cr.occurred_at DESC, cr.id DESC"
            + "</script>")
    List<CallRecordEntity> searchPhoneRepository(@Param("query") String query);

    @Select("<script>SELECT cr.* FROM call_records cr " +
            "WHERE cr.owner_user_id = #{ownerId}::uuid " +
            "<if test='query != null and query != \"\"'>" +
            "AND (cr.phone_point_id LIKE CONCAT('%', #{query}, '%') " +
            "OR cr.note ILIKE CONCAT('%', #{query}, '%'))" +
            "</if> ORDER BY cr.occurred_at DESC, cr.id DESC</script>")
    List<CallRecordEntity> searchPhoneRepositoryByOwner(@Param("ownerId") UUID ownerId,
                                                         @Param("query") String query);
}
