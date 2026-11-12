package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantConversationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface AssistantConversationMapper {
    @Select("select id, user_id, status, created_at, last_activity_at, archived_at, expired_at, deleted_at, updated_at "
            + "from assistant_conversations where user_id = #{userId}::uuid and id = #{conversationId}::uuid")
    AssistantConversationEntity findOwned(@Param("userId") UUID userId,
                                           @Param("conversationId") UUID conversationId);

    @Insert("insert into assistant_conversations (id, user_id, status, created_at, last_activity_at, updated_at) "
            + "values (#{id}::uuid, #{userId}::uuid, 'ACTIVE', #{now}, #{now}, #{now}) "
            + "on conflict (user_id, id) do nothing")
    int insertActive(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Update("update assistant_conversations set status = 'ARCHIVED', archived_at = #{now}, updated_at = #{now} "
            + "where user_id = #{userId}::uuid and status = 'ACTIVE'")
    int archiveActive(@Param("userId") UUID userId, @Param("now") Instant now);

    @Update("update assistant_conversations set status = 'ACTIVE', archived_at = null, updated_at = #{now} "
            + "where user_id = #{userId}::uuid and id = #{conversationId}::uuid and status = 'ARCHIVED'")
    int activateArchived(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
                          @Param("now") Instant now);

    @Update("update assistant_conversations set last_activity_at = #{now}, updated_at = #{now} "
            + "where user_id = #{userId}::uuid and id = #{conversationId}::uuid and status = 'ACTIVE'")
    int touchActive(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
                    @Param("now") Instant now);

    @Update("update assistant_conversations set status = 'EXPIRED', expired_at = #{now}, updated_at = #{now} "
            + "where user_id = #{userId}::uuid and id = #{conversationId}::uuid "
            + "and status in ('ACTIVE', 'ARCHIVED') and last_activity_at < #{cutoff}")
    int markExpired(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
                    @Param("now") Instant now, @Param("cutoff") Instant cutoff);

    @Update("update assistant_conversations set status = 'EXPIRED', expired_at = #{now}, updated_at = #{now} "
            + "where status in ('ACTIVE', 'ARCHIVED') and last_activity_at < #{cutoff}")
    int expireStale(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    @Update("update assistant_conversations set status = 'DELETED', deleted_at = #{now}, updated_at = #{now} "
            + "where user_id = #{userId}::uuid and id = #{conversationId}::uuid "
            + "and status in ('ACTIVE', 'ARCHIVED')")
    int markDeleted(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
                    @Param("now") Instant now);

    @Select("select id, user_id, status, created_at, last_activity_at, archived_at, expired_at, deleted_at, updated_at "
            + "from assistant_conversations where user_id = #{userId}::uuid "
            + "and status in ('ACTIVE', 'ARCHIVED') "
            + "and (status = 'ACTIVE' or (status = 'ARCHIVED' and exists (select 1 "
            + "from assistant_conversation_messages m where m.user_id = assistant_conversations.user_id "
            + "and m.conversation_id = assistant_conversations.id))) "
            + "order by case when status = 'ACTIVE' then 0 else 1 end, "
            + "last_activity_at desc, id desc")
    List<AssistantConversationEntity> listVisible(@Param("userId") UUID userId);

    @Select("select id from assistant_conversations where user_id = #{userId}::uuid "
            + "and status in ('ACTIVE', 'ARCHIVED') "
            + "and (status = 'ACTIVE' or (status = 'ARCHIVED' and exists (select 1 "
            + "from assistant_conversation_messages m where m.user_id = assistant_conversations.user_id "
            + "and m.conversation_id = assistant_conversations.id))) "
            + "order by last_activity_at desc, id desc limit 1")
    UUID latestVisibleId(@Param("userId") UUID userId);
}
