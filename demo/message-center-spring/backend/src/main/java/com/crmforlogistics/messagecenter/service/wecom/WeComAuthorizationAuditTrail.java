package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.mapper.WeComAuthorizationAuditMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists credential-free diagnostics for official Suite callbacks to the
 * {@code wecom_authorization_audit} table.
 */
@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuthorizationAuditTrail {
    private final WeComAuthorizationAuditMapper mapper;

    public WeComAuthorizationAuditTrail(WeComAuthorizationAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void record(WeComCallbackCodec.DecodedCallback callback, String authCorpId, String result,
                       WeComException failure) {
        if (callback == null || !result.matches("accepted|succeeded|failed")) {
            throw new IllegalArgumentException("authorization audit event is invalid");
        }
        WeComAuthorizationAuditEntity entity = new WeComAuthorizationAuditEntity();
        entity.setOccurredAt(Instant.now());
        entity.setAction(bounded("wecom.authorization." + callback.infoType(), 64));
        entity.setResult(result);
        entity.setSuiteId(bounded(callback.suiteId(), 128));
        String resolvedCorpId = authCorpId == null || authCorpId.isBlank() ? callback.authCorpId() : authCorpId;
        if (!resolvedCorpId.isBlank()) {
            entity.setAuthCorpId(bounded(resolvedCorpId, 128));
        }
        if (failure != null) {
            entity.setErrorCode(bounded(failure.code(), 128));
            entity.setUpstreamErrcode(failure.upstreamErrcode());
            entity.setUpstreamPath(failure.upstreamPath());
            entity.setUpstreamHttpStatus(failure.upstreamHttpStatus());
            entity.setUpstreamHint(failure.upstreamHint());
        }
        mapper.insert(entity);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("authorization audit field is invalid");
        }
        return value;
    }
}
