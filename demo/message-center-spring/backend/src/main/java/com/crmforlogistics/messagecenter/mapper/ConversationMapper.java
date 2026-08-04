package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * Get or create a conversation for a given channel account + contact identity pair.
     * Uses PostgreSQL CTE to atomically upsert and return the row.
     */
    ConversationEntity getOrCreateConversation(@Param("channelAccountId") UUID channelAccountId,
                                               @Param("contactIdentityId") UUID contactIdentityId);

    /**
     * List conversation threads for a contact, across all identities.
     */
    IPage<ConversationEntity> listThreads(IPage<ConversationEntity> page,
                                          @Param("contactId") UUID contactId);

    /**
     * Lock and return a conversation for update (used in message insert).
     */
    ConversationEntity lockForMessage(@Param("conversationId") UUID conversationId,
                                      @Param("channelAccountId") UUID channelAccountId);
}
