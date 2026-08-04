# 企业微信专区会话展示索引同步 Implementation Plan

> **2026-07-31 凭证更正：** 本计划的专区同步主体仍有效，但代开发 access token 获取以 `2026-07-31-wecom-developed-app-access-token.md` 为当前真源。官方定义代开发授权返回的 `permanent_code` 即应用 Secret，8107 必须使用 `authCorpId + permanent_code` 调用 `GET /cgi-bin/gettoken`，不得调用第三方应用 `/cgi-bin/service/get_corp_token`。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 制作 Java 17、`linux/amd64` 最小专区镜像，并让现有 viewer session 创建链路同步真实 `msgid + secret_key` 后交给 `ww-open-message` 展示。

**Architecture:** 专区镜像沿用企业微信 Java SDK 1.4.0 的加密 HTTP server，只允许固定能力调用 `sync_msg` 并投影最小索引字段。8107 使用代开发授权安装的 `authCorpId + permanent_code` 经 `GET /cgi-bin/gettoken` 获取应用 access token，调用 `sync_call_program`，校验并解密每一页后原子发布 viewer JSONL 与 cursor；现有 `WeComViewerService` 继续独占 token、owner、限流和一次性 session 语义。

**Tech Stack:** JDK 17、Maven、Gson 2.11、JDK `HttpClient`、JCA RSA、企业微信 SpecSDK Java 1.4.0、Netty 4.1、Docker/BuildKit `linux/amd64`。

## Global Constraints

- 本轮不做 Topic、摘要、画像或 AI 分析，也不把原始正文带出专区。
- 不依赖、不修改、不停止 8067；不新增公开 HTTP operation。
- 点击 Viewer 时固定 `mode=0`、每页最多 200、最多 5 页/1000 条、总超时 15 秒。
- 仅投影员工与外部联系人的一对一消息；其他消息只统计跳过数量。
- `WECOM_DATA_FILE` 最多 5000 条且硬上限 8 MiB；消息快照必须先于 cursor 原子发布。
- RSA 私钥只从绝对路径 secret 文件读取，密钥、密文、token 和身份 ID 不进入错误或审计。
- 所有实现和验收使用 JDK 17；镜像目标架构固定 `linux/amd64`。
- 工作区包含用户原有改动；禁止整体回滚混合文件，禁止 `git add .`，本轮不提交。

---

## 文件结构

- `demo/wecom-chatdata-zone-program/`：独立最小专区镜像工程；不依赖消息中心。
- `WeComAccessTokenService.java`：代开发安装应用 access token 的唯一缓存 owner。
- `WeComChatDataGateway.java`：`sync_call_program` HTTP 映射、响应上界和合同解析。
- `WeComChatDataCrypto.java`：单版本 RSA 私钥加载与 `encrypted_secret_key` 解密。
- `WeComChatDataStore.java`：一对一消息投影、幂等快照和 cursor 原子发布。
- `WeComChatDataSyncService.java`：单企业单飞、分页、15 秒 deadline、审计和 session 前编排。
- `WeComChatDataException.java`：chatdata 结构化 HTTP 错误。

### Task 1: 代开发应用 access token owner

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAccessTokenService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAccessTokenServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`

**Interfaces:**
- Produces: `String WeComAccessTokenService.accessToken(WeComAuthorizationStore.ResolvedInstallation installation)`。
- Produces: `WeComAuthorizationGateway.CorpTokenResponse getDevelopedAppToken(String authCorpId, String developedAppSecret, Duration timeout)`，请求 `GET /cgi-bin/gettoken?corpid=...&corpsecret=...`。
- Consumes: `ResolvedInstallation.installation().installationId()/version()/authCorpId()` 与解密后的 `permanentCode()`。

- [ ] **Step 1: 写失败测试**

  覆盖 `/cgi-bin/gettoken` 的 GET URL、`access_token/expires_in` 解析、同一安装版本缓存、版本变化后重新获取、上游错误脱敏。测试使用固定假 Secret，不输出真实 permanent code。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAccessTokenServiceTest,WeComAuthorizationGatewayTest test`

  Expected: FAIL，原因是 `WeComAccessTokenService` 和 `getDevelopedAppToken` 尚不存在。

- [ ] **Step 3: 最小实现**

  缓存键固定为 `installationId + ':' + version`，刷新条件为 `now >= expiresAt - WECOM_TOKEN_REFRESH_SKEW_SECONDS`。`WeComAccessTokenService` 将 resolved installation 中的 `permanent_code` 按官方代开发语义作为应用 Secret，调用 `getDevelopedAppToken`；viewer、专区同步和公钥注册只委托该 owner。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAccessTokenServiceTest,WeComAuthorizationGatewayTest test`

  Expected: PASS。

### Task 2: Chatdata 配置和结构化错误

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataException.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataExceptionTest.java`

**Interfaces:**
- Produces: `wecomChatDataProgramId()`, `wecomChatDataAbilityId()`, `wecomChatDataPrivateKeyFile()`, `wecomChatDataPublicKeyVersion()`, `wecomChatDataCursorFile()`, `wecomChatDataSyncLimit()`, `wecomChatDataSyncMaxPages()`, `wecomChatDataSyncTimeoutSeconds()`, `wecomChatDataStoreMaxMessages()`, `wecomChatDataStoreMaxBytes()`。
- Produces: `WeComChatDataException(String code, int httpStatus, String safeMessage[, Throwable cause])`。

- [ ] **Step 1: 写失败测试**

  断言默认值为 `200/5/15/5000/8388608`，limit 范围 `1..200`、页数 `1..5`、超时 `1..15`、条数 `1..5000`、字节 `4096..8388608`；私钥必须为绝对路径，公钥版本必须正数。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComChatDataExceptionTest test`

  Expected: FAIL，原因是 chatdata getters/type 尚不存在。

- [ ] **Step 3: 最小实现**

  `App.writeRouteError` 优先映射 `WeComChatDataException.httpStatus()/code()`；安全 message 由异常构造时固定，不拼接上游 body、token、密钥、密文或用户 ID。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComChatDataExceptionTest test`

  Expected: PASS。

### Task 3: `sync_call_program` HTTP Gateway

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataGateway.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataGatewayTest.java`

**Interfaces:**
- Consumes: `WeComAccessTokenService.accessToken(ResolvedInstallation)`。
- Produces: `ProgramPage sync(ResolvedInstallation installation, String cursor, int limit, Duration timeout)`。
- Produces records: `ProgramPage(boolean hasMore, String nextCursor, List<EncryptedMessage> messages)`、`EncryptedMessage(String msgid, Party sender, List<Party> receivers, String chatId, long sendTime, int msgType, String encryptedSecretKey, int publicKeyVersion)`、`Party(int type, String id)`。

- [ ] **Step 1: 写失败测试**

  使用本地 `HttpServer` 断言请求路径只可能是 `/cgi-bin/chatdata/sync_call_program`，body 精确包含 `program_id`、`ability_id` 和字符串 `request_data`，内部 input 只含 cursor/limit 且首次显式发送空 cursor；`mode=0` 由专区程序独占注入。覆盖 HTTP 非 2xx、外层 `errcode != 0`、缺失/超大 `response_data`、内部 `errcode != 0`、未知字段、超过 200 条、字段越界和 timeout。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataGatewayTest test`

  Expected: FAIL，原因是 gateway 尚不存在。

- [ ] **Step 3: 最小实现**

  JDK `HttpClient` connect/request timeout 均有界；HTTP body 和 `response_data` 分别最多 1 MiB。内部只接受 `errcode/errmsg/has_more/next_cursor/msg_list` 以及设计文档列明的消息字段；`mode=0` 只由专区程序设置，不暴露任意 SDK apiName。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataGatewayTest test`

  Expected: PASS。

### Task 4: RSA secret key 解密

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataCrypto.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataCryptoTest.java`

**Interfaces:**
- Produces: `String decryptSecretKey(int publicKeyVersion, String encryptedSecretKey)`。

- [ ] **Step 1: 写失败测试**

  测试用 JCA 生成 RSA-2048 keypair，以官方 RSA/PKCS#1 v1.5 规则加密 fixture。覆盖成功、版本不匹配、非 2048 位私钥、错误 PEM、错误 base64、错误密文和解密文本长度/字符边界；所有异常 message 断言不含密文或明文 secret。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataCryptoTest test`

  Expected: FAIL，原因是 crypto 尚不存在。

- [ ] **Step 3: 最小实现**

  启动时从绝对 PEM 路径读取 PKCS#8 私钥，校验 `RSAKey.getModulus().bitLength() == 2048`，使用 `RSA/ECB/PKCS1Padding` 解密；公钥版本不一致返回 `WECOM_CHATDATA_KEY_VERSION_MISMATCH`，其余安全失败返回 `WECOM_CHATDATA_DECRYPT_FAILED`。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataCryptoTest test`

  Expected: PASS。

### Task 5: 消息引用与 cursor 原子 store

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataStore.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataStoreTest.java`

**Interfaces:**
- Consumes: `ProgramPage` 和逐条解密后的 secret key。
- Produces: `String cursor(String installationId, long version, String programId, String abilityId)`。
- Produces: `PublishResult publishPage(SyncKey key, ProgramPage page, List<DecryptedMessage> decrypted)`。
- Produces records: `SyncKey(...)`、`DecryptedMessage(...)`、`PublishResult(int stored, int skipped)`。

- [ ] **Step 1: 写失败测试**

  覆盖员工发给单一外部联系人、外部联系人回复单一员工的投影；群聊、机器人、多接收人安全跳过。覆盖按 `msgid + userid + external_userid` 幂等、send_time 排序、最近 5000 条、8 MiB 上限、消息快照先于 cursor、模拟第二次 replace 失败后 cursor 不前进。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataStoreTest test`

  Expected: FAIL，原因是 store 尚不存在。

- [ ] **Step 3: 最小实现**

  每页先完整校验和投影，再在同目录写 temp 文件并以 `ATOMIC_MOVE, REPLACE_EXISTING` 发布 `WECOM_DATA_FILE`，随后同样发布 cursor JSON。读写均限制字节数；JSONL 仅包含 `msgid/secret_key/external_userid/userid/send_time/msgtype`。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataStoreTest test`

  Expected: PASS。

### Task 6: 有界同步编排与 viewer session 接线

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataSyncService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataSyncServiceTest.java`

**Interfaces:**
- Produces: `WeComViewerService.ViewerSyncContext viewerSyncContext(String viewerAuthToken)`，只返回当前有效 token 绑定的 `ResolvedInstallation` 和 `wecomUserId`，不返回 token 或 permanent code 到 controller。
- Produces: `SyncResult sync(ViewerSyncContext context)`。

- [ ] **Step 1: 写失败测试**

  覆盖首次空 cursor、后续 cursor、最多 5 页、1000 条、15 秒 deadline、`has_more=1` 页上限错误、同企业并发忙、不同企业不互锁、整页解密失败不发布、审计失败关闭。路由测试断言顺序为联系人可读校验 → viewer token/安装校验 → sync → `createViewerSession`；同步失败时 session 不创建。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataSyncServiceTest,UnifiedMessageStoreTest test`

  Expected: FAIL，原因是 sync service/context 尚不存在。

- [ ] **Step 3: 最小实现**

  使用 `ConcurrentHashMap<String, ReentrantLock>` 的 `tryLock()` 实现单企业单飞，不排队；每页前计算剩余 deadline。`App.startWeb` 共享同一个 `WeComAuthorizationGateway`/`WeComAccessTokenService` 实例给授权安装、viewer 和 chatdata。未完整配置 chatdata 时返回 `WECOM_CHATDATA_NOT_CONFIGURED`，不回退模拟路径。

- [ ] **Step 4: 运行针对性测试**

  Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataSyncServiceTest,UnifiedMessageStoreTest test`

  Expected: PASS。

### Task 7: 最小专区程序与镜像

**Files:**
- Create: `demo/wecom-chatdata-zone-program/pom.xml`
- Create: `demo/wecom-chatdata-zone-program/src/main/java/com/tencent/wework/SpecSDK.java`
- Create: `demo/wecom-chatdata-zone-program/src/main/java/com/tencent/wework/SpecCallbackSDK.java`
- Create: `demo/wecom-chatdata-zone-program/src/main/java/com/tencent/wework/SpecUtil.java`
- Create: `demo/wecom-chatdata-zone-program/src/main/java/com/crmforlogistics/wecomchatdata/ZoneProgram.java`
- Create: `demo/wecom-chatdata-zone-program/src/main/java/com/crmforlogistics/wecomchatdata/SyncMessageAbility.java`
- Create: `demo/wecom-chatdata-zone-program/src/test/java/com/crmforlogistics/wecomchatdata/SyncMessageAbilityTest.java`
- Create: `demo/wecom-chatdata-zone-program/Dockerfile`
- Create: `demo/wecom-chatdata-zone-program/start`
- Create: `demo/wecom-chatdata-zone-program/prepare-official-sdk.sh`
- Create: `demo/wecom-chatdata-zone-program/build-image.sh`
- Create: `demo/wecom-chatdata-zone-program/.gitignore`
- Create: `demo/wecom-chatdata-zone-program/README.md`

**Interfaces:**
- Consumes official package: `java_demo_src_1.4.0.tar.gz`，SHA-256 在 README 和准备脚本中固定。
- Produces ability output: `{errcode,errmsg,has_more,next_cursor,msg_list}`，`msg_list` 只含设计文档第 7.2 节字段。

- [ ] **Step 1: 写失败测试**

  `SyncMessageAbilityTest` 使用 fake SDK invoker，覆盖 cursor/token/limit、未知字段拒绝、固定 `mode=0`、SDK ret/errcode、超大或畸形响应、最多 200 条以及正文/extra_info 不出现在输出。

- [ ] **Step 2: 验证测试先失败**

  Run: `cd demo/wecom-chatdata-zone-program && mvn -q test`

  Expected: FAIL，原因是专区程序尚不存在。

- [ ] **Step 3: 最小实现**

  HTTP 加解密框架基于官方 Java 1.4.0 示例的 `SpecCallbackSDK` 调用模式；能力只接受构建时固定的 `WECOM_CHATDATA_ABILITY_ID` 并执行 `new SpecSDK(callback).Invoke("sync_msg")`。官方 demo 的 HTTP aggregator 收紧为 64 KiB，IO/业务线程上限各为 4，并移除无用数据库初始化。`start` 内容固定为 `#!/usr/bin/env sh` 与 `exec java -Dfile.encoding=UTF-8 -jar /app/wecom-chatdata-zone-program.jar`。官方 SDK 由准备脚本校验源码 tar.gz SHA-256 后提取，不提交二进制或密钥。

- [ ] **Step 4: 运行单元测试**

  Run: `cd demo/wecom-chatdata-zone-program && mvn -q test`

  Expected: PASS。

- [ ] **Step 5: 构建和导出镜像**

  Run: `cd demo/wecom-chatdata-zone-program && ./prepare-official-sdk.sh /private/tmp/java_demo_src_1.4.0.tar.gz && WECOM_CHATDATA_ABILITY_ID=conversation_viewer_sync ./build-image.sh`

  Expected: 生成 `target/wecom-chatdata-zone-program-linux-amd64.tar`；该 rootfs tar 可被 `docker import`，构建源镜像架构为 `amd64`，容器入口 `/app/start`。

### Task 8: 配置、合同和部署文档

**Files:**
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- OpenAPI operation 数量保持当前代码真源的 27；只补现有 session route 的 chatdata 错误响应。

- [ ] **Step 1: 更新配置和部署说明**

  写明 program/ability ID、RSA-2048 keypair、`set_public_key`、私钥绝对路径、镜像上传/审核、8107 重启和真实消息前置条件。禁止把任何真实 suite secret、permanent code、私钥或 token 写进示例。

- [ ] **Step 2: 更新 OpenAPI 合同测试**

  断言 session route 包含 `409/429/500/502/503/504`，operation 总数仍为 27。

- [ ] **Step 3: 运行合同验证**

  Run: `cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs`

  Expected: `validated 27 OpenAPI operations`。

### Task 9: 完整门禁与真实验收边界

**Files:**
- Verify only; no commit.

- [ ] **Step 1: 运行 JDK 17 回归门禁**

  Run:

  ```bash
  cd demo/message-center-demo
  java -version
  mvn -q test
  mvn -q test-compile
  mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
  mvn -q -DskipTests package
  node contracts/openapi/message-center-v1.test.mjs
  ```

  Expected: JDK 17；全部命令退出码 0；OpenAPI 为 27 operations。

- [ ] **Step 2: 运行专区工程和镜像门禁**

  Run:

  ```bash
  cd demo/wecom-chatdata-zone-program
  mvn -q test
  ./prepare-official-sdk.sh /private/tmp/java_demo_src_1.4.0.tar.gz
  WECOM_CHATDATA_ABILITY_ID=conversation_viewer_sync ./build-image.sh
  ```

  Expected: 测试通过；rootfs tar 可 `docker import`；构建源镜像架构 `amd64`。

- [ ] **Step 3: 检查敏感信息和 diff**

  Run: `git diff --check && git status --short`

  Expected: 无 whitespace error；没有新增私钥、token、secret、permanent code 或生成镜像 tar 被 Git 跟踪。

- [ ] **Step 4: 记录外部验收边界**

  镜像审核、能力关联、RSA 公钥设置和最近 5 天真实会话属于企业微信后台外部状态。本地门禁通过后只声称“本地实现与镜像构建完成”；只有浏览器中员工发出和外部联系人回复两条消息均由官方组件显示并取得桌面/移动截图后，才声称真实链路完成。
