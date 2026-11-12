package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_authorization_audit")
public class WeComAuthorizationAuditEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Instant occurredAt;
    private String action;
    private String result;
    private String suiteId;
    private String authCorpId;
    private String eventId;
    private Integer attempt;
    private String targetStatus;
    private Long expectedVersion;
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

    public String getSuiteId() { return suiteId; }
    public void setSuiteId(String suiteId) { this.suiteId = suiteId; }

    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }

    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

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
