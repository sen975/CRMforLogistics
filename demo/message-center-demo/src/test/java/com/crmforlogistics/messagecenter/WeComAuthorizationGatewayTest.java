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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComAuthorizationGatewayTest {
    @Test
    void selectsSuiteSecretByExactSuiteId() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"errcode\":0,\"suite_access_token\":\"login-token\",\"expires_in\":7200}");
        });
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "delegated-secret",
                    "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                    "WECOM_LOGIN_SUITE_SECRET", "login-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("ww-login-suite", "login-ticket", Instant.now());

            assertEquals("login-token", gateway.suiteAccessToken("ww-login-suite"));
            assertTrue(requestBody.get().contains("\"suite_id\":\"ww-login-suite\""));
            assertTrue(requestBody.get().contains("\"suite_secret\":\"login-secret\""));
            assertTrue(requestBody.get().contains("\"suite_ticket\":\"login-ticket\""));
            assertEquals("WECOM_CALLBACK_SUITE_MISMATCH", assertThrows(WeComAuthorizationException.class,
                    () -> gateway.acceptSuiteTicket("unknown-suite", "ticket", Instant.now())).code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exchangesServiceAppCodeForCorpBoundIdentity() throws Exception {
        AtomicReference<String> identityQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"errcode\":0,\"suite_access_token\":\"login-token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/service/auth/getuserinfo3rd", exchange -> {
            identityQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, "{\"errcode\":0,\"corpid\":\"ww-corp\",\"userid\":\"employee-1\"}");
        });
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "delegated-secret",
                    "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                    "WECOM_LOGIN_SUITE_SECRET", "login-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("ww-login-suite", "login-ticket", Instant.now());

            WeComAuthorizationGateway.LoginIdentity identity = gateway.getLoginIdentity("one-time-code");

            assertEquals("ww-corp", identity.corpId());
            assertEquals("employee-1", identity.userId());
            assertEquals("suite_access_token=login-token&code=one-time-code", identityQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsLoginSuiteTokenAndIdentityExchangeByOneDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> {
            pause(180);
            respond(exchange, "{\"errcode\":0,\"suite_access_token\":\"login-token\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/service/auth/getuserinfo3rd", exchange -> {
            pause(180);
            respond(exchange, "{\"errcode\":0,\"corpid\":\"ww-corp\",\"userid\":\"employee-1\"}");
        });
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "delegated-secret",
                    "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                    "WECOM_LOGIN_SUITE_SECRET", "login-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("ww-login-suite", "login-ticket", Instant.now());

            WeComAuthorizationException error = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.getLoginIdentity("one-time-code", Duration.ofMillis(300)));
            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", error.code());
        } finally {
            server.stop(0);
        }
    }

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
    void preservesSafeUpstreamErrorDetailsWithoutExposingCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"errcode\":48002,\"errmsg\":\"api forbidden, hint: [abc123_DEF-9]\"}"));
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "sensitive-ticket", Instant.now());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.suiteAccessToken("dk-suite"));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
            assertEquals(48002, exception.upstreamErrcode());
            assertEquals("/cgi-bin/service/get_suite_token", exception.upstreamPath());
            assertEquals("abc123_DEF-9", exception.upstreamHint());
            assertTrue(!exception.getMessage().contains("sensitive-ticket"));
            assertTrue(!exception.getMessage().contains("suite-secret"));
            assertTrue(!exception.getMessage().contains("api forbidden"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotExposeInternalValidationWhenUpstreamOmitsErrorCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"errmsg\":\"malformed upstream response\"}"));
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "sensitive-ticket", Instant.now());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.suiteAccessToken("dk-suite"));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
            assertEquals(null, exception.upstreamErrcode());
            assertEquals("/cgi-bin/service/get_suite_token", exception.upstreamPath());
            assertTrue(!exception.getMessage().contains("Upstream error code is invalid"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedUpstreamHintInsteadOfPersistingArbitraryErrorText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"errcode\":48002,\"errmsg\":\"api forbidden, hint: [secret:value] permanent-code\"}"));
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "sensitive-ticket", Instant.now());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.suiteAccessToken("dk-suite"));

            assertEquals(48002, exception.upstreamErrcode());
            assertEquals(null, exception.upstreamHint());
            assertTrue(!exception.getMessage().contains("secret:value"));
            assertTrue(!exception.getMessage().contains("permanent-code"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesHttpStatusWhenUpstreamRejectsBeforeReturningWeComJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> {
            byte[] bytes = "upstream gateway rejected request".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "sensitive-ticket", Instant.now());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.suiteAccessToken("dk-suite"));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
            assertEquals(403, exception.upstreamHttpStatus());
            assertEquals(null, exception.upstreamErrcode());
            assertEquals("/cgi-bin/service/get_suite_token", exception.upstreamPath());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsSuccessfulSuiteTokenResponseWithoutErrcode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"suite_access_token\":\"suite-token\",\"expires_in\":7200}"));
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            assertEquals("suite-token", gateway.suiteAccessToken("dk-suite"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsAuthorizedCorpAndAgentFromOfficialGetAuthInfoShape() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange -> respond(exchange,
                "{\"errcode\":0,\"suite_access_token\":\"suite-token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/service/v2/get_auth_info", exchange -> respond(exchange, """
                {"errcode":0,
                 "auth_corp_info":{"corpid":"ww-corp","corp_name":"Authorized Corp"},
                 "auth_info":{"agent":[{"agentid":1000014}]}}
                """));
        server.start();
        try {
            Config config = new Config(Map.of(
                    "WECOM_SUITE_ID", "dk-suite",
                    "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            WeComAuthorizationGateway.AuthorizationInfo info =
                    gateway.getAuthInfo("ww-corp", "permanent-code");

            assertEquals("ww-corp", info.authCorpId());
            assertEquals("1000014", info.agents().get(0).agentId());
        } finally {
            server.stop(0);
        }
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
    void getsDevelopedAppTokenThroughSelfBuiltAppEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, "{\"errcode\":0,\"access_token\":\"corp-token\",\"expires_in\":7200}");
        });
        server.start();
        try {
            Config config = new Config(Map.of());
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());

            WeComAuthorizationGateway.CorpTokenResponse response =
                    gateway.getDevelopedAppToken("ww-corp", "developed-secret", Duration.ofSeconds(2));

            assertEquals("GET", method.get());
            assertEquals("corpid=ww-corp&corpsecret=developed-secret", query.get());
            assertEquals("corp-token", response.accessToken());
            assertEquals(7200, response.expiresIn());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesSafeHintWhenDevelopedAppTokenIsForbidden() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> respond(exchange,
                "{\"errcode\":48002,\"errmsg\":\"api forbidden, hint: [fresh123]\"}"));
        server.start();
        try {
            Config config = new Config(Map.of());
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());

            WeComAuthorizationException exception = assertThrows(WeComAuthorizationException.class,
                    () -> gateway.getDevelopedAppToken(
                            "ww-corp", "sensitive-developed-secret", Duration.ofSeconds(2)));

            assertEquals(48002, exception.upstreamErrcode());
            assertEquals("/cgi-bin/gettoken", exception.upstreamPath());
            assertEquals(200, exception.upstreamHttpStatus());
            assertEquals("fresh123", exception.upstreamHint());
            assertTrue(!exception.getMessage().contains("sensitive-developed-secret"));
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

    @Test
    void boundsSuiteTokenResponseBodyByTheOverallDeadline() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange ->
                hangResponseBody(exchange, releaseBody));
        server.start();
        try {
            Config config = new Config(Map.of("WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            WeComAuthorizationException exception = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(WeComAuthorizationException.class,
                            () -> gateway.getCorpToken("ww-corp", "permanent-code", Duration.ofMillis(100))));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void boundsCorpTokenResponseBodyByTheOverallDeadline() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/service/get_suite_token", exchange ->
                respond(exchange, "{\"errcode\":0,\"suite_access_token\":\"suite-token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/service/get_corp_token", exchange ->
                hangResponseBody(exchange, releaseBody));
        server.start();
        try {
            Config config = new Config(Map.of("WECOM_SUITE_ID", "dk-suite", "WECOM_SUITE_SECRET", "suite-secret"));
            WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Clock.systemUTC());
            gateway.acceptSuiteTicket("dk-suite", "suite-ticket", Instant.now());

            WeComAuthorizationException exception = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(WeComAuthorizationException.class,
                            () -> gateway.getCorpToken("ww-corp", "permanent-code", Duration.ofMillis(150))));

            assertEquals("WECOM_UPSTREAM_UNAVAILABLE", exception.code());
        } finally {
            releaseBody.countDown();
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

    private static void hangResponseBody(com.sun.net.httpserver.HttpExchange exchange,
                                         CountDownLatch releaseBody) throws IOException {
        exchange.sendResponseHeaders(200, 100);
        exchange.getResponseBody().write('{');
        exchange.getResponseBody().flush();
        try {
            releaseBody.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

}
