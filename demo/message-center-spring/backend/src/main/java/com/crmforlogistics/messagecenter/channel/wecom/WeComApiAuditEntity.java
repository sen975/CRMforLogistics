package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_api_audit")
public class WeComApiAuditEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID operationId;
    private Instant occurredAt;
    private UUID installationId;
    private UUID actorUserId;
    private String action;
    private String result;
    private String upstreamPath;
    private String errorCode;
    private Integer upstreamErrcode;
    private Integer upstreamHttpStatus;
    private String upstreamHint;
    private String traceId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID operationId) { this.operationId = operationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public UUID getInstallationId() { return installationId; }
    public void setInstallationId(UUID installationId) { this.installationId = installationId; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getUpstreamPath() { return upstreamPath; }
    public void setUpstreamPath(String upstreamPath) { this.upstreamPath = upstreamPath; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getUpstreamErrcode() { return upstreamErrcode; }
    public void setUpstreamErrcode(Integer upstreamErrcode) { this.upstreamErrcode = upstreamErrcode; }
    public Integer getUpstreamHttpStatus() { return upstreamHttpStatus; }
    public void setUpstreamHttpStatus(Integer upstreamHttpStatus) { this.upstreamHttpStatus = upstreamHttpStatus; }
    public String getUpstreamHint() { return upstreamHint; }
    public void setUpstreamHint(String upstreamHint) { this.upstreamHint = upstreamHint; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
