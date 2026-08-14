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

    private static ResolvedInstallation installation(long version) {
        return new ResolvedInstallation("installation-1", "dk-suite", "ww-corp",
                "1000001", "developed-secret", version);
    }
}
