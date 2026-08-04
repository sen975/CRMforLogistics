# 企业微信会话展示组件接入设计

> 认证配置更新：本项目当前按代开发应用接入。`authCorpId`、AgentID、`permanent_code`、suite 授权和安装选择以 `2026-07-28-wecom-delegated-authorization-installation-design.md` 为当前真源；本文中的 viewer、权限、限流、审计、统一时间线和前端组件设计继续有效。

## 1. 背景

消息中心已经有 Email、ChatApp/WhatsApp 和企业微信 WIP 接入。企业微信当前按代开发应用接入，并把官方会话展示组件加入消息中心，同时保留后续多渠道消息合并能力。

官方入口：

- 企业微信会话展示组件要求：`https://developer.work.weixin.qq.com/document/path/100049`
- 企业微信 JS-SDK 包：`@wecom/jssdk`

本设计只定义消息中心如何获取授权、挂载会话展示组件、控制权限和审计边界；不把企业微信官方组件内部展示内容伪造成消息中心自己的完整消息真相。

## 2. 目标

- 使用企业安装后的代开发应用接入企业微信会话展示组件。
- 用户在消息中心中打开企业微信联系人或会话时，可以进入官方会话展示窗口。
- 企业微信组件接入后，Email、ChatApp/WhatsApp、企业微信客服消息和企业微信会话组件仍能归并到同一个统一联系人。
- 首版支持本地 demo、单一目标授权企业和真实 suite 授权安装验证。
- 保留现有消息中心前端样式、三栏布局、滚动加载、发送入口、toast、详情栏和联系人交互；除新增企业微信会话入口和组件容器外，不重做现有 UI。
- 统一时间线每个联系人首次加载最近 10 条，向上滚动时每次增量加载 10 条更早消息；官方 viewer 引用默认只挂载按 `send_time` 排序后的最近 10 条，配置硬上限为 20，不一次返回全部引用。
- 前端不接触 `corpsecret`、`access_token`、`jsapi_ticket` 或任何长期密钥。
- 所有打开会话组件、授权失败、越权尝试和组件错误必须进入审计。

## 3. 非目标

- 不在首版实现多企业选择器、多租户路由或多实例安装记录存储。
- 不把会话展示组件中的完整内容批量复制到 `UnifiedMessageStore` 或 PostgreSQL 消息表。
- 不绕过消息中心自己的会话权限，直接凭企业微信用户身份决定可见范围。
- 不把企业微信会话展示组件作为 Email、WhatsApp 或普通客服消息的通用 UI 组件。
- 不借企业微信接入重做现有前端视觉、布局、消息气泡、联系人列表、滚动分页、发送区或已有交互。
- 不为了 demo 在前端硬编码企业微信密钥、签名、ticket 或用户身份。

## 4. 推荐主线

采用“组件嵌入 + 后端签发短时展示会话”的主线。

```text
Browser
  -> Message Center frontend
  -> backend /api/v1/wecom/login/attempts
  -> 企业微信 ww.createWWLoginPanel
  -> backend /api/v1/wecom/login/exchange
  -> backend /api/v1/wecom/js-sdk-config
  -> backend /api/v1/wecom/conversation-view/sessions
  -> 企业微信官方会话展示组件
```

消息中心前端只负责展示官方扫码面板、初始化 JS-SDK、接收用户操作、挂载组件和展示错误状态。后端负责一次性登录 state、企业微信签名、用户授权交换、消息中心权限校验、短时组件会话签发和审计。

## 5. Owner 与边界

| 概念 | Owner | 禁止成为 owner 的层 |
|---|---|---|
| 企业微信 API、JS-SDK 签名、access token、ticket 缓存 | `channel/wecom adapter` | 前端、UI 状态、HTTP controller |
| 消息中心用户能否查看某个会话 | `messaging` / `AccessControlService` | 企业微信组件、前端路由、浏览器本地状态 |
| 企业微信外部联系人和消息中心联系人归并 | `contact` | WeCom adapter、页面组件 |
| 统一会话、统一时间线、未读、分页 | `messaging` / `UnifiedMessageStore` 或后续模块化 `messaging` | WeCom JS-SDK adapter |
| 会话展示组件打开记录、失败原因、越权尝试 | `audit` | console log、toast 文案 |

企业微信展示组件是 `wecom` 渠道的受控显示入口，不拥有联系人合并、统一消息排序、权限或审计真相。

## 6. 多渠道合并模型

多渠道合并继续保留，而且必须和企业微信组件解耦。

```text
Email message
ChatApp / WhatsApp message
WeCom KF sync_msg message
WeCom conversation viewer reference
        |
        v
channel identity
        |
        v
contact identity merge
        |
        v
unified contact + unified timeline
```

企业微信分两类进入消息中心：

1. 可同步消息：例如微信客服 `sync_msg` 已拉取并允许保存的消息，进入 `UnifiedMessageStore`，参与统一时间线、未读、搜索、分页和 SSE 刷新。
2. 官方展示内容：会话展示组件内部内容不直接进入统一消息库。消息中心保存最小引用，例如 `corpId`、`externalUserId`、`openKfid`、企业微信会话标识、关联联系人、打开审计和可展示入口状态。

这样同一个客户仍可在左侧联系人列表中合并展示 Email、WhatsApp 和企业微信来源；中间时间线展示消息中心已经同步且有权限保存的消息；右侧或消息操作入口打开企业微信官方会话展示组件。

## 7. 后端组件

### 7.1 `WeComTokenService`

职责：

- 从 `WeComAuthorizationStore` 读取服务端选定的 active 代开发安装记录。企业微信官方把代开发授权返回的 `permanent_code` 定义为该代开发应用的 Secret；服务使用安装记录中的 `authCorpId` 与解密后的 `permanent_code` 调用 `GET /cgi-bin/gettoken?corpid=...&corpsecret=...`，获取并缓存该应用的 `access_token`。
- 生产代开发链路禁止调用第三方应用凭证接口 `/cgi-bin/service/get_corp_token`。该接口使用 Suite access token 和永久授权码，适用于第三方应用，不适用于代开发应用；误用会返回 `48002`。
- access token 缓存必须绑定 `installationId + version`；安装记录更新或撤销后立即失效。
- 缓存必须带过期时间和提前刷新窗口，例如过期前 5 分钟刷新。
- 不把 token 写入普通日志或前端响应。
- 上游错误只允许输出结构化的 HTTP 状态、`errcode`、路径和格式受限的 `hint`，不得记录 Secret、access token 或完整 `errmsg`。

官方依据：

- [代开发授权应用 Secret 的获取](https://developer.work.weixin.qq.com/document/path/97163)：代开发授权返回的 `permanent_code` 即应用 Secret。
- [代开发授权应用 access_token 的获取](https://developer.work.weixin.qq.com/document/path/97164)：使用自建应用 `/cgi-bin/gettoken` 接口，不使用第三方应用凭证接口。

### 7.2 `WeComJsSdkSignatureService`

职责：

- 获取并缓存 JS-SDK 需要的 ticket。
- 根据前端当前页面 URL、nonce、timestamp 生成 JS-SDK 签名。
- 返回最小前端配置：`corpId`、`agentId`、`timestamp`、`nonceStr`、`signature`、允许的 JSAPI 列表。

### 7.3 `WeComLoginAttemptService` 与 `WeComViewerService`

职责：

- 生成短时、有界、一次性原子消费的扫码 `state`。
- 处理企业微信 Web 登录组件在 `redirect_type=callback` 下返回的临时 `code`。
- 后端用 `code` 换取企业微信用户身份。
- 签发只用于 viewer 的短时 `viewerAuthToken`，不建立持久账号绑定或完整消息中心 session。
- 失败时返回结构化错误：state 缺失、过期或重放，code 过期，企业微信身份无效或容量限流。

### 7.4 `WeComConversationViewerService`

职责：

- 接收消息中心会话 ID 或联系人渠道身份 ID。
- 调用 `messaging` 权限服务确认当前用户可读。
- 找到对应企业微信身份映射，例如 `externalUserId`、`openKfid` 或后续官方组件要求的会话标识。
- 创建短时、一次性 `viewerSession`。
- 返回前端挂载官方组件所需的参数，不返回长期密钥。

### 7.5 `WeComConversationComponentGateway`

职责：

- 只适配企业微信官方组件或组件前置接口要求。
- 把官方错误码映射为项目结构化错误。
- 不直接写消息表，不决定联系人合并，不判断消息中心权限。

### 7.6 `WeComChatDataPublicKeyRegistrar`

职责：

- 只在配置显式启用时，从受 owner-only 权限保护的 PKCS#8 RSA-2048 私钥派生 X.509 PEM 公钥。
- `App.startWeb()` 不得同步读取私钥或因公钥注册依赖错误终止；registrar `open()` 只建立可选后台生命周期，缺少授权 owner 时降级为关闭状态并输出脱敏失败事件。
- `suite_ticket` 进入当前 8107 进程内存、或目标企业安装记录成功创建/更新后，只接收容量为 1 的合并式异步信号；授权回调不得等待企业微信上游 HTTP。
- worker 首次处理信号时才加载私钥；加载失败不缓存，下一次外部信号重新尝试；首次成功加载后缓存公钥材料。
- 使用 `WECOM_LOGIN_AUTH_CORP_ID` 唯一选定的 active 安装获取授权企业 access token，并调用官方 `chatdata/set_public_key`。
- 以 `authCorpId + publicKeyVersion + publicKeySha256` 为幂等键原子写脱敏状态；状态不得包含 ticket、token、permanent code、私钥或完整公钥。
- 注册失败不写成功状态、不改变授权安装状态，等待下一次 ticket 或安装变更事件重试。
- 公钥未注册时只阻断企业微信会话同步和 viewer，消息中心网页、登录、Email 与 ChatApp 必须继续可用。

## 8. 前端接入

消息中心登录页作为普通浏览器首屏，扫码成功后在同一标签页显示现有消息中心；企业微信会话查看入口放在现有发送区的企业微信 tab 中，替换原“企业微信 API 接入位已预留”占位，不重做消息中心结构：

- 登录页和会话组件必须位于完全相同的域名与 top frame。
- 登录页异步加载 `@wecom/jssdk 2.3.4`，调用 `ww.createWWLoginPanel()`，使用 `login_type=ServiceApp`、登录授权 SuiteID 作为 `appid` 和 `redirect_type=callback`，不发送 `agentid`。
- 扫码前不请求联系人、消息、模板、SSE 或同步 API；SDK 10 秒未完成时显示可重试错误态。
- 扫码成功后用临时 `code + state` 交换一次短时 `viewerAuthToken`，仅保存在当前页面 JavaScript 内存；刷新或过期后重新扫码。
- 联系人或消息属于 `wecom` 渠道时，发送区的企业微信 tab 显示“打开企业微信会话”操作。
- 授权完成后，请求后端创建 `viewerSession`。
- 点击 viewer 后懒加载 `jwxwork-1.0.0.js`，再使用后端返回的配置初始化官方会话展示组件。
- 组件加载中、授权失败、域名未配置、JSAPI 签名失败、无会话权限、企业微信接口错误必须有明确错误态。

官方组件接线必须使用：

- `ww.register()` 初始化企业与应用信息。
- `jsApiList` 包含 `wwapp.invokeJsApiByCallInfo`。
- `ww.initOpenData()` 初始化开放数据能力。
- `ww.createOpenDataFrameFactory().createOpenDataFrame(...)` 挂载组件。
- 模板中使用 `ww-open-message`，参数为 `message-id`、`secret-key`、`open-type="viewMessage"`。
- 模板组件通过 `binderror` 把错误交给 OpenDataFrame `methods`；外部浏览器通过 `handleModal({modalUrl, modalSize})` 创建 iframe 预览。
- `42006`、`42003`、`40029` 和 `Missing open sid` 视为登录态失效，清除内存 token 并返回扫码首屏。

前端 UI 约束：

- 现有三栏消息中心、联系人列表、消息线程、向上滚动加载、发送入口、附件预览、详情栏、toast 和按钮交互保持原样。
- 企业微信只新增必要 UI：入口按钮、授权状态、组件容器、加载态和错误态。新增 UI 必须沿用现有 CSS class、按钮样式、toast 模式和详情栏布局。
- 首版可以把企业微信入口文案、空态和错误态直接写在当前前端页面里；不得硬编码真实 `corpId`、`secret`、`access_token`、ticket、签名或用户身份。
- 新增组件容器优先复用现有发送区或消息详情区域，不改变联系人列表和中间消息线程的主交互。
- 除了接线企业微信入口所必需的最小 DOM 和事件处理，不修改现有 Email、ChatApp/WhatsApp、线程分页、联系人合并和发送交互。

前端不得保存完整企业微信密钥，不得把组件中展示的消息内容复制到全局状态或本地持久化。`viewerAuthToken` 不得写入 URL、cookie、localStorage 或 sessionStorage。

## 9. API 合同草案

首版建议放在版本化 API 下，后续写入 OpenAPI：

```text
GET /api/v1/wecom/js-sdk-config?url={encodedCurrentUrl}
```

返回当前页面可用的 JS-SDK 配置。后端必须校验 URL 属于允许域名。

```text
POST /api/v1/wecom/login/attempts
```

请求体必须为空对象。返回只读 `loginType=ServiceApp`、登录授权 `appId`、同域 `redirectUri`、一次性 `state` 和有效期，不返回 AgentID、secret、ticket、签名或 viewer token。

```text
POST /api/v1/wecom/login/exchange
```

请求体包含企业微信官方登录面板返回的临时 `code` 和刚创建的 `state`。后端先原子消费 state，再换取企业微信用户身份。
响应返回短时 `viewerAuthToken`，只用于后续创建和读取 viewer session。前端不得自报 `wecomUserId` 作为授权依据。

```text
POST /api/v1/wecom/conversation-view/sessions
```

请求体包含消息中心 `conversationId` 或 `contactPointId`，以及登录换码得到的 `viewerAuthToken`。后端执行消息中心读权限校验，并通过 `viewerAuthToken` 解析企业微信用户身份后创建短时展示会话。

```text
GET /api/v1/wecom/conversation-view/sessions/{viewerSessionId}
```

请求带 `X-WeCom-Viewer-Auth` header。返回挂载官方会话展示组件所需参数。`viewerSessionId` 必须短时有效、可撤销，并绑定当前消息中心用户和企业微信用户。

```text
POST /api/v1/wecom/conversation-view/events
```

只接受 `component_error` 这类有界客户端组件事件，请求通过 `X-WeCom-Viewer-Auth` 传短时 token，并且必须匹配同一 token 最近一次成功读取、尚未报错的 viewer session。该资格必须原子消费，阻断顺序和并发重放。事件 body 不接受合同之外的字段，包括 token、ticket、签名或 `secretKey`。

## 10. 配置

代开发应用的服务商级配置需要：

```text
WECOM_SUITE_ID
WECOM_SUITE_SECRET
WECOM_AUTHORIZATION_INSTALLATIONS_FILE
WECOM_LOGIN_AUTH_CORP_ID
# 服务商登录授权 Suite；两项必须同时配置
WECOM_LOGIN_SUITE_ID
WECOM_LOGIN_SUITE_SECRET
CREDENTIAL_MASTER_KEY_FILE
WECOM_ALLOWED_JSAPI_ORIGINS
WECOM_LOGIN_REDIRECT_URI
WECOM_LOGIN_ATTEMPT_TTL_SECONDS
WECOM_LOGIN_MAX_PENDING
WECOM_TOKEN
WECOM_ENCODING_AES_KEY
```

真实扫码使用的 redirect URI、登录页面和会话组件必须是企业微信后台配置的同一公网 HTTPS 域名。

可选：

```text
WECOM_DATA_FILE
WECOM_VIEWER_SESSION_TTL_SECONDS
WECOM_VIEWER_MAX_MESSAGES
WECOM_VIEWER_SESSION_RATE_LIMIT
WECOM_VIEWER_AUDIT_FILE
WECOM_VIEWER_AUDIT_MAX_BYTES
WECOM_TOKEN_REFRESH_SKEW_SECONDS
WECOM_CHATDATA_PRIVATE_KEY_FILE
WECOM_CHATDATA_PUBLIC_KEY_VERSION
WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER
WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE
```

本地 demo 的 `WECOM_DATA_FILE` 用 JSONL 存放会话展示组件消息引用；每行必须带 `msgid`、`external_userid`、解密后的 `secret_key` 或 `secretKey`，以及当前企业微信用户归属字段（`userid`、`UserId`、`wecom_userid` 或 `wecomUserId`），用于阻断一个短时授权 token 打开其他企业微信用户的会话引用。

浏览器登录授权的 SuiteID、SuiteSecret 只用于 `ServiceApp` Web 登录二维码和 `service/auth/getuserinfo3rd` 换码；二维码不发送 AgentID。会话展示链路的 AgentID 和 `permanent_code` 仍来自企业安装后的授权记录，不从登录 Suite 或静态 `WECOM_AGENT_ID/WECOM_SECRET` 读取。代开发语义下该 `permanent_code` 是应用 Secret，只能在后端作为 `/cgi-bin/gettoken` 的 `corpsecret` 使用。生产环境服务商密钥必须通过项目既有 secret 机制或环境配置下传。不得提交真实企业微信密钥。

## 11. 安全与资源治理

- 登录 attempt 默认 5 分钟内有效、一次性消费并有 256 条容量上限。
- `viewerSession` 默认 5 分钟内有效，且绑定创建它的短时 viewer token。
- `viewerAuthToken` 默认 5 分钟内有效，只由后端根据企业微信授权 `code` 签发，创建和读取 viewer session 时都必须校验。
- 创建 `viewerSession` 前必须执行消息中心会话读权限校验。
- JS-SDK 签名 URL 必须是当前允许域名，禁止为任意 URL 签名。
- access token、ticket 和签名材料不得进入普通日志。
- 调用企业微信 API 必须设置连接和请求超时，并限制响应体大小；viewer JSON 请求体也必须有独立上界。
- 后端错误必须映射为结构化错误；官方组件 `binderror` 的四类登录态失效特征由前端按官方合同识别并返回扫码首屏。
- 每个用户创建展示会话需要速率上限，防止刷新或脚本造成无界 session。
- 打开组件、授权失败、越权、签名失败、官方接口失败都写审计。
- 本地 demo 使用有字节上限的结构化 JSONL audit adapter，达到上限时 viewer 操作失败关闭；生产模块化路径把同一事件交给现有 `AuditService`。audit 事件不得包含 token、ticket、签名或 `secretKey`。

## 12. 与会话存档专区同步的关系

8107 不再挂载模拟 `/webhook/wecom`、无版本 `/api/wecom/*`、本地消息注入或模拟 `gettoken` 路由。`WeComReceiver` helper 只保留给隔离测试，不能成为真实 viewer 数据入口。

真实 viewer 引用由当前授权企业关联的会话存档专区程序能力同步：

- 公钥注册成功后，企业微信开始生成可由该私钥解密的 `encrypted_secret_key`。
- 8107 调用固定 `conversation_viewer_sync` 能力获取会话索引，在本机 JSONL 只保存一对一会话的 `msgid`、解密后的 `secret_key`、员工/外部联系人 ID 和发送时间。
- 前端把这些最小引用交给官方 `ww-open-message` 展示原始内容；不把组件内正文复制进消息中心数据库或浏览器持久化。
- 后续摘要能力是独立阶段，默认关闭，不是 viewer 组件可用的前置条件。

## 13. 验收标准

- 目标企业存在 active 代开发授权安装记录后，普通浏览器首屏通过真实 `ww.createWWLoginPanel()` 展示二维码，登录前不请求消息中心数据。
- 扫码成功后地址栏不出现 code、state 或 token，并在同一标签页进入现有消息中心。
- 后端能原子消费一次性 state、识别企业微信用户并签发短时 viewer token；重放 state 被拒绝。
- 用户有消息中心会话读权限时，可以创建 `viewerSession` 并挂载官方会话展示组件。
- 8107 收到真实 `suite_ticket` 后立即响应，并在后台为唯一目标授权企业完成公钥注册；同版本和摘要不重复注册，失败不污染安装状态。
- 专区同步生成至少一条合法 `msgid + secret_key` 引用后，viewer 能把该引用交给官方组件展示原始消息。
- 用户无消息中心会话读权限时，后端拒绝创建 `viewerSession`，并写审计。
- 现有消息中心前端样式和交互保持不变；新增企业微信 UI 只作为入口、授权状态、组件容器和错误态出现。
- 每个联系人统一时间线首次仅有最近 10 条，顶部滚动继续增量加载；官方 viewer 默认只挂载最近 10 条引用。
- Email、ChatApp/WhatsApp、WeCom `sync_msg` 消息仍按统一联系人合并展示。
- 会话展示组件内容不被前端复制进全局状态或本地存储。
- access token、ticket、secret 不出现在前端响应、日志和测试快照中。
- 外部浏览器 `handleModal`、模板 `binderror`、登录失效返回扫码层均有真实交互证据。
- 桌面和移动 viewport 均保留真实截图；字符串探针不能替代截图验收。

## 14. 测试计划

- 单元测试：token 缓存、ticket 缓存、JS-SDK 签名 URL 校验、过期刷新。
- 单元测试：扫码 state 创建、容量、过期、原子消费、重放拒绝和审计脱敏。
- 单元测试：企业微信授权 `code + state` 交换成功、过期和身份无效。
- 权限测试：有读权限才能创建 `viewerSession`，无读权限返回结构化拒绝并写审计。
- API 合同测试：新增 `/api/v1/wecom/*` 接口写入 OpenAPI 并通过合同探针。
- 前端行为测试：二维码态、授权成功态、组件加载态、组件失败态、无权限态、预览态和重新扫码态。
- 回归测试：现有 Email、ChatApp/WhatsApp、WeCom `sync_msg` 统一联系人和线程分页不回退。
- 代开发凭证测试：真实生产 owner 只调用 `GET /cgi-bin/gettoken`，请求使用安装记录的 `authCorpId + permanent_code`；测试必须证明不请求 Suite token 或 `/cgi-bin/service/get_corp_token`。
- 既有安装兼容测试：已有加密 JSONL 安装记录无需迁移或重新授权即可获取代开发应用 token；安装版本变化仍使缓存失效。

## 15. 后续路线

第一阶段只做代开发应用和服务端显式选定的单企业测试闭环。

第二阶段可接：

- 多企业选择器、多租户路由和数据库安装记录。
- 企业微信会话内容存档导入 worker。
- 企业微信组件查看入口与消息中心统一审计报表联动。
- 基于企业微信身份的联系人自动归并建议。
