package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppMessageApplicationServiceTest {
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock MessageSendApplicationService sendService;
    @Mock ConversationAccessService conversationAccessService;
    @Mock AppConfig appConfig;

    @Test
    void legacyRecipientSendResolvesFixedAccountAndConversationBeforeAccepting() {
        UUID actorId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier("60111111111");
        account.setAuthStatus("active");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setChannelAccountId(account.getId());

        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(contactIdentityMapper.findByNormalizedValue(
                "chatapp", ContactPointUtil.normalizePhone("+60 123-456-789")))
                .thenReturn(Optional.of(identity));
        when(conversationMapper.getOrCreateConversation(account.getId(), identity.getId()))
                .thenReturn(conversation);
        when(appConfig.chatappFrom()).thenReturn("60111111111");
        when(sendService.accept(any(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        UUID.randomUUID(), "pending", false));

        ChatAppMessageApplicationService service = new ChatAppMessageApplicationService(
                channelAccountMapper, contactIdentityMapper, conversationMapper, sendService,
                conversationAccessService, appConfig);
        service.acceptRecipient("+60 123-456-789", "text", "request-1",
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
        identity.setIdentityValue("60123456789");
        when(conversationMapper.selectById(conversation.getId())).thenReturn(conversation);
        when(channelAccountMapper.selectById(account.getId())).thenReturn(account);
        when(contactIdentityMapper.selectById(identity.getId())).thenReturn(identity);
        when(appConfig.chatappFrom()).thenReturn("60111111111");
        org.mockito.Mockito.doThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"))
                .when(conversationAccessService)
                .requireAccessible(conversation.getId(), account.getId(), actorId);
        ChatAppMessageApplicationService service = new ChatAppMessageApplicationService(
                channelAccountMapper, contactIdentityMapper, conversationMapper, sendService,
                conversationAccessService, appConfig);

        assertThatThrownBy(() -> service.acceptConversation(
                conversation.getId(), "text", "request-1", Map.of("text", "hello"), actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CHATAPP_CONVERSATION_FORBIDDEN");
        org.mockito.Mockito.verifyNoInteractions(sendService);
    }
}
