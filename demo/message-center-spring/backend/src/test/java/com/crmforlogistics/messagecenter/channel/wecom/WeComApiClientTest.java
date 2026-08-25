package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComApiClientTest {
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp-1",
            "100001", "permanent-code", 3L);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void getAddsTokenAndBusinessQueryAndRemovesProviderEnvelope() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        start("/cgi-bin/user/get", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\",\"userid\":\"member-1\"}");
        });
        WeComAccessTokenService tokens = mock(WeComAccessTokenService.class);
        when(tokens.accessToken(INSTALLATION, Duration.ofSeconds(2))).thenReturn("secret-token");

        JsonNode result = client(tokens).get(INSTALLATION, "/cgi-bin/user/get",
                Map.of("userid", "member-1"), Duration.ofSeconds(2));

        assertThat(result.path("userid").asText()).isEqualTo("member-1");
        assertThat(result.has("errcode")).isFalse();
        assertThat(result.has("errmsg")).isFalse();
        assertThat(query.get()).contains("access_token=secret-token").contains("userid=member-1");
    }

    @Test
    void tokenExpiryInvalidatesAndRetriesExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start("/cgi-bin/tag/list", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"errcode\":42001,\"errmsg\":\"expired\"}");
            } else {
                respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\",\"taglist\":[]}");
            }
        });
        WeComAccessTokenService tokens = mock(WeComAccessTokenService.class);
        when(tokens.accessToken(INSTALLATION, Duration.ofSeconds(2)))
                .thenReturn("expired-token", "fresh-token");

        JsonNode result = client(tokens).get(INSTALLATION, "/cgi-bin/tag/list",
                Map.of(), Duration.ofSeconds(2));

        assertThat(result.path("taglist").isArray()).isTrue();
        assertThat(calls).hasValue(2);
        verify(tokens).invalidate(INSTALLATION);
        verify(tokens, times(2)).accessToken(INSTALLATION, Duration.ofSeconds(2));
    }

    @Test
    void repeatedTokenExpiryStopsAfterSecondAttemptWithoutLeakingToken() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start("/cgi-bin/tag/list", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"errcode\":42001,\"errmsg\":\"expired\"}");
        });
        WeComAccessTokenService tokens = mock(WeComAccessTokenService.class);
        when(tokens.accessToken(INSTALLATION, Duration.ofSeconds(2)))
                .thenReturn("first-secret-token", "second-secret-token");

        Throwable thrown = catchThrowable(() -> client(tokens).get(INSTALLATION,
                "/cgi-bin/tag/list", Map.of(), Duration.ofSeconds(2)));

        assertThat(thrown).isInstanceOf(WeComException.class);
        assertThat(((WeComException) thrown).upstreamPath()).isEqualTo("/cgi-bin/tag/list");
        assertThat(calls).hasValue(2);
        StringWriter stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString()).doesNotContain("first-secret-token")
                .doesNotContain("second-secret-token").doesNotContain("permanent-code");
    }

    @Test
    void rejectsResponseLargerThanTwoMiB() throws Exception {
        start("/cgi-bin/department/list", exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"data\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"));
        WeComAccessTokenService tokens = mock(WeComAccessTokenService.class);
        when(tokens.accessToken(INSTALLATION, Duration.ofSeconds(2))).thenReturn("secret-token");

        Throwable thrown = catchThrowable(() -> client(tokens).get(INSTALLATION,
                "/cgi-bin/department/list", Map.of(), Duration.ofSeconds(2)));

        assertThat(thrown).isInstanceOf(WeComException.class);
        assertThat(((WeComException) thrown).code()).isEqualTo("WECOM_UPSTREAM_RESPONSE_TOO_LARGE");
    }

    private WeComApiClient client(WeComAccessTokenService tokens) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomApiBaseUrl()).thenReturn(
                "http://127.0.0.1:" + server.getAddress().getPort());
        when(config.wecomApiTimeoutSeconds()).thenReturn(2);
        return new WeComApiClient(new ObjectMapper(), tokens, new WeComRestClientFactory(config));
    }

    private void start(String path, Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                throw new java.io.IOException(exception);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String value) throws Exception {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
