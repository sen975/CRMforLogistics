# CAMS WhatsApp 内嵌注册员工自助绑定设计

## 状态（历史）

本文已被
`2026-09-10-whatsapp-admin-managed-account-assignment-design.md`
取代，仅保留历史实现和公开合同记录，不再定义当前产品行为。

当前路线是管理员在 CAMS 手工配置 WABA/电话号码，CRM 只读同步并由管理员分配、收回或转交销售账号。

## 目标

让管理员完成企业级 WhatsApp Business API/WABA 绑定，让已登录 CRM 的员工在该 API 账号下绑定自己的新电话号码，或自行绑定自己的 WhatsApp Business App 账号。CRM 使用阿里云 CAMS 的公开接口和 Meta 官方 Embedded Signup 完成真实授权，不调用 CAMS 控制台私有接口，也不把阿里云凭据、Meta token、验证码或授权结论交给浏览器。

支持两种明确不同的发送账号类型；企业 API 的管理员绑定是建立企业基础连接的管理流程，不是第三种员工账号类型：

- `BUSINESS_APP_COEXISTENCE`：员工已有 WhatsApp Business 手机 App 号码，通过 Meta 的 Business App 共存授权接入。该路线不是号码迁移，禁止调用 `CreateChatappMigrationInitiate`、`ChatappMigrationVerified` 和 `ChatappMigrationRegister`。
- `API_ONLY`：员工使用 API 发送账号。先完成 WABA 授权/绑定；若需要新增实体电话号码，则在 WABA 绑定完成后走 CAMS 的添加号码、发送验证码、注册号码公开 API。CAMS 不购买或生成运营商电话号码，号码验证仍是 WhatsApp 的必要条件。

企业 API WABA 只能由管理员绑定或更换；普通员工不能创建、绑定或更换企业 API WABA。员工可以在已绑定的企业 API WABA 下添加自己控制的新电话号码，也可以走独立的 Business App 共存 Embedded Signup 绑定自己的 WhatsApp Business 账号。每位员工默认最多一个活跃 WhatsApp 发送账号；每个活跃号码全局只归属一名员工。解绑只删除 CRM 与该账号的关系，不删除 CAMS、WABA、号码、模板或 WhatsApp Business App 侧资源。

## 已确认的公开合同

以下为 2026-09-09 从阿里云 OpenAPI 门户读取的 CAMS `2020-06-06` 正式合同。接口存在不等于当前阿里云账号已获得权限，真实账号验收仍是发布门禁。

- `IsvGetAppId(Type=whatsapp, IntlVersion=2, Permissions?)`：返回 `AppId`、`ConfigId`。这是 CRM 启动 Meta Embedded Signup 的唯一配置来源，不从环境变量或 CAMS 控制台页面复制固定值。
- `GetPermissionByCode(Code, CustSpaceId?, Permissions?)`：校验 Meta 嵌入式授权产生的 `code`，响应仅表达 CAMS 权限校验结果，不返回 WABA 或号码。
- `ChatappBindWaba(WabaId)`：绑定 WABA，响应返回 `CustSpaceId` 和 `WabaId`。
- `ChatappSyncPhoneNumber(CustSpaceId)`：从 CAMS 同步号码和状态。
- `QueryChatappPhoneNumbers(CustSpaceId)`：查询 CAMS 已接入号码。
- `GetPreValidatePhoneId(PhoneNumber, VerifyCode)`：仅将预校验号码转换为号码 ID，不是 Embedded Signup 的启动或完成接口。
- `AddChatappPhoneNumber`、`GetChatappVerifyCode`、`ChatappVerifyAndRegister`：仅用于 `API_ONLY` 的新增号码注册，不得用于 Business App 共存。

官方入口：

- `https://api.aliyun.com/api/CAMS/2020-06-06/IsvGetAppId`
- `https://api.aliyun.com/api/CAMS/2020-06-06/GetPermissionByCode`
- `https://api.aliyun.com/api/CAMS/2020-06-06/ChatappBindWaba`
- `https://api.aliyun.com/api/CAMS/2020-06-06/ChatappSyncPhoneNumber`

Meta 的浏览器完成事件是 CAMS 公开接口之外的前端合同。当前 CAMS 控制台实证显示其通过 Meta 内嵌注册打开页面；共存模式带有 `whatsapp_business_app_onboarding` 特征。CRM 必须以实际测试得到的 Meta SDK 事件字段、`extras` 参数、允许来源和浏览器限制作为实现真源，不能按控制台私有 URL 或猜测字段硬编码。

## 唯一 Owner 与数据模型

### 授权编排

`WhatsAppAuthorizationService` 是授权状态机唯一 owner。Controller 只取得当前登录用户并映射 HTTP；React 页面只渲染后端返回的启动投影、转发受限 Meta 事件和展示状态。任何一层 UI 都不能自行判定“授权完成”或创建渠道账号。

授权 attempt 必须至少保存：

- `id`、`user_id`、`state_hash`、状态、创建/过期/消费时间；
- `onboarding_mode`、员工填写的账号名称和备注；
- 完成后经后端验证的 `waba_id`、`phone_number_id`、选择的标准化号码；
- 无原始授权 `code`、Meta token、AccessKey、短信/语音验证码。

状态只能沿以下方向变化：`PENDING -> META_COMPLETED -> PROVIDER_SYNCED -> COMPLETED`，或从任何未终态进入 `CANCELLED`、`EXPIRED`、`FAILED`。完成和失败均记录结构化阶段、CAMS 请求 ID 与脱敏错误码。

### Provider scope

`whatsapp_provider_scopes` 的业务身份为 `(provider, waba_id)`，并区分 scope 用途：

- `ENTERPRISE_API`：企业主 API scope，只能由管理员通过 API/WABA Embedded Signup 建立或更换；员工的 API-only 新号码只能挂在这个 scope 下。
- `EMPLOYEE_BUSINESS_APP`：员工自己的 Business App 共存 scope，由该员工本人完成授权建立或复用；不改变企业 API scope，也不能被其他员工使用。

每个成功绑定的 WABA 建立或复用一个 `ALIYUN_CAMS` scope，保存 CAMS 返回的 `cust_space_id` 和仅后端可读的加密 CAMS 凭据引用。不能再用一个全局 scope 代表所有员工，也不能让员工把自己的 Business App WABA 变成企业 API scope。

不同员工可以绑定不同 WABA。相同 WABA 可以拥有多个已同步号码，但不能通过“当前企业仅一个 WABA”的旧限制阻断。所有发送、模板与同步代码以 `channel_account.provider_scope_id` 找到该号码所属 scope。

### CRM 渠道账号

`channel_accounts` 是员工可用发送身份的唯一记录，保存 owner、名称、备注、标准化号码、模式、scope、provider 号码状态和验证状态。CAMS 密钥不得复制到该表。

数据库必须用部分唯一约束保证同一个标准化号码至多一条活跃 WhatsApp 账号；同一 owner 的活跃 WhatsApp 账号数也必须由事务内查询和条件更新保护。历史解绑记录可保留审计，但不参与活跃唯一性判断。

### 模板归属

模板服务根据 `channel_account.onboarding_mode` 选择唯一的模板权限域：

- `API_ONLY` 账号的模板归属于 `ENTERPRISE_API` scope，是企业 API 下所有员工号码可共享的资产。普通员工新建模板可直接提交 WhatsApp 官方审核；已有模板的修改、发送权限调整、停用和删除必须创建管理员审批申请。
- `BUSINESS_APP_COEXISTENCE` 账号的模板归属于该员工的 `EMPLOYEE_BUSINESS_APP` scope 和 `channel_account_id`，是账号私有资产。该员工可以直接提交官方申请、修改、停用和删除，不产生内部审批申请；其他员工和企业 API 账号不能读取、申请修改或使用这些模板。

模板业务身份必须包含权限域：

```text
template_domain + provider_scope_id + channel_account_id? + provider_template_id + language_code
```

其中 `channel_account_id` 仅对 `EMPLOYEE_BUSINESS_APP` 必填；`ENTERPRISE_API` 模板不绑定某一个员工号码。模板同步、发送和缓存必须先校验当前账号属于该权限域，不能因为两个账号的官方模板名称或模板 ID 相同就跨域合并。

## 自助绑定流程

### 管理员绑定企业 API WABA

```text
管理员登录
  -> 选择“绑定企业 WhatsApp Business API”
  -> CRM 创建 ADMIN_API_WABA attempt
  -> CRM 调用 IsvGetAppId
  -> 浏览器启动 API/WABA Embedded Signup
  -> 后端校验 code 和管理员 attempt
  -> ChatappBindWaba + ChatappSyncPhoneNumber
  -> 建立或更新 ENTERPRISE_API scope
```

该流程只创建/更新企业 API scope，不直接创建某个员工的号码账号。更换企业 WABA 必须是显式管理员操作，并检查旧 scope 下是否仍有活跃号码；有活跃号码时拒绝更换，避免发送路由和模板归属被静默改变。

### 员工共同启动步骤

```text
员工选择“企业 API 下绑定新号码”或“绑定我的 Business App”并填写账号名称和备注
  -> CRM 创建一次性 employee attempt，绑定当前用户、目标 scope 类型和 state
  -> CRM 调用 IsvGetAppId
  -> CRM 返回 attemptId、state、AppId、ConfigId、受限启动 profile
  -> 浏览器用 Meta 官方 SDK 打开内嵌注册弹窗
```

账号名称和备注在打开 Meta 页面前被保存到 attempt；Meta 成功前不创建 `channel_account`。`AppId` 与 `ConfigId` 可交给浏览器，阿里云 AccessKey、CAMS endpoint、scope 配置和服务端错误详情不可交给浏览器。

启动 profile 是后端枚举，不接受浏览器传入的 `feature`、`permissions` 或 `extras`：

- `ADMIN_API_WABA` 只能由管理员使用，采用 API/WABA profile。
- `EMPLOYEE_BUSINESS_APP` 只能由员工使用，采用实机验证过的 Business App 共存 profile。
- `EMPLOYEE_API_PHONE` 不启动新的 WABA 授权；它要求企业 API scope 已存在，之后使用 CAMS 添加号码合同。

若当前 CAMS 凭据无法取得 profile，或某一模式尚未通过实机验证，后端返回明确的 `WHATSAPP_ONBOARDING_MODE_UNAVAILABLE`，页面不打开弹窗。

### 员工绑定自己的 Business App

```text
Meta 共存授权完成事件
  -> 浏览器校验允许来源和事件信封，提交 attemptId/state/code/wabaId/phoneNumberId
  -> 后端校验 state、登录用户、attempt 状态和 EMPLOYEE_BUSINESS_APP mode
  -> GetPermissionByCode 校验 code
  -> ChatappBindWaba 获得或确认该员工的 EMPLOYEE_BUSINESS_APP scope
  -> ChatappSyncPhoneNumber + QueryChatappPhoneNumbers 获取真实号码
  -> 单一候选号码自动选择；多个候选号码则返回受限选择列表
  -> 后端校验该号码仍属于此 scope、未被占用且状态可用
  -> 原子创建 channel_account，消费 attempt
```

浏览器不传手机号、显示名称、provider 状态或 `history_sync` 作为可信输入。若 Meta 事件不含可用于 CAMS 查询的完整号码，后端只使用新绑定 scope 的同步结果；多个可用候选号码时要求员工选择掩码号码，随后由后端在新鲜同步结果中复核。无法确定唯一号码时失败，不猜测 `phone_number_id` 与号码的映射。

共存成功后的历史同步是单独、可观测、幂等的异步能力。它不影响号码绑定结果，也不以“同步历史成功”作为发送可用的前置条件。

该账号完成绑定后，员工自动获得其 `EMPLOYEE_BUSINESS_APP` 私有模板空间。模板相关请求必须携带当前登录员工的账号上下文，由服务端从账号记录推导模板空间；员工不能通过请求体指定其他账号、企业 API scope 或模板权限域。

### 员工在企业 API 下绑定新电话号码

```text
员工登录
  -> 后端确认 ENTERPRISE_API scope 已由管理员绑定
  -> 员工填写自己控制的新电话号码、账号名称和备注
  -> AddChatappPhoneNumber(enterprise custSpaceId)
      -> 员工显式确认发送验证码
      -> GetChatappVerifyCode
      -> 员工输入验证码
      -> ChatappVerifyAndRegister
      -> 同步、查询、创建 CRM 账号
```

管理员 API/WABA 绑定、员工企业 API 新号码、员工 Business App 共存共享 attempt、安全校验、WABA scope、号码归属、同步、落库和解绑代码；三者只在权限、目标 scope 和 provider 步骤上不同。禁止让共存号码进入 `AddChatappPhoneNumber`、验证码、普通迁移或迁移验证码流程。

## 模板权限分流

### 企业 API 下的员工号码

企业 API scope 下的号码使用系统级共享模板目录。员工可直接提交新模板到 WhatsApp 官方审核，但对已有模板的内容修改、发送权限、停用和删除只产生内部审批申请，由管理员批准后使用申请账号的企业 API 凭证执行。模板不会因为号码属于某个员工而变成该员工私有资产。

### 独立 Business App 账号

员工独立绑定的 Business App 账号拥有自己的模板目录。该账号的模板申请、修改、发送权限、停用和删除都由账号 owner 直接调用上游执行，不经过管理员审批；每次操作仍须经过当前用户 owner 校验、版本校验、幂等、上游状态刷新和审计。

私有模板操作使用该 Business App 账号所属 scope 的凭证，不借用企业 API 账号或其他员工账号凭证。解绑员工账号时只解除 CRM 关系，模板和 CAMS 侧账号保留；重新绑定同一账号后必须通过新的同步建立模板投影，不能凭旧的 CRM 账号 ID 越权恢复。

## 完成接口与安全边界

完成接口只接受 `attemptId`、`state`、Meta `code`、`wabaId`、`phoneNumberId` 和受限事件类型。服务端按以下顺序执行：

1. 锁定 attempt，校验当前用户、短时有效、一次性状态和模式。
2. 对 `code` 调用 `GetPermissionByCode`，失败立即终止，不绑定 WABA。
3. 若 attempt 为 `ADMIN_API_WABA`，仅管理员可调用 `ChatappBindWaba` 并建立/更新 `ENTERPRISE_API` scope；若为 `EMPLOYEE_BUSINESS_APP`，建立/读取该员工的 `EMPLOYEE_BUSINESS_APP` scope；员工不能写入 `ENTERPRISE_API` scope。
4. 同步并查询号码。用户的号码选择仅是候选标识，最终身份来自当前 scope 的 CAMS 查询结果。
5. 在一个本地事务中写 scope、渠道账号、审计和 attempt 消费状态。

完成接口必须幂等：同一个已完成 attempt 返回原账号投影；重放不同结果、跨用户提交、过期提交和已解绑账号的旧 attempt 全部拒绝。原始 `code` 只存在于单次请求内，不入库、不写日志。

前端仅监听经过允许来源白名单校验的 Meta SDK 事件。它不信任 CAMS 控制台 origin，也不使用控制台 `channel_url`、`fallback_redirect_uri`、cookie 或私有请求作为 CRM 合同。

## 查看与解绑

- `GET /api/whatsapp/accounts/me`：仅返回当前员工的账号、绑定状态、模式、名称、备注、脱敏号码、模板权限域及可恢复错误状态。
- `DELETE /api/whatsapp/accounts/{accountId}`：只允许 owner 对自己的活跃账号调用。服务端仅解除 CRM 归属/本地可用状态，保留 scope、CAMS 账号和 WhatsApp 资源。员工不能解绑企业 API scope；管理员更换/解绑企业 API scope 是独立的高风险管理操作。
- 解绑操作写入 owner、账号、执行时间与原因的审计记录，不记录密钥或 token。
- 管理员只参与企业 API/WABA 的绑定、更换和诊断；管理员不能代替员工创建个人 Business App 归属。员工账号生命周期仍按当前登录用户严格隔离。

## 明确禁止

- 禁止用 CAMS 控制台的私有 URL、`channel_url`、内部接口、页面 cookie 或反向代理伪造 CRM 内嵌注册。
- 禁止把 `CreateChatappMigrationInitiate` 及其验证码流程作为 Business App 共存实现。
- 禁止在绑定前要求已有 enterprise scope，或用一个全局 scope 拒绝不同 WABA。
- 禁止普通员工绑定或更换 `ENTERPRISE_API` WABA；普通员工只能在该 scope 下添加自己的新号码，或绑定自己的 `EMPLOYEE_BUSINESS_APP` 账号。
- 禁止把 `EMPLOYEE_BUSINESS_APP` 的私有模板并入企业 API 共享模板；私有模板的申请、修改、停用和删除不得强制经过管理员审批。
- 禁止把前端提交的电话号码、状态、显示名称或“FINISH”结论直接写入渠道账号。
- 禁止解绑时清除 scope 凭据、注销 CAMS 号码或影响 WhatsApp Business App。
- 禁止允许浏览器提交 `accessKeyId`、`accessKeySecret`、CAMS `custSpaceId`、Meta token 或任意启动 profile。

## 实机验收门禁

代码实现前后都必须运行以下真实环境验证，并保存脱敏结果、接口名、阶段、请求 ID、时间、模式和结果码：

1. 使用当前 CAMS 凭据调用 `IsvGetAppId(Type=whatsapp, IntlVersion=2)`，确认能取得 `AppId` 与 `ConfigId`。
2. 由管理员在 CRM 已登记的 HTTPS 域名上启动 `ADMIN_API_WABA` 弹窗；由员工启动 `EMPLOYEE_BUSINESS_APP` 弹窗，记录 Meta 的实际成功、取消、错误事件信封与允许来源。`EMPLOYEE_API_PHONE` 不启动 WABA 弹窗。
3. 对成功事件的 `code` 调用 `GetPermissionByCode`，确认权限校验结果。
4. 使用 `waba_id` 调用 `ChatappBindWaba`，确认获得 CAMS `cust_space_id`。
5. 调用 `ChatappSyncPhoneNumber` 与 `QueryChatappPhoneNumbers`，确认能找回授权号码和其真实可用状态。
6. 仅用专用测试号码执行员工企业 API 下的添加号码、验证码、注册和重试验证；禁止对生产 Business App 共存号码试调用迁移 API。
7. 使用管理员和两个不同员工测试权限：员工不能绑定企业 WABA；员工能在企业 API scope 下添加自己的号码；员工能绑定自己的 Business App WABA；员工不能使用其他员工的 scope 或号码；确认解绑后 CAMS 侧资源仍存在。

任一 Embedded Signup 模式无法完成第 1 至第 5 步时，该模式必须保持不可用，不能退回控制台私有跳转或伪造成功。第 6 步失败仅阻断员工在企业 API 下的新号码注册，不阻断已验证的 Business App 共存或管理员企业 API 绑定。

## 验收标准

- 员工填写账号名称和备注后，CRM 实际打开 Meta 官方内嵌注册页面，不跳转 CAMS 控制台。
- 管理员企业 API 绑定、员工企业 API 新号码、员工 Business App 共存使用独立后端 profile 和权限；共存不调用迁移或 API-only 的添加号码/验证码接口。
- 成功记录均经过 `code` 校验、WABA 绑定和 CAMS 号码同步，前端无法伪造账号。
- 每个账号按 `owner_user_id` 严格隔离；员工只能查看、完成和解绑自己的账号。
- 同一活跃号码不能同时绑定至两名员工；不同 WABA 的账号可以在 CRM 并存。
- 解绑仅改变 CRM 本地关系，CAMS/WhatsApp 资源保持不变。
- 所有失败路径返回结构化阶段和错误码，日志与数据库没有 AccessKey、token、验证码和完整手机号以外的非必要敏感数据。

## 非目标

- 不购买电话号码、不提供运营商开户或虚拟号码服务。
- 不实现 CAMS 控制台功能的抓取、嵌套、代理或私有 API 兼容。
- 不保证 Business App 历史消息同步；该能力以 CAMS 实机支持为准。
- 不将管理员代绑员工个人 Business App 或管理员分配员工个人号码作为员工账号的产品流程。
