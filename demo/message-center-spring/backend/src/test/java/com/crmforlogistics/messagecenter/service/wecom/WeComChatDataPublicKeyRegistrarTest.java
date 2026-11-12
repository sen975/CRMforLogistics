package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataPublicKeyGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChatDataPublicKeyRegistrarTest {

    @Test
    void repeatedRegistrationSignalsCoalesceWhileOneRegistrationIsRunning() throws Exception {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("wwcorp");
        WeComInstallationService installations = mock(WeComInstallationService.class);
        ResolvedInstallation installation = new ResolvedInstallation(
                "installation", "suite", "wwcorp", "agent", "permanent", 1L);
        when(installations.resolveInstallation("suite", "wwcorp")).thenReturn(installation);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        when(accessTokens.accessToken(installation)).thenReturn("access-token");
        WeComChatDataPublicKeyRegistrationStore store = mock(WeComChatDataPublicKeyRegistrationStore.class);
        WeComChatDataPublicKeyGateway gateway = mock(WeComChatDataPublicKeyGateway.class);
        WeComStartupGate gate = new WeComStartupGate();
        gate.open();
        WeComChatDataCrypto.PublicKeyMaterial material = new WeComChatDataCrypto.PublicKeyMaterial(
                "public-key", 1, "sha256", 2048);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(ignored -> {
            calls.incrementAndGet();
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(gateway).register("access-token", material);

        WeComChatDataPublicKeyRegistrar registrar = new WeComChatDataPublicKeyRegistrar(
                config, installations, accessTokens, store, gateway, gate, () -> material);
        try {
            registrar.requestRegistration();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            registrar.requestRegistration();
            release.countDown();
            verify(gateway, org.mockito.Mockito.timeout(2000).times(1))
                    .register("access-token", material);
            registrar.close();
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            registrar.close();
        }
    }

    @Test
    void successfulAuthorizationRequestsRegistrationAfterMutationReturns() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        WeComAuthorizationAuditTrail audit = mock(WeComAuthorizationAuditTrail.class);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComAuthorizationGateway gateway = mock(WeComAuthorizationGateway.class);
        WeComAuthorizationMutationService mutations = mock(WeComAuthorizationMutationService.class);
        WeComStartupGate gate = mock(WeComStartupGate.class);
        WeComChatDataPublicKeyRegistrar registrar = mock(WeComChatDataPublicKeyRegistrar.class);
        WeComAuthorizationAuditTrail.Attempt attempt = new WeComAuthorizationAuditTrail.Attempt(
                "sha256:" + "a".repeat(64), 1, "wecom.authorization.create_auth");
        WeComCallbackCodec.DecodedCallback callback = new WeComCallbackCodec.DecodedCallback(
                "suite", "create_auth", "", "auth-code", "", "", Instant.now());
        when(audit.begin(callback)).thenReturn(new WeComAuthorizationAuditTrail.BeginResult(
                WeComAuthorizationAuditTrail.BeginDisposition.NEW_ATTEMPT, attempt));
        when(gateway.getPermanentCode("auth-code"))
                .thenReturn(new WeComAuthorizationGateway.PermanentCodeResponse("wwcorp", "permanent"));
        when(gateway.getAuthInfo("wwcorp", "permanent"))
                .thenReturn(new WeComAuthorizationGateway.AuthorizationInfo(
                        "wwcorp", List.of(new WeComAuthorizationGateway.AuthorizedAgent("agent"))));

        WeComAuthorizationService service = new WeComAuthorizationService(
                config, audit, installations, gateway, mutations, gate, registrar,
                new ArrayBlockingQueue<>(1), false);

        assertThat(service.handle(callback).success()).isTrue();
        assertThat(service.runNext()).isTrue();

        var order = inOrder(mutations, registrar);
        order.verify(mutations).applyActive(attempt, callback, "wwcorp", "agent", "permanent", 0L);
        order.verify(registrar).requestRegistration();
        verify(registrar, never()).registerIfNeeded();
    }
}
