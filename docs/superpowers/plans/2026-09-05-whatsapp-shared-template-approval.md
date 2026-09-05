# WhatsApp 共享模板与变更审批 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 WhatsApp 模板从账号级副本升级为单一 CAMS 空间内的系统共享资产，并实现“所有用户可用和可申请、普通用户变更需审批、管理员可直接执行”的完整合同。

**Architecture:** `message_templates` 是共享模板唯一真源，业务身份固定为 `provider_scope_id + provider_template_id + language_code`；`channel_accounts` 只提供当前操作使用的用户私有凭证。`template_change_requests` 独立拥有普通用户审批状态机，`template_operations` 只记录真实上游执行；Flyway 只建立可迁移 schema，应用启动后用既有凭证解密器解析 `custSpaceId`、完成一次性归并与官方对账，未对账完成时由模板专用启动门禁拒绝模板 API。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring Security、MyBatis-Plus、PostgreSQL 17/Flyway、阿里云 CAMS SDK 5.0.5、React 18、TypeScript 5.6、TanStack Query、Ant Design、Vitest、Testcontainers。

## Global Constraints

- 所有登录用户看到并使用同一份共享模板目录；模板没有个人所有权，也不按账号复制。
- 新模板申请由所有登录用户直接提交 CAMS 官方审核，不经过内部审批。
- 普通用户修改、发送权限、停用或删除、媒体绑定只创建内部审批申请；管理员可批准、拒绝、重试，也可使用自己的账号直接执行。
- 同步是只读操作，所有登录用户可触发；定时同步每个 provider scope 只执行一次，并记录实际使用的凭证账号。
- 上游调用必须使用明确的当前用户或申请时账号凭证；审批时账号失效或 scope 不匹配必须结构化失败，禁止替换账号。
- 所有参与共享模板的有效 WhatsApp 账号必须属于同一 `custSpaceId`；检测到多个 scope 时模板门禁保持关闭。
- 旧 `/api/v1/channel-accounts/{accountId}/whatsapp/templates/**` 管理入口整体退出，不保留双写、回退读取或兼容分支。
- 列表每页最大 100 条、申请 payload 最大 64 KiB、幂等键最大 255 字符、拒绝原因最大 500 字符；执行和对账均有最大批次、租约、退避和终止状态。
- 迁移 SQL 不解密 `encrypted_config`；密文只经 `ChatAppAccountCredentialsResolver` 在应用内解析，日志和响应不得输出访问密钥或原始密文。
- 工作区已有大量用户 WIP；每个 Task 只暂存本 Task 文件，禁止 `git add .`，禁止回滚无关改动。

---

## 文件与 owner 划分

| Owner | 文件 | 职责 |
| --- | --- | --- |
| Schema | `demo/message-center-spring/backend/src/main/resources/db/migration/V49__whatsapp_shared_template_approval.sql` | provider scope、共享身份、审批、执行引用、迁移状态和约束 |
| Scope | `WhatsAppProviderScopeService`、`WhatsAppTemplateScopeMigrationService`、`WhatsAppTemplateScopeGate` | 解密 scope、账号绑定、单 scope 门禁、历史归并和迁移报告 |
| Catalog | `WhatsAppSharedTemplateCatalogService`、`TemplateMapper` | 共享模板查询、官方投影 upsert、乐观锁和发送可用性 |
| Provider execution | `WhatsAppTemplateApplicationService`、`WhatsAppTemplateGateway` | 使用明确账号调用 CAMS，记录 operation，不拥有审批策略 |
| Approval | `WhatsAppTemplateChangeRequestService`、`TemplateChangeRequestMapper` | 普通用户申请、管理员批准/拒绝/重试、状态机和幂等 |
| HTTP | `WhatsAppTemplateController`、`AdminWhatsAppTemplateChangeRequestController` | 认证身份映射、DTO 校验和状态码，不拥有授权结论 |
| Send projection | `ChatAppTemplateService`、`TemplateMessageTextResolver` | 按当前用户账号所属 scope 读取共享可发送模板 |
| Frontend | `TemplatesPage` 和 `components/templates/*Change*` | 共享目录、我的申请、管理员审批和结构化差异展示 |

---

### Task 1: 建立共享 schema 与数据库门禁

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V49__whatsapp_shared_template_approval.sql`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppSharedTemplateApprovalMigrationTest.java`

**Interfaces:**
- Consumes: V48 后的 `channel_accounts`、`message_templates`、`template_operations`、`template_media_assets`、`users`。
- Produces: `whatsapp_provider_scopes`、`template_change_requests`、`template_media_bindings`、`whatsapp_template_migration_state`、`whatsapp_template_migration_exceptions`，以及现有表上的共享外键。

- [ ] **Step 1: 写失败的迁移契约测试**

测试先迁移到 V48，插入两个账号和账号级模板副本，再迁移到 V49，断言 schema 具备以下约束：

```java
assertThat(columns(jdbc, schema, "channel_accounts")).contains("provider_scope_id");
assertThat(columns(jdbc, schema, "message_templates"))
        .contains("provider_scope_id", "created_by_user_id");
assertThat(columns(jdbc, schema, "template_operations"))
        .contains("template_id", "change_request_id");
assertThatThrownBy(() -> jdbc.update("insert into " + schema
        + ".template_change_requests (template_id, change_type, requested_payload_jsonb, base_version, "
        + "requested_by_user_id, requested_via_account_id, status, idempotency_key) "
        + "values (?, 'UNKNOWN', '{}'::jsonb, 0, ?, ?, 'PENDING_APPROVAL', 'bad')",
        templateId, userId, accountId)).isInstanceOf(DataIntegrityViolationException.class);
```

测试还必须验证：同一申请人幂等键唯一、一个模板最多一个 `EXECUTING` 申请或 `PROCESSING` operation、同一 scope 模板身份唯一、payload 超过 64 KiB 被拒绝、拒绝状态必须有原因、迁移异常记录不能静默删除。

- [ ] **Step 2: 运行测试并确认因 V49 不存在而失败**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppSharedTemplateApprovalMigrationTest test
```

Expected: FAIL，Flyway 报告找不到 V49 或断言缺少共享表/列。

- [ ] **Step 3: 编写 V49 schema**

迁移核心必须采用以下结构；旧账号字段暂留作历史审计，业务代码不再读取它们：

```sql
CREATE TABLE whatsapp_provider_scopes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    external_scope_id varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'READY',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_provider_scope UNIQUE (provider, external_scope_id),
    CONSTRAINT ck_whatsapp_provider_scope_status CHECK (status IN ('READY','BLOCKED'))
);

ALTER TABLE channel_accounts
    ADD COLUMN provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id);
ALTER TABLE message_templates
    ADD COLUMN provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id),
    ADD COLUMN created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_message_templates_shared_identity
    ON message_templates(provider_scope_id, provider_template_id, language_code)
    WHERE provider_scope_id IS NOT NULL;

CREATE TABLE template_change_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id uuid NOT NULL REFERENCES message_templates(id),
    change_type varchar(30) NOT NULL,
    requested_payload_jsonb jsonb NOT NULL,
    base_version bigint NOT NULL,
    requested_by_user_id uuid NOT NULL REFERENCES users(id),
    requested_via_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    status varchar(30) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    reviewed_by_user_id uuid REFERENCES users(id),
    review_reason varchar(500),
    reviewed_at timestamptz,
    execution_started_at timestamptz,
    execution_completed_at timestamptz,
    execution_error_code varchar(100),
    execution_error_message text,
    provider_request_id varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_template_change_type CHECK
      (change_type IN ('MODIFY','SET_SEND_PERMISSION','DELETE','BIND_MEDIA')),
    CONSTRAINT ck_template_change_status CHECK
      (status IN ('PENDING_APPROVAL','REJECTED','STALE','EXECUTING','SUCCEEDED','EXECUTION_FAILED')),
    CONSTRAINT ck_template_change_payload_size CHECK
      (octet_length(requested_payload_jsonb::text) <= 65536),
    CONSTRAINT ck_template_change_review_reason CHECK
      (status <> 'REJECTED' OR nullif(btrim(review_reason), '') IS NOT NULL),
    CONSTRAINT uq_template_change_idempotency UNIQUE (requested_by_user_id, idempotency_key)
);

CREATE UNIQUE INDEX ux_template_change_single_execution
    ON template_change_requests(template_id) WHERE status = 'EXECUTING';

ALTER TABLE template_operations
    ADD COLUMN template_id uuid REFERENCES message_templates(id),
    ADD COLUMN change_request_id uuid REFERENCES template_change_requests(id);

CREATE UNIQUE INDEX ux_template_operations_single_execution
    ON template_operations(template_id)
    WHERE template_id IS NOT NULL AND operation_status = 'PROCESSING';

CREATE TABLE template_media_bindings (
    template_id uuid NOT NULL REFERENCES message_templates(id),
    media_asset_id uuid NOT NULL REFERENCES template_media_assets(id),
    change_request_id uuid REFERENCES template_change_requests(id),
    bound_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, media_asset_id)
);

CREATE TABLE whatsapp_template_migration_state (
    migration_key varchar(64) PRIMARY KEY,
    status varchar(20) NOT NULL,
    provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id),
    report_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_template_migration_key
      CHECK (migration_key = 'shared-template-v1'),
    CONSTRAINT ck_whatsapp_template_migration_status
      CHECK (status IN ('PENDING','RUNNING','READY','BLOCKED'))
);

CREATE TABLE whatsapp_template_migration_exceptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    migration_key varchar(64) NOT NULL REFERENCES whatsapp_template_migration_state(migration_key),
    resource_type varchar(40) NOT NULL,
    resource_id uuid,
    reason_code varchar(100) NOT NULL,
    details_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_template_migration_details_size
      CHECK (octet_length(details_jsonb::text) <= 65536)
);

CREATE INDEX ix_template_change_review_queue
    ON template_change_requests(status, created_at, id);
CREATE INDEX ix_template_change_requester
    ON template_change_requests(requested_by_user_id, created_at DESC, id DESC);
CREATE INDEX ix_channel_accounts_provider_scope
    ON channel_accounts(provider_scope_id, created_at, id)
    WHERE deleted_at IS NULL AND channel_type IN ('chatapp','whatsapp');
CREATE INDEX ix_whatsapp_template_migration_exceptions
    ON whatsapp_template_migration_exceptions(migration_key, reason_code, created_at, id);
```

- [ ] **Step 4: 运行迁移测试**

Run:

```bash
mvn -Dtest=WhatsAppSharedTemplateApprovalMigrationTest test
```

Expected: 迁移测试全部 PASS，0 failures，0 errors。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V49__whatsapp_shared_template_approval.sql \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppSharedTemplateApprovalMigrationTest.java
git commit -m "feat: add shared WhatsApp template schema"
```

---

### Task 2: 解析 provider scope 并保护切换门禁

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeGate.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountServiceTest.java`

**Interfaces:**
- Consumes: `ChatAppAccountCredentialsResolver.resolve(ChannelAccountEntity)` 和账号 owner 规则。
- Produces: `ScopeAccount requireOwnedActive(UUID userId)`、`ScopeAccount requireAccount(UUID accountId)`、`void requireReady()`。

- [ ] **Step 1: 写 scope 和账号生命周期失败测试**

覆盖：同一 `custSpaceId` 复用同一 scope；第二个不同 `custSpaceId` 被 `WHATSAPP_PROVIDER_SCOPE_MISMATCH` 拒绝；账号不属于当前用户、已禁用或密文不可读均失败；更新凭证不能跨 scope；错误不含密钥。

```java
ScopeAccount resolved = service.requireOwnedActive(USER_ID);
assertThat(resolved.account().getId()).isEqualTo(ACCOUNT_ID);
assertThat(resolved.scope().getExternalScopeId()).isEqualTo("space-1");
verify(scopeMapper).insertIgnore("ALIYUN_CAMS", "space-1");
```

- [ ] **Step 2: 运行专项测试并确认缺少类型/服务**

```bash
cd demo/message-center-spring/backend
mvn -Dtest='WhatsAppProviderScopeServiceTest,ChannelAccountServiceTest' test
```

Expected: FAIL，编译器报告 scope 类型和方法不存在。

- [ ] **Step 3: 实现 scope owner**

公开合同固定为：

```java
public record ScopeAccount(ChannelAccountEntity account, WhatsAppProviderScopeEntity scope) {}

public ScopeAccount requireOwnedActive(UUID userId);
public ScopeAccount requireAccount(UUID accountId);
public WhatsAppProviderScopeEntity bind(ChannelAccountEntity account);
public void assertCompatible(String custSpaceId);
```

`requireOwnedActive` 必须调用 `findByOwnerAndChannelType(userId, "chatapp")`，严格要求一个有效账号；`bind` 通过凭证解析器读取 `custSpaceId`，以 `ALIYUN_CAMS + custSpaceId` upsert scope，再用条件更新写入 `channel_accounts.provider_scope_id`。`ChannelAccountService.createOrBind()` 与 `updateCredentials()` 在提交新凭证前调用 `assertCompatible`，成功后绑定 scope；解绑只禁用账号，不清除 scope，以便历史申请能够判定“原账号失效”。

门禁合同为：

```java
public void open(UUID providerScopeId);
public void fail(String code, String publicMessage);
public UUID requireReady();
```

`requireReady()` 失败时抛 `WhatsAppTemplateException`，HTTP 503，code 为 `WHATSAPP_TEMPLATE_MIGRATION_PENDING` 或持久化的结构化失败码。

- [ ] **Step 4: 运行专项测试**

```bash
mvn -Dtest='WhatsAppProviderScopeServiceTest,ChannelAccountServiceTest,ChannelAccountOwnerIsolationTest' test
```

Expected: 所有测试 PASS，跨用户账号仍返回不可访问。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeGate.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeServiceTest.java
git commit -m "feat: bind WhatsApp accounts to provider scope"
```

---

### Task 3: 归并账号副本并把同步切到共享目录

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppTemplateMigrationExceptionEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppTemplateMigrationMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationRunner.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `ScopeAccount` 和 V49 迁移表。
- Produces: `SyncResult syncScope(UUID providerScopeId, UUID credentialAccountId)`，共享 mapper 查询和启动迁移报告。

- [ ] **Step 1: 写归并、官方覆盖与停止条件测试**

必须覆盖：同 scope 两个账号的同代码/语言副本只保留一个 canonical row；operation 和可识别媒体绑定重连；官方详情覆盖本地冲突但保留共享备注；多 scope、无法关联 operation、已标记 ATTACHED 却找不到模板的媒体形成迁移异常并关闭门禁；重复运行无新增副作用。

```java
MigrationReport report = migration.migrate();
assertThat(report.status()).isEqualTo(MigrationStatus.READY);
assertThat(report.scopeCount()).isEqualTo(1);
assertThat(report.canonicalTemplates()).isEqualTo(1);
verify(gate).open(SCOPE_ID);
verify(reconciliation).syncScope(SCOPE_ID, ACCOUNT_A);
```

- [ ] **Step 2: 运行测试确认旧同步仍按账号产生副本**

```bash
mvn -Dtest='WhatsAppTemplateScopeMigrationServiceTest,WhatsAppTemplateReconciliationServiceTest,ChatAppTemplateSyncServiceTest' test
```

Expected: FAIL，旧 mapper 仍以 `channel_account_id` 查询/upsert。

- [ ] **Step 3: 实现共享归并与同步**

`TemplateSnapshot` 只表达上游事实，不携带本地账号或 scope 身份：

```java
public record TemplateSnapshot(
        String templateCode,
        String templateName,
        String language,
        String category,
        ReviewStatus reviewStatus,
        String rawAuditStatus,
        String rejectionReason,
        boolean allowSend,
        List<TemplateComponent> components,
        Map<String, List<String>> examples,
        Integer messageSendTtlSeconds,
        Instant providerUpdatedAt,
        Instant deletedAt) {}
```

Gateway 仍接收 `accountId`，因为它只负责凭证选择；service 在写入前把 snapshot 投影到已验证的 `providerScopeId`。`TemplateMapper` 提供并只由新业务使用：

```java
Optional<TemplateEntity> findBySharedIdentity(UUID scopeId, String code, String language);
Optional<TemplateEntity> findSharedForUpdate(UUID templateId);
List<TemplateEntity> findScopeTemplates(UUID scopeId);
List<TemplateEntity> findScopeSendable(UUID scopeId);
int upsertShared(TemplateEntity entity);
```

迁移顺序固定为：绑定所有有效账号 scope；验证 scope 数为 0 或 1；按代码/语言选择稳定 survivor；重连 operation 和媒体；把无法证明的引用写入 exception；异常为 0 时使用一个健康账号执行完整官方同步；对账后把 migration state 置为 `READY` 并打开门禁。`runOnce()` 按 scope 分组，每个 scope 依次尝试健康账号，首个成功后停止，禁止每个账号重复同步。

- [ ] **Step 4: 运行归并与同步专项测试**

```bash
mvn -Dtest='WhatsAppTemplateScopeMigrationServiceTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppTemplateReconciliationSchedulerTest,ChatAppTemplateSyncServiceTest' test
```

Expected: PASS；同一 scope 只发生一次 list/detail 同步。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppTemplateMigrationExceptionEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppTemplateMigrationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationRunner.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateScopeMigrationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java
git commit -m "feat: migrate WhatsApp templates to shared catalog"
```

---

### Task 4: 提供共享目录、新模板申请、公共库和媒体 API

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateCatalogService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/SharedTemplateResponse.java`
- Delete: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateAdminResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateOperationResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateCatalogServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java`

**Interfaces:**
- Consumes: shared mapper、scope gate、当前用户唯一有效 WhatsApp 账号。
- Produces: 不含账号身份的共享目录 API，以及用户私有的媒体上传结果。

- [ ] **Step 1: 写共享 API 失败测试**

断言 AGENT 和 ADMIN 均可 list/detail/sync/public-list/create/upload；list/detail 不调用 `requireOwned(accountId)`；create 使用当前用户唯一账号；响应模板包含 `id/version` 且不含 `accountId`；匿名 401；scope 门禁关闭时 503；旧账号级 URL 404。

```java
mvc.perform(get("/api/v1/whatsapp/templates").with(agent()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].providerScopeId").doesNotExist())
        .andExpect(jsonPath("$.items[0].accountId").doesNotExist());
```

- [ ] **Step 2: 运行测试并确认旧 controller 路径失败**

```bash
mvn -Dtest='WhatsAppSharedTemplateCatalogServiceTest,WhatsAppTemplateApplicationServiceTest,PublicTemplateApplicationServiceTest,WhatsAppTemplateControllerTest' test
```

Expected: FAIL，新共享路由未映射。

- [ ] **Step 3: 实现共享读取和直接新建**

Controller 根路径改为 `/api/v1/whatsapp`，公开以下方法：

```java
GET  /templates
GET  /templates/{templateId}
POST /templates/applications
POST /templates/sync
GET  /templates/{templateId}/operations
GET  /public-templates
POST /template-media
GET  /template-media/uploads/{clientRequestId}
```

目录 service 合同为：

```java
TemplatePageView list(UUID actorUserId, int page, int size, TemplateFilters filters);
TemplateView detail(UUID actorUserId, UUID templateId);
List<OperationHistoryView> history(UUID actorUserId, UUID templateId);
```

共享响应合同必须删除账号和旧权限重放字段：

```java
public record SharedTemplateResponse(
        UUID id,
        long version,
        String templateCode,
        String name,
        String remark,
        String displayName,
        String language,
        String category,
        String reviewStatus,
        String providerAuditStatus,
        String rejectionReason,
        boolean allowSend,
        List<TemplateComponent> components,
        Map<String, List<String>> examples,
        Integer messageSendTtlSeconds,
        String qualityScore,
        Instant providerUpdatedAt,
        Instant lastSyncedAt,
        Instant deletedAt) {}
```

新模板执行合同为：

```java
OperationView create(
        UUID providerScopeId,
        UUID credentialAccountId,
        TemplateCommand command,
        UUID actorUserId,
        String traceId);
```

创建成功或上游已接受后，以 scope 身份 upsert 共享模板并写 `created_by_user_id`；官方审核状态保持真实值。公共模板读取和媒体上传先由 `requireOwnedActive(actorUserId)` 获取账号，不接受前端传入 accountId。媒体仍写 `channel_account_id` 和 `created_by_user_id`，因此其他用户不能查询该上传。

- [ ] **Step 4: 运行共享 API 专项测试**

```bash
mvn -Dtest='WhatsAppSharedTemplateCatalogServiceTest,WhatsAppTemplateApplicationServiceTest,PublicTemplateApplicationServiceTest,WhatsAppTemplateMediaUploadServiceTest,WhatsAppTemplateControllerTest' test
```

Expected: PASS；旧管理 URL 明确 404，新模板申请直接调用一次 gateway.create。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateCatalogService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/SharedTemplateResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateAdminResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateOperationResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateCatalogServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/PublicTemplateApplicationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java
git commit -m "feat: expose shared WhatsApp template catalog"
```

---

### Task 5: 实现普通用户变更申请状态机

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateChangeRequestEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateChangeRequestMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateChangeCommandRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateChangeRequestResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java`

**Interfaces:**
- Consumes: 当前模板 version、当前用户账号和 scope、Task 4 共享目录。
- Produces: `submit`、`listMine` 和结构化申请投影；本 Task 不执行上游。

- [ ] **Step 1: 写普通用户权限、幂等和过期测试**

覆盖四种 change type、共享备注作为 `MODIFY` payload、媒体必须属于申请人账号且为 `UPLOADED`、同键同 payload 重放、同键不同 payload 409、baseVersion 不一致立即 `STALE`、普通用户提交时 gateway 零调用。

```java
ChangeOutcome outcome = service.submit(USER_ID, TEMPLATE_ID,
        new ChangeCommand(ChangeType.SET_SEND_PERMISSION, 4L, "req-1", null, false, null), "trace-1");
assertThat(outcome.mode()).isEqualTo(ChangeMode.APPROVAL_REQUIRED);
assertThat(outcome.request().status()).isEqualTo(ChangeRequestStatus.PENDING_APPROVAL);
verifyNoInteractions(gateway);
```

- [ ] **Step 2: 运行测试确认审批模型不存在**

```bash
mvn -Dtest='WhatsAppTemplateChangeRequestServiceTest,WhatsAppTemplateControllerTest' test
```

Expected: FAIL，缺少 request entity/mapper/service。

- [ ] **Step 3: 实现申请合同**

核心命令和结果固定为：

```java
public enum ChangeType {
    MODIFY, SET_SEND_PERMISSION, DELETE, BIND_MEDIA
}

public enum ChangeRequestStatus {
    PENDING_APPROVAL, REJECTED, STALE, EXECUTING, SUCCEEDED, EXECUTION_FAILED
}

public enum ChangeMode {
    APPROVAL_REQUIRED, DIRECT
}

public record TemplateDraft(
        String name,
        String category,
        List<TemplateComponent> components,
        Map<String, List<String>> examples,
        Integer messageSendTtlSeconds) {}

public record ChangeCommand(
        ChangeType changeType,
        long expectedVersion,
        String clientRequestId,
        TemplateDraft template,
        Boolean allowSend,
        String remark) {}

public record ChangeOutcome(
        ChangeMode mode,
        TemplateChangeRequestView request,
        OperationView operation) {}
```

`TemplateChangeCommandRequest` 只包含上述六个业务字段；`MODIFY/BIND_MEDIA` 必须提供完整 `template` 快照，`SET_SEND_PERMISSION` 必须且只能提供 `allowSend`，`DELETE` 不接受模板内容，备注-only 修改使用 `MODIFY + remark`。service 使用现有模板的 language 和顶层 `clientRequestId` 将 `TemplateDraft` 转成 provider `TemplateCommand`，请求体内不存在第二份幂等键。响应由 service 投影为结构化差异：

```java
public record TemplateChangeRequestResponse(
        UUID id,
        UUID templateId,
        String templateDisplayName,
        long baseVersion,
        String changeType,
        String status,
        List<FieldDiff> diffs,
        String requestedByDisplayName,
        String reviewedByDisplayName,
        String reviewReason,
        String executionErrorCode,
        String executionErrorMessage,
        String providerRequestId,
        Instant createdAt,
        Instant reviewedAt,
        Instant executionCompletedAt) {
    public record FieldDiff(String field, String label, Object beforeValue, Object afterValue) {}
}
```

原始 `requested_payload_jsonb` 不从 controller 返回；媒体差异只返回已提交 asset 的内部 ID、格式、大小和受控预览地址，不返回账号凭证或上游授权字段。

普通用户调用 `POST /api/v1/whatsapp/templates/{templateId}/change-requests` 时，service 读取当前模板、解析用户唯一账号、验证 scope、媒体 owner、payload 上界和幂等，然后保存 `PENDING_APPROVAL`。`GET /api/v1/whatsapp/template-change-requests/mine?page=1&size=20` 只按 `requested_by_user_id` 返回本人的申请。DTO 不接受申请人、审批人、scope、凭证账号或“管理员直执行”字段。

- [ ] **Step 4: 运行申请专项测试**

```bash
mvn -Dtest='WhatsAppTemplateChangeRequestServiceTest,WhatsAppTemplateControllerTest' test
```

Expected: PASS；普通用户所有既有模板变更均无上游调用。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateChangeRequestEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateChangeRequestMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateChangeCommandRequest.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateChangeRequestResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java
git commit -m "feat: add WhatsApp template change requests"
```

---

### Task 6: 实现管理员审批、拒绝、重试和直接执行

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateChangeReviewRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateChangeRequestMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateOperationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateOperationEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 5 pending request 和 `RoleMapper.userHasRole(userId, "admin")`。
- Produces: approve/reject/retry、管理员直接执行、执行 operation 与申请终态一致性。

- [ ] **Step 1: 写审批并发和凭证隔离失败测试**

覆盖：非管理员 403；拒绝必须有原因；批准用 `requested_via_account_id`；申请账号禁用或跨 scope 转 `EXECUTION_FAILED` 且不替换账号；模板 version 变化转 `STALE`；并发批准仅一次 claim；管理员直接变更使用管理员自己的账号且不创建审批；明确上游失败不改模板；submission unknown 保持 `EXECUTING` 等待有界 reconcile；只有 `EXECUTION_FAILED` 可重试。

```java
service.approve(ADMIN_ID, REQUEST_ID, "approve-1", "trace-1");
verify(gateway).modify(eq(REQUESTED_ACCOUNT_ID), eq("tpl-1"), eq("zh_CN"), any());
verify(gateway, never()).modify(eq(ADMIN_ACCOUNT_ID), any(), any(), any());
```

- [ ] **Step 2: 运行审批测试并确认直接执行策略缺失**

```bash
mvn -Dtest='WhatsAppTemplateChangeRequestServiceTest,WhatsAppTemplateApplicationServiceTest,AdminWhatsAppTemplateChangeRequestControllerTest' test
```

Expected: FAIL，新 admin controller 和 claim/finalize SQL 不存在。

- [ ] **Step 3: 实现两阶段 claim 和执行**

Mapper 必须用条件更新完成状态转移，不在持有数据库行锁时等待网络：

```java
int claimApproval(UUID requestId, UUID reviewerId, Instant now);
int markStale(UUID requestId, UUID reviewerId, Instant now);
int markSucceeded(UUID requestId, String providerRequestId, Instant now);
int markExecutionFailed(UUID requestId, String code, String message, Instant now);
```

批准流程先原子 claim `PENDING_APPROVAL -> EXECUTING` 并检查 `message_templates.version = base_version`，再在事务外调用 gateway，最后用短事务刷新官方详情、递增模板 version、写 operation 和申请终态。`SUBMISSION_UNKNOWN` 由现有 reconcile worker 以最大 10 次、24 小时窗口收敛，单个失败不占据后续申请队头。

管理员 API：

```text
GET  /api/v1/admin/whatsapp/template-change-requests
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/approve
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/reject
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/retry
```

普通变更入口内部检查角色：AGENT 调 `submit`；ADMIN 用自己的 `ScopeAccount` 调 application service 直接执行并返回 `mode=DIRECT`。角色判断必须位于 service，不能只依赖前端。

- [ ] **Step 4: 运行权限矩阵和状态机测试**

```bash
mvn -Dtest='WhatsAppTemplateChangeRequestServiceTest,WhatsAppTemplateApplicationServiceTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppTemplateControllerTest,AdminWhatsAppTemplateChangeRequestControllerTest' test
```

Expected: PASS；重复批准不产生第二次上游调用，跨用户凭证从未被替换。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/TemplateChangeReviewRequest.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateChangeRequestMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateOperationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateOperationEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminWhatsAppTemplateChangeRequestControllerTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java
git commit -m "feat: govern shared template changes"
```

---

### Task 7: 统一发送解析、媒体绑定并移除账号级残影

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadStore.java`
- Delete: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationService.java`
- Delete: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateRemarkService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolverTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java`
- Delete: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationServiceTest.java`
- Delete: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateRemarkServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateNoLegacyPathTest.java`

**Interfaces:**
- Consumes: 当前发送账号 `provider_scope_id` 和共享模板身份。
- Produces: 单聊、群发和历史展示一致的共享模板解析；源代码门禁确保旧账号级管理路径退出。

- [ ] **Step 1: 写发送 scope 和旧路径扫描失败测试**

测试必须证明：用户 A/B 在同 scope 可发送同一 template；不同 scope 账号拒绝；发送仍只使用各自账号凭证；历史消息按消息账号 scope 找共享模板；媒体绑定成功后建立 `template_media_bindings`；源代码不再包含旧管理 mapping 和账号级 `findForSend/findForDisplay/findGloballySendable`。

```java
String rendered = resolver.renderForSend(ACCOUNT_B, "shipping_notice", "zh_CN", Map.of("name", "张三"));
assertThat(rendered).isEqualTo("张三，货物已发出");
verify(templateMapper).findSharedForSend(SCOPE_ID, "shipping_notice", "zh_CN");
```

- [ ] **Step 2: 运行测试确认发送仍绑定模板副本**

```bash
mvn -Dtest='ChatAppTemplateServiceTest,TemplateMessageTextResolverTest,ChatAppBroadcastApplicationServiceTest,WhatsAppSharedTemplateNoLegacyPathTest' test
```

Expected: FAIL，旧查询仍要求 `template.channel_account_id == accountId`。

- [ ] **Step 3: 切换所有消费者**

`TemplateMessageTextResolver.renderForSend(accountId, code, language, params)` 保留调用签名，但先解析 account scope，再调用：

```java
Optional<TemplateEntity> findSharedForSend(UUID providerScopeId, String code, String language);
Optional<TemplateEntity> findSharedForDisplay(UUID providerScopeId, String code, String language);
```

`ChatAppTemplateService.listAll()` 改成通过模板门禁的唯一共享目录；`listForAccount(accountId)` 只用账号 scope 做可见性验证，不再检查 template.accountId。群发创建也必须验证所选模板属于发送账号 scope。删除 `desired_allow_send` 驱动的自动权限重放 service、entity/API 字段和调度接线：权限变更只由已批准申请或管理员直接执行，定时同步只读取官方真实状态，不能绕过审批再次写上游。账号级 `WhatsAppTemplateRemarkService` 同时删除；共享备注统一由 `MODIFY` 申请或管理员直接变更处理。

旧账号级 controller mapping、endpoint helper、mapper 查询和测试整体删除；不提供 301、别名 controller 或 fallback。

- [ ] **Step 4: 运行发送和无旧路径专项测试**

```bash
mvn -Dtest='ChatAppTemplateServiceTest,TemplateMessageTextResolverTest,ThreadServiceTemplateRenderingTest,ChatAppBroadcastApplicationServiceTest,WhatsAppSharedTemplateNoLegacyPathTest' test
```

Expected: PASS；共享模板不扩大联系人、消息或账号凭证权限。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadStore.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateRemarkService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolverTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateRemarkServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateNoLegacyPathTest.java
git commit -m "refactor: use shared templates for WhatsApp sending"
```

---

### Task 8: 改造前端共享目录、我的申请和管理员审批

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/SharedTemplateCardGrid.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/SharedTemplateDetailModal.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/sharedTemplatePreview.ts`
- Delete: `demo/message-center-spring/frontend/src/components/templates/OwnedTemplateCardGrid.tsx`
- Delete: `demo/message-center-spring/frontend/src/components/templates/OwnedTemplateDetailModal.tsx`
- Delete: `demo/message-center-spring/frontend/src/components/templates/ownedTemplatePreview.ts`
- Modify: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateChangeRequestList.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateChangeRequestList.test.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateChangeDiffModal.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateChangeDiffModal.test.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateAdminApprovalPanel.tsx`
- Create: `demo/message-center-spring/frontend/src/components/templates/TemplateAdminApprovalPanel.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/templates/publicTemplateWorkbench.css`

**Interfaces:**
- Consumes: Task 4-6 共享 API 和 `useAuth().isAdmin`。
- Produces: 无账号选择器的共享模板工作台和完整审批可见状态。

- [ ] **Step 1: 写前端角色和交互失败测试**

覆盖：页面标题和 tab 为“共享模板”；普通用户编辑/发送权限/删除显示“提交审批”且 API 返回后进入“我的申请”；管理员看到“影响所有用户”确认并直接执行；拒绝原因必填；STALE 禁用批准；EXECUTION_FAILED 显示重试；结构化 diff 不渲染原始 JSON；新建模板仍直接显示“已提交官方审核”；同步所有用户可用。

```tsx
expect(screen.getByRole('tab', { name: '共享模板' })).toBeVisible();
await user.click(screen.getByRole('button', { name: '编辑 shipping_notice' }));
expect(screen.getByRole('button', { name: '提交审批' })).toBeVisible();
expect(api.createTemplateChangeRequest).toHaveBeenCalledWith(template.id, expect.objectContaining({
  expectedVersion: template.version,
}));
```

- [ ] **Step 2: 运行前端专项测试并确认旧 API/文案失败**

```bash
cd demo/message-center-spring/frontend
npm run test:ui -- src/pages/TemplatesPage.test.tsx src/components/templates/TemplateChangeRequestList.test.tsx src/components/templates/TemplateChangeDiffModal.test.tsx src/components/templates/TemplateAdminApprovalPanel.test.tsx
```

Expected: FAIL，缺少新组件且页面仍显示“我的模板”。

- [ ] **Step 3: 实现前端合同和 UI**

API 类型固定为：

```ts
export type TemplateChangeType = 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE' | 'BIND_MEDIA';
export type TemplateChangeStatus =
  | 'PENDING_APPROVAL' | 'REJECTED' | 'STALE'
  | 'EXECUTING' | 'SUCCEEDED' | 'EXECUTION_FAILED';

export interface SharedTemplate {
  id: string;
  version: number;
  templateCode: string;
  name: string;
  remark: string | null;
  displayName: string;
  language: string;
  category: string | null;
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED' | 'UNKNOWN';
  providerAuditStatus: string | null;
  rejectionReason: string | null;
  allowSend: boolean;
  components: TemplateComponent[];
  examples: Record<string, string[]>;
  messageSendTtlSeconds: number | null;
  qualityScore: string | null;
  providerUpdatedAt: string | null;
  lastSyncedAt: string | null;
  deletedAt: string | null;
}

export interface TemplateChangeRequestView {
  id: string;
  templateId: string;
  templateDisplayName: string;
  baseVersion: number;
  changeType: TemplateChangeType;
  status: TemplateChangeStatus;
  diffs: Array<{ field: string; label: string; beforeValue: unknown; afterValue: unknown }>;
  requestedByDisplayName: string;
  reviewedByDisplayName: string | null;
  reviewReason: string | null;
  executionErrorCode: string | null;
  executionErrorMessage: string | null;
  providerRequestId: string | null;
  createdAt: string;
  reviewedAt: string | null;
  executionCompletedAt: string | null;
}
```

`whatsappManagementBase()` 改为常量 `/v1/whatsapp`，所有模板调用以 `template.id` 定位；账号查询只用于显示“未配置 WhatsApp 账号”的可操作门禁，不再参与模板 query key 或 URL。Tabs 为“共享模板”“公共模板库”“我的申请”，管理员增加“变更审批”。差异 modal 逐字段显示名称、备注、正文、组件、发送权限和媒体变化；状态文案统一为“等待审批、已批准正在执行、执行成功、已拒绝、模板版本已变化、执行失败”。

- [ ] **Step 4: 运行前端专项与生产构建**

```bash
npm run test:ui -- src/pages/TemplatesPage.test.tsx src/components/templates/TemplateEditorDrawer.test.tsx src/components/templates/TemplateChangeRequestList.test.tsx src/components/templates/TemplateChangeDiffModal.test.tsx src/components/templates/TemplateAdminApprovalPanel.test.tsx
npm run build
```

Expected: 测试 PASS；TypeScript 与 Vite build 成功，无 warning。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx \
  demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx \
  demo/message-center-spring/frontend/src/components/templates/SharedTemplateCardGrid.tsx \
  demo/message-center-spring/frontend/src/components/templates/SharedTemplateDetailModal.tsx \
  demo/message-center-spring/frontend/src/components/templates/sharedTemplatePreview.ts \
  demo/message-center-spring/frontend/src/components/templates/OwnedTemplateCardGrid.tsx \
  demo/message-center-spring/frontend/src/components/templates/OwnedTemplateDetailModal.tsx \
  demo/message-center-spring/frontend/src/components/templates/ownedTemplatePreview.ts \
  demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateChangeRequestList.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateChangeRequestList.test.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateChangeDiffModal.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateChangeDiffModal.test.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateAdminApprovalPanel.tsx \
  demo/message-center-spring/frontend/src/components/templates/TemplateAdminApprovalPanel.test.tsx \
  demo/message-center-spring/frontend/src/components/templates/publicTemplateWorkbench.css
git commit -m "feat: add shared template approval workspace"
```

---

### Task 9: 全量回归、浏览器验收、文档和发布制品

**Files:**
- Modify: `docs/superpowers/README.md`
- Create: `docs/superpowers/reviews/2026-09-05-whatsapp-shared-template-approval-verification.md`
- Modify: `demo/message-center-spring/README.md`

**Interfaces:**
- Consumes: Task 1-8 的共享模板完整链路。
- Produces: 可复验的迁移/权限/UI 证据以及 `backend/target/message-center.jar` 和前端 ZIP。

- [ ] **Step 1: 运行 schema 和后端专项门禁**

```bash
cd demo/message-center-spring/backend
mvn -Dtest='WhatsAppSharedTemplateApprovalMigrationTest,WhatsAppProviderScopeServiceTest,WhatsAppTemplateScopeMigrationServiceTest,WhatsAppSharedTemplateCatalogServiceTest,WhatsAppTemplateChangeRequestServiceTest,WhatsAppTemplateApplicationServiceTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppTemplateControllerTest,AdminWhatsAppTemplateChangeRequestControllerTest,ChatAppTemplateServiceTest,TemplateMessageTextResolverTest,ChatAppBroadcastApplicationServiceTest,WhatsAppSharedTemplateNoLegacyPathTest' test
```

Expected: 全部 PASS，0 failures，0 errors。

- [ ] **Step 2: 运行前后端全量测试和构建**

```bash
cd demo/message-center-spring/backend
mvn test
mvn -Pproduction clean package

cd ../frontend
npm test
npm run build
```

Expected: 后端、前端全量测试均 PASS；`backend/target/message-center.jar` 和 `frontend/dist/index.html` 存在；无新增 warning、lint/type error。

- [ ] **Step 3: 启动本地前后端并完成浏览器验收**

使用现有 Docker PostgreSQL/MinIO，后端不启用企业微信模块；准备同 scope 的 AGENT 与 ADMIN 测试账号。桌面 1440x900 和移动 390x844 分别验证：共享目录一致；普通用户申请不直接改变模板；管理员差异审批、拒绝原因、STALE、失败重试；管理员直接变更确认；新模板直接申请；无账号空态；长模板名称、错误态、加载态不重叠。

证据写入验收文档，包含实际 URL、截图路径、浏览器 console 结果、测试用户角色和停止条件。真实 CAMS 账号仅执行可回滚测试模板，不使用生产业务模板。

- [ ] **Step 4: 打包并校验制品**

```bash
cd demo/message-center-spring
test -f backend/target/message-center.jar
jar tf backend/target/message-center.jar | grep 'V49__whatsapp_shared_template_approval.sql'

test ! -e frontend-dist-20260905-whatsapp-shared-template-approval.zip
zip -qr frontend-dist-20260905-whatsapp-shared-template-approval.zip frontend/dist
unzip -t frontend-dist-20260905-whatsapp-shared-template-approval.zip
shasum -a 256 backend/target/message-center.jar frontend-dist-20260905-whatsapp-shared-template-approval.zip
```

Expected: Jar 包含 V49；ZIP 完整性为 `No errors detected`；两个 SHA-256 写入验收文档。执行前确认目标 ZIP 是本 Task 固定文件名，若已存在先停止并核对，不能覆盖未知用户制品。

- [ ] **Step 5: 回写真源和部署门禁**

`demo/message-center-spring/README.md` 增加：V49 迁移后查看 `whatsapp_template_migration_state` 和异常表的 SQL；门禁失败码；共享 API；旧 URL 已删除；先备份数据库、替换 Jar、启动、确认 migration state 为 READY，再替换前端的部署顺序。验收文档记录真实命令和结果，不写推测结论。

```bash
git add docs/superpowers/README.md \
  docs/superpowers/reviews/2026-09-05-whatsapp-shared-template-approval-verification.md \
  demo/message-center-spring/README.md
git commit -m "docs: verify shared WhatsApp template approvals"
```

---

## 最终自审门禁

- [ ] **Spec coverage:** 逐条对照设计文档验收标准 1-12，分别指向 Task 1-9 的自动化或实机证据；任何一条无证据则不宣布完成。
- [ ] **Placeholder scan:** 运行 `rg -n 'TB''D|TO''DO|implement ''later|fill ''in|稍后''实现|后续''补' docs/superpowers/plans/2026-09-05-whatsapp-shared-template-approval.md`，结果必须为空。
- [ ] **Type consistency:** 对照 `ChangeType`、`ChangeRequestStatus`、`ChangeCommand`、`ChangeOutcome`、`ScopeAccount` 的 Java/TypeScript 名称和值，API JSON 不允许另造别名。
- [ ] **No legacy path:** 运行 `rg -n '/api/v1/channel-accounts/.*/whatsapp/templates|whatsappManagementBase\(accountId\)|findGloballySendable|findSendableForChannelAccount|findForSend\(' demo/message-center-spring/backend/src demo/message-center-spring/frontend/src`，除明确断言旧路径不存在的测试字符串外必须为空。
- [ ] **Git boundary:** 运行 `git status --short` 和 `git diff --cached --name-only`，每次提交只包含当前 Task 文件；`AiTopicStoreApprovalServiceTest.java` 等既有 WIP 不得被模板提交吸入。
