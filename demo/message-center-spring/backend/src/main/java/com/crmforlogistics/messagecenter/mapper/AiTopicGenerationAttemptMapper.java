package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface AiTopicGenerationAttemptMapper extends BaseMapper<AiTopicGenerationAttemptEntity> {
    @Insert("insert into ai_topic_generation_attempts (id,generation_job_id,contact_id,owner_type,owner_id,attempt_number,provider_host,model,request_payload,request_truncated,stage,status,created_at) "
            + "values (#{id}::uuid,#{generationJobId}::uuid,#{contactId}::uuid,#{ownerType},#{ownerId}::uuid,#{attemptNumber},#{providerHost},#{model},cast(#{requestPayload} as jsonb),#{requestTruncated},#{stage},#{status},coalesce(#{createdAt},now()))")
    int insertStarted(AiTopicGenerationAttemptEntity entity);

    @Update("update ai_topic_generation_attempts set response_status=coalesce(#{responseStatus},response_status),response_headers=coalesce(cast(#{responseHeaders} as jsonb),response_headers),raw_response_body=coalesce(#{rawResponseBody},raw_response_body),response_truncated=coalesce(#{responseTruncated},response_truncated),parsed_response=coalesce(cast(#{parsedResponse} as jsonb),parsed_response),stage=#{stage},status=#{status},error_code=#{errorCode},error_diagnostic=#{errorDiagnostic},duration_ms=#{durationMs},completed_at=#{completedAt} where id=#{id}::uuid")
    int updateOutcome(AiTopicGenerationAttemptEntity entity);

    @Select("select * from ai_topic_generation_attempts where contact_id=#{contactId}::uuid order by created_at desc limit #{limit}")
    List<AiTopicGenerationAttemptEntity> listByContact(UUID contactId, int limit);
}
