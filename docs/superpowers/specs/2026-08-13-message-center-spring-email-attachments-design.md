# 消息中心 Spring 邮件附件闭环设计

**日期：** 2026-08-13
**状态：** 已确认，待实施
**适用范围：** `demo/message-center-spring`

## 1. 决策摘要

在 Spring 运行面补齐邮件附件完整闭环：发件上传、SMTP MIME 组装、收件 MIME 解析、MinIO 存储、数据库关联、右侧详情预览和鉴权下载。

邮件附件复用现有 `attachments` 表、`AttachmentEntity`、`AttachmentMapper` 和 `MinioStorage`，不创建邮件专属的第二套附件模型。附件是消息领域中绑定 `message_id` 的一等资源；前端只消费服务端返回的附件元数据和受鉴权保护的媒体接口，不接触 bucket 或 object key。

## 2. 当前问题与证据

当前 Spring 邮件链路没有闭合附件合同：

- `EmailSyncService` 只从 Jakarta Mail `Part` 提取正文，没有识别、保存或关联普通附件。
- `EmailSendService` 只发送 `text/plain`，`MessageController` 的邮件接口仍按 JSON 请求处理。
- 前端 `sendEmail` 已在存在文件时发送 multipart，但后端没有对应 multipart 合同。
- `MessageQueryService` 可以投影已就绪附件，但邮件链路不会创建这些附件记录。
- `ContactDetailPanel` 的右侧消息详情没有附件区域。
- `/api/media/{id}` 支持鉴权后的 inline 响应，但没有明确的下载响应合同。

因此问题根因位于邮件收发、附件存储、HTTP 访问和前端详情四个边界，不能只修改右侧组件。

## 3. 目标与非目标

### 3.1 目标

- 发件支持 0 到 16 个普通附件，附件总大小不超过 20 MiB。
- SMTP 使用标准 `multipart/mixed`，正文为 UTF-8 文本 part，附件保持选择顺序。
- 收件从 MIME 中区分正文、普通附件和 inline/CID 资源。
- 普通附件写入 MinIO，并通过 `attachments.message_id` 绑定邮件。
- 邮件详情返回可访问附件的安全元数据。
- 右侧详情支持图片、音频和视频预览，其他文件显示文件信息。
- 所有已保存附件均可通过独立下载按钮下载。
- 预览和下载都执行当前用户对附件所属会话的服务端授权校验。
- 存储、数据库、解析和下载失败具有可验证、结构化的错误行为。

### 3.2 非目标

- 不在线解析或渲染 PDF、Office、压缩包等文档内容。
- 不展示 CID 内嵌图片或其他 inline 正文资源。
- 不实现病毒扫描、内容安全识别或附件内容搜索。
- 不提供公开 MinIO URL、客户端 object key 或 query token。
- 不实现 Range 分段请求；大媒体的流式拖动不属于本期验收。
- 不自动补采历史上已经同步但未保存附件的邮件。重新同步能够重新进入收件合同的邮件，以及新收取邮件，才应用新行为。

## 4. Owner 与组件边界

| 组件 | 唯一职责 | 禁止拥有的语义 |
|---|---|---|
| `attachments` / `AttachmentEntity` | 消息附件元数据、存储状态和消息归属 | UI 展示状态、SMTP 会话 |
| 邮件附件存储服务 | 有界读取、MinIO 写入、摘要计算、附件记录提交和失败补偿 | MIME 正文选择、HTTP 响应 |
| `EmailSyncService` | 收件 MIME 分类、邮件消息创建、调用附件存储 | MinIO 路径规则、前端文案 |
| `EmailSendService` | 发件校验、MIME 组装、SMTP 发送、消息与附件提交 | multipart HTTP 解析、下载响应 |
| `MessageQueryService` | 组装授权后的消息详情与附件公开投影 | 二进制读取、媒体类型猜测 |
| `MediaController` | 附件授权、预览/下载响应映射 | 邮件 MIME 解析、对象写入 |
| `ContactDetailPanel` | 根据结构化附件元数据呈现预览、下载和错误状态 | 对象路径、授权结论、附件存储状态机 |

附件的存储、状态和消息绑定只有一套 owner。邮件、ChatApp 和其他渠道可复用该合同，但各渠道仍负责自己的协议解析。

## 5. 数据合同

沿用现有 `attachments` 表字段：

- `message_id`：附件所属消息。
- `bucket`、`object_key`：仅服务端存储层使用。
- `original_name`、`mime_type`、`size_bytes`、`sha256`：文件元数据。
- `media_kind`：`image | video | audio | document | archive | other`。
- `storage_status`：沿用 `pending | ready | failed | deleted`。
- `failure_code`、`failure_message`：结构化失败信息；不得包含附件内容或敏感路径。

公开的 `MessageAttachmentResponse` 继续只返回：

```text
id
mediaKind
mimeType
fileName
sizeBytes
```

前端根据 `mediaKind` 选择组件。服务端根据经过规范化的 MIME 类型确定 `mediaKind`，前端不得通过文件扩展名创造第二套分类真相。

只有 `storage_status=ready` 且未删除的附件进入普通消息详情。收件时单个附件失败不会阻止正文和其他成功附件入库；失败原因保存在附件记录或同步诊断中，不伪装成可下载资源。

## 6. 收件数据流

```text
IMAP Message
  -> EmailSyncService MIME 分类
  -> 选择正文 text/plain；无纯文本时 HTML 转文本
  -> 创建稳定 MessageEntity
  -> 对普通附件逐个执行有界流读取
  -> 邮件附件存储服务写入 MinIO
  -> 写 attachments(message_id, metadata, ready)
  -> MessageQueryService 投影到右侧详情
```

MIME 分类规则：

- `Part.ATTACHMENT`，或具有文件名且不是 `Part.INLINE` 的 part，视为普通附件。
- `Part.INLINE` 或含 Content-ID 的正文资源不进入普通附件列表。
- `multipart/alternative` 只选择一个正文版本，优先 `text/plain`。
- 其他 `multipart/*` 递归遍历；附件 part 不得再次作为正文读取。
- 单封最多保存 16 个附件，实际读取字节总量最多 20 MiB；不相信 MIME 声明大小。
- 达到数量或总量上限后停止存储后续附件，但邮件正文和此前成功附件仍提交。

MinIO 对象写入成功但附件数据库记录失败时，必须删除本次对象。消息提交失败时，必须清理本次已经写入的附件对象和记录，避免无主对象。

## 7. 发件数据流

发件接口同时接受无附件和有附件请求，但服务层使用同一个发送命令：

```text
POST /api/send/email
  -> MessageController 映射 multipart 文本字段与重复 file
  -> 邮件附件存储服务校验数量、实际总字节和元数据
  -> EmailSendService 生成 multipart/mixed
  -> SMTP 接受
  -> 提交 MessageEntity、MinIO 对象和 attachments 记录
```

HTTP multipart 字段为：

```text
to       必填文本
subject  可空文本
body     可空文本
file     可重复，0 到 16 个
```

无附件请求允许继续使用 JSON，控制器将两种协议映射为同一个应用服务命令，核心发送语义不分叉。

发件必须在连接 SMTP 前完成附件数量和 20 MiB 总量校验。SMTP 未接受时不写成功消息记录。SMTP 已接受但本地提交失败属于发送结果已发生、历史记录失败，必须返回专门的结构化错误，不能谎报为“邮件未发送”。实施计划必须为该不可回滚边界设计恢复或对账记录。

## 8. 预览与下载合同

### 8.1 预览

```text
GET /api/media/{attachmentId}
```

- 使用当前登录用户身份调用 `AttachmentMapper.findReadableById`。
- 成功响应使用附件 MIME 类型和 `Content-Disposition: inline`。
- 图片、音频和视频组件通过带认证的 Blob 请求取得临时 object URL。
- object URL 在附件切换、失败和组件卸载时释放。

### 8.2 下载

```text
GET /api/media/{attachmentId}/download
```

- 与预览使用完全相同的附件归属和会话授权。
- 返回 `Content-Disposition: attachment`，使用 UTF-8 安全文件名。
- 返回实际 `Content-Type`、`Content-Length`、`Cache-Control: private, no-store` 和 `X-Content-Type-Options: nosniff`。
- 前端以认证请求取得 Blob 后触发下载，不暴露 token 或 MinIO 地址。

不存在、未就绪、已删除、越权或对象缺失均返回结构化不可用结果，不泄露 bucket、object key 或磁盘信息。

## 9. 右侧详情交互

邮件正文下方新增附件区域，按邮件中的附件顺序展示：

- 图片：内嵌缩略预览，支持查看大图，并显示独立下载按钮。
- 音频：原生音频播放器，并显示文件名、大小和下载按钮。
- 视频：内嵌视频播放器，并显示文件名、大小和下载按钮。
- 文档、压缩包和其他类型：文件图标、文件名、大小和下载按钮，不在线解析。
- 加载中使用稳定尺寸骨架或占位，不能引起详情布局跳动。
- 单个附件预览失败只影响该附件，仍保留下载操作；下载失败显示可见错误。
- 桌面与窄屏均不得出现文件名、播放器或按钮溢出。

时间线消息气泡保持紧凑，只显示附件数量或已有媒体摘要；完整附件交互属于右侧详情。

## 10. 限制与错误处理

- 单封附件最多 16 个。
- 单封附件实际总字节最多 20 MiB。
- 文件名必须去除路径、控制字符和危险响应头字符，并设置长度上界。
- 未提供或非法 MIME 类型规范化为 `application/octet-stream`。
- 读取和写入必须有上界，不能使用无界 `readAllBytes()` 处理外部附件流。
- 收件的单附件失败不回滚邮件正文和其他已保存附件。
- 发件的附件校验或暂存失败发生在 SMTP 前，不发送邮件。
- MinIO 与数据库跨资源操作采用明确补偿；补偿失败写结构化日志和可对账标识。
- 日志只记录 message ID、attachment ID、stage、大小和稳定错误码，不记录正文、附件内容、凭据和完整对象路径。

## 11. 测试与验收

### 11.1 后端单元与服务测试

- `text/plain`、HTML fallback、`multipart/alternative` 和嵌套 `multipart/mixed` 正文选择正确。
- 多附件的文件名、MIME、顺序、字节、摘要和 `mediaKind` 正确。
- inline/CID part 不进入普通附件列表。
- 第 17 个附件和总量超过 20 MiB 时行为有界，正文与此前成功附件保留。
- 发件生成正确 `multipart/mixed`，正文为首个 part，附件顺序和内容保持一致。
- MinIO 成功、数据库失败时执行对象补偿；补偿失败产生稳定诊断。
- SMTP 失败不写成功消息；SMTP 成功、本地提交失败返回准确结果。

### 11.2 控制器与权限测试

- multipart 发件参数、空附件、多附件和超限请求合同正确。
- 消息详情只返回所属且 `ready` 的附件元数据。
- 预览与下载均验证当前用户对附件所属会话的权限。
- 下载响应头包含 attachment disposition、UTF-8 文件名、真实长度、no-store 和 nosniff。
- 越权、缺失、未就绪和对象缺失不会触发非授权存储读取或泄露路径。

### 11.3 前端行为与浏览器验收

- 右侧详情分别渲染图片、音频、视频和普通文件。
- 每种已保存附件均可发起下载。
- 预览加载、预览失败、下载失败和附件切换时 object URL 生命周期正确。
- 邮件发送可追加、移除附件并准确限制数量和总大小；失败保留表单，成功才清空。
- 桌面与窄屏下正文、文件名、播放器和操作按钮不重叠、不溢出。

### 11.4 验收命令

实施完成后至少运行：

```bash
cd demo/message-center-spring/backend
mvn test

cd ../frontend
npm test
npm run build
```

另用真实浏览器验证右侧附件区域和下载行为，并使用本地 SMTP/IMAP fixture 做图片、音频和普通文件的字节往返校验。

## 12. 完成定义

只有同时满足以下条件才可声称闭环完成：

- 邮件发件附件通过 SMTP MIME 合同测试。
- 邮件收件附件完成 MIME 分类、MinIO 保存和消息关联。
- 消息详情稳定返回附件元数据。
- 图片、音频、视频在右侧正确预览，普通文件可识别展示。
- 所有已保存附件均可鉴权下载。
- 数量、总量、文件名、流读取、权限和失败补偿边界通过自动测试。
- 后端测试、前端测试、前端构建和真实浏览器验收均通过。
- 实现后的 API、配置和运行说明同步到对应文档层。
- git 范围只包含本功能改动，不吸入工作区中无关的未提交修改。
