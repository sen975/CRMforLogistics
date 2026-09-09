package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("chatapp_capability_results")
public class ChatAppCapabilityResultEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID reportId;
    private UUID channelAccountId;
    private UUID providerScopeId;
    private String phase;
    private String action;
    private String status;
    private String providerRequestId;
    private String diagnosticCode;
    private String diagnosticMessage;
    private String testedPhoneLast4;
    private Instant testedAt;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getReportId() { return reportId; }
    public void setReportId(UUID reportId) { this.reportId = reportId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getProviderScopeId() { return providerScopeId; }
    public void setProviderScopeId(UUID providerScopeId) { this.providerScopeId = providerScopeId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getDiagnosticCode() { return diagnosticCode; }
    public void setDiagnosticCode(String diagnosticCode) { this.diagnosticCode = diagnosticCode; }
    public String getDiagnosticMessage() { return diagnosticMessage; }
    public void setDiagnosticMessage(String diagnosticMessage) { this.diagnosticMessage = diagnosticMessage; }
    public String getTestedPhoneLast4() { return testedPhoneLast4; }
    public void setTestedPhoneLast4(String testedPhoneLast4) { this.testedPhoneLast4 = testedPhoneLast4; }
    public Instant getTestedAt() { return testedAt; }
    public void setTestedAt(Instant testedAt) { this.testedAt = testedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
