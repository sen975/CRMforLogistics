package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerAuditEntity;
import com.crmforlogistics.messagecenter.mapper.WeComViewerAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComViewerAuditTrail implements ViewerAuditSink {
    private static final Logger log = LoggerFactory.getLogger(WeComViewerAuditTrail.class);
    private static final List<String> RESULTS = List.of("success", "denied", "failed", "rate_limited");
    private final WeComViewerAuditMapper mapper;
    private final Clock clock;

    @Autowired
    public WeComViewerAuditTrail(WeComViewerAuditMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    WeComViewerAuditTrail(WeComViewerAuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void record(String action, String result, String wecomUserId,
                       String contactPointId, String viewerSessionId) {
        if (action == null || action.isBlank() || action.length() > 100 || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("WeCom viewer audit event is invalid");
        }
        WeComViewerAuditEntity entity = new WeComViewerAuditEntity();
        entity.setOccurredAt(clock.instant());
        entity.setAction(action);
        entity.setResult(result);
        entity.setWecomUserId(bounded(wecomUserId, 128));
        entity.setContactPointId(bounded(contactPointId, 256));
        entity.setViewerSessionId(bounded(viewerSessionId, 64));
        insert(entity);
    }

    @Override
    public void recordDiagnostic(String action, String result, String wecomUserId,
                                 String contactPointId, String viewerSessionId,
                                 String errorCode, Integer upstreamErrcode,
                                 String upstreamPath, Integer upstreamHttpStatus,
                                 String upstreamHint) {
        if (action == null || action.isBlank() || action.length() > 100 || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("WeCom viewer audit event is invalid");
        }
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,128}")) {
            throw new IllegalArgumentException("WeCom viewer diagnostic code is invalid");
        }
        if (upstreamErrcode != null && upstreamErrcode < 0) {
            throw new IllegalArgumentException("WeCom upstream error code is invalid");
        }
        if (upstreamPath != null && (upstreamPath.isBlank() || upstreamPath.length() > 256
                || !upstreamPath.startsWith("/"))) {
            throw new IllegalArgumentException("WeCom upstream path is invalid");
        }
        if (upstreamHttpStatus != null && (upstreamHttpStatus < 100 || upstreamHttpStatus > 599)) {
            throw new IllegalArgumentException("WeCom upstream HTTP status is invalid");
        }
        if (upstreamHint != null && !upstreamHint.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("WeCom upstream hint is invalid");
        }
        WeComViewerAuditEntity entity = new WeComViewerAuditEntity();
        entity.setOccurredAt(clock.instant());
        entity.setAction(action);
        entity.setResult(result);
        entity.setWecomUserId(bounded(wecomUserId, 128));
        entity.setContactPointId(bounded(contactPointId, 256));
        entity.setViewerSessionId(bounded(viewerSessionId, 64));
        entity.setErrorCode(errorCode);
        entity.setUpstreamErrcode(upstreamErrcode);
        entity.setUpstreamPath(upstreamPath);
        entity.setUpstreamHttpStatus(upstreamHttpStatus);
        entity.setUpstreamHint(upstreamHint);
        insert(entity);
    }

    private void insert(WeComViewerAuditEntity entity) {
        try {
            mapper.insert(entity);
        } catch (Exception failure) {
            log.warn("WeCom viewer audit write failed", failure);
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
