package com.crmforlogistics.messagecenter.service.whatsapp.template;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WhatsAppTemplateModels {

    private WhatsAppTemplateModels() {
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED, SUSPENDED, UNKNOWN
    }

    public enum OperationStatus {
        PROCESSING, SUCCEEDED, SUBMISSION_UNKNOWN, FAILED
    }

    public enum OperationType {
        CREATE, MODIFY, SET_SEND_PERMISSION, DELETE, RECONCILE, RETIRED
    }

    public enum ChangeType {
        MODIFY, SET_SEND_PERMISSION, DELETE, BIND_MEDIA
    }

    public enum ChangeRequestStatus {
        PENDING_APPROVAL, REJECTED, STALE, EXECUTING, SUCCEEDED, EXECUTION_FAILED
    }

    public enum ChangeMode {
        APPROVAL_REQUIRED, DIRECT
    }

    public enum MediaAssetStatus {
        PROCESSING, UPLOADED, FAILED, SUBMISSION_UNKNOWN,
        ATTACHED, ATTACHMENT_UNKNOWN, ORPHANED
    }

    public enum HeaderFormat {
        TEXT, IMAGE, VIDEO, DOCUMENT
    }

    public enum ButtonType {
        QUICK_REPLY, URL, PHONE_NUMBER
    }

    public enum ComponentType {
        HEADER, BODY, FOOTER, BUTTONS
    }

    public record TemplateCommand(
            String name,
            String language,
            String category,
            List<TemplateComponent> components,
            Map<String, List<String>> examples,
            Integer messageSendTtlSeconds,
            String clientRequestId) {

        public TemplateCommand {
            components = components == null ? List.of() : List.copyOf(components);
            examples = immutableExamples(examples);
        }
    }

    public record TemplateDraft(
            String name,
            String category,
            List<TemplateComponent> components,
            Map<String, List<String>> examples,
            Integer messageSendTtlSeconds) {
        public TemplateDraft {
            components = components == null ? List.of() : List.copyOf(components);
            examples = immutableExamples(examples);
        }
    }

    public record ChangeCommand(
            ChangeType changeType,
            long expectedVersion,
            String clientRequestId,
            TemplateDraft template,
            Boolean allowSend,
            String remark) { }

    public record TemplateComponent(
            ComponentType type,
            HeaderFormat headerFormat,
            String text,
            String mediaAssetId,
            List<TemplateButton> buttons) {

        public TemplateComponent {
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
        }
    }

    public record TemplateButton(
            ButtonType type,
            String text,
            String url,
            String phoneNumber) {
    }

    public record TemplateSnapshot(
            UUID accountId,
            String templateCode,
            String templateName,
            String language,
            String category,
            ReviewStatus reviewStatus,
            String rawAuditStatus,
            String rejectionReason,
            boolean allowSend,
            List<TemplateComponent> components,
            Map<String, List<String>> examples,
            Integer messageSendTtlSeconds,
            Instant providerUpdatedAt,
            Instant deletedAt) {

        public TemplateSnapshot {
            components = components == null ? List.of() : List.copyOf(components);
            examples = immutableExamples(examples);
        }
    }

    public record ProviderOperationResult(
            OperationType operationType,
            OperationStatus status,
            String providerRequestId,
            String providerCode,
            String reason) {
    }

    public record CreateResult(String templateCode, String templateName, String providerRequestId) {
    }

    public record ModifyResult(String templateCode, String templateName, String providerRequestId) {
    }

    public record PropertyResult(boolean allowSend, String providerRequestId) {
    }

    public record DeleteResult(boolean success, String providerRequestId) {
    }

    public record UploadedMedia(
            String objectKey,
            String url,
            HeaderFormat format,
            String contentType,
            long sizeBytes,
            String sha256) {
    }

    public record ProviderTemplateSummary(
            String templateCode,
            String templateName,
            String language,
            String category,
            String rawAuditStatus,
            String reason,
            Instant providerUpdatedAt) {
    }

    public record ProviderTemplatePage(List<ProviderTemplateSummary> items, int page, boolean hasNext) {
        public ProviderTemplatePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    private static Map<String, List<String>> immutableExamples(Map<String, List<String>> examples) {
        if (examples == null || examples.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        examples.forEach((variable, values) -> copied.put(variable, values == null ? List.of() : List.copyOf(values)));
        return Collections.unmodifiableMap(copied);
    }
}
