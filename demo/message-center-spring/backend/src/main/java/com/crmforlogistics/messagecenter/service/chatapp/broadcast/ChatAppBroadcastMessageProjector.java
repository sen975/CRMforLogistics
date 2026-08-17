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
        if (recipient.getMessageId() != null) {
            MessageEntity existing = messageMapper.selectById(recipient.getMessageId());
            return new ProjectionResult(recipient.getMessageId(), false,
                    existing == null ? "processing" : existing.getCurrentStatus());
        }

        String clientRequestId = "broadcast:" + broadcastId + ":recipient:" + recipientId;
        Optional<MessageEntity> duplicate = messageMapper.findByClientRequestId(
                broadcast.getChannelAccountId(), clientRequestId);
        if (duplicate.isPresent()) {
            MessageEntity existing = duplicate.orElseThrow();
            recipientMapper.linkMessageIfAbsent(recipientId, existing.getId(), clock.instant());
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
        if (recipientMapper.linkMessageIfAbsent(recipientId, message.getId(), clock.instant()) != 1) {
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
}
