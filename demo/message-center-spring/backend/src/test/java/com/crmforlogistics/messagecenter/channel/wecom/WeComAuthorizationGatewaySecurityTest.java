package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComAuthorizationGatewaySecurityTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void developedAppSecretNeverAppearsInDiagnosticPathOrExceptionChain() throws Exception {
        String secret = "permanent-code-must-not-leak";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            byte[] body = "{\"errcode\":40013,\"errmsg\":\"invalid corpid\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Throwable thrown = catchThrowable(() -> gateway(2)
                .getDevelopedAppToken("corp", secret, Duration.ofSeconds(1)));

        assertThat(thrown).isInstanceOf(WeComException.class);
        WeComException exception = (WeComException) thrown;
        assertThat(exception.upstreamPath()).isEqualTo("/cgi-bin/gettoken");
        StringWriter stack = new StringWriter();
        exception.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString()).doesNotContain(secret).doesNotContain("corpsecret=");
    }

    @Test
    void upstreamErrorWithBlankMessageStillExposesDiagnosticErrorCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> respond(exchange,
                "{\"errcode\":40013,\"errmsg\":\"\"}"));
        server.start();

        Throwable thrown = catchThrowable(() -> gateway(2)
                .getDevelopedAppToken("corp", "secret", Duration.ofSeconds(1)));

        assertThat(thrown).isInstanceOf(WeComException.class);
        assertThat(thrown).hasMessageContaining("errcode=40013");
    }

    @Test
    void perCallTimeoutBoundsTheActualSocketRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        long started = System.nanoTime();
        try {
            assertThat(catchThrowable(() -> gateway(2)
                    .getDevelopedAppToken("corp", "secret", Duration.ofMillis(150))))
                    .isInstanceOf(WeComException.class);
        } finally {
            release.countDown();
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    void delegatedTokenAndLoginIdentityUseOfficialEndpoints() throws Exception {
        AtomicReference<String> tokenQuery = new AtomicReference<>();
        AtomicReference<String> identityQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            tokenQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, "{\"errcode\":0,\"access_token\":\"corp-token\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/auth/getuserinfo", exchange -> {
            identityQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\",\"userid\":\"member-1\"}");
        });
        server.start();

        WeComAuthorizationGateway gateway = gateway(2);
        WeComAuthorizationGateway.CorpTokenResponse token = gateway.getDevelopedAppToken(
                "corp-1", "permanent-code", Duration.ofSeconds(1));
        WeComAuthorizationGateway.LoginIdentity identity = gateway.getLoginIdentity(
                "corp-1", token.accessToken(), "login-code", Duration.ofSeconds(1));

        assertThat(token.accessToken()).isEqualTo("corp-token");
        assertThat(identity).isEqualTo(new WeComAuthorizationGateway.LoginIdentity("corp-1", "member-1"));
        assertThat(tokenQuery).hasValue("corpid=corp-1&corpsecret=permanent-code");
        assertThat(identityQuery).hasValue("access_token=corp-token&code=login-code");
    }

    @Test
    void tokenAndIdentitySuccessResponsesMayOmitErrcode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> respond(exchange,
                "{\"access_token\":\"corp-token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/auth/getuserinfo", exchange -> respond(exchange,
                "{\"userid\":\"member-1\"}"));
        server.start();

        WeComAuthorizationGateway gateway = gateway(2);
        WeComAuthorizationGateway.CorpTokenResponse token = gateway.getDevelopedAppToken(
                "corp-1", "permanent-code", Duration.ofSeconds(1));
        WeComAuthorizationGateway.LoginIdentity identity = gateway.getLoginIdentity(
                "corp-1", token.accessToken(), "login-code", Duration.ofSeconds(1));

        assertThat(token.accessToken()).isEqualTo("corp-token");
        assertThat(identity.userId()).isEqualTo("member-1");
    }

    @Test
    void delegatedIdentityRejectsNonMemberResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/auth/getuserinfo", exchange -> respond(exchange,
                "{\"errcode\":0,\"errmsg\":\"ok\",\"openid\":\"external-1\"}"));
        server.start();

        Throwable thrown = catchThrowable(() -> gateway(2).getLoginIdentity(
                "corp-1", "access-token", "login-code", Duration.ofSeconds(1)));

        assertThat(thrown).isInstanceOf(WeComException.class);
        assertThat(((WeComException) thrown).code()).isEqualTo("WECOM_LOGIN_IDENTITY_UNAVAILABLE");
        assertThat(((WeComException) thrown).upstreamPath()).isEqualTo("/cgi-bin/auth/getuserinfo");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String value)
            throws java.io.IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private WeComAuthorizationGateway gateway(int configuredTimeoutSeconds) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomApiBaseUrl()).thenReturn(
                "http://127.0.0.1:" + server.getAddress().getPort());
        when(config.wecomApiTimeoutSeconds()).thenReturn(configuredTimeoutSeconds);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomSuiteSecret()).thenReturn("suite-secret");
        return new WeComAuthorizationGateway(config, new ObjectMapper());
    }
}
