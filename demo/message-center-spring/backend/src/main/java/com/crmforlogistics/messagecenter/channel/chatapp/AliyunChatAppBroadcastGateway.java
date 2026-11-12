package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMassMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMassMessageResponseBody;
import com.aliyun.sdk.gateway.pop.exception.PopClientException;
import com.aliyun.sdk.gateway.pop.exception.PopServerException;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageStatusNormalizer;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastException;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;

@Component
public class AliyunChatAppBroadcastGateway implements ChatAppBroadcastGateway {
    private static final int PROVIDER_TIMEOUT_SECONDS = 30;
    private static final Logger LOG = LoggerFactory.getLogger(AliyunChatAppBroadcastGateway.class);

    private final ChannelAccountMapper accountMapper;
    private final ChatAppAccountCredentialsResolver credentialsResolver;

    @Autowired
    public AliyunChatAppBroadcastGateway(ChannelAccountMapper accountMapper,
                                         ChatAppAccountCredentialsResolver credentialsResolver) {
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.credentialsResolver = Objects.requireNonNull(credentialsResolver);
    }

    @Override
    public SubmissionResult submit(BroadcastSubmission command) {
        try {
            ChatAppSubmissionContext snapshot = command.submissionContext();
            ChatAppAccountCredentials credentials = snapshot == null
                    ? credentials(command.channelAccountId()) : snapshot.credentials();
            if (credentials == null) {
                throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
            }
            SendChatappMassMessageRequest request = buildSubmitRequest(command, credentials.custSpaceId());
            try (AsyncClient client = createClient(credentials)) {
            var response = client.sendChatappMassMessage(request)
                    .get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return parseSubmission(response == null ? null : response.getBody());
            }
        } catch (ChatAppBroadcastException e) {
            throw e;
        } catch (ChatAppAccountCredentialsException e) {
            throw accountCredentialFailure(e);
        } catch (Exception e) {
            ChatAppBroadcastException mapped = submissionFailure(e);
            LOG.warn("ChatApp broadcast submission failed: providerCode={}, requestId={}",
                    mapped.providerCode(), mapped.providerRequestId());
            throw mapped;
        }
    }

    @Override
    public ReconciliationPage reconcile(BroadcastQuery query) {
        try {
            ChatAppAccountCredentials credentials = credentials(query.channelAccountId());
            ListChatappMessageRequest request = buildQueryRequest(query, credentials.custSpaceId());
            try (AsyncClient client = createClient(credentials)) {
            var response = client.listChatappMessage(request)
                    .get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return parseReconciliation(response == null ? null : response.getBody(), query.page(), query.size());
            }
        } catch (ChatAppBroadcastException e) {
            throw e;
        } catch (ChatAppAccountCredentialsException e) {
            throw accountCredentialFailure(e);
        } catch (Exception e) {
            ChatAppBroadcastException mapped = reconciliationFailure(e);
            LOG.warn("ChatApp broadcast reconciliation failed: providerCode={}, requestId={}",
                    mapped.providerCode(), mapped.providerRequestId());
            throw mapped;
        }
    }

    static SendChatappMassMessageRequest buildSubmitRequest(
            BroadcastSubmission command, String custSpaceId) {
        Objects.requireNonNull(command, "command is required");
        if (command.recipients().isEmpty() || command.recipients().size() > 1000) {
            throw new IllegalArgumentException("CHATAPP_BROADCAST_RECIPIENT_COUNT_INVALID");
        }
        List<SendChatappMassMessageRequest.SenderList> recipients = command.recipients().stream()
                .map(item -> SendChatappMassMessageRequest.SenderList.builder()
                        .to(required(ContactPointUtil.normalizePhone(item.recipientNumber())))
                        .templateParams(item.templateParams())
                        .build())
                .toList();
        return SendChatappMassMessageRequest.builder()
                .channelType("WHATSAPP")
                .custSpaceId(required(custSpaceId))
                .from(required(ContactPointUtil.normalizePhone(command.from())))
                .templateCode(required(command.templateCode()))
                .templateName(required(command.templateName()))
                .language(required(command.languageCode()))
                .taskId(required(command.taskId()))
                .senderList(recipients)
                .build();
    }

    static SubmissionResult parseSubmission(SendChatappMassMessageResponseBody body) {
        if (body == null || (value(body.getGroupMessageId()).isBlank()
                && "OK".equalsIgnoreCase(value(body.getCode())))) {
            throw new ChatAppBroadcastException(
                    "CHATAPP_BROADCAST_PROVIDER_RESPONSE_INVALID", HttpStatus.BAD_GATEWAY,
                    true, false, body == null ? "" : value(body.getCode()),
                    body == null ? "" : value(body.getRequestId()),
                    body == null ? "" : value(body.getMessage()), null);
        }
        if (!"OK".equalsIgnoreCase(value(body.getCode()))) {
            throw new ChatAppBroadcastException(
                    "CHATAPP_BROADCAST_PROVIDER_REJECTED", HttpStatus.BAD_GATEWAY,
                    false, false, value(body.getCode()), value(body.getRequestId()),
                    value(body.getMessage()), null);
        }
        return new SubmissionResult(
                body.getGroupMessageId().trim(), value(body.getRequestId()), value(body.getCode()));
    }

    static ChatAppBroadcastException submissionFailure(Exception error) {
        ProviderFailure failure = providerFailure(error);
        boolean explicitProviderResponse = !failure.code().isBlank()
                || hasCause(error, PopClientException.class)
                || hasCause(error, PopServerException.class);
        boolean unknown = !explicitProviderResponse && (
                hasCause(error, TimeoutException.class)
                        || hasCause(error, SocketTimeoutException.class)
                        || hasCause(error, SocketException.class)
                        || hasCause(error, EOFException.class));
        return new ChatAppBroadcastException(
                unknown ? "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN" : "CHATAPP_BROADCAST_PROVIDER_REJECTED",
                unknown ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                unknown, false, failure.code(), failure.requestId(), failure.message(), error);
    }

    static ListChatappMessageRequest buildQueryRequest(BroadcastQuery query, String custSpaceId) {
        Objects.requireNonNull(query, "query is required");
        if (query.page() < 1 || query.size() < 1 || query.size() > 100) {
            throw new IllegalArgumentException("CHATAPP_BROADCAST_RECONCILIATION_PAGE_INVALID");
        }
        if (query.startTime() == null || query.endTime() == null
                || !query.endTime().isAfter(query.startTime())
                || Duration.between(query.startTime(), query.endTime()).compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("CHATAPP_BROADCAST_RECONCILIATION_TIME_RANGE_INVALID");
        }
        return ListChatappMessageRequest.builder()
                .channelType("WHATSAPP")
                .custSpaceId(required(custSpaceId))
                .businessNumber(required(ContactPointUtil.normalizePhone(query.businessNumber())))
                .groupMessageId(required(query.groupMessageId()))
                .startTime(query.startTime().toEpochMilli())
                .endTime(query.endTime().toEpochMilli())
                .page(ListChatappMessageRequest.Page.builder()
                        .index((long) query.page()).size((long) query.size()).build())
                .build();
    }

    static ChatAppBroadcastException reconciliationFailure(Exception error) {
        ProviderFailure failure = providerFailure(error);
        return new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                false, reconciliationRetryable(error, failure), failure.code(),
                failure.requestId(), failure.message(), error);
    }

    private static ChatAppBroadcastException accountCredentialFailure(
            ChatAppAccountCredentialsException error) {
        return new ChatAppBroadcastException(error.code(), HttpStatus.CONFLICT,
                false, false, error.code(), "", error.code(), error);
    }

    static ReconciliationPage parseReconciliation(
            ListChatappMessageResponseBody body, int page, int size) {
        if (body == null) {
            throw invalid("CHATAPP_BROADCAST_RECONCILIATION_BODY_MISSING", "", "", "");
        }
        String code = value(body.getCode());
        if (!code.isBlank() && !"OK".equalsIgnoreCase(code)) {
            throw rejected(code, value(body.getRequestId()), value(body.getMessage()));
        }
        if (body.getData() == null) {
            return new ReconciliationPage(List.of(), page, false,
                    new ProviderDiagnostic(code, value(body.getMessage()), value(body.getRequestId()),
                            "CHATAPP_BROADCAST_RECONCILIATION_DATA_MISSING"));
        }
        if (body.getData().isEmpty()) {
            return new ReconciliationPage(List.of(), page, false,
                    new ProviderDiagnostic(code, value(body.getMessage()), value(body.getRequestId()),
                            "CHATAPP_BROADCAST_RECONCILIATION_DATA_EMPTY"));
        }
        String diagnosticCode = body.getSuccess() == null
                ? "CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING"
                : Boolean.FALSE.equals(body.getSuccess())
                ? "CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT" : "";
        List<ReconciliationItem> items = new ArrayList<>();
        for (int index = 0; index < body.getData().size(); index++) {
            items.add(parseRow(body.getData().get(index), index + 1));
        }
        return new ReconciliationPage(
                items, page, body.getData().size() == size,
                new ProviderDiagnostic(code, value(body.getMessage()), value(body.getRequestId()),
                        diagnosticCode));
    }

    public static ReconciliationItem parseRow(
            ListChatappMessageResponseBody.Data row, int rowNumber) {
        if (row == null) {
            return new ReconciliationItem(rowNumber, "", "", "", RecipientStatus.PROCESSING,
                    "", "", null, "CHATAPP_BROADCAST_RECONCILIATION_ROW_MISSING");
        }
        String recognizedStatus = firstRecognizedStatus(
                value(row.getClientReadStatusName()), value(row.getMessageStatusName()),
                value(row.getClientAcceptStatusName()), value(row.getMessageStatus()),
                value(row.getClientReadStatus()), value(row.getEventActionName()),
                value(row.getEventAction()));
        String firstRawStatus = firstNonBlank(
                value(row.getClientReadStatusName()), value(row.getMessageStatusName()),
                value(row.getClientAcceptStatusName()), value(row.getMessageStatus()),
                value(row.getClientReadStatus()), value(row.getEventActionName()),
                value(row.getEventAction()));
        String rawStatus = recognizedStatus.isBlank() ? firstRawStatus : recognizedStatus;
        String number = ContactPointUtil.normalizePhone(row.getUserNumber());
        String providerMessageId = value(row.getMessageId());
        String providerUniqueMessageId = value(row.getUniqueMessageId());
        String failureReason = value(row.getFailReason());
        RecipientStatus parsedStatus = recipientStatus(recognizedStatus);
        RecipientStatus effectiveStatus = parsedStatus == RecipientStatus.PROCESSING
                && !providerUniqueMessageId.isBlank()
                && failureReason.isBlank()
                ? RecipientStatus.SENT : parsedStatus;
        String diagnosticCode = number.isBlank()
                ? "CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING"
                : providerMessageId.isBlank() && providerUniqueMessageId.isBlank()
                ? "CHATAPP_BROADCAST_PROVIDER_MESSAGE_ID_MISSING"
                : rawStatus.isBlank() ? "CHATAPP_BROADCAST_PROVIDER_STATUS_MISSING" : "";
        return new ReconciliationItem(
                rowNumber, number, providerMessageId, providerUniqueMessageId,
                effectiveStatus, rawStatus, failureReason,
                parseInstant(row.getSendTime()), diagnosticCode);
    }

    private static RecipientStatus recipientStatus(String rawStatus) {
        String normalized = ChatAppMessageStatusNormalizer.normalize(rawStatus);
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "sent", "submitted" -> RecipientStatus.SENT;
            case "delivered" -> RecipientStatus.DELIVERED;
            case "read" -> RecipientStatus.READ;
            case "failed" -> RecipientStatus.FAILED_RECIPIENT;
            default -> RecipientStatus.PROCESSING;
        };
    }

    private static String firstRecognizedStatus(String... values) {
        for (String candidate : values) {
            String value = candidate == null ? "" : candidate.trim();
            if (!ChatAppMessageStatusNormalizer.normalize(value).isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static ChatAppBroadcastException invalid(
            String code, String providerCode, String requestId, String message) {
        return new ChatAppBroadcastException(code, HttpStatus.BAD_GATEWAY,
                false, true, providerCode, requestId, message, null);
    }

    private static ChatAppBroadcastException rejected(
            String providerCode, String requestId, String message) {
        return new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_RECONCILIATION_RESPONSE_INVALID", HttpStatus.BAD_GATEWAY,
                false, false, providerCode, requestId, message, null);
    }

    private static Instant parseInstant(String value) {
        String raw = value(value);
        if (raw.isBlank()) return null;
        try {
            long numeric = Long.parseLong(raw);
            return numeric > 100_000_000_000L
                    ? Instant.ofEpochMilli(numeric) : Instant.ofEpochSecond(numeric);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private ChatAppAccountCredentials credentials(java.util.UUID accountId) {
        ChannelAccountEntity account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        return credentialsResolver.resolve(account);
    }

    private static AsyncClient createClient(ChatAppAccountCredentials credentials) {
        return AsyncClient.builder()
                .region(credentials.region())
                .credentialsProvider(StaticCredentialProvider.create(Credential.builder()
                        .accessKeyId(credentials.accessKeyId())
                        .accessKeySecret(credentials.accessKeySecret()).build()))
                .overrideConfiguration(ClientOverrideConfiguration.create().setEndpointOverride(credentials.endpoint()))
                .build();
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_BROADCAST_PROVIDER_REQUEST_INVALID");
        }
        return value.trim();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String candidate : values) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return "";
    }

    private static ProviderFailure providerFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof PopClientException client) {
                return new ProviderFailure(
                        value(client.getErrCode()), value(client.getRequestId()),
                        value(client.getErrMessage()), client.getStatusCode());
            }
            if (current instanceof PopServerException server) {
                return new ProviderFailure(
                        value(server.getErrCode()), value(server.getRequestId()),
                        value(server.getErrMessage()), server.getStatusCode());
            }
            current = current.getCause();
        }
        return new ProviderFailure("", "", "", null);
    }

    private static boolean reconciliationRetryable(Throwable error, ProviderFailure failure) {
        if (hasCause(error, TimeoutException.class)
                || hasCause(error, SocketTimeoutException.class)
                || hasCause(error, SocketException.class)
                || hasCause(error, EOFException.class)) {
            return true;
        }
        Integer statusCode = failure.statusCode();
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }
        String code = failure.code().toLowerCase(Locale.ROOT);
        if (code.matches("4\\d\\d")) {
            return "408".equals(code) || "429".equals(code);
        }
        if (!code.isBlank()) {
            return code.contains("throttl") || code.contains("timeout")
                    || code.contains("serviceunavailable") || code.contains("internalerror");
        }
        return true;
    }

    private record ProviderFailure(String code, String requestId, String message, Integer statusCode) {
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
