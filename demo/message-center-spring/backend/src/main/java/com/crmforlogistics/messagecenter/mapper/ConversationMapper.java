package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * Get or create a conversation for a given channel account + contact identity pair.
     * Uses PostgreSQL CTE to atomically upsert and return the row.
     */
    @Select("with existing as (" +
        "select id, channel_account_id, contact_identity_id, status, assigned_team_id, assigned_user_id, next_ingest_sequence, last_message_id, last_message_at, created_at, updated_at, version " +
        "from conversations where channel_account_id = #{channelAccountId}::uuid and contact_identity_id = #{contactIdentityId}::uuid" +
        "), ins as (" +
        "insert into conversations (id, channel_account_id, contact_identity_id) " +
        "select gen_random_uuid(), #{channelAccountId}::uuid, #{contactIdentityId}::uuid " +
        "where not exists (select 1 from existing) " +
        "on conflict (channel_account_id, contact_identity_id) do nothing " +
        "returning id, channel_account_id, contact_identity_id, status, assigned_team_id, assigned_user_id, next_ingest_sequence, last_message_id, last_message_at, created_at, updated_at, version" +
        ") select * from existing union all select * from ins limit 1")
    ConversationEntity getOrCreateConversation(@Param("channelAccountId") UUID channelAccountId,
                                               @Param("contactIdentityId") UUID contactIdentityId);

    /**
     * List conversation threads for a contact, across all identities.
     */
    @Select("<script>" +
        "select cv.id, cv.channel_account_id, cv.contact_identity_id, cv.status, cv.assigned_team_id, cv.assigned_user_id, cv.next_ingest_sequence, cv.last_message_id, cv.last_message_at, cv.created_at, cv.updated_at, cv.version " +
        "from conversations cv join contact_identities ci on ci.id = cv.contact_identity_id " +
        "where ci.contact_id = #{contactId}::uuid and ci.deleted_at is null " +
        "order by cv.last_message_at desc nulls last, cv.created_at desc " +
        "limit ${page.size}" +
        "</script>")
    IPage<ConversationEntity> listThreads(IPage<ConversationEntity> page,
                                          @Param("contactId") UUID contactId);

    /**
     * Lock and return a conversation for update (used in message insert).
     */
    @Select("select id, channel_account_id, contact_identity_id, status, assigned_team_id, assigned_user_id, next_ingest_sequence, last_message_id, last_message_at, created_at, updated_at, version " +
        "from conversations where id = #{conversationId}::uuid and channel_account_id = #{channelAccountId}::uuid for update")
    ConversationEntity lockForMessage(@Param("conversationId") UUID conversationId,
                                      @Param("channelAccountId") UUID channelAccountId);
}
