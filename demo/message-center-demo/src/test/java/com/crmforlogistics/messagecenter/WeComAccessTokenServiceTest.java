package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeComAccessTokenServiceTest {
    @Test
    void cachesByInstallationVersionAndRefreshesChangedInstallation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Duration> requestedTimeout = new AtomicReference<>();
        Config config = new Config(Map.of("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "300"));
        WeComAccessTokenService service = WeComAccessTokenService.forTests(config,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                (corpId, permanentCode, timeout) -> {
                    requestedTimeout.set(timeout);
                    return new WeComAuthorizationGateway.CorpTokenResponse(
                            "token-" + calls.incrementAndGet(), 7200);
                });

        assertEquals("token-1", service.accessToken(installation(1), Duration.ofMillis(250)));
        assertEquals(Duration.ofMillis(250), requestedTimeout.get());
        assertEquals("token-1", service.accessToken(installation(1)));
        assertEquals("token-2", service.accessToken(installation(2)));
        assertEquals(2, calls.get());
        assertEquals(Duration.ofSeconds(10), requestedTimeout.get());
    }

    private static WeComAuthorizationStore.ResolvedInstallation installation(long version) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new WeComAuthorizationStore.ResolvedInstallation(
                new WeComAuthorizationStore.Installation("installation-1", "dk-suite", "ww-corp",
                        "1000001", "encrypted", WeComAuthorizationStore.AuthStatus.ACTIVE,
                        now, now, now, version),
                "permanent-code");
    }
}
