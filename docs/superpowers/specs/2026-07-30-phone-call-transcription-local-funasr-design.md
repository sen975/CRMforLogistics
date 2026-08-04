# 消息中心本地电话录音转录设计

**日期：** 2026-07-30
**状态：** 已被 `2026-08-04-phone-repository-phone-only-contact-design.md` supersede；仅保留作为历史实现记录

> 当前电话身份、电话仓库、联系人必填和备注合同以 2026-08-04 设计为准。本文件中的旧联系人绑定边界不得作为实现依据。
**适用范围：** `demo/message-center-demo` 当前 pre-split 运行面

## 1. 决策摘要

在当前联系人的聊天框增加“电话记录”上传入口。用户上传一段已有 MP3，明确选择呼入或呼出、实际通话时间和本次通话号码；服务立即创建排队中的电话记录卡片，后台通过内网 FunASR sidecar 转录。电话卡片与邮件、WhatsApp、企业微信消息按实际发生时间混排，点击后在现有右侧详情栏播放录音、查看状态、完整时间戳分段、机器原文和人工修订历史。

电话记录是独立业务对象，不伪装成渠道消息，也不扩展现有人工 `phone_notes`。本期使用本地文件作为唯一真源，不连接 PostgreSQL 或 MinIO，不引入 DashScope 回退。业务合同通过 repository 和 FunASR adapter 隔离，以便后续替换存储或迁入 Spring/React 运行面。

本设计是对 `docs/项目细节PRD.md` 中“第一阶段不包含电话录音自动转写”的定向产品决策覆盖，仅覆盖 `demo/message-center-demo` 的电话录音上传转录闭环。该 PRD 中其他 AI 非目标保持不变；实现阶段必须同步 PRD 的对应边界说明。

## 2. 当前真源与约束

当前可运行消息中心仍由 `App.java` 提供 JDK `HttpServer`、内嵌 HTML/CSS/JavaScript 和旧 `/api/*` 路由。联系人列表与统一线程由 `UnifiedMessageStore` 驱动；文件运行模式下，页面使用联系人组的 `primaryPointId` 作为 `contact.id`，邮件、WhatsApp 和企业微信渠道身份则使用规范化 `contactPointId`。

仓库已有 PostgreSQL `phone_notes` 表和 `PhoneNoteRepository`，但没有电话记录 API、录音资源、转录任务或 UI 接线。`phone_notes` 只拥有人工纪要与下一步，不适合作为录音、异步状态和原始转录的 owner。

已确认的模块化迁移设计最终会删除 `App.java` 内嵌页面和旧路由，但该运行面尚未实现。本期允许触达当前页面，条件是：页面和 HTTP handler 只做协议接线，电话状态与文件规则进入独立核心类，不在 `App.java` 复制业务逻辑。

## 3. 目标与非目标

### 3.1 目标

- 从当前聊天窗口上传一个已有 MP3，并绑定当前联系人。
- 单文件最大 100 MiB，录音最长 2 小时。
- 上传时必须选择呼入或呼出，并填写实际通话时间；时间默认当前时间。
- 当前联系人有电话号码时必须选择本次通话号码；没有电话身份时允许“未指定号码”。
- 上传成功立即创建电话卡片，状态依次为排队中、转录中、已完成或失败。
- 使用 FunASR 自部署 sidecar 的 OpenAI 兼容转录接口。
- 保存完整机器原文和带起止时间的分段，不做说话人分离。
- 允许人工创建修订稿；机器原文不可覆盖，修订历史可追溯。
- 电话卡片直接进入聊天框时间线，点击后在右侧展示详情。
- 进程重启并取得本地目录独占锁后，恢复排队任务并立即回收所有遗留的处理中任务。
- 所有文件、队列、响应、重试、并发和磁盘使用均有明确上界。

### 3.2 非目标

- 不从浏览器直接录音。
- 不接电话系统、SIP、呼叫中心或通话结束回调。
- 不做说话人分离、员工/客户角色推断、摘要、情绪分析或任务提取。
- 不把电话记录写入 WhatsApp、邮件、企业微信会话或普通 `messages`。
- 不把机器转录写入 `phone_notes`。
- 不连接 PostgreSQL、MinIO、Redis、消息队列或 DashScope。
- 不实现多实例共享本地目录。
- 本期不提供电话记录删除，避免在当前缺少完整权限 owner 的运行面引入不可恢复操作。
- 不借本功能执行完整 Spring/React 拆分。

## 4. 架构与唯一 Owner

```text
当前聊天窗口
  -> App.java 薄 HTTP/UI adapter
  -> CallRecordService
       -> CallRecordRepository
            -> FileCallRecordRepository
       -> LocalAudioStore
       -> TranscriptionQueue
            -> TranscriptionWorker
                 -> FunAsrClient
                      -> FunASR sidecar
  -> ContactTimelineService
       -> UnifiedMessageStore + CallRecordRepository
```

| 组件 | 唯一职责 | 禁止拥有的语义 |
|---|---|---|
| `CallRecordService` | 创建、授权校验、联系人/号码绑定、状态转换、重试、人工修订 | multipart 解析、FunASR JSON 细节 |
| `CallRecordRepository` | 电话记录、转录快照和修订版本的持久化合同 | UI 文案、渠道消息 |
| `FileCallRecordRepository` | 本地 JSON 原子读写、索引和启动恢复 | 业务状态推断 |
| `LocalAudioStore` | MP3 临时写入、校验、发布、读取和容量核算 | 联系人归属、转录状态 |
| `TranscriptionQueue` | 有界排队、领取和背压 | FunASR 协议 |
| `TranscriptionWorker` | 租约、有限重试、调用 adapter、结果提交 | 联系人绑定、修订稿 |
| `FunAsrClient` | OpenAI 兼容 multipart 请求与响应映射 | 重试策略、任务状态 |
| `ContactTimelineService` | 消息和电话记录的稳定排序、分页与 revision | 修改电话记录或消息 |
| `App.java` | HTTP 参数映射、页面接线、SSE 发布 | 电话状态机、文件路径规则 |

当前实现保留 repository port。后续 PostgreSQL 替换只能新增另一个 adapter，不能改变电话记录、时间线或前端合同。

## 5. 本地文件真源

默认目录：

```text
data/call-records/
├── runtime.lock
├── records/
│   └── <callRecordId>.json
├── audio/
│   └── <callRecordId>.mp3
├── indexes/
│   └── <sha256(contactAnchorPointId)>.json
└── tmp/
```

规则：

- `callRecordId` 由服务端生成 UUID；任何用户文件名都不能进入路径。
- 上传流先写入 `tmp`，校验通过后用原子移动发布到 `audio`。
- 每条记录使用独立 JSON 快照；更新时写同目录临时文件并原子替换。
- 联系人索引只保存记录 ID。记录快照是事实真源，索引损坏或缺失时可从 `records` 有界重建。
- 启动时清理超过配置时限的临时文件，扫描记录并重建排队任务；扫描记录总数有配置上界。
- 运行时持有 `runtime.lock` 独占文件锁。第二个进程不能同时写同一目录；锁失败时电话子系统返回结构化不可用，消息中心其他能力可继续运行。
- 默认本地总容量为 10 GiB。容量核算和最终发布位于同一进程锁内；达到上限时拒绝新上传，不自动清理历史录音。
- JSON、索引和临时文件不保存 FunASR 地址、密钥或用户提交的任意路径。

## 6. 核心模型

### 6.1 CallRecord

```text
id
contactAnchorPointId
phonePointId?
direction                 inbound | outbound
occurredAt
createdAt
createdBy
audio                     relativePath, sizeBytes, sha256, contentType
transcription             state, model, attempts, lease, result, error
revisions[]               id, text, editedAt, editedBy
currentRevisionId?
clientRequestId
version
```

- `contactAnchorPointId` 保存具体、规范化的渠道身份锚点，不保存页面数组下标。
- 有电话身份时，`contactAnchorPointId` 与 `phonePointId` 都使用所选号码身份。
- 没有电话身份时，`phonePointId` 为 null，锚点使用创建时当前联系人组的 primary point。
- `createdBy`、`editedBy` 只能来自服务端已认证上下文，不能信任请求体。拿不到可靠 actor 时不得伪造人员身份；修订接口应失败关闭为 `AUTH_REQUIRED`。
- 当前运行面使用既有 `X-WeCom-Viewer-Auth` 解析企业微信员工身份；所有电话写操作都必须从该服务端 token 上下文取得 actor，不能新增客户端 actor 字段。
- `version` 用于阻断 worker、重试和人工修订的并发丢更新。

### 6.2 TranscriptionResult

```text
model                   sensevoice
durationSeconds
originalText
segments[]              startSeconds, endSeconds, text
completedAt
```

机器原文和分段一经成功提交不可修改。人工修订只追加 revision，并通过 `currentRevisionId` 选择当前修订稿。

### 6.3 状态机

```text
queued -> processing -> completed
          |         \-> failed
          \-> queued             transient retry
failed -> queued                 manual retry
```

- `queued -> processing` 必须持久化租约和 attempt。
- 只有持有当前租约与预期 version 的 worker 才能提交结果。
- 同一进程运行期间，租约过期后任务可以重新领取；旧 worker 的迟到提交会因 lease 与 version 不匹配被拒绝。
- 新进程只有取得 `runtime.lock` 后才能启动 worker。独占锁证明旧进程不再写该目录，因此启动恢复会立即把所有遗留 `processing` 改回 `queued`，不等待旧租约自然过期。
- 已完成记录不能再次自动转录。人工重试只允许从 `failed` 进入 `queued`。

## 7. 联系人绑定、合并与拆分

文件运行模式没有稳定的联系人 UUID，联系人组 primary point 可能因合并或拆分变化。因此电话记录不把当前 `contact.id` 当长期事实，而保存实际渠道身份锚点。

查询某个聊天窗口的电话记录时：

1. 使用现有 `contactGroup(contactId)` 解析当前组全部 point。
2. 读取这些 point 对应的电话记录索引并去重。
3. 只返回 `contactAnchorPointId` 仍属于当前组的记录。

由此获得以下语义：

- 合并联系人后，两组渠道身份及其电话记录进入合并后的聊天框。
- 拆分所绑定号码后，电话记录随该号码进入拆分后的联系人。
- “未指定号码”的记录随创建时选定的联系人锚点移动，不根据姓名或备注猜测归属。
- 合并、拆分失败不得部分迁移电话记录；电话记录本身不需要被重写。

## 8. HTTP 合同

当前 JDK HTTP 运行面直接承载 `/api/v1` 电话合同；不再创建另一套 `/api/call-*` 业务合同。

```text
POST  /api/v1/contacts/{contactId}/call-records
GET   /api/v1/contacts/{contactId}/timeline
GET   /api/v1/call-records/{callRecordId}
POST  /api/v1/call-records/{callRecordId}/audio-sessions
GET   /api/v1/call-records/{callRecordId}/audio
POST  /api/v1/call-records/{callRecordId}/retry
PATCH /api/v1/call-records/{callRecordId}/transcript
```

除音频内容 GET 外，所有接口都要求既有 `X-WeCom-Viewer-Auth`。当前本地单实例运行面的明确权限边界是：有效企业微信 viewer 对本实例全部 CRM 联系人和电话记录 tenant-wide 可见；上传时仍必须验证电话 identity 属于目标联系人，上传、重试和修订从 token 解析员工 actor。当前没有 actor-specific 联系人 ACL 的服务端 owner，因此不得在 adapter、页面或测试里伪造该权限结论；未来引入权限 owner 时，再在服务端边界收窄。token 缺失、过期或无法解析员工身份时返回 `AUTH_REQUIRED`，不得接受请求体传入的人员 ID。音频内容 GET 只接受服务端创建的短时播放会话 Cookie，不接受 query token、本地路径或任意 caller ID。

### 8.1 创建电话记录

`POST /api/v1/contacts/{contactId}/call-records` 使用流式 multipart：

```text
file             必填，一个 MP3
phonePointId     联系人有电话身份时必填，否则为空
direction        inbound | outbound
occurredAt       ISO-8601 date-time
clientRequestId  必填，幂等键
```

服务端必须在接收大文件前应用请求体上限，并使用流式 multipart 解析，禁止把 100 MiB 文件整体读入堆。成功返回 `202`：

```json
{
  "callRecordId": "uuid",
  "state": "queued",
  "clientRequestId": "opaque-id"
}
```

同一 `clientRequestId + contactAnchorPointId` 重放在 winner 已持久化后返回同一记录，不重复保存 MP3 或排队；winner 仍在上传时，同键并发请求在读取 MP3 前按 `TRANSCRIPTION_QUEUE_FULL` 做 429 背压，客户端稍后重放即可取得 winner。

### 8.2 联系人时间线

时间线 item 使用显式判别字段：

```text
type = message | callRecord
occurredAt
sortId
payload
```

排序固定为 `occurredAt ASC, typeRank ASC, sortId ASC`；cursor 编码三元组，不能使用数组下标。`threadRevision` 必须覆盖消息 revision 与电话记录 version，使 SSE 或轮询能识别电话状态变化。每页和当前前端缓存继续使用既有上界。

### 8.3 详情、播放会话、重试与修订

- 详情接口在验证 viewer 后返回本地实例内存在的记录；当前不伪造 actor-specific 联系人 ACL。
- 右侧详情挂载播放器前，用认证头调用 `POST /audio-sessions`。服务端校验 actor 且电话记录存在于本实例后创建 5 分钟内存播放会话，返回 `204` 并设置随机 opaque Cookie：`HttpOnly`、`SameSite=Strict`、路径只限当前记录的 `/audio`，HTTPS 部署必须带 `Secure`。
- 播放会话只绑定当前 viewer actor、`callRecordId`、到期时间和最近创建顺序；不保存音频内容。每个 actor 最多 8 个、全局最多 256 个，超限时淘汰该作用域最旧会话。
- 音频 GET 按 `callRecordId` 与播放 Cookie 双向匹配后解析服务端相对路径，支持标准单区间 `Range` 请求以便浏览器播放；拒绝多区间请求，绝不接受任意文件路径。
- `<audio>` 不能携带自定义认证头。详情保持打开时，前端每 4 分钟重新创建同一记录的播放会话并旋转 Cookie；离开详情、切换记录或页面隐藏时停止续期。播放请求因会话失效返回 401 时，UI 重新创建会话，恢复原 `currentTime` 后继续播放，最多自动恢复一次，避免认证故障形成无限循环。
- 播放 Cookie 不进入 URL、JSON、日志、localStorage 或 sessionStorage；服务重启后内存会话自然失效，前端重新创建即可。
- 重试接口仅接受 `failed` 状态，并使用请求幂等键阻断重复排队。
- 修订接口必须带预期 version；冲突返回 409，不覆盖他人修订。
- API 错误统一返回现有结构化错误 envelope、trace ID、稳定 code 和可审计 context。

## 9. 上传与转录数据流

1. 页面在当前联系人发送区打开“电话记录”页签。
2. 用户选择号码、呼入/呼出、实际通话时间和一个 MP3。
3. 后端流式写临时文件，同时计算 SHA-256 和大小。
4. `LocalAudioStore` 验证 MPEG/ID3 签名、`audio/mpeg`、100 MiB 上限和 2 小时时长；时长使用成熟 MP3 解析库读取，不相信浏览器 MIME 或文件名。
5. 服务在同一临界区检查磁盘预算、发布音频、创建 `queued` 记录并更新索引。记录或索引发布失败时立即清理本次音频；进程崩溃遗留的无记录音频由启动对账清理。
6. 返回 `202`；前端立即把电话卡片插入时间线。
7. worker 领取任务并持久化 `processing` 与租约。
8. `FunAsrClient` 流式读取本地 MP3，调用 sidecar。
9. 成功时校验响应、保存原文和分段并切换为 `completed`；失败时按策略重试或切换为 `failed`。
10. 状态提交后发布现有 SSE 更新事件；断线时前端对当前可见记录做有界轮询。

## 10. FunASR Adapter

sidecar 合同：

```text
POST {FUNASR_BASE_URL}/v1/audio/transcriptions
Content-Type: multipart/form-data

file=<recording.mp3>
model=sensevoice
response_format=verbose_json
```

期望响应：

```json
{
  "text": "完整转录",
  "duration": 3.45,
  "segments": [
    { "text": "完整转录", "start": 0.0, "end": 3.45 }
  ]
}
```

适配规则：

- base URL 只能来自后端配置，前端不得提交或读取。
- 默认模型固定为 `sensevoice`，本期页面不提供模型选择器。
- `response_format` 固定为 `verbose_json`。
- 不发送鉴权头；sidecar 只能加入 Compose 内网，不映射公网端口。
- 响应必须使用结构化 JSON 解析；不得通过字符串查找提取字段。
- `text`、`duration`、`segments` 必须存在且满足类型、非空和时间范围约束；新版 OpenAI-compatible FunASR 可省略响应 `model`，此时适配器使用已经校验过的请求模型。若响应包含 `model`，仍必须是非空字符串。
- 不补造说话人、不把空响应当成功、不回退 DashScope。

## 11. 资源治理与重试

默认值：

| 配置 | 默认值 |
|---|---:|
| 单文件上限 | 100 MiB |
| 单录音时长上限 | 7200 秒 |
| 本地总容量 | 10 GiB |
| 启动扫描记录上限 | 10000 |
| 持久待处理任务上限 | 64 |
| worker 并发 | 1 |
| FunASR 连接超时 | 3 秒 |
| 单次转录超时 | 30 分钟 |
| 最大自动尝试次数 | 3 |
| FunASR 响应体上限 | 10 MiB |
| 分段数量上限 | 20000 |
| 人工修订版本上限 | 20 |
| 单 actor 播放会话上限 | 8 |
| 全局播放会话上限 | 256 |
| 播放会话 TTL | 300 秒 |

网络错误、连接超时、请求超时和 HTTP `5xx` 可以自动重试。可重试失败会清除旧租约，写入有上界的 `nextAttemptAt`，并从 `processing` 返回 `queued`。MP3 非法、超过文件/时长上限、磁盘容量不足、FunASR `4xx` 和响应合同错误不自动重试。三次尝试耗尽后记录进入 `failed`，保留录音并允许用户人工重试。

sidecar 不可用不阻断消息中心启动。电话子系统对新任务提供真实失败状态，不返回伪健康；已有消息、联系人和渠道功能继续运行。

## 12. UI 与交互

### 12.1 上传入口

发送区增加与邮件、WhatsApp、企业微信并列的“电话记录”页签。表单包含：

- 单个 MP3 文件选择器，`accept="audio/mpeg,.mp3"`。
- 呼入/呼出选择，默认不选，必须明确选择。
- 实际通话时间，默认当前时间，可修改。
- 电话号码选择：存在电话身份时必选；不存在时显示只读“未指定号码”。
- 带上传进度的提交按钮；上传期间阻断重复提交。

上传成功后关闭表单，在聊天框按实际通话时间插入 `queued` 电话卡片。失败时保留用户已填元数据，但浏览器安全限制下不得伪装仍持有文件选择。

### 12.2 电话卡片

聊天框内卡片保持紧凑，展示：

- 电话图标与“电话记录”。
- 呼入或呼出。
- 所选号码或“未指定号码”。
- 实际通话时间。
- 已知时展示录音时长。
- 排队中、转录中、已完成或失败状态。

卡片不在聊天框展开完整转录。点击卡片复用现有选中态和右侧详情栏。

### 12.3 右侧详情

详情栏展示：

- 原生或项目样式化 MP3 播放器。
- 文件大小、时长、SHA-256 短摘要。
- 呼入/呼出、号码、实际通话时间和当前状态。
- 完整时间戳分段。
- 只读机器原文。
- 当前人工修订稿和历史版本。
- 失败原因与“重新转录”命令。

详情取得数据后先创建短时播放会话，再设置 `<audio src>`；创建失败时只禁用播放器并展示结构化错误，转录和修订内容仍可查看。详情保持打开时按第 8.3 节续期，关闭时清理 timer 和媒体资源。

排队和处理中显示稳定占位尺寸，不能因文案变化导致右栏或聊天列表跳动。移动端沿用现有详情抽屉/单列约束，不让右栏覆盖发送区。

## 13. 错误语义

| code | 含义 | 是否自动重试 |
|---|---|---|
| `INVALID_MP3` | 文件签名、结构或 MIME 无效 | 否 |
| `AUDIO_TOO_LARGE` | 超过 100 MiB | 否 |
| `AUDIO_TOO_LONG` | 超过 2 小时 | 否 |
| `CONTACT_BINDING_INVALID` | 联系人或号码不属于当前聊天对象 | 否 |
| `LOCAL_STORAGE_FULL` | 达到本地容量上限或磁盘不可写 | 否 |
| `CALL_RECORD_STORE_BUSY` | 本地目录被另一进程独占 | 否 |
| `TRANSCRIPTION_QUEUE_FULL` | 持久待处理任务达到上限 | 否 |
| `FUNASR_UNAVAILABLE` | sidecar 连接失败或健康不可用 | 是 |
| `FUNASR_TIMEOUT` | 单次转录超时 | 是 |
| `FUNASR_REJECTED` | sidecar 返回 `4xx` 拒绝请求 | 否 |
| `FUNASR_INVALID_RESPONSE` | 响应类型、范围或上界不符合合同 | 否 |
| `TRANSCRIPT_VERSION_CONFLICT` | 人工修订发生并发冲突 | 否 |
| `AUTH_REQUIRED` | 无法取得可靠服务端 actor | 否 |
| `AUDIO_SESSION_EXPIRED` | 播放会话缺失、失效或不匹配当前记录 | 否 |
| `AUDIO_RANGE_INVALID` | Range 缺失有效单区间或请求多区间 | 否 |

错误详情不得包含本地绝对路径、用户音频内容、FunASR 完整响应、内部网络拓扑或其他敏感配置。

## 14. 安全与隐私

- FunASR 容器只加入私有 Compose 网络；不声明宿主机端口映射。
- 所有音频读取必须先验证短时播放 Cookie 与本实例内记录双向绑定，不能只凭 UUID 猜测访问；本期 viewer 可见性按 tenant-wide 本地权限边界执行。
- 音频 URL 不携带认证 token；浏览器只能使用路径限定、HttpOnly、SameSite=Strict 的短时播放 Cookie。HTTPS 下 Cookie 必须带 `Secure`。
- 上传文件名只用于安全展示；存储路径完全由服务端 ID 生成。
- multipart、JSON、分段、修订文本、错误文本和 SSE payload 都有长度上限。
- 日志只记录 record ID、trace ID、状态、耗时、大小和稳定错误码，不记录音频、完整转录或 FunASR body。
- 本地目录部署时必须限制为应用用户读写。备份、磁盘加密和主机访问控制属于部署责任，README 必须明确。
- 当前运行面虽然能从企业微信 viewer token 取得员工 actor，但没有“谁可以删除录音”的完整角色权限 owner，因此不实现删除。所有电话写操作必须取得服务端认证 actor，否则失败关闭。

## 15. 测试与验收

### 15.1 单元与合同测试

- `CallRecordService`：绑定校验、方向、时间、幂等、状态机、version 冲突、重试和修订上限。
- `CallAudioSessionService`：viewer token 换取播放会话、actor/record 绑定、TTL、每 actor 8 个、全局 256 个、Cookie 属性和淘汰顺序。
- `FileCallRecordRepository`：临时文件、原子替换、损坏记录、索引重建、孤立音频对账、独占锁和启动恢复。
- `LocalAudioStore`：MP3 签名、MIME、大小、时长、SHA-256、容量竞争、路径穿越和临时文件清理。
- `FunAsrClient`：multipart 字段、流式文件、超时、`4xx`、`5xx`、空文本、非法 JSON、倒置时间、响应和分段上界。
- `TranscriptionWorker`：有界并发、租约、三次尝试、退避、重启恢复、旧 worker 提交拒绝。
- `ContactTimelineService`：消息/电话混排、相同时间 tie-break、cursor、revision、分页无重无漏。
- 音频响应：完整 GET、单区间 `206`、`Content-Range`、seek、多区间拒绝、Cookie 缺失/过期/错记录拒绝。
- OpenAPI 合同：所有请求/响应 additional-properties、上界、错误 envelope 和 MP3 content type。

### 15.2 集成测试

- 使用临时目录和本地假 FunASR HTTP 服务完成上传、排队、处理、完成、播放、失败和人工重试闭环。
- 进程级测试验证取得新独占锁后，重启立即恢复 `queued` 和全部遗留 `processing`，并拒绝旧 lease 的迟到提交。
- 联系人合并后两侧电话记录进入同一时间线；拆分所选号码后记录跟随该号码。
- 并发提交相同 `clientRequestId` 只产生一个 MP3 和一个任务。
- SSE 丢失时详情轮询能收敛到最终状态，且停止轮询不可见记录。
- 播放会话在右栏打开时每 4 分钟续期，切换记录后旧 timer 停止；模拟过期后最多自动恢复一次并保持 `currentTime`。

### 15.3 浏览器验收

- 桌面和移动 viewport 覆盖上传表单、进度、排队、处理中、完成、失败、重试和修订。
- 电话卡片与真实消息按实际通话时间混排，点击只更新右侧详情，不破坏滚动位置。
- MP3 可播放、seek 和暂停；Range 请求工作正常。
- 长分段、长文件名、错误文案和 20 个修订版本不产生重叠或溢出。
- 页面刷新和服务重启后卡片、状态、录音和修订仍存在。

### 15.4 真实 FunASR 验收

- Compose 内健康检查 `/health` 返回真实可用状态。
- 使用一段可核对的中文 MP3，实际取得 `sensevoice` 的文本、duration 和 segments。
- 停止 sidecar 后任务进入可解释失败态；恢复 sidecar 并人工重试后完成。
- 不使用 mock 结果、固定文本或源代码字符串替代真实转录证据。

## 16. 配置与部署

建议配置：

```env
CALL_RECORD_DATA_DIR=data/call-records
CALL_RECORD_MAX_AUDIO_BYTES=104857600
CALL_RECORD_MAX_DURATION_SECONDS=7200
CALL_RECORD_STORAGE_MAX_BYTES=10737418240
CALL_RECORD_MAX_RECORDS=10000
CALL_RECORD_QUEUE_CAPACITY=64
CALL_RECORD_WORKER_CONCURRENCY=1
CALL_RECORD_LEASE_SECONDS=2100
CALL_RECORD_MAX_ATTEMPTS=3
CALL_RECORD_MAX_RESPONSE_BYTES=10485760
CALL_RECORD_MAX_SEGMENTS=20000
CALL_RECORD_MAX_REVISIONS=20
CALL_AUDIO_SESSION_TTL_SECONDS=300
CALL_AUDIO_SESSION_MAX_PER_ACTOR=8
CALL_AUDIO_SESSION_MAX_ACTIVE=256
FUNASR_BASE_URL=http://funasr:8000
FUNASR_MODEL=sensevoice
FUNASR_CONNECT_TIMEOUT_SECONDS=3
FUNASR_REQUEST_TIMEOUT_SECONDS=1800
```

配置必须通过现有 `Config` 显式读取并校验。负数、零值冲突、超大上界、非 HTTP(S) URL、带 user-info 的 URL 或公网 FunASR 地址均应失败关闭电话子系统。示例配置不得包含真实凭据。

Compose 增加 `funasr` 服务与健康检查，但不增加 `ports` 暴露。具体镜像、CUDA runtime 和模型缓存卷必须在实现计划中根据部署主机确认；本设计不伪造尚未提供的镜像标签。

## 17. 文档与迁移边界

实现完成时必须同步：

- `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- `demo/message-center-demo/config.example.env`
- `demo/message-center-demo/README.md`
- `docs/项目细节PRD.md` 中电话自动转录的范围说明
- 本设计对应的实现计划和实际验收证据

未来迁入 Spring/React 时：

- 保留 HTTP DTO、状态机、FunASR adapter 合同和时间线 item 判别语义。
- 将 `FileCallRecordRepository` 替换为数据库 adapter，将 `LocalAudioStore` 替换为正式媒体 adapter。
- 不保留本地文件与数据库双写，不保留旧 `App.java` 电话路由。
- 迁移前通过导入器读取本地快照与音频，完成数量、哈希、状态和分段对账后一次性切换。

## 18. 完成定义与停止条件

只有同时满足以下条件才能声称本功能完成：

- OpenAPI、核心模型、本地 repository、音频存储、worker、FunASR adapter、当前 UI 和文档全部闭环。
- 所有自动化测试、构建和合同门禁通过且无 warning。
- 浏览器桌面/移动截图验证电话卡片、右侧详情和所有状态。
- 真实 FunASR sidecar 完成至少一条中文 MP3 转录，并验证不可用与恢复重试。
- Git diff 只包含电话转录目标链路及必要文档，没有吸入其他未提交改动。

出现以下任一情况必须停止并报告，不得用兼容分支绕过：

- 无法取得可用 FunASR 镜像、CUDA 运行条件或真实 sidecar 响应。
- 当前认证上下文无法为人工修订提供可靠 actor。
- MP3 时长校验库无法在 100 MiB 上界内稳定处理目标样本。
- 本地联系人合并/拆分语义无法通过真实记录测试。
- 当前内嵌 UI 改动与已确认的 pre-split 运行边界产生不可闭合冲突。
