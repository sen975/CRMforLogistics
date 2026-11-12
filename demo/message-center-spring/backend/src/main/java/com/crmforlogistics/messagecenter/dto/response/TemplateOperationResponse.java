package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.OperationHistoryView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.OperationView;

import java.time.Instant;
import java.util.UUID;

public record TemplateOperationResponse(
        UUID operationId,
        String operationType,
        String operationStatus,
        String templateCode,
        String language,
        String errorCode,
        String errorMessage,
        String traceId,
        UUID actorUserId,
        Instant startedAt,
        Instant completedAt) {

    public static TemplateOperationResponse from(OperationView view, String traceId) {
        return new TemplateOperationResponse(view.operationId(), view.operationType().name(),
                view.operationStatus().name(), view.templateCode(), null, view.errorCode(),
                null, traceId, null, null, null);
    }

    public static TemplateOperationResponse from(OperationHistoryView view) {
        return new TemplateOperationResponse(view.operationId(), view.operationType(), view.operationStatus(),
                view.templateCode(), view.language(), view.errorCode(), view.errorMessage(), view.traceId(),
                view.actorUserId(), view.startedAt(), view.completedAt());
    }

}
