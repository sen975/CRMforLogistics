package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("call_transcript_revisions")
public class CallTranscriptRevisionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID callRecordId;
    private String text;
    private Instant editedAt;
    private String editedBy;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCallRecordId() { return callRecordId; }
    public void setCallRecordId(UUID callRecordId) { this.callRecordId = callRecordId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }

    public String getEditedBy() { return editedBy; }
    public void setEditedBy(String editedBy) { this.editedBy = editedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
