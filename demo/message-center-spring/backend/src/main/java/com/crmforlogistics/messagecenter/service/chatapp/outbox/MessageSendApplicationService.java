package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.entity.OutboxJobEntity;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.OutboxJobMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;

@Service
public class MessageSendApplicationService {
    private static final Set<String> SUPPORTED_KINDS =
            Set.of("text", "template", "image", "video", "document");
    private final MessageMapper messageMapper;
    private final OutboxJobMapper outboxJobMapper;
    private final MessageStatusEventMapper statusEventMapper;
    private final ObjectMapper objectMapper;
    private final EventHub eventHub;
    private final ConversationAccessService conversationAccessService;
    private final TemplateMessageTextResolver templateTextResolver;
    private final AiTopicActivityRecorder topicActivityRecorder;
    private final ChatAppAccountResolver accountResolver;

    public MessageSendApplicationService(MessageMapper messageMapper,
                                         OutboxJobMapper outboxJobMapper,
                                         MessageStatusEventMapper statusEventMapper,
                                         ObjectMapper objectMapper,
                                         EventHub eventHub,
                                         ConversationAccessService conversationAccessService,
                                         TemplateMessageTextResolver templateTextResolver) {
        this(messageMapper, outboxJobMapper, statusEventMapper, objectMapper, eventHub,
                conversationAccessService, templateTextResolver, null, null);
    }

    public MessageSendApplicationService(MessageMapper messageMapper,
                                         OutboxJobMapper outboxJobMapper,
                                         MessageStatusEventMapper statusEventMapper,
                                         ObjectMapper objectMapper,
                                         EventHub eventHub,
                                         ConversationAccessService conversationAccessService,
                                         TemplateMessageTextResolver templateTextResolver,
                                         AiTopicActivityRecorder topicActivityRecorder) {
        this(messageMapper, outboxJobMapper, statusEventMapper, objectMapper, eventHub,
                conversationAccessService, templateTextResolver, topicActivityRecorder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MessageSendApplicationService(MessageMapper messageMapper,
                                         OutboxJobMapper outboxJobMapper,
                                         MessageStatusEventMapper statusEventMapper,
                                         ObjectMapper objectMapper,
                                         EventHub eventHub,
                                         ConversationAccessService conversationAccessService,
                                         TemplateMessageTextResolver templateTextResolver,
                                         AiTopicActivityRecorder topicActivityRecorder,
                                         ChatAppAccountResolver accountResolver) {
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.outboxJobMapper = Objects.requireNonNull(outboxJobMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.conversationAccessService = Objects.requireNonNull(conversationAccessService);
        this.templateTextResolver = Objects.requireNonNull(templateTextResolver);
        this.topicActivityRecorder = topicActivityRecorder;
        this.accountResolver = accountResolver;
    }

    @Transactional
    public MessageAccepted accept(SendMessageCommand command, UUID actorUserId) {
        validate(command, actorUserId);
        String clientRequestId = command.clientRequestId().trim();
        ConversationEntity conversation = conversationAccessService.lockForMessage(
                command.conversationId(), command.channelAccountId(), actorUserId);
        Long accountVersion = null;
        if (accountResolver != null) {
            ChannelAccountEntity account = accountResolver.requireOwnedAccountForSend(
                    actorUserId, command.channelAccountId(), null);
            accountVersion = account.getVersion() == null ? 0L : account.getVersion();
        }
        var duplicate = messageMapper.findByClientRequestId(
                command.channelAccountId(), clientRequestId);
        if (duplicate.isPresent()) {
            MessageEntity existing = duplicate.get();
            return new MessageAccepted(existing.getId(), existing.getCurrentStatus(), true);
        }

        duplicate = messageMapper.findByClientRequestId(
                command.channelAccountId(), clientRequestId);
        if (duplicate.isPresent()) {
            MessageEntity existing = duplicate.get();
            return new MessageAccepted(existing.getId(), existing.getCurrentStatus(), true);
        }

        Instant now = Instant.now();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(command.conversationId());
        message.setChannelAccountId(command.channelAccountId());
        message.setChannelAccountVersion(accountVersion);
        message.setClientRequestId(clientRequestId);
        message.setDirection("outbound");
        message.setMessageKind(command.kind());
        message.setBodyText(displayText(command));
        message.setOccurredAt(now);
        message.setCountsAsUnread(false);
        message.setCurrentStatus("pending");
        message.setCurrentStatusAt(now);
        message.setCreatedByUserId(actorUserId);
        message.setMetadataJsonb(toJson(command.content()));
        messageMapper.insertWithSequence(message);
        if (topicActivityRecorder != null) {
            topicActivityRecorder.recordConversation(conversation, now);
        }

        OutboxJobEntity job = new OutboxJobEntity();
        job.setId(UUID.randomUUID());
        job.setMessageId(message.getId());
        job.setJobType("chatapp_send");
        job.setStatus("pending");
        job.setAttemptCount(0);
        job.setMaxAttempts(3);
        job.setNextAttemptAt(now);
        outboxJobMapper.insertIgnore(job);

        MessageStatusEventEntity status = new MessageStatusEventEntity();
        status.setId(UUID.randomUUID());
        status.setMessageId(message.getId());
        status.setStatus("pending");
        status.setOccurredAt(now);
        status.setMetadataJsonb("{}");
        statusEventMapper.insertIgnore(status);
        publishAfterCommit();

        return new MessageAccepted(message.getId(), "pending", false);
    }

    private static void validate(SendMessageCommand command, UUID actorUserId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(command.channelAccountId(), "channelAccountId");
        Objects.requireNonNull(command.conversationId(), "conversationId");
        if (!SUPPORTED_KINDS.contains(command.kind())) {
            throw new IllegalArgumentException("CHATAPP_MESSAGE_KIND_UNSUPPORTED");
        }
        if (command.clientRequestId() == null || command.clientRequestId().isBlank()
                || command.clientRequestId().length() > 255) {
            throw new IllegalArgumentException("CHATAPP_CLIENT_REQUEST_ID_INVALID");
        }
        if (command.content() == null || command.content().isEmpty()) {
            throw new IllegalArgumentException("CHATAPP_MESSAGE_CONTENT_REQUIRED");
        }
    }

    private String toJson(Map<String, Object> content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("CHATAPP_MESSAGE_CONTENT_INVALID", e);
        }
    }

    private String displayText(SendMessageCommand command) {
        Object value = command.content().get("text");
        if (value != null) {
            return value.toString();
        }
        if (!"template".equals(command.kind())) {
            return "[" + command.kind() + "]";
        }

        String code = stringValue(command.content().get("templateCode"));
        String language = firstNonBlank(stringValue(command.content().get("languageCode")), "en_US");
        Map<String, String> params = stringMap(command.content().get("templateParams"));
        return templateTextResolver.renderForSend(command.channelAccountId(), code, language, params);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), stringValue(item)));
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private void publishAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventHub.publish("message-new", "{}");
                }
            });
        } else {
            eventHub.publish("message-new", "{}");
        }
    }

    public record SendMessageCommand(UUID channelAccountId, UUID conversationId, String kind,
                                     String clientRequestId, Map<String, Object> content,
                                     Long channelAccountVersion) {
        public SendMessageCommand(UUID channelAccountId, UUID conversationId, String kind,
                                  String clientRequestId, Map<String, Object> content) {
            this(channelAccountId, conversationId, kind, clientRequestId, content, null);
        }
    }

    public record MessageAccepted(UUID messageId, String status, boolean duplicate) {}
}
