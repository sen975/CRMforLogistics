package com.crmforlogistics.messagecenter;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import darabonba.core.client.ClientOverrideConfiguration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ChatAppSender {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;
    private final TemplateStore templateStore;

    public ChatAppSender(Config config) {
        this.config = config;
        this.templateStore = new TemplateStore(config.chatappTemplateFile());
    }

    public UnifiedMessage sendText(String to, String text, String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String cleanText = text == null ? "" : text;
        SendChatappMessageRequest.Builder builder = baseBuilder(cleanTo)
                .messageType("text")
                .content(textContentJson(cleanText));
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient()) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = responseMessageId(response);
            String raw = GSON.toJson(response.getBody());
            appendLocal(messageId, "outbound", chatappFrom(), cleanTo, cleanText, "Submitted", raw, Map.of());
            return findStored(messageId, cleanTo);
        }
    }

    public UnifiedMessage sendTemplate(String to, String templateCode, String templateName, String languageCode,
                                       Map<String, String> params, String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String code = required(templateCode, "templateCode");
        String language = ContactPointUtil.firstNonBlank(languageCode, config.value("CHATAPP_LANGUAGE", "en_US"));
        Map<String, String> safeParams = params == null ? Map.of() : params;
        SendChatappMessageRequest request = buildTemplateRequest(config, cleanTo, code, templateName, language,
                safeParams, clientRequestId);

        try (AsyncClient client = createClient()) {
            SendChatappMessageResponse response = client.sendChatappMessage(request).get();
            String messageId = responseMessageId(response);
            String paramsJson = GSON.toJson(safeParams);
            String raw = outboundTemplateRaw(response, code, templateName, language, paramsJson);
            String text = templateStore.render(code, language, paramsJson, templateName);
            appendLocal(messageId, "outbound", chatappFrom(), cleanTo, text, "Submitted", raw, Map.of());
            return findStored(messageId, cleanTo);
        }
    }

    public UnifiedMessage sendMedia(String to, String mediaType, UploadedFile file, String caption,
                                    String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String normalizedType = normalizeMediaType(mediaType);
        if (file == null || file.bytes == null || file.bytes.length == 0) {
            throw new IllegalArgumentException("file is required");
        }
        String fileName = ContactPointUtil.firstNonBlank(file.fileName, "upload.bin");
        String mimeType = ContactPointUtil.firstNonBlank(file.contentType, mimeTypeFromName(fileName), "application/octet-stream");
        validateMediaMime(normalizedType, mimeType);

        try (AsyncClient client = createClient()) {
            GetChatappUploadAuthorizationResponse authResponse = client.getChatappUploadAuthorization(
                    GetChatappUploadAuthorizationRequest.builder()
                            .custSpaceId(requiredConfig("CUST_SPACE_ID"))
                            .build()
            ).get();
            GetChatappUploadAuthorizationResponseBody.Data auth = authResponse.getBody() == null ? null : authResponse.getBody().getData();
            if (auth == null) {
                throw new IllegalStateException("GetChatappUploadAuthorization returned empty data");
            }

            String objectKey = uploadObjectKey(auth.getDir(), fileName);
            String mediaUrl = ossObjectUrl(auth.getEndPoint(), auth.getBucketName(), objectKey);
            uploadToOss(auth, objectKey, file.bytes, mimeType);

            String content = mediaContentJson(normalizedType, mediaUrl, caption, fileName);
            SendChatappMessageRequest.Builder builder = baseBuilder(cleanTo)
                    .messageType(normalizedType)
                    .content(content);
            putIfNotBlank(clientRequestId, builder::taskId);

            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = responseMessageId(response);
            String raw = outboundMediaRaw(response, normalizedType, content, mediaUrl, objectKey, fileName, mimeType, caption);
            String text = mediaDisplayText(normalizedType, caption, fileName);
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("mediaType", normalizedType);
            extra.put("mediaUrl", mediaUrl);
            extra.put("objectKey", objectKey);
            extra.put("mimeType", mimeType);
            extra.put("fileName", fileName);
            extra.put("caption", caption == null ? "" : caption);
            appendLocal(messageId, "outbound", chatappFrom(), cleanTo, text, "Submitted", raw, extra);
            return findStored(messageId, cleanTo);
        }
    }

    public UnifiedMessage appendWebhook(String raw) throws Exception {
        WebhookMessage parsed = WebhookMessage.parse(raw);
        appendLocal(parsed.id, parsed.direction, parsed.from, parsed.to, parsed.text, parsed.status, raw, Map.of());
        return findStored(parsed.direction.equals("status") ? statusTargetId(parsed.id) : parsed.id,
                "outbound".equals(parsed.direction) ? parsed.to : parsed.from);
    }

    private SendChatappMessageRequest.Builder baseBuilder(String to) {
        return SendChatappMessageRequest.builder()
                .custSpaceId(requiredConfig("CUST_SPACE_ID"))
                .from(chatappFrom())
                .to(required(to, "to"))
                .channelType(config.value("CHATAPP_CHANNEL_TYPE", "whatsapp"))
                .type(config.value("CHATAPP_TYPE", "message"));
    }

    static SendChatappMessageRequest buildTemplateRequest(Config config, String to, String templateCode,
                                                          String templateName, String language,
                                                          Map<String, String> params, String clientRequestId) {
        SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                .custSpaceId(requiredConfig(config, "CUST_SPACE_ID"))
                .from(requiredConfig(config, "CHATAPP_FROM"))
                .to(required(to, "to"))
                .channelType(config.value("CHATAPP_CHANNEL_TYPE", "whatsapp"))
                .type(templateRequestType(config))
                .templateCode(required(templateCode, "templateCode"))
                .language(language)
                .templateParams(params == null ? Map.of() : params);
        putIfNotBlank(templateName, builder::templateName);
        putIfNotBlank(clientRequestId, builder::taskId);
        return builder.build();
    }

    static String templateRequestType(Config config) {
        return config.value("CHATAPP_TEMPLATE_TYPE", "template");
    }

    private AsyncClient createClient() {
        ICredentialProvider provider = createCredentialsProvider();
        return AsyncClient.builder()
                .region(config.value("CAMS_REGION", "ap-southeast-1"))
                .credentialsProvider(provider)
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(config.value("CAMS_ENDPOINT", "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private ICredentialProvider createCredentialsProvider() {
        String accessKeyId = config.value("ALIYUN_ACCESS_KEY_ID", "");
        String accessKeySecret = config.value("ALIYUN_ACCESS_KEY_SECRET", "");
        if (!accessKeyId.isBlank() && !accessKeySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private void appendLocal(String id, String direction, String from, String to, String text, String status,
                             String raw, Map<String, String> extra) throws IOException {
        if (config.chatappDataFile().getParent() != null) {
            Files.createDirectories(config.chatappDataFile().getParent());
        }
        String messageId = ContactPointUtil.firstNonBlank(id, "local-" + UUID.randomUUID());
        if (containsMessage(messageId)) {
            return;
        }
        Map<String, String> record = new LinkedHashMap<>();
        record.put("id", messageId);
        record.put("direction", direction);
        record.put("timestamp", Instant.now().toString());
        record.put("from", from == null ? "" : from);
        record.put("to", to == null ? "" : to);
        record.put("text", text == null ? "" : text);
        if (status != null && !status.isBlank() && !"status".equals(direction)) {
            record.put("status", status);
            record.put("statusTimestamp", Instant.now().toString());
        }
        if (extra != null) {
            record.putAll(extra);
        }
        record.put("raw", raw == null ? "" : raw);
        Files.writeString(config.chatappDataFile(), GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private boolean containsMessage(String id) throws IOException {
        if (id == null || id.isBlank() || !Files.exists(config.chatappDataFile())) {
            return false;
        }
        for (String line : Files.readAllLines(config.chatappDataFile(), StandardCharsets.UTF_8)) {
            if (line.contains("\"id\":\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")) {
                return true;
            }
        }
        return false;
    }

    private UnifiedMessage findStored(String sourceId, String to) throws IOException {
        UnifiedMessage found = new UnifiedMessageStore(config).findMessage("chatapp:" + sourceId);
        if (found != null) {
            return found;
        }
        UnifiedMessage fallback = new UnifiedMessage();
        fallback.id = "chatapp:" + sourceId;
        fallback.sourceId = sourceId;
        fallback.channel = "chatapp";
        fallback.direction = "outbound";
        fallback.timestamp = Instant.now().toString();
        fallback.from = chatappFrom();
        fallback.to = to;
        fallback.contactPointId = ContactPointUtil.normalizePointId("chatapp:whatsapp:" + to);
        return fallback;
    }

    private String responseMessageId(SendChatappMessageResponse response) {
        String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
        return messageId == null || messageId.isBlank() ? "local-" + UUID.randomUUID() : messageId;
    }

    private String chatappFrom() {
        return requiredConfig("CHATAPP_FROM");
    }

    private String requiredConfig(String key) {
        return required(config.value(key, ""), key);
    }

    private static String requiredConfig(Config config, String key) {
        return required(config.value(key, ""), key);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String textContentJson(String text) {
        JsonObject content = new JsonObject();
        content.addProperty("text", text == null ? "" : text);
        return GSON.toJson(content);
    }

    private static String outboundTemplateRaw(SendChatappMessageResponse response, String templateCode,
                                              String templateName, String languageCode, String paramsJson) {
        JsonObject raw = new JsonObject();
        raw.addProperty("messageTypeName", "template");
        raw.addProperty("messageType", "TEMPLATE");
        raw.addProperty("templateCode", templateCode);
        raw.addProperty("templateName", templateName == null ? "" : templateName);
        raw.addProperty("languageCode", languageCode == null ? "" : languageCode);
        raw.addProperty("message", paramsJson == null ? "{}" : paramsJson);
        if (response.getBody() != null) {
            raw.add("response", GSON.toJsonTree(response.getBody()));
            raw.addProperty("messageId", response.getBody().getMessageId());
        }
        return GSON.toJson(raw);
    }

    private static String mediaContentJson(String mediaType, String link, String caption, String fileName) {
        JsonObject content = new JsonObject();
        content.addProperty("link", required(link, "link"));
        if (caption != null && !caption.isBlank()) {
            content.addProperty("caption", caption);
        }
        if ("document".equals(mediaType) && fileName != null && !fileName.isBlank()) {
            content.addProperty("fileName", fileName);
        }
        return GSON.toJson(content);
    }

    private static String outboundMediaRaw(SendChatappMessageResponse response, String mediaType, String content,
                                           String mediaUrl, String objectKey, String fileName,
                                           String mimeType, String caption) {
        JsonObject raw = new JsonObject();
        raw.addProperty("messageTypeName", mediaType);
        raw.addProperty("messageType", mediaType);
        raw.addProperty("message", content);
        raw.add("content", JsonParser.parseString(content));
        raw.addProperty("link", mediaUrl);
        raw.addProperty("objectKey", objectKey);
        raw.addProperty("fileName", fileName);
        raw.addProperty("mimeType", mimeType);
        raw.addProperty("caption", caption == null ? "" : caption);
        if (response.getBody() != null) {
            raw.add("response", GSON.toJsonTree(response.getBody()));
            raw.addProperty("messageId", response.getBody().getMessageId());
        }
        return GSON.toJson(raw);
    }

    private static String uploadObjectKey(String dir, String fileName) {
        String prefix = dir == null ? "" : dir.trim().replace('\\', '/');
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (!prefix.isBlank() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + UUID.randomUUID().toString().replace("-", "") + safeExtension(fileName);
    }

    private static String safeExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,12}") ? extension : "";
    }

    private static String ossObjectUrl(String endpoint, String bucketName, String objectKey) {
        String bucket = required(bucketName, "bucketName");
        String host = required(endpoint, "endpoint").trim()
                .replaceFirst("(?i)^https?://", "")
                .replaceAll("/+$", "");
        if (!host.startsWith(bucket + ".")) {
            host = bucket + "." + host;
        }
        return "https://" + host + "/" + objectKey;
    }

    private static void uploadToOss(GetChatappUploadAuthorizationResponseBody.Data auth, String objectKey,
                                    byte[] bytes, String mimeType) throws Exception {
        String bucketName = required(auth.getBucketName(), "bucketName");
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
        String resource = "/" + bucketName + "/" + objectKey;
        String securityToken = auth.getSecurityToken();
        String canonicalHeaders = securityToken == null || securityToken.isBlank()
                ? ""
                : "x-oss-security-token:" + securityToken + "\n";
        String stringToSign = "PUT\n\n" + mimeType + "\n" + date + "\n" + canonicalHeaders + resource;
        String authorization = "OSS " + required(auth.getAccessKeyId(), "accessKeyId")
                + ":" + hmacSha1Base64(required(auth.getAccessKeySecret(), "accessKeySecret"), stringToSign);

        URL url = new URL(ossObjectUrl(auth.getEndPoint(), bucketName, objectKey));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Date", date);
        connection.setRequestProperty("Content-Type", mimeType);
        connection.setRequestProperty("Authorization", authorization);
        if (securityToken != null && !securityToken.isBlank()) {
            connection.setRequestProperty("x-oss-security-token", securityToken);
        }
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OSS upload failed: HTTP " + status + " " + readResponseText(connection));
        }
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String readResponseText(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }
        try (InputStream input = stream) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeMediaType(String mediaType) {
        String value = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "image", "photo" -> "image";
            case "video" -> "video";
            case "file", "document" -> "document";
            default -> throw new IllegalArgumentException("Unsupported mediaType: " + mediaType);
        };
    }

    private static void validateMediaMime(String mediaType, String mimeType) {
        String value = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if ("image".equals(mediaType) && !value.startsWith("image/")) {
            throw new IllegalArgumentException("image messages require image/* files, got: " + mimeType);
        }
        if ("video".equals(mediaType) && !value.startsWith("video/")) {
            throw new IllegalArgumentException("video messages require video/* files, got: " + mimeType);
        }
    }

    private static String mimeTypeFromName(String fileName) {
        String guessed = java.net.URLConnection.guessContentTypeFromName(fileName);
        return guessed == null ? "" : guessed;
    }

    private static String mediaDisplayText(String mediaType, String caption, String fileName) {
        String label = switch (mediaType) {
            case "image" -> "image";
            case "video" -> "video";
            default -> "file";
        };
        String suffix = ContactPointUtil.firstNonBlank(caption, fileName, "");
        return suffix.isBlank() ? "[" + label + "]" : "[" + label + "] " + suffix;
    }

    private static String statusTargetId(String id) {
        return id != null && id.endsWith("-status") ? id.substring(0, id.length() - "-status".length()) : id;
    }

    private static void putIfNotBlank(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    public static class UploadedFile {
        public String fileName;
        public String contentType;
        public byte[] bytes;
    }

    private static class WebhookMessage {
        String id;
        String direction;
        String from;
        String to;
        String text;
        String status;

        static WebhookMessage parse(String raw) {
            WebhookMessage result = new WebhookMessage();
            try {
                JsonElement root = JsonParser.parseString(raw);
                result.id = first(root, "MessageId", "messageId", "message_id", "wamid", "id", "TaskId");
                result.from = first(root, "From", "from", "sender", "wa_id", "phoneNumber");
                result.to = first(root, "To", "to", "recipient", "businessPhoneNumber");
                result.text = ContactPointUtil.firstNonBlank(
                        JsonSupport.textField(first(root, "Message", "message")),
                        first(root, "Message", "message", "text", "content", "body", "ErrorDescription"));
                String status = first(root, "Status", "status");
                String noticeType = first(root, "NoticeType", "noticeType");
                if (!status.isBlank() && noticeType.isBlank()) {
                    result.direction = "status";
                    result.id = ContactPointUtil.firstNonBlank(result.id, "webhook-" + UUID.randomUUID()) + "-status";
                    result.status = status;
                    result.text = "Status: " + status + (result.text.isBlank() ? "" : " - " + result.text);
                    return result;
                }
                result.direction = "inbound";
                result.id = ContactPointUtil.firstNonBlank(result.id, "webhook-" + UUID.randomUUID());
                return result;
            } catch (RuntimeException ex) {
                result.id = "webhook-" + UUID.randomUUID();
                result.direction = "inbound";
                result.text = raw == null ? "" : raw;
                return result;
            }
        }

        private static String first(JsonElement root, String... keys) {
            for (String key : keys) {
                String value = find(root, key);
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String find(JsonElement element, String key) {
            if (element == null || element.isJsonNull()) {
                return "";
            }
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(key)) {
                        JsonElement value = entry.getValue();
                        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
                    }
                    String child = find(entry.getValue(), key);
                    if (!child.isBlank()) {
                        return child;
                    }
                }
            }
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    String value = find(child, key);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            return "";
        }
    }
}
