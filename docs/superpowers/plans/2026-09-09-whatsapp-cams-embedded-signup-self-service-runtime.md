# CAMS WhatsApp 内嵌注册自助绑定运行时实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 实现管理员企业 API WABA 绑定、员工 Business App 共存自助绑定、企业 API 新号码注册，以及受 owner 和 provider scope 约束的查询、解绑与诊断闭环。

**架构：** `WhatsAppAuthorizationService` 是 attempt 状态机和授权完成唯一 owner；`WhatsAppProviderScopeEntity` 按 `(provider,waba_id)` 保存两种用途的 scope，`ChannelAccountEntity` 是员工发送身份唯一记录。浏览器只消费后端启动投影并提交经白名单验证的 Meta `code/wabaId/phoneNumberId`；后端不将事件中的 `phoneNumberId` 当作 CAMS 号码事实，号码、状态、显示名称和最终账户投影都由后端调用 CAMS 公开 API 同步得到。多号码时由服务端为本次 attempt 签发候选标识，提交后以新鲜 CAMS 查询复核。模板权限域及模板 UI 由 `2026-09-09-whatsapp-template-permission-domain.md` 承担，本计划不重复实现。

**技术栈：** Spring Boot 3.4、Java 17、MyBatis-Plus、PostgreSQL/Flyway、阿里云 CAMS `2020-06-06` SDK、React/TypeScript、Ant Design、JUnit 5、Vitest。

## 全局约束

- 当前设计文档 `docs/superpowers/specs/2026-09-09-whatsapp-cams-embedded-signup-self-service-design.md` 是本计划唯一产品真源；9 月 8 日计划中的“单企业 WABA”和管理员分配员工号码路线不得保留。
- scope 用途只能是 `ENTERPRISE_API` 或 `EMPLOYEE_BUSINESS_APP`；同一 `ALIYUN_CAMS + waba_id` 复用一个 scope，不用全局 scope 表达不同员工的 WABA。
- `ADMIN_API_WABA` 仅管理员可启动和完成；`EMPLOYEE_BUSINESS_APP` 仅当前员工可创建个人 scope；`EMPLOYEE_API_PHONE` 不启动 Meta WABA 弹窗，只在已存在企业 scope 下走添加号码、验证码与注册。
- 完成接口不信任浏览器手机号、账号名称、provider 状态、历史同步结论或启动 profile；绝不传递、保存或记录 AccessKey、Meta token、原始 code 和验证码。
- Business App 共存绝不调用 `AddChatappPhoneNumber`、`GetChatappVerifyCode`、`ChatappVerifyAndRegister` 或任何迁移 API。
- 每个活跃标准化号码只能归属一名员工；每个员工最多一个活跃 WhatsApp 账号；解绑仅切断 CRM 本地关系，不删除 CAMS、WABA、号码、模板或 Business App 资源。
- 每个 task 先写失败测试并实际执行红灯，再写最小实现并执行专项绿灯和编译；不提交 Git，不覆盖现有用户 WIP。

---

### Task 1：Scope 与授权 attempt 的当前合同迁移

**文件：**
- 新建：`demo/message-center-spring/backend/src/main/resources/db/migration/V59__whatsapp_self_service_scope_and_attempts.sql`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppAuthorizationAttemptEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAuthorizationAttemptMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppSelfServiceSchemaContractTest.java`

**接口：**
- `WhatsAppProviderScopeEntity.scopeType` 为 `ENTERPRISE_API|EMPLOYEE_BUSINESS_APP`；查询 API 为 `findByProviderAndWabaId(provider,wabaId)`、`findEnterpriseApiScope(provider)` 和 `findOwnedBusinessAppScope(provider,wabaId,ownerUserId)`。
- `WhatsAppAuthorizationAttemptEntity` 持有 `userId/stateHash/status/onboardingMode/accountName/accountRemark/wabaId/phoneNumberId/completedAccountId/expiresAt/consumedAt/failureStage/failureCode`；状态为 `PENDING -> META_COMPLETED -> PROVIDER_SYNCED -> COMPLETED` 或任意未终态至 `CANCELLED|EXPIRED|FAILED`。

- [ ] **Step 1：写 schema 合同失败测试。**

  断言 V59 将 `scope_type`、Business App scope owner、`whatsapp_authorization_attempts` 的完成标识和失败阶段加入 schema；断言 `ENTERPRISE_API` 不能有 owner、Business App scope 必须有 owner；断言活跃 WhatsApp 号码和 owner 的部分唯一约束存在。

- [ ] **Step 2：运行红灯。**

  运行：`mvn -q -Dtest=WhatsAppSelfServiceSchemaContractTest test`（目录：`demo/message-center-spring/backend`）。

  预期：因 V59 与实体/mapper 合同不存在失败。

- [ ] **Step 3：编写迁移与实体/mapper。**

  用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 保持可重跑。将当前无 owner 的 READY scope 标为 `ENTERPRISE_API`，不能证明用途或 WABA 的旧 scope 标记不可用于新绑定；为 `(provider,waba_id)` 建立唯一性，为 `(provider,waba_id,scope_type,owner_user_id)` 增加受约束查询索引。将 attempt 的旧 `CONSUMED` 记录映射为 `COMPLETED`，保留已完成 WABA/号码，不把 code/token 写入任何列。

- [ ] **Step 4：实现受锁的 mapper 操作。**

  添加 `findByIdForUpdate`、`advanceMetaCompleted`、`markProviderSynced`、`complete`、`fail` 与 `expirePending`，每个更新均包含允许的前置状态。`ChannelAccountMapper` 增加按 owner 查询活跃 WhatsApp 账号及 owner-only soft unlink；不改变 email 账号的约束。

- [ ] **Step 5：运行专项测试和编译。**

  运行：`mvn -q -Dtest=WhatsAppSelfServiceSchemaContractTest,ChannelAccountOwnerIsolationTest test`，再运行：`mvn -q -DskipTests compile`。

### Task 2：CAMS 公开授权与号码同步 Gateway

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppOnboardingGateway.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AliyunWhatsAppOnboardingGateway.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AliyunWhatsAppOnboardingGatewayTest.java`

**接口：**
- `startupProfile(mode)` 返回非秘密 `appId/configId/allowedOrigins/mode`；只经 `IsvGetAppId(Type=whatsapp,IntlVersion=2)` 获得。
- `verifyEmbeddedCode(code)` 调用 `GetPermissionByCode`；`bindWaba(wabaId)` 调用 `ChatappBindWaba` 并返回 `custSpaceId/wabaId`；`syncPhoneNumbers(scope)` 调用 `ChatappSyncPhoneNumber` 后 `QueryChatappPhoneNumbers`，返回后端标准化的 `ProviderPhone` 列表。

- [ ] **Step 1：写 gateway 动作矩阵失败测试。**

  mock CAMS `AsyncClient`，断言 API/WABA 完成按 `GetPermissionByCode -> ChatappBindWaba -> ChatappSyncPhoneNumber -> QueryChatappPhoneNumbers` 调用；共存完成使用相同公开绑定/同步路径；API-only 注册仅继续使用既有 add/send/verify 路径。断言传入 code 不出现在异常和结果对象中。

- [ ] **Step 2：运行红灯。**

  运行：`mvn -q -Dtest=AliyunWhatsAppOnboardingGatewayTest test`。

  预期：因公开授权接口和 provider phone 投影尚未存在失败。

- [ ] **Step 3：按已安装 CAMS SDK 实现公开合同。**

  先在本地 Maven 依赖中确认 `IsvGetAppId`、`GetPermissionByCode`、`ChatappBindWaba` 的实际 request/response 类型与字段；只按实际 SDK 生成类实现。将 provider request ID、有限错误码和脱敏诊断映射为 `WhatsAppAuthorizationException` 的结构化字段，不以猜测的 SDK 字段编译。

- [ ] **Step 4：实现同步筛选。**

  已安装 SDK 的 `QueryChatappPhoneNumbers` 和 `ChatappSyncPhoneNumber` 不返回 `phoneNumberId`，因此 `ProviderPhone` 只包含 `normalizedPhoneNumber/verifiedName/providerStatus/verificationStatus`。仅同步结果内 `ACTIVE + VERIFIED` 的号码可供绑定；多个候选由授权服务签发 attempt-bound 的不透明候选标识，选择后以新鲜同步结果复核，绝不把浏览器号码或 `phoneNumberId` 反推为 CAMS 号码。

- [ ] **Step 5：运行 gateway 测试与编译。**

  运行：`mvn -q -Dtest=AliyunWhatsAppOnboardingGatewayTest,ChatAppCapabilityProbeTest test`，再运行：`mvn -q -DskipTests compile`。

### Task 3：管理员 WABA 与员工 Business App 授权状态机

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAuthorizationService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAuthorizationException.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppAuthorizationController.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAuthorizationServiceTest.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppAuthorizationControllerTest.java`

**接口：**
- `POST /api/whatsapp/authorization/attempts` 接受 `ADMIN_API_WABA|EMPLOYEE_BUSINESS_APP` 和 `accountName/accountRemark`，返回 `attemptId/state/startsAt/expiresAt/startupProfile`。
- `POST /api/whatsapp/authorization/complete` 只接受 `attemptId/state/code/wabaId/phoneNumberId/eventType`；对于多个同步候选返回 `phoneSelectionRequired` 投影。
- `POST /api/whatsapp/authorization/complete/{attemptId}/phone` 只接受服务端签发的 attempt-bound 候选标识，服务端在新鲜同步结果中复核后创建账号。

- [ ] **Step 1：写状态机失败测试。**

  覆盖：普通员工不能启动/完成 `ADMIN_API_WABA`；管理员不能把完成流程直接创建员工账号；Business App 不要求 enterprise scope；跨用户、state 重放、过期和不同结果重放失败；单一活跃候选自动绑定；多个候选只能用服务端为本 attempt 签发的标识选择；浏览器手机号与 provider 号码不一致不会创建账号；相同 WABA scope 复用但不同员工 WABA 可并存。

- [ ] **Step 2：运行红灯。**

  运行：`mvn -q -Dtest=WhatsAppAuthorizationServiceTest,WhatsAppAuthorizationControllerTest test`。

  预期：当前服务要求全局 enterprise scope 且信任浏览器 `phoneNumber`，测试失败。

- [ ] **Step 3：实现启动和权限判定。**

  `createAttempt` 从当前认证角色判定模式，产生随机 state 的 SHA-256 存储、5 分钟过期和不可由浏览器覆盖的启动 profile。`EMPLOYEE_API_PHONE` 在此端点明确拒绝并定向到号码注册 API；能力门禁为相应模式不可用时返回 `WHATSAPP_ONBOARDING_MODE_UNAVAILABLE`。

- [ ] **Step 4：实现完成事务。**

  锁定 attempt 后校验 owner、state、状态与 event；调用 code 验证、绑定 WABA、同步号码，按 `scopeType/wabaId/owner` 建立或复用 scope；在同一事务更新 attempt、scope、channel account 和审计。管理员替换 ENTERPRISE_API scope 时，只要旧 scope 存在活跃账号即返回冲突，绝不静默改路由。

- [ ] **Step 5：完成幂等与受控错误。**

  已完成同结果 attempt 返回原 account 投影；跨用户、过期、cancelled、解绑后旧 attempt、未知候选均为结构化拒绝。`code` 只在调用栈局部变量中存在，日志、attempt 和响应不得包含它。

- [ ] **Step 6：运行专项测试和编译。**

  运行：`mvn -q -Dtest=WhatsAppAuthorizationServiceTest,WhatsAppAuthorizationControllerTest test`，再运行：`mvn -q -DskipTests compile`。

### Task 4：企业 API 新号码的 employee lifecycle

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppPhoneNumberService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppPhoneNumberController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppPhoneOnboardingOperationEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppPhoneOnboardingOperationMapper.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppPhoneNumberServiceTest.java`

**接口：**
- `POST /api/whatsapp/api-phone-operations` 创建 `EMPLOYEE_API_PHONE` 操作；`POST .../{operationId}/verification-code` 必须有显式确认；`POST .../{operationId}/verify` 接收一次性验证码；`GET /api/whatsapp/api-phone-operations` 仅列当前 owner 操作投影。

- [ ] **Step 1：写失败测试。**

  覆盖无 ENTERPRISE_API scope 拒绝、非 owner 不能取码/验证、首次 add 后幂等重试、未确认拒绝发送验证码、验证码不进入投影/异常、provider 确认前不创建账号、已验证账号重复验证返回同一投影、Business App scope 绝不作为本流程目标。

- [ ] **Step 2：运行红灯。**

  运行：`mvn -q -Dtest=WhatsAppPhoneNumberServiceTest test`。

- [ ] **Step 3：把操作绑定到企业 scope。**

  只用 `findEnterpriseApiScope`，并保存 `PENDING/CODE_SENT/REGISTERED/FAILED` 及有限 provider request ID；失败的副作用操作重试前先同步/查询 provider，已成功则复用结果，不重复注册。

- [ ] **Step 4：验证后以 provider 事实创建账号。**

  调用 `ChatappVerifyAndRegister` 后强制同步和查询；只在 `ACTIVE + VERIFIED` 且号码归属企业 scope、全局号码唯一和 owner 活跃账号计数仍满足时插入 `API_ONLY` account。账号名称/备注取创建操作保存的用户资料，号码/status 取 provider。

- [ ] **Step 5：运行专项测试和编译。**

  运行：`mvn -q -Dtest=WhatsAppPhoneNumberServiceTest,ChannelAccountServiceTest test`，再运行：`mvn -q -DskipTests compile`。

### Task 5：员工账号查看、解绑、历史同步与管理员诊断

**文件：**
- 新建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAccountLifecycleService.java`
- 新建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppAccountController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessageSyncService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminChatAppCapabilityController.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAccountLifecycleServiceTest.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppAccountControllerTest.java`

**接口：**
- `GET /api/whatsapp/accounts/me` 返回当前 owner 活跃账号及 `accountId/mode/name/remark/maskedPhone/providerStatus/verificationStatus/templateDomain/recoverableError`。
- `DELETE /api/whatsapp/accounts/{accountId}` 仅解除当前 owner 的本地账号关系并写审计；`POST /api/whatsapp/accounts/{accountId}/history-sync` 触发可观测、幂等的异步任务，不决定绑定是否可用。

- [ ] **Step 1：写失败测试。**

  覆盖其他员工/管理员不能读取或解绑个人账号、解绑不调用 CAMS 删除或注销 API、scope 凭据保持、解绑后旧 attempt 不能重放、历史同步仅对 owner 的 Business App 账号允许、同步失败不使已完成绑定失效。

- [ ] **Step 2：运行红灯。**

  运行：`mvn -q -Dtest=WhatsAppAccountLifecycleServiceTest,WhatsAppAccountControllerTest test`。

- [ ] **Step 3：实现 owner-only projection 和 unlink。**

  使用 mapper 的条件更新标记账号本地不可用并记录 actor/account/reason/timestamp；只返回末四位号码、恢复错误码和模板域，不返回 scope ID、custSpaceId、凭据或 provider request ID。

- [ ] **Step 4：实现独立历史同步与诊断投影。**

  将同步排入现有 worker/scheduler 基础设施并使用 `accountId` 幂等键；不会同步历史时记录结构化失败阶段。管理员诊断只展示脱敏 capability report 和绑定阶段，不能代替员工创建/解绑个人账号。

- [ ] **Step 5：运行专项测试和编译。**

  运行：`mvn -q -Dtest=WhatsAppAccountLifecycleServiceTest,WhatsAppAccountControllerTest,ChatAppMessageSyncServiceTest test`，再运行：`mvn -q -DskipTests compile`。

### Task 6：受控前端内嵌注册与账号管理

**文件：**
- 修改：`demo/message-center-spring/frontend/src/api/types.ts`
- 修改：`demo/message-center-spring/frontend/src/api/endpoints.ts`
- 修改：`demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/components/whatsapp/WhatsAppAuthorizationPanel.tsx`
- 修改：`demo/message-center-spring/frontend/src/components/whatsapp/WhatsAppPhoneNumberPanel.tsx`
- 新建：`demo/message-center-spring/frontend/src/components/whatsapp/WhatsAppAccountPanel.tsx`
- 测试：`demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.test.tsx`
- 测试：`demo/message-center-spring/frontend/test/whatsapp-self-service-contract.test.mjs`

**接口：**
- 仅使用 Task 3-5 的 HTTP 合同；Meta SDK 只接收服务端投影的 `appId/configId/mode/allowedOrigins`，完成时只发送 `attemptId/state/code/wabaId/phoneNumberId/eventType`。

- [ ] **Step 1：写前端失败测试。**

  断言普通员工看不到企业 WABA 管理启动；Business App 流程不渲染手机号/验证码字段；API-only 流程不启动 Meta SDK；页面不保留 AccessKey、custSpaceId、Meta token、provider 状态或号码作为可信表单值；多个号码候选只能提交 service 返回的 ID。

- [ ] **Step 2：运行红灯。**

  运行：`npm test -- --run src/pages/ChannelSettingsPage.test.tsx` 和 `node test/whatsapp-self-service-contract.test.mjs`（目录：`demo/message-center-spring/frontend`）。

- [ ] **Step 3：接入启动与完成状态。**

  创建 attempt 后按 profile 延迟加载 Meta 官方 SDK，严格检查 `event.origin` 与 event 信封；取消、错误、候选号码选择与完成投影由后端状态驱动。UI 绝不显示完整手机号、provider request ID 或秘密。

- [ ] **Step 4：实现账号列表、解绑与 API-only wizard。**

  列表使用 `/accounts/me`；解绑先要求原因并刷新本地投影；验证码输入只存在受控表单提交期间且成功/失败后清空。历史同步状态与绑定状态独立展示。

- [ ] **Step 5：运行前端测试和构建。**

  运行：`npm test -- --run src/pages/ChannelSettingsPage.test.tsx`，运行：`node test/whatsapp-self-service-contract.test.mjs`，运行：`npm run build`。

### Task 7：实机门禁、回归与文档回写

**文件：**
- 新建：`docs/superpowers/reviews/2026-09-09-whatsapp-cams-embedded-signup-self-service-verification.md`
- 修改：`docs/superpowers/README.md`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`

- [ ] **Step 1：运行本地回归。**

  后端运行：`mvn test`；前端运行：`npm test`、`npm run build`。记录具体通过数、失败项和已有 WIP 的独立失败原因，不能将失败写成通过。

- [ ] **Step 2：执行真实 CAMS/Meta 发布门禁。**

  使用项目既有本地 secret 配置调用 `IsvGetAppId`，再分别从登记 HTTPS 域启动管理员 API/WABA 和员工 Business App 弹窗；对成功 code 验证权限、绑定 WABA、同步/查询号码。仅对专用测试号码执行 API-only add/code/verify。保存脱敏的接口名、阶段、request ID、时间、模式、结果码到 git 外位置。

- [ ] **Step 3：覆盖授权隔离场景。**

  验证两名员工可绑定不同 WABA，不能访问对方 scope/号码；员工不能绑定企业 WABA；员工可添加企业 API 测试号码；解绑后 CAMS/Business App 资源不变。任一 Embedded Signup 模式在前述合同未验证时保持不可用，不降级为 CAMS 控制台跳转。

- [ ] **Step 4：回写验收记录并检查边界。**

  写入实际命令、结果、实机阻断项和不触碰的 WIP；运行 `git diff --check` 与 `git status --short`，确保本轮没有秘密、生成物或无关改动被混入。

## 停止条件

- 已安装 CAMS SDK 缺少公开授权接口、或实机返回与文档字段不符时，停止 Task 2-6 的对应模式实现，先记录实际合同，不能猜测字段或复用 CAMS 控制台私有 URL。
- 现有数据库无法同时表达企业 API 与员工 Business App scope 时，停止业务层兼容分支，先修复 Task 1 合同。
- 任一 owner、scope 或活跃号码唯一性专项测试失败时，停止前端扩展。
- 用户未提供可在本地安全使用的 CAMS 凭据时，Task 7 的实机门禁记录为未执行；这不允许把未验证模式显示为可用。

## 自检

- 设计覆盖：scope/attempt、CAMS 公共合同、管理员 WABA、Business App 共存、企业 API 新号、查看解绑、历史同步、前端和实机门禁均有独立任务。
- 排除重复：模板权限域、私有模板 CRUD、共享模板审批只由既有 9 月 9 日模板计划实现。
- 反漂移：不保留单 WABA、管理员分配员工账号、迁移 API 或浏览器信任手机号的旧路线。
