# 管理员平台工作台与多 CAMS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 每个任务完成后运行该任务的专项测试；不要使用 `git add .`。

**Goal:** 将 CAMS 配置、WhatsApp 账号分配和 WhatsApp 模板审批迁移到管理员专用工作台，并把 CAMS 从单例扩展为可按 scope 管理的多个实例。

**Architecture:** `whatsapp_provider_scopes` 是 CAMS 实例唯一 owner，账号、模板和审批申请通过 `provider_scope_id` 保持空间隔离。后端提供按 scope 的管理员 API，并继续由服务端执行管理员授权；React 只负责路由、展示和调用 API。普通 `/templates` 仅保留共享模板、申请和“我的申请”，管理员审批迁移到独立路由。

**Tech Stack:** Spring Boot 3.4、Java 17、MyBatis-Plus、PostgreSQL/Flyway、Spring Security、React 18、TypeScript、Ant Design、TanStack Query、Vitest、JUnit 5。

## Global Constraints

- 不创建第二套后台应用、登录或权限体系。
- AccessKey Secret 只在服务端加密保存，任何响应、日志和前端状态不得回显。
- 已被账号或模板引用的 CAMS scope 只能停用，不物理删除。
- 所有跨入口的 scope、账号、模板和审批状态以服务端 owner 为准，前端不得自行推断。
- 数据库已有 CAMS 配置时，测试和同步必须使用指定 scope 的数据库配置；不得静默混用环境变量。
- 所有列表、分页、同步和重试都有明确上界；未知上游结果保持执行中并由有界 reconcile 收敛。
- 保留用户已有未提交改动，不修改无关 contact memory、待办或环境配置文件。

---

### Task 1: 扩展多 CAMS scope 数据合同与授权边界

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V77__whatsapp_multi_cams_admin_workbench.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAdminAuthorization.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAdminAuthorizationTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapperIntegrationTest.java`

**Interfaces:**
- Produces `WhatsAppProviderScopeEntity.displayName`, `lastTestedAt`, `lastTestStatus`, `lastTestErrorCode`, `lastSyncedAt`, `lastSyncStatus`, `lastSyncErrorCode` and `version`.
- Produces mapper methods `findAllEnterpriseScopes()`, `findEnterpriseScopeById(UUID)`, `insertAdminScope(...)`, `updateAdminScope(...)`, `blockAdminScope(...)`, `touchTestResult(...)`, `touchSyncResult(...)`.
- Produces `WhatsAppAdminAuthorization.requireAdmin(UUID actorUserId)` using `RoleMapper.userHasRole(actorUserId, "admin")` as the authorization source.

- [ ] **Step 1: Write the failing schema and authorization tests.**

```java
@Test
void duplicateCustSpaceIdIsRejectedAndScopeHasVersion() {
    jdbcTemplate.update("insert into whatsapp_provider_scopes(provider, external_scope_id, scope_type, status) values ('ALIYUN_CAMS','space-1','ENTERPRISE_API','READY')");
    assertThatThrownBy(() -> jdbcTemplate.update("insert into whatsapp_provider_scopes(provider, external_scope_id, scope_type, status) values ('ALIYUN_CAMS','space-1','ENTERPRISE_API','READY')"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbcTemplate.queryForObject("select version from whatsapp_provider_scopes where external_scope_id='space-1'", Long.class)).isEqualTo(0L);
}

@Test
void nonAdminIsForbidden() {
    when(roles.userHasRole(USER_ID, "admin")).thenReturn(false);
    assertThatThrownBy(() -> authorization.requireAdmin(USER_ID))
        .isInstanceOf(WhatsAppAuthorizationException.class)
        .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
}
```

- [ ] **Step 2: Run tests and verify the new columns and owner do not exist.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppProviderScopeMapperIntegrationTest,WhatsAppAdminAuthorizationTest test`

Expected: FAIL because migration columns, mapper methods and authorization service are absent.

- [ ] **Step 3: Add the migration and mapper projections.**

`V77__whatsapp_multi_cams_admin_workbench.sql` must add nullable `display_name`, status/timestamp/error columns, `version bigint not null default 0`, and a check that `version >= 0`; backfill `display_name` from `external_scope_id`. Add partial index for enterprise scopes by status and `created_at`. Extend every mapper SELECT to include the new columns and implement conditional updates with `where id = #{scopeId}::uuid and version = #{expectedVersion}`. `insertAdminScope` must use `on conflict (provider, external_scope_id) do nothing` and initialize `scope_type='ENTERPRISE_API'`, `owner_user_id=null`, `status='READY'`, `identity_status='IDENTITY_PENDING'`.

- [ ] **Step 4: Implement and test the single authorization owner.**

`WhatsAppAdminAuthorization.requireAdmin` calls `SecurityUtil.currentUserId()` only at controller boundaries and then checks `RoleMapper.userHasRole`; it throws `WhatsAppAuthorizationException("WHATSAPP_ADMIN_REQUIRED", HttpStatus.FORBIDDEN)` for non-admin and null actors. Controllers added later must call this service rather than inspect authorities independently.

- [ ] **Step 5: Run the focused tests.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppProviderScopeMapperIntegrationTest,WhatsAppAdminAuthorizationTest test`

Expected: PASS.

- [ ] **Step 6: Commit only Task 1 files.**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V77__whatsapp_multi_cams_admin_workbench.sql demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAdminAuthorization.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppAdminAuthorizationTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapperIntegrationTest.java
git commit -m "feat: add multi-CAMS scope contract"
```

### Task 2: Implement multi-CAMS configuration service and APIs

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCamsConfigService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCamsConfigController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCamsConfigServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppCamsConfigControllerTest.java`

**Interfaces:**
- Produces `list()`, `create(UUID, ConfigRequest)`, `update(UUID, UUID, ConfigRequest)`, `block(UUID, UUID, long)`, `test(UUID, UUID)`, `sync(UUID, UUID)` service methods and scope-specific `ConfigView`/`TestResult` projections.
- Produces `GET/POST /api/admin/whatsapp/cams`, `PUT/POST /api/admin/whatsapp/cams/{scopeId}`, `POST /{scopeId}/test`, `POST /{scopeId}/sync`.

- [ ] **Step 1: Add failing service tests for create, secret retention and scope isolation.**

```java
@Test
void updateWithBlankSecretRetainsEncryptedSecret() {
    when(scopes.findEnterpriseScopeById(SCOPE_ID)).thenReturn(existingScope("space-1", encrypted("old-secret")));
    service.update(ADMIN_ID, SCOPE_ID, new ConfigRequest("主空间", "space-1", "key-2", "", "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", 0));
    verify(scopes).updateAdminScope(eq(SCOPE_ID), eq("主空间"), eq("space-1"), contains("old-secret"), eq(1L), eq(0L));
}
```

- [ ] **Step 2: Run the focused service tests and verify failure.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppCamsConfigServiceTest,WhatsAppCamsConfigControllerTest test`

Expected: FAIL because the service is still single-scope and endpoints only expose `/api/admin/whatsapp/cams` without IDs.

- [ ] **Step 3: Implement service validation and secret retention.**

Validate display name, CustSpaceId, AccessKey ID, Region and Endpoint length/blank constraints. On create reject duplicate `(ALIYUN_CAMS, custSpaceId)` with a structured 409. On update require expected version; blank `accessKeySecret` decrypts and retains the old secret, while a nonblank value replaces it. `ConfigView` returns `scopeId`, `displayName`, `custSpaceId`, masked AccessKey ID, region, endpoint, status, version and last test/sync projections, never secret.

- [ ] **Step 4: Implement scope-specific controller routes and authorization.**

Every method calls `WhatsAppAdminAuthorization.requireAdmin(SecurityUtil.currentUserId())`, loads the requested scope, and returns 404 for unknown scope or 409 for blocked scope writes. `block` is logical status transition only. `test` calls `gateway.syncConfiguredPhoneNumbers(scope)` and persists success/failure projection; it must not mutate account rows. `sync` delegates to the updated account sync service in Task 3.

- [ ] **Step 5: Run tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppCamsConfigServiceTest,WhatsAppCamsConfigControllerTest test && mvn -q -DskipTests compile`

Expected: PASS.

- [ ] **Step 6: Commit Task 2.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCamsConfigService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCamsConfigController.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCamsConfigServiceTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppCamsConfigControllerTest.java
git commit -m "feat: manage multiple CAMS configurations"
```

### Task 3: Make account sync and assignment scope-aware

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberControllerTest.java`

**Interfaces:**
- Produces `list(UUID actor, UUID scopeId)`, `sync(UUID actor, UUID scopeId)` and account projection including `scopeId`, `version`, `lastSyncedAt`.
- Produces `/api/admin/whatsapp/cams/{scopeId}/accounts` and `/api/admin/whatsapp/cams/{scopeId}/sync`; legacy unscoped list/sync routes are removed after frontend migration.

- [ ] **Step 1: Add failing tests for scope isolation and single/multiple account selection contract.**

```java
@Test
void syncUsesRequestedScopeConfigAndNeverImportsAnotherScope() {
    service.sync(ADMIN_ID, SCOPE_A);
    verify(gateway).syncConfiguredPhoneNumbers(scopeA);
    verify(accounts).upsertAdminSynced(any(), eq(SCOPE_A), any());
    verify(accounts, never()).upsertAdminSynced(any(), eq(SCOPE_B), any());
}

@Test
void assignmentRejectsAccountFromDifferentScope() {
    assertThatThrownBy(() -> service.assign(ADMIN_ID, SCOPE_A, ACCOUNT_FROM_B, OWNER_ID, "reason", 0L))
        .isInstanceOf(WhatsAppAuthorizationException.class);
}
```

- [ ] **Step 2: Run tests and verify unscoped behavior fails the new contract.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AdminWhatsAppAccountSyncServiceTest,AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppPhoneNumberControllerTest test`

Expected: FAIL because current sync resolves one environment/default scope and list/assignment have no scope argument.

- [ ] **Step 3: Implement requested-scope sync and assignment guards.**

Resolve the scope by ID through `WhatsAppCamsConfigService.requireReadyScope(scopeId)`, pass that entity to `gateway.syncConfiguredPhoneNumbers`, and mark unavailable accounts only inside the requested scope. `list` queries only `provider_scope_id = scopeId`. Assignment, reclaim, transfer and history load the account and assert `account.providerScopeId == scopeId` before mutating. Return 409 on version conflict and 404 for unknown account/scope.

- [ ] **Step 4: Add scoped controller routes.**

Use `@RequestMapping("/api/admin/whatsapp/cams/{scopeId}/accounts")`; keep assignment paths relative to that scope (`/{accountId}/assign`, `/reclaim`, `/transfer`, `/assignment-history`). Every request includes `expectedVersion`; controller delegates role checks to the shared authorization service.

- [ ] **Step 5: Run focused tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AdminWhatsAppAccountSyncServiceTest,AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppPhoneNumberControllerTest test && mvn -q -DskipTests compile`

Expected: PASS.

- [ ] **Step 6: Commit Task 3.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberController.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncServiceTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppPhoneNumberServiceTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppPhoneNumberControllerTest.java
git commit -m "feat: scope WhatsApp account administration by CAMS"
```

### Task 4: Add admin overview and platform/account frontend routes

**Files:**
- Create: `demo/message-center-spring/frontend/src/pages/AdminHomePage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/AdminPlatformsPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/AdminWhatsAppAccountsPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/AdminPlatformPages.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.test.tsx`

**Interfaces:**
- Produces endpoint helpers `fetchAdminCams`, `createAdminCams`, `updateAdminCams`, `blockAdminCams`, `testAdminCams`, `syncAdminCams`, `fetchAdminScopedWhatsAppAccounts`, `assignAdminScopedWhatsAppAccount`, `reclaimAdminScopedWhatsAppAccount`, `transferAdminScopedWhatsAppAccount`, `fetchAdminScopedAssignmentHistory`.
- Produces routes `/admin`, `/admin/platforms`, `/admin/whatsapp/accounts`; account page receives `scopeId` from a CAMS selector and implements auto-select when exactly one usable number exists.

- [ ] **Step 1: Write failing route and interaction tests.**

```tsx
it('shows admin routes only to administrators', async () => {
  auth.isAdmin = true;
  renderWithRouter('/admin/platforms');
  expect(await screen.findByRole('heading', { name: '平台接入管理' })).toBeVisible();
  auth.isAdmin = false;
  renderWithRouter('/admin/platforms');
  expect(await screen.findByText('无权访问')).toBeVisible();
});

it('auto-selects the only usable phone in a CAMS scope', async () => {
  api.fetchAdminCams.mockResolvedValue([scope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([usableAccount]);
  renderWithRouter('/admin/whatsapp/accounts');
  expect(await screen.findByText('已自动选择唯一可用号码')).toBeVisible();
  expect(screen.queryByRole('combobox', { name: '选择号码' })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run tests and verify the routes/pages are missing.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/pages/AdminPlatformPages.test.tsx src/pages/ChannelSettingsPage.test.tsx`

Expected: FAIL because the admin routes and scoped endpoint helpers do not exist.

- [ ] **Step 3: Add typed API helpers and admin route guard.**

Extend `api/types.ts` with `AdminCamsScope`, `AdminCamsRequest`, `AdminScopedAccount` and status projections. Add endpoint helpers using `/admin/whatsapp/cams/{scopeId}`. Add an `AdminGuard` in `router.tsx` that reads `useAuth().isAdmin` and renders an Ant Design `Result` with `无权访问`; this is presentation only and does not replace backend authorization.

- [ ] **Step 4: Build the three platform pages.**

`AdminHomePage` uses bounded queries for scope summaries and pending approvals. `AdminPlatformsPage` renders a list of CAMS instances with add/edit/block/test/sync actions; the secret input is empty on edit and blank means retain. `AdminWhatsAppAccountsPage` renders a scope selector first, then scoped account table and assignment modal. With one usable account, set selected account ID immediately and omit the account selector; with multiple accounts require selection before showing the sales assignment action. Use existing `AdminWhatsAppAccountPanel` table patterns and icons.

- [ ] **Step 5: Remove CAMS and admin WhatsApp account panels from `/settings/channels`.**

Keep email and enterprise WeChat rows. Replace the CAMS section and `AdminWhatsAppAccountPanel` with a link to `/admin/platforms` for administrators; non-admin users retain only their own WhatsApp account summary if the product still exposes it elsewhere.

- [ ] **Step 6: Run frontend tests and build.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/pages/AdminPlatformPages.test.tsx src/pages/ChannelSettingsPage.test.tsx && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit Task 4.**

```bash
git add demo/message-center-spring/frontend/src/pages/AdminHomePage.tsx demo/message-center-spring/frontend/src/pages/AdminPlatformsPage.tsx demo/message-center-spring/frontend/src/pages/AdminWhatsAppAccountsPage.tsx demo/message-center-spring/frontend/src/pages/AdminPlatformPages.test.tsx demo/message-center-spring/frontend/src/api/endpoints.ts demo/message-center-spring/frontend/src/api/types.ts demo/message-center-spring/frontend/src/router.tsx demo/message-center-spring/frontend/src/components/AppLayout.tsx demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.test.tsx
git commit -m "feat: add admin platform and account pages"
```

### Task 5: Move template approvals to a dedicated admin page

**Files:**
- Create: `demo/message-center-spring/frontend/src/pages/AdminWhatsAppTemplateApprovalsPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/AdminWhatsAppTemplateApprovalsPage.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`

**Interfaces:**
- Produces `/admin/whatsapp/template-approvals` using existing `fetchTemplateChangeRequestsForReview`, `approveTemplateChangeRequest`, `rejectTemplateChangeRequest`, `retryTemplateChangeRequest`, `TemplateAdminApprovalPanel` and `TemplateChangeDiffModal`.
- `/templates` retains `SharedTemplateCardGrid`, `PublicTemplateLibrary`, `TemplateChangeRequestList`; it no longer queries or renders the review queue.

- [ ] **Step 1: Write failing page and regression tests.**

```tsx
it('renders approval queue only on the admin route', async () => {
  auth.isAdmin = true;
  renderAdminApprovals();
  expect(await screen.findByRole('heading', { name: 'WhatsApp 模板审批' })).toBeVisible();
  expect(screen.getByRole('button', { name: '批准' })).toBeVisible();
});

it('removes the approval tab from the ordinary template page', async () => {
  auth.isAdmin = true;
  renderTemplatesPage();
  await screen.findByText('发货提醒');
  expect(screen.queryByRole('tab', { name: '变更审批' })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run tests and verify the current embedded approval tab fails the regression.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/pages/AdminWhatsAppTemplateApprovalsPage.test.tsx src/pages/TemplatesPage.test.tsx`

Expected: FAIL because no dedicated page exists and `TemplatesPage` still renders the admin tab.

- [ ] **Step 3: Implement the dedicated approval page.**

Move the query and mutations currently in `TemplatesPage` into `AdminWhatsAppTemplateApprovalsPage`. Add status/applicant/template filters, bounded pagination, loading/empty/error states, `TemplateAdminApprovalPanel`, `TemplateChangeDiffModal`, reject-reason validation and mutation invalidation. The page must not expose credentials or raw JSON. Add admin navigation under WhatsApp management and an entry card from `/admin`.

- [ ] **Step 4: Remove review query and tab from `TemplatesPage`.**

Delete `reviewRequestsQuery`, approval/reject/retry mutations and the conditional `变更审批` tab. Keep direct-admin edit confirmation and user “我的申请” behavior unchanged. Update mocks and assertions in `TemplatesPage.test.tsx`.

- [ ] **Step 5: Run focused tests and build.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/pages/AdminWhatsAppTemplateApprovalsPage.test.tsx src/pages/TemplatesPage.test.tsx && npm run build`

Expected: PASS.

- [ ] **Step 6: Commit Task 5.**

```bash
git add demo/message-center-spring/frontend/src/pages/AdminWhatsAppTemplateApprovalsPage.tsx demo/message-center-spring/frontend/src/pages/AdminWhatsAppTemplateApprovalsPage.test.tsx demo/message-center-spring/frontend/src/router.tsx demo/message-center-spring/frontend/src/components/AppLayout.tsx demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx demo/message-center-spring/frontend/src/api/endpoints.ts
git commit -m "feat: move WhatsApp template approvals to admin workspace"
```

### Task 6: Align template backend scope selection and admin overview contract

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeGate.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppWorkbenchController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppWorkbenchControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java`

**Interfaces:**
- Produces `GET /api/admin/whatsapp/overview` returning per-scope counts and status projections.
- Ensures template approval execution always uses the request's stored `requested_via_account_id` and its account `provider_scope_id`; no controller field can override scope.

- [ ] **Step 1: Add failing tests for scope mismatch and overview aggregation.**

```java
@Test
void approvalRejectsWhenStoredAccountScopeDiffersFromTemplateScope() {
    assertThatThrownBy(() -> service.approve(ADMIN_ID, REQUEST_ID, "client-1", TRACE_ID))
        .isInstanceOf(WhatsAppAuthorizationException.class)
        .extracting("code").isEqualTo("WHATSAPP_TEMPLATE_SCOPE_MISMATCH");
}

@Test
void overviewReturnsIndependentCountsForEachScope() {
    var result = controller.overview();
    assertThat(result.scopes()).extracting("scopeId").containsExactly(SCOPE_A, SCOPE_B);
    assertThat(result.scopes().get(0).pendingApprovalCount()).isEqualTo(2);
}
```

- [ ] **Step 2: Run focused backend tests and verify missing overview/scope guard.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppTemplateChangeRequestServiceTest,AdminWhatsAppWorkbenchControllerTest test`

Expected: FAIL because overview endpoint and explicit stored-scope guard are absent.

- [ ] **Step 3: Implement the backend scope guard and overview projection.**

Before approval/retry execution, load the request, template and requested account; compare both provider scope IDs and use the stored account only. Return structured `WHATSAPP_TEMPLATE_SCOPE_MISMATCH` or `WHATSAPP_TEMPLATE_ACCOUNT_UNAVAILABLE`. Add a bounded overview query grouped by scope for ready/blocked state, usable account count, pending approval count, last sync and last test projection. Protect the controller with `WhatsAppAdminAuthorization`.

- [ ] **Step 4: Run tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WhatsAppTemplateChangeRequestServiceTest,AdminWhatsAppWorkbenchControllerTest,AdminWhatsAppTemplateChangeRequestControllerTest test && mvn -q -DskipTests compile`

Expected: PASS.

- [ ] **Step 5: Commit Task 6.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeGate.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestController.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppWorkbenchController.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppWorkbenchControllerTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java
git commit -m "feat: enforce CAMS scope boundaries in template admin"
```

### Task 7: Full verification, documentation and runtime UI acceptance

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-admin-platform-workbench-design.md`
- Create: `docs/superpowers/reviews/2026-09-16-admin-platform-workbench-verification.md`
- Modify: `demo/message-center-spring/README.md` only if the existing admin route/API documentation is present and needs updating

- [ ] **Step 1: Run frontend full UI tests and production build.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui && npm run build`

Expected: PASS with no test failures or TypeScript errors.

- [ ] **Step 2: Run backend full tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn test && mvn -q -DskipTests compile`

Expected: PASS with no compilation warnings introduced by this feature.

- [ ] **Step 3: Check repository boundaries and diff quality.**

Run: `git status --short && git diff --check && git diff --stat`

Confirm that only feature files and the explicitly updated design/review docs are in the task diff; do not stage existing user changes, nested `message-center-spring/`, contact memory changes, todo files, or application configuration edits.

- [ ] **Step 4: Perform runtime acceptance against local servers.**

Start frontend with `cd demo/message-center-spring/frontend && npm run dev -- --host 127.0.0.1` and use the existing backend on `http://127.0.0.1:8107`. As an administrator verify `/admin`, `/admin/platforms`, `/admin/whatsapp/accounts`, and `/admin/whatsapp/template-approvals`; create two CAMS scopes, verify each account list is isolated, verify one-number auto-selection and multi-number required selection, then submit/approve/reject/retry a template request. As a non-admin verify admin routes show no permission and `/templates` still shows “我的申请” without approval controls.

- [ ] **Step 5: Record evidence and remaining risk.**

Write actual commands, test counts, screenshots or browser observations, server URLs, and any unavailable external CAMS checks to `docs/superpowers/reviews/2026-09-16-admin-platform-workbench-verification.md`. Mark unexecuted real-CAMS tests as unexecuted; do not claim success from mocks.

- [ ] **Step 6: Commit documentation only after verification.**

```bash
git add docs/superpowers/specs/2026-09-16-admin-platform-workbench-design.md docs/superpowers/reviews/2026-09-16-admin-platform-workbench-verification.md demo/message-center-spring/README.md
git commit -m "docs: verify admin platform workbench"
```

## Plan self-review

- Spec coverage: multi-CAMS lifecycle is Task 1-2; scope-aware accounts and single/multiple selection are Task 3-4; dedicated template approval is Task 5-6; authorization, errors, tests and runtime acceptance are covered in every task and Task 7.
- Placeholder scan: no `TODO`, `TBD`, “implement later” or unspecified test step remains; every task names files, interfaces, commands and expected outcomes.
- Type consistency: `scopeId`, `version`, `AdminCamsScope`, scoped account methods and approval route names are defined before consumers; Task 4 endpoint names match Task 2/3 route contracts.
- Git boundary: all commit examples list explicit files and avoid `git add .`; known user changes remain outside this plan's stage lists.
