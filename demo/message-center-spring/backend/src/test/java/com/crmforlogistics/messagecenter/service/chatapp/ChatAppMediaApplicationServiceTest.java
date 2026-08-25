package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppMediaApplicationServiceTest {
    @Mock MinioStorage minioStorage;
    @Mock AttachmentMapper attachmentMapper;
    @Mock ChatAppMessageApplicationService messageApplicationService;

    @Test
    void mediaIsStoredAndEnqueuedBeforeProviderSubmission() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
        when(minioStorage.store(bytes, "image/png")).thenReturn("object-1");
        when(minioStorage.bucketName()).thenReturn("messages");
        when(messageApplicationService.acceptContactIdentity(
                eq(contactId), eq(identityId), eq("image"), eq("request-1"),
                anyMap(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        messageId, "pending", false));

        ChatAppMediaApplicationService service = new ChatAppMediaApplicationService(
                minioStorage, attachmentMapper, messageApplicationService,
                TransactionOperations.withoutTransaction());
        var accepted = service.accept(
                contactId, identityId, "image", bytes, "photo.png", "image/png",
                "caption", "request-1", actorId);

        assertThat(accepted.messageId()).isEqualTo(messageId);
        assertThat(accepted.status()).isEqualTo("pending");
        ArgumentCaptor<AttachmentEntity> attachment =
                ArgumentCaptor.forClass(AttachmentEntity.class);
        verify(attachmentMapper).insert(attachment.capture());
        assertThat(attachment.getValue().getMessageId()).isEqualTo(messageId);
        assertThat(attachment.getValue().getObjectKey()).isEqualTo("object-1");
        assertThat(attachment.getValue().getSha256()).hasSize(64);
        verify(messageApplicationService).authorizeContactIdentity(
                contactId, identityId, actorId);
    }

    @Test
    void unauthorizedMediaIsRejectedBeforeObjectStorage() {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"))
                .when(messageApplicationService)
                .authorizeContactIdentity(contactId, identityId, actorId);
        ChatAppMediaApplicationService service = new ChatAppMediaApplicationService(
                minioStorage, attachmentMapper, messageApplicationService,
                TransactionOperations.withoutTransaction());

        assertThatThrownBy(() -> service.accept(
                contactId, identityId, "image", "image".getBytes(StandardCharsets.UTF_8),
                "photo.png", "image/png", "caption", "request-1", actorId))
                .isInstanceOf(SecurityException.class);
        org.mockito.Mockito.verifyNoInteractions(minioStorage, attachmentMapper);
    }

    @Test
    void duplicateMediaRequestRemovesNewlyUploadedObject() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
        when(minioStorage.store(bytes, "image/png")).thenReturn("object-duplicate");
        when(messageApplicationService.acceptContactIdentity(
                eq(contactId), eq(identityId), eq("image"), eq("request-1"),
                anyMap(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        UUID.randomUUID(), "submitted", true));
        ChatAppMediaApplicationService service = new ChatAppMediaApplicationService(
                minioStorage, attachmentMapper, messageApplicationService,
                TransactionOperations.withoutTransaction());

        var accepted = service.accept(
                contactId, identityId, "image", bytes, "photo.png", "image/png",
                "caption", "request-1", actorId);

        assertThat(accepted.duplicate()).isTrue();
        verify(minioStorage).remove("object-duplicate");
        verify(attachmentMapper, never()).insert(any(AttachmentEntity.class));
    }
}
