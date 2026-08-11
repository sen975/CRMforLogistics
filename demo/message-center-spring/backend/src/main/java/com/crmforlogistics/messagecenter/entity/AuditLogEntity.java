package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("audit_logs")
public class AuditLogEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID actorUserId;
    private String action;
    private String resourceType;
    private UUID resourceId;
    private String beforeSummaryJsonb;
    private String afterSummaryJsonb;
    private String result;
    private String ipAddress;
    private String userAgent;
    private String traceId;
    private Instant occurredAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }
    public String getBeforeSummaryJsonb() { return beforeSummaryJsonb; }
    public void setBeforeSummaryJsonb(String beforeSummaryJsonb) { this.beforeSummaryJsonb = beforeSummaryJsonb; }
    public String getAfterSummaryJsonb() { return afterSummaryJsonb; }
    public void setAfterSummaryJsonb(String afterSummaryJsonb) { this.afterSummaryJsonb = afterSummaryJsonb; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
