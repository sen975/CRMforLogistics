# WeCom ChatData Public Key Auto-Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让接收真实 `suite_ticket` 的 8107 进程在回调之外异步完成 `chatdata/set_public_key`，从而解除会话展示组件读取真实消息前的公钥注册阻断。

**Architecture:** `WeComAuthorizationService` 只在 ticket 入内存、授权安装成功后向一个容量为 1 的合并式 registrar 发出信号，绝不等待企业微信 HTTP。registrar 是公钥自动注册的唯一 owner：从现有 PKCS#8 RSA-2048 私钥派生 X.509 PEM 公钥，获取目标授权企业 access token，调用官方接口，并以企业、版本、公钥摘要为幂等键原子写脱敏状态文件。

**Tech Stack:** Java 17、JDK `HttpClient`、JCA RSA、Gson、JUnit 5、Maven。

## Global Constraints

- 默认关闭：`WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER=false`。
- 只处理 `WECOM_LOGIN_AUTH_CORP_ID` 唯一指定的授权企业，不引入数据库或公开 API。
- 回调响应不等待 suite token、corp token 或 `set_public_key` 网络请求。
- 状态文件不得保存 ticket、token、permanent code、私钥或完整公钥。
- 私钥继续执行 Linux owner-only 权限与 RSA-2048 校验。
- 上游失败不写成功状态、不污染授权安装状态；等待下一次 ticket 或安装事件重试。
- 保留工作区现有 ChatApp 与每日摘要未提交改动，不提交、不暂存。

---

### Task 1: 配置与密钥材料合同

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataCrypto.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataCryptoTest.java`

**Interfaces:**
- Produces: `boolean wecomChatDataPublicKeyAutoRegister()`、`Path wecomChatDataPublicKeyRegistrationFile()`、`PublicKeyMaterial publicKeyMaterial()`。

- [ ] **Step 1: 写失败测试**

```java
assertFalse(new Config(Map.of()).wecomChatDataPublicKeyAutoRegister());
assertEquals(tempDir.resolve("registration.json"), configured.wecomChatDataPublicKeyRegistrationFile());
assertTrue(crypto.publicKeyMaterial().pem().startsWith("-----BEGIN PUBLIC KEY-----"));
assertEquals(2048, crypto.publicKeyMaterial().bitLength());
```

- [ ] **Step 2: 运行测试确认因接口不存在而失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComChatDataCryptoTest test`

Expected: 编译失败，缺少上述配置和公钥材料方法。

- [ ] **Step 3: 最小实现配置和公钥派生**

```java
public boolean wecomChatDataPublicKeyAutoRegister() {
    return strictBoolean("WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER", false);
}

public Path wecomChatDataPublicKeyRegistrationFile() {
    return dataFile("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE",
            "wecom-chatdata-public-key-registration.json");
}
```

使用 `RSAPrivateCrtKey` 的 modulus 与 public exponent 构造 `RSAPublicKeySpec`，输出 X.509 PEM 和 SHA-256 摘要；非 CRT RSA 私钥失败关闭。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComChatDataCryptoTest test`

Expected: PASS。

### Task 2: 官方 set_public_key 网关与幂等状态

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyGateway.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrationStore.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyGatewayTest.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrationStoreTest.java`

**Interfaces:**
- Consumes: 授权企业 access token、PEM 公钥、正整数版本。
- Produces: `register(String accessToken, String publicKeyPem, int version)` 与 `isRegistered/markRegistered`。

- [ ] **Step 1: 写失败测试**

```java
gateway.register("corp-token", pem, 1);
assertEquals("access_token=corp-token", requestQuery.get());
assertEquals(1, JsonParser.parseString(requestBody.get()).getAsJsonObject()
        .get("public_key_ver").getAsInt());
```

状态测试覆盖同企业/版本/摘要命中、不同版本或摘要未命中、超大/非法状态拒绝、原子替换后只含四个脱敏字段。

- [ ] **Step 2: 运行测试确认类不存在而失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyGatewayTest,WeComChatDataPublicKeyRegistrationStoreTest test`

Expected: 编译失败，缺少网关和状态 store。

- [ ] **Step 3: 最小实现网关与状态 store**

网关使用 10 秒 connect/request timeout、1 MiB 响应上限、结构化异常，调用 `/cgi-bin/chatdata/set_public_key?access_token=...`。状态 store 读取上限 4096 字节，临时文件与 `ATOMIC_MOVE`/`REPLACE_EXISTING` 发布：

```json
{"authCorpId":"ww...","publicKeyVersion":1,"publicKeySha256":"...","registeredAt":"..."}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyGatewayTest,WeComChatDataPublicKeyRegistrationStoreTest test`

Expected: PASS。

### Task 3: 合并式异步 registrar 与授权事件接线

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrar.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrarTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: `requestRegistration()` 信号。
- Produces: 容量 1 的后台注册循环和可排空 `close()`。

- [ ] **Step 1: 写失败测试**

测试 ticket 回调在阻塞 registrar 下立即返回；ticket 先到、安装后到和安装先到、ticket 后到均触发；繁忙期间多次信号最多保留一次；成功状态跳过；上游失败后下次信号重试；失败不修改 installation 状态。

- [ ] **Step 2: 运行测试确认接口不存在而失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyRegistrarTest,WeComAuthorizationServiceTest test`

Expected: 编译失败或新行为断言失败。

- [ ] **Step 3: 最小实现与接线**

```java
gateway.acceptSuiteTicket(callback.suiteId(), callback.suiteTicket(), callback.timestamp());
publicKeyRegistration.requestRegistration();
yield CallbackAck.accepted();
```

在 `create_auth/change_auth` 成功 `upsertActive` 后再次发信号。信号使用容量 1 队列合并，worker 只解析配置指定企业；任何注册失败只等待下一次外部触发。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyRegistrarTest,WeComAuthorizationServiceTest test`

Expected: PASS。

### Task 4: Runtime 生命周期、部署文档与全门禁

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`

**Interfaces:**
- Consumes: 现有 authorization store/gateway/access-token service 与私钥配置。
- Produces: 默认关闭、显式启用的 8107 runtime 接线。

- [ ] **Step 1: 接线并更新部署配置**

`App` 先构造 access token service 和 registrar，再构造 authorization service；shutdown 时先停止授权事件进入，再排空 registrar。示例增加：

```env
WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER=false
WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE=wecom-chatdata-public-key-registration.json
```

README 改为启用自动注册并等待下一次真实 `suite_ticket`，成功后检查脱敏状态文件；无需重传专区镜像，只替换 8107 JAR。

- [ ] **Step 2: 运行完整门禁**

Run: `cd demo/message-center-demo && mvn -q test`

Run: `cd demo/message-center-demo && mvn -q test-compile`

Run: `cd demo/message-center-demo && mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java`

Run: `cd demo/message-center-demo && mvn -q -DskipTests package`

Run: `node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

Run: `git diff --check`

Expected: 全部 exit 0；OpenAPI operation 数量保持当前合同值；无 whitespace error。

- [ ] **Step 3: 复核 git 边界**

Run: `git status --short && git diff --stat && git diff -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

Expected: 本任务改动与原有 ChatApp 配置改动都保留且可逐段辨认；无暂存和提交。

### Task 5: 自动注册启动降级与密钥懒加载

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrar.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataPublicKeyRegistrarTest.java`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`

**Interfaces:**
- Consumes: `Config` 中的私钥路径与公钥版本，以及现有 `requestRegistration()` 外部信号。
- Produces: `PublicKeyMaterialProvider.load()` 懒加载边界；`open()` 在自动注册配置或私钥错误时仍返回可关闭的降级 registrar，不阻断 `App.startWeb()`。

- [x] **Step 1: 写失败测试**

新增测试覆盖：

```java
assertDoesNotThrow(() -> WeComChatDataPublicKeyRegistrar.open(configWithMissingKey, store, accessTokens));
```

并通过可计数 `PublicKeyMaterialProvider` 断言：构造 registrar 时不加载密钥；第一次信号加载失败只记录脱敏失败事件且不写成功状态；补齐条件后的下一次信号重新加载并成功注册；首次成功后缓存材料，后续信号不重复加载。

- [x] **Step 2: 运行测试确认当前同步加载行为失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyRegistrarTest test`

Expected: `open()` 因 `WECOM_CHATDATA_DECRYPT_FAILED` 抛出，或缺少懒加载测试接口而编译失败。

- [x] **Step 3: 最小实现懒加载和启动降级**

将 registrar 的不可变 `PublicKeyMaterial` 改为 provider 与成功缓存：

```java
private WeComChatDataCrypto.PublicKeyMaterial material() throws Exception {
    WeComChatDataCrypto.PublicKeyMaterial current = cachedMaterial;
    if (current != null) return current;
    current = materialProvider.load();
    cachedMaterial = current;
    return current;
}
```

`open()` 只创建 provider、状态 store、gateway 和 worker，不读取私钥；授权 store、access-token owner、suite/corp 配置不完整时返回降级 registrar并写脱敏失败事件。worker 每次外部信号调用 `material()`，失败后不缓存，等待下一次 `suite_ticket` 或授权安装事件重试。任何失败不得修改安装 `ACTIVE` 状态。

- [x] **Step 4: 运行针对性测试确认通过**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataPublicKeyRegistrarTest,WeComAuthorizationServiceTest,WeComChatDataCryptoTest test`

Expected: PASS；测试日志不得包含 ticket、access token、permanent code 或私钥正文。

- [x] **Step 5: 更新运行语义文档并执行全门禁**

README 和 viewer design 必须明确：`WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER=true` 不再使网页启动依赖私钥；私钥或授权依赖错误时仅自动注册降级；修复配置后等待下一次真实事件重试；viewer 在公钥未成功注册前不可用，其他消息中心能力继续运行。

Run: `cd demo/message-center-demo && mvn -q test`

Run: `cd demo/message-center-demo && mvn -q test-compile`

Run: `cd demo/message-center-demo && mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java`

Run: `cd demo/message-center-demo && mvn -q -DskipTests package`

Run: `node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

Run: `git diff --check`

Expected: 全部 exit 0；OpenAPI operation 数量保持当前合同值；无 whitespace error；不暂存、不提交。

### Task 6: JDK 17 授权响应体总体超时

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`

**Interfaces:**
- Consumes: `postJson(..., Duration timeout)` 的剩余总体超时和 1 MiB 响应上限。
- Produces: JDK 17 下覆盖请求头、响应头和完整响应体的同一 deadline；超时、超大响应和中断继续映射为 `WECOM_UPSTREAM_UNAVAILABLE`。

- [x] **Step 1: 运行既有失败测试确认 JDK 17 缺陷**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -q test`

Observed: `boundsSuiteTokenResponseBodyByTheOverallDeadline` 与 `boundsCorpTokenResponseBodyByTheOverallDeadline` 超过 1 秒；栈停在 `BodyHandlers.ofInputStream()` 返回后的 `readNBytes()`。

- [x] **Step 2: 使用有界异步响应体实现总体 deadline**

`postJson` 使用 `sendAsync(request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES))`，并仅等待传入 `timeout`：

```java
CompletableFuture<HttpResponse<byte[]>> responseFuture = client.sendAsync(
        request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
HttpResponse<byte[]> response = responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
```

超时或中断必须取消 future；中断必须恢复线程标志。subscriber 在超过 1 MiB 时取消 subscription 并异常完成，不缓存或记录响应正文。

- [x] **Step 3: 用 JDK 17 运行授权网关测试和全门禁**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -q -Dtest=WeComAuthorizationGatewayTest test`

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -q test`

随后重跑 Task 5 的其余编译、回归、打包、OpenAPI 和 diff 门禁。Expected: 全部 exit 0；不暂存、不提交。
