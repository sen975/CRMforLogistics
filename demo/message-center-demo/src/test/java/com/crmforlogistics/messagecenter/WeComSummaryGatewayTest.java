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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComSummaryGatewayTest {
    @Test
    void submitsAndPollsTheFixedSummaryAbility() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonObject> submitRequest = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            JsonObject outer = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("program-1", outer.get("program_id").getAsString());
            assertEquals("conversation_daily_summary", outer.get("ability_id").getAsString());
            JsonObject inner = JsonParser.parseString(outer.get("request_data").getAsString()).getAsJsonObject();
            JsonObject response = new JsonObject();
            response.addProperty("errcode", 0);
            response.addProperty("errmsg", "ok");
            if (calls.getAndIncrement() == 0) {
                submitRequest.set(inner);
                response.addProperty("response_data",
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"status\":0,"
                                + "\"jobid\":\"job-1\",\"summary\":\"\"}");
            } else {
                assertEquals("poll", inner.get("operation").getAsString());
                assertEquals("job-1", inner.get("jobid").getAsString());
                assertEquals(0, inner.getAsJsonArray("msg_list").size());
                response.addProperty("response_data",
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"status\":1,"
                                + "\"jobid\":\"job-1\",\"summary\":\"已确认装运时间\"}");
            }
            respond(exchange, 200, response.toString());
        });
        try {
            WeComSummaryGateway gateway = gateway(server);

            WeComSummaryGateway.SubmitResult submitted = gateway.submit(installation(),
                    List.of(new WeComSummaryGateway.MessageReference("msg-1", "sensitive-secret")),
                    Duration.ofSeconds(2));
            WeComSummaryGateway.PollResult completed = gateway.poll(installation(), "job-1",
                    Duration.ofSeconds(2));

            assertEquals(0, submitted.errcode());
            assertEquals("job-1", submitted.jobId());
            JsonObject inner = submitRequest.get();
            assertEquals("submit", inner.get("operation").getAsString());
            assertEquals("", inner.get("jobid").getAsString());
            assertEquals("sensitive-secret", inner.getAsJsonArray("msg_list").get(0)
                    .getAsJsonObject().get("secret_key").getAsString());
            assertEquals(1, completed.status());
            assertEquals("已确认装运时间", completed.summary());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsSafeProgramCodeButNeverUpstreamMessage() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"response_data\":\"{\\\"errcode\\\":790040,"
                        + "\\\"errmsg\\\":\\\"sensitive detail\\\",\\\"status\\\":2,"
                        + "\\\"jobid\\\":\\\"\\\",\\\"summary\\\":\\\"\\\"}\"}"));
        try {
            WeComSummaryGateway.SubmitResult result = gateway(server).submit(installation(),
                    List.of(new WeComSummaryGateway.MessageReference("msg-1", "secret")),
                    Duration.ofSeconds(2));

            assertEquals(790040, result.errcode());
            assertEquals("", result.jobId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnknownOutputFieldsAndDoesNotLeakThem() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"response_data\":\"{\\\"errcode\\\":0,"
                        + "\\\"errmsg\\\":\\\"ok\\\",\\\"status\\\":0,"
                        + "\\\"jobid\\\":\\\"job-1\\\",\\\"summary\\\":\\\"\\\","
                        + "\\\"secret\\\":\\\"must-not-leak\\\"}\"}"));
        try {
            WeComSummaryException exception = assertThrows(WeComSummaryException.class,
                    () -> gateway(server).poll(installation(), "job-1", Duration.ofSeconds(2)));

            assertEquals("WECOM_SUMMARY_PROGRAM_ERROR", exception.code());
            assertFalse(exception.getMessage().contains("must-not-leak"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonSuccessHttpStatusAndOuterProgramError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 503, "sensitive proxy response");
            } else {
                respond(exchange, 200,
                        "{\"errcode\":40014,\"errmsg\":\"sensitive upstream detail\","
                                + "\"response_data\":\"\"}");
            }
        });
        try {
            WeComSummaryGateway gateway = gateway(server);

            WeComSummaryException statusFailure = assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "job-1", Duration.ofSeconds(2)));
            WeComSummaryException outerFailure = assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "job-1", Duration.ofSeconds(2)));

            assertEquals("WECOM_SUMMARY_PROGRAM_ERROR", statusFailure.code());
            assertEquals("WECOM_SUMMARY_PROGRAM_ERROR", outerFailure.code());
            assertFalse(statusFailure.getMessage().contains("sensitive"));
            assertFalse(outerFailure.getMessage().contains("sensitive"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponsesAndSummariesOverTheirBounds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 200, "x".repeat(1_048_577));
            } else {
                JsonObject response = new JsonObject();
                response.addProperty("errcode", 0);
                response.addProperty("errmsg", "ok");
                JsonObject inner = new JsonObject();
                inner.addProperty("errcode", 0);
                inner.addProperty("errmsg", "ok");
                inner.addProperty("status", 1);
                inner.addProperty("jobid", "job-1");
                inner.addProperty("summary", "x".repeat(65_537));
                response.addProperty("response_data", inner.toString());
                respond(exchange, 200, response.toString());
            }
        });
        try {
            WeComSummaryGateway gateway = gateway(server);

            assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "job-1", Duration.ofSeconds(2)));
            assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "job-1", Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidProgramStatusAndInvalidInput() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"errmsg\":\"ok\","
                        + "\"response_data\":\"{\\\"errcode\\\":0,\\\"errmsg\\\":\\\"ok\\\","
                        + "\\\"status\\\":3,\\\"jobid\\\":\\\"job-1\\\","
                        + "\\\"summary\\\":\\\"\\\"}\"}"));
        try {
            WeComSummaryGateway gateway = gateway(server);

            assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "job-1", Duration.ofSeconds(2)));
            assertThrows(WeComSummaryException.class,
                    () -> gateway.submit(installation(), List.of(), Duration.ofSeconds(2)));
            assertThrows(WeComSummaryException.class,
                    () -> gateway.submit(installation(),
                            List.of(
                                    new WeComSummaryGateway.MessageReference("msg-1", "secret-1"),
                                    new WeComSummaryGateway.MessageReference("msg-1", "secret-2")),
                            Duration.ofSeconds(2)));
            assertThrows(WeComSummaryException.class,
                    () -> gateway.poll(installation(), "", Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesOneOverallTimeoutAcrossTokenAndProgramCalls() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            handlerStarted.countDown();
            try {
                releaseHandler.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            WeComSummaryException exception = assertThrows(WeComSummaryException.class,
                    () -> gateway(server).poll(installation(), "job-1", Duration.ofMillis(50)));

            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            assertEquals("WECOM_SUMMARY_TIMEOUT", exception.code());
        } finally {
            releaseHandler.countDown();
            server.stop(0);
        }
    }

    private static WeComSummaryGateway gateway(HttpServer server) {
        Config config = new Config(Map.of(
                "WECOM_CHATDATA_PROGRAM_ID", "program-1",
                "WECOM_DAILY_SUMMARY_ABILITY_ID", "conversation_daily_summary"
        ));
        return WeComSummaryGateway.forTests(config, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                (ignored, timeout) -> "installation-token");
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
        server.createContext("/cgi-bin/chatdata/sync_call_program", handler::handle);
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
