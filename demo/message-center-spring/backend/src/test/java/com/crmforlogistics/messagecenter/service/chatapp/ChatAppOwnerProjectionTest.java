package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAppOwnerProjectionTest {

    @Test
    void resolvesOnlyTheCurrentUsersRequestedAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID ownerId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(ownerId);
        when(mapper.findByIdAndOwner(account.getId(), ownerId)).thenReturn(account);

        ChannelAccountEntity resolved = new ChatAppAccountResolver(mapper)
                .requireOwnedAccount(ownerId, account.getId());

        assertThat(resolved).isSameAs(account);
        verify(mapper).findByIdAndOwner(account.getId(), ownerId);
        verify(mapper, never()).selectById(account.getId());
    }

    @Test
    void rejectsAnotherUsersAccountAsInvisible() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> new ChatAppAccountResolver(mapper)
                .requireOwnedAccount(ownerId, accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");

        verify(mapper).findByIdAndOwner(accountId, ownerId);
        verify(mapper, never()).selectById(accountId);
    }

    @Test
    void resolvesTheCurrentUsersSingleActiveAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID ownerId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(ownerId);
        when(mapper.findByOwnerAndChannelType(ownerId, "chatapp")).thenReturn(List.of(account));

        ChannelAccountEntity resolved = new ChatAppAccountResolver(mapper)
                .currentOwnedAccount(ownerId);

        assertThat(resolved).isSameAs(account);
        verify(mapper).findByOwnerAndChannelType(ownerId, "chatapp");
        verify(mapper, never()).selectActiveChatAppAccounts();
    }

    @Test
    void inboundProjectionCreatesTheContactInsideTheAccountOwnerScope() {
        ProjectionFixture fixture = new ProjectionFixture();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        ChannelAccountEntity firstAccount = activeAccount(firstOwner);
        ChannelAccountEntity secondAccount = activeAccount(secondOwner);
        UUID firstIdentity = UUID.randomUUID();
        UUID secondIdentity = UUID.randomUUID();
        when(fixture.accounts.selectById(firstAccount.getId())).thenReturn(firstAccount);
        when(fixture.accounts.selectById(secondAccount.getId())).thenReturn(secondAccount);
        when(fixture.addressBook.resolveOrCreateInbound(
                firstOwner, "chatapp", firstAccount.getId(), "60123456789", "Alice"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), firstIdentity, true));
        when(fixture.addressBook.resolveOrCreateInbound(
                secondOwner, "chatapp", secondAccount.getId(), "60123456789", "Alice"))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), secondIdentity, true));
        when(fixture.conversations.getOrCreateConversation(firstAccount.getId(), firstIdentity))
                .thenReturn(conversation(firstIdentity));
        when(fixture.conversations.getOrCreateConversation(secondAccount.getId(), secondIdentity))
                .thenReturn(conversation(secondIdentity));

        fixture.projector().project(inboundEvent(firstAccount.getId(), "message-a"));
        fixture.projector().project(inboundEvent(secondAccount.getId(), "message-b"));

        verify(fixture.addressBook).resolveOrCreateInbound(
                firstOwner, "chatapp", firstAccount.getId(), "60123456789", "Alice");
        verify(fixture.addressBook).resolveOrCreateInbound(
                secondOwner, "chatapp", secondAccount.getId(), "60123456789", "Alice");
    }

    @Test
    void inboundProjectionRejectsAnAccountWithoutAnOwnerBeforeWritingBusinessData() {
        ProjectionFixture fixture = new ProjectionFixture();
        ChannelAccountEntity account = activeAccount(null);
        when(fixture.accounts.selectById(account.getId())).thenReturn(account);

        assertThatThrownBy(() -> fixture.projector().project(
                inboundEvent(account.getId(), "message-ownerless")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");

        verifyNoInteractions(fixture.addressBook, fixture.conversations,
                fixture.messageMapper, fixture.statusEvents);
    }

    @Test
    void outgoingContactAuthorizationPassesTheActorToAccountOwnershipCheck() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(actorId);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("60123456789");
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatAppAccountResolver resolver = mock(ChatAppAccountResolver.class);
        when(identities.selectById(identity.getId())).thenReturn(identity);
        when(resolver.requireOwnedAccount(actorId, account.getId())).thenReturn(account);
        when(contacts.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorId))
                .thenReturn(java.util.Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        ChatAppMessageApplicationService service = new ChatAppMessageApplicationService(
                identities, contacts, conversations, mock(MessageSendApplicationService.class),
                mock(ConversationAccessService.class), resolver);

        service.authorizeContactIdentity(contactId, identity.getId(), actorId);

        verify(resolver).requireOwnedAccount(actorId, account.getId());
        verify(resolver, never()).requireCurrentAccount(account.getId());
    }

    private static ChannelAccountEntity activeAccount(UUID ownerId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(ownerId);
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        return account;
    }

    private static ConversationEntity conversation(UUID identityId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        return conversation;
    }

    private static ChannelEventEntity inboundEvent(UUID accountId, String messageId) {
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setPayloadJsonb("{\"MessageId\":\"" + messageId
                + "\",\"From\":\"60123456789\",\"ContactName\":\"Alice\","
                + "\"Message\":\"hello\"}");
        return event;
    }

    private static final class ProjectionFixture {
        private final ChannelEventMapper events = mock(ChannelEventMapper.class);
        private final MessageMapper messageMapper = mock(MessageMapper.class);
        private final MessageStatusEventMapper statusEvents = mock(MessageStatusEventMapper.class);
        private final ConversationMapper conversations = mock(ConversationMapper.class);
        private final EventHub eventHub = mock(EventHub.class);
        private final ChatAppBroadcastRecipientMapper broadcastRecipients =
                mock(ChatAppBroadcastRecipientMapper.class);
        private final ChatAppBroadcastMessageProjector broadcastProjector =
                mock(ChatAppBroadcastMessageProjector.class);
        private final AiTopicActivityRecorder topicActivity = mock(AiTopicActivityRecorder.class);
        private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        private final ChannelAddressBookService addressBook = mock(ChannelAddressBookService.class);

        private ChatAppWebhookProjector projector() {
            return new ChatAppWebhookProjector(
                    events, messageMapper, statusEvents, conversations, eventHub,
                    new ObjectMapper(), broadcastRecipients, broadcastProjector,
                    topicActivity, accounts, addressBook);
        }
    }
}
