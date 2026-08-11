package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ContactMapper extends BaseMapper<ContactEntity> {

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
        "    and (c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark,'') ilike '%' || #{search} || '%') " +
        "  </if>" +
        ") visible " +
        "where 1 = 1 " +
        "<if test=\"beforeLastMessageAt != null and beforeId != null\">" +
        "  and (visible.sort_at &lt; #{beforeLastMessageAt} or (visible.sort_at = #{beforeLastMessageAt} and visible.id &lt; #{beforeId}::uuid)) " +
        "</if>" +
        "order by visible.sort_at desc, visible.id desc " +
        "limit ${page.size}" +
        "</script>")
    IPage<ContactEntity> listForUser(IPage<ContactEntity> page,
                                     @Param("userId") UUID userId,
                                     @Param("search") String search,
                                     @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
                                     @Param("beforeId") UUID beforeId,
                                     @Param("isAdmin") boolean isAdmin);

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
}
