package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
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
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAppContactProjectionTest {
    private final ChannelEventMapper channelEventMapper = mock(ChannelEventMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final MessageStatusEventMapper statusEventMapper = mock(MessageStatusEventMapper.class);
    private final ContactIdentityMapper contactIdentityMapper = mock(ContactIdentityMapper.class);
    private final ContactMapper contactMapper = mock(ContactMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final EventHub eventHub = mock(EventHub.class);
    private final ChatAppBroadcastRecipientMapper broadcastRecipientMapper =
            mock(ChatAppBroadcastRecipientMapper.class);
    private final ChatAppBroadcastMessageProjector broadcastMessageProjector =
            mock(ChatAppBroadcastMessageProjector.class);
    private final ChannelAccountMapper channelAccountMapper = mock(ChannelAccountMapper.class);
    private final ChannelAddressBookService addressBookService = mock(ChannelAddressBookService.class);

    @Test
    void usesExistingIdentityOnlyWithinCurrentChatAppAccountScope() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity existing = new ContactIdentityEntity();
        existing.setId(UUID.randomUUID());
        existing.setDisplayName("人工维护名称");
        ConversationEntity conversation = conversation(existing.getId());
        UUID ownerId = stubOwnedAccount(accountId);
        when(addressBookService.resolveOrCreateInbound(
                ownerId, "chatapp", accountId, "60123456789", "60123456789"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), existing.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, existing.getId()))
                .thenReturn(conversation);

        projector().project(inboundEvent(accountId, "wamid-existing"));

        verify(addressBookService).resolveOrCreateInbound(
                ownerId, "chatapp", accountId, "60123456789", "60123456789");
        verify(contactIdentityMapper, never()).insert(any(ContactIdentityEntity.class));
        verify(contactMapper, never()).insert(any(ContactEntity.class));
        assertThat(existing.getDisplayName()).isEqualTo("人工维护名称");
    }

    @Test
    void createsSeparateIdentitiesForSameNumberInDifferentChatAppAccounts() {
        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();
        UUID firstOwner = stubOwnedAccount(firstAccountId);
        UUID secondOwner = stubOwnedAccount(secondAccountId);
        UUID firstIdentity = UUID.randomUUID();
        UUID secondIdentity = UUID.randomUUID();
        when(addressBookService.resolveOrCreateInbound(
                firstOwner, "chatapp", firstAccountId, "60123456789", "60123456789"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), firstIdentity, true));
        when(addressBookService.resolveOrCreateInbound(
                secondOwner, "chatapp", secondAccountId, "60123456789", "60123456789"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), secondIdentity, true));
        when(conversationMapper.getOrCreateConversation(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> conversation(invocation.getArgument(1, UUID.class)));

        projector().project(inboundEvent(firstAccountId, "wamid-first"));
        projector().project(inboundEvent(secondAccountId, "wamid-second"));

        verify(addressBookService).resolveOrCreateInbound(
                firstOwner, "chatapp", firstAccountId, "60123456789", "60123456789");
        verify(addressBookService).resolveOrCreateInbound(
                secondOwner, "chatapp", secondAccountId, "60123456789", "60123456789");
    }

    @Test
    void duplicateMessageDoesNotCreateAnotherIdentity() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = stubOwnedAccount(accountId);
        when(messageMapper.findByProviderMessageId(accountId, "wamid-duplicate"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new MessageEntity()));
        UUID identityId = UUID.randomUUID();
        when(addressBookService.resolveOrCreateInbound(
                ownerId, "chatapp", accountId, "60123456789", "60123456789"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identityId, true));
        when(conversationMapper.getOrCreateConversation(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> conversation(invocation.getArgument(1, UUID.class)));

        projector().project(inboundEvent(accountId, "wamid-duplicate"));
        var duplicate = projector().project(inboundEvent(accountId, "wamid-duplicate"));

        assertThat(duplicate.duplicate()).isTrue();
        verify(addressBookService).resolveOrCreateInbound(
                ownerId, "chatapp", accountId, "60123456789", "60123456789");
    }

    private ChatAppWebhookProjector projector() {
        return new ChatAppWebhookProjector(
                channelEventMapper, messageMapper, statusEventMapper,
                conversationMapper, eventHub, new ObjectMapper(), broadcastRecipientMapper,
                broadcastMessageProjector, null, channelAccountMapper, addressBookService);
    }

    private UUID stubOwnedAccount(UUID accountId) {
        UUID ownerId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setOwnerUserId(ownerId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);
        return ownerId;
    }

    private static ChannelEventEntity inboundEvent(UUID accountId, String providerMessageId) {
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setProviderEventId("event-" + providerMessageId);
        event.setPayloadJsonb("{\"MessageId\":\"" + providerMessageId
                + "\",\"From\":\"60123456789\",\"Message\":\"hello\"}");
        return event;
    }

    private static ConversationEntity conversation(UUID identityId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        return conversation;
    }
}
