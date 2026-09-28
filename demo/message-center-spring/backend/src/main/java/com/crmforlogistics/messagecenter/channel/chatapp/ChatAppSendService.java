package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.config.AppConfig;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;

@Service
public class ChatAppSendService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSendService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL_TYPE = "whatsapp";
    private static final Duration DEFAULT_API_TIMEOUT = Duration.ofSeconds(30);

    private final ChatAppOssMediaUploader ossMediaUploader;
    private final Supplier<AsyncClient> clientFactory;
    private final Duration apiTimeout;

    public ChatAppSendService(ChatAppOssMediaUploader ossMediaUploader) {
        this(ossMediaUploader, null, DEFAULT_API_TIMEOUT);
    }

    @Autowired
    public ChatAppSendService(ChatAppOssMediaUploader ossMediaUploader, AppConfig config) {
        this(ossMediaUploader, null, Duration.ofSeconds(config.chatappApiTimeoutSeconds()));
    }

    ChatAppSendService(ChatAppOssMediaUploader ossMediaUploader, Supplier<AsyncClient> clientFactory) {
        this(ossMediaUploader, clientFactory, DEFAULT_API_TIMEOUT);
    }

    ChatAppSendService(ChatAppOssMediaUploader ossMediaUploader, Supplier<AsyncClient> clientFactory,
                       Duration apiTimeout) {
        this.ossMediaUploader = Objects.requireNonNull(ossMediaUploader);
        this.clientFactory = clientFactory;
        this.apiTimeout = Objects.requireNonNull(apiTimeout);
        if (apiTimeout.isZero() || apiTimeout.isNegative()) {
            throw new IllegalArgumentException("CHATAPP_API_TIMEOUT_INVALID");
        }
    }

    public SendResult sendText(ChatAppAccountCredentials credentials, String from, String to,
                               String text, String clientRequestId) throws Exception {
        String cleanFrom = required(from, "from");
        String cleanTo = required(to, "to");
        String cleanText = text == null ? "" : text;
        String content = MAPPER.writeValueAsString(Map.of("text", cleanText));

        SendChatappMessageRequest.Builder builder = baseBuilder(credentials, cleanFrom, cleanTo)
                .messageType("text")
                .content(content);
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient(credentials)) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build())
                    .get(apiTimeout.toMillis(), TimeUnit.MILLISECONDS);
            String messageId = responseMessageId(response);
            return new SendResult(messageId, cleanFrom, cleanTo, cleanText, "Submitted");
        }
    }

    public SendResult sendTemplate(ChatAppAccountCredentials credentials, String from, String to,
                                   String templateCode, String templateName, String languageCode,
                                   Map<String, String> params, String clientRequestId) throws Exception {
        String cleanFrom = required(from, "from");
        String cleanTo = required(to, "to");
        String code = required(templateCode, "templateCode");
        String language = firstNonBlank(languageCode, "en_US");
        Map<String, String> safeParams = params == null ? Map.of() : params;

        SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                .custSpaceId(required(credentials.custSpaceId(), "custSpaceId"))
                .from(cleanFrom)
                .to(cleanTo)
                .channelType(CHANNEL_TYPE)
                .type("template")
                .templateCode(code)
                .language(language)
                .templateParams(safeParams);
        putIfNotBlank(templateName, builder::templateName);
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient(credentials)) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build())
                    .get(apiTimeout.toMillis(), TimeUnit.MILLISECONDS);
            String messageId = responseMessageId(response);
            String text = templateRenderPreview(code, templateName, safeParams);
            return new SendResult(messageId, cleanFrom, cleanTo, text, "Submitted");
        }
    }

    public SendResult sendMedia(ChatAppAccountCredentials credentials, String from, String to,
                                String mediaType, byte[] fileBytes, String fileName,
                                String contentType, String caption, String clientRequestId) throws Exception {
        String cleanFrom = required(from, "from");
        String cleanTo = required(to, "to");
        String normalizedType = normalizeMediaType(mediaType);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("file is required");
        }
        String mimeType = firstNonBlank(contentType, guessContentType(fileName), "application/octet-stream");
        validateMediaMime(normalizedType, mimeType);

        try (AsyncClient client = createClient(credentials)) {
            GetChatappUploadAuthorizationResponse authResponse = client.getChatappUploadAuthorization(
                    GetChatappUploadAuthorizationRequest.builder()
                            .custSpaceId(required(credentials.custSpaceId(), "custSpaceId"))
                            .build()
            ).get(apiTimeout.toMillis(), TimeUnit.MILLISECONDS);
            GetChatappUploadAuthorizationResponseBody.Data auth =
                    authResponse.getBody() == null ? null : authResponse.getBody().getData();
            if (auth == null) {
                throw new IllegalStateException("GetChatappUploadAuthorization returned empty data");
            }

            ChatAppOssMediaUploader.UploadedObject uploaded = ossMediaUploader.upload(
                    auth, fileBytes, firstNonBlank(fileName, "upload.bin"), mimeType);
            String mediaUrl = uploaded.url();

            String content = mediaContentJson(normalizedType, mediaUrl, caption, fileName);
            SendChatappMessageRequest.Builder builder = baseBuilder(credentials, cleanFrom, cleanTo)
                    .messageType(normalizedType)
                    .content(content);
            putIfNotBlank(clientRequestId, builder::taskId);

            SendChatappMessageResponse response = client.sendChatappMessage(builder.build())
                    .get(apiTimeout.toMillis(), TimeUnit.MILLISECONDS);
            String messageId = responseMessageId(response);
            String text = mediaDisplayText(normalizedType, caption, fileName);
            return new SendResult(messageId, cleanFrom, cleanTo, text, "Submitted");
        }
    }

    public SendResult processWebhook(String rawBody) {
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            String messageId = firstJsonField(root, "MessageId", "messageId", "message_id", "wamid", "id", "TaskId");
            String from = firstJsonField(root, "From", "from", "sender", "wa_id", "phoneNumber");
            String to = firstJsonField(root, "To", "to", "recipient", "businessPhoneNumber");
            String status = firstJsonField(root, "Status", "status");
            String noticeType = firstJsonField(root, "NoticeType", "noticeType");
            String text;

            if (!status.isBlank() && noticeType.isBlank()) {
                String id = messageId.isBlank() ? "webhook-" + UUID.randomUUID() : messageId;
                text = "Status: " + status;
                return new SendResult(id + "-status", from, to, text, status);
            }
            text = firstJsonField(root, "Message", "message", "text", "content", "body", "ErrorDescription");
            String id = messageId.isBlank() ? "webhook-" + UUID.randomUUID() : messageId;
            return new SendResult(id, from, to, text, "Received");
        } catch (Exception e) {
            log.warn("Failed to parse webhook body", e);
            String id = "webhook-" + UUID.randomUUID();
            return new SendResult(id, "", "", rawBody == null ? "" : rawBody, "ParseError");
        }
    }

    // --- private helpers ---

    private SendChatappMessageRequest.Builder baseBuilder(ChatAppAccountCredentials credentials,
                                                          String from, String to) {
        return SendChatappMessageRequest.builder()
                .custSpaceId(required(credentials.custSpaceId(), "custSpaceId"))
                .from(required(from, "from"))
                .to(required(to, "to"))
                .channelType(CHANNEL_TYPE)
                .type("message");
    }

    private String responseMessageId(SendChatappMessageResponse response) {
        String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalStateException("CAMS_MESSAGE_ID_MISSING");
        }
        return messageId;
    }

    private AsyncClient createClient(ChatAppAccountCredentials credentials) {
        if (clientFactory != null) {
            return clientFactory.get();
        }
        return AsyncClient.builder()
                .region(credentials.region())
                .credentialsProvider(createCredentialsProvider(credentials))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(credentials.endpoint())
                        .setConnectTimeout(apiTimeout)
                        .setResponseTimeout(apiTimeout))
                .build();
    }

    private ICredentialProvider createCredentialsProvider(ChatAppAccountCredentials credentials) {
        return StaticCredentialProvider.create(Credential.builder()
                .accessKeyId(credentials.accessKeyId())
                .accessKeySecret(credentials.accessKeySecret())
                .build());
    }

    private String mediaContentJson(String mediaType, String link, String caption, String fileName) {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("link", required(link, "link"));
        if (caption != null && !caption.isBlank()) {
            content.put("caption", caption);
        }
        if ("document".equals(mediaType) && fileName != null && !fileName.isBlank()) {
            content.put("fileName", fileName);
        }
        try { return MAPPER.writeValueAsString(content); } catch (Exception e) { return "{}"; }
    }

    private static String normalizeMediaType(String mediaType) {
        String v = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "image", "photo" -> "image";
            case "video" -> "video";
            case "file", "document" -> "document";
            default -> throw new IllegalArgumentException("Unsupported mediaType: " + mediaType);
        };
    }

    private static void validateMediaMime(String mediaType, String mimeType) {
        String v = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if ("image".equals(mediaType) && !v.startsWith("image/")) {
            throw new IllegalArgumentException("image messages require image/* files, got: " + mimeType);
        }
        if ("video".equals(mediaType) && !v.startsWith("video/")) {
            throw new IllegalArgumentException("video messages require video/* files, got: " + mimeType);
        }
    }

    private static String guessContentType(String fileName) {
        String g = java.net.URLConnection.guessContentTypeFromName(fileName);
        return g == null ? "" : g;
    }

    private static String mediaDisplayText(String mediaType, String caption, String fileName) {
        String label = switch (mediaType) {
            case "image" -> "image";
            case "video" -> "video";
            default -> "file";
        };
        String suffix = firstNonBlank(caption, fileName, "");
        return suffix.isBlank() ? "[" + label + "]" : "[" + label + "] " + suffix;
    }

    private static String templateRenderPreview(String code, String name, Map<String, String> params) {
        if (params.isEmpty()) return "[" + (name != null ? name : code) + "]";
        return "[" + (name != null ? name : code) + "] " + String.join(", ",
                params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static String firstJsonField(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = findJsonField(root, key);
            if (node != null && !node.isNull()) {
                return node.isTextual() ? node.asText() : node.toString();
            }
        }
        return "";
    }

    private static JsonNode findJsonField(JsonNode node, String key) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            for (var it = node.fields(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
                JsonNode child = findJsonField(entry.getValue(), key);
                if (child != null && !child.isNull()) return child;
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode v = findJsonField(child, key);
                if (v != null && !v.isNull()) return v;
            }
        }
        return null;
    }

    // --- static util ---

    static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    static void putIfNotBlank(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) setter.accept(value);
    }

public record SendResult(String messageId, String from, String to, String text, String status) {}
}
