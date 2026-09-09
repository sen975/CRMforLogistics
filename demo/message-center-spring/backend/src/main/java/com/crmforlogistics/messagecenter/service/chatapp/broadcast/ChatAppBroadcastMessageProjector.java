package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageStateMachine;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppProviderMessageIdentity;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppBroadcastMessageProjector {
    private static final String TEMPLATE_UNAVAILABLE = "模板内容不可用";

    private final ChatAppBroadcastMapper broadcastMapper;
    private final ChatAppBroadcastRecipientMapper recipientMapper;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final MessageStatusEventMapper statusEventMapper;
    private final TemplateMessageTextResolver textResolver;
    private final ObjectMapper objectMapper;
    private final EventHub eventHub;
    private final Clock clock;
    private final AiTopicActivityRecorder topicActivityRecorder;

    public ChatAppBroadcastMessageProjector(
            ChatAppBroadcastMapper broadcastMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            MessageStatusEventMapper statusEventMapper,
            TemplateMessageTextResolver textResolver,
            ObjectMapper objectMapper,
            EventHub eventHub,
            Clock clock) {
        this(broadcastMapper, recipientMapper, messageMapper, conversationMapper, statusEventMapper,
                textResolver, objectMapper, eventHub, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatAppBroadcastMessageProjector(
            ChatAppBroadcastMapper broadcastMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            MessageStatusEventMapper statusEventMapper,
            TemplateMessageTextResolver textResolver,
            ObjectMapper objectMapper,
            EventHub eventHub,
            Clock clock,
            AiTopicActivityRecorder topicActivityRecorder) {
        this.broadcastMapper = Objects.requireNonNull(broadcastMapper);
        this.recipientMapper = Objects.requireNonNull(recipientMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.textResolver = Objects.requireNonNull(textResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.clock = Objects.requireNonNull(clock);
        this.topicActivityRecorder = topicActivityRecorder;
    }

    @Transactional
    public ProjectionResult ensureProcessing(UUID broadcastId, UUID recipientId) {
        ChatAppBroadcastRecipientEntity recipient = recipientMapper.findByIdForUpdate(recipientId)
                .orElseThrow(() -> new IllegalStateException("CHATAPP_BROADCAST_RECIPIENT_NOT_FOUND"));
        if (!broadcastId.equals(recipient.getBroadcastId())) {
            throw new IllegalStateException("CHATAPP_BROADCAST_RECIPIENT_SCOPE_INVALID");
        }
        ChatAppBroadcastEntity broadcast = broadcastMapper.selectById(broadcastId);
        if (broadcast == null) {
            throw new IllegalStateException("CHATAPP_BROADCAST_NOT_FOUND");
        }
        return ensureProcessingInternal(broadcast, recipient);
    }

    @Transactional
    public ReconciliationProjectionResult applyReconciliation(
            UUID broadcastId,
            UUID recipientId,
            ChatAppBroadcastGateway.ReconciliationItem item,
            Instant reconciledAt) {
        ChatAppBroadcastRecipientEntity recipient = recipientMapper.findByIdForUpdate(recipientId)
                .orElseThrow(() -> new IllegalStateException("CHATAPP_BROADCAST_RECIPIENT_NOT_FOUND"));
        ChatAppBroadcastEntity broadcast = broadcastMapper.selectById(broadcastId);
        if (broadcast == null || !broadcastId.equals(recipient.getBroadcastId())) {
            throw new IllegalStateException("CHATAPP_BROADCAST_SCOPE_MISMATCH");
        }

        String providerMessageId = nullIfBlank(bounded(item.providerMessageId(), 255));
        String providerUniqueMessageId = nullIfBlank(bounded(item.providerUniqueMessageId(), 255));
        String canonicalProviderMessageId = nullIfBlank(bounded(
                ChatAppProviderMessageIdentity.canonicalForBroadcast(
                        providerMessageId, providerUniqueMessageId,
                        broadcast.getProviderGroupMessageId()), 255));
        java.util.List<MessageEntity> providerMatches = canonicalProviderMessageId == null
                ? java.util.List.of()
                : messageMapper.findAllByProviderMessageId(
                        broadcast.getChannelAccountId(), canonicalProviderMessageId);
        if (providerMatches.size() > 1) {
            return new ReconciliationProjectionResult(recipient.getMessageId(), false, false,
                    "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        }

        MessageEntity linked = recipient.getMessageId() == null
                ? null : messageMapper.selectById(recipient.getMessageId());
        MessageEntity providerMessage = providerMatches.isEmpty() ? null : providerMatches.get(0);
        if (linked != null && providerMessage != null && !linked.getId().equals(providerMessage.getId())) {
            return new ReconciliationProjectionResult(linked.getId(), false, false,
                    "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        }

        boolean created = false;
        MessageEntity target = linked != null ? linked : providerMessage;
        if (target == null) {
            ProjectionResult projection = ensureProcessingInternal(broadcast, recipient);
            target = messageMapper.selectById(projection.messageId());
            if (target == null) {
                return new ReconciliationProjectionResult(projection.messageId(), projection.created(), false,
                        "CHATAPP_BROADCAST_MESSAGE_NOT_FOUND");
            }
            created = projection.created();
        }

        ConversationEntity expected = conversationMapper.getOrCreateConversationForSender(
                broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
                broadcast.getCreatedByUserId());
        if (!broadcast.getChannelAccountId().equals(target.getChannelAccountId())
                || expected == null || !expected.getId().equals(target.getConversationId())
                || !"outbound".equals(target.getDirection())) {
            return new ReconciliationProjectionResult(target.getId(), created, false,
                    "CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
        }

        String targetProviderMessageId = nullIfBlank(target.getProviderMessageId());
        String groupMessageId = nullIfBlank(broadcast.getProviderGroupMessageId());
        boolean legacyGroupBinding = canonicalProviderMessageId != null
                && groupMessageId != null
                && groupMessageId.equals(providerMessageId)
                && groupMessageId.equals(targetProviderMessageId);
        if (legacyGroupBinding) {
            if (messageMapper.replaceProviderMessageId(
                    target.getId(), groupMessageId, canonicalProviderMessageId) != 1) {
                MessageEntity persisted = messageMapper.selectById(target.getId());
                if (persisted == null
                        || !canonicalProviderMessageId.equals(
                        nullIfBlank(persisted.getProviderMessageId()))) {
                    return new ReconciliationProjectionResult(target.getId(), created, false,
                            "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
                }
                target = persisted;
            } else {
                target.setProviderMessageId(canonicalProviderMessageId);
            }
        }
        if (target.getProviderMessageId() != null && !target.getProviderMessageId().isBlank()
                && canonicalProviderMessageId != null
                && !target.getProviderMessageId().equals(canonicalProviderMessageId)) {
            return new ReconciliationProjectionResult(target.getId(), created, false,
                    "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        }
        if (recipient.getProviderMessageId() != null && !recipient.getProviderMessageId().isBlank()
                && providerMessageId != null
                && !recipient.getProviderMessageId().equals(providerMessageId)) {
            return new ReconciliationProjectionResult(target.getId(), created, false,
                    "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        }
        if (recipient.getMessageId() == null
                && recipientMapper.linkMessageIfAbsent(
                        recipientId, target.getId(), reconciledAt) != 1) {
            return new ReconciliationProjectionResult(target.getId(), created, false,
                    "CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
        }

        String nextStatus = recipientMessageStatus(item.status());
        String advanced = ChatAppOutboundMessageStateMachine.advance(target.getCurrentStatus(), nextStatus);
        boolean messageStatusChanged = !Objects.equals(target.getCurrentStatus(), advanced);
        String effectiveProviderMessageId = canonicalProviderMessageId == null
                ? nullIfBlank(target.getProviderMessageId()) : canonicalProviderMessageId;
        boolean providerBindingChanged = canonicalProviderMessageId != null
                && !Objects.equals(
                nullIfBlank(target.getProviderMessageId()), canonicalProviderMessageId);
        boolean persistedMessageStatus = false;
        if (messageStatusChanged) {
            persistedMessageStatus = messageMapper.updateDeliveryStatus(
                    target.getId(), effectiveProviderMessageId, advanced, reconciledAt) == 1;
        }
        boolean persistedProviderBinding = false;
        if (persistedMessageStatus) {
            MessageStatusEventEntity statusEvent = new MessageStatusEventEntity();
            statusEvent.setId(UUID.randomUUID());
            statusEvent.setMessageId(target.getId());
            statusEvent.setStatus(advanced);
            statusEvent.setOccurredAt(reconciledAt);
            statusEvent.setProviderEventId(reconciliationEventId(
                    recipientId, effectiveProviderMessageId, advanced));
            statusEvent.setReasonCode(bounded(item.diagnosticCode(), 100));
            statusEvent.setReasonMessage(ChatAppBroadcastDiagnosticSanitizer.sanitize(item.failureReason()));
            statusEvent.setMetadataJsonb("{}");
            statusEventMapper.insertIgnore(statusEvent);
            conversationMapper.recomputeProjection(target.getConversationId());
        } else if (providerBindingChanged) {
            persistedProviderBinding = messageMapper.updateProviderMessageId(
                    target.getId(), effectiveProviderMessageId) == 1;
        }

        if ((messageStatusChanged && !persistedMessageStatus)
                || (providerBindingChanged && !messageStatusChanged && !persistedProviderBinding)) {
            MessageEntity persisted = messageMapper.selectById(target.getId());
            if (persisted == null) {
                return new ReconciliationProjectionResult(target.getId(), created, false,
                        "CHATAPP_BROADCAST_MESSAGE_NOT_FOUND");
            }
            if (!broadcast.getChannelAccountId().equals(persisted.getChannelAccountId())
                    || !expected.getId().equals(persisted.getConversationId())
                    || !"outbound".equals(persisted.getDirection())) {
                return new ReconciliationProjectionResult(target.getId(), created, false,
                        "CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
            }
            if (canonicalProviderMessageId != null
                    && persisted.getProviderMessageId() != null
                    && !persisted.getProviderMessageId().isBlank()
                    && !canonicalProviderMessageId.equals(persisted.getProviderMessageId())) {
                return new ReconciliationProjectionResult(target.getId(), created, false,
                        "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
            }
            if (providerBindingChanged
                    && (persisted.getProviderMessageId() == null
                    || persisted.getProviderMessageId().isBlank())) {
                return new ReconciliationProjectionResult(target.getId(), created, false,
                        "CHATAPP_BROADCAST_MESSAGE_UPDATE_CONFLICT");
            }
            String persistedStatus = normalizeText(persisted.getCurrentStatus());
            if (messageStatusChanged
                    && !ChatAppOutboundMessageStateMachine.advance(persistedStatus, advanced)
                    .equals(persistedStatus)) {
                return new ReconciliationProjectionResult(target.getId(), created, false,
                        "CHATAPP_BROADCAST_MESSAGE_UPDATE_CONFLICT");
            }
            target = persisted;
        }

        ChatAppBroadcastModels.RecipientStatus currentRecipientStatus =
                ChatAppBroadcastModels.RecipientStatus.valueOf(recipient.getStatus());
        String effectiveMessageStatus = persistedMessageStatus ? advanced : target.getCurrentStatus();
        ChatAppBroadcastModels.RecipientStatus nextRecipientStatus =
                recipientStatus(effectiveMessageStatus);
        ChatAppBroadcastModels.RecipientStatus advancedRecipientStatus =
                ChatAppBroadcastStateMachine.advanceRecipient(
                        currentRecipientStatus, nextRecipientStatus);
        String effectiveRecipientMessageId = providerMessageId == null
                ? nullIfBlank(recipient.getProviderMessageId()) : providerMessageId;
        String effectiveRecipientUniqueId = providerUniqueMessageId == null
                ? nullIfBlank(recipient.getProviderUniqueMessageId()) : providerUniqueMessageId;
        String failureReason = advancedRecipientStatus
                == ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT
                ? item.failureReason() == null || item.failureReason().isBlank()
                ? normalizeText(recipient.getFailureReason())
                : ChatAppBroadcastDiagnosticSanitizer.sanitize(item.failureReason())
                : "";
        Instant effectiveProviderSentAt = item.providerSentAt() == null
                ? recipient.getProviderSentAt() : item.providerSentAt();
        boolean recipientChanged = !Objects.equals(recipient.getStatus(), advancedRecipientStatus.name())
                || !Objects.equals(nullIfBlank(recipient.getProviderMessageId()), effectiveRecipientMessageId)
                || !Objects.equals(nullIfBlank(recipient.getProviderUniqueMessageId()), effectiveRecipientUniqueId)
                || !Objects.equals(normalizeText(recipient.getFailureReason()), failureReason)
                || !Objects.equals(recipient.getProviderSentAt(), effectiveProviderSentAt);
        if (recipientChanged) {
            recipientMapper.updateProviderStatus(
                    recipientId, effectiveRecipientMessageId, effectiveRecipientUniqueId,
                    advancedRecipientStatus.name(), failureReason,
                    effectiveProviderSentAt, reconciledAt);
        }
        return new ReconciliationProjectionResult(
                target.getId(), created,
                persistedMessageStatus || persistedProviderBinding || recipientChanged, "");
    }

    private ProjectionResult ensureProcessingInternal(
            ChatAppBroadcastEntity broadcast, ChatAppBroadcastRecipientEntity recipient) {
        if (recipient.getMessageId() != null) {
            MessageEntity existing = messageMapper.selectById(recipient.getMessageId());
            requireExpectedScope(broadcast, recipient, existing);
            return new ProjectionResult(recipient.getMessageId(), false,
                    existing.getCurrentStatus());
        }

        String clientRequestId = "broadcast:" + broadcast.getId() + ":recipient:" + recipient.getId();
        Optional<MessageEntity> duplicate = messageMapper.findByClientRequestId(
                broadcast.getChannelAccountId(), clientRequestId);
        if (duplicate.isPresent()) {
            MessageEntity existing = duplicate.orElseThrow();
            requireExpectedScope(broadcast, recipient, existing);
            if (recipientMapper.linkMessageIfAbsent(
                    recipient.getId(), existing.getId(), clock.instant()) != 1) {
                ChatAppBroadcastRecipientEntity refreshed = recipientMapper.findByIdForUpdate(recipient.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "CHATAPP_BROADCAST_RECIPIENT_NOT_FOUND"));
                if (!existing.getId().equals(refreshed.getMessageId())) {
                    throw new IllegalStateException("CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
                }
            }
            return new ProjectionResult(existing.getId(), false, existing.getCurrentStatus());
        }

        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
                broadcast.getCreatedByUserId());
        if (conversation == null || conversation.getId() == null) {
            throw new IllegalStateException("CHATAPP_BROADCAST_CONVERSATION_NOT_FOUND");
        }
        if (broadcast.getSubmittedAt() == null) {
            throw new IllegalStateException("CHATAPP_BROADCAST_SUBMITTED_AT_MISSING");
        }
        Instant occurredAt = broadcast.getSubmittedAt();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversation.getId());
        message.setChannelAccountId(broadcast.getChannelAccountId());
        message.setClientRequestId(clientRequestId);
        message.setDirection("outbound");
        message.setMessageKind("template");
        RenderedBody renderedBody = renderBody(broadcast, recipient);
        message.setBodyText(renderedBody.text());
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread(false);
        message.setCurrentStatus("processing");
        message.setCurrentStatusAt(occurredAt);
        message.setCreatedByUserId(broadcast.getCreatedByUserId());
        message.setMetadataJsonb(metadata(broadcast, recipient, renderedBody.unavailableReason()));
        if (messageMapper.insertWithSequence(message) != 1) {
            throw new IllegalStateException("CHATAPP_BROADCAST_MESSAGE_INSERT_FAILED");
        }
        if (recipientMapper.linkMessageIfAbsent(recipient.getId(), message.getId(), clock.instant()) != 1) {
            throw new IllegalStateException("CHATAPP_BROADCAST_MESSAGE_LINK_FAILED");
        }

        MessageStatusEventEntity event = new MessageStatusEventEntity();
        event.setId(UUID.randomUUID());
        event.setMessageId(message.getId());
        event.setStatus("processing");
        event.setOccurredAt(occurredAt);
        event.setProviderEventId(clientRequestId + ":processing");
        event.setMetadataJsonb("{}");
        statusEventMapper.insertIgnore(event);
        conversationMapper.recomputeProjection(conversation.getId());
        if (topicActivityRecorder != null) {
            topicActivityRecorder.recordConversation(conversation, occurredAt);
        }
        publishAfterCommit();
        return new ProjectionResult(message.getId(), true, "processing");
    }

    private static String recipientMessageStatus(
            ChatAppBroadcastModels.RecipientStatus status) {
        if (status == null) return "processing";
        return switch (status) {
            case QUEUED, PROCESSING -> "processing";
            case SENT -> "sent";
            case DELIVERED -> "delivered";
            case READ -> "read";
            case FAILED_RECIPIENT -> "failed";
        };
    }

    private static ChatAppBroadcastModels.RecipientStatus recipientStatus(String messageStatus) {
        String normalized = messageStatus == null ? "" : messageStatus.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "sent", "submitted" -> ChatAppBroadcastModels.RecipientStatus.SENT;
            case "failed" -> ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT;
            case "delivered" -> ChatAppBroadcastModels.RecipientStatus.DELIVERED;
            case "read" -> ChatAppBroadcastModels.RecipientStatus.READ;
            default -> ChatAppBroadcastModels.RecipientStatus.PROCESSING;
        };
    }

    private void requireExpectedScope(
            ChatAppBroadcastEntity broadcast,
            ChatAppBroadcastRecipientEntity recipient,
            MessageEntity message) {
        if (message == null) {
            throw new IllegalStateException("CHATAPP_BROADCAST_MESSAGE_NOT_FOUND");
        }
        ConversationEntity expected = conversationMapper.getOrCreateConversationForSender(
                broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
                broadcast.getCreatedByUserId());
        if (!broadcast.getChannelAccountId().equals(message.getChannelAccountId())
                || expected == null || !expected.getId().equals(message.getConversationId())
                || !"outbound".equals(message.getDirection())) {
            throw new IllegalStateException("CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value;
    }

    private static String reconciliationEventId(
            UUID recipientId, String providerMessageId, String status) {
        String source = (providerMessageId == null ? "" : providerMessageId) + "|" + status;
        UUID digest = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "broadcast-reconcile:" + recipientId + ":" + status + ":" + digest;
    }

    private RenderedBody renderBody(ChatAppBroadcastEntity broadcast,
                                    ChatAppBroadcastRecipientEntity recipient) {
        Map<String, Object> params = parseParams(recipient.getTemplateParamsJsonb());
        String body = broadcast.getTemplateBodySnapshot();
        if (body == null || body.isBlank()) {
            body = textResolver.findDisplayBody(broadcast.getChannelAccountId(), broadcast.getTemplateCode(),
                    broadcast.getLanguageCode()).orElse("");
        }
        return body == null || body.isBlank()
                ? new RenderedBody(TEMPLATE_UNAVAILABLE,
                        "CHATAPP_BROADCAST_TEMPLATE_SNAPSHOT_MISSING")
                : new RenderedBody(textResolver.renderSnapshot(body, params), null);
    }

    private String metadata(ChatAppBroadcastEntity broadcast,
                             ChatAppBroadcastRecipientEntity recipient,
                             String unavailableReason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("broadcastId", broadcast.getId().toString());
        value.put("broadcastRecipientId", recipient.getId().toString());
        value.put("providerGroupMessageId", broadcast.getProviderGroupMessageId());
        value.put("templateCode", broadcast.getTemplateCode());
        value.put("templateName", broadcast.getTemplateName());
        value.put("languageCode", broadcast.getLanguageCode());
        value.put("templateParams", parseParams(recipient.getTemplateParamsJsonb()));
        if (unavailableReason != null) {
            value.put("bodyUnavailableReason", unavailableReason);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_METADATA_SERIALIZATION_FAILED", e);
        }
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_PARAMS_INVALID", e);
        }
    }

    private void publishAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventHub.publish("message-new", "{}");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventHub.publish("message-new", "{}");
            }
        });
    }

    private record RenderedBody(String text, String unavailableReason) {}

    public record ProjectionResult(UUID messageId, boolean created, String status) {}

    public record ReconciliationProjectionResult(
            UUID messageId, boolean created, boolean updated, String diagnosticCode) {
        public ReconciliationProjectionResult {
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
        }
    }
}
