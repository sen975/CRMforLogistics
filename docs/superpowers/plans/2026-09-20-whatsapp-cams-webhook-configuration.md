# WhatsApp CAMS 回调地址管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为管理员提供 CAMS WhatsApp 号码级回调地址配置闭环，调用 `UpdatePhoneWebhook`/`UpdateAccountWebhook`，兼容真实 CAMS 无签名回调并支持基于 scope + 号码的多账号安全路由。

**Architecture:** 新增 callback config service 作为回调期望状态和版本 owner；新增 CAMS callback gateway 只负责 SDK 协议映射和错误归一化；管理员 controller/frontend 只负责 DTO、展示和接线。webhook ingress 继续由 `ChatAppWebhookInboxService` 负责，按真实 CAMS 的无签名 JSON 数组合同执行 scope + 标准化业务号码解析；若未来请求带成对签名 Header，则由 `ChatAppWebhookVerifier` 严格验签。

**Tech Stack:** Spring Boot 3.4、Java 17、MyBatis-Plus、PostgreSQL/Flyway、阿里云 CAMS SDK `5.0.5`、React/TypeScript、Vitest、JUnit 5。

## Global Constraints

- CAMS 回调写入必须调用 SDK 生成的 `AsyncClient.updatePhoneWebhook` 或 `updateAccountWebhook`，不得猜测私有控制台接口。
- 浏览器不得提交或覆盖 `custSpaceId`、AccessKey、Secret、真实 provider 号码和 provider 状态。
- 当前没有确认 CAMS 回调配置回读 API；页面只能展示本地期望配置与最近写入结果。
- 所有管理员接口在服务端调用 `WhatsAppAdminAuthorization.requireAdmin`；不能只依赖前端隐藏按钮。
- URL 只允许 `https`；localhost 例外由后端开发 profile 明确控制。
- 不记录完整 callback URL、AccessKey、Secret、完整号码、消息正文和原始 provider 响应。
- webhook 必须保留 body 上限、幂等落库和多账号 scope + 号码路由；无签名 CAMS 请求不得因缺少项目内假设的 Header 被拒绝，成对签名 Header 出现时必须执行 HMAC 和时间窗校验。
- 不修改 WABA/号码生命周期、账号分配、模板、Embedded Signup 或员工自助绑定路线。
- 只修改本计划列出的文件；保留工作区既有用户改动。

---

### Task 1: 回调配置数据库合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V79__whatsapp_cams_webhook_configuration.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppCallbackConfigEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppCallbackAuditEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackConfigMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackAuditMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackSchemaContractTest.java`

**Interfaces:**
- `WhatsAppCallbackConfigEntity` stores `providerScopeId`, nullable `channelAccountId`, `level`, desired URLs, flags, provider state, apply status, request id, error code, version and timestamps.
- `WhatsAppCallbackConfigMapper` provides `findPhone(scopeId, accountId)`, `findAccount(scopeId)`, `insertOrUpdatePhone(...)`, `insertOrUpdateAccount(...)`, and version-guarded result updates.
- `WhatsAppCallbackAuditMapper` inserts append-only audit rows.

- [x] **Step 1: Write the failing schema test.**

  Assert the migration creates both tables, partial uniqueness for `ACCOUNT` and `PHONE`, URL length constraints, `Y/N` flag constraints, and foreign keys to `whatsapp_provider_scopes` and `channel_accounts`.

- [x] **Step 2: Run the schema test to verify it fails.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WhatsAppCallbackSchemaContractTest test
  ```

  Expected: FAIL because the migration and mapper contract do not exist.

- [x] **Step 3: Add the migration and entity mappings.**

  Use `V79` after the current V78 migration. Add `CHECK` constraints for `level`, `http_flag`, `queue_flag`, and `last_apply_status`; add partial unique indexes; default `provider_state` to `UNKNOWN`, `last_apply_status` to `NEVER_APPLIED`, and `version` to `0`.

- [x] **Step 4: Add mapper methods with optimistic locking.**

  Each update must include `where id = ... and version = ...`; apply success increments version only after the provider call returns success. Failed attempts update the result row without replacing the prior desired value.

- [x] **Step 5: Run the schema test and diff checks.**

  ```bash
  mvn -q -Dtest=WhatsAppCallbackSchemaContractTest test
  git diff --check
  ```

  Expected: test pass and no whitespace errors.

### Task 2: CAMS callback gateway

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunWhatsAppCallbackGateway.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCallbackGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/cams/ChatAppAccountCredentialsResolver.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunWhatsAppCallbackGatewayTest.java`

**Interfaces:**
- `WhatsAppCallbackGateway.updatePhone(PhoneUpdate command, ChatAppAccountCredentials credentials)` returns `ProviderApplyResult(requestId)`.
- `WhatsAppCallbackGateway.updateAccount(AccountUpdate command, ChatAppAccountCredentials credentials)` returns the same result.
- `PhoneUpdate` includes only server-resolved `custSpaceId`, normalized phone, URL values and flags.
- Gateway maps SDK exceptions and non-success body codes to `WHATSAPP_CALLBACK_PROVIDER_FAILED` or `WHATSAPP_CALLBACK_PROVIDER_TIMEOUT` without including secrets or raw responses.

- [x] **Step 1: Write failing gateway tests.**

  Mock `AsyncClient` and assert `UpdatePhoneWebhookRequest` receives `custSpaceId`, normalized phone, `UpCallbackUrl`, `StatusCallbackUrl`, `HttpFlag`, and `QueueFlag`. Assert account update cannot carry an upstream URL. Assert provider RequestId is retained while secret and raw request data never appear in exception text.

- [x] **Step 2: Run the tests to verify they fail.**

  ```bash
  mvn -q -Dtest=AliyunWhatsAppCallbackGatewayTest test
  ```

  Expected: FAIL because the gateway types and methods do not exist.

- [x] **Step 3: Implement the SDK adapter.**

  Create the CAMS client using the existing credential/region/endpoint pattern. Call `updatePhoneWebhook` and `updateAccountWebhook`; await the bounded future, inspect the response body code, and return only the provider RequestId and normalized result.

- [x] **Step 4: Run gateway tests and compile.**

  ```bash
  mvn -q -Dtest=AliyunWhatsAppCallbackGatewayTest test
  mvn -q -DskipTests compile
  ```

  Expected: both commands exit 0.

### Task 3: Callback service, admin API, and audit

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCallbackConfigService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCallbackConfigController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WhatsAppCallbackConfigView.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCamsConfigController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCallbackConfigServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppCallbackConfigControllerTest.java`

**Interfaces:**
- `GET /api/admin/whatsapp/cams/{scopeId}/callbacks`
- `PUT /api/admin/whatsapp/cams/{scopeId}/callbacks/phones/{channelAccountId}`
- `PUT /api/admin/whatsapp/cams/{scopeId}/callbacks/account`
- Service methods `list(scopeId)`, `applyPhone(actorId, scopeId, accountId, request)`, and `applyAccount(actorId, scopeId, request)`.

- [x] **Step 1: Write failing service tests.**

  Cover admin-only access, account not in scope, URL rejection, account-level upstream field rejection, stale version rejection, successful provider update, failed provider update retaining the old desired values, append-only audit, and response redaction.

- [x] **Step 2: Run service/controller tests to verify they fail.**

  ```bash
  mvn -q -Dtest=WhatsAppCallbackConfigServiceTest,WhatsAppCallbackConfigControllerTest test
  ```

  Expected: FAIL because the service/controller and DTOs do not exist.

- [x] **Step 3: Implement server-side validation and owner resolution.**

  Resolve scope with `WhatsAppCamsConfigService.requireReadyScope`; resolve credentials via existing `ChatAppAccountCredentialsResolver`; derive `custSpaceId` and normalized phone from the stored entities; ignore any browser-supplied provider identity. Validate HTTPS, max length 2048, flag values, and `expectedVersion`.

- [x] **Step 4: Implement provider call and transactional result handling.**

  Lock the config row and referenced account, call the gateway outside the database lock only if the version remains current, then write success/failure projection and audit in a transaction. A provider failure returns a structured 502 and leaves the previous desired value available for retry.

- [x] **Step 5: Run tests and compile.**

  ```bash
  mvn -q -Dtest=WhatsAppCallbackConfigServiceTest,WhatsAppCallbackConfigControllerTest test
  mvn -q -DskipTests compile
  ```

  Expected: all selected tests pass and compilation exits 0.

### Task 4: Secure webhook verification and multi-account routing

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookInboxService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookVerifier.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookInboxServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookVerifierTest.java`

**Interfaces:**
- `ChatAppWebhookInboxService.accept` must call `verifier.verify` before parsing or persistence.
- Mapper adds `findActiveChatAppByNormalizedIdentifier(String identifier)` returning at most one row.
- Account resolution uses business-number candidates from `From`, `To`, `businessNumber`, and `businessPhoneNumber`, with status callback direction rules already represented in the current service.

- [x] **Step 1: Write failing security/routing tests.**

  Assert invalid/missing signatures fail, expired timestamps fail, raw body is not logged, two different business numbers route to two different accounts, unknown numbers fail, and duplicate events remain idempotent.

- [x] **Step 2: Run tests to verify the current bypass fails the new contract.**

  ```bash
  mvn -q -Dtest=ChatAppWebhookInboxServiceTest,ChatAppWebhookVerifierTest test
  ```

  Expected: FAIL because `verifier.verify` is currently commented out and account resolution still requires exactly one active account.

- [x] **Step 3: Restore verification and replace fixed-account routing.**

  Verify the raw body before JSON parsing; remove full-body INFO logging; normalize candidate business numbers with `ContactPointUtil`; require exactly one active account match; return `CHATAPP_WEBHOOK_ACCOUNT_UNRESOLVED` for zero or multiple matches.

- [x] **Step 4: Run security/routing tests and relevant regression tests.**

  ```bash
  mvn -q -Dtest=ChatAppWebhookInboxServiceTest,ChatAppWebhookVerifierTest,ChatAppControllerTest test
  ```

  Expected: all selected tests pass.

### Task 5: Admin frontend

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/pages/AdminWhatsAppAccountsPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx`
- Create or modify: `demo/message-center-spring/frontend/src/components/whatsapp/AdminWhatsAppCallbackPanel.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/AdminWhatsAppAccountsPage.test.tsx`
- Test: `demo/message-center-spring/frontend/src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx`

**Interfaces:**
- Add typed API functions `fetchAdminWhatsAppCallbacks`, `updateAdminWhatsAppPhoneCallback`, and `updateAdminWhatsAppAccountCallback`.
- Types mirror the redacted server projection and never contain AccessKey, Secret, custSpaceId, provider RequestId, or full phone.

- [x] **Step 1: Write failing UI/API contract tests.**

  Assert admin users see callback controls, ordinary users do not; phone rows render both URLs; account rows do not render `UpCallbackUrl`; save sends only URL/flag/version fields; 409 refreshes state; failed writes show “最近写入失败” and do not claim activation.

- [x] **Step 2: Run frontend tests to verify they fail.**

  ```bash
  cd demo/message-center-spring/frontend
  npm test -- --run src/pages/AdminWhatsAppAccountsPage.test.tsx src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx
  ```

  Expected: FAIL because callback endpoints and panel are not implemented.

- [x] **Step 3: Add the typed endpoints and panel.**

  Reuse the existing admin WhatsApp page and design tokens. Use controlled URL inputs, explicit save icons/buttons, loading/error states, disabled duplicate submission, and version-conflict reload. Do not add a separate credentials form.

- [x] **Step 4: Run UI tests and build.**

  ```bash
  npm test -- --run src/pages/AdminWhatsAppAccountsPage.test.tsx src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx
  npm run build
  ```

  Expected: selected tests pass and production build exits 0.

### Task 6: OpenAPI, documentation, and release gates

**Files:**
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `docs/superpowers/README.md`
- Create: `docs/superpowers/reviews/2026-09-20-whatsapp-cams-webhook-configuration-verification.md`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/architecture/ArchitectureBoundaryTest.java`

- [x] **Step 1: Add the public HTTP contract.**

  Document the three callback routes, redacted response schema, structured errors, admin security requirement, version conflict behavior, and the fact that provider state is `UNKNOWN` without an official readback API.

- [x] **Step 2: Add architecture boundary assertions.**

  Assert frontend/API layers do not import CAMS SDK classes, controller does not resolve secrets, and webhook projector does not choose accounts.

- [x] **Step 3: Run focused backend/frontend gates.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WhatsAppCallbackSchemaContractTest,AliyunWhatsAppCallbackGatewayTest,WhatsAppCallbackConfigServiceTest,WhatsAppCallbackConfigControllerTest,ChatAppWebhookInboxServiceTest,ChatAppWebhookVerifierTest,ArchitectureBoundaryTest test
  mvn -q -DskipTests compile
  cd ../frontend
  npm test -- --run src/pages/AdminWhatsAppAccountsPage.test.tsx src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx
  npm run build
  ```

- [ ] **Step 4: Execute real CAMS test-number acceptance.**（待授权部署环境和公网回调地址）

  Use a dedicated test number and existing safe credentials. Record only action name, masked number suffix, scope id, provider RequestId, timestamp, and result outside git. Verify an inbound message and a delivery status reach the new HTTPS URL, pass signature verification, route to the correct account, and do not reach the old URL.

- [x] **Step 5: Write the verification record and review git boundaries.**

  Record actual command output, skipped environment gates, and remaining risk in the review file. Run:

  ```bash
  git diff --check
  git status --short
  ```

  Do not stage or revert unrelated user changes. Completion requires focused tests/builds and real CAMS gate to be explicitly marked pass or blocked with evidence.

## Stop Conditions

- If the installed SDK request/response shape differs from the documented source, stop the gateway task and record the actual generated type; do not guess fields.
- If CAMS rejects the target callback URL or credentials lack `UpdatePhoneWebhook` permission, keep the feature unavailable and report the provider error; do not fall back to a private console API.
- If webhook signatures or business-number fields cannot be verified against a real test callback, do not enable multi-account production routing.
- If the existing database migration cannot enforce scope/account uniqueness, stop before frontend work and fix the schema contract.
