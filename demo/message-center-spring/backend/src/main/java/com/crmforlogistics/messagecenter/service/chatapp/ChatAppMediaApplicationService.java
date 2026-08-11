package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatAppMediaApplicationService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatAppMediaApplicationService.class);
    private static final Set<String> KINDS = Set.of("image", "video", "document");
    private static final int MAX_MEDIA_BYTES = 64 * 1024 * 1024;

    private final MinioStorage minioStorage;
    private final AttachmentMapper attachmentMapper;
    private final ChatAppMessageApplicationService messageApplicationService;
    private final TransactionOperations transactions;

    public ChatAppMediaApplicationService(MinioStorage minioStorage,
                                          AttachmentMapper attachmentMapper,
                                          ChatAppMessageApplicationService messageApplicationService,
                                          TransactionOperations transactions) {
        this.minioStorage = Objects.requireNonNull(minioStorage);
        this.attachmentMapper = Objects.requireNonNull(attachmentMapper);
        this.messageApplicationService = Objects.requireNonNull(messageApplicationService);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public MessageSendApplicationService.MessageAccepted accept(
            String recipient, String kind, byte[] bytes, String fileName, String contentType,
            String caption, String clientRequestId, UUID actorUserId) throws Exception {
        validate(kind, bytes, contentType);
        messageApplicationService.authorizeRecipient(recipient, actorUserId);
        String digest = sha256(bytes);
        String objectKey = minioStorage.store(bytes, contentType);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("objectKey", objectKey);
        content.put("fileName", fileName == null ? "" : fileName);
        content.put("contentType", contentType);
        content.put("caption", caption == null ? "" : caption);
        try {
            MessageSendApplicationService.MessageAccepted accepted = transactions.execute(status -> {
                MessageSendApplicationService.MessageAccepted result =
                        messageApplicationService.acceptRecipient(
                                recipient, kind, clientRequestId, content, actorUserId);
                if (!result.duplicate()) {
                    AttachmentEntity attachment = new AttachmentEntity();
                    attachment.setId(UUID.randomUUID());
                    attachment.setMessageId(result.messageId());
                    attachment.setStorageProvider("minio");
                    attachment.setBucket(minioStorage.bucketName());
                    attachment.setObjectKey(objectKey);
                    attachment.setOriginalName(fileName == null || fileName.isBlank()
                            ? "upload.bin" : fileName);
                    attachment.setMimeType(contentType);
                    attachment.setSizeBytes((long) bytes.length);
                    attachment.setSha256(digest);
                    attachment.setMediaKind(kind);
                    attachment.setStorageStatus("ready");
                    attachment.setCreatedAt(Instant.now());
                    attachment.setReadyAt(Instant.now());
                    attachmentMapper.insert(attachment);
                }
                return result;
            });
            if (accepted == null) {
                throw new IllegalStateException("CHATAPP_MEDIA_TRANSACTION_RESULT_MISSING");
            }
            if (accepted.duplicate()) {
                removeQuietly(objectKey);
            }
            return accepted;
        } catch (RuntimeException | Error e) {
            removeQuietly(objectKey);
            throw e;
        }
    }

    private static void validate(String kind, byte[] bytes, String contentType) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_KIND_UNSUPPORTED");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_FILE_REQUIRED");
        }
        if (bytes.length > MAX_MEDIA_BYTES) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_TOO_LARGE");
        }
        String mime = contentType == null ? "" : contentType.toLowerCase();
        if ("image".equals(kind) && !mime.startsWith("image/")) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_MIME_INVALID");
        }
        if ("video".equals(kind) && !mime.startsWith("video/")) {
            throw new IllegalArgumentException("CHATAPP_MEDIA_MIME_INVALID");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void removeQuietly(String objectKey) {
        try {
            minioStorage.remove(objectKey);
        } catch (Exception cleanupError) {
            LOG.warn("Failed to clean up uncommitted ChatApp media object", cleanupError);
        }
    }
}
