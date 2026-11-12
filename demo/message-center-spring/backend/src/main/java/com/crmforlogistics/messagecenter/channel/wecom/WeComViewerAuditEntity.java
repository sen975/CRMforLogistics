package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_viewer_audit")
public class WeComViewerAuditEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Instant occurredAt;
    private String action;
    private String result;
    private String wecomUserId;
    private String contactPointId;
    private String viewerSessionId;
    private String errorCode;
    private Integer upstreamErrcode;
    private String upstreamPath;
    private Integer upstreamHttpStatus;
    private String upstreamHint;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getWecomUserId() { return wecomUserId; }
    public void setWecomUserId(String wecomUserId) { this.wecomUserId = wecomUserId; }

    public String getContactPointId() { return contactPointId; }
    public void setContactPointId(String contactPointId) { this.contactPointId = contactPointId; }

    public String getViewerSessionId() { return viewerSessionId; }
    public void setViewerSessionId(String viewerSessionId) { this.viewerSessionId = viewerSessionId; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Integer getUpstreamErrcode() { return upstreamErrcode; }
    public void setUpstreamErrcode(Integer upstreamErrcode) { this.upstreamErrcode = upstreamErrcode; }

    public String getUpstreamPath() { return upstreamPath; }
    public void setUpstreamPath(String upstreamPath) { this.upstreamPath = upstreamPath; }

    public Integer getUpstreamHttpStatus() { return upstreamHttpStatus; }
    public void setUpstreamHttpStatus(Integer upstreamHttpStatus) { this.upstreamHttpStatus = upstreamHttpStatus; }

    public String getUpstreamHint() { return upstreamHint; }
    public void setUpstreamHint(String upstreamHint) { this.upstreamHint = upstreamHint; }
}
