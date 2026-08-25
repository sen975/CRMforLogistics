package com.crmforlogistics.messagecenter.service.message;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadServiceTemplateRenderingTest {

    @Mock ConversationMapper conversationMapper;
    @Mock MessageMapper messageMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock TemplateMessageTextResolver templateMessageTextResolver;
    @Mock AttachmentMapper attachmentMapper;

    @Test
    void threadResolvesChatAppAccountFromIdentityScope() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID scopedAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(scopedAccountId.toString());
        identity.setIdentityValue("8613800138000");
        ChannelAccountEntity scopedAccount = new ChannelAccountEntity();
        scopedAccount.setId(scopedAccountId);
        scopedAccount.setChannelType("chatapp");
        scopedAccount.setAuthStatus("active");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);

        when(contactIdentityMapper.findByContactId(contactId)).thenReturn(List.of(identity));
        when(channelAccountMapper.selectById(scopedAccountId)).thenReturn(scopedAccount);
        when(conversationMapper.getOrCreateConversation(scopedAccountId, identityId))
                .thenReturn(conversation);
        IPage<MessageEntity> page = new Page<>(1, 11, false);
        page.setRecords(List.of());
        when(messageMapper.listMessagesByConversations(
                any(), anyList(), eq(userId), isNull(), isNull(), eq(false))).thenReturn(page);

        ThreadService service = new ThreadService(
                conversationMapper, messageMapper, contactIdentityMapper, channelAccountMapper,
                templateMessageTextResolver, attachmentMapper);

        service.threadPage(userId, contactId, "chatapp", null, 10);

        verify(channelAccountMapper).selectById(scopedAccountId);
        verify(channelAccountMapper, never()).selectOne(any());
        verify(conversationMapper).getOrCreateConversation(scopedAccountId, identityId);
    }

    @Test
    void threadUsesResolvedTemplateTextForHistoricalPlaceholder() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityValue("8613800138000");
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAccountIdentifier("8613900139000");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setMessageKind("template");
        message.setBodyText("[template]");
        when(contactIdentityMapper.findByContactId(contactId)).thenReturn(List.of(identity));
        when(channelAccountMapper.selectOne(any())).thenReturn(account);
        when(conversationMapper.getOrCreateConversation(accountId, identityId)).thenReturn(conversation);
        IPage<MessageEntity> page = new Page<>(1, 11, false);
        page.setRecords(List.of(message));
        when(messageMapper.listMessagesByConversations(
                any(), anyList(), eq(userId), isNull(), isNull(), eq(false))).thenReturn(page);
        when(templateMessageTextResolver.resolve(message)).thenReturn("Hello Alice");

        ThreadService service = new ThreadService(
                conversationMapper, messageMapper, contactIdentityMapper, channelAccountMapper,
                templateMessageTextResolver, attachmentMapper);

        assertThat(service.threadPage(userId, contactId, "chatapp", null, 10)
                .items().get(0).bodyText()).isEqualTo("Hello Alice");
    }

    @Test
    void threadProjectsReadyAttachmentsWithoutExposingObjectKeys() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        identity.setChannelType("chatapp");
        identity.setIdentityValue("8613800138000");
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAccountIdentifier("8613900139000");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setMessageKind("image");
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setId(attachmentId);
        attachment.setMessageId(messageId);
        attachment.setMediaKind("image");
        attachment.setMimeType("image/png");
        attachment.setOriginalName("photo.png");
        attachment.setSizeBytes(123L);
        attachment.setObjectKey("must-not-leak");

        when(contactIdentityMapper.findByContactId(contactId)).thenReturn(List.of(identity));
        when(channelAccountMapper.selectOne(any())).thenReturn(account);
        when(conversationMapper.getOrCreateConversation(accountId, identityId)).thenReturn(conversation);
        IPage<MessageEntity> page = new Page<>(1, 11, false);
        page.setRecords(List.of(message));
        when(messageMapper.listMessagesByConversations(
                any(), anyList(), eq(userId), isNull(), isNull(), eq(false))).thenReturn(page);
        when(attachmentMapper.listReadyByMessageIds(List.of(messageId))).thenReturn(List.of(attachment));

        ThreadService service = new ThreadService(
                conversationMapper, messageMapper, contactIdentityMapper, channelAccountMapper,
                templateMessageTextResolver, attachmentMapper);

        var projected = service.threadPage(userId, contactId, "chatapp", null, 10)
                .items().get(0).attachments();
        assertThat(projected).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(attachmentId);
            assertThat(item.mediaKind()).isEqualTo("image");
            assertThat(item.mimeType()).isEqualTo("image/png");
            assertThat(item.fileName()).isEqualTo("photo.png");
            assertThat(item.sizeBytes()).isEqualTo(123L);
            assertThat(item.toString()).doesNotContain("must-not-leak");
        });
        verify(attachmentMapper).listReadyByMessageIds(List.of(messageId));
        verify(attachmentMapper, never()).listReadyByMessageId(any());
    }
}
