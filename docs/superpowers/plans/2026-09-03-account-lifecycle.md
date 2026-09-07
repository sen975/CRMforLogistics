# Account Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供自定义用户名注册、当前用户资料与头像维护、凭原密码改密，以及管理员用户角色和密码管理的完整闭环。

**Architecture:** 后端新增 `AccountService` 作为账户资料、密码、头像优先级和管理员变更的唯一 owner，controller 只映射 HTTP 和权限。数据库保存上传头像指针，MinIO 保存二进制；企业微信头像只读取当前绑定对应的已入库员工资料。前端 `useAuth` 持有当前账户投影，登录页、账户抽屉和管理员页面只消费该合同。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring Security、MyBatis-Plus、PostgreSQL/Flyway、MinIO、React 18、TypeScript、Ant Design、Vitest。

## Global Constraints

- 注册用户名 NFKC + trim 后为 3-32 位，仅 ASCII 字母、数字、`.`、`_`、`-`，首位必须为字母或数字，唯一性按小写归一化值。
- 昵称为 1-50 字符；密码为 8-72 字符且至少包含一个字母和一个数字。
- 新注册用户仅分配数据库中的 `agent` 角色。
- 头像只接受 JPEG/PNG，最大 2 MiB，宽高均不超过 4096；对象键为 `user-avatars/{userId}/{uuid}`。
- 头像投影优先级固定为企业微信已存头像、用户上传头像、昵称首字。
- 改密或管理员重置密码必须撤销目标用户全部旧会话；当前用户改密后签发替代 token。
- 管理员角色变更至少保留一个角色，且不能移除最后一名有效管理员。
- 不引入邮箱、手机或未登录找回密码，不改变企业微信消息、Topic 或联系人合同。

---

### Task 1: 数据库与账户基础合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V46__account_profile_avatar.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/UserEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/UserMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/RoleMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/SessionMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComPartyMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/account/AccountProfileSchemaContractTest.java`

**Interfaces:**
- Produces: `UserEntity.avatarObjectKey/avatarMimeType/avatarSizeBytes/avatarUpdatedAt`；用户、角色、会话及企业微信头像所需的受控 mapper 操作。

- [ ] **Step 1: 写失败的 schema 合同测试**

  读取 `V46__account_profile_avatar.sql`，断言四个头像字段、大小约束和对象键约束存在；运行 `mvn -Dtest=AccountProfileSchemaContractTest test`，预期因迁移不存在而失败。

- [ ] **Step 2: 添加迁移和实体字段**

  使用 `ALTER TABLE users ADD COLUMN IF NOT EXISTS` 增加头像字段，并增加 `avatar_size_bytes between 1 and 2097152` 及对象键前缀约束；在 `UserEntity` 添加完全对应的 Java 字段和 getter/setter。

- [ ] **Step 3: 添加 mapper 原子操作**

  `UserMapper` 增加资料更新、密码更新、头像指针替换/清除、分页列表；`RoleMapper` 增加角色全集、删除/替换所需查询和有效管理员计数；`SessionMapper` 增加 `revokeActiveByUserId(UUID, Instant)`；`WeComPartyMapper` 增加绑定员工已存头像查询。

- [ ] **Step 4: 运行合同测试**

  运行 `mvn -Dtest=AccountProfileSchemaContractTest test`，预期通过且无 warning。

### Task 2: 注册、校验与会话轮换

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/account/AccountCredentials.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/account/AccountException.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/account/AccountService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/RegisterRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ChangePasswordRequest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/AuthSessionService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/account/AccountServiceRegistrationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/account/AccountServicePasswordTest.java`

**Interfaces:**
- Produces: `AccountCredentials.normalizeUsername/validatePassword`；`AccountService.register(...)`；`AccountService.changePassword(...)`；`AuthSessionService.revokeAll(UUID)`。

- [ ] **Step 1: 写注册失败测试并确认红灯**

  覆盖合法注册、NFKC/大小写重复、非法用户名、弱密码和缺失 `agent` 角色；运行两个账户 service 测试，预期因类型不存在而失败。

- [ ] **Step 2: 实现共享校验器和事务注册**

  将用户名归一化和密码规则收敛进 `AccountCredentials`；`AccountService.register` 在同一事务中插入用户并分配唯一 `agent`，将数据库唯一冲突映射为 `USERNAME_ALREADY_EXISTS`。

- [ ] **Step 3: 写改密失败测试并实现会话轮换**

  覆盖错误原密码、密码复用、确认不一致、成功后全会话撤销；实现散列更新、`revokeAll` 和替代 token 签发。

- [ ] **Step 4: 接入公开注册与安全规则**

  `POST /api/auth/register` 返回现有 `LoginResponse` 并立即签发会话；仅将该精确路径加入 `permitAll`，其余账户 API 保持认证。

- [ ] **Step 5: 运行专项测试**

  运行 `mvn -Dtest='AccountServiceRegistrationTest,AccountServicePasswordTest' test`，预期全部通过。

### Task 3: 当前资料与头像

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/UpdateAccountProfileRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/AccountProfileResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AccountController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/account/AccountService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/account/AccountServiceProfileTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AccountControllerTest.java`

**Interfaces:**
- Produces: `GET/PATCH /api/account/profile`、`POST/GET/DELETE /api/account/avatar`、`PUT /api/account/password`；`AccountProfileResponse.Avatar(source, contentUrl, initial, revision)`。

- [ ] **Step 1: 写资料与头像优先级失败测试**

  覆盖 WECOM > UPLOAD > INITIAL、昵称首字、昵称更新和空企业微信头像回退；运行 `AccountServiceProfileTest`，预期因 API 缺失失败。

- [ ] **Step 2: 实现资料投影与更新**

  从认证主体 UUID 加载用户和角色，从绑定及 `wecom_parties` 读取已存头像；不调用上游；实现昵称校验和受控更新。

- [ ] **Step 3: 写头像验证失败测试并实现存储事务**

  以真实最小 JPEG/PNG fixture 覆盖 MIME、魔数、解码、2 MiB 和 4096 尺寸限制；新对象写入成功后更新指针，数据库失败删除新对象，成功后尽力删除旧对象。

- [ ] **Step 4: 实现认证头像内容响应**

  `GET /api/account/avatar/content` 返回流、真实 MIME、`Cache-Control: private`；没有上传头像返回 `USER_NOT_FOUND`/404，不暴露对象键。

- [ ] **Step 5: 运行专项测试**

  运行 `mvn -Dtest='AccountServiceProfileTest,AccountControllerTest' test`，预期全部通过。

### Task 4: 管理员用户管理

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/AdminReplaceRolesRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/AdminResetPasswordRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/AdminUserResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminUserController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/account/AccountService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/account/AccountServiceAdminTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminUserControllerSecurityTest.java`

**Interfaces:**
- Produces: `GET /api/admin/users?page&size`、`PUT /api/admin/users/{id}/roles`、`PUT /api/admin/users/{id}/password`、`GET /api/admin/roles`。

- [ ] **Step 1: 写管理员领域失败测试**

  覆盖分页上界、未知角色、空角色、最后管理员保护、角色原子替换、重置密码后会话撤销；运行专项测试并确认红灯。

- [ ] **Step 2: 实现服务层管理员复核与事务**

  每个管理操作同时验证调用者当前仍有 `admin` 角色；角色替换锁定目标用户，检查有效管理员计数后删除再插入；密码重置复用统一密码规则并撤销全部会话。

- [ ] **Step 3: 接入双层授权**

  SecurityConfig 对 `/api/admin/users/**` 要求 `ROLE_ADMIN`；controller 传入认证主体 UUID，服务层再次复核，不信任前端入口隐藏。

- [ ] **Step 4: 运行专项测试**

  运行 `mvn -Dtest='AccountServiceAdminTest,AdminUserControllerSecurityTest' test`，预期全部通过。

### Task 5: 前端账户生命周期

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/client.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useAuth.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/LoginPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AccountPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Create: `demo/message-center-spring/frontend/src/components/AccountAvatar.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/UserManagementPage.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/LoginPage.test.tsx`
- Test: `demo/message-center-spring/frontend/src/components/AccountPanel.test.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/UserManagementPage.test.tsx`
- Test: `demo/message-center-spring/frontend/src/hooks/useAuth.test.tsx`

**Interfaces:**
- Consumes: Task 2-4 HTTP 合同。
- Produces: `useAuth.profile/register/refreshProfile/replaceSession`，受认证 Blob 头像加载，注册表单、账户维护抽屉、管理员用户管理页。

- [ ] **Step 1: 写 API 与认证状态失败测试**

  覆盖注册后落盘 token、初始化后加载 profile、改密替换 token、401 清理账户资料，以及认证 Blob 请求；运行对应 Vitest，预期因接口缺失失败。

- [ ] **Step 2: 实现 API 类型和认证状态**

  增加严格请求/响应类型；`useAuth` 在会话建立后读取 profile，暴露注册和刷新；新 token 原子替换 localStorage 与 React 状态。

- [ ] **Step 3: 写并实现登录页注册模式**

  在账号密码 Tab 内使用分段切换登录/注册；注册字段做与服务端一致的基本提示，提交期间禁用，失败后清空密码字段。

- [ ] **Step 4: 写并实现账户抽屉**

  顶部显示统一头像投影、昵称和只读登录 ID；实现昵称、头像、密码三个表单并保留企业微信绑定区。上传头像用 multipart，读取上传头像用携带 Bearer 的 Blob/object URL 并在卸载时释放。

- [ ] **Step 5: 写并实现管理员页面**

  使用现有 Ant Design 表格与 Modal，支持分页、角色多选和密码重置；请求中禁用按钮，不显示内部 ID、散列或对象键。

- [ ] **Step 6: 接入菜单和路由**

  仅管理员菜单显示“用户管理”并导航 `/settings/users`；路由页面仍依赖服务端 403 作为真实授权边界。

- [ ] **Step 7: 运行前端专项测试**

  运行 `npm run test:ui -- LoginPage.test.tsx AccountPanel.test.tsx UserManagementPage.test.tsx useAuth.test.tsx AppLayout.test.tsx router.test.tsx`，预期全部通过且无未处理 Promise。

### Task 6: 文档、全量验证与制品

**Files:**
- Modify: `docs/superpowers/README.md`
- Create: `docs/superpowers/reviews/2026-09-03-account-lifecycle-verification.md`
- Output: `demo/message-center-spring/backend/target/message-center.jar`
- Output: `demo/message-center-spring/frontend-dist-20260903-account-lifecycle-r1.zip`

**Interfaces:**
- Consumes: Task 1-5 的最终代码和测试。
- Produces: 可审计验证记录、后端 target Jar、前端 dist ZIP、SHA-256 与部署命令。

- [ ] **Step 1: 运行后端专项及全量测试**

  先运行账户专项测试，再运行 `mvn test`；任何失败都按当前代码证据修复，不能将 warning 当成功。

- [ ] **Step 2: 运行前端专项及全量测试**

  运行账户专项后执行 `npm test`；检查测试数、失败数和未处理异常。

- [ ] **Step 3: 构建生产制品**

  在前端运行 `npm run build`；在后端运行 `mvn -Pproduction clean package`，确认 Jar 位于 `backend/target/message-center.jar`；将前端 `dist/` 目录打入版本化 ZIP。

- [ ] **Step 4: 校验制品**

  运行 `unzip -t`、`jar tf`、`shasum -a 256`，确认 ZIP 以 `dist/index.html` 为入口、Jar 包含 V46 和账户 controller 类。

- [ ] **Step 5: 回写验收记录**

  记录本轮实际命令、通过/失败数量、不能在本机完成的服务器验证、制品绝对路径、校验和以及不触碰的既有 WIP。

## Self-Review

- Spec coverage：注册、当前资料、头像三层优先级、原密码改密、全会话撤销、管理员角色/重置密码、最后管理员保护、前端三处消费和发布制品均有对应任务。
- Placeholder scan：计划无 TBD/TODO/“类似前项”等占位语句；每项均给出准确路径、接口、行为和验证命令。
- Type consistency：统一使用 `AccountProfileResponse`、`AccountService`、`AuthSessionService.revokeAll(UUID)`、`/api/account/*` 和 `/api/admin/users/*`，前后任务命名一致。
