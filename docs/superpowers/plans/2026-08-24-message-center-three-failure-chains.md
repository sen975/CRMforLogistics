# 消息中心三条独立故障链路实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 稳定企业微信联系人 A/B 切换，修复 ChatApp 成功状态被跳过和邮件认证诊断链路，同时保持既有企业微信权限与官方 API 准入边界。

**Architecture:** `WeComViewerService` 是 Viewer Session 生命周期的唯一 owner：session 按 ID 独立存储、按 token 归属校验、单次消费，并在受限容量下确定性淘汰最旧会话。前端用请求代次和 `AbortController` 阻断失效请求对当前联系人 UI 的回写；`ThreadPage` 的普通时间线与 `WeComTimelineSegment` 的 SDK/session 链路分别隔离。ChatApp 和邮件各自在既有服务与异常合同中修复，不复用企业微信逻辑。

**Tech Stack:** Java 17、Spring Boot 3.4、JUnit 5、Mockito、React 18、TypeScript、TanStack Query、Vitest、Vite、企业微信 OpenDataFrame、CAMS、Jakarta Mail。

## 执行状态（2026-08-24）

- Task 1-6 的实现和专项回归已完成：Viewer session 并存与租约隔离、Viewer/ThreadPage 请求代次、ChatApp `submitted` 投影、邮件认证错误合同和只读验收脚本均已有对应回归。
- 本轮发现并修复了独立的 WebMvc 测试上下文缺口：所有测试条件化企业微信 Controller 的套件显式设置 `app.wecom-enabled=true` 与测试 `app.wecom-suite-id`，不改变生产开关或权限边界。
- Task 7 的前端全量回归已通过；后端全量回归实际执行但被 Docker Desktop 阻断。Testcontainers 在受控执行环境中仍从 Docker daemon 收到 HTTP 400 和空能力集，10 个容器型集成测试无法启动。该外部运行时阻断未解除前，不运行浏览器验收，不生成 Jar、dist 或部署 ZIP。
- 下列 Task 2-6 的细粒度复选项保留为原始执行清单；实际命令、结果和阻断以验收记录的“执行更新”章节为准。

## Global Constraints

- 保留 `WeComViewerService.readViewerMessages` 的 `wecomUserId` 数据权限过滤；不得扩大成员可见范围。
- 不修改企业微信官方 API 准入设计，不引入旧 Suite Token 或全局 corp token 路线。
- Viewer Session 仍须校验有效 viewer token、session token 归属、消息与联系点范围、TTL 和单次消费。
- Viewer Session 可并存；全局与每 token 的容量上限必须有确定的同步淘汰顺序。
- `Success`、`Successful`、`Succeeded`、`OK` 只归一化为上游已接受的 `submitted`，不得写成 `delivered`。
- IMAP 用户名优先级固定为已保存 `imapUser`、环境变量、`account_identifier`；密码及裸堆栈不得返回前端或日志。
- 未通过专项回归与后端全量测试前，不打 Jar；未通过前端测试、类型检查和生产构建前，不生成部署 ZIP。
- 不手改 `target/`、`dist/` 或其他生成物；构建物只由项目命令产生。
- 当前工作树的 Git 元数据入口异常时，先记录阻断与可见文件改动，禁止提交、重置或覆盖任何既有 WIP。

---

## 文件边界

- `backend/.../service/wecom/WeComViewerService.java`：Viewer Session 的创建、读取、单次消费、TTL、token 归属、容量和引用租约。
- `backend/.../service/wecom/WeComViewerReferenceLeaseRegistry.java`：按 session ID 管理消息引用租约，不拥有 token/session 淘汰策略。
- `backend/.../service/wecom/WeComViewerServiceTest.java`：session 并存、乱序创建、消费、权限、TTL、容量合同。
- `frontend/src/api/endpoints.ts`：Viewer HTTP 请求接受可选 `AbortSignal`，不拥有联系人代次。
- `frontend/src/hooks/useWeComViewer.ts`：每次 `prepareSegment` 将 signal 传给 session create/detail load 与 SDK 初始化后的有效性检查。
- `frontend/src/components/wecom/WeComTimelineSegment.tsx`：渲染 effect 的 generation、取消、当前容器与 frame 所有权。
- `frontend/src/components/wecom/WeComTimelineSegment.test.tsx`：延迟 A、后发 B、晚到失败和旧 SDK 的回归用例。
- `frontend/src/pages/ThreadPage.tsx`：普通消息、读取标记、滚动状态和渠道选择的联系人代次隔离。
- `frontend/src/pages/ThreadPage.wecom.test.tsx`：A -> B -> A 与单一企业微信联系人全高区域。
- `backend/.../service/chatapp/ChatAppMessageStatusNormalizer.java`：CAMS 状态到内部状态的唯一归一化 owner。
- `backend/.../channel/chatapp/ChatAppPollingProjector.java`：仅跳过实际重复事件，投影正常 `submitted` 事件。
- `backend/.../service/chatapp/ChatAppMessageStatusNormalizerTest.java`、`.../channel/chatapp/ChatAppPollingProjectorTest.java`：成功状态与重复事件投影回归。
- `backend/.../channel/email/EmailSyncSettings.java`：IMAP 配置解析与脱敏诊断数据。
- `backend/.../channel/email/EmailSyncService.java`、`OpenSslImapClient.java`：认证失败映射、定时同步诊断与安全错误传播。
- `backend/.../web/GlobalExceptionHandler.java`、`.../channel/email/EmailController.java`：稳定 `EMAIL_IMAP_AUTHENTICATION_FAILED` HTTP 合同。
- `backend/.../channel/email/*Test.java`：优先级、错误合同和诊断回归。
- `scripts/message-center-server-acceptance.sh`：只读验证已部署服务的通道账号、邮件计数、最近同步错误和构建哈希。
- `docs/superpowers/reviews/2026-08-24-message-center-three-failure-chains-verification.md`：实际命令、哈希、浏览器证据和外部凭证风险。

---

### Task 1: 基线审计与专项测试

**Files:**
- Modify: `docs/superpowers/plans/2026-08-24-message-center-three-failure-chains.md`
- Create: `docs/superpowers/reviews/2026-08-24-message-center-three-failure-chains-verification.md`
- Inspect: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`
- Inspect: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`
- Inspect: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx`
- Inspect: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`

**Interfaces:**
- `ViewerSession(id, viewerAuthToken, contactPointId, expiresAtEpochSecond, createdOrder, messages)` remains the session contract.
- The active-session cap is owned by `WeComViewerService`, never by React or `WeComViewerReferenceLeaseRegistry`.

- [x] **Step 1: Record Git and documentation boundaries.**

  Run:

  ```bash
  git status --short
  git rev-parse --show-toplevel
  find docs/superpowers -maxdepth 3 -type f | sort
  ```

  Record any Git metadata failure verbatim in the verification review; do not repair, reset, or stage files.

- [x] **Step 2: Trace current Viewer ownership and request order.**

  Read `createViewerSession`, `viewerSession`, `storeViewerSession`, `cleanupExpiredSessions`, `removeViewerSessionsForToken`, `useWeComViewer.prepareSegment`, the timeline effect cleanup, and the `ThreadPage` contact-change effect. Record which caller can still complete after an effect cleanup.

- [x] **Step 3: Run backend Viewer baseline.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WeComViewerServiceTest,WeComViewerControllerTest,WeComViewerCredentialBoundaryTest,WeComViewerAuditTrailTest test
  ```

  Expected before the fix: existing tests pass but lack the A/B concurrent-session contract.

- [x] **Step 4: Run ChatApp and email baselines.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest,EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
  ```

  Capture exact failures or passing gaps in the verification review.

- [x] **Step 5: Run frontend Viewer baseline.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- --run src/components/wecom/WeComTimelineSegment.test.tsx src/components/wecom/WeComConversationPanel.test.tsx src/pages/ThreadPage.wecom.test.tsx
  ```

  Confirm existing tests do not drive route contact A -> B -> A with deferred requests.

### Task 2: 让 Viewer Session 按 ID 并存

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerServiceTest.java`

**Interfaces:**
- Add `MAX_ACTIVE_VIEWER_SESSIONS_PER_TOKEN` in `WeComViewerService`.
- Add `removeOldestViewerSessionForToken(String viewerAuthToken)`; it removes exactly one session with the smallest `createdOrder`, releases only that ID's leases, and is called while holding the service monitor.
- `storeViewerSession(ViewerSession session)` no longer calls `removeViewerSessionsForToken(session.viewerAuthToken())`; it cleans expired sessions, enforces global capacity, then token capacity before inserting the new session.

- [ ] **Step 1: Add failing concurrent-session tests.**

  Add test fixtures for `contact-a` and `contact-b`, both owned by `wecom-user`, then assert:

  ```java
  String sessionA = service.createViewerSession("wecom:contact-a", token, List.of("a-1")).viewerSessionId();
  String sessionB = service.createViewerSession("wecom:contact-b", token, List.of("b-1")).viewerSessionId();
  assertThat(service.viewerSession(sessionA, token).messages()).extracting("msgid").containsExactly("a-1");
  assertThat(service.viewerSession(sessionB, token).messages()).extracting("msgid").containsExactly("b-1");
  ```

  Add separate tests that consuming A leaves B readable, a second token cannot consume A, an entity owned by another `userid` is rejected, expiry removes only the expired ID, and overflow evicts the oldest active same-token session but keeps the just-created session.

- [ ] **Step 2: Verify RED.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WeComViewerServiceTest test
  ```

  Expected: the A session is missing after B is created under current token-wide removal behavior.

- [ ] **Step 3: Implement token-scoped deterministic capacity.**

  Keep all mutation paths synchronized. Replace token-wide deletion during creation with a count-based loop that removes the minimum `createdOrder` session for that token until the cap permits insertion. Global capacity continues to evict the global oldest session before insert. Existing `viewerSession` keeps `viewerSessions.remove(viewerSessionId, session)`, so it consumes only that ID.

- [ ] **Step 4: Verify GREEN.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WeComViewerServiceTest,WeComViewerControllerTest,WeComViewerCredentialBoundaryTest,WeComViewerAuditTrailTest test
  ```

  Expected: all session ownership, TTL, single-use, lease, A/B coexistence and overflow tests pass.

### Task 3: 阻断企业微信组件的失效请求回写

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx`

**Interfaces:**
- Extend `createWeComViewerSession` and `fetchWeComViewerSession` with optional `{ signal?: AbortSignal }` without changing URLs, payloads, or viewer auth headers.
- Extend `WeComViewerHandle.prepareSegment(contactPointId, messageIds, signal?)` and reject an aborted caller with `AbortError` before it can return a prepared frame.
- `WeComTimelineSegment` owns `generationRef`; only its latest generation may set status/failure/preview or mount/dispose its frame.

- [ ] **Step 1: Add deferred A/B UI tests.**

  In `WeComTimelineSegment.test.tsx`, use two deferred `prepareSegment` promises. Rerender from `wecom:contact-a` to `wecom:contact-b`, resolve A after B, then assert only B's factory mounted and A did not set failure. Add A -> B -> A, late A failure after B success, and late SDK resolution after cleanup cases.

- [ ] **Step 2: Verify RED.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- --run src/components/wecom/WeComTimelineSegment.test.tsx
  ```

  Expected: at least the late A completion test fails under the pre-generation implementation.

- [ ] **Step 3: Add cancellation and generation checks.**

  Create one `AbortController` per render effect. Increment the generation before enqueuing. Pass `controller.signal` into `prepareSegment`; after viewer token, SDK, session create and session detail boundaries, throw `AbortError` when `signal.aborted` or generation changed. In cleanup call `controller.abort()`, release only the captured registry key, and dispose only the captured frame.

- [ ] **Step 4: Verify GREEN.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- --run src/components/wecom/WeComTimelineSegment.test.tsx src/components/wecom/WeComConversationPanel.test.tsx
  ```

  Expected: late A responses never mutate B loading, error or container state.

### Task 4: 隔离 ThreadPage 的联系人请求与渠道状态

**Files:**
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`

**Interfaces:**
- `ThreadPage` owns a `contactRequestGenerationRef`; only the generation started for current `contactId` may write `allItems`, cursors, loading state, read mutation side effects or scroll state.
- `clearChannel`, `clearSelection` and `timelineScrollTopRef` are reset for the current contact generation only.

- [ ] **Step 1: Add route-driven A -> B -> A tests.**

  Use a route harness with deferred `fetchThread`/`fetchContact`. Resolve the first A request after B, assert no A messages or selection are rendered for B, then navigate back to A and assert only the final A response is visible. Cover a late `markContactRead` completion and a late contact fetch as no-op for the new route.

- [ ] **Step 2: Verify RED.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- --run src/pages/ThreadPage.wecom.test.tsx
  ```

  Expected: stale promise callbacks can write the newer route's state before the change.

- [ ] **Step 3: Implement generation-safe callbacks.**

  Capture `const generation = ++contactRequestGenerationRef.current` in the contact effect. Check `generation === contactRequestGenerationRef.current` before every state write and query invalidation callback. Apply the same check to refresh, older-page and SSE fetch callbacks. Preserve the mixed timeline for ChatApp/email/phone and replace only the timeline content area while `selectedChannel === 'wecom'`.

- [ ] **Step 4: Verify GREEN.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- --run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx
  ```

  Expected: A -> B -> A is deterministic; a WeCom-only contact automatically fills the timeline area; non-WeCom channels remain in the mixed timeline.

### Task 5: 修复 ChatApp 成功状态投影

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessageStatusNormalizer.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessageStatusNormalizerTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java`

**Interfaces:**
- `ChatAppMessageStatusNormalizer.normalize(String upstreamStatus)` maps case-insensitive `success`, `successful`, `succeeded`, `ok` to `submitted`.
- Duplicate detection remains keyed to the durable upstream event identity; a new success event must reach persistence and contact projection.

- [ ] **Step 1: Add failing success-state and projection tests.**

  Parameterize the normalizer with the four accepted spellings and assert `submitted`. In the projector test, provide a CAMS payload with `status=Success`, a new event ID and a contact identity, then assert `saved == 1`, the projection service is called, and no `DUPLICATE_EVENT` result occurs. Add a second identical event-id case asserting only that second case is skipped as duplicate.

- [ ] **Step 2: Verify RED.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest test
  ```

  Expected: `Success` is currently unrecognized or bypasses normal projection.

- [ ] **Step 3: Implement the smallest owner-level normalization.**

  Normalize status before the projector's skip decision. Use the normalized `submitted` state for storage; do not infer `delivered` from CAMS acceptance. Preserve the original durable event identity in duplicate checks.

- [ ] **Step 4: Verify GREEN and document production query.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest,ChatAppContactProjectionTest test
  ```

  Include in the verification review a read-only SQL query that counts `UNRECOGNIZED_MESSAGE_STATUS` and recent saved ChatApp messages by current account without selecting content.

### Task 6: 完成邮件认证、错误合同与只读诊断

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncSettings.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/OpenSslImapClient.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncSettingsDiagnosticTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailControllerTest.java`
- Create: `scripts/message-center-server-acceptance.sh`

**Interfaces:**
- `EmailSyncSettings` returns a redacted diagnostic projection containing source labels, host, port, provider and no credential value.
- `EmailException` with code `EMAIL_IMAP_AUTHENTICATION_FAILED` reaches `GlobalExceptionHandler` unchanged and produces the existing structured `ApiError` envelope.
- The server script accepts only endpoint/base URL and database command environment variables; it emits account status, message count, latest sync error code and artifact SHA-256, never a password or email body.

- [ ] **Step 1: Add failing precedence and error-contract tests.**

  Assert persisted `imapUser` wins over environment and account identifier; absent persisted value falls back in that order. Mock IMAP auth failure and assert the controller response code is `EMAIL_IMAP_AUTHENTICATION_FAILED`, response body has no password/stack trace, and scheduler diagnostic includes only source labels and error code.

- [ ] **Step 2: Verify RED.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
  ```

  Expected: existing controller or service behavior fails one or more stable-contract assertions.

- [ ] **Step 3: Implement the email owner changes.**

  Resolve IMAP user once in `EmailSyncSettings`; preserve an explicit source enum/label for diagnostics. Map 139/OpenSSL authentication rejection (including `errno=1310`) to `EmailException("EMAIL_IMAP_AUTHENTICATION_FAILED", ...)`; let it escape the controller to `GlobalExceptionHandler`. Log only redacted configuration source and error code from scheduled sync.

- [ ] **Step 4: Add read-only server acceptance script.**

  Script operations are limited to: `curl -fsS` health/account summaries, `psql -Atc` count and latest error-code queries, and `shasum -a 256` for named existing artifacts. It exits nonzero for missing inputs or endpoint/database errors and does not alter services, files, rows or schedules.

- [ ] **Step 5: Verify GREEN.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
  shellcheck scripts/message-center-server-acceptance.sh
  ```

  If `shellcheck` is unavailable, run `bash -n scripts/message-center-server-acceptance.sh` and record that substitution.

### Task 7: 统一验收、浏览器验证、构建与交付记录

**Files:**
- Modify: `docs/superpowers/reviews/2026-08-24-message-center-three-failure-chains-verification.md`

- [ ] **Step 1: Run backend focused suite.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Dtest=WeComViewerServiceTest,WeComViewerControllerTest,WeComViewerCredentialBoundaryTest,WeComViewerAuditTrailTest,ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest,ChatAppContactProjectionTest,EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
  ```

- [ ] **Step 2: Run frontend focused suite and production checks.**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:source
  npm run test:ui -- --run src/components/wecom/WeComTimelineSegment.test.tsx src/components/wecom/WeComConversationPanel.test.tsx src/pages/ThreadPage.wecom.test.tsx
  npm run build
  ```

  Verify `dist/index.html` references only generated hashed assets from that build.

- [ ] **Step 3: Browser A -> B -> A verification.**

  Start the existing local backend/frontend only after focused tests pass. Use the browser to open two WeCom contacts, switch A -> B -> A while delaying or throttling A, and capture evidence that only the current contact frame remains mounted; also check loading, failure retry, desktop and mobile layout.

- [ ] **Step 4: Run full regressions.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q test

  cd ../frontend
  npm test
  ```

  Stop and report any pre-existing or introduced failures before building artifacts.

- [ ] **Step 5: Build only after Steps 1-4 pass.**

  ```bash
  cd demo/message-center-spring/backend
  mvn -q -Pproduction -DskipTests package
  shasum -a 256 target/message-center.jar

  cd ../frontend
  npm run build
  cd dist
  zip -r ../message-center-frontend.zip .
  shasum -a 256 ../message-center-frontend.zip
  ```

  Record exact hashes, the generated `dist/index.html` asset hashes, replacement commands and rollback commands in the verification review. Do not claim 139 authentication solved when the server still returns `errno=1310`; record it as an external valid-IMAP-authorization-code blocker.

## Review Checklist

- [ ] A/B session coexistence, session A consumption isolation, user/token denial, TTL and deterministic capacity are covered by backend tests.
- [ ] The frontend test suite proves stale A success/failure/SDK completion cannot affect B or final A.
- [ ] ChatApp maps the four accepted upstream success spellings to `submitted` and only genuine duplicates skip projection.
- [ ] IMAP precedence, stable error code, redaction and scheduler diagnostics are covered without exposing a password.
- [ ] Full test, build, browser and artifact evidence is written before completion is claimed.
- [ ] External 139 credential outcomes are explicitly separated from code outcomes.
