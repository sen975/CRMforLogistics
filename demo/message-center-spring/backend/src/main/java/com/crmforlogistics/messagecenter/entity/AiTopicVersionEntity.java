package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_topic_versions")
public class AiTopicVersionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID topicId;
    private Long version;
    private String changeType;
    private String title;
    private String summary;
    private String sourceTopicIds;
    private UUID actorUserId;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTopicId() { return topicId; }
    public void setTopicId(UUID topicId) { this.topicId = topicId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSourceTopicIds() { return sourceTopicIds; }
    public void setSourceTopicIds(String sourceTopicIds) { this.sourceTopicIds = sourceTopicIds; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
