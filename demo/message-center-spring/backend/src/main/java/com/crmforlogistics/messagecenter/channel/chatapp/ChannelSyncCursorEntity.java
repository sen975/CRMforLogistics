package com.crmforlogistics.messagecenter.channel.chatapp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;
import java.util.UUID;

@TableName("channel_sync_cursors")
public class ChannelSyncCursorEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID channelAccountId;
    private String cursorType;
    private String scopeKey;
    private String cursorValue;
    private Instant cursorTimestamp;
    private Instant updatedAt;

    @Version
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }

    public String getCursorType() { return cursorType; }
    public void setCursorType(String cursorType) { this.cursorType = cursorType; }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getCursorValue() { return cursorValue; }
    public void setCursorValue(String cursorValue) { this.cursorValue = cursorValue; }

    public Instant getCursorTimestamp() { return cursorTimestamp; }
    public void setCursorTimestamp(Instant cursorTimestamp) { this.cursorTimestamp = cursorTimestamp; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
