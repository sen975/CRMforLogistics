package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComMessageSummaryJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

@Mapper
public interface WeComMessageSummaryJobMapper extends BaseMapper<WeComMessageSummaryJobEntity> {
    @Insert("INSERT INTO wecom_message_summary_jobs "
            + "(id, installation_id, auth_corp_id, source_conversation_id, msgid, send_time, status, "
            + " raw_request_json, attempt_count, next_attempt_at, created_at, updated_at) "
            + "VALUES (#{id}, #{installationId}, #{authCorpId}, #{sourceConversationId}, #{msgid}, #{sendTime}, 'PENDING', "
            + " #{rawRequestJson}, 0, #{nextAttemptAt}, #{createdAt}, #{updatedAt}) "
            + "ON CONFLICT (installation_id, msgid) DO NOTHING")
    int insertIfAbsent(WeComMessageSummaryJobEntity entity);

    @Select("SELECT id FROM wecom_message_summary_jobs "
            + "WHERE status IN ('PENDING','SUBMITTED','RETRY_WAIT') "
            + "AND next_attempt_at <= #{now} "
            + "AND (lease_until IS NULL OR lease_until <= #{now}) "
            + "ORDER BY next_attempt_at, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1")
    UUID leaseCandidate(@Param("now") Instant now);

    @Update("UPDATE wecom_message_summary_jobs SET lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=#{now} "
            + "WHERE id=#{jobId} AND status IN ('PENDING','SUBMITTED','RETRY_WAIT')")
    int updateLease(@Param("jobId") UUID jobId, @Param("owner") String owner,
                    @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Select("SELECT * FROM wecom_message_summary_jobs WHERE id = #{jobId}")
    WeComMessageSummaryJobEntity selectById(@Param("jobId") UUID jobId);

    @Select("SELECT * FROM wecom_message_summary_jobs "
            + "WHERE installation_id = #{installationId} AND msgid = #{msgid} LIMIT 1")
    WeComMessageSummaryJobEntity selectByInstallationMsgid(@Param("installationId") UUID installationId,
                                                            @Param("msgid") String msgid);

    @Update("UPDATE wecom_message_summary_jobs SET status='SUBMITTED', wecom_job_id=#{wecomJobId}, "
            + "next_attempt_at=#{nextAttemptAt}, lease_owner=NULL, lease_until=NULL, "
            + "submitted_at=COALESCE(submitted_at, now()), updated_at=now() "
            + "WHERE id=#{jobId} AND status IN ('PENDING','RETRY_WAIT','SUBMITTED')")
    int updateSubmitted(@Param("jobId") UUID jobId, @Param("wecomJobId") String wecomJobId,
                        @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update("UPDATE wecom_message_summary_jobs SET status='COMPLETED', summary=#{summary}, "
            + "raw_response_json=#{rawResponseJson}, validation_stage=#{validationStage}, "
            + "lease_owner=NULL, lease_until=NULL, completed_at=#{now}, updated_at=#{now} "
            + "WHERE id=#{jobId} AND status='SUBMITTED'")
    int updateCompleted(@Param("jobId") UUID jobId, @Param("summary") String summary,
                        @Param("rawResponseJson") String rawResponseJson,
                        @Param("validationStage") String validationStage, @Param("now") Instant now);

    @Update("UPDATE wecom_message_summary_jobs SET status='RETRY_WAIT', attempt_count=attempt_count+1, "
            + "next_attempt_at=#{nextAttemptAt}, raw_response_json=#{rawResponseJson}, "
            + "validation_stage=#{validationStage}, last_error_code=#{code}, lease_owner=NULL, lease_until=NULL, updated_at=now() "
            + "WHERE id=#{jobId} AND status IN ('PENDING','SUBMITTED','RETRY_WAIT') AND attempt_count < #{maxAttempts}")
    int updateRetry(@Param("jobId") UUID jobId, @Param("code") String code,
                    @Param("rawResponseJson") String rawResponseJson,
                    @Param("validationStage") String validationStage,
                    @Param("nextAttemptAt") Instant nextAttemptAt, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE wecom_message_summary_jobs SET status='FAILED', last_error_code=#{code}, failure_state=#{state}, "
            + "raw_response_json=#{rawResponseJson}, validation_stage=#{validationStage}, "
            + "lease_owner=NULL, lease_until=NULL, completed_at=#{now}, updated_at=#{now} "
            + "WHERE id=#{jobId} AND status IN ('PENDING','SUBMITTED','RETRY_WAIT')")
    int updateFailed(@Param("jobId") UUID jobId, @Param("code") String code, @Param("state") String state,
                     @Param("rawResponseJson") String rawResponseJson,
                     @Param("validationStage") String validationStage, @Param("now") Instant now);

    @Select("SELECT EXISTS (SELECT 1 FROM wecom_message_summary_jobs WHERE installation_id=#{installationId} AND msgid=#{msgid})")
    boolean exists(@Param("installationId") UUID installationId, @Param("msgid") String msgid);

    @Select({"<script>",
            "SELECT msgid FROM wecom_message_summary_jobs",
            "WHERE status IN ('PENDING','SUBMITTED','RETRY_WAIT') AND msgid IN",
            "<foreach collection='msgids' item='msgid' open='(' separator=',' close=')'>#{msgid}</foreach>",
            "</script>"})
    List<String> findNonTerminalMsgids(@Param("msgids") List<String> msgids);

    @Select({"<script>",
            "SELECT * FROM wecom_message_summary_jobs WHERE installation_id=#{installationId}",
            "<if test='sourceConversationId != null'> AND source_conversation_id=#{sourceConversationId}</if>",
            "<if test='status != null'> AND status=#{status}</if>",
            "<if test='fromSendTime != null'> AND send_time &gt;= #{fromSendTime}</if>",
            "<if test='toSendTime != null'> AND send_time &lt; #{toSendTime}</if>",
            "ORDER BY send_time DESC, id DESC OFFSET #{offset} LIMIT #{limit}",
            "</script>"})
    List<WeComMessageSummaryJobEntity> search(@Param("installationId") UUID installationId,
                                              @Param("sourceConversationId") UUID sourceConversationId,
                                              @Param("status") String status,
                                              @Param("fromSendTime") Long fromSendTime,
                                              @Param("toSendTime") Long toSendTime,
                                              @Param("offset") int offset, @Param("limit") int limit);

    @Select({"<script>",
            "SELECT count(*) FROM wecom_message_summary_jobs WHERE installation_id=#{installationId}",
            "<if test='sourceConversationId != null'> AND source_conversation_id=#{sourceConversationId}</if>",
            "<if test='status != null'> AND status=#{status}</if>",
            "<if test='fromSendTime != null'> AND send_time &gt;= #{fromSendTime}</if>",
            "<if test='toSendTime != null'> AND send_time &lt; #{toSendTime}</if>",
            "</script>"})
    long count(@Param("installationId") UUID installationId,
               @Param("sourceConversationId") UUID sourceConversationId,
               @Param("status") String status,
               @Param("fromSendTime") Long fromSendTime,
               @Param("toSendTime") Long toSendTime);

    @Select("SELECT j.* FROM wecom_message_summary_jobs j "
            + "JOIN wecom_source_conversations sc ON sc.id=j.source_conversation_id AND sc.conversation_type='DIRECT' "
            + "JOIN contact_identities ci ON ci.id=sc.contact_identity_id AND ci.deleted_at IS NULL "
            + "WHERE ci.contact_id=#{contactId}::uuid AND j.status='COMPLETED' "
            + "AND j.summary IS NOT NULL AND btrim(j.summary)<>'' "
            + "AND NOT EXISTS (SELECT 1 FROM ai_topic_items i WHERE i.wecom_message_summary_job_id=j.id) "
            + "ORDER BY j.send_time,j.id LIMIT #{limit}")
    List<WeComMessageSummaryJobEntity> listCompletedUnassignedForContact(
            @Param("contactId") UUID contactId, @Param("limit") int limit);

    @Select("SELECT j.* FROM wecom_message_summary_jobs j "
            + "JOIN ai_topic_items i ON i.wecom_message_summary_job_id=j.id "
            + "JOIN ai_topics t ON t.id=i.topic_id AND t.owner_type='CONTACT' AND t.status='ARCHIVED' "
            + "JOIN contacts source_contact ON source_contact.id=t.owner_id "
            + "JOIN wecom_source_conversations sc ON sc.id=j.source_conversation_id AND sc.conversation_type='DIRECT' "
            + "WHERE source_contact.status='merged' AND source_contact.merged_to_id=#{targetContactId}::uuid "
            + "AND j.status='COMPLETED' AND j.summary IS NOT NULL AND btrim(j.summary)<>'' "
            + "ORDER BY j.send_time,j.id LIMIT #{limit}")
    List<WeComMessageSummaryJobEntity> listArchivedMergedContactSummaries(
            @Param("targetContactId") UUID targetContactId, @Param("limit") int limit);

    @Select("SELECT j.* FROM wecom_message_summary_jobs j "
            + "JOIN wecom_source_conversations sc ON sc.id=j.source_conversation_id AND sc.conversation_type='GROUP' "
            + "WHERE j.source_conversation_id=#{sourceConversationId}::uuid AND j.status='COMPLETED' "
            + "AND j.summary IS NOT NULL AND btrim(j.summary)<>'' "
            + "AND NOT EXISTS (SELECT 1 FROM ai_topic_items i WHERE i.wecom_message_summary_job_id=j.id) "
            + "ORDER BY j.send_time,j.id LIMIT #{limit}")
    List<WeComMessageSummaryJobEntity> listCompletedUnassignedForGroup(
            @Param("sourceConversationId") UUID sourceConversationId, @Param("limit") int limit);
}
