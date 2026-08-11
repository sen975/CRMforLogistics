package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplatePageView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplateView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TemplateAdminResponse(
        UUID id,
        UUID accountId,
        String templateCode,
        String name,
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

    public static TemplateAdminResponse from(TemplateView view) {
        return new TemplateAdminResponse(view.id(), view.accountId(), view.templateCode(), view.name(),
                view.language(), view.category(), view.reviewStatus(), view.providerAuditStatus(),
                view.rejectionReason(), view.allowSend(), view.components(), view.examples(),
                view.messageSendTtlSeconds(), view.qualityScore(), view.providerUpdatedAt(),
                view.lastSyncedAt(), view.deletedAt());
    }

    public record Page(List<TemplateAdminResponse> items, long total, int page, int size) {
        public static Page from(TemplatePageView view) {
            return new Page(view.items().stream().map(TemplateAdminResponse::from).toList(),
                    view.total(), view.page(), view.size());
        }
    }
}
