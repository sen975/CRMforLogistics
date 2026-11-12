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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class WeComChatDataPublicKeyGatewayTest {
    @Test
    void postsOfficialPublicKeyContractWithAuthorizedCorpToken() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/chatdata/set_public_key", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
        server.start();
        try {
            WeComChatDataPublicKeyGateway gateway = WeComChatDataPublicKeyGateway.forTests(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            WeComChatDataCrypto.PublicKeyMaterial material = new WeComChatDataCrypto.PublicKeyMaterial(
                    "-----BEGIN PUBLIC KEY-----\npublic-material\n-----END PUBLIC KEY-----\n",
                    7, "a".repeat(64), 2048);

            gateway.register("corp token/+", material);

            assertEquals("access_token=corp+token%2F%2B", query.get());
            JsonObject request = JsonParser.parseString(body.get()).getAsJsonObject();
            assertEquals(material.pem(), request.get("public_key").getAsString());
            assertEquals(7, request.get("public_key_ver").getAsInt());
            assertEquals(2, request.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsSanitizedFailureForRejectedRegistration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/chatdata/set_public_key", exchange ->
                respond(exchange, "{\"errcode\":600001,\"errmsg\":\"rejected-secret-detail\"}"));
        server.start();
        try {
            WeComChatDataPublicKeyGateway gateway = WeComChatDataPublicKeyGateway.forTests(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            WeComChatDataCrypto.PublicKeyMaterial material = new WeComChatDataCrypto.PublicKeyMaterial(
                    "-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----\n",
                    1, "b".repeat(64), 2048);

            WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                    () -> gateway.register("sensitive-token", material));

            assertEquals("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", exception.code());
            assertEquals(Integer.valueOf(600001), exception.upstreamErrcode());
            assertFalse(exception.getMessage().contains("sensitive-token"));
            assertFalse(exception.getMessage().contains("rejected-secret-detail"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsResponseBodyReadByTheOverallRequestTimeout() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/chatdata/set_public_key", exchange -> {
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
        });
        server.start();
        try {
            WeComChatDataPublicKeyGateway gateway = WeComChatDataPublicKeyGateway.forTests(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofMillis(100));
            WeComChatDataCrypto.PublicKeyMaterial material = new WeComChatDataCrypto.PublicKeyMaterial(
                    "-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----\n",
                    1, "b".repeat(64), 2048);

            WeComChatDataException exception = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(WeComChatDataException.class,
                            () -> gateway.register("corp-token", material)));

            assertEquals("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", exception.code());
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void rejectsResponseBodiesAboveOneMibibyte() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/chatdata/set_public_key", exchange ->
                respond(exchange, "x".repeat(1_048_577)));
        server.start();
        try {
            WeComChatDataPublicKeyGateway gateway = WeComChatDataPublicKeyGateway.forTests(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(2));
            WeComChatDataCrypto.PublicKeyMaterial material = new WeComChatDataCrypto.PublicKeyMaterial(
                    "-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----\n",
                    1, "b".repeat(64), 2048);

            WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                    () -> gateway.register("corp-token", material));

            assertEquals("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", exception.code());
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
}
