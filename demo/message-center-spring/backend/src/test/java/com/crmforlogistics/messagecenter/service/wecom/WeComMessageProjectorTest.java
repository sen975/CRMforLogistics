package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComMessageProjectorTest {
    @Test
    void projectsReferenceWithoutPlaintextAndPreservesDirection() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "external"))
                .thenReturn(Optional.empty());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(conversation);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages);
        WeComMessageProjector.ProjectionResult result = projector.project(
                new WeComMessageProjector.WeComProjectedMessage(
                        "m1", "external", "employee", 100L, "outbound"));

        assertThat(result.inserted()).isTrue();
        ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insertWithSequence(inserted.capture());
        assertThat(inserted.getValue().getProviderMessageId()).isEqualTo("m1");
        assertThat(inserted.getValue().getDirection()).isEqualTo("outbound");
        assertThat(inserted.getValue().getBodyText()).isEmpty();
        assertThat(inserted.getValue().getMetadataJsonb()).isEqualTo("{\"wecomReference\":true}");
    }
}
