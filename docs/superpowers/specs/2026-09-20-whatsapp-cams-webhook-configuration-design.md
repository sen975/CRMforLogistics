# WhatsApp CAMS 回调地址管理设计

## 文档状态

当前设计。本文补充 `2026-09-10-whatsapp-admin-managed-account-assignment-design.md`，只定义 CAMS 回调地址的 CRM 管理闭环，不改变 WABA、电话号码、账号分配和模板的现有 owner。

## 目标

管理员可以在 CRM 管理页面为已同步的 CAMS WhatsApp 电话配置或修改：

- 入站消息回调地址 `UpCallbackUrl`；
- 消息状态回执地址 `StatusCallbackUrl`；
- HTTP 回调开关 `HttpFlag`；
- MNS 队列开关 `QueueFlag`。

CRM 服务端调用阿里云 CAMS `UpdatePhoneWebhook` 写入号码级配置，并记录可审计的本地期望配置和 provider 写入结果。账户级 `UpdateAccountWebhook` 只用于状态回执配置，不承担入站消息路由。

## 当前事实

- 项目锁定 `com.aliyun:alibabacloud-cams20200606:5.0.5`。
- SDK 已提供 `AsyncClient.updatePhoneWebhook(UpdatePhoneWebhookRequest)`。
- `UpdatePhoneWebhook` 必填 `CustSpaceId`、`PhoneNumber`，支持 `UpCallbackUrl`、`StatusCallbackUrl`、`HttpFlag`、`QueueFlag`。
- SDK 也提供 `UpdateAccountWebhook`，但请求模型没有 `UpCallbackUrl`。
- CRM 已有公开接收入口 `/api/v1/webhooks/chatapp`，当前实现按标准化业务号码和 CAMS `CustSpaceId` 路由 active ChatApp/WhatsApp 账号。实机 CAMS 请求未携带 `X-CAMS-Signature`/`X-CAMS-Timestamp`，因此不能把项目内假设的 HMAC 当作必需外部合同。
- CAMS 写入已通过 `AliyunWhatsAppCallbackGateway` 调用 `UpdatePhoneWebhook` 和 `UpdateAccountWebhook`。
- 未确认存在 CAMS 的回调配置读取接口。因此 CRM 不能声称“从 CAMS 读取当前回调地址”，只能展示本地最近一次写入的期望配置和结果。

## 产品边界

### 本轮做什么

- 管理员在 CRM 中查看已同步 CAMS 电话。
- 管理员按电话保存入站消息和状态回执 URL。
- 服务端从现有 CAMS scope 和加密凭据解析 `custSpaceId`、AccessKey、区域和 endpoint。
- 服务端执行 URL、开关、账号归属和版本校验。
- 服务端调用 CAMS `UpdatePhoneWebhook`，持久化写入结果和审计。
- webhook 接收端兼容 CAMS 的无签名回调：无签名 Header 时执行 `CustSpaceId + 业务号码` scope 路由；若请求携带成对的签名 Header，则执行严格 HMAC 验签。两种路径都保留 body 上限、幂等和 active 账号校验。
- 前端展示保存状态、最近结果和恢复动作；provider RequestId 只保存在受限审计/结果字段，不进入浏览器投影。

### 本轮不做什么

- 不在 CRM 中创建、删除、注销 WABA 或电话号码。
- 不实现 Embedded Signup、Meta 授权、号码注册、验证码或模板管理。
- 不把 AccessKey Secret、完整号码、provider 原始响应或回调正文展示给浏览器。
- 不把本地期望配置当成 CAMS 回读事实；CAMS 没有已确认的回读合同时，页面必须显示“最近写入结果”。
- 不同时保留旧的“固定单账号 webhook”作为生产回退。多账号路由完成前，相关配置能力必须保持不可用或明确返回结构化错误。

## 唯一 owner 与职责

| 概念 | 唯一 owner | 禁止成为 owner 的层 |
| --- | --- | --- |
| CAMS scope、custSpaceId、加密凭据 | `WhatsAppProviderScopeEntity` + `WhatsAppCamsConfigService` | React、Controller、CAMS SDK adapter |
| CAMS 电话身份 | `ChannelAccountEntity` 的 `providerScopeId`、`accountIdentifierNormalized` | 浏览器提交值、回调正文中的任意名称字段 |
| 回调配置期望状态 | `WhatsAppCallbackConfigService` + `whatsapp_cams_callback_configs` | Controller、前端表单、webhook projector |
| CAMS 写入动作和 provider 错误映射 | `AliyunWhatsAppCallbackGateway` | 页面、通用 CAMS 配置服务 |
| webhook 验签和账号路由 | `ChatAppWebhookInboxService` + `ChatAppWebhookVerifier` | projector、前端、固定全局账号 |
| 管理员权限 | `WhatsAppAdminAuthorization` + Spring Security | 前端隐藏按钮 |
| 回调变更审计 | `WhatsAppCallbackAuditEntity`/对应 mapper | 普通应用日志 |

## 外部 API 合同

### 号码级配置

```java
UpdatePhoneWebhookRequest.builder()
    .custSpaceId(custSpaceId)
    .phoneNumber(normalizedPhoneNumber)
    .httpFlag("Y")
    .queueFlag("N")
    .upCallbackUrl(upCallbackUrl)
    .statusCallbackUrl(statusCallbackUrl)
    .build();
```

调用 `AsyncClient.updatePhoneWebhook(request)`。默认产品路线使用 HTTP 回调和非 MNS 队列；如果未来启用 MNS，必须另立设计，不允许页面静默切换。

### 账户级状态回执

`UpdateAccountWebhook` 只接受 `CustSpaceId`、`StatusCallbackUrl`、`HttpFlag`、`QueueFlag` 等字段。它不是入站消息配置入口。第一版可以在同一页面提供“账户级状态回执”折叠区域，但该配置不能覆盖号码级 `UpCallbackUrl`。

### Provider 回读边界

当前只以 SDK 证实更新动作，不以猜测的 `GetWebhook`、`QueryWebhook` 或控制台私有接口实现回读。保存成功后：

- 本地 `desired_*` 字段表示管理员最后提交的目标值；
- `last_apply_status=SUCCEEDED` 表示 CAMS 更新 API 返回成功；
- `last_apply_status=FAILED` 表示 CAMS 返回错误或请求超时；
- `provider_state=UNKNOWN` 表示没有可用的官方回读证据，不得显示“当前已生效”以外的强断言。

## 数据模型

新增迁移 `V79__whatsapp_cams_webhook_configuration.sql`。当前迁移最大版本为 V78，本计划固定使用 V79，避免实施时出现版本漂移。

### `whatsapp_cams_callback_configs`

| 字段 | 约束/用途 |
| --- | --- |
| `id` | UUID 主键 |
| `provider_scope_id` | 外键到 CAMS scope；只允许 `ALIYUN_CAMS` |
| `channel_account_id` | 号码级配置必填；账户级配置为空 |
| `level` | `PHONE` 或 `ACCOUNT` |
| `desired_up_callback_url` | 号码级入站地址；账户级必须为空 |
| `desired_status_callback_url` | 状态回执地址，可为空表示清空/不启用 |
| `http_flag` | `Y`/`N`，默认 `Y` |
| `queue_flag` | `Y`/`N`，默认 `N` |
| `provider_state` | 初始/失败为 `UNKNOWN`，成功写入仍为 `UNKNOWN`，直到有官方回读合同 |
| `last_apply_status` | `NEVER_APPLIED`、`SUCCEEDED`、`FAILED` |
| `last_provider_request_id` | 仅保存 CAMS RequestId，不保存原始响应；不返回浏览器 |
| `last_error_code` | 结构化本地/provider 错误码 |
| `last_applied_at` | 最近调用时间 |
| `version` | 乐观锁 |
| `created_at/updated_at` | 审计时间 |

约束：

- `(provider_scope_id, level)` 在 `level=ACCOUNT` 时唯一；
- `(provider_scope_id, channel_account_id, level)` 在 `level=PHONE` 时唯一；
- 号码级记录的 `channel_account.provider_scope_id` 必须等于 `provider_scope_id`；
- `desired_up_callback_url` 只能在 `PHONE` 层非空；
- 回调 URL 最大 2048 字节，仅允许 `https`，本地开发可由明确的 profile 开关允许 `http://localhost`；
- 不存储 AccessKey、Secret、签名密钥或回调正文。

### `whatsapp_cams_callback_audits`

记录 `actor_user_id`、scope、channel account、level、动作 `APPLY`/`CLEAR`、脱敏前后 URL 摘要、expectedVersion、结果、错误码、provider RequestId、时间。URL 审计只保存 host、path 的 SHA-256 摘要和是否 HTTPS，不保存 query、凭据或完整 URL。

## 服务端接口

所有接口要求管理员身份，由服务端再次调用 `WhatsAppAdminAuthorization.requireAdmin`。

### 查询配置

`GET /api/admin/whatsapp/cams/{scopeId}/callbacks`

返回中不出现 `custSpaceId`、AccessKey、Secret、完整号码或原始 provider 响应；号码仅返回脱敏值和本地回调投影。

### 保存号码级配置

`PUT /api/admin/whatsapp/cams/{scopeId}/callbacks/phones/{channelAccountId}`

请求：

```json
{
  "upCallbackUrl": "https://crm.example.com/api/v1/webhooks/chatapp",
  "statusCallbackUrl": "https://crm.example.com/api/v1/webhooks/chatapp",
  "httpFlag": "Y",
  "queueFlag": "N",
  "expectedVersion": 2
}
```

服务端锁定 scope 和 channel account，确认号码属于该 scope 且处于可同步状态，解析实际 provider 号码后调用 CAMS。CAMS 成功才更新 `lastApplyStatus=SUCCEEDED` 和 version；失败保留旧期望配置，写入失败结果并返回结构化 502。

### 保存账户级状态回执

`PUT /api/admin/whatsapp/cams/{scopeId}/callbacks/account`

只接受 `statusCallbackUrl/httpFlag/queueFlag/expectedVersion`，调用 `UpdateAccountWebhook`。请求不能包含 `upCallbackUrl`。

## 请求流程

```text
管理员页面
  -> GET callback projection
  -> PUT desired callback config + expectedVersion
  -> Controller 做身份校验和 DTO 约束
  -> CallbackConfigService 锁定 scope/account/config
  -> CredentialResolver 解密 CAMS 凭据
  -> AliyunWhatsAppCallbackGateway.updatePhoneWebhook
  -> 写 apply result + audit
  -> 返回新的本地 projection

CAMS webhook
  -> /api/v1/webhooks/chatapp
  -> 限制 body 大小
  -> 无签名 CAMS 请求按 CustSpaceId + 业务号码校验；成对签名 Header 存在时执行 HMAC 和时间窗校验
  -> 从数组或对象 payload 的 From/To/businessNumber 等字段提取业务号码
  -> 按 provider scope + normalized phone 找唯一 channel account
  -> 幂等落库 channel event
  -> projector 投影消息/状态
```

## Webhook 安全与多账号路由

- 真实 CAMS 请求目前不发送 `X-CAMS-Signature`/`X-CAMS-Timestamp`，因此两者同时缺失时不因“时间戳无效”拒绝；若只携带一个 Header，或携带成对 Header 但 secret/时间戳/签名不合法，仍拒绝。
- 无签名路径必须命中 `CustSpaceId` 对应的 `whatsapp_provider_scopes.external_scope_id` 和标准化业务号码；缺少 scope、号码或命中不唯一均拒绝。
- 删除完整正文日志，改为 payload hash、event id、trace id 和脱敏字段。
- 保留 1 MiB body 上限和已有幂等 `insertIgnore`。
- `fixedAccount` 改为 `resolveAccount(root)`：按状态回执优先使用 `From/businessNumber`，按入站消息优先使用 `To/businessNumber`，必要时检查另一侧号码；归一化后必须恰好命中一个 active ChatApp/WhatsApp 账号。
- 空号码、多个命中、未命中、号码与 scope 不一致都返回结构化拒绝，不回退到任意一个账号。
- webhook 接收不要求当前 owner 仍存在；收回账号后仍按稳定 `channel_account_id` 入库。
- URL 配置和账号 scope/号码路由是无签名 CAMS 合同下的安全边界。若未来 CAMS 官方启用 HMAC，可通过配置 secret 强制使用成对签名 Header；当前不能把不存在的 Header 写成必需合同。

## 前端设计

在现有管理员 CAMS 配置/WhatsApp 账号页面增加“回调配置”区域，不新建平行导航。

管理员看到：号码脱敏值、账号名称、provider/验证状态、入站回调 URL、状态回执 URL、HTTP/MNS 开关、最近写入状态、时间、错误码和版本。

交互规则：

- URL 输入使用 `https` 校验；开发 profile 的 localhost 例外由后端决定，前端不绕过服务端校验。
- 账户级区域只显示状态回执 URL，不渲染入站 URL 字段。
- 保存期间禁用当前行，防止重复提交；成功后以服务端 projection 覆盖本地表单。
- 409 版本冲突时重新加载该 scope 的配置，不自动覆盖其他管理员的修改。
- 失败时保留用户输入但不把失败值标记为“已生效”；页面显示“最近写入失败”。
- 普通销售不看到配置区域和任何 CAMS 回调 URL。

## 错误与观测

统一错误码：

- `WHATSAPP_CALLBACK_ADMIN_REQUIRED`
- `WHATSAPP_CALLBACK_SCOPE_NOT_FOUND`
- `WHATSAPP_CALLBACK_ACCOUNT_NOT_IN_SCOPE`
- `WHATSAPP_CALLBACK_URL_INVALID`
- `WHATSAPP_CALLBACK_LEVEL_FIELD_INVALID`
- `WHATSAPP_CALLBACK_VERSION_CONFLICT`
- `WHATSAPP_CALLBACK_CREDENTIALS_UNAVAILABLE`
- `WHATSAPP_CALLBACK_PROVIDER_FAILED`
- `WHATSAPP_CALLBACK_PROVIDER_TIMEOUT`
- `WHATSAPP_CALLBACK_WEBHOOK_SIGNATURE_INVALID`
- `WHATSAPP_CALLBACK_WEBHOOK_ACCOUNT_UNRESOLVED`

日志字段只包括 `traceId`、actor id、scope id、channel account id、level、action、elapsedMs、providerRequestId、errorCode、result。完整 URL、secret、原始 CAMS 响应和消息正文禁止写日志。

## 迁移与兼容

- 现有 CAMS scope 和 channel account 不重建。
- 迁移只创建索引和空配置表，不把环境变量或猜测值写成已应用配置。
- 如果已有部署通过环境变量保存 webhook 地址，首次读取时只作为“建议值”返回，管理员明确保存后才写入数据库并调用 CAMS。
- 旧的单账号 webhook 接收测试可以保留为隔离测试 fixture，但生产路径必须使用号码路由。
- `ChatAppWebhookVerifier` 必须覆盖两条已确认路径：无签名 CAMS payload 的 scope 路由，以及成对签名 Header 的严格 HMAC 验签；禁止以注释形式保留全局旁路。

## 验收标准

1. 管理员可查看 scope 下所有已同步号码的回调配置投影，普通销售不可见。
2. 管理员保存号码级配置时，服务端实际调用 `UpdatePhoneWebhook`，请求包含正确的 `CustSpaceId`、标准化 `PhoneNumber`、`UpCallbackUrl` 和 `StatusCallbackUrl`。
3. CAMS 成功后返回 `SUCCEEDED`；RequestId 仅保存在受限审计/结果字段，失败不覆盖旧成功配置，并留下审计。
4. 账户级配置只能写 `StatusCallbackUrl`，不能伪造入站消息配置。
5. 并发保存使用 version fencing，旧版本请求返回 409。
6. 真实无签名 CAMS payload 可被接收并按 `CustSpaceId + 号码` 路由；半签名、伪造签名或过期时间戳仍被拒绝。
7. 两个 active CAMS 号码都能按业务号码路由到各自的 `channel_account_id`，不存在单账号固定回退。
8. 回调配置和接收日志不泄露 AccessKey、Secret、完整号码或消息正文。
9. 前端构建、后端专项测试、OpenAPI/源代码合同测试和真实 CAMS 测试号码验收全部有记录。

## 剩余风险

- 当前 SDK 已确认写入 API，但没有确认官方回读 API；因此“保存成功”只能证明更新请求成功，不能证明 provider 端最终投递成功。
- CAMS 回调字段命名和 URL 限制仍需用专用测试号码持续验证；当前实机证据已确认请求为 JSON 数组且无 `X-CAMS-*` 签名 Header，本地 fixture 不能替代后续 provider 变更监测。
- 当前工作区存在用户未提交改动；实施时只能触碰计划列出的文件，不能清理或覆盖其他 WIP。
