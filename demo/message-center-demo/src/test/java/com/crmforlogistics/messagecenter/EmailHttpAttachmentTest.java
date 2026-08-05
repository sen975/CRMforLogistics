package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailHttpAttachmentTest {
    @Test
    void parsesOrderedMultipartFieldsAndRepeatedFilesWithoutJsonBuffering() throws Exception {
        String boundary = "mail-boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"to\"\r\n\r\n"
                + "buyer@example.com\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"subject\"\r\n\r\n"
                + "Quote\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"body\"\r\n\r\n"
                + "See files\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "alpha\r\n"
                + "--" + boundary + "--\r\n";
        Path dir = Files.createTempDirectory("email-http-parser");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        FakeExchange exchange = new FakeExchange(body);
        exchange.requestHeaders.set("Content-Type", "multipart/form-data; boundary=" + boundary);

        EmailSendCommand command = new EmailMultipartParser(config).parse(exchange);

        assertEquals("buyer@example.com", command.to());
        assertEquals("Quote", command.subject());
        assertEquals(1, command.attachments().size());
        assertEquals("a.txt", command.attachments().get(0).fileName());
    }

    @Test
    void rejectsNonMultipartBeforeCreatingStagedFiles() throws Exception {
        Path dir = Files.createTempDirectory("email-http-required");
        Path emailDir = dir.resolve("email");
        Files.createDirectories(emailDir);
        Config config = config(dir, emailDir);
        FakeExchange exchange = new FakeExchange("{}");

        EmailMultipartException exception = assertThrows(EmailMultipartException.class,
                () -> new EmailMultipartParser(config).parse(exchange));

        assertEquals("EMAIL_MULTIPART_REQUIRED", exception.code());
        try (var entries = Files.list(emailDir.resolve("attachment-tmp"))) {
            assertEquals(0, entries.count());
        }
    }

    private static Config config(Path dir, Path emailDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailDir.toString());
        values.put("CHATAPP_DATA_FILE", dir.resolve("chatapp.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDir.resolve("groups.jsonl").toString());
        return new Config(values);
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayInputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String body) {
            requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1));
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/api/send/email"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int code, long length) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return 0; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
