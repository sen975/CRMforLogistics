package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppWebhookProjectorTest {
    @Mock ChannelEventMapper channelEventMapper;
    @Mock MessageMapper messageMapper;
    @Mock MessageStatusEventMapper statusEventMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ContactMapper contactMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock EventHub eventHub;
    @Mock ChatAppBroadcastRecipientMapper broadcastRecipientMapper;
    @Mock ChatAppBroadcastMessageProjector broadcastMessageProjector;
    @Mock AiTopicActivityRecorder topicActivityRecorder;

    @Test
    void inboundMessageRecordsTopicActivityAfterMessagePersistence() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-1"))
                .thenReturn(Optional.empty());
        when(contactIdentityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), "60123456789"))
                .thenReturn(Optional.of(identity));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(java.time.Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-1\",\"From\":\"60123456789\",\"Message\":\"hello\"}");

        projector().project(event);

        verify(topicActivityRecorder).recordConversation(
                conversation, java.time.Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void statusWebhookProjectsOntoExistingOutboundMessage() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setCurrentStatus("submitted");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(Optional.of(message));
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setProviderEventId("event-1");
        event.setPayloadJsonb(
                "{\"MessageId\":\"wamid-1\",\"Status\":\"DELIVERED\"}");

        ChatAppWebhookProjector projector = new ChatAppWebhookProjector(
                channelEventMapper, messageMapper, statusEventMapper,
                contactIdentityMapper, contactMapper, conversationMapper,
                eventHub, new ObjectMapper(), broadcastRecipientMapper,
                broadcastMessageProjector);
        var result = projector.project(event);

        assertThat(result.type()).isEqualTo("status");
        assertThat(message.getCurrentStatus()).isEqualTo("delivered");
        verify(messageMapper, never()).updateById(message);
        verify(messageMapper).updateDeliveryStatus(
                eq(message.getId()), any(), eq("delivered"), any());
        verify(statusEventMapper).insertIgnore(any());
        verify(channelEventMapper).markProcessed(any(), any());
    }

    @Test
    void broadcastGroupStatusProjectsByGroupMessageIdAndRecipient() {
        UUID accountId = UUID.randomUUID();
        UUID broadcastId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
        recipient.setId(recipientId);
        recipient.setBroadcastId(broadcastId);
        recipient.setMessageId(messageId);
        recipient.setProviderMessageId("group-1");
        recipient.setProviderUniqueMessageId("unique-1");
        recipient.setStatus("SENT");
        when(messageMapper.findByProviderMessageId(accountId, "group-1"))
                .thenReturn(Optional.empty());
        when(broadcastRecipientMapper.findByGroupMessageIdAndNumber(
                accountId, "group-1", "60123456789", 2))
                .thenReturn(List.of(recipient));
        when(broadcastMessageProjector.applyReconciliation(
                eq(broadcastId), eq(recipientId), any(), any()))
                .thenReturn(new ChatAppBroadcastMessageProjector.ReconciliationProjectionResult(
                        messageId, false, true, ""));

        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setProviderEventId("event-group-read");
        event.setPayloadJsonb(
                "{\"MessageId\":\"group-1\",\"Status\":\"Read\","
                        + "\"From\":\"8613266259485\",\"To\":\"60123456789\"}");

        projector().project(event);

        ArgumentCaptor<ReconciliationItem> item =
                ArgumentCaptor.forClass(ReconciliationItem.class);
        verify(broadcastMessageProjector).applyReconciliation(
                eq(broadcastId), eq(recipientId), item.capture(), any());
        assertThat(item.getValue().providerMessageId()).isEqualTo("group-1");
        assertThat(item.getValue().recipientNumber()).isEqualTo("60123456789");
        assertThat(item.getValue().status()).isEqualTo(RecipientStatus.READ);
        verify(statusEventMapper, never()).insertIgnore(any());
        verify(channelEventMapper).markProcessed(eq(event.getId()), any());
    }

    @Test
    void lateFailureDoesNotRegressReadMessage() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = messageWithStatus("read");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(Optional.of(message));

        projector().project(statusEvent(accountId, "FAILED"));

        assertThat(message.getCurrentStatus()).isEqualTo("read");
        verify(messageMapper, never()).updateById(any(MessageEntity.class));
        verify(statusEventMapper).insertIgnore(any());
    }

    @Test
    void laterDeliveryCanRecoverMessageFromFailedStatus() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = messageWithStatus("failed");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(Optional.of(message));

        projector().project(statusEvent(accountId, "DELIVERED"));

        assertThat(message.getCurrentStatus()).isEqualTo("delivered");
        verify(messageMapper, never()).updateById(message);
        verify(messageMapper).updateDeliveryStatus(
                eq(message.getId()), any(), eq("delivered"), any());
    }

    @Test
    void equalProviderStatusDoesNotIssueAnotherDeliveryUpdate() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = messageWithStatus("delivered");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(Optional.of(message));

        projector().project(statusEvent(accountId, "DELIVERED"));

        verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
        verify(statusEventMapper).insertIgnore(any());
    }

    @Test
    void orphanOutboundStatusCreatesHistoryMessageInRecipientConversation() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "wamid-orphan-1"))
                .thenReturn(Optional.empty());
        when(contactIdentityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), "60123456789"))
                .thenReturn(Optional.of(identity));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setProviderEventId("poll-orphan-1");
        event.setPayloadJsonb("""
                {"MessageId":"wamid-orphan-1","ClientRequestId":"task-orphan-1",
                 "Direction":"outbound","To":"60123456789","Message":"order ready",
                 "MessageKind":"template","Status":"DELIVERED"}
                """);

        var result = projector().project(event);

        assertThat(result.type()).isEqualTo("status");
        ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).insertWithSequence(inserted.capture());
        assertThat(inserted.getValue().getConversationId()).isEqualTo(conversation.getId());
        assertThat(inserted.getValue().getProviderMessageId()).isEqualTo("wamid-orphan-1");
        assertThat(inserted.getValue().getClientRequestId()).isEqualTo("task-orphan-1");
        assertThat(inserted.getValue().getDirection()).isEqualTo("outbound");
        assertThat(inserted.getValue().getMessageKind()).isEqualTo("template");
        assertThat(inserted.getValue().getBodyText()).isEqualTo("order ready");
        assertThat(inserted.getValue().getCurrentStatus()).isEqualTo("delivered");
        assertThat(inserted.getValue().getCountsAsUnread()).isFalse();
        verify(statusEventMapper).insertIgnore(any());
        verify(channelEventMapper).markProcessed(eq(event.getId()), any());
    }

    private ChatAppWebhookProjector projector() {
        return new ChatAppWebhookProjector(
                channelEventMapper, messageMapper, statusEventMapper,
                contactIdentityMapper, contactMapper, conversationMapper,
                eventHub, new ObjectMapper(), broadcastRecipientMapper,
                broadcastMessageProjector, topicActivityRecorder);
    }

    private static MessageEntity messageWithStatus(String status) {
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setCurrentStatus(status);
        return message;
    }

    private static ChannelEventEntity statusEvent(UUID accountId, String status) {
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setProviderEventId("event-1");
        event.setPayloadJsonb("{\"MessageId\":\"wamid-1\",\"Status\":\"" + status + "\"}");
        return event;
    }
}
