package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SharedTemplateResponse(
        UUID id,
        long version,
        String templateCode,
        String name,
        String remark,
        String displayName,
        String language,
        String category,
        String reviewStatus,
        String providerAuditStatus,
        String rejectionReason,
        boolean allowSend,
        List<TemplateComponent> components,
        Map<String, List<String>> examples,
        Integer messageSendTtlSeconds,
        String qualityScore,
        Instant providerUpdatedAt,
        Instant lastSyncedAt,
        Instant deletedAt) {

    public SharedTemplateResponse {
        components = components == null ? List.of() : List.copyOf(components);
        examples = examples == null ? Map.of() : Map.copyOf(examples);
    }

    public record Page(List<SharedTemplateResponse> items, long total, int page, int size) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
