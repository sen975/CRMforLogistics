# 企业微信浏览器扫码登录最小闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在普通桌面浏览器首屏展示企业微信官方扫码登录面板，扫码成功后在同一标签页进入现有消息中心，并用同一短时授权加载真实 `ww-open-message` 会话组件。

**Architecture:** 新增有界内存 `WeComLoginAttemptService`，只拥有一次性 state 的创建和消费；现有 `WeComViewerService` 继续唯一拥有 code 交换、viewer token、owner 校验、session、限流和审计。页面使用 `ww.createWWLoginPanel({redirect_type:'callback'})` 获取 code，token 仅存在 JavaScript 内存；点击 viewer 时再完成 `ww.register`、`ww.initOpenData`、`jwxwork`、OpenDataFrame、`handleModal` 和 `binderror` 接线。

**Tech Stack:** JDK 17、Java `HttpServer`、Gson 2.11、JUnit 5、原生 HTML/CSS/JavaScript、企业微信 `@wecom/jssdk 2.3.4`、`jwxwork-1.0.0.js`、OpenAPI 3.1、Node.js 合同测试。

## Global Constraints

- 始终使用 JDK 17；`pom.xml` 的 source/target 保持 17。
- 只实现浏览器扫码、短时授权和真实会话组件；不引入数据库、用户名密码、持久 session、账号绑定、自动开户、角色、解绑或换绑。
- 登录和会话组件必须处于完全相同的域名与 top frame；不自行构造企业微信登录 URL。
- 使用 `ww.createWWLoginPanel`、`redirect_type: 'callback'` 和 `@wecom/jssdk 2.3.4`。
- `viewerAuthToken` 只存在当前页面 JavaScript 内存，不进入 URL、cookie、localStorage 或 sessionStorage。
- 登录前不得请求联系人、消息、模板、SSE 或同步 API。
- 会话组件必须加载 `jwxwork-1.0.0.js`，实现 `handleModal`、模板 `binderror`，并处理 `42006`、`42003`、`40029`、`Missing open sid`。
- 不记录 code、state、token、ticket、签名、secretKey、corp secret 或登录 URL。
- 本轮不得执行 `git add` 或 `git commit`；每个任务以针对性测试和 `git diff --check` 代替提交步骤。
- 工作区包含用户的 Email、139 IMAP、模板、SDK `.project` 等无关改动；禁止整体回滚或格式化 `App.java`、`Config.java`、`UnifiedMessageStoreTest.java`。
- 需要访问企业微信官方网络、启动 Safari/safaridriver 或检查系统进程时直接申请系统权限，由用户手动审批。
- 不得强制关闭用户 Safari；如旧 WebDriver 配对状态阻止新会话，停止并报告证据。

---

## File Map

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java` — 一次性扫码 state 的唯一 owner。
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java` — attempt 的 TTL、容量、重放和审计测试。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java` — 登录 redirect URI、TTL 和容量的配置与校验。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java` — 登录配置默认值、边界和同源校验。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java` — attempt/exchange 路由、登录首屏、页面状态门禁和官方 viewer 补全。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java` — HTTP 路由和静态前端回归探针；它不是截图验收替代品。
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml` — 新增 attempt operation，exchange 增加 state。
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs` — 25 个 operation 和敏感字段边界。
- Modify: `demo/message-center-demo/config.example.env` — 新增三项登录配置。
- Modify: `demo/message-center-demo/README.md` — 官方扫码配置、运行流程和限制。
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md` — 回写扫码前置、官方弹窗和错误处理。
- Modify: `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md` — 只在实现中发现合同细节偏差时同步，不重新引入 callback/handoff 路线。

### Task 1: 登录配置合同与边界

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java:76-111`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java:45-105`

**Interfaces:**
- Consumes: 现有 `Config.value`、`boundedInt`、`wecomAllowedJsapiOrigins()`。
- Produces: `String wecomLoginRedirectUri()`、`int wecomLoginAttemptTtlSeconds()`、`int wecomLoginMaxPending()`。

- [ ] **Step 1: 在 `ConfigTest` 写登录配置成功测试**

```java
@Test
void exposesBoundedWeComBrowserLoginSettings() {
    Config config = new Config(Map.of(
            "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
            "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/",
            "WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "300",
            "WECOM_LOGIN_MAX_PENDING", "256"
    ));

    assertEquals("https://crm.example.com/", config.wecomLoginRedirectUri());
    assertEquals(300, config.wecomLoginAttemptTtlSeconds());
    assertEquals(256, config.wecomLoginMaxPending());
}
```

- [ ] **Step 2: 写非法 redirect URI 和容量边界测试**

```java
@Test
void rejectsUnsafeOrCrossOriginWeComBrowserLoginSettings() {
    Config insecure = new Config(Map.of(
            "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
            "WECOM_LOGIN_REDIRECT_URI", "http://crm.example.com/"
    ));
    Config crossOrigin = new Config(Map.of(
            "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
            "WECOM_LOGIN_REDIRECT_URI", "https://other.example.com/"
    ));
    Config fragmented = new Config(Map.of(
            "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
            "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/#code"
    ));
    Config ttlTooSmall = new Config(Map.of("WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "29"));
    Config pendingTooLarge = new Config(Map.of("WECOM_LOGIN_MAX_PENDING", "1025"));

    assertThrows(IllegalArgumentException.class, insecure::wecomLoginRedirectUri);
    assertThrows(IllegalArgumentException.class, crossOrigin::wecomLoginRedirectUri);
    assertThrows(IllegalArgumentException.class, fragmented::wecomLoginRedirectUri);
    assertThrows(IllegalArgumentException.class, ttlTooSmall::wecomLoginAttemptTtlSeconds);
    assertThrows(IllegalArgumentException.class, pendingTooLarge::wecomLoginMaxPending);
}
```

- [ ] **Step 3: 运行测试并确认先失败**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest test
```

Expected: 编译失败，指出三个新的 `Config` 方法尚不存在。

- [ ] **Step 4: 在 `Config` 增加配置 getter 和 redirect URI 校验**

```java
public String wecomLoginRedirectUri() {
    String raw = value("WECOM_LOGIN_REDIRECT_URI", "http://localhost:" + webPort() + "/");
    if (raw.length() > 2048) {
        throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI must not exceed 2048 characters");
    }
    java.net.URI uri;
    try {
        uri = java.net.URI.create(raw);
    } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI must be an absolute URL", exception);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    boolean localHttp = "http".equals(scheme) && "localhost".equals(host);
    if ((!"https".equals(scheme) && !localHttp) || host.isBlank()
            || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI must use HTTPS or local http://localhost without user info or fragment");
    }
    String origin = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    if (!wecomAllowedJsapiOrigins().contains(origin)) {
        throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI origin must be listed in WECOM_ALLOWED_JSAPI_ORIGINS");
    }
    return uri.toString();
}

public int wecomLoginAttemptTtlSeconds() {
    return boundedInt("WECOM_LOGIN_ATTEMPT_TTL_SECONDS", 300, 30, 600);
}

public int wecomLoginMaxPending() {
    return boundedInt("WECOM_LOGIN_MAX_PENDING", 256, 1, 1024);
}
```

- [ ] **Step 5: 运行配置测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest test
```

Expected: `ConfigTest` 全部通过，0 failures。

- [ ] **Step 6: 检查本任务 diff 边界**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java
git diff -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java
```

Expected: 无空白错误；diff 仅包含三项登录配置及其测试，不覆盖已有 viewer、数据库或 Email 配置。

### Task 2: 一次性登录 attempt owner

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java`

**Interfaces:**
- Consumes: `Config.wecomCorpId()`、`wecomAgentId()`、`wecomLoginRedirectUri()`、`wecomLoginAttemptTtlSeconds()`、`wecomLoginMaxPending()`、现有 `WeComViewerAuditTrail.record(...)`。
- Produces: `LoginAttemptResponse createAttempt()`、`void consume(String state)`、`PendingLimitException`、测试工厂 `forTests(Config, Clock, NonceSource)`。

- [ ] **Step 1: 写 attempt 生命周期测试**

```java
package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComLoginAttemptServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsConsumesExpiresAndBoundsAttemptsWithoutAuditingState() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_CORP_ID", "ww-test-corp",
                "WECOM_AGENT_ID", "1000247",
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/",
                "WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "30",
                "WECOM_LOGIN_MAX_PENDING", "2",
                "WECOM_VIEWER_AUDIT_FILE", tempDir.resolve("audit.jsonl").toString()
        ));
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1000));
        Queue<String> states = new ArrayDeque<>();
        states.add("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        states.add("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        states.add("cccccccccccccccccccccccccccccccc");
        WeComLoginAttemptService service = WeComLoginAttemptService.forTests(
                config, clock, states::remove);

        WeComLoginAttemptService.LoginAttemptResponse first = service.createAttempt();
        assertEquals("ww-test-corp", first.corpId());
        assertEquals("1000247", first.agentId());
        assertEquals("https://crm.example.com/", first.redirectUri());
        assertEquals(30, first.expiresIn());
        service.createAttempt();
        assertThrows(WeComLoginAttemptService.PendingLimitException.class, service::createAttempt);

        service.consume(first.state());
        assertThrows(SecurityException.class, () -> service.consume(first.state()));

        WeComLoginAttemptService.LoginAttemptResponse expiring = service.createAttempt();
        clock.advanceSeconds(30);
        assertThrows(SecurityException.class, () -> service.consume(expiring.state()));

        String audit = Files.readString(config.wecomViewerAuditFile(), StandardCharsets.UTF_8);
        assertTrue(audit.contains("wecom.viewer.login_attempt_create"));
        assertTrue(audit.contains("wecom.viewer.login_attempt_consume"));
        assertTrue(audit.contains("rate_limited"));
        assertFalse(audit.contains(first.state()));
        assertFalse(audit.contains(expiring.state()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认先失败**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComLoginAttemptServiceTest test
```

Expected: 编译失败，指出 `WeComLoginAttemptService` 尚不存在。

- [ ] **Step 3: 创建完整 attempt service**

```java
package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WeComLoginAttemptService {
    private final Config config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComViewerAuditTrail auditTrail;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    public WeComLoginAttemptService(Config config) {
        this(config, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""));
    }

    private WeComLoginAttemptService(Config config, Clock clock, NonceSource nonceSource) {
        this.config = config;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.auditTrail = new WeComViewerAuditTrail(config, clock);
    }

    static WeComLoginAttemptService forTests(Config config, Clock clock, NonceSource nonceSource) {
        return new WeComLoginAttemptService(config, clock, nonceSource);
    }

    public synchronized LoginAttemptResponse createAttempt() throws IOException {
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        requireBounded(config.wecomCorpId(), "WeCom corp id", 64);
        requireBounded(config.wecomAgentId(), "WeCom agent id", 32);
        String redirectUri = config.wecomLoginRedirectUri();
        if (attempts.size() >= config.wecomLoginMaxPending()) {
            auditTrail.record("wecom.viewer.login_attempt_create", "rate_limited", "", "", "");
            throw new PendingLimitException("WeCom login attempt capacity exceeded");
        }
        String state = nonceSource.nextNonce();
        requireBounded(state, "WeCom login state", 128);
        if (state.length() < 16 || attempts.containsKey(state)) {
            throw new IllegalStateException("WeCom login state source returned an unsafe or duplicate value");
        }
        auditTrail.record("wecom.viewer.login_attempt_create", "success", "", "", "");
        attempts.put(state, new Attempt(now + config.wecomLoginAttemptTtlSeconds()));
        return new LoginAttemptResponse(config.wecomCorpId(), config.wecomAgentId(), redirectUri,
                state, config.wecomLoginAttemptTtlSeconds());
    }

    public synchronized void consume(String state) throws IOException {
        requireBoundedSecurity(state, "WeCom login state", 128);
        long now = clock.instant().getEpochSecond();
        Attempt attempt = attempts.remove(state);
        if (attempt == null || now >= attempt.expiresAtEpochSecond()) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new SecurityException("WeCom login state is expired, missing, or already used");
        }
        auditTrail.record("wecom.viewer.login_attempt_consume", "success", "", "", "");
    }

    private void cleanupExpired(long now) throws IOException {
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Attempt> entry = iterator.next();
            if (now >= entry.getValue().expiresAtEpochSecond()) {
                auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
                iterator.remove();
            }
        }
    }

    private static void requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    private static void requireBoundedSecurity(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new SecurityException(name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    public record LoginAttemptResponse(String corpId, String agentId, String redirectUri,
                                       String state, int expiresIn) {}

    private record Attempt(long expiresAtEpochSecond) {}

    interface NonceSource {
        String nextNonce();
    }

    public static final class PendingLimitException extends IllegalStateException {
        PendingLimitException(String message) {
            super(message);
        }
    }
}
```

- [ ] **Step 4: 运行 attempt 测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComLoginAttemptServiceTest test
```

Expected: 1 test，0 failures；审计文件不包含任何 state。

- [ ] **Step 5: 运行配置与 attempt 组合测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,WeComLoginAttemptServiceTest test
```

Expected: 两个测试类全部通过。

- [ ] **Step 6: 检查新 owner 没有吸入 code 或 viewer 权限语义**

Run:

```bash
rg -n "exchangeLoginCode|viewerAuthToken|contactPointId|secretKey|access_token|corpsecret" demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java
```

Expected: `rg` 无输出；`git diff --check` 通过。

### Task 3: 登录 attempt 和 exchange HTTP 路由

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:110-145,273-310,338-375`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java:61-65,629-733`

**Interfaces:**
- Consumes: `WeComLoginAttemptService.createAttempt()`、`consume(state)`、`WeComViewerService.exchangeLoginCode(code)`。
- Produces: `POST /api/v1/wecom/login/attempts` 和要求 `{code,state}` 的 `POST /api/v1/wecom/login/exchange`。

- [ ] **Step 1: 扩展路由测试，先创建 attempt 再交换 code**

在 `wecomViewerRoutesReturnBoundedConfigAndSessionPayloads()` 的 login exchange 前加入：

```java
WeComLoginAttemptService loginAttempts = new WeComLoginAttemptService(config);
FakeHttpExchange attemptExchange = new FakeHttpExchange("POST", "/api/v1/wecom/login/attempts");
attemptExchange.requestBodyJson("{}");
App.routeForTests(attemptExchange, config, store, viewer, loginAttempts);
assertEquals(200, attemptExchange.responseCode);
JsonObject attempt = JsonParser.parseString(attemptExchange.responseText()).getAsJsonObject();
assertContains(attemptExchange.responseText(), "\"corpId\": \"ww-test-corp\"");
assertContains(attemptExchange.responseText(), "\"redirectUri\": \"http://localhost:8099/\"");
assertNotContains(attemptExchange.responseText(), "secret");
String loginState = attempt.get("state").getAsString();
```

把原交换请求改为：

```java
FakeHttpExchange loginExchange = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
loginExchange.requestBodyJson("{\"code\":\"code-1\",\"state\":\"" + loginState + "\"}");
App.routeForTests(loginExchange, config, store, viewer, loginAttempts);
assertEquals(200, loginExchange.responseCode);
assertContains(loginExchange.responseText(), "\"wecomUserId\": \"user-1\"");
```

- [ ] **Step 2: 增加 state 重放、缺失和额外字段测试**

```java
FakeHttpExchange replayedLogin = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
replayedLogin.requestBodyJson("{\"code\":\"code-2\",\"state\":\"" + loginState + "\"}");
App.routeForTests(replayedLogin, config, store, viewer, loginAttempts);
assertEquals(403, replayedLogin.responseCode);
assertContains(replayedLogin.responseText(), "\"code\": \"FORBIDDEN\"");

FakeHttpExchange missingStateLogin = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
missingStateLogin.requestBodyJson("{\"code\":\"code-3\"}");
App.routeForTests(missingStateLogin, config, store, viewer, loginAttempts);
assertEquals(403, missingStateLogin.responseCode);

FakeHttpExchange secretAttempt = new FakeHttpExchange("POST", "/api/v1/wecom/login/attempts");
secretAttempt.requestBodyJson("{\"secret\":\"must-not-be-accepted\"}");
App.routeForTests(secretAttempt, config, store, viewer, loginAttempts);
assertEquals(400, secretAttempt.responseCode);
```

- [ ] **Step 3: 运行手工集成探针并确认先失败**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: 编译失败，指出五参数 `routeForTests` 尚不存在，或运行失败于新的 attempt 路由 404。

- [ ] **Step 4: 在 `App.startWeb` 创建并注入 attempt owner**

```java
WeComViewerService weComViewer = new WeComViewerService(config);
WeComLoginAttemptService weComLoginAttempts = new WeComLoginAttemptService(config);
```

把 `route(...)` 参数追加 `WeComLoginAttemptService weComLoginAttempts`，并在所有生产调用中传入同一个实例，保证 attempt 不因请求重建而丢失。

- [ ] **Step 5: 增加两个路由分支**

在 `js-sdk-config` 与 viewer session 路由之间加入：

```java
if ("POST".equals(method) && "/api/wecom/login/attempts".equals(weComPath)) {
    readViewerJson(exchange);
    writeJson(exchange, 200, weComLoginAttempts.createAttempt());
    return;
}
if ("POST".equals(method) && "/api/wecom/login/exchange".equals(weComPath)) {
    JsonObject body = readViewerJson(exchange, "code", "state");
    weComLoginAttempts.consume(json(body, "state"));
    writeJson(exchange, 200, weComViewer.exchangeLoginCode(json(body, "code")));
    return;
}
```

删除旧的只接受 `code` 的 exchange 分支。消费 state 必须发生在调用 `exchangeLoginCode` 之前；企业微信网络失败后用户重新扫码，不恢复已消费 state。

- [ ] **Step 6: 扩展测试入口但保留旧调用兼容**

```java
static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                          WeComViewerService weComViewer) throws Exception {
    routeForTests(exchange, config, store, weComViewer, new WeComLoginAttemptService(config));
}

static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                          WeComViewerService weComViewer,
                          WeComLoginAttemptService weComLoginAttempts) throws Exception {
    EventHub events = new EventHub();
    try {
        route(exchange, config, store, new MailSender(config), new ChatAppSender(config),
                new EmailSyncService(config), new ChatAppHistorySyncService(config),
                new WeComReceiver(config), weComViewer, weComLoginAttempts, events);
    } catch (Exception exception) {
        writeRouteError(exchange, exception);
    } finally {
        events.close();
    }
}
```

- [ ] **Step 7: 将 attempt 容量异常映射为结构化 429**

```java
if (exception instanceof WeComViewerService.RateLimitException
        || exception instanceof WeComLoginAttemptService.PendingLimitException) {
    status = 429;
    code = "RATE_LIMITED";
}
```

- [ ] **Step 8: 运行路由门禁**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: 进程退出码 0；attempt 成功，缺失/重放 state 为结构化 403，未知字段为 400，现有 viewer owner/session/event 测试继续通过。

- [ ] **Step 9: 检查敏感字段与 diff 边界**

Run:

```bash
rg -n "println.*(code|state|token)|viewerAuthToken.*query|secretAttempt" demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
```

Expected: 生产代码没有敏感日志或 URL token；测试变量名允许出现，diff check 通过。

### Task 4: 登录首屏与官方会话组件浏览器接线

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:517-730,732-880,1593-1745,1944-1958`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java:82,1269-1301`

**Interfaces:**
- Consumes: `POST /api/v1/wecom/login/attempts`、`POST /api/v1/wecom/login/exchange`、现有 viewer session 和 JS-SDK config API。
- Produces: `initWeComLogin()`、`completeWeComLogin(code)`、`enterMessageCenter()`、`currentWeComAuth()`、`returnToWeComLogin(message)`、`loadWeComJwxwork()`、`openWeComModal()`、`handleWeComComponentError()`。

- [ ] **Step 1: 更新前端回归探针**

把 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中旧的 `wecomAuthCode()` 断言替换并增加：

```java
assertContains(html, "id=\"wecomLoginScreen\"");
assertContains(html, "id=\"wwLoginPanel\"");
assertContains(html, "id=\"shell\" hidden");
assertContains(html, "function initWeComLogin()");
assertContains(html, "ww.createWWLoginPanel({");
assertContains(html, "redirect_type: 'callback'");
assertContains(html, "onLoginSuccess({ code })");
assertContains(html, "/api/v1/wecom/login/attempts");
assertContains(html, "/api/v1/wecom/login/exchange");
assertContains(html, "function currentWeComAuth()");
assertContains(html, "const WECOM_JWXWORK_SRC = 'https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js'");
assertContains(html, "binderror=\"handleMessageError\"");
assertContains(html, "handleModal({ modalUrl, modalSize })");
assertContains(html, "const WECOM_LOGIN_EXPIRED_MARKERS = ['42006','42003','40029','Missing open sid']");
assertNotContains(html, "function wecomAuthCode()");
assertNotContains(html, "new URLSearchParams(window.location.search).get('code')");
assertNotContains(html, "localStorage");
assertNotContains(html, "sessionStorage");
```

增加对初始化门禁顺序的断言：

```java
int loginInit = html.indexOf("initWeComLogin().catch");
int messageCenterInit = html.indexOf("async function enterMessageCenter()");
assertTrue(loginInit >= 0 && messageCenterInit >= 0);
assertNotContains(html, "init().catch(err => toast(err.message))");
```

- [ ] **Step 2: 运行手工探针并确认先失败**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: 失败于缺少登录首屏或仍存在 `wecomAuthCode()`。

- [ ] **Step 3: 增加登录首屏 HTML/CSS，并默认隐藏消息中心**

在 `<body>` 开头加入：

```html
<section class="wecom-login-screen" id="wecomLoginScreen">
  <div class="wecom-login-card">
    <div class="wecom-login-brand">统一消息中心</div>
    <div class="small">使用企业微信扫码后查看会话消息</div>
    <div class="wecom-login-panel" id="wwLoginPanel"><div class="empty">正在加载企业微信登录组件</div></div>
    <div class="wecom-login-status small" id="wecomLoginStatus" role="status">正在准备二维码</div>
    <button id="wecomLoginRetry" type="button" hidden>重新加载二维码</button>
  </div>
</section>
```

把消息中心根节点改为：

```html
<main class="shell" id="shell" hidden>
```

加入样式：

```css
.wecom-login-screen { min-height:100vh; display:grid; place-items:center; padding:24px; background:#f7f7f8; }
.wecom-login-screen[hidden], .shell[hidden] { display:none; }
.wecom-login-card { width:min(392px,100%); display:grid; justify-items:center; gap:14px; padding:28px 24px; background:#fff; border:1px solid var(--line); border-radius:12px; box-shadow:0 18px 50px rgba(32,33,36,.10); }
.wecom-login-brand { font-size:20px; font-weight:750; }
.wecom-login-panel { width:320px; min-height:380px; max-width:100%; overflow:hidden; }
.wecom-login-status { min-height:18px; text-align:center; }
.wecom-open-modal { width:min(960px,calc(100vw - 40px)); height:min(720px,calc(100vh - 40px)); padding:0; overflow:hidden; }
.wecom-open-modal iframe { width:100%; height:100%; border:0; display:block; }
```

- [ ] **Step 4: 扩展页面 state 和通用 API 错误对象**

加入常量：

```javascript
const WECOM_JWXWORK_SRC = 'https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js';
const WECOM_LOGIN_EXPIRED_MARKERS = ['42006','42003','40029','Missing open sid'];
let weComJwxworkLoadPromise = null;
```

在 `state` 增加：

```javascript
wecomLoginAttempt:null,
wecomAuth:null,
wecomAuthExpiresAt:0,
messageCenterInitialized:false,
eventSource:null,
refreshTimer:null
```

把 `api` 改为保留 HTTP 状态和结构化 code：

```javascript
const api = async (url, options = {}) => {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.message || data.code || data.error || response.statusText);
    error.code = data.code || '';
    error.status = response.status;
    throw error;
  }
  return data;
};
```

- [ ] **Step 5: 实现官方登录面板和同标签页授权完成**

```javascript
function setWeComLoginStatus(message, retryable = false) {
  $('wecomLoginStatus').textContent = message;
  $('wecomLoginRetry').hidden = !retryable;
}

async function initWeComLogin() {
  $('shell').hidden = true;
  $('wecomLoginScreen').hidden = false;
  $('wwLoginPanel').innerHTML = '<div class="empty">正在加载企业微信登录组件</div>';
  setWeComLoginStatus('正在准备二维码');
  const ww = await loadWeComSdk();
  const attempt = await postJson('/api/v1/wecom/login/attempts', {});
  state.wecomLoginAttempt = attempt;
  $('wwLoginPanel').innerHTML = '';
  ww.createWWLoginPanel({
    el: '#wwLoginPanel',
    params: {
      login_type: 'CorpApp',
      appid: attempt.corpId,
      agentid: attempt.agentId,
      redirect_uri: attempt.redirectUri,
      state: attempt.state,
      redirect_type: 'callback',
      panel_size: 'small',
      lang: 'zh'
    },
    onCheckWeComLogin({ isWeComLogin }) {
      setWeComLoginStatus(isWeComLogin ? '请在企业微信中确认登录' : '请使用企业微信扫码登录');
    },
    onLoginSuccess({ code }) {
      completeWeComLogin(code).catch(error => setWeComLoginStatus(`登录失败：${error.message}`, true));
    },
    onLoginFail({ errCode, errMsg }) {
      setWeComLoginStatus(`企业微信登录失败：${errMsg || errCode || '未知错误'}`, true);
    }
  });
}

async function completeWeComLogin(code) {
  const attempt = state.wecomLoginAttempt;
  if (!attempt?.state || !code) throw new Error('企业微信登录结果无效，请重新扫码');
  setWeComLoginStatus('正在进入消息中心');
  const login = await postJson('/api/v1/wecom/login/exchange', { code, state:attempt.state });
  state.wecomLoginAttempt = null;
  state.wecomAuth = login;
  state.wecomAuthExpiresAt = Date.now() + Number(login.expiresIn || 0) * 1000;
  $('wwLoginPanel').innerHTML = '';
  $('wecomLoginScreen').hidden = true;
  $('shell').hidden = false;
  await enterMessageCenter();
}

function currentWeComAuth() {
  if (!state.wecomAuth?.viewerAuthToken || Date.now() >= state.wecomAuthExpiresAt) {
    throw new Error('企业微信登录已过期，请重新扫码');
  }
  return state.wecomAuth;
}

async function returnToWeComLogin(message) {
  state.wecomAuth = null;
  state.wecomAuthExpiresAt = 0;
  state.wecomLoginAttempt = null;
  if (state.eventSource) state.eventSource.close();
  state.eventSource = null;
  if (state.refreshTimer) clearInterval(state.refreshTimer);
  state.refreshTimer = null;
  $('shell').hidden = true;
  $('wecomLoginScreen').hidden = false;
  setWeComLoginStatus(message || '请重新扫码登录');
  await initWeComLogin();
}
```

把旧 `init()` 改名为 `enterMessageCenter()`，并保护一次性结构绑定：

```javascript
async function enterMessageCenter() {
  const caps = await api('/api/channel-capabilities');
  caps.forEach(item => state.capabilities[item.channel] = item);
  state.templates = await api('/api/templates');
  await loadContacts(false);
  if (!state.messageCenterInitialized) {
    state.messageCenterInitialized = true;
    $('refreshBtn').onclick = () => refreshAll(false);
    $('syncEmailBtn').onclick = syncEmail;
    $('syncChatBtn').onclick = syncChatApp;
    $('searchInput').oninput = renderContacts;
    bindScrollSurfaces();
    $('detailToggleBtn').onclick = toggleDetailPane;
    $('editProfileBtn').onclick = openProfileModal;
    $('profileCloseBtn').onclick = closeProfileModal;
    $('profileCancelBtn').onclick = closeProfileModal;
    $('profileSaveBtn').onclick = saveContactProfile;
    $('profileNicknameInput').oninput = markProfileDirty;
    $('profileTagsInput').oninput = markProfileDirty;
    $('profileNicknameInput').onkeydown = profileEnterSave;
    $('profileTagsInput').onkeydown = profileEnterSave;
    $('profileModal').onclick = event => { if (event.target === $('profileModal')) closeProfileModal(); };
    $('previewImageCloseBtn').onclick = closeImagePreview;
    $('previewImageModal').onclick = event => { if (event.target === $('previewImageModal')) closeImagePreview(); };
    document.addEventListener('keydown', event => { if (event.key === 'Escape') closeImagePreview(); });
    $('notifyBtn').onclick = enableNotifications;
  }
  connectEvents();
  state.refreshTimer = setInterval(() => refreshAll(true), 5000);
}
```

页面末尾只启动登录：

```javascript
$('wecomLoginRetry').onclick = () => initWeComLogin()
  .catch(error => setWeComLoginStatus(error.message, true));
initWeComLogin().catch(error => setWeComLoginStatus(error.message, true));
```

- [ ] **Step 6: viewer 复用内存 token，不再交换 URL code**

把 `loadWeComViewer()` 中的登录部分改为：

```javascript
const login = currentWeComAuth();
const currentUrl = window.location.href.split('#')[0];
const config = await api('/api/v1/wecom/js-sdk-config?url=' + encodeURIComponent(currentUrl));
await ensureWeComViewerSdk(config);
const created = await postJson('/api/v1/wecom/conversation-view/sessions', {
  contactPointId: point.id,
  viewerAuthToken: login.viewerAuthToken
});
const detail = await api('/api/v1/wecom/conversation-view/sessions/' + encodeURIComponent(created.viewerSessionId), {
  headers: { 'X-WeCom-Viewer-Auth': login.viewerAuthToken }
});
await mountWeComOpenDataFrame(detail, login.viewerAuthToken);
```

彻底删除 `wecomAuthCode()`。把 `ensureWeComSdk(config)` 改名为 `ensureWeComViewerSdk(config)`，其中依次执行：

```javascript
await loadWeComSdk();
await loadWeComJwxwork();
ww.register({
  corpId: config.corpId,
  agentId: config.agentId,
  jsApiList: config.jsApiList || ['wwapp.invokeJsApiByCallInfo'],
  async getConfigSignature() { return config.configSignature; },
  async getAgentConfigSignature() { return config.agentConfigSignature; }
});
await ww.initOpenData();
```

- [ ] **Step 7: 增加 `jwxwork` 异步加载器**

```javascript
function loadWeComJwxwork() {
  if (window.WWOpenData) return Promise.resolve(window.WWOpenData);
  if (weComJwxworkLoadPromise) return weComJwxworkLoadPromise;
  weComJwxworkLoadPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    let settled = false;
    let timer;
    const fail = message => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      script.onload = null;
      script.onerror = null;
      script.remove();
      reject(new Error(message));
    };
    timer = setTimeout(() => fail('企业微信会话组件脚本加载超时'), 10000);
    script.src = WECOM_JWXWORK_SRC;
    script.async = true;
    script.onload = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(true);
    };
    script.onerror = () => fail('企业微信会话组件脚本加载失败');
    document.head.appendChild(script);
  }).catch(error => {
    weComJwxworkLoadPromise = null;
    throw error;
  });
  return weComJwxworkLoadPromise;
}
```

- [ ] **Step 8: 实现官方外部浏览器预览和模板错误回调**

在 body 中增加预览容器：

```html
<div class="modal-backdrop" id="wecomOpenModal" hidden>
  <div class="profile-modal wecom-open-modal" role="dialog" aria-modal="true" aria-label="企业微信会话详情">
    <iframe id="wecomOpenFrame" title="企业微信会话详情"></iframe>
  </div>
</div>
```

增加：

```javascript
function openWeComModal({ modalUrl, modalSize }) {
  if (!modalUrl) throw new Error('企业微信预览地址无效');
  const previewUrl = new URL(modalUrl, window.location.href);
  if (previewUrl.protocol !== 'https:') throw new Error('企业微信预览地址必须使用 HTTPS');
  const modal = $('wecomOpenModal');
  const frame = $('wecomOpenFrame');
  frame.src = previewUrl.toString();
  if (modalSize?.width) modal.firstElementChild.style.width = `${Math.min(Number(modalSize.width), 960)}px`;
  if (modalSize?.height) modal.firstElementChild.style.height = `${Math.min(Number(modalSize.height), 720)}px`;
  modal.hidden = false;
}

function closeWeComModal() {
  $('wecomOpenFrame').src = 'about:blank';
  $('wecomOpenModal').hidden = true;
}

function isWeComLoginExpired(error) {
  const detail = error?.detail || error || {};
  const text = `${detail.errCode || ''} ${detail.errMsg || ''} ${detail.message || error?.message || ''}`;
  return WECOM_LOGIN_EXPIRED_MARKERS.some(marker => text.includes(marker));
}

function handleWeComComponentError(error, detail, viewerAuthToken) {
  if (isWeComLoginExpired(error)) {
    returnToWeComLogin('企业微信登录已失效，请重新扫码')
      .catch(loginError => setWeComLoginStatus(loginError.message, true));
    return;
  }
  const container = $('wecomViewerContainer');
  if (container) container.innerHTML = '<div class="empty">企业微信组件渲染失败</div>';
  reportWeComViewerEvent('component_error', detail.viewerSessionId, viewerAuthToken);
}
```

OpenDataFrame 改为：

```javascript
const factory = ww.createOpenDataFrameFactory();
let componentErrorReported = false;
factory.createOpenDataFrame({
  el: container,
  template: `
    <view wx:for="{{data.msgList}}" wx:key="msgid" class="msg">
      <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}"
        open-type="viewMessage" binderror="handleMessageError" />
    </view>
  `,
  style: `.msg { height: 100%; overflow: auto; }`,
  data: { msgList: detail.messages },
  methods: {
    handleMessageError(error) {
      if (componentErrorReported && !isWeComLoginExpired(error)) return;
      componentErrorReported = true;
      handleWeComComponentError(error, detail, viewerAuthToken);
    }
  },
  handleModal({ modalUrl, modalSize }) {
    openWeComModal({ modalUrl, modalSize });
    return false;
  },
  error(error) {
    if (componentErrorReported && !isWeComLoginExpired(error)) return;
    componentErrorReported = true;
    handleWeComComponentError(error, detail, viewerAuthToken);
  }
});
```

给 `wecomOpenModal` backdrop click 和 Escape 绑定 `closeWeComModal()`。组件错误事件仍由服务端的一次性 viewed-session 消费阻断重复上报；前端不新增第二套重放状态。

- [ ] **Step 9: 修正 SSE 生命周期，避免重新登录时后台继续拉取**

```javascript
function connectEvents() {
  if (state.eventSource) state.eventSource.close();
  try {
    state.eventSource = new EventSource('/events');
    state.eventSource.onmessage = async () => { toast('有新消息'); await refreshAll(true); };
  } catch (error) {
    state.eventSource = null;
  }
}
```

`enterMessageCenter()` 创建刷新 timer 前先清理旧 timer，确保重登不会叠加轮询。

- [ ] **Step 10: 运行前端静态回归和 Java 门禁**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q test
```

Expected: 所有命令退出 0；静态探针确认没有 URL code、storage token 或同步 CDN script。该结果不等于浏览器截图验收。

- [ ] **Step 11: 检查页面敏感数据和 parser-blocking script**

Run:

```bash
rg -n "URLSearchParams.*code|viewerAuthToken=.*[?&]|localStorage|sessionStorage|<script src=\"https://(wwcdn|open.work.weixin.qq.com)" demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
```

Expected: `rg` 无输出；所有官方脚本均由异步 loader 创建；diff check 通过。

### Task 5: OpenAPI、配置文档和完整验收

**Files:**
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml:496-548,1384-1410`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs:1-28,195-218`
- Modify: `demo/message-center-demo/config.example.env:105-118`
- Modify: `demo/message-center-demo/README.md:153-176,194-204`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`
- Verify: all task files and real browser behavior

**Interfaces:**
- Consumes: 已完成的 runtime HTTP 和页面合同。
- Produces: 25-operation OpenAPI、可复制配置说明、JDK 17 门禁和真实浏览器证据。

- [ ] **Step 1: 先扩展 OpenAPI 合同测试**

在 `requiredPaths` 加入：

```javascript
'/api/v1/wecom/login/attempts',
```

在企业微信断言区加入：

```javascript
const wecomAttempt = operation(operations, 'post', '/api/v1/wecom/login/attempts');
assert.match(wecomAttempt.body, /operationId: createWeComLoginAttempt/);
assert.match(wecomAttempt.body, /^      security: \[\]$/m);
assert.match(schema(contract, 'WeComLoginAttemptResponse'), /required: \[corpId, agentId, redirectUri, state, expiresIn\]/);
assert.doesNotMatch(schema(contract, 'WeComLoginAttemptResponse'), /secret|access_token|ticket|signature|viewerAuthToken/i);

const wecomExchange = operation(operations, 'post', '/api/v1/wecom/login/exchange');
assert.match(wecomExchange.body, /^      security: \[\]$/m);
assert.match(schema(contract, 'WeComLoginExchangeRequest'), /required: \[code, state\]/);
assert.match(schema(contract, 'WeComLoginExchangeRequest'), /^        state:$/m);
assert.equal(operations.length, 25);
```

- [ ] **Step 2: 运行合同测试并确认先失败**

Run:

```bash
cd demo/message-center-demo
node contracts/openapi/message-center-v1.test.mjs
```

Expected: 失败于缺少 `/api/v1/wecom/login/attempts` 或 operation 数量仍为 24。

- [ ] **Step 3: 在 OpenAPI 新增匿名 attempt operation**

```yaml
  /api/v1/wecom/login/attempts:
    post:
      operationId: createWeComLoginAttempt
      description: Create one bounded short-lived state for the official WeCom browser login panel without returning secrets, tickets, signatures, or viewer tokens.
      tags:
        - Channels
      security: []
      parameters:
        - $ref: '#/components/parameters/TraceRequestId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              maxProperties: 0
      responses:
        '200':
          description: Short-lived official WeCom login panel configuration and one-time state
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WeComLoginAttemptResponse'
        '4XX':
          $ref: '#/components/responses/ApiErrorResponse'
        default:
          $ref: '#/components/responses/ApiErrorResponse'
```

将 login exchange 标为匿名扫码完成入口，并保留服务端 state 校验：

```yaml
      security: []
```

`WeComLoginExchangeRequest` 改为：

```yaml
    WeComLoginExchangeRequest:
      type: object
      required: [code, state]
      additionalProperties: false
      properties:
        code:
          type: string
          minLength: 1
          maxLength: 512
        state:
          type: string
          minLength: 16
          maxLength: 128
```

新增：

```yaml
    WeComLoginAttemptResponse:
      type: object
      required: [corpId, agentId, redirectUri, state, expiresIn]
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
        redirectUri:
          type: string
          format: uri
          minLength: 1
          maxLength: 2048
        state:
          type: string
          minLength: 16
          maxLength: 128
        expiresIn:
          type: integer
          minimum: 30
          maximum: 600
```

- [ ] **Step 4: 运行 OpenAPI 合同测试**

Run:

```bash
cd demo/message-center-demo
node contracts/openapi/message-center-v1.test.mjs
```

Expected: 输出 `validated 25 OpenAPI operations`。

- [ ] **Step 5: 更新示例配置**

在企业微信配置块加入：

```env
WECOM_LOGIN_REDIRECT_URI=http://localhost:8099/
WECOM_LOGIN_ATTEMPT_TTL_SECONDS=300
WECOM_LOGIN_MAX_PENDING=256
```

紧邻配置增加注释：真实扫码和会话组件验收应把 redirect URI 与 allowlist 同时改为企业微信后台已配置的同一公网 HTTPS 域名；`http://localhost` 只用于本地开发错误态和合同测试。

- [ ] **Step 6: 更新 README 的配置与真实流程**

README 必须明确列出：

```text
POST /api/v1/wecom/login/attempts
POST /api/v1/wecom/login/exchange  body={code,state}
GET  /api/v1/wecom/js-sdk-config
POST /api/v1/wecom/conversation-view/sessions
GET  /api/v1/wecom/conversation-view/sessions/{viewerSessionId}
POST /api/v1/wecom/conversation-view/events
```

把“从当前 URL 读取 code”和“仅点击 viewer 才加载 JSSDK”的旧描述替换为：首屏异步加载 JSSDK 并用 `createWWLoginPanel` 显示二维码；扫码后同页交换一次 code；点击 viewer 时复用内存 token，并额外懒加载 `jwxwork`、注册签名和 OpenDataFrame。明确 token 不持久化，刷新后重新扫码，且这不是完整 CRM 全局认证。

- [ ] **Step 7: 回写 viewer 设计文档**

在 `2026-07-27-wecom-conversation-viewer-design.md` 的前端、授权和验收章节同步：

- 普通浏览器的 viewer 前置是官方 `createWWLoginPanel`，不是 URL code。
- 登录与 viewer 同域、top frame。
- `jwxwork-1.0.0.js`、`handleModal`、`binderror`。
- 四类登录失效错误返回扫码首屏。
- 静态字符串探针不是截图验收。

- [ ] **Step 8: 执行完整 JDK 17 门禁**

Run:

```bash
java -version
cd demo/message-center-demo
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
cd ../..
git diff --check
```

Expected:

- `java -version` 显示 17。
- Maven 四条命令退出码均为 0。
- OpenAPI 输出 `validated 25 OpenAPI operations`。
- `git diff --check` 无输出。

- [ ] **Step 9: 启动真实服务并记录唯一 PID**

在 `demo/message-center-demo` 使用现有 `.env` 和 JDK 17 启动 8107；如果需要系统权限，直接申请用户手动审批。启动后只读确认：

```bash
curl -sS --max-time 5 -o /dev/null -w '%{http_code}\n' http://localhost:8107/
```

Expected: HTTP 200。记录新 Java PID；后续如需停止，只终止该明确 PID，不使用 `killall` 或模糊匹配。

- [ ] **Step 10: 验证登录前 API 门禁和真实二维码**

用 Safari 或可用真实浏览器访问实际配置域名：

- 首屏只显示登录卡片和真实企业微信二维码。
- Network 面板在扫码前不得出现 contacts、threads、templates、events 或 sync 请求。
- SDK 加载时页面可见，10 秒超时显示可重试错误，不出现白屏。
- 地址栏始终不出现 code、state 或 viewer token。

保存桌面 viewport 截图：登录加载态、二维码态、扫码失败/重试态。

- [ ] **Step 11: 完成真实扫码和 viewer 交互验收**

用户使用企业微信扫码后验证：

- 当前标签页直接显示现有消息中心。
- 联系人数量和最近 10 条时间线仍正常。
- 点击联系人、企业微信 tab、打开 viewer 后出现真实加载态。
- 成功渲染真实 `ww-open-message`。
- 点击图片、视频或聊天记录详情时，通过 `handleModal` 打开 iframe 预览。
- 组件错误显示错误态并写入一次审计；重复上报仍被服务端拒绝。
- 模拟或实际遇到登录失效错误时清除内存 token并回到扫码层。

保存桌面截图：消息中心、企业微信 tab、viewer 加载态、viewer 成功态、预览态、错误态。

- [ ] **Step 12: 补移动 viewport 验收**

在不嵌套业务 iframe 的前提下，把浏览器 viewport 调整到约 `390x844`：

- 登录卡片和 320px 二维码不横向溢出。
- 扫码后单列消息中心可滚动。
- 企业微信 viewer、错误态和预览层不超出 viewport。

保存移动 viewport 的登录态、消息中心和 viewer 截图。不得用源码字符串、DOM 探针或网络响应文本替代截图。

- [ ] **Step 13: 最终 git 边界复核**

Run:

```bash
git status --short
git diff --name-only
git diff -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/contracts/openapi/message-center-v1.yaml demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs demo/message-center-demo/config.example.env demo/message-center-demo/README.md docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md docs/superpowers/plans/2026-07-28-wecom-browser-scan-login.md
```

Expected: 不存在 staged 文件；任务 diff 不包含 Email、139 IMAP、模板、SDK `.project` 等无关改动；不执行提交。

## Completion Evidence

只有同时具备以下证据才能声明本轮完成：

- JDK 17 的 Maven test、test-compile、手工集成探针和 package 均通过。
- OpenAPI 验证 25 个 operation。
- attempt 的 TTL、容量、重放和审计测试通过。
- 登录前没有消息中心数据请求。
- 真实浏览器显示官方二维码并完成扫码。
- 同标签页进入消息中心并渲染真实 `ww-open-message`。
- `handleModal`、`binderror`、登录失效返回扫码层有真实交互证据。
- 桌面与移动 viewport 截图齐全。
- `git diff --check` 通过且无 staged/commit。

## Stop Conditions

- 需要强制关闭用户 Safari。
- 需要数据库依赖、账号持久绑定、权限模型变化或公开主合同重构。
- 企业微信后台域名、应用可见范围或“使用会话展示组件”授权缺失，且本地代码无法补偿。
- 官方 API 行为与上述四份官方文档不一致；先重新申请网络访问并给出官方证据。
- 当前脏工作区中的用户改动与本任务必须修改的同一代码块发生无法安全合并的冲突。
