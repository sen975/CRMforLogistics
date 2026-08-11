package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAppOssMediaUploaderTest {

    @Test
    void shouldDeriveObjectKeyAndHttpsUrlFromCamsAuthorization() {
        String objectKey = ChatAppOssMediaUploader.objectKey("whatsapp/templates", "Invoice.PDF");

        assertThat(objectKey).startsWith("whatsapp/templates/").endsWith(".pdf");
        assertThat(ChatAppOssMediaUploader.httpsObjectUrl("https://oss-ap-southeast-1.aliyuncs.com/",
                "cams-media", objectKey))
                .isEqualTo("https://cams-media.oss-ap-southeast-1.aliyuncs.com/" + objectKey);
    }

    @Test
    void uploadsViaPutWithSignedHeadersAndBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> securityToken = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> date = new AtomicReference<>();
        AtomicReference<URL> requestedUrl = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            securityToken.set(exchange.getRequestHeaders().getFirst("x-oss-security-token"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            date.set(exchange.getRequestHeaders().getFirst("Date"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            var auth = GetChatappUploadAuthorizationResponseBody.Data.builder()
                    .bucketName("cams-media").dir("templates").endPoint("oss.example.com")
                    .accessKeyId("id").accessKeySecret("secret").securityToken("token").build();
            ChatAppOssMediaUploader uploader = new ChatAppOssMediaUploader(url -> {
                requestedUrl.set(url);
                return localConnection(server);
            });

            var uploaded = uploader.upload(auth, "payload".getBytes(StandardCharsets.UTF_8), "a.png", "image/png");

            assertThat(method).hasValue("PUT");
            assertThat(contentType).hasValue("image/png");
            assertThat(securityToken).hasValue("token");
            assertThat(authorization).hasValueSatisfying(value -> assertThat(value).startsWith("OSS id:"));
            assertThat(date).hasValueSatisfying(value -> assertThat(value).isNotBlank());
            assertThat(body).hasValue("payload".getBytes(StandardCharsets.UTF_8));
            assertThat(requestedUrl.get().toString()).startsWith("https://cams-media.oss.example.com/templates/");
            assertThat(uploaded.url()).startsWith("https://cams-media.oss.example.com/templates/");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNon2xxOssResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var auth = GetChatappUploadAuthorizationResponseBody.Data.builder()
                    .bucketName("cams-media").dir("templates").endPoint("oss.example.com")
                    .accessKeyId("id").accessKeySecret("secret").build();
            ChatAppOssMediaUploader uploader = new ChatAppOssMediaUploader(url -> localConnection(server));

            assertThatThrownBy(() -> uploader.upload(auth, new byte[]{1}, "a.png", "image/png"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 503");
        } finally {
            server.stop(0);
        }
    }

    private static HttpURLConnection localConnection(HttpServer server) {
        try {
            return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/put").openConnection();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
