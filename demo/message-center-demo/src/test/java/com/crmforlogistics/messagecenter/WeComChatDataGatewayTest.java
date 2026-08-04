package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataGatewayTest {
    @Test
    void sendsOnlyZoneAbilityInputsAndParsesProjectedPage() throws Exception {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            assertEquals("access_token=installation-token", exchange.getRequestURI().getQuery());
            requestBody.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            String programResponse = """
                    {"errcode":0,"errmsg":"ok","has_more":1,"next_cursor":"next-1","msg_list":[
                      {"msgid":"msg-1","sender":{"type":1,"id":"employee-1"},
                       "receiver_list":[{"type":2,"id":"external-1"}],"send_time":123,
                       "msgtype":2,"service_encrypt_info":{"encrypted_secret_key":"cipher","public_key_ver":1}}
                    ]}
                    """;
            JsonObject outer = new JsonObject();
            outer.addProperty("errcode", 0);
            outer.addProperty("errmsg", "ok");
            outer.addProperty("response_data", programResponse);
            respond(exchange, outer.toString());
        });
        try {
            Config config = config(server);
            WeComChatDataGateway gateway = WeComChatDataGateway.forTests(config, HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    (ignored, timeout) -> "installation-token");

            WeComChatDataGateway.ProgramPage page = gateway.sync(installation(), "", 200, Duration.ofSeconds(2));

            assertTrue(page.hasMore());
            assertEquals("next-1", page.nextCursor());
            assertEquals(1, page.messages().size());
            assertEquals("msg-1", page.messages().get(0).msgid());
            JsonObject outerRequest = requestBody.get();
            assertEquals("program-1", outerRequest.get("program_id").getAsString());
            assertEquals("ability-1", outerRequest.get("ability_id").getAsString());
            JsonObject inner = JsonParser.parseString(outerRequest.get("request_data").getAsString()).getAsJsonObject();
            assertFalse(inner.has("mode"));
            assertEquals(200, inner.get("limit").getAsInt());
            assertEquals("", inner.get("cursor").getAsString());
            assertFalse(inner.has("token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProgramErrorsWithoutLeakingUpstreamResponse() throws Exception {
        HttpServer server = server(exchange -> respond(exchange,
                "{\"errcode\":40001,\"errmsg\":\"secret upstream diagnostic\"}"));
        try {
            WeComChatDataGateway gateway = WeComChatDataGateway.forTests(config(server), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    (ignored, timeout) -> "installation-token");

            WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                    () -> gateway.sync(installation(), "cursor-1", 200, Duration.ofSeconds(2)));

            assertEquals("WECOM_CHATDATA_PROGRAM_ERROR", exception.code());
            assertEquals(502, exception.httpStatus());
            assertEquals(40001, exception.upstreamErrcode());
            assertEquals("/cgi-bin/chatdata/sync_call_program", exception.upstreamPath());
            assertEquals(200, exception.upstreamHttpStatus());
            assertFalse(exception.getMessage().contains("secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesSafeAuthorizationHintWhenCorpTokenIsRejected() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_CHATDATA_PROGRAM_ID", "program-1",
                "WECOM_CHATDATA_ABILITY_ID", "ability-1"));
        WeComChatDataGateway gateway = WeComChatDataGateway.forTests(config, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1"), (ignored, timeout) -> {
                    throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                            "企业微信上游服务暂时不可用", 48002,
                            "/cgi-bin/gettoken", 200, "abc123", null);
                });

        WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                () -> gateway.sync(installation(), "", 200, Duration.ofSeconds(2)));

        assertEquals(48002, exception.upstreamErrcode());
        assertEquals("/cgi-bin/gettoken", exception.upstreamPath());
        assertEquals(200, exception.upstreamHttpStatus());
        assertEquals("abc123", exception.upstreamHint());
    }

    @Test
    void includesAccessTokenTimeInTheRequestDeadline() throws Exception {
        HttpServer server = server(exchange -> {
            pause(300);
            respond(exchange, "{\"errcode\":0,\"response_data\":\"{\\\"errcode\\\":0,"
                    + "\\\"has_more\\\":0,\\\"next_cursor\\\":\\\"done\\\",\\\"msg_list\\\":[]}\"}");
        });
        try {
            WeComChatDataGateway gateway = WeComChatDataGateway.forTests(config(server), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), (ignored, timeout) -> {
                        Thread.sleep(300);
                        return "installation-token";
                    });

            WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                    () -> gateway.sync(installation(), "", 200, Duration.ofMillis(450)));

            assertEquals("WECOM_CHATDATA_TIMEOUT", exception.code());
            assertEquals(504, exception.httpStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsContractFieldsOutsideTheViewerProjection() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, "{\"errcode\":0,\"response_data\":\""
                + "{\\\"errcode\\\":0,\\\"has_more\\\":0,\\\"next_cursor\\\":\\\"done\\\","
                + "\\\"msg_list\\\":[{\\\"msgid\\\":\\\"msg-1\\\","
                + "\\\"sender\\\":{\\\"type\\\":1,\\\"id\\\":\\\"employee-1\\\"},"
                + "\\\"receiver_list\\\":[{\\\"type\\\":2,\\\"id\\\":\\\"external-1\\\"}],"
                + "\\\"send_time\\\":123,\\\"msgtype\\\":2,\\\"content\\\":\\\"must-reject\\\","
                + "\\\"service_encrypt_info\\\":{\\\"encrypted_secret_key\\\":\\\"cipher\\\","
                + "\\\"public_key_ver\\\":1}}]}\"}"));
        try {
            WeComChatDataGateway gateway = WeComChatDataGateway.forTests(config(server), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    (ignored, timeout) -> "installation-token");

            WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                    () -> gateway.sync(installation(), "", 200, Duration.ofSeconds(2)));

            assertEquals("WECOM_CHATDATA_PROGRAM_ERROR", exception.code());
        } finally {
            server.stop(0);
        }
    }

    private static Config config(HttpServer server) {
        return new Config(Map.of(
                "WECOM_CHATDATA_PROGRAM_ID", "program-1",
                "WECOM_CHATDATA_ABILITY_ID", "ability-1",
                "WECOM_API_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort()
        ));
    }

    private static WeComAuthorizationStore.ResolvedInstallation installation() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new WeComAuthorizationStore.ResolvedInstallation(
                new WeComAuthorizationStore.Installation("installation-1", "dk-suite", "ww-corp",
                        "1000001", "encrypted", WeComAuthorizationStore.AuthStatus.ACTIVE,
                        now, now, now, 1), "permanent-code");
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/chatdata/sync_call_program", exchange -> handler.handle(exchange));
        server.start();
        return server;
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

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
