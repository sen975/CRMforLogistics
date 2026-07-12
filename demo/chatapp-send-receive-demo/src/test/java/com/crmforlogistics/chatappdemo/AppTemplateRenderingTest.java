package com.crmforlogistics.chatappdemo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AppTemplateRenderingTest {
    public static void main(String[] args) throws Exception {
        rendersTemplateBodyFromLocalTemplateStore();
        rendersDollarParenthesesTemplatePlaceholders();
        fallsBackToTemplateParameterPreviewWhenTemplateIsMissing();
        rendersSelectedTemplateForOutboundBubble();
        rendersLocalEmojiCatalogForWebPicker();
        buildsMediaContentJsonForImageVideoAndDocument();
        sanitizesUploadedObjectNames();
        keepsMediaMetadataWhenReadingStoredMessages();
        parsesUtf8MultipartFieldsAndFileNames();
        parsesRfc5987MultipartFileName();
        rendersIdempotentSendGuardInWebClient();
        rendersPrivateMediaThroughLocalProxy();
        findsStoredMediaByMessageId();
        savesAndResolvesLocalMediaCopy();
        rejectsHistoricalMediaWithoutLocalCopy();
        reusesPersistedResultForSameClientRequestId();
        doesNotRepeatFailedRequestWithSameClientRequestId();
        findsPersistedMessageByClientRequestId();
    }

    private static void rendersTemplateBodyFromLocalTemplateStore() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-template-test");
        Path messageFile = dir.resolve("messages.jsonl");
        Path templateFile = dir.resolve("templates.json");

        Files.writeString(templateFile, "[{"
                + "\"templateCode\":\"tpl-001\","
                + "\"templateName\":\"shipping_notice\","
                + "\"languageCode\":\"zh_CN\","
                + "\"body\":\"Hello {{3}}, your address is {{1}}. Contact {{2}}.\","
                + "\"placeholders\":[\"3\",\"1\",\"2\"],"
                + "\"raw\":\"{}\","
                + "\"updatedAt\":\"2026-07-10T00:00:00Z\""
                + "}]", StandardCharsets.UTF_8);

        String raw = "{\"businessNumber\":\"8613266259485\","
                + "\"eventAction\":\"DOWN\","
                + "\"languageCode\":\"zh_CN\","
                + "\"message\":\"{\\\"text1\\\":\\\"Songshan Lake\\\",\\\"text2\\\":\\\"Yuewei Service\\\",\\\"text\\\":\\\"Xiaosen\\\"}\","
                + "\"messageId\":\"msg-template-001\","
                + "\"messageType\":\"TEMPLATE\","
                + "\"messageTypeName\":\"template\","
                + "\"templateCode\":\"tpl-001\","
                + "\"templateName\":\"shipping_notice\","
                + "\"userNumber\":\"8613428277520\"}";
        writeMessage(messageFile, raw);

        App.MessageStore store = new App.MessageStore(messageFile, new App.TemplateStore(templateFile));
        List<App.StoredMessage> messages = store.readAll();

        assertEquals("Hello Xiaosen, your address is Songshan Lake. Contact Yuewei Service.",
                messages.get(0).text);
    }

    private static void rendersDollarParenthesesTemplatePlaceholders() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-template-dollar-test");
        Path messageFile = dir.resolve("messages.jsonl");
        Path templateFile = dir.resolve("templates.json");

        Files.writeString(templateFile, "[{"
                + "\"templateCode\":\"tpl-dollar\","
                + "\"templateName\":\"wl\","
                + "\"languageCode\":\"zh_CN\","
                + "\"body\":\"嗨$(text),您的送货地址已成功更新到$(text1)。任何咨询请联系 $(text2)。\","
                + "\"placeholders\":[\"text\",\"text1\",\"text2\"],"
                + "\"raw\":\"{}\","
                + "\"updatedAt\":\"2026-07-10T00:00:00Z\""
                + "}]", StandardCharsets.UTF_8);

        String raw = "{\"businessNumber\":\"8613266259485\","
                + "\"eventAction\":\"DOWN\","
                + "\"languageCode\":\"zh_CN\","
                + "\"message\":\"{\\\"text1\\\":\\\"松山湖\\\",\\\"text2\\\":\\\"悦为小森\\\",\\\"text\\\":\\\"经理\\\"}\","
                + "\"messageId\":\"msg-template-dollar\","
                + "\"messageType\":\"TEMPLATE\","
                + "\"messageTypeName\":\"template\","
                + "\"templateCode\":\"tpl-dollar\","
                + "\"templateName\":\"wl\","
                + "\"userNumber\":\"8613428277520\"}";
        writeMessage(messageFile, raw);

        App.MessageStore store = new App.MessageStore(messageFile, new App.TemplateStore(templateFile));
        List<App.StoredMessage> messages = store.readAll();

        assertEquals("嗨经理,您的送货地址已成功更新到松山湖。任何咨询请联系 悦为小森。", messages.get(0).text);
    }

    private static void fallsBackToTemplateParameterPreviewWhenTemplateIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-template-fallback-test");
        Path messageFile = dir.resolve("messages.jsonl");
        Path templateFile = dir.resolve("templates.json");

        String raw = "{\"businessNumber\":\"8613266259485\","
                + "\"eventAction\":\"DOWN\","
                + "\"languageCode\":\"zh_CN\","
                + "\"message\":\"{\\\"text1\\\":\\\"Songshan Lake\\\",\\\"text2\\\":\\\"Yuewei Service\\\",\\\"text\\\":\\\"Xiaosen\\\"}\","
                + "\"messageId\":\"msg-template-002\","
                + "\"messageType\":\"TEMPLATE\","
                + "\"messageTypeName\":\"template\","
                + "\"templateCode\":\"missing-template\","
                + "\"templateName\":\"wl\","
                + "\"userNumber\":\"8613428277520\"}";
        writeMessage(messageFile, raw);

        App.MessageStore store = new App.MessageStore(messageFile, new App.TemplateStore(templateFile));
        List<App.StoredMessage> messages = store.readAll();

        assertEquals("模板消息 wl: Songshan Lake / Yuewei Service / Xiaosen", messages.get(0).text);
    }

    private static void rendersSelectedTemplateForOutboundBubble() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-template-outbound-test");
        Path templateFile = dir.resolve("templates.json");

        Files.writeString(templateFile, "[{"
                + "\"templateCode\":\"tpl-send\","
                + "\"templateName\":\"shipping_notice\","
                + "\"languageCode\":\"en_US\","
                + "\"body\":\"Hi $(text), your address is $(text1). Contact $(text2).\","
                + "\"placeholders\":[],"
                + "\"raw\":\"{}\","
                + "\"updatedAt\":\"2026-07-10T00:00:00Z\""
                + "}]", StandardCharsets.UTF_8);

        App.TemplateStore templateStore = new App.TemplateStore(templateFile);
        String rendered = App.renderOutboundTemplateText(
                templateStore,
                "tpl-send",
                "shipping_notice",
                "en_US",
                "{\"text\":\"Alex\",\"text1\":\"Songshan Lake\",\"text2\":\"Service Team\"}"
        );

        assertEquals("Hi Alex, your address is Songshan Lake. Contact Service Team.", rendered);
    }

    private static void rendersLocalEmojiCatalogForWebPicker() {
        String json = App.emojiCatalogJson();

        assertContains(json, "Smileys");
        assertContains(json, "Business");
        assertContains(json, "😀");
        assertContains(json, "🚢");
    }

    private static void buildsMediaContentJsonForImageVideoAndDocument() {
        assertEquals("{\"link\":\"https://example.com/a.png\",\"caption\":\"报价单\"}",
                App.mediaContentJson("image", "https://example.com/a.png", "报价单", "a.png"));
        assertEquals("{\"link\":\"https://example.com/a.mp4\",\"caption\":\"装柜视频\"}",
                App.mediaContentJson("video", "https://example.com/a.mp4", "装柜视频", "a.mp4"));
        assertEquals("{\"link\":\"https://example.com/a.pdf\",\"caption\":\"发票\",\"fileName\":\"invoice.pdf\"}",
                App.mediaContentJson("document", "https://example.com/a.pdf", "发票", "invoice.pdf"));
    }

    private static void sanitizesUploadedObjectNames() {
        String objectKey = App.uploadObjectKey("chatapp/media/", "报价 单 01.pdf");

        if (!objectKey.startsWith("chatapp/media/")) {
            throw new AssertionError("Expected object key to keep dir prefix: " + objectKey);
        }
        if (!objectKey.endsWith(".pdf")) {
            throw new AssertionError("Expected object key to keep extension: " + objectKey);
        }
        if (objectKey.contains(" ") || objectKey.contains("报价")) {
            throw new AssertionError("Expected object key to be storage-safe: " + objectKey);
        }
    }

    private static void keepsMediaMetadataWhenReadingStoredMessages() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-media-test");
        Path messageFile = dir.resolve("messages.jsonl");
        String raw = "{\"messageType\":\"image\",\"content\":{\"link\":\"https://example.com/a.png\",\"caption\":\"箱单\"}}";
        App.StoredMessage message = App.StoredMessage.outbound("media-1", "8613000000000", "8613111111111",
                "[图片] 箱单", raw);
        message.mediaType = "image";
        message.mediaUrl = "https://example.com/a.png";
        message.mimeType = "image/png";
        message.fileName = "a.png";
        message.caption = "箱单";

        App.MessageStore store = new App.MessageStore(messageFile);
        store.append(message);
        App.StoredMessage saved = store.readAll().get(0);

        assertEquals("image", saved.mediaType);
        assertEquals("https://example.com/a.png", saved.mediaUrl);
        assertEquals("image/png", saved.mimeType);
        assertEquals("a.png", saved.fileName);
        assertEquals("箱单", saved.caption);
    }

    private static void parsesUtf8MultipartFieldsAndFileNames() {
        String caption = "\u8fd9\u662f\u6211\u4eec\u7684\u8425\u4e1a\u6267\u7167";
        String fileName = "\u8425\u4e1a\u6267\u7167.jpg";
        String boundary = "----chatapp-test-boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"caption\"\r\n\r\n"
                + caption + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n"
                + "image-bytes\r\n"
                + "--" + boundary + "--\r\n";

        App.MultipartForm form = App.parseMultipartBody(body.getBytes(StandardCharsets.UTF_8), boundary);

        assertEquals(caption, form.field("caption"));
        assertEquals(fileName, form.file("file").fileName);
    }

    private static void reusesPersistedResultForSameClientRequestId() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-idempotent-execution-test");
        App.MessageStore store = new App.MessageStore(dir.resolve("messages.jsonl"));
        App.IdempotentRequestExecutor executor = new App.IdempotentRequestExecutor();
        AtomicInteger sends = new AtomicInteger();

        App.StoredMessage first = App.executeIdempotently(store, executor, "draft-001", "fingerprint-001", () -> {
            sends.incrementAndGet();
            App.StoredMessage message = App.StoredMessage.outbound("message-1", "from", "to", "hello", "{}");
            message.clientRequestId = "draft-001";
            message.requestFingerprint = "fingerprint-001";
            return store.append(message);
        });
        App.StoredMessage retry = App.executeIdempotently(store, executor, "draft-001", "fingerprint-001", () -> {
            sends.incrementAndGet();
            return App.StoredMessage.outbound("message-2", "from", "to", "hello", "{}");
        });

        assertEquals("message-1", first.id);
        assertEquals("message-1", retry.id);
        assertEquals("1", String.valueOf(sends.get()));
    }

    private static void parsesRfc5987MultipartFileName() {
        String boundary = "----chatapp-rfc5987-boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''%E8%90%A5%E4%B8%9A%E6%89%A7%E7%85%A7.jpg\r\n"
                + "Content-Type: image/jpeg\r\n\r\n"
                + "image-bytes\r\n"
                + "--" + boundary + "--\r\n";

        App.MultipartForm form = App.parseMultipartBody(body.getBytes(StandardCharsets.UTF_8), boundary);

        assertEquals("\u8425\u4e1a\u6267\u7167.jpg", form.file("file").fileName);
    }

    private static void rendersIdempotentSendGuardInWebClient() {
        String html = App.html();

        assertContains(html, "id=\"sendButton\"");
        assertContains(html, "clientRequestId");
        assertContains(html, "sendInFlight");
        assertContains(html, "pendingRequestKey");
    }

    private static void rendersPrivateMediaThroughLocalProxy() {
        App.StoredMessage message = App.StoredMessage.outbound("message id/1", "from", "to", "image", "{}");
        message.mediaType = "image";
        message.mediaUrl = "https://private.example.com/object.jpg";
        message.fileName = "object.jpg";
        message.localMediaPath = "object.jpg";

        String proxyUrl = App.mediaProxyUrl(message);

        assertEquals("/api/media?id=message+id%2F1", proxyUrl);
        if (proxyUrl.contains("private.example.com")) {
            throw new AssertionError("Private OSS URL must not be rendered directly: " + proxyUrl);
        }

        message.localMediaPath = "";
        assertEquals("", App.mediaProxyUrl(message));
    }

    private static void findsStoredMediaByMessageId() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-media-proxy-test");
        App.MessageStore store = new App.MessageStore(dir.resolve("messages.jsonl"));
        App.StoredMessage message = App.StoredMessage.outbound("media-001", "from", "to", "image", "{}");
        message.mediaType = "image";
        message.mediaUrl = "https://private.example.com/object.jpg";
        message.objectKey = "tenant/object.jpg";
        message.mimeType = "image/jpeg";
        store.append(message);

        App.StoredMessage found = store.findMediaById("media-001");

        assertEquals("tenant/object.jpg", found.objectKey);
        assertEquals("image/jpeg", found.mimeType);
    }

    private static void savesAndResolvesLocalMediaCopy() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-media-cache-test");
        App.MessageStore store = new App.MessageStore(dir.resolve("messages.jsonl"));
        byte[] expected = "image-bytes".getBytes(StandardCharsets.UTF_8);
        App.StoredMessage message = App.StoredMessage.outbound("media-local", "from", "to", "image", "{}");
        message.mediaType = "image";
        message.mimeType = "image/jpeg";
        message.localMediaPath = store.saveMedia(expected, "photo.jpg");

        Path resolved = store.resolveLocalMedia(message);

        assertEquals("image-bytes", Files.readString(resolved, StandardCharsets.UTF_8));
        if (!resolved.startsWith(dir.resolve("media").toAbsolutePath().normalize())) {
            throw new AssertionError("Resolved media escaped data/media: " + resolved);
        }
    }

    private static void rejectsHistoricalMediaWithoutLocalCopy() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-history-media-test");
        App.MessageStore store = new App.MessageStore(dir.resolve("messages.jsonl"));
        App.StoredMessage message = App.StoredMessage.outbound("history-media", "from", "to", "image", "{}");
        message.mediaType = "image";
        message.mediaUrl = "https://private.example.com/object.jpg";
        message.objectKey = "tenant/object.jpg";

        assertThrows("historical media was not retained locally", () -> App.loadMediaContent(store, message));
    }

    private static void findsPersistedMessageByClientRequestId() throws Exception {
        Path dir = Files.createTempDirectory("chatapp-idempotency-test");
        App.MessageStore store = new App.MessageStore(dir.resolve("messages.jsonl"));
        App.StoredMessage message = App.StoredMessage.outbound("message-1", "from", "to", "hello", "{}");
        message.clientRequestId = "draft-001";
        message.requestFingerprint = "fingerprint-001";
        store.append(message);

        App.StoredMessage found = store.findByClientRequestId("draft-001");

        assertEquals("message-1", found.id);
        assertEquals("fingerprint-001", found.requestFingerprint);
    }

    private static void doesNotRepeatFailedRequestWithSameClientRequestId() throws Exception {
        App.IdempotentRequestExecutor executor = new App.IdempotentRequestExecutor();
        AtomicInteger sends = new AtomicInteger();

        assertThrows("network result is unknown", () -> executor.execute("draft-failed", "fingerprint-001", () -> {
            sends.incrementAndGet();
            throw new IOException("network result is unknown");
        }));
        assertThrows("network result is unknown", () -> executor.execute("draft-failed", "fingerprint-001", () -> {
            sends.incrementAndGet();
            return "message-2";
        }));

        assertEquals("1", String.valueOf(sends.get()));
    }

    private static void writeMessage(Path messageFile, String raw) throws Exception {
        String line = "{\"id\":\"local-template\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-09T06:35:13.738Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613428277520\","
                + "\"text\":\"\","
                + "\"raw\":\"" + escapeJson(raw) + "\"}";
        Files.writeString(messageFile, line + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected:\n" + expected + "\nActual:\n" + actual);
        }
    }

    private static void assertContains(String value, String expected) {
        if (!value.contains(expected)) {
            throw new AssertionError("Expected value to contain: " + expected + "\nActual:\n" + value);
        }
    }

    private static void assertThrows(String expectedMessage, ThrowingAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected exception: " + expectedMessage);
        } catch (Exception ex) {
            assertEquals(expectedMessage, ex.getMessage());
        }
    }

    interface ThrowingAction {
        void run() throws Exception;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
