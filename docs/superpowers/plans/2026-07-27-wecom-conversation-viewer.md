# 企业微信会话展示组件 Implementation Plan

> **非当前计划：** 本文记录最早的企业自建应用方案，已被 `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md` 的服务商代开发主线取代，不得作为当前凭证、登录或部署依据。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WeCom self-built-application conversation viewer path to the current message center demo while preserving the existing frontend layout and multi-channel merge behavior.

**Architecture:** Keep WeCom viewer integration in a channel adapter service, and keep contact/thread truth in the existing `UnifiedMessageStore` path. The backend signs JS-SDK requests, exchanges WeCom user auth codes, creates short-lived viewer sessions, and returns only bounded message references. The frontend replaces only the current WeCom placeholder with a small panel that initializes `@wecom/jssdk`, calls `ww.initOpenData()`, and renders `ww-open-message` entries through `ww.createOpenDataFrame()`.

**Tech Stack:** Java 17, `com.sun.net.httpserver.HttpServer`, Gson, Java `HttpClient`, JUnit 5, Node OpenAPI contract probe, embedded HTML/CSS/JS in `App.pageHtml()`, WeCom `@wecom/jssdk` CDN/global `ww`.

**Post-review hardening:** The implemented v1 path uses `viewerAuthToken` instead of browser-supplied `wecomUserId` for viewer session creation and reading. Session detail requests pass the token with the `X-WeCom-Viewer-Auth` header, not a URL query parameter. Local demo JSONL viewer references must include a matching enterprise WeCom user owner field (`userid`, `UserId`, `wecom_userid`, or `wecomUserId`), and viewer sessions are single-use.

**Implementation reconciliation:** The completed runtime additionally selects the latest configured message-reference window by `send_time` (default 10, hard maximum 20), skips malformed local JSONL rows, rate-limits viewer-session creation per WeCom user, maps failures to structured HTTP errors, and writes bounded local audit events through `WeComViewerAuditTrail`. Only the most recently consumed viewer session keeps a short-lived token binding, and that binding is atomically consumed so one matching `component_error` can be audited without sequential or concurrent replay; no message content or secret key is copied into audit storage. Viewer JSON bodies reject undeclared fields and have a 4 KiB limit. External WeCom HTTP calls URL-encode query values, use 10-second connect/request timeouts, and cap responses at 1 MiB.

## Global Constraints

- 企业微信先按“企业自建应用”接入，不实现第三方服务商 suite 授权。
- 保留现有消息中心前端样式、三栏布局、滚动加载、发送入口、toast、详情栏和联系人交互；除新增企业微信会话入口和组件容器外，不重做现有 UI。
- 企业微信会话展示组件不拥有联系人合并、统一消息排序、权限或审计真相。
- 前端不接触 `corpsecret`、`access_token`、`jsapi_ticket` 或任何长期密钥。
- 企业微信会话展示组件前端必须使用官方开放数据方式：`ww.register()`、`ww.initOpenData()`、`ww.createOpenDataFrame()`、`ww-open-message`。
- `jsApiList` 必须包含 `wwapp.invokeJsApiByCallInfo`。
- `ww-open-message` 必须使用 `message-id="{{item.msgid}}"`、`secret-key="{{item.secretKey}}"`、`open-type="viewMessage"`。
- `secretKey` 是从会话记录 `encrypted_secretkey` 解密后的消息密钥；demo 可以从本地 WeCom JSONL 读取 `secret_key` 或 `secretKey` 字段，但不得在前端硬编码真实密钥。
- 单个 viewer session 默认 300 秒有效，默认返回按 `send_time` 排序后的最近 10 条 WeCom viewer message references，配置硬上限为 20。
- 同一企业微信用户每分钟默认最多创建 10 个 viewer session；本地结构化审计文件默认 1 MiB，达到上限时失败关闭。
- 禁止 `git add .`。如果提交，只能精确 stage 本任务文件；当前工作区存在其他 WeCom/Email WIP，不能回滚或吸入。

---

## File Structure

- Create `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
  - Owns JS-SDK signature generation, token/ticket cache, allowed-origin validation, auth code exchange facade, short-lived viewer sessions, and WeCom viewer message references.
  - Provides deterministic test hooks through an injectable `Clock`, `NonceSource`, and `WeComHttpGateway`.

- Create `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrail.java`
  - Owns the bounded local JSONL audit adapter for the file-based demo; production modular wiring delegates the same structured events to `AuditService`.

- Modify `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
  - Adds WeCom self-built app config getters: `wecomCorpId()`, `wecomAgentId()`, `wecomSecret()`, `wecomAllowedJsapiOrigins()`, `wecomViewerSessionTtlSeconds()`, `wecomViewerMaxMessages()`, `wecomTokenRefreshSkewSeconds()`.

- Modify `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
  - Wires `WeComViewerService`.
  - Adds unversioned demo routes next to current `/api/wecom/*` routes.
  - Replaces only the current WeCom composer placeholder with the viewer panel.
  - Adds minimal CSS classes for the new WeCom panel using existing colors, borders, buttons, and toast behavior.

- Modify `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
  - Adds versioned API contract for future modular path: `/api/v1/wecom/js-sdk-config`, `/api/v1/wecom/login/exchange`, `/api/v1/wecom/conversation-view/sessions`, `/api/v1/wecom/conversation-view/sessions/{viewerSessionId}`.

- Modify `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`
  - Adds required paths and operation assertions for the WeCom viewer API.

- Modify `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
  - Covers new config defaults, bounds, and parsing.

- Modify `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
  - Adds service, route, and embedded frontend probes because this demo already uses `App.pageHtml()` string probes.

- Modify `demo/message-center-demo/config.example.env`
  - Documents new WeCom self-built application config without real secrets.

- Modify `demo/message-center-demo/README.md`
  - Documents how to configure and try the WeCom viewer path in the demo.

---

### Task 1: WeCom Viewer Config And Signature Core

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes:
  - `Config.value(String key, String defaultValue)`
  - `Config.readSecret(Path path)`
  - `JsonSupport.string(JsonObject object, String key)`
- Produces:
  - `Config.wecomCorpId(): String`
  - `Config.wecomAgentId(): String`
  - `Config.wecomSecret(): String`
  - `Config.wecomAllowedJsapiOrigins(): List<String>`
  - `Config.wecomViewerSessionTtlSeconds(): int`
  - `Config.wecomViewerMaxMessages(): int`
  - `Config.wecomTokenRefreshSkewSeconds(): int`
  - `WeComViewerService.makeSignature(String ticket, String url, long timestamp, String nonceStr): String`
  - `WeComViewerService.JsSdkConfig jsSdkConfig(String url)`
  - `WeComViewerService.SignatureBundle signatureBundle(String url)`

- [ ] **Step 1: Write failing config tests**

Add these imports to `ConfigTest.java`:

```java
import java.util.List;
```

Add this test method to `ConfigTest.java`:

```java
@Test
void exposesWeComViewerSettingsWithBoundsAndOrigins() {
    Config config = new Config(Map.ofEntries(
            Map.entry("WECOM_CORP_ID", "ww-test-corp"),
            Map.entry("WECOM_AGENT_ID", "1000247"),
            Map.entry("WECOM_SECRET", "corp-secret"),
            Map.entry("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099,https://crm.example.com"),
            Map.entry("WECOM_VIEWER_SESSION_TTL_SECONDS", "300"),
            Map.entry("WECOM_VIEWER_MAX_MESSAGES", "20"),
            Map.entry("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "300")
    ));

    assertEquals("ww-test-corp", config.wecomCorpId());
    assertEquals("1000247", config.wecomAgentId());
    assertEquals("corp-secret", config.wecomSecret());
    assertEquals(List.of("http://localhost:8099", "https://crm.example.com"),
            config.wecomAllowedJsapiOrigins());
    assertEquals(300, config.wecomViewerSessionTtlSeconds());
    assertEquals(20, config.wecomViewerMaxMessages());
    assertEquals(300, config.wecomTokenRefreshSkewSeconds());
}

@Test
void rejectsWeComViewerSettingsOutsideBounds() {
    Config tooSmallTtl = new Config(Map.of("WECOM_VIEWER_SESSION_TTL_SECONDS", "29"));
    Config tooLargeTtl = new Config(Map.of("WECOM_VIEWER_SESSION_TTL_SECONDS", "3601"));
    Config tooManyMessages = new Config(Map.of("WECOM_VIEWER_MAX_MESSAGES", "101"));
    Config tooSmallSkew = new Config(Map.of("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "4"));

    assertThrows(IllegalArgumentException.class, tooSmallTtl::wecomViewerSessionTtlSeconds);
    assertThrows(IllegalArgumentException.class, tooLargeTtl::wecomViewerSessionTtlSeconds);
    assertThrows(IllegalArgumentException.class, tooManyMessages::wecomViewerMaxMessages);
    assertThrows(IllegalArgumentException.class, tooSmallSkew::wecomTokenRefreshSkewSeconds);
}
```

- [ ] **Step 2: Run config tests to verify failure**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test
```

Expected: compilation fails because `Config.wecomCorpId()` and related methods do not exist.

- [ ] **Step 3: Implement config getters**

Add `List` import to `Config.java`:

```java
import java.util.List;
```

Add these methods near the existing config getters:

```java
public String wecomCorpId() { return value("WECOM_CORP_ID", ""); }
public String wecomAgentId() { return value("WECOM_AGENT_ID", ""); }
public String wecomSecret() { return value("WECOM_SECRET", ""); }
public List<String> wecomAllowedJsapiOrigins() {
    String raw = value("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:" + webPort());
    return splitCsv(raw);
}
public int wecomViewerSessionTtlSeconds() {
    return boundedInt("WECOM_VIEWER_SESSION_TTL_SECONDS", 300, 30, 3600);
}
public int wecomViewerMaxMessages() {
    return boundedInt("WECOM_VIEWER_MAX_MESSAGES", 10, 1, 20);
}
public int wecomViewerSessionRateLimit() {
    return boundedInt("WECOM_VIEWER_SESSION_RATE_LIMIT", 10, 1, 60);
}
public int wecomTokenRefreshSkewSeconds() {
    return boundedInt("WECOM_TOKEN_REFRESH_SKEW_SECONDS", 300, 5, 1800);
}
```

Add this private helper near `stripQuotes`:

```java
private static List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) {
        return List.of();
    }
    return java.util.Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
}
```

- [ ] **Step 4: Run config tests to verify pass**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test
```

Expected: exit code 0.

- [ ] **Step 5: Write failing signature tests**

Add these imports to `UnifiedMessageStoreTest.java`:

```java
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
```

Add this method near the other WeCom tests:

```java
private static void wecomViewerSignatureUsesOfficialJsapiAlgorithmAndOriginAllowlist() throws Exception {
    Config config = new Config(Map.of(
            "WECOM_CORP_ID", "ww-test-corp",
            "WECOM_AGENT_ID", "1000247",
            "WECOM_SECRET", "secret",
            "WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099,https://crm.example.com"
    ));
    WeComViewerService service = WeComViewerService.forTests(
            config,
            Clock.fixed(Instant.ofEpochSecond(1414587457), ZoneOffset.UTC),
            () -> "Wm3WZYTPz0wzccnW",
            new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

    assertEquals("c97d4ff7bc7e97cd2e0222f9c69ee8bf2fcc3bfe",
            WeComViewerService.makeSignature("sM4AOVdWfPE4DxkXGEs8VMP",
                    "http://mp.weixin.qq.com?params=value",
                    1414587457L,
                    "Wm3WZYTPz0wzccnW"));

    WeComViewerService.JsSdkConfig js = service.jsSdkConfig("http://localhost:8099/?a=1#ignored");
    assertEquals("ww-test-corp", js.corpId());
    assertEquals("1000247", js.agentId());
    assertTrue(js.jsApiList().contains("wwapp.invokeJsApiByCallInfo"),
            "conversation viewer must request wwapp.invokeJsApiByCallInfo");
    assertEquals("1414587457", js.configSignature().timestamp());
    assertEquals("Wm3WZYTPz0wzccnW", js.configSignature().nonceStr());
    assertFalse(js.configSignature().signature().isBlank(), "config signature must be populated");
    assertFalse(js.agentConfigSignature().signature().isBlank(), "agent signature must be populated");

    assertThrows(IllegalArgumentException.class,
            () -> service.jsSdkConfig("https://evil.example.com/page"));
}
```

Add this call before `exposesWecomAdapterPlaceholderAsUnavailableChannel()` in `main()`:

```java
wecomViewerSignatureUsesOfficialJsapiAlgorithmAndOriginAllowlist();
```

- [ ] **Step 6: Run signature test to verify failure**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: compilation fails because `WeComViewerService` does not exist.

- [ ] **Step 7: Implement `WeComViewerService` signature core**

Create `WeComViewerService.java`:

```java
package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class WeComViewerService {
    private final Config config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComHttpGateway gateway;
    private final ConcurrentMap<String, CachedTicket> tickets = new ConcurrentHashMap<>();

    public WeComViewerService(Config config) {
        this(config, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""),
                new JdkWeComHttpGateway(config));
    }

    private WeComViewerService(Config config, Clock clock, NonceSource nonceSource, WeComHttpGateway gateway) {
        this.config = config;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.gateway = gateway;
    }

    static WeComViewerService forTests(Config config, Clock clock, NonceSource nonceSource,
                                       WeComHttpGateway gateway) {
        return new WeComViewerService(config, clock, nonceSource, gateway);
    }

    public JsSdkConfig jsSdkConfig(String rawUrl) throws Exception {
        String url = canonicalAllowedUrl(rawUrl);
        SignatureBundle configSignature = signatureBundle("corp", url);
        SignatureBundle agentSignature = signatureBundle("agent", url);
        return new JsSdkConfig(config.wecomCorpId(), config.wecomAgentId(),
                List.of("selectExternalContact", "shareAppMessage", "wwapp.invokeJsApiByCallInfo"),
                configSignature, agentSignature);
    }

    SignatureBundle signatureBundle(String ticketType, String rawUrl) throws Exception {
        String ticket = ticket(ticketType);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = nonceSource.nextNonce();
        return new SignatureBundle(Long.toString(timestamp), nonce,
                makeSignature(ticket, canonicalAllowedUrl(rawUrl), timestamp, nonce));
    }

    private String ticket(String ticketType) throws Exception {
        long now = clock.instant().getEpochSecond();
        CachedTicket cached = tickets.get(ticketType);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.ticket();
        }
        TicketResponse response = "agent".equals(ticketType)
                ? gateway.fetchAgentJsapiTicket()
                : gateway.fetchCorpJsapiTicket();
        if (response.errcode() != 0 || response.ticket().isBlank()) {
            throw new IOException("Unable to fetch WeCom " + ticketType + " jsapi_ticket: " + response.errmsg());
        }
        tickets.put(ticketType, new CachedTicket(response.ticket(), now + response.expiresIn()));
        return response.ticket();
    }

    private String canonicalAllowedUrl(String rawUrl) {
        URI uri = URI.create(rawUrl == null ? "" : rawUrl);
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        int port = uri.getPort();
        String origin = scheme + "://" + host + (port >= 0 ? ":" + port : "");
        if (scheme.isBlank() || host.isBlank() || !config.wecomAllowedJsapiOrigins().contains(origin)) {
            throw new IllegalArgumentException("WeCom JSAPI URL origin is not allowed");
        }
        URI noFragment = URI.create(origin + Objects.toString(uri.getRawPath(), "")
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        return noFragment.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public static String makeSignature(String ticket, String url, long timestamp, String nonceStr) {
        String payload = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    public record SignatureBundle(String timestamp, String nonceStr, String signature) {}
    public record JsSdkConfig(String corpId, String agentId, List<String> jsApiList,
                              SignatureBundle configSignature, SignatureBundle agentConfigSignature) {}
    public record TicketResponse(int errcode, String errmsg, String ticket, int expiresIn) {}
    private record CachedTicket(String ticket, long expiresAtEpochSecond) {}

    interface NonceSource {
        String nextNonce();
    }

    interface WeComHttpGateway {
        TicketResponse fetchCorpJsapiTicket() throws Exception;
        TicketResponse fetchAgentJsapiTicket() throws Exception;
    }

    static class StaticGateway implements WeComHttpGateway {
        private final String corpTicket;
        private final String agentTicket;
        private final String userId;

        StaticGateway(String corpTicket, String agentTicket, String userId) {
            this.corpTicket = corpTicket;
            this.agentTicket = agentTicket;
            this.userId = userId;
        }

        @Override public TicketResponse fetchCorpJsapiTicket() {
            return new TicketResponse(0, "ok", corpTicket, 7200);
        }

        @Override public TicketResponse fetchAgentJsapiTicket() {
            return new TicketResponse(0, "ok", agentTicket, 7200);
        }

        String userId() {
            return userId;
        }
    }

    private static class JdkWeComHttpGateway implements WeComHttpGateway {
        private final Config config;
        private final HttpClient client = HttpClient.newHttpClient();
        private String accessToken;
        private long accessTokenExpiresAt;

        JdkWeComHttpGateway(Config config) {
            this.config = config;
        }

        @Override public TicketResponse fetchCorpJsapiTicket() throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/get_jsapi_ticket?access_token=" + accessToken());
        }

        @Override public TicketResponse fetchAgentJsapiTicket() throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/ticket/get?access_token="
                    + accessToken() + "&type=agent_config");
        }

        private String accessToken() throws Exception {
            long now = System.currentTimeMillis() / 1000;
            if (accessToken != null && now < accessTokenExpiresAt - config.wecomTokenRefreshSkewSeconds()) {
                return accessToken;
            }
            if (config.wecomCorpId().isBlank() || config.wecomSecret().isBlank()) {
                throw new IOException("WeCom corp id or secret is not configured");
            }
            String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="
                    + config.wecomCorpId() + "&corpsecret=" + config.wecomSecret();
            JsonObject body = getJson(url);
            int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
            if (errcode != 0) {
                throw new IOException("Unable to fetch WeCom access_token: " + JsonSupport.string(body, "errmsg"));
            }
            accessToken = JsonSupport.string(body, "access_token");
            int expiresIn = body.has("expires_in") ? body.get("expires_in").getAsInt() : 7200;
            accessTokenExpiresAt = now + expiresIn;
            return accessToken;
        }

        private TicketResponse fetchTicket(String url) throws Exception {
            JsonObject body = getJson(url);
            int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
            return new TicketResponse(errcode, JsonSupport.string(body, "errmsg"),
                    JsonSupport.string(body, "ticket"),
                    body.has("expires_in") ? body.get("expires_in").getAsInt() : 7200);
        }

        private JsonObject getJson(String url) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
    }
}
```

- [ ] **Step 8: Run compile and focused tests**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test
cd demo/message-center-demo && mvn -q test-compile
```

Expected: both exit code 0. The `UnifiedMessageStoreTest` main probe may still fail later on existing WeCom placeholder WIP if the old placeholder assertion has not been updated in Task 3.

- [ ] **Step 9: Review and commit exact files if the workspace is clean enough**

Run:

```bash
git status --short -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
```

If committing is appropriate in the current checkout, stage exact paths only:

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: add wecom viewer signature core"
```

---

### Task 2: Auth Exchange And Short-Lived Viewer Sessions

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes:
  - `WeComViewerService.StaticGateway`
  - `Config.wecomViewerSessionTtlSeconds()`
  - `Config.wecomViewerMaxMessages()`
  - `Config.wecomDataFile()`
- Produces:
  - `WeComViewerService.LoginExchangeResponse exchangeLoginCode(String code)`
  - `WeComViewerService.ViewerSessionResponse createViewerSession(String contactPointId, String viewerAuthToken)`
  - `WeComViewerService.ViewerSessionDetail viewerSession(String viewerSessionId, String viewerAuthToken)`
  - JSONL message fields read by viewer: `msgid`, `secret_key` or `secretKey`, `encrypted_secretkey`, `external_userid`, `open_kfid`, `send_time`

- [ ] **Step 1: Write failing session tests**

Add this method to `UnifiedMessageStoreTest.java`:

```java
private static void wecomViewerSessionsAreBoundedUserScopedAndReadMessageReferences() throws Exception {
    Path dir = Files.createTempDirectory("message-center-wecom-viewer-test");
    Path dataFile = dir.resolve("wecom-messages.jsonl");
    Files.writeString(dataFile, ""
            + "{\"msgid\":\"m1\",\"secret_key\":\"s1\",\"external_userid\":\"ext-1\",\"open_kfid\":\"kf-1\",\"send_time\":1,\"msgtype\":\"text\",\"text\":{\"content\":\"one\"}}\n"
            + "{\"msgid\":\"m2\",\"secretKey\":\"s2\",\"external_userid\":\"ext-1\",\"open_kfid\":\"kf-1\",\"send_time\":2,\"msgtype\":\"text\",\"text\":{\"content\":\"two\"}}\n"
            + "{\"msgid\":\"m3\",\"secret_key\":\"s3\",\"external_userid\":\"ext-2\",\"open_kfid\":\"kf-1\",\"send_time\":3,\"msgtype\":\"text\",\"text\":{\"content\":\"three\"}}\n",
            StandardCharsets.UTF_8);
    Config config = new Config(Map.of(
            "DATA_DIR", dir.toString(),
            "WECOM_DATA_FILE", dataFile.toString(),
            "WECOM_CORP_ID", "ww-test-corp",
            "WECOM_AGENT_ID", "1000247",
            "WECOM_SECRET", "secret",
            "WECOM_VIEWER_SESSION_TTL_SECONDS", "60",
            "WECOM_VIEWER_MAX_MESSAGES", "2"
    ));
    WeComViewerService service = WeComViewerService.forTests(
            config,
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
            () -> "nonce",
            new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

    WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("code-1");
    assertEquals("user-1", login.wecomUserId());
    assertFalse(login.viewerAuthToken().isBlank(), "login exchange must return a bounded viewer auth token");

    WeComViewerService.ViewerSessionResponse created = service.createViewerSession("wecom:ext-1", "user-1");
    assertEquals(60, created.expiresIn());
    assertFalse(created.viewerSessionId().isBlank(), "viewer session id must be generated");

    WeComViewerService.ViewerSessionDetail detail = service.viewerSession(created.viewerSessionId(), "user-1");
    assertEquals("ww-test-corp", detail.corpId());
    assertEquals("1000247", detail.agentId());
    assertEquals(2, detail.messages().size());
    assertEquals("m1", detail.messages().get(0).msgid());
    assertEquals("s1", detail.messages().get(0).secretKey());
    assertEquals("m2", detail.messages().get(1).msgid());
    assertEquals("s2", detail.messages().get(1).secretKey());

    assertThrows(SecurityException.class,
            () -> service.viewerSession(created.viewerSessionId(), "other-user"));

    WeComViewerService empty = WeComViewerService.forTests(
            config,
            Clock.fixed(Instant.ofEpochSecond(2000), ZoneOffset.UTC),
            () -> "nonce",
            new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));
    assertThrows(IllegalArgumentException.class,
            () -> empty.viewerSession(created.viewerSessionId(), "user-1"));
}
```

Add this call before `exposesWecomAdapterPlaceholderAsUnavailableChannel()` in `main()`:

```java
wecomViewerSessionsAreBoundedUserScopedAndReadMessageReferences();
```

- [ ] **Step 2: Run session tests to verify failure**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: compilation fails because `exchangeLoginCode`, `createViewerSession`, and `viewerSession` do not exist.

- [ ] **Step 3: Extend gateway and records**

In `WeComViewerService.java`, add records near the existing records:

```java
public record LoginExchangeResponse(String wecomUserId, String viewerAuthToken, int expiresIn) {}
public record ViewerSessionResponse(String viewerSessionId, int expiresIn) {}
public record ViewerSessionDetail(String viewerSessionId, String corpId, String agentId,
                                  List<ViewerMessage> messages) {}
public record ViewerMessage(String msgid, String secretKey) {}
private record ViewerSession(String id, String wecomUserId, String contactPointId,
                             long expiresAtEpochSecond, List<ViewerMessage> messages) {}
```

Add a field:

```java
private final ConcurrentMap<String, ViewerSession> viewerSessions = new ConcurrentHashMap<>();
```

Extend `WeComHttpGateway`:

```java
String exchangeLoginCode(String code) throws Exception;
```

Implement it in `StaticGateway`:

```java
@Override public String exchangeLoginCode(String code) {
    if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("WeCom login code is required");
    }
    return userId;
}
```

Implement it in `JdkWeComHttpGateway` using the self-built app user info endpoint:

```java
@Override public String exchangeLoginCode(String code) throws Exception {
    if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("WeCom login code is required");
    }
    JsonObject body = getJson("https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token="
            + accessToken() + "&code=" + code);
    int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
    if (errcode != 0) {
        throw new IOException("Unable to exchange WeCom login code: " + JsonSupport.string(body, "errmsg"));
    }
    String userId = JsonSupport.string(body, "userid");
    if (userId.isBlank()) {
        userId = JsonSupport.string(body, "UserId");
    }
    if (userId.isBlank()) {
        throw new IOException("WeCom login code did not return userid");
    }
    return userId;
}
```

- [ ] **Step 4: Implement session methods**

Add methods to `WeComViewerService.java` using the post-review hardened contract:

- `exchangeLoginCode(code)` stores a bounded `viewerAuthToken` mapped to the WeCom `userid`.
- `createViewerSession(contactPointId, viewerAuthToken)` resolves the token server-side, filters viewer message references by both `external_userid` and the resolved WeCom user owner field, and refuses empty or unauthorized result sets.
- `viewerSession(viewerSessionId, viewerAuthToken)` requires the same token that created the session and removes the session after successful read.
- The local JSONL owner field can be `userid`, `UserId`, `wecom_userid`, or `wecomUserId`.

- [ ] **Step 5: Run focused compile**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: exit code 0.

- [ ] **Step 6: Commit exact files if appropriate**

Run:

```bash
git status --short -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
```

If committing is appropriate:

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: add wecom viewer sessions"
```

---

### Task 3: Demo HTTP Routes

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes:
  - `WeComViewerService.jsSdkConfig(String url)`
  - `WeComViewerService.exchangeLoginCode(String code)`
  - `WeComViewerService.createViewerSession(String contactPointId, String viewerAuthToken)`
  - `WeComViewerService.viewerSession(String viewerSessionId, String viewerAuthToken)`
- Produces demo routes:
  - `GET /api/wecom/js-sdk-config?url=...`
  - `POST /api/wecom/login/exchange`
  - `POST /api/wecom/conversation-view/sessions`
  - `GET /api/v1/wecom/conversation-view/sessions/{viewerSessionId}` with `X-WeCom-Viewer-Auth`

- [ ] **Step 1: Write failing route test**

Add this method to `UnifiedMessageStoreTest.java`:

```java
private static void wecomViewerRoutesReturnBoundedConfigAndSessionPayloads() throws Exception {
    Path dir = Files.createTempDirectory("message-center-wecom-viewer-route-test");
    Path emailData = dir.resolve("email");
    Path chatData = dir.resolve("chatapp");
    Path wecomData = dir.resolve("wecom.jsonl");
    Files.createDirectories(emailData);
    Files.createDirectories(chatData);
    Files.writeString(wecomData,
            "{\"msgid\":\"m1\",\"secret_key\":\"s1\",\"external_userid\":\"ext-1\",\"open_kfid\":\"kf-1\",\"send_time\":1,\"msgtype\":\"text\"}\n",
            StandardCharsets.UTF_8);
    Config config = new Config(Map.of(
            "DATA_DIR", dir.toString(),
            "EMAIL_DATA_DIR", emailData.toString(),
            "CHATAPP_DATA_FILE", chatData.resolve("messages.jsonl").toString(),
            "CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString(),
            "WECOM_DATA_FILE", wecomData.toString(),
            "WECOM_CORP_ID", "ww-test-corp",
            "WECOM_AGENT_ID", "1000247",
            "WECOM_SECRET", "secret",
            "WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099"
    ));
    UnifiedMessageStore store = new UnifiedMessageStore(config);
    WeComViewerService viewer = WeComViewerService.forTests(
            config,
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
            () -> "nonce",
            new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

    FakeHttpExchange configExchange = new FakeHttpExchange(
            "GET", "/api/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F");
    App.routeForTests(configExchange, config, store, viewer);
    assertEquals(200, configExchange.responseCode);
    assertContains(configExchange.responseText(), "\"corpId\": \"ww-test-corp\"");
    assertContains(configExchange.responseText(), "wwapp.invokeJsApiByCallInfo");
    assertNotContains(configExchange.responseText(), "secret");

    FakeHttpExchange loginExchange = new FakeHttpExchange("POST", "/api/wecom/login/exchange");
    loginExchange.requestBodyJson("{\"code\":\"code-1\"}");
    App.routeForTests(loginExchange, config, store, viewer);
    assertEquals(200, loginExchange.responseCode);
    assertContains(loginExchange.responseText(), "\"wecomUserId\": \"user-1\"");
    String viewerAuthToken = JsonParser.parseString(loginExchange.responseText()).getAsJsonObject()
            .get("viewerAuthToken").getAsString();

    FakeHttpExchange createExchange = new FakeHttpExchange("POST", "/api/wecom/conversation-view/sessions");
    createExchange.requestBodyJson("{\"contactPointId\":\"wecom:ext-1\",\"viewerAuthToken\":\"" + viewerAuthToken + "\"}");
    App.routeForTests(createExchange, config, store, viewer);
    assertEquals(200, createExchange.responseCode);
    String sessionId = JsonParser.parseString(createExchange.responseText()).getAsJsonObject()
            .get("viewerSessionId").getAsString();

    FakeHttpExchange detailExchange = new FakeHttpExchange(
            "GET", "/api/wecom/conversation-view/sessions/" + sessionId);
    detailExchange.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
    App.routeForTests(detailExchange, config, store, viewer);
    assertEquals(200, detailExchange.responseCode);
    assertContains(detailExchange.responseText(), "\"msgid\": \"m1\"");
    assertContains(detailExchange.responseText(), "\"secretKey\": \"s1\"");
}
```

Add this call before `exposesWecomAdapterPlaceholderAsUnavailableChannel()` in `main()`:

```java
wecomViewerRoutesReturnBoundedConfigAndSessionPayloads();
```

- [ ] **Step 2: Run route test to verify failure**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: compilation fails because `App.routeForTests(...)` does not exist.

- [ ] **Step 3: Wire viewer service into App**

In `startWeb`, create the service:

```java
WeComViewerService weComViewer = new WeComViewerService(config);
```

Change the route call:

```java
route(exchange, config, store, mailSender, chatAppSender, emailSyncService,
        chatAppSyncService, weComReceiver, weComViewer, events);
```

Change route signature:

```java
private static void route(HttpExchange exchange, Config config, UnifiedMessageStore store, MailSender mailSender,
                          ChatAppSender chatAppSender, EmailSyncService emailSyncService,
                          ChatAppHistorySyncService chatAppSyncService, WeComReceiver weComReceiver,
                          WeComViewerService weComViewer, EventHub events) throws Exception {
```

Add this helper near `route`:

```java
static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                          WeComViewerService weComViewer) throws Exception {
    route(exchange, config, store, new MailSender(config), new ChatAppSender(config),
            new EmailSyncService(config), new ChatAppHistorySyncService(config),
            new WeComReceiver(config), weComViewer, new EventHub());
}
```

- [ ] **Step 4: Add WeCom viewer route branches**

Add route branches before the existing `/api/wecom/sync_msg` route:

```java
if ("GET".equals(method) && "/api/wecom/js-sdk-config".equals(path)) {
    writeJson(exchange, 200, weComViewer.jsSdkConfig(query(exchange).getOrDefault("url", "")));
    return;
}
if ("POST".equals(method) && "/api/wecom/login/exchange".equals(path)) {
    JsonObject body = readJson(exchange);
    writeJson(exchange, 200, weComViewer.exchangeLoginCode(json(body, "code")));
    return;
}
if ("POST".equals(method) && "/api/wecom/conversation-view/sessions".equals(path)) {
    JsonObject body = readJson(exchange);
    writeJson(exchange, 200, weComViewer.createViewerSession(
            json(body, "contactPointId"), json(body, "viewerAuthToken")));
    return;
}
if ("GET".equals(method) && path.startsWith("/api/wecom/conversation-view/sessions/")) {
    String sessionId = path.substring("/api/wecom/conversation-view/sessions/".length());
    writeJson(exchange, 200, weComViewer.viewerSession(sessionId,
            exchange.getRequestHeaders().getFirst("X-WeCom-Viewer-Auth")));
    return;
}
```

- [ ] **Step 5: Update old placeholder expectation**

The previous `exposesWecomAdapterPlaceholderAsUnavailableChannel()` assertion is no longer valid once WeCom viewer exists. Replace it with:

```java
private static void exposesWecomAdapterAsAvailableViewerChannel() throws Exception {
    Path dir = Files.createTempDirectory("message-center-wecom-test");
    Path emailData = dir.resolve("email");
    Path chatData = dir.resolve("chatapp");
    Files.createDirectories(emailData);
    Files.createDirectories(chatData);
    UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

    ChannelCapability capability = store.channelCapability("wecom");

    assertEquals("wecom", capability.channel);
    assertEquals("true", Boolean.toString(capability.available));
}
```

Rename the `main()` call to:

```java
exposesWecomAdapterAsAvailableViewerChannel();
```

- [ ] **Step 6: Run focused compile and main probe**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
cd demo/message-center-demo && mvn -q -e -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: `test-compile` exits 0. The main probe should proceed past the new WeCom viewer tests and old placeholder assertion. If it fails later on unrelated Email WIP, record the exact failing method and do not change unrelated files.

- [ ] **Step 7: Commit exact files if appropriate**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: expose wecom viewer demo routes"
```

---

### Task 4: Frontend WeCom Viewer Panel Without Restyling Existing UI

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes demo routes from Task 3.
- Produces frontend functions:
  - `wecomPoint(contact): ContactPoint | null`
  - `renderWeComViewerPanel(contact): void`
  - `loadWeComViewer(contact): Promise<void>`
  - `ensureWeComSdk(config): Promise<void>`
  - `mountWeComOpenDataFrame(detail, viewerAuthToken): Promise<void>`

- [ ] **Step 1: Write failing frontend string/probe test**

Add this method to `UnifiedMessageStoreTest.java`:

```java
private static void frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions() {
    String html = App.pageHtml();

    assertContains(html, "function renderWeComViewerPanel(contact)");
    assertContains(html, "function loadWeComViewer(contact)");
    assertContains(html, "function ensureWeComSdk(config)");
    assertContains(html, "function mountWeComOpenDataFrame(detail, viewerAuthToken)");
    assertContains(html, "ww.register({");
    assertContains(html, "await ww.initOpenData();");
    assertContains(html, "ww.createOpenDataFrameFactory()");
    assertContains(html, "'wwapp.invokeJsApiByCallInfo'");
    assertContains(html, "<ww-open-message message-id=\"{{item.msgid}}\" secret-key=\"{{item.secretKey}}\" open-type=\"viewMessage\" />");
    assertContains(html, "企业微信 API 接入位已预留");
    assertContains(html, "renderChatMode(state.selectedMode, point?.value || '');");
    assertContains(html, "if (state.selectedChannel === 'email')");
    assertContains(html, "if (state.selectedChannel === 'chatapp')");
}
```

Add this call before other frontend probes in `main()`:

```java
frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions();
```

- [ ] **Step 2: Run frontend probe to verify failure**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: compilation succeeds, but running the main probe would fail because the new strings are not present.

- [ ] **Step 3: Add minimal CSS classes**

In `App.pageHtml()` CSS near `.account-list`, add:

```css
    .wecom-viewer-panel { display:grid; gap:10px; }
    .wecom-viewer-actions { display:flex; justify-content:flex-end; gap:8px; align-items:center; }
    .wecom-viewer-container { min-height:160px; border:1px solid var(--hairline); border-radius:6px; background:#fff; overflow:auto; }
```

Do not change `.shell`, `.thread`, `.contact-list`, `.composer`, `.detail`, `.msg`, or `.contact`.

- [ ] **Step 4: Add WeCom panel functions**

Add these JS functions near `renderSendPanel`:

```javascript
    function wecomPoint(contact) {
      const points = (contact && contact.points ? contact.points : []).filter(p => p.channel === 'wecom');
      return points[0] || null;
    }

    function renderWeComViewerPanel(contact) {
      const point = wecomPoint(contact);
      const disabled = !point ? 'disabled' : '';
      $('sendPanel').innerHTML = `
        <div class="wecom-viewer-panel">
          <div class="empty">企业微信 API 接入位已预留</div>
          <div class="readonly-row">
            <div class="small">企业微信账号</div>
            <div class="readonly-value">${esc(point?.value || point?.id || '无可用账号')}</div>
          </div>
          <div class="wecom-viewer-actions">
            <button class="primary" id="openWeComViewer" type="button" ${disabled}>打开企业微信会话</button>
          </div>
          <div class="wecom-viewer-container" id="wecomViewerContainer"><div class="empty">点击后加载企业微信会话展示组件</div></div>
        </div>`;
      const button = $('openWeComViewer');
      if (button) button.onclick = () => loadWeComViewer(contact);
    }

    async function loadWeComViewer(contact) {
      const point = wecomPoint(contact);
      if (!point) { toast('当前联系人没有企业微信账号'); return; }
      const container = $('wecomViewerContainer');
      container.innerHTML = '<div class="empty">正在加载企业微信会话</div>';
      try {
        const currentUrl = window.location.href.split('#')[0];
        const config = await api('/api/wecom/js-sdk-config?url=' + encodeURIComponent(currentUrl));
        await ensureWeComSdk(config);
        const login = await postJson('/api/v1/wecom/login/exchange', { code:wecomAuthCode() });
        const created = await postJson('/api/v1/wecom/conversation-view/sessions', {
          contactPointId: point.id,
          viewerAuthToken: login.viewerAuthToken
        });
        const detail = await api('/api/v1/wecom/conversation-view/sessions/' + encodeURIComponent(created.viewerSessionId), {
          headers: { 'X-WeCom-Viewer-Auth': login.viewerAuthToken }
        });
        await mountWeComOpenDataFrame(detail, login.viewerAuthToken);
      } catch (err) {
        container.innerHTML = `<div class="empty">企业微信会话加载失败：${esc(err.message)}</div>`;
        toast(`企业微信会话加载失败：${err.message}`);
      }
    }

    async function ensureWeComSdk(config) {
      if (!window.ww) throw new Error('企业微信 JS-SDK 未加载');
      ww.register({
        corpId: config.corpId,
        agentId: config.agentId,
        jsApiList: config.jsApiList || ['wwapp.invokeJsApiByCallInfo'],
        async getConfigSignature() { return config.configSignature; },
        async getAgentConfigSignature() { return config.agentConfigSignature; }
      });
      await ww.initOpenData();
    }

    async function mountWeComOpenDataFrame(detail, viewerAuthToken) {
      const container = $('wecomViewerContainer');
      if (!detail.messages || !detail.messages.length) {
        container.innerHTML = '<div class="empty">暂无可展示的企业微信会话记录</div>';
        return;
      }
      const factory = ww.createOpenDataFrameFactory();
      factory.createOpenDataFrame({
        el: container,
        template: `
          <view wx:for="{{data.msgList}}" wx:key="msgid" class="msg">
            <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}" open-type="viewMessage" />
          </view>
        `,
        style: `.msg { height: 100%; overflow: auto; }`,
        data: { msgList: detail.messages },
        methods: {},
        error(error) {
          container.innerHTML = '<div class="empty">企业微信组件渲染失败</div>';
          reportWeComViewerEvent('component_error', detail.viewerSessionId, viewerAuthToken);
        }
      });
    }
```

Load the official JS-SDK only when the user opens the viewer. Keep the CDN out of the main page's parser-critical path and reject after 10 seconds:

```javascript
const WECOM_SDK_SRC = 'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js';

function loadWeComSdk() {
  if (window.ww) return Promise.resolve(window.ww);
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    const timer = setTimeout(() => reject(new Error('企业微信 JS-SDK 加载超时')), 10000);
    script.src = WECOM_SDK_SRC;
    script.async = true;
    script.onload = () => { clearTimeout(timer); window.ww ? resolve(window.ww) : reject(new Error('企业微信 JS-SDK 未加载')); };
    script.onerror = () => { clearTimeout(timer); reject(new Error('企业微信 JS-SDK 加载失败')); };
    document.head.appendChild(script);
  });
}
```

- [ ] **Step 5: Replace only WeCom branch in `renderSendPanel`**

Replace:

```javascript
      panel.innerHTML = '<div class="empty">企业微信 API 接入位已预留</div>';
```

With:

```javascript
      renderWeComViewerPanel(contact);
```

Do not alter the Email branch, ChatApp branch, tab rendering, existing composer CSS, or scroll logic.

- [ ] **Step 6: Run frontend probe**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
cd demo/message-center-demo && mvn -q -e -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: new frontend probe passes. If the main probe fails on unrelated WIP after this point, record the exact failing method and continue with focused Maven tests.

- [ ] **Step 7: Commit exact files if appropriate**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: add wecom viewer frontend panel"
```

---

### Task 5: Versioned OpenAPI Contract

**Files:**
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- Produces versioned contract paths:
  - `/api/v1/wecom/js-sdk-config`
  - `/api/v1/wecom/login/exchange`
  - `/api/v1/wecom/conversation-view/sessions`
  - `/api/v1/wecom/conversation-view/sessions/{viewerSessionId}`

- [ ] **Step 1: Write failing OpenAPI probe expectations**

In `message-center-v1.test.mjs`, add paths to `requiredPaths`:

```javascript
  '/api/v1/wecom/js-sdk-config',
  '/api/v1/wecom/login/exchange',
  '/api/v1/wecom/conversation-view/sessions',
  '/api/v1/wecom/conversation-view/sessions/{viewerSessionId}',
```

Add checks after webhook assertions:

```javascript
const wecomConfig = operation(operations, 'get', '/api/v1/wecom/js-sdk-config');
assert.match(wecomConfig.body, /operationId: getWeComJsSdkConfig/);
assert.match(wecomConfig.body, /WECOM_ALLOWED_JSAPI_ORIGINS/);
assert.match(wecomConfig.body, /wwapp\.invokeJsApiByCallInfo/);

const wecomSession = operation(operations, 'post', '/api/v1/wecom/conversation-view/sessions');
assert.match(wecomSession.body, /operationId: createWeComConversationViewerSession/);
assert.match(wecomSession.body, /read permission/);
assert.match(contract, /^    WeComViewerMessage:$/m);
assert.match(schema(contract, 'WeComViewerMessage'), /required: \[msgid, secretKey\]/);
assert.doesNotMatch(schema(contract, 'WeComViewerMessage'), /access_token|corpsecret|jsapi_ticket/i);
```

- [ ] **Step 2: Run OpenAPI probe to verify failure**

Run:

```bash
cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs
```

Expected: fails with missing WeCom paths or schema.

- [ ] **Step 3: Add OpenAPI paths**

In `message-center-v1.yaml`, add paths under `paths:`:

```yaml
  /api/v1/wecom/js-sdk-config:
    get:
      operationId: getWeComJsSdkConfig
      description: Return WeCom JS-SDK signatures for an allowed frontend URL. The server validates the URL against WECOM_ALLOWED_JSAPI_ORIGINS and never returns corpsecret, access_token, or jsapi_ticket.
      tags:
        - Channels
      parameters:
        - $ref: '#/components/parameters/TraceRequestId'
        - name: url
          in: query
          required: true
          schema:
            type: string
            minLength: 1
            maxLength: 2048
      responses:
        '200':
          description: WeCom JS-SDK config
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WeComJsSdkConfig'
        '4XX':
          $ref: '#/components/responses/ApiErrorResponse'
        default:
          $ref: '#/components/responses/ApiErrorResponse'
  /api/v1/wecom/login/exchange:
    post:
      operationId: exchangeWeComLoginCode
      description: Exchange a short-lived WeCom authorization code for the current message-center user's WeCom identity.
      tags:
        - Channels
      parameters:
        - $ref: '#/components/parameters/TraceRequestId'
        - $ref: '#/components/parameters/CsrfTokenHeader'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/WeComLoginExchangeRequest'
      responses:
        '200':
          description: WeCom user identity for this message-center session
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WeComLoginExchangeResponse'
        '4XX':
          $ref: '#/components/responses/ApiErrorResponse'
        default:
          $ref: '#/components/responses/ApiErrorResponse'
  /api/v1/wecom/conversation-view/sessions:
    post:
      operationId: createWeComConversationViewerSession
      description: Create a short-lived WeCom viewer session after the messaging read permission check succeeds.
      tags:
        - Channels
      parameters:
        - $ref: '#/components/parameters/TraceRequestId'
        - $ref: '#/components/parameters/CsrfTokenHeader'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/WeComViewerSessionCreateRequest'
      responses:
        '200':
          description: Bounded WeCom viewer session id
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WeComViewerSessionCreateResponse'
        '4XX':
          $ref: '#/components/responses/ApiErrorResponse'
        default:
          $ref: '#/components/responses/ApiErrorResponse'
  /api/v1/wecom/conversation-view/sessions/{viewerSessionId}:
    get:
      operationId: getWeComConversationViewerSession
      description: Return bounded WeCom viewer message references for a valid short-lived viewer session.
      tags:
        - Channels
      parameters:
        - $ref: '#/components/parameters/TraceRequestId'
        - name: viewerSessionId
          in: path
          required: true
          schema:
            type: string
            minLength: 16
            maxLength: 64
      responses:
        '200':
          description: WeCom viewer session detail
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WeComViewerSessionDetail'
        '4XX':
          $ref: '#/components/responses/ApiErrorResponse'
        default:
          $ref: '#/components/responses/ApiErrorResponse'
```

- [ ] **Step 4: Add OpenAPI schemas**

Add schemas under `components.schemas`:

```yaml
    WeComJsSdkSignature:
      type: object
      required: [timestamp, nonceStr, signature]
      additionalProperties: false
      properties:
        timestamp:
          type: string
          minLength: 1
          maxLength: 16
        nonceStr:
          type: string
          minLength: 1
          maxLength: 64
        signature:
          type: string
          minLength: 40
          maxLength: 40
    WeComJsSdkConfig:
      type: object
      required: [corpId, agentId, jsApiList, configSignature, agentConfigSignature]
      additionalProperties: false
      properties:
        corpId:
          type: string
          minLength: 1
          maxLength: 64
        agentId:
          type: string
          minLength: 1
          maxLength: 32
        jsApiList:
          type: array
          minItems: 1
          maxItems: 20
          items:
            type: string
            minLength: 1
            maxLength: 128
          contains:
            const: wwapp.invokeJsApiByCallInfo
        configSignature:
          $ref: '#/components/schemas/WeComJsSdkSignature'
        agentConfigSignature:
          $ref: '#/components/schemas/WeComJsSdkSignature'
    WeComLoginExchangeRequest:
      type: object
      required: [code]
      additionalProperties: false
      properties:
        code:
          type: string
          minLength: 1
          maxLength: 512
    WeComLoginExchangeResponse:
      type: object
      required: [wecomUserId, viewerAuthToken, expiresIn]
      additionalProperties: false
      properties:
        wecomUserId:
          type: string
          minLength: 1
          maxLength: 128
        viewerAuthToken:
          type: string
          minLength: 16
          maxLength: 128
        expiresIn:
          type: integer
          minimum: 30
          maximum: 3600
    WeComViewerSessionCreateRequest:
      type: object
      required: [contactPointId]
      additionalProperties: false
      properties:
        conversationId:
          type: string
          format: uuid
        contactPointId:
          type: string
          minLength: 1
          maxLength: 256
    WeComViewerSessionCreateResponse:
      type: object
      required: [viewerSessionId, expiresIn]
      additionalProperties: false
      properties:
        viewerSessionId:
          type: string
          minLength: 16
          maxLength: 64
        expiresIn:
          type: integer
          minimum: 30
          maximum: 3600
    WeComViewerMessage:
      type: object
      required: [msgid, secretKey]
      additionalProperties: false
      properties:
        msgid:
          type: string
          minLength: 1
          maxLength: 128
        secretKey:
          type: string
          minLength: 1
          maxLength: 1024
    WeComViewerSessionDetail:
      type: object
      required: [viewerSessionId, corpId, agentId, messages]
      additionalProperties: false
      properties:
        viewerSessionId:
          type: string
          minLength: 16
          maxLength: 64
        corpId:
          type: string
          minLength: 1
          maxLength: 64
        agentId:
          type: string
          minLength: 1
          maxLength: 32
        messages:
          type: array
          maxItems: 20
          items:
            $ref: '#/components/schemas/WeComViewerMessage'
```

- [ ] **Step 5: Run OpenAPI probe**

Run:

```bash
cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs
```

Expected: exits 0 and prints `validated N OpenAPI operations` with `N` greater than the previous count.

- [ ] **Step 6: Commit exact files if appropriate**

```bash
git add demo/message-center-demo/contracts/openapi/message-center-v1.yaml demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
git commit -m "docs: add wecom viewer api contract"
```

---

### Task 6: Demo Docs And Config Example

**Files:**
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`

**Interfaces:**
- Consumes actual config names from Task 1.
- Produces user-facing setup docs and internal spec alignment.

- [ ] **Step 1: Write config example entries**

Append this block to `config.example.env`:

```env
# 企业微信会话展示组件（企业自建应用）
WECOM_CORP_ID=
WECOM_AGENT_ID=
WECOM_SECRET=
WECOM_ALLOWED_JSAPI_ORIGINS=http://localhost:8099
WECOM_VIEWER_SESSION_TTL_SECONDS=300
WECOM_VIEWER_MAX_MESSAGES=10
WECOM_VIEWER_SESSION_RATE_LIMIT=10
WECOM_VIEWER_AUDIT_FILE=data/wecom-viewer-audit.jsonl
WECOM_VIEWER_AUDIT_MAX_BYTES=1048576
WECOM_TOKEN_REFRESH_SKEW_SECONDS=300
```

- [ ] **Step 2: Update README WeCom section**

Replace the old “企业微信预留地址” wording with:

```markdown
企业微信会话展示组件：

```text
GET  http://localhost:8099/api/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F
POST http://localhost:8099/api/wecom/login/exchange
POST http://localhost:8099/api/wecom/conversation-view/sessions
GET  http://localhost:8099/api/wecom/conversation-view/sessions/{viewerSessionId}
```

首版按企业自建应用接入。需要在 `.env` 配置 `WECOM_CORP_ID`、`WECOM_AGENT_ID`、`WECOM_SECRET` 和 `WECOM_ALLOWED_JSAPI_ORIGINS`。前端只拿 JS-SDK 签名和短时 viewer session，不接触 `corpsecret`、`access_token` 或 `jsapi_ticket`。

会话展示组件使用企业微信开放数据组件：前端调用 `ww.register()`、`ww.initOpenData()`、`ww.createOpenDataFrame()`，并通过 `ww-open-message` 展示 `msgid + secretKey`。`secretKey` 必须由服务端从企业微信会话记录的 `encrypted_secretkey` 解密得到；本地 demo 可以在 `WECOM_DATA_FILE` 中放入 `secret_key` 或 `secretKey` 字段用于验证 UI。
```

- [ ] **Step 3: Align design spec with confirmed official names**

In `2026-07-27-wecom-conversation-viewer-design.md`, add this under frontend section if it is missing:

```markdown
官方组件接线必须使用：

- `ww.register()` 初始化企业与应用信息。
- `jsApiList` 包含 `wwapp.invokeJsApiByCallInfo`。
- `ww.initOpenData()` 初始化开放数据能力。
- `ww.createOpenDataFrameFactory().createOpenDataFrame(...)` 挂载组件。
- 模板中使用 `ww-open-message`，参数为 `message-id`、`secret-key`、`open-type="viewMessage"`。
```

- [ ] **Step 4: Run doc checks**

Run:

```bash
rg -n "TBD|TODO|待定|后续再补|corpsecret=.*[A-Za-z0-9]" demo/message-center-demo/README.md demo/message-center-demo/config.example.env docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md
git diff --check -- demo/message-center-demo/README.md demo/message-center-demo/config.example.env docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md
```

Expected: `rg` exits 1 with no matches; `git diff --check` exits 0.

- [ ] **Step 5: Commit exact files if appropriate**

```bash
git add demo/message-center-demo/README.md demo/message-center-demo/config.example.env docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md
git commit -m "docs: document wecom viewer setup"
```

---

### Task 7: Final Verification And Dirty Worktree Review

**Files:**
- No new files.
- Review all files touched by Tasks 1-6.

**Interfaces:**
- Consumes all previous tasks.
- Produces final verification evidence.

- [ ] **Step 1: Run full unit tests**

Run:

```bash
cd demo/message-center-demo && mvn -q test
```

Expected: exit code 0.

- [ ] **Step 2: Run OpenAPI probe**

Run:

```bash
cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs
```

Expected: exit code 0 and `validated N OpenAPI operations`.

- [ ] **Step 3: Run test compile**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile
```

Expected: exit code 0.

- [ ] **Step 4: Run main probe**

Run:

```bash
cd demo/message-center-demo && mvn -q -e -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: exits 0 after all probes if existing WIP has been reconciled. If it fails on unrelated pre-existing Email or WeCom WIP, record the exact failing method and line number, then cite `mvn -q test`, OpenAPI probe, and `test-compile` as the primary verification for this task.

- [ ] **Step 5: Run diff checks**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/contracts/openapi/message-center-v1.yaml demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs demo/message-center-demo/README.md demo/message-center-demo/config.example.env docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md
```

Expected: exit code 0.

- [ ] **Step 6: Review exact git status**

Run:

```bash
git status --short
```

Expected: touched files for this feature are identifiable. Existing unrelated WIP must remain unstaged and must be called out in the final handoff.

- [ ] **Step 7: Final response evidence**

Report:

```text
Implemented:
- WeCom JS-SDK signature config with corp and agent tickets.
- WeCom login code exchange and short-lived viewer sessions.
- Demo HTTP routes and versioned OpenAPI contract.
- Existing UI preserved; only WeCom placeholder replaced by a viewer panel.
- Official open-data rendering path: ww.initOpenData + createOpenDataFrameFactory + ww-open-message.

Verification:
- mvn -q test: <result>
- node contracts/openapi/message-center-v1.test.mjs: <result>
- mvn -q test-compile: <result>
- UnifiedMessageStoreTest main probe: <result or exact unrelated WIP failure>
- git diff --check: <result>
```

Do not claim the work is complete unless these commands have been run in the same turn and the outputs have been read.

---

## Self-Review

- Spec coverage: enterprise self-built app, no third-party suite, no frontend secrets, existing UI preserved, official `ww-open-message` rendering, multi-channel merge boundaries, bounded viewer sessions, OpenAPI contract, docs, and verification are all assigned to tasks.
- Placeholder scan target: this plan intentionally avoids `TBD`, `TODO`, vague error handling steps, and unnamed test commands.
- Type consistency: `WeComViewerService.JsSdkConfig`, `SignatureBundle`, `LoginExchangeResponse`, `ViewerSessionResponse`, `ViewerSessionDetail`, and `ViewerMessage` are defined before route/frontend/OpenAPI tasks consume them.
