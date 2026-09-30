package com.crmforlogistics.messagecenter.service.whatsapp.template;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WhatsAppTemplateModels {

    private WhatsAppTemplateModels() {
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED, SUSPENDED, UNKNOWN;

        /**
         * CAMS 的 {@code AuditStatus} 是一个自由字符串，而且<b>实测取值比官方文档还多</b>：
         * 文档只列了 {@code pass / fail / auditing / unaudit / disabled / paused}，
         * 线上却真实出现过 {@code sendFail}（2026-09-29 实测；同一条记录还带着
         * {@code Reason}，例如「变量不允许出现在模板正文的开头或结尾」）。
         *
         * <p>所以这里必须把<b>已知取值全部覆盖</b>。任何落空的取值都会静默变成
         * {@link #UNKNOWN}，而 {@link #UNKNOWN} 会把一个「审核被拒」的模板在界面上
         * 显示成灰色的「未知」—— 用户于是以为它还能被「恢复发送」，点下去只会撞上
         * 409 {@code TEMPLATE_NOT_APPROVED}，而真正的原因（模板内容违规、审核未通过）
         * 一个字都不会露出来。这正是 2026-09-29 那次报障的完整成因。
         *
         * <p>{@code null} 与仍然认不出的取值继续回落到 {@link #UNKNOWN}：这里不能猜。
         * 但调用方要清楚它是一个<b>显式的「没认出来」</b>信号，不是「没问题」，
         * 更不该拿它当「可以发送」来用。
         */
        public static ReviewStatus fromProviderAuditStatus(String raw) {
            if (raw == null) return UNKNOWN;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "pass" -> APPROVED;
                // sendFail 是文档外的实测取值：它带 Reason、且模板不可发送，按拒审处理。
                case "fail", "sendfail" -> REJECTED;
                case "auditing" -> PENDING;
                case "unaudit", "paused", "disabled" -> SUSPENDED;
                default -> UNKNOWN;
            };
        }
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
