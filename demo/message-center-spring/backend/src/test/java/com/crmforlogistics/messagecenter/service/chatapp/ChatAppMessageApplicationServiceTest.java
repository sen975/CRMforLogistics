package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppMessageApplicationServiceTest {
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ContactMapper contactMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock MessageSendApplicationService sendService;
    @Mock ConversationAccessService conversationAccessService;
    @Mock AppConfig appConfig;

    @Test
    void currentContactIdentityResolvesFixedAccountAndConversationBeforeAccepting() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("+60 123-456-789");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setChannelAccountId(account.getId());

        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        when(contactMapper.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorId))
                .thenReturn(Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(conversationMapper.getOrCreateConversationForSender(
                account.getId(), identity.getId(), actorId))
                .thenReturn(conversation);
        when(sendService.accept(any(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        UUID.randomUUID(), "pending", false));

        ChatAppMessageApplicationService service = service();
        service.acceptContactIdentity(contactId, identity.getId(), "text", "request-1",
                Map.of("text", "hello"), actorId);

        ArgumentCaptor<MessageSendApplicationService.SendMessageCommand> command =
                ArgumentCaptor.forClass(MessageSendApplicationService.SendMessageCommand.class);
        verify(sendService).accept(command.capture(), eq(actorId));
        assertThat(command.getValue().channelAccountId()).isEqualTo(account.getId());
        assertThat(command.getValue().conversationId()).isEqualTo(conversation.getId());
        assertThat(command.getValue().content())
                .containsEntry("to", "60123456789")
                .containsEntry("text", "hello");
        verify(conversationAccessService).requireAccessible(
                conversation.getId(), account.getId(), actorId);
    }

    @Test
    void firstSendClaimsUnassignedTargetConversationForAuthorizedContact() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("60123456789");
        ConversationEntity conversation = conversation(account.getId());

        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactMapper.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorId))
                .thenReturn(Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));
        when(conversationMapper.getOrCreateConversationForSender(
                account.getId(), identity.getId(), actorId)).thenReturn(conversation);
        when(sendService.accept(any(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        UUID.randomUUID(), "pending", false));

        service().acceptContactIdentity(contactId, identity.getId(), "text", "request-1",
                Map.of("text", "hello"), actorId);

        verify(conversationMapper).getOrCreateConversationForSender(
                account.getId(), identity.getId(), actorId);
        verify(conversationAccessService).requireAccessible(
                conversation.getId(), account.getId(), actorId);
    }

    @Test
    void authorizationCheckDoesNotCreateOrClaimAConversation() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("60123456789");
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactMapper.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorId))
                .thenReturn(Optional.of(new com.crmforlogistics.messagecenter.entity.ContactEntity()));

        service().authorizeContactIdentity(contactId, identity.getId(), actorId);

        verify(conversationMapper, never()).getOrCreateConversationForSender(any(), any(), any());
        verifyNoInteractions(conversationAccessService, sendService);
    }

    @Test
    void rejectsChatAppIdentityOwnedByAnotherContact() {
        UUID currentContactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(UUID.randomUUID());
        identity.setChannelType("chatapp");
        identity.setIdentityValue("60123456789");
        when(contactIdentityMapper.selectById(identityId)).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptContactIdentity(
                currentContactId, identityId, "text", "request-1",
                Map.of("text", "hello"), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_IDENTITY_MISMATCH");
        verifyNoInteractions(channelAccountMapper, conversationMapper, sendService,
                conversationAccessService, appConfig);
    }

    @Test
    void rejectsDeletedChatAppIdentityBeforeResolvingAccountOrConversation() {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityValue("60123456789");
        identity.setDeletedAt(Instant.now());
        when(contactIdentityMapper.selectById(identityId)).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptContactIdentity(
                contactId, identityId, "text", "request-1",
                Map.of("text", "hello"), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_IDENTITY_NOT_FOUND");
        verifyNoInteractions(channelAccountMapper, conversationMapper, sendService,
                conversationAccessService, appConfig);
    }

    @Test
    void rejectsNonChatAppIdentityBeforeResolvingAccountOrConversation() {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("email");
        identity.setIdentityValue("customer@example.com");
        when(contactIdentityMapper.selectById(identityId)).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptContactIdentity(
                contactId, identityId, "text", "request-1",
                Map.of("text", "hello"), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_IDENTITY_CHANNEL_INVALID");
        verifyNoInteractions(channelAccountMapper, conversationMapper, sendService,
                conversationAccessService, appConfig);
    }

    @Test
    void rejectsChatAppIdentityFromAnotherActiveAccount() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID activeAccountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(activeAccountId);
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(UUID.randomUUID().toString());
        identity.setIdentityValue("60123456789");

        when(contactIdentityMapper.selectById(identityId)).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptContactIdentity(
                contactId, identityId, "text", "request-1", Map.of("text", "hello"), actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        verifyNoInteractions(conversationMapper, sendService, conversationAccessService);
    }

    @Test
    void rejectsInaccessibleContactBeforeCreatingConversation() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("60123456789");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());

        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        when(contactMapper.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorId)).thenReturn(Optional.empty());
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptContactIdentity(
                contactId, identity.getId(), "text", "request-1",
                Map.of("text", "hello"), actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CHATAPP_CONVERSATION_FORBIDDEN");
        verify(conversationMapper, never())
                .getOrCreateConversationForSender(account.getId(), identity.getId(), actorId);
        verifyNoInteractions(sendService);
    }

    @Test
    void rejectsConversationWithNonChatAppIdentity() {
        UUID actorId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount();
        ConversationEntity conversation = conversation(account.getId());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(conversation.getContactIdentityId());
        identity.setChannelType("email");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("customer@example.com");

        when(conversationMapper.selectById(conversation.getId())).thenReturn(conversation);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptConversation(
                conversation.getId(), "text", "request-1", Map.of("text", "hello"), actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_IDENTITY_CHANNEL_INVALID");
        verifyNoInteractions(sendService, conversationAccessService);
    }

    @Test
    void rejectsConversationWithIdentityFromAnotherAccount() {
        UUID actorId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount();
        ConversationEntity conversation = conversation(account.getId());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(conversation.getContactIdentityId());
        identity.setChannelType("chatapp");
        identity.setIdentityScope(UUID.randomUUID().toString());
        identity.setIdentityValue("60123456789");

        when(conversationMapper.selectById(conversation.getId())).thenReturn(conversation);
        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptConversation(
                conversation.getId(), "text", "request-1", Map.of("text", "hello"), actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        verifyNoInteractions(sendService, conversationAccessService);
    }

    @Test
    void rejectsConversationNotAccessibleToActor() {
        UUID actorId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setChannelAccountId(account.getId());
        conversation.setContactIdentityId(UUID.randomUUID());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(conversation.getContactIdentityId());
        identity.setChannelType("chatapp");
        identity.setIdentityScope(account.getId().toString());
        identity.setIdentityValue("60123456789");
        when(conversationMapper.selectById(conversation.getId())).thenReturn(conversation);
        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        org.mockito.Mockito.doThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"))
                .when(conversationAccessService)
                .requireAccessible(conversation.getId(), account.getId(), actorId);
        ChatAppMessageApplicationService service = service();

        assertThatThrownBy(() -> service.acceptConversation(
                conversation.getId(), "text", "request-1", Map.of("text", "hello"), actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CHATAPP_CONVERSATION_FORBIDDEN");
        org.mockito.Mockito.verifyNoInteractions(sendService);
    }

    private static ChannelAccountEntity activeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        return account;
    }

    private static ConversationEntity conversation(UUID accountId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setChannelAccountId(accountId);
        conversation.setContactIdentityId(UUID.randomUUID());
        return conversation;
    }

    private ChatAppMessageApplicationService service() {
        return new ChatAppMessageApplicationService(
                contactIdentityMapper, contactMapper, conversationMapper, sendService,
                conversationAccessService, new ChatAppAccountResolver(channelAccountMapper, appConfig));
    }
}
