# WhatsApp MVP 与扩展能力设计

**状态：** 消息中控 MVP 已进入实现验收；号码注册与多号码治理已暂停

**日期：** 2026-08-06

## 1. 背景

Spring 版消息中心已经通过阿里云 ChatApp Messaging Service（CAMS）接入部分 WhatsApp 能力，包括文本、模板、图片、视频、文件发送，以及消息和模板同步。但当前实现仍是迁移阶段的渠道调用集合，还没有形成可上线的 WhatsApp 客服闭环，也没有模板管理、群组管理和群消息模型。

本设计定义两条连续路线：

- 版本 A：可上线的 WhatsApp 最小 MVP，覆盖单号码、一对一消息和模板生命周期。
- `G1`：依赖 OBA 和 Groups API 资格的群组条件能力包，独立于基础 MVP 验收。
- 版本 B：建立在同一合同上的扩展路线，按需要增加富消息、群组高级管理、群发、Flow、商品、账号开通、分析和通话。

两个版本共享同一业务模型和 provider adapter，不维护两套 API，不让 MVP 形成后续必须推倒的临时路线。

### 1.1 2026-08-07 范围收敛

本轮只交付固定现有 WhatsApp 号码的消息中控 MVP。号码验证码、CAMS 号码注册、销售申请、管理员分配、多号码切换和重新分配均暂停，不进入本轮运行路径。

当前完成边界为：

- 文本、模板、图片、视频和文件统一进入本地消息与 outbox，不在 HTTP 请求内直接向 CAMS 提交。
- 相同 channelAccountId + clientRequestId 幂等，初始状态为 pending。
- outbox 有批次、租约、最大尝试次数和退避上界；提交结果未知进入 submission_unknown，不盲目重发。
- Webhook 进入 channel_events inbox，按 provider event 或 payload hash 幂等，再投影入站文本与消息状态。
- ListChatappMessage 作为 Webhook 漏失补偿，转换为 channel event 后复用同一个 projector，不保留第二套消息写入 owner。
- 发送和媒体读取复用 assigned user、team member、有效 grant 和 admin 权限模型，服务端边界执行最终授权。
- 用户登录使用 `user_sessions` opaque token；数据库只保存 SHA-256，logout 吊销当前 session。
- 前端持续展示 pending、submission_unknown、submitted、sent、delivered、read、failed 和 cancelled，不用瞬时提示替代消息状态。

## 2. 当前真源与审计结论

### 2.1 当前源码能力

消息中控 MVP 的当前主入口：

| 方法 | 路径 | 当前用途 |
| --- | --- | --- |
| POST | `/api/auth/login` | 校验用户名密码并签发 opaque session |
| POST | `/api/auth/logout` | 吊销当前 Bearer session |
| POST | `/api/v1/whatsapp/messages` | 按 conversation 统一受理 JSON 消息命令 |
| POST | `/api/send/chatapp-media` | multipart 媒体薄 adapter，进入同一消息/outbox owner |
| POST | `/api/v1/webhooks/chatapp` | 有界读取、验签并写入 webhook inbox |
| GET | `/api/media/{id}` | 按 conversation 权限读取附件 |

旧发送入口仍作为薄 adapter 进入同一 application service/outbox，不再直接拥有 CAMS 提交语义。模板管理与群组能力仍按后续阶段推进。

### 2.2 初始审计问题及当前处置

消息中控范围内的 webhook 放行、验签/inbox、统一 outbox、固定账号作用域和结构化授权已经收敛。以下问题仍属于后续模板、群组或真实上线门禁：

1. CAMS 官方 Webhook header 与 canonical string 尚未校准；当前 HMAC verifier 必须保持 fail-closed。
2. 模板同步与 DTO 仍未形成完整生命周期，不进入本轮消息中控完成声明。
3. `conversations.contact_identity_id` 当前模型不能表达群会话，群组能力继续关闭。
4. PostgreSQL 17.5 Testcontainers 已从空库执行 Flyway v1-v8，并通过认证与 HTTP 边界集成测试；`FOR UPDATE SKIP LOCKED` 的真实并发竞争仍缺少专用容器测试。Docker 29.6.1 环境需向 docker-java 显式传入 `-Dapi.version=1.44`。

### 2.3 本地 SDK 能力依据

项目锁定 `com.aliyun:alibabacloud-cams20200606:5.0.5`。本地 SDK 源码确认存在：

- 模板：`CreateChatappTemplate`、`ListChatappTemplate`、`GetChatappTemplateDetail`、`ModifyChatappTemplate`、`ModifyChatappTemplateProperties`、`DeleteChatappTemplate`。
- 群组：`AddChatGroup`、`ListChatGroup`、`UpdateChatGroup`、`DeleteChatGroup`、`AddChatGroupInviteLink`、`DeleteChatGroupInviteLink`、`ListChatGroupParticipants`、`DeleteChatGroupParticipants`。
- 消息：`SendChatappMessage`、`SendChatappMassMessage`、`ListChatappMessage`、`GetChatappUploadAuthorization`。
- 账号：WABA 绑定、号码添加/注册/注销/同步、webhook 设置、业务资料和健康状态相关 API。
- 扩展：Chat Flow、Message Campaign、Custom Audience、商品目录、Conversion API、指标和 WhatsApp Calling。

SDK 中没有发现“按手机号向群组添加参与人”的对应 API。因此产品不能承诺强制拉人入群，必须使用邀请链接或二维码。

`AddChatGroup` 同时包含 `GroupLink`、`Subject` 和 `Description` 字段，但本地生成源码没有完整说明它在当前账号上属于“创建新群”还是“通过链接接入已有群”。该语义必须通过真实账号实调后才能开放。

### 2.4 真实账号 capability probe 结果

2026-08-06 使用项目配置的 CAMS 账号、`alibabacloud-cams20200606:5.0.5` 和真实 API 完成探测；所有号码和 WABA 标识在文档中脱敏。

| 探测项 | 结果 | 结论 |
| --- | --- | --- |
| `GetChatappOpenStatus` | `open=true` | ChatApp 服务已开通 |
| `QueryChatappBindWaba` | 成功，WABA 审核 `APPROVED` | WABA 绑定可用 |
| `QueryChatappPhoneNumbers` | 1 个号码，`CONNECTED / VERIFIED`，显示名 `APPROVED`，质量 `GREEN`，等级 `TIER_2K` | 基础消息账号可用 |
| `GetWhatsappHealthStatus` | WABA 和号码均 `canSend=AVAILABLE` | 发送健康检查通过 |
| `ListChatappTemplate` | 成功，2 个模板，真实审核状态为 `pass` | 模板查询可用，不能固定映射为批准 |
| `ListChatGroup` | 调用成功，当前群数为 0 | 只能证明列表入口可调用，不能证明建群资格 |
| `AddChatGroup` | Meta 错误 `#131215`：号码不具备 Groups API 资格 | 当前账号群组能力必须关闭 |

`AddChatGroup` 测试使用不带 `GroupLink` 的临时主题；请求被上游拒绝，前后同名测试群均为 0，没有创建或删除真实群组资源。

当前号码的 `IsOfficial=N`。Meta 官方群组文档要求企业拥有 OBA（Official Business Account）；因此当前已确认的群组阻断原因是 `OBA_REQUIRED`，provider code 为 `131215`。企业注册时长、公司验证和两步验证状态尚未通过 CAMS 探测确认，不能在系统中伪造为已满足。

## 3. 已确认产品边界

### 3.1 版本 A：最小 MVP

- 只操作一个 WhatsApp Business 号码。
- 一对一消息能进入统一消息中心并从系统回复。
- 群组不再是基础 MVP 的完成条件，作为单独的条件能力包 `G1` 管理。
- 模板支持 `UTILITY` 和 `MARKETING`。
- 模板组件覆盖文本正文、变量示例、媒体 Header、Footer 和常用按钮。
- 模板具备注册、查询、修改、删除和审核状态同步。
- webhook、幂等、发送状态、结构化失败和审计属于 MVP，不推迟到后续阶段。

### 3.2 版本 A 非目标

- `AUTHENTICATION` 模板。
- Carousel、Flow 等复杂模板。
- 营销群发、受众管理和活动编排。
- 商品目录、商品消息和商务设置。
- WABA/号码自助迁移和多租户开户流程。
- WhatsApp Calling。
- 当前未满足 OBA 资格的群组创建、群消息和成员管理。
- 绕过 WhatsApp 或阿里云的账号、模板、会话窗口、隐私和反滥用限制。

### 3.3 版本 B：扩展方向

版本 B 和 `G1` 只增加 capability，不改变基础消息、模板和账号主键，不新增平行业务 owner。群组模型一旦启用，仍复用本设计定义的会话和消息合同。

## 4. 方案选择

### 4.1 推荐方案：按能力域分阶段接入

按账号底座、可靠消息、模板、群组四个能力域推进。每一阶段都包含 provider adapter、业务服务、持久化、HTTP API、前端接线和验收，形成独立闭环。

该方案能够先交付客服主线，同时保留 CAMS 高级能力扩展空间。

### 4.2 不采用：继续扩充 `ChatAppController`

直接在现有 Controller 增加 SDK 调用虽然改动较少，但会让 Controller 同时拥有账号选择、供应商请求、状态映射、重试、审计和业务规则，无法形成可靠的消息与模板真源。

### 4.3 不采用：一次性复刻 CAMS 控制台

一次覆盖群发、Flow、商品、号码迁移、指标和通话会扩大权限、数据模型、UI 和验收范围，不能作为最小 MVP 的完成条件。

## 5. Owner 与架构边界

### 5.1 业务渠道与供应商 adapter 分离

- `WhatsApp` 是业务渠道语义。
- `ChatApp/CAMS` 是当前供应商 adapter。
- Controller、前端和数据库不得直接依赖阿里云 SDK 类型。
- provider 切换只能替换 adapter，不改变共享消息、模板和群组合同。

### 5.2 唯一 owner

| 概念 | 唯一 owner | 禁止成为 owner 的层 |
| --- | --- | --- |
| 渠道账号、号码、加密凭据 | `channel-account` | 前端、Controller、ChatApp SDK client |
| 会话、消息、发送状态、收件箱、outbox | `message` / `conversation` | webhook、定时任务、页面 |
| WhatsApp 模板语义与状态 | `whatsapp-template` application service | 模板页面、同步器 |
| WhatsApp 群组与参与人投影 | `whatsapp-group` application service | 群页面、SDK adapter |
| CAMS 请求/响应映射 | `channel/chatapp` adapter | 业务 service、Controller |
| 权限与审计 | 服务端 application boundary | 前端按钮状态 |

### 5.3 推荐组件

```text
WhatsAppController
  -> WhatsAppApplicationService
      -> Message/Conversation owner
      -> WhatsAppTemplateService
      -> WhatsAppGroupService
      -> WhatsAppGateway
          -> AliyunChatAppGateway
              -> CAMS AsyncClient

ChatAppWebhookController
  -> ChannelEventInbox
      -> WhatsAppWebhookProjector
          -> Message/Conversation owner
```

`WhatsAppGateway` 返回内部结构化结果和错误，不向上泄漏 SDK response class。

## 6. 账号和 capability 合同

虽然首版 UI 只操作一个号码，持久化、游标、模板、群组和消息 owner 仍按 `channelAccountId` 隔离。当前消息 API 不接收客户端传入的账号 ID，而是从 conversation 和服务端固定账号配置解析并校验 active 账号；单账号是产品限制，不是数据库的隐式全局状态。

### 6.1 内部 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/capabilities` | 返回当前账号真实可用能力 |
| POST | `/api/v1/channel-accounts/{accountId}/whatsapp/connection-check` | 检查凭据、WABA、号码和健康状态 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/status` | 返回最近同步、webhook、号码和上游健康状态 |

capability 必须来自配置和真实上游结果，例如 `direct_message_send`、`template_create`、`group_import`、`group_create`、`group_message_send`，不能用固定常量伪装支持。

### 6.2 上游映射

- `GetChatappOpenStatus`
- `QueryChatappBindWaba`
- `QueryChatappPhoneNumbers`
- `QueryWabaBusinessInfo`
- `GetPhoneNumberVerificationStatus`
- `GetChatappPhoneNumberSetting`
- `GetWhatsappHealthStatus`
- webhook 设置相关 API

连接检查必须设置超时和调用上限，不在普通页面请求中循环重试。

### 6.3 OBA 资格与平台边界

Meta 群组 API 只向具备 OBA 的企业开放。官方申请前置条件包括：遵守 WhatsApp Business 消息政策、业务账户在 WhatsApp Business Platform 注册满 30 天、拥有该号码的业务资产组合已完成公司验证、号码启用两步验证、号码显示名已批准。满足条件只代表可以申请，最终结果仍由 Meta 审核。参考 [Meta 群组 API 入门指南](https://developers.facebook.com/documentation/business-messaging/whatsapp/groups/get-started/) 和 [Meta OBA 文档](https://developers.facebook.com/documentation/business-messaging/whatsapp/official-business-accounts/)。

当前平台可以同步资格预检和审核状态，但不能把 CAMS 凭据当作 Meta Graph API 凭据提交 OBA。CAMS SDK 5.0.5 没有明确的 OBA 提交 API；其中的 `UpdateAuditRequest` 是 CAMS 通用审核记录接口，不能直接映射为 OBA 申请。

内部合同：

```http
POST /api/v1/channel-accounts/{accountId}/whatsapp/oba/precheck
GET  /api/v1/channel-accounts/{accountId}/whatsapp/oba/status
```

OBA 状态至少包含 `not_started`、`eligible`、`not_eligible`、`submitted`、`under_review`、`approved`、`rejected` 和 `retry_after`。当前账号应返回 `not_eligible`、`reason=OBA_REQUIRED`、`providerCode=131215`，并关闭所有群组写入和群消息 capability。OBA 申请暂由 WhatsApp Manager 完成；只有未来接入独立的 Meta Graph API 应用、System User Token、WABA/Phone Number ID 和相应权限后，才评估开放平台代提交。

## 7. 消息合同

### 7.1 发送 API

统一入口：

```http
POST /api/v1/whatsapp/messages
```

请求核心字段：

```json
{
  "conversationId": "uuid",
  "kind": "text",
  "clientRequestId": "stable-id",
  "content": {
    "text": "hello"
  }
}
```

消息 owner 允许的 `kind`：

- `text`
- `template`
- `image`
- `video`
- `document`

文本和模板可通过 JSON 会话入口受理。图片、视频和文件当前由 multipart 媒体 adapter 完成上传、授权和附件持久化，再进入同一个 outbox；后续可以统一传输协议，但不得建立第二套消息 owner。

群消息和一对一消息使用同一个发送命令，由 `conversationId` 解析真实 provider recipient；前端不得自行决定号码还是群 ID。

### 7.2 发送状态

本地先创建消息和 outbox，再异步调用 CAMS。状态至少包含：

- `pending`
- `submitted`
- `sent`
- `delivered`
- `read`
- `failed`
- `submission_unknown`

相同 `channelAccountId + clientRequestId` 必须幂等。超时但无法确认上游是否接收时进入 `submission_unknown`，禁止直接重发造成重复消息。该名称复用当前数据库约束，不建立平行状态。

### 7.3 webhook

公开入口建议为：

```http
POST /api/v1/webhooks/chatapp
```

该路径不使用用户登录 token，但必须执行供应商要求的验签或来源验证。处理流程：

1. 校验请求大小、签名、时间窗口和账号映射。
2. 以 provider event/message ID 写入原始事件收件箱。
3. 重复事件直接返回已接收，不重复投影。
4. 在事务内创建或更新身份、会话、消息和状态历史。
5. 事务提交后发送 SSE 缓存失效事件。
6. 解析或投影失败进入有界重试和可查询失败状态。

原始载荷必须脱敏；密钥、完整签名 URL 和不必要的个人数据不得进入普通日志。

当前 CAMS SDK 5.0.5 只提供状态/上行回调 URL 配置，没有提供可验证的回调签名字段合同。实现因此采用 fail-closed 的可替换 HMAC-SHA256 verifier，密钥由 `app.chatapp-webhook-secret` 提供；真实环境上线前必须用 CAMS 官方回调文档或真实请求样本校准 header 和 canonical string，未校准时不得关闭验签。

### 7.4 轮询同步

`ListChatappMessage` 作为 webhook 漏失补偿，不维护第二套消息写入逻辑。轮询先按 provider message ID 或 task/clientRequestId 关联本地消息，再生成 `channel_events` 并调用同一个 projector。已知客户收件人的未关联 outbound 使用稳定 provider event key 导入对应会话的历史消息；缺少收件人时保守跳过，避免写入错误会话。状态候选先归一化，`Unread` 等负向读标志不能覆盖 delivered/sent 等真实进度。

当 outbox 已调用 CAMS 但租约过期时，不得重新领取并再次提交。任务进入 `dead`，对应仍处于 `pending/processing` 的本地消息原子转为 `submission_unknown`，等待 provider message ID 或 task ID 对账；已进入 submitted/delivered/read 的消息不得被回退。

### 7.5 认证、会话权限与媒体

- 登录 token 为 32-byte `SecureRandom` 生成的 URL-safe opaque token，`user_sessions.token_hash` 只保存 SHA-256。
- 每次 Bearer 鉴权必须检查 session 未过期、未吊销，且用户仍为 active；`/api/auth/logout` 只吊销当前 session。
- 只有 `/api/auth/login` 公开；Webhook、企业微信 callback 和带独立短期授权的音频入口按各自协议豁免。
- 消息发送在幂等查询前先授权，并在创建消息/outbox 前对 conversation 做 `FOR UPDATE` 最终复核。
- 可见范围严格复用 assigned user、team member、有效且未过期 grant 和 admin，不从企业微信 corp 身份推导销售身份。
- 媒体上传在 MinIO 写入前授权，数据库事务失败或重复请求会清理本次对象；下载按附件所属 conversation 再次授权。

## 8. 模板合同

### 8.1 内部 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/channel-accounts/{accountId}/whatsapp/templates` | 注册并提交模板 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/templates` | 分页查询本地成功快照 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}` | 查询模板及语言版本详情 |
| PUT | `/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}` | 修改上游允许修改的模板 |
| DELETE | `/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}` | 删除模板语言版本 |
| POST | `/api/v1/channel-accounts/{accountId}/whatsapp/templates/sync` | 显式触发一次有界全量对账 |

### 8.2 MVP 模板请求

模板注册字段：

- `name`
- `language`
- `category`：`UTILITY` 或 `MARKETING`
- `components`
- `examples`
- 可选消息 TTL

支持组件：

- 文本 `BODY` 和变量示例。
- 文本、图片、视频或文件 `HEADER`。
- `FOOTER`。
- 常用快速回复和 URL/电话按钮。

页面不提交 `custSpaceId`、WABA ID 或密钥；这些值由 `channelAccountId` 对应的服务端账号配置提供。

### 8.3 状态模型

本地保存标准化状态、provider 原始状态、失败阶段、拒绝原因和最后同步时间。创建 API 成功只表示“上游已接收提交”，不能直接写成 `APPROVED`。

可发送模板必须同时满足：

- 本地标准化状态为 `approved`。
- 对应语言版本存在。
- 组件和变量满足发送请求。
- 当前账号与模板归属一致。

### 8.4 对账规则

模板稳定键为：

```text
channelAccountId + providerTemplateId + languageCode
```

同步必须 upsert 并处理上游删除或不可见状态；任一分页或详情调用失败时不能把截断集合当成完整快照。同步完成后仅在业务内容变化时发布 `templates-changed`。

### 8.5 上游映射

- `CreateChatappTemplate`
- `ListChatappTemplate`
- `GetChatappTemplateDetail`
- `ModifyChatappTemplate`
- `ModifyChatappTemplateProperties`
- `DeleteChatappTemplate`

## 9. 群组合同

### 9.1 入群方式

产品支持邀请链接和二维码。二维码由前端或服务端根据邀请 URL 生成，它只是邀请链接的展示形式，不伪装成独立供应商能力。

由于 SDK 没有添加参与人 API：

- UI 命令使用“邀请加入”，不使用“强制拉入”。
- API 不提供 `POST /participants`。
- 成员加入结果通过群成员同步或 webhook 观察。

### 9.2 内部 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/channel-accounts/{accountId}/whatsapp/groups` | 创建或通过链接接入群，具体 mode 由 capability 控制 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/groups` | 分页查询群组 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}` | 查询群资料和同步状态 |
| PATCH | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}` | 修改主题、描述或头像 |
| DELETE | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}` | 删除或解除群接入 |
| POST | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}/invite-link` | 创建/刷新邀请链接 |
| DELETE | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}/invite-link` | 撤销邀请链接 |
| GET | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}/participants` | 查询成员 |
| DELETE | `/api/v1/channel-accounts/{accountId}/whatsapp/groups/{groupId}/participants/{participantNumber}` | 移除成员 |

群消息仍通过统一 `/whatsapp/messages` 发送，不新增平行的群消息发送 owner。

### 9.3 上游映射

- `AddChatGroup`
- `ListChatGroup`
- `UpdateChatGroup`
- `DeleteChatGroup`
- `AddChatGroupInviteLink`
- `DeleteChatGroupInviteLink`
- `ListChatGroupParticipants`
- `DeleteChatGroupParticipants`

`AddChatGroup` 的模式必须先实调：

- `CREATE` 只有在号码具备 OBA、当前账号实调成功且群消息/邀请 Webhook 门禁通过后才进入 capabilities。
- `IMPORT_BY_LINK` 只有在具备资格的真实账号上用真实 `GroupLink` 实调成功后才进入 capabilities；当前账号未完成该验证。
- `ListChatGroup` 成功不能推导 `group_create=true`。
- 未满足资格时不显示群组操作按钮，服务端返回结构化 `CAPABILITY_NOT_AVAILABLE`，原因固定为 `OBA_REQUIRED` 或真实上游错误。

## 10. 群聊数据模型

### 10.1 会话模型

现有 `conversations` 增加：

- `conversation_type`：`direct` 或 `group`。
- `provider_conversation_id`：群组使用稳定 provider group ID。
- `title`：群主题的本地投影。

约束：

- `direct` 会话必须有 `contact_identity_id`。
- `group` 会话不绑定单个 `contact_identity_id`，必须有 `provider_conversation_id`。
- 一对一和群组分别使用部分唯一索引，不能继续共用“账号 + 联系人身份”唯一规则。

### 10.2 群组与成员

新增群组投影保存：

- `channel_account_id`
- `provider_group_id`
- `conversation_id`
- `subject`
- `description`
- `group_link`
- `invite_link`
- `group_status`
- `business_role`
- `participant_count`
- `provider_updated_at`
- `last_synced_at`
- provider metadata

群成员投影保存 provider participant number、角色、加入/离开状态、最后同步时间，以及可选的本地 `contact_identity_id`。不能因为号码相似自动绑定联系人；未匹配成员保持外部身份。

群成员投影是上游群状态快照，不拥有 CRM 联系人真相。

## 11. 权限与审计

### 11.1 角色建议

- 客服人员：查看会话、发送允许的消息、查看已批准模板、查看群成员、生成邀请链接。
- 渠道管理员：账号检查、模板注册/修改/删除、群创建/接入、群资料修改、成员移除。
- 审计角色：查看操作记录和结构化失败，不默认查看敏感凭据或完整原始载荷。

### 11.2 审计事件

至少记录：

- 模板创建、修改、删除和审核状态变化。
- 群创建/接入、资料修改、邀请链接创建/撤销、成员移除。
- 账号配置和 webhook 配置变化。
- 人工重试和 `delivery_unknown` 的处置。

审计记录包含 actor、账号、目标稳定 ID、操作、结果、trace ID 和时间，不记录密钥与完整消息正文。

## 12. 错误与资源边界

### 12.1 结构化错误

错误至少区分：

- `CAPABILITY_NOT_AVAILABLE`
- `CHANNEL_ACCOUNT_NOT_READY`
- `PROVIDER_AUTH_FAILED`
- `PROVIDER_PERMISSION_DENIED`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_TIMEOUT`
- `PROVIDER_REJECTED`
- `TEMPLATE_NOT_APPROVED`
- `TEMPLATE_VALIDATION_FAILED`
- `GROUP_NOT_FOUND`
- `INVITE_LINK_UNAVAILABLE`
- `DELIVERY_STATE_UNKNOWN`

Controller 不捕获所有异常后统一返回 HTTP 400。业务错误映射稳定 HTTP 状态，provider request ID 进入诊断 context，但不向普通用户暴露敏感上游载荷。

### 12.2 上界

- 所有 CAMS 请求设置连接和响应超时。
- webhook 限制请求体大小。
- 模板和群组分页设置页大小、最大页数和单轮总数上限。
- 同一账号的消息、模板和群组同步分别单飞。
- 重试使用有上限的指数退避和抖动，遵守 provider 限流。
- 文件上传校验 MIME、扩展名和大小，并设置上传超时。
- SSE 只发送资源失效通知，不携带完整消息、模板或群成员数据。

## 13. 版本 A 实施阶段

### A0：真实能力探测与账号底座

目的：在写入业务能力前确认账号、号码、WABA、凭据和群组/模板权限。

交付：

- 账号作用域 gateway。
- capabilities、connection-check 和 status API。
- 真实账号探测报告。
- webhook 可达性、验签方式和权限确认。

停止条件：`AddChatGroup`、群消息 recipient 格式、模板创建和 webhook 权限任一无法确认时，关闭对应 capability，不阻断已确认的一对一消息和模板能力。当前账号的群组停止原因是 `OBA_REQUIRED / 131215`。

### A1：可靠的一对一消息闭环

目的：让文本、模板、图片、视频和文件真正进入消息中心 owner。

交付：

- webhook 收件箱与统一 projector。
- 消息 outbox、幂等发送和状态历史。
- 轮询补偿。
- 前端 pending、delivered、read、failed 和 unknown 展示。

验收：重复 webhook 和重复 `clientRequestId` 均只产生一条业务消息；上游超时不产生静默成功或盲目重复发送。

### A2：模板完整生命周期

目的：用户可以在系统中注册和管理 MVP 范围模板。

交付：

- `UTILITY`、`MARKETING` 创建、查询、修改、删除。
- BODY、变量示例、媒体 Header、Footer 和常用按钮编辑。
- 审核状态和拒绝原因同步。
- 只有已批准语言版本能进入发送选择器。

验收：创建后先显示提交/审核状态；上游批准、拒绝、修改和删除能通过对账进入本地；同步失败保留上一版完整快照。

### G1：条件群组与群消息能力包

目的：仅在真实账号满足 OBA 和 Groups API 资格后，员工才能创建或接入群、邀请成员，并在消息中心收发群消息。

交付：

- 群会话和成员数据模型。
- 群创建/接入、列表、资料、邀请链接、二维码、成员查询和移除。
- 群消息入站投影和统一发送。
- 群权限与审计。

验收：OBA 资格探测通过后，员工创建或接入群并生成邀请；成员加入后可同步显示；群消息进入正确群会话，系统回复进入同一群；直接添加成员能力不出现。未满足资格时，接口返回 `CAPABILITY_NOT_AVAILABLE`，不创建本地群投影。

### A4：前端和端到端门禁

目的：把账号、一对一消息和模板组合成可操作的基础产品；群组仅在 `G1` capability 开启后接入页面。

交付：

- 渠道状态和能力提示。
- 模板管理页面。
- 群列表、群详情、邀请二维码和成员页面（仅在 `G1` 可用时显示）。
- 一对一消息操作体验；群会话只有在 `G1` 可用时启用。
- 加载、空态、权限、限流和失败状态。

验收：桌面和移动视口完成真实渲染检查；使用真实账号完成模板提交、一对一消息和状态回执端到端测试。群组页面和群消息仅在 `G1` capability 开启时纳入本阶段验收。

## 14. 版本 B 扩展阶段

| 阶段 | 能力 | 主要上游 API/合同 |
| --- | --- | --- |
| B1 | 音频、贴纸、位置、联系人卡片、互动按钮/列表、引用回复、Reaction | `SendChatappMessage` 扩展类型 |
| B2 | 群头像、更多群角色和成员同步策略、群会话高级权限 | ChatGroup 系列 API |
| B3 | 批量模板发送、受众和营销活动 | `SendChatappMassMessage`、MessageCampaign、CustomAudience |
| B4 | WhatsApp Flow 表单和自动化 | ChatFlow、FlowVersion、TriggerChatFlow |
| B5 | 商品目录、商品消息和商务设置 | ProductCatalog、Product、CommerceSetting |
| B6 | WABA/号码自助开通、注册、注销、同步和迁移 | BindWaba、PhoneNumber、Migration 系列 |
| B7 | 模板/号码指标、活动洞察、ROI 和转化回传 | Metric、CampaignInsights、Conversion API |
| B8 | WhatsApp Calling | `WhatsappCall`，仅在业务和账号权限明确时启用 |

推荐扩展顺序为 `B1 -> B3`，其余阶段由真实客户需求触发。版本 B 的任何能力都不能成为版本 A 消息收发和模板管理的隐性依赖。

## 15. 测试与验收

### 15.1 单元与契约测试

- CAMS response 到内部模型的成功、空体、业务错误、限流和超时映射。
- template component 校验、变量示例、状态归一化和稳定键。
- direct/group conversation 约束和 recipient 解析。
- provider error 到结构化错误的映射。
- capability 关闭时禁止进入 gateway。

### 15.2 数据层测试

- webhook 和 `clientRequestId` 幂等。
- outbox 领取、租约、失败重试和超时歧义。
- 模板全量对账的新增、修改、删除和失败回滚。
- 群组与参与人快照的新增、离开、重新加入和本地身份可选绑定。
- direct/group 部分唯一索引不能互相污染。

### 15.3 HTTP 与安全测试

- webhook 无用户 token 可达，但无效签名、过期请求和超限请求被拒绝。
- 客服和渠道管理员权限边界。
- 模板和群组破坏性操作有审计。
- 所有错误符合统一 `ApiError` 合同。

### 15.4 UI 验收

- 加载、空数据、权限不足、上游限流、上游不可用和部分 capability 状态。
- 模板提交后展示审核中，不伪装已批准。
- 邀请二维码与原始链接一致。
- 群会话标题、成员数、发送目标和一对一会话不混淆。
- 桌面和移动视口无布局重叠。

### 15.5 真实账号门禁

使用真实阿里云 CAMS 账号和测试号码完成：

1. 连接检查和 WABA/号码状态读取。
2. webhook 可达、合法回调处理和重复投递。
3. 一对一文本、模板和媒体发送及回执。
4. `UTILITY` 或 `MARKETING` 模板创建和审核状态同步。
5. 若账号具备 OBA，才执行 `AddChatGroup` 的创建/链接接入语义确认。
6. 若账号具备 Groups API 资格，才执行邀请链接生成、成员加入、成员列表同步和群消息门禁。

保护机制介入、mock 通过或本地固定返回值不能替代真实账号门禁。

### 15.6 当前代码层证据

- 后端定向测试覆盖消息受理、outbox、固定账号解析、权限、媒体、Webhook、轮询投影和 opaque session。
- `AppIntegrationTest` 在 PostgreSQL 17.5 容器上 4/4 通过，Flyway v1-v8 从空库迁移成功；错误凭据返回结构化 401，未认证 API 返回 401，未知路由返回 404。
- 前端测试与生产构建通过；现有 500 kB chunk warning 作为非本轮债务保留。
- MyBatis mapper 当前存在两个扫描入口，容器启动会输出重复注册 warning；在独立配置治理前，不把日志无 warning 作为本轮完成声明。

## 16. 风险与停止条件

### 16.1 阻断问题

- 当前账号未开通群组、模板创建或 webhook 权限。
- 无法获得 provider 要求的 webhook 验证方式。
- 群消息没有稳定 group ID 或 recipient 合同。
- 当前号码不允许 `AddChatGroup` 的目标模式。
- 当前号码缺少 OBA 或其他 Groups API 资格时，群组能力必须保持关闭并可解释。
- 模板状态/语言/组件响应无法可靠映射。

出现阻断时，关闭对应 capability 并保留结构化原因；不得用 mock、固定成功或 UI 隐藏伪装完成。

### 16.2 设计风险

- CAMS 群组 API 可能受 OBA、地区、账号类型或灰度权限限制。
- 当前账号 `IsOfficial=N`，Meta 以 `#131215` 拒绝 `AddChatGroup`；申请资格不能从 `ListChatGroup` 的成功响应推导。
- 群成员涉及个人数据，保留范围和审计需要遵循客户部署地区政策。
- 模板规则会随 WhatsApp 政策变化，应保留 provider 原始状态和 metadata，避免只保存当前枚举。
- 群消息会扩大 conversation 权限面，不能默认继承单联系人会话的可见范围。

### 16.3 非本轮能力

版本 B 能力不进入版本 A 完成门禁。发现相关 SDK 类型或页面入口时，只记录 capability，不提前加入生产调用链。

## 17. 完成定义

版本 A 只有同时满足以下条件才算完成：

- 单个 WhatsApp Business 号码通过真实连接检查。
- 一对一消息能可靠收发并展示最终状态或明确失败原因。
- webhook 通过验证、幂等和持久化门禁。
- `UTILITY`、`MARKETING` 模板能注册、对账和按审核状态发送。
- 权限、审计、超时、重试、分页和资源上界生效。
- 单元、数据层、HTTP、安全、UI 和真实账号验收均有本轮证据。

`G1` 条件群组能力包只有同时满足以下条件才算完成：

- OBA 和 Groups API 资格通过真实账号探测。
- `AddChatGroup` 创建或 `GroupLink` 接入语义有真实证据。
- 邀请链接、成员 Webhook、群消息入站和系统回复闭环通过验收。
- 不存在直接添加群成员的虚假入口。

版本 B 按独立阶段验收，任何扩展失败不得破坏版本 A 的消息和模板主线。
