package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 助手会话消息。只有「追加一条」与「按会话正序读回来」两件事 ——
 * 这是一张追加写表，不提供更新与删除：能改的对话记录不能用来复盘。
 */
@Mapper
public interface AssistantConversationMessageMapper {

    /** {@code id} 与 {@code created_at} 都走数据库默认值，写入方不需要（也不该）自己造时间。 */
    @Insert("insert into assistant_conversation_messages "
            + "(conversation_id, user_id, role, kind, text) "
            + "values (#{conversationId}::uuid, #{userId}::uuid, #{role}, #{kind}, #{text})")
    int insert(AssistantConversationMessageEntity entity);

    /**
     * 某会话的历史消息，<b>时间正序</b>（对话要从上往下读）。
     *
     * <p>{@code user_id} 是查询条件的一部分而不是事后过滤：跨用户的会话即使
     * {@code conversation_id} 撞上（UUID 碰撞或有人拿别人的号来试），也读不出任何东西。
     * 归属校验放在 SQL 里是这套系统的既有立场 —— 不依赖调用方记得校验。
     */
    @Select("select id, conversation_id, user_id, role, kind, text, created_at "
            + "from assistant_conversation_messages "
            + "where user_id = #{userId}::uuid and conversation_id = #{conversationId}::uuid "
            + "order by created_at asc, id asc limit #{limit}")
    List<AssistantConversationMessageEntity> listByConversation(@Param("userId") UUID userId,
                                                               @Param("conversationId") UUID conversationId,
                                                               @Param("limit") int limit);
}
