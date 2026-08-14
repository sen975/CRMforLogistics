# 企业微信 Spring 完整迁移设计

**日期：** 2026-08-14

**状态：** 已确认，待实施

**适用范围：** `demo/message-center-spring`

**交互真源：** `2026-08-10-wecom-inline-expandable-message-card-design.md`
**迁移来源：** `demo/message-center-demo`

## 1. 背景与问题

Spring 版已经包含授权回调、文本发送、chatdata、viewer、公钥注册、每日摘要和审计的部分核心类，但功能尚未形成可用闭环：viewer 没有 HTTP adapter 和 React 消费方，chatdata 没有进入统一消息时间线，授权安装缺少事件幂等和版本递增，关键凭据仍以明文保存。

本设计完成 Spring 原生迁移。Spring 是唯一产品运行面；不得把旧 demo 作为旁路服务、反向代理子系统或第二数据真源。

## 2. 目标与非目标

### 2.1 目标

- 登录页同时支持账号密码登录和企业微信扫码登录，两者为并列入口。
- 未绑定的企业微信成员首次扫码时自动创建 Spring 用户，并赋予现有 `agent` 角色。
- 已有 Spring 用户可以在登录后扫码绑定企业微信身份，绑定后可直接扫码登录。
- 补齐 viewer、JS-SDK、chatdata 同步、viewer session 和客户端事件 HTTP API。
- chatdata 同步后进入统一联系人、会话和消息时间线，并由官方 OpenDataFrame 展示原文。
- 将 demo 已确认的企业微信连续消息段和纯企业微信整窗展示交互迁移到 React。
- 授权回调采用有界队列、数据库 required audit、事件幂等、版本递增和启动对账。
- 加密保存 permanent code 和 chatdata secret key，并完成已有明文数据的一次性迁移。
- 授权成功后立即触发公钥注册，同时保留定时补偿。
- 清除 tracked 配置中的真实凭据，只保留环境变量合同。

### 2.2 非目标

- 本轮不重新设计角色、权限矩阵或用户管理系统。
- 本轮不自动授予 `supervisor`、`admin`、`owner` 或 `broadcast_sender`。
- 本轮不增加每日摘要查询 API 或摘要 UI；现有生成和入库链路保持不变。
- 本轮不解析、复制或持久化企业微信消息原文。
- 本轮不支持一个 Spring 用户绑定多个企业微信身份，也不支持一个企业微信身份绑定多个 Spring 用户。
- 本轮不引入 Redis、消息队列中间件或第二个企业微信服务进程。

## 3. 方案选择

选择 Spring 原生完整迁移：Spring MVC 拥有 HTTP adapter，Spring Security 和 `user_sessions` 拥有 CRM 登录状态，PostgreSQL 拥有授权、绑定、审计、chatdata 和统一消息投影，React 拥有页面交互。

不选择以下方案：

- 保留 demo 作为企业微信旁路：会形成双登录、双数据、双审计和双部署真源。
- 只暴露现有 Spring viewer Service：chatdata 仍不会进入联系人时间线，凭据和授权状态仍不可靠。
- 前端直接调用企业微信上游：会泄露凭据，并绕过服务端身份、权限和审计边界。

## 4. 唯一 Owner

| 概念 | 唯一 owner | 禁止成为 owner 的层 |
|------|------------|----------------------|
| CRM 登录会话 | `AuthSessionService` + `user_sessions` | React、viewer token |
| 企业微信身份绑定 | `WeComUserBindingService` + `wecom_user_bindings` | 用户名约定、localStorage |
| 企业微信登录尝试 | `WeComLoginAttemptService` | Controller、React |
| 授权安装与版本 | `WeComInstallationService` + `wecom_installations` | Gateway、scheduler |
| 授权事件状态机 | `WeComAuthorizationService` + `wecom_authorization_audit` | Controller 日志 |
| chatdata 最小引用 | `WeComChatDataStore` | 通用 `messages` 正文 |
| 统一消息投影 | `WeComMessageProjector` | React、OpenDataFrame |
| viewer token/session | `WeComViewerService` | Spring CRM session |
| OpenDataFrame 生命周期 | React 企业微信时间线组件 | 后端、通用消息气泡 |
| 凭据加密 | 现有 `CredentialCipher` | Entity、Mapper、YAML |

## 5. 登录与绑定合同

### 5.1 数据模型

新增 `wecom_user_bindings`：

- `id uuid` 主键。
- `user_id uuid` 外键，唯一且非空。
- `suite_id varchar(128)`、`auth_corp_id varchar(128)`、`wecom_user_id varchar(128)`。
- `(suite_id, auth_corp_id, wecom_user_id)` 唯一。
- `bound_at`、`last_login_at`、`created_at`、`updated_at`。
- `version bigint`，每次变更递增。

绑定是一对一合同。重复交换同一登录 attempt 必须幂等；不同用户尝试占用已有企业微信身份返回 `WECOM_IDENTITY_ALREADY_BOUND`。已有用户已经绑定其他身份时返回 `WECOM_USER_ALREADY_BOUND`，不得静默改绑。

### 5.2 可选登录

登录页保留账号密码表单，并增加“企业微信登录”入口。企业微信扫码区域必须使用企业微信官方登录组件及其官方样式、尺寸、二维码、状态和交互，不得自行绘制二维码、仿制企业微信品牌样式或用普通 Ant Design 表单伪装官方登录组件。Spring 页面只负责在现有登录布局中组织“账号密码”和“企业微信登录”两个清晰入口。扫码流程：

1. 前端调用 `POST /api/auth/wecom/attempts` 创建 `purpose=LOGIN` 的一次性 attempt。
2. 企业微信回调 code 与 state 发送到 `POST /api/auth/wecom/exchange`。
3. 服务端消费 state、换取 `corpId + userId`，并校验企业与有效授权安装。
4. 已绑定身份复用现有 Spring 用户；未绑定身份在同一数据库事务内创建用户、分配 `agent` 角色并创建绑定。
5. 服务端通过 `AuthSessionService.issue` 签发标准 CRM token，并返回与密码登录相同的 `LoginResponse`。

自动创建用户名使用稳定、不可猜测冲突的服务端格式 `wecom_<sha256前24位>`，摘要输入包含 suite、corp 和 user ID。`display_name` 优先使用企业微信可验证成员资料；拿不到时使用有界 user ID。自动创建用户写入不可登录的随机 Argon2 密码哈希，禁止生成默认密码或把扫码账号伪装成可用密码账号。

### 5.3 已有账号扫码绑定

已认证用户调用 `POST /api/account/wecom-binding/attempts` 创建 `purpose=BIND` attempt。exchange 必须携带当前 CRM token，服务端把 state 绑定到当前 `user_id`；请求体不得传入目标用户 ID。绑定成功后返回绑定摘要，不替换当前 CRM session。

账号设置提供绑定状态、发起扫码和解除绑定入口。解除绑定要求当前 CRM session，且只删除当前用户的绑定；解除后密码登录仍可用，企业微信登录不可用。自动创建且没有可用密码登录方式的用户不得直接解除唯一绑定，必须先建立其他登录方式；本轮没有密码设置功能，因此这类账号的解除操作返回 `WECOM_LAST_LOGIN_METHOD`。

## 6. HTTP 与安全边界

Spring 新增以下入口，并保留当前 `/api/wecom/callback`：

- `GET/POST /api/v1/wecom/authorization/callback`
- `GET/POST /hook_path`
- `POST /api/auth/wecom/attempts`
- `POST /api/auth/wecom/exchange`
- `GET /api/account/wecom-binding`
- `POST /api/account/wecom-binding/attempts`
- `POST /api/account/wecom-binding/exchange`
- `DELETE /api/account/wecom-binding`
- `POST /api/v1/wecom/conversation-view/bootstrap`
- `GET /api/v1/wecom/js-sdk-config`
- `POST /api/v1/wecom/conversation-view/sync`
- `POST /api/v1/wecom/conversation-view/sessions`
- `GET /api/v1/wecom/conversation-view/sessions/{viewerSessionId}`
- `POST /api/v1/wecom/conversation-view/events`

授权 callback 和企业微信登录 attempt/exchange 为公开入口，但受 state 一次性消费、TTL、容量、来源企业和频率限制保护。账号绑定、联系人、线程和 viewer API 要求有效 CRM token。viewer session 还必须从当前 CRM 用户的企业微信绑定解析 actor，禁止相信浏览器提交的 wecom user ID。

登录 exchange 成功时可同时返回短时 viewer token，供当前页面立即展示企业微信消息。密码登录用户已绑定企业微信时，可通过受保护的 viewer bootstrap 取得对应 viewer token；未绑定时页面显示绑定动作，不显示空白 OpenDataFrame。

## 7. 授权安装、审计与并发

Spring 使用 PostgreSQL 事务实现 demo 文件协议的等价语义，不复制 `BoundedAuditFile`：

- callback 解码后先 required 写入 `accepted`。
- `create_auth`、`change_auth`、`reset_permanent_code` 进入容量为配置值的单 worker 队列。
- worker 确定 mutation 后，在修改安装前 required 写入 `pending`，包含 `event_id`、`attempt`、`target_status`、`expected_version`。
- 安装更新使用 `WHERE version = expected_version`，成功时 `version = version + 1`，并保存 `last_authorization_event_id/at`。
- 安装 mutation 与 `pending -> succeeded` 在同一数据库事务中完成。
- 失败写 `failed`；同一 event ID 已成功时直接幂等确认，陈旧事件不得覆盖较新状态。
- 启动时扫描开放 attempt。能够由安装事件标记证明成功的补齐 `succeeded`；无法证明的进入故障状态，不猜测、不回滚。
- callback 请求内只完成验证、accepted 和有界入队；队列满返回可重试错误。

数据库审计表新增 event/attempt/phase 所需字段和唯一约束。viewer 审计继续 best effort；授权审计失败必须阻止对应授权状态变化。

授权结果包含多个 Agent 时沿用当前 demo 合同：选择企业微信响应中的第一个有效 Agent，并记录明确选择结果；禁止循环覆盖同一安装记录。

## 8. 凭据加密与单向迁移

`wecom_installations.permanent_code` 和 `wecom_chatdata_messages.secret_key` 继续使用现有列，但列值统一变为 `CredentialCipher` envelope。Entity、Mapper 和日志不得暴露解密值。

新增有界启动迁移：

- 迁移在企业微信 scheduler、callback 和 HTTP endpoint 激活前运行。
- 每批最多 200 行，使用主键游标，不一次性加载全表。
- 已是合法 envelope 的行跳过；合法旧明文加密后原位更新。
- 空值、超长值、损坏 envelope 或加密失败立即停止启动并返回结构化错误。
- 全部迁移成功后写入 schema marker；正常运行路径只接受 envelope，不长期保留双读合同。
- 迁移日志只记录表名、批次数、迁移数和错误码，不记录凭据、密文或消息 ID。

tracked YAML 中所有企业微信 secret、token 和 AES key 改为环境变量引用。仓库中曾出现的真实值视为泄露，部署前必须在企业微信后台轮换。

## 9. chatdata 与统一时间线

`WeComChatDataStore` 继续保存官方展示所需的最小引用：msgid、加密后的 secret key、员工 ID、外部联系人 ID、时间、类型和方向。

同一数据库事务内调用 `WeComMessageProjector`：

1. 按规范化 `wecom:<external_userid>` 查找或创建 contact identity。
2. 通过现有 contact/conversation owner 创建联系人和 WeCom conversation。
3. 以稳定 source ID `wecom:<msgid>` 幂等写入通用 `messages`。
4. 通用消息只保存 channel、方向、时间、来源标识和安全占位摘要，不保存原文或 secret key。
5. 新消息提交后发布现有 SSE 事件，使打开的线程自动刷新。

cursor 只有在 chatdata 最小引用和统一消息投影都成功后推进。任何投影失败必须回滚本页，避免“游标已前进但页面永远缺消息”。

## 10. React 展示

Spring 前端现有 React、Ant Design 和页面布局是新增 UI 的唯一视觉真源。除企业微信官方登录组件和官方 OpenDataFrame 内容外，新增页面、按钮、状态、错误提示、抽屉、弹窗和加载反馈必须复用现有组件、`theme.useToken()`、间距、边框、字号、圆角、图标和响应式规则；不得复制 demo 的颜色、CSS 或嵌入式页面外观，也不得另建企业微信专属视觉主题。

企业微信登录组件保持官方视觉，不覆盖其内部字体、颜色、二维码、按钮或状态样式。外围容器仅提供与当前 `LoginPage` 一致的页面背景、宽度约束和入口切换，不把官方组件嵌入额外的装饰卡片层级。

React 迁移 `2026-08-10-wecom-inline-expandable-message-card-design.md` 的当前交互合同：

- 纯企业微信联系人使用一个全宽 OpenDataFrame，展示最近 15 条并占用聊天区可视高度。
- 混合联系人按统一时间线划分连续企业微信消息段，每段 4 至 6 条，非企业微信消息立即断段。
- 每段只创建一个 OpenDataFrame，正文只由官方 `ww-open-message` 渲染。
- Frame 未 mounted 前保持隐藏，不显示空白大框；失败显示紧凑段级标识。
- 稳定段签名复用 Frame；新增消息只准备并原子替换变化段。
- 联系人切换、登录失效、缓存淘汰和组件卸载必须销毁 Frame 与详情 iframe。
- 详情使用官方 `handleModal` 的 modal URL 和建议尺寸，不持久化 URL。
- viewer token、secret key、modal URL 不写 localStorage、console、SSE 或普通错误响应。

通用 `MessageBubble` 不渲染企业微信空正文。`ThreadPage` 先把统一时间线投影为普通消息、通话记录和企业微信连续段，再交给专用 `WeComTimelineSegment`。

## 11. 自动同步、公钥与渠道设置

- 自动同步保持有界单安装运行面和配置间隔，不依赖用户点击联系人。
- 授权安装成功后立即请求公钥注册；定时任务继续作为补偿。
- 公钥注册、自动同步和 viewer 共用同一安装解析、版本和 access token owner。
- 渠道设置中的企业微信“同步”调用真实 chatdata sync，不再进入不支持 WeCom 的通用 switch。
- 企业微信凭据不在渠道设置表单编辑；页面只展示安装、绑定、公钥和最近同步状态。
- 删除没有消费者的 `wecomSyncEnabled`，其他容量配置必须接入 owner 或从合同删除。

## 12. 错误与资源边界

- 登录 attempt、绑定 attempt、viewer token/session、用户速率和授权队列均有配置上限和 TTL。
- REST 上游必须配置真实连接和读取超时；外围 deadline 不能代替 socket timeout。
- chatdata 表遵守 `wecomChatDataStoreMaxMessages/MaxBytes`，采用按最旧时间清理的有界保留策略；游标和仍被 viewer session 使用的引用不得被提前删除。
- 所有公开错误使用项目统一结构化错误，不返回内部异常消息或企业微信完整 errmsg。
- upstream hint 可进入受控诊断和审计；access token、permanent code、secret key、auth code、suite ticket 和 AES key 永不进入日志。

## 13. 测试与验收

实施必须采用 TDD，并至少覆盖：

- 未绑定企业微信身份首次登录原子创建用户、`agent` 角色、绑定和 CRM session。
- 已绑定身份登录复用用户，不重复建号。
- 已有账号扫码绑定、重复 exchange 幂等、身份占用和用户已绑定拒绝。
- 自动创建账号无法解除唯一登录方式。
- callback accepted/pending/succeeded、队列满、重放、陈旧事件、版本冲突和启动对账。
- permanent code 与 secret key 明文批量迁移、损坏 envelope 阻止启动及日志脱敏。
- chatdata 页发布与统一消息投影原子性、方向、联系人归属和 SSE 刷新。
- viewer API 的 CRM token、绑定 actor、session 单次消费和错误审计。
- SecurityFilterChain 对 callback、登录、绑定、viewer 和普通 API 的真实过滤链。
- React 可选登录、绑定状态、纯企业微信整窗、混合时间线分段、Frame 生命周期和错误态。
- 登录页外围保持 Spring 现有视觉，企业微信扫码区域保持官方组件视觉，测试不得依赖自行伪造的二维码 DOM。
- 公钥立即触发与定时补偿，渠道设置真实同步入口。
- Flyway migration 合同、后端完整测试、前端测试、生产构建和无敏感配置扫描。

最低验收命令：

```bash
cd demo/message-center-spring/backend
mvn -q test
mvn -q -DskipTests package

cd ../frontend
npm test
npm run build

cd ../../..
rg -n 'wecom-suite-secret: [^$]|wecom-token: [^$]|wecom-encoding-aes-key: [^$]' \
  demo/message-center-spring/backend/src/main/resources
git diff --check
```

真实企业微信环境还必须验证：密码和扫码均可登录；首次扫码自动建号；已有账号可绑定；联系人打开后无需手动刷新即可看到企业微信段；新消息自动加入；授权回调、公钥注册和 chatdata 同步均有可诊断结果。

## 14. 完成定义

只有以下条件全部满足才可称为迁移完成：

- Spring 自身提供全部企业微信入口和 React 交互，不依赖 demo 进程。
- 企业微信消息进入统一联系人时间线并能通过官方组件展示。
- 登录、绑定、授权、凭据、审计和版本 owner 均在服务端闭合。
- 当前 WeCom 源码、迁移、测试和配置全部纳入 Git，不存在部署时遗漏的 untracked 功能。
- 自动化门禁通过，真实企业微信环境证据完成，剩余风险明确记录。
