package com.crmforlogistics.messagecenter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
        AtomicReference<String> requestedCorpId = new AtomicReference<>();
        AtomicReference<String> requestedSecret = new AtomicReference<>();
        Config config = new Config(Map.of("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "300"));
        WeComAccessTokenService service = WeComAccessTokenService.forTests(config,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                (corpId, developedAppSecret, timeout) -> {
                    requestedCorpId.set(corpId);
                    requestedSecret.set(developedAppSecret);
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
        assertEquals("ww-corp", requestedCorpId.get());
        assertEquals("developed-secret", requestedSecret.get());
    }

    @Test
    void productionConstructorUsesDevelopedAppTokenEndpoint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            calls.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, "{\"errcode\":0,\"access_token\":\"developed-token\",\"expires_in\":7200}");
        });
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_API_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "WECOM_TOKEN_REFRESH_SKEW_SECONDS", "300"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            WeComAccessTokenService service = new WeComAccessTokenService(config, gateway);

            assertEquals("developed-token", service.accessToken(installation(1)));
            assertEquals(1, calls.get());
            assertEquals("corpid=ww-corp&corpsecret=developed-secret", query.get());
        } finally {
            server.stop(0);
        }
    }

    private static WeComAuthorizationStore.ResolvedInstallation installation(long version) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new WeComAuthorizationStore.ResolvedInstallation(
                new WeComAuthorizationStore.Installation("installation-1", "dk-suite", "ww-corp",
                        "1000001", "encrypted", WeComAuthorizationStore.AuthStatus.ACTIVE,
                        now, now, now, version),
                "developed-secret");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
