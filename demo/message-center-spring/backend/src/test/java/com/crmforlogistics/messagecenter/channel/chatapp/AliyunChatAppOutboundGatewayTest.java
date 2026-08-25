package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliyunChatAppOutboundGatewayTest {
    @Test
    void rejectsMediaObjectNotBoundToMessage() {
        ChatAppSendService sendService = mock(ChatAppSendService.class);
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        ChannelAccountMapper channelAccountMapper = mock(ChannelAccountMapper.class);
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60111111111");
        UUID messageId = UUID.randomUUID();
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);
        when(attachmentMapper.existsReadyForMessage(messageId, "object-1"))
                .thenReturn(false);
        AliyunChatAppOutboundGateway gateway = new AliyunChatAppOutboundGateway(
                sendService, storage, attachmentMapper, channelAccountMapper);

        assertThatThrownBy(() -> gateway.submit(new ChatAppOutboundGateway.Command(
                accountId, messageId, "request-1", "image",
                Map.of("to", "60123456789", "objectKey", "object-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_MEDIA_ATTACHMENT_NOT_READY");
        verifyNoInteractions(storage, sendService);
    }

    @Test
    void sendsFromTheSelectedChannelAccountIdentifier() throws Exception {
        ChatAppSendService sendService = mock(ChatAppSendService.class);
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        ChannelAccountMapper channelAccountMapper = mock(ChannelAccountMapper.class);
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60122222222");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);
        when(sendService.sendText("60122222222", "60123456789", "hello", "request-1"))
                .thenReturn(new ChatAppSendService.SendResult(
                        "wamid-1", "60122222222", "60123456789", "hello", "Submitted"));
        AliyunChatAppOutboundGateway gateway = new AliyunChatAppOutboundGateway(
                sendService, storage, attachmentMapper, channelAccountMapper);

        gateway.submit(new ChatAppOutboundGateway.Command(
                accountId, UUID.randomUUID(), "request-1", "text",
                Map.of("to", "60123456789", "text", "hello")));

        verify(sendService).sendText(
                "60122222222", "60123456789", "hello", "request-1");
    }
}
