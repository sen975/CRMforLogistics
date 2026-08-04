# 邮件附件支持实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 `demo/message-center-demo` 运行面闭合邮件收发附件：多文件、单封 20 MiB、邮件绑定、本地保存、下载、前端操作和收件解析。

**Architecture:** `EmailAttachmentStore` 是附件文件与路径的唯一 owner；`MailSender` 只负责 Jakarta Mail MIME/SMTP；`EmailInboxWriter` 只负责收件 MIME 分类；`App` 与嵌入式页面只做协议映射和展示。邮件 JSONL 保存附件元数据，二进制保存在 `Config.emailDataDir()` 下，后续 Spring/React 迁移必须承接同一合同。

**Tech Stack:** Java 17、JDK `HttpServer`、Jakarta Mail 2.0.1、Apache Commons FileUpload 1.6.0、Gson 2.11.0、JUnit 5.11.4、内嵌 HTML/CSS/JavaScript、现有 JSONL 邮件存储。

## Global Constraints

- 单封邮件最多 16 个附件，附件内容总量最多 20 MiB（`EMAIL_ATTACHMENT_MAX_COUNT=16`、`EMAIL_ATTACHMENT_MAX_TOTAL_BYTES=20971520`）。
- 附件总存储预算默认 10 GiB，范围 20 MiB 到 1 TiB（`EMAIL_ATTACHMENT_STORAGE_MAX_BYTES=10737418240`）。
- `to` 最多 2,048 UTF-8 bytes，`subject` 最多 998 UTF-8 bytes，`body` 最多 1 MiB，单个 MIME part header 最多 8 KiB，multipart 非文件开销最多 128 KiB。
- 附件只能作为标准 MIME `multipart/mixed` 普通附件；不实现 HTML 富文本、CID inline、预览、云存储或病毒扫描。
- 二进制只能进入 `Config.emailDataDir()/attachments/<messageId>/`；客户端路径、相对路径和 query token 永远不进入 API。
- 收件投影最多保留 16 个 `stored` 条目和 1 个 `rejected` 条目，其余超限 MIME part 必须排空但不能无限保存元数据。
- 发件超限必须在 SMTP 连接前失败；收件超限保留正文和此前合法附件。
- 每个任务先写失败测试、运行确认失败，再写最小实现；每个任务单独提交。
- 当前 Spring/React 目录存在用户/并行 dirty 改动，本计划不修改、不暂存、不回滚这些文件。
- 完成前必须运行 `cd demo/message-center-demo && mvn -q test`；需要本地 HTTP 临时端口时在允许绑定的环境运行。

---

### Task 1: 配置与附件核心模型

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailAttachment.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailAttachmentStore.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentStoreTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**Interfaces:**
- `EmailAttachment` 提供 `id()`, `fileName()`, `mimeType()`, `sizeBytes()`, `relativePath()`, `state()`, `errorCode()`；`state` 只有 `stored`/`rejected`。
- `EmailAttachmentStore.stage(InputStream input, String fileName, String mimeType, AttachmentBudget budget)` 返回暂存附件并计算实际字节数与 SHA-256。
- `EmailAttachmentStore.publish(String messageId, List<StagedAttachment> staged)` 原子移动整目录并返回 `List<EmailAttachment>`。
- `EmailAttachmentStore.open(String messageId, String attachmentId)` 只打开已发布且属于邮件的文件；错误返回稳定 `EMAIL_ATTACHMENT_NOT_FOUND` 或 `EMAIL_ATTACHMENT_PATH_INVALID`。
- `EmailAttachmentStore.availableBytes()` 和 `EmailAttachmentStore.reconcile(int maxEntries)` 为容量检查与启动对账入口。
- `AttachmentBudget` 是不可变值对象，字段为 `maxCount`、`maxTotalBytes`、`maxStorageBytes`；`StagedAttachment` 是不可变值对象，字段为 `id`、`fileName`、`mimeType`、`sizeBytes`、`sha256`、`temporaryPath`。
- `EmailAttachmentStore` 对外抛出 `EmailAttachmentStoreException`，其 `errorCode()` 只返回 `EMAIL_ATTACHMENT_NOT_FOUND`、`EMAIL_ATTACHMENT_PATH_INVALID`、`EMAIL_ATTACHMENT_SIZE_LIMIT` 或 `EMAIL_ATTACHMENT_STORAGE_FULL`。

- [ ] **Step 1: 写配置与路径隔离失败测试**

在 `ConfigTest` 增加以下断言：默认值为 16、20 MiB、10 GiB；超过最大值、低于最小值和非数字值抛 `IllegalArgumentException`。在 `EmailAttachmentStoreTest` 写入 `../escape.txt`、反斜杠、控制字符和超长文件名，断言发布后的路径仍位于 `emailDataDir/attachments/<messageId>`。

```java
Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
assertEquals(16, config.emailAttachmentMaxCount());
assertEquals(20_971_520L, config.emailAttachmentMaxTotalBytes());
assertEquals(10_737_418_240L, config.emailAttachmentStorageMaxBytes());
assertThrows(IllegalArgumentException.class,
        () -> new Config(Map.of("EMAIL_ATTACHMENT_MAX_COUNT", "17"))
                .emailAttachmentMaxCount());
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,EmailAttachmentStoreTest test
```

预期：失败，原因是配置 accessor、模型和 store 尚不存在。

- [ ] **Step 3: 实现配置与 store**

在 `Config` 添加 bounded accessor：

```java
public int emailAttachmentMaxCount() {
    return boundedInt("EMAIL_ATTACHMENT_MAX_COUNT", 16, 1, 16);
}
public long emailAttachmentMaxTotalBytes() {
    return boundedLong("EMAIL_ATTACHMENT_MAX_TOTAL_BYTES", 20_971_520L,
            1_048_576L, 20_971_520L);
}
public long emailAttachmentStorageMaxBytes() {
    return boundedLong("EMAIL_ATTACHMENT_STORAGE_MAX_BYTES", 10_737_418_240L,
            20_971_520L, 1_099_511_627_776L);
}
```

`EmailAttachmentStore` 必须使用 `EMAIL_DATA_DIR/attachment-tmp/<requestId>` 暂存、`EMAIL_DATA_DIR/attachments/<messageId>` 发布；所有目录创建使用 `Files.createDirectories`，发布使用同文件系统 `ATOMIC_MOVE`，目标存在时失败关闭。读取前用 `normalize().startsWith(root)` 检查路径，并从邮件附件元数据匹配 attachment ID。

- [ ] **Step 4: 运行核心测试确认通过**

运行同一 `mvn -q -Dtest=ConfigTest,EmailAttachmentStoreTest test`，预期全部通过，并额外断言暂存目录在 `close`/失败路径被清理、空文件大小为 0、SHA-256 由实际字节计算。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailAttachment.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailAttachmentStore.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentStoreTest.java
git commit -m "feat: add bounded email attachment storage"
```

### Task 2: 邮件记录附件投影

**Files:**
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentProjectionTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessage.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- `UnifiedMessage.attachments` 永远是非空 `List<EmailAttachment>`。
- 邮件 JSONL 的 `attachments` 数组解析为同顺序列表；损坏或缺失数组投影为空列表，不能让整行邮件丢失。
- `UnifiedMessageStore.thread` 和 `findMessage` 返回附件元数据，但不读取二进制、不返回 `relativePath`。
- `threadRevision` 必须把 attachment ID、state、filename、size 和 errorCode 纳入 digest。

- [ ] **Step 1: 写 JSONL 与 revision 失败测试**

写入包含两个附件和一个 rejected 条目的邮件 JSONL，断言 `UnifiedMessageStore.thread("email:buyer@example.com")` 返回同顺序列表，`raw` 保留原始 JSON，`relativePath` 不出现在前端 projection；改变附件 state 后 `threadRevision` 必须变化。

```java
assertEquals(List.of("quote.pdf", "photo.jpg", "too-large.zip"),
        message.attachments.stream().map(EmailAttachment::fileName).toList());
assertEquals("rejected", message.attachments.get(2).state());
assertNotContains(message.raw, "viewerAuthToken");
```

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=EmailAttachmentProjectionTest,UnifiedMessageStoreTest#emailMessagesKeepBodyTextOutOfTimelineBubble test
```

预期：失败，因为 `UnifiedMessage` 没有附件列表且邮件 reader 未解析数组。

- [ ] **Step 3: 实现投影**

在 `UnifiedMessage` 初始化 `public List<EmailAttachment> attachments = List.of();`。在 `UnifiedMessageStore.readEmailMessages()` 解析 `attachments` 数组，只映射公开字段；`threadRevision()` 对每个附件调用稳定 digest helper，不把 `relativePath`、二进制或 token 写入 projection。

- [ ] **Step 4: 运行通过测试**

运行：

```bash
mvn -q -Dtest=EmailAttachmentProjectionTest,UnifiedMessageStoreTest test
```

预期：邮件时间线、contact preview、JSONL 损坏行容错和 revision 测试全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessage.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentProjectionTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: project email attachments in messages"
```

### Task 3: 发件 MIME 与恢复记录

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailSendCommand.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailRecoveryJournal.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/MailSenderAttachmentTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MailSender.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- `EmailSendCommand(String to, String subject, String body, String messageId, List<StagedAttachment> attachments)`。
- `MailSender.send(EmailSendCommand command)` 发送正文和附件，返回已持久化 `UnifiedMessage`。
- `EmailRecoveryJournal.prepare`, `accepted`, `remove`, `reconcile` 管理 `prepared`/`accepted` 状态；`prepared` 不自动重发，`accepted` 可恢复本地历史。
- SMTP 传输必须可注入测试 double，使测试读取实际 `MimeMessage` 而不连接公网 SMTP。

- [ ] **Step 1: 写 MIME 失败测试**

构造两个 `StagedAttachment`（UTF-8 文件名、不同 MIME、不同字节），注入捕获 `MimeMessage` 的 transport，断言正文是首个 `text/plain` part、附件数量为 2、顺序/字节/MIME/文件名一致；零附件仍生成合法纯文本邮件。增加 SMTP 抛异常时暂存目录和 recovery journal 的断言。

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=MailSenderAttachmentTest test
```

预期：失败，因为 `MailSender` 只有三字符串 send 方法，没有 multipart MIME 或注入 transport。

- [ ] **Step 3: 实现 MIME 与 journal**

使用 `MimeMultipart("mixed")`：首个 `MimeBodyPart` 调用 `setText(body, "UTF-8")`，每个附件使用 `DataHandler(new ByteArrayDataSource(input, mimeType))`、`setFileName(MimeUtility.encodeText(fileName, "UTF-8", null))`。SMTP 前写 `prepared`，`sendMessage` 正常返回后改 `accepted`，再发布附件目录、append JSONL row、删除 journal。任何 SMTP 前异常清理暂存；SMTP 已接受但本地提交失败返回 `EMAIL_SENT_HISTORY_FAILED`，结果未知返回 `EMAIL_SEND_OUTCOME_UNKNOWN`。

- [ ] **Step 4: 运行通过测试**

```bash
mvn -q -Dtest=MailSenderAttachmentTest,UnifiedMessageStoreTest#mailSenderUsesSenderDomainForMessageId test
```

预期：MIME 字节、UTF-8 文件名、零附件、失败清理和恢复状态全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MailSender.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailSendCommand.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailRecoveryJournal.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/MailSenderAttachmentTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: send email attachments as mime parts"
```

### Task 4: 收件 MIME 附件解析

**Files:**
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailInboxAttachmentTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailInboxWriter.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- `EmailInboxWriter.append(Message message, String direction)` 保持调用合同，但内部通过 `EmailAttachmentStore` 处理附件。
- 正文分类规则：`multipart/alternative` 优先 `text/plain`；`Part.ATTACHMENT` 或带文件名且非 `Part.INLINE` 才保存；inline/CID 不保存、不拼入正文。
- 收件结果的附件列表最多 16 个 stored + 1 个 rejected；稳定拒绝码为 `EMAIL_ATTACHMENT_COUNT_LIMIT`、`EMAIL_ATTACHMENT_SIZE_LIMIT`、`EMAIL_ATTACHMENT_STORAGE_FULL`、`EMAIL_ATTACHMENT_READ_FAILED`、`EMAIL_ATTACHMENT_STORE_FAILED`。

- [ ] **Step 1: 写收件失败测试**

用 `MimeMessage` 构造正文、两个附件、嵌套 alternative 和一个 inline CID，断言正文不包含附件文本，普通附件保存到邮件目录且字节可读，inline 不出现在附件列表。再构造 17 个附件和超过 20 MiB 的流，断言正文与此前合法附件仍写入，最多一个 rejected 条目。

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=EmailInboxAttachmentTest test
```

预期：失败，因为现有 `extractText` 会递归读取所有 text part，且 `append` 没有附件数组。

- [ ] **Step 3: 实现分类与有界写入**

把 `extractText(Part)` 改为只处理正文 part；在 `append` 生成 messageId 后创建暂存目录，按 MIME part 流式写入。达到第一个数量/总量/存储拒绝后排空剩余输入但不追加元数据；孤立读取失败允许继续保存后续合法附件但只保留首个 rejected。邮件 JSONL append 失败删除本次最终目录。

- [ ] **Step 4: 运行通过测试**

```bash
mvn -q -Dtest=EmailInboxAttachmentTest,UnifiedMessageStoreTest#emailInboxWriterStoresImapMessagesInLegacyInboxJsonlFormat test
```

预期：旧无附件格式、重复 Message-ID、嵌套 MIME、超限正文保留和 inline 排除全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailInboxWriter.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailInboxAttachmentTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: persist received email attachments"
```

### Task 5: HTTP multipart adapter 与下载路由

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailMultipartParser.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailHttpAttachmentTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`

**Interfaces:**
- `EmailMultipartParser.parse(HttpExchange exchange, Config config)` 返回 `EmailSendCommand` 所需的文本字段和 `List<StagedAttachment>`，使用 Commons FileUpload streaming API。
- 只允许 `to`、`subject`、`body`、重复 `file`；文本字段必须先于 file；字段/part header/总开销遵守全局限制。
- `GET /api/email/attachments/{messageId}/{attachmentId}` 只接受 GET，要求当前 viewer token，并由 `EmailAttachmentStore.open` 返回流。

- [ ] **Step 1: 写 HTTP 失败测试**

启动临时 JDK `HttpServer`，发送合法 multipart、非 multipart、未知字段、file 早于文本、17 files、超过 20 MiB、非法 UUID/attachmentId 和无 viewer header 的下载请求。断言合法请求只调用一次 mail sender，非法请求返回合同中的 400/413/401/404/405，SMTP 不被调用，下载不泄露 `relativePath`。

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=EmailHttpAttachmentTest test
```

预期：失败，因为 `/api/send/email` 当前只调用 `readJson`，也没有附件下载 route。

- [ ] **Step 3: 实现 parser、route 和启动接线**

在 `startWeb` 创建一个 `EmailAttachmentStore` 并传入 route；`POST /api/send/email` 按 Content-Type 选择 parser，parser 完成暂存和边界校验后调用 `MailSender.send(command)`。新增 download route：先调用现有 viewer actor resolver，再验证 messageId/attachmentId 归属和 state，设置 `Content-Type`、`Content-Disposition: attachment`、`Content-Length`、`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff` 后流式写响应。`HEAD`、Range、query token 和任意路径返回 405/400。

- [ ] **Step 4: 运行通过测试**

```bash
mvn -q -Dtest=EmailHttpAttachmentTest test
```

预期：HTTP 绑定、错误 envelope、SMTP 调用边界、下载 headers 和路径隔离全部通过；本机端口测试需在允许 socket bind 的环境执行。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailMultipartParser.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailHttpAttachmentTest.java
git commit -m "feat: expose email attachment http contract"
```

### Task 6: 前端邮件附件交互

**Files:**
- Create: `demo/message-center-demo/src/test/resources/email-attachment-ui-probe.mjs`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- `renderEmailComposer` 维护 `state.emailAttachments`，每项保存浏览器 `File`、安全展示名和 size。
- `sendEmail` 使用 `FormData` 按 `to`、`subject`、`body`、重复 `file` 提交，不再发送 JSON。
- `renderEmailAttachmentList` 显示数量/总大小、移除按钮和超限错误。
- 邮件详情消费 `message.attachments`；stored 附件用 viewer header fetch Blob 下载，rejected 只显示 errorCode。

- [ ] **Step 1: 写前端失败 probe**

扩展现有 Node VM probe：注入三个 `File`（其中一个被移除），断言 FormData 只含剩余两个文件；注入第 17 个或使总量超过 20 MiB，断言 `fetch` 调用数为 0；模拟 413 保留文件和正文，模拟成功清空两者；详情中只为 stored 附件创建下载请求。

```javascript
state.emailAttachments = [{ name:'a.txt', size:3 }, { name:'b.pdf', size:4 }];
removeEmailAttachment(0);
assert.deepEqual(state.emailAttachments.map(file => file.name), ['b.pdf']);
```

- [ ] **Step 2: 运行失败 probe**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest#emailSendShowsValidationAndFailureFeedback test
```

预期：失败，现有页面没有 file input、FormData 或附件状态。

- [ ] **Step 3: 实现 UI**

在 `renderSendPanel` 的 email 分支增加 `input type=file multiple`、附件列表和总大小；用 `FormData` 替换 JSON body，保留现有收件人/主题校验、禁用发送按钮、失败 toast 和成功刷新。邮件详情 projection 增加附件下载按钮，使用 `currentWeComAuth().viewerAuthToken` 设置 header，Blob 下载结束后调用 `URL.revokeObjectURL`。

- [ ] **Step 4: 运行通过 probe**

```bash
node src/test/resources/email-attachment-ui-probe.mjs
mvn -q -Dtest=UnifiedMessageStoreTest test
```

预期：新增邮件附件 probe 和现有消息存储测试全部通过，且请求不把附件内容放入 JSON 或 URL。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java \
  demo/message-center-demo/src/test/resources/email-attachment-ui-probe.mjs
git commit -m "feat: add email attachment composer and downloads"
```

### Task 7: 文档、配置与 OpenAPI 合同

**Files:**
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentContractTest.java`

**Interfaces:**
- 示例配置包含三个 `EMAIL_ATTACHMENT_*` 配置及 `EMAIL_DATA_DIR` 的附件目录说明，不包含真实凭据。
- OpenAPI 描述 multipart email request、attachment projection、download headers、400/401/405/413/507/502 responses；不公开 `relativePath`、query token 或 inline preview。

- [ ] **Step 1: 写合同失败测试**

在 contract test 中断言 `/api/send/email` 是 multipart、`file` 为可重复 binary、20 MiB/16 个限制和 attachment schema 存在；断言 download operation 的 response content type 是 binary，且文档不含 `relativePath` 或 token query。

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
node contracts/openapi/message-center-v1.test.mjs
```

预期：失败，因为当前 OpenAPI 没有邮件附件 operation/schema。

- [ ] **Step 3: 更新合同和用户文档**

把真实实现路径、配置默认值、收发超限行为、附件保存目录、恢复状态和本地 SMTP/IMAP 验收命令写入 README；OpenAPI 与 `EmailAttachment` 字段保持同名、同状态枚举和同错误码。

- [ ] **Step 4: 运行合同测试**

```bash
node contracts/openapi/message-center-v1.test.mjs
```

预期：合同测试通过，文档不出现占位符、真实凭据或相对路径泄露。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/config.example.env \
  demo/message-center-demo/README.md \
  demo/message-center-demo/contracts/openapi/message-center-v1.yaml \
  demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentContractTest.java
git commit -m "docs: publish email attachment contract"
```

### Task 8: 端到端验收与边界复核

**Files:**
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentEndToEndTest.java`
- Modify: `demo/message-center-demo/README.md` only if final run instructions differ from Task 7

**Interfaces:**
- Fixture must provide local SMTP capture and MIME input, not public SMTP credentials。
- Test consumes `/api/send/email` multipart contract and `EmailSyncService`/`EmailInboxWriter` output, asserting the same attachment bytes.

- [ ] **Step 1: 写端到端失败测试**

发送 `quote.pdf` 和 `photo.jpg` 两个文件，收件 fixture 返回同一封 MIME 邮件；断言数量、文件名、MIME、大小、SHA-256 和邮件 messageId 绑定一致，并验证失败/超限不会调用 SMTP。

- [ ] **Step 2: 运行失败测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=EmailAttachmentEndToEndTest test
```

预期：在所有任务完成前失败，缺失的每一项都要指向对应任务，不得修改测试以掩盖失败。

- [ ] **Step 3: 运行完整门禁**

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
git diff --check
```

预期：全部退出码为 0；若测试需要本机监听，在允许绑定的环境重跑，不把 `SocketException: Operation not permitted` 当成通过。

- [ ] **Step 4: 检查 git 边界**

```bash
git status --short
git diff --cached --name-only
git check-ignore demo/message-center-demo/data-local
```

只允许本功能文件进入暂存区；Spring/React dirty 文件、`data-local` 和其他用户改动必须保持原状。

- [ ] **Step 5: 提交验收记录**

```bash
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EmailAttachmentEndToEndTest.java
git commit -m "test: verify email attachment round trip"
```

## 计划自审

- 设计第 1-5 节由 Tasks 1-5 覆盖：模型、路径、配置、邮件绑定、HTTP 和下载。
- 设计第 6-10 节由 Tasks 6-7 覆盖：限制、MIME、收件分类、下载 headers 和 UI。
- 设计第 11-13 节由 Tasks 1、3、4、8 覆盖：原子发布、recovery journal、启动/容量边界、端到端和完成门禁。
- Spring/React 迁移边界被明确为后续承接合同，未把并行 dirty 文件纳入本计划。
- 已逐项核对占位内容、函数签名和测试命令；每个生产步骤先有失败测试和实际命令。
