package com.crmforlogistics.messagecenter.service.wecom;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComGroupThreadResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComPartyView;
import com.crmforlogistics.messagecenter.dto.response.WeComThreadResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.crmforlogistics.messagecenter.service.message.ThreadService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComMessageProjectorGroupTest {

    @Test
    void groupThreadReturnsRealSendersAndObservedParticipants() {
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WeComSourceConversationMapper sources = mock(WeComSourceConversationMapper.class);
        WeComSourceParticipantMapper participants = mock(WeComSourceParticipantMapper.class);
        WeComChatDataMessageMapper chatData = mock(WeComChatDataMessageMapper.class);
        MessageEntity entity = message("group-msg", sourceId);
        Page<MessageEntity> page = new Page<>(1, 20, false);
        page.setRecords(List.of(entity));
        when(conversations.findAccessibleWeComGroup(any(), any())).thenReturn(
                new ConversationMapper.WeComSourceConversationAccessRow(
                        sourceId, UUID.randomUUID(), "group:chat-id", "EXTERNAL", "研发群", null,
                        "GROUP", UUID.randomUUID()));
        when(messages.listMessagesByConversations(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(page);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setChannelType("wecom");
        account.setName("企业微信");
        when(accounts.selectOne(any())).thenReturn(account);
        WeComPartyView sender = new WeComPartyView(UUID.randomUUID(), "EMPLOYEE", "employee-a", "员工 A", null,
                null, false, false);
        when(chatData.findMessageSenderView("group-msg", userId)).thenReturn(sender);
        when(participants.listPartyViews(sourceId, userId)).thenReturn(List.of(sender,
                new WeComPartyView(UUID.randomUUID(), "EXTERNAL_CONTACT", "external-a", "客户 A", null,
                        UUID.randomUUID(), true, false)));

        ThreadService service = new ThreadService(
                conversations, messages, mock(ContactIdentityMapper.class), accounts,
                mock(TemplateMessageTextResolver.class), mock(AttachmentMapper.class),
                sources, chatData, participants, mock(WeComPartyMapper.class));

        WeComGroupThreadResponse response = service.getWeComGroupThread(userId, sourceId, null, 20);

        assertThat(response.displayName()).isEqualTo("研发群");
        assertThat(response.groupChatId()).isEqualTo("chat-id");
        assertThat(response.participants()).extracting(WeComPartyView::displayName)
                .containsExactly("员工 A", "客户 A");
        assertThat(response.items()).singleElement().extracting(MessageResponse::sender)
                .isEqualTo(sender);
    }

    @Test
    void directThreadKeepsEachSourceConversationIdAndStructuredSender() {
        WeComPartyView sender = new WeComPartyView(UUID.randomUUID(), "EMPLOYEE", "employee-a", "员工 A", null,
                null, true, false);
        WeComThreadResponse response = new WeComThreadResponse(
                UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(
                        new MessageResponse(UUID.randomUUID(), "m1", "inbound", "text", null, "", null,
                                "wecom", "员工 A", "当前成员", Instant.EPOCH, "delivered", 1, List.of(),
                                UUID.randomUUID(), "DIRECT", "直聊", sender)),
                null, 1, "r1");

        assertThat(response.items()).singleElement().extracting(MessageResponse::sourceConversationId)
                .isNotNull();
        assertThat(response.items()).singleElement().extracting(item -> item.sender().partyType())
                .isEqualTo("EMPLOYEE");
    }

    private static MessageEntity message(String providerId, UUID sourceId) {
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(UUID.randomUUID());
        message.setChannelAccountId(UUID.randomUUID());
        message.setProviderMessageId(providerId);
        message.setDirection("inbound");
        message.setMessageKind("text");
        message.setOccurredAt(Instant.EPOCH);
        message.setIngestSequence(1L);
        return message;
    }
}
