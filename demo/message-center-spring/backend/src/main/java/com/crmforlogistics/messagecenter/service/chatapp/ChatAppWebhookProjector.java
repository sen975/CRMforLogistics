package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppWebhookProjector {
    private static final Set<String> MESSAGE_KINDS = Set.of(
            "text", "template", "image", "video", "document", "email", "system");
    private final ChannelEventMapper channelEventMapper;
    private final MessageMapper messageMapper;
    private final MessageStatusEventMapper statusEventMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ContactMapper contactMapper;
    private final ConversationMapper conversationMapper;
    private final EventHub eventHub;
    private final ObjectMapper objectMapper;

    public ChatAppWebhookProjector(ChannelEventMapper channelEventMapper,
                                   MessageMapper messageMapper,
                                   MessageStatusEventMapper statusEventMapper,
                                   ContactIdentityMapper contactIdentityMapper,
                                   ContactMapper contactMapper,
                                   ConversationMapper conversationMapper,
                                   EventHub eventHub,
                                   ObjectMapper objectMapper) {
        this.channelEventMapper = Objects.requireNonNull(channelEventMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper);
        this.contactMapper = Objects.requireNonNull(contactMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public ProjectionResult project(ChannelEventEntity event) {
        try {
            JsonNode root = objectMapper.readTree(event.getPayloadJsonb());
            String providerMessageId = field(root,
                    "MessageId", "messageId", "message_id", "wamid", "TaskId");
            String status = ChatAppMessageStatusNormalizer.normalize(
                    field(root, "Status", "status", "messageStatus"));
            if (!status.isBlank() && !providerMessageId.isBlank()) {
                projectStatus(event, providerMessageId, status, root);
                channelEventMapper.markProcessed(event.getId(), Instant.now());
                eventHub.publish("message-new", "{}");
                return new ProjectionResult("status", false);
            }
            boolean duplicate = projectInbound(event, root, providerMessageId);
            channelEventMapper.markProcessed(event.getId(), Instant.now());
            if (!duplicate) eventHub.publish("message-new", "{}");
            return new ProjectionResult("message", duplicate);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_PAYLOAD_INVALID", e);
        }
    }

    private void projectStatus(ChannelEventEntity event, String providerMessageId,
                               String status, JsonNode root) {
        Optional<MessageEntity> existing = messageMapper.findByProviderMessageId(
                event.getChannelAccountId(), providerMessageId);
        boolean imported = existing.isEmpty();
        MessageEntity message = existing.orElseGet(
                () -> importOutboundHistory(event, providerMessageId, status, root));
        String advancedStatus = ChatAppOutboundMessageStateMachine.advance(
                message.getCurrentStatus(), status);
        if (!imported && !Objects.equals(message.getCurrentStatus(), advancedStatus)) {
            message.setCurrentStatus(advancedStatus);
            message.setCurrentStatusAt(Instant.now());
            messageMapper.updateDeliveryStatus(
                    message.getId(), message.getProviderMessageId(), advancedStatus,
                    message.getCurrentStatusAt());
        }
        MessageStatusEventEntity statusEvent = new MessageStatusEventEntity();
        statusEvent.setId(UUID.randomUUID());
        statusEvent.setMessageId(message.getId());
        statusEvent.setStatus(status);
        statusEvent.setOccurredAt(Instant.now());
        statusEvent.setProviderEventId(eventKey(event));
        statusEvent.setReasonCode(field(root, "ErrorCode", "errorCode", "code"));
        statusEvent.setReasonMessage(field(root, "ErrorDescription", "errorDescription", "reason"));
        statusEvent.setMetadataJsonb("{}");
        statusEventMapper.insertIgnore(statusEvent);
    }

    private MessageEntity importOutboundHistory(ChannelEventEntity event,
                                                String providerMessageId,
                                                String status,
                                                JsonNode root) {
        String direction = field(root, "Direction", "direction");
        if (!"outbound".equalsIgnoreCase(direction)) {
            throw new IllegalStateException("CHATAPP_STATUS_MESSAGE_NOT_FOUND");
        }
        String recipient = field(root, "To", "to", "recipient", "userNumber");
        String normalized = ContactPointUtil.normalizePhone(recipient);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_OUTBOUND_RECIPIENT_REQUIRED");
        }
        ContactIdentityEntity identity = findOrCreateIdentity(normalized, recipient);
        ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                event.getChannelAccountId(), identity.getId());
        Instant occurredAt = event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt();
        String kind = field(root, "MessageKind", "messageKind", "kind").toLowerCase();
        if (!MESSAGE_KINDS.contains(kind)) kind = "text";

        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(event.getChannelAccountId());
        message.setConversationId(conversation.getId());
        message.setSourceEventId(event.getId());
        message.setProviderMessageId(providerMessageId);
        String clientRequestId = field(root,
                "ClientRequestId", "clientRequestId", "client_request_id", "TaskId");
        if (!clientRequestId.isBlank()) message.setClientRequestId(clientRequestId);
        message.setDirection("outbound");
        message.setMessageKind(kind);
        message.setBodyText(field(root, "Message", "message", "text", "content", "body"));
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread(false);
        message.setCurrentStatus(status);
        message.setCurrentStatusAt(occurredAt);
        message.setMetadataJsonb("{}");
        messageMapper.insertWithSequence(message);
        return message;
    }

    private boolean projectInbound(ChannelEventEntity event, JsonNode root,
                                   String providerMessageId) {
        if (providerMessageId.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_MESSAGE_ID_REQUIRED");
        }
        if (messageMapper.findByProviderMessageId(
                event.getChannelAccountId(), providerMessageId).isPresent()) {
            return true;
        }
        String from = field(root, "From", "from", "sender", "wa_id", "userNumber");
        String normalized = ContactPointUtil.normalizePhone(from);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_SENDER_REQUIRED");
        }
        ContactIdentityEntity identity = findOrCreateIdentity(normalized, from);
        ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                event.getChannelAccountId(), identity.getId());
        Instant occurredAt = Instant.now();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(event.getChannelAccountId());
        message.setConversationId(conversation.getId());
        message.setSourceEventId(event.getId());
        message.setProviderMessageId(providerMessageId);
        message.setDirection("inbound");
        message.setMessageKind("text");
        message.setBodyText(field(root, "Message", "message", "text", "content", "body"));
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread(true);
        message.setCurrentStatus("delivered");
        message.setCurrentStatusAt(occurredAt);
        message.setMetadataJsonb("{}");
        messageMapper.insertWithSequence(message);

        MessageStatusEventEntity statusEvent = new MessageStatusEventEntity();
        statusEvent.setId(UUID.randomUUID());
        statusEvent.setMessageId(message.getId());
        statusEvent.setStatus("delivered");
        statusEvent.setOccurredAt(occurredAt);
        statusEvent.setProviderEventId(eventKey(event));
        statusEvent.setMetadataJsonb("{}");
        statusEventMapper.insertIgnore(statusEvent);
        return false;
    }

    private ContactIdentityEntity findOrCreateIdentity(String normalized, String display) {
        Optional<ContactIdentityEntity> existing =
                contactIdentityMapper.findByNormalizedValue("chatapp", normalized);
        if (existing.isEmpty()) {
            existing = contactIdentityMapper.findByNormalizedValue("whatsapp", normalized);
        }
        if (existing.isPresent()) return existing.get();

        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(display == null || display.isBlank() ? normalized : display);
        contact.setStatus("active");
        contactMapper.insert(contact);

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType("chatapp");
        identity.setIdentityScope("phone");
        identity.setIdentityValue(normalized);
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(display);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        contactIdentityMapper.insert(identity);
        return identity;
    }

    static String field(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode found = find(node, name);
            if (found != null && !found.isNull()) {
                return found.isTextual() ? found.asText() : found.toString();
            }
        }
        return "";
    }

    private static JsonNode find(JsonNode node, String name) {
        if (node == null) return null;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
                JsonNode nested = find(entry.getValue(), name);
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode nested = find(child, name);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String eventKey(ChannelEventEntity event) {
        return event.getProviderEventId() == null
                ? event.getId().toString() : event.getProviderEventId();
    }

    public record ProjectionResult(String type, boolean duplicate) {}
}
