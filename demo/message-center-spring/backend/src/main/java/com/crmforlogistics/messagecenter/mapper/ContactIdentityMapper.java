package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactIdentityMapper extends BaseMapper<ContactIdentityEntity> {

    @Insert("insert into contact_identities (id, contact_id, channel_type, identity_scope, " +
            "identity_value, normalized_value, display_name, is_primary, verify_status, source, " +
            "created_at, updated_at, version) values (#{id}::uuid, #{contactId}::uuid, #{channelType}, " +
            "#{identityScope}, #{identityValue}, #{normalizedValue}, #{displayName}, #{isPrimary}, " +
            "#{verifyStatus}, #{source}, #{createdAt}, #{updatedAt}, #{version}) " +
            "on conflict (channel_type, identity_scope, normalized_value) where deleted_at is null " +
            "do nothing")
    int insertIfAbsent(ContactIdentityEntity identity);

    @Select("select count(*) from contact_identities where contact_id=#{contactId}::uuid " +
            "and deleted_at is null and source <> 'manual'")
    int countNonManualByContactId(@Param("contactId") UUID contactId);

    @Select("select count(*) from contact_identities where contact_id=#{contactId}::uuid " +
            "and deleted_at is null")
    int countActiveByContactId(@Param("contactId") UUID contactId);

    @Delete("delete from contact_identities where contact_id=#{contactId}::uuid")
    int deleteByContactId(@Param("contactId") UUID contactId);

    /**
     * Move all identities from one contact to another (merge).
     */
    @Update("update contact_identities set contact_id = #{newContactId}::uuid, updated_at = now(), version = version + 1 where contact_id = #{oldContactId}::uuid")
    int updateContactId(@Param("newContactId") UUID newContactId,
                        @Param("oldContactId") UUID oldContactId);

    /**
     * Move a single identity to a different contact (split).
     */
    @Update("update contact_identities set contact_id = #{newContactId}::uuid, updated_at = now(), version = version + 1 where id = #{identityId}::uuid")
    int updateContactIdForIdentity(@Param("newContactId") UUID newContactId,
                                   @Param("identityId") UUID identityId);

    /**
     * List identities for a contact (non-deleted, primary-first ordering).
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where contact_id = #{contactId}::uuid and deleted_at is null order by is_primary desc, created_at, id")
    List<ContactIdentityEntity> findByContactId(@Param("contactId") UUID contactId);

    @Select("select ci.id, ci.contact_id, ci.channel_type, ci.identity_scope, ci.identity_value, "
            + "ci.normalized_value, ci.display_name, ci.is_primary, ci.verify_status, ci.source, "
            + "ci.created_at, ci.updated_at, ci.deleted_at, ci.version "
            + "from contact_identities ci join contacts c on c.id=ci.contact_id "
            + "where ci.contact_id=#{contactId}::uuid and (c.created_by=#{ownerId}::uuid "
            + "or exists (select 1 from conversations cv where cv.contact_identity_id=ci.id and ("
            + "cv.assigned_user_id=#{ownerId}::uuid "
            + "or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{ownerId}::uuid) "
            + "or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{ownerId}::uuid "
            + "and g.revoked_at is null and (g.expires_at is null or g.expires_at>now())))) "
            + "or exists (select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=#{ownerId}::uuid and r.code='admin')) "
            + "and ci.deleted_at is null and c.deleted_at is null and c.status<>'merged' "
            + "order by ci.is_primary desc, ci.created_at, ci.id")
    List<ContactIdentityEntity> findByContactIdAndOwner(@Param("contactId") UUID contactId,
                                                        @Param("ownerId") UUID ownerId);

    @Select("select ci.id, ci.contact_id, ci.channel_type, ci.identity_scope, ci.identity_value, "
            + "ci.normalized_value, ci.display_name, ci.is_primary, ci.verify_status, ci.source, "
            + "ci.created_at, ci.updated_at, ci.deleted_at, ci.version "
            + "from contact_identities ci join contacts c on c.id=ci.contact_id "
            + "where ci.channel_type='phone' and ci.deleted_at is null and c.deleted_at is null "
            + "and c.status<>'merged' and c.created_by=#{ownerId}::uuid "
            + "and ('phone:' || ci.normalized_value)=#{anchor} "
            + "order by ci.is_primary desc, ci.created_at, ci.id limit 1")
    Optional<ContactIdentityEntity> findPhoneByAnchorAndOwner(@Param("anchor") String anchor,
                                                               @Param("ownerId") UUID ownerId);

    /**
     * Find a non-deleted identity by normalized value within a channel type.
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where channel_type = #{channelType} and normalized_value = #{normalizedValue} and deleted_at is null limit 1")
    Optional<ContactIdentityEntity> findByNormalizedValue(@Param("channelType") String channelType,
                                                          @Param("normalizedValue") String normalizedValue);

    /**
     * Find a non-deleted identity by normalized value within a channel type and scope.
     */
    @Select("select id, contact_id, channel_type, identity_scope, identity_value, normalized_value, display_name, is_primary, verify_status, source, created_at, updated_at, deleted_at, version from contact_identities where channel_type = #{channelType} and identity_scope = #{identityScope} and normalized_value = #{normalizedValue} and deleted_at is null limit 1")
    Optional<ContactIdentityEntity> findByNormalizedValueInScope(@Param("channelType") String channelType,
                                                                 @Param("identityScope") String identityScope,
                                                                 @Param("normalizedValue") String normalizedValue);

    @Update("update contact_identities set display_name = #{displayName}, updated_at = now(), version = version + 1 where id = #{id}::uuid")
    int updateDisplayName(@Param("id") UUID id, @Param("displayName") String displayName);

    @Select("<script>"
            + "select ci.id as contact_identity_id, ci.contact_id, "
            + "ci.identity_value as recipient_number, ci.normalized_value as normalized_number, "
            + "coalesce(nullif(ci.display_name, ''), c.display_name) as recipient_name "
            + "from contact_identities ci join contacts c on c.id = ci.contact_id "
            + "where ci.id in "
            + "<foreach item='id' collection='identityIds' open='(' separator=',' close=')'>#{id}::uuid</foreach> "
            + "and ci.channel_type = 'chatapp' and ci.identity_scope = #{channelAccountId}::text "
            + "and ci.deleted_at is null and c.deleted_at is null and c.status != 'merged' "
            + "and (c.created_by = #{userId}::uuid "
            + "or exists (select 1 from conversations cv where cv.contact_identity_id = ci.id "
            + "and (cv.assigned_user_id = #{userId}::uuid "
            + "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id "
            + "and tm.user_id = #{userId}::uuid) "
            + "or exists (select 1 from conversation_access_grants grant_row "
            + "where grant_row.conversation_id = cv.id and grant_row.user_id = #{userId}::uuid "
            + "and grant_row.revoked_at is null "
            + "and (grant_row.expires_at is null or grant_row.expires_at &gt; now())))) "
            + "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id "
            + "where ur.user_id = #{userId}::uuid and r.code = 'admin')) "
            + "order by ci.id"
            + "</script>")
    List<RecipientCandidate> findEligibleChatAppBroadcastRecipients(
            @Param("channelAccountId") UUID channelAccountId,
            @Param("identityIds") List<UUID> identityIds,
            @Param("userId") UUID userId);

    @Select("""
            select (
                exists (
                    select 1 from user_roles ur join roles r on r.id = ur.role_id
                    where ur.user_id = #{userId}::uuid and r.code = 'admin'
                )
                or exists (
                    select 1 from contact_identities ci join contacts c on c.id = ci.contact_id
                    where ci.channel_type = 'chatapp'
                      and ci.identity_scope = #{channelAccountId}::text
                      and ci.deleted_at is null
                      and c.deleted_at is null
                      and c.status != 'merged'
                      and (
                          c.created_by = #{userId}::uuid
                          or exists (
                              select 1 from conversations cv
                              where cv.contact_identity_id = ci.id
                                and (
                                    cv.assigned_user_id = #{userId}::uuid
                                    or exists (
                                        select 1 from team_members tm
                                        where tm.team_id = cv.assigned_team_id
                                          and tm.user_id = #{userId}::uuid
                                    )
                                    or exists (
                                        select 1 from conversation_access_grants g
                                        where g.conversation_id = cv.id
                                          and g.user_id = #{userId}::uuid
                                          and g.revoked_at is null
                                          and (g.expires_at is null or g.expires_at > now())
                                    )
                                )
                          )
                      )
                )
            )
            """)
    boolean canAccessChatAppAccount(@Param("channelAccountId") UUID channelAccountId,
                                    @Param("userId") UUID userId);
}
