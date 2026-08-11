package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("template_media_assets")
public class TemplateMediaAssetEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID channelAccountId;
    private String providerObjectKey;
    private String providerUrl;
    private String mediaFormat;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String assetStatus;
    private UUID createdByUserId;
    private Instant createdAt;
    private Instant attachedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public String getProviderObjectKey() { return providerObjectKey; }
    public void setProviderObjectKey(String providerObjectKey) { this.providerObjectKey = providerObjectKey; }
    public String getProviderUrl() { return providerUrl; }
    public void setProviderUrl(String providerUrl) { this.providerUrl = providerUrl; }
    public String getMediaFormat() { return mediaFormat; }
    public void setMediaFormat(String mediaFormat) { this.mediaFormat = mediaFormat; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getAssetStatus() { return assetStatus; }
    public void setAssetStatus(String assetStatus) { this.assetStatus = assetStatus; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(UUID createdByUserId) { this.createdByUserId = createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAttachedAt() { return attachedAt; }
    public void setAttachedAt(Instant attachedAt) { this.attachedAt = attachedAt; }
}
