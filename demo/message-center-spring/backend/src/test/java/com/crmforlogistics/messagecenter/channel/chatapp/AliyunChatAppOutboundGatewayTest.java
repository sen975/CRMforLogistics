package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AliyunChatAppOutboundGatewayTest {
    @Test
    void rejectsMediaObjectNotBoundToMessage() {
        ChatAppSendService sendService = mock(ChatAppSendService.class);
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        UUID messageId = UUID.randomUUID();
        when(attachmentMapper.existsReadyForMessage(messageId, "object-1"))
                .thenReturn(false);
        AliyunChatAppOutboundGateway gateway = new AliyunChatAppOutboundGateway(
                sendService, storage, attachmentMapper);

        assertThatThrownBy(() -> gateway.submit(new ChatAppOutboundGateway.Command(
                UUID.randomUUID(), messageId, "request-1", "image",
                Map.of("to", "60123456789", "objectKey", "object-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_MEDIA_ATTACHMENT_NOT_READY");
        verifyNoInteractions(storage, sendService);
    }
}
