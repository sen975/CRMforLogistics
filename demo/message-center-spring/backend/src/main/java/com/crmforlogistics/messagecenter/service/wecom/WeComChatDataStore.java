package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataIngestFailureMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.entity.WeComChatDataIngestFailureEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataStore {
    private static final Logger log = LoggerFactory.getLogger(WeComChatDataStore.class);
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComChatDataCursorMapper cursorMapper;
    private final WeComCredentialProtector credentialProtector;
    private final WeComMessageProjector projector;
    private final EventHub eventHub;
    private final WeComChatDataRetention retention;
    private final WeComChatDataIngestFailureMapper failureMapper;
    private final WeComChatDataNormalizer normalizer;
    private final WeComPartyMapper partyMapper;
    private final WeComSourceConversationMapper sourceConversationMapper;
    private final WeComSourceParticipantMapper participantMapper;

    @Autowired
    public WeComChatDataStore(WeComChatDataMessageMapper messageMapper,
                              WeComChatDataCursorMapper cursorMapper,
                              WeComCredentialProtector credentialProtector,
                              WeComMessageProjector projector,
                              EventHub eventHub,
                              WeComChatDataRetention retention,
                              WeComChatDataIngestFailureMapper failureMapper,
                              WeComChatDataNormalizer normalizer,
                              WeComPartyMapper partyMapper,
                              WeComSourceConversationMapper sourceConversationMapper,
                              WeComSourceParticipantMapper participantMapper) {
        this.messageMapper = messageMapper;
        this.cursorMapper = cursorMapper;
        this.credentialProtector = credentialProtector;
        this.projector = projector;
        this.eventHub = eventHub;
        this.retention = retention;
        this.failureMapper = failureMapper;
        this.normalizer = normalizer;
        this.partyMapper = partyMapper;
        this.sourceConversationMapper = sourceConversationMapper;
        this.participantMapper = participantMapper;
    }

    WeComChatDataStore(WeComChatDataMessageMapper messageMapper,
                       WeComChatDataCursorMapper cursorMapper,
                       WeComCredentialProtector credentialProtector,
                       WeComMessageProjector projector,
                       EventHub eventHub) {
        this(messageMapper, cursorMapper, credentialProtector, projector, eventHub, null, null,
                null, null, null, null);
    }

    WeComChatDataStore(WeComChatDataMessageMapper messageMapper,
                       WeComChatDataCursorMapper cursorMapper,
                       WeComCredentialProtector credentialProtector,
                       WeComMessageProjector projector,
                       EventHub eventHub,
                       WeComChatDataRetention retention) {
        this(messageMapper, cursorMapper, credentialProtector, projector, eventHub, retention, null,
                null, null, null, null);
    }

    WeComChatDataStore(WeComChatDataMessageMapper messageMapper,
                       WeComChatDataCursorMapper cursorMapper,
                       WeComCredentialProtector credentialProtector,
                       WeComMessageProjector projector,
                       EventHub eventHub,
                       WeComChatDataRetention retention,
                       WeComChatDataIngestFailureMapper failureMapper) {
        this(messageMapper, cursorMapper, credentialProtector, projector, eventHub, retention, failureMapper,
                null, null, null, null);
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
            int duplicates = 0;
            int failed = 0;
            boolean projected = false;
            for (DecryptedMessage item : decrypted) {
                if (publishNormalizedGroup(key, item)) {
                    stored++;
                    projected = true;
                    continue;
                }
                if (publishNormalizedDirect(key, item)) {
                    stored++;
                    projected = true;
                    continue;
                }
                Candidate candidate = project(item);
                if (candidate == null) {
                    failed++;
                    persistFailure(key, nextCursor, item, "normalize", "WECOM_CHATDATA_MESSAGE_INVALID");
                    continue;
                }
                WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
                entity.setId(UUID.randomUUID());
                entity.setMsgid(candidate.msgid());
                entity.setSecretKey(credentialProtector.protectSecretKey(candidate.secretKey()));
                entity.setExternalUserid(candidate.externalUserId());
                entity.setUserid(candidate.userId());
                entity.setSendTime(candidate.sendTime());
                entity.setMsgtype(candidate.msgType());
                entity.setDirection(candidate.direction());
                entity.setIngestStatus(candidate.direction().isBlank() ? "stored" : "direct");
                entity.setInstallationId(parseUuid(key.installationId()));
                if (messageMapper.insertIgnore(entity) > 0) {
                    stored++;
                } else {
                    duplicates++;
                }
                WeComMessageProjector.ProjectionResult result = projector.project(
                        new WeComMessageProjector.WeComProjectedMessage(
                                candidate.msgid(), candidate.externalUserId(), candidate.userId(),
                                candidate.sendTime(), candidate.direction(), key.authCorpId()));
                projected |= result.inserted();
            }
            cursorMapper.upsert(keyField(key), nextCursor);
            runAfterCommit(projected);
            return new PublishResult(stored, duplicates + failed, failed, duplicates);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    private boolean publishNormalizedGroup(SyncKey key, DecryptedMessage item) {
        if (normalizer == null || partyMapper == null || sourceConversationMapper == null
                || participantMapper == null || item == null || item.message() == null
                || item.message().chatId() == null || item.message().chatId().isBlank()) return false;
        UUID installationId = parseUuid(key.installationId());
        if (installationId == null) return false;
        ResolvedInstallation installation = new ResolvedInstallation(key.installationId(), "", key.authCorpId(), "", "", key.version());
        WeComChatDataNormalizer.NormalizedWeComMessage normalized = normalizer.normalize(
                installation, item.message(), item.secretKey());
        UUID sourceId = sourceConversationMapper.upsertObserved(installationId,
                normalized.providerConversationKey(), normalized.conversationType());
        UUID senderId = partyMapper.upsertObserved(installationId, normalized.sender().partyType(),
                normalized.sender().providerPartyId(), "");
        participantMapper.observe(sourceId, senderId);
        for (WeComChatDataNormalizer.PartyRef receiver : normalized.receivers()) {
            UUID receiverId = partyMapper.upsertObserved(installationId, receiver.partyType(),
                    receiver.providerPartyId(), "");
            participantMapper.observe(sourceId, receiverId);
        }
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setInstallationId(installationId);
        entity.setSourceConversationId(sourceId);
        entity.setSenderPartyId(senderId);
        entity.setMsgid(normalized.msgid());
        entity.setSecretKey(credentialProtector.protectSecretKey(normalized.secretKey()));
        entity.setUserid(normalized.sender().partyType().equals("EMPLOYEE")
                ? normalized.sender().providerPartyId() : null);
        entity.setSendTime(normalized.sendTime());
        entity.setMsgtype(Integer.toString(normalized.msgType()));
        entity.setDirection(normalized.sender().partyType().equals("EMPLOYEE") ? "outbound" : "inbound");
        entity.setIngestStatus("group");
        if (messageMapper.insertIgnore(entity) == 0) return false;
        if ("GROUP".equals(normalized.conversationType())) {
            projector.projectGroup(new WeComMessageProjector.WeComProjectedGroupMessage(
                    normalized.msgid(), sourceId, normalized.sendTime(), entity.getDirection()));
        } else {
            projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                    normalized.msgid(), sourceId, installationId, normalized.authCorpId(),
                    normalized.contactParty() == null ? null : new WeComMessageProjector.ContactParty(
                            normalized.contactParty().partyType(), normalized.contactParty().providerPartyId()),
                    normalized.sendTime(), entity.getDirection()));
        }
        return true;
    }

    private boolean publishNormalizedDirect(SyncKey key, DecryptedMessage item) {
        if (normalizer == null || partyMapper == null || sourceConversationMapper == null
                || participantMapper == null || item == null || item.message() == null
                || item.message().chatId() != null && !item.message().chatId().isBlank()) return false;
        UUID installationId = parseUuid(key.installationId());
        if (installationId == null) return false;
        ResolvedInstallation installation = new ResolvedInstallation(key.installationId(), "",
                key.authCorpId(), "", "", key.version());
        WeComChatDataNormalizer.NormalizedWeComMessage normalized = normalizer.normalize(
                installation, item.message(), item.secretKey());
        if (!"DIRECT".equals(normalized.conversationType()) || normalized.contactParty() == null) return false;
        UUID sourceId = sourceConversationMapper.upsertObserved(installationId,
                normalized.providerConversationKey(), normalized.conversationType());
        UUID senderId = partyMapper.upsertObserved(installationId, normalized.sender().partyType(),
                normalized.sender().providerPartyId(), "");
        participantMapper.observe(sourceId, senderId);
        for (WeComChatDataNormalizer.PartyRef receiver : normalized.receivers()) {
            UUID receiverId = partyMapper.upsertObserved(installationId, receiver.partyType(),
                    receiver.providerPartyId(), "");
            participantMapper.observe(sourceId, receiverId);
        }
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setInstallationId(installationId);
        entity.setSourceConversationId(sourceId);
        entity.setSenderPartyId(senderId);
        entity.setMsgid(normalized.msgid());
        entity.setSecretKey(credentialProtector.protectSecretKey(normalized.secretKey()));
        entity.setExternalUserid("EXTERNAL_CONTACT".equals(normalized.contactParty().partyType())
                ? normalized.contactParty().providerPartyId() : null);
        entity.setUserid("EMPLOYEE".equals(normalized.contactParty().partyType())
                ? normalized.contactParty().providerPartyId() : null);
        entity.setSendTime(normalized.sendTime());
        entity.setMsgtype(Integer.toString(normalized.msgType()));
        entity.setDirection(normalized.sender().partyType().equals("EMPLOYEE") ? "outbound" : "inbound");
        entity.setIngestStatus("direct");
        if (messageMapper.insertIgnore(entity) == 0) return false;
        projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                normalized.msgid(), sourceId, installationId, normalized.authCorpId(),
                new WeComMessageProjector.ContactParty(normalized.contactParty().partyType(),
                        normalized.contactParty().providerPartyId()), normalized.sendTime(), entity.getDirection()));
        return true;
    }

    private void runAfterCommit(boolean publishMessageEvent) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommit(publishMessageEvent);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                WeComChatDataStore.this.afterCommit(publishMessageEvent);
            }
        });
    }

    private void afterCommit(boolean publishMessageEvent) {
        if (retention != null) {
            try {
                WeComChatDataRetention.RetentionResult result = retention.enforce();
                if (!result.withinBudget()) {
                    log.warn("WeCom chatdata retention remains above its configured budget");
                }
            } catch (RuntimeException failure) {
                log.warn("WeCom chatdata retention failed after page commit", failure);
            }
        }
        if (publishMessageEvent) eventHub.publish("message-new", "{}");
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

    private void persistFailure(SyncKey key, String nextCursor, DecryptedMessage item,
                                String stage, String errorCode) {
        if (failureMapper == null) return;
        UUID installationId = parseUuid(key.installationId());
        if (installationId == null) {
            throw new WeComChatDataException("WECOM_CHATDATA_INSTALLATION_INVALID", 422,
                    "企业微信会话安装实例标识无效");
        }
        WeComChatDataIngestFailureEntity failure = new WeComChatDataIngestFailureEntity();
        failure.setId(UUID.randomUUID());
        failure.setInstallationId(installationId);
        failure.setCursorKey(keyFieldUnchecked(key));
        failure.setCursorTo(nextCursor);
        failure.setFailureStage(stage);
        failure.setErrorCode(errorCode);
        failure.setRetryCount(0);
        if (item != null && item.message() != null && item.message().msgid() != null) {
            failure.setMsgidDigest(digest(item.message().msgid()));
        }
        if (failureMapper.insert(failure) != 1) {
            throw new WeComChatDataException("WECOM_CHATDATA_FAILURE_RECORD_FAILED", 500,
                    "企业微信会话失败事实保存失败");
        }
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException ignored) { return null; }
    }

    private static String keyFieldUnchecked(SyncKey key) {
        try { return keyField(key); } catch (Exception exception) {
            throw new IllegalArgumentException("sync key invalid", exception);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("digest unavailable", exception);
        }
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

    public record SyncKey(String installationId, long version, String programId, String abilityId,
                          String authCorpId) {
        public SyncKey(String installationId, long version, String programId, String abilityId) {
            this(installationId, version, programId, abilityId, "");
        }
    }
    public record DecryptedMessage(WeComChatDataGateway.EncryptedMessage message, String secretKey) {}
    public record PublishResult(int stored, int skipped, int failed, int duplicates) {
        public PublishResult(int stored, int skipped) {
            this(stored, skipped, 0, skipped);
        }
    }
    public record StoredMessageReference(String msgid, String secretKey, String externalUserId,
                                         String userId, long sendTime, String msgType) {}

    private record Candidate(String msgid, String secretKey, String externalUserId, String userId,
                             long sendTime, String msgType, String direction) {}
}
