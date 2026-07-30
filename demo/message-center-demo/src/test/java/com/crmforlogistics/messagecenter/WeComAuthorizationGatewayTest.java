package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComAuthorizationGatewayTest {
    @Test
    void requiresSuiteTicketBeforeCallingUpstream() {
        Config config = new Config(Map.of("WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "suite-secret"));
        WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                HttpClient.newHttpClient(), URI.create("http://localhost:1"), Clock.systemUTC());
        WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                () -> gateway.suiteAccessToken("dk-suite"));
        assertEquals("WECOM_SUITE_TICKET_NOT_READY", exception.code());
    }

    @Test
    void exchangesPermanentCodeThroughServiceCorpTokenEndpoint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> corpTokenBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> {
            calls.incrementAndGet();
            respond(exchange, "{\"errcode\":0,\"suite_access_token\":\"suite-token\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/service/get_corp_token", exchange -> {
            calls.incrementAndGet();
            corpTokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("suite_access_token=suite-token", exchange.getRequestURI().getQuery());
            respond(exchange, "{\"errcode\":0,\"access_token\":\"corp-token\",\"expires_in\":7200}");
        });
        server.start();
        try {
            Config config = new Config(Map.of("WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            WeComAuthorizationGateway.CorpTokenResponse response =
                    gateway.getCorpToken("ww-corp", "permanent-code");

            assertEquals("corp-token", response.accessToken());
            assertEquals(7200, response.expiresIn());
            assertEquals(2, calls.get());
            assertTrue(corpTokenBody.get().contains("\"auth_corpid\":\"ww-corp\""));
            assertTrue(corpTokenBody.get().contains("\"permanent_code\":\"permanent-code\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsSuiteAndCorpTokenCallsByOneDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> {
            pause(180);
            respond(exchange, "{\"errcode\":0,\"suite_access_token\":\"suite-token\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/service/get_corp_token", exchange -> {
            pause(180);
            respond(exchange, "{\"errcode\":0,\"access_token\":\"corp-token\",\"expires_in\":7200}");
        });
        server.start();
        try {
            Config config = new Config(Map.of("WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.getCorpToken("ww-corp", "permanent-code", Duration.ofMillis(300)));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void pause(long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test server interrupted", exception);
        }
    }

}
