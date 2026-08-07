# ChatApp 号码注册与销售绑定设计

## 1. 目标与边界

### 目标

在 Spring 版消息中心中，为单个企业提供以下能力：

- 每名销售拥有独立的系统账号，并绑定企业微信 `userId`。
- 一个 ChatApp 通道使用一个 `CustSpaceId` 和一个 WhatsApp Business Account（WABA）。
- 同一 WABA 下可以注册多个 WhatsApp 发送号码。
- 每个销售固定绑定一个发送号码；一个号码同一时间只绑定一个销售。
- 销售提交号码申请，平台管理员完成 CAMS 验证、注册、审核和分配。
- 发送、收件、同步和会话权限都以当前销售的号码绑定为准，不需要改配置或重启。

### 非目标

- 不为每个销售创建独立 WABA。
- 不做多租户企业模型；当前范围只有一个企业。
- 不让销售直接调用 CAMS 注册接口。
- 不把 CAMS 的号码注册误建模为企业微信员工账号注册。
- 不允许共享登录账号。

## 2. 已确认的外部合同

CAMS 的层级是 `CustSpaceId -> WABA -> 多个发送号码`。官方接口中：

- `QueryChatappBindWaba` 查询客户绑定的 WABA。
- `QueryChatappPhoneNumbers` 查询客户下的所有发送号码及号码状态。
- `GetChatappVerifyCode`、`ChatappVerifyAndRegister`、`GetPhoneNumberVerificationStatus` 均以 `CustSpaceId + PhoneNumber` 为操作对象。

这些接口管理号码，不管理企业微信员工账号。员工身份由本系统的企业微信登录合同返回的 `corpId + userId` 管理。

## 3. Owner 与数据模型

### 3.1 员工账号

沿用 `users` 作为系统账号，一名销售一条账号记录；企业微信登录成功后以 `wecom_user_id` 绑定该销售。登录身份唯一性为企业微信 `userId`，禁止多人共用账号。

平台管理员也是本企业中的独立账号，通过角色授权拥有号码治理权限。

### 3.2 ChatApp 通道

`CustSpaceId`、WABA 和 CAMS 凭据是单例通道配置。MVP 可继续由应用级配置提供，不复制到每个号码；后续如需热更新凭据，再抽取单例 `ChatAppIntegration` 配置表。

### 3.3 发送号码

复用 `channel_accounts`，每条 ChatApp 记录表示 WABA 下的一个发送号码：

- `channel_type = 'chatapp'`
- `account_identifier`：规范化后的 WhatsApp 号码
- `auth_status`：provider 认证状态
- `encrypted_config`：仅保存该号码需要的 provider 元数据，不保存重复的全局 WABA 凭据
- `deleted_at`：软删除

新增 `chatapp_number_requests`，记录销售申请和平台管理员操作：

- `id`
- `requested_by_user_id`
- `phone_number`
- `status`
- `provider_request_id`
- `provider_code`
- `provider_message`
- `expires_at`
- `attempt_count`
- `reviewed_by_user_id`
- `reviewed_at`
- `created_at` / `updated_at`

新增 `channel_account_user_bindings`，记录号码与销售的当前绑定及历史：

- `channel_account_id`
- `user_id`
- `status`：`ACTIVE` / `RELEASED`
- `assigned_by_user_id`
- `assigned_at`
- `released_by_user_id`
- `released_at`
- `reason`

数据库约束：

- 一个号码最多一个 active 绑定。
- 一个销售最多一个 active ChatApp 号码。
- 号码申请中的同一号码不能并发注册。

### 3.4 会话与消息

继续使用现有 `conversations.channel_account_id` 作为号码归属，`assigned_user_id` 作为销售归属，`messages.created_by_user_id` 记录真实发送人。历史消息不因号码重新绑定而改写。

## 4. 申请、注册与分配流程

```text
销售提交号码申请
  -> 平台管理员审核申请
  -> GetChatappVerifyCode
  -> ChatappVerifyAndRegister
  -> GetPhoneNumberVerificationStatus
  -> 创建 channel_accounts
  -> 平台管理员绑定给指定销售
  -> 销售可发送
```

申请状态：

```text
REQUESTED -> CODE_SENT -> PROVIDER_REGISTERED -> PENDING_BINDING -> BOUND
```

失败或终止状态：

```text
REJECTED / FAILED / EXPIRED / DISABLED / RELEASED
```

验证码不落库，只保存短期申请会话的过期时间、重试次数和 provider request id。验证失败、过期、重复提交和 provider 限流都返回结构化错误，并保留可审计状态。

## 5. HTTP 合同

销售端：

```text
POST /api/chatapp/number-requests
GET  /api/chatapp/number-requests
```

平台管理员端：

```text
GET  /api/admin/chatapp/number-requests
POST /api/admin/chatapp/number-requests/{id}/send-code
POST /api/admin/chatapp/number-requests/{id}/verify
GET  /api/admin/chatapp/number-requests/{id}/status
POST /api/admin/chatapp/number-requests/{id}/bind
POST /api/admin/chatapp/channel-accounts/{id}/disable
POST /api/admin/chatapp/channel-accounts/{id}/reassign
```

现有 ChatApp 发送接口保持路径兼容，但请求体不接受 `from` 或 `channelAccountId`。后端按当前登录用户解析：

```text
当前用户 -> active channel_account_user_bindings -> channel_accounts.account_identifier -> CAMS From
```

## 6. 收发与可见性

入站消息通过 CAMS 业务号码找到 `channel_account_id`，再找到当前绑定销售。新客户消息进入该销售的未分配队列，或由平台管理员分配。

普通销售只能查看和发送自己被分配的会话；平台管理员可以查看全部会话。若发生号码释放或重分配，旧会话保留历史，新的发送权限从新的 active 绑定开始生效。

同一客户和同一发送号码仍然只有一条底层会话，禁止通过权限过滤制造互相冲突的平行会话。

同步调度器必须遍历所有 active ChatApp `channel_accounts`，每个号码使用独立游标、同步状态和 provider 业务号码；不得继续使用 `limit 1` 选择号码。

## 7. 安全、并发与错误

- 号码注册、验证码发送和绑定操作只允许平台管理员执行 provider 动作。
- 申请接口校验当前用户身份，禁止前端提交任意销售或号码 owner 覆盖服务端权限。
- 验证码、AccessKey Secret、WABA 凭据和请求签名不进入日志。
- provider 错误统一保存 `provider_code`、`provider_message`、`request_id`，不返回敏感请求内容。
- 同一号码注册使用数据库唯一约束和短期锁，验证码有冷却时间、重试上限和过期时间。
- 发送前再次校验用户 active binding 和会话权限；绑定被释放后立即拒绝发送。
- 当前开发配置中的明文阿里云凭据必须先撤出并轮换，之后才开放注册接口。

## 8. 验收与测试门禁

### 数据与权限

- 一个销售不能拥有两个 active ChatApp 号码。
- 一个号码不能绑定两个销售。
- 销售 A 不能读取或发送销售 B 的会话。
- 平台管理员可以查看、审核、禁用和重新分配全部号码。
- 重分配不修改旧消息的 `channel_account_id` 和发送人审计。

### CAMS 合同

- 验证码、校验注册、状态查询的 SDK 请求字段正确映射 `CustSpaceId` 和号码。
- provider 成功、失败、限流、过期和重复提交都有稳定状态映射。
- provider request id 和结构化错误可追踪，但不泄漏凭据。

### 运行时

- 两个 active 号码可以并行同步，游标和状态互不污染。
- 发送服务始终使用当前销售的绑定号码，不能被请求体覆盖。
- 未绑定或 disabled 号码不能发送。
- 入站消息按业务号码正确归属到号码和销售。

## 9. 当前实现缺口

- `ChatAppSendService` 仍从 `app.chatapp-from` 读取固定号码。
- 消息同步、模板同步和调度器仍用 `limit 1` 取 ChatApp 账号。
- 手动按账号同步接口没有把账号 ID传入同步服务。
- `ThreadService` 按渠道类型 `selectOne`，不支持多号码会话。
- 当前 Spring 版认证仍是本地用户名流程，尚未落地企业微信 `corpId + userId` 员工身份映射。
- `channel_accounts.auth_status` 当前不覆盖完整的注册/审核语义，应通过新增字段或申请/绑定表分离状态。

本设计不包含以上实现缺口的代码修改；下一步应先据此编写实施计划，再按 owner、数据库合同、服务端权限、同步和前端管理页面分阶段实现。
