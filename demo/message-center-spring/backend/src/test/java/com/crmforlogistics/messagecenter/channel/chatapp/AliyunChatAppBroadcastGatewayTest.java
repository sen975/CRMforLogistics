package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMassMessageResponseBody;
import com.aliyun.sdk.gateway.pop.exception.PopServerException;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastException;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.BroadcastQuery;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.BroadcastSubmission;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.SubmissionRecipient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.DELIVERED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunChatAppBroadcastGatewayTest {

    @Test
    void buildsTheOfficialMassMessageRequestFromFrozenSnapshots() {
        var command = new BroadcastSubmission(
                UUID.randomUUID(), "60199999999", "shipping_notice", "Shipping Notice",
                "zh_CN", "broadcast-task-1", List.of(
                new SubmissionRecipient("60111111111", Map.of("order", "SO-1")),
                new SubmissionRecipient("60122222222", Map.of("order", "SO-2"))));

        var request = AliyunChatAppBroadcastGateway.buildSubmitRequest(command, "space-1");

        assertThat(request.getChannelType()).isEqualTo("WHATSAPP");
        assertThat(request.getCustSpaceId()).isEqualTo("space-1");
        assertThat(request.getFrom()).isEqualTo("60199999999");
        assertThat(request.getTemplateCode()).isEqualTo("shipping_notice");
        assertThat(request.getTemplateName()).isEqualTo("Shipping Notice");
        assertThat(request.getLanguage()).isEqualTo("zh_CN");
        assertThat(request.getTaskId()).isEqualTo("broadcast-task-1");
        assertThat(request.getSenderList()).extracting(item -> item.getTo())
                .containsExactly("60111111111", "60122222222");
        assertThat(request.getSenderList().get(0).getTemplateParams())
                .containsExactlyEntriesOf(Map.of("order", "SO-1"));
    }

    @Test
    void rejectsSubmissionResponsesWithoutAGroupMessageId() {
        var body = SendChatappMassMessageResponseBody.builder()
                .code("OK").requestId("request-1").build();

        assertThatThrownBy(() -> AliyunChatAppBroadcastGateway.parseSubmission(body))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_PROVIDER_RESPONSE_INVALID");
    }

    @Test
    void treatsExplicitProviderResponseAndServerExceptionAsDefiniteRejection() {
        var body = SendChatappMassMessageResponseBody.builder()
                .code("InvalidParameter").requestId("request-1").build();

        assertThatThrownBy(() -> AliyunChatAppBroadcastGateway.parseSubmission(body))
                .isInstanceOfSatisfying(ChatAppBroadcastException.class, error -> {
                    assertThat(error).hasMessage("CHATAPP_BROADCAST_PROVIDER_REJECTED");
                    assertThat(error.resultUnknown()).isFalse();
                });

        ChatAppBroadcastException mapped = AliyunChatAppBroadcastGateway.submissionFailure(
                new ExecutionException(new PopServerException("provider rejected")));
        assertThat(mapped).hasMessage("CHATAPP_BROADCAST_PROVIDER_REJECTED");
        assertThat(mapped.resultUnknown()).isFalse();
    }

    @Test
    void preservesBoundedProviderFieldsFromNestedSdkException() {
        PopServerException provider = new PopServerException();
        provider.setErrCode("InvalidParameter");
        provider.setRequestId("request-sdk-1");
        provider.setErrMessage("invalid\nrecipient");

        ChatAppBroadcastException mapped = AliyunChatAppBroadcastGateway.submissionFailure(
                new ExecutionException(provider));

        assertThat(mapped.providerCode()).isEqualTo("InvalidParameter");
        assertThat(mapped.providerRequestId()).isEqualTo("request-sdk-1");
        assertThat(mapped.safeMessage()).isEqualTo("invalid recipient");
    }

    @Test
    void treatsSubmissionTimeoutAsUnknown() {
        ChatAppBroadcastException mapped =
                AliyunChatAppBroadcastGateway.submissionFailure(new TimeoutException("timeout"));

        assertThat(mapped).hasMessage("CHATAPP_BROADCAST_SUBMISSION_UNKNOWN");
        assertThat(mapped.resultUnknown()).isTrue();
    }

    @Test
    void buildsGroupScopedReconciliationRequestAndMapsRecipientStatus() {
        Instant startTime = Instant.parse("2026-08-17T07:55:00Z");
        Instant endTime = Instant.parse("2026-08-17T08:00:00Z");
        var query = new BroadcastQuery(
                UUID.randomUUID(), "60199999999", "group-1",
                startTime, endTime, 2, 100);

        var request = AliyunChatAppBroadcastGateway.buildQueryRequest(query, "space-1");
        assertThat(request.getChannelType()).isEqualTo("WHATSAPP");
        assertThat(request.getBusinessNumber()).isEqualTo("60199999999");
        assertThat(request.getCustSpaceId()).isEqualTo("space-1");
        assertThat(request.getGroupMessageId()).isEqualTo("group-1");
        assertThat(request.getStartTime()).isEqualTo(startTime.toEpochMilli());
        assertThat(request.getEndTime()).isEqualTo(endTime.toEpochMilli());
        assertThat(request.getPage().getIndex()).isEqualTo(2L);
        assertThat(request.getPage().getSize()).isEqualTo(100L);

        var row = ListChatappMessageResponseBody.Data.builder()
                .userNumber("60111111111")
                .messageId("wamid-1")
                .uniqueMessageId("unique-1")
                .messageStatusName("Delivered")
                .build();
        var page = AliyunChatAppBroadcastGateway.parseReconciliation(
                ListChatappMessageResponseBody.builder()
                        .success(true).code("OK").requestId("request-2")
                        .data(List.of(row)).build(), 2, 100);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.recipientNumber()).isEqualTo("60111111111");
            assertThat(item.status()).isEqualTo(DELIVERED);
            assertThat(item.providerMessageId()).isEqualTo("wamid-1");
        });
    }

    @Test
    void mapsProvider400AsNonRetryableReconciliationFailure() {
        PopServerException provider = new PopServerException();
        provider.setStatusCode(400);
        provider.setErrCode("QueryParam.startTime");
        provider.setRequestId("request-400");
        provider.setErrMessage("Query start time not allowed to be empty");

        ChatAppBroadcastException mapped = AliyunChatAppBroadcastGateway.reconciliationFailure(
                new ExecutionException(provider));

        assertThat(mapped.retryable()).isFalse();
        assertThat(mapped.providerCode()).isEqualTo("QueryParam.startTime");
        assertThat(mapped.providerRequestId()).isEqualTo("request-400");
    }

    @Test
    void mapsReconciliationTimeoutAsRetryable() {
        ChatAppBroadcastException mapped = AliyunChatAppBroadcastGateway.reconciliationFailure(
                new TimeoutException("timeout"));

        assertThat(mapped.retryable()).isTrue();
    }

    @Test
    void acceptsCodeOkDataWhenSuccessIsMissingAndKeepsTwoSuccessesAndOneFailure() {
        var rows = List.of(
                row("60111111111", "wamid-1", "Delivered", null),
                row("60122222222", "wamid-2", "Read", null),
                row("60199999999", "wamid-3", "Failed", "cannot send to self"));

        var page = AliyunChatAppBroadcastGateway.parseReconciliation(
                ListChatappMessageResponseBody.builder()
                        .code("OK").requestId("request-real-1").data(rows).build(), 1, 100);

        assertThat(page.items()).extracting(ReconciliationItem::status)
                .containsExactly(DELIVERED, READ, FAILED_RECIPIENT);
        assertThat(page.diagnostic().providerRequestId()).isEqualTo("request-real-1");
        assertThat(page.diagnostic().diagnosticCode())
                .isEqualTo("CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING");
    }

    @Test
    void preservesProviderFieldsForNonOkResponse() {
        var body = ListChatappMessageResponseBody.builder()
                .success(false).code("InvalidParameter").message("group message id invalid")
                .requestId("request-error-1").build();

        assertThatThrownBy(() -> AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100))
                .isInstanceOfSatisfying(ChatAppBroadcastException.class, error -> {
                    assertThat(error.providerCode()).isEqualTo("InvalidParameter");
                    assertThat(error.providerRequestId()).isEqualTo("request-error-1");
                    assertThat(error.safeMessage()).isEqualTo("group message id invalid");
                });
    }

    @Test
    void keepsMissingRecipientAsDiagnosticRow() {
        var body = ListChatappMessageResponseBody.builder().success(true).code("OK")
                .requestId("request-row-1")
                .data(List.of(row(null, "wamid-missing", "Failed", "invalid recipient"))).build();

        var page = AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.rowNumber()).isEqualTo(1);
            assertThat(item.recipientNumber()).isBlank();
            assertThat(item.diagnosticCode())
                    .isEqualTo("CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING");
        });
    }

    @Test
    void parsesRowsWithTheDocumentedProviderStatusPrecedence() {
        var row = ListChatappMessageResponseBody.Data.builder()
                .userNumber("60111111111")
                .messageId("wamid-priority")
                .clientReadStatusName("Read")
                .messageStatusName("Failed")
                .clientAcceptStatusName("Delivered")
                .build();

        ReconciliationItem item = AliyunChatAppBroadcastGateway.parseRow(row, 7);

        assertThat(item.rowNumber()).isEqualTo(7);
        assertThat(item.rawProviderStatus()).isEqualTo("Read");
        assertThat(item.status()).isEqualTo(READ);
    }

    @Test
    void skipsUnrecognizedHigherPriorityStatusAndKeepsDeliveredFact() {
        var row = ListChatappMessageResponseBody.Data.builder()
                .userNumber("60111111111")
                .messageId("wamid-delivered")
                .clientReadStatusName("Unread")
                .messageStatusName("Delivered")
                .build();

        ReconciliationItem item = AliyunChatAppBroadcastGateway.parseRow(row, 8);

        assertThat(item.rawProviderStatus()).isEqualTo("Delivered");
        assertThat(item.status()).isEqualTo(DELIVERED);
        assertThat(item.diagnosticCode()).isBlank();
    }

    @Test
    void readsStatusFromEventActionWhenDedicatedStatusFieldsAreEmpty() {
        var row = ListChatappMessageResponseBody.Data.builder()
                .userNumber("60111111111")
                .messageId("group-1")
                .uniqueMessageId("unique-1")
                .eventActionName("Read")
                .build();

        ReconciliationItem item = AliyunChatAppBroadcastGateway.parseRow(row, 9);

        assertThat(item.rawProviderStatus()).isEqualTo("Read");
        assertThat(item.status()).isEqualTo(READ);
        assertThat(item.diagnosticCode()).isBlank();
    }

    @Test
    void keepsEmptyDataAsRetryableEnvelopeDiagnostic() {
        var body = ListChatappMessageResponseBody.builder().success(true).code("OK")
                .requestId("request-empty-1").data(List.of()).build();

        var page = AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.diagnostic().diagnosticCode())
                .isEqualTo("CHATAPP_BROADCAST_RECONCILIATION_DATA_EMPTY");
    }

    private static ListChatappMessageResponseBody.Data row(
            String number, String messageId, String status, String failureReason) {
        return ListChatappMessageResponseBody.Data.builder()
                .userNumber(number).messageId(messageId).uniqueMessageId("unique-" + messageId)
                .messageStatusName(status).failReason(failureReason)
                .sendTime("1786932000000").build();
    }
}
