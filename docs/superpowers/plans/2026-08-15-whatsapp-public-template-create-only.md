# WhatsApp 公共模板仅创建送审实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除不可用的 CopyTemplate 公共模板复制主线，让公共模板只作为可编辑样板，补全必填字段后通过现有 CreateChatappTemplate 流程送审。

**Architecture:** ListBaseTemplate 仍由 PublicTemplateResponseParser 和 PublicTemplateApplicationService.list 提供只读公共样板查询。详情页唯一动作是把结构化样板转换为 TemplateEditorInitialValue，由 TemplatesPage 复用现有创建 mutation 和 CreateChatappTemplate。后端删除复制 gateway、controller 和服务状态机；新增前向迁移把已存在的 COPY 操作转换为只读 RETIRED，保留历史状态但禁止重试和对账。

**Tech Stack:** Spring Boot 3.4.5、Java 17、MyBatis-Plus、Flyway、PostgreSQL、React 19、TypeScript、Ant Design、Vitest、Node --test、Maven。

## Global Constraints

- 公共模板是创建样板，不是可直接复制的成品；唯一写入路径是补全字段后调用现有 CreateChatappTemplate。
- 运行时代码、前端 bundle、active spec/plan 和合同测试不得保留 CopyTemplate 复制主线、copyPublicTemplate 调用或“复制并送审”文案；历史 Git 提交不改写。
- 已执行的迁移文件 V9、V14 不回写、不删除；新增前向迁移只处理历史 COPY 数据。
- 历史 COPY 行迁移为 RETIRED，保留原有状态和请求/错误信息，清除 next_reconcile_at、lease_owner、lease_until，不再进入 reconciliation。
- 不新增模板创建 API、不绕过审核、不自动开启 allowSend，不把 provider 原始 JSON、Cookie、HAR、AccessKey 放入前端或普通日志。
- 修改前先写失败测试；每个任务独立运行针对性测试后再进入下一个任务。

## 文件与职责地图

- Create: demo/message-center-spring/backend/src/main/resources/db/migration/V18__retire_whatsapp_public_template_copy.sql — 退役历史复制操作并收紧操作类型约束。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppPublicTemplateGateway.java — 仅保留 ListBaseTemplate 查询。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/ChatAppPublicTemplateGateway.java — 删除复制接口和命令。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationService.java — 收敛为只读列表服务。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateModels.java — 删除复制模型。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java — 删除公共模板 copy endpoint。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java — 将运行时操作类型从 COPY 改为历史 RETIRED。
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java — 删除复制对账分支，忽略 RETIRED。
- Modify: demo/message-center-spring/frontend/src/api/endpoints.ts、demo/message-center-spring/frontend/src/api/types.ts — 删除复制 API 和 COPY 类型。
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateLibrary.tsx — 删除复制 mutation、草稿和错误状态，只保留样板转换入口。
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateDetailModal.tsx — 删除复制表单和按钮，详情只展示样板并提供创建入口。
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateLibrary.test.tsx、demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx 及相关 contract tests — 验证只调用创建路径。
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppPublicTemplateCopyMigrationTest.java、公共模板 gateway/service/controller/reconciliation 测试 — 改为退役迁移和无复制合同。
- Modify: docs/superpowers/specs/2026-08-12-whatsapp-template-remarks-and-library-design.md、docs/superpowers/specs/2026-08-13-chatapp-contacts-template-copy-mass-messaging-design.md、docs/superpowers/plans/2026-08-14-whatsapp-template-library.md、docs/superpowers/plans/2026-08-14-whatsapp-public-template-visual-workbench.md — 删除 active 文档中的复制主线，保留历史文档已被当前设计替代的明确标记。

### Task 1: 建立删除复制路径的 RED 门禁

**Files:**
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/template/PublicTemplateGatewayContractTest.java
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/PublicTemplateControllerTest.java
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationServiceTest.java
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateLibrary.test.tsx
- Modify: demo/message-center-spring/frontend/test/whatsapp-template-lifecycle-contract.test.mjs

**Interfaces:**
- Consumes: current public-template list contract and existing createAdminTemplate mock.
- Produces: failing tests that require no copy() method, no /copy route, no COPY operation type, and one public-template-to-create flow.

- [ ] Step 1: Replace copy assertions with removal assertions.
  - Assert ChatAppPublicTemplateGateway exposes only Page list(Query query).
  - Assert POST /api/v1/channel-accounts/{accountId}/whatsapp/public-templates/{code}/copy is not mapped.
  - Assert public library tests click 基于此模板创建, submit the editor, and expect createAdminTemplate with converted components.
  - Assert copyPublicTemplate is absent from endpoints.ts, PublicTemplateLibrary.tsx, and the lifecycle contract source scan.

- [ ] Step 2: Run the focused RED suite.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=PublicTemplateGatewayContractTest,PublicTemplateApplicationServiceTest,PublicTemplateControllerTest test

    cd ../frontend
    npm test -- --run src/components/templates/PublicTemplateLibrary.test.tsx src/pages/TemplatesPage.test.tsx

Expected: FAIL because current gateway, service, controller, frontend tests, and types still expose CopyTemplate.

### Task 2: Retire persisted COPY operations with a forward migration

**Files:**
- Create: demo/message-center-spring/backend/src/main/resources/db/migration/V18__retire_whatsapp_public_template_copy.sql
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppPublicTemplateCopyMigrationTest.java

**Interfaces:**
- Consumes: template_operations created by V9 and widened by immutable V14.
- Produces: operation_type=RETIRED history rows and a constraint that permits RETIRED but not new COPY rows.

- [ ] Step 1: Add the migration contract test.
  - Verify the migration drops and recreates ck_template_operation_type with exactly CREATE, MODIFY, SET_SEND_PERMISSION, DELETE, RECONCILE, RETIRED.
  - Verify it updates COPY rows to RETIRED, preserves operation_status, provider_request_id, provider_code, error_code, error_message, started_at, and completed_at, and clears next_reconcile_at, lease_owner, and lease_until.
  - Verify it uses no DELETE, TRUNCATE, or destructive table rewrite.

- [ ] Step 2: Run the migration test to verify it fails.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=WhatsAppPublicTemplateCopyMigrationTest test

Expected: FAIL because V18__retire_whatsapp_public_template_copy.sql does not exist.

- [ ] Step 3: Write the migration.

    ALTER TABLE template_operations
        DROP CONSTRAINT IF EXISTS ck_template_operation_type;

    UPDATE template_operations
    SET operation_type = 'RETIRED',
        next_reconcile_at = NULL,
        lease_owner = NULL,
        lease_until = NULL,
        error_code = COALESCE(error_code, 'OPERATION_RETIRED'),
        error_message = COALESCE(error_message, '旧公共模板复制路径已退役')
    WHERE operation_type = 'COPY';

    ALTER TABLE template_operations
        ADD CONSTRAINT ck_template_operation_type CHECK
            (operation_type IN ('CREATE','MODIFY','SET_SEND_PERMISSION','DELETE','RECONCILE','RETIRED'));

- [ ] Step 4: Run migration and focused tests.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=WhatsAppPublicTemplateCopyMigrationTest,TemplateOperationMapperContractTest test

Expected: migration contract passes and no existing operation status fields are rewritten.

### Task 3: Remove backend CopyTemplate runtime ownership

**Files:**
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppPublicTemplateGateway.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/ChatAppPublicTemplateGateway.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationService.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateModels.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java

**Interfaces:**
- Consumes: PublicTemplateModels.Query and PublicTemplateModels.Page.
- Produces: GET /api/v1/channel-accounts/{accountId}/whatsapp/public-templates only; no public-template write endpoint.

- [ ] Step 1: Delete copy gateway and service code.
  - Remove CopyTemplateCommand, CopyResult, CopyAuditSnapshot, copyQuery, copy(), copy() validation, idempotency, provider error mapping, operation persistence, and copy response records.
  - Remove now-unused TemplateOperationMapper, ObjectMapper, and copy-only imports/constructor dependencies from PublicTemplateApplicationService; retain account validation, bounded query validation, cache, clock, and gateway.list.
  - Remove CopyTemplate from Tea SDK action selection so AliyunChatAppPublicTemplateGateway.params handles only ListBaseTemplate with GET.

- [ ] Step 2: Run backend RED tests.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=PublicTemplateGatewayContractTest,PublicTemplateApplicationServiceTest,PublicTemplateControllerTest test

Expected: FAIL only where old tests still reference deleted copy symbols; compilation errors identify remaining call sites.

- [ ] Step 3: Remove controller mapping and update list-only construction.
  - Delete POST /public-templates/{code}/copy, request/response records, actor extraction used only by copy, and service call.
  - Keep GET /public-templates account-scoped and unchanged in query semantics.

- [ ] Step 4: Make backend focused tests pass.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=PublicTemplateGatewayContractTest,PublicTemplateApplicationServiceTest,PublicTemplateResponseParserTest,ListBaseTemplateFixtureTest,PublicTemplateControllerTest test

Expected: all focused public-template tests pass and no source file in the backend contains CopyTemplate or PUBLIC_TEMPLATE_COPY_.

### Task 4: Preserve historical RETIRED records without reconciliation

**Files:**
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java
- Modify: demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateOperationResponse.java
- Modify: demo/message-center-spring/frontend/src/api/types.ts
- Modify: demo/message-center-spring/frontend/src/components/templates/OwnedTemplateDetailModal.tsx
- Modify: demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationServiceTest.java

**Interfaces:**
- Consumes: migrated template_operations.operation_type=RETIRED rows.
- Produces: history projection that renders RETIRED as 旧路径已退役 and never claims it is retryable.

- [ ] Step 1: Add failing history/reconciliation tests.
  - Insert a RETIRED operation into the in-memory mapper fixture and assert history() returns it without enum conversion failure.
  - Assert reconcileUnknown() does not claim or mutate RETIRED rows.
  - Assert the detail projection exposes the raw status and error message without a retry action.

- [ ] Step 2: Implement the narrow historical type.
  - Add RETIRED to WhatsAppTemplateModels.OperationType.
  - Add a case RETIRED -> false branch in reconciliation that is unreachable for scheduled rows after migration but fails closed if an old row is encountered.
  - Keep claimUnknown unchanged; migration clears the scheduling fields, and no new RETIRED operation is created.
  - Map RETIRED to a user-facing label only in the existing detail projection; do not add retry or action semantics.

- [ ] Step 3: Run the reconciliation/history tests.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=WhatsAppTemplateReconciliationServiceTest,PublicTemplateControllerTest,WhatsAppPublicTemplateCopyMigrationTest test

Expected: historical rows are readable, never claimed for reconciliation, and the operation type set contains no runtime COPY.

### Task 5: Make the frontend public library create-only

**Files:**
- Modify: demo/message-center-spring/frontend/src/api/endpoints.ts
- Modify: demo/message-center-spring/frontend/src/api/types.ts
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateLibrary.tsx
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateDetailModal.tsx
- Modify: demo/message-center-spring/frontend/src/components/templates/PublicTemplateLibrary.test.tsx
- Modify: demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx

**Interfaces:**
- Consumes: publicTemplateToEditorDraft(template, selectedPageIndex) and TemplatesPage.onCreateFromPublicTemplate.
- Produces: one detail CTA, TemplateEditorDrawer initialization, and existing createAdminTemplate request.

- [ ] Step 1: Remove copy state and API calls.
  - Delete copyPublicTemplate import and endpoint function.
  - Replace COPY with RETIRED in the TemplateOperation union so historical rows remain renderable without exposing a new copy action.
  - Delete copyDraft, copyError, activeCopyIdentity, copyMutation, confirmCopy, variable audit inputs, copy-specific retry identity, and copy notices.
  - Keep selected page, preview mode, list query, account isolation, and customize() conversion.

- [ ] Step 2: Simplify the detail modal contract.
  - Remove props and callbacks named copyName, copyVariables, copyPending, copyError, onCopyNameChange, onCopyVariableChange, and onCopy.
  - Remove copy name/variable fields and the copy audit alert.
  - Render 基于此模板创建 as the only primary action; disable it when no page is selected, no pages exist, or conversion is unavailable.
  - Keep source metadata, page selection, parameter/example preview, and close behavior.

- [ ] Step 3: Update component tests first, then implementation.
  - Test that opening a public template shows no 复制此模板 control.
  - Test that clicking 基于此模板创建 calls the page callback with converted components and does not call any copy API.
  - Test that multi-page templates require a page selection and that unsupported buttons remain blocking conversion issues.

- [ ] Step 4: Run frontend focused tests.

    cd demo/message-center-spring/frontend
    npm test -- --run src/components/templates/PublicTemplateLibrary.test.tsx src/pages/TemplatesPage.test.tsx
    node --test test/whatsapp-template-lifecycle-contract.test.mjs

Expected: all create-only tests pass; source scan finds no copyPublicTemplate, /public-templates/.../copy, or COPY operation union.

### Task 6: Clean active documentation and contract fixtures

**Files:**
- Modify: docs/superpowers/specs/2026-08-12-whatsapp-template-remarks-and-library-design.md
- Modify: docs/superpowers/specs/2026-08-13-chatapp-contacts-template-copy-mass-messaging-design.md
- Modify: docs/superpowers/plans/2026-08-14-whatsapp-template-library.md
- Modify: docs/superpowers/plans/2026-08-14-whatsapp-public-template-visual-workbench.md
- Modify: docs/superpowers/specs/2026-08-14-whatsapp-public-template-visual-workbench-design.md

**Interfaces:**
- Consumes: the approved create-only design in the current spec.
- Produces: active docs that describe only list → customize → create → review; historical Git commits remain unchanged.

- [ ] Step 1: Replace active copy contracts.
  - Remove current endpoint, SDK parameter, idempotency, error-code, and provider-write sections that describe CopyTemplate as supported.
  - Replace them with the create-only conversion contract and point to the current design spec.
  - Mark any superseded historical section as non-current instead of leaving contradictory acceptance checkboxes.

- [ ] Step 2: Run the documentation source scan.

    rg -n "复制此模板|复制并送审|copyPublicTemplate|PUBLIC_TEMPLATE_COPY_|/public-templates/.*/copy|CopyTemplate" \
      docs/superpowers/specs docs/superpowers/plans demo/message-center-spring/frontend/src demo/message-center-spring/backend/src/main

Expected: only the approved retirement rationale and historical-migration wording in the current design spec may mention the retired concept; no active implementation contract may require it.

### Task 7: Full verification and browser acceptance

**Files:**
- No source changes; verify the touched files and the preserved dirty-worktree boundary.

**Interfaces:**
- Consumes: completed Tasks 1–6.
- Produces: test, build, migration, browser, and git evidence for the create-only mainline.

- [ ] Step 1: Run backend focused and package verification.

    cd demo/message-center-spring/backend
    mvn -q -Dtest=PublicTemplateGatewayContractTest,PublicTemplateApplicationServiceTest,PublicTemplateResponseParserTest,ListBaseTemplateFixtureTest,PublicTemplateControllerTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppPublicTemplateCopyMigrationTest test
    mvn -q -DskipTests package

- [ ] Step 2: Run frontend tests and production build.

    cd demo/message-center-spring/frontend
    npm test
    npm run build

- [ ] Step 3: Browser acceptance.
  - Desktop: open /templates, switch to 公共模板库, verify 20 cards and pagination.
  - Open one card, verify only 基于此模板创建 is present, select a page if needed, and verify the editor opens with converted body/buttons/examples.
  - Verify editor submit calls the existing create flow in the UI test environment; do not issue a real provider write without a separate explicit authorization.
  - Mobile 390x844: verify one-column cards, no horizontal overflow, and detail/editor layout remains usable.
  - Check page identity, non-blank content, no framework overlay, console errors, and one interaction state change.

- [ ] Step 4: Git boundary verification.

    git status --short
    git diff --check -- \
      demo/message-center-spring/backend \
      demo/message-center-spring/frontend \
      docs/superpowers/specs/2026-08-12-whatsapp-template-remarks-and-library-design.md \
      docs/superpowers/specs/2026-08-13-chatapp-contacts-template-copy-mass-messaging-design.md \
      docs/superpowers/plans/2026-08-14-whatsapp-template-library.md \
      docs/superpowers/plans/2026-08-14-whatsapp-public-template-visual-workbench.md

Expected: only task-owned files are changed or staged; unrelated user modifications remain untouched; no generated bundle or secret is staged.
