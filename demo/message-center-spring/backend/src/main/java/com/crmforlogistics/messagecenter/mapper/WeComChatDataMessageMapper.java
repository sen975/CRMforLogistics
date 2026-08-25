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

@Mapper
public interface WeComChatDataMessageMapper extends BaseMapper<WeComChatDataMessageEntity> {

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

    @Select("SELECT * FROM wecom_chatdata_messages WHERE send_time >= #{from} AND send_time < #{to} "
            + "ORDER BY send_time, msgid, userid, external_userid")
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
