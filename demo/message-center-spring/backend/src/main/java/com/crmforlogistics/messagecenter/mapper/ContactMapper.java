package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactMapper extends BaseMapper<ContactEntity> {

    @Select("""
            <script>
            select c.id as contact_id, ci.id as identity_id,
                   coalesce(nullif(c.remark, ''), nullif(ci.display_name, ''), ci.identity_value) as display_name,
                   c.remark, ci.channel_type, ci.identity_value as address,
                   coalesce(nullif(ci.display_name, ''), ci.identity_value) as channel_display_name,
                   coalesce((select string_agg(distinct other.channel_type, ',' order by other.channel_type)
                             from contact_identities other
                             where other.contact_id = c.id and other.deleted_at is null
                               and other.channel_type &lt;&gt; ci.channel_type), '') as additional_channel_types,
                   ci.source,
                   greatest(
                     (select max(m.occurred_at) from contact_identities all_ci
                      join conversations cv on cv.contact_identity_id = all_ci.id
                      join messages m on m.conversation_id = cv.id
                      where all_ci.contact_id = c.id and all_ci.deleted_at is null),
                     (select max(cr.occurred_at) from call_records cr where cr.contact_id = c.id)
                   ) as last_contact_at,
                   (exists (select 1 from contact_identities activity_ci
                            join conversations activity_cv on activity_cv.contact_identity_id = activity_ci.id
                            where activity_ci.contact_id = c.id)
                    or exists (select 1 from call_records cr where cr.contact_id = c.id)
                    or exists (select 1 from ai_topics topic where topic.contact_id = c.id)
                    or exists (select 1 from phone_notes pn where pn.contact_id = c.id)
                    or exists (select 1 from company_contacts cc where cc.contact_id = c.id)
                    or exists (select 1 from chatapp_broadcast_recipients br where br.contact_id = c.id)
                    or exists (select 1 from wecom_source_conversations sc where sc.contact_identity_id in
                        (select owned_ci.id from contact_identities owned_ci where owned_ci.contact_id = c.id)))
                   as has_activity,
                   (not exists (select 1 from contact_identities non_manual
                                where non_manual.contact_id = c.id and non_manual.source &lt;&gt; 'manual')
                    and not exists (select 1 from contact_identities activity_ci
                                    join conversations activity_cv on activity_cv.contact_identity_id = activity_ci.id
                                    where activity_ci.contact_id = c.id)
                    and not exists (select 1 from call_records cr where cr.contact_id = c.id)
                    and not exists (select 1 from ai_topics topic where topic.contact_id = c.id)
                    and not exists (select 1 from phone_notes pn where pn.contact_id = c.id)
                    and not exists (select 1 from company_contacts cc where cc.contact_id = c.id)
                    and not exists (select 1 from chatapp_broadcast_recipients br where br.contact_id = c.id)
                    and not exists (select 1 from wecom_source_conversations sc where sc.contact_identity_id in
                        (select owned_ci.id from contact_identities owned_ci where owned_ci.contact_id = c.id)))
                   as can_delete
            from contacts c
            join contact_identities ci on ci.contact_id = c.id and ci.deleted_at is null
            where c.created_by = #{ownerId}::uuid and c.deleted_at is null and c.status &lt;&gt; 'merged'
              and ci.channel_type = #{channelType}
            <if test="query != null and query != ''">
              and ( <choose>
                <when test="tagSearch">
                  exists (select 1 from contact_taggings ct
                          join contact_tags t on t.id = ct.tag_id
                          where ct.contact_id = c.id
                            and t.status = 'active'
                            and t.owner_user_id = #{ownerId}::uuid
                            and t.name ilike '%' || #{query} || '%')
                </when>
                <otherwise>
                  coalesce(c.remark, '') ilike '%' || #{query} || '%'
                   or c.display_name ilike '%' || #{query} || '%'
                   or coalesce(ci.display_name, '') ilike '%' || #{query} || '%'
                   or ci.identity_value ilike '%' || #{query} || '%'
                </otherwise>
              </choose> )
            </if>
            order by last_contact_at desc nulls last, c.updated_at desc, c.id desc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<ChannelAddressBookRow> listAddressBookByOwner(@Param("ownerId") UUID ownerId,
                                                        @Param("channelType") String channelType,
                                                        @Param("query") String query,
                                                        @Param("tagSearch") boolean tagSearch,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);

    @Select("""
            select (exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id=ci.id
                            where ci.contact_id=#{contactId}::uuid)
                 or exists (select 1 from call_records where contact_id=#{contactId}::uuid)
                 or exists (select 1 from ai_topics where contact_id=#{contactId}::uuid
                              or review_source_contact_id=#{contactId}::uuid)
                 or exists (select 1 from phone_notes where contact_id=#{contactId}::uuid)
                 or exists (select 1 from company_contacts where contact_id=#{contactId}::uuid)
                 or exists (select 1 from chatapp_broadcast_recipients where contact_id=#{contactId}::uuid)
                 or exists (select 1 from contacts where merged_to_id=#{contactId}::uuid)
                 or exists (select 1 from wecom_source_conversations where contact_identity_id in
                       (select id from contact_identities where contact_id=#{contactId}::uuid)))
            from contacts where id=#{contactId}::uuid and created_by=#{ownerId}::uuid
            """)
    boolean hasBusinessActivity(@Param("ownerId") UUID ownerId, @Param("contactId") UUID contactId);

    @Delete("delete from contact_taggings where contact_id=#{contactId}::uuid")
    int deleteTaggingsByContactId(@Param("contactId") UUID contactId);

    @Delete("delete from contacts where id=#{contactId}::uuid and created_by=#{ownerId}::uuid")
    int deleteOwned(@Param("ownerId") UUID ownerId, @Param("contactId") UUID contactId);

    record ChannelAddressBookRow(UUID contactId, UUID identityId, String displayName, String remark,
                                 String channelType, String address, String channelDisplayName,
                                 String additionalChannelTypes, String source, Instant lastContactAt,
                                 boolean hasActivity, boolean canDelete) {}

    @Select("select id, owner_user_id, display_name, role_title, remark, status, merged_to_id, " +
            "created_by, created_at, updated_at, deleted_at, version from contacts " +
            "where id = #{id}::uuid and created_by = #{ownerId}::uuid " +
            "and deleted_at is null and status <> 'merged' limit 1")
    Optional<ContactEntity> findByIdAndOwner(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Select("select created_by from contacts where id = #{id}::uuid and deleted_at is null limit 1")
    Optional<UUID> findCreatedBy(@Param("id") UUID id);

    /**
     * List contacts visible to a user with permission scoping:
     * user-owned contacts OR contacts with team-assigned conversations
     * OR contacts with access grants, excluding soft-deleted and merged contacts.
     * Cursor pagination by sort_at desc, id desc.
     */
    @Select("<script>" +
        "select visible.id, visible.display_name, visible.remark, visible.sort_at " +
        "from (" +
        "  select c.id, c.display_name, c.remark, " +
        "    coalesce((select max(m.occurred_at) " +
        "      from contact_identities ci " +
        "      join conversations cv on cv.contact_identity_id = ci.id " +
        "      join messages m on m.conversation_id = cv.id " +
        "      where ci.contact_id = c.id " +
        "      and (cv.assigned_user_id = #{userId}::uuid " +
        "        or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "        or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now()))) " +
        "    ), c.updated_at) as sort_at " +
        "  from contacts c " +
        "  where c.deleted_at is null and c.status != 'merged' " +
        "  <if test=\"!isAdmin\">" +
        "  and (c.created_by = #{userId}::uuid " +
        "    or exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id = ci.id " +
        "      where ci.contact_id = c.id " +
        "      and (cv.assigned_user_id = #{userId}::uuid " +
        "        or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "        or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now()))))) " +
        "  </if>" +
        "  <if test=\"search != null and search != ''\">" +
        "    and ( <choose>" +
        "      <when test=\"tagSearch\">" +
        "        exists (select 1 from contact_taggings ct join contact_tags t on t.id = ct.tag_id " +
        "          where ct.contact_id = c.id and t.status = 'active' " +
        "          and t.owner_user_id = #{userId}::uuid " +
        "          and t.name ilike '%' || #{search} || '%') " +
        "      </when>" +
        "      <otherwise>" +
        "        c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark,'') ilike '%' || #{search} || '%' " +
        "      </otherwise>" +
        "    </choose> ) " +
        "  </if>" +
        "  <if test=\"channelType != null and channelAccountId != null\">" +
        "    and exists (select 1 from contact_identities filtered_ci " +
        "      where filtered_ci.contact_id = c.id " +
        "      and filtered_ci.channel_type = #{channelType} " +
        "      and filtered_ci.identity_scope = #{channelAccountId}::text " +
        "      and filtered_ci.deleted_at is null) " +
        "  </if>" +
        ") visible " +
        "where 1 = 1 " +
        "<if test=\"beforeLastMessageAt != null and beforeId != null\">" +
        "  and (visible.sort_at &lt; #{beforeLastMessageAt} or (visible.sort_at = #{beforeLastMessageAt} and visible.id &lt; #{beforeId}::uuid)) " +
        "</if>" +
        "order by visible.sort_at desc, visible.id desc " +
        "limit #{page.size}" +
        "</script>")
    IPage<ContactEntity> listForUser(IPage<ContactEntity> page,
                                     @Param("userId") UUID userId,
                                     @Param("search") String search,
                                     @Param("tagSearch") boolean tagSearch,
                                     @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
                                     @Param("beforeId") UUID beforeId,
                                     @Param("isAdmin") boolean isAdmin,
                                     @Param("channelType") String channelType,
                                     @Param("channelAccountId") UUID channelAccountId);

    @Select("<script>" +
        "select id, display_name, role_title, remark, status, merged_to_id, created_by, created_at, updated_at, deleted_at, version " +
        "from contacts where id = #{id}::uuid and deleted_at is null and status != 'merged' " +
        "<if test=\"!isAdmin\">" +
        "and (created_by = #{userId}::uuid or exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id = ci.id " +
        "where ci.contact_id = contacts.id and (cv.assigned_user_id = #{userId}::uuid " +
        "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid) " +
        "or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now()))))) " +
        "</if>" +
        "limit 1" +
        "</script>")
    Optional<ContactEntity> findAccessibleById(@Param("id") UUID id,
                                               @Param("userId") UUID userId,
                                               @Param("isAdmin") boolean isAdmin);

    @Select("select c.id, c.display_name, c.role_title, c.remark, c.status, " +
            "c.merged_to_id, c.created_by, c.created_at, c.updated_at, c.deleted_at, c.version " +
            "from contacts c join contact_identities ci on ci.contact_id = c.id " +
            "where c.id = #{contactId}::uuid and c.deleted_at is null and c.status != 'merged' " +
            "and ci.id = #{identityId}::uuid and ci.channel_type = 'chatapp' " +
            "and ci.identity_scope = #{channelAccountId}::text and ci.deleted_at is null " +
            "and (c.created_by = #{userId}::uuid " +
            "or exists (select 1 from contact_identities access_ci " +
            "join conversations cv on cv.contact_identity_id = access_ci.id " +
            "where access_ci.contact_id = c.id and access_ci.deleted_at is null " +
            "and (cv.assigned_user_id = #{userId}::uuid " +
            "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id " +
            "and tm.user_id = #{userId}::uuid) " +
            "or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id " +
            "and g.user_id = #{userId}::uuid and g.revoked_at is null " +
            "and (g.expires_at is null or g.expires_at > now())))) " +
            "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id " +
            "where ur.user_id = #{userId}::uuid and r.code = 'admin')) limit 1")
    Optional<ContactEntity> findAccessibleForChatAppSend(
            @Param("contactId") UUID contactId,
            @Param("identityId") UUID identityId,
            @Param("channelAccountId") UUID channelAccountId,
            @Param("userId") UUID userId);

    @Update("update contacts set display_name = #{displayName}, updated_at = now(), version = version + 1 where id = #{id}::uuid")
    int updateDisplayName(@Param("id") UUID id, @Param("displayName") String displayName);
}
