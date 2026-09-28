package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface AssistantContactCandidateWindowMapper {

    @Insert("insert into assistant_contact_candidate_windows "
            + "(user_id, conversation_id, references_json, saved_at, expires_at) values "
            + "(#{userId}::uuid, #{conversationId}::uuid, #{referencesJson}::jsonb, #{savedAt}, #{expiresAt}) "
            + "on conflict (user_id, conversation_id) do update set "
            + "references_json = excluded.references_json, saved_at = excluded.saved_at, "
            + "expires_at = excluded.expires_at "
            + "where assistant_contact_candidate_windows.saved_at <= excluded.saved_at")
    int upsert(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
               @Param("referencesJson") String referencesJson, @Param("savedAt") Instant savedAt,
               @Param("expiresAt") Instant expiresAt);

    @Select("select references_json::text from assistant_contact_candidate_windows "
            + "where user_id = #{userId}::uuid and conversation_id = #{conversationId}::uuid "
            + "and expires_at > #{now} limit 1")
    String findFresh(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
                     @Param("now") Instant now);

    @Delete("delete from assistant_contact_candidate_windows where expires_at <= #{now}")
    int deleteExpired(@Param("now") Instant now);
}
