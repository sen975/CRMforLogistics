package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataCursorEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WeComChatDataCursorMapper extends BaseMapper<WeComChatDataCursorEntity> {

    @Select("SELECT cursor_value FROM wecom_chatdata_cursor WHERE cursor_key = #{cursorKey}")
    String findValueByKey(String cursorKey);

    @Insert("INSERT INTO wecom_chatdata_cursor (cursor_key, cursor_value, updated_at) "
            + "VALUES (#{cursorKey}, #{cursorValue}, now()) "
            + "ON CONFLICT (cursor_key) DO UPDATE SET cursor_value = EXCLUDED.cursor_value, updated_at = now()")
    int upsert(String cursorKey, String cursorValue);
}
