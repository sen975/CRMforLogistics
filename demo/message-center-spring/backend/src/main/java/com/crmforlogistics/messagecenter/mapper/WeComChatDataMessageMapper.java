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

    @Insert("INSERT INTO wecom_chatdata_messages (msgid, secret_key, external_userid, userid, send_time, msgtype, direction) "
            + "VALUES (#{msgid}, #{secretKey}, #{externalUserid}, #{userid}, #{sendTime}, #{msgtype}, #{direction}) "
            + "ON CONFLICT (msgid, userid, external_userid) DO NOTHING")
    int insertIgnore(WeComChatDataMessageEntity entity);

    @Select("SELECT * FROM wecom_chatdata_messages WHERE send_time >= #{from} AND send_time < #{to} "
            + "ORDER BY send_time, msgid, userid, external_userid")
    List<WeComChatDataMessageEntity> findBySendTimeRange(long from, long to);

    @Select("SELECT * FROM wecom_chatdata_messages WHERE external_userid = #{externalUserid} "
            + "ORDER BY send_time, msgid")
    List<WeComChatDataMessageEntity> findByExternalUserid(String externalUserid);

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
