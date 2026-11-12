package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailAttachmentStoreTest {

    @Test
    void reconcilesDecodedNameForExistingReadyAttachmentWithoutUploadingAgain() throws Exception {
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper mapper = mock(AttachmentMapper.class);
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AttachmentEntity existing = new AttachmentEntity();
        existing.setId(attachmentId);
        existing.setMessageId(messageId);
        existing.setOriginalName("=?UTF-8?Q?encoded.png?=");
        existing.setSha256("same-sha");
        when(mapper.listReadyByMessageId(messageId)).thenReturn(List.of(existing));

        EmailAttachmentPayload payload = new EmailAttachmentPayload(
                "截屏.png", "image/png", "image",
                "png".getBytes(StandardCharsets.UTF_8), "same-sha");

        new EmailAttachmentStore(storage, mapper).storeIfMissing(messageId, List.of(payload));

        verify(mapper).updateById(org.mockito.ArgumentMatchers.<AttachmentEntity>argThat(entity ->
                attachmentId.equals(entity.getId()) && "截屏.png".equals(entity.getOriginalName())));
        verify(storage, never()).store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void storesOnlyMissingAttachmentsWhenSomeReadyAttachmentsAlreadyExist() throws Exception {
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper mapper = mock(AttachmentMapper.class);
        UUID messageId = UUID.randomUUID();
        AttachmentEntity existing = new AttachmentEntity();
        existing.setId(UUID.randomUUID());
        existing.setMessageId(messageId);
        existing.setOriginalName("one.png");
        existing.setSha256("sha-one");
        when(mapper.listReadyByMessageId(messageId)).thenReturn(List.of(existing));

        EmailAttachmentPayload retained = new EmailAttachmentPayload(
                "renamed.png", "image/png", "image", new byte[]{1}, "sha-one");
        EmailAttachmentPayload missing = new EmailAttachmentPayload(
                "two.png", "image/png", "image", new byte[]{2}, "sha-two");

        new EmailAttachmentStore(storage, mapper).storeIfMissing(messageId, List.of(retained, missing));

        verify(mapper).updateById(org.mockito.ArgumentMatchers.<AttachmentEntity>argThat(entity ->
                existing.getId().equals(entity.getId()) && "renamed.png".equals(entity.getOriginalName())));
        verify(storage).store(argThat(key -> key.startsWith("email/" + messageId + "/")),
                org.mockito.ArgumentMatchers.eq(new byte[]{2}), org.mockito.ArgumentMatchers.eq("image/png"));
    }

    @Test
    void removesUploadedObjectsIfAttachmentMetadataTransactionRollsBackAfterStoreReturns() throws Exception {
        MinioStorage storage = mock(MinioStorage.class);
        AttachmentMapper mapper = mock(AttachmentMapper.class);
        UUID messageId = UUID.randomUUID();
        EmailAttachmentPayload payload = new EmailAttachmentPayload(
                "file.txt", "text/plain", "document", new byte[]{1}, "sha");
        TransactionSynchronizationManager.initSynchronization();
        try {
            new EmailAttachmentStore(storage, mapper).store(messageId, List.of(payload), true);
            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(storage).remove(argThat(key -> key.startsWith("email/" + messageId + "/")));
    }
}
