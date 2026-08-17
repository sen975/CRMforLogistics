package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public transaction boundary for installation mutation plus final audit success. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuthorizationMutationService {
    private final WeComInstallationService installations;
    private final WeComAuthorizationAuditTrail audit;

    public WeComAuthorizationMutationService(WeComInstallationService installations,
                                             WeComAuthorizationAuditTrail audit) {
        this.installations = installations;
        this.audit = audit;
    }

    @Transactional
    public void applyActive(WeComAuthorizationAuditTrail.Attempt attempt,
                            WeComCallbackCodec.DecodedCallback callback,
                            String authCorpId, String agentId, String permanentCode,
                            long expectedVersion) {
        installations.applyActiveForEvent(callback, authCorpId, agentId, permanentCode,
                attempt.eventId(), expectedVersion);
        audit.succeeded(attempt, authCorpId);
    }

    @Transactional
    public void revoke(WeComAuthorizationAuditTrail.Attempt attempt,
                       WeComCallbackCodec.DecodedCallback callback,
                       WeComInstallationEntity installation, long expectedVersion) {
        installations.revokeForEvent(callback, installation, attempt.eventId(), expectedVersion);
        audit.succeeded(attempt, callback.authCorpId());
    }
}
