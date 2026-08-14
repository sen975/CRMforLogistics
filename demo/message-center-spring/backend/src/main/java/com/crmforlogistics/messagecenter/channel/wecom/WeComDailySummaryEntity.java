package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@TableName("wecom_daily_summaries")
public class WeComDailySummaryEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String installationId;
    private String authCorpId;
    private LocalDate summaryDay;
    private String userId;
    private String externalUserId;
    private String summary;
    private Integer messageCount;
    private Integer completedMessageCount;
    private Integer batchCount;
    private Integer completedBatchCount;
    private String completeness;
    private Instant generatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getInstallationId() { return installationId; }
    public void setInstallationId(String installationId) { this.installationId = installationId; }

    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }

    public LocalDate getSummaryDay() { return summaryDay; }
    public void setSummaryDay(LocalDate summaryDay) { this.summaryDay = summaryDay; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public Integer getCompletedMessageCount() { return completedMessageCount; }
    public void setCompletedMessageCount(Integer completedMessageCount) { this.completedMessageCount = completedMessageCount; }

    public Integer getBatchCount() { return batchCount; }
    public void setBatchCount(Integer batchCount) { this.batchCount = batchCount; }

    public Integer getCompletedBatchCount() { return completedBatchCount; }
    public void setCompletedBatchCount(Integer completedBatchCount) { this.completedBatchCount = completedBatchCount; }

    public String getCompleteness() { return completeness; }
    public void setCompleteness(String completeness) { this.completeness = completeness; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
