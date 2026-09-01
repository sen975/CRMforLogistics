package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.UUID;
import com.crmforlogistics.messagecenter.dto.response.WeComPartyView;

@Mapper
public interface WeComChatDataMessageMapper extends BaseMapper<WeComChatDataMessageEntity> {

    @Select("""
        select p.id as party_id, p.party_type, p.provider_party_id, p.display_name, p.avatar_url,
               c.id as contact_id,
               case when c.id is not null and (c.created_by = #{userId}::uuid
                 or exists (select 1 from contact_identities ci2 join conversations cv on cv.contact_identity_id = ci2.id
                   where ci2.contact_id = c.id and (cv.assigned_user_id = #{userId}::uuid
                     or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id and tm.user_id = #{userId}::uuid)
                     or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id and g.user_id = #{userId}::uuid and g.revoked_at is null and (g.expires_at is null or g.expires_at > now())))))
               then true else false end as contact_accessible,
               case when p.party_type = 'EMPLOYEE' and p.provider_party_id = binding.wecom_user_id then true else false end as current_viewer
        from wecom_chatdata_messages m
        join wecom_parties p on p.id = m.sender_party_id
        join wecom_installations wi on wi.id = m.installation_id
        join wecom_user_bindings binding on binding.user_id = #{userId}::uuid
          and binding.suite_id = wi.suite_id and binding.auth_corp_id = wi.auth_corp_id
        left join contact_identities ci on ci.channel_type = 'wecom' and ci.identity_value = p.provider_party_id and ci.deleted_at is null
        left join contacts c on c.id = ci.contact_id and c.deleted_at is null and c.status <> 'merged'
        where m.msgid = #{msgid} limit 1
        """)
    WeComPartyView findMessageSenderView(@Param("msgid") String msgid, @Param("userId") UUID userId);

    @Select("""
            SELECT CASE
                     WHEN sender.party_type = 'EMPLOYEE'
                          AND sender.provider_party_id = binding.wecom_user_id THEN 'outbound'
                     WHEN EXISTS (
                       SELECT 1
                       FROM wecom_source_conversation_participants participant
                       JOIN wecom_parties viewer_party ON viewer_party.id = participant.party_id
                       WHERE participant.source_conversation_id = message.source_conversation_id
                         AND participant.participant_status = 'OBSERVED'
                         AND viewer_party.party_type = 'EMPLOYEE'
                         AND viewer_party.provider_party_id = binding.wecom_user_id
                     ) THEN 'inbound'
                     ELSE NULL
                   END
            FROM wecom_chatdata_messages message
            JOIN wecom_parties sender ON sender.id = message.sender_party_id
            JOIN wecom_installations installation ON installation.id = message.installation_id
            JOIN wecom_user_bindings binding
              ON binding.user_id = #{userId}::uuid
             AND binding.suite_id = installation.suite_id
             AND binding.auth_corp_id = installation.auth_corp_id
            WHERE message.msgid = #{msgid}
            ORDER BY message.created_at DESC
            LIMIT 1
            """)
    String resolveDirectionForViewer(@Param("msgid") String msgid,
                                     @Param("userId") UUID userId);

    @Insert("INSERT INTO wecom_chatdata_messages (id, installation_id, source_conversation_id, sender_party_id, "
            + "receiver_party_ids, msgid, secret_key, external_userid, userid, send_time, msgtype, direction, ingest_status) "
            + "VALUES (#{id}, #{installationId}, #{sourceConversationId}, #{senderPartyId}, "
            + "CAST(COALESCE(#{receiverPartyIds}, '[]') AS jsonb), "
            + "#{msgid}, #{secretKey}, #{externalUserid}, #{userid}, #{sendTime}, #{msgtype}, #{direction}, "
            + "COALESCE(#{ingestStatus}, 'stored')) "
            + "ON CONFLICT DO NOTHING")
    int insertIgnore(WeComChatDataMessageEntity entity);

    @Select("SELECT * FROM wecom_chatdata_messages "
            + "WHERE installation_id = #{installationId} AND msgid = #{msgid} LIMIT 1")
    WeComChatDataMessageEntity findSummaryReference(@Param("installationId") UUID installationId,
                                                    @Param("msgid") String msgid);

    @Select("SELECT m.* FROM wecom_chatdata_messages m "
            + "LEFT JOIN wecom_message_summary_jobs j "
            + "ON j.installation_id = m.installation_id AND j.msgid = m.msgid "
            + "WHERE m.installation_id = #{installationId} AND j.id IS NULL "
            + "ORDER BY m.send_time, m.id LIMIT #{limit}")
    List<WeComChatDataMessageEntity> findForSummaryBackfill(@Param("installationId") UUID installationId,
                                                            @Param("limit") int limit);

    @Select("SELECT m.*, sc.conversation_type AS conversation_type "
            + "FROM wecom_chatdata_messages m "
            + "LEFT JOIN wecom_source_conversations sc ON sc.id = m.source_conversation_id "
            + "WHERE m.send_time >= #{from} AND m.send_time < #{to} "
            + "ORDER BY m.send_time, m.msgid, m.userid, m.external_userid")
    List<WeComChatDataMessageEntity> findBySendTimeRange(long from, long to);

    @Select("SELECT * FROM wecom_chatdata_messages WHERE external_userid = #{externalUserid} "
            + "ORDER BY send_time, msgid")
    List<WeComChatDataMessageEntity> findByExternalUserid(String externalUserid);

    /**
     * Includes migrated DIRECT/GROUP rows whose legacy userid column is null,
     * but whose source conversation still records the viewer as an observed
     * employee participant.
     */
    @Select("""
            SELECT m.*
            FROM wecom_chatdata_messages m
            WHERE m.external_userid = #{externalUserid}
              AND (
                m.userid = #{wecomUserId}
                OR EXISTS (
                    SELECT 1
                    FROM wecom_source_conversation_participants sp
                    JOIN wecom_parties p ON p.id = sp.party_id
                    WHERE sp.source_conversation_id = m.source_conversation_id
                      AND sp.participant_status = 'OBSERVED'
                      AND p.party_type = 'EMPLOYEE'
                      AND p.provider_party_id = #{wecomUserId}
                )
              )
            ORDER BY m.send_time, m.msgid
            """)
    List<WeComChatDataMessageEntity> findViewableByExternalUserid(
            @Param("externalUserid") String externalUserid,
            @Param("wecomUserId") String wecomUserId);

    /** Resolves employee-to-employee/direct rows where legacy external_userid is null. */
    @Select("""
            SELECT m.*
            FROM wecom_chatdata_messages m
            WHERE m.source_conversation_id IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM wecom_source_conversation_participants contact_sp
                  JOIN wecom_parties contact_party ON contact_party.id = contact_sp.party_id
                  WHERE contact_sp.source_conversation_id = m.source_conversation_id
                    AND contact_sp.participant_status = 'OBSERVED'
                    AND contact_party.provider_party_id = #{contactPartyId}
                    AND contact_party.party_type IN ('EMPLOYEE', 'EXTERNAL_CONTACT')
              )
              AND EXISTS (
                  SELECT 1
                  FROM wecom_source_conversation_participants viewer_sp
                  JOIN wecom_parties viewer_party ON viewer_party.id = viewer_sp.party_id
                  WHERE viewer_sp.source_conversation_id = m.source_conversation_id
                    AND viewer_sp.participant_status = 'OBSERVED'
                    AND viewer_party.party_type = 'EMPLOYEE'
                    AND viewer_party.provider_party_id = #{wecomUserId}
              )
            ORDER BY m.send_time, m.msgid
            """)
    List<WeComChatDataMessageEntity> findViewableByContactParty(
            @Param("contactPartyId") String contactPartyId,
            @Param("wecomUserId") String wecomUserId);

    @Select("""
            SELECT DISTINCT m.*
            FROM wecom_chatdata_messages m
            JOIN wecom_source_conversations sc ON sc.id = m.source_conversation_id
            JOIN wecom_installations wi ON wi.id = sc.installation_id
            JOIN wecom_user_bindings binding ON binding.user_id = #{crmUserId}::uuid
              AND binding.suite_id = wi.suite_id AND binding.auth_corp_id = wi.auth_corp_id
            JOIN wecom_source_conversation_participants viewer_sp ON viewer_sp.source_conversation_id = sc.id
              AND viewer_sp.participant_status = 'OBSERVED'
            JOIN wecom_parties viewer_party ON viewer_party.id = viewer_sp.party_id
              AND viewer_party.party_type = 'EMPLOYEE'
              AND viewer_party.provider_party_id = binding.wecom_user_id
            JOIN contact_identities ci ON ci.contact_id = #{contactId}::uuid
              AND ci.channel_type = 'wecom' AND ci.deleted_at IS NULL
            JOIN wecom_source_conversation_participants contact_sp ON contact_sp.source_conversation_id = sc.id
              AND contact_sp.participant_status = 'OBSERVED'
            JOIN wecom_parties contact_party ON contact_party.id = contact_sp.party_id
              AND contact_party.provider_party_id = ci.identity_value
            WHERE sc.conversation_type = 'DIRECT'
            ORDER BY m.send_time, m.msgid
            """)
    List<WeComChatDataMessageEntity> findViewableByContactTarget(
            @Param("contactId") UUID contactId, @Param("crmUserId") UUID crmUserId);

    @Select("""
            SELECT DISTINCT m.*
            FROM wecom_chatdata_messages m
            JOIN wecom_source_conversations sc ON sc.id = m.source_conversation_id
            JOIN wecom_installations wi ON wi.id = sc.installation_id
            JOIN wecom_user_bindings binding ON binding.user_id = #{crmUserId}::uuid
              AND binding.suite_id = wi.suite_id AND binding.auth_corp_id = wi.auth_corp_id
            JOIN wecom_source_conversation_participants viewer_sp ON viewer_sp.source_conversation_id = sc.id
              AND viewer_sp.participant_status = 'OBSERVED'
            JOIN wecom_parties viewer_party ON viewer_party.id = viewer_sp.party_id
              AND viewer_party.party_type = 'EMPLOYEE'
              AND viewer_party.provider_party_id = binding.wecom_user_id
            WHERE sc.id = #{sourceConversationId}::uuid AND sc.conversation_type = 'GROUP'
            ORDER BY m.send_time, m.msgid
            """)
    List<WeComChatDataMessageEntity> findViewableByGroupTarget(
            @Param("sourceConversationId") UUID sourceConversationId,
            @Param("crmUserId") UUID crmUserId);

    @Select("""
            SELECT count(*) AS message_count,
                   COALESCE(sum(
                       octet_length(COALESCE(msgid, ''))
                       + octet_length(COALESCE(secret_key, ''))
                       + octet_length(COALESCE(external_userid, ''))
                       + octet_length(COALESCE(userid, ''))
                       + octet_length(COALESCE(msgtype, ''))
                       + octet_length(COALESCE(direction, ''))
                       + 8
                   ), 0) AS stored_bytes
            FROM wecom_chatdata_messages
            """)
    RetentionUsage retentionUsage();

    @Select("""
            SELECT id, msgid,
                   octet_length(COALESCE(msgid, ''))
                   + octet_length(COALESCE(secret_key, ''))
                   + octet_length(COALESCE(external_userid, ''))
                   + octet_length(COALESCE(userid, ''))
                   + octet_length(COALESCE(msgtype, ''))
                   + octet_length(COALESCE(direction, ''))
                   + 8 AS stored_bytes
            FROM wecom_chatdata_messages
            ORDER BY send_time, created_at, id
            LIMIT #{limit}
            """)
    List<RetentionCandidate> oldestRetentionCandidates(@Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM wecom_chatdata_messages
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}::uuid
            </foreach>
            </script>
            """)
    int deleteRetentionCandidates(@Param("ids") List<UUID> ids);

    record RetentionUsage(long messageCount, long storedBytes) {}
    record RetentionCandidate(UUID id, String msgid, long storedBytes) {}
}
