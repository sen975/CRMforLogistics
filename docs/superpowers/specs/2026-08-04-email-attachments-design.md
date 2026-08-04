# 消息中心邮件附件设计

**日期：** 2026-08-04  
**状态：** 已确认，待实施  
**适用范围：** `demo/message-center-demo` 当前 pre-split 运行面

## 1. 决策摘要

本期为邮件发送与收取同时补齐附件闭环。单封邮件支持最多 16 个普通附件，附件总大小最多 20 MiB。附件必须绑定对应邮件，不生成独立消息；邮件正文继续使用纯文本，附件只作为标准 MIME `multipart/mixed` 附件，不实现 HTML 富文本或 CID 内嵌图片。

发送接口从 JSON 改为有界、流式 `multipart/form-data`。`MailSender` 负责生成标准 MIME 邮件，邮件附件存储由独立 `EmailAttachmentStore` 负责。收件同步在解析正文的同时提取普通附件。文件内容保存在本地附件目录，邮件 JSONL 只保存结构化附件元数据。

当前 Spring/React 迁移运行面已经开始移植邮件通道，但其目录存在并行未提交改动。本期只修改当前可运行的 `demo/message-center-demo`。本设计中的附件模型、限制和 HTTP 语义是后续 Spring/React 迁移必须承接的合同，不得在迁移层另造第二套附件含义。

## 2. 当前真源与问题

当前邮件发送链路是：

```text
App.java POST /api/send/email JSON
  -> MailSender.send(to, subject, body)
  -> Jakarta Mail text/plain
  -> SMTP
  -> email/inbox.jsonl 发件记录
```

当前收件链路由 `EmailInboxWriter` 从 Jakarta Mail `Message` 提取正文并写入同一邮件 JSONL。邮件目录的当前 owner 是 `Config.emailDataDir()`，由 `EMAIL_DATA_DIR` 配置；附件必须跟随该目录，不能另从 `DATA_DIR` 推导第二个邮件根目录。`UnifiedMessage` 只有适合单个 ChatApp 媒体的 `mediaType`、`mediaUrl`、`mimeType` 和 `fileName` 字段，没有多附件合同。`App.MultipartForm` 会把整个请求读入堆且只保存一个文件，只适合现有小型 ChatApp 媒体路径，不能作为 20 MiB 多附件邮件的 owner。

因此本功能不能通过增加一个前端文件输入完成。必须同时闭合 MIME 生成、流式上传、收件解析、本地存储、邮件关联、下载授权、页面显示、限制和清理。

## 3. 目标与非目标

### 3.1 目标

- 一封发件邮件可选择、移除并发送多个普通附件。
- 单封邮件最多 16 个附件，附件字节总量最多 20 MiB。
- SMTP 邮件使用标准 `multipart/mixed`，正文为 UTF-8 `text/plain`。
- 收件同步保存普通附件，并将其绑定到对应邮件记录。
- 发件和收件使用同一个附件模型、存储和下载合同。
- 邮件详情展示附件列表并允许下载；时间线只显示含附件提示。
- 文件名、请求体、附件数量、单封总量、磁盘路径和下载响应均有明确边界。
- SMTP 失败、收件超限、文件写入失败和非法请求返回或记录稳定错误原因。

### 3.2 非目标

- 不实现 HTML 富文本编辑器。
- 不实现 CID 内嵌图片或 inline MIME 资源展示。
- 不实现附件在线预览、缩略图或内容搜索。
- 不实现云存储、对象存储迁移或跨实例共享目录。
- 不实现病毒扫描或内容安全判定；下载始终按附件处理并禁止 MIME sniffing。
- 不借本功能修改当前并行开发中的 Spring/React dirty 文件。

## 4. 架构与唯一 Owner

```text
邮件编辑器
  -> App.java 邮件 multipart adapter
  -> EmailAttachmentStore.stage(inputStream, metadata)
  -> MailSender.send(command, attachments)
       -> Jakarta Mail multipart/mixed
       -> SMTP
       -> EmailAttachmentStore.publish(messageId, stagedDirectory)
       -> email/inbox.jsonl outgoing row

IMAP / OpenSSL 收件
  -> EmailInboxWriter
       -> MIME body/attachment classifier
       -> EmailAttachmentStore.stage(inputStream, metadata)
       -> EmailAttachmentStore.publish(messageId, stagedDirectory)
       -> email/inbox.jsonl incoming row

邮件详情
  -> UnifiedMessage.attachments
  -> GET /api/email/attachments/{messageId}/{attachmentId}
  -> EmailAttachmentStore.open(messageId, attachmentId)
```

| 组件 | 唯一职责 | 禁止拥有的语义 |
|---|---|---|
| `EmailAttachment` | 附件公开元数据与存储状态 | 任意客户端文件路径、SMTP 会话 |
| `EmailAttachmentStore` | 有界暂存、目录发布、打开、清理和路径校验 | MIME 结构、联系人、UI 文案 |
| `MailSender` | 发件校验、MIME 组装、SMTP 发送和发件记录提交 | multipart HTTP 解析、下载路由 |
| `EmailInboxWriter` | 收件 MIME 分类、正文提取和邮件记录提交 | HTTP 上传、页面展示 |
| `UnifiedMessageStore` | 从邮件 JSONL 投影邮件及附件列表 | 二进制文件读取、MIME 推断 |
| `App.java` | HTTP 参数映射、认证、下载响应和页面接线 | 附件路径规则、MIME 业务规则 |

## 5. 附件模型与邮件绑定

新增不可变附件模型：

```text
EmailAttachment
  id             UUID，由服务端生成
  fileName       原文件名的安全展示值
  mimeType       规范化 MIME；未知时 application/octet-stream
  sizeBytes      非负字节数
  relativePath   仅 state=stored 时存在，只在服务端使用
  state          stored | rejected
  errorCode      rejected 时的稳定原因
```

`UnifiedMessage` 增加 `attachments` 列表，默认空列表，不能复用只表示单媒体的 `mediaType`/`mediaUrl` 字段。邮件 JSONL row 增加同名数组。公开 API 投影附件时只返回 `id`、`fileName`、`mimeType`、`sizeBytes`、`state`、`errorCode` 和服务端派生的 `downloadUrl`，不得返回 `relativePath`。

每封邮件在保存附件前取得稳定本地 `messageId`。附件目录固定在 `Config.emailDataDir()` 下：

```text
EMAIL_DATA_DIR/
├── inbox.jsonl
├── attachments/
│   └── <messageId>/
│       └── <attachmentId>-<safeFileName>
├── attachment-tmp/
│   └── <requestId>/
└── attachment-recovery/
    └── <messageId>.json
```

路径只使用服务端生成的 UUID 和净化后的末级文件名。读取时以 `messageId + attachmentId` 查询邮件元数据，再解析服务端 `relativePath`；客户端不能传入或覆盖相对路径。重复文件名通过不同附件 UUID 区分，展示名保持原值。

## 6. 限制与配置

新增配置：

```text
EMAIL_ATTACHMENT_MAX_TOTAL_BYTES=20971520
EMAIL_ATTACHMENT_MAX_COUNT=16
EMAIL_ATTACHMENT_STORAGE_MAX_BYTES=10737418240
```

- `EMAIL_ATTACHMENT_MAX_TOTAL_BYTES` 范围为 1 MiB 到 20 MiB，默认和最大值均为 20 MiB。
- `EMAIL_ATTACHMENT_MAX_COUNT` 范围为 1 到 16，默认和最大值均为 16。
- `EMAIL_ATTACHMENT_STORAGE_MAX_BYTES` 默认 10 GiB，范围为 20 MiB 到 1 TiB；达到总预算时不自动删除历史附件。
- 单个附件没有独立更大上限，其大小受单封总量限制。
- `to` 最多 2,048 UTF-8 bytes，`subject` 最多 998 UTF-8 bytes，`body` 最多 1 MiB；单个 part header 最多 8 KiB，multipart 非文件开销最多 128 KiB。20 MiB 只表示附件内容预算。
- 发送端必须在连接 SMTP 前完成数量与总量校验。
- 收件端按实际读取字节累计，不相信 MIME 声明的大小。
- 空文件允许发送和保存，但仍计入附件数量。
- MIME 类型由 Jakarta Mail part 或上传 header 提供，只作为展示和下载元数据，不作为安全判断。
- 发件发布前在同一存储锁内核算总预算，空间不足时在 SMTP 前返回 `EMAIL_ATTACHMENT_STORAGE_FULL`；收件空间不足时保留正文并产生有界 `rejected` 条目。

## 7. 发件 HTTP 与 MIME 合同

`POST /api/send/email` 改为：

```text
Content-Type: multipart/form-data

to       必填，一个 UTF-8 文本字段
subject  必填，一个 UTF-8 文本字段
body     可空，一个 UTF-8 文本字段
file     可重复，0 到 16 个文件，文件 part 必须位于文本字段之后
```

adapter 使用 Apache Commons FileUpload 的流式 API，不能使用当前会整体读取请求的 `App.MultipartForm`。处理顺序固定为：

1. 验证 Content-Type、boundary、文本字段白名单、字段大小和顺序。
2. 为请求创建唯一暂存目录。
3. 按流读取每个 `file`，同时累计数量和实际总字节。
4. 超过任一上限立即停止、清理整个暂存目录并返回 413 或 400；SMTP 不得被调用。
5. 生成稳定本地 `messageId` 与各附件 UUID。
6. `MailSender` 构造 `multipart/mixed`：第一个 body part 是 UTF-8 `text/plain`，随后按用户选择顺序添加附件。
7. SMTP 成功后发布整个附件目录并写发件 JSONL row；页面收到带 `attachments` 的邮件投影。
8. SMTP 失败时清理暂存目录，不写成功发件记录。

成功保持 200。稳定错误包括：

```text
EMAIL_MULTIPART_REQUIRED       400
EMAIL_MULTIPART_FIELD_INVALID 400
EMAIL_ATTACHMENT_COUNT_LIMIT  413
EMAIL_ATTACHMENT_SIZE_LIMIT   413
EMAIL_ATTACHMENT_STORAGE_FULL 507
EMAIL_ATTACHMENT_STORE_FAILED 500
EMAIL_SEND_FAILED             502
```

SMTP 已接受邮件但本地记录提交失败无法回滚远端发送。发送前必须在 `EMAIL_DATA_DIR/attachment-recovery/<messageId>.json` 原子写入 `prepared` 恢复记录；`Transport.sendMessage` 正常返回后立即原子更新为 `accepted`，附件目录发布并写入邮件 JSONL 后删除恢复记录。启动时只自动修复 `accepted` 且 JSONL 尚无对应 messageId 的记录；`prepared` 表示结果未知，不自动重发，避免重复邮件。该阶段返回 `EMAIL_SENT_HISTORY_FAILED` 或 `EMAIL_SEND_OUTCOME_UNKNOWN`，日志只记录 Message-ID 和非敏感 stage；不得把结果谎报为“邮件未发送”。启动对账有记录数上界，并清理超过 1 小时且无邮件或恢复记录引用的暂存/孤儿目录。

## 8. 收件解析合同

`EmailInboxWriter` 对 MIME part 做明确分类：

- `Part.ATTACHMENT` 或带非空文件名且不是 `Part.INLINE` 的 part 视为普通附件。
- `Part.INLINE`、带 Content-ID 的正文资源本期不保存为附件，也不进入正文。
- `multipart/alternative` 优先选择 `text/plain`；没有纯文本时才把 HTML 转成文本。
- 普通 `multipart/*` 递归遍历，但附件 part 不能再次作为正文读取。

每封收件邮件先取得稳定本地 `messageId`，再把附件流写入该邮件的暂存目录。合法附件按出现顺序保存。出现下列情况时邮件正文仍必须入库：

- 超过第 16 个附件；
- 累计附件实际字节超过 20 MiB；
- 单个附件读取失败；
- 文件存储失败但正文仍可解析。

未保存的附件在 `attachments` 中保留 `state=rejected`、安全文件名、已知 MIME/大小和稳定 `errorCode`；没有可靠大小时使用 0，并以状态表明不可下载。稳定拒绝原因是 `EMAIL_ATTACHMENT_COUNT_LIMIT`、`EMAIL_ATTACHMENT_SIZE_LIMIT`、`EMAIL_ATTACHMENT_STORAGE_FULL`、`EMAIL_ATTACHMENT_READ_FAILED` 或 `EMAIL_ATTACHMENT_STORE_FAILED`。为阻断恶意 MIME part 无界增长，收件投影最多保存 16 个 `stored` 条目和第 1 个 `rejected` 条目；孤立读取失败后可以继续保存后续合法附件，但不再追加第二个 rejected 元数据。出现数量、总量或存储预算拒绝后，其余附件流只做有界排空，不再追加元数据或写盘。已成功保存的附件不因后续一个附件失败而删除。邮件 JSONL append 失败时，删除本次刚发布的整个邮件附件目录，避免无主数据。

## 9. 附件下载合同

```text
GET /api/email/attachments/{messageId}/{attachmentId}
X-WeCom-Viewer-Auth: <current viewer token>
```

服务端先验证当前 viewer，再从 `UnifiedMessageStore` 查找邮件和附件归属，最后由 `EmailAttachmentStore` 打开文件。不存在、被拒绝、归属不匹配或路径非法都不能泄露真实磁盘路径。

成功响应：

```text
Content-Type: <stored mimeType or application/octet-stream>
Content-Disposition: attachment; filename*=UTF-8''<percent-encoded-file-name>
Content-Length: <actual size>
Cache-Control: private, no-store
X-Content-Type-Options: nosniff
```

下载不接受 query token、客户端路径或任意文件名。`relativePath` 不进入响应、日志或 DOM。附件不提供 inline disposition、Range、预览或公共 URL。

## 10. 前端交互

邮件编辑器保留现有收件人、主题和纯文本正文，并增加：

- `multiple` 文件输入；
- 已选附件列表，显示文件名和大小；
- 删除单个附件的图标按钮；
- 当前附件数与总大小；
- 超过 16 个或 20 MiB 时的就地错误状态。

发送使用 `FormData`，按 `to`、`subject`、`body`、重复 `file` 的顺序提交。发送期间禁用编辑和发送按钮；失败保留用户已选文件与正文，成功后才清空正文和附件选择。

时间线邮件项只显示“含 N 个附件”提示。右侧邮件详情列出所有附件：`stored` 显示下载按钮，`rejected` 显示稳定原因且不显示下载按钮。下载使用带 viewer header 的 `fetch` 获取 Blob，再创建短时 object URL；完成后释放 URL，不把 viewer token 放入下载 URL。

## 11. 数据完整性与恢复

- 暂存目录与最终目录必须位于同一文件系统，发布采用目录级原子移动。
- 最终目录存在时失败关闭，禁止覆盖已有邮件附件。
- 附件实际 SHA-256 可在流式写入时计算并仅用于完整性对账；本期不向 UI 暴露，也不基于 hash 去重。
- JSONL 仍是当前邮件记录真源；附件目录只保存被 JSONL 或显式恢复记录引用的内容。
- 启动对账每轮最多读取 1,024 个恢复记录或附件目录，超过上界时附件子系统失败关闭并返回结构化不可用，不能把部分扫描伪装为完整事实。
- 清理只删除明确位于 `attachment-tmp` 的过期目录，或超过宽限期且无任何邮件/恢复记录引用的附件目录。
- 文件名、正文、邮件地址和附件内容不得写入错误日志；日志只记录 messageId、attachmentId、stage、大小和稳定错误码。

## 12. 测试与验收

### 12.1 核心与 MIME

- 纯文本、零附件仍生成合法单体邮件。
- 多附件生成 `multipart/mixed`，正文为首个 part，附件顺序、字节、MIME 和 UTF-8 文件名正确。
- 重复展示文件名不冲突。
- SMTP 失败清理暂存目录且不写成功发件记录。

### 12.2 HTTP 与存储

- multipart 多文件上传成功并返回附件投影。
- 非 multipart、未知字段、文件早于文本字段、17 个附件和超过 20 MiB 分别被拒绝。
- 超限请求在 SMTP 调用前失败。
- `../`、反斜杠、控制字符和超长文件名不能逃逸附件目录。
- 下载验证 viewer、邮件归属、附件归属和 `stored` 状态。
- 下载响应包含 attachment disposition、真实长度、no-store 和 nosniff，且不泄露 `relativePath`。

### 12.3 收件

- `multipart/mixed` 中正文与多个普通附件同时入库。
- 嵌套 `multipart/alternative` 只选择一个正文版本。
- 文本附件不混入邮件正文。
- inline/CID part 不作为普通附件保存。
- 第 17 个附件、累计超过 20 MiB 和单附件读取失败时，正文与此前合法附件仍保存，并最多保留 1 个有界 `rejected` 条目。
- 收件 JSONL 写入失败不会留下无主最终目录。

### 12.4 前端

- 可多选、追加、删除附件并准确计算总大小。
- 超过数量或大小上限时不发起请求。
- 发送失败保留表单，成功后清空。
- 时间线显示附件数量；详情中 stored 可下载、rejected 不可下载。
- 下载失败显示可见错误且释放临时 object URL。

### 12.5 验收门禁

```bash
cd demo/message-center-demo
mvn -q test
```

另使用本地 SMTP/IMAP fixture 完成真实字节往返：发送两个不同类型附件，收回同一邮件，验证附件数量、文件名、MIME、大小和 SHA-256 一致。浏览器验收桌面和窄屏下的文件列表、移除、超限、发送失败、详情下载，确认按钮可操作且文本不溢出。

## 13. 完成定义

只有同时满足以下条件才能声称本阶段完成：

- 发件多附件、20 MiB/16 个上界和 SMTP MIME 合同通过自动测试。
- 收件附件解析、保存、拒绝状态和正文保留通过自动测试。
- 附件与邮件的稳定绑定、下载授权和路径隔离通过 HTTP 测试。
- 前端选择、移除、校验、发送和详情下载通过行为及真实浏览器验收。
- 完整 `mvn -q test` 通过，无新增 warning、未完成标记、占位符或生成物漂移。
- README、示例配置和 OpenAPI 合同同步实现后的最终行为。
- git 暂存范围只包含本功能文件，不吸入 Spring/React 并行 dirty 改动。
