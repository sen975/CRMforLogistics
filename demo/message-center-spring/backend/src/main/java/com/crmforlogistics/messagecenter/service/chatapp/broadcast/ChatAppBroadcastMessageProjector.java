package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageStateMachine;
import com.crmforlogistics.messagecenter.service.event.EventHub;
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
    private final TemplateMapper templateMapper;
    private final TemplateMessageTextResolver textResolver;
    private final ObjectMapper objectMapper;
    private final EventHub eventHub;
    private final Clock clock;

    public ChatAppBroadcastMessageProjector(
            ChatAppBroadcastMapper broadcastMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            MessageStatusEventMapper statusEventMapper,
            TemplateMapper templateMapper,
            TemplateMessageTextResolver textResolver,
            ObjectMapper objectMapper,
            EventHub eventHub,
            Clock clock) {
        this.broadcastMapper = Objects.requireNonNull(broadcastMapper);
        this.recipientMapper = Objects.requireNonNull(recipientMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.textResolver = Objects.requireNonNull(textResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.clock = Objects.requireNonNull(clock);
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
        java.util.List<MessageEntity> providerMatches = providerMessageId == null
                ? java.util.List.of()
                : messageMapper.findAllByProviderMessageId(
                        broadcast.getChannelAccountId(), providerMessageId);
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
        if (target.getProviderMessageId() != null && !target.getProviderMessageId().isBlank()
                && providerMessageId != null
                && !target.getProviderMessageId().equals(providerMessageId)) {
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
        boolean providerBindingChanged =
                !Objects.equals(nullIfBlank(target.getProviderMessageId()), providerMessageId);
        if (messageStatusChanged) {
            messageMapper.updateDeliveryStatus(target.getId(), providerMessageId, advanced, reconciledAt);
            MessageStatusEventEntity statusEvent = new MessageStatusEventEntity();
            statusEvent.setId(UUID.randomUUID());
            statusEvent.setMessageId(target.getId());
            statusEvent.setStatus(advanced);
            statusEvent.setOccurredAt(reconciledAt);
            statusEvent.setProviderEventId(reconciliationEventId(
                    recipientId, providerMessageId, advanced));
            statusEvent.setReasonCode(bounded(item.diagnosticCode(), 100));
            statusEvent.setReasonMessage(bounded(item.failureReason(), 1000));
            statusEvent.setMetadataJsonb("{}");
            statusEventMapper.insertIgnore(statusEvent);
            conversationMapper.recomputeProjection(target.getConversationId());
        } else if (providerBindingChanged) {
            messageMapper.updateProviderMessageId(target.getId(), providerMessageId);
        }

        ChatAppBroadcastModels.RecipientStatus currentRecipientStatus =
                ChatAppBroadcastModels.RecipientStatus.valueOf(recipient.getStatus());
        ChatAppBroadcastModels.RecipientStatus nextRecipientStatus = item.status() == null
                ? ChatAppBroadcastModels.RecipientStatus.PROCESSING : item.status();
        ChatAppBroadcastModels.RecipientStatus advancedRecipientStatus =
                ChatAppBroadcastStateMachine.advanceRecipient(
                        currentRecipientStatus, nextRecipientStatus);
        String failureReason = bounded(item.failureReason(), 1000);
        boolean recipientChanged = !Objects.equals(recipient.getStatus(), advancedRecipientStatus.name())
                || !Objects.equals(nullIfBlank(recipient.getProviderMessageId()), providerMessageId)
                || !Objects.equals(nullIfBlank(recipient.getProviderUniqueMessageId()), providerUniqueMessageId)
                || !Objects.equals(normalizeText(recipient.getFailureReason()), failureReason)
                || !Objects.equals(recipient.getProviderSentAt(), item.providerSentAt());
        if (recipientChanged) {
            recipientMapper.updateProviderStatus(
                    recipientId, providerMessageId, providerUniqueMessageId,
                    advancedRecipientStatus.name(), failureReason,
                    item.providerSentAt(), reconciledAt);
        }
        return new ReconciliationProjectionResult(
                target.getId(), created,
                messageStatusChanged || providerBindingChanged || recipientChanged, "");
    }

    private ProjectionResult ensureProcessingInternal(
            ChatAppBroadcastEntity broadcast, ChatAppBroadcastRecipientEntity recipient) {
        if (recipient.getMessageId() != null) {
            MessageEntity existing = messageMapper.selectById(recipient.getMessageId());
            return new ProjectionResult(recipient.getMessageId(), false,
                    existing == null ? "processing" : existing.getCurrentStatus());
        }

        String clientRequestId = "broadcast:" + broadcast.getId() + ":recipient:" + recipient.getId();
        Optional<MessageEntity> duplicate = messageMapper.findByClientRequestId(
                broadcast.getChannelAccountId(), clientRequestId);
        if (duplicate.isPresent()) {
            MessageEntity existing = duplicate.orElseThrow();
            recipientMapper.linkMessageIfAbsent(recipient.getId(), existing.getId(), clock.instant());
            return new ProjectionResult(existing.getId(), false, existing.getCurrentStatus());
        }

        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
                broadcast.getCreatedByUserId());
        if (conversation == null || conversation.getId() == null) {
            throw new IllegalStateException("CHATAPP_BROADCAST_CONVERSATION_NOT_FOUND");
        }
        Instant occurredAt = broadcast.getSubmittedAt() == null
                ? clock.instant() : broadcast.getSubmittedAt();
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
            Optional<TemplateEntity> fallback = templateMapper.findForDisplay(
                    broadcast.getChannelAccountId(), broadcast.getTemplateCode(),
                    broadcast.getLanguageCode());
            body = fallback.map(TemplateEntity::getBody).orElse("");
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
