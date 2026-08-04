package com.crmforlogistics.messagecenter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComAuthorizationCallbackRouteTest {
    private static final String SUITE = "dk-suite";
    private static final String TOKEN = "callback-token";
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final byte[] CALLBACK_KEY = callbackKey();

    @TempDir
    Path tempDir;

    @Test
    void acceptsOfficialEncryptedCallbackAndRejectsInvalidRequests() throws Exception {
        Config config = config();
        UnifiedMessageStore messages = new UnifiedMessageStore(config);
        WeComAuthorizationStore installations = installationStore();
        WeComViewerService viewer = WeComViewerService.forTests(config, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "viewer-nonce", new WeComViewerService.StaticGateway("corp-ticket",
                        "agent-ticket", "user-1"), installations);
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, encodingKey(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RecordingGateway gateway = new RecordingGateway();
        try (WeComAuthorizationService service = new WeComAuthorizationService(
                config, installations, gateway)) {
            String timestamp = Long.toString(NOW.getEpochSecond());
            String nonce = "callback-nonce";
            String encrypted = encrypt("<xml><SuiteId>dk-suite</SuiteId>"
                    + "<InfoType>suite_ticket</InfoType><SuiteTicket>ticket-value</SuiteTicket></xml>");
            String body = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";
            String signature = WeComCallbackCodec.sha1(TOKEN, timestamp, nonce, encrypted);

            String encryptedEcho = encrypt("verified-echo");
            String echoSignature = WeComCallbackCodec.sha1(TOKEN, timestamp, nonce, encryptedEcho);
            FakeExchange validated = getExchange(echoSignature, timestamp, nonce, encryptedEcho);
            App.routeForTests(validated, config, messages, viewer, null, codec, service);
            assertEquals(200, validated.responseCode);
            assertEquals("verified-echo", validated.responseText());
            assertEquals("text/plain; charset=utf-8", validated.responseHeaders.getFirst("Content-Type"));

            FakeExchange invalidEcho = getExchange("bad-signature", timestamp, nonce, encryptedEcho);
            App.routeForTests(invalidEcho, config, messages, viewer, null, codec, service);
            assertEquals(403, invalidEcho.responseCode);

            FakeExchange accepted = exchange(signature, timestamp, nonce, body);
            App.routeForTests(accepted, config, messages, viewer, null, codec, service);
            assertEquals(200, accepted.responseCode);
            assertEquals("success", accepted.responseText());
            assertEquals("text/plain; charset=utf-8", accepted.responseHeaders.getFirst("Content-Type"));
            assertEquals("ticket-value", gateway.ticket);

            FakeExchange wrongSignature = exchange("bad-signature", timestamp, nonce, body);
            App.routeForTests(wrongSignature, config, messages, viewer, null, codec, service);
            assertEquals(403, wrongSignature.responseCode);
            assertTrue(wrongSignature.responseText().contains("WECOM_CALLBACK_SIGNATURE_INVALID"));

            FakeExchange missingNonce = exchange(signature, timestamp, "", body);
            App.routeForTests(missingNonce, config, messages, viewer, null, codec, service);
            assertEquals(403, missingNonce.responseCode);

            FakeExchange hookValidated = getExchange(
                    "/hook_path", echoSignature, timestamp, nonce, encryptedEcho);
            App.routeForTests(hookValidated, config, messages, viewer, null, codec, service);
            assertEquals(200, hookValidated.responseCode);
            assertEquals("verified-echo", hookValidated.responseText());

            FakeExchange hookAccepted = exchange("/hook_path", signature, timestamp, nonce, body);
            App.routeForTests(hookAccepted, config, messages, viewer, null, codec, service);
            assertEquals(200, hookAccepted.responseCode);
            assertEquals("success", hookAccepted.responseText());
        }

        FakeExchange unavailable = exchange("signature", Long.toString(NOW.getEpochSecond()),
                "nonce", "<xml/>");
        App.routeForTests(unavailable, config, messages, viewer, null, null, null);
        assertEquals(503, unavailable.responseCode);
        assertTrue(unavailable.responseText().contains("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE"));

        FakeExchange unavailableGet = getExchange("signature", Long.toString(NOW.getEpochSecond()),
                "nonce", "encrypted-echo");
        App.routeForTests(unavailableGet, config, messages, viewer, null, null, null);
        assertEquals(503, unavailableGet.responseCode);
        assertTrue(unavailableGet.responseText().contains("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE"));
    }

    private Config config() throws Exception {
        Path email = tempDir.resolve("email");
        Files.createDirectories(email);
        return new Config(Map.ofEntries(
                Map.entry("DATA_DIR", tempDir.toString()),
                Map.entry("EMAIL_DATA_DIR", email.toString()),
                Map.entry("CHATAPP_DATA_FILE", tempDir.resolve("chatapp.jsonl").toString()),
                Map.entry("CHATAPP_TEMPLATE_FILE", tempDir.resolve("templates.json").toString()),
                Map.entry("WECOM_DATA_FILE", tempDir.resolve("wecom.jsonl").toString()),
                Map.entry("WECOM_SUITE_ID", SUITE),
                Map.entry("WECOM_TOKEN", TOKEN),
                Map.entry("WECOM_ENCODING_AES_KEY", encodingKey()),
                Map.entry("WECOM_AUTHORIZATION_AUDIT_FILE", tempDir.resolve("authorization-audit.jsonl").toString()),
                Map.entry("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099")));
    }

    private WeComAuthorizationStore installationStore() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        return WeComAuthorizationStore.forTests(tempDir.resolve("installations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FakeExchange exchange(String signature, String timestamp, String nonce, String body) {
        return exchange("/api/v1/wecom/authorization/callback", signature, timestamp, nonce, body);
    }

    private static FakeExchange exchange(String path, String signature, String timestamp, String nonce, String body) {
        String uri = path + "?msg_signature=" + signature
                + "&timestamp=" + timestamp;
        if (!nonce.isEmpty()) uri += "&nonce=" + nonce;
        return new FakeExchange("POST", uri, body);
    }

    private static FakeExchange getExchange(String signature, String timestamp, String nonce, String echo) {
        return getExchange("/api/v1/wecom/authorization/callback", signature, timestamp, nonce, echo);
    }

    private static FakeExchange getExchange(String path, String signature, String timestamp, String nonce,
                                            String echo) {
        String uri = path + "?msg_signature=" + signature
                + "&timestamp=" + timestamp + "&nonce=" + nonce + "&echostr="
                + java.net.URLEncoder.encode(echo, StandardCharsets.UTF_8);
        return new FakeExchange("GET", uri, "");
    }

    private static String encrypt(String xml) throws Exception {
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        byte[] receiveId = SUITE.getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(16 + 4 + xmlBytes.length + receiveId.length)
                .put(new byte[16]).putInt(xmlBytes.length).put(xmlBytes).put(receiveId).array();
        int padding = 32 - plain.length % 32;
        byte[] padded = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(padded, plain.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(CALLBACK_KEY, "AES"),
                new IvParameterSpec(CALLBACK_KEY, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static byte[] callbackKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
        return key;
    }

    private static String encodingKey() {
        return Base64.getEncoder().withoutPadding().encodeToString(CALLBACK_KEY);
    }

    private static final class RecordingGateway implements WeComAuthorizationClient {
        private String ticket = "";

        @Override
        public void acceptSuiteTicket(String suiteId, String suiteTicket, Instant receivedAt) {
            ticket = suiteTicket;
        }

        @Override
        public WeComAuthorizationGateway.PermanentCodeResponse getPermanentCode(String authCode) {
            return new WeComAuthorizationGateway.PermanentCodeResponse("ww-corp", "permanent-code");
        }

        @Override
        public WeComAuthorizationGateway.AuthorizationInfo getAuthInfo(String authCorpId, String permanentCode) {
            return new WeComAuthorizationGateway.AuthorizationInfo(authCorpId,
                    List.of(new WeComAuthorizationGateway.AuthorizedAgent("1000002")));
        }
    }

    private static final class FakeExchange extends HttpExchange {
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayInputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final String method;
        private int responseCode;

        private FakeExchange(String method, String uri, String body) {
            this.method = method;
            this.uri = URI.create(uri);
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            requestHeaders.set("Content-Type", "application/xml");
        }

        private String responseText() { return responseBody.toString(StandardCharsets.UTF_8); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int code, long length) { responseCode = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
