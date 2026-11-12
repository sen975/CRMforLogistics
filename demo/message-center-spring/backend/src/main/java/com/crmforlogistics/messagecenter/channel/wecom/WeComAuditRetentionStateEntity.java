package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("wecom_audit_retention_state")
public class WeComAuditRetentionStateEntity {

    @TableId
    private String stream;
    private Instant lastStartedAt;
    private Instant lastCompletedAt;
    private long deletedCount;
    private String status;
    private String errorCode;
    private Instant updatedAt;

    public String getStream() { return stream; }
    public void setStream(String stream) { this.stream = stream; }

    public Instant getLastStartedAt() { return lastStartedAt; }
    public void setLastStartedAt(Instant lastStartedAt) { this.lastStartedAt = lastStartedAt; }

    public Instant getLastCompletedAt() { return lastCompletedAt; }
    public void setLastCompletedAt(Instant lastCompletedAt) { this.lastCompletedAt = lastCompletedAt; }

    public long getDeletedCount() { return deletedCount; }
    public void setDeletedCount(long deletedCount) { this.deletedCount = deletedCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
