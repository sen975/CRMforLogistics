package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComAuthorizationAuditMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Required, credential-free authorization audit state machine. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuthorizationAuditTrail {
    private final WeComAuthorizationAuditMapper mapper;

    public WeComAuthorizationAuditTrail(WeComAuthorizationAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public synchronized BeginResult begin(WeComCallbackCodec.DecodedCallback callback) {
        requireCallback(callback);
        String eventId = eventId(callback);
        mapper.lockEvent(eventId);
        if (mapper.countSucceeded(eventId) > 0) {
            return new BeginResult(BeginDisposition.ALREADY_SUCCEEDED, null);
        }
        WeComAuthorizationAuditEntity open = mapper.findOpenAttempt(eventId);
        if (open != null) {
            return new BeginResult(BeginDisposition.OPEN_ALREADY_ACCEPTED, attempt(open));
        }
        Attempt attempt = new Attempt(eventId, Math.addExact(mapper.maxAttempt(eventId), 1),
                action(callback.infoType()));
        insertRequired(entity(callback, attempt, "accepted", callback.authCorpId(), null, null, null));
        return new BeginResult(BeginDisposition.NEW_ATTEMPT, attempt);
    }

    public boolean alreadySucceeded(String eventId) {
        requireEventId(eventId);
        return mapper.countSucceeded(eventId) > 0;
    }

    @Transactional
    public void pending(Attempt attempt, String authCorpId, String targetStatus, long expectedVersion) {
        requireAttempt(attempt);
        mapper.lockEvent(attempt.eventId());
        if (!targetStatus.matches("ACTIVE|REVOKED|FAILED") || expectedVersion < 0) {
            throw new IllegalArgumentException("authorization pending fields are invalid");
        }
        insertRequired(entity(null, attempt, "pending", authCorpId, targetStatus, expectedVersion, null));
    }

    @Transactional
    public void succeeded(Attempt attempt, String authCorpId) {
        requireAttempt(attempt);
        mapper.lockEvent(attempt.eventId());
        WeComAuthorizationAuditEntity open = openAttempt(attempt);
        if (!"pending".equals(open.getResult())) {
            throw new IllegalStateException("authorization audit success requires pending phase");
        }
        insertRequired(entity(null, attempt, "succeeded", authCorpId, null, null, null));
    }

    @Transactional
    public void failed(Attempt attempt, String authCorpId, WeComException failure) {
        requireAttempt(attempt);
        mapper.lockEvent(attempt.eventId());
        if (failure == null) throw new IllegalArgumentException("authorization failure is required");
        insertRequired(entity(null, attempt, "failed", authCorpId, null, null, failure));
    }

    public List<OpenAttempt> openAttempts() {
        return mapper.findOpenAttempts().stream().map(entity -> new OpenAttempt(
                attempt(entity), entity.getSuiteId(), value(entity.getAuthCorpId()),
                entity.getResult(), value(entity.getTargetStatus()),
                entity.getExpectedVersion() == null ? 0L : entity.getExpectedVersion())).toList();
    }

    String eventId(WeComCallbackCodec.DecodedCallback callback) {
        requireCallback(callback);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, callback.infoType());
            update(digest, callback.suiteId());
            update(digest, callback.authCorpId());
            update(digest, callback.timestamp().toString());
            update(digest, sensitiveDigest(callback.authCode()));
            update(digest, sensitiveDigest(callback.suiteTicket()));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private WeComAuthorizationAuditEntity entity(
            WeComCallbackCodec.DecodedCallback callback, Attempt attempt, String result,
            String authCorpId, String targetStatus, Long expectedVersion, WeComException failure) {
        WeComAuthorizationAuditEntity entity = new WeComAuthorizationAuditEntity();
        entity.setId(UUID.randomUUID());
        entity.setOccurredAt(Instant.now());
        entity.setAction(attempt.action());
        entity.setResult(result);
        entity.setSuiteId(callback == null ? suiteIdFor(attempt) : bounded(callback.suiteId(), 128));
        if (authCorpId != null && !authCorpId.isBlank()) entity.setAuthCorpId(bounded(authCorpId, 128));
        entity.setEventId(attempt.eventId());
        entity.setAttempt(attempt.attempt());
        entity.setTargetStatus(targetStatus);
        entity.setExpectedVersion(expectedVersion);
        if (failure != null) {
            entity.setErrorCode(bounded(failure.code(), 128));
            entity.setUpstreamErrcode(failure.upstreamErrcode());
            entity.setUpstreamPath(failure.upstreamPath());
            entity.setUpstreamHttpStatus(failure.upstreamHttpStatus());
            entity.setUpstreamHint(failure.upstreamHint());
        }
        return entity;
    }

    private String suiteIdFor(Attempt attempt) {
        return bounded(openAttempt(attempt).getSuiteId(), 128);
    }

    private WeComAuthorizationAuditEntity openAttempt(Attempt attempt) {
        WeComAuthorizationAuditEntity open = mapper.findOpenAttempt(attempt.eventId());
        if (open == null || open.getAttempt() == null || open.getAttempt() != attempt.attempt()) {
            throw new IllegalStateException("authorization audit attempt is not open");
        }
        return open;
    }

    private void insertRequired(WeComAuthorizationAuditEntity entity) {
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("required authorization audit insert failed");
        }
    }

    private static Attempt attempt(WeComAuthorizationAuditEntity entity) {
        if (entity.getEventId() == null || entity.getAttempt() == null || entity.getAction() == null) {
            throw new IllegalStateException("authorization audit row is invalid");
        }
        return new Attempt(entity.getEventId(), entity.getAttempt(), entity.getAction());
    }

    private static String action(String infoType) {
        return bounded("wecom.authorization." + bounded(infoType, 44), 64);
    }

    private static String sensitiveDigest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value(value).getBytes(StandardCharsets.UTF_8)));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireCallback(WeComCallbackCodec.DecodedCallback callback) {
        if (callback == null || callback.timestamp() == null) {
            throw new IllegalArgumentException("authorization callback is invalid");
        }
        bounded(callback.suiteId(), 128);
        bounded(callback.infoType(), 44);
        if (callback.authCorpId() != null && !callback.authCorpId().isBlank()) {
            bounded(callback.authCorpId(), 128);
        }
    }

    private static void requireAttempt(Attempt attempt) {
        if (attempt == null || attempt.attempt() < 1
                || !attempt.action().matches("wecom\\.authorization\\.[A-Za-z0-9_]{1,44}")) {
            throw new IllegalArgumentException("authorization audit attempt is invalid");
        }
        requireEventId(attempt.eventId());
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || !eventId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("authorization event id is invalid");
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("authorization audit field is invalid");
        }
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public enum BeginDisposition { NEW_ATTEMPT, OPEN_ALREADY_ACCEPTED, ALREADY_SUCCEEDED }

    public record Attempt(String eventId, int attempt, String action) {}
    public record BeginResult(BeginDisposition disposition, Attempt attempt) {}
    public record OpenAttempt(Attempt attempt, String suiteId, String authCorpId, String phase,
                              String targetStatus, long expectedVersion) {}
}
