package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WeComCredentialMigrationRunnerTest {

    @Test
    void opensStartupGateOnlyAfterMigrationAndAuthorizationRecovery() {
        var migration = mock(WeComCredentialMigrationService.class);
        var recovery = mock(WeComAuthorizationRecovery.class);
        var gate = mock(WeComStartupGate.class);

        new WeComCredentialMigrationRunner(migration, recovery, gate).run(null);

        InOrder order = inOrder(migration, recovery, gate);
        order.verify(migration).migrateAll();
        order.verify(recovery).reconcile();
        order.verify(gate).open();
    }

    @Test
    void unresolvedAuthorizationAttemptKeepsGateClosedWithReconciliationCode() {
        var migration = mock(WeComCredentialMigrationService.class);
        var recovery = mock(WeComAuthorizationRecovery.class);
        var gate = mock(WeComStartupGate.class);
        doThrow(new WeComException("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED", 503,
                "manual reconciliation required")).when(recovery).reconcile();

        new WeComCredentialMigrationRunner(migration, recovery, gate).run(null);

        verify(gate).fail("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED",
                "manual reconciliation required");
        verify(gate, never()).open();
    }

    @Test
    void migrationFailurePreventsRecoveryAndGateOpening() {
        var migration = mock(WeComCredentialMigrationService.class);
        var recovery = mock(WeComAuthorizationRecovery.class);
        var gate = mock(WeComStartupGate.class);
        doThrow(new WeComException("WECOM_CREDENTIAL_MIGRATION_FAILED", 500, "failed"))
                .when(migration).migrateAll();

        assertThatThrownBy(() -> new WeComCredentialMigrationRunner(migration, recovery, gate).run(null))
                .isInstanceOf(WeComException.class);
        verify(recovery, never()).reconcile();
        verify(gate, never()).open();
    }
}
