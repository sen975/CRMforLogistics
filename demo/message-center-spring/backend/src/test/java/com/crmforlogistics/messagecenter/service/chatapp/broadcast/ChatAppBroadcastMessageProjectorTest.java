package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ChatAppBroadcastMessageProjectorTest {

    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");

    @Mock ChatAppBroadcastMapper broadcastMapper;
    @Mock ChatAppBroadcastRecipientMapper recipientMapper;
    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock MessageStatusEventMapper statusEventMapper;
    @Mock TemplateMapper templateMapper;
    @Mock ChannelAccountMapper accountMapper;
    @Mock EventHub eventHub;
    @Mock AiTopicActivityRecorder topicActivityRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID broadcastId;
    private UUID recipientId;
    private UUID conversationId;
    private UUID accountId;
    private ChatAppBroadcastEntity broadcast;
    private ChatAppBroadcastRecipientEntity recipient;
    private ChatAppBroadcastMessageProjector projector;

    @BeforeEach
    void setUp() {
        broadcastId = UUID.randomUUID();
        recipientId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        broadcast = new ChatAppBroadcastEntity();
        broadcast.setId(broadcastId);
        broadcast.setChannelAccountId(accountId);
        broadcast.setTemplateCode("shipping_notice");
        broadcast.setTemplateName("Shipping Notice");
        broadcast.setTemplateBodySnapshot("订单 $(order) 已发货");
        broadcast.setLanguageCode("zh_CN");
        broadcast.setProviderGroupMessageId("group-1");
        broadcast.setCreatedByUserId(UUID.randomUUID());
        broadcast.setSubmittedAt(NOW);

        recipient = new ChatAppBroadcastRecipientEntity();
        recipient.setId(recipientId);
        recipient.setBroadcastId(broadcastId);
        recipient.setContactIdentityId(UUID.randomUUID());
        recipient.setTemplateParamsJsonb("{\"order\":\"SO-1\"}");
        recipient.setStatus("PROCESSING");

        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);

        when(recipientMapper.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
        when(broadcastMapper.selectById(broadcastId)).thenReturn(broadcast);
        lenient().when(messageMapper.findByClientRequestId(
                broadcast.getChannelAccountId(),
                "broadcast:" + broadcastId + ":recipient:" + recipientId))
                .thenReturn(Optional.empty());
        lenient().when(conversationMapper.getOrCreateConversationForSender(
                broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
                broadcast.getCreatedByUserId())).thenReturn(conversation);
        lenient().when(messageMapper.insertWithSequence(any())).thenReturn(1);
        lenient().when(messageMapper.updateDeliveryStatus(any(), any(), any(), any())).thenReturn(1);
        lenient().when(recipientMapper.linkMessageIfAbsent(any(), any(), any())).thenAnswer(invocation -> {
            recipient.setMessageId(invocation.getArgument(1));
            return 1;
        });

        projector = new ChatAppBroadcastMessageProjector(
                broadcastMapper, recipientMapper, messageMapper, conversationMapper,
                statusEventMapper,
                new TemplateMessageTextResolver(templateMapper, accountMapper, objectMapper),
                objectMapper, eventHub, Clock.fixed(NOW, ZoneOffset.UTC), topicActivityRecorder);
    }

    @Test
    void createsOneProcessingTemplateMessageAndLinksRecipient() throws Exception {
        ChatAppBroadcastMessageProjector.ProjectionResult first =
                projector.ensureProcessing(broadcastId, recipientId);
        MessageEntity persisted = message(first.messageId());
        persisted.setChannelAccountId(accountId);
        persisted.setConversationId(conversationId);
        when(messageMapper.selectById(first.messageId())).thenReturn(persisted);
        ChatAppBroadcastMessageProjector.ProjectionResult second =
                projector.ensureProcessing(broadcastId, recipientId);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.messageId()).isEqualTo(first.messageId());
        ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper, times(1)).insertWithSequence(inserted.capture());
        assertThat(inserted.getValue().getBodyText()).isEqualTo("订单 SO-1 已发货");
        assertThat(inserted.getValue().getCountsAsUnread()).isFalse();
        assertThat(inserted.getValue().getCurrentStatus()).isEqualTo("processing");
        assertThat(inserted.getValue().getDirection()).isEqualTo("outbound");
        assertThat(inserted.getValue().getMessageKind()).isEqualTo("template");
        JsonNode metadata = objectMapper.readTree(inserted.getValue().getMetadataJsonb());
        assertThat(metadata.path("broadcastId").asText()).isEqualTo(broadcastId.toString());
        assertThat(metadata.path("broadcastRecipientId").asText()).isEqualTo(recipientId.toString());
        assertThat(metadata.path("providerGroupMessageId").asText()).isEqualTo("group-1");
        assertThat(metadata.path("templateCode").asText()).isEqualTo("shipping_notice");
        assertThat(metadata.path("languageCode").asText()).isEqualTo("zh_CN");
        assertThat(metadata.path("templateParams").path("order").asText()).isEqualTo("SO-1");
        verify(statusEventMapper, times(1)).insertIgnore(argThat(
                event -> "processing".equals(event.getStatus())
                        && ("broadcast:" + broadcastId + ":recipient:" + recipientId + ":processing")
                        .equals(event.getProviderEventId())));
        verify(conversationMapper).recomputeProjection(conversationId);
        verify(topicActivityRecorder).recordConversation(any(ConversationEntity.class), eq(NOW));
        verify(eventHub, times(1)).publish("message-new", "{}");
    }

    @Test
    void reusesMessageFoundByStableClientRequestIdWithoutCreatingAnother() {
        UUID existingId = UUID.randomUUID();
        MessageEntity existing = message(existingId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        recipient.setMessageId(null);
        when(messageMapper.findByClientRequestId(any(), any())).thenReturn(Optional.of(existing));

        ChatAppBroadcastMessageProjector.ProjectionResult result =
                projector.ensureProcessing(broadcastId, recipientId);

        assertThat(result).isEqualTo(new ChatAppBroadcastMessageProjector.ProjectionResult(
                existingId, false, "processing"));
        verify(recipientMapper).linkMessageIfAbsent(recipientId, existingId, NOW);
        verify(messageMapper, never()).insertWithSequence(any());
        verify(eventHub, never()).publish(any(), any());
    }

    @Test
    void existingRecipientLinkMustBelongToExpectedOutboundScope() {
        UUID existingId = UUID.randomUUID();
        MessageEntity existing = message(existingId);
        existing.setChannelAccountId(UUID.randomUUID());
        existing.setConversationId(conversationId);
        recipient.setMessageId(existingId);
        when(messageMapper.selectById(existingId)).thenReturn(existing);

        assertThatThrownBy(() -> projector.ensureProcessing(broadcastId, recipientId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
    }

    @Test
    void missingSubmittedAtStopsHistoricalProjectionInsteadOfUsingCurrentTime() {
        broadcast.setSubmittedAt(null);

        assertThatThrownBy(() -> projector.ensureProcessing(broadcastId, recipientId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_BROADCAST_SUBMITTED_AT_MISSING");
        verify(messageMapper, never()).insertWithSequence(any());
    }

    @Test
    void reconciliationUpdatesLinkedProcessingMessage() {
        UUID localId = UUID.randomUUID();
        MessageEntity local = message(localId);
        local.setChannelAccountId(accountId);
        local.setConversationId(conversationId);
        recipient.setMessageId(localId);
        when(messageMapper.selectById(localId)).thenReturn(local);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1")).thenReturn(List.of());

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result).isEqualTo(new ChatAppBroadcastMessageProjector.ReconciliationProjectionResult(
                localId, false, true, ""));
        verify(messageMapper).updateDeliveryStatus(localId, "unique-1", "delivered", NOW);
        verify(recipientMapper).updateProviderStatus(
                recipientId, "wamid-1", "unique-1", "DELIVERED", "", NOW, NOW);
        ArgumentCaptor<MessageStatusEventEntity> statusEvent =
                ArgumentCaptor.forClass(MessageStatusEventEntity.class);
        verify(statusEventMapper).insertIgnore(statusEvent.capture());
        assertThat(statusEvent.getValue().getProviderEventId())
                .contains(":delivered:")
                .hasSizeLessThanOrEqualTo(255);
    }

    @Test
    void reconciliationRepairsLegacyGroupBindingBeforeApplyingReadStatus() {
        UUID localId = UUID.randomUUID();
        MessageEntity local = message(localId);
        local.setChannelAccountId(accountId);
        local.setConversationId(conversationId);
        local.setProviderMessageId("group-1");
        recipient.setMessageId(localId);
        recipient.setProviderMessageId("group-1");
        when(messageMapper.selectById(localId)).thenReturn(local);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1")).thenReturn(List.of());
        when(messageMapper.replaceProviderMessageId(localId, "group-1", "unique-1")).thenReturn(1);

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("group-1", ChatAppBroadcastModels.RecipientStatus.READ), NOW);

        assertThat(result.diagnosticCode()).isBlank();
        verify(messageMapper).replaceProviderMessageId(localId, "group-1", "unique-1");
        verify(messageMapper).updateDeliveryStatus(localId, "unique-1", "read", NOW);
        verify(recipientMapper).updateProviderStatus(
                recipientId, "group-1", "unique-1", "READ", "", NOW, NOW);
    }

    @Test
    void reconciliationReusesScopedProviderHistoryWhenLocalProjectionIsMissing() {
        UUID historyId = UUID.randomUUID();
        MessageEntity history = message(historyId);
        history.setChannelAccountId(accountId);
        history.setConversationId(conversationId);
        history.setProviderMessageId("unique-1");
        recipient.setMessageId(null);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(history));

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.READ), NOW);

        assertThat(result.messageId()).isEqualTo(historyId);
        assertThat(result.diagnosticCode()).isBlank();
        verify(recipientMapper).linkMessageIfAbsent(recipientId, historyId, NOW);
        verify(messageMapper).updateDeliveryStatus(historyId, "unique-1", "read", NOW);
        verify(messageMapper, never()).insertWithSequence(any());
    }

    @Test
    void reconciliationUsesSameMessageWhenLocalAndProviderLinksAgree() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        existing.setProviderMessageId("unique-1");
        recipient.setMessageId(messageId);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(existing));

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.updated()).isTrue();
        verify(messageMapper).updateDeliveryStatus(messageId, "unique-1", "delivered", NOW);
        verify(recipientMapper, never()).linkMessageIfAbsent(any(), any(), any());
    }

    @Test
    void reconciliationStopsWhenProviderIdBelongsToAnotherMessage() {
        UUID localId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        MessageEntity local = message(localId);
        local.setChannelAccountId(accountId);
        local.setConversationId(conversationId);
        MessageEntity providerHistory = message(providerId);
        providerHistory.setChannelAccountId(accountId);
        providerHistory.setConversationId(conversationId);
        providerHistory.setProviderMessageId("unique-1");
        recipient.setMessageId(localId);
        when(messageMapper.selectById(localId)).thenReturn(local);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(providerHistory));

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result.diagnosticCode()).isEqualTo("CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
        verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconciliationDoesNotRegressRecipientOrTouchTimestampsOnReplay() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        existing.setProviderMessageId("unique-1");
        existing.setCurrentStatus("read");
        existing.setDirection("outbound");
        recipient.setMessageId(messageId);
        recipient.setProviderMessageId("wamid-1");
        recipient.setProviderUniqueMessageId("unique-1");
        recipient.setStatus("READ");
        recipient.setFailureReason("");
        recipient.setProviderSentAt(NOW);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(existing));

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result.updated()).isFalse();
        verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
        verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
        verify(statusEventMapper, never()).insertIgnore(any());
    }

    @Test
    void rejectedStaleMessageUpdateUsesPersistedStatusForRecipient() {
        UUID messageId = UUID.randomUUID();
        MessageEntity stale = message(messageId);
        stale.setChannelAccountId(accountId);
        stale.setConversationId(conversationId);
        stale.setProviderMessageId("unique-1");
        MessageEntity persisted = message(messageId);
        persisted.setChannelAccountId(accountId);
        persisted.setConversationId(conversationId);
        persisted.setProviderMessageId("unique-1");
        persisted.setCurrentStatus("read");
        recipient.setMessageId(messageId);
        recipient.setProviderMessageId("wamid-1");
        when(messageMapper.selectById(messageId)).thenReturn(stale, persisted);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(stale));
        when(messageMapper.updateDeliveryStatus(messageId, "unique-1", "failed", NOW))
                .thenReturn(0);
        ChatAppBroadcastGateway.ReconciliationItem failed =
                new ChatAppBroadcastGateway.ReconciliationItem(
                        1, "60111111111", "wamid-1", "unique-1",
                        ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT,
                        "FAILED", "blocked", NOW, "");

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId, failed, NOW);

        assertThat(result.diagnosticCode()).isBlank();
        verify(recipientMapper).updateProviderStatus(
                recipientId, "wamid-1", "unique-1", "READ", "", NOW, NOW);
        verify(statusEventMapper, never()).insertIgnore(any());
    }

    @Test
    void rejectedStatusUpdateStopsOnConcurrentProviderBindingConflict() {
        UUID messageId = UUID.randomUUID();
        MessageEntity stale = message(messageId);
        stale.setChannelAccountId(accountId);
        stale.setConversationId(conversationId);
        MessageEntity concurrentlyBound = message(messageId);
        concurrentlyBound.setChannelAccountId(accountId);
        concurrentlyBound.setConversationId(conversationId);
        concurrentlyBound.setProviderMessageId("unique-other");
        recipient.setMessageId(messageId);
        when(messageMapper.selectById(messageId)).thenReturn(stale, concurrentlyBound);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of());
        when(messageMapper.updateDeliveryStatus(messageId, "unique-1", "delivered", NOW))
                .thenReturn(0);

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-new", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result.diagnosticCode()).isEqualTo("CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        verify(recipientMapper, never()).updateProviderStatus(
                any(), any(), any(), any(), any(), any(), any());
        verify(statusEventMapper, never()).insertIgnore(any());
    }

    @Test
    void reconciliationStopsWhenLinkedMessageIsOutsideExpectedScope() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(UUID.randomUUID());
        existing.setConversationId(conversationId);
        existing.setDirection("outbound");
        recipient.setMessageId(messageId);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1")).thenReturn(List.of());

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId,
                        item("wamid-1", ChatAppBroadcastModels.RecipientStatus.DELIVERED), NOW);

        assertThat(result.diagnosticCode()).isEqualTo("CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
        verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
        verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconciliationBoundsProviderIdentifiersBeforeQueryAndPersistence() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        recipient.setMessageId(messageId);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        String longMessageId = "m".repeat(300);
        String longUniqueId = "u".repeat(300);
        String boundedMessageId = longMessageId.substring(0, 255);
        String boundedUniqueId = longUniqueId.substring(0, 255);
        when(messageMapper.findAllByProviderMessageId(accountId, boundedUniqueId))
                .thenReturn(List.of());
        ChatAppBroadcastGateway.ReconciliationItem item =
                new ChatAppBroadcastGateway.ReconciliationItem(
                        1, "60111111111", longMessageId, longUniqueId,
                        ChatAppBroadcastModels.RecipientStatus.DELIVERED,
                        "DELIVERED", "", NOW, "");

        projector.applyReconciliation(broadcastId, recipientId, item, NOW);

        verify(messageMapper).findAllByProviderMessageId(accountId, boundedUniqueId);
        verify(messageMapper).updateDeliveryStatus(messageId, boundedUniqueId, "delivered", NOW);
        verify(recipientMapper).updateProviderStatus(
                recipientId, boundedMessageId, boundedUniqueId,
                "DELIVERED", "", NOW, NOW);
    }

    @Test
    void reconciliationNeverClearsExistingProviderIdentifiersWhenRowOmitsThem() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        existing.setProviderMessageId("wamid-existing");
        recipient.setMessageId(messageId);
        recipient.setProviderMessageId("wamid-existing");
        recipient.setProviderUniqueMessageId("unique-existing");
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        ChatAppBroadcastGateway.ReconciliationItem missingIds =
                new ChatAppBroadcastGateway.ReconciliationItem(
                        1, "60111111111", "", "",
                        ChatAppBroadcastModels.RecipientStatus.DELIVERED,
                        "DELIVERED", "", NOW,
                        "CHATAPP_BROADCAST_PROVIDER_MESSAGE_ID_MISSING");

        projector.applyReconciliation(broadcastId, recipientId, missingIds, NOW);

        verify(messageMapper).updateDeliveryStatus(
                messageId, "wamid-existing", "delivered", NOW);
        verify(recipientMapper).updateProviderStatus(
                recipientId, "wamid-existing", "unique-existing",
                "DELIVERED", "", NOW, NOW);
    }

    @Test
    void reconciliationSanitizesProviderFailureBeforePersistence() {
        UUID messageId = UUID.randomUUID();
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        recipient.setMessageId(messageId);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(existing));
        ChatAppBroadcastGateway.ReconciliationItem failed =
                new ChatAppBroadcastGateway.ReconciliationItem(
                        1, "60111111111", "wamid-1", "unique-1",
                        ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT,
                        "FAILED", "AccessKeySecret=topsecret recipient 60111111111", NOW, "");

        projector.applyReconciliation(broadcastId, recipientId, failed, NOW);

        verify(recipientMapper).updateProviderStatus(
                recipientId, "wamid-1", "unique-1", "FAILED_RECIPIENT",
                "AccessKeySecret=[REDACTED] recipient [REDACTED]", NOW, NOW);
        ArgumentCaptor<MessageStatusEventEntity> event =
                ArgumentCaptor.forClass(MessageStatusEventEntity.class);
        verify(statusEventMapper).insertIgnore(event.capture());
        assertThat(event.getValue().getReasonMessage())
                .isEqualTo("AccessKeySecret=[REDACTED] recipient [REDACTED]");
    }

    @Test
    void sparseFailedReplayPreservesKnownFailureReasonAndProviderTime() {
        UUID messageId = UUID.randomUUID();
        Instant providerSentAt = NOW.minusSeconds(60);
        MessageEntity existing = message(messageId);
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        existing.setProviderMessageId("unique-1");
        existing.setCurrentStatus("failed");
        recipient.setMessageId(messageId);
        recipient.setProviderMessageId("wamid-1");
        recipient.setProviderUniqueMessageId("unique-1");
        recipient.setStatus("FAILED_RECIPIENT");
        recipient.setFailureReason("blocked by provider");
        recipient.setProviderSentAt(providerSentAt);
        when(messageMapper.selectById(messageId)).thenReturn(existing);
        when(messageMapper.findAllByProviderMessageId(accountId, "unique-1"))
                .thenReturn(List.of(existing));
        ChatAppBroadcastGateway.ReconciliationItem sparse =
                new ChatAppBroadcastGateway.ReconciliationItem(
                        1, "60111111111", "wamid-1", "unique-1",
                        ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT,
                        "FAILED", "", null, "");

        ChatAppBroadcastMessageProjector.ReconciliationProjectionResult result =
                projector.applyReconciliation(broadcastId, recipientId, sparse, NOW);

        assertThat(result.updated()).isFalse();
        verify(recipientMapper, never()).updateProviderStatus(
                any(), any(), any(), any(), any(), any(), any());
    }

    private static ChatAppBroadcastGateway.ReconciliationItem item(
            String providerMessageId, ChatAppBroadcastModels.RecipientStatus status) {
        return new ChatAppBroadcastGateway.ReconciliationItem(
                1, "60111111111", providerMessageId, "unique-1", status,
                status.name(), "", NOW, "");
    }

    private MessageEntity message(UUID id) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setCurrentStatus("processing");
        message.setDirection("outbound");
        return message;
    }
}
