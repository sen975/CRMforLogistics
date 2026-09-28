package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AssistantConversationSummaryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface AssistantConversationSummaryMapper extends BaseMapper<AssistantConversationSummaryEntity> {

    @Select("select * from assistant_conversation_summaries "
            + "where user_id = #{userId}::uuid and conversation_id = #{conversationId}::uuid limit 1")
    AssistantConversationSummaryEntity find(@Param("userId") UUID userId,
                                            @Param("conversationId") UUID conversationId);

    @Insert("insert into assistant_conversation_summaries "
            + "(user_id, conversation_id, summary, through_message_id, through_created_at, covered_message_count, version) "
            + "values (#{userId}::uuid, #{conversationId}::uuid, #{summary}, "
            + "#{throughMessageId}::uuid, #{throughCreatedAt}, #{coveredMessageCount}, 1) "
            + "on conflict (user_id, conversation_id) do nothing")
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("conversationId") UUID conversationId,
                       @Param("summary") String summary,
                       @Param("throughCreatedAt") Instant throughCreatedAt,
                       @Param("throughMessageId") UUID throughMessageId,
                       @Param("coveredMessageCount") int coveredMessageCount);

    @Update("update assistant_conversation_summaries set summary = #{summary}, "
            + "through_message_id = #{throughMessageId}::uuid, through_created_at = #{throughCreatedAt}, "
            + "covered_message_count = #{coveredMessageCount}, "
            + "version = version + 1, updated_at = now() "
            + "where user_id = #{userId}::uuid and conversation_id = #{conversationId}::uuid "
            + "and version = #{expectedVersion}")
    int advanceIfVersion(@Param("userId") UUID userId,
                        @Param("conversationId") UUID conversationId,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("summary") String summary,
                        @Param("throughCreatedAt") Instant throughCreatedAt,
                        @Param("throughMessageId") UUID throughMessageId,
                        @Param("coveredMessageCount") int coveredMessageCount);
}
