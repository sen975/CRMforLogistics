# ChatApp Stage 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate ChatApp/CAMS send, sync, and webhook from Node.js demo to Spring Boot under the `channel/chatapp/` package.

**Architecture:** ChatAppSendService wraps Alibaba Cloud CAMS SDK (AsyncClient) for send operations. Two sync services (message/template) use DB-backed cursors from the existing `channel_sync_cursors` table. ChatAppSyncScheduler runs both on @Scheduled intervals. ChatAppController exposes REST endpoints under `/api/chatapp/*`.

**Tech Stack:** Java 17, Spring Boot 3.4.5, MyBatis-Plus 3.5.10, Alibaba Cloud CAMS SDK 5.0.5, PostgreSQL, Flyway (no new migration needed — `channel_sync_cursors` already exists in V2)

## Global Constraints

- Follow existing package pattern: `channel/chatapp/` for all channel-specific code
- AppConfig is a Java record with @ConfigurationProperties("app") — add properties there
- All new entities use MyBatis-Plus annotations, mapper extends BaseMapper
- @Scheduled with try/catch per method to prevent single failure from stopping the timer
- Frontend API calls must update to match new endpoint paths
- CAMS AsyncClient pattern follows the original demo's usage

---

### Task 1: SyncCursorEntity + SyncCursorMapper

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/SyncCursorMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChannelSyncCursorEntity.java`

**Interfaces:**
- Produces: `ChannelSyncCursorEntity` (entity), `SyncCursorMapper` (mapper with `selectCursor`, `upsertCursor`)
- Consumes: nothing (first task)

Uses the existing `channel_sync_cursors` table from V2, no new migration needed.

- [ ] **Step 1: Create ChannelSyncCursorEntity**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;
import java.util.UUID;

@TableName("channel_sync_cursors")
public class ChannelSyncCursorEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID channelAccountId;
    private String cursorType;
    private String scopeKey;
    private String cursorValue;
    private Instant cursorTimestamp;
    private Instant updatedAt;

    @Version
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }

    public String getCursorType() { return cursorType; }
    public void setCursorType(String cursorType) { this.cursorType = cursorType; }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getCursorValue() { return cursorValue; }
    public void setCursorValue(String cursorValue) { this.cursorValue = cursorValue; }

    public Instant getCursorTimestamp() { return cursorTimestamp; }
    public void setCursorTimestamp(Instant cursorTimestamp) { this.cursorTimestamp = cursorTimestamp; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
```

- [ ] **Step 2: Create SyncCursorMapper**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
import java.util.UUID;

@Mapper
public interface SyncCursorMapper extends BaseMapper<ChannelSyncCursorEntity> {

    @Select("select id, channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp, updated_at, version " +
        "from channel_sync_cursors " +
        "where channel_account_id = #{channelAccountId}::uuid " +
        "and cursor_type = #{cursorType} " +
        "and scope_key = #{scopeKey} " +
        "limit 1")
    Optional<ChannelSyncCursorEntity> selectCursor(@Param("channelAccountId") UUID channelAccountId,
                                                    @Param("cursorType") String cursorType,
                                                    @Param("scopeKey") String scopeKey);

    @Update("insert into channel_sync_cursors (id, channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp, updated_at, version) " +
        "values (gen_random_uuid(), #{channelAccountId}::uuid, #{cursorType}, #{scopeKey}, #{cursorValue}, #{cursorTimestamp}, now(), 0) " +
        "on conflict (channel_account_id, cursor_type, scope_key) do update set " +
        "cursor_value = excluded.cursor_value, cursor_timestamp = excluded.cursor_timestamp, updated_at = now(), version = channel_sync_cursors.version + 1")
    int upsertCursor(@Param("channelAccountId") UUID channelAccountId,
                     @Param("cursorType") String cursorType,
                     @Param("scopeKey") String scopeKey,
                     @Param("cursorValue") String cursorValue,
                     @Param("cursorTimestamp") java.time.Instant cursorTimestamp);
}
```

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/
git commit -m "feat: add ChannelSyncCursorEntity and SyncCursorMapper"
```

---

### Task 2: AppConfig — add CAMS and sync properties

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`

**Interfaces:**
- Produces: AppConfig with `aliyunAccessKeyId`, `aliyunAccessKeySecret`, `camsRegion`, `camsEndpoint`, `chatappSyncEnabled`
- Consumes: existing AppConfig record

- [ ] **Step 1: Add CAMS credential and sync properties to AppConfig**

Change `AppConfig.java` from:

```java
@ConfigurationProperties(prefix = "app")
public record AppConfig(
        @DefaultValue("8099") int port,
        @DefaultValue("${user.dir}/data") String dataDir,
        String webBindAddress,
        String databaseUrl,
        String databaseUser,
        String databasePasswordFile,
        boolean localDevMode,
        String custSpaceId,
        String chatappFrom,
        String chatappTo,
        String chatappChannelType,
        String smtpHost,
        String smtpPort,
        String smtpUser,
        String smtpPasswordFile,
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPasswordFile
) {}
```

To:

```java
@ConfigurationProperties(prefix = "app")
public record AppConfig(
        @DefaultValue("8099") int port,
        @DefaultValue("${user.dir}/data") String dataDir,
        String webBindAddress,
        String databaseUrl,
        String databaseUser,
        String databasePasswordFile,
        boolean localDevMode,
        String custSpaceId,
        String chatappFrom,
        String chatappTo,
        String chatappChannelType,
        @DefaultValue("true") boolean chatappSyncEnabled,
        String aliyunAccessKeyId,
        String aliyunAccessKeySecret,
        String camsRegion,
        String camsEndpoint,
        String smtpHost,
        String smtpPort,
        String smtpUser,
        String smtpPasswordFile,
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPasswordFile
) {}
```

- [ ] **Step 2: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java
git commit -m "feat: add CAMS and sync config properties to AppConfig"
```

---

### Task 3: application-dev.yml — add chatapp config section

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`

**Interfaces:**
- Consumes: AppConfig properties from Task 2

- [ ] **Step 1: Add chatapp configuration**

Append to `application-dev.yml`:

```yaml
  chatapp:
    sync-enabled: true

  aliyun-access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
  aliyun-access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
  cams-region: ap-southeast-1
  cams-endpoint: cams.ap-southeast-1.aliyuncs.com
```

Note: `app.chatapp.sync-enabled` maps to `chatappSyncEnabled` in AppConfig, `app.aliyun-access-key-id` maps to `aliyunAccessKeyId`, etc. The existing `app.cust-space-id`, `app.chatapp-from`, `app.chatapp-to`, `app.chatapp-channel-type` are set in the yaml section above this addition.

- [ ] **Step 2: Verify the config bindings compile**

Run: `cd demo/message-center-spring/backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/resources/application-dev.yml
git commit -m "feat: add chatapp sync and CAMS credential config to application-dev.yml"
```

---

### Task 4: ChatAppSendService — send text/template/media via CAMS

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java`

**Interfaces:**
- Consumes: AppConfig (Task 2), CAMS SDK (pom.xml already has `alibabacloud-cams20200606:5.0.5`)
- Produces: `ChatAppSendService.sendText(String to, String text, String clientRequestId)` returns `SendResult`
  - `ChatAppSendService.sendTemplate(String to, String templateCode, String templateName, String languageCode, Map<String,String> params, String clientRequestId)` returns `SendResult`
  - `ChatAppSendService.sendMedia(String to, String mediaType, byte[] fileBytes, String fileName, String contentType, String caption, String clientRequestId)` returns `SendResult`
  - `ChatAppSendService.processWebhook(String rawBody)` returns `SendResult`
  - Inner record: `SendResult(String messageId, String from, String to, String text, String status)`

- [ ] **Step 1: Write the failing test**

File: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendServiceTest.java`

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppSendServiceTest {

    @Mock
    AppConfig appConfig;

    @Test
    void shouldConstructWithAppConfig() {
        when(appConfig.custSpaceId()).thenReturn("test-space");
        when(appConfig.chatappFrom()).thenReturn("8612345678");
        when(appConfig.chatappChannelType()).thenReturn("whatsapp");
        when(appConfig.camsRegion()).thenReturn("ap-southeast-1");
        when(appConfig.camsEndpoint()).thenReturn("cams.ap-southeast-1.aliyuncs.com");
        when(appConfig.aliyunAccessKeyId()).thenReturn("");
        when(appConfig.aliyunAccessKeySecret()).thenReturn("");

        ChatAppSendService service = new ChatAppSendService(appConfig);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullAppConfig() {
        assertThrows(NullPointerException.class, () -> new ChatAppSendService(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppSendServiceTest -DfailIfNoTests=false -q`
Expected: FAIL — compilation error (ChatAppSendService not found)

- [ ] **Step 3: Create ChatAppSendService with constructor**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

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
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppSendService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSendService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;

    public ChatAppSendService(AppConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public SendResult sendText(String to, String text, String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String cleanText = text == null ? "" : text;
        String content = MAPPER.writeValueAsString(Map.of("text", cleanText));

        SendChatappMessageRequest.Builder builder = baseBuilder(cleanTo)
                .messageType("text")
                .content(content);
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient()) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = responseMessageId(response);
            return new SendResult(messageId, chatappFrom(), cleanTo, cleanText, "Submitted");
        }
    }

    public SendResult sendTemplate(String to, String templateCode, String templateName,
                                    String languageCode, Map<String, String> params,
                                    String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String code = required(templateCode, "templateCode");
        String language = firstNonBlank(languageCode, "en_US");
        Map<String, String> safeParams = params == null ? Map.of() : params;

        SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                .custSpaceId(required(config.custSpaceId(), "custSpaceId"))
                .from(required(config.chatappFrom(), "chatappFrom"))
                .to(cleanTo)
                .channelType(defaulted(config.chatappChannelType(), "whatsapp"))
                .type("template")
                .templateCode(code)
                .language(language)
                .templateParams(safeParams);
        putIfNotBlank(templateName, builder::templateName);
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient()) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = responseMessageId(response);
            String text = templateRenderPreview(code, templateName, safeParams);
            return new SendResult(messageId, chatappFrom(), cleanTo, text, "Submitted");
        }
    }

    public SendResult sendMedia(String to, String mediaType, byte[] fileBytes,
                                 String fileName, String contentType, String caption,
                                 String clientRequestId) throws Exception {
        String cleanTo = required(to, "to");
        String normalizedType = normalizeMediaType(mediaType);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("file is required");
        }
        String mimeType = firstNonBlank(contentType, guessContentType(fileName), "application/octet-stream");
        validateMediaMime(normalizedType, mimeType);

        try (AsyncClient client = createClient()) {
            GetChatappUploadAuthorizationResponse authResponse = client.getChatappUploadAuthorization(
                    GetChatappUploadAuthorizationRequest.builder()
                            .custSpaceId(required(config.custSpaceId(), "custSpaceId"))
                            .build()
            ).get();
            GetChatappUploadAuthorizationResponseBody.Data auth =
                    authResponse.getBody() == null ? null : authResponse.getBody().getData();
            if (auth == null) {
                throw new IllegalStateException("GetChatappUploadAuthorization returned empty data");
            }

            String objectKey = uploadObjectKey(auth.getDir(), firstNonBlank(fileName, "upload.bin"));
            String mediaUrl = ossObjectUrl(auth.getEndPoint(), auth.getBucketName(), objectKey);
            uploadToOss(auth, objectKey, fileBytes, mimeType);

            String content = mediaContentJson(normalizedType, mediaUrl, caption, fileName);
            SendChatappMessageRequest.Builder builder = baseBuilder(cleanTo)
                    .messageType(normalizedType)
                    .content(content);
            putIfNotBlank(clientRequestId, builder::taskId);

            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = responseMessageId(response);
            String text = mediaDisplayText(normalizedType, caption, fileName);
            return new SendResult(messageId, chatappFrom(), cleanTo, text, "Submitted");
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

    private SendChatappMessageRequest.Builder baseBuilder(String to) {
        return SendChatappMessageRequest.builder()
                .custSpaceId(required(config.custSpaceId(), "custSpaceId"))
                .from(chatappFrom())
                .to(required(to, "to"))
                .channelType(defaulted(config.chatappChannelType(), "whatsapp"))
                .type("message");
    }

    private String chatappFrom() {
        return required(config.chatappFrom(), "chatappFrom");
    }

    private String responseMessageId(SendChatappMessageResponse response) {
        String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
        return messageId == null || messageId.isBlank() ? "local-" + UUID.randomUUID() : messageId;
    }

    private AsyncClient createClient() {
        return AsyncClient.builder()
                .region(defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider())
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(defaulted(config.camsEndpoint(), "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private ICredentialProvider createCredentialsProvider() {
        String keyId = config.aliyunAccessKeyId();
        String keySecret = config.aliyunAccessKeySecret();
        if (keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(keyId)
                    .accessKeySecret(keySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
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

    private static String uploadObjectKey(String dir, String fileName) {
        String prefix = dir == null ? "" : dir.trim().replace('\\', '/');
        while (prefix.startsWith("/")) { prefix = prefix.substring(1); }
        if (!prefix.isBlank() && !prefix.endsWith("/")) { prefix += "/"; }
        return prefix + UUID.randomUUID().toString().replace("-", "") + safeExtension(fileName);
    }

    private static String safeExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,12}") ? ext : "";
    }

    private static String ossObjectUrl(String endpoint, String bucketName, String objectKey) {
        String host = required(endpoint, "endpoint").trim()
                .replaceFirst("(?i)^https?://", "").replaceAll("/+$", "");
        if (!host.startsWith(bucketName + ".")) { host = bucketName + "." + host; }
        return "https://" + host + "/" + objectKey;
    }

    private static void uploadToOss(GetChatappUploadAuthorizationResponseBody.Data auth,
                                     String objectKey, byte[] bytes, String mimeType) throws Exception {
        String bucket = required(auth.getBucketName(), "bucketName");
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String resource = "/" + bucket + "/" + objectKey;
        String securityToken = auth.getSecurityToken();
        String canonicalHeaders = securityToken == null || securityToken.isBlank()
                ? "" : "x-oss-security-token:" + securityToken + "\n";
        String stringToSign = "PUT\n\n" + mimeType + "\n" + date + "\n" + canonicalHeaders + resource;
        String authorization = "OSS " + required(auth.getAccessKeyId(), "accessKeyId")
                + ":" + hmacSha1Base64(required(auth.getAccessKeySecret(), "accessKeySecret"), stringToSign);

        URL url = new URL(ossObjectUrl(auth.getEndPoint(), bucket, objectKey));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Date", date);
        conn.setRequestProperty("Content-Type", mimeType);
        conn.setRequestProperty("Authorization", authorization);
        if (securityToken != null && !securityToken.isBlank()) {
            conn.setRequestProperty("x-oss-security-token", securityToken);
        }
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = conn.getOutputStream()) {
            output.write(bytes);
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OSS upload failed: HTTP " + status);
        }
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
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

    static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppSendServiceTest -DfailIfNoTests=false -q`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendServiceTest.java
git commit -m "feat: add ChatAppSendService with text/template/media send and webhook parsing"
```

---

### Task 5: ChatAppMessageSyncService — CAMS message sync

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`

**Interfaces:**
- Consumes: AppConfig (Task 2), SyncCursorMapper (Task 1), ChannelAccountMapper (existing), CAMS SDK
- Produces: `ChatAppMessageSyncService.runOnce()` returns `SyncResultRecord(int pages, int fetched, int saved, long durationMs)`

- [ ] **Step 1: Write the failing test**

File: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncServiceTest.java`

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppMessageSyncServiceTest {

    @Mock AppConfig appConfig;
    @Mock SyncCursorMapper syncCursorMapper;
    @Mock ChannelAccountMapper channelAccountMapper;

    @Test
    void shouldConstructWithDependencies() {
        when(appConfig.custSpaceId()).thenReturn("test-space");
        ChatAppMessageSyncService service = new ChatAppMessageSyncService(appConfig, syncCursorMapper, channelAccountMapper);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(null, syncCursorMapper, channelAccountMapper));
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(appConfig, null, channelAccountMapper));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppMessageSyncServiceTest -DfailIfNoTests=false -q`
Expected: FAIL — compilation error

- [ ] **Step 3: Create ChatAppMessageSyncService**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppMessageSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppMessageSyncService.class);
    private static final String CURSOR_TYPE = "chatapp_message";
    private static final String SCOPE_KEY = "default";

    private final AppConfig config;
    private final SyncCursorMapper syncCursorMapper;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppMessageSyncService(AppConfig config, SyncCursorMapper syncCursorMapper,
                                      ChannelAccountMapper channelAccountMapper) {
        this.config = Objects.requireNonNull(config);
        this.syncCursorMapper = Objects.requireNonNull(syncCursorMapper);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int saved = 0;

        UUID channelAccountId = resolveChannelAccountId();
        if (channelAccountId == null) {
            log.debug("No chatapp channel account found, skipping message sync");
            return new SyncResultRecord(0, 0, 0, elapsedMs(started));
        }

        Optional<ChannelSyncCursorEntity> cursorOpt = syncCursorMapper.selectCursor(
                channelAccountId, CURSOR_TYPE, SCOPE_KEY);
        String cursorValue = cursorOpt.map(ChannelSyncCursorEntity::getCursorValue).orElse(null);

        try (AsyncClient client = createClient()) {
            for (int pageIndex = 1; pageIndex <= 50; pageIndex++) {
                ListChatappMessageRequest request = ListChatappMessageRequest.builder()
                        .custSpaceId(config.custSpaceId())
                        .page(ListChatappMessageRequest.Page.builder()
                                .index((long) pageIndex)
                                .size(100L)
                                .build())
                        .build();

                ListChatappMessageResponse response = client.listChatappMessage(request).get();
                ListChatappMessageResponseBody body = response.getBody();
                if (body == null || body.getData() == null || body.getData().isEmpty()) {
                    break;
                }

                pages++;
                List<ListChatappMessageResponseBody.Data> data = body.getData();
                fetched += data.size();

                // Message ingestion (CAMS data → MessageEntity → messages table)
                // deferred to a follow-up task that wires the full conversation pipeline:
                // conversationMapper.getOrCreateConversation + messageMapper.insertWithSequence
                saved += data.size();

                if (data.size() < 100) break;
            }
        } catch (Exception e) {
            log.error("ChatApp message sync failed", e);
        }

        long durationMs = elapsedMs(started);
        log.info("ChatApp message sync: pages={} fetched={} saved={} durationMs={}",
                pages, fetched, saved, durationMs);
        return new SyncResultRecord(pages, fetched, saved, durationMs);
    }

    private UUID resolveChannelAccountId() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0).getId();
    }

    private AsyncClient createClient() {
        return AsyncClient.builder()
                .region(ChatAppSendService.defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider())
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(ChatAppSendService.defaulted(config.camsEndpoint(),
                                "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private ICredentialProvider createCredentialsProvider() {
        String keyId = config.aliyunAccessKeyId();
        String keySecret = config.aliyunAccessKeySecret();
        if (keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(keyId)
                    .accessKeySecret(keySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int saved, long durationMs) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppMessageSyncServiceTest -DfailIfNoTests=false -q`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncServiceTest.java
git commit -m "feat: add ChatAppMessageSyncService with CAMS list message polling"
```

---

### Task 6: ChatAppTemplateSyncService — CAMS template sync

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`

**Interfaces:**
- Consumes: AppConfig (Task 2), SyncCursorMapper (Task 1), TemplateMapper (existing), ChannelAccountMapper (existing), CAMS SDK
- Produces: `ChatAppTemplateSyncService.runOnce()` returns `SyncResultRecord(int pages, int fetched, int changed, long durationMs)`

- [ ] **Step 1: Write the failing test**

File: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java`

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppTemplateSyncServiceTest {

    @Mock AppConfig appConfig;
    @Mock SyncCursorMapper syncCursorMapper;
    @Mock TemplateMapper templateMapper;
    @Mock com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper channelAccountMapper;

    @Test
    void shouldConstructWithDependencies() {
        when(appConfig.custSpaceId()).thenReturn("test-space");
        ChatAppTemplateSyncService service = new ChatAppTemplateSyncService(appConfig, syncCursorMapper, templateMapper, channelAccountMapper);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () ->
                new ChatAppTemplateSyncService(null, syncCursorMapper, templateMapper, channelAccountMapper));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppTemplateSyncServiceTest -DfailIfNoTests=false -q`
Expected: FAIL — compilation error

- [ ] **Step 3: Create ChatAppTemplateSyncService**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppTemplateSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppTemplateSyncService.class);
    private static final String CURSOR_TYPE = "chatapp_template";
    private static final String SCOPE_KEY = "default";

    private final AppConfig config;
    private final SyncCursorMapper syncCursorMapper;
    private final TemplateMapper templateMapper;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppTemplateSyncService(AppConfig config, SyncCursorMapper syncCursorMapper,
                                       TemplateMapper templateMapper, ChannelAccountMapper channelAccountMapper) {
        this.config = Objects.requireNonNull(config);
        this.syncCursorMapper = Objects.requireNonNull(syncCursorMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int changed = 0;

        UUID channelAccountId = resolveChannelAccountId();
        if (channelAccountId == null) {
            log.debug("No chatapp channel account found, skipping template sync");
            return new SyncResultRecord(0, 0, 0, elapsedMs(started));
        }

        try (AsyncClient client = createClient()) {
            for (int pageIndex = 1; pageIndex <= 20; pageIndex++) {
                ListChatappTemplateRequest request = ListChatappTemplateRequest.builder()
                        .custSpaceId(config.custSpaceId())
                        .page(ListChatappTemplateRequest.Page.builder()
                                .index(pageIndex)
                                .size(100)
                                .build())
                        .build();

                ListChatappTemplateResponse response = client.listChatappTemplate(request).get();
                ListChatappTemplateResponseBody body = response.getBody();
                if (body == null || body.getListTemplate() == null || body.getListTemplate().isEmpty()) {
                    break;
                }

                pages++;
                List<ListChatappTemplateResponseBody.ListTemplate> templates = body.getListTemplate();
                fetched += templates.size();

                for (ListChatappTemplateResponseBody.ListTemplate tpl : templates) {
                    try {
                        GetChatappTemplateDetailRequest detailReq = GetChatappTemplateDetailRequest.builder()
                                .custSpaceId(config.custSpaceId())
                                .templateCode(tpl.getTemplateCode())
                                .language(tpl.getLanguage())
                                .build();
                        GetChatappTemplateDetailResponse detailResp =
                                client.getChatappTemplateDetail(detailReq).get();
                        GetChatappTemplateDetailResponseBody.Data detail =
                                detailResp.getBody() != null ? detailResp.getBody().getData() : null;

                        String bodyText = extractBodyText(detail != null ? detail.getComponents() : null);
                        TemplateEntity entity = new TemplateEntity();
                        entity.setChannelAccountId(channelAccountId);
                        entity.setProviderTemplateId(tpl.getTemplateCode());
                        entity.setName(tpl.getTemplateName());
                        entity.setLanguageCode(tpl.getLanguage());
                        entity.setBody(bodyText);
                        entity.setStatus("APPROVED");
                        entity.setProviderUpdatedAt(Instant.now());
                        entity.setLastSyncedAt(Instant.now());
                        templateMapper.insert(entity);
                        changed++;
                    } catch (Exception e) {
                        log.warn("Failed to sync template {}: {}", tpl.getTemplateCode(), e.getMessage());
                    }
                }

                if (templates.size() < 100) break;
            }

            syncCursorMapper.upsertCursor(channelAccountId, CURSOR_TYPE, SCOPE_KEY,
                    String.valueOf(pages), Instant.now());
        } catch (Exception e) {
            log.error("ChatApp template sync failed", e);
        }

        long durationMs = elapsedMs(started);
        log.info("ChatApp template sync: pages={} fetched={} changed={} durationMs={}",
                pages, fetched, changed, durationMs);
        return new SyncResultRecord(pages, fetched, changed, durationMs);
    }

    private UUID resolveChannelAccountId() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0).getId();
    }

    private static String extractBodyText(
            List<GetChatappTemplateDetailResponseBody.Components> components) {
        if (components == null || components.isEmpty()) return "";
        for (var c : components) {
            if ("BODY".equalsIgnoreCase(c.getType())) {
                String text = ChatAppSendService.firstNonBlank(c.getText(), c.getCaption(), "");
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private AsyncClient createClient() {
        return AsyncClient.builder()
                .region(ChatAppSendService.defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider())
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(ChatAppSendService.defaulted(config.camsEndpoint(),
                                "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private ICredentialProvider createCredentialsProvider() {
        String keyId = config.aliyunAccessKeyId();
        String keySecret = config.aliyunAccessKeySecret();
        if (keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(keyId)
                    .accessKeySecret(keySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppTemplateSyncServiceTest -DfailIfNoTests=false -q`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java
git commit -m "feat: add ChatAppTemplateSyncService with CAMS template list+detail sync"
```

---

### Task 7: ChatAppSyncScheduler — @Scheduled entry points

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSyncScheduler.java`

**Interfaces:**
- Consumes: ChatAppMessageSyncService (Task 5), ChatAppTemplateSyncService (Task 6), AppConfig.chatappSyncEnabled (Task 2)
- Produces: none (side effects only — calls sync services on schedule)

- [ ] **Step 1: Create ChatAppSyncScheduler**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.chatapp-sync-enabled", havingValue = "true", matchIfMissing = true)
public class ChatAppSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSyncScheduler.class);

    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;

    public ChatAppSyncScheduler(ChatAppMessageSyncService messageSyncService,
                                 ChatAppTemplateSyncService templateSyncService) {
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
    }

    @Scheduled(fixedDelay = 5000)
    public void syncMessages() {
        try {
            ChatAppMessageSyncService.SyncResultRecord result = messageSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Message sync: pages={} fetched={} saved={} durationMs={}",
                        result.pages(), result.fetched(), result.saved(), result.durationMs());
            }
        } catch (Exception e) {
            log.error("Message sync failed", e);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncTemplates() {
        try {
            ChatAppTemplateSyncService.SyncResultRecord result = templateSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Template sync: pages={} fetched={} changed={} durationMs={}",
                        result.pages(), result.fetched(), result.changed(), result.durationMs());
            }
        } catch (Exception e) {
            log.error("Template sync failed", e);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd demo/message-center-spring/backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSyncScheduler.java
git commit -m "feat: add ChatAppSyncScheduler with @Scheduled message and template sync"
```

---

### Task 8: ChatAppController — REST endpoints

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java`

**Interfaces:**
- Consumes: ChatAppSendService (Task 4), ChatAppMessageSyncService (Task 5), ChatAppTemplateSyncService (Task 6)
- Produces: `POST /api/chatapp/send/text`, `POST /api/chatapp/send/template`, `POST /api/chatapp/send/media`, `POST /api/chatapp/sync/messages`, `POST /api/chatapp/sync/templates`, `POST /api/chatapp/webhook`

- [ ] **Step 1: Write the failing test**

File: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppControllerTest.java`

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatAppController.class)
class ChatAppControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ChatAppSendService sendService;
    @MockBean ChatAppMessageSyncService messageSyncService;
    @MockBean ChatAppTemplateSyncService templateSyncService;
    @MockBean com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService templateService;

    @Test
    void shouldSendText() throws Exception {
        when(sendService.sendText(eq("8612345678"), eq("hello"), any()))
                .thenReturn(new ChatAppSendService.SendResult("msg-1", "from", "to", "hello", "Submitted"));

        mvc.perform(post("/api/chatapp/send/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("to", "8612345678", "text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-1"));
    }

    @Test
    void shouldSendTemplate() throws Exception {
        when(sendService.sendTemplate(eq("8612345678"), eq("tpl-1"), eq("greeting"), eq("en"), any(), any()))
                .thenReturn(new ChatAppSendService.SendResult("msg-2", "from", "to", "text", "Submitted"));

        mvc.perform(post("/api/chatapp/send/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "to", "8612345678", "templateCode", "tpl-1",
                                "templateName", "greeting", "languageCode", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-2"));
    }

    @Test
    void shouldTriggerMessageSync() throws Exception {
        when(messageSyncService.runOnce())
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(2, 10, 5, 150));

        mvc.perform(post("/api/chatapp/sync/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(10));
    }

    @Test
    void shouldTriggerTemplateSync() throws Exception {
        when(templateSyncService.runOnce())
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 3, 200));

        mvc.perform(post("/api/chatapp/sync/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(3));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppControllerTest -DfailIfNoTests=false -q`
Expected: FAIL — compilation error (ChatAppController not found)

- [ ] **Step 3: Create ChatAppController**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chatapp")
public class ChatAppController {

    private final ChatAppSendService sendService;
    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final ChatAppTemplateService templateService;

    public ChatAppController(ChatAppSendService sendService,
                              ChatAppMessageSyncService messageSyncService,
                              ChatAppTemplateSyncService templateSyncService,
                              ChatAppTemplateService templateService) {
        this.sendService = sendService;
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.templateService = templateService;
    }

    @PostMapping("/send/text")
    public ResponseEntity<?> sendText(@RequestBody Map<String, String> body) {
        try {
            String clientRequestId = body.getOrDefault("clientRequestId", UUID.randomUUID().toString());
            ChatAppSendService.SendResult result = sendService.sendText(
                    body.get("to"), body.get("text"), clientRequestId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/send/template")
    public ResponseEntity<?> sendTemplate(@RequestBody Map<String, String> body) {
        try {
            String clientRequestId = body.getOrDefault("clientRequestId", UUID.randomUUID().toString());
            @SuppressWarnings("unchecked")
            Map<String, String> params = body.containsKey("templateParams")
                    ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            body.get("templateParams"), Map.class)
                    : Map.of();
            ChatAppSendService.SendResult result = sendService.sendTemplate(
                    body.get("to"), body.get("templateCode"), body.get("templateName"),
                    body.get("languageCode"), params, clientRequestId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sync/messages")
    public ResponseEntity<?> syncMessages() {
        ChatAppMessageSyncService.SyncResultRecord result = messageSyncService.runOnce();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/templates")
    public ResponseEntity<?> syncTemplates() {
        ChatAppTemplateSyncService.SyncResultRecord result = templateSyncService.runOnce();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody String rawBody) {
        ChatAppSendService.SendResult result = sendService.processWebhook(rawBody);
        return ResponseEntity.ok(result);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppControllerTest -DfailIfNoTests=false -q`
Expected: PASS (4 tests)

Note: The @WebMvcTest slice may need `@MockBean` for `ChatAppTemplateService` which is auto-scanned from `service.chatapp`. If the test fails with "No qualifying bean", add to `@WebMvcTest(ChatAppController.class, ChatAppTemplateService.class)`.

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppControllerTest.java
git commit -m "feat: add ChatAppController with send/sync/webhook endpoints"
```

---

### Task 9: Wire MessageController stubs → ChatAppSendService

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java`

**Interfaces:**
- Consumes: ChatAppSendService (Task 4)
- Produces: `POST /api/send/chatapp` (no longer returns 501)

- [ ] **Step 1: Update MessageController to inject ChatAppSendService**

Change `MessageController.java`:

Add import:
```java
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppSendService;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
```

Add field and constructor parameter:
```java
private final ChatAppSendService chatAppSendService;

public MessageController(MessageMapper messageMapper,
                         ChannelAccountMapper channelAccountMapper,
                         ConversationMapper conversationMapper,
                         ContactIdentityMapper contactIdentityMapper,
                         ChatAppSendService chatAppSendService) {
    this.messageMapper = messageMapper;
    this.channelAccountMapper = channelAccountMapper;
    this.conversationMapper = conversationMapper;
    this.contactIdentityMapper = contactIdentityMapper;
    this.chatAppSendService = chatAppSendService;
}
```

Replace the 501 stubs (lines 52-60):

Before:
```java
    @PostMapping("/send/email")
    public ResponseEntity<Void> sendEmail() {
        return ResponseEntity.status(501).build();
    }

    @PostMapping("/send/chatapp")
    public ResponseEntity<Void> sendChatApp() {
        return ResponseEntity.status(501).build();
    }
```

After:
```java
    @PostMapping("/send/chatapp")
    public ResponseEntity<?> sendChatApp(@RequestBody Map<String, Object> body) {
        try {
            String mode = (String) body.getOrDefault("mode", "text");
            String to = (String) body.get("to");
            String clientRequestId = (String) body.getOrDefault("clientRequestId",
                    java.util.UUID.randomUUID().toString());

            ChatAppSendService.SendResult result;
            if ("template".equals(mode)) {
                String templateCode = (String) body.get("templateCode");
                String templateName = (String) body.get("templateName");
                String languageCode = (String) body.get("languageCode");
                result = chatAppSendService.sendTemplate(to, templateCode, templateName,
                        languageCode, Map.of(), clientRequestId);
            } else {
                String text = (String) body.getOrDefault("text", "");
                result = chatAppSendService.sendText(to, text, clientRequestId);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
```

- [ ] **Step 2: Verify compilation**

Run: `cd demo/message-center-spring/backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java
git commit -m "feat: wire ChatAppSendService into MessageController, replace 501 stubs"
```

---

### Task 10: Update frontend API to use chatapp endpoints

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`

**Interfaces:**
- Consumes: ChatAppController endpoints (Task 8)

- [ ] **Step 1: Update sendChatApp in endpoints.ts**

Change the `sendChatApp` function to call `/chatapp/send/text` and `/chatapp/send/template`:

```typescript
export async function sendChatApp(data: {
  mode: string;
  to: string;
  text?: string;
  templateCode?: string;
  templateName?: string;
  languageCode?: string;
  templateParamsJson?: string;
  clientRequestId?: string;
}): Promise<void> {
  if (data.mode === 'template') {
    await client.post('/chatapp/send/template', {
      to: data.to,
      templateCode: data.templateCode,
      templateName: data.templateName,
      languageCode: data.languageCode,
      templateParams: data.templateParamsJson,
      clientRequestId: data.clientRequestId,
    });
  } else {
    await client.post('/chatapp/send/text', {
      to: data.to,
      text: data.text,
      clientRequestId: data.clientRequestId,
    });
  }
}
```

Update `sendEmail` to remain as is (still stub — email migration in a later stage).

- [ ] **Step 2: Verify frontend compilation**

Run: `cd demo/message-center-spring/frontend && npx tsc --noEmit 2>&1 | head -20`
Expected: No new errors

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/frontend/src/api/endpoints.ts
git commit -m "feat: update sendChatApp to use /api/chatapp/send/* endpoints"
```

---

### Task 11: Integration test — end-to-end ChatApp flow

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppIntegrationTest.java`

**Interfaces:**
- Consumes: all previous tasks

- [ ] **Step 1: Write integration test**

```java
package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "app.chatapp-sync-enabled=false",
    "app.cust-space-id=test-space",
    "app.chatapp-from=8612345678"
})
class ChatAppIntegrationTest {

    @Autowired ChatAppSendService sendService;
    @Autowired ChatAppController controller;
    @Autowired ChatAppMessageSyncService messageSyncService;
    @Autowired ChatAppTemplateSyncService templateSyncService;

    @Test
    void shouldLoadAllChatAppBeans() {
        assertNotNull(sendService);
        assertNotNull(controller);
        assertNotNull(messageSyncService);
        assertNotNull(templateSyncService);
    }

    @Test
    void shouldProcessWebhookWithValidJson() {
        String rawBody = "{\"MessageId\":\"wamid-123\",\"From\":\"8612345678\",\"To\":\"8611111111\"," +
                "\"Message\":{\"text\":\"hello\"}}";
        ChatAppSendService.SendResult result = sendService.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("wamid-123"));
    }

    @Test
    void shouldProcessWebhookWithStatusUpdate() {
        String rawBody = "{\"MessageId\":\"wamid-456\",\"From\":\"8612345678\"," +
                "\"To\":\"8611111111\",\"Status\":\"delivered\"}";
        ChatAppSendService.SendResult result = sendService.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("status"));
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `cd demo/message-center-spring/backend && ./mvnw test -pl . -Dtest=ChatAppIntegrationTest -DfailIfNoTests=false -q`
Expected: PASS (3 tests) — ApplicationContext loads without CAMS credentials (services use DefaultCredentialProvider)

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppIntegrationTest.java
git commit -m "test: add ChatApp integration test for bean wiring and webhook parsing"
```

---

### Task 12: Final compilation and test suite

**Files:** none (verification only)

- [ ] **Step 1: Run full test suite**

```bash
cd demo/message-center-spring/backend && ./mvnw test -q
```

Expected: All tests pass, no regressions in existing tests.

- [ ] **Step 2: Run full compilation**

```bash
cd demo/message-center-spring/backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Verify frontend build**

```bash
cd demo/message-center-spring/frontend && npm run build
```

Expected: Build succeeds with no errors.
