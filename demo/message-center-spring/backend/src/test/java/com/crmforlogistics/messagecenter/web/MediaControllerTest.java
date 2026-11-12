package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaControllerTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inaccessibleAttachmentIsRejectedBeforeStorageRead() {
        UUID actorId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), "", java.util.List.of()));
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        when(attachmentMapper.findReadableById(attachmentId, actorId)).thenReturn(null);
        MediaController controller = new MediaController(storage, attachmentMapper);

        assertThatThrownBy(() -> controller.getMedia(attachmentId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
        verifyNoInteractions(storage);
    }

    @Test
    void readableAttachmentUsesStoredMimeTypeAndObjectKey() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), "", java.util.List.of()));
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setId(attachmentId);
        attachment.setObjectKey("chatapp/photo-1");
        attachment.setOriginalName("photo.png");
        attachment.setMimeType("image/png");
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        when(attachmentMapper.findReadableById(attachmentId, actorId)).thenReturn(attachment);
        when(storage.get("chatapp/photo-1"))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        var response = new MediaController(storage, attachmentMapper).getMedia(attachmentId);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        verify(storage).get("chatapp/photo-1");
    }
}
