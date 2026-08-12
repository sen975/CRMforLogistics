# WhatsApp 模板完整生命周期设计

**状态：** 已确认，待实施

**日期：** 2026-08-10

**本次更新：** 2026-08-11，补齐媒体上传持久幂等与可查询状态合同

## 1. 目标

在现有固定 WhatsApp Business 号码和消息中控 MVP 上，交付可由平台管理员直接使用的模板完整生命周期：创建并提交审核、查询、修改并重新审核、暂停或恢复发送、删除、审核状态同步、拒绝原因展示、媒体 Header 上传、操作记录和审计。

本设计以阿里云 CAMS 官方 API 和项目锁定的官方 SDK `com.aliyun:alibabacloud-cams20200606:5.0.5` 为唯一 provider 合同。平台不模拟 Meta 审核，不提供官方 API 没有承诺的能力。

## 2. 已确认边界

- CAMS/WhatsApp 是唯一审核方，不增加本地草稿和企业内部审批。
- 本轮包含后端、数据库迁移、前端管理页、销售发送选择器和自动化验收。
- 只有平台管理员可以创建、修改、启停、删除和手动同步模板。
- 销售只能查看和使用当前账号下已批准、允许发送且未删除的语言版本。
- 支持 `UTILITY`、`MARKETING`，不支持 `AUTHENTICATION`。
- 支持 BODY、变量示例、文本或媒体 Header、Footer、快速回复、URL 和电话按钮。
- 不支持 Carousel、Flow、限时优惠、商品或营销活动编排。
- 媒体 Header 由管理员上传文件；后端使用 CAMS 上传授权写入 CAMS 指定 OSS，不要求用户准备公网 URL。
- 媒体上传是 provider 写操作，必须使用持久化 `clientRequestId` 保证同一请求 ID 最多发起一次复合上传尝试；不承诺跨本地数据库与 CAMS/OSS 的 exactly-once。
- 手动 `sync` 只触发只读 List/Detail 对账，不接受 `clientRequestId`，也不创建 provider 写操作记录。
- 号码注册、多号码切换、群组、群发和账号迁移不进入本轮。

## 3. 官方 API 真源

| 能力 | CAMS 官方 API | 本地用途 |
| --- | --- | --- |
| 创建并送审 | `CreateChatappTemplate` | 创建模板语言版本并提交 WhatsApp 审核 |
| 查询列表 | `ListChatappTemplate` | 分页对账审核状态和上游可见集合 |
| 查询详情 | `GetChatappTemplateDetail` | 读取组件、示例、发送开关、质量、拒绝原因和 TTL |
| 修改并重审 | `ModifyChatappTemplate` | 修改官方允许的内容并重新进入审核 |
| 启停发送 | `ModifyChatappTemplateProperties` | 只修改 `allowSend`，不伪造审核状态 |
| 删除 | `DeleteChatappTemplate` | 删除指定模板语言版本 |
| 上传素材 | `GetChatappUploadAuthorization` | 获取短期 OSS 凭据并上传 Header 审核素材 |

`UpdateAuditRequest` 是 CAMS 通用企业资料审核记录接口，不是 WhatsApp 模板批准或拒绝接口，本轮禁止调用。

SDK 只提供上传授权，没有明确的模板审核素材删除 API。上传成功但模板提交失败时，本地素材状态进入 `ORPHANED`；当前不承诺物理删除 CAMS OSS 对象，也不尝试未被官方合同保证的删除权限。

## 4. Owner 与架构

模板业务语义的唯一 owner 是 `whatsapp-template` application service。Controller 只做 HTTP 映射，前端只展示结构化状态，CAMS adapter 只映射官方 SDK 请求和响应。

```text
WhatsAppTemplateController
  -> WhatsAppTemplateApplicationService
      -> TemplateMapper
      -> TemplateOperationMapper
      -> TemplateMediaAssetMapper
      -> AuditLogMapper
      -> WhatsAppTemplateGateway
          -> AliyunChatAppTemplateGateway
              -> CreateChatappTemplate
              -> ListChatappTemplate
              -> GetChatappTemplateDetail
              -> ModifyChatappTemplate
              -> ModifyChatappTemplateProperties
              -> DeleteChatappTemplate
              -> GetChatappUploadAuthorization + OSS PUT
```

媒体上传仍属于 `whatsapp-template` 业务 owner，但从已经较大的 `WhatsAppTemplateApplicationService` 中拆为聚焦的 `WhatsAppTemplateMediaUploadService`。它通过短事务协作者原子预留请求、落最终状态；CAMS 授权和 OSS PUT 必须发生在数据库事务之外。Controller、前端和 gateway 均不得自行解释幂等状态。

现有 `ChatAppTemplateSyncService` 收敛为定时触发 adapter，不再拥有审核状态解释、删除判断或数据库 upsert 语义。现有 `ChatAppTemplateService` 收敛为销售发送选择器 adapter，读取同一个模板 owner。

## 5. 状态模型

### 5.1 审核状态

本地标准状态保持大写：

| CAMS 原始状态 | 本地审核状态 |
| --- | --- |
| `auditing` | `PENDING` |
| `pass` | `APPROVED` |
| `fail` | `REJECTED` |
| `unaudit` | `SUSPENDED` |
| 空值或未知值 | `UNKNOWN` |

数据库同时保存本地标准状态、CAMS 原始状态和拒绝原因。未知状态必须 fail-closed，不能发送。

### 5.2 发送开关

`allow_send` 独立于审核状态。`ModifyChatappTemplateProperties` 只改变发送开关，不能把未批准模板变成可发送模板。

可发送条件必须全部满足：

```text
reviewStatus == APPROVED
&& allowSend == true
&& deletedAt == null
&& channelAccountId 与会话账号一致
&& languageCode 精确匹配
&& 发送参数满足组件变量合同
```

### 5.3 操作状态

创建、修改、启停和删除分别创建持久化操作记录：

- `PROCESSING`：本地已接受，正在调用官方 API。
- `SUCCEEDED`：官方响应与本地投影均已持久化。
- `SUBMISSION_UNKNOWN`：请求超时或连接中断，无法确认上游是否受理。
- `FAILED`：上游明确拒绝或本地校验失败。

`SUBMISSION_UNKNOWN` 禁止自动重复提交同一 provider 写操作。后台只能通过官方 List/Detail API 对账；确认结果后转为 `SUCCEEDED` 或 `FAILED`。

### 5.4 媒体上传状态

- `PROCESSING`：请求指纹已持久化，当前进程可能正在调用 CAMS/OSS。
- `UPLOADED`：provider 明确返回成功，object key 和 URL 已持久化。
- `FAILED`：provider 明确拒绝或在调用前发生可确定失败；同一请求 ID 不重试。
- `SUBMISSION_UNKNOWN`：调用超时、连接中断、进程失联或无法确认 provider 是否接收；禁止自动重放。
- `ATTACHED`：模板创建或修改明确成功，并已引用该素材。
- `ATTACHMENT_UNKNOWN`：模板写操作结果未知，无法确认素材是否已被引用。
- `ORPHANED`：模板写操作明确失败或对账确认未引用该素材。

`PROCESSING` 不是永久状态。读取上传状态时，若 `started_at` 已超过 90 秒且仍未完成，后端以条件更新将其收敛为 `SUBMISSION_UNKNOWN`。这个转换不调用 provider，也不能据此断言 provider 未执行。

### 5.5 删除

上游明确删除成功后，本地写 `deleted_at`，保留模板快照、操作记录、审计和历史消息正文。销售选择器完全隐藏已删除版本；管理员默认列表隐藏，可通过“已删除”筛选查看。

## 6. 数据模型

新增 Flyway 迁移扩展 `message_templates`：

- `category`：`UTILITY` 或 `MARKETING`。
- `template_type`：本轮固定保存官方 WhatsApp 模板类型。
- `components_jsonb`：标准化组件快照。
- `examples_jsonb`：变量示例快照。
- `message_send_ttl_seconds`：可空官方 TTL。
- `allow_send`：最近一次官方详情返回的发送开关。
- `provider_audit_status`：CAMS 原始审核状态。
- `rejection_reason`：CAMS 原始拒绝原因。
- `quality_score`：CAMS 原始质量信息。
- `deleted_at`：本地软删除时间。
- `version`：乐观锁版本。

现有 `status` 继续作为标准化审核状态；`metadata_jsonb` 只保存未提升为稳定列的 provider 扩展字段。

新增 `template_operations`：

- `id`、`channel_account_id`、`idempotency_key`。
- `operation_type`：`CREATE`、`MODIFY`、`SET_SEND_PERMISSION`、`DELETE`、`RECONCILE`。
- `provider_template_id`、`language_code`。
- `requested_snapshot_jsonb`。
- `operation_status`。
- `provider_request_id`、`provider_code`。
- `error_code`、`error_message`。
- `next_reconcile_at`、`reconcile_attempt_count`。
- `actor_user_id`、`trace_id`、`started_at`、`completed_at`。

唯一约束为 `channel_account_id + idempotency_key`。同一个管理员请求重复到达时返回原操作，不再次调用 CAMS。

`V9__whatsapp_template_lifecycle.sql` 已执行后保持不可变。新增 `V10__whatsapp_template_media_idempotency.sql` 扩展 `template_media_assets`：

- `id`、`channel_account_id`。
- `client_request_id`：媒体请求在账号内唯一，必须匹配 `[A-Za-z0-9._~:-]{1,255}` 路径段安全合同。
- `provider_object_key`、`provider_url`：`PROCESSING`、`FAILED`、`SUBMISSION_UNKNOWN` 时允许为空。
- `media_format`、`content_type`、`size_bytes`、`sha256`。
- `asset_status`：`PROCESSING`、`UPLOADED`、`FAILED`、`SUBMISSION_UNKNOWN`、`ATTACHED`、`ATTACHMENT_UNKNOWN`、`ORPHANED`。
- `error_code`、`error_message`、`trace_id`。
- `created_by_user_id`、`started_at`、`created_at`、`updated_at`、`attached_at`。

唯一索引为 `channel_account_id + client_request_id`。V9 旧记录按 `legacy:` 加素材 UUID 回填稳定请求 ID，`started_at` 回填 `created_at`，`updated_at` 回填 `COALESCE(attached_at, created_at)`，完成回填后再设置非空并替换状态 CHECK；原始文件名不作为持久幂等所需字段，本轮不新增。

临时 OSS AccessKey、Secret 和 SecurityToken 不进入数据库、日志、错误响应或审计。

现有 `audit_logs` 记录模板创建、修改、启停、删除、审核状态变化和操作失败。`audit_logs.result` 是跨领域通用结果，只允许 `success`、`denied`、`failed`、`unknown`；领域状态继续由 owner 表及 `after_summary_jsonb` 持有。媒体上传映射固定为 `UPLOADED -> success`、`FAILED -> failed`、`SUBMISSION_UNKNOWN -> unknown`，不得同时引入大写重复值，也不得把未知结果降格为失败。V10 只扩展 V3 的结果约束以加入 `unknown`，不新增列、不修改 V3/V9 或历史数据。审计摘要不保存临时密钥和完整 provider 原始载荷。

## 7. HTTP 合同与权限

### 7.1 销售发送选择器

```http
GET /api/templates
```

任意有效登录用户可访问，只返回当前固定 WhatsApp 账号下满足可发送条件的语言版本。响应包含发送表单需要的模板代码、名称、语言、正文、组件和变量，不返回已拒绝、审核中、暂停或删除版本。

### 7.2 管理 API

以下路径统一要求 `ROLE_ADMIN`：

```http
GET    /api/v1/channel-accounts/{accountId}/whatsapp/templates
GET    /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}?language=en_US
POST   /api/v1/channel-accounts/{accountId}/whatsapp/templates
PUT    /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}?language=en_US
PUT    /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/send-permission?language=en_US
DELETE /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}?language=en_US
POST   /api/v1/channel-accounts/{accountId}/whatsapp/templates/sync
POST   /api/v1/channel-accounts/{accountId}/whatsapp/template-media
GET    /api/v1/channel-accounts/{accountId}/whatsapp/template-media/uploads/{clientRequestId}
GET    /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/operations?language=en_US
```

列表接口支持 `page`、`size`、`search`、`status`、`category`、`language`、`allowSend` 和 `deleted`。`size` 最大 100。

所有会触发 provider 写入的请求必须带 `clientRequestId`，用作本地持久幂等键；媒体上传的值必须匹配 `[A-Za-z0-9._~:-]{1,255}` 路径段安全合同。`templates/sync` 是只读对账触发，明确排除。

媒体上传通过 multipart 字段提交 `format`、`file`、`clientRequestId`。状态查询以账号和请求 ID 定位记录，不接受素材 ID 替代请求 ID。查询响应包含内部素材 ID、请求 ID、状态、格式、内容类型、大小、SHA-256、可用时的 provider URL，以及结构化错误 code/message/traceId；不返回 provider 临时凭据。

上传 POST 在新请求完成为 `UPLOADED` 时返回 `201`；相同指纹的既有成功或后续附件状态返回 `200`；并发重复仍为 `PROCESSING` 或结果未知时返回 `202` 和当前状态；明确失败在落库后按原错误 code/status 返回非 2xx；请求 ID 重用为不同指纹时返回 `409 IDEMPOTENCY_KEY_REUSED`。状态查询对任一已持久状态返回 `200`，不存在时返回 `404`。

### 7.3 模板命令

创建和修改命令包含：

- `name`
- `language`
- `category`
- `components`
- `examples`
- 可选 `messageSendTtlSeconds`
- `clientRequestId`

页面和 API 不接收 `custSpaceId`、WABA ID、AccessKey 或 Secret。账号作用域由服务端根据 `accountId` 校验后解析。

## 8. 组件与校验

- 每个模板必须恰好有一个 BODY；BODY 最长 1,024 字符。
- Header 最多一个，格式为 `TEXT`、`IMAGE`、`VIDEO` 或 `DOCUMENT`。
- 文本 Header 和 Footer 各最长 60 字符。
- 媒体 Header 必须引用同一账号下状态为 `UPLOADED` 的内部素材 ID。
- 图片只接受 JPEG/PNG，最大 5 MiB；视频只接受 MP4，最大 16 MiB；文件只接受 PDF，最大 64 MiB。
- BUTTONS 最多一组；官方 SDK 约束下按钮总数最多 10。
- 电话按钮最多一个，URL 按钮最多两个。
- 快速回复不能与电话或 URL 按钮混用。
- BODY 和 Header 中的变量必须有对应示例；变量名和发送参数必须精确匹配。
- `category` 只接受 `UTILITY`、`MARKETING`。
- `language`、TTL、按钮类型和 URL 语义最终按官方 SDK 与 CAMS 返回校验；provider 明确拒绝时转换为结构化业务错误，不改写请求后偷偷重试。

## 9. 核心流程

### 9.1 媒体上传

1. Controller 校验管理员、账号、`clientRequestId`、文件非空、媒体格式和声明大小。
2. Service 有界读取文件并再次校验 5/16/64 MiB 分类上限，计算 SHA-256。`contentType` 先 trim、转小写并移除分号后的参数，再按媒体格式 allowlist 校验；幂等请求指纹固定为 `mediaFormat + contentType + sizeBytes + sha256`。
3. 短事务使用 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` 原子插入 `PROCESSING` 占位并提交；禁止先查后插。
4. 若账号和请求 ID 已存在，则比较完整指纹：不同则返回 `IDEMPOTENCY_KEY_REUSED`；相同则返回原状态，不调用 CAMS/OSS。
5. 只有成功创建占位的调用方可在无数据库事务状态下执行一次复合上传尝试；该尝试中的 `GetChatappUploadAuthorization` 与 OSS PUT 分别至多调用一次。
6. provider 明确成功后，用短事务条件更新为 `UPLOADED`；明确失败更新为 `FAILED`；超时、连接中断或无法判断是否提交则更新为 `SUBMISSION_UNKNOWN`。
7. 若进程在 provider 调用前后崩溃，记录可能暂留 `PROCESSING`；状态查询在 90 秒后将其保守收敛为 `SUBMISSION_UNKNOWN`，绝不自动重放。
8. 创建或修改明确成功后转 `ATTACHED`；明确失败后转 `ORPHANED`；模板提交结果未知时转 `ATTACHMENT_UNKNOWN`。
9. 每个上传终态转换与审计在同一个短事务中提交；通用审计结果使用 `success/failed/unknown`，精确媒体状态保留在 `template_media_assets.asset_status` 与审计摘要中。

重复请求合同：

- 同一请求 ID、同一指纹、`UPLOADED`：返回原素材，不调用 provider。
- 同一请求 ID、同一指纹、`PROCESSING`：返回处理中，不调用 provider。
- 同一请求 ID、同一指纹、`FAILED`：返回原失败；新尝试必须显式使用新请求 ID。
- 同一请求 ID、同一指纹、`SUBMISSION_UNKNOWN`：返回未知；禁止自动换 ID 或自动重传。
- 同一请求 ID、同一指纹、`ATTACHED`、`ATTACHMENT_UNKNOWN` 或 `ORPHANED`：返回原素材当前状态，不调用 provider；需要重新上传时必须由用户显式创建新请求 ID。
- 同一请求 ID、不同指纹：返回 `IDEMPOTENCY_KEY_REUSED`。

该合同只保证同一账号、同一 `clientRequestId` 在本系统中最多发起一次复合上传尝试。数据库提交与外部 provider 之间不存在分布式事务，因此响应丢失、进程崩溃或 provider 未提供可按请求 ID 查询的能力时，只能暴露 `SUBMISSION_UNKNOWN`，不能宣称 exactly-once。

### 9.2 创建

1. 校验管理员、账号状态、幂等键、组件和素材归属。
2. 写 `template_operations=PROCESSING`。
3. Gateway 映射并调用 `CreateChatappTemplate`。
4. 保存官方返回的 `templateCode/templateName`。
5. 立即调用官方 Detail；拿不到详情时保存 `UNKNOWN`，不能假定批准。
6. upsert 本地模板，操作转 `SUCCEEDED`，写审计。
7. 超时进入 `SUBMISSION_UNKNOWN`，关联媒体进入 `ATTACHMENT_UNKNOWN`，不再次创建。

### 9.3 修改

1. 以 `channelAccountId + templateCode + language` 锁定本地版本并校验未删除。
2. 写操作记录后调用 `ModifyChatappTemplate`。
3. 立即查询 Detail 并覆盖完整快照。
4. 若上游重新审核，本地展示 `PENDING`，销售选择器立即隐藏。

### 9.4 暂停或恢复

调用 `ModifyChatappTemplateProperties(allowSend)`，成功后查询 Detail。恢复发送仍需审核状态为 `APPROVED`；接口不能绕过审核状态。

### 9.5 删除

写操作记录后调用 `DeleteChatappTemplate`。只有上游明确成功才写本地软删除；超时进入 `SUBMISSION_UNKNOWN` 并由对账确认。

### 9.6 对账

- 单轮最多 20 页，每页最多 100，总量上限 2,000。
- 同一账号模板同步单飞。
- 每页 List 成功后逐项 Detail；详情失败保留该版本上一份完整组件快照，但允许更新列表中已确认的审核状态和拒绝原因。
- 只有所有 List 分页完整成功时，本轮集合才可用于判断上游删除。
- 任一 List 页失败时禁止把未出现模板标为删除。
- 仅业务内容、审核状态、发送开关、拒绝原因或删除状态变化时发布 `templates-changed`。
- 对账详情明确引用该素材 URL 时转 `ATTACHED`；上游明确未受理写操作时转 `ORPHANED`；无法判定时保持 `ATTACHMENT_UNKNOWN`，不能用观察窗口耗尽代替事实。

## 10. 前端管理页

现有 `TemplatesPage` 改为表格主视图，复用当前 Ant Design 风格：

- 搜索以及状态、类别、语言、发送开关、已删除筛选。
- 列展示模板名称、代码、语言、类别、审核状态、拒绝原因摘要、发送状态、最后同步时间。
- 新建和编辑使用结构化表单，不提供原始 JSON 编辑器。
- 媒体 Header 使用文件选择和上传进度；上传完成后显示预览和替换操作。
- 用户选择文件时生成一次稳定 `clientRequestId`；同一次 POST 和后续状态查询始终复用该 ID。
- POST 响应丢失、网络异常或返回处理中时，每 2 秒查询一次，最多 45 次。`UPLOADED` 恢复内部素材 ID；`FAILED` 或 `SUBMISSION_UNKNOWN` 立即停止。
- 轮询耗尽后保留请求 ID并展示“仍在处理”，不自动生成新 ID 重传。只有用户明确选择重新上传或选择新文件时，才创建新的请求 ID。
- 详情抽屉展示完整组件、示例、原始审核状态、拒绝原因、质量、TTL 和操作记录。
- 行操作使用编辑、暂停/恢复和删除；删除必须二次确认。
- 页面提供手动同步按钮以及成功、部分失败、完整失败状态。
- 加载、空列表、403、provider 失败、上传失败和 `SUBMISSION_UNKNOWN` 都有明确状态，不使用瞬时成功提示替代持久化结果。

销售发送选择器根据模板组件生成变量输入，只提交模板代码、语言、内部变量映射和稳定 `clientRequestId`，不能手工覆盖模板名或账号。

## 11. 结构化错误

- `TEMPLATE_VALIDATION_FAILED`
- `TEMPLATE_NOT_FOUND`
- `TEMPLATE_NOT_APPROVED`
- `TEMPLATE_SEND_DISABLED`
- `TEMPLATE_OPERATION_IN_PROGRESS`
- `TEMPLATE_OPERATION_UNKNOWN`
- `TEMPLATE_MEDIA_INVALID`
- `TEMPLATE_MEDIA_UPLOAD_FAILED`
- `TEMPLATE_MEDIA_UPLOAD_IN_PROGRESS`
- `TEMPLATE_MEDIA_SUBMISSION_UNKNOWN`
- `IDEMPOTENCY_KEY_REUSED`
- `CHANNEL_ACCOUNT_NOT_READY`
- `PROVIDER_AUTH_FAILED`
- `PROVIDER_PERMISSION_DENIED`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_TIMEOUT`
- `PROVIDER_REJECTED`

错误响应包含稳定 code、用户可展示 message、trace ID 和字段错误。provider request ID 只进入诊断 context 和审计，不暴露临时凭据或完整响应体。

## 12. 资源与一致性边界

- CAMS 和 OSS 连接超时 15 秒、读取超时 60 秒，单次调用总时长上限 75 秒。
- 文件在读取前检查声明大小，流式读取时再次执行 5/16/64 MiB 的分类硬上界；HTTP multipart 请求总上限沿用项目 65 MiB。
- 单次上传最多在内存中持有一个已通过分类上界校验的文件副本，provider 调用返回后释放；禁止把文件内容写入数据库、普通日志或审计。
- 媒体请求预留和最终状态各使用独立短事务；外部 CAMS/OSS 调用期间 `TransactionSynchronizationManager.isActualTransactionActive()` 必须为 false。
- 同一账号和请求 ID 依赖数据库唯一索引仲裁并发；禁止 JVM 内存 Map、前端 UUID 去重或进程内锁充当幂等真源。
- 同一账号的模板写操作按稳定模板键串行化。
- 操作对账最多 10 次，退避上限 3 小时，总观察窗口 24 小时；超过上界保持可查询失败，不无限轮询。
- 创建、修改和删除的未知结果不自动重放。
- 同步分页、前端分页、操作历史和审计查询都有上界。
- 临时上传凭据只存在于调用栈内，并禁止进入普通日志。

## 13. 验收标准

### 13.1 后端

- Gateway 单测逐项验证七个官方 API 的请求字段、响应和错误映射。
- Application service 测试覆盖权限、幂等、状态机、参数校验、未知提交和素材归属。
- 媒体上传 service 测试覆盖并发同 key 只调用一次 gateway、相同 key 不同指纹冲突、每个持久状态的重复返回、provider 调用无活动事务，以及失联 `PROCESSING` 收敛为 `SUBMISSION_UNKNOWN`。
- PostgreSQL 集成测试覆盖通用审计结果映射、旧 `denied` 兼容、`unknown` 可持久化，以及大写领域状态不能写入 `audit_logs.result`。
- 同步测试覆盖 `pass/fail/auditing/unaudit/unknown`、拒绝原因、完整删除判断和分页失败保留快照。
- PostgreSQL 17.5 Testcontainers 从空库执行 Flyway，并验证组件 JSON、操作幂等、媒体请求唯一索引、V9 旧素材回填、可空 provider 字段、软删除、乐观锁和发送选择条件。
- 现有消息发送测试证明未批准、暂停和删除模板均不能进入 outbox。

### 13.2 前端

- 单元测试覆盖列表筛选、创建表单、组件校验、稳定上传请求 ID、有界状态查询、响应丢失恢复、未知状态禁止自动重传、编辑、启停、删除和结构化错误。
- 浏览器验收覆盖管理员完整操作、销售只见可发送模板、桌面和移动视口、加载态、空态和失败态。
- 页面不泄漏 CAMS SDK 字段、AccessKey、Secret、SecurityToken 或 `custSpaceId`。

### 13.3 真实 CAMS 门禁

- 使用测试模板完成一次 `Create -> PENDING -> APPROVED/REJECTED` 状态同步。
- 完成一次官方允许的修改并观察重新审核状态。
- 完成一次 `allowSend=false -> true`，验证发送选择器同步变化。
- 完成一次删除并验证本地软删除和历史正文保留。
- 使用图片 Header 完成 CAMS 授权上传和模板送审。
- 真实操作必须使用专用测试模板名称和素材，执行前由平台管理员确认；自动化测试不得调用生产 CAMS 写接口。

## 14. 非目标与停止条件

本轮不实现本地审批、模板批量导入、跨账号复制、营销群发、群模板、AUTHENTICATION、Carousel、Flow、商品模板和素材物理删除。

出现以下情况必须关闭对应操作并返回结构化错误，不能用兼容分支绕过：

- 官方 SDK 或真实账号不支持该模板组件。
- CAMS 对修改、启停或删除返回权限不足。
- 无法获取官方上传授权。
- 真实返回状态与当前映射不一致且没有官方证据。
- provider 写入结果未知且无法通过 List/Detail 对账。
