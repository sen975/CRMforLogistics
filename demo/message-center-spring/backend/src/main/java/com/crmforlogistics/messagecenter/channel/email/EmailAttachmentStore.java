package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class EmailAttachmentStore {
    private static final Logger log = LoggerFactory.getLogger(EmailAttachmentStore.class);
    private final MinioStorage storage;
    private final AttachmentMapper mapper;

    public EmailAttachmentStore(MinioStorage storage, AttachmentMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<AttachmentEntity> store(UUID messageId, List<EmailAttachmentPayload> payloads, boolean ready) throws Exception {
        var saved = new ArrayList<AttachmentEntity>();
        var keys = new ArrayList<String>();
        try {
            for (EmailAttachmentPayload payload : payloads) {
                UUID id = UUID.randomUUID();
                String key = "email/" + messageId + "/" + id + "/" + EmailAttachmentReader.normalizeFileName(payload.fileName());
                storage.store(key, payload.bytes(), payload.mimeType());
                keys.add(key);
                AttachmentEntity entity = new AttachmentEntity();
                entity.setId(id);
                entity.setMessageId(messageId);
                entity.setStorageProvider("minio");
                entity.setBucket(storage.bucketName());
                entity.setObjectKey(key);
                entity.setOriginalName(payload.fileName());
                entity.setMimeType(payload.mimeType());
                entity.setSizeBytes(payload.sizeBytes());
                entity.setSha256(payload.sha256());
                entity.setMediaKind(payload.mediaKind());
                entity.setStorageStatus("pending");
                entity.setCreatedAt(Instant.now());
                mapper.insert(entity);
                saved.add(entity);
            }
            if (ready && !saved.isEmpty()) mapper.markReadyByMessageId(messageId, Instant.now());
            return List.copyOf(saved);
        } catch (Exception ex) {
            for (int i = keys.size() - 1; i >= 0; i--) {
                try { storage.remove(keys.get(i)); } catch (Exception compensation) {
                    log.warn("Attachment compensation failed messageId={} stage=store", messageId);
                }
            }
            throw ex;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<AttachmentEntity> storeIfMissing(UUID messageId, List<EmailAttachmentPayload> payloads) throws Exception {
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<AttachmentEntity> existing = mapper.listReadyByMessageId(messageId);
        if (!existing.isEmpty()) {
            var matched = new boolean[existing.size()];
            var missing = new ArrayList<EmailAttachmentPayload>();
            for (EmailAttachmentPayload payload : payloads) {
                int match = findMatchingAttachment(existing, matched, payload.sha256());
                if (match < 0) {
                    missing.add(payload);
                    continue;
                }
                matched[match] = true;
                AttachmentEntity attachment = existing.get(match);
                if (!payload.fileName().equals(attachment.getOriginalName())) {
                    AttachmentEntity update = new AttachmentEntity();
                    update.setId(attachment.getId());
                    update.setOriginalName(payload.fileName());
                    mapper.updateById(update);
                }
            }
            return missing.isEmpty() ? List.of() : store(messageId, missing, true);
        }
        return store(messageId, payloads, true);
    }

    private int findMatchingAttachment(List<AttachmentEntity> existing, boolean[] matched, String sha256) {
        if (sha256 == null || sha256.isBlank()) return -1;
        for (int i = 0; i < existing.size(); i++) {
            if (!matched[i] && sha256.equalsIgnoreCase(existing.get(i).getSha256())) return i;
        }
        return -1;
    }

    public void markReady(UUID messageId, Instant readyAt) {
        mapper.markReadyByMessageId(messageId, readyAt);
    }

    public void removeMessageObjects(UUID messageId) {
        for (AttachmentEntity entity : mapper.listByMessageId(messageId)) {
            try { storage.remove(entity.getObjectKey()); } catch (Exception ex) {
                log.warn("Attachment removal failed messageId={} attachmentId={}", messageId, entity.getId());
            }
        }
        mapper.markDeletedByMessageId(messageId);
    }
}
