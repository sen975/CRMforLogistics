package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRecordHttpAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final String CONTACT = "email:buyer@example.com";

    @TempDir
    Path tempDir;

    @Test
    void decodesRawContactSegmentExactlyOnceAndRejectsEncodedPathSeparators() throws Exception {
        assertEquals("email:buyer@example.com",
                CallRecordHttpAdapter.decodeRawPathSegment("email%3Abuyer%40example.com"));
        assertEquals("phone:8613800000000",
                CallRecordHttpAdapter.decodeRawPathSegment("phone%3A8613800000000"));

        for (String raw : List.of(
                "email%2fbuyer", "email%2Fbuyer", "email%5cbuyer", "email%5Cbuyer",
                "email%00buyer", "email%252Fbuyer", "email%255cbuyer",
                "%", "%2", "%GG", "%FF")) {
            CallRecordException exception = assertThrows(CallRecordException.class,
                    () -> CallRecordHttpAdapter.decodeRawPathSegment(raw), raw);
            assertEquals("ROUTE_PATH_INVALID", exception.code(), raw);
        }
    }

    @Test
    void classifiesRawPathsBeforeAnyAuthenticatedRouteWork() throws Exception {
        AtomicInteger actorResolutions = new AtomicInteger();
        try (CreateFixture fixture = CreateFixture.open(tempDir, 100, token -> {
                 actorResolutions.incrementAndGet();
                 return "viewer-1";
             })) {
            TestServer server = fixture.server();
            HttpResponse<String> legal = server.post(
                    "/api/v1/contacts/email%3Abuyer%40example.com/call-records");
            assertEquals(400, legal.statusCode());
            assertEquals("MULTIPART_INVALID", code(legal));
            assertEquals(1, actorResolutions.get());

            for (String raw : List.of("email%2Fbuyer", "email%252Fbuyer",
                    "email%5Cbuyer", "email%00buyer")) {
                HttpResponse<String> rejected = server.post(
                        "/api/v1/contacts/" + raw + "/call-records");
                assertEquals(400, rejected.statusCode(), raw);
                assertEquals("ROUTE_PATH_INVALID", code(rejected), raw);
            }
            assertEquals(1, actorResolutions.get());

            HttpResponse<String> legacy = server.get("/api/threads");
            assertEquals(418, legacy.statusCode());
            assertEquals(1, actorResolutions.get());
        }
    }

    @Test
    void streamsMultipartCreateAndRejectsUnauthenticatedOrStructurallyUnsafeBodies()
            throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir)) {
            HttpResponse<String> created = fixture.server().send(createRequest(
                    fixture.server().baseUri(), fields(), List.of(fixtureMp3()), false));
            assertEquals(202, created.statusCode());
            var createdJson = JsonParser.parseString(created.body()).getAsJsonObject();
            assertEquals("queued", createdJson.get("state").getAsString());
            assertEquals("request-1", createdJson.get("clientRequestId").getAsString());
            assertEquals(3, createdJson.size());
            assertEquals(false, createdJson.has("id"));
            UUID.fromString(createdJson.get("callRecordId").getAsString());
            assertEquals(1, fixture.repository().listByAnchors(Set.of(CONTACT)).size());

            HttpRequest noToken = createRequest(
                    fixture.server().baseUri(), requestFields("request-no-token"),
                    List.of(fixtureMp3()), false, false);
            assertEquals(401, fixture.server().send(noToken).statusCode());

            Map<String, String> unknownFields = requestFields("request-unknown");
            unknownFields.put("unexpected", "x");
            assertRejectedWithoutPublishing(fixture, "request-unknown",
                    unknownFields, List.of(fixtureMp3()), false,
                    "MULTIPART_PART_INVALID");
            assertRejectedWithoutPublishing(fixture, "request-two-files",
                    requestFields("request-two-files"), List.of(fixtureMp3(), fixtureMp3()), false,
                    "MULTIPART_ORDER_INVALID");
            assertRejectedWithoutPublishing(fixture, "request-file-not-last",
                    requestFields("request-file-not-last"), List.of(fixtureMp3()), true,
                    "MULTIPART_ORDER_INVALID");

            RawResponse tooLarge = fixture.server().postDeclaredLength(104_923_137L);
            assertEquals(413, tooLarge.statusCode());
            assertEquals("AUDIO_TOO_LARGE", JsonParser.parseString(tooLarge.body())
                    .getAsJsonObject().get("code").getAsString());
        }
    }

    @Test
    void rejectsReplayAndFullQueueBeforeReadingAnyFileBytes() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir, 1)) {
            HttpResponse<String> first = fixture.server().send(createRequest(
                    fixture.server().baseUri(), requestFields("preflight-winner"),
                    List.of(fixtureMp3()), false));
            assertEquals(202, first.statusCode());
            String winnerId = JsonParser.parseString(first.body()).getAsJsonObject()
                    .get("callRecordId").getAsString();

            RawResponse replay = fixture.server().postMultipartHeaderOnly("preflight-winner");
            assertEquals(202, replay.statusCode());
            assertEquals(winnerId, JsonParser.parseString(replay.body()).getAsJsonObject()
                    .get("callRecordId").getAsString());

            RawResponse full = fixture.server().postMultipartHeaderOnly("preflight-full");
            assertEquals(429, full.statusCode());
            assertEquals("TRANSCRIPTION_QUEUE_FULL", JsonParser.parseString(full.body())
                    .getAsJsonObject().get("code").getAsString());
        }
    }

    @Test
    void releasesQueueReservationWhenMultipartHasTrailingPart() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir, 1)) {
            HttpResponse<String> rejected = fixture.server().send(createRequest(
                    fixture.server().baseUri(), requestFields("trailing-abort"),
                    List.of(fixtureMp3()), true));
            assertEquals(400, rejected.statusCode());
            assertEquals("MULTIPART_ORDER_INVALID", code(rejected));

            HttpResponse<String> accepted = fixture.server().send(createRequest(
                    fixture.server().baseUri(), requestFields("after-trailing-abort"),
                    List.of(fixtureMp3()), false));
            assertEquals(202, accepted.statusCode());
        }
    }

    @Test
    void rejectsDuplicateInFlightRequestBeforeReadingAnyFileBytes() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir, 2);
             CallRecordService.PreparedCreate ignored = fixture.service().prepareCreate(
                     new CallRecordService.CreateCallRecordCommand(
                             CONTACT, "", "inbound", NOW.minusSeconds(60),
                             "in-flight-duplicate", "call.mp3", "audio/mpeg", "viewer-1"))) {
            RawResponse duplicate = fixture.server()
                    .postMultipartHeaderOnly("in-flight-duplicate");
            assertEquals(429, duplicate.statusCode());
            assertEquals("TRANSCRIPTION_QUEUE_FULL", JsonParser.parseString(duplicate.body())
                    .getAsJsonObject().get("code").getAsString());
        }
    }

    @Test
    void timelineAndDetailUseExplicitPublicProjections() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir)) {
            HttpResponse<String> created = fixture.server().send(createRequest(
                    fixture.server().baseUri(), fields(), List.of(fixtureMp3()), false));
            String id = JsonParser.parseString(created.body()).getAsJsonObject()
                    .get("callRecordId").getAsString();
            assertEquals(2, fixture.timeline().page(CONTACT, "", 10).items().size());

            HttpResponse<String> timeline = fixture.server().authenticatedGet(
                    "/api/v1/contacts/email%3Abuyer%40example.com/timeline?limit=10");
            assertEquals(200, timeline.statusCode());
            JsonObject timelineJson = JsonParser.parseString(timeline.body()).getAsJsonObject();
            assertEquals(2, timelineJson.getAsJsonArray("items").size());
            assertFalse(timeline.body().contains("\"raw\""));
            assertFalse(timeline.body().contains("raw-secret"));
            assertFalse(timeline.body().contains("\"objectKey\""));

            HttpResponse<String> detail = fixture.server().authenticatedGet(
                    "/api/v1/call-records/" + id);
            assertEquals(200, detail.statusCode());
            JsonObject detailJson = JsonParser.parseString(detail.body()).getAsJsonObject();
            assertEquals(id, detailJson.get("id").getAsString());
            assertEquals("queued", detailJson.getAsJsonObject("transcription")
                    .get("state").getAsString());
            assertFalse(detail.body().contains("relativePath"));
            assertFalse(detail.body().contains(tempDir.toString()));
        }
    }

    @Test
    void createsAudioSessionAndStreamsOnlyAuthorizedSingleRanges() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir)) {
            HttpResponse<String> created = fixture.server().send(createRequest(
                    fixture.server().baseUri(), fields(), List.of(fixtureMp3()), false));
            String id = JsonParser.parseString(created.body()).getAsJsonObject()
                    .get("callRecordId").getAsString();
            String audioPath = "/api/v1/call-records/" + id + "/audio";

            HttpResponse<byte[]> session = fixture.server().sendBytes(HttpRequest.newBuilder(
                            fixture.server().baseUri().resolve(audioPath + "-sessions"))
                    .header("X-WeCom-Viewer-Auth", "viewer-token")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(204, session.statusCode());
            assertEquals(0, session.body().length);
            String cookie = session.headers().firstValue("Set-Cookie").orElseThrow()
                    .split(";", 2)[0];

            long size = Files.size(fixtureMp3());
            HttpResponse<byte[]> complete = fixture.server().audio(audioPath, cookie, null);
            assertEquals(200, complete.statusCode());
            assertEquals(size, complete.body().length);
            for (Map.Entry<String, Integer> range : Map.of(
                    "bytes=10-19", 10, "bytes=10-", Math.toIntExact(size - 10),
                    "bytes=-10", 10).entrySet()) {
                HttpResponse<byte[]> partial = fixture.server().audio(
                        audioPath, cookie, range.getKey());
                assertEquals(206, partial.statusCode(), range.getKey());
                assertEquals(range.getValue(), partial.body().length, range.getKey());
                assertTrue(partial.headers().firstValue("Content-Range").orElseThrow()
                        .endsWith("/" + size));
            }
            for (String range : List.of("bytes=0-1,4-5", "items=0-1",
                    "bytes=nope", "bytes=" + size + "-")) {
                HttpResponse<byte[]> rejected = fixture.server().audio(audioPath, cookie, range);
                assertEquals(416, rejected.statusCode(), range);
                assertEquals("bytes */" + size, rejected.headers()
                        .firstValue("Content-Range").orElseThrow(), range);
            }
            assertEquals(401, fixture.server().audio(audioPath, "", null).statusCode());
            for (String method : List.of("HEAD", "POST")) {
                HttpResponse<byte[]> rejected = fixture.server().sendBytes(HttpRequest.newBuilder(
                                fixture.server().baseUri().resolve(audioPath))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build());
                assertEquals(405, rejected.statusCode());
                assertFalse(rejected.headers().firstValue("Content-Range").isPresent());
                assertFalse(rejected.headers().firstValue("Content-Length")
                        .map(Long::parseLong).filter(length -> length == size).isPresent());
            }
        }
    }

    @Test
    void retryAndRevisionUseBoundedWhitelistedJsonAndViewerActor() throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir)) {
            CallRecord failed = fixture.createAndFail("retry-source");
            HttpResponse<String> retried = fixture.server().json(
                    "/api/v1/call-records/" + failed.id() + "/retry", "POST",
                    "{\"clientRequestId\":\"retry-1\"}", true);
            assertEquals(200, retried.statusCode());
            assertEquals("queued", JsonParser.parseString(retried.body()).getAsJsonObject()
                    .getAsJsonObject("transcription").get("state").getAsString());
            assertFalse(retried.body().contains("relativePath"));

            CallRecord completed = fixture.createAndComplete("revision-source");
            HttpResponse<String> revised = fixture.server().json(
                    "/api/v1/call-records/" + completed.id() + "/transcript", "PATCH",
                    "{\"text\":\"人工修订稿\",\"expectedVersion\":"
                            + completed.version() + "}", true);
            assertEquals(200, revised.statusCode());
            JsonObject revisedJson = JsonParser.parseString(revised.body()).getAsJsonObject();
            assertEquals(completed.version() + 1, revisedJson.get("version").getAsLong());
            assertEquals("viewer-1", revisedJson.getAsJsonArray("revisions")
                    .get(0).getAsJsonObject().get("editedBy").getAsString());
            assertFalse(revised.body().contains("relativePath"));

            for (RequestCase invalid : List.of(
                    new RequestCase("/retry", "POST", "{}", "JSON_FIELD_INVALID"),
                    new RequestCase("/retry", "POST",
                            "{\"clientRequestId\":\"x\",\"actor\":\"attacker\"}",
                            "JSON_FIELD_INVALID"),
                    new RequestCase("/transcript", "PATCH",
                            "{\"text\":\"x\"}", "JSON_FIELD_INVALID"))) {
                HttpResponse<String> response = fixture.server().json(
                        "/api/v1/call-records/" + completed.id() + invalid.suffix(),
                        invalid.method(), invalid.body(), true);
                assertEquals(400, response.statusCode());
                assertEquals(invalid.code(), code(response));
            }
            String oversized = "{\"clientRequestId\":\"" + "x".repeat(65_536) + "\"}";
            HttpResponse<String> tooLarge = fixture.server().json(
                    "/api/v1/call-records/" + completed.id() + "/retry",
                    "POST", oversized, true);
            assertEquals(413, tooLarge.statusCode());
            assertEquals("JSON_BODY_TOO_LARGE", code(tooLarge));
            assertEquals(401, fixture.server().json(
                    "/api/v1/call-records/" + completed.id() + "/retry",
                    "POST", "{\"clientRequestId\":\"no-auth\"}", false).statusCode());
        }
    }

    @Test
    void allSevenTelephoneShapesOwnMethodMismatchWhileOtherPathsFallThrough()
            throws Exception {
        try (CreateFixture fixture = CreateFixture.open(tempDir)) {
            String id = "550e8400-e29b-41d4-a716-446655440000";
            for (RequestCase route : List.of(
                    new RequestCase("/api/v1/contacts/email%3Abuyer%40example.com/call-records",
                            "GET", "", ""),
                    new RequestCase("/api/v1/contacts/email%3Abuyer%40example.com/timeline",
                            "POST", "", ""),
                    new RequestCase("/api/v1/call-records/" + id, "POST", "", ""),
                    new RequestCase("/api/v1/call-records/" + id + "/audio-sessions",
                            "GET", "", ""),
                    new RequestCase("/api/v1/call-records/" + id + "/audio", "HEAD", "", ""),
                    new RequestCase("/api/v1/call-records/" + id + "/retry", "GET", "", ""),
                    new RequestCase("/api/v1/call-records/" + id + "/transcript", "GET", "", ""))) {
                HttpResponse<byte[]> response = fixture.server().sendBytes(HttpRequest.newBuilder(
                                fixture.server().baseUri().resolve(route.suffix()))
                        .method(route.method(), HttpRequest.BodyPublishers.noBody()).build());
                assertEquals(405, response.statusCode(), route.suffix());
            }
            assertEquals(418, fixture.server().get("/api/threads").statusCode());
        }
    }

    private void assertRejectedWithoutPublishing(CreateFixture fixture, String requestId,
                                                  Map<String, String> fields,
                                                  List<Path> files, boolean trailingField,
                                                  String expectedCode) throws Exception {
        int before = fixture.repository().listByAnchors(Set.of(CONTACT)).size();
        HttpResponse<String> response = fixture.server().send(createRequest(
                fixture.server().baseUri(), fields, files, trailingField));
        assertEquals(400, response.statusCode(), requestId);
        assertEquals(expectedCode, code(response), requestId);
        assertEquals(before, fixture.repository().listByAnchors(Set.of(CONTACT)).size(), requestId);
        try (var audio = Files.list(tempDir.resolve("audio"))) {
            assertEquals(before, audio.count(), requestId);
        }
    }

    private static Map<String, String> fields() {
        return requestFields("request-1");
    }

    private static Map<String, String> requestFields(String requestId) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("direction", "inbound");
        fields.put("occurredAt", "2026-07-30T09:00:00Z");
        fields.put("clientRequestId", requestId);
        return fields;
    }

    private static HttpRequest createRequest(URI baseUri, Map<String, String> fields,
                                             List<Path> files, boolean trailingField)
            throws IOException {
        return createRequest(baseUri, fields, files, trailingField, true);
    }

    private static HttpRequest createRequest(URI baseUri, Map<String, String> fields,
                                             List<Path> files, boolean trailingField,
                                             boolean authenticated) throws IOException {
        String boundary = "call-record-test-boundary";
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n", StandardCharsets.UTF_8));
        }
        for (int index = 0; index < files.size(); index++) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"call-"
                    + index + ".mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofFile(files.get(index)));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        if (trailingField) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"unexpected\"\r\n\r\nx\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(
                        "/api/v1/contacts/email%3Abuyer%40example.com/call-records"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        parts.toArray(HttpRequest.BodyPublisher[]::new)));
        if (authenticated) request.header("X-WeCom-Viewer-Auth", "viewer-token");
        return request.build();
    }

    private static Path fixtureMp3() throws Exception {
        return Path.of(CallRecordHttpAdapterTest.class.getResource(
                "/callrecord/short-ding.mp3").toURI());
    }

    private static String code(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject()
                .get("code").getAsString();
    }

    private record TestServer(HttpServer server, URI baseUri) implements AutoCloseable {
        static TestServer start(CallRecordHttpAdapter adapter) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if (!adapter.handle(exchange)) {
                    byte[] body = "legacy".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(418, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                }
            });
            server.start();
            return new TestServer(server, URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
        }

        HttpResponse<String> post(String rawPath) throws Exception {
            return send(HttpRequest.newBuilder(baseUri.resolve(rawPath))
                    .header("X-WeCom-Viewer-Auth", "viewer-token")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        HttpResponse<String> send(HttpRequest request) throws Exception {
            return HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<byte[]> sendBytes(HttpRequest request) throws Exception {
            return HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<byte[]> audio(String path, String cookie, String range) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path)).GET();
            if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
            if (range != null) request.header("Range", range);
            return sendBytes(request.build());
        }

        HttpResponse<String> json(String path, String method, String body,
                                  boolean authenticated) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (authenticated) request.header("X-WeCom-Viewer-Auth", "viewer-token");
            return send(request.build());
        }

        HttpResponse<String> get(String rawPath) throws Exception {
            return send(HttpRequest.newBuilder(baseUri.resolve(rawPath)).GET().build());
        }

        HttpResponse<String> authenticatedGet(String rawPath) throws Exception {
            return send(HttpRequest.newBuilder(baseUri.resolve(rawPath))
                    .header("X-WeCom-Viewer-Auth", "viewer-token").GET().build());
        }

        RawResponse postDeclaredLength(long contentLength) throws Exception {
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.setSoTimeout(2_000);
                String headers = "POST /api/v1/contacts/email%3Abuyer%40example.com/call-records HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "X-WeCom-Viewer-Auth: viewer-token\r\n"
                        + "Content-Type: multipart/form-data; boundary=oversized\r\n"
                        + "Content-Length: " + contentLength + "\r\n"
                        + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                InputStream input = socket.getInputStream();
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                int matched = 0;
                byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                while (matched < delimiter.length) {
                    int next = input.read();
                    if (next < 0) throw new IOException("response headers ended early");
                    headerBytes.write(next);
                    matched = next == delimiter[matched] ? matched + 1
                            : (next == delimiter[0] ? 1 : 0);
                }
                String headersText = headerBytes.toString(StandardCharsets.US_ASCII);
                String statusLine = headersText.substring(0, headersText.indexOf("\r\n"));
                int status = Integer.parseInt(statusLine.split(" ")[1]);
                long responseLength = headersText.lines()
                        .filter(line -> line.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("content-length:"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .mapToLong(Long::parseLong).findFirst().orElse(0);
                String body = new String(input.readNBytes(Math.toIntExact(responseLength)),
                        StandardCharsets.UTF_8);
                return new RawResponse(status, body);
            }
        }

        RawResponse postMultipartHeaderOnly(String requestId) throws Exception {
            String boundary = "preflight-boundary";
            StringBuilder prefix = new StringBuilder();
            for (Map.Entry<String, String> field : requestFields(requestId).entrySet()) {
                prefix.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"")
                        .append(field.getKey()).append("\"\r\n\r\n")
                        .append(field.getValue()).append("\r\n");
            }
            prefix.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"file\"; ")
                    .append("filename=\"call.mp3\"\r\n")
                    .append("Content-Type: audio/mpeg\r\n\r\n");
            byte[] sent = prefix.toString().getBytes(StandardCharsets.UTF_8);
            long declaredLength = sent.length + 4_096L;
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.setSoTimeout(2_000);
                String headers = "POST /api/v1/contacts/email%3Abuyer%40example.com/call-records HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "X-WeCom-Viewer-Auth: viewer-token\r\n"
                        + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                        + "Content-Length: " + declaredLength + "\r\n"
                        + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(sent);
                socket.getOutputStream().flush();
                InputStream input = socket.getInputStream();
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                int matched = 0;
                byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                while (matched < delimiter.length) {
                    int next = input.read();
                    if (next < 0) throw new IOException("response headers ended early");
                    headerBytes.write(next);
                    matched = next == delimiter[matched] ? matched + 1
                            : (next == delimiter[0] ? 1 : 0);
                }
                String headersText = headerBytes.toString(StandardCharsets.US_ASCII);
                String statusLine = headersText.substring(0, headersText.indexOf("\r\n"));
                int status = Integer.parseInt(statusLine.split(" ")[1]);
                long responseLength = headersText.lines()
                        .filter(line -> line.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("content-length:"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .mapToLong(Long::parseLong).findFirst().orElse(0);
                String body = new String(input.readNBytes(Math.toIntExact(responseLength)),
                        StandardCharsets.UTF_8);
                return new RawResponse(status, body);
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    private record CreateFixture(TestServer server, FileCallRecordRepository repository,
                                 ContactTimelineService timeline, CallRecordService service)
            implements AutoCloseable {
        static CreateFixture open(Path root) throws Exception {
            return open(root, 100);
        }

        static CreateFixture open(Path root, int queueCapacity) throws Exception {
            return open(root, queueCapacity, token -> {
                if (!"viewer-token".equals(token)) {
                    throw new CallRecordException(
                            "AUTH_REQUIRED", 401, "认证已失效", false);
                }
                return "viewer-1";
            });
        }

        static CreateFixture open(Path root, int queueCapacity,
                                  CallRecordHttpAdapter.ViewerActorResolver actors)
                throws Exception {
            Path emailDir = root.resolve("email");
            Files.createDirectories(emailDir);
            Files.writeString(emailDir.resolve("inbox.jsonl"), "{"
                    + "\"id\":\"mail-1\",\"direction\":\"in\","
                    + "\"contactEmail\":\"buyer@example.com\","
                    + "\"sentDate\":\"2026-07-30T08:00:00Z\","
                    + "\"bodyText\":\"message\",\"_raw\":\"raw-secret\"}\n");
            Config config = new Config(Map.of(
                    "CALL_RECORD_DATA_DIR", root.toString(),
                    "DATA_DIR", root.toString(),
                    "EMAIL_DATA_DIR", emailDir.toString(),
                    "CHATAPP_DATA_FILE", root.resolve("chatapp.jsonl").toString(),
                    "CHATAPP_TEMPLATE_FILE", root.resolve("templates.json").toString(),
                    "CALL_RECORD_MAX_RECORDS", "100",
                    "CALL_RECORD_QUEUE_CAPACITY", Integer.toString(queueCapacity)));
            UnifiedMessageStore store = new UnifiedMessageStore(config);
            FileCallRecordRepository repository = FileCallRecordRepository.open(
                    config, Clock.fixed(NOW, ZoneOffset.UTC));
            CallRecordService service = new CallRecordService(repository,
                    new LocalAudioStore(config), store::contactGroup, config,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            ContactTimelineService timeline = new ContactTimelineService(store, service);
            CallRecordHttpAdapter adapter = new CallRecordHttpAdapter(
                    config, service, timeline, new LocalAudioStore(config),
                    new CallAudioSessionService(config), actors);
            return new CreateFixture(TestServer.start(adapter), repository, timeline, service);
        }

        CallRecord createAndFail(String requestId) throws Exception {
            CallRecord created = create(requestId);
            CallRecord processing = CallRecordStateMachine.lease(
                    created, "worker", NOW, NOW.plusSeconds(60));
            repository.replace(processing, created.version());
            CallRecord failed = CallRecordStateMachine.fail(
                    processing, processing.transcription().lease().id(),
                    new CallRecordError("FUNASR_REJECTED", "rejected", false), NOW, 3);
            return repository.replace(failed, processing.version());
        }

        CallRecord createAndComplete(String requestId) throws Exception {
            CallRecord created = create(requestId);
            CallRecord processing = CallRecordStateMachine.lease(
                    created, "worker", NOW, NOW.plusSeconds(60));
            repository.replace(processing, created.version());
            CallRecord completed = CallRecordStateMachine.complete(
                    processing, processing.transcription().lease().id(),
                    new TranscriptionResult("sensevoice", 1, "机器原文",
                            List.of(new TranscriptSegment(0, 1, "机器原文")), NOW), NOW);
            return repository.replace(completed, processing.version());
        }

        private CallRecord create(String requestId) throws Exception {
            try (InputStream input = Files.newInputStream(fixtureMp3())) {
                return service.create(new CallRecordService.CreateCallRecordCommand(
                        CONTACT, "", "inbound", NOW.minusSeconds(60), requestId,
                        "call.mp3", "audio/mpeg", "fixture"), input);
            }
        }

        @Override public void close() throws Exception {
            server.close();
            repository.close();
        }
    }

    private record RawResponse(int statusCode, String body) {}
    private record RequestCase(String suffix, String method, String body, String code) {}
}
