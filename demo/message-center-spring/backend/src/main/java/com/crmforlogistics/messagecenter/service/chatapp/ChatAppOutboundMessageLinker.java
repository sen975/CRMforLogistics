package com.crmforlogistics.messagecenter.service.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.channel.chatapp.AliyunChatAppBroadcastGateway;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector.ReconciliationProjectionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppOutboundMessageLinker {
    private final MessageMapper messageMapper;
    private final ChatAppBroadcastRecipientMapper recipientMapper;
    private final ChatAppBroadcastMessageProjector messageProjector;
    private final Clock clock;

    public ChatAppOutboundMessageLinker(
            MessageMapper messageMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            ChatAppBroadcastMessageProjector messageProjector,
            Clock clock) {
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.recipientMapper = Objects.requireNonNull(recipientMapper);
        this.messageProjector = Objects.requireNonNull(messageProjector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public LinkResult resolve(UUID channelAccountId, ListChatappMessageResponseBody.Data row) {
        ReconciliationItem item = AliyunChatAppBroadcastGateway.parseRow(row, 1);
        String providerMessageId = value(row.getMessageId());
        List<MessageEntity> providerMatches = providerMessageId.isBlank()
                ? List.of()
                : messageMapper.findAllByProviderMessageId(channelAccountId, providerMessageId);
        if (providerMatches.size() > 1) {
            return new LinkResult(LinkResult.Kind.PROVIDER_CONFLICT, null,
                    "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        }
        if (providerMatches.size() == 1) {
            return new LinkResult(LinkResult.Kind.EXISTING_PROVIDER,
                    providerMatches.get(0).getId(), "");
        }

        String providerClientRequestId = value(row.getUniqueMessageId());
        if (!providerClientRequestId.isBlank()) {
            Optional<MessageEntity> ordinary = messageMapper.findByClientRequestId(
                    channelAccountId, providerClientRequestId);
            if (ordinary.isPresent()) {
                return new LinkResult(LinkResult.Kind.EXISTING_CLIENT_REQUEST,
                        ordinary.orElseThrow().getId(), "");
            }
        }

        Instant sentAt = item.providerSentAt();
        List<ChatAppBroadcastRecipientEntity> candidates = recipientMapper.findPendingCandidates(
                channelAccountId, item.recipientNumber(), value(row.getTemplateCode()),
                value(row.getLanguageCode()), sentAt, 2);
        if (candidates.size() > 1) {
            return new LinkResult(LinkResult.Kind.AMBIGUOUS_BROADCAST, null,
                    "AMBIGUOUS_BROADCAST_RECIPIENT");
        }
        if (candidates.isEmpty()) {
            return new LinkResult(LinkResult.Kind.ORPHAN, null, "");
        }

        ChatAppBroadcastRecipientEntity candidate = candidates.get(0);
        messageProjector.ensureProcessing(candidate.getBroadcastId(), candidate.getId());
        ReconciliationProjectionResult projection = messageProjector.applyReconciliation(
                candidate.getBroadcastId(), candidate.getId(), item, clock.instant());
        if (!projection.diagnosticCode().isBlank()) {
            return new LinkResult(LinkResult.Kind.PROVIDER_CONFLICT, null,
                    projection.diagnosticCode());
        }
        return new LinkResult(LinkResult.Kind.UNIQUE_BROADCAST, projection.messageId(), "");
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    public record LinkResult(Kind kind, UUID messageId, String reason) {
        public enum Kind {
            EXISTING_PROVIDER,
            EXISTING_CLIENT_REQUEST,
            UNIQUE_BROADCAST,
            AMBIGUOUS_BROADCAST,
            PROVIDER_CONFLICT,
            ORPHAN
        }

        public boolean messageResolved() {
            return messageId != null;
        }
    }
}
