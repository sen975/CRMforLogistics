package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
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
    private final ConversationMapper conversationMapper;
    private final EventHub eventHub;
    private final ObjectMapper objectMapper;
    private final ChatAppBroadcastRecipientMapper broadcastRecipientMapper;
    private final ChatAppBroadcastMessageProjector broadcastMessageProjector;
    private final AiTopicActivityRecorder topicActivityRecorder;
    private final ChannelAccountMapper channelAccountMapper;
    private final ChannelAddressBookService addressBookService;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatAppWebhookProjector(ChannelEventMapper channelEventMapper,
                                   MessageMapper messageMapper,
                                   MessageStatusEventMapper statusEventMapper,
                                   ConversationMapper conversationMapper,
                                   EventHub eventHub,
                                   ObjectMapper objectMapper,
                                   ChatAppBroadcastRecipientMapper broadcastRecipientMapper,
                                   ChatAppBroadcastMessageProjector broadcastMessageProjector,
                                   AiTopicActivityRecorder topicActivityRecorder,
                                   ChannelAccountMapper channelAccountMapper,
                                   ChannelAddressBookService addressBookService) {
        this.channelEventMapper = Objects.requireNonNull(channelEventMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.broadcastRecipientMapper = Objects.requireNonNull(broadcastRecipientMapper);
        this.broadcastMessageProjector = Objects.requireNonNull(broadcastMessageProjector);
        this.topicActivityRecorder = topicActivityRecorder;
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.addressBookService = Objects.requireNonNull(addressBookService);
    }

    @Transactional
    public ProjectionResult project(ChannelEventEntity event) {
        try {
            ChannelAccountEntity account = requireProjectionAccount(event.getChannelAccountId());
            JsonNode root = objectMapper.readTree(event.getPayloadJsonb());
            String providerMessageId = field(root,
                    "MessageId", "messageId", "message_id", "wamid", "TaskId");
            String status = ChatAppMessageStatusNormalizer.normalize(
                    field(root, "Status", "status", "messageStatus"));
            if (!status.isBlank() && !providerMessageId.isBlank()) {
                projectStatus(event, account, providerMessageId, status, root);
                channelEventMapper.markProcessed(event.getId(), Instant.now());
                eventHub.publish("message-new", "{}");
                return new ProjectionResult("status", false);
            }
            boolean duplicate = projectInbound(event, account, root, providerMessageId);
            channelEventMapper.markProcessed(event.getId(), Instant.now());
            if (!duplicate) eventHub.publish("message-new", "{}");
            return new ProjectionResult("message", duplicate);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_PAYLOAD_INVALID", e);
        }
    }

    private void projectStatus(ChannelEventEntity event, ChannelAccountEntity account,
                               String providerMessageId,
                               String status, JsonNode root) {
        Optional<MessageEntity> existing = messageMapper.findByProviderMessageId(
                event.getChannelAccountId(), providerMessageId);
        if (existing.isEmpty() && projectBroadcastStatus(event, providerMessageId, status, root)) {
            return;
        }
        boolean imported = existing.isEmpty();
        MessageEntity message = existing.orElseGet(
                () -> importOutboundHistory(event, account, providerMessageId, status, root));
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

    private boolean projectBroadcastStatus(ChannelEventEntity event,
                                           String providerMessageId,
                                           String status,
                                           JsonNode root) {
        String recipientNumber = ContactPointUtil.normalizePhone(field(
                root, "To", "to", "recipient", "userNumber"));
        if (recipientNumber.isBlank()) return false;
        var matches = broadcastRecipientMapper.findByGroupMessageIdAndNumber(
                event.getChannelAccountId(), providerMessageId, recipientNumber, 2);
        if (matches.isEmpty()) return false;
        if (matches.size() > 1) {
            throw new IllegalStateException("CHATAPP_BROADCAST_STATUS_RECIPIENT_AMBIGUOUS");
        }
        var recipient = matches.get(0);
        RecipientStatus recipientStatus = recipientStatus(status);
        ReconciliationItem item = new ReconciliationItem(
                0,
                recipientNumber,
                providerMessageId,
                field(root, "UniqueMessageId", "uniqueMessageId", "UniqueId"),
                recipientStatus,
                field(root, "Status", "status", "messageStatus"),
                field(root, "ErrorDescription", "errorDescription", "reason"),
                event.getOccurredAt(),
                "");
        var result = broadcastMessageProjector.applyReconciliation(
                recipient.getBroadcastId(), recipient.getId(), item,
                event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt());
        if (result != null && result.diagnosticCode() != null
                && !result.diagnosticCode().isBlank()) {
            throw new IllegalStateException(result.diagnosticCode());
        }
        return true;
    }

    private static RecipientStatus recipientStatus(String status) {
        return switch (status) {
            case "sent", "submitted" -> RecipientStatus.SENT;
            case "delivered" -> RecipientStatus.DELIVERED;
            case "read" -> RecipientStatus.READ;
            case "failed" -> RecipientStatus.FAILED_RECIPIENT;
            default -> RecipientStatus.PROCESSING;
        };
    }

    private MessageEntity importOutboundHistory(ChannelEventEntity event,
                                                ChannelAccountEntity account,
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
        ChannelAddressBookService.ResolvedContact resolved = addressBookService.resolveOrCreateInbound(
                account.getOwnerUserId(), "chatapp", event.getChannelAccountId(), normalized, recipient);
        ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                event.getChannelAccountId(), resolved.identityId());
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
        recordTopicActivity(conversation, occurredAt);
        return message;
    }

    private boolean projectInbound(ChannelEventEntity event, ChannelAccountEntity account, JsonNode root,
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
        String displayName = field(root, "ContactName", "contactName", "contact_name",
                "Name", "FromUserName", "fromUserName");
        ChannelAddressBookService.ResolvedContact resolved = addressBookService.resolveOrCreateInbound(
                account.getOwnerUserId(), "chatapp", event.getChannelAccountId(), normalized,
                displayName.isBlank() ? from : displayName);
        ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                event.getChannelAccountId(), resolved.identityId());
        Instant occurredAt = event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt();
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
        recordTopicActivity(conversation, occurredAt);

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

    private void recordTopicActivity(ConversationEntity conversation, Instant occurredAt) {
        if (topicActivityRecorder != null) {
            topicActivityRecorder.recordConversation(conversation, occurredAt);
        }
    }

    private ChannelAccountEntity requireProjectionAccount(UUID accountId) {
        ChannelAccountEntity account = accountId == null ? null : channelAccountMapper.selectById(accountId);
        if (account == null || account.getOwnerUserId() == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        return account;
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
