package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WhatsAppTemplateMediaUploadStore {
    private final TemplateMediaAssetMapper mediaMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public WhatsAppTemplateMediaUploadStore(
            TemplateMediaAssetMapper mediaMapper,
            AuditLogMapper auditLogMapper,
            ObjectMapper objectMapper) {
        this.mediaMapper = Objects.requireNonNull(mediaMapper);
        this.auditLogMapper = Objects.requireNonNull(auditLogMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(TemplateMediaAssetEntity candidate) {
        boolean created = mediaMapper.insertProcessing(candidate) == 1;
        TemplateMediaAssetEntity stored = created ? candidate
                : mediaMapper.findByClientRequestId(candidate.getChannelAccountId(), candidate.getClientRequestId())
                    .orElseThrow(() -> new IllegalStateException("Reserved media upload is missing"));
        return new Reservation(stored, created);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markUploaded(UUID id, UploadedMedia uploaded, Instant now) {
        mediaMapper.markUploaded(id, uploaded.objectKey(), uploaded.url(), now);
        TemplateMediaAssetEntity asset = required(id);
        audit(asset, "success", now);
        return asset;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markFailed(UUID id, WhatsAppTemplateException error, Instant now) {
        mediaMapper.markFailed(id, error.code(), error.getMessage(), now);
        TemplateMediaAssetEntity asset = required(id);
        audit(asset, "failed", now);
        return asset;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markUnknown(UUID id, WhatsAppTemplateException error, Instant now) {
        mediaMapper.markUnknown(id, "TEMPLATE_MEDIA_SUBMISSION_UNKNOWN", error.getMessage(), now);
        TemplateMediaAssetEntity asset = required(id);
        audit(asset, "unknown", now);
        return asset;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity expireIfStale(UUID id, Instant cutoff, Instant now) {
        int updated = mediaMapper.expireProcessing(id, cutoff, now);
        TemplateMediaAssetEntity asset = required(id);
        if (updated == 1) {
            audit(asset, "unknown", now);
        }
        return asset;
    }

    @Transactional(readOnly = true)
    public TemplateMediaAssetEntity find(UUID accountId, String clientRequestId) {
        return mediaMapper.findByClientRequestId(accountId, clientRequestId)
                .orElseThrow(() -> new WhatsAppTemplateException("TEMPLATE_MEDIA_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Media upload was not found", Map.of(), null, false));
    }

    private TemplateMediaAssetEntity required(UUID id) {
        return Optional.ofNullable(mediaMapper.selectById(id))
                .orElseThrow(() -> new IllegalStateException("Media upload state disappeared"));
    }

    private void audit(TemplateMediaAssetEntity asset, String result, Instant now) {
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId(UUID.randomUUID());
        audit.setActorUserId(asset.getCreatedByUserId());
        audit.setAction("WHATSAPP_TEMPLATE_MEDIA_UPLOAD");
        audit.setResourceType("TEMPLATE_MEDIA_ASSET");
        audit.setResourceId(asset.getId());
        audit.setBeforeSummaryJsonb("{}");
        audit.setAfterSummaryJsonb(summary(asset));
        audit.setResult(result);
        audit.setTraceId(asset.getTraceId());
        audit.setOccurredAt(now);
        auditLogMapper.insert(audit);
    }

    private String summary(TemplateMediaAssetEntity asset) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", asset.getAssetStatus(),
                    "format", asset.getMediaFormat()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize media upload audit", error);
        }
    }

    public record Reservation(TemplateMediaAssetEntity asset, boolean created) {
    }
}
