# 账户生命周期与用户资料验收记录

## 结果

账户生命周期主链路已实现：自定义登录 ID 注册、默认 `agent` 角色、当前用户昵称/头像/密码维护、企业微信头像优先级、管理员用户列表/角色调整/密码重置，以及改密后的全会话撤销。

## 自动化验证

- 后端账户专项：`mvn -Dtest='GlobalExceptionHandlerAccountUploadTest,Account*Test,AuthControllerSecurityTest' test`
  - 23 tests，0 failures，0 errors，0 skipped。
  - 包含 multipart 层超限时仍返回 `AVATAR_INVALID` 的账户头像错误合同。
- 前端源码合同：`npm run test:source`
  - 29 tests，全部通过。
- 前端 UI 全量：`npm run test:ui`
  - 54 files、272 tests，全部通过。
- 前端生产构建：`npm run build`
  - TypeScript 与 Vite 构建成功，3209 modules transformed。
- 后端生产构建：`mvn -Pproduction -DskipTests package`
  - 构建成功，输出 `backend/target/message-center.jar`。
  - 构建仍报告既有 Topic `AiTopicService` 及渠道账号测试的 unchecked 编译警告；该警告属于本轮账户功能之外的已有 WIP，本轮不扩大修改。

后端全量 `mvn test` 实际执行 993 tests，0 failures、23 errors、7 skipped。23 个 error 均发生在沙箱禁止 Docker socket 或回环测试服务器访问的 Testcontainers/本地 HTTP 测试；授权重跑又因审批服务超时未能开始。该结果不作为全量通过，服务器或允许 Docker/socket 的本机仍需重跑 `mvn test`。

## 浏览器验收

Browser 插件验证 URL `http://127.0.0.1:5174/login`：

- 桌面视口：账号密码入口可切换到注册，登录 ID、昵称、密码、确认密码和“注册并登录”完整可见。
- 移动视口 390×844：`body.scrollWidth = body.clientWidth = 390`，无横向溢出，提交按钮在首屏可见。
- 页面标题为“统一消息中心”，DOM 非空，无 Vite/React 错误覆盖；移动视口控制台无 error。
- 未使用真实服务器账户执行注册提交，避免产生测试账号；账户抽屉和管理员页由 Vitest 覆盖，部署环境仍需用真实管理员做 API/MinIO 实机验收。
- 交付时重新启动的本地预览地址为 `http://127.0.0.1:5175/`，`/login` 返回 HTTP 200；5174 端口已被其他本机进程占用。

## 制品校验

- 前端：`demo/message-center-spring/frontend-dist-20260903-account-lifecycle-r3.zip`
  - SHA-256：`9d25c2fca58b1d71b12475dc18f92f97698ba6bf33a6b4e575c7e399da45937c`
- 后端：`demo/message-center-spring/backend/target/message-center.jar`
  - SHA-256：`6d6281818e2eebc3060ae05dcd4d540d0b813a63f37b290b56a6c61536072dee`
- ZIP 通过 `unzip -t`，入口为 `dist/index.html`。
- Jar 已确认包含 `V46__account_profile_avatar.sql`、`AccountController.class` 和 `AdminUserController.class`。

## 工作区边界

工作区原有企业微信、Topic、邮件、联系人和既有 ZIP 等大量未提交改动均被保留。本轮未回滚、清理或 stage 这些改动；构建出的 Jar/ZIP 会按用户此前要求包含当前工作区的全部改动。
