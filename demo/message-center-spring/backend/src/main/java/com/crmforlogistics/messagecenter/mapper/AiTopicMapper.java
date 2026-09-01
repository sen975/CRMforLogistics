package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.UUID;

@Mapper
public interface AiTopicMapper extends BaseMapper<AiTopicEntity> {
    String SAFE_GROUP_OWNER_LABEL = "case when sc.group_kind='INTERNAL' then '内部群聊' "
            + "when sc.group_kind='EXTERNAL' and nullif(sc.display_name, '') is not null "
            + "and sc.display_name not like 'group:%' then sc.display_name else '外部群聊' end";

    @Select("select * from ai_topics where owner_type=#{ownerType} and owner_id=#{ownerId}::uuid and status='READY' order by last_occurred_at desc, id")
    List<AiTopicEntity> listReadyByOwner(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId);

    @Select("select exists (select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=#{userId}::uuid and r.code='admin')")
    boolean isAdmin(@Param("userId") UUID userId);

    @Select("select exists (select 1 from ai_topics t join wecom_source_conversation_participants p on p.source_conversation_id=t.owner_id and p.participant_status='OBSERVED' join wecom_parties party on party.id=p.party_id join contact_identities ci on ci.channel_type='wecom' and ci.identity_value=party.provider_party_id and ci.deleted_at is null join contacts c on c.id=ci.contact_id and c.deleted_at is null and c.status&lt;&gt;'merged' where t.id=#{topicId}::uuid and t.owner_type='WECOM_GROUP' and (#{isAdmin}=true or c.created_by=#{userId}::uuid or exists (select 1 from conversations cv where cv.contact_identity_id=ci.id and (cv.assigned_user_id=#{userId}::uuid or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{userId}::uuid) or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at&gt;now()))))))")
    boolean canAccessGroupTopic(@Param("topicId") UUID topicId, @Param("userId") UUID userId,
                                @Param("isAdmin") boolean isAdmin);

    @Select("select exists (select 1 from wecom_source_conversation_participants p join wecom_parties party on party.id=p.party_id join contact_identities ci on ci.channel_type='wecom' and ci.identity_value=party.provider_party_id and ci.deleted_at is null join contacts c on c.id=ci.contact_id and c.deleted_at is null and c.status&lt;&gt;'merged' where p.source_conversation_id=#{sourceConversationId}::uuid and p.participant_status='OBSERVED' and (c.created_by=#{userId}::uuid or exists (select 1 from conversations cv where cv.contact_identity_id=ci.id and (cv.assigned_user_id=#{userId}::uuid or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{userId}::uuid) or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at&gt;now()))))))")
    boolean canAccessGroupOwner(@Param("sourceConversationId") UUID sourceConversationId, @Param("userId") UUID userId);

    @Select("select t.*, c.display_name as contact_display_name, c.remark as contact_remark, ci.channel_type as contact_channel_type, coalesce(nullif(ci.display_name, ''), nullif(ci.identity_value, '')) as contact_channel_nickname from ai_topics t join contacts c on c.id=t.contact_id left join lateral (select channel_type, display_name, identity_value from contact_identities where contact_id=t.contact_id and deleted_at is null and channel_type in ('chatapp', 'email', 'phone') order by case when channel_type in (select distinct channel_type from ai_topic_items where topic_id=t.id and channel_type in ('chatapp', 'email', 'phone')) then 0 else 1 end, is_primary desc nulls last, created_at, id limit 1) ci on true where t.contact_id=#{contactId}::uuid and t.status='READY' and c.deleted_at is null order by t.last_occurred_at desc, t.id")
    List<AiTopicEntity> listReady(UUID contactId);

    @Select("select t.*, " + SAFE_GROUP_OWNER_LABEL + " as owner_label from ai_topics t join wecom_source_conversations sc on sc.id=t.owner_id where t.owner_type='WECOM_GROUP' and t.status='READY' and exists (select 1 from wecom_source_conversation_participants participant join wecom_parties party on party.id=participant.party_id join contact_identities ci on ci.channel_type='wecom' and ci.identity_value=party.provider_party_id and ci.deleted_at is null where participant.source_conversation_id=t.owner_id and participant.participant_status='OBSERVED' and ci.contact_id=#{contactId}::uuid) order by t.last_occurred_at desc, t.id")
    List<AiTopicEntity> listReadyReferencedGroupTopics(@Param("contactId") UUID contactId);

    @Select("select t.*, " + SAFE_GROUP_OWNER_LABEL + " as owner_label from ai_topics t join wecom_source_conversations sc on sc.id=t.owner_id where t.owner_type='WECOM_GROUP' and t.owner_id=#{sourceConversationId}::uuid and t.status='READY' order by t.last_occurred_at desc, t.id")
    List<AiTopicEntity> listReadyGroupTopics(@Param("sourceConversationId") UUID sourceConversationId);

    @Update("update ai_topics set title=#{title}, confirmed_summary=#{confirmedSummary}, version=version+1, updated_at=now() where id=#{id}::uuid and version=#{expectedVersion} and status='READY'")
    int updateEmployee(@Param("id") UUID id, @Param("title") String title, @Param("confirmedSummary") String confirmedSummary, @Param("expectedVersion") long expectedVersion);

    @Update("update ai_topics set status='ARCHIVED', updated_at=now() where id=#{id}::uuid and status='READY'")
    int archive(@Param("id") UUID id);

    @Update("update ai_topics set status='ARCHIVED', version=version+1, updated_at=now() "
            + "where owner_type='CONTACT' and owner_id=#{contactId}::uuid and status='READY'")
    int archiveReadyByContact(@Param("contactId") UUID contactId);

    @Update("update ai_topics set status=#{toStatus}, version=version+1, updated_at=now() where id=#{id}::uuid and status=#{fromStatus}")
    int transitionStatus(@Param("id") UUID id, @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus);

    @Select("<script>select t.*, c.display_name as contact_display_name, c.remark as contact_remark, ci.channel_type as contact_channel_type, coalesce(nullif(ci.display_name, ''), nullif(ci.identity_value, '')) as contact_channel_nickname, coalesce(c.display_name, " + SAFE_GROUP_OWNER_LABEL + ") as owner_label from ai_topics t left join contacts c on c.id=t.contact_id left join wecom_source_conversations sc on sc.id=t.wecom_group_source_conversation_id left join lateral (select channel_type, display_name, identity_value from contact_identities where contact_id=t.contact_id and deleted_at is null and channel_type in ('chatapp', 'email', 'phone') order by case when channel_type in (select distinct channel_type from ai_topic_items where topic_id=t.id and channel_type in ('chatapp', 'email', 'phone')) then 0 else 1 end, is_primary desc nulls last, created_at, id limit 1) ci on true where t.status='STORED' and (#{isAdmin}=true or (t.owner_type='CONTACT' and c.deleted_at is null and (c.created_by=#{userId}::uuid or exists (select 1 from contact_identities contact_identity join conversations cv on cv.contact_identity_id=contact_identity.id where contact_identity.contact_id=c.id and (cv.assigned_user_id=#{userId}::uuid or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{userId}::uuid) or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at&gt;now())))))) or (t.owner_type='WECOM_GROUP' and exists (select 1 from wecom_source_conversation_participants participant join wecom_parties party on party.id=participant.party_id join contact_identities group_identity on group_identity.channel_type='wecom' and group_identity.identity_value=party.provider_party_id and group_identity.deleted_at is null join contacts group_contact on group_contact.id=group_identity.contact_id and group_contact.deleted_at is null and group_contact.status&lt;&gt;'merged' where participant.source_conversation_id=t.owner_id and participant.participant_status='OBSERVED' and (group_contact.created_by=#{userId}::uuid or exists (select 1 from conversations cv where cv.contact_identity_id=group_identity.id and (cv.assigned_user_id=#{userId}::uuid or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=#{userId}::uuid) or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=#{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at&gt;now()))))))) ) <if test=\"ownerType != null\">and t.owner_type=#{ownerType}</if> <if test=\"search != null and search != ''\">and (t.title ilike '%'||#{search}||'%' or t.ai_summary ilike '%'||#{search}||'%' or c.display_name ilike '%'||#{search}||'%' or sc.display_name ilike '%'||#{search}||'%')</if> order by t.last_occurred_at desc, t.id</script>")
    IPage<AiTopicEntity> listStoredForUser(Page<AiTopicEntity> page, @Param("userId") UUID userId,
                                            @Param("search") String search, @Param("ownerType") String ownerType,
                                            @Param("isAdmin") boolean isAdmin);
}
