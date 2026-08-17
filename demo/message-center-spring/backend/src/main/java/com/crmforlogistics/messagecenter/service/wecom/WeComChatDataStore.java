package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.crmforlogistics.messagecenter.service.event.EventHub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataStore {
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComChatDataCursorMapper cursorMapper;
    private final WeComCredentialProtector credentialProtector;
    private final WeComMessageProjector projector;
    private final EventHub eventHub;

    public WeComChatDataStore(WeComChatDataMessageMapper messageMapper,
                              WeComChatDataCursorMapper cursorMapper,
                              WeComCredentialProtector credentialProtector,
                              WeComMessageProjector projector,
                              EventHub eventHub) {
        this.messageMapper = messageMapper;
        this.cursorMapper = cursorMapper;
        this.credentialProtector = credentialProtector;
        this.projector = projector;
        this.eventHub = eventHub;
    }

    public String cursor(SyncKey key) throws WeComChatDataException {
        try {
            String value = cursorMapper.findValueByKey(keyField(key));
            return value == null ? "" : value;
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    @Transactional
    public PublishResult publishPage(SyncKey key, String nextCursor,
                                     List<DecryptedMessage> decrypted)
            throws WeComChatDataException {
        try {
            requireKey(key);
            if (nextCursor == null || nextCursor.length() > 128 || decrypted == null || decrypted.size() > 200) {
                throw new IllegalArgumentException("page invalid");
            }
            int stored = 0;
            int skipped = 0;
            boolean projected = false;
            for (DecryptedMessage item : decrypted) {
                Candidate candidate = project(item);
                if (candidate == null) {
                    skipped++;
                    continue;
                }
                WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
                entity.setMsgid(candidate.msgid());
                entity.setSecretKey(credentialProtector.protectSecretKey(candidate.secretKey()));
                entity.setExternalUserid(candidate.externalUserId());
                entity.setUserid(candidate.userId());
                entity.setSendTime(candidate.sendTime());
                entity.setMsgtype(candidate.msgType());
                entity.setDirection(candidate.direction());
                if (messageMapper.insertIgnore(entity) > 0) {
                    stored++;
                } else {
                    skipped++;
                }
                WeComMessageProjector.ProjectionResult result = projector.project(
                        new WeComMessageProjector.WeComProjectedMessage(
                                candidate.msgid(), candidate.externalUserId(), candidate.userId(),
                                candidate.sendTime(), candidate.direction()));
                projected |= result.inserted();
            }
            cursorMapper.upsert(keyField(key), nextCursor);
            if (projected) publishAfterCommit();
            return new PublishResult(stored, skipped);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    private void publishAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventHub.publish("message-new", "{}");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventHub.publish("message-new", "{}");
            }
        });
    }

    public List<StoredMessageReference> load(Instant fromInclusive, Instant toExclusive)
            throws WeComChatDataException {
        try {
            if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("time window invalid");
            }
            long from = fromInclusive.getEpochSecond();
            long to = toExclusive.getEpochSecond();
            List<StoredMessageReference> result = new ArrayList<>();
            for (WeComChatDataMessageEntity entity : messageMapper.findBySendTimeRange(from, to)) {
                result.add(new StoredMessageReference(entity.getMsgid(),
                        credentialProtector.revealSecretKey(entity.getSecretKey()),
                        entity.getExternalUserid(), entity.getUserid(), entity.getSendTime(),
                        entity.getMsgtype()));
            }
            return List.copyOf(result);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    private Candidate project(DecryptedMessage decrypted) {
        if (decrypted == null || decrypted.message() == null || decrypted.secretKey() == null
                || decrypted.secretKey().isBlank() || decrypted.secretKey().length() > 512) return null;
        WeComChatDataGateway.EncryptedMessage message = decrypted.message();
        if (message.receivers() == null || message.receivers().size() != 1 || message.sender() == null) return null;
        WeComChatDataGateway.Party receiver = message.receivers().get(0);
        String userId;
        String externalUserId;
        if (message.sender().type() == 1 && receiver.type() == 2) {
            userId = message.sender().id();
            externalUserId = receiver.id();
        } else if (message.sender().type() == 2 && receiver.type() == 1) {
            externalUserId = message.sender().id();
            userId = receiver.id();
        } else {
            return null;
        }
        if (!bounded(message.msgid(), 256) || !bounded(userId, 128) || !bounded(externalUserId, 128)
                || message.sendTime() < 0) return null;
        String direction = message.sender().type() == 1 ? "outbound" : "inbound";
        return new Candidate(message.msgid(), decrypted.secretKey(), externalUserId, userId,
                message.sendTime(), Integer.toString(message.msgType()), direction);
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static void requireKey(SyncKey key) {
        if (key == null || !bounded(key.installationId(), 128) || key.version() < 1
                || !bounded(key.programId(), 128) || !bounded(key.abilityId(), 128)) {
            throw new IllegalArgumentException("sync key invalid");
        }
    }

    private static String keyField(SyncKey key) throws Exception {
        requireKey(key);
        String value = key.installationId() + "\n" + key.version() + "\n"
                + key.programId() + "\n" + key.abilityId();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static WeComChatDataException storeFailed(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_STORE_FAILED", 500,
                "企业微信会话索引保存失败", cause);
    }

    public record SyncKey(String installationId, long version, String programId, String abilityId) {}
    public record DecryptedMessage(WeComChatDataGateway.EncryptedMessage message, String secretKey) {}
    public record PublishResult(int stored, int skipped) {}
    public record StoredMessageReference(String msgid, String secretKey, String externalUserId,
                                         String userId, long sendTime, String msgType) {}

    private record Candidate(String msgid, String secretKey, String externalUserId, String userId,
                             long sendTime, String msgType, String direction) {}
}
