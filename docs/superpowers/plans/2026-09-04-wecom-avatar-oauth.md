# 企业微信代开发成员头像 OAuth2 授权 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留企业微信 Web 登录，同时新增独立的 `snsapi_privateinfo` 二维码/企业微信内授权流程，严格校验当前绑定成员后保存本人头像。

**Architecture:** `WeComAvatarAuthorizationService` 作为一次性授权状态、OAuth URL、回调身份校验和终态查询的唯一 owner；专用 Controller 暴露两个认证接口和一个公开回调。前端账户企业微信区域只消费授权投影，用 Ant Design `QRCode` 展示 URL，并在终态成功后刷新账号资料。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring Security、JUnit 5、Mockito、React 18、TypeScript、TanStack Query、Ant Design 5、Vitest。

## Global Constraints

- Web 登录组件与敏感头像授权是两个独立流程，`createWWLoginPanel` 不得接收 `scope`。
- OAuth URL 必须固定使用 `scope=snsapi_privateinfo`，且必须包含当前安装 AgentID。
- 回调必须验证 state、安装版本、企业和绑定成员，缺少 `user_ticket` 时不得降级到 `/cgi-bin/user/get`。
- code、state、`user_ticket`、access token 和永久授权码不得写入应用日志或返回普通前端 API。
- attempt 五分钟过期、一次性消费、有界存储；页面每两秒查询且在关闭或终态时停止。
- 前端只在 `SUCCEEDED` 后刷新企业微信绑定和账户头像。
- 不新增数据库表，不引入二维码依赖，复用 Ant Design `QRCode`。
- 工作区已有大量用户 WIP；禁止回滚、格式化或提交无关文件。

---

### Task 1: 移除错误的 Web 登录 scope 合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginAttemptService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/LocalWeComDevelopmentService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginAttemptServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComAuthControllerSecurityTest.java`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.test.tsx`

**Interfaces:**
- Consumes: 现有 `LoginAttemptResponse(loginType, appId, agentId, redirectUri, state, expiresIn)`。
- Produces: Web 登录组件只接收官方文档列出的登录参数，不再表达敏感资料授权。

- [ ] **Step 1: 修改前端测试，拒绝向 Web 登录组件传 scope**

```ts
expect(options.params).not.toHaveProperty('scope');
```

- [ ] **Step 2: 运行前端测试并确认当前错误实现失败**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/wecom/WeComLoginPanel.test.tsx --run`

Expected: FAIL，实际参数仍包含 `scope`。

- [ ] **Step 3: 删除前后端 Web 登录 scope 字段**

将 `WeComLoginAttempt.scope`、`WeComLoginPanelOptions.params.scope`、组件参数和 Java record 第七参数全部删除；恢复本地开发服务与安全测试的六参数构造器。

- [ ] **Step 4: 运行专项测试**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/wecom/WeComLoginPanel.test.tsx --run`

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComLoginAttemptServiceTest,WeComAuthControllerSecurityTest test`

Expected: 全部 PASS，且无 warning。

### Task 2: 建立有界 OAuth attempt owner

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAvatarAuthorizationService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAvatarAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: `WeComUserBindingService.requireByUserId(UUID)`、`WeComInstallationService.resolveInstallation(String,String)`、`AppConfig.wecomLoginRedirectUri()`。
- Produces:

```java
AuthorizationAttempt create(UUID userId, String remoteAddress);
AuthorizationStatus status(UUID userId, String authorizationId);
CallbackResult complete(String code, String state);

record AuthorizationAttempt(String authorizationId, String authorizationUrl,
                            Status status, int expiresIn) {}
record AuthorizationStatus(String authorizationId, Status status, String errorCode) {}
enum Status { PENDING, SUCCEEDED, FAILED, EXPIRED }
```

- [ ] **Step 1: 写 URL 与所有权失败测试**

覆盖：URL host/path 固定、`scope=snsapi_privateinfo`、AgentID、URL 编码 redirect URI、高熵 state；未绑定账号拒绝；他人无法查询；过期返回 `EXPIRED`；容量和每用户未完成 attempt 有上界。

- [ ] **Step 2: 运行测试并确认类不存在**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationServiceTest test`

Expected: FAIL，`WeComAvatarAuthorizationService` 尚未定义。

- [ ] **Step 3: 实现最小 attempt 状态机**

使用构造器注入 `Clock` 和 `NonceSource` 的测试工厂；生产构造器使用 `Clock.systemUTC()` 与 128 bit 以上随机 state。用同步、有界 `LinkedHashMap` 保存 attempt，授权 ID 与 state 分离，五分钟过期，回调地址从配置 URI 的 scheme/authority 派生，不读取请求头。

- [ ] **Step 4: 运行 owner 测试**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationServiceTest test`

Expected: PASS。

### Task 3: 实现回调身份校验与头像持久化

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAvatarAuthorizationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComPartyProfileService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAvatarAuthorizationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGatewaySecurityTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComPartyProfileServiceTest.java`

**Interfaces:**
- Consumes: `gateway.getLoginIdentity(authCorpId, accessToken, code, timeout)`、`gateway.getUserDetail(accessToken,userTicket,timeout)`、`profiles.syncAuthorizedEmployee(...)`。
- Produces: 原子、幂等的 `complete(code,state)`；终态保留阶段错误码，不泄露敏感值。

- [ ] **Step 1: 写回调失败与成功测试**

覆盖：state 缺失/过期/重放、安装版本变化、返回成员不一致、ticket 为空、详情成员不一致、头像为空、上游失败、成功写入头像；验证 ticket 为空绝不调用 `getUserDetail` 或 `syncEmployee`。

- [ ] **Step 2: 运行测试并确认回调行为缺失**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationServiceTest test`

Expected: FAIL，`complete` 尚未完成合同。

- [ ] **Step 3: 实现回调状态迁移与结构化日志**

`PENDING -> SUCCEEDED|FAILED` 只允许一次；异常映射为设计文档定义的错误码。日志只写 authorization ID 摘要、阶段、安装 ID 和错误码。详情响应中的 user ID 必须与 attempt 预期 user ID 相等；`ProfileResult.avatarUrl()` 为空时写 `WECOM_AVATAR_AUTH_PROFILE_EMPTY`。

- [ ] **Step 4: 运行授权与资料专项测试**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationServiceTest,WeComAuthorizationGatewaySecurityTest,WeComPartyProfileServiceTest test`

Expected: PASS；网关本地 HTTP 测试若受沙箱端口限制，则按批准权限原命令重跑。

### Task 4: 接入认证 API 与公开回调安全边界

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComAvatarAuthorizationController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComAvatarAuthorizationControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/WeComModuleIsolationTest.java`

**Interfaces:**
- Consumes: Task 2/3 的 `create/status/complete`。
- Produces:

```text
POST /api/account/wecom-avatar/authorizations
GET  /api/account/wecom-avatar/authorizations/{authorizationId}
GET  /api/public/wecom-avatar/oauth/callback?code=&state=
```

- [ ] **Step 1: 写 Controller 与 Security 失败测试**

验证创建/查询未登录返回 401；公开回调无需 CRM token；未知字段/超长 code/state 返回结构化 400；成功回调响应 `text/html;charset=UTF-8`，且正文不含 code、state、ticket、成员 ID。

- [ ] **Step 2: 运行测试并确认路由不存在**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationControllerTest,WeComModuleIsolationTest test`

Expected: FAIL，目标路由/bean 尚不存在。

- [ ] **Step 3: 实现薄 Controller 与精确白名单**

只将 `GET /api/public/wecom-avatar/oauth/callback` 放入公开白名单；创建/查询继续依赖当前认证 UUID。HTML 结果页使用固定模板与固定 CSP，不插值任何上游内容。

- [ ] **Step 4: 运行 API 安全测试**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationControllerTest,WeComModuleIsolationTest test`

Expected: PASS。

### Task 5: 实现前端二维码授权与终态轮询

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComAvatarAuthorizationModal.tsx`
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComAvatarAuthorizationModal.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AccountPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AccountPanel.test.tsx`

**Interfaces:**
- Consumes: Task 4 API 与 `useAuth().profile/refreshProfile`。
- Produces: `WeComAvatarAuthorizationModal({open,onClose,onSucceeded})`，以及仅在绑定且头像非 WECOM 时出现的授权入口。

- [ ] **Step 1: 写 Modal 与入口失败测试**

覆盖：打开时创建 attempt；桌面展示 `QRCode`；企业微信 UA 展示直达按钮；每两秒查询；`PENDING` 不刷新头像；`SUCCEEDED` 停止查询并调用 `onSucceeded`；失败/过期允许重试；关闭后不再查询；已有 WECOM 头像不显示入口。

- [ ] **Step 2: 运行测试并确认组件不存在**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/wecom/WeComAvatarAuthorizationModal.test.tsx src/components/wecom/WeComBindingPanel.test.tsx src/components/AccountPanel.test.tsx --run`

Expected: FAIL，Modal 和 API 合同尚不存在。

- [ ] **Step 3: 实现类型、API、Modal 与账户入口**

新增：

```ts
type WeComAvatarAuthorizationStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
interface WeComAvatarAuthorizationAttempt {
  authorizationId: string;
  authorizationUrl: string;
  status: WeComAvatarAuthorizationStatus;
  expiresIn: number;
}
interface WeComAvatarAuthorizationProjection {
  authorizationId: string;
  status: WeComAvatarAuthorizationStatus;
  errorCode: string | null;
}
```

TanStack Query 的 `refetchInterval` 仅在 `PENDING` 时返回 `2000`，否则返回 `false`；Modal unmount 自动取消后续更新。成功回调依次执行绑定 refetch 与 `refreshProfile()`。

- [ ] **Step 4: 运行前端专项测试与构建**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/wecom/WeComAvatarAuthorizationModal.test.tsx src/components/wecom/WeComBindingPanel.test.tsx src/components/AccountPanel.test.tsx src/components/wecom/WeComLoginPanel.test.tsx --run`

Run: `cd demo/message-center-spring/frontend && npm run build`

Expected: 测试与构建 PASS，无 TypeScript/Vite warning。

### Task 6: 文档、全量门禁与制品

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-account-lifecycle-design.md`
- Modify: `docs/superpowers/README.md`
- Create: `docs/superpowers/reviews/2026-09-04-wecom-avatar-oauth-verification.md`
- Generate: `demo/message-center-spring/backend/target/message-center.jar`
- Generate: `demo/frontend-dist-20260904-wecom-avatar-oauth-r2.zip`

**Interfaces:**
- Consumes: Tasks 1-5 的最终合同与测试证据。
- Produces: 当前设计索引、验收记录、可校验后端 JAR 与前端 ZIP。

- [ ] **Step 1: 同步账户头像真源**

将账户设计中企业微信头像来源补充为“必须经独立 `snsapi_privateinfo` OAuth 授权；Web 登录和 `user/get` 不构成敏感头像授权”。

- [ ] **Step 2: 运行专项与全量门禁**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAvatarAuthorizationServiceTest,WeComAvatarAuthorizationControllerTest,WeComAuthorizationGatewaySecurityTest,WeComPartyProfileServiceTest,WeComLoginApplicationServiceTest,WeComLoginAttemptServiceTest,WeComAuthControllerSecurityTest test`

Run: `cd demo/message-center-spring/backend && mvn -q test`

Run: `cd demo/message-center-spring/frontend && npm test`

Run: `cd demo/message-center-spring/frontend && npm run build`

Expected: 全部退出码 0；环境阻断必须写入验收记录，不得伪报通过。

- [ ] **Step 3: 打包并校验制品**

Run: `cd demo/message-center-spring/backend && mvn -q -DskipTests package && sha256sum target/message-center.jar`

Run: `cd demo/message-center-spring/frontend && rm -f ../../frontend-dist-20260904-wecom-avatar-oauth-r2.zip && zip -qr ../../frontend-dist-20260904-wecom-avatar-oauth-r2.zip dist && unzip -t ../../frontend-dist-20260904-wecom-avatar-oauth-r2.zip && sha256sum ../../frontend-dist-20260904-wecom-avatar-oauth-r2.zip`

Expected: JAR 存在于 `backend/target`；ZIP 根目录包含 `dist/index.html`；校验值写入验收记录。

- [ ] **Step 4: 服务器实机停止条件**

部署后由真实绑定员工扫码；只有同时满足以下证据才声明生产修复：

```text
event=wecom.avatar_authorization stage=PROFILE_PERSIST status=SUCCEEDED
avatar.source=WECOM
wecom_parties.profile_status=READY
wecom_parties.avatar_url 非空
```

未取得实机扫码证据时，交付状态只能写“代码与本地门禁完成，生产 OAuth 验收待执行”。

