package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComAuthorizationRecoveryTest {

    @Test
    void acceptedAttemptIsClosedAsInterruptedWithoutReadingInstallation() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("create_auth");
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "accepted", "wwcorp")));

        new WeComAuthorizationRecovery(audit, installations).reconcile();

        verify(audit).failed(eq(attempt), eq("wwcorp"), argThat(
                failure -> failure.code().equals("WECOM_AUTHORIZATION_PROCESS_INTERRUPTED")));
    }

    @Test
    void pendingSuiteTicketIsClosedAsInterrupted() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("suite_ticket");
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "pending", "")));

        new WeComAuthorizationRecovery(audit, installations).reconcile();

        verify(audit).failed(eq(attempt), eq(""), argThat(
                failure -> failure.code().equals("WECOM_AUTHORIZATION_PROCESS_INTERRUPTED")));
    }

    @Test
    void pendingMutationIsSucceededOnlyWhenInstallationCarriesSameEventId() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("change_auth");
        var installation = new WeComInstallationEntity();
        installation.setLastAuthorizationEventId(attempt.eventId());
        installation.setAuthStatus("ACTIVE");
        installation.setVersion(1L);
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "pending", "wwcorp")));
        when(installations.find("suite", "wwcorp")).thenReturn(installation);

        new WeComAuthorizationRecovery(audit, installations).reconcile();

        verify(audit).succeeded(attempt, "wwcorp");
    }

    @Test
    void pendingMutationWithWrongTargetStatusRequiresManualReconciliation() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("change_auth");
        var installation = new WeComInstallationEntity();
        installation.setLastAuthorizationEventId(attempt.eventId());
        installation.setAuthStatus("REVOKED");
        installation.setVersion(1L);
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "pending", "wwcorp")));
        when(installations.find("suite", "wwcorp")).thenReturn(installation);

        assertThatThrownBy(() -> new WeComAuthorizationRecovery(audit, installations).reconcile())
                .isInstanceOf(WeComException.class)
                .extracting(error -> ((WeComException) error).code())
                .isEqualTo("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED");
    }

    @Test
    void pendingMutationWithImpossibleVersionRequiresManualReconciliation() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("change_auth");
        var installation = new WeComInstallationEntity();
        installation.setLastAuthorizationEventId(attempt.eventId());
        installation.setAuthStatus("ACTIVE");
        installation.setVersion(3L);
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "pending", "wwcorp")));
        when(installations.find("suite", "wwcorp")).thenReturn(installation);

        assertThatThrownBy(() -> new WeComAuthorizationRecovery(audit, installations).reconcile())
                .isInstanceOf(WeComException.class)
                .extracting(error -> ((WeComException) error).code())
                .isEqualTo("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED");
    }

    @Test
    void unresolvedPendingMutationRequiresManualReconciliation() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var attempt = attempt("cancel_auth");
        var installation = new WeComInstallationEntity();
        installation.setLastAuthorizationEventId("sha256:" + "b".repeat(64));
        when(audit.openAttempts()).thenReturn(List.of(open(attempt, "pending", "wwcorp")));
        when(installations.find("suite", "wwcorp")).thenReturn(installation);

        assertThatThrownBy(() -> new WeComAuthorizationRecovery(audit, installations).reconcile())
                .isInstanceOf(WeComException.class)
                .extracting(error -> ((WeComException) error).code())
                .isEqualTo("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED");
    }

    private static WeComAuthorizationAuditTrail.Attempt attempt(String type) {
        return new WeComAuthorizationAuditTrail.Attempt("sha256:" + "a".repeat(64), 1,
                "wecom.authorization." + type);
    }

    private static WeComAuthorizationAuditTrail.OpenAttempt open(
            WeComAuthorizationAuditTrail.Attempt attempt, String phase, String corpId) {
        return new WeComAuthorizationAuditTrail.OpenAttempt(
                attempt, "suite", corpId, phase, "ACTIVE", 0L);
    }
}
