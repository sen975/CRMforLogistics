# 企业微信代开发授权安装记录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信认证配置的唯一 owner 改为持久化的代开发授权安装记录，使登录首屏能唯一选择授权企业，并让扫码登录、JS-SDK 和会话展示组件始终使用同一安装上下文。

**Architecture:** 新增 `WeComAuthorizationStore` 作为本地加密 JSONL 安装记录 owner；`WeComCallbackCodec` 和授权服务处理官方 suite 回调、永久码和授权信息；登录 attempt、viewer token/session、access-token/jsapi-ticket 缓存均绑定 `installationId + version`。HTTP 层只做协议映射，保留现有 viewer 权限、一次性 session、限流和审计语义。

**Tech Stack:** JDK 17、Java `HttpServer`、Gson 2.11、JUnit 5、现有 `CredentialCipher` AES-256-GCM、原生 HTML/JavaScript、企业微信 `@wecom/jssdk 2.3.4`、OpenAPI 3.1、Node.js 合同测试。

## Global Constraints

- 始终使用 JDK 17；Maven source/target、编译和运行验证均为 17。
- 代开发服务商配置只来自 `WECOM_SUITE_ID`、`WECOM_SUITE_SECRET`、`WECOM_TOKEN`、`WECOM_ENCODING_AES_KEY`；安装级 `authCorpId`、AgentID、加密 `permanent_code` 只来自 `WeComAuthorizationStore`。
- `WECOM_AGENT_ID`、`WECOM_SECRET` 不得作为代开发认证回退；不得按文件顺序、最新时间或匿名请求 CorpID 猜测企业。
- 默认安装文件为 `data/wecom-authorization-installations.jsonl`，本地首版只支持单机、单 JVM writer，不引入数据库。
- 安装文件任意非空损坏行、密文无法解密、原子替换失败、缺少 active 安装或上游凭证不可用都必须失败关闭，并返回结构化错误。
- 不记录 permanent code、suite ticket、access token、登录 code、viewer token、ticket、签名或 secretKey；suite ticket 和 token 只在内存缓存。
- 登录首屏必须由必填 `WECOM_LOGIN_AUTH_CORP_ID` 唯一确定授权企业；登录、JS-SDK 和 viewer 必须同一 HTTPS 域名与 top frame。
- 不修改消息权限模型，不实现完整 CRM 账号体系、持久登录、账号绑定、多企业选择器或会话内容同步。
- 本工作区已有 Email、139 IMAP、模板和 SDK `.project` 改动；禁止整体回滚、格式化、`git add .` 或提交。本轮每个任务用针对性测试和 `git diff --check` 代替提交。
- 需要真实企业微信网络、启动 JDK 服务或 Safari WebDriver 时申请用户批准的系统权限；不得强制关闭用户 Safari。

---

## 文件职责与接口地图

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationStore.java` — 安装记录模型、JSONL 解析校验、AES-GCM 解密、串行读改写和原子发布。
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationStoreTest.java` — 新建、更新、撤销、重启恢复、损坏、并发和原子写入失败测试。
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComCallbackCodec.java` — 企业微信回调 SHA-1 验签、AES-CBC/PKCS#7 解密和严格 XML 字段解析。
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComCallbackCodecTest.java` — 官方布局、签名、receiveId、XML 安全和边界测试。
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java` — suite token、permanent code、auth info 的 HTTP 客户端及有界响应。
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java` — suite_ticket、create_auth、change_auth、cancel_auth 事件编排和有界 worker。
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java` — 事件顺序、队列背压、重复事件、撤销和失败关闭测试。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java` — suite/安装文件/目标 CorpID/回调队列配置及校验。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java` — 新配置默认值、边界和 HTTPS/origin 校验。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java` — attempt 保存安装 ID 与版本并精确解析 active 安装。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java` — 安装选择、版本变化、撤销和重放测试。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java` — gateway、token、session、detail 和 JS-SDK 全部绑定安装上下文。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java` — 正式授权回调路由、登录 exchange 绑定、结构化异常映射和依赖装配。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java` — 回调/登录 HTTP 路由回归测试。
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml` — 新增正式授权 callback operation。
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs` — operation 数、参数、错误 envelope 和敏感字段断言。
- Modify: `demo/message-center-demo/config.example.env`、`demo/message-center-demo/README.md` — 代开发安装、suite 回调、扫码登录配置和排障。
- Modify: `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md`、`docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md` — 删除静态 AgentID/Secret 冲突描述并链接新真源。

### Task 1: 配置合同与结构化错误基础

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationException.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationExceptionTest.java`

**Interfaces:**
- Produces `String wecomSuiteId()`、`String wecomSuiteSecret()`、`String wecomToken()`、`String wecomEncodingAesKey()`、`Path wecomAuthorizationInstallationsFile()`、`String wecomLoginAuthCorpId()`、`int wecomAuthorizationQueueCapacity()`。
- `WeComAuthorizationException(String code, int httpStatus, String message, Throwable cause)` 暴露 `code()`、`httpStatus()`，消息不得包含凭据值。

- [ ] **Step 1: 写失败测试**：构造最小 `Config`，断言 suite 配置、默认 JSONL 路径和 1..64 队列容量；断言缺少 `WECOM_LOGIN_AUTH_CORP_ID`、非 HTTPS `WECOM_ALLOWED_JSAPI_ORIGINS`、错误 AES key 长度抛 `IllegalArgumentException`；断言异常序列化只包含 code/status/message。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComAuthorizationExceptionTest test`

Expected: 编译失败，新的 getter 和异常类型尚不存在。
- [ ] **Step 3: 最小实现**：沿用现有 `Config.value`/`boundedInt`，加入配置 key、长度上限和 URI/origin 校验；新增固定错误码常量和不可变异常类。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComAuthorizationExceptionTest test`

Expected: 全部通过，0 failures。
- [ ] **Step 5: 边界检查**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationException.java`

Expected: 无空白错误，未触碰无关配置。

### Task 2: 加密 JSONL 安装记录 owner

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationStore.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationStoreTest.java`

**Interfaces:**
- `record Installation(String installationId, String suiteId, String authCorpId, String agentId, String permanentCodeEncrypted, AuthStatus authStatus, Instant authorizedAt, Instant updatedAt, Instant lastSuiteTicketAt, long version)`。
- `enum AuthStatus { ACTIVE, REVOKED, FAILED }`。
- `Optional<Installation> find(String suiteId, String authCorpId)`、`Installation requireActive(String suiteId, String authCorpId)`、`Installation upsertActive(String suiteId, String authCorpId, String agentId, String permanentCode)`、`Installation updateStatus(String suiteId, String authCorpId, AuthStatus status)`、`ResolvedInstallation resolveActive(String suiteId, String authCorpId)`。
- `record ResolvedInstallation(Installation installation, String permanentCode)`；调用方在使用后立即丢弃明文，异常和审计不得携带它。

- [ ] **Step 1: 写失败测试**：覆盖首次 upsert 稳定 installationId/version=1、第二次保留 ID 且 version+1、重启读取解密、revoked 不可 `requireActive`、重复 key 拒绝、非法字段/超长字段拒绝、任意损坏非空行使读取失败、临时文件原子替换失败不覆盖旧文件。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationStoreTest test`

Expected: 编译失败，store 尚不存在。
- [ ] **Step 3: 最小实现**：使用 Gson 严格映射和字段上界；调用 `CredentialCipher` 加密/解密；所有读改写在 JVM lock 内完成，临时文件设 `0600`、flush 后 `ATOMIC_MOVE`，不支持原子移动直接抛 `WECOM_INSTALLATION_STORE_WRITE_FAILED`；空文件视为空 store，非空坏行抛 `WECOM_INSTALLATION_STORE_CORRUPTED`。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationStoreTest test`

Expected: 全部通过，且测试目录不存在明文 permanent code。
- [ ] **Step 5: 检查 JSONL 和权限边界**

Run: `rg -n "permanentCode|suiteTicket|accessToken|secretKey|loginCode|viewerToken" target/test-data 2>/dev/null || true && git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationStore.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationStoreTest.java`

Expected: 只出现测试字段名，不出现真实敏感值。

### Task 3: 官方回调验签与解密 codec

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComCallbackCodec.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComCallbackCodecTest.java`

**Interfaces:**
- `DecodedCallback decode(String msgSignature, String timestamp, String nonce, String encryptXml)`。
- `record DecodedCallback(String suiteId, String infoType, String authCorpId, String authCode, String suiteTicket, String state, Instant timestamp)`。
- `String verifyAndDecrypt(String msgSignature, String timestamp, String nonce, String encryptXml)` 仅返回明文 XML，不记录输入。

- [ ] **Step 1: 写失败测试**：用固定 AES key 构造官方明文布局，断言合法签名解密出 XML；错误签名、过期/非数字 timestamp、超 1 MiB body、错误 receiveId、缺失 `InfoType`、XXE/DTD、PKCS#7 错误均拒绝；`suite_ticket` 和 `create_auth` 字段上限分别为 512 字节。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComCallbackCodecTest test`

Expected: 编译失败，codec 尚不存在。
- [ ] **Step 3: 最小实现**：按 `SHA-1(sort(token,timestamp,nonce,encrypt))` 验签；EncodingAESKey 补 `=` 后 Base64 解码 32 字节；AES/CBC/NoPadding，IV 为 key 前 16 字节；去 PKCS#7 block=32；解析 16 随机字节、网络序 XML 长度、XML 和 receiveId；JDK DOM 禁用 DTD/外部实体并限定节点/文本长度。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComCallbackCodecTest test`

Expected: 全部通过，异常消息不包含 token、AES key 或密文。

### Task 4: suite 凭证与官方 HTTP gateway

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`

**Interfaces:**
- `void acceptSuiteTicket(String suiteId, String suiteTicket, Instant receivedAt)`。
- `String suiteAccessToken(String suiteId)`。
- `PermanentCodeResponse getPermanentCode(String authCode)`。
- `AuthorizationInfo getAuthInfo(String permanentCode)`。
- `record PermanentCodeResponse(String authCorpId, String permanentCode)`、`record AuthorizationInfo(String authCorpId, List<AuthorizedAgent> agents)`、`record AuthorizedAgent(String agentId)`。

- [ ] **Step 1: 写失败测试**：模拟 HTTP server，断言 `get_suite_token`/`get_permanent_code`/`get_auth_info` 请求参数来自内存 ticket 和安装凭证；10 秒超时、1 MiB 响应上限、非零 errcode、缺字段均映射结构化异常；suite ticket 有效 30 分钟，access token 提前刷新，缓存 key 含 suite/installation。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationGatewayTest test`

Expected: 编译失败，gateway 尚不存在。
- [ ] **Step 3: 最小实现**：使用 JDK `HttpClient`、10 秒 connect/request timeout 和有界读取；实现官方三个 endpoint；只缓存 token/ticket 于内存，永久码结果交给 service，不写日志。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationGatewayTest test`

Expected: 全部通过；测试确认 timeout、超大响应和上游错误均为 `WECOM_UPSTREAM_UNAVAILABLE` 或对应结构化码。

### Task 5: 授权事件服务与有界异步 worker

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java`

**Interfaces:**
- `CallbackAck handle(DecodedCallback callback)`，其中 `CallbackAck` 只有 `success()` 或 `retry()`。
- `void close()` 停止单 worker 并等待已取出的任务结束。
- `record AuthorizationEvent(String infoType, String suiteId, String authCorpId, String authCode, String state)`。

- [ ] **Step 1: 写失败测试**：`suite_ticket` 同步更新 gateway 内存；`create_auth` 只入容量 64 队列并立即 success；队列满返回 retry；worker 调用 permanent code/auth info 后 active upsert；同一事件重放不产生第二个安装；`change_auth` 刷新 agent/version；`cancel_auth` revoke 并清理缓存/未完成 attempt；任何上游失败记录 failed 且不暴露凭据。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationServiceTest test`

Expected: 编译失败，service 尚不存在。
- [ ] **Step 3: 最小实现**：固定 64 容量 `BlockingQueue` + 单 daemon worker；`create_auth/change_auth` 入队，满队列返回 retry 让官方重试；`cancel_auth` 同步 revoke；在 upsert 时复制当前 suite ticket 接收时间到 `lastSuiteTicketAt`，不因每次 ticket 推送重写所有安装或递增 version。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationServiceTest test`

Expected: 全部通过，`close()` 后无后台线程泄漏。

### Task 6: 正式授权回调 HTTP 路由与 OpenAPI

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- 新增 `GET/POST /api/v1/wecom/authorization/callback`：GET 接收 `msg_signature`、`timestamp`、`nonce`、`echostr` 并返回解密后的纯文本校验串；POST 接收 XML 授权事件并返回 `200 text/plain success`；`create_auth/change_auth` 解密后异步处理，回调 1000ms 内返回；验签/解密失败返回结构化 403。

- [ ] **Step 1: 写失败路由/合同测试**：断言 GET 合法校验返回解密纯文本、GET 错误签名 403/缺配置 503，POST 合法 callback 返回 200 `success`、错误签名 403、缺 query/body 400、队列满非 2xx；OpenAPI operation 从 25 增至 27，参数和 XML/plain 响应准确描述。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test && node contracts/openapi/message-center-v1.test.mjs`

Expected: 新路由不存在或 operation 数仍为 25，测试失败。
- [ ] **Step 3: 最小实现**：在 `App` 装配 codec/service；限制请求体 1 MiB；只把 `WeComAuthorizationException` 映射为统一 `{code,message,requestId}` envelope，其他异常 500；保留旧模拟 webhook 作为消息 adapter，但禁止其成为认证 owner。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test && node contracts/openapi/message-center-v1.test.mjs`

Expected: HTTP 回归与 27 operations 合同均通过。

### Task 7: 登录 attempt 绑定安装版本

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`

**Interfaces:**
- `LoginAttemptResponse createAttempt()` 返回 `state`、`appid`、`agentid`、`redirectUri`、`expiresAt`。
- `InstallationBinding consume(String state)` 返回 `installationId`、`version`、`suiteId`、`authCorpId`、`agentId`；consume 成功后 state 一次性删除。
- `WeComViewerService.exchangeLoginCode(String code, InstallationBinding binding)`。

- [ ] **Step 1: 写失败测试**：创建 attempt 使用 `WECOM_LOGIN_AUTH_CORP_ID` 精确记录；安装被 revoke、版本更新或 JSONL 损坏后 consume/exchange 拒绝；重复 state、过期 state 和容量满均拒绝并写正确审计事件；匿名请求不能传 corpId 覆盖配置。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComLoginAttemptServiceTest test`

Expected: 旧签名只返回 void/读取静态 Config，新增安装断言失败。
- [ ] **Step 3: 最小实现**：attempt 内存值增加 installation ID/version；create 时调用 `store.requireActive`；consume 返回 binding 并原子删除；App 先 consume 再把 binding 传给 viewer，禁止跨安装交换 code。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComLoginAttemptServiceTest test`

Expected: 生命周期、版本阻断和重放测试全部通过。

### Task 8: viewer、JS-SDK 和 token/session 安装上下文迁移

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- `JsSdkConfig jsSdkConfig(InstallationBinding binding, String url)`。
- `ViewerAuthResult exchangeLoginCode(String code, InstallationBinding binding)`。
- `ViewerDetail viewerDetail(String token)` 和 `openViewer(...)` 只能使用 token 中安装 ID/version；detail 返回绑定安装的 corpId/agentId。

- [ ] **Step 1: 写失败测试**：token/session 绑定 installation ID/version；安装 revoke/version+1 后 detail、open、events 返回 403 `WECOM_INSTALLATION_CHANGED`；JS-SDK 签名缓存按安装版本和 URL 隔离；旧 `JdkWeComHttpGateway` 静态 corp/secret 路径不能被调用。
- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: viewer 仍从 Config 读取静态 corpId/agentId/secret，新增断言失败。
- [ ] **Step 3: 最小实现**：gateway 方法接收 `ResolvedInstallation`；access-token cache key 为 installation ID/version；exchange 调 `/gettoken(authCorpId, permanentCode)` 和 `/auth/getuserinfo`，只接受 `userid`；所有 viewer token、session、event consume、detail 和 SDK config 重读 store 校验状态/版本；明文 permanent code 生命周期最短。
- [ ] **Step 4: 运行通过测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: viewer 安装绑定、撤销、版本变化和 SDK 隔离测试通过。

### Task 9: 清理第二认证 owner 与文档配置

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComReceiver.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`

**Interfaces:**
- 删除 `/api/wecom/gettoken` 诊断路由及 `WeComReceiver.getToken()` 的静态 secret 读取（这是已批准的认证 owner 收敛；执行前确认不保留该旧路径）。
- 文档只描述 suite 安装、`WECOM_LOGIN_AUTH_CORP_ID` 和 callback URL；明确 `WECOM_AGENT_ID/WECOM_SECRET` 失效。

- [ ] **Step 1: 写失败搜索/回归测试**：合同测试断言不存在静态 secret/token 路由；README/config 示例不再出现 `WECOM_AGENT_ID`、`WECOM_SECRET` 作为认证配置；旧消息 webhook 行为仍通过既有测试。
- [ ] **Step 2: 运行失败检查**

Run: `rg -n "WECOM_AGENT_ID|WECOM_SECRET|/api/wecom/gettoken|getToken\(" demo/message-center-demo docs/superpowers/specs`

Expected: 命中旧 owner，检查失败。
- [ ] **Step 3: 最小实现**：移除诊断 token 输出和静态 Secret 分支；补充从企业微信管理后台获取 SuiteId/Secret、配置 callback、安装记录文件权限、首次扫码前必须存在 active installation 的操作说明及结构化错误表。
- [ ] **Step 4: 运行通过检查**

Run: `! rg -n "WECOM_AGENT_ID|WECOM_SECRET|/api/wecom/gettoken|getToken\(" demo/message-center-demo docs/superpowers/specs`

Expected: 命令成功，无静态认证 owner 残留；消息 webhook 测试不受影响。

### Task 10: JDK 17 全量门禁与真实浏览器验收

**Files:**
- Modify only files proven necessary by prior tasks; do not touch unrelated dirty files.

**Interfaces:**
- 交付可启动的 JDK 17 服务、27-operation OpenAPI 合同、可验证的 callback/login/viewer 链路和桌面/移动真实浏览器证据。

- [ ] **Step 1: 运行单元和编译门禁**

Run:

```bash
cd demo/message-center-demo
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
```

Expected: 所有命令退出码 0；JUnit 0 failures；Node 输出 `validated 27 OpenAPI operations`。
- [ ] **Step 2: 检查敏感字段和空白**

Run: `git diff --check`

Expected: `git diff --check` 成功；另行人工检查计划中的每一步均有具体文件、接口、失败测试、实现动作和通过命令。
- [ ] **Step 3: 启动真实服务并验收安装选择失败态**：用 JDK 17 在未配置 active installation 的隔离端口启动，浏览器首屏显示结构化 `WECOM_INSTALLATION_NOT_FOUND`，不得发起联系人/消息请求。
- [ ] **Step 4: 用户批准后验收官方网络和 Safari**：配置真实 SuiteId/Secret/Token/AES key、安装记录和公网 HTTPS；完成 `suite_ticket -> create_auth -> active installation -> 同域扫码 -> viewer`，用 Safari WebDriver 获取桌面和移动截图并点击联系人、WeCom tab、加载态、组件错误态。不得用字符串探针替代截图；旧 Safari 配对状态阻塞时停止并报告，不强制关闭浏览器。
- [ ] **Step 5: 最终 git 边界复核**

Run: `git status --short && git diff --stat && git diff --check`

Expected: 只列出本任务明确文件和用户原有 dirty 文件；无暂存、无提交、无整体回滚。

## 自审清单

- [ ] 每个设计章节均有对应任务：配置、store、codec、suite gateway、事件 worker、callback、attempt、viewer、owner 清理、全量验收。
- [ ] 所有外部输入、响应体、队列、TTL、并发 writer、临时文件和 token cache 均有上界或释放策略。
- [ ] 后续任务引用的类型和方法名与前置任务一致：`InstallationBinding`、`ResolvedInstallation`、`WeComAuthorizationGateway`、`WeComAuthorizationService`。
- [ ] 无数据库、无匿名 CorpID、无静态 AgentID/Secret 回退、无持久浏览器 token。
- [ ] 正式 callback 是新增公开 operation；执行 Task 6 前须向用户说明已批准的合同变更并再次确认。

本工作区不执行 `git add` 或 `git commit`。计划完成后若要实施，推荐在用户明确授权后调用 `executing-plans` 以内联方式逐任务执行。
