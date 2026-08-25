package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComLoginAttemptServiceTest {

    private static final ViewerAuditSink NO_OP_AUDIT = new ViewerAuditSink() {
        @Override public void record(String action, String result, String userId,
                                      String contactPointId, String sessionId) { }
        @Override public void recordDiagnostic(String action, String result, String userId,
                                               String contactPointId, String sessionId,
                                               String errorCode, Integer upstreamErrcode,
                                               String upstreamPath, Integer upstreamHttpStatus,
                                               String upstreamHint) { }
    };

    @Mock AppConfig config;
    @Mock WeComInstallationService installationService;

    @Test
    void createsAndConsumesSingleUseState() {
        stubConfig(2);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L));

        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", NO_OP_AUDIT);

        WeComLoginAttemptService.LoginAttemptResponse first = service.createAttempt();
        assertEquals("CorpApp", first.loginType());
        assertEquals("ww-test-corp", first.appId());
        assertEquals("https://crm.example.com/", first.redirectUri());
        assertEquals(30, first.expiresIn());
        assertEquals("1000247", first.agentId());

        WeComLoginAttemptService.InstallationBinding binding = service.consume(first.state());
        assertEquals("dk-test-suite", binding.suiteId());
        assertEquals("ww-test-corp", binding.authCorpId());
        assertEquals("1000247", binding.agentId());
        assertEquals(1L, binding.version());

        assertThrows(SecurityException.class, () -> service.consume(first.state()));
    }

    @Test
    void boundsPendingAttempts() {
        stubConfig(2);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L));
        java.util.concurrent.atomic.AtomicInteger nonce = new java.util.concurrent.atomic.AtomicInteger();

        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> String.format("%032d", nonce.incrementAndGet()), NO_OP_AUDIT);

        service.createAttempt();
        service.createAttempt();
        assertThrows(WeComLoginAttemptService.PendingLimitException.class, service::createAttempt);
    }

    @Test
    void rejectsInstallationChangeOnConsume() {
        stubConfig(2);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L), installation(2L));

        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", NO_OP_AUDIT);

        WeComLoginAttemptService.LoginAttemptResponse attempt = service.createAttempt();
        WeComException error = assertThrows(WeComException.class, () -> service.consume(attempt.state()));
        assertEquals("WECOM_INSTALLATION_CHANGED", error.code());
        assertEquals(403, error.httpStatus());
    }

    @Test
    void rejectsMissingLoginAuthCorpId() {
        when(config.wecomSuiteId()).thenReturn("dk-test-suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("");

        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> "cccccccccccccccccccccccccccccccc", NO_OP_AUDIT);

        WeComException error = assertThrows(WeComException.class, service::createAttempt);
        assertEquals("WECOM_LOGIN_INSTALLATION_NOT_SELECTED", error.code());
    }

    @Test
    void wrongUserCannotConsumeBindingAttemptBeforeTargetUserRetries() {
        stubConfig(2);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L));
        UUID targetUserId = UUID.randomUUID();
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> "dddddddddddddddddddddddddddddddd", NO_OP_AUDIT);
        var attempt = service.createBindingAttempt(targetUserId, "127.0.0.1");

        WeComException denied = assertThrows(WeComException.class,
                () -> service.executeOnce(attempt.state(), WeComLoginAttemptService.Purpose.BIND,
                        UUID.randomUUID(), "authorization-code", ignored -> "unexpected"));
        assertEquals("WECOM_LOGIN_PURPOSE_MISMATCH", denied.code());

        String result = service.executeOnce(attempt.state(), WeComLoginAttemptService.Purpose.BIND,
                targetUserId, "authorization-code", ignored -> "bound");
        assertEquals("bound", result);
    }

    @Test
    void completedReplayDoesNotConsumePendingAttemptCapacity() {
        stubConfig(1);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L));
        AtomicInteger nonce = new AtomicInteger();
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> String.format("%032d", nonce.incrementAndGet()), NO_OP_AUDIT);

        var first = service.createAttempt();
        assertEquals("first", service.executeOnce(first.state(), WeComLoginAttemptService.Purpose.LOGIN,
                null, "first-code", ignored -> "first"));

        var second = service.createAttempt();
        assertEquals("second", service.executeOnce(second.state(), WeComLoginAttemptService.Purpose.LOGIN,
                null, "second-code", ignored -> "second"));
        assertThrows(SecurityException.class,
                () -> service.executeOnce(first.state(), WeComLoginAttemptService.Purpose.LOGIN,
                        null, "first-code", ignored -> "must-not-run"));
    }

    @Test
    void concurrentReplayReturnsRetryableInProgressErrorWithoutWaiting() throws Exception {
        stubConfig(2);
        when(installationService.resolveInstallation("dk-test-suite", "ww-test-corp"))
                .thenReturn(installation(1L));
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(config, installationService,
                Clock.systemUTC(), () -> "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", NO_OP_AUDIT);
        var attempt = service.createAttempt();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var leader = executor.submit(() -> service.executeOnce(attempt.state(),
                    WeComLoginAttemptService.Purpose.LOGIN, null, "authorization-code", ignored -> {
                        started.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        return "logged-in";
                    }));
            if (!started.await(1, TimeUnit.SECONDS)) throw new AssertionError("leader did not start");

            WeComException error = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofMillis(250),
                    () -> assertThrows(WeComException.class,
                            () -> service.executeOnce(attempt.state(), WeComLoginAttemptService.Purpose.LOGIN,
                                    null, "authorization-code", ignored -> "duplicate")));
            assertEquals("WECOM_LOGIN_EXCHANGE_IN_PROGRESS", error.code());
            assertEquals(409, error.httpStatus());

            release.countDown();
            assertEquals("logged-in", leader.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void stubConfig(int maxPending) {
        when(config.wecomSuiteId()).thenReturn("dk-test-suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("ww-test-corp");
        when(config.wecomLoginRedirectUri()).thenReturn("https://crm.example.com/");
        when(config.wecomLoginAttemptTtlSeconds()).thenReturn(30);
        when(config.wecomLoginMaxPending()).thenReturn(maxPending);
    }

    private static ResolvedInstallation installation(long version) {
        return new ResolvedInstallation("installation-1", "dk-test-suite", "ww-test-corp",
                "1000247", "permanent-code", version);
    }
}
