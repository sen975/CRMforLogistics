# 消息中心 Spring 邮件附件闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or **superpowers:executing-plans**. Steps use checkbox syntax and each task ends with a focused test cycle.

**Goal:** 在 `demo/message-center-spring` 完成邮件附件发件、收件、MinIO/数据库关联、右侧预览和鉴权下载闭环。

**Architecture:** 复用现有 `attachments` 表、`AttachmentEntity`、`AttachmentMapper` 和 `MinioStorage` 作为唯一附件 owner。邮件服务负责 MIME/SMTP 协议，附件服务负责有界读取、MinIO 对象和数据库状态，控制器只做 JSON/multipart 与预览/下载映射，React 右侧只消费公开附件 DTO。

**Tech Stack:** Java 17、Spring Boot 3.4.5、Jakarta Mail、MyBatis-Plus、PostgreSQL、MinIO、JUnit 5、Mockito、React 18、TypeScript、Ant Design、Axios、Vitest。

## Global Constraints

- 单封最多 16 个普通附件，实际附件总字节最多 20 MiB（20,971,520 bytes）。
- 外部附件流使用有界 8 KiB buffer；生产代码禁止无界 `readAllBytes()`。
- 普通附件分类为 `image | video | audio | document | archive | other`，由服务端 MIME 产生。
- `Part.INLINE` 或 Content-ID 资源不进入普通附件列表。
- 图片、音频、视频在右侧预览；其他类型显示文件名、大小和下载按钮。
- 所有预览和下载都先验证当前用户对附件所属会话的权限。
- 不实现 Range、公开 MinIO URL、query token 或历史附件自动补采。
- `/api/send/email` 是主入口；`/api/email/send` 保留为共享同一服务命令的兼容 adapter，不在本轮删除。
- 工作区已有大量 dirty 文件；修改已有文件时按 hunk 合并，禁止覆盖、回滚或 `git add .`。
- 每个任务执行 RED -> GREEN -> REFACTOR；提交只包含任务列出的文件。

## File Map

- 新增：`channel/email/EmailAttachmentInput.java`、`EmailAttachmentPayload.java`、`EmailAttachmentReader.java`、`EmailException.java`、`EmailAttachmentStore.java`、`EmailMimeParser.java`、`EmailMessageStore.java`、`EmailSendCommand.java`、`EmailTransportGateway.java`。
- 修改后端：`AppConfig.java`、`application.yml`、`MinioStorage.java`、`AttachmentMapper.java`、`MessageMapper.java`、`EmailSyncService.java`、`EmailSendService.java`、`EmailController.java`、`MessageController.java`、`MediaController.java`、`GlobalExceptionHandler.java`。
- 新增前端：`EmailAttachmentList.tsx` 及测试。
- 修改前端：`endpoints.ts`、`ContactDetailPanel.tsx`、`SendForm.tsx` 及相关测试。

---

### Task 1: 有界附件合同与 MIME 分类元数据

**Files:** 新增 `EmailAttachmentInput.java`、`EmailAttachmentPayload.java`、`EmailAttachmentReader.java`、`EmailException.java`、`EmailAttachmentReaderTest.java`、`EmailAttachmentConfigTest.java`；修改 `AppConfig.java`、`application.yml`。

**Interfaces:** `EmailAttachmentInput(String fileName, String mimeType, long declaredSize, InputStreamOpener opener)`；`EmailAttachmentReader.read(List<EmailAttachmentInput>)`；`EmailAttachmentPayload(fileName, mimeType, mediaKind, bytes, sha256)`；配置 `emailAttachmentMaxCount=16`、`emailAttachmentMaxTotalBytes=20971520`。

- [ ] RED：测试正常读取两项并保持顺序；`../照片.png` 规范化为 `照片.png`；`image/png` 和 `audio/mpeg` 分类正确；第 17 项在打开流前抛 `EMAIL_ATTACHMENT_COUNT_LIMIT`；实际字节超过预算抛 `EMAIL_ATTACHMENT_SIZE_LIMIT`。
- [ ] 运行：`cd demo/message-center-spring/backend && mvn -q -Dtest=EmailAttachmentReaderTest test`。预期因类型不存在而失败。
- [ ] GREEN：使用 8 KiB buffer、实际累计字节、SHA-256；文件名去路径/控制字符并限制 255 UTF-8 bytes；无效 MIME 回退 `application/octet-stream`；分类规则固定为 image/video/audio、zip/rar/7z/gzip archive、text/pdf/Office document、其余 other。
- [ ] 运行：`mvn -q -Dtest=EmailAttachmentReaderTest,EmailAttachmentConfigTest test`，预期 PASS。
- [ ] 提交：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentInput.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentPayload.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentReader.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailException.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java demo/message-center-spring/backend/src/main/resources/application.yml demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentReaderTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentConfigTest.java && git commit -m "feat: define bounded email attachment contract"`。

### Task 2: MinIO 与 attachments 状态协调

**Files:** 新增 `EmailAttachmentStore.java`、`EmailAttachmentStoreTest.java`；修改 `MinioStorage.java`、`AttachmentMapper.java`。

**Interfaces:** `MinioStorage.store(String objectKey, InputStream input, long size, String contentType)`；`EmailAttachmentStore.store(UUID messageId, List<EmailAttachmentPayload> payloads, boolean ready)`；`markReady(UUID messageId, Instant readyAt)`；`removeMessageObjects(UUID messageId)`。

- [ ] RED：Mockito 测试验证对象 key 为 `email/<messageId>/<attachmentId>/<safeName>`，先 MinIO 后 insert；insert 失败调用 remove；ready 只在状态完整后可见。
- [ ] 运行：`mvn -q -Dtest=EmailAttachmentStoreTest test`，预期因 store 与流式 MinIO API 不存在而失败。
- [ ] GREEN：保留 byte[] overload 并委托已知长度流式 put；Mapper 增加 `markReadyByMessageId`、`listByMessageId`、`markDeletedByMessageId`；批量失败倒序补偿，补偿失败只记录 messageId/attachmentId/stage/code。
- [ ] 运行：`mvn -q -Dtest=EmailAttachmentStoreTest,ChatAppMediaApplicationServiceTest test`，预期 PASS。
- [ ] 提交：只暂存本任务四个文件，提交 `feat: persist email attachments in minio`。

### Task 3: 收件 MIME 正文与附件解析

**Files:** 新增 `EmailMimeParser.java`、`EmailMimeParserTest.java`。

**Interfaces:** `EmailMimeParser.parse(Part)` 返回 `ParsedEmail(bodyText, attachments, errorCodes)`。

- [ ] RED：构造 `multipart/alternative`（plain + html）和嵌套 `multipart/mixed`；断言优先 plain、普通附件按出现顺序返回；inline/CID 不返回；文本附件不混入正文。
- [ ] 运行：`mvn -q -Dtest=EmailMimeParserTest test`，预期因 parser 不存在而失败。
- [ ] GREEN：先判断普通附件，再判断正文；alternative 只选一个正文，其他 multipart 递归；读取失败记录 `EMAIL_ATTACHMENT_READ_FAILED` 并继续，数量/总量超限停止存储但保留正文。
- [ ] 运行：`mvn -q -Dtest=EmailMimeParserTest test`，预期 PASS。
- [ ] 提交：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailMimeParser.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailMimeParserTest.java && git commit -m "feat: parse email body and attachments"`。

### Task 4: 收件消息与附件提交

**Files:** 新增 `EmailMessageStore.java`、`EmailMessageStoreTest.java`；修改 `EmailSyncService.java`、`MessageMapper.java`、`EmailSyncServiceTest.java`。

**Interfaces:** `persistInbound(InboundEmail command)`；`reconcileSentCopy(UUID messageId, String providerMessageId, Instant occurredAt)`。

- [ ] RED：测试消息先 `insertWithSequence` 再 attachment store；附件失败仍保存正文并返回 saved；Sent folder 已有相同 provider ID 时只 finalize，不重复 insert。
- [ ] 运行：`mvn -q -Dtest=EmailMessageStoreTest,EmailSyncServiceTest test`，预期失败。
- [ ] GREEN：`EmailSyncService` 调 parser；重复依据当前 account 的 `provider_message_id`；消息字段为 email/inbound 或 outbound、delivered 或 sent；错误码写有限 `metadata_jsonb`；事务失败清理本批附件对象。
- [ ] 运行：`mvn -q -Dtest=EmailMessageStoreTest,EmailSyncServiceTest,MessageQueryServiceTest test`，预期 PASS。
- [ ] 提交：只暂存本任务列出的文件，提交 `feat: persist received email attachments`。

### Task 5: 发件 multipart/mixed 与双 adapter

**Files:** 新增 `EmailSendCommand.java`、`EmailTransportGateway.java`；修改 `EmailSendService.java`、`EmailController.java`、`MessageController.java`、`GlobalExceptionHandler.java`；修改 `EmailSendServiceTest.java`、`EmailControllerTest.java`、`MessageControllerAuthorizationTest.java`。

**Interfaces:** `EmailSendService.send(EmailSendCommand)`；JSON 与 multipart 都映射同一 command；主路由 `/api/send/email`、兼容路由 `/api/email/send`。

- [ ] RED：服务测试断言 `multipart/mixed` body 为第一个 part，附件顺序和字节一致；控制器 multipart 测试断言重复 file 映射到 command；SMTP 已接受后本地 finalization 失败返回 `EMAIL_SENT_HISTORY_FAILED`，不删除附件。
- [ ] 运行：`mvn -q -Dtest=EmailSendServiceTest,EmailControllerTest,MessageControllerAuthorizationTest test`，预期失败。
- [ ] GREEN：controller 只把 MultipartFile 元数据和 opener 传入 command；服务在 SMTP 前完成数量/大小校验和 pending 存储，SMTP 成功后 mark sent + ready；sendMessage 异常标 `EMAIL_SEND_OUTCOME_UNKNOWN`；已接受后 DB 失败标 `EMAIL_SENT_HISTORY_FAILED`；保留旧 JSON 行为且无第二套业务逻辑。
- [ ] 运行：同一测试命令，预期 PASS。
- [ ] 提交：`feat: send email attachments through smtp`，只 stage 精确列出的文件。

### Task 6: 鉴权预览与下载接口

**Files:** 修改 `MediaController.java`；修改 `MediaControllerTest.java`。

**Interfaces:** `GET /api/media/{id}` inline；新增 `GET /api/media/{id}/download` attachment。

- [ ] RED：测试 download 返回 `attachment`、UTF-8 文件名、实际 Content-Length、`private, no-store` 和 `nosniff`；越权时 storage.get 不被调用。
- [ ] 运行：`mvn -q -Dtest=MediaControllerTest test`，预期因 download 不存在而失败。
- [ ] GREEN：抽共享 readable lookup；先 `findReadableById` 再 MinIO get；预览/下载仅 disposition 不同；对象缺失不泄露 key。
- [ ] 运行：`mvn -q -Dtest=MediaControllerTest,MessageQueryServiceTest test`，预期 PASS。
- [ ] 提交：`feat: add authenticated attachment downloads`。

### Task 7: 右侧图片/音频/视频/文件展示

**Files:** 新增 `EmailAttachmentList.tsx`、`EmailAttachmentList.test.tsx`、`ContactDetailPanel.test.tsx`；修改 `endpoints.ts`、`ContactDetailPanel.tsx`。

**Interfaces:** `fetchMediaBlob(id): Promise<Blob>`；`downloadAttachment(id, fileName): Promise<void>`；`<EmailAttachmentList attachments={...} />`。

- [ ] RED：测试四种附件渲染 image、audio controls、video controls、文件项和四个下载按钮；预览失败仍保留下载按钮；详情正文下方出现附件区域。
- [ ] 运行：`cd demo/message-center-spring/frontend && npx vitest run src/components/EmailAttachmentList.test.tsx`，预期失败。
- [ ] GREEN：Axios Blob 请求沿用 bearer interceptor；每项独立 object URL 并在切换/卸载/错误时 revoke；图片使用 Ant Image 放大；音视频原生 controls；普通文件不预览；下载失败显示可见错误。
- [ ] 运行：`npx vitest run src/components/EmailAttachmentList.test.tsx src/components/ContactDetailPanel.test.tsx src/components/MessageBubble.test.tsx`，预期 PASS。
- [ ] 提交：`feat: preview and download email attachments`，只 stage 本任务文件。

### Task 8: 发件表单上界与失败保留

**Files:** 修改 `SendForm.tsx`、`SendForm.test.tsx`、`endpoints.ts`。

**Interfaces:** `sendEmail({to, subject, body, attachments})` 保持对调用者兼容；常量 `EMAIL_ATTACHMENT_MAX_COUNT=16`、`EMAIL_ATTACHMENT_MAX_TOTAL_BYTES=20971520`。

- [ ] RED：测试上传第 17 项或总量超限不调用 sendEmail；发送失败后主题、正文和文件仍存在；成功后才清空。
- [ ] 运行：`npx vitest run src/components/SendForm.test.tsx`，预期失败。
- [ ] GREEN：beforeUpload 计算合并后的 count/size，返回 `Upload.LIST_IGNORE` 并显示中文错误；handleEmail 只在 await 成功后 resetFields/clear files；失败保留状态。
- [ ] 运行：`npx vitest run src/components/SendForm.test.tsx`，预期 PASS。
- [ ] 提交：`feat: enforce email attachment form limits`。

### Task 9: 文档、全量测试和浏览器验收

**Files:** 修改 `docs/superpowers/specs/2026-08-13-message-center-spring-email-attachments-design.md`；不创建不存在的 Spring README，也不修改无关文档。

- [ ] 文档写明 multipart 字段、16/20 MiB、`/api/media/{id}`、`/download`、兼容路由和历史不补采。
- [ ] 后端：`cd demo/message-center-spring/backend && mvn test`，预期 BUILD SUCCESS，0 failures/errors。
- [ ] 前端：`cd demo/message-center-spring/frontend && npm test && npm run build`，预期测试和 TypeScript/Vite 构建均 exit 0。
- [ ] SMTP/IMAP fixture 往返验证 PNG、MP3、PDF 的数量、顺序、MIME、大小、SHA-256、message_id 和 ready 状态。
- [ ] 浏览器桌面/窄屏验证图片放大、音视频播放、文件下载、预览/下载错误态、超限不发请求、发件失败保留表单。
- [ ] Git 门禁：`git status --short && git diff --check`；只 stage 本功能文件，现有 dirty 改动保持不变；提交 `docs: document spring email attachments`。
