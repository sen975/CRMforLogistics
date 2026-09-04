package com.crmforlogistics.messagecenter.service.message;

import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.dto.response.ChannelCapabilityResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageQueryServiceTest {

    @Test
    void projectsChannelAccountIdInChannelCapabilities() {
        ChannelAccountMapper channelAccountMapper = mock(ChannelAccountMapper.class);
        MessageQueryService service = new MessageQueryService(
                mock(MessageMapper.class), channelAccountMapper, mock(ConversationMapper.class),
                mock(ContactIdentityMapper.class), mock(AttachmentMapper.class),
                mock(TemplateMessageTextResolver.class), mock(ConversationAccessService.class));
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setName("CAMS 一号账号");
        account.setAuthStatus("active");
        when(channelAccountMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(account));

        ChannelCapabilityResponse capability = service.channelCapabilities().get(0);

        assertThat(capability.channelAccountId()).isEqualTo(accountId);
    }

    @Test
    void returnsNullWhenMessageMissingWithoutAuthorizationLookup() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationAccessService access = mock(ConversationAccessService.class);
        TemplateMessageTextResolver resolver = mock(TemplateMessageTextResolver.class);
        MessageQueryService service = service(messageMapper, resolver, access);
        UUID messageId = UUID.randomUUID();
        when(messageMapper.findByIdAndOwner(eq(messageId), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(messageMapper.findWeComById(messageId)).thenReturn(null);

        assertThat(service.getMessage(messageId, UUID.randomUUID())).isNull();
        verifyNoInteractions(access, resolver);
    }

    @Test
    void throwsWhenConversationForbidden() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationAccessService access = mock(ConversationAccessService.class);
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setChannelAccountId(channelAccountId);
        when(messageMapper.findByIdAndOwner(messageId, userId)).thenReturn(null);
        when(messageMapper.findWeComById(messageId)).thenReturn(message);
        MessageQueryService service = new MessageQueryService(messageMapper, mock(ChannelAccountMapper.class), mock(ConversationMapper.class),
                mock(ContactIdentityMapper.class), mock(AttachmentMapper.class),
                mock(TemplateMessageTextResolver.class), access);
        when(access.requireAccessible(conversationId, channelAccountId, userId))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        assertThatThrownBy(() -> service.getMessage(messageId, userId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void assemblesResolvedBodyChannelTypeAndAttachments() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ChannelAccountMapper channelAccountMapper = mock(ChannelAccountMapper.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        TemplateMessageTextResolver resolver = mock(TemplateMessageTextResolver.class);
        ConversationAccessService access = mock(ConversationAccessService.class);
        MessageQueryService service = new MessageQueryService(
                messageMapper, channelAccountMapper, mock(ConversationMapper.class),
                mock(ContactIdentityMapper.class), attachmentMapper, resolver, access);

        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setChannelAccountId(channelAccountId);
        message.setMessageKind("template");
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(channelAccountId);
        account.setChannelType("chatapp");
        UUID userId = UUID.randomUUID();
        when(messageMapper.findByIdAndOwner(messageId, userId)).thenReturn(message);
        when(channelAccountMapper.selectById(channelAccountId)).thenReturn(account);
        when(resolver.resolve(message)).thenReturn("Hello Alice");
        when(attachmentMapper.listReadyByMessageId(messageId)).thenReturn(List.of());

        MessageResponse response = service.getMessage(messageId, userId);

        assertThat(response.bodyText()).isEqualTo("Hello Alice");
        assertThat(response.channelType()).isEqualTo("chatapp");
        assertThat(response.attachments()).isEmpty();
    }

    @Test
    void doesNotFallBackToConversationAuthorizationForAnotherUsersPrivateMessage() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ConversationAccessService access = mock(ConversationAccessService.class);
        UUID messageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(messageMapper.findByIdAndOwner(messageId, userId)).thenReturn(null);
        when(messageMapper.findWeComById(messageId)).thenReturn(null);
        MessageQueryService service = new MessageQueryService(messageMapper, accounts,
                mock(ConversationMapper.class), mock(ContactIdentityMapper.class),
                mock(AttachmentMapper.class), mock(TemplateMessageTextResolver.class), access);

        assertThat(service.getMessage(messageId, userId)).isNull();
        verify(access, never()).requireAccessible(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(messageMapper, never()).selectById(messageId);
    }

    private static MessageQueryService service(MessageMapper messageMapper,
                                               TemplateMessageTextResolver resolver,
                                               ConversationAccessService access) {
        return new MessageQueryService(
                messageMapper,
                mock(ChannelAccountMapper.class),
                mock(ConversationMapper.class),
                mock(ContactIdentityMapper.class),
                mock(AttachmentMapper.class),
                resolver,
                access);
    }
}
