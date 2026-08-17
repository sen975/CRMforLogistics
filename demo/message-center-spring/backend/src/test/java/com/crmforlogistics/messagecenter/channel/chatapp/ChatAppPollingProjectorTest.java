package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookProjector;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAppPollingProjectorTest {
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ChannelEventMapper eventMapper = mock(ChannelEventMapper.class);
    private final ChatAppWebhookProjector webhookProjector = mock(ChatAppWebhookProjector.class);
    private final ChatAppOutboundMessageLinker linker = mock(ChatAppOutboundMessageLinker.class);
    private final ChatAppPollingProjector projector = new ChatAppPollingProjector(
            messageMapper, eventMapper, webhookProjector, linker, new ObjectMapper());

    @BeforeEach
    void defaultOutboundLinkIsOrphan() {
        when(linker.resolve(any(), any())).thenReturn(
                new LinkResult(LinkResult.Kind.ORPHAN, null, ""));
    }

    @Test
    void inboundRowUsesWebhookProjectorWithCustomerAsSender() {
        UUID accountId = UUID.randomUUID();
        when(eventMapper.insertIgnore(any())).thenReturn(1);
        var row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-in-1")
                .messageSource("inbound")
                .userNumber("60123456789")
                .businessNumber("8613266259485")
                .message("hello")
                .build();

        assertThat(projector.project(row, accountId)).isTrue();

        ArgumentCaptor<ChannelEventEntity> event =
                ArgumentCaptor.forClass(ChannelEventEntity.class);
        verify(eventMapper).insertIgnore(event.capture());
        assertThat(event.getValue().getPayloadJsonb())
                .contains("60123456789")
                .contains("hello");
        verify(webhookProjector).project(event.getValue());
    }

    @Test
    void outboundRowReconcilesSubmissionUnknownByTaskIdBeforeStatusProjection() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setCurrentStatus("submission_unknown");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-out-1"))
                .thenReturn(Optional.empty());
        when(messageMapper.findByClientRequestId(accountId, "request-1"))
                .thenReturn(Optional.of(message));
        when(eventMapper.insertIgnore(any())).thenReturn(1);
        var row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-out-1")
                .uniqueMessageId("request-1")
                .messageSource("outbound")
                .messageStatusName("DELIVERED")
                .businessNumber("8613266259485")
                .userNumber("60123456789")
                .build();
        when(linker.resolve(accountId, row)).thenReturn(new LinkResult(
                LinkResult.Kind.EXISTING_CLIENT_REQUEST, message.getId(), ""));
        when(messageMapper.selectById(message.getId())).thenReturn(message);

        assertThat(projector.project(row, accountId)).isTrue();

        assertThat(message.getProviderMessageId()).isEqualTo("wamid-out-1");
        verify(messageMapper, never()).updateById(message);
        verify(messageMapper).updateProviderMessageId(eq(message.getId()), eq("wamid-out-1"));
        verify(webhookProjector).project(any(ChannelEventEntity.class));
    }

    @Test
    void outboundRowWithoutLocalMessageIsNotImportedIntoWrongConversation() {
        UUID accountId = UUID.randomUUID();
        when(messageMapper.findByProviderMessageId(accountId, "wamid-out-1"))
                .thenReturn(Optional.empty());
        when(messageMapper.findByClientRequestId(accountId, "unknown-task"))
                .thenReturn(Optional.empty());
        var row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-out-1")
                .uniqueMessageId("unknown-task")
                .messageSource("outbound")
                .messageStatusName("DELIVERED")
                .build();

        assertThat(projector.project(row, accountId)).isFalse();

        verifyNoInteractions(eventMapper, webhookProjector);
    }

    @Test
    void repeatedOrphanOutboundRowUsesProviderEventKeyForDeduplication() {
        UUID accountId = UUID.randomUUID();
        when(messageMapper.findByProviderMessageId(accountId, "wamid-orphan-1"))
                .thenReturn(Optional.empty());
        when(messageMapper.findByClientRequestId(accountId, "orphan-task-1"))
                .thenReturn(Optional.empty());
        when(eventMapper.insertIgnore(any())).thenReturn(1, 0);
        var row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-orphan-1")
                .uniqueMessageId("orphan-task-1")
                .messageSource("outbound")
                .messageStatusName("DELIVERED")
                .userNumber("60123456789")
                .businessNumber("8613266259485")
                .message("{\"templateCode\":\"order_ready\"}")
                .build();

        assertThat(projector.project(row, accountId)).isTrue();
        assertThat(projector.project(row, accountId)).isFalse();
        ArgumentCaptor<ChannelEventEntity> events =
                ArgumentCaptor.forClass(ChannelEventEntity.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).insertIgnore(events.capture());
        assertThat(events.getAllValues())
                .extracting(ChannelEventEntity::getProviderEventId)
                .containsExactly(
                        events.getAllValues().get(0).getProviderEventId(),
                        events.getAllValues().get(0).getProviderEventId());
        assertThat(events.getAllValues().get(0).getPayloadJsonb())
                .contains("\"Direction\":\"outbound\"")
                .contains("\"To\":\"60123456789\"")
                .contains("\"ClientRequestId\":\"orphan-task-1\"");
        verify(webhookProjector).project(events.getAllValues().get(0));
    }

    @Test
    void unreadClientFlagDoesNotOverrideDeliveredMessageStatus() {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setProviderMessageId("wamid-out-2");
        when(messageMapper.findByProviderMessageId(accountId, "wamid-out-2"))
                .thenReturn(Optional.of(message));
        when(eventMapper.insertIgnore(any())).thenReturn(1);
        var row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-out-2")
                .messageSource("outbound")
                .clientReadStatusName("Unread")
                .messageStatusName("DELIVERED")
                .build();
        when(linker.resolve(accountId, row)).thenReturn(new LinkResult(
                LinkResult.Kind.EXISTING_PROVIDER, message.getId(), ""));
        when(messageMapper.selectById(message.getId())).thenReturn(message);

        assertThat(projector.project(row, accountId)).isTrue();

        ArgumentCaptor<ChannelEventEntity> event =
                ArgumentCaptor.forClass(ChannelEventEntity.class);
        verify(eventMapper).insertIgnore(event.capture());
        assertThat(event.getValue().getPayloadJsonb())
                .contains("\"Status\":\"delivered\"")
                .doesNotContain("Unread");
    }
}
