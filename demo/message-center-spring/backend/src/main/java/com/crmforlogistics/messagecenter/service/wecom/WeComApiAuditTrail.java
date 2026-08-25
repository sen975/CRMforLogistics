package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComApiAuditEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComApiAuditMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
public class WeComApiAuditTrail {
    private final WeComApiAuditMapper mapper;
    private final Clock clock;

    @Autowired
    public WeComApiAuditTrail(WeComApiAuditMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    WeComApiAuditTrail(WeComApiAuditMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Attempt begin(UUID actorUserId, ResolvedInstallation installation,
                         String action, String upstreamPath, String traceId) {
        UUID installationId = parseInstallationId(installation);
        if (actorUserId == null || action == null
                || !action.matches("wecom\\.api\\.[a-z0-9_.]{1,90}")
                || upstreamPath == null || !upstreamPath.matches("/cgi-bin/[a-z0-9_/-]{1,240}")
                || traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("WeCom API audit attempt is invalid");
        }
        Attempt attempt = new Attempt(UUID.randomUUID(), installationId, actorUserId,
                action, upstreamPath, traceId);
        insert(entity(attempt, "accepted", null));
        return attempt;
    }

    public void success(Attempt attempt) {
        insert(entity(requireAttempt(attempt), "success", null));
    }

    public void failed(Attempt attempt, WeComException exception) {
        Objects.requireNonNull(exception, "exception");
        String result = exception.httpStatus() == 403 ? "denied" : "failed";
        insert(entity(requireAttempt(attempt), result, exception));
    }

    private WeComApiAuditEntity entity(Attempt attempt, String result, WeComException error) {
        WeComApiAuditEntity entity = new WeComApiAuditEntity();
        entity.setId(UUID.randomUUID());
        entity.setOperationId(attempt.operationId());
        entity.setOccurredAt(clock.instant());
        entity.setInstallationId(attempt.installationId());
        entity.setActorUserId(attempt.actorUserId());
        entity.setAction(attempt.action());
        entity.setResult(result);
        entity.setUpstreamPath(attempt.upstreamPath());
        entity.setTraceId(attempt.traceId());
        if (error != null) {
            entity.setErrorCode(boundedCode(error.code()));
            entity.setUpstreamErrcode(error.upstreamErrcode());
            entity.setUpstreamHttpStatus(error.upstreamHttpStatus());
            entity.setUpstreamHint(boundedHint(error.upstreamHint()));
        }
        return entity;
    }

    private void insert(WeComApiAuditEntity entity) {
        try {
            if (mapper.insert(entity) != 1) throw new IllegalStateException("audit insert failed");
        } catch (RuntimeException failure) {
            throw new WeComException("WECOM_API_AUDIT_UNAVAILABLE", 503,
                    "企业微信操作审计暂时不可用");
        }
    }

    private static Attempt requireAttempt(Attempt attempt) {
        return Objects.requireNonNull(attempt, "attempt");
    }

    private static UUID parseInstallationId(ResolvedInstallation installation) {
        try {
            return UUID.fromString(Objects.requireNonNull(installation, "installation").installationId());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("WeCom installation audit identity is invalid");
        }
    }

    private static String boundedCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,128}") ? value : "WECOM_API_FAILED";
    }

    private static String boundedHint(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}") ? value : null;
    }

    public record Attempt(UUID operationId, UUID installationId, UUID actorUserId,
                          String action, String upstreamPath, String traceId) {}
}
