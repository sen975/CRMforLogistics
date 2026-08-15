# WhatsApp 模板默认启用与状态对账实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 让已审核模板默认请求 CAMS 启用，同时持久化用户手动关闭意图，区分期望状态和 CAMS 实际发送权限。

**架构：** 新增 `desired_allow_send` 及有界退避字段；`allow_send` 继续表示 CAMS 实际状态。用户操作由 `WhatsAppTemplateApplicationService` 拥有，自动同步由 `WhatsAppTemplatePermissionReconciliationService` 拥有，现有模板同步只触发两者，不在 Controller 或前端计算发送资格。

**技术栈：** Java 17、Spring Boot 3.4.5、MyBatis-Plus、PostgreSQL、Flyway、JUnit 5、Mockito、React 18、TypeScript、Ant Design。

## 全局约束

- 只有 `APPROVED`、未删除且 `allow_send=true` 的模板可发送。
- `desired_allow_send=true` 不能替代 CAMS 实际权限；实际关闭时发送选择器必须拒绝。
- `REJECTED`、`PENDING`、已删除模板不触发自动启用。
- 自动对账每账号每轮最多 20 个模板；退避为 1 分钟、5 分钟、15 分钟、1 小时、6 小时、24 小时封顶。
- 用户手动关闭后重启不自动重新打开；再次手动操作清零失败计数并立即同步。

---

### 任务 1：写入模板状态失败测试

**文件：**
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationServiceTest.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolverTest.java`

- [ ] **步骤 1：先写失败测试**

```java
@Test
void manualDisablePersistsDesiredStateWhileProviderActualStateIsUpdated() {
    TemplateEntity template = template("tpl-1", "APPROVED", true);
    when(templateMapper.selectOne(any())).thenReturn(template);
    when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-disable"))
            .thenReturn(Optional.empty());
    when(operationMapper.insertIgnore(any())).thenReturn(1);
    when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
    when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", false))
            .thenReturn(new PropertyResult(false, "req-disable"));

    service.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", false,
            "client-disable", ACTOR_ID, "trace-disable");

    ArgumentCaptor<TemplateEntity> updated = ArgumentCaptor.forClass(TemplateEntity.class);
    verify(templateMapper).updateById(updated.capture());
    assertThat(updated.getValue().getDesiredAllowSend()).isFalse();
    assertThat(updated.getValue().getAllowSend()).isFalse();
}

@Test
void approvedTemplateWithDesiredEnabledAndActualDisabledIsQueuedForProviderEnable() {
    TemplateEntity template = template("tpl-1", "APPROVED", false);
    template.setDesiredAllowSend(true);
    when(templateMapper.findPermissionReconciliationCandidates(any(Instant.class), eq(20)))
            .thenReturn(List.of(template));

    service.reconcileDueTemplates("worker-1");

    verify(gateway).setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true);
}

@Test
void rejectedTemplateIsNeverAutomaticallyEnabled() {
    TemplateEntity template = template("tpl-1", "REJECTED", false);
    template.setDesiredAllowSend(true);
    when(templateMapper.findPermissionReconciliationCandidates(any(Instant.class), eq(20)))
            .thenReturn(List.of());

    service.reconcileDueTemplates("worker-1");

    verifyNoInteractions(gateway);
}
```

- [ ] **步骤 2：运行失败测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplateApplicationServiceTest,WhatsAppTemplatePermissionReconciliationServiceTest test
```

预期：失败，原因是新状态字段、候选查询和自动对账 owner 尚未存在。

### 任务 2：创建 V18 模板权限状态迁移

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/resources/db/migration/V18__whatsapp_template_permission_intent.sql`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppTemplatePermissionMigrationTest.java`

- [ ] **步骤 1：写迁移 SQL**

```sql
ALTER TABLE message_templates
    ADD COLUMN desired_allow_send boolean NOT NULL DEFAULT true,
    ADD COLUMN permission_sync_status varchar(20) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN permission_sync_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN permission_sync_next_attempt_at timestamptz,
    ADD COLUMN permission_sync_error_code varchar(100),
    ADD COLUMN permission_sync_error_message text;

ALTER TABLE message_templates
    ADD CONSTRAINT ck_template_permission_sync_status
        CHECK (permission_sync_status IN ('IDLE', 'PENDING', 'FAILED')),
    ADD CONSTRAINT ck_template_permission_attempt_count
        CHECK (permission_sync_attempt_count >= 0);

CREATE INDEX ix_message_templates_permission_reconcile
    ON message_templates(channel_account_id, permission_sync_next_attempt_at, updated_at)
    WHERE deleted_at IS NULL AND upper(status) = 'APPROVED'
      AND desired_allow_send <> allow_send;
```

历史模板默认 `desired_allow_send=true`；不更新 `allow_send`，以保留 CAMS 实际快照。

- [ ] **步骤 2：扩展 Entity 字段**

在 `TemplateEntity` 增加 `desiredAllowSend`、`permissionSyncStatus`、`permissionSyncAttemptCount`、`permissionSyncNextAttemptAt`、`permissionSyncErrorCode`、`permissionSyncErrorMessage` 的 getter/setter，并让 MyBatis-Plus 按下划线命名映射。

- [ ] **步骤 3：运行迁移合同测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplatePermissionMigrationTest test
```

断言历史模板默认期望开启、`allow_send` 原值不被覆盖、约束拒绝非法状态和负重试次数。

### 任务 3：扩展 TemplateMapper 查询与更新合同

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/TemplatePermissionMapperSqlTest.java`

- [ ] **步骤 1：更新所有 SELECT 列**

在 `findSendableForChannelAccount`、`findForSend`、`findForDisplay`、`findForUpdate` 和 `upsert` 相关 SQL 中加入六个权限状态字段。`findSendableForChannelAccount` 与 `findForSend` 继续只过滤 `allow_send=true`，不改发送门禁语义。

- [ ] **步骤 2：增加候选查询**

```java
@Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
        + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
        + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
        + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
        + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
        + "last_synced_at, created_at, updated_at, deleted_at, version "
        + "from message_templates where deleted_at is null "
        + "and upper(status) = 'APPROVED' "
        + "and desired_allow_send <> allow_send "
        + "and (permission_sync_next_attempt_at is null "
        + "or permission_sync_next_attempt_at <= #{now}) "
        + "order by updated_at, id limit #{limit}")
List<TemplateEntity> findPermissionReconciliationCandidates(@Param("now") Instant now,
                                                            @Param("limit") int limit);
```

增加带版本条件的 `markPermissionPending`、`markPermissionSucceeded`、`markPermissionFailed`，避免两个 worker 覆盖彼此状态。

- [ ] **步骤 3：运行 Mapper 测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=TemplatePermissionMapperSqlTest test
```

### 任务 4：修改用户启停 owner

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`

- [ ] **步骤 1：在 `setSendPermission` 开始时持久化期望状态**

锁定模板后设置 `desiredAllowSend(allowSend)`、`permissionSyncStatus("PENDING")`、失败计数为 0、清空错误和下次重试时间，再调用 gateway。开启请求先校验 `status=APPROVED`；关闭请求不改变审核状态。

- [ ] **步骤 2：成功/失败分别写实际状态**

成功时同时更新 `allowSend=result.allowSend()`、`permissionSyncStatus("IDLE")`，并清空错误字段。明确 provider 失败时保留 `allowSend` 原值，写 `FAILED` 和错误；未知结果沿用现有 operation reconciliation，不把实际状态改成目标值。

- [ ] **步骤 3：运行现有模板服务测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplateApplicationServiceTest test
```

除新增断言外，既有“provider 写结果覆盖滞后详情”测试必须继续通过。

### 任务 5：实现自动权限对账 worker

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationService.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplatePermissionReconciliationServiceTest.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationScheduler.java`

**接口：**

```java
@Transactional
public ReconciliationResult reconcileDueTemplates(String workerId)
```

- [ ] **步骤 1：实现候选选择和单轮上限**

使用 `Instant.now(clock)` 调 Mapper，limit 固定为 20。对每个模板生成幂等键
`auto-permission:{templateId}:{desiredAllowSend}:{version}`，重复键直接跳过。

- [ ] **步骤 2：实现退避**

失败次数到下次间隔映射为 `[1m, 5m, 15m, 1h, 6h, 24h]`，超过第六档保持 24 小时。错误信息清理 provider 凭据和完整请求体。

- [ ] **步骤 3：接入现有 scheduler**

现有 `reconcileUnknown(workerId)` 完成后调用 `reconcileDueTemplates(workerId)`；保留 `@ConditionalOnProperty` 和现有 5 分钟调度开关。`ChatAppTemplateSyncService` 完成只读模板同步后也调用一次，以便首次启动立即处理。

- [ ] **步骤 4：运行 worker 测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplatePermissionReconciliationServiceTest,ChatAppWorkerSchedulingTest test
```

覆盖 APPROVED 自动开启、REJECTED 跳过、成功清零、失败退避、20 条上限和重复 worker 幂等。

### 任务 6：扩展 DTO 与前端模板状态投影

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateAdminResponse.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateResponse.java`
- 修改：`demo/message-center-spring/frontend/src/api/types.ts`
- 修改：`demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/components/templates/templateUi.ts`
- 修改：`demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`

- [ ] **步骤 1：扩展后端 `TemplateView` 和响应**

增加 `desiredAllowSend`、`permissionSyncStatus`、`permissionSyncError`，保持 `allowSend` 代表实际 CAMS 状态。模板选择器继续使用既有 `allowSend` 过滤。

- [ ] **步骤 2：扩展前端类型和状态显示**

开关绑定 `desiredAllowSend`；`PENDING` 禁用开关并显示同步状态；`FAILED` 显示结构化失败提示；`status !== APPROVED` 禁用开启。实际 `allowSend=false` 时不把模板放入发送选择器。

- [ ] **步骤 3：运行前端测试和构建**

```bash
cd demo/message-center-spring/frontend
npm test -- --runInBand
npm run build
```

验证正常、同步中、失败、审核未通过四种状态，且不产生新的包体积告警之外的类型错误。

### 任务 7：真实数据库和 CAMS 验收

**文件：**
- 修改：`docs/superpowers/specs/2026-08-15-whatsapp-template-default-enable-design.md`

- [ ] **步骤 1：执行 Flyway 迁移并记录模板快照**

启动本地 Postgres，迁移前后查询模板数量、审核状态、`allow_send` 和新增权限字段；迁移不得改变 CAMS 实际快照。

- [ ] **步骤 2：启动 ChatApp 同步并观察自动启用**

执行一次模板同步，确认两个 APPROVED 模板产生一次 CAMS `setSendPermission(true)`；确认 REJECTED 模板无 provider 写调用。

- [ ] **步骤 3：验证手动关闭持久化**

关闭一个 APPROVED 模板，重启后再次同步，确认不产生自动开启调用，`desired_allow_send=false` 保持不变。

- [ ] **步骤 4：运行完整后端回归**

```bash
cd demo/message-center-spring/backend
mvn test
```

Docker/Testcontainers 不可用时报告具体环境失败，不能把单元测试结果冒充完整回归通过。

- [ ] **步骤 5：同步中文文档和 Git 边界复核**

只 stage 本任务触达的迁移、Java、前端、测试和文档；不得使用 `git add .`，不得回滚其他线程改动。
