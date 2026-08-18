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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private WeComAuthorizationGateway gateway(int configuredTimeoutSeconds) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomApiBaseUrl()).thenReturn(
                "http://127.0.0.1:" + server.getAddress().getPort());
        when(config.wecomApiTimeoutSeconds()).thenReturn(configuredTimeoutSeconds);
        when(config.wecomSuiteId()).thenReturn("suite");
        return new WeComAuthorizationGateway(config, new ObjectMapper());
    }
}
