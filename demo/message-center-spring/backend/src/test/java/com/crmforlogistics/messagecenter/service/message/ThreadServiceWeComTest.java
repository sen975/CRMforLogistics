package com.crmforlogistics.messagecenter.service.message;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.ThreadResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComThreadResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThreadServiceWeComTest {
    @Test
    void contactWeComThreadDropsGroupMessagesFromLegacyRows() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID directSourceId = UUID.randomUUID();
        UUID groupSourceId = UUID.randomUUID();
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        AttachmentMapper attachments = mock(AttachmentMapper.class);
        WeComSourceConversationMapper sources = mock(WeComSourceConversationMapper.class);

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setChannelType("wecom");
        identity.setIdentityValue("external");
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity));

        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectOne(any())).thenReturn(account);
        when(accounts.selectById(account.getId())).thenReturn(account);

        ConversationEntity identityConversation = new ConversationEntity();
        identityConversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(account.getId(), identity.getId()))
                .thenReturn(identityConversation);
        ConversationEntity directConversation = sourceConversation(directSourceId);
        ConversationEntity groupConversation = sourceConversation(groupSourceId);
        when(conversations.listAccessibleWeComSourceForContact(contactId, userId))
                .thenReturn(List.of(directConversation, groupConversation));
        when(conversations.selectById(directConversation.getId())).thenReturn(directConversation);
        when(conversations.selectById(groupConversation.getId())).thenReturn(groupConversation);

        MessageEntity directMessage = message(directConversation.getId(), account.getId(), "direct-message");
        MessageEntity groupMessage = message(groupConversation.getId(), account.getId(), "group-message");
        Page<MessageEntity> page = new Page<>();
        page.setRecords(List.of(groupMessage, directMessage));
        when(messages.listMessagesByConversations(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(page);
        when(attachments.listReadyByMessageIds(any())).thenReturn(List.of());

        WeComSourceConversationEntity directSource = new WeComSourceConversationEntity();
        directSource.setId(directSourceId);
        directSource.setConversationType("DIRECT");
        WeComSourceConversationEntity groupSource = new WeComSourceConversationEntity();
        groupSource.setId(groupSourceId);
        groupSource.setConversationType("GROUP");
        when(sources.selectById(directSourceId)).thenReturn(directSource);
        when(sources.selectById(groupSourceId)).thenReturn(groupSource);

        ThreadService service = new ThreadService(conversations, messages, identities, accounts,
                mock(TemplateMessageTextResolver.class), attachments, sources, null);

        WeComThreadResponse response = service.getContactWeComThread(userId, contactId, null, 20);

        assertThat(response.items()).extracting(item -> item.sourceId())
                .containsExactly("direct-message");
        assertThat(response.sourceConversationIds()).containsExactly(directSourceId);
    }

    @Test
    void recalculatesStoredEmployeeDirectionFromCurrentBoundMember() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        AttachmentMapper attachments = mock(AttachmentMapper.class);
        WeComChatDataMessageMapper chatDataMessages = mock(WeComChatDataMessageMapper.class);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setChannelType("wecom");
        identity.setIdentityValue("employee-b");
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectOne(any())).thenReturn(account);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(account.getId(), identity.getId())).thenReturn(conversation);
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversation.getId());
        message.setChannelAccountId(account.getId());
        message.setProviderMessageId("internal-message");
        message.setDirection("outbound");
        message.setMessageKind("text");
        message.setOccurredAt(Instant.EPOCH);
        message.setIngestSequence(1L);
        Page<MessageEntity> page = new Page<>();
        page.setRecords(List.of(message));
        when(messages.listMessagesByConversations(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(page);
        when(attachments.listReadyByMessageIds(any())).thenReturn(List.of());
        when(chatDataMessages.resolveDirectionForViewer("internal-message", userId)).thenReturn("inbound");

        ThreadService service = new ThreadService(conversations, messages, identities, accounts,
                mock(TemplateMessageTextResolver.class), attachments, null, chatDataMessages);

        assertThat(service.threadPage(userId, contactId, "wecom", null, 20).items())
                .singleElement().extracting(item -> item.direction()).isEqualTo("inbound");
    }

    @Test
    void threadResponseIncludesWeComSourceIdWithoutPlaintext() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        AttachmentMapper attachments = mock(AttachmentMapper.class);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setChannelType("wecom");
        identity.setIdentityValue("external");
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectOne(any())).thenReturn(account);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(account.getId(), identity.getId()))
                .thenReturn(conversation);
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversation.getId());
        message.setChannelAccountId(account.getId());
        message.setProviderMessageId("m1");
        message.setDirection("inbound");
        message.setMessageKind("text");
        message.setBodyText("");
        message.setOccurredAt(Instant.EPOCH);
        message.setIngestSequence(1L);
        Page<MessageEntity> page = new Page<>();
        page.setRecords(List.of(message));
        when(messages.listMessagesByConversations(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(page);
        when(attachments.listReadyByMessageIds(any())).thenReturn(List.of());
        TemplateMessageTextResolver textResolver = mock(TemplateMessageTextResolver.class);
        when(textResolver.resolve(message)).thenReturn("");

        ThreadService service = new ThreadService(conversations, messages, identities, accounts,
                textResolver, attachments);
        ThreadResponse response = service.threadPage(userId, contactId, "wecom", null, 20);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.channelType()).isEqualTo("wecom");
            assertThat(item.sourceId()).isEqualTo("m1");
            assertThat(item.bodyText()).isEmpty();
        });
    }

    private static ConversationEntity sourceConversation(UUID sourceId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setSourceConversationId(sourceId);
        return conversation;
    }

    private static MessageEntity message(UUID conversationId, UUID channelAccountId, String providerMessageId) {
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setChannelAccountId(channelAccountId);
        message.setProviderMessageId(providerMessageId);
        message.setDirection("inbound");
        message.setMessageKind("text");
        message.setOccurredAt(Instant.EPOCH);
        message.setIngestSequence(1L);
        return message;
    }
}
