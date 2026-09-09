# WhatsApp Business App 共存与企业多号码设计（已废止）

> 本文件已被 `2026-09-09-whatsapp-cams-embedded-signup-self-service-design.md` 取代，不得作为实现依据。当前权限模型是：管理员绑定企业 WhatsApp Business API/WABA；员工只能在企业 API 下绑定自己的新号码，或绑定自己的 Business App 账号。

## 目标

在一个企业 WABA 和一个阿里云 ChatApp `custSpaceId` 下，接入公司销售各自已有的 WhatsApp Business App 号码，并允许后续为新销售添加新的发送号码。每名销售在 CRM 内保持一个独立的 WhatsApp 账号，号码由本人完成授权，管理员负责查看、禁用和重新分配。

## 已确认的业务边界

- 当前系统服务一个实体供应商，不实现多个外部企业共用同一套 CRM 的 SaaS 多租户授权。
- 企业只维护一个 WhatsApp provider scope：`wabaId + custSpaceId`。
- 一个企业 scope 下允许多个 WhatsApp 发送号码。
- 每个销售可以拥有一个独立的 WhatsApp CRM 账号，对应一个发送号码。
- 现有销售号码优先走 WhatsApp Business App Coexistence Embedded Signup。
- 新号码可以走 Coexistence；如果只使用 CRM/API，也可以走阿里云号码添加、验证码和注册接口。
- 不让前端接触阿里云 AccessKey；企业级凭证只在后端加密保存。
- 不把“SDK 中存在方法”当作“当前阿里云账号已经开通能力”。真实账号能力探测通过前，不开发正式授权和号码管理 UI。

## 官方与本地证据

项目已依赖 `com.aliyun:alibabacloud-cams20200606:5.0.5`。本地 SDK 已包含以下接口：

- `ChatappBindWaba`
- `QueryChatappBindWaba`
- `ChatappSyncPhoneNumber`
- `QueryChatappPhoneNumbers`
- `AddChatappPhoneNumber`
- `GetChatappVerifyCode`
- `ChatappVerifyAndRegister`
- `ChatappPhoneNumberRegister`
- `ChatappPhoneNumberDeregister`
- `CreateChatappMigrationInitiate`
- `ChatappMigrationVerified`
- `ChatappMigrationRegister`

SDK 源码显示：`ChatappBindWaba` 必填 `WabaId`，返回 `CustSpaceId` 与 `WabaId`；`AddChatappPhoneNumber` 必填 `CustSpaceId`、区号、电话号码和显示名称；验证码和注册接口都以 `CustSpaceId + PhoneNumber` 为作用域。

阿里云官方嵌入式注册文档：

`https://help.aliyun.com/zh/chatapp/user-guide/implement-embedded-signup`

Meta Business App 用户接入文档：

`https://developers.facebook.com/docs/whatsapp/embedded-signup/custom-flows/onboarding-business-app-users`

用户已在真实环境验证 ChatApp 支持 Business App 共存；该事实作为当前项目的实机证据，正式验收仍需记录测试号码、scope、返回状态和请求 ID，不记录 AccessKey 或验证码。

## 核心模型

### 企业 provider scope

`whatsapp_provider_scopes` 是企业级 WABA 连接记录，至少表达：

- provider：`ALIYUN_CAMS`
- `external_scope_id`：阿里云 `custSpaceId`
- `waba_id`
- scope 状态
- 加密的企业级 CAMS 配置引用
- 最近一次能力探测结果

一个有效企业 scope 只能对应一个 `wabaId`。后续销售授权返回不同 WABA 时拒绝绑定，不自动创建第二个企业 scope。

### 销售号码账号

`channel_accounts` 继续表示 CRM 内可收发消息的账号，但 WhatsApp 账号必须引用企业 provider scope。号码账号补充以下业务状态：

- `phone_number`
- `verified_name`
- `onboarding_mode`：`BUSINESS_APP_COEXISTENCE` 或 `API_ONLY`
- provider 号码状态
- 验证状态
- 当前销售 owner

同一电话号码只能有一个有效 CRM 账号。账号解绑只解除 CRM 使用关系，不调用阿里云注销号码；注销号码必须使用显式管理员操作和独立的 `Deregister` 流程。

### 员工关系

第一阶段沿用 `channel_accounts.owner_user_id` 表达“号码当前归属销售”，避免同时引入不必要的分配表。管理员重新分配时必须写审计记录，并校验目标销售没有其他有效 WhatsApp 账号。

## 两条授权链路

### 已有 Business App 号码

```text
销售登录
  -> 后端创建一次性授权 state
  -> 前端启动 Meta/ChatApp Embedded Signup 共存流程
  -> 前端只回传授权结果和 wabaId/号码标识
  -> 后端校验 state、当前用户和企业 wabaId
  -> ChatappBindWaba（首次建立企业 scope）或校验已有 scope
  -> ChatappSyncPhoneNumber
  -> QueryChatappPhoneNumbers
  -> 匹配已授权号码
  -> 创建或恢复当前销售的 channel_account
```

授权结果中的 WABA 必须与企业 scope 一致。号码不在该 WABA、已被其他 CRM 账号占用或状态不允许收发时，流程失败且不创建本地账号。

### 新 API 号码

```text
取得可接收短信/语音的真实电话号码
  -> AddChatappPhoneNumber
  -> GetChatappVerifyCode（明确的用户动作）
  -> 输入验证码
  -> ChatappVerifyAndRegister
  -> QueryChatappPhoneNumbers
  -> 创建或恢复 channel_account
```

API 不生成或购买运营商电话号码。若新号码也要保留手机 Business App 使用，必须改走共存授权流程，而不是只走 API_ONLY 注册流程。

## API 可用性确认门禁

正式 UI 开发前必须生成一份真实账号能力报告，分为四级：

1. **SDK 门禁**：确认本地依赖、类、方法、必填字段和 endpoint 配置可编译。
2. **只读在线门禁**：使用真实企业凭证调用 `QueryChatappBindWaba`、`QueryChatappPhoneNumbers` 及适用的状态查询，记录成功/权限错误/参数错误/请求 ID。
3. **专用测试号码门禁**：使用不会影响生产销售的测试号码验证 `AddChatappPhoneNumber`、`GetChatappVerifyCode`、`ChatappVerifyAndRegister`、查询和注册状态。发送验证码和注册号码均属于外部副作用，必须由管理员在执行前确认。
4. **迁移门禁**：`CreateChatappMigrationInitiate`、`ChatappMigrationVerified`、`ChatappMigrationRegister` 不得对现有生产号码做试错调用。只有阿里云明确确认该号码类型和迁移条件，或提供专用可回滚测试号码后，才允许标记为可用；否则报告为“接口存在但生产迁移未验证”。

Embedded Signup 还必须单独验证：Meta App、Configuration ID、域名白名单、Business App 共存权限、浏览器 `message` 结果、后端 state 校验，以及返回的 WABA/号码是否能在 CAMS 查询到。

能力报告必须保存接口名称、探测阶段、结果码、请求 ID、时间和脱敏诊断；禁止保存 AccessKey、AccessKey Secret、验证码和完整授权 token。

## 既有代码需要调整的边界

- `WhatsAppProviderScopeService` 保留单一企业 `custSpaceId` 兼容校验，但 scope 的 owner 和凭证不再依附某一名销售号码。
- `ChannelAccountService` 取消“企业/全局只能有一个 ChatApp 账号”的错误假设，改为同一销售最多一个有效 WhatsApp 账号、同一号码全局唯一。
- ChatApp 消息发送、同步、模板和 webhook 继续以 `channel_account_id` 选择具体发送号码，并通过 `provider_scope_id` 获取企业级凭证。
- `ChannelSettingsPage` 改为显示企业 WhatsApp 连接状态和号码列表；销售看到自己的授权入口，管理员看到所有号码及分配操作。
- 现有手工输入 `accessKeyId`、`accessKeySecret`、`custSpaceId`、`chatappFrom` 的账号创建流程只作为迁移兼容入口，正式共存流程不再要求销售输入这些字段。

## 错误和安全

- 授权 state 一次性、短时有效并绑定当前用户；重复提交、跨用户提交和过期 state 均拒绝。
- 前端不调用 CAMS，不保存企业 AccessKey。
- WABA 不一致、号码不属于 scope、号码已被占用、验证码错误和 provider 状态异常分别返回结构化错误。
- 外部接口的 request ID、错误码和阶段写入诊断，不把密钥、验证码和 token 写日志。
- 添加、验证码、注册、迁移和注销都必须幂等；重试前查询已有状态，避免重复发送验证码或重复注册。

## 验收标准

- 真实账号能力报告完成后，明确列出每个接口“已验证可用”“权限未开通”或“生产迁移未验证”。
- 一个企业 scope 能绑定多个不同销售的 Business App 共存号码。
- 同一号码不能被两个销售绑定。
- 销售只能启动自己的授权，管理员可以查看、禁用和重新分配号码。
- 新 API 号码能在测试号码门禁中完成添加、验证码、注册和查询。
- 不同 WABA 不能加入当前企业 scope。
- 消息发送使用被分配销售对应的发送号码，模板和 webhook 不串号。
- 全部失败路径保留结构化阶段、错误码和 provider request ID。

## 非目标

- 本设计不做多外部企业 SaaS 租户隔离。
- 本设计不实现电话号码购买、运营商开户或虚拟号码供应商集成。
- 本设计不承诺迁移后保留手机端历史聊天记录；历史消息保留和同步以阿里云/Meta 共存能力的实测结果为准。
- 本设计不在未通过真实账号 API 门禁前发布授权 UI。
