package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatAppBroadcastModels {
    private ChatAppBroadcastModels() {
    }

    public enum BroadcastStatus {
        DRAFT,
        QUEUED,
        SUBMITTING,
        SUBMITTED,
        RECONCILING,
        SUCCEEDED,
        PARTIALLY_FAILED,
        FAILED,
        SUBMISSION_UNKNOWN,
        STATUS_UNKNOWN,
        CANCELLED
    }

    public enum RecipientStatus {
        QUEUED,
        PROCESSING,
        SENT,
        DELIVERED,
        READ,
        FAILED_RECIPIENT
    }

    public enum JobType {
        SUBMIT,
        RECONCILE
    }

    public enum JobStatus {
        PENDING,
        PROCESSING,
        SUCCEEDED,
        FAILED,
        DEAD
    }

    public record AggregateResult(
            BroadcastStatus status,
            int successCount,
            int failedCount,
            int processingCount) {
    }

    public record RecipientInput(UUID contactIdentityId, Map<String, String> templateParams) {
        public RecipientInput {
            templateParams = templateParams == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(templateParams));
        }
    }

    public record RecipientCandidate(
            UUID contactIdentityId,
            UUID contactId,
            String recipientNumber,
            String normalizedNumber,
            String recipientName) {
    }

    public record CreateBroadcastCommand(
            UUID channelAccountId,
            String name,
            String templateCode,
            String languageCode,
            String clientRequestId,
            List<RecipientInput> recipients,
            Map<String, String> sharedTemplateParams) {
        public CreateBroadcastCommand {
            recipients = recipients == null
                    ? List.of() : Collections.unmodifiableList(new ArrayList<>(recipients));
            sharedTemplateParams = sharedTemplateParams == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sharedTemplateParams));
        }
    }

    public record BroadcastView(
            UUID id,
            UUID channelAccountId,
            String name,
            String templateCode,
            String templateName,
            String languageCode,
            int recipientCount,
            int successCount,
            int failedCount,
            int processingCount,
            BroadcastStatus status,
            String providerGroupMessageId,
            String providerRequestId,
            String providerCode,
            String lastReconciliationRequestId,
            String lastReconciliationProviderCode,
            String errorCode,
            String errorMessage,
            UUID retriesBroadcastId,
            UUID createdByUserId,
            Instant submittedAt,
            Instant reconciledAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record BroadcastPage(List<BroadcastView> records, long total, int page, int size) {
        public BroadcastPage {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public record RecipientView(
            UUID id,
            UUID contactId,
            UUID contactIdentityId,
            String recipientName,
            String maskedNumber,
            Map<String, String> templateParams,
            UUID messageId,
            String providerMessageId,
            String providerUniqueMessageId,
            RecipientStatus status,
            String failureReason,
            Instant providerSentAt,
            Instant lastReconciledAt) {
        public RecipientView {
            templateParams = templateParams == null ? Map.of() : Map.copyOf(templateParams);
        }
    }

    public record ReconciliationSummary(
            long evidenceRows,
            long matchedRows,
            long unmatchedRows,
            int processingRecipients,
            String latestDiagnosticCode) {
    }

    public record BroadcastDetail(
            BroadcastView broadcast,
            List<RecipientView> recipients,
            ReconciliationSummary reconciliation) {
        public BroadcastDetail {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    public record RecipientPage(List<RecipientView> records, long total, int page, int size) {
        public RecipientPage {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}
