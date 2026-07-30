# 企业微信代开发授权安装记录设计

## 1. 背景与真源地位

消息中心已经实现浏览器企业微信扫码登录、短时 viewer 授权和会话展示组件接线，但认证配置仍按企业自建应用读取静态 `WECOM_CORP_ID`、`WECOM_AGENT_ID` 和 `WECOM_SECRET`。当前实际接入形态是代开发应用：AgentID 和应用 Secret 不是服务商预先填写的固定值，而是企业安装代开发模板后产生的企业级授权数据。

本文将企业微信认证配置的唯一 owner 改为“代开发授权安装记录”。本文优先于以下旧设计中的自建应用静态配置段落：

- `2026-07-27-message-center-login-wecom-oauth-design.md`
- `2026-07-27-wecom-conversation-viewer-design.md`

旧文档中的扫码 UI、短时 state、viewer session、权限、限流、审计和组件交互设计继续有效；与本文冲突的静态 `AgentID/Secret` 和“不实现 suite 授权”描述失效。

## 2. 官方约束

实现以企业微信官方文档为准：

- Web 登录组件：`https://developer.work.weixin.qq.com/document/path/98152`
- 获取用户登录身份：`https://developer.work.weixin.qq.com/document/path/98177`
- 获取代开发应用模板凭证：`https://developer.work.weixin.qq.com/document/path/97162`
- 代开发授权应用 Secret 的获取：`https://developer.work.weixin.qq.com/document/path/97163`
- 代开发授权应用 access_token 的获取：`https://developer.work.weixin.qq.com/document/path/97164`
- 获取企业授权信息：`https://developer.work.weixin.qq.com/document/path/100795`
- 会话展示组件：`https://developer.work.weixin.qq.com/document/path/100049`

代开发应用的浏览器扫码登录使用：

```text
login_type = CorpApp
appid = 授权企业 CorpID
agentid = 企业安装代开发应用后产生的 AgentID
```

安装授权产生的 `permanent_code` 是该授权企业下代开发应用的 Secret。应用 access token 仍通过 `gettoken(authCorpId, permanentCode)` 获取。扫码回调 code 使用该应用 access token 调用 `/cgi-bin/auth/getuserinfo`，只接受返回 `userid` 的企业成员。code 只能使用一次，5 分钟未使用自动过期。

## 3. 目标

- 以持久化的代开发授权安装记录作为 `authCorpId`、AgentID 和 `permanent_code` 的唯一真源。
- 服务重启后可以恢复已授权企业的扫码登录和会话展示组件配置。
- `permanent_code` 使用项目现有 AES-256-GCM 凭据能力加密落盘。
- 登录首屏由服务端配置唯一选定一个授权企业，不允许匿名浏览器指定 CorpID。
- 登录 state 绑定具体 `installationId`，防止换码期间跨企业或跨安装记录混用。
- 授权取消、凭据损坏、配置切换和上游错误均失败关闭。
- 保留现有 viewer owner 校验、一次性 session、限流、审计和 SDK 错误处理。
- 使用 JDK 17 构建和运行。

## 4. 非目标

- 不引入 PostgreSQL 或新增数据库迁移。
- 不提供多企业选择器、授权安装管理后台或匿名 CorpID 请求参数。
- 不保留 `WECOM_AGENT_ID`、`WECOM_SECRET` 作为代开发认证回退。
- 不修改消息中心联系人、会话或权限模型。
- 不实现完整 CRM 账号体系、持久登录、账号绑定或自动开户。
- 不把 access token、suite ticket 或 jsapi ticket 持久化到安装记录。
- 不在本轮扩展企业微信会话内容同步能力。

## 5. Owner 与组件边界

### 5.1 `WeComAuthorizationStore`

`WeComAuthorizationStore` 是代开发授权安装记录的唯一 owner，负责：

- 按 `suiteId + authCorpId` 唯一查询和更新安装记录。
- 加密写入、解密读取 `permanent_code`。
- 使用临时文件和原子替换发布完整 JSONL 快照。
- 串行化同一进程内的读改写，阻断并发丢更新。
- 校验记录结构、状态和字段上界。
- 返回明确的损坏、解密失败、未找到或非 active 原因。

HTTP controller、`WeComViewerService`、`WeComReceiver` 和前端不得直接读写安装 JSONL，也不得从环境变量拼装安装记录。

### 5.2 `WeComSuiteCredentialService`

负责服务商级凭证和短时缓存：

- 接收并验证官方 `suite_ticket` 回调。
- 只在进程内保存最新 suite ticket 和接收时间。
- 使用 `suiteId + suiteSecret + suiteTicket` 获取并缓存 `suite_access_token`。
- 对所有企业微信 HTTP 调用实施 10 秒超时和 1 MiB 响应上限。

suite ticket、suite access token 和应用 access token 不写入授权安装 JSONL。

### 5.3 `WeComAuthorizationService`

负责授权事件的数据流：

- 授权成功后使用 `auth_code` 调用 `get_permanent_code`。
- 使用 `get_auth_info` 读取 `authCorpId` 和 `auth_info.agent[].agentid`。
- 构造完整安装记录并交由 store 原子保存。
- 授权取消或失效时把对应记录更新为 `revoked` 或 `failed`。
- 更新安装记录后使该安装的 access token、JS-SDK ticket 和未完成登录 attempt 失效。

### 5.4 登录和 viewer 服务

`WeComLoginAttemptService` 创建 attempt 时从 store 精确选择 active 安装记录，并把 `installationId` 及记录版本绑定到 state。`WeComViewerService` 只能通过绑定的安装记录取得 `authCorpId`、AgentID 和解密后的 `permanent_code`，不能读取旧静态 Secret。

## 6. 安装记录模型

安装 JSONL 默认路径：

```text
data/wecom-authorization-installations.jsonl
```

每行是一条完整安装记录：

```json
{
  "installationId": "稳定随机标识",
  "suiteId": "dk...",
  "authCorpId": "ww...",
  "agentId": "1000002",
  "permanentCodeEncrypted": "AES-256-GCM envelope",
  "authStatus": "active",
  "authorizedAt": "2026-07-28T00:00:00Z",
  "updatedAt": "2026-07-28T00:00:00Z",
  "lastSuiteTicketAt": "2026-07-28T00:00:00Z",
  "version": 1
}
```

约束：

- 唯一键为 `suiteId + authCorpId`。
- `installationId` 首次创建后保持稳定。
- `authStatus` 只允许 `active`、`revoked`、`failed`。
- `version` 每次有效更新递增，用于阻断 attempt 创建后发生的安装记录变化。
- `permanentCodeEncrypted` 必须是现有 `CredentialCipher` 生成的 AES-256-GCM envelope。
- 任何明文 `permanent_code`、suite ticket、access token、登录 code、viewer token、ticket 或签名字段都不允许出现在文件中。
- 文件存在任意无法解析或不符合合同的非空行时，整个认证 store 返回不可用，不能静默忽略损坏行。

## 7. 文件持久化规则

- 保存时读取并验证完整快照，在内存中按唯一键 upsert。
- 把新快照写入与目标文件同目录的权限受限临时文件。
- flush 后通过原子移动替换目标文件；文件系统不支持原子移动时返回结构化写入失败，不降级为直接覆盖。
- 同一 JVM 内对读改写加锁；本地 JSONL 首版明确只支持单进程 writer。
- 文件、目录或主密钥不可用时认证链路 fail closed。
- 不把密文或解密明文写入异常消息、审计或日志。
- 本地 JSONL 是当前最快闭环的 adapter；未来迁移数据库时保持 store 接口和安装记录语义不变。

## 8. 服务商配置与安装配置

服务商级静态配置：

```env
WECOM_SUITE_ID=dk...
WECOM_SUITE_SECRET=...
WECOM_TOKEN=...
WECOM_ENCODING_AES_KEY=...
WECOM_CALLBACK_RECEIVE_ID=...
WECOM_AUTHORIZATION_INSTALLATIONS_FILE=data/wecom-authorization-installations.jsonl
CREDENTIAL_MASTER_KEY_FILE=secrets/credential_master_key
WECOM_LOGIN_AUTH_CORP_ID=ww...
WECOM_ALLOWED_JSAPI_ORIGINS=https://crm.example.com
WECOM_LOGIN_REDIRECT_URI=https://crm.example.com/
```

`WECOM_CALLBACK_RECEIVE_ID` 只拥有 GET 指令回调 AES 明文末尾 `receiveId` 的校验语义，未配置时回退到 `WECOM_SUITE_ID`。它不能替代业务 SuiteID；Suite API、安装记录和 POST 授权事件中的 SuiteID 仍由 `WECOM_SUITE_ID` 唯一拥有。

安装级配置只存在于 `WeComAuthorizationStore`，包括 `authCorpId`、AgentID、加密 `permanent_code`、授权状态、时间戳和版本。

`WECOM_AGENT_ID` 和 `WECOM_SECRET` 不再是代开发认证配置。检测到缺少 active 安装记录时必须返回安装配置错误，禁止回退。

## 9. 唯一安装选择

首版通过必填 `WECOM_LOGIN_AUTH_CORP_ID` 唯一选择登录企业：

1. 读取 `WECOM_SUITE_ID + WECOM_LOGIN_AUTH_CORP_ID`。
2. 从 store 精确查找安装记录。
3. 只接受 `active` 且 AgentID、密文和版本完整的记录。
4. attempt 返回该记录的 `authCorpId`、AgentID 和同域 redirect URI。
5. state 保存 `installationId + version`。
6. exchange 时重新读取同一记录并比较 ID、版本和状态。
7. 记录变化、撤销、替换或解密失败时拒绝换码，要求重新创建二维码。

禁止按文件顺序、更新时间或“唯一 active 记录”隐式选择；禁止由匿名前端传入 CorpID。未来多租户必须由已验证域名或租户路由映射到 `installationId`。

## 10. 授权与登录数据流

### 10.1 安装授权

```text
企业微信加密回调
  -> 验签与解密
  -> suite_ticket：更新内存缓存
  -> 授权成功 auth_code
       -> get_suite_token
       -> get_permanent_code
       -> get_auth_info
       -> encrypt(permanent_code)
       -> WeComAuthorizationStore.upsert(active)
```

授权回调必须使用官方签名与加解密规则；当前 `/webhook/wecom` 的模拟 XML 解析不能作为正式授权入口。

### 10.2 浏览器扫码登录

```text
POST login/attempts
  -> 精确选择安装记录
  -> state 绑定 installationId + version
  -> 返回 appid=authCorpId、agentid=安装 AgentID
  -> ww.createWWLoginPanel(login_type=CorpApp)
  -> code + state
  -> 重读并校验同一安装记录
  -> gettoken(authCorpId, permanentCode)
  -> auth/getuserinfo(accessToken, code)
  -> 仅接受 userid
  -> viewerAuthToken
```

JS-SDK 签名、viewer session 和 `ww.register` 必须继续使用同一 `installationId`，不能跨企业混用。

## 11. 错误合同

新增或明确以下结构化错误码：

- `WECOM_LOGIN_INSTALLATION_NOT_SELECTED`：未配置目标授权企业。
- `WECOM_INSTALLATION_NOT_FOUND`：没有匹配的安装记录。
- `WECOM_INSTALLATION_INACTIVE`：记录已撤销或失效。
- `WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE`：密文损坏、主密钥错误或解密失败。
- `WECOM_INSTALLATION_STORE_CORRUPTED`：JSONL 存在损坏或不符合合同的记录。
- `WECOM_INSTALLATION_CHANGED`：state 绑定后安装记录版本或状态发生变化。
- `WECOM_SUITE_TICKET_NOT_READY`：进程尚未收到可用 suite ticket。
- `WECOM_UPSTREAM_UNAVAILABLE`：企业微信接口超时、超限或暂时不可用。

这些错误不得包含 secret、ticket、code、token、签名或密文。现有 400/403/429/500 HTTP 分层保持不变，由错误类型映射到适当状态。

## 12. 安全与审计

- 企业微信回调必须先验签、解密，再解析事件。
- `permanent_code` 只在调用栈内短暂存在，立即加密后写入 store。
- suite secret、Token、EncodingAESKey 通过项目配置/secret 文件下传，不写入安装记录。
- 授权安装写入失败、加密失败、解密失败和审计失败均 fail closed。
- 授权取消后立即撤销记录，并清除相关 access token、JS-SDK ticket 和未完成 attempt。
- 审计只记录事件类型、suiteId 摘要、`authCorpId`、`installationId`、结果和错误码。
- 普通日志、HTTP 响应、审计和测试快照不得出现明文凭据或短时授权材料。

## 13. 测试与验收

### 13.1 单元和持久化测试

- JSONL 新建、唯一键更新、稳定 installationId、版本递增和重启恢复。
- 临时文件加原子替换；并发写入不丢更新。
- 明文 `permanent_code` 不落盘。
- 错误主密钥、篡改密文和损坏 JSONL 均失败关闭。
- 多条 active 记录存在时只选择 `WECOM_LOGIN_AUTH_CORP_ID` 对应记录。
- attempt 绑定 `installationId + version`；配置切换、撤销、更新和重放均被拒绝。

### 13.2 官方链路测试

- 回调验签解密、suite ticket 更新、授权成功、授权取消和上游错误映射。
- `login_type=CorpApp`，`appid=authCorpId`，`agentid=安装产生的AgentID`。
- `gettoken` 使用 `authCorpId + permanent_code`。
- `getuserinfo` 使用对应应用 access token，非成员响应被拒绝。
- JS-SDK 签名、viewer session 和 viewer detail 均绑定同一安装记录。

### 13.3 工程门禁

在 JDK 17 下执行：

```text
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
git diff --check
```

### 13.4 真实验收

在同一公网 HTTPS 域名和 top frame 中完成：

- 企业安装授权并生成持久化安装记录。
- 服务重启后仍能创建正确企业的二维码。
- 浏览器扫码登录并在同标签页进入消息中心。
- 点击企业微信 viewer，正常加载真实会话展示组件。
- 验收加载态、安装未就绪、凭据不可用、授权撤销和组件错误态。
- 保存桌面和移动 viewport 的真实截图；字符串探针不能替代截图。

## 14. 完成边界

本设计完成后，企业微信认证配置 owner 从静态自建应用环境变量迁移为代开发授权安装记录。JSONL 方案只承诺单机、单进程 writer 的最快真实闭环；多实例、数据库迁移、企业选择器和完整 CRM 身份绑定留待独立设计。

## 15. 实现状态（2026-07-28）

- 已实现 Suite 回调验签解密、`suite_ticket`、`create_auth`、`change_auth` 和 `cancel_auth` 编排。
- 已实现 AES-256-GCM 加密 JSONL 安装 owner、损坏失败关闭、稳定 installationId 和版本递增。
- 登录 attempt、扫码换码、JS-SDK 签名、viewer token/session/detail 已绑定同一 `installationId + version`。
- 登录首屏只接受服务端 `WECOM_LOGIN_AUTH_CORP_ID` 选定的 active 安装，不接受浏览器 CorpID。
- 正式回调为 `GET/POST /api/v1/wecom/authorization/callback`：保存地址时 GET 负责验签、解密并返回 `echostr`，校验通过后 POST 接收 `suite_ticket`、安装变更等事件；OpenAPI 共 27 个 operation。
- 授权 worker 已覆盖有界背压、成功重放、失败后重新投递、变更失败恢复和撤销不被并发旧任务覆盖。
- 尚未完成的外部验收是使用真实 Suite 配置、active 安装记录和公网 HTTPS 域名扫码，并保存桌面/移动真实截图。
