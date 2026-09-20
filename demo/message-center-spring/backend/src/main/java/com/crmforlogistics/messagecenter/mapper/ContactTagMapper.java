package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Mapper
public interface ContactTagMapper {

    @Select("select t.id, t.name, t.color from contact_taggings ct "
            + "join contact_tags t on t.id = ct.tag_id "
            + "join contacts c on c.id = ct.contact_id "
            + "where ct.contact_id = #{contactId}::uuid and c.created_by = #{ownerId}::uuid "
            + "and t.status = 'active' and t.owner_user_id = #{ownerId}::uuid "
            + "order by lower(t.name), t.id")
    List<ContactTagResponse> findActiveByContactIdAndOwner(@Param("contactId") UUID contactId,
                                                            @Param("ownerId") UUID ownerId);

    @Select("select id, name, color from contact_tags where owner_user_id = #{ownerId}::uuid "
            + "and lower(name) = lower(#{name}) and status = 'active' limit 1")
    Optional<ContactTagResponse> findActiveByNameAndOwner(@Param("name") String name,
                                                          @Param("ownerId") UUID ownerId);

    @Insert("insert into contact_tags (owner_user_id, name, color, status) values (#{ownerId}::uuid, #{name}, #{color}, 'active') "
            + "on conflict (owner_user_id, lower(name)) do nothing")
    int insertTagForOwner(@Param("ownerId") UUID ownerId, @Param("name") String name,
                          @Param("color") String color);

    @Delete("delete from contact_taggings where contact_id = #{contactId}::uuid "
            + "and exists (select 1 from contacts c where c.id = #{contactId}::uuid and c.created_by = #{ownerId}::uuid)")
    int deleteByContactIdAndOwner(@Param("contactId") UUID contactId, @Param("ownerId") UUID ownerId);

    @Insert("insert into contact_taggings (contact_id, tag_id) "
            + "select #{contactId}::uuid, t.id from contact_tags t "
            + "join contacts c on c.id = #{contactId}::uuid and c.created_by = #{ownerId}::uuid "
            + "where t.id = #{tagId}::uuid and t.owner_user_id = #{ownerId}::uuid "
            + "on conflict do nothing")
    int insertTaggingForOwner(@Param("contactId") UUID contactId, @Param("tagId") UUID tagId,
                              @Param("ownerId") UUID ownerId);

    @Select("select t.id, t.name, t.color from contact_taggings ct "
            + "join contact_tags t on t.id = ct.tag_id "
            + "where ct.contact_id = #{contactId}::uuid and t.status = 'active' "
            + "order by lower(t.name), t.id")
    List<ContactTagResponse> findActiveByContactId(@Param("contactId") UUID contactId);

    @Select("select id, name, color from contact_tags "
            + "where lower(name) = lower(#{name}) and status = 'active' limit 1")
    Optional<ContactTagResponse> findActiveByName(@Param("name") String name);

    @Insert("insert into contact_tags (name, color, status) values (#{name}, #{color}, 'active') "
            + "on conflict (lower(name)) do nothing")
    int insertTag(@Param("name") String name, @Param("color") String color);

    @Delete("delete from contact_taggings where contact_id = #{contactId}::uuid")
    int deleteByContactId(@Param("contactId") UUID contactId);

    @Insert("insert into contact_taggings (contact_id, tag_id) values (#{contactId}::uuid, #{tagId}::uuid) "
            + "on conflict do nothing")
    int insertTagging(@Param("contactId") UUID contactId, @Param("tagId") UUID tagId);

    @Select("""
            <script>
            select contact_id, name from (
              select ct.contact_id as contact_id, t.name as name
              from contact_taggings ct
              join contact_tags t on t.id = ct.tag_id
              where t.owner_user_id = #{ownerId}::uuid
                and t.status = 'active'
                and t.name ilike '%' || #{search} || '%'
                and ct.contact_id in
                <foreach item="contactId" collection="contactIds" open="(" separator="," close=")">
                  #{contactId}::uuid
                </foreach>
              union all
              select al.contact_id, al.display_name
              from contact_ai_labels al
              where al.owner_user_id = #{ownerId}::uuid
                and al.status = 'ACTIVE'
                and al.display_name ilike '%' || #{search} || '%'
                and al.contact_id in
                <foreach item="contactId" collection="contactIds" open="(" separator="," close=")">
                  #{contactId}::uuid
                </foreach>
            ) matched
            group by contact_id, name
            order by lower(name), contact_id
            </script>
            """)
    List<MatchedTagRow> findMatchedByContactIds(@Param("ownerId") UUID ownerId,
                                                 @Param("contactIds") Collection<UUID> contactIds,
                                                 @Param("search") String search);

    record MatchedTagRow(UUID contactId, String name) {}
}
