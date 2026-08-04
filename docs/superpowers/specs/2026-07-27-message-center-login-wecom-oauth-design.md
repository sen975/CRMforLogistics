# 浏览器企业微信扫码登录与会话组件最小闭环设计

> 认证配置更新：代开发应用的 `authCorpId`、AgentID 和 `permanent_code` 以 `2026-07-28-wecom-delegated-authorization-installation-design.md` 为当前真源。本文中的扫码 UI、state、短时 viewer 授权和组件交互仍有效；“不实现 suite 授权”及静态应用配置边界已被替代。

## 1. 背景

`message-center-demo` 已经实现企业微信 JS-SDK 配置、登录 code 交换、短时 `viewerAuthToken`、一次性 viewer session、联系人可读校验、企业微信用户 owner 校验、限流和本地审计。当前缺口是页面仍要求从 URL 手工读取 `code`，没有符合企业微信官方要求的浏览器扫码入口；会话组件也缺少外部浏览器预览和模板错误回调。

本轮只追求最快看到真实企业微信会话展示组件：普通桌面浏览器首屏展示企业微信扫码面板，扫码成功后在当前标签页进入现有消息中心，随后点击企业微信渠道加载真实 `ww-open-message`。

企业微信只是 WhatsApp、邮件和电话的并列消息渠道，不是产品主线。本设计提供的是会话组件的短时授权闭环，不是完整 CRM 登录体系。

## 2. 官方约束

实现以企业微信官方文档为准：

- [会话展示组件](https://developer.work.weixin.qq.com/document/path/100049)
- [Web 登录组件](https://developer.work.weixin.qq.com/document/path/98152)
- [创建企业微信登录面板](https://developer.work.weixin.qq.com/document/path/98622)
- [`ww.register`](https://developer.work.weixin.qq.com/document/path/94325)

必须满足以下约束：

- 普通浏览器使用会话展示组件时，必须使用企业微信 Web 登录组件，不能自行构造登录链接。
- 登录面板使用 `@wecom/jssdk >= 2.3.2`；本项目继续使用当前 `2.3.4`。
- 登录 API 使用 `ww.createWWLoginPanel()`，并设置 `login_type: 'ServiceApp'`、登录授权 SuiteID 作为 `appid`、`redirect_type: 'callback'`，不发送 `agentid`。
- `onLoginSuccess({ code })` 在当前页面返回临时 code，不进行 OAuth callback 页面跳转。
- 登录面板所在域名与会话展示组件所在域名必须完全一致。
- 会话展示组件必须运行在 top frame，不能嵌套在业务 iframe 中。
- 桌面系统浏览器创建登录面板前不要求调用 `ww.register()`；打开会话组件时再完成 `ww.register()`、`ww.initOpenData()` 和 OpenDataFrame 初始化。
- 页面使用 `ww-open-message` 前必须加载 `https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js`。
- `jsApiList` 必须包含 `wwapp.invokeJsApiByCallInfo`。
- 外部浏览器必须通过 `handleModal({ modalUrl, modalSize })` 打开图片、视频和聊天记录详情预览。
- 模板组件错误通过 `binderror` 和 OpenDataFrame `methods` 回调处理，不能只依赖顶层 `error`。
- `42006`、`42003`、`40029` 和 `Missing open sid` 按登录态失效处理，清除当前页面短时授权并要求重新扫码。

## 3. 目标

- 消息中心登录页作为浏览器首屏，直接展示企业微信二维码登录面板。
- 扫码成功后在同一标签页显示现有三栏消息中心。
- code、state、`viewerAuthToken`、ticket、签名和 `secretKey` 不进入地址栏。
- 页面复用一次 code 交换得到的短时 `viewerAuthToken` 打开企业微信 viewer，不重复交换 code。
- 保留现有 viewer owner 校验、消息上限、一次性读取、限流、审计和 SDK 超时。
- 补齐外部浏览器 `handleModal`、模板 `binderror` 和登录态失效处理。
- 使用 JDK 17 构建和运行。

## 4. 非目标

- 不实现用户名密码登录、PostgreSQL 用户 session、角色或完整 CSRF 体系。
- 不实现企业微信身份持久绑定、首次自动开户、账号合并、解绑或换绑。
- 不新增数据库表或数据库迁移。
- 不实现“记住登录”；页面刷新、短时 token 过期或服务重启后重新扫码。
- 不提供多企业选择器或多租户路由；代开发 suite 授权和单一目标企业安装记录由新的授权安装设计负责。
- 不修改联系人、消息、权限或企业微信会话内容的数据真相。
- 不把短时 viewer 授权描述为完整生产登录或消息中心全局鉴权。

## 5. 推荐方案

页面异步加载企业微信 JSSDK 后调用：

```javascript
ww.createWWLoginPanel({
  el: '#wwLoginPanel',
  params: {
    login_type: 'ServiceApp',
    appid: loginSuiteId,
    redirect_uri: redirectUri,
    state,
    redirect_type: 'callback',
    panel_size: 'small',
    lang: 'zh'
  },
  onCheckWeComLogin({ isWeComLogin }) {},
  onLoginSuccess({ code }) {},
  onLoginFail({ errCode, errMsg }) {}
});
```

后端提供一次性登录 attempt。页面先取得服务端生成的 `state` 和只读登录配置，再把官方回调返回的 `code` 与同一个 `state` 交给后端交换。交换成功后，页面只在 JavaScript 内存中保存 `wecomUserId`、`viewerAuthToken` 和到期时间，并隐藏登录层、显示现有消息中心。

该方案没有构造登录 URL、OAuth callback、handoff cookie 或第二次 complete 请求，调用链最短，并符合官方 Web 登录组件的 callback 模式。

## 6. Owner 与合同

### 6.1 `WeComLoginAttemptService`

新增小型服务作为扫码 attempt 的唯一 owner：

- 生成安全随机、不可预测的 state。
- 保存和原子消费短时 attempt。
- 默认 TTL 5 分钟，最多 256 条。
- 创建前清理过期记录；清理后仍满则结构化返回 429。
- attempt 只保存验证所需的随机 state 和到期时间，不保存 code、token、签名或 `secretKey`。
- 服务重启后未完成 attempt 失效，用户重新扫码。

### 6.2 `WeComViewerService`

继续作为以下语义的唯一 owner：

- 企业微信临时 code 交换。
- 短时 `viewerAuthToken`。
- 企业微信用户 owner 校验。
- viewer session 创建、读取和一次性客户端错误上报。
- 每用户限流和 viewer 审计。

登录 attempt service 不复制 code 交换和 viewer 权限逻辑。

### 6.3 双认证上下文

浏览器登录授权 Suite 与代开发 Suite 是两个独立 owner。登录授权由 `WECOM_LOGIN_SUITE_ID/WECOM_LOGIN_SUITE_SECRET` 唯一拥有；会话展示及专区能力仍由 `WECOM_SUITE_ID/WECOM_SUITE_SECRET`、目标企业 active 安装记录和 `permanent_code` 拥有。代开发授权返回的 `permanent_code` 即该代开发应用的 Secret，后端必须以安装企业 `authCorpId` 和该 Secret 调用自建应用 `/cgi-bin/gettoken`，不得调用第三方应用 `/cgi-bin/service/get_corp_token`。两项登录 Suite 配置必须同时存在，部分或缺失配置结构化拒绝，不保留 CorpApp 回退。两套 Suite 的 ticket/token 按 SuiteID 隔离缓存。

登录 attempt 的安装绑定仍由 `WECOM_LOGIN_AUTH_CORP_ID` 选择。二维码投影使用 `ServiceApp + WECOM_LOGIN_SUITE_ID`，换码走登录 Suite 的 `suite_access_token` 与官方 `service/auth/getuserinfo3rd`。返回的 `corpid` 必须与 attempt 绑定的 active 安装企业一致；绑定、viewer session、JS-SDK 签名和会话展示均不接受浏览器传入的企业标识覆盖。

### 6.4 HTTP 合同

新增：

```text
POST /api/v1/wecom/login/attempts
```

成功响应：

```json
{
  "loginType": "ServiceApp",
  "appId": "ww登录授权SuiteID",
  "redirectUri": "https://crm.example.com/",
  "state": "安全随机值",
  "expiresIn": 300
}
```

现有 exchange 请求增加必填 `state`：

```text
POST /api/v1/wecom/login/exchange
```

```json
{
  "code": "企业微信回调的临时 code",
  "state": "创建 attempt 时返回的 state"
}
```

后端必须先原子消费 state，再调用 `WeComViewerService.exchangeLoginCode(code)`。缺失、过期、伪造或重复 state 均返回结构化 400/403，不能调用企业微信 code 交换接口。

成功响应继续使用现有合同：

```json
{
  "wecomUserId": "zhangsan",
  "viewerAuthToken": "短时随机 token",
  "expiresIn": 300
}
```

OpenAPI 增加 login attempt operation，并同步 exchange 的 `state` 字段；HTML 页面不计入 API operation。

## 7. 配置

新增显式配置：

```text
WECOM_LOGIN_REDIRECT_URI=https://crm.example.com/
WECOM_LOGIN_ATTEMPT_TTL_SECONDS=300
WECOM_LOGIN_MAX_PENDING=256
```

- `WECOM_LOGIN_REDIRECT_URI` 必须是绝对 HTTPS URL；只允许本地开发显式使用 `http://localhost`。
- redirect URI 的 origin 必须存在于 `WECOM_ALLOWED_JSAPI_ORIGINS`。
- redirect URI 的域名必须与实际承载登录面板和会话组件的页面域名完全一致。
- `corpId` 和 `agentId` 只从服务端选定的 active 代开发授权安装记录读取；redirect URI 只从服务端配置读取，前端不能覆盖。
- 真实扫码成功态需要在企业微信管理后台配置可信域名、回调域名，并为应用授权“使用会话展示组件”。

## 8. 端到端流程

1. 浏览器访问消息中心根页面，先显示登录层，不请求联系人或消息数据。
2. 页面异步加载 `wecom-jssdk-2.3.4.js`；10 秒超时或配置缺失时显示可重试错误态。
3. 页面调用 `POST /api/v1/wecom/login/attempts`，取得 corpId、agentId、redirectUri、一次性 state 和有效期。
4. 页面在 top frame 中调用 `ww.createWWLoginPanel()`，直接显示官方二维码面板。
5. 用户使用企业微信扫码确认，官方组件通过 `onLoginSuccess({ code })` 在当前页面返回临时 code。
6. 页面调用 login exchange，提交 code 和内存中的 state。
7. 后端原子消费 state，再交换 code，返回 `wecomUserId + viewerAuthToken + expiresIn`。
8. 页面把授权结果只保存在 JavaScript 内存中，销毁登录面板，显示现有消息中心并开始加载联系人。
9. 用户选择联系人并点击企业微信渠道。页面复用内存 token 创建和读取 viewer session。
10. 页面按官方顺序执行 `ww.register()`、`ww.initOpenData()`、`ww.createOpenDataFrameFactory()`，并挂载单个 OpenDataFrame。
11. 页面通过 `ww-open-message` 展示真实消息，通过 `handleModal` 在同页预览详情，通过 `binderror` 上报组件错误。
12. 页面刷新、token 到期或出现官方登录态失效错误后，清除内存 token、隐藏消息中心并重新创建扫码 attempt。

## 9. 页面状态

登录层包含产品名称、官方二维码面板、重试按钮和简短状态区域，支持：

- SDK 加载中。
- 二维码加载中。
- 等待扫码。
- 正在交换授权。
- 配置缺失。
- SDK 超时或加载失败。
- 扫码取消或授权失败。
- attempt 过期或已消费，可重新生成二维码。

登录成功后显示现有消息中心。企业微信 viewer 支持：

- SDK 和 `jwxwork` 加载态。
- viewer session 加载态。
- 无消息态。
- 403、429 和 10 秒超时错误态。
- 模板组件错误态。
- 登录态失效并返回扫码层。
- 图片、视频和聊天记录详情预览层。

## 10. 安全与审计

- state 使用密码学安全随机数，短时、一次性、原子消费并有容量上限。
- code、state、viewer token、ticket、签名、`secretKey` 和登录配置 URL 不进入普通日志或审计详情。
- 登录 attempt 创建、成功、拒绝、过期、交换失败和限流写入现有有界 viewer audit adapter；审计失败继续 fail closed。
- `viewerAuthToken` 只保存在当前页面 JavaScript 内存，不写入 URL、cookie、localStorage 或 sessionStorage。
- 失败或登录态失效时先清除内存 token，再显示重新扫码入口。
- 联系人可读校验和企业微信用户 owner 校验保持在服务端；扫码成功不代表可以读取任意会话。
- 本闭环不能保护尚未接入认证的其他消息中心 API，因此不得宣称完成了生产级全局登录授权。

## 11. 验收

- 普通访问根页面只显示登录首屏；登录前不请求联系人和消息 API。
- 页面通过真实 `ww.createWWLoginPanel()` 展示二维码，不构造登录链接。
- 登录页与会话组件运行在同一域名、同一个 top frame。
- 缺失、伪造、过期和重复 state 均不能换取 viewer token。
- 扫码完成后地址栏不包含 code、state 或 viewer token，并在同一标签页进入消息中心。
- 刷新页面后内存 token 消失，重新显示扫码面板。
- 点击联系人和企业微信渠道后，使用真实 viewer session 渲染 `ww-open-message`。
- 外部浏览器能通过 `handleModal` 打开图片、视频和聊天记录详情。
- 模板 `binderror` 能进入结构化错误态，并只上报一次匹配当前 session/token 的组件错误事件。
- `42006`、`42003`、`40029` 和 `Missing open sid` 会清除授权并返回扫码层。
- viewer 继续通过 owner 校验、限流、一次性 session 和审计测试。
- JDK 17 下通过 Maven 单元测试、test-compile、集成测试入口、package、OpenAPI 校验和 `git diff --check`。
- 使用真实公网 HTTPS 域名和普通桌面浏览器完成扫码、登录、联系人选择、企业微信 tab、加载态、错误态、成功态和预览交互验收；桌面与移动 viewport 均保留真实截图。

## 12. 明确取舍

本轮选择官方 Web 登录面板加短时内存授权，不实现完整 CRM 账号体系和持久绑定。这是能最快闭合真实会话组件的实现，同时避免把临时 code 或 token 放入 URL。

完整账号登录、密码、持久 session、首次开户和企业微信绑定应作为独立后续设计；在它们实现前，本页面只能称为企业微信会话组件的扫码授权入口。
