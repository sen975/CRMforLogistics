# 企业微信代开发成员头像 OAuth2 授权设计

## 背景与根因

生产环境已经确认：代开发应用使用企业安装 access token 调用 `/cgi-bin/user/get` 时，成员姓名可以返回，但 `avatar` 为空。将 `scope=snsapi_privateinfo` 传给 `@wecom/jssdk` 的 `createWWLoginPanel` 后，`/cgi-bin/auth/getuserinfo` 仍不返回 `user_ticket`。

官方 Web 登录组件使用 `https://login.work.weixin.qq.com/wwlogin/sso/login`，公开参数不包含 `scope`；敏感信息授权则属于另一条 OAuth2 合同，必须访问 `https://open.weixin.qq.com/connect/oauth2/authorize`，携带 `scope=snsapi_privateinfo` 和 `agentid`。因此，Web 登录与成员敏感资料授权必须拆成两个阶段，不能由登录组件隐式完成。

## 目标与边界

本设计实现已绑定企业微信的 CRM 账号主动授权并保存本人企业微信头像：

- 保留现有企业微信 Web 登录作为 CRM 登录和绑定入口。
- 头像为空时，在账户企业微信区域提供明确的头像授权入口。
- 普通浏览器显示二维码，成员使用企业微信扫码完成手工授权。
- 企业微信内置浏览器可以直接打开同一授权地址。
- 授权成功后原 CRM 页面异步等待终态，并只在完成后刷新绑定状态和账户头像。

本设计不读取其他成员的敏感资料，不以用户上传头像冒充企业微信头像，不把 OAuth code、`user_ticket`、access token 或永久授权码返回前端或写入普通日志，也不改变企业微信消息、联系人、会话和 Topic 合同。

## 所有权与组件

`WeComAvatarAuthorizationService` 是头像敏感授权流程的唯一 owner，负责创建 attempt、生成 OAuth2 地址、消费回调、验证身份、保存头像和投影状态。Controller 只做 HTTP 映射；前端只展示服务端投影并触发命令。

复用现有组件：

- `WeComInstallationService`：解析并校验当前安装版本、企业和 AgentID。
- `WeComAccessTokenService`：提供企业安装 access token。
- `WeComAuthorizationGateway`：调用 `/cgi-bin/auth/getuserinfo` 和 `/cgi-bin/auth/getuserdetail`。
- `WeComUserBindingService`：读取当前 CRM 账号的企业微信绑定。
- `WeComPartyProfileService`：保存授权返回的姓名和头像，继续维护 `READY/PARTIAL` 资料状态。
- `AccountService`：继续统一投影 `WECOM > UPLOAD > INITIAL` 的头像优先级。

OAuth attempt 是五分钟内有效的一次性状态，不新增数据库表。服务使用有界内存容器，保存随机 state、CRM user ID、installation ID/version、corp ID、AgentID、预期 WeCom user ID、状态和到期时间。服务重启后未完成 attempt 失效，用户重新发起即可。

## HTTP 合同

### 创建授权

`POST /api/account/wecom-avatar/authorizations` 需要 CRM 登录，返回：

```json
{
  "authorizationId": "opaque-id",
  "authorizationUrl": "https://open.weixin.qq.com/connect/oauth2/authorize?...",
  "status": "PENDING",
  "expiresIn": 300
}
```

创建前必须确认当前账号已绑定企业微信、安装仍有效、AgentID 非空。OAuth 地址固定包含：

- `appid={authCorpId}`
- `redirect_uri={服务端固定回调地址，URL 编码}`
- `response_type=code`
- `scope=snsapi_privateinfo`
- `state={高熵一次性状态}`
- `agentid={当前安装 AgentID}`
- `#wechat_redirect`

回调地址不从请求的 `Host`、`Origin` 或 `Referer` 推导，也不新增第二套域名配置。服务使用 URI 解析器读取现有 `app.wecom-login-redirect-uri` 的 scheme 与 authority，并将 path 固定替换为 `/api/public/wecom-avatar/oauth/callback`，同时清除原 query 和 fragment。配置不是绝对 HTTPS URI 时，生产环境启动门禁失败。

`authorizationUrl` 只包含短期 state，不包含 access token、永久授权码或 CRM token。

### OAuth 回调

`GET /api/public/wecom-avatar/oauth/callback?code=...&state=...` 不依赖浏览器 CRM 会话，因为扫码可能发生在另一台设备。回调必须：

1. 原子领取未过期、未消费的 state。
2. 重新解析安装并校验 installation ID、version、corp ID 和 AgentID 未变化。
3. 使用企业安装 access token 调 `/cgi-bin/auth/getuserinfo`。
4. 校验返回 corp ID 与 attempt 企业一致，返回 user ID 与 attempt 绑定成员完全一致。
5. 要求 `user_ticket` 非空；否则授权失败，不降级为 `/cgi-bin/user/get`。
6. 调 `/cgi-bin/auth/getuserdetail`，校验返回 user ID 与预期成员一致。
7. 通过 `WeComPartyProfileService` 保存头像；头像为空视为失败，不覆盖已有头像。

回调成功或失败后返回一个最小 HTML 结果页，只显示“授权完成，可返回原页面”或结构化失败文案，不展示成员 ID、code、ticket 或上游响应。

### 查询状态

`GET /api/account/wecom-avatar/authorizations/{authorizationId}` 需要 CRM 登录，并只允许创建该 attempt 的账号查询。返回：

```json
{
  "authorizationId": "opaque-id",
  "status": "PENDING|SUCCEEDED|FAILED|EXPIRED",
  "errorCode": null
}
```

终态只保留到 attempt 到期后的短暂清理窗口。前端每两秒查询一次，最长五分钟；页面关闭或终态后立即停止。`SUCCEEDED` 后依次刷新企业微信绑定和账户资料，不在处理中持续替换头像。

## 前端交互

入口位于账户面板现有“企业微信”区域：仅在已绑定企业微信且当前账户头像来源不是 `WECOM` 时显示“授权企业微信头像”。

点击后打开一个独立 Modal：

- 桌面普通浏览器展示 Ant Design `QRCode`，二维码内容完全使用服务端返回的 `authorizationUrl`。
- 企业微信内置浏览器显示“在企业微信中授权”按钮，点击后导航到同一地址。
- Modal 显示等待、成功、失败和过期状态；失败或过期时可重新生成 attempt。
- 成功后关闭二维码、刷新账户资料，并显示一次成功反馈。

不自动弹窗打断每次登录。用户上传头像仍作为企业微信头像不可用时的备用，不因发起或失败授权而删除。

## 错误、安全与观测

服务端至少区分：

- `WECOM_AVATAR_AUTH_BINDING_REQUIRED`
- `WECOM_AVATAR_AUTH_INSTALLATION_CHANGED`
- `WECOM_AVATAR_AUTH_STATE_INVALID`
- `WECOM_AVATAR_AUTH_IDENTITY_MISMATCH`
- `WECOM_AVATAR_AUTH_TICKET_MISSING`
- `WECOM_AVATAR_AUTH_PROFILE_EMPTY`
- `WECOM_AVATAR_AUTH_UPSTREAM_FAILED`

创建 attempt 按用户和来源地址限流，并限制全局未完成数量。state 至少 128 bit 随机性、最多使用一次；重复回调返回同一终态但不重复调用上游。查询接口必须校验 CRM user ID 所有权，防止枚举其他账号的状态。

结构化日志记录 authorization ID 的不可逆摘要、阶段、状态、企业安装 ID 和错误码；不得记录 authorization URL、code、state、`user_ticket`、access token、永久授权码或原始上游响应。关键阶段为 `ATTEMPT_CREATE`、`IDENTITY_EXCHANGE`、`PROFILE_EXCHANGE` 和 `PROFILE_PERSIST`。

## 旧路径处理

从 Web 登录组件和 `WeComLoginAttemptService.LoginAttemptResponse` 删除无效的 `scope` 字段，避免继续表达错误合同。普通 Web 登录/绑定成功后只同步非敏感目录资料；没有 `user_ticket` 是该路径的正常结果，不再把它描述为头像授权成功。

现有 `/cgi-bin/auth/getuserdetail` 网关和 `WeComPartyProfileService.syncAuthorizedEmployee` 继续复用，但只有专用头像 OAuth owner 可以把缺少 ticket 判为授权失败。

## 验收标准

1. Web 登录组件不再接收或宣称支持 `scope=snsapi_privateinfo`。
2. 已绑定账号可以创建五分钟有效的头像授权 attempt，URL 参数与官方 OAuth2 合同一致。
3. 未登录、未绑定、安装变化、state 过期/重放、企业不一致或成员不一致都不能写入头像。
4. 回调拿不到 `user_ticket` 时明确失败且不降级调用 `/cgi-bin/user/get`。
5. 成功回调只为当前绑定成员保存授权详情中的头像，并使账户投影变为 `avatar.source=WECOM`。
6. 普通浏览器二维码和企业微信内置浏览器直达均使用同一服务端 URL；轮询在成功、失败、过期、关闭 Modal 时停止。
7. 前端只在终态成功后刷新头像，处理中不持续更新账户区。
8. 日志可判断失败阶段与错误码，同时不包含 OAuth 和企业微信敏感凭据。
9. 后端 owner/Controller/网关专项测试、前端 Modal/轮询测试、前后端构建和服务器实机扫码验收全部通过。
