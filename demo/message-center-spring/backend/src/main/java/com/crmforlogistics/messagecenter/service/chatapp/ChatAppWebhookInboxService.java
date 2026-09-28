package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppWebhookInboxService {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final ChatAppWebhookVerifier verifier;
    private final ChannelAccountMapper channelAccountMapper;
    private final ChannelEventMapper channelEventMapper;
    private final ChatAppWebhookProjector projector;
    private final ObjectMapper objectMapper;

    public ChatAppWebhookInboxService(ChatAppWebhookVerifier verifier,
                                      ChannelAccountMapper channelAccountMapper,
                                      ChannelEventMapper channelEventMapper,
                                      ChatAppWebhookProjector projector,
                                      ObjectMapper objectMapper) {
        this.verifier = Objects.requireNonNull(verifier);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.channelEventMapper = Objects.requireNonNull(channelEventMapper);
        this.projector = Objects.requireNonNull(projector);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public WebhookReceipt accept(String signature, String timestamp, String rawBody) {
        byte[] bodyBytes = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (bodyBytes.length == 0 || bodyBytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_BODY_SIZE_INVALID");
        }
        verifier.verify(signature, timestamp, rawBody);
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            boolean unsignedProviderRequest = isBlank(signature) && isBlank(timestamp);
            ChannelAccountEntity account = resolveAccount(root, unsignedProviderRequest);
            Instant now = Instant.now();
            ChannelEventEntity event = new ChannelEventEntity();
            event.setId(UUID.randomUUID());
            event.setChannelAccountId(account.getId());
            String providerEventId = ChatAppWebhookProjector.field(
                    root, "EventId", "eventId", "event_id", "NoticeId", "noticeId",
                    "MessageId", "messageId", "message_id");
            event.setProviderEventId(providerEventId.isBlank() ? null : providerEventId);
            String status = ChatAppWebhookProjector.field(root, "Status", "status");
            event.setEventType(status.isBlank() ? "chatapp_message" : "chatapp_status");
            event.setOccurredAt(now);
            event.setReceivedAt(now);
            event.setPayloadJsonb(projectionPayload(root));
            event.setPayloadHash(sha256(bodyBytes));
            event.setProcessingStatus("received");
            event.setAttemptCount(0);
            event.setNextAttemptAt(now);
            event.setTraceId(UUID.randomUUID().toString());
            int inserted = channelEventMapper.insertIgnore(event);
            if (inserted == 0) {
                return new WebhookReceipt(event.getId(), true, "received");
            }
            try {
                projector.project(event);
                return new WebhookReceipt(event.getId(), false, "processed");
            } catch (RuntimeException projectionError) {
                channelEventMapper.markRetry(event.getId(), now.plusSeconds(10),
                        "PROJECTION_FAILED", safeMessage(projectionError));
                return new WebhookReceipt(event.getId(), false, "retry_wait");
            }
        } catch (ChatAppWebhookAuthenticationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_PAYLOAD_INVALID", e);
        }
    }

    private ChannelAccountEntity resolveAccount(JsonNode root, boolean unsignedProviderRequest) {
        boolean statusCallback = !ChatAppWebhookProjector.field(root, "Status", "status").isBlank();
        String businessNumber = ContactPointUtil.normalizePhone(ChatAppWebhookProjector.field(
                root, statusCallback
                        ? new String[] {"From", "from", "businessNumber", "businessPhoneNumber"}
                        : new String[] {"To", "to", "businessNumber", "businessPhoneNumber"}));
        if (businessNumber.isBlank()) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_ACCOUNT_UNRESOLVED");
        }
        String custSpaceId = ChatAppWebhookProjector.field(root,
                "CustSpaceId", "custSpaceId", "cust_space_id");
        if (unsignedProviderRequest && custSpaceId.isBlank()) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_ACCOUNT_UNRESOLVED");
        }
        ChannelAccountEntity account = custSpaceId.isBlank()
                ? channelAccountMapper.findActiveChatAppByNormalizedIdentifier(businessNumber)
                : channelAccountMapper.findActiveChatAppByScopeAndNormalizedIdentifier(custSpaceId, businessNumber);
        if (account == null) {
            throw new ChatAppWebhookAuthenticationException("CHATAPP_WEBHOOK_ACCOUNT_UNRESOLVED");
        }
        return account;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String projectionPayload(JsonNode root) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        putIfPresent(payload, "EventId", ChatAppWebhookProjector.field(
                root, "EventId", "eventId", "event_id", "NoticeId", "noticeId",
                "MessageId", "messageId", "message_id"));
        putIfPresent(payload, "MessageId", ChatAppWebhookProjector.field(
                root, "MessageId", "messageId", "message_id", "wamid", "TaskId"));
        putIfPresent(payload, "Status", ChatAppWebhookProjector.field(
                root, "Status", "status", "messageStatus"));
        putIfPresent(payload, "From", ChatAppWebhookProjector.field(
                root, "From", "from", "sender", "wa_id", "userNumber"));
        putIfPresent(payload, "To", ChatAppWebhookProjector.field(
                root, "To", "to", "businessNumber", "businessPhoneNumber"));
        putIfPresent(payload, "ContactName", ChatAppWebhookProjector.field(
                root, "Name", "FromUserName", "fromUserName", "from_user_name", "profileName", "pushName"));
        putIfPresent(payload, "Message", ChatAppWebhookProjector.field(
                root, "Message", "message", "text", "content", "body"));
        putIfPresent(payload, "ErrorCode", ChatAppWebhookProjector.field(
                root, "ErrorCode", "errorCode", "code"));
        putIfPresent(payload, "ErrorDescription", ChatAppWebhookProjector.field(
                root, "ErrorDescription", "errorDescription", "reason"));
        return objectMapper.writeValueAsString(payload);
    }

    private static void putIfPresent(ObjectNode payload, String field, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(field, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record WebhookReceipt(UUID eventId, boolean duplicate, String status) {}
}
