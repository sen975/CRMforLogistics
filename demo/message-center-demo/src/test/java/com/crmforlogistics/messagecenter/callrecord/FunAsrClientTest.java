package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunAsrClientTest {
    @TempDir
    Path tempDir;

    @Test
    void postsOpenAiCompatibleMultipartWithoutAuthorization() throws Exception {
        byte[] expectedAudio = fixtureBytes();
        HttpServer server = server(exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/v1/audio/transcriptions", exchange.getRequestURI().getRawPath());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            assertTrue(contentType.startsWith("multipart/form-data; boundary="));
            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
            byte[] body = exchange.getRequestBody().readNBytes(65_537);
            assertTrue(body.length <= 65_536);
            String multipart = new String(body, StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("name=\"model\"\r\n\r\nsensevoice"));
            assertTrue(multipart.contains(
                    "name=\"response_format\"\r\n\r\nverbose_json"));
            String fileHeader = "Content-Disposition: form-data; name=\"file\"; "
                    + "filename=\"recording.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n";
            int fileStart = multipart.indexOf(fileHeader) + fileHeader.length();
            int fileEnd = multipart.indexOf("\r\n--" + boundary + "--\r\n", fileStart);
            assertTrue(fileStart >= fileHeader.length());
            assertTrue(fileEnd > fileStart);
            assertArrayEquals(expectedAudio, Arrays.copyOfRange(body, fileStart, fileEnd));
            respond(exchange, 200, "{\"text\":\"你好\",\"duration\":1.0,"
                    + "\"model\":\"sensevoice\",\"segments\":[{\"text\":\"你好\","
                    + "\"start\":0.0,\"end\":1.0}]}");
        });
        try {
            Path audio = audioFile(expectedAudio);
            TranscriptionResult result = client(server, Map.of()).transcribe(audio, "sensevoice");

            assertEquals("你好", result.originalText());
            assertEquals(1.0, result.durationSeconds());
            assertEquals("sensevoice", result.model());
            assertEquals(List.of(new TranscriptSegment(0.0, 1.0, "你好")),
                    result.segments());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsVerboseResponsesThatOmitOptionalModelField() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                "{\"text\":\"你好\",\"duration\":1.0,"
                        + "\"segments\":[{\"text\":\"你好\",\"start\":0.0,\"end\":1.0}]}"));
        try {
            TranscriptionResult result = client(server, Map.of()).transcribe(
                    audioFile(fixtureBytes()), "sensevoice");

            assertEquals("sensevoice", result.model());
            assertEquals("你好", result.originalText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsRejectedAndUnavailableStatusesWithoutLeakingBodies() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 400, "sensitive rejected detail");
            } else {
                respond(exchange, 503, "sensitive unavailable detail");
            }
        });
        try {
            FunAsrClient client = client(server, Map.of());
            Path audio = audioFile(fixtureBytes());

            CallRecordException rejected = assertThrows(CallRecordException.class,
                    () -> client.transcribe(audio, "sensevoice"));
            assertEquals("FUNASR_REJECTED", rejected.code());
            assertFalse(rejected.retryable());
            assertFalse(rejected.getMessage().contains("sensitive"));

            CallRecordException unavailable = assertThrows(CallRecordException.class,
                    () -> client.transcribe(audio, "sensevoice"));
            assertEquals("FUNASR_UNAVAILABLE", unavailable.code());
            assertTrue(unavailable.retryable());
            assertFalse(unavailable.getMessage().contains("sensitive"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedAndMalformedSuccessfulResponses() throws Exception {
        List<String> responses = List.of(
                "x".repeat(10_485_761),
                "not-json",
                "{\"text\":\"\",\"duration\":1,\"model\":\"sensevoice\","
                        + "\"segments\":[{\"text\":\"x\",\"start\":0,\"end\":1}]}",
                "{\"text\":\"x\",\"duration\":1,\"model\":\"sensevoice\","
                        + "\"segments\":[]}",
                "{\"text\":\"x\",\"duration\":1,\"model\":\"sensevoice\","
                        + "\"segments\":[{\"text\":\"x\",\"start\":0.8,\"end\":0.2}]}",
                responseWithSegments(20_001));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> respond(
                exchange, 200, responses.get(calls.getAndIncrement())));
        try {
            FunAsrClient client = client(server, Map.of());
            Path audio = audioFile(fixtureBytes());
            for (int index = 0; index < responses.size(); index++) {
                CallRecordException error = assertThrows(CallRecordException.class,
                        () -> client.transcribe(audio, "sensevoice"));
                assertEquals("FUNASR_INVALID_RESPONSE", error.code());
                assertFalse(error.retryable());
                assertFalse(error.getMessage().contains("not-json"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsHttpTimeoutAsRetryable() throws Exception {
        Config config = new Config(Map.of("FUNASR_BASE_URL", "http://localhost:8000"));
        FunAsrClient client = new FunAsrClient(config, new TimeoutHttpClient());

        CallRecordException timeout = assertThrows(CallRecordException.class,
                () -> client.transcribe(audioFile(fixtureBytes()), "sensevoice"));

        assertEquals("FUNASR_TIMEOUT", timeout.code());
        assertEquals(504, timeout.httpStatus());
        assertTrue(timeout.retryable());
    }

    private FunAsrClient client(HttpServer server, Map<String, String> overrides) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>(overrides);
        values.put("FUNASR_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        values.put("FUNASR_CONNECT_TIMEOUT_SECONDS", "1");
        values.put("FUNASR_REQUEST_TIMEOUT_SECONDS", "30");
        return new FunAsrClient(new Config(values), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build());
    }

    private Path audioFile(byte[] bytes) throws IOException {
        Path audio = tempDir.resolve("source.mp3");
        Files.write(audio, bytes);
        return audio;
    }

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream input = FunAsrClientTest.class.getResourceAsStream(
                "/callrecord/short-ding.mp3")) {
            if (input == null) throw new IOException("MP3 fixture is missing");
            return input.readAllBytes();
        }
    }

    private static String responseWithSegments(int count) {
        StringBuilder response = new StringBuilder(
                "{\"text\":\"x\",\"duration\":1,\"model\":\"sensevoice\",\"segments\":[");
        for (int index = 0; index < count; index++) {
            if (index > 0) response.append(',');
            response.append("{\"text\":\"x\",\"start\":0,\"end\":1}");
        }
        return response.append("]}").toString();
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class TimeoutHttpClient extends HttpClient {
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            throw new HttpTimeoutException("timed out");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new HttpTimeoutException("timed out"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new HttpTimeoutException("timed out"));
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException();
        }
    }
}
