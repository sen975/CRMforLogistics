package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComSourceParticipantEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.UUID;
import java.util.List;
import com.crmforlogistics.messagecenter.dto.response.WeComPartyView;

@Mapper
public interface WeComSourceParticipantMapper extends BaseMapper<WeComSourceParticipantEntity> {
    @Insert("insert into wecom_source_conversation_participants (source_conversation_id, party_id, "
            + "participant_status, first_observed_at, last_observed_at) values (#{conversationId}::uuid, "
            + "#{partyId}::uuid, 'OBSERVED', now(), now()) on conflict (source_conversation_id, party_id) "
            + "do update set participant_status = 'OBSERVED', last_observed_at = now()")
    int observe(@Param("conversationId") UUID conversationId, @Param("partyId") UUID partyId);

    @Select("""
        select p.id as party_id, p.party_type, p.provider_party_id, p.display_name, p.avatar_url,
               c.id as contact_id,
               case when c.id is not null and (c.created_by = #{userId}::uuid
                 or exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id = ci.id
                   where ci.contact_id = c.id and (cv.assigned_user_id = #{userId}::uuid
                     or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
                     or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())))))
               then true else false end as contact_accessible,
               case when p.party_type = 'EMPLOYEE' and p.provider_party_id = binding.wecom_user_id then true else false end as current_viewer
        from wecom_source_conversation_participants sp
        join wecom_parties p on p.id = sp.party_id
        join wecom_source_conversations sc on sc.id = sp.source_conversation_id
        join wecom_installations wi on wi.id = sc.installation_id
        join wecom_user_bindings binding on binding.user_id = #{userId}::uuid
          and binding.suite_id = wi.suite_id and binding.auth_corp_id = wi.auth_corp_id
        left join contact_identities ci on ci.channel_type = 'wecom' and ci.identity_value = p.provider_party_id and ci.deleted_at is null
        left join contacts c on c.id = ci.contact_id and c.deleted_at is null and c.status <> 'merged'
        where sp.source_conversation_id = #{sourceConversationId}::uuid and sp.participant_status = 'OBSERVED'
        order by p.display_name nulls last, p.provider_party_id
        """)
    List<WeComPartyView> listPartyViews(@Param("sourceConversationId") UUID sourceConversationId,
                                        @Param("userId") UUID userId);

    /**
     * Resolves WeCom external contact ids to the CRM contact the current account may open.
     * The accessibility rule is the same one used by {@link #listPartyViews}: the account owns
     * the contact, or it can reach a conversation of that identity. Rows are ordered so that an
     * accessible identity wins when one external user id maps to several identities.
     */
    @Select("""
        <script>
        select ci.identity_value as provider_party_id, ci.id as identity_id, c.id as contact_id,
               case when c.created_by = #{userId}::uuid
                 or exists (select 1 from conversations cv where cv.contact_identity_id = ci.id
                   and (cv.assigned_user_id = #{userId}::uuid
                     or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
                     or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now()))))
               then true else false end as contact_accessible
        from contact_identities ci
        join contacts c on c.id = ci.contact_id and c.deleted_at is null and c.status &lt;&gt; 'merged'
        where ci.channel_type = 'wecom' and ci.deleted_at is null
          and ci.identity_value in
          <foreach item="id" collection="externalUserIds" open="(" separator="," close=")">#{id}</foreach>
        order by ci.identity_value, contact_accessible desc, ci.id
        </script>
        """)
    List<WeComExternalContactLinkRow> resolveExternalContactLinks(
            @Param("userId") UUID userId,
            @Param("externalUserIds") List<String> externalUserIds);

    record WeComExternalContactLinkRow(String providerPartyId, UUID identityId, UUID contactId,
                                       boolean contactAccessible) {}
}
