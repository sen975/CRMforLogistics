# WeCom Developed App Access Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 8107 按企业微信官方代开发模型，以授权企业 CorpID 和回调所得 `permanent_code`（代开发应用 Secret）调用 `/cgi-bin/gettoken`，不再因误用第三方 `get_corp_token` 返回 48002。

**Architecture:** `WeComAuthorizationStore` 继续作为授权安装及加密凭据的唯一 owner，现有 JSONL 和密文结构不迁移。`WeComAccessTokenService` 只把 resolved installation 投影成 `authCorpId + developedAppSecret`，由 `WeComAuthorizationGateway` 执行有界 GET 并沿用当前安全错误诊断。登录 Suite、授权回调、viewer、专区同步和公钥注册继续消费同一个 token service。

**Tech Stack:** Java 17、JDK `HttpClient`、Gson、JUnit 5、Maven、JSONL

## Global Constraints

- 只支持当前产品主线中的服务商代开发安装，不新增第三方应用运行模式配置。
- 现有 `permanentCodeEncrypted` 和密文键 `permanentCode` 不迁移；官方字段名仍是 `permanent_code`。
- 不记录 CorpSecret、access token、完整 `errmsg`、suite ticket 或 permanent code。
- 不修改公开 HTTP API、OpenAPI operation 数量、权限模型或数据库。
- 使用 JDK 17；产物字节码 major version 必须为 61。
- 不执行 `git add` 或 `git commit`。

---

### Task 1: 代开发 token 网关合同

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`

**Interfaces:**
- Consumes: `authCorpId`、代开发应用 Secret、`Duration timeout`
- Produces: `CorpTokenResponse getDevelopedAppToken(String authCorpId, String developedAppSecret, Duration timeout)`

- [ ] **Step 1: 写失败测试**

新增本地 HTTP server 用例，只注册 `/cgi-bin/gettoken`，捕获请求 method 和 raw query：

```java
WeComAuthorizationGateway.CorpTokenResponse response =
        gateway.getDevelopedAppToken("ww-corp", "developed-secret", Duration.ofSeconds(2));
assertEquals("GET", method.get());
assertEquals("corpid=ww-corp&corpsecret=developed-secret", query.get());
assertEquals("corp-token", response.accessToken());
```

另加 `errcode=48002, errmsg="api forbidden, hint: [fresh123]"` 用例，断言路径为 `/cgi-bin/gettoken` 且 `upstreamHint=fresh123`，异常消息不含 Secret。

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComAuthorizationGatewayTest test
```

Expected: FAIL，因为 `getDevelopedAppToken` 尚不存在。

- [ ] **Step 3: 实现有界 GET**

在 `WeComAuthorizationGateway` 增加：

```java
public CorpTokenResponse getDevelopedAppToken(
        String authCorpId, String developedAppSecret, Duration timeout)
        throws WeComAuthorizationException {
    require(authCorpId, "authCorpId", 128);
    require(developedAppSecret, "developedAppSecret", 512);
    long deadline = deadline(timeout);
    String path = "/cgi-bin/gettoken";
    JsonObject body = getJson(path + "?corpid=" + encode(authCorpId)
            + "&corpsecret=" + encode(developedAppSecret), remaining(deadline));
    return new CorpTokenResponse(successString(body, "access_token", path),
            positiveInt(body, "expires_in", 7200));
}
```

保留现有响应大小、超时、错误码和 hint 上界；不得打印完整 URI。

- [ ] **Step 4: 运行测试确认绿灯**

Run: `mvn -q -Dtest=WeComAuthorizationGatewayTest test`

Expected: PASS。

### Task 2: Token owner 接线

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAccessTokenServiceTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAccessTokenService.java`

**Interfaces:**
- Consumes: `ResolvedInstallation.installation().authCorpId()`、`ResolvedInstallation.permanentCode()`
- Produces: 按 `installationId + version` 缓存的代开发应用 access token

- [ ] **Step 1: 写失败测试**

让 provider 捕获 `corpId` 与 `developedAppSecret`：

```java
assertEquals("token-1", service.accessToken(installation(1)));
assertEquals("ww-corp", requestedCorpId.get());
assertEquals("developed-secret", requestedSecret.get());
```

增加构造器接线集成测试：真实 `WeComAuthorizationGateway` 只允许访问本地 `/cgi-bin/gettoken`；若访问 `/cgi-bin/service/get_corp_token` 或 `/cgi-bin/service/get_suite_token` 则测试失败。

- [ ] **Step 2: 运行测试确认红灯**

Run: `mvn -q -Dtest=WeComAccessTokenServiceTest test`

Expected: 构造器仍绑定 `gateway::getCorpToken`，测试 FAIL。

- [ ] **Step 3: 修正生产接线和命名**

```java
public WeComAccessTokenService(Config config, WeComAuthorizationGateway gateway) {
    this(config, Clock.systemUTC(), gateway::getDevelopedAppToken);
}
```

将内部 provider 参数从 `permanentCode` 改名为 `developedAppSecret`，保持序列化字段和公开授权回调字段不变。

- [ ] **Step 4: 运行 token 与下游测试**

Run:

```bash
mvn -q -Dtest=WeComAccessTokenServiceTest,WeComChatDataGatewayTest,WeComChatDataPublicKeyRegistrarTest,WeComViewerServiceTest test
```

Expected: PASS，缓存版本、专区同步、公钥注册和 viewer 均不回退。

### Task 3: 文档与发布门禁

**Files:**
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`
- Modify: `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md`

**Interfaces:**
- Consumes: 官方文档 97163、97164
- Produces: 部署者可执行的代开发凭证说明

- [ ] **Step 1: 修正文档**

README 明确：

```text
permanent_code 在代开发模型中就是应用 Secret。
8107 使用 authCorpId + permanent_code 调用 /cgi-bin/gettoken。
现有安装 JSONL 无需重导入或重新授权。
```

删除“`permanent_code` 不作为 `corpsecret`”错误表述。

- [ ] **Step 2: 跑完整门禁**

```bash
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
git diff --check
```

Expected: 全部 exit 0，OpenAPI 保持 28 operations。

- [ ] **Step 3: 生成发布 JAR**

```bash
cp target/message-center-demo-0.1.0.jar release/message-center/app/message-center.jar
sha256sum target/message-center-demo-0.1.0.jar release/message-center/app/message-center.jar
```

Expected: 两个 SHA256 完全一致；`App.class` major version 为 61。

- [ ] **Step 4: Git 边界复核**

Run: `git status --short`

Expected: 不暂存、不提交，不覆盖用户既有 Email、ChatApp、模板、IMAP 或其他未提交改动。
