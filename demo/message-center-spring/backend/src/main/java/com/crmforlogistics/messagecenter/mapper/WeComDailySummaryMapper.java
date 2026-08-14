package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDailySummaryEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDailySummaryJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface WeComDailySummaryMapper extends BaseMapper<WeComDailySummaryJobEntity> {

    @Insert("INSERT INTO wecom_daily_summary_jobs "
            + "(installation_id, auth_corp_id, summary_day, user_id, external_user_id, "
            + " slice_start, slice_end, message_count, message_digest, status, "
            + " next_attempt_at, deadline_at, created_at, updated_at) "
            + "VALUES (#{installationId}, #{authCorpId}, #{summaryDay}, #{userId}, #{externalUserId}, "
            + " #{sliceStart}, #{sliceEnd}, #{messageCount}, #{messageDigest}, 'PENDING', "
            + " #{nextAttemptAt}, #{deadlineAt}, #{createdAt}, #{updatedAt}) "
            + "ON CONFLICT (installation_id, auth_corp_id, summary_day, user_id, external_user_id, "
            + " slice_start, slice_end) DO NOTHING")
    int insertJob(WeComDailySummaryJobEntity entity);

    @Select("SELECT message_digest, message_count, deadline_at FROM wecom_daily_summary_jobs "
            + "WHERE installation_id = #{installationId} AND auth_corp_id = #{authCorpId} "
            + "  AND summary_day = #{day} AND user_id = #{userId} AND external_user_id = #{externalUserId} "
            + "  AND slice_start = #{sliceStart} AND slice_end = #{sliceEnd}")
    WeComDailySummaryJobEntity selectJobIdentity(@Param("installationId") String installationId,
                                                 @Param("authCorpId") String authCorpId,
                                                 @Param("day") LocalDate day,
                                                 @Param("userId") String userId,
                                                 @Param("externalUserId") String externalUserId,
                                                 @Param("sliceStart") int sliceStart,
                                                 @Param("sliceEnd") int sliceEnd);

    @Select("SELECT id FROM wecom_daily_summary_jobs "
            + "WHERE status IN ('PENDING','SUBMITTED','RETRY_WAIT') "
            + "  AND next_attempt_at <= #{now} "
            + "  AND (lease_until IS NULL OR lease_until <= #{now}) "
            + "ORDER BY next_attempt_at, created_at, id "
            + "FOR UPDATE SKIP LOCKED LIMIT 1")
    UUID leaseCandidate(@Param("now") Instant now);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET lease_owner = #{owner}, lease_until = #{leaseUntil}, updated_at = #{now} "
            + "WHERE id = #{jobId}")
    int updateLease(@Param("jobId") UUID jobId, @Param("owner") String owner,
                    @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Select("SELECT * FROM wecom_daily_summary_jobs WHERE id = #{jobId}")
    WeComDailySummaryJobEntity selectJobById(@Param("jobId") UUID jobId);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET status = 'SUBMITTED', wecom_job_id = #{wecomJobId}, next_attempt_at = #{nextAttemptAt}, "
            + "    lease_owner = NULL, lease_until = NULL, submitted_at = COALESCE(submitted_at, now()), updated_at = now() "
            + "WHERE id = #{jobId} AND status IN ('PENDING','RETRY_WAIT','SUBMITTED') "
            + "  AND (wecom_job_id IS NULL OR wecom_job_id = #{wecomJobId})")
    int updateSubmitted(@Param("jobId") UUID jobId, @Param("wecomJobId") String wecomJobId,
                        @Param("nextAttemptAt") Instant nextAttemptAt);

    @Select("SELECT installation_id, auth_corp_id, summary_day, user_id, external_user_id, status "
            + "FROM wecom_daily_summary_jobs WHERE id = #{jobId} FOR UPDATE")
    WeComDailySummaryJobEntity lockJobGroup(@Param("jobId") UUID jobId);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET status = 'COMPLETED', batch_summary = #{summary}, lease_owner = NULL, lease_until = NULL, "
            + "    completed_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{jobId} AND status = 'SUBMITTED'")
    int updateCompleted(@Param("jobId") UUID jobId, @Param("summary") String summary,
                        @Param("now") Instant now);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET status = 'RETRY_WAIT', attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt}, "
            + "    last_error_code = #{code}, lease_owner = NULL, lease_until = NULL, updated_at = now() "
            + "WHERE id = #{jobId} AND status IN ('PENDING','SUBMITTED','RETRY_WAIT') "
            + "  AND attempt_count < 20")
    int updateRetry(@Param("jobId") UUID jobId, @Param("code") String code,
                    @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET status = 'FAILED', last_error_code = #{code}, failure_state = #{state}, "
            + "    lease_owner = NULL, lease_until = NULL, completed_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{jobId} AND status IN ('PENDING','SUBMITTED','RETRY_WAIT')")
    int updateFailed(@Param("jobId") UUID jobId, @Param("code") String code,
                     @Param("state") String state, @Param("now") Instant now);

    @Select("SELECT installation_id, auth_corp_id, summary_day, user_id, external_user_id, "
            + "       slice_start, slice_end, status, wecom_job_id, deadline_at "
            + "FROM wecom_daily_summary_jobs WHERE id = #{jobId} FOR UPDATE")
    WeComDailySummaryJobEntity selectParentForUpdate(@Param("jobId") UUID jobId);

    @Select("SELECT count(*) FROM wecom_daily_summary_jobs "
            + "WHERE installation_id = #{installationId} AND auth_corp_id = #{authCorpId} "
            + "  AND summary_day = #{day} AND user_id = #{userId} AND external_user_id = #{externalUserId} "
            + "  AND status <> 'SUPERSEDED'")
    int countActive(@Param("installationId") String installationId,
                    @Param("authCorpId") String authCorpId, @Param("day") LocalDate day,
                    @Param("userId") String userId, @Param("externalUserId") String externalUserId);

    @Update("UPDATE wecom_daily_summary_jobs "
            + "SET status = 'SUPERSEDED', lease_owner = NULL, lease_until = NULL, updated_at = #{now} "
            + "WHERE id = #{jobId}")
    int updateSuperseded(@Param("jobId") UUID jobId, @Param("now") Instant now);

    @Select("SELECT count(*) FILTER (WHERE status NOT IN ('COMPLETED','FAILED')) AS non_terminal, "
            + "       count(*) AS batch_count, "
            + "       COALESCE(sum(message_count),0) AS message_count, "
            + "       count(*) FILTER (WHERE status = 'COMPLETED') AS completed_batches, "
            + "       COALESCE(sum(message_count) FILTER (WHERE status = 'COMPLETED'),0) AS completed_message_count, "
            + "       count(*) FILTER (WHERE status = 'FAILED') AS failed_batches, "
            + "       string_agg(batch_summary, E'\\n\\n' ORDER BY slice_start) FILTER (WHERE status = 'COMPLETED') AS combined_summary "
            + "FROM wecom_daily_summary_jobs "
            + "WHERE installation_id = #{installationId} AND auth_corp_id = #{authCorpId} "
            + "  AND summary_day = #{day} AND user_id = #{userId} AND external_user_id = #{externalUserId} "
            + "  AND status <> 'SUPERSEDED'")
    Map<String, Object> aggregateGroup(@Param("installationId") String installationId,
                                       @Param("authCorpId") String authCorpId,
                                       @Param("day") LocalDate day,
                                       @Param("userId") String userId,
                                       @Param("externalUserId") String externalUserId);

    @Insert("INSERT INTO wecom_daily_summaries "
            + "(installation_id, auth_corp_id, summary_day, user_id, external_user_id, summary, "
            + " message_count, completed_message_count, batch_count, completed_batch_count, "
            + " completeness, generated_at) "
            + "VALUES (#{installationId}, #{authCorpId}, #{summaryDay}, #{userId}, #{externalUserId}, #{summary}, "
            + " #{messageCount}, #{completedMessageCount}, #{batchCount}, #{completedBatchCount}, "
            + " #{completeness}, #{generatedAt}) "
            + "ON CONFLICT (installation_id, auth_corp_id, summary_day, user_id, external_user_id) DO NOTHING")
    int insertSummary(WeComDailySummaryEntity entity);

    @Select("SELECT summary, message_count, completed_message_count, batch_count, "
            + "       completed_batch_count, completeness, generated_at "
            + "FROM wecom_daily_summaries "
            + "WHERE installation_id = #{installationId} AND auth_corp_id = #{authCorpId} "
            + "  AND summary_day = #{day} AND user_id = #{userId} AND external_user_id = #{externalUserId}")
    WeComDailySummaryEntity selectSummary(@Param("installationId") String installationId,
                                          @Param("authCorpId") String authCorpId,
                                          @Param("day") LocalDate day,
                                          @Param("userId") String userId,
                                          @Param("externalUserId") String externalUserId);
}
