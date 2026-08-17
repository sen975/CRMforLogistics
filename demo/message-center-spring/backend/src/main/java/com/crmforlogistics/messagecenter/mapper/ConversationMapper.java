package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
     * Resolve the target conversation for an outbound send and atomically assign an
     * unassigned conversation to the already-authorized sender. Existing assignments
     * are never overwritten.
     */
    @Select("insert into conversations (id, channel_account_id, contact_identity_id, assigned_user_id) " +
        "values (gen_random_uuid(), #{channelAccountId}::uuid, #{contactIdentityId}::uuid, #{actorUserId}::uuid) " +
        "on conflict (channel_account_id, contact_identity_id) do update " +
        "set assigned_user_id = case when conversations.assigned_user_id is null " +
        "and conversations.assigned_team_id is null then excluded.assigned_user_id " +
        "else conversations.assigned_user_id end, " +
        "updated_at = case when conversations.assigned_user_id is null " +
        "and conversations.assigned_team_id is null then now() else conversations.updated_at end " +
        "returning id, channel_account_id, contact_identity_id, status, assigned_team_id, assigned_user_id, " +
        "next_ingest_sequence, last_message_id, last_message_at, created_at, updated_at, version")
    ConversationEntity getOrCreateConversationForSender(
            @Param("channelAccountId") UUID channelAccountId,
            @Param("contactIdentityId") UUID contactIdentityId,
            @Param("actorUserId") UUID actorUserId);

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

    @Update("update conversations cv set " +
        "last_message_id = (select m.id from messages m " +
        "where m.conversation_id = cv.id order by m.occurred_at desc, m.id desc limit 1), " +
        "last_message_at = (select m.occurred_at from messages m " +
        "where m.conversation_id = cv.id order by m.occurred_at desc, m.id desc limit 1), " +
        "updated_at = now(), version = version + 1 " +
        "where cv.id = #{conversationId}::uuid")
    int recomputeProjection(@Param("conversationId") UUID conversationId);

    @Select("<script>" +
        "select cv.id, cv.channel_account_id, cv.contact_identity_id, cv.status, " +
        "cv.assigned_team_id, cv.assigned_user_id, cv.next_ingest_sequence, " +
        "cv.last_message_id, cv.last_message_at, cv.created_at, cv.updated_at, cv.version " +
        "from conversations cv where cv.id = #{conversationId}::uuid " +
        "and cv.channel_account_id = #{channelAccountId}::uuid and (" +
        "cv.assigned_user_id = #{actorUserId}::uuid " +
        "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id " +
        "and tm.user_id = #{actorUserId}::uuid) " +
        "or exists (select 1 from conversation_access_grants g " +
        "where g.conversation_id = cv.id and g.user_id = #{actorUserId}::uuid " +
        "and g.revoked_at is null and (g.expires_at is null or g.expires_at &gt; now())) " +
        "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id " +
        "where ur.user_id = #{actorUserId}::uuid and r.code = 'admin')) " +
        "<if test=\"lockForUpdate\">for update</if>" +
        "</script>")
    ConversationEntity findAccessibleForMessage(@Param("conversationId") UUID conversationId,
                                                 @Param("channelAccountId") UUID channelAccountId,
                                                 @Param("actorUserId") UUID actorUserId,
                                                 @Param("lockForUpdate") boolean lockForUpdate);

    @Select("select cv.id, cv.channel_account_id, cv.contact_identity_id, cv.status, " +
        "cv.assigned_team_id, cv.assigned_user_id, cv.next_ingest_sequence, cv.last_message_id, " +
        "cv.last_message_at, cv.created_at, cv.updated_at, cv.version " +
        "from conversations cv join contact_identities ci on ci.id = cv.contact_identity_id " +
        "where ci.contact_id = #{contactId}::uuid and ci.deleted_at is null and " +
        "(cv.assigned_user_id = #{userId}::uuid " +
        "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())) " +
        "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = #{userId}::uuid and r.code = 'admin'))")
    java.util.List<ConversationEntity> listAccessibleForContact(@Param("contactId") UUID contactId,
                                                                  @Param("userId") UUID userId);
}
