# WhatsApp Template Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有固定 WhatsApp 账号上交付以 CAMS 官方 API 为准的模板创建、送审、查询、修改、启停、删除、对账、媒体 Header 上传、审计和前端管理闭环。

**Architecture:** `service/whatsapp/template` 是模板业务 owner，持久化完整模板投影、操作记录和媒体资产；`channel/chatapp/template` 只封装 CAMS 5.0.5 官方 SDK。所有 provider 写操作先写本地操作记录，超时进入 `SUBMISSION_UNKNOWN` 且禁止盲目重放；销售发送选择器只读取 `APPROVED + allow_send + 未删除` 投影。

**Tech Stack:** Java 17、Spring Boot 3.4.5、Spring Security、MyBatis-Plus 3.5.10、PostgreSQL 17.5、Flyway、Aliyun CAMS SDK 5.0.5、React 18、TypeScript 5.6、Ant Design 5、TanStack Query 5、Vite 6、Vitest、Testing Library、Playwright/Browser。

## Global Constraints

- 真源规格：`docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md`。
- Provider 合同只使用 `CreateChatappTemplate`、`ListChatappTemplate`、`GetChatappTemplateDetail`、`ModifyChatappTemplate`、`ModifyChatappTemplateProperties`、`DeleteChatappTemplate`、`GetChatappUploadAuthorization`。
- 禁止调用 `UpdateAuditRequest` 处理 WhatsApp 模板。
- 不实现本地草稿/审批、AUTHENTICATION、Carousel、Flow、限时优惠、群发、多号码或素材物理删除。
- 不修改 `backend/src/main/resources/application-dev.yml`，不吸入工作区其他既有修改。
- 每个行为先写失败测试并确认 RED，再写最小实现并确认 GREEN。
- 自动化测试禁止调用真实 CAMS 写接口；真实写入验收必须由平台管理员单独确认。
- `CREDENTIAL_MASTER_KEY` 仍是完整 Spring 启动前提。
- 每次提交前运行 `git diff --cached --name-only`，暂存区只允许出现当前 Task 明确列出的文件；发现并发暂存或 HEAD 变化立即停止提交并重新审计。

---

## File Map

### Database and persistence

- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V9__whatsapp_template_lifecycle.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateOperationEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AuditLogEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateOperationMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AuditLogMapper.java`

### Business owner and provider adapter

- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateValidator.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateGateway.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateException.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppOssMediaUploader.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppTemplateGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java`

### HTTP, auth, errors and scheduling

- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateCreateRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateUpdateRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateSendPermissionRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateAdminResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateOperationResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/LoginResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationScheduler.java`

### Frontend

- Modify: `demo/message-center-spring/frontend/package.json`
- Modify: `demo/message-center-spring/frontend/package-lock.json`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useAuth.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateDetailDrawer.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateStatusTag.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/templateForm.ts`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.test.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`
- Create: `demo/message-center-spring/frontend/vitest.config.ts`
- Create: `demo/message-center-spring/frontend/test/setup.ts`

---

### Task 1: Add the lifecycle schema and persistence contracts

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__whatsapp_template_lifecycle.sql`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateOperationEntity.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/entity/AuditLogEntity.java`
- Modify/Create the four mapper files listed in File Map
- Modify: `backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`

**Interfaces:**
- Produces: durable template projection, operation idempotency, media asset state and audit insert APIs.
- Consumed by: Tasks 4-7.

- [ ] **Step 1: Write the failing PostgreSQL integration tests**

Add tests that expect V9 columns and tables, then use mapper calls to prove:

```java
assertThat(jdbcTemplate.queryForObject("select count(*) from template_operations", Integer.class))
        .isZero();
assertThat(jdbcTemplate.queryForObject("select count(*) from template_media_assets", Integer.class))
        .isZero();
assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs", Integer.class))
        .isZero();
```

Insert the same `channel_account_id + idempotency_key` twice and assert the second insert is ignored or returns the existing row. Insert a template with `components_jsonb`, `allow_send=false`, then assert `findForSend` returns empty.

- [ ] **Step 2: Run the integration test and confirm RED**

Run:

```bash
mvn -q -Dapi.version=1.44 -Dapp.chatapp-outbox-enabled=false -Dtest=AppIntegrationTest test
```

Expected: FAIL because V9 tables/columns and mapper methods do not exist.

- [ ] **Step 3: Create V9 migration**

Use `ALTER TABLE message_templates ADD COLUMN` for:

```sql
category varchar(30),
template_type varchar(30) NOT NULL DEFAULT 'WHATSAPP',
components_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
examples_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
message_send_ttl_seconds integer,
allow_send boolean NOT NULL DEFAULT false,
provider_audit_status varchar(100),
rejection_reason text,
quality_score varchar(100),
deleted_at timestamptz,
version bigint NOT NULL DEFAULT 0
```

Create `template_operations` and `template_media_assets` exactly as specified in the design, including checks for operation/asset states, `reconcile_attempt_count <= 10`, and unique index:

```sql
CREATE TABLE template_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    idempotency_key varchar(255) NOT NULL,
    operation_type varchar(40) NOT NULL,
    provider_template_id varchar(255),
    language_code varchar(30) NOT NULL,
    requested_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    operation_status varchar(40) NOT NULL,
    provider_request_id varchar(255),
    provider_code varchar(100),
    error_code varchar(100),
    error_message text,
    next_reconcile_at timestamptz,
    reconcile_attempt_count integer NOT NULL DEFAULT 0,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    trace_id varchar(100),
    lease_owner varchar(100),
    lease_until timestamptz,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_template_operation_type CHECK
        (operation_type IN ('CREATE','MODIFY','SET_SEND_PERMISSION','DELETE','RECONCILE')),
    CONSTRAINT ck_template_operation_status CHECK
        (operation_status IN ('PROCESSING','SUCCEEDED','SUBMISSION_UNKNOWN','FAILED')),
    CONSTRAINT ck_template_reconcile_attempts CHECK
        (reconcile_attempt_count BETWEEN 0 AND 10)
);

CREATE TABLE template_media_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    provider_object_key text NOT NULL,
    provider_url text NOT NULL,
    media_format varchar(20) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    asset_status varchar(20) NOT NULL,
    created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    attached_at timestamptz,
    CONSTRAINT ck_template_media_format CHECK
        (media_format IN ('IMAGE','VIDEO','DOCUMENT')),
    CONSTRAINT ck_template_asset_status CHECK
        (asset_status IN ('UPLOADED','ATTACHED','ATTACHMENT_UNKNOWN','ORPHANED')),
    CONSTRAINT ck_template_asset_size CHECK (size_bytes > 0)
);

CREATE UNIQUE INDEX ux_template_operations_idempotency
ON template_operations(channel_account_id, idempotency_key);
```

Add indexes for admin listing, unknown reconciliation and asset attachment. Do not alter V1-V8.

- [ ] **Step 4: Add entities and mapper contracts**

`TemplateOperationMapper` must expose:

```java
int insertIgnore(TemplateOperationEntity operation);
Optional<TemplateOperationEntity> findByIdempotency(UUID accountId, String idempotencyKey);
Optional<TemplateOperationEntity> findByIdForUpdate(UUID operationId);
int markSucceeded(UUID id, String providerTemplateId, String providerRequestId, Instant completedAt);
int markUnknown(UUID id, String errorCode, String errorMessage, Instant nextReconcileAt);
int markFailed(UUID id, String providerRequestId, String errorCode, String errorMessage, Instant completedAt);
List<TemplateOperationEntity> claimUnknown(String workerId, Instant now, Instant leaseUntil, int limit);
```

`TemplateMediaAssetMapper` must expose insert, account-scoped lookup, `markAttached`, and `markOrphaned`. `AuditLogMapper` must expose one structured insert method. Extend `TemplateMapper.findForSend` with `allow_send = true and deleted_at is null`.

- [ ] **Step 5: Run AppIntegrationTest and confirm GREEN**

Expected: Flyway validates and applies V1-V9; persistence assertions pass.

- [ ] **Step 6: Commit only Task 1 files**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V9__whatsapp_template_lifecycle.sql
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateOperationEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AuditLogEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateOperationMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AuditLogMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java
git commit -m "feat: add whatsapp template lifecycle persistence"
```

### Task 2: Define the domain contract and validator

**Files:**
- Create the four owner files under `service/whatsapp/template`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateValidatorTest.java`

**Interfaces:**
- Produces: `TemplateCommand`, `TemplateComponent`, `TemplateButton`, `TemplateSnapshot`, `ProviderOperationResult`, `ProviderTemplatePage` and `WhatsAppTemplateGateway`.
- Consumed by: provider adapter and application service.

- [ ] **Step 1: Write validator RED tests**

Cover exactly one BODY, BODY length 1,024, Header/Footer length 60, category allowlist, media asset requirement, button limits, no quick-reply mixing, and exact example keys.

Example:

```java
assertThatThrownBy(() -> validator.validate(commandWithMixedButtons()))
        .isInstanceOf(WhatsAppTemplateException.class)
        .extracting("code")
        .isEqualTo("TEMPLATE_VALIDATION_FAILED");
```

- [ ] **Step 2: Run and confirm RED**

```bash
mvn -q -Dtest=WhatsAppTemplateValidatorTest test
```

Expected: compilation failure because the contract does not exist.

- [ ] **Step 3: Implement immutable owner models**

Use records and enums, never SDK classes:

```java
public enum ReviewStatus { PENDING, APPROVED, REJECTED, SUSPENDED, UNKNOWN }
public enum OperationStatus { PROCESSING, SUCCEEDED, SUBMISSION_UNKNOWN, FAILED }
public enum OperationType { CREATE, MODIFY, SET_SEND_PERMISSION, DELETE, RECONCILE }
public enum HeaderFormat { TEXT, IMAGE, VIDEO, DOCUMENT }
public enum ButtonType { QUICK_REPLY, URL, PHONE_NUMBER }
```

Define gateway result records with these exact signatures:

```java
public record CreateResult(String templateCode, String templateName, String providerRequestId) {}
public record ModifyResult(String templateCode, String templateName, String providerRequestId) {}
public record PropertyResult(boolean allowSend, String providerRequestId) {}
public record DeleteResult(boolean success, String providerRequestId) {}
public record UploadedMedia(String objectKey, String url, HeaderFormat format,
                            String contentType, long sizeBytes, String sha256) {}
public record ProviderTemplateSummary(String templateCode, String templateName,
                                      String language, String category,
                                      String rawAuditStatus, String reason,
                                      Instant providerUpdatedAt) {}
public record ProviderTemplatePage(List<ProviderTemplateSummary> items,
                                   int page, boolean hasNext) {}
```

`WhatsAppTemplateGateway` must define:

```java
CreateResult create(UUID accountId, TemplateCommand command);
ModifyResult modify(UUID accountId, String templateCode, String language, TemplateCommand command);
PropertyResult setSendPermission(UUID accountId, String templateCode, String language, boolean allowSend);
DeleteResult delete(UUID accountId, String templateCode, String language);
ProviderTemplatePage list(UUID accountId, int page, int size);
Optional<TemplateSnapshot> detail(UUID accountId, String templateCode, String language);
UploadedMedia upload(UUID accountId, HeaderFormat format, byte[] bytes, String fileName, String contentType);
```

- [ ] **Step 4: Implement validator and structured exception**

`WhatsAppTemplateException` carries `code`, `HttpStatus`, `fieldErrors`, provider request ID and retryability. Validation errors use HTTP 400; account mismatches and non-admin access remain service/security boundary errors.

- [ ] **Step 5: Run validator tests and confirm GREEN**

- [ ] **Step 6: Commit Task 2 files**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateValidatorTest.java
git commit -m "feat: define whatsapp template domain contract"
```

### Task 3: Implement the official CAMS gateway and shared OSS uploader

**Files:**
- Create: `channel/chatapp/ChatAppOssMediaUploader.java`
- Create: `channel/chatapp/template/AliyunChatAppTemplateGateway.java`
- Modify: `channel/chatapp/ChatAppSendService.java`
- Test: `channel/chatapp/ChatAppOssMediaUploaderTest.java`
- Test: `channel/chatapp/template/AliyunChatAppTemplateGatewayTest.java`
- Modify: `channel/chatapp/ChatAppSendServiceTest.java`

**Interfaces:**
- Consumes owner gateway records from Task 2.
- Produces the only SDK-backed adapter.

- [ ] **Step 1: Write gateway RED tests using mocked `AsyncClient`**

Capture requests and assert:

```java
assertThat(request.getTemplateType()).isEqualTo("WHATSAPP");
assertThat(request.getCategory()).isEqualTo("UTILITY");
assertThat(request.getCustSpaceId()).isEqualTo("cams-space");
assertThat(request.getComponents()).hasSize(3);
```

Add separate tests for Modify, Properties, Delete, List, Detail and upload authorization. Assert `UpdateAuditRequest` is never referenced.

- [ ] **Step 2: Run and confirm RED**

```bash
mvn -q -Dtest='AliyunChatAppTemplateGatewayTest,ChatAppOssMediaUploaderTest,ChatAppSendServiceTest' test
```

- [ ] **Step 3: Extract `ChatAppOssMediaUploader`**

Move the existing signed OSS PUT behavior from `ChatAppSendService` into a focused service:

```java
public UploadedObject upload(GetChatappUploadAuthorizationResponseBody.Data authorization,
                             byte[] bytes, String fileName, String contentType)
```

It sets 15 second connect and 60 second read timeouts, never logs authorization fields, and returns `objectKey` plus HTTPS URL. Modify message media sending to call this service and keep existing behavior green.

- [ ] **Step 4: Implement official request/response mappings**

Map only the approved component types. Check response body code/success instead of treating a completed future as success. Convert provider exceptions into stable `WhatsAppTemplateException` codes, preserving request ID in diagnostic context.

- [ ] **Step 5: Run tests and confirm GREEN**

- [ ] **Step 6: Commit Task 3 files**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppOssMediaUploader.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppTemplateGateway.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppOssMediaUploaderTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppTemplateGatewayTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendServiceTest.java
git commit -m "feat: add official cams template gateway"
```

### Task 4: Implement media upload and lifecycle write operations

**Files:**
- Create/Modify: `WhatsAppTemplateApplicationService.java`
- Test: `WhatsAppTemplateApplicationServiceTest.java`

**Interfaces:**
- Consumes persistence from Task 1, validator/gateway from Tasks 2-3.
- Produces upload/create/modify/send-permission/delete commands for HTTP Task 7.

- [ ] **Step 1: Write RED tests for media upload**

Cover admin/account validation at caller boundary, 5/16/64 MiB caps, MIME allowlist, SHA-256, no credential persistence, and `UPLOADED` insert.

- [ ] **Step 2: Write RED tests for idempotent create**

Call create twice with the same account and `clientRequestId`; verify gateway `create` is called once and the second response returns the original operation.

- [ ] **Step 3: Write RED tests for unknown provider outcomes**

Make gateway throw a timeout after the operation row exists. Assert operation becomes `SUBMISSION_UNKNOWN`, media becomes `ATTACHMENT_UNKNOWN`, and no retry occurs. A provider-declared rejection is the separate case that marks media `ORPHANED`.

- [ ] **Step 4: Implement upload and CREATE minimal path**

Sequence must be:

```text
validate -> find/insert idempotency row -> lock operation -> call gateway once
-> fetch detail -> upsert projection -> attach/orphan asset -> audit -> complete operation
```

If detail is absent after an acknowledged create, persist `UNKNOWN` and `allow_send=false`.

- [ ] **Step 5: Add MODIFY, SET_SEND_PERMISSION and DELETE tests and implementation**

Modify and delete lock by `accountId + templateCode + language`. Delete only sets `deleted_at` after explicit provider success. Send-permission recovery still requires `APPROVED` for sales visibility.

- [ ] **Step 6: Run and confirm GREEN**

```bash
mvn -q -Dtest=WhatsAppTemplateApplicationServiceTest test
```

- [ ] **Step 7: Commit Task 4 files**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java
git commit -m "feat: implement whatsapp template lifecycle commands"
```

### Task 5: Reconcile official state and close unknown operations

**Files:**
- Create: `WhatsAppTemplateReconciliationService.java`
- Create: `WhatsAppTemplateReconciliationScheduler.java`
- Modify: `config/AppConfig.java`
- Modify: `channel/chatapp/ChatAppTemplateSyncService.java`
- Test: `WhatsAppTemplateReconciliationServiceTest.java`
- Modify: `channel/chatapp/ChatAppTemplateSyncServiceTest.java`
- Modify: `service/message/ChatAppWorkerSchedulingTest.java`

**Interfaces:**
- Produces one owner for full snapshot sync and unknown-operation reconciliation.

- [ ] **Step 1: Write sync RED tests**

Cover 20x100 bounds, list-page failure retaining the previous snapshot, detail failure retaining components but updating confirmed review status, and deletion only after complete pagination.

- [ ] **Step 2: Write unknown-operation RED tests**

Create unknown resolves by exact requested template name/language only when one unambiguous provider item exists. Modify/delete resolve by template code/language. Detail referencing the stored media URL marks it `ATTACHED`; an explicit provider rejection marks it `ORPHANED`; ambiguous results leave both operation and media unknown.

- [ ] **Step 3: Implement owner reconciliation**

`ChatAppTemplateSyncService.runOnce()` becomes a thin scheduler adapter calling `reconciliationService.syncAccount(accountId)`. Remove SDK/client creation and direct mapper upsert from it.

- [ ] **Step 4: Implement bounded scheduler**

Run every five minutes only when `app.chatapp-sync-enabled=true`. Claim at most 20 unknown operations, lease for two minutes, max 10 reconciliations, backoff cap three hours, total window 24 hours.

Add `chatappTemplateReconcileEnabled` and `chatappTemplateReconcileIntervalMs` to `AppConfig`, defaulting to `true` and `300000`; tests set the enabled flag to false unless scheduling itself is under test.

- [ ] **Step 5: Run and confirm GREEN**

```bash
mvn -q -Dtest='WhatsAppTemplateReconciliationServiceTest,ChatAppTemplateSyncServiceTest,ChatAppWorkerSchedulingTest' test
```

- [ ] **Step 6: Commit Task 5 files**

Stage only files listed above and commit:

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationScheduler.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ChatAppWorkerSchedulingTest.java
git commit -m "feat: reconcile whatsapp template lifecycle state"
```

### Task 6: Enforce the sales selector and send-time contract

**Files:**
- Modify: `service/chatapp/ChatAppTemplateService.java`
- Modify: `service/message/TemplateMessageTextResolver.java`
- Modify: `dto/response/TemplateResponse.java`
- Modify: `mapper/TemplateMapper.java`
- Test: `service/chatapp/ChatAppTemplateServiceTest.java`
- Modify: `service/message/TemplateMessageTextResolverTest.java`
- Modify: `service/message/MessageSendApplicationServiceTest.java`

**Interfaces:**
- Produces approved-only selector and strict send-time lookup.

- [ ] **Step 1: Write RED tests**

Assert rejected, pending, suspended, `allow_send=false`, deleted and wrong-account versions never appear in `/api/templates` and cannot render for send. Historical display still uses `findForDisplay` regardless of current status.

- [ ] **Step 2: Implement account-scoped queries**

Replace `selectList(null)` with explicit mapper methods. Extend `TemplateResponse` with category, components and variable definitions while preserving existing field names used by the sender.

- [ ] **Step 3: Run and confirm GREEN**

```bash
mvn -q -Dtest='ChatAppTemplateServiceTest,TemplateMessageTextResolverTest,MessageSendApplicationServiceTest' test
```

- [ ] **Step 4: Commit Task 6 files**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateResponse.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolverTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationServiceTest.java
git commit -m "feat: enforce approved template sending"
```

### Task 7: Expose admin API, stable errors and frontend role information

**Files:**
- Create the controller/request/response files listed in File Map
- Modify: `SecurityConfig.java`, `GlobalExceptionHandler.java`, `LoginResponse.java`, `AuthController.java`
- Test: `web/WhatsAppTemplateControllerTest.java`
- Modify: `web/AuthControllerSecurityTest.java`

**Interfaces:**
- Produces the exact HTTP contract from the design.

- [ ] **Step 1: Write authorization RED tests**

Using MockMvc, assert regular sales receive 403 for every management write path, admins can invoke them, anonymous requests receive 401, and `/api/templates` remains authenticated-user accessible.

- [ ] **Step 2: Write request/response RED tests**

Cover list filters, multipart template-media upload, create, detail, modify, send permission, delete, sync and operation history. Assert error responses preserve `ApiError(code, message, traceId, fieldErrors)`.

- [ ] **Step 3: Add the admin security matcher**

Place before generic `/api/**`:

```java
.requestMatchers("/api/v1/channel-accounts/*/whatsapp/templates/**").hasRole("ADMIN")
.requestMatchers("/api/v1/channel-accounts/*/whatsapp/template-media").hasRole("ADMIN")
```

Business service still verifies account type and identity; URL authorization is not the only boundary.

- [ ] **Step 4: Expose roles at login**

Change response to:

```java
public record LoginResponse(String token, String username, List<String> roles) {}
```

Populate from authenticated authorities after removing the `ROLE_` prefix. This is UI capability information only; backend authorization remains authoritative.

- [ ] **Step 5: Implement controller and exception mapping**

Do not catch all exceptions in the controller. Map `WhatsAppTemplateException.httpStatus()` in `GlobalExceptionHandler`, including field errors and a generated trace ID.

- [ ] **Step 6: Run and confirm GREEN**

```bash
mvn -q -Dtest='WhatsAppTemplateControllerTest,AuthControllerSecurityTest' test
```

- [ ] **Step 7: Commit Task 7 files**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateCreateRequest.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateUpdateRequest.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateSendPermissionRequest.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateAdminResponse.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateOperationResponse.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/LoginResponse.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AuthControllerSecurityTest.java
git commit -m "feat: expose whatsapp template management api"
```

### Task 8: Add frontend contracts, auth roles and admin navigation

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `frontend/src/hooks/useAuth.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Add/modify source contract tests under `frontend/test`

**Interfaces:**
- Produces typed management endpoints and `isAdmin` UI capability.

- [ ] **Step 1: Write source-contract RED tests**

Assert roles are persisted/cleared with the token, channel settings and template management navigation render only for admin, and endpoints use the approved `/api/v1/channel-accounts/...` paths.

- [ ] **Step 2: Run and confirm RED**

```bash
npm test
```

- [ ] **Step 3: Implement TypeScript contracts and endpoints**

Add `TemplateAdmin`, `TemplateOperation`, `TemplateComponent`, `TemplateMediaAsset`, `TemplateListPage`, `TemplateCommand` and typed functions for every management API. Media upload uses `FormData` and browser-generated multipart boundary.

- [ ] **Step 4: Persist roles in auth context**

`AuthState` exposes `roles: string[]` and `isAdmin: boolean`. Logout clears `roles`; a 401 interceptor continues to clear all auth state keys.

- [ ] **Step 5: Run and confirm GREEN**

```bash
npm test
npm run build
```

- [ ] **Step 6: Commit Task 8 files**

```bash
git add demo/message-center-spring/frontend/src/api/types.ts
git add demo/message-center-spring/frontend/src/api/endpoints.ts
git add demo/message-center-spring/frontend/src/hooks/useAuth.tsx
git add demo/message-center-spring/frontend/src/components/AppLayout.tsx
git add demo/message-center-spring/frontend/test
git commit -m "feat: add template management frontend contracts"
```

### Task 9: Build the template management UI with real component tests

**Files:**
- Modify package files and create Vitest setup
- Replace `TemplatesPage.tsx`
- Create template components and tests listed in File Map

**Interfaces:**
- Consumes Task 8 endpoints and types.
- Produces complete administrator workflow.

- [ ] **Step 1: Add Vitest and Testing Library**

Add dev dependencies `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`. Keep existing Node source tests and change scripts to:

```json
{
  "test": "npm run test:source && npm run test:ui",
  "test:source": "node --test test/*.test.mjs",
  "test:ui": "vitest run"
}
```

- [ ] **Step 2: Write UI RED tests**

Mock endpoint modules and test:

- status/category/language/deleted filters;
- create BODY + variables;
- media upload progress and returned internal asset ID;
- edit causing pending status;
- pause/resume confirmation;
- delete confirmation;
- rejection reason and operation history;
- 403, upload failure and `SUBMISSION_UNKNOWN` states.

- [ ] **Step 3: Run and confirm RED**

```bash
npm run test:ui
```

- [ ] **Step 4: Implement compact Ant Design management UI**

Use a full-width table, toolbar filters, right-side detail drawer and editor drawer. Avoid nested cards. Use icon buttons with tooltips for edit, pause/resume, delete and sync. Maintain stable row/action dimensions and responsive drawer widths.

- [ ] **Step 5: Implement structured editor**

Use selects for category/language/Header/button types, text areas for BODY/Footer, upload control for media, dynamic variable example rows and dynamic button rows. Do not expose raw JSON or provider credentials.

- [ ] **Step 6: Run tests and build**

```bash
npm test
npm run build
```

Expected: all tests pass, TypeScript build succeeds, no new Vite warning beyond any explicitly recorded existing bundle warning.

- [ ] **Step 7: Commit Task 9 files**

```bash
git add demo/message-center-spring/frontend/package.json
git add demo/message-center-spring/frontend/package-lock.json
git add demo/message-center-spring/frontend/vitest.config.ts
git add demo/message-center-spring/frontend/test/setup.ts
git add demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx
git add demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx
git add demo/message-center-spring/frontend/src/components/templates
git commit -m "feat: build whatsapp template management ui"
```

### Task 10: Complete persistent media upload idempotency prerequisite

**Files:**
- Execute: `docs/superpowers/plans/2026-08-11-whatsapp-template-media-idempotency.md`

**Interfaces:**
- Consumes the Task 9 template editor and the approved media idempotency design.
- Produces V10 persistence, the short-transaction upload state machine, status-query HTTP contract, and bounded frontend recovery required by final lifecycle QA.

- [ ] **Step 1: Execute the media idempotency sub-plan**

Complete Tasks 1-4 in `docs/superpowers/plans/2026-08-11-whatsapp-template-media-idempotency.md` in order, including each RED/GREEN cycle and scoped commit. Do not start the browser lifecycle gate while any sub-plan task remains incomplete.

- [ ] **Step 2: Verify the prerequisite completion definition**

Confirm V10 fresh/upgrade migration tests, concurrent same-request gateway-once proof, no-active-transaction proof, admin POST/GET contracts, 45-read frontend bound, frontend build, and backend test suite all pass. Stop if the sub-plan documentation still reports an unresolved automated-test failure.

### Task 11: Complete integration, browser QA and documentation

**Files:**
- Modify: `backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`
- Create: `backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppTemplateLifecycleIntegrationTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-whatsapp-template-lifecycle.md`
- Modify: `docs/superpowers/plans/2026-08-07-whatsapp-message-control-mvp.md`

**Interfaces:**
- Verifies the complete lifecycle without automatic production writes.

- [ ] **Step 1: Add PostgreSQL lifecycle integration tests**

Use a stub `WhatsAppTemplateGateway` bean and real PostgreSQL to execute create, idempotent repeat, approved sync, pause, resume, modify/review, unknown reconciliation and delete. Assert audit and operation rows after every stage.

- [ ] **Step 2: Run backend total verification**

```bash
mvn -q -Dtest='*Test,!AppIntegrationTest,!ChatAppIntegrationTest,!WhatsAppTemplateLifecycleIntegrationTest' test
mvn -q -Dapi.version=1.44 -Dapp.chatapp-outbox-enabled=false -Dtest='AppIntegrationTest,WhatsAppTemplateLifecycleIntegrationTest' test
```

- [ ] **Step 3: Run frontend verification**

```bash
npm test
npm run build
```

- [ ] **Step 4: Start local services and perform browser QA**

Start the backend from IDEA or Maven with `CREDENTIAL_MASTER_KEY` set and CAMS writes stubbed/disabled. Start Vite on an unused loopback port. Verify desktop 1440x900 and mobile 390x844:

- admin can list, create with text/media Header, edit, pause/resume, delete and sync;
- sales do not see admin management actions and only see approved selectable templates;
- loading, empty, rejected, unknown, upload failure and 403 states do not overlap or resize controls;
- no console errors and no blank table/drawer states.

- [ ] **Step 5: Gate real CAMS acceptance**

Stop and request explicit platform-admin approval before any real `Create/Modify/Properties/Delete` call. After approval, use a dedicated test template and non-sensitive test media. Record provider request IDs and final states in a local redacted acceptance note; do not commit credentials or full provider payloads.

- [ ] **Step 6: Update current documentation**

Mark implemented items, exact verification commands/results, warnings, and remaining real-CAMS gate. Do not claim complete production readiness if the real provider write gate was not run.

- [ ] **Step 7: Final scoped diff and git checks**

```bash
git status --short
git diff --check -- demo/message-center-spring docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md docs/superpowers/plans/2026-08-10-whatsapp-template-lifecycle.md docs/superpowers/plans/2026-08-07-whatsapp-message-control-mvp.md
git diff --cached --name-only
```

Confirm `application-dev.yml` was not changed by this plan and no unrelated dirty files are staged.

- [ ] **Step 8: Commit integration and documentation files**

```bash
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppTemplateLifecycleIntegrationTest.java
git add docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md
git add docs/superpowers/plans/2026-08-10-whatsapp-template-lifecycle.md
git add docs/superpowers/plans/2026-08-07-whatsapp-message-control-mvp.md
git commit -m "test: verify whatsapp template lifecycle"
```

## Completion Definition

The feature is complete only when Tasks 1-11 are checked, the media idempotency sub-plan completion definition is satisfied, backend and frontend verification pass, browser QA evidence exists, all provider writes remain behind the real-CAMS approval gate, and documentation states whether that external gate was actually executed.
