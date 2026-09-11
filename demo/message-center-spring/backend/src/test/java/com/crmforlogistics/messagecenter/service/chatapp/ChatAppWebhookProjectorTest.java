package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
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
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryTriggerService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ChannelAddressBookService addressBookService;
    @Mock ContactMemoryTriggerService contactMemoryTriggerService;
    @Mock ObjectProvider<WeComUserNotificationService> notificationProvider;
    @Mock WeComUserNotificationService notificationService;

    @BeforeEach
    void allowOwnedActiveAccounts() {
        lenient().when(channelAccountMapper.selectById(any(UUID.class))).thenAnswer(invocation -> {
            ChannelAccountEntity account = new ChannelAccountEntity();
            account.setId(invocation.getArgument(0, UUID.class));
            account.setOwnerUserId(UUID.randomUUID());
            account.setChannelType("chatapp");
            account.setAuthStatus("active");
            return account;
        });
    }

    @Test
    void inboundMessageRecordsTopicActivityAfterMessagePersistence() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-1"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
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
    void inboundMessageTriggersContactMemoryAfterMessagePersistence() {
        UUID accountId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-memory-1"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        contactId, identity.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-memory-1\",\"From\":\"60123456789\","
                + "\"Message\":\"hello\"}");

        projector().project(event);

        verify(contactMemoryTriggerService).markInboundPersisted(
                eq(contactId), eq(Instant.parse("2026-09-01T08:00:00Z")));
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

        var result = projector().project(event);

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
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
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

    @Test
    void inboundProjectionEnqueuesAWeComNotificationForTheAssignee() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setAssignedUserId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-9"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        when(notificationProvider.getIfAvailable()).thenReturn(notificationService);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-9\",\"From\":\"60123456789\","
                + "\"Message\":\"hello\",\"ContactName\":\"张三\"}");

        projector().project(event);

        ArgumentCaptor<WeComUserNotificationService.InboundMessage> captor =
                ArgumentCaptor.forClass(WeComUserNotificationService.InboundMessage.class);
        verify(notificationService).enqueueInbound(captor.capture());
        assertThat(captor.getValue().conversationId()).isEqualTo(conversation.getId());
        assertThat(captor.getValue().channelAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().channelType()).isEqualTo("chatapp");
        assertThat(captor.getValue().contactLabel()).isEqualTo("张三");
        assertThat(captor.getValue().bodyText()).isEqualTo("hello");
        assertThat(captor.getValue().occurredAt())
                .isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void inboundProjectionSucceedsWhenWeComNotificationsAreNotEnabled() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-10"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-10\",\"From\":\"60123456789\","
                + "\"Message\":\"hello\"}");

        var result = projector().project(event);

        assertThat(result.type()).isEqualTo("message");
        verify(notificationService, never()).enqueueInbound(any());
        verify(messageMapper).insertWithSequence(any(MessageEntity.class));
    }

    private ChatAppWebhookProjector projector() {
        return new ChatAppWebhookProjector(
                channelEventMapper, messageMapper, statusEventMapper,
                conversationMapper, eventHub, new ObjectMapper(), broadcastRecipientMapper,
                broadcastMessageProjector, topicActivityRecorder, channelAccountMapper,
                addressBookService, notificationProvider, contactMemoryTriggerService);
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
