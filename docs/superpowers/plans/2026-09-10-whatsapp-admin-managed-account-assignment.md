# WhatsApp 管理员配置与销售账号分配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 停用 CAMS/Meta 自助内嵌注册，由管理员同步 CAMS 已有号码并将单个号码分配、收回或转交给一名销售，同时保持历史消息作者和只读访问不变。

**Architecture:** `channel_accounts` 保持 provider 电话的稳定身份，`owner_user_id` 只表示当前发送归属；历史可见性继续由会话分配和 `conversation_access_grants` 负责。管理员同步只消费 CAMS 的只读号码事实，分配事务以账号 `version` 做并发控制；同步发送锁定账号，异步广播保存账号版本并在 provider 调用前 fencing。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis Plus、PostgreSQL/Flyway、JUnit 5、Mockito、React 18、TypeScript、TanStack Query、Ant Design、Vitest。

## Global Constraints

- 当前产品真源是 `docs/superpowers/specs/2026-09-10-whatsapp-admin-managed-account-assignment-design.md`。
- 不调用 `IsvGetAppId`、`GetPermissionByCode`、`ChatappBindWaba`，不打开 Meta Embedded Signup。
- 一个有效 WhatsApp 电话同一时刻最多分配给一名销售；一个销售最多拥有一个有效 WhatsApp 电话。
- 收回和转交不得删除、复制、改挂历史联系人、会话、消息、附件、模板或 CAMS 资源。
- 原销售保留既有会话只读访问；新销售获得账号全部既有会话访问并接管未来新会话。
- 密钥、完整号码、Meta token、授权 code 和验证码不得进入 API 投影或日志。
- 保留用户当前 WIP；不执行回滚，不使用 `git add .`，本计划执行期间不提交 Git。
- 每个 Task 只跑专项测试和编译；最终 Task 才运行全量回归。

---

### Task 1：分配审计与会话授权数据库合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V66__whatsapp_admin_account_assignment.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAccountAssignmentAuditMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAdminAssignmentSchemaContractTest.java`

**Interfaces:**
- Produces: `ChannelAccountMapper.findWhatsAppByIdForUpdate(UUID)`、`assignWhatsAppOwner(UUID,UUID,long)`、`reclaimWhatsAppOwner(UUID,long)`。
- Produces: `ConversationMapper.grantAccountHistory(UUID accountId, UUID userId, UUID grantedBy)`，对已有活跃 grant 幂等。
- Produces: 审计动作 `ASSIGN|RECLAIM|TRANSFER`。

- [x] **Step 1：写失败 schema/SQL 合同测试。**

  断言 V66 将审计 CHECK 约束改为三个规范动作，回填现有账号初始分配审计，并为当前 owner 的账号会话补齐 grant；断言 mapper 更新同时比较 `version`，且收回只清空 owner、不清空凭据、不禁用账号。

- [x] **Step 2：运行红灯。**

  Run: `mvn -q -Dtest=WhatsAppAdminAssignmentSchemaContractTest test`
  Expected: FAIL，V66 和新 mapper 合同尚不存在。

- [x] **Step 3：实现迁移与原子 mapper。**

  V66 使用 `ALTER TABLE ... DROP CONSTRAINT` 后重建动作约束；历史 `DISABLE` 映射为 `RECLAIM`。初始审计只为可证明 owner 的 WhatsApp 账号写入，grant 通过 `(conversation_id,user_id) WHERE revoked_at IS NULL` 唯一索引幂等。

- [x] **Step 4：运行绿灯与编译。**

  Run: `mvn -q -Dtest=WhatsAppAdminAssignmentSchemaContractTest,ChannelAccountOwnerIsolationTest test`
  Run: `mvn -q -DskipTests compile`
  Expected: PASS。

### Task 2：管理员 CAMS 已有号码只读同步

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppOnboardingGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AliyunWhatsAppOnboardingGateway.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AliyunWhatsAppOnboardingGatewayTest.java`

**Interfaces:**
- Produces: `WhatsAppOnboardingGateway.syncConfiguredPhoneNumbers()`，只调用 `ChatappSyncPhoneNumber` 和 `QueryChatappPhoneNumbers`。
- Produces: `AdminWhatsAppAccountSyncService.sync(UUID actorId)`，返回导入数、刷新数、不可发送数和脱敏账号投影。
- Consumes: `AppConfig.custSpaceId()` 与后端 AK/SK；不要求 WABA Embedded Signup 身份状态。

- [x] **Step 1：写失败 gateway 与同步服务测试。**

  覆盖：只读同步不调用三项内嵌注册 API；仅导入 `ACTIVE+VERIFIED` 号码；号码按标准化值幂等；已存在记录保持稳定 ID/owner；上游消失只标记 provider 不可用；投影只返回末四位。

- [x] **Step 2：运行红灯。**

  Run: `mvn -q -Dtest=AdminWhatsAppAccountSyncServiceTest,AliyunWhatsAppOnboardingGatewayTest test`
  Expected: FAIL，新接口和服务不存在。

- [x] **Step 3：实现配置 scope 同步与幂等 upsert。**

  Gateway 用 `CAMS_CUST_SPACE_ID` 和全局后端凭据建立临时 scope，只返回规范化 provider facts。Service 以号码唯一键 upsert，未分配账号保持 `owner_user_id=null`，不复制 AK/SK 到浏览器投影。

- [x] **Step 4：运行绿灯与编译。**

  Run: `mvn -q -Dtest=AdminWhatsAppAccountSyncServiceTest,AliyunWhatsAppOnboardingGatewayTest test`
  Run: `mvn -q -DskipTests compile`
  Expected: PASS。

### Task 3：管理员分配、收回和转交状态机/API

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/UserMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAccountAssignmentAuditMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberControllerTest.java`

**Interfaces:**
- Produces: `GET/POST /api/admin/whatsapp/accounts/**`，请求显式携带 `expectedVersion`。
- Produces: `assign` 仅接受未分配账号，`reclaim` 仅接受已分配账号，`transfer` 原子完成旧 owner 到新 owner。
- Consumes: Task 1 的账号锁、版本更新、会话 grant 和审计 mapper。

- [x] **Step 1：写失败状态机和权限测试。**

  覆盖无 admin、目标用户不存在/禁用、目标已有 WhatsApp 账号、旧版本、重复请求、分配/转交历史授权、原 owner grant 保留、收回不清密钥、审计失败事务回滚。

- [x] **Step 2：运行红灯。**

  Run: `mvn -q -Dtest=AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppPhoneNumberControllerTest test`
  Expected: FAIL，当前服务只有无版本 `assign/disable`。

- [x] **Step 3：实现状态机和规范路由。**

  Controller 切换到 `/api/admin/whatsapp/accounts`；Service 锁账号、校验目标用户、owner 和 expectedVersion，执行 grant、owner 更新和 audit。GET 列表与历史只返回脱敏字段。

- [x] **Step 4：运行绿灯与编译。**

  Run: `mvn -q -Dtest=AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppPhoneNumberControllerTest,SecurityConfigTest test`
  Run: `mvn -q -DskipTests compile`
  Expected: PASS。

### Task 4：历史访问与发送 fencing

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V68__chatapp_broadcast_account_version.sql`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V69__message_account_version.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolver.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageOutboxWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactIdentityMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolverTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorkerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageOutboxWorkerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAssignmentHistoryAccessSqlTest.java`

**Interfaces:**
- Produces: `ChatAppAccountResolver.requireOwnedAccountForSend(...)`，以数据库锁确认当前 owner。
- Produces: 广播保存 `channel_account_version`；未提交任务版本或 owner 不一致时失败为 `ACCOUNT_REASSIGNED`，已提交任务仍可对账。
- Consumes: `conversation_access_grants` 作为历史联系人、会话和消息读取权限。

- [x] **Step 1：写失败访问与竞态测试。**

  覆盖原销售可经 grant 阅读旧联系人/消息但不能发送；新销售可阅读全部旧会话；未提交广播在版本变化后不调用 gateway；已提交广播仍调用 reconcile；消息作者不随 owner 改变。

- [x] **Step 2：运行红灯。**

  Run: `mvn -q -Dtest=ChatAppAccountResolverTest,ChatAppBroadcastWorkerTest,WhatsAppAssignmentHistoryAccessSqlTest test`
  Expected: FAIL，缺少版本快照和部分查询仍只看账号 owner。

- [x] **Step 3：实现读写权限分离和 worker fencing。**

  历史读取统一允许会话 assignee/team/grant/admin；发送仍要求当前账号 owner。广播提交前比较 creator、账号 owner 和版本，失败只终止未提交 recipients，不更换操作者。

- [x] **Step 4：运行绿灯与编译。**

  Run: `mvn -q -Dtest=ChatAppAccountResolverTest,ChatAppBroadcastWorkerTest,WhatsAppAssignmentHistoryAccessSqlTest,MessageControllerAuthorizationTest test`
  Run: `mvn -q -DskipTests compile`
  Expected: PASS。

### Task 5：管理员账号管理页面

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Create: `demo/message-center-spring/frontend/src/components/whatsapp/AdminWhatsAppAccountPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.test.tsx`
- Test: `demo/message-center-spring/frontend/test/whatsapp-admin-assignment-contract.test.mjs`

**Interfaces:**
- Consumes: Task 3 的账号列表、同步、分配、收回、转交和历史 API。
- Produces: 管理员 CAMS 同步与账号分配表；普通销售只显示自己的有效账号摘要。

- [ ] **Step 1：写失败 UI/源代码合同测试。**

  断言管理员看到同步、分配、收回、转交；选择目标用户和原因；冲突刷新；普通销售看不到管理动作；页面不引用 Embedded Signup、授权 attempt 或 API 电话注册入口；号码只显示末四位。

- [ ] **Step 2：运行红灯。**

  Run: `npm test -- --run src/pages/ChannelSettingsPage.test.tsx`
  Run: `node test/whatsapp-admin-assignment-contract.test.mjs`
  Expected: FAIL，管理面板不存在且页面仍展示自助入口。

- [ ] **Step 3：实现 API 类型、管理面板和页面接线。**

  复用 `fetchAdminUsers` 作为目标销售目录；所有 mutation 成功或 409 后刷新账号列表。使用图标按钮和确认弹窗，原因必填，不展示完整号码或凭据。

- [ ] **Step 4：运行绿灯与构建。**

  Run: `npm test -- --run src/pages/ChannelSettingsPage.test.tsx`
  Run: `node test/whatsapp-admin-assignment-contract.test.mjs`
  Run: `npm run build`
  Expected: PASS。

### Task 6：禁用自助 API、真源回写和最终回归

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppAuthorizationController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppPhoneNumberController.java`
- Modify: `demo/message-center-spring/README.md`
- Modify: `docs/superpowers/README.md`
- Modify: `docs/superpowers/reviews/2026-09-09-whatsapp-cams-embedded-signup-self-service-verification.md`
- Create: `docs/superpowers/reviews/2026-09-10-whatsapp-admin-managed-account-assignment-verification.md`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppSelfServiceDisabledControllerTest.java`

**Interfaces:**
- Produces: 旧自助路由统一返回 `WHATSAPP_SELF_SERVICE_DISABLED`，不创建 attempt/operation，不调用 CAMS。
- Produces: README 和真源索引只将管理员手工配置/分配列为当前主线。

- [x] **Step 1：写失败路由测试。**

  覆盖 authorization attempts/complete 和 api-phone-operations 创建/取码/验证均返回结构化禁用错误，且对应 service 无交互。

- [x] **Step 2：运行红灯。**

  Run: `mvn -q -Dtest=WhatsAppSelfServiceDisabledControllerTest test`
  Expected: FAIL，旧路由仍进入自助服务。

- [x] **Step 3：实现禁用投影并回写文档。**

  保留历史类和表，不再提供前端入口；索引将 2026-09-10 设计和计划标为当前，将 2026-09-09 自助设计/计划标为已被取代。

- [x] **Step 4：运行专项与全量验收。**

  Run: `mvn -q -Dtest=WhatsAppSelfServiceDisabledControllerTest,AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppAccountSyncServiceTest,ChatAppBroadcastWorkerTest test`
  Run: `mvn test`
  Run: `npm test`
  Run: `npm run build`
  Run: `git diff --check`
  Expected: 本轮专项、前端测试和构建通过；全量失败若来自既有 WIP，逐项记录而不伪装为通过。

## 停止条件

- CAMS `QueryChatappPhoneNumbers` 的真实返回不含可稳定识别和验证号码的字段时，停止自动导入并记录实际合同，不猜测号码映射。
- 现有消息或联系人查询无法通过 `conversation_access_grants` 表达转交后的只读访问时，停止 Task 4 并先修正统一访问 owner，不在前端拼接历史。
- 需要删除旧 API、表或迁移历史时停止并单独请求确认；本计划只禁用入口并保留审计数据。
