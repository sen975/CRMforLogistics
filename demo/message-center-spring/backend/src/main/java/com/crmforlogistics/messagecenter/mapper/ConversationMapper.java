package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * Server-side union of accessible CRM contacts and observed WeCom groups.
     * The cursor is the pair (sort_at, sort_key), where sort_key is TYPE:id.
     */
    @Select("""
        <script>
        with unified as (
          select 'CONTACT' as type,
                 c.id,
                 c.display_name,
                 null::text as avatar_url,
                 coalesce(string_agg(distinct ci.channel_type, ','), '') as channel_types,
                 max(m.occurred_at) as last_message_at,
                 (array_agg(m.body_text order by m.occurred_at desc nulls last, m.id desc))[1] as last_text,
                 count(m.id)::int as message_count,
                 count(m.id) filter (where m.counts_as_unread)::int as unread_count,
                 null::text as provider_conversation_key,
                 0::int as participant_count,
                 coalesce(max(m.occurred_at), c.updated_at) as sort_at,
                 'CONTACT:' || c.id::text as sort_key
          from contacts c
          left join contact_identities ci on ci.contact_id = c.id and ci.deleted_at is null
          left join conversations cv on (
            cv.contact_identity_id = ci.id
            or exists (select 1 from wecom_source_conversations direct_sc
              where direct_sc.id = cv.source_conversation_id
                and direct_sc.conversation_type = 'DIRECT'
                and direct_sc.contact_identity_id = ci.id)
          ) and (
            exists (select 1 from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = #{userId}::uuid and r.code = 'admin')
            or cv.assigned_user_id = #{userId}::uuid
            or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
            or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now()))
          )
          left join messages m on m.conversation_id = cv.id
          where c.deleted_at is null and c.status <> 'merged'
            and (
              exists (select 1 from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = #{userId}::uuid and r.code = 'admin')
              or c.created_by = #{userId}::uuid
              or cv.id is not null
            )
            <if test="search != null and search != ''">
              and (c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark, '') ilike '%' || #{search} || '%')
            </if>
          group by c.id, c.display_name, c.updated_at
          union all
          select 'WECOM_GROUP' as type,
                 sc.id,
                 coalesce(nullif(sc.display_name, ''), sc.provider_conversation_key) as display_name,
                 sc.avatar_url,
                 'wecom' as channel_types,
                 max(m.occurred_at) as last_message_at,
                 (array_agg(m.body_text order by m.occurred_at desc nulls last, m.id desc))[1] as last_text,
                 count(m.id)::int as message_count,
                 count(m.id) filter (where m.counts_as_unread)::int as unread_count,
                 sc.provider_conversation_key,
                 count(distinct participant.party_id)::int as participant_count,
                 coalesce(max(m.occurred_at), sc.updated_at) as sort_at,
                 'WECOM_GROUP:' || sc.id::text as sort_key
          from wecom_source_conversations sc
          join conversations cv on cv.source_conversation_id = sc.id
          join wecom_installations wi on wi.id = sc.installation_id
          join wecom_user_bindings binding on binding.user_id = #{userId}::uuid
            and binding.suite_id = wi.suite_id and binding.auth_corp_id = wi.auth_corp_id
          join wecom_source_conversation_participants viewer_participant
            on viewer_participant.source_conversation_id = sc.id
            and viewer_participant.participant_status = 'OBSERVED'
          join wecom_parties viewer_party on viewer_party.id = viewer_participant.party_id
            and viewer_party.party_type = 'EMPLOYEE'
            and viewer_party.provider_party_id = binding.wecom_user_id
          left join wecom_source_conversation_participants participant
            on participant.source_conversation_id = sc.id and participant.participant_status = 'OBSERVED'
          left join messages m on m.conversation_id = cv.id
          where sc.conversation_type = 'GROUP'
            and (cv.assigned_user_id is null and cv.assigned_team_id is null
              or cv.assigned_user_id = #{userId}::uuid
              or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
              or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())))
            <if test="search != null and search != ''">
              and coalesce(nullif(sc.display_name, ''), sc.provider_conversation_key) ilike '%' || #{search} || '%'
            </if>
          group by sc.id, sc.display_name, sc.provider_conversation_key, sc.avatar_url, sc.updated_at
        )
        select type, id, display_name, avatar_url, channel_types, last_message_at, last_text,
               message_count, unread_count, provider_conversation_key, participant_count
        from unified
        <if test="cursorAt != null and cursorKey != null">
          where (sort_at &lt; #{cursorAt}::timestamptz or (sort_at = #{cursorAt}::timestamptz and sort_key &lt; #{cursorKey}))
        </if>
        order by sort_at desc nulls last, sort_key desc
        limit #{limit}
        </script>
        """)
    List<UnifiedConversationRow> listUnified(@Param("userId") UUID userId,
                                             @Param("search") String search,
                                             @Param("cursorAt") String cursorAt,
                                             @Param("cursorKey") String cursorKey,
                                             @Param("limit") int limit);

    record UnifiedConversationRow(String type, UUID id, String displayName, String avatarUrl,
                                  String channelTypes, Instant lastMessageAt, String lastText,
                                  int messageCount, int unreadCount,
                                  String providerConversationKey, int participantCount) {
        public ConversationListItemResponse toResponse() {
            List<String> channels = channelTypes == null || channelTypes.isBlank()
                    ? List.of() : Arrays.stream(channelTypes.split(","))
                    .filter(value -> !value.isBlank()).sorted().toList();
            return new ConversationListItemResponse(type, id, displayName, avatarUrl, channels,
                    lastMessageAt, lastText, messageCount, unreadCount,
                    providerConversationKey, participantCount);
        }
    }

    @Select("""
        select sc.id as source_conversation_id, sc.installation_id, sc.provider_conversation_key,
               sc.display_name, sc.avatar_url, sc.conversation_type, cv.id as conversation_id
        from wecom_source_conversations sc
        join conversations cv on cv.source_conversation_id = sc.id
        join wecom_installations wi on wi.id = sc.installation_id
        join wecom_user_bindings binding on binding.user_id = #{userId}::uuid
          and binding.suite_id = wi.suite_id and binding.auth_corp_id = wi.auth_corp_id
        join wecom_source_conversation_participants viewer_sp
          on viewer_sp.source_conversation_id = sc.id and viewer_sp.participant_status = 'OBSERVED'
        join wecom_parties viewer_party on viewer_party.id = viewer_sp.party_id
          and viewer_party.party_type = 'EMPLOYEE' and viewer_party.provider_party_id = binding.wecom_user_id
        where sc.id = #{sourceConversationId}::uuid and sc.conversation_type = 'GROUP'
          and (cv.assigned_user_id is null and cv.assigned_team_id is null
            or cv.assigned_user_id = #{userId}::uuid
            or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
            or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())))
        limit 1
        """)
    WeComSourceConversationAccessRow findAccessibleWeComGroup(@Param("userId") UUID userId,
                                                               @Param("sourceConversationId") UUID sourceConversationId);

    record WeComSourceConversationAccessRow(UUID sourceConversationId, UUID installationId,
                                            String providerConversationKey, String displayName,
                                            String avatarUrl, String conversationType,
                                            UUID conversationId) {}

    @Select("insert into conversations (id, channel_account_id, contact_identity_id, source_conversation_id) "
        + "values (gen_random_uuid(), #{channelAccountId}::uuid, null, #{sourceConversationId}::uuid) "
        + "on conflict (channel_account_id, source_conversation_id) "
        + "where source_conversation_id is not null do update set updated_at = now() "
        + "returning id, channel_account_id, contact_identity_id, source_conversation_id, status, assigned_team_id, "
        + "assigned_user_id, next_ingest_sequence, last_message_id, last_message_at, created_at, updated_at, version")
    ConversationEntity getOrCreateSourceConversation(
            @Param("channelAccountId") UUID channelAccountId,
            @Param("sourceConversationId") UUID sourceConversationId);

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

    @Select("update conversations set next_ingest_sequence = " +
        "greatest(next_ingest_sequence, coalesce((select max(ingest_sequence) from messages " +
        "where conversation_id = #{conversationId}::uuid), 0)) + 1, " +
        "updated_at = now(), version = version + 1 " +
        "where id = #{conversationId}::uuid returning next_ingest_sequence")
    long allocateNextIngestSequence(@Param("conversationId") UUID conversationId);

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

    /** WeCom source conversations are visible through an external party linked to this CRM contact. */
    @Select("select distinct cv.id, cv.channel_account_id, cv.contact_identity_id, cv.source_conversation_id, "
        + "cv.status, cv.assigned_team_id, cv.assigned_user_id, cv.next_ingest_sequence, cv.last_message_id, "
        + "cv.last_message_at, cv.created_at, cv.updated_at, cv.version "
        + "from conversations cv join wecom_source_conversations sc on sc.id = cv.source_conversation_id "
        + "join wecom_source_conversation_participants sp on sp.source_conversation_id = sc.id "
        + "join wecom_parties p on p.id = sp.party_id and p.party_type in ('EXTERNAL_CONTACT', 'EMPLOYEE') "
        + "join contact_identities ci on ci.channel_type = 'wecom' and ci.identity_scope = cv.channel_account_id::text "
        + "and ci.identity_value = p.provider_party_id and ci.contact_id = #{contactId}::uuid "
        + "where sp.participant_status = 'OBSERVED' and ci.deleted_at is null and ("
        + "cv.assigned_user_id = #{userId}::uuid or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) "
        + "or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())) "
        + "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = #{userId}::uuid and r.code = 'admin'))")
    java.util.List<ConversationEntity> listAccessibleWeComSourceForContact(@Param("contactId") UUID contactId,
                                                                             @Param("userId") UUID userId);
}
