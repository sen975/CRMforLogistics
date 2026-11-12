package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComAccessTokenServiceTest {

    @Mock AppConfig config;
    @Mock WeComAuthorizationGateway gateway;

    @Test
    void cachesByInstallationVersionAndRefreshesChangedInstallation() {
        AtomicInteger calls = new AtomicInteger();
        when(config.wecomTokenRefreshSkewSeconds()).thenReturn(300);
        when(gateway.getDevelopedAppToken(eq("ww-corp"), eq("developed-secret"), any(Duration.class)))
                .thenAnswer(inv -> new WeComAuthorizationGateway.CorpTokenResponse(
                        "token-" + calls.incrementAndGet(), 7200));

        WeComAccessTokenService service = new WeComAccessTokenService(config,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC), gateway);

        assertEquals("token-1", service.accessToken(installation(1), Duration.ofMillis(250)));
        assertEquals("token-1", service.accessToken(installation(1)));
        assertEquals("token-2", service.accessToken(installation(2)));
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsMissingInstallation() {
        when(config.wecomTokenRefreshSkewSeconds()).thenReturn(300);
        WeComAccessTokenService service = new WeComAccessTokenService(config, Clock.systemUTC(), gateway);

        WeComException error = assertThrows(WeComException.class, () -> service.accessToken(null));
        assertEquals("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", error.code());
    }

    @Test
    void invalidateForcesRefreshOfCurrentInstallationVersion() {
        when(config.wecomTokenRefreshSkewSeconds()).thenReturn(300);
        when(gateway.getDevelopedAppToken(eq("ww-corp"), eq("developed-secret"), any(Duration.class)))
                .thenReturn(new WeComAuthorizationGateway.CorpTokenResponse("token-1", 7200))
                .thenReturn(new WeComAuthorizationGateway.CorpTokenResponse("token-2", 7200));
        WeComAccessTokenService service = new WeComAccessTokenService(config,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC), gateway);
        ResolvedInstallation installation = installation(1L);

        assertEquals("token-1", service.accessToken(installation));
        service.invalidate(installation);
        assertEquals("token-2", service.accessToken(installation));

        verify(gateway, times(2)).getDevelopedAppToken(
                eq("ww-corp"), eq("developed-secret"), any(Duration.class));
    }

    private static ResolvedInstallation installation(long version) {
        return new ResolvedInstallation("installation-1", "dk-suite", "ww-corp",
                "1000001", "developed-secret", version);
    }
}
