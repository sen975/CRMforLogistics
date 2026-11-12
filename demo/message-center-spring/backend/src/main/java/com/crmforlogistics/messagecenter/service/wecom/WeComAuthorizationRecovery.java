package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Reconciles incomplete required-audit attempts before callback readiness. */
@Service
@ConditionalOnWeComEnabled
public class WeComAuthorizationRecovery {
    private static final String INTERRUPTED = "WECOM_AUTHORIZATION_PROCESS_INTERRUPTED";
    private static final String RECONCILIATION_REQUIRED =
            "WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED";

    private final WeComAuthorizationAuditTrail audit;
    private final WeComInstallationService installations;

    @Autowired
    public WeComAuthorizationRecovery(ObjectProvider<WeComAuthorizationAuditTrail> audit,
                                      ObjectProvider<WeComInstallationService> installations) {
        this.audit = audit.getIfAvailable();
        this.installations = installations.getIfAvailable();
    }

    public WeComAuthorizationRecovery(WeComAuthorizationAuditTrail audit,
                                      WeComInstallationService installations) {
        this.audit = audit;
        this.installations = installations;
    }

    public void reconcile() {
        if (audit == null && installations == null) return;
        if (audit == null || installations == null) throw reconciliationRequired();
        for (WeComAuthorizationAuditTrail.OpenAttempt open : audit.openAttempts()) {
            if ("accepted".equals(open.phase())
                    || open.attempt().action().equals("wecom.authorization.suite_ticket")) {
                audit.failed(open.attempt(), open.authCorpId(), interrupted());
                continue;
            }
            WeComInstallationEntity current = open.authCorpId().isBlank() ? null
                    : installations.find(open.suiteId(), open.authCorpId());
            if (current != null
                    && open.attempt().eventId().equals(current.getLastAuthorizationEventId())
                    && open.targetStatus().equals(current.getAuthStatus())
                    && provesExpectedVersion(current.getVersion(), open.expectedVersion())) {
                audit.succeeded(open.attempt(), open.authCorpId());
                continue;
            }
            throw reconciliationRequired();
        }
    }

    private static WeComException interrupted() {
        return new WeComException(INTERRUPTED, 500, "企业微信授权处理被进程中断");
    }

    private static boolean provesExpectedVersion(Long actualVersion, long expectedVersion) {
        if (actualVersion == null || expectedVersion < 0) return false;
        return actualVersion == expectedVersion
                || (expectedVersion < Long.MAX_VALUE && actualVersion == expectedVersion + 1);
    }

    private static WeComException reconciliationRequired() {
        return new WeComException(RECONCILIATION_REQUIRED, 503,
                "企业微信授权审计存在无法自动证明的未闭合状态");
    }
}
