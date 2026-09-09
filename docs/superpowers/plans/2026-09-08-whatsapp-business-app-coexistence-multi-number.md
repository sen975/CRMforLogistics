# WhatsApp Business App 共存与企业多号码实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在一个企业 WABA/custSpaceId 下，让销售本人完成 WhatsApp Business App 共存授权，并管理多个销售号码及后续 API 号码，同时在真实阿里云账号能力确认前阻止正式授权 UI 上线。

**Architecture:** 企业级 `whatsapp_provider_scopes` 持有唯一 WABA、custSpaceId 和 CAMS 配置；`channel_accounts` 表示每个销售的发送号码；CAMS gateway 统一负责绑定、查询、同步、添加、验证码和注册；授权 attempt 和能力报告负责安全状态与上线门禁。沿用现有 owner 隔离和 provider scope，不把 AccessKey 暴露给浏览器。

**Tech Stack:** Spring Boot 3.4.5、Java 17、MyBatis-Plus、PostgreSQL/Flyway、阿里云 `alibabacloud-cams20200606:5.0.5`、React/TypeScript、Ant Design、Vitest。

## Global Constraints

- 单企业模式：一个有效企业 provider scope 只能对应一个 `wabaId + custSpaceId`。
- 一个 scope 允许多个 WhatsApp `channel_accounts`，但每个销售最多一个有效 WhatsApp 账号、每个号码最多一个有效 CRM 账号。
- 现有 Business App 号码走 Embedded Signup Coexistence；API-only 号码走添加、验证码和注册接口。
- 不把 AccessKey、AccessKey Secret、验证码、完整授权 token 写入日志、响应或前端状态。
- 在线探测、发送验证码、号码注册和迁移必须记录脱敏诊断与 provider request ID；副作用动作必须由管理员在执行前确认。
- 在真实账号能力报告完成前，不显示正式 WhatsApp 授权和号码注册入口。
- 不对生产号码试调用迁移接口；迁移能力只能用阿里云确认的测试号码或明确的生产操作窗口验证。
- 每个 Task 只运行专项测试和编译；全量回归在最终 Task 运行一次。

---

## Task 1: CAMS 能力探测与上线门禁

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppCapabilityProbe.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChatAppCapabilityReport.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminChatAppCapabilityController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppCapabilityProbeTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminChatAppCapabilityControllerTest.java`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V51__chatapp_capability_probe.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java:54-72`

**Interfaces:**
- `ChatAppCapabilityProbe.probeReadOnly(ChatAppAccountCredentials)`：只调用 `QueryChatappBindWaba`、`QueryChatappPhoneNumbers`、`GetPhoneNumberVerificationStatus`（仅对已知号码）；返回结构化阶段结果，不发送验证码。
- `ChatAppCapabilityProbe.probeProvisioning(...)`：只能由管理员调用，接收显式测试号码和确认标记，按 `AddChatappPhoneNumber -> GetChatappVerifyCode -> ChatappVerifyAndRegister -> QueryChatappPhoneNumbers` 执行；验证码输入不能写入数据库或日志。
- `ChatAppCapabilityProbe.recordMigrationNotVerified(...)`：将迁移接口标记为 `UNVERIFIED_FOR_PRODUCTION`，不得发起生产号码迁移。
- `GET /api/admin/chatapp/capabilities`：返回最近能力报告和逐接口状态。
- `POST /api/admin/chatapp/capabilities/read-only`：管理员触发只读探测。
- `POST /api/admin/chatapp/capabilities/provisioning`：管理员携带测试号码和确认头触发副作用测试。

- [ ] **Step 1: Write failing unit tests for the SDK action matrix.**

  Use mocked `AsyncClient` to assert that read-only mode calls only query actions, provisioning mode calls the exact action order, and migration actions are never called. Assert reports contain action, phase, status, provider request ID and redacted diagnostic only.

- [ ] **Step 2: Run the focused tests and verify they fail.**

  Run: `mvn -q -Dtest=ChatAppCapabilityProbeTest,AdminChatAppCapabilityControllerTest test` from `demo/message-center-spring/backend`.

  Expected: FAIL because the probe, report and controller do not exist.

- [ ] **Step 3: Add the capability report migration and domain contracts.**

  Create a bounded report table with `id`, `scope_id`, `phase`, `action`, `status`, `provider_request_id`, `diagnostic_code`, `diagnostic_message`, `tested_phone_last4`, `tested_at`, and `created_at`. Add a check constraint for `READ_ONLY`, `PROVISIONING_TEST`, and `MIGRATION_REVIEW`; never add secret or verification-code columns.

- [ ] **Step 4: Implement the probe using the installed generated SDK.**

  Build the existing CAMS client factory pattern from `ChatAppMessageSyncService`. Use `QueryChatappBindWabaRequest`, `QueryChatappPhoneNumbersRequest`, `GetPhoneNumberVerificationStatusRequest`, `AddChatappPhoneNumberRequest`, `GetChatappVerifyCodeRequest`, and `ChatappVerifyAndRegisterRequest`. Convert SDK exceptions into report statuses and preserve only bounded provider codes/messages and request IDs.

- [ ] **Step 5: Add admin-only endpoints and migration guard.**

  Require `ROLE_ADMIN` in `SecurityConfig`. Reject provisioning requests without an explicit confirmation value and reject production-number migration requests unconditionally. Persist the report in a transaction after each action so a later failure still leaves diagnostic evidence.

- [ ] **Step 6: Run focused tests and compile.**

  Run: `mvn -q -Dtest=ChatAppCapabilityProbeTest,AdminChatAppCapabilityControllerTest test`.

  Expected: PASS. Run: `mvn -q -DskipTests compile`.

- [ ] **Step 7: Run the real-account read-only gate before continuing.**

  Configure credentials only through the existing local secret/config mechanism, invoke `GET/POST /api/admin/chatapp/capabilities/read-only`, and save the returned redacted report outside git. Stop implementation if `QueryChatappBindWaba`, `QueryChatappPhoneNumbers`, or status query returns permission/parameter failure. Do not continue to Task 2 until the report records the real result for the target `custSpaceId`.

## Task 2: 企业 scope 与多个销售号码的数据模型

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V52__whatsapp_scope_waba_and_phone_metadata.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppMultiNumberSchemaContractTest.java`

**Interfaces:**
- `WhatsAppProviderScopeEntity` exposes `wabaId`, `externalScopeId`, status and capability gate state.
- `ChannelAccountEntity` exposes `onboardingMode`, verification status and provider phone status.
- Mapper methods provide one enterprise scope, active accounts by scope, unique phone lookup and reassignment-safe updates.

- [ ] **Step 1: Add failing schema/entity contract tests.**

  Assert `whatsapp_provider_scopes.waba_id` is nonblank and unique for the provider, `channel_accounts` has onboarding/verification/provider-status fields, and a unique partial index prevents two active records for one normalized phone number.

- [ ] **Step 2: Run the schema contract test and verify failure.**

  Run: `mvn -q -Dtest=WhatsAppMultiNumberSchemaContractTest test`.

- [ ] **Step 3: Add the Flyway migration and entity fields.**

  Backfill `waba_id` from the capability/authorization source when available; leave existing scopes in a blocked state if no WABA can be proven. Preserve existing `provider_scope_id` and owner columns. Add check constraints for `BUSINESS_APP_COEXISTENCE` and `API_ONLY`.

- [ ] **Step 4: Add mapper queries for scope and phone ownership.**

  Add `findEnterpriseScope`, `findActiveByScope`, `findActiveByNormalizedIdentifier`, `countActiveByOwnerAndChannel`, and an atomic reassignment update that only succeeds when the target owner has no active WhatsApp account.

- [ ] **Step 5: Run focused schema and mapper tests.**

  Run: `mvn -q -Dtest=WhatsAppMultiNumberSchemaContractTest,ChannelAccountOwnerIsolationTest test`.

## Task 3: 企业授权与 Business App Coexistence onboarding

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppAuthorizationAttemptEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAuthorizationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppAuthorizationController.java`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V53__whatsapp_authorization_attempts.sql`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAuthorizationServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppAuthorizationControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeService.java:33-89`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java:54-72`

**Interfaces:**
- `POST /api/whatsapp/authorization/attempts` creates a short-lived one-time attempt for the current user.
- `POST /api/whatsapp/authorization/complete` consumes the attempt and accepts only the non-secret Embedded Signup result required to bind the WABA/number.
- `WhatsAppAuthorizationService.complete(userId, attemptId, wabaId, phoneNumber, mode)` validates state, scope identity, capability gate and phone uniqueness, then binds the local account.

- [ ] **Step 1: Write failing tests for state, owner and scope validation.**

  Cover expired/replayed attempts, cross-user completion, a different WABA, an already-bound phone, successful first-scope binding, and successful additional-number binding. Assert no local account is created on any failed validation.

- [ ] **Step 2: Run the focused tests and verify failure.**

  Run: `mvn -q -Dtest=WhatsAppAuthorizationServiceTest,WhatsAppAuthorizationControllerTest test`.

- [ ] **Step 3: Implement one-time attempts and completion transaction.**

  Generate a cryptographically random state, hash it before persistence, bind it to the authenticated user and expiry, and mark it consumed in the same transaction as scope/phone account creation. Never accept AccessKey fields from the browser.

- [ ] **Step 4: Enforce the enterprise WABA invariant.**

  On first completion call `ChatappBindWaba`; on later completions compare the returned/queried WABA with the stored enterprise `waba_id`. Reject mismatches with a structured error. Use `ChatappSyncPhoneNumber` and `QueryChatappPhoneNumbers` before local account creation.

- [ ] **Step 5: Run focused tests and compile.**

  Run: `mvn -q -Dtest=WhatsAppAuthorizationServiceTest,WhatsAppAuthorizationControllerTest,WhatsAppProviderScopeServiceTest test` and `mvn -q -DskipTests compile`.

## Task 4: API-only 添加号码与销售账号生命周期

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppPhoneNumberService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppPhoneNumberController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/AddWhatsAppPhoneNumberRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WhatsAppPhoneNumberStatus.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppPhoneNumberServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java:136-167`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`

**Interfaces:**
- `POST /api/whatsapp/phone-numbers` starts `AddChatappPhoneNumber` for an API-only number.
- `POST /api/whatsapp/phone-numbers/{phone}/verification-code` invokes `GetChatappVerifyCode` only after explicit UI confirmation.
- `POST /api/whatsapp/phone-numbers/{phone}/verify` invokes `ChatappVerifyAndRegister` and then queries status.
- `GET /api/whatsapp/phone-numbers` returns provider and local status without credentials.

- [ ] **Step 1: Write failing tests for multi-number lifecycle.**

  Cover adding a second number under the same scope, rejecting duplicate normalized numbers, rejecting a different WABA/scope, keeping verification codes out of logs/results, idempotent retry after a successful provider operation, and creating the local `channel_account` only after provider confirmation.

- [ ] **Step 2: Run focused tests and verify failure.**

  Run: `mvn -q -Dtest=WhatsAppPhoneNumberServiceTest test`.

- [ ] **Step 3: Implement provider lifecycle calls and state transitions.**

  Use persisted operation state to distinguish `PENDING`, `CODE_SENT`, `REGISTERED`, `FAILED`, and `DISABLED`. Before retrying a side-effecting call, query provider status and reuse an existing successful result.

- [ ] **Step 4: Remove the one-account-per-owner restriction only for WhatsApp semantics.**

  Preserve one active WhatsApp account per owner, but remove the global single-account assumption. Keep email behavior unchanged. Make `chatappFrom` a derived phone identifier for new accounts, not an enterprise credential field.

- [ ] **Step 5: Run focused tests and compile.**

  Run: `mvn -q -Dtest=WhatsAppPhoneNumberServiceTest,ChannelAccountServiceTest,ChannelAccountOwnerIsolationTest test` and `mvn -q -DskipTests compile`.

## Task 5: 前端授权、号码列表和管理员分配

**Files:**
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx:54-134`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts:740-772`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts:492-510`
- Create: `demo/message-center-spring/frontend/src/components/whatsapp/WhatsAppAuthorizationPanel.tsx`
- Create: `demo/message-center-spring/frontend/src/components/whatsapp/WhatsAppPhoneNumberPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.test.tsx`
- Create: `demo/message-center-spring/frontend/test/whatsapp-coexistence-contract.test.mjs`

**Interfaces:**
- `POST /api/whatsapp/authorization/attempts` and `/complete` are the only browser authorization endpoints.
- `GET /api/whatsapp/phone-numbers` supplies the number list and capability gate.
- Admin-only assignment actions are hidden and rejected for non-admin users by server authorization, not only by UI.

- [ ] **Step 1: Add failing frontend contract tests.**

  Assert the page does not render the old AccessKey/custSpaceId form when the capability gate is blocked; after a passed gate it renders the authorization action, current-user number state, and API-only add-number action. Assert no secret field is sent by the browser.

- [ ] **Step 2: Run focused frontend tests and verify failure.**

  Run: `npm test -- --run src/pages/ChannelSettingsPage.test.tsx` and `node test/whatsapp-coexistence-contract.test.mjs` from `demo/message-center-spring/frontend`.

- [ ] **Step 3: Implement the authorization panel.**

  Load the Meta/ChatApp SDK only after the server returns an active capability gate and a valid attempt. Listen for the provider `message` result, send only the documented non-secret result to the backend, and render pending/success/error states with provider request IDs omitted from normal user copy.

- [ ] **Step 4: Implement number list and API-only wizard.**

  Show each number, owner, onboarding mode, verification status, provider status and quality status. Require an explicit confirmation before sending a verification code. Never display or store AccessKey values in form state.

- [ ] **Step 5: Run focused frontend tests and build.**

  Run: `npm test -- --run src/pages/ChannelSettingsPage.test.tsx` and `npm run build`.

## Task 6: 管理员重新分配、禁用和审计

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V54__whatsapp_account_assignment_audit.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppAccountAssignmentAuditEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java:54-72`

**Interfaces:**
- `POST /api/admin/whatsapp/phone-numbers/{accountId}/assign` changes owner atomically.
- `POST /api/admin/whatsapp/phone-numbers/{accountId}/disable` disables CRM use without provider deregistration.
- `GET /api/admin/whatsapp/phone-numbers` lists all enterprise numbers and current owners.

- [ ] **Step 1: Write failing admin authorization and audit tests.**

  Cover non-admin rejection, assignment to a user who already owns a WhatsApp number, successful assignment with an audit row, concurrent assignment conflict, and disable behavior that does not call `ChatappPhoneNumberDeregister`.

- [ ] **Step 2: Run focused tests and verify failure.**

  Run: `mvn -q -Dtest=AdminWhatsAppPhoneNumberControllerTest test`.

- [ ] **Step 3: Implement atomic assignment and audit.**

  Lock the target account and target owner’s active WhatsApp account in one transaction, update owner only if the invariant remains true, and insert before/after owner IDs plus actor ID and reason into the audit table.

- [ ] **Step 4: Add admin endpoints and security rules.**

  Require `ROLE_ADMIN` at the HTTP boundary and repeat ownership/role validation in the service. Keep provider deregistration as a separate future command; disable must only stop local use.

- [ ] **Step 5: Run focused tests and compile.**

  Run: `mvn -q -Dtest=AdminWhatsAppPhoneNumberControllerTest,ChannelAccountOwnerIsolationTest test` and `mvn -q -DskipTests compile`.

## Task 7: 端到端验收、文档与发布门禁

**Files:**
- Create: `docs/superpowers/reviews/2026-09-08-whatsapp-business-app-coexistence-multi-number-verification.md`
- Modify: `docs/superpowers/README.md`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`
- Modify: `demo/message-center-spring/frontend/test/whatsapp-coexistence-contract.test.mjs`

- [ ] **Step 1: Run the full backend suite.**

  Run: `mvn test` from `demo/message-center-spring/backend`. Record existing unrelated failures separately; do not mask them or modify user WIP to force a green result.

- [ ] **Step 2: Run the full frontend suite and production build.**

  Run: `npm test -- --run` and `npm run build` from `demo/message-center-spring/frontend`.

- [ ] **Step 3: Execute real-account read-only and approved test-number gates.**

  Record the redacted capability report for each CAMS action, the Embedded Signup result, WABA consistency, one coexistence number, one API-only test number if available, and all provider request IDs. Stop and report any action that is not enabled rather than claiming the feature is complete.

- [ ] **Step 4: Verify browser flows at desktop and mobile widths.**

  Verify sales authorization, repeated authorization rejection, number status display, API-only confirmation, admin assignment, and blocked capability state. Confirm no secrets appear in DOM, network payloads, console logs or screenshots.

- [ ] **Step 5: Write the verification record and update the index.**

  Document exact commands, test results, provider action statuses, known limitations, and whether migration APIs remain unverified for production. Update `docs/superpowers/README.md` under current verification only after evidence exists.

- [ ] **Step 6: Final git boundary review.**

  Run `git status --short`, `git diff --check`, inspect only task files, and confirm no user WIP, generated ZIP, secrets, test numbers or provider tokens entered the diff.

## Stop Conditions

- If read-only CAMS queries fail due to permission, endpoint or scope mismatch, stop after Task 1 and report the exact redacted provider result.
- If Business App Coexistence completion does not produce a queryable WABA/phone in CAMS, stop before Task 3 UI integration.
- If API-only test-number provisioning cannot be performed safely, mark that path as unverified and do not expose its production UI.
- If migration prerequisites for the existing production number are not explicitly confirmed by Alibaba Cloud, do not call migration APIs and do not claim migration support.
