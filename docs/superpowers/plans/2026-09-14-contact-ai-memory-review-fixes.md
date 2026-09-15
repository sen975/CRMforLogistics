# 联系人 AI 记忆系统审查问题修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Task 1–9 审查发现的租约、游标、触发可靠性、观察晋升、审计和权限数据一致性问题，使联系人 AI 记忆链路达到可合并状态。

**Architecture:** `contactmemory` 继续作为联系人 AI 记忆唯一 owner。消息入库使用稳定的 `received_at + id` 增量边界，处理状态使用每次 claim 独立的 fencing token；记忆结果、成功游标、画像指针和成功审计在同一事务中提交。入站触发使用与消息入库同事务的 durable event，并由记忆 scheduler 先重放 event 再领取状态，避免 best-effort 日志造成永久漏处理。

**Tech Stack:** Java 17、Spring Boot 3.4.5、MyBatis-Plus 3.5.10、PostgreSQL、Flyway、JUnit 5/Mockito、Testcontainers、React 18、TypeScript、Vitest。

## Global Constraints

- `owner_user_id` 始终来自 `contacts.created_by`，不得使用联系人查询权限中的管理员/分配关系替代 owner。
- 人工标签继续使用原有表、查询和写入路径；AI 只读人工标签，禁止修改、删除、失效或恢复人工标签。
- 不引入基于 `occurred_at` 的成功游标；业务发生时间只用于内容筛选和展示排序。
- 任何过期或失去 fencing token 的 worker 都不能提交记忆结果、画像、审计或游标。
- 入站消息事件必须可重放、幂等、有状态；不能只记录日志。
- 画像正文最多 200 个 code point；AI 标签存储不设产品总量上限，单次 LLM 上下文和单次 API 返回使用资源预算并提供分页。
- LLM 失败、证据失败、持久化失败不得推进成功游标，不得替换上一版画像或标签。
- 每个 Task 只修改声明文件，专项测试通过后精确提交；禁止 `git add .`、禁止回滚或吸收 WhatsApp/Topic 用户 WIP。

---

## 文件与模块地图

### 状态、游标和可靠触发

- `demo/message-center-spring/backend/src/main/resources/db/migration/V71__contact_memory_processing_fencing.sql`：fencing token 和观察语义唯一性。
- `demo/message-center-spring/backend/src/main/resources/db/migration/V72__contact_memory_trigger_events.sql`：可靠入站触发 event。
- `demo/message-center-spring/backend/src/main/resources/db/migration/V73__contact_memory_evidence_ownership.sql`：evidence 复合约束。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryStateEntity.java`：新增当前 claim token。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryTriggerEventEntity.java`：可靠入站触发事件。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`：claim、complete、fail 的 token 条件。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryTriggerEventMapper.java`：event 入队、领取、完成和重试。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java`：入站 event 入队。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java`：先重放 event，再处理 runnable state。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`：携带独立 token 的处理编排。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`：`received_at + id` 游标和 cutoff。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`：新游标、lease token、attempt 运行上下文。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`：稳定游标查询和历史观察证据查询。

### 观察、事实和审计

- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`：跨轮观察合并、晋升和标签短词组校验。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`：观察生命周期、画像指针、真实统计和原子审计。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptService.java`：每次 claim 的开始、成功、失败审计。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryAttemptEntity.java`：审计字段映射。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java`：人工标签只读上下文、输入错误码和响应字节上限。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/ContactMemoryConfig.java`：观察 TTL 和上下文预算的唯一配置来源。

### 查询与测试

- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`：失败状态语义和 AI 标签分页。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java`：分页字段和独立 retryable 状态。
- `demo/message-center-spring/frontend/src/api/types.ts`：同步分页和失败状态类型。
- `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`：保留人工标签，展示 AI 标签分页状态。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryStateMapperSqlTest.java`：token fencing SQL 合同。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerServiceTest.java`：event 入队和失败重放合同。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java`：迟到消息、cutoff 和稳定游标。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java`：跨轮观察晋升和标签短词组。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java`：生命周期、画像指针和真实统计。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptServiceTest.java`：成功、失败和跳过审计。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConcurrencyTest.java`：真实 PostgreSQL stale worker 和 event 重放。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryEndToEndTest.java`：完整链路和跨轮证据。
- `docs/superpowers/reviews/2026-09-14-contact-ai-memory-review-fixes-verification.md`：修复验收记录。

---

### Task 1: 加入 lease fencing 并修复稳定增量游标

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V71__contact_memory_processing_fencing.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryStateEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryStateMapperSqlTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java`

**Interfaces:**
- `ContactMemoryStateMapper.claim(UUID id, String leaseOwner, Instant leaseUntil): Optional<UUID>` 返回本次 claim 独立生成的 `lease_token`。
- `ContactMemoryStateMapper.complete(UUID id, UUID leaseToken, String cursor, UUID profileId, Instant processedAt): int`。
- `ContactMemoryStateMapper.fail(UUID id, UUID leaseToken, String code, String message, int retryCount, Instant retryAt, boolean terminal): int`。
- `ContactMemoryMapper.listInboundMessagesByCursor(UUID ownerUserId, UUID contactId, Instant afterReceivedAt, UUID afterMessageId, Instant cutoff, int limit): List<MessageEntity>`。
- 游标编码为 `received_at` 与 `message_id`，不再编码 `occurred_at`。

- [x] **Step 1: 添加失败测试。**

```java
@Test
void expiredLeaseCannotCompleteEvenBeforeAnotherWorkerClaims() { }

@Test
void sameWorkerCannotReuseAnOldLeaseToken() { }

@Test
void lateInsertedMessageIsReadByReceivedAtCursor() { }
```

- [ ] **Step 2: 运行专项测试确认失败。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryStateMapperSqlTest,ContactMemoryContextServiceTest,ContactMemoryWorkerTest' test`

Expected: `FAIL`，原因是当前没有 `lease_token`，且 SQL 仍使用 `occurred_at` 游标。

- [ ] **Step 3: 实现 migration 和 token fencing。**

Migration 必须增加 `contact_memory_states.lease_token uuid`，并增加 observations 的联系人 owner 语义唯一约束；`claim` 使用 `gen_random_uuid()` 写入并返回 token。`complete` 和 `fail` 必须同时满足：

```sql
where id = #{id}::uuid
  and lease_token = #{leaseToken}::uuid
  and status = 'PROCESSING'
  and lease_expires_at > now()
```

`complete` 只能在本次事务内更新成功游标；如果返回 `0`，抛出 `LEASE_LOST` 使所有前置写入回滚。

- [ ] **Step 4: 将消息查询和 cursorAfterFetched 改为 `received_at + id`。**

查询条件必须使用：

```sql
and (
    #{afterReceivedAt}::timestamptz is null
    or m.received_at > #{afterReceivedAt}
    or (m.received_at = #{afterReceivedAt} and m.id > #{afterMessageId}::uuid)
)
and m.received_at <= #{cutoff}
and m.direction = 'inbound'
```

`m.occurred_at <= cutoff` 不再作为入库边界；`occurred_at` 保留为内容时间字段。

- [ ] **Step 5: 运行专项测试和生产编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryStateMapperSqlTest,ContactMemoryContextServiceTest,ContactMemoryWorkerTest' test && mvn -DskipTests compile`

Expected: 专项测试通过，生产代码编译退出码为 `0`。

- [ ] **Step 6: 精确提交 Task 1。**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V71__contact_memory_processing_fencing.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryStateEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryStateMapperSqlTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java
git commit -m "fix: fence contact memory workers and stabilize cursor"
```

### Task 2: 建立可靠入站触发和可重放 event

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V72__contact_memory_trigger_events.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryTriggerEventEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryTriggerEventMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerEventMapperSqlTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerServiceTest.java`

**Interfaces:**
- `ContactMemoryTriggerService.markInboundPersisted(UUID contactId, UUID messageId, Long ingestSequence, Instant occurredAt, Instant receivedAt): void`。
- `ContactMemoryTriggerEventMapper.enqueue(...)` 在消息入库事务内幂等写入 event。
- `ContactMemoryTriggerEventMapper.claimDue(...)` 返回有限批次 event。
- `ContactMemoryTriggerEventMapper.markApplied(...)` 和 `markFailed(...)` 使用 event lease。

- [ ] **Step 1: 添加失败测试。**

```java
@Test
void inboundInsertCreatesOneReplayableEvent() { }

@Test
void duplicateProjectionDoesNotCreateDuplicateTriggerEvents() { }

@Test
void failedStateMarkingIsRecoveredBySchedulerReplay() { }
```

- [ ] **Step 2: 运行专项测试确认当前 best-effort 实现不满足。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryTriggerEventMapperSqlTest,ContactMemoryTriggerServiceTest' test`

Expected: `FAIL`，因为当前仅调用 `states.markDirty`，没有 durable event。

- [ ] **Step 3: 创建 durable trigger event 表。**

表至少包含：

```text
id, message_id, contact_id, owner_user_id, ingest_sequence,
occurred_at, received_at, status, attempt_count,
next_attempt_at, lease_token, last_failure_code,
last_failure_message, created_at, applied_at
```

`message_id` 唯一；event 状态限制为 `PENDING/PROCESSING/APPLIED/FAILED`。event 与 message、contact、owner 使用数据库外键或复合外键保持一致。

- [ ] **Step 4: 将三个入站 projector 的 event 入队放入消息入库事务。**

成功插入消息后，使用消息真实的 `id`、`ingest_sequence`、`received_at` 入队。同步 `markDirty` 可以保留为快速路径，但不能作为唯一来源；触发异常必须让当前事务回滚或留下可重放 event，不得只 `log.warn` 后丢弃。

- [ ] **Step 5: scheduler 在领取记忆 state 前重放 due event。**

每轮先以有限 batch claim event，按 owner/contact 调用 `states.markDirty`，成功后标记 `APPLIED`；失败记录错误并按指数退避，达到上限后保留 `FAILED` 供诊断和人工重试。event replay 必须幂等，不调用 LLM。

- [ ] **Step 6: 运行专项测试和编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryTriggerEventMapperSqlTest,ContactMemoryTriggerServiceTest,ContactMemorySchedulerTest' test && mvn -DskipTests compile`

Expected: 专项测试通过，生产代码编译退出码为 `0`。

- [ ] **Step 7: 精确提交 Task 2。**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V71__contact_memory_processing_fencing.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryTriggerEventEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryTriggerEventMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerEventMapperSqlTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerServiceTest.java
git commit -m "fix: make contact memory triggers replayable"
```

### Task 3: 完成观察生命周期和跨轮事实晋升

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryObservationEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapperSqlTest.java`

**Interfaces:**
- `Context` 携带未过期 observation 及其去重后的 evidence count。
- `ConsolidationResult` 携带真实 `inputMessageCount`、`evidenceCount` 和 profile 输入来源。
- `ContactMemoryMapper.upsertObservation(...)` 返回存储 observation id。
- `ContactMemoryMapper.updateObservationLifecycle(...)` 按 owner/contact/semantic key 更新状态和 `promoted_fact_id`。

- [x] **Step 1: 添加真正跨轮的失败测试。**

```java
@Test
void oneEvidenceInFirstRunAndIndependentEvidenceInSecondRunPromoteFact() { }

@Test
void promotedObservationPointsToTheCreatedFact() { }

@Test
void expiredObservationIsMarkedExpiredAndCannotPromoteFact() { }
```

第一轮只能返回一条 evidence 并持久化；第二轮只返回另一条 evidence，测试必须通过真实 mapper 或明确的历史 context 验证跨轮累计，不能把两条 evidence 放进同一次 output。

- [x] **Step 2: 运行专项测试确认当前实现失败。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryConsolidationServiceTest,ContactMemoryMutationServiceTest' test`

Expected: 至少跨轮晋升、observation 状态和 `promoted_fact_id` 测试失败。

- [x] **Step 3: 合并当前 output 与历史未过期 observations。**

按 `category + normalized_key + normalized_value + polarity` 形成语义键；历史 observation 的 evidence count 与当前独立 evidence 合并，重复 evidence 按 `(type,id)` 去重。达到至少两个独立证据才产生 ACTIVE fact，否则保留 CANDIDATE。

- [x] **Step 4: 在同一事务内闭合 observation 状态。**

规则固定为：

- 新观察未达到晋升门槛：`CANDIDATE`。
- 新观察晋升事实成功：`PROMOTED` 且写入 `promoted_fact_id`。
- 同语义重复观察被合并：旧行 `MERGED`，保留主 observation。
- 超过 `expires_at`：`EXPIRED`，不得参与晋升。
- 明确冲突但未达到新事实门槛：保持旧事实，观察不直接删除。

事实 evidence 写入成功后再更新 observation，任一步失败都由事务回滚。

- [x] **Step 5: 让 `observationTtlDays` 成为唯一 TTL 来源。**

删除 `OBSERVATION_TTL_DAYS = 30`；由 `ContactMemoryConfig.observationTtlDays()` 计算 `expires_at`，并在 context 查询和 mutation 过期清理中使用同一配置。

- [x] **Step 6: 运行专项测试和编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryConsolidationServiceTest,ContactMemoryMutationServiceTest' test && mvn -DskipTests compile`

Expected: 专项测试通过，生产代码编译退出码为 `0`。

实际验证：`mvn -DskipTests compile` 通过；因 3 个既有 WIP 测试缺少 `isNull`/`Instant` 导入，Maven 全量 testCompile 被阻断；通过直接编译目标测试类后运行 `mvn surefire:test`，Task 3 的 3 个测试类共 19 项通过。

- [x] **Step 7: 精确提交 Task 3。**

提交：`2b4f164 fix: close contact memory observation lifecycle`

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java
git commit -m "fix: close contact memory observation lifecycle"
```

### Task 4: 补齐人工标签上下文、短标签约束和 LLM 输入输出边界

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGatewayTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java`

**Interfaces:**
- `Context.manualTags()` 返回人工标签只读副本。
- LLM payload 使用 `manualTags` 字段，系统提示明确该字段不可修改。
- 输入超限错误码为 `INPUT_LIMIT`，provider 响应超限错误码为 `OUTPUT_LIMIT`。

- [ ] **Step 1: 添加失败测试。**

```java
@Test
void llmPayloadContainsManualTagsAsReadOnlyContext() { }

@Test
void rejectsSentenceLikeAiLabelName() { }

@Test
void rejectsProviderResponseExceedingByteBudget() { }

@Test
void reportsInputLimitInsteadOfOutputLimitForOversizedContext() { }
```

- [x] **Step 2: 运行 gateway/context 专项测试确认失败。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryContextServiceTest,ContactMemoryLlmGatewayTest,ContactMemoryConsolidationServiceTest' test`

Expected: 当前 payload 缺少 `manualTags`，且标签只检查 100 字符。

- [x] **Step 3: 加入人工标签只读上下文。**

ContextService 通过现有人工标签 mapper 按 owner/contact 查询，转成 contactmemory 自己的不可变 record；gateway 只序列化，不接受模型返回的人工标签操作字段。

- [x] **Step 4: 增加服务端标签短词组校验。**

标签名称限制为最多 32 个 code point，不允许换行、句末标点、连续空白和明显句式；颜色仍由服务端 category 映射，模型不能返回颜色。

- [x] **Step 5: 限制 provider response body。**

读取 response body 时先按 `maxResponseBytes` 预算，超过预算立即抛出结构化 `OUTPUT_LIMIT`；context JSON 超过请求预算抛出 `INPUT_LIMIT`。不使用无界 `readAllBytes()`。

- [x] **Step 6: 运行测试和编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryContextServiceTest,ContactMemoryLlmGatewayTest,ContactMemoryConsolidationServiceTest' test && mvn -DskipTests compile`

Expected: 专项测试通过，生产代码编译退出码为 `0`。

实际验证：`ContactMemoryContextServiceTest`、`ContactMemoryLlmGatewayTest`、`ContactMemoryConsolidationServiceTest` 共 26 项通过；`mvn -DskipTests compile` 通过。全量 Maven testCompile 仍被用户既有 WIP 测试的缺少 import 阻断，未修改这些非 Task 4 文件。`INPUT_LIMIT` 已纳入 Worker 终止校验，避免上下文超限无意义重试。

- [x] **Step 7: 精确提交 Task 4。**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGatewayTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java
git commit -m "fix: bound contact memory llm context and labels"
```

### Task 5: 完成 attempt 审计和画像状态指针

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java`

**Interfaces:**
- `ContactMemoryAttemptService.start(...)` 返回 `AttemptRun(attemptId, generationBatchId, startedAt)`。
- `ContactMemoryAttemptService.fail(...)` 持久化 `FAILED`，包含 code、message、retryCount、duration 和 input cursor。
- `ContactMemoryMutationService.persist(..., AttemptRun attempt)` 成功时更新同一 attempt 为 `SUCCEEDED`。
- profile 插入使用 context/result 真实消息数和证据数。
- `complete` 接受 nullable `profileId`，只有新画像存在时才更新 `current_profile_version_id`。

- [ ] **Step 1: 添加失败测试。**

```java
@Test
void llmFailureCreatesFailedAttemptWithDurationAndCursor() { }

@Test
void successfulMutationStoresRealInputAndEvidenceCounts() { }

@Test
void successfulProfileWriteUpdatesCurrentProfilePointer() { }
```

- [ ] **Step 2: 运行专项测试确认当前失败路径没有 attempt。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryAttemptServiceTest,ContactMemoryMutationServiceTest,ContactMemoryWorkerTest' test`

Expected: LLM 失败后找不到 `FAILED` attempt，画像统计和 state 指针仍为 `0/null`。

- [ ] **Step 3: 在每次 claim 后创建 attempt。**

attempt 的创建时间在 gateway 调用前；context 读取后回填 input count；成功、跳过和失败都必须有终态。旧 worker 因 token 丢失时，失败审计只能记录为 `FAILED/LEASE_LOST`，不能覆盖新 worker 的 state。

- [ ] **Step 4: 将 success attempt 与记忆结果放进同一事务。**

事务顺序为：校验 token → upsert observations/evidence → facts/evidence → labels/evidence → profile → 更新 attempt 成功统计 → 更新 `current_profile_version_id` → `complete` 推进 cursor。任一步失败整体回滚。

- [ ] **Step 5: 将异常路径写入 FAILED attempt。**

gateway、validation、persistence、lease loss 都写结构化 code；失败 attempt 不得伪造 output cursor，不得把失败计为成功。

- [ ] **Step 6: 运行测试和编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryAttemptServiceTest,ContactMemoryMutationServiceTest,ContactMemoryWorkerTest' test && mvn -DskipTests compile`

Expected: 专项测试通过，生产代码编译退出码为 `0`。

- [ ] **Step 7: 精确提交 Task 5。**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java
git commit -m "fix: audit contact memory attempts and profile pointers"
```

### Task 6: 加固 evidence 归属约束和 AI 标签分页

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V73__contact_memory_evidence_ownership.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryEvidenceConstraintTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java`
- Modify: `demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts`

**Interfaces:**
- evidence parent 与 evidence 行使用 `(id, contact_id, owner_user_id)` 复合外键。
- AI 标签查询支持 `limit` 和 opaque `cursor`，返回 `hasMore`。
- 记忆上下文仍有 `maxLabels` 预算，但这只是单次 LLM 输入预算，不是标签总量上限。

- [ ] **Step 1: 添加跨 owner evidence 和分页失败测试。**

```java
@Test
void evidenceCannotReferenceFactFromAnotherContactOrOwner() { }

@Test
void aiLabelsArePagedWithoutChangingUnlimitedStorageSemantics() { }
```

- [ ] **Step 2: 运行专项测试确认当前 schema/query 不满足。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryEvidenceConstraintTest,ContactMemoryControllerTest' test`

Expected: 当前数据库允许冗余 owner/contact 与父记录不一致，查询只有固定前 100 条。

- [ ] **Step 3: 增加复合键和外键。**

父表增加对应复合唯一键；observation/fact/label evidence 使用复合外键同时校验 parent id、contact id 和 owner id。`contact_ai_label_evidence` 还必须同时校验 label 与 fact 的归属。

- [ ] **Step 4: 将标签查询改为显式分页。**

默认页大小保留资源上限，但不再把 `100` 表达为产品总量限制；前端只读展示第一页并可继续加载，人工标签接口不变。

- [ ] **Step 5: 运行后端/前端专项测试和构建。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryEvidenceConstraintTest,ContactMemoryControllerTest' test && cd ../frontend && npm test && npm run build`

Expected: 后端专项测试、前端测试和构建通过。

- [ ] **Step 6: 精确提交 Task 6。**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V71__contact_memory_processing_fencing.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryEvidenceConstraintTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java \
  demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts
git commit -m "fix: enforce memory evidence ownership and label paging"
```

### Task 7: 真实 PostgreSQL 并发、跨轮和全量验收

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConcurrencyTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryEndToEndTest.java`
- Create: `docs/superpowers/reviews/2026-09-14-contact-ai-memory-review-fixes-verification.md`
- Modify: `docs/superpowers/README.md`

**Interfaces:**
- 验收必须覆盖真实 PostgreSQL/Testcontainers，而不是只通过 Mockito。
- 必须保留当前 WhatsApp WIP 的编译阻断记录，不修复或 stage 用户文件。

- [ ] **Step 1: 添加真实数据库场景。**

至少覆盖：

```java
@Test
void staleWorkerCannotCommitAfterLeaseExpiry() { }

@Test
void firstRunObservationAndSecondRunEvidencePromoteFact() { }

@Test
void triggerEventReplayMarksStateDirtyAfterTransientFailure() { }

@Test
void llmFailureLeavesCursorProfileFactsAndLabelsUntouched() { }
```

- [ ] **Step 2: 运行记忆模块专项测试。**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest='ContactMemory*Test' test
```

Expected: 在 Docker 可用时全部通过；Docker 不可用时必须明确记录为环境阻断，不得写成通过。

- [ ] **Step 3: 运行生产编译、前端测试和构建。**

Run:

```bash
cd demo/message-center-spring/backend
mvn -DskipTests compile
cd ../frontend
npm test
npm run build
```

Expected: 后端编译、前端测试和生产构建退出码为 `0`。

- [ ] **Step 4: 运行 diff、工作区边界和全量审计。**

Run:

```bash
git diff --check 9017460..HEAD
git status --short
git diff --cached --stat
git diff --name-only 9017460..HEAD
```

Expected: 无 whitespace error、无 staged 未授权文件；报告中分别列出记忆修复提交和未触碰的 WhatsApp WIP。

- [ ] **Step 5: 更新验收记录。**

文档必须列出每条审查意见的关闭证据、实际命令、退出码、Docker/LLM/浏览器未执行项和剩余非阻断债务。不能只写“测试已完成”。

- [ ] **Step 6: 只有所有 Critical 和 Important 关闭后，才允许标记 Ready to merge。**

若 Docker 或用户 WIP 仍阻断全量测试，状态保持 `Not ready to merge`，并明确阻断原因和替代证据。

---

## 方案取舍

### 为什么选择 `received_at + id`

`ingest_sequence` 只在单个 conversation 内递增，而联系人可能跨多个消息渠道和多个 conversation；它不能直接作为联系人级单游标。`received_at + id` 是跨渠道可比较、在消息落库时生成的稳定顺序边界，能覆盖“业务发生时间较早但延迟入库”的消息。

### 为什么选择 durable trigger event

单纯把 `states.markDirty` 放在 projector 后面并捕获异常，会出现“消息已提交、记忆状态未标脏、没有任何任务可重放”的永久漏处理。event 与消息入库同事务，scheduler 重放 event，可以同时保留消息链路的可靠性和记忆处理的异步边界。

### 为什么不把人工标签复制进 AI 标签表

人工标签是用户维护的数据，不是模型推断结果。只读复制到 LLM context 可以帮助画像和 AI 标签生成，但不能改变 owner、存储表或写入权限，避免模型输出覆盖人工事实。

### 为什么不取消单次上下文上限

“标签总量不限”与“单次 LLM 输入有界”是两个不同合同。数据库和产品查询不设置总量上限；LLM context 继续限制单次读取量，API 使用分页，避免无界请求和内存增长。
