# 企业微信代开发成员头像 OAuth2 授权验收记录

## 结果

企业微信 Web 登录与成员头像敏感授权已拆分。当前账户已绑定企业微信但没有已入库头像时，用户可以从账户面板启动独立的 `snsapi_privateinfo` OAuth2 授权；服务端仅在一次性 state、企业、安装版本和当前绑定成员全部一致，并取得 `user_ticket` 后读取成员详情并保存头像。

该实现不再把 Web 登录组件或 `/cgi-bin/user/get` 当作代开发成员头像的授权来源。常规资料同步返回空头像时保留现有资料，并记录 `WECOM_PROFILE_AVATAR_EMPTY`。

## 自动化验证

- 后端头像授权专项：`mvn -q -Dtest=WeComAvatarAuthorizationServiceTest,WeComAvatarAuthorizationControllerTest,WeComAuthorizationGatewaySecurityTest,WeComPartyProfileServiceTest,WeComLoginApplicationServiceTest,WeComLoginAttemptServiceTest,WeComAuthControllerSecurityTest test`
  - 通过。
- 前端源码与 UI 全量：`npm test`
  - 源码测试 29/29，UI 测试 282/282，均通过。
- 前端生产构建：`npm run build`
  - 通过。

后端全量：`mvn -q test` 共执行 1019 个测试，0 failures、10 errors、7 skipped。10 个错误均来自本机 Docker Desktop 未运行或不可用的 Testcontainers 集成测试；回环 HTTP 网关测试已在允许环境中通过。该结果不等同于后端全量通过，Docker 可用环境仍需重跑。

## 制品校验

- 后端 JAR：`demo/message-center-spring/backend/target/message-center.jar`
  - SHA-256：`516846c4181b198841229249a476af234211fb4eea9734aeb24173c012259b8f`
- 前端 ZIP：`demo/message-center-spring/frontend-dist-20260904-wecom-avatar-oauth-r2.zip`
  - SHA-256：`cf38d7999b8faee0ab107db70cccb20dce5e5340ff68b7cb092bae79992af7f8`
  - 已执行 `unzip -t`，入口为 `dist/index.html`。

## 浏览器验收

临时本地账户页面验证：

- 企业微信已绑定且当前头像来源为文字头像时，显示“授权企业微信头像”入口。
- 点击入口后普通浏览器展示二维码和“等待授权完成”状态，账户背景被正确遮罩，无重叠或横向溢出。
- 前端不会在 `PENDING` 期间刷新账户头像；只有后端返回 `SUCCEEDED` 才刷新绑定资料与账户资料。

## 服务器实机停止条件

代码与本地门禁通过不表示生产头像已取得。真实绑定成员扫码后，只有同时出现以下证据才可确认生产修复：

```text
event=wecom.avatar_authorization stage=PROFILE_PERSIST status=SUCCEEDED
GET /api/account/profile 返回 avatar.source=WECOM
wecom_parties.profile_status=READY
wecom_parties.avatar_url 非空
```

若失败，日志只应包含阶段、脱敏授权 ID 摘要和结构化错误码；不得记录 OAuth code、state、user_ticket、access token 或永久授权码。
