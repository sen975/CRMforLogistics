package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ChatAppBroadcastGateway {
    SubmissionResult submit(BroadcastSubmission command);

    ReconciliationPage reconcile(BroadcastQuery query);

    record SubmissionRecipient(String recipientNumber, Map<String, String> templateParams) {
        public SubmissionRecipient {
            templateParams = templateParams == null ? Map.of() : Map.copyOf(templateParams);
        }
    }

    record BroadcastSubmission(
            UUID channelAccountId,
            String from,
            String templateCode,
            String templateName,
            String languageCode,
            String taskId,
            List<SubmissionRecipient> recipients) {
        public BroadcastSubmission {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    record SubmissionResult(String groupMessageId, String providerRequestId, String providerCode) {
    }

    record BroadcastQuery(
            UUID channelAccountId,
            String businessNumber,
            String groupMessageId,
            int page,
            int size) {
    }

    record ProviderDiagnostic(
            String providerCode,
            String providerMessage,
            String providerRequestId,
            String diagnosticCode) {
    }

    record ReconciliationItem(
            int rowNumber,
            String recipientNumber,
            String providerMessageId,
            String providerUniqueMessageId,
            RecipientStatus status,
            String rawProviderStatus,
            String failureReason,
            Instant providerSentAt,
            String diagnosticCode) {
        public ReconciliationItem(
                String recipientNumber, String providerMessageId,
                String providerUniqueMessageId, RecipientStatus status,
                String failureReason, Instant providerSentAt) {
            this(0, recipientNumber, providerMessageId, providerUniqueMessageId,
                    status, "", failureReason, providerSentAt, "");
        }
    }

    record ReconciliationPage(
            List<ReconciliationItem> items,
            int page,
            boolean hasNext,
            ProviderDiagnostic diagnostic) {
        public ReconciliationPage {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public ReconciliationPage(
                List<ReconciliationItem> items, int page,
                boolean hasNext, String providerRequestId) {
            this(items, page, hasNext,
                    new ProviderDiagnostic("", "", providerRequestId, ""));
        }

        public String providerRequestId() {
            return diagnostic == null ? "" : diagnostic.providerRequestId();
        }
    }
}
