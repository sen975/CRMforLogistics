package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookProjector;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageStatusNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppPollingProjector {
    private final MessageMapper messageMapper;
    private final ChannelEventMapper eventMapper;
    private final ChatAppWebhookProjector webhookProjector;
    private final ObjectMapper objectMapper;

    public ChatAppPollingProjector(MessageMapper messageMapper,
                                   ChannelEventMapper eventMapper,
                                   ChatAppWebhookProjector webhookProjector,
                                   ObjectMapper objectMapper) {
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.eventMapper = Objects.requireNonNull(eventMapper);
        this.webhookProjector = Objects.requireNonNull(webhookProjector);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public boolean project(ListChatappMessageResponseBody.Data row, UUID channelAccountId) {
        String providerMessageId = firstNonBlank(row.getMessageId(), row.getUniqueMessageId());
        if (providerMessageId.isBlank()) {
            return false;
        }

        boolean inbound = isInbound(row);
        String status = "";
        if (!inbound) {
            Optional<MessageEntity> local = messageMapper.findByProviderMessageId(
                    channelAccountId, providerMessageId);
            if (local.isEmpty()) {
                String taskId = value(row.getUniqueMessageId());
                if (!taskId.isBlank()) {
                    local = messageMapper.findByClientRequestId(channelAccountId, taskId);
                }
            }
            if (local.isEmpty() && value(row.getUserNumber()).isBlank()) {
                return false;
            }
            local.ifPresent(message -> {
                if (message.getProviderMessageId() == null
                        || message.getProviderMessageId().isBlank()) {
                    message.setProviderMessageId(providerMessageId);
                    messageMapper.updateProviderMessageId(message.getId(), providerMessageId);
                }
            });
            status = firstRecognizedStatus(
                    row.getClientReadStatusName(), row.getMessageStatusName(),
                    row.getClientAcceptStatusName(), row.getMessageStatus(),
                    row.getClientReadStatus());
            if (status.isBlank()) {
                return false;
            }
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("MessageId", providerMessageId);
        put(payload, "From", inbound ? row.getUserNumber() : row.getBusinessNumber());
        put(payload, "To", inbound ? row.getBusinessNumber() : row.getUserNumber());
        if (inbound) {
            put(payload, "Message", messageText(row.getMessage()));
        } else {
            payload.put("Direction", "outbound");
            put(payload, "ClientRequestId", row.getUniqueMessageId());
            put(payload, "Message", messageText(row.getMessage()));
            payload.put("MessageKind", outboundMessageKind(row));
            payload.put("Status", status);
            put(payload, "ErrorDescription", row.getFailReason());
        }

        String payloadJson = payload.toString();
        String eventDiscriminator = inbound ? "message" : "status:" + status.toLowerCase(Locale.ROOT);
        String eventKey = sha256(channelAccountId + ":" + providerMessageId + ":" + eventDiscriminator);
        Instant now = Instant.now();
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(channelAccountId);
        event.setProviderEventId("poll-" + eventKey);
        event.setEventType(inbound ? "chatapp_message" : "chatapp_status");
        event.setOccurredAt(now);
        event.setReceivedAt(now);
        event.setPayloadJsonb(payloadJson);
        event.setPayloadHash(sha256(payloadJson));
        event.setProcessingStatus("received");
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setTraceId(UUID.randomUUID().toString());
        if (eventMapper.insertIgnore(event) == 0) {
            return false;
        }
        webhookProjector.project(event);
        return true;
    }

    private String messageText(String raw) {
        String value = value(raw);
        if (value.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root.isTextual()) return root.asText();
            for (String field : new String[] {"text", "body", "caption", "message", "content"}) {
                JsonNode found = root.get(field);
                if (found != null && found.isValueNode()) return found.asText();
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private String outboundMessageKind(ListChatappMessageResponseBody.Data row) {
        String type = value(row.getType()).toLowerCase(Locale.ROOT);
        if (type.contains("template")) return "template";
        String raw = value(row.getMessage());
        if (raw.isBlank()) return "text";
        try {
            JsonNode root = objectMapper.readTree(raw);
            for (String field : new String[] {
                    "templateCode", "templateName", "template", "templateId"}) {
                if (root.has(field)) return "template";
            }
        } catch (Exception ignored) {
        }
        return "text";
    }

    private static boolean isInbound(ListChatappMessageResponseBody.Data row) {
        String marker = firstNonBlank(
                row.getMessageSource(), row.getEventAction(), row.getType()).toLowerCase(Locale.ROOT);
        return marker.contains("in") || marker.contains("up") || marker.contains("receive")
                || marker.contains("user") || marker.contains("customer") || "mo".equals(marker);
    }

    private static void put(ObjectNode payload, String field, String value) {
        if (value != null && !value.isBlank()) payload.put(field, value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String firstRecognizedStatus(String... values) {
        for (String value : values) {
            String normalized = ChatAppMessageStatusNormalizer.normalize(value);
            if (!normalized.isBlank()) return normalized;
        }
        return "";
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("CHATAPP_POLL_EVENT_HASH_FAILED", e);
        }
    }
}
