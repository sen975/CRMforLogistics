package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("wecom_chatdata_cursor")
public class WeComChatDataCursorEntity {

    @TableId(type = IdType.INPUT)
    private String cursorKey;
    private String cursorValue;
    private Instant updatedAt;

    public String getCursorKey() { return cursorKey; }
    public void setCursorKey(String cursorKey) { this.cursorKey = cursorKey; }

    public String getCursorValue() { return cursorValue; }
    public void setCursorValue(String cursorValue) { this.cursorValue = cursorValue; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
