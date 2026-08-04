package com.crmforlogistics.messagecenter.channel.chatapp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface SyncCursorMapper extends BaseMapper<ChannelSyncCursorEntity> {

    @Select("select id, channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp, updated_at, version " +
        "from channel_sync_cursors " +
        "where channel_account_id = #{channelAccountId}::uuid " +
        "and cursor_type = #{cursorType} " +
        "and scope_key = #{scopeKey} " +
        "limit 1")
    Optional<ChannelSyncCursorEntity> selectCursor(@Param("channelAccountId") UUID channelAccountId,
                                                    @Param("cursorType") String cursorType,
                                                    @Param("scopeKey") String scopeKey);

    @Update("insert into channel_sync_cursors (id, channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp, updated_at, version) " +
        "values (gen_random_uuid(), #{channelAccountId}::uuid, #{cursorType}, #{scopeKey}, #{cursorValue}, #{cursorTimestamp}, now(), 0) " +
        "on conflict (channel_account_id, cursor_type, scope_key) do update set " +
        "cursor_value = excluded.cursor_value, cursor_timestamp = excluded.cursor_timestamp, updated_at = now(), version = channel_sync_cursors.version + 1")
    int upsertCursor(@Param("channelAccountId") UUID channelAccountId,
                     @Param("cursorType") String cursorType,
                     @Param("scopeKey") String scopeKey,
                     @Param("cursorValue") String cursorValue,
                     @Param("cursorTimestamp") Instant cursorTimestamp);
}
