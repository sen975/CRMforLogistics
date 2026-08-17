package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComAuthorizationAuditMapper;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import java.time.Instant;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T10:15:30Z");

    @Test
    void acceptedIsRequiredBeforeQueueing() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("create_auth", "wwcorp", "auth-code", "");
        doThrow(new IllegalStateException("db down")).when(fixture.audit).begin(callback);

        var ack = fixture.service.handle(callback);

        assertThat(ack.success()).isFalse();
        verifyNoInteractions(fixture.gateway, fixture.mutations, fixture.installations);
        assertThat(fixture.queue).isEmpty();
    }

    @Test
    void workerWritesPendingThenCommitsOnlyFirstAgentAndSuccessThroughTransactionalOwner() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("create_auth", "", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(fixture.gateway.getPermanentCode("auth-code"))
                .thenReturn(new WeComAuthorizationGateway.PermanentCodeResponse("wwcorp", "permanent"));
        when(fixture.gateway.getAuthInfo("wwcorp", "permanent"))
                .thenReturn(new WeComAuthorizationGateway.AuthorizationInfo("wwcorp", List.of(
                        new WeComAuthorizationGateway.AuthorizedAgent("agent-first"),
                        new WeComAuthorizationGateway.AuthorizedAgent("agent-ignored"))));
        when(fixture.installations.find("suite", "wwcorp")).thenReturn(null);

        assertThat(fixture.service.handle(callback).success()).isTrue();
        assertThat(fixture.service.runNext()).isTrue();

        InOrder order = inOrder(fixture.audit, fixture.mutations);
        order.verify(fixture.audit).pending(attempt, "wwcorp", "ACTIVE", 0L);
        order.verify(fixture.mutations).applyActive(
                attempt, callback, "wwcorp", "agent-first", "permanent", 0L);
        verify(fixture.mutations, never()).applyActive(
                any(), any(), anyString(), eq("agent-ignored"), anyString(), anyLong());
    }

    @Test
    void queueFullClosesAttemptAndRequestsRetry() {
        @SuppressWarnings("unchecked")
        BlockingQueue<WeComAuthorizationService.AuthorizationEvent> queue = mock(BlockingQueue.class);
        Fixture fixture = fixture(queue);
        var callback = callback("create_auth", "wwcorp", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(queue.offer(any())).thenReturn(false);

        assertThat(fixture.service.handle(callback).success()).isFalse();

        verify(fixture.audit).failed(eq(attempt), eq("wwcorp"),
                org.mockito.ArgumentMatchers.argThat(
                        error -> error.code().equals("WECOM_AUTHORIZATION_QUEUE_FULL")));
        verifyNoInteractions(fixture.gateway, fixture.mutations, fixture.installations);
    }

    @Test
    void openAttemptRequestsRetryBecauseAcceptedMayHaveBeenLostFromQueue() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("create_auth", "wwcorp", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(new WeComAuthorizationAuditTrail.BeginResult(
                WeComAuthorizationAuditTrail.BeginDisposition.OPEN_ALREADY_ACCEPTED, attempt));

        assertThat(fixture.service.handle(callback).success()).isFalse();

        verify(fixture.audit).begin(callback);
        verifyNoInteractions(fixture.gateway, fixture.mutations, fixture.installations);
        assertThat(fixture.queue).isEmpty();
    }

    @Test
    void closeWaitsForAdmissionToFinishQueueOffer() throws Exception {
        @SuppressWarnings("unchecked")
        BlockingQueue<WeComAuthorizationService.AuthorizationEvent> queue = mock(BlockingQueue.class);
        Fixture fixture = fixture(queue);
        var callback = callback("create_auth", "wwcorp", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        CountDownLatch offerEntered = new CountDownLatch(1);
        CountDownLatch releaseOffer = new CountDownLatch(1);
        when(queue.offer(any())).thenAnswer(invocation -> {
            offerEntered.countDown();
            assertThat(releaseOffer.await(2, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        var ack = new java.util.concurrent.atomic.AtomicReference<WeComAuthorizationService.CallbackAck>();
        Thread handler = new Thread(() -> ack.set(fixture.service.handle(callback)));
        handler.start();
        assertThat(offerEntered.await(2, TimeUnit.SECONDS)).isTrue();

        CountDownLatch closeDone = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            fixture.service.close();
            closeDone.countDown();
        });
        closer.start();
        assertThat(closeDone.await(200, TimeUnit.MILLISECONDS)).isFalse();

        releaseOffer.countDown();
        handler.join(2_000L);
        closer.join(2_000L);
        assertThat(ack.get()).isNotNull();
        assertThat(ack.get().success()).isTrue();
        assertThat(closeDone.getCount()).isZero();
    }

    @Test
    void suiteTicketUsesRequiredAuditAndRunsSynchronously() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("suite_ticket", "", "", "ticket-secret");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));

        assertThat(fixture.service.handle(callback).success()).isTrue();

        InOrder order = inOrder(fixture.audit, fixture.gateway);
        order.verify(fixture.audit).pending(attempt, "", "ACTIVE", 0L);
        order.verify(fixture.gateway).acceptSuiteTicket("suite", "ticket-secret", NOW);
        order.verify(fixture.audit).succeeded(attempt, "");
        verifyNoInteractions(fixture.mutations, fixture.installations);
    }

    @Test
    void cancellationUsesPendingAndTransactionalMutationSynchronously() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("cancel_auth", "wwcorp", "", "");
        var attempt = attempt();
        var installation = installation("wwcorp", 3L, null);
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(fixture.installations.find("suite", "wwcorp")).thenReturn(installation);

        assertThat(fixture.service.handle(callback).success()).isTrue();

        InOrder order = inOrder(fixture.audit, fixture.mutations);
        order.verify(fixture.audit).pending(attempt, "wwcorp", "REVOKED", 3L);
        order.verify(fixture.mutations).revoke(attempt, callback, installation, 3L);
    }

    @Test
    void unknownEventIsClosedAsFailed() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("future_event", "wwcorp", "", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));

        assertThat(fixture.service.handle(callback).success()).isFalse();

        verify(fixture.audit).failed(eq(attempt), eq("wwcorp"),
                org.mockito.ArgumentMatchers.argThat(
                        error -> error.code().equals("WECOM_CALLBACK_UNKNOWN_INFOTYPE")));
        verifyNoInteractions(fixture.gateway, fixture.mutations, fixture.installations);
    }

    @Test
    void reconciliationGateRejectsNewMutationBeforeAuditBegin() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        doThrow(new WeComException("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED", 503,
                "reconciliation required")).when(fixture.startupGate).requireOpen();

        assertThat(fixture.service.handle(
                callback("create_auth", "wwcorp", "auth-code", "")).success()).isFalse();

        verifyNoInteractions(fixture.audit, fixture.gateway, fixture.mutations, fixture.installations);
    }

    @Test
    void workerRejectsPreviouslyAcceptedMutationAfterReconciliationGateCloses() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("create_auth", "wwcorp", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        assertThat(fixture.service.handle(callback).success()).isTrue();
        doThrow(new WeComException("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED", 503,
                "reconciliation required")).when(fixture.startupGate).requireOpen();

        assertThat(fixture.service.runNext()).isTrue();

        verify(fixture.audit).failed(eq(attempt), eq("wwcorp"),
                org.mockito.ArgumentMatchers.argThat(error -> error.code()
                        .equals("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED")));
        verifyNoInteractions(fixture.gateway, fixture.mutations, fixture.installations);
    }

    @Test
    void eventIdIsStableLengthDelimitedAndDoesNotPersistSensitiveTicket() {
        WeComAuthorizationAuditMapper mapper = mock(WeComAuthorizationAuditMapper.class);
        WeComAuthorizationAuditTrail trail = new WeComAuthorizationAuditTrail(mapper);
        var callback = callback("suite_ticket", "", "", "ticket-secret");
        when(mapper.countSucceeded(anyString())).thenReturn(0);
        when(mapper.maxAttempt(anyString())).thenReturn(2);
        when(mapper.insert(any(
                com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity.class)))
                .thenReturn(1);

        var begin = trail.begin(callback);

        ArgumentCaptor<com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity> captor =
                ArgumentCaptor.forClass(
                        com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity.class);
        verify(mapper).insert((com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity)
                captor.capture());
        var accepted = captor.getValue();
        assertThat(begin.attempt().eventId()).startsWith("sha256:").hasSize(71);
        assertThat(begin.attempt().attempt()).isEqualTo(3);
        assertThat(accepted.getResult()).isEqualTo("accepted");
        assertThat(accepted.getEventId()).doesNotContain("ticket-secret");
        assertThat(trail.eventId(callback)).isEqualTo(begin.attempt().eventId());
    }

    @Test
    void acceptedInsertMustActuallyPersistBeforeReturningAnAttempt() {
        WeComAuthorizationAuditMapper mapper = mock(WeComAuthorizationAuditMapper.class);
        WeComAuthorizationAuditTrail trail = new WeComAuthorizationAuditTrail(mapper);
        var callback = callback("create_auth", "wwcorp", "auth-code", "");

        assertThatThrownBy(() -> trail.begin(callback))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eventIdIgnoresNonCredentialStateButChangesWithAuthCode() {
        WeComAuthorizationAuditTrail trail = new WeComAuthorizationAuditTrail(
                mock(WeComAuthorizationAuditMapper.class));
        var left = new WeComCallbackCodec.DecodedCallback(
                "suite", "create_auth", "wwcorp", "auth-code", "", "state-left", NOW);
        var right = new WeComCallbackCodec.DecodedCallback(
                "suite", "create_auth", "wwcorp", "auth-code", "", "state-right", NOW);
        var changedCredential = new WeComCallbackCodec.DecodedCallback(
                "suite", "create_auth", "wwcorp", "other-code", "", "state-left", NOW);

        assertThat(trail.eventId(left)).isEqualTo(trail.eventId(right));
        assertThat(trail.eventId(left)).isNotEqualTo(trail.eventId(changedCredential));
    }

    @Test
    void beginSerializesAttemptAllocationAndAcceptedInsert() throws Exception {
        assertThat(Modifier.isSynchronized(WeComAuthorizationAuditTrail.class
                .getMethod("begin", WeComCallbackCodec.DecodedCallback.class).getModifiers()))
                .isTrue();
        assertThat(WeComAuthorizationAuditTrail.class
                .getMethod("begin", WeComCallbackCodec.DecodedCallback.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void auditStateTransitionsAreTransactionalAndDatabaseLocked() throws Exception {
        assertThat(WeComAuthorizationAuditTrail.class.getMethod("pending",
                WeComAuthorizationAuditTrail.Attempt.class, String.class, String.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(WeComAuthorizationAuditTrail.class.getMethod("succeeded",
                WeComAuthorizationAuditTrail.Attempt.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(WeComAuthorizationAuditTrail.class.getMethod("failed",
                WeComAuthorizationAuditTrail.Attempt.class, String.class, WeComException.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        Select lock = WeComAuthorizationAuditMapper.class.getMethod("lockEvent", String.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", lock.value()))
                .containsIgnoringCase("pg_advisory_xact_lock")
                .contains("eventId");
    }

    @Test
    void succeededRequiresPendingPhase() {
        WeComAuthorizationAuditMapper mapper = mock(WeComAuthorizationAuditMapper.class);
        WeComAuthorizationAuditTrail trail = new WeComAuthorizationAuditTrail(mapper);
        var attempt = attempt();
        var accepted = new com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity();
        accepted.setEventId(attempt.eventId());
        accepted.setAttempt(attempt.attempt());
        accepted.setAction(attempt.action());
        accepted.setSuiteId("suite");
        accepted.setResult("accepted");
        when(mapper.findOpenAttempt(attempt.eventId())).thenReturn(accepted);

        assertThatThrownBy(() -> trail.succeeded(attempt, "wwcorp"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insert(any(
                com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity.class));
    }

    @Test
    void workerSelectsFirstValidAgent() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("create_auth", "", "auth-code", "");
        var attempt = attempt();
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(fixture.gateway.getPermanentCode("auth-code"))
                .thenReturn(new WeComAuthorizationGateway.PermanentCodeResponse("wwcorp", "permanent"));
        when(fixture.gateway.getAuthInfo("wwcorp", "permanent"))
                .thenReturn(new WeComAuthorizationGateway.AuthorizationInfo("wwcorp", List.of(
                        new WeComAuthorizationGateway.AuthorizedAgent(""),
                        new WeComAuthorizationGateway.AuthorizedAgent("agent-valid"))));

        assertThat(fixture.service.handle(callback).success()).isTrue();
        assertThat(fixture.service.runNext()).isTrue();

        verify(fixture.mutations).applyActive(
                attempt, callback, "wwcorp", "agent-valid", "permanent", 0L);
    }

    @Test
    void changeAuthorizationUsesRefreshableInstallationIncludingFailedStatus() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("change_auth", "wwcorp", "", "");
        var attempt = attempt();
        var current = installation("wwcorp", 4L, null);
        current.setAuthStatus("FAILED");
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(fixture.installations.resolveRefreshable("suite", "wwcorp"))
                .thenReturn(new ResolvedInstallation(
                        "installation", "suite", "wwcorp", "old-agent", "permanent", 4L));
        when(fixture.installations.find("suite", "wwcorp")).thenReturn(current);
        when(fixture.gateway.getAuthInfo("wwcorp", "permanent"))
                .thenReturn(new WeComAuthorizationGateway.AuthorizationInfo("wwcorp", List.of(
                        new WeComAuthorizationGateway.AuthorizedAgent("agent-valid"))));

        assertThat(fixture.service.handle(callback).success()).isTrue();
        assertThat(fixture.service.runNext()).isTrue();

        verify(fixture.mutations).applyActive(
                attempt, callback, "wwcorp", "agent-valid", "permanent", 4L);
    }

    @Test
    void resetPermanentCodeDoesNotReactivateRevokedInstallation() {
        Fixture fixture = fixture(new ArrayBlockingQueue<>(2));
        var callback = callback("reset_permanent_code", "", "auth-code", "");
        var attempt = attempt();
        var revoked = installation("wwcorp", 4L, null);
        revoked.setAuthStatus("REVOKED");
        when(fixture.audit.begin(callback)).thenReturn(newBegin(attempt));
        when(fixture.gateway.getPermanentCode("auth-code"))
                .thenReturn(new WeComAuthorizationGateway.PermanentCodeResponse("wwcorp", "permanent"));
        when(fixture.installations.resolveRefreshable("suite", "wwcorp"))
                .thenThrow(new WeComException("WECOM_INSTALLATION_INACTIVE", 403,
                        "企业微信授权安装已撤销或失效"));
        when(fixture.installations.find("suite", "wwcorp")).thenReturn(revoked);

        assertThat(fixture.service.handle(callback).success()).isTrue();
        assertThat(fixture.service.runNext()).isTrue();

        verify(fixture.audit).failed(eq(attempt), eq("wwcorp"),
                org.mockito.ArgumentMatchers.argThat(
                        error -> error.code().equals("WECOM_INSTALLATION_INACTIVE")));
        verify(fixture.gateway, never()).getAuthInfo(anyString(), anyString());
        verifyNoInteractions(fixture.mutations);
    }

    @Test
    void revokedInstallationWinsAgainstSameTimestampActiveEvent() {
        var mapper = mock(com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper.class);
        var gateway = mock(WeComAuthorizationGateway.class);
        var protector = mock(WeComCredentialProtector.class);
        var installations = new WeComInstallationService(mapper, gateway, protector);
        var current = installation("wwcorp", 4L, "sha256:" + "b".repeat(64));
        current.setAuthStatus("REVOKED");
        current.setLastAuthorizationEventAt(NOW);
        when(mapper.selectOne(any())).thenReturn(current);

        assertThatThrownBy(() -> installations.applyActiveForEvent(
                callback("create_auth", "wwcorp", "", ""), "wwcorp", "agent", "permanent",
                "sha256:" + "c".repeat(64), 4L))
                .isInstanceOf(WeComException.class)
                .hasMessageContaining("早于当前安装状态");
        verify(mapper, never()).updateForEvent(any(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyLong());
        verifyNoInteractions(protector);
    }

    @Test
    void activeInstallationSqlRejectsSameOrOlderEventAfterRevocation() throws Exception {
        Update update = WeComInstallationMapper.class.getMethod("updateForEvent",
                java.util.UUID.class, String.class, String.class, String.class, Instant.class,
                String.class, Instant.class, long.class).getAnnotation(Update.class);
        assertThat(String.join(" ", update.value()))
                .contains("auth_status <> 'REVOKED'")
                .contains("last_authorization_event_at < #{eventAt}");
    }

    @Test
    void mutationMethodsArePublicTransactionalOwners() throws Exception {
        assertThat(WeComAuthorizationMutationService.class.getMethod("applyActive",
                WeComAuthorizationAuditTrail.Attempt.class, WeComCallbackCodec.DecodedCallback.class,
                String.class, String.class, String.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(WeComAuthorizationMutationService.class.getMethod("revoke",
                WeComAuthorizationAuditTrail.Attempt.class, WeComCallbackCodec.DecodedCallback.class,
                WeComInstallationEntity.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void transactionalMutationCommitsInstallationBeforeSucceededAudit() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var mutation = new WeComAuthorizationMutationService(installations, audit);
        var attempt = attempt();
        var callback = callback("create_auth", "", "auth-code", "");

        mutation.applyActive(attempt, callback, "wwcorp", "agent-first", "permanent", 3L);

        InOrder order = inOrder(installations, audit);
        order.verify(installations).applyActiveForEvent(
                callback, "wwcorp", "agent-first", "permanent", attempt.eventId(), 3L);
        order.verify(audit).succeeded(attempt, "wwcorp");
    }

    @Test
    void transactionalRevocationCommitsInstallationBeforeSucceededAudit() {
        var audit = mock(WeComAuthorizationAuditTrail.class);
        var installations = mock(WeComInstallationService.class);
        var mutation = new WeComAuthorizationMutationService(installations, audit);
        var attempt = attempt();
        var callback = callback("cancel_auth", "wwcorp", "", "");
        var installation = installation("wwcorp", 3L, null);

        mutation.revoke(attempt, callback, installation, 3L);

        InOrder order = inOrder(installations, audit);
        order.verify(installations).revokeForEvent(
                callback, installation, attempt.eventId(), 3L);
        order.verify(audit).succeeded(attempt, "wwcorp");
    }

    private static Fixture fixture(BlockingQueue<WeComAuthorizationService.AuthorizationEvent> queue) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        WeComAuthorizationAuditTrail audit = mock(WeComAuthorizationAuditTrail.class);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComAuthorizationGateway gateway = mock(WeComAuthorizationGateway.class);
        WeComAuthorizationMutationService mutations = mock(WeComAuthorizationMutationService.class);
        WeComStartupGate startupGate = mock(WeComStartupGate.class);
        var service = new WeComAuthorizationService(config, audit, installations, gateway,
                mutations, startupGate, queue, false);
        return new Fixture(service, audit, installations, gateway, mutations, startupGate, queue);
    }

    private static WeComAuthorizationAuditTrail.BeginResult newBegin(
            WeComAuthorizationAuditTrail.Attempt attempt) {
        return new WeComAuthorizationAuditTrail.BeginResult(
                WeComAuthorizationAuditTrail.BeginDisposition.NEW_ATTEMPT, attempt);
    }

    private static WeComAuthorizationAuditTrail.Attempt attempt() {
        return new WeComAuthorizationAuditTrail.Attempt("sha256:" + "a".repeat(64), 1,
                "wecom.authorization.create_auth");
    }

    private static WeComCallbackCodec.DecodedCallback callback(
            String infoType, String corpId, String authCode, String suiteTicket) {
        return new WeComCallbackCodec.DecodedCallback(
                "suite", infoType, corpId, authCode, suiteTicket, "", NOW);
    }

    private static WeComInstallationEntity installation(String corpId, long version, String eventId) {
        var entity = new WeComInstallationEntity();
        entity.setSuiteId("suite");
        entity.setAuthCorpId(corpId);
        entity.setVersion(version);
        entity.setLastAuthorizationEventId(eventId);
        return entity;
    }

    private record Fixture(
            WeComAuthorizationService service,
            WeComAuthorizationAuditTrail audit,
            WeComInstallationService installations,
            WeComAuthorizationGateway gateway,
            WeComAuthorizationMutationService mutations,
            WeComStartupGate startupGate,
            BlockingQueue<WeComAuthorizationService.AuthorizationEvent> queue) {}
}
