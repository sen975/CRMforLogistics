package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
}
