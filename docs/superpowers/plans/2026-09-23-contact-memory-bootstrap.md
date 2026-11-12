# 联系人 AI 记忆首次历史回填实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` and implement inline task-by-task. Each Task is a separate review/verification boundary. Do not delegate overlapping work.

**Goal:** 对尚无有效 AI 画像的联系人执行有界、可观测的一次性历史回填（包括已有成功游标但未形成画像者），验证每条消息页完整处理后才推进回填游标，并继续使用现有增量记忆流程。

**Architecture:** `contactmemory` 仍是唯一业务 owner。新增回填状态、独立成功回填游标和固定 target tuple；worker 用 keyset pagination 分批回填，常规增量只在回填完成后消费 target 之后的新消息。复用 `contact_memory_trigger_events` 作为消息级 inbox，增加与 trigger `status/applied_at` 分离的 `memory_applied_at`；只将成功批次实际输入的消息写入记忆回执。连续 tuple page 与游标之前的迟到 inbox 分开选择，迟到消息只占用连续页剩余预算且不改变 tuple 输出游标，避免越过未读历史。有效记忆或明确 `NO_SIGNAL` 与审计、回执和游标在同一事务提交；失败不推进游标、不写回执。既有 `last_success_cursor` 保持不变，回填完成时只向前合并 target。数据库查询、LLM adapter、API/UI 只做各自边界内的合同映射。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis、PostgreSQL/Flyway、JUnit 5/Mockito、Testcontainers、React/TypeScript。

## Global Constraints

- 新数据库变更暂定使用 `V99__contact_memory_history_backfill.sql`；当前工作区已有用户未提交的 V97、V98 迁移，不得覆盖、改名或吸收它们。开始 Task 1 前必须重新审计迁移目录及并行变更；若版本已被占用，选当时下一个空闲版本并同步测试/文档。不得修改任何已有迁移。
- 联系人 owner 始终使用 `contacts.created_by`；所有状态、消息和证据写入同时限定 owner 与 contact。
- 回填和增量不可并发推进同一个联系人的游标；继续使用租约 token/fencing。
- 默认每联系人每轮最多 50 条消息、50,000 字符；回填每轮最多领取 5 个联系人、并发 1；超限必须结构化失败，不能静默跳过。
- 新的自动历史回填开关默认关闭；不得在迁移、部署或普通启动时自动发起历史 LLM 请求。统计待处理联系人/页数并估算费用后，须由用户/部署负责人明确授权启用；每个活跃实例单独计算配额和并发。
- `MEMORY_UPDATED` 与 `NO_SIGNAL` 可推进回填专用游标；`FAILED` 不得推进游标或覆盖既有记忆。
- 不保存新的原始消息副本；无信号审计只保存游标边界、条数和结果类型。
- 当前本机数据库已知存在 V84 missing migration 和 V89 checksum drift；不得执行 `flyway repair` 或在该库上应用新迁移，除非迁移历史另行完成只读对账和用户批准的恢复。
- 每 Task 只运行专项测试与编译；最后 Task 再跑整合验收。每个 Task 只 stage 明确列出的文件，不使用 `git add .`。

---

## 文件地图

| 文件 | 职责 |
|---|---|
| `demo/message-center-spring/backend/src/main/resources/db/migration/V99__contact_memory_history_backfill.sql` | 回填状态、target tuple、attempt outcome、trigger-event memory receipt 及 pending inbox 索引；为既有状态作无副作用初始化（版本号须在实施前复核） |
| `ContactMemoryStateEntity.java` / `ContactMemoryAttemptEntity.java` | 持久化状态字段与结果类别 |
| `ContactMemoryStateMapper.java` / `ContactMemoryMapper.java` / `ContactMemoryTriggerEventMapper.java` | target 固定、tuple 分页、pending inbox 合并、补漏和并发安全领取 |
| `ContactMemoryModels.java` / `OpenAiCompatibleContactMemoryGateway.java` / `ContactMemoryConsolidationService.java` | 共享 tuple、批次结果合同、无信号证据和严格输入/输出验证 |
| `ContactMemoryAttemptService.java` / `ContactMemoryMutationService.java` / `ContactMemoryWorker.java` | 原子保存、失败不推进、回填推进和重试 |
| `ContactMemoryScheduler.java` / `ContactMemoryConfig.java` / `application.yml` | 有界回填轮询与配置 |
| `ContactMemoryQueryService.java` / `ContactMemoryResponse.java` / `ContactMemoryController.java` | 返回回填状态 |
| 后端与 mapper 测试文件 | 每个 Task 的 Files 清单逐项列出；覆盖分页、游标、结果原子性、owner/fencing 和真实 PostgreSQL 行为，不使用文件名通配符 |
| `frontend/src/components/ContactDetailPanel.tsx` 与同目录测试 | 呈现回填进度、失败和完成 |
| `docs/superpowers/specs/2026-09-23-contact-memory-bootstrap-design.md` | 当前变更设计合同 |
| `docs/superpowers/README.md` | 设计/计划真源索引；文件已有用户未提交改动，只允许在当前设计与实施索引分组各追加本任务链接，不整理或改动其他内容 |

### Gate 0: 验证迟到入库消息不会漏处理

原 Gate 0 测试已在 PostgreSQL Testcontainers 证实失败：迟到旧消息触发状态重置，但 worker 仍按 `last_success_cursor` 过滤掉它。修订合同复用 `contact_memory_trigger_events` 作为消息级 durable inbox，在 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryEndToEndTest.java` 保留回归测试：建立成功游标后插入 `received_at` 小于 cursor 的入站消息并触发事件；修复后必须断言消息进入模型输入，成功事务只对实际输入写 `memory_applied_at`，失败事务不写，重放不重复消费。另加历史页完整性断言：连续历史页中有未处理消息时，即使存在更晚的未回执 inbox 消息，也不能推进游标越过该历史页边界；迟到消息只占剩余 batch 预算，且只有源消息在模型输入后才回执。

该测试的旧实现红灯已确认，修订方案新增数据库回执合同，因此本计划在用户确认该修订前暂停 Task 1–6。确认后以测试先行实现 inbox 合并与原子回执；不得把该失败记为非阻断债务，也不得继续声称“历史完整回填”已经闭合。

---

### Task 1: 增加回填状态与 attempt 结果迁移

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V99__contact_memory_history_backfill.sql` (暂定；按 Global Constraints 在实施前复核版本)
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryStateEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryAttemptEntity.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryBackfillMigrationTest.java`

**Contract:**
- `history_backfill_status` 允许 `NOT_STARTED / IN_PROGRESS / COMPLETE`。
- `history_backfill_cursor` 和 `history_backfill_target_cursor` 保存 nullable `<Instant>|<UUID>` 字符串；回填游标独立于普通 `last_success_cursor`。
- `contact_memory_trigger_events.memory_applied_at` 与既有触发 `status/applied_at` 分离；它是单条入站消息已成功纳入记忆的 durable receipt。
- 为 owner/contact scoped 的 `memory_applied_at IS NULL` 迟到补漏查询增加 partial index；inbox 查询依据关联 `messages` 的 tuple，而非假设 event 的快照时间永远等于当前消息时间。
- 有效当前画像的既有状态行迁移为 `COMPLETE`；无有效当前画像的状态行迁移为 `NOT_STARTED`，即使 `last_success_cursor` 非空。
- 迁移不得重置或改写 `last_success_cursor`；既有回填专用游标初始化为 NULL。
- 新联系人状态由记忆 trigger/mapper 显式插入 `NOT_STARTED`，表级 default 使用 `COMPLETE`，以免非记忆写入意外创建回填任务；同一事务中的 stale-state 补漏插入也必须显式使用 `NOT_STARTED`。
- `contact_memory_attempts.outcome` nullable，仅新 attempt 写入 `MEMORY_UPDATED / NO_SIGNAL / FAILED / SKIPPED`；旧 attempt 保持 NULL，避免伪造历史结果。原 `status` 合同仍为 `SUCCEEDED / FAILED / SKIPPED`，不得写入消息正文。

- [ ] **Step 1: 写失败迁移合同测试。** 使用随机 schema 创建 V70–V76 所需最小历史结构，执行暂定 V99 后断言 backfill 状态、独立回填游标、target/outcome 列、event receipt/index 和 check constraint；已有有效画像的状态设为 `COMPLETE`，无画像状态（包括 `last_success_cursor` 非空者）设为 `NOT_STARTED`；所有既有成功游标原值保留，旧 attempt 的 outcome 保持 NULL；对已有画像联系人，只按 `messages.received_at, messages.id <= last_success_cursor tuple` 将旧 event 的 `memory_applied_at` 初始化为迁移时 `now()`，游标之后的 event 保持 pending；迁移绝不改写 `status/applied_at` 或消息内容。
- [ ] **Step 2: 先运行单测并确认失败。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryBackfillMigrationTest test`

Expected: 测试因新迁移文件/字段缺失失败；不得连接或修改默认本机数据库。

- [ ] **Step 3: 增加新迁移与实体字段。** 版本复核后暂定使用 V99；若已被占用，则使用当时下一个空闲版本并同步测试名及验收命令。用 `ALTER TABLE ... ADD COLUMN`、显式 `UPDATE` 和约束建立合同；只改新增迁移，不改既有迁移。
- [ ] **Step 4: 重跑测试与编译。**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryBackfillMigrationTest test && mvn -DskipTests compile`

Expected: PostgreSQL migration assertions pass and production compile succeeds。

- [ ] **Step 5: 精确检查迁移差异。** `git diff --check`；确认只含本 Task 新迁移、实体和测试，无运行本机 `flyway repair`。

### Task 2: 实现一致的 tuple target、分页和补漏

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryTriggerEventMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryTriggerEventEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapperSqlTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapperSqlTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java`

**Interfaces:**
- Add `ContactMemoryModels.CursorBoundary(Instant receivedAt, UUID messageId)` as the shared serialized cursor unit.
- `ContactMemoryStateMapper.beginHistoryBackfill(UUID stateId, UUID leaseToken): Optional<String>` only fixes target while the state is `NOT_STARTED` and the matching lease is valid.
- `ContactMemoryMapper.listInboundMessagesByCursor(...)` returns only the next contiguous page within lower-exclusive/upper-inclusive tuple bounds; for backfill, `after` comes from `history_backfill_cursor`, never `last_success_cursor`.
- Add a separate `listUnappliedInboundAtOrBeforeCursor(owner, contact, cursor, limit)` inbox query for `memory_applied_at IS NULL` rows at/before the active successful cursor. Append only within remaining message/character budget; never include rows after the active cursor in this supplement because the contiguous tuple query owns them.
- `ContactMemoryContextService` deduplicates IDs, preserves the tuple-page output cursor irrespective of appended late inbox rows, and returns the exact message IDs included in model input for atomic receipt writes.
- `ContactMemoryStateMapper.hasPendingInbound(owner, contact, cursor)` uses `EXISTS` for both tuple-new messages and unreceipted inbox rows at/before the cursor, including events whose trigger status is already `APPLIED`.

- [ ] **Step 1: Add failing tuple/inbox boundary tests.** Cover same `received_at` with UUIDs below/at/above cursor; assert ordered pages are disjoint and exhaustive. Cover an inbound row newer than cursor only by UUID; pending detection must return true. Cover an old unreceipted inbox row and an `APPLIED` trigger event; it must be selected despite trigger status. Cover a later inbox row while an earlier contiguous page has an unprocessed middle row; it must not enter the supplement or move page output cursor. Assert late inbox rows use only remaining count/character budget and repeated IDs are deduplicated.
- [ ] **Step 2: Run mapper/context focused tests and verify the tie-case fails against timestamp-only repair.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryMapperSqlTest,ContactMemoryStateMapperSqlTest,ContactMemoryContextServiceTest' test`

Expected: the same-timestamp pagination, late inbox receipt, or no-cursor-jump regression assertion fails before implementation.

- [ ] **Step 3: Implement one tuple comparator contract.** Parse cursor once into `CursorBoundary`; bind lower-exclusive and upper-inclusive tuples in SQL; implement late inbox lookup only at/before the active cursor and requeue as `EXISTS` over tuple-new messages or unreceipted inbox, not `MAX(received_at)`. Keep this supplement separate from the ordered keyset page.
- [ ] **Step 4: Make target initialization conditional on the current lease token.** Read the latest inbound tuple for the same owner/contact; atomically store it and transition `NOT_STARTED -> IN_PROGRESS`, or set `COMPLETE` if none exists. Preserve the existing incremental cursor.
- [ ] **Step 5: Run focused tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryMapperSqlTest,ContactMemoryStateMapperSqlTest,ContactMemoryContextServiceTest' test && mvn -DskipTests compile`

Expected: tuple equality/tie tests pass; owner predicates remain present in every query.

### Task 3: Define durable `NO_SIGNAL` and prevent accidental cursor skips

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGatewayTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryAttemptServiceTest.java`

**Contract:**
- Batch outcome is `MEMORY_UPDATED`, `NO_SIGNAL`, or `FAILED`; existing administrative `SKIPPED` remains distinct.
- A valid `NO_SIGNAL` requires the model to explicitly return no durable memory changes and valid `MESSAGE` evidence for the no-signal decision, bounded to the current batch IDs. A null profile alone is not sufficient.
- Every emitted evidence ID must be in the currently loaded owner-scoped context. Existing categories, confidence bounds, label limits, 200-character profile cap, and manual-tag read-only rule remain unchanged.
- Store only outcome, cursor boundaries, counts, and existing evidence IDs; never store raw message text in attempt audit.

- [ ] **Step 1: Add tests for the result contract.** Assert valid `NO_SIGNAL` with current batch evidence is accepted; empty output without a no-signal disposition is rejected as `UNACCOUNTED_INPUT`; fabricated or cross-contact message IDs are rejected; null profile plus valid label is `MEMORY_UPDATED`.
- [ ] **Step 2: Run focused gateway/consolidation tests and verify they fail.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryLlmGatewayTest,ContactMemoryConsolidationServiceTest,ContactMemoryAttemptServiceTest' test`

Expected: new outcome/evidence assertions fail before implementation.

- [ ] **Step 3: Extend the structured JSON contract and strict parser.** Add an explicit no-signal disposition with message evidence; reject unknown fields and invalid references using the existing structured gateway error contract.
- [ ] **Step 4: Calculate and persist the outcome.** `MEMORY_UPDATED` if any observation/fact/label/profile mutation is valid; `NO_SIGNAL` only for a complete explicit no-signal result; otherwise throw `UNACCOUNTED_INPUT` and leave cursor unchanged.
- [ ] **Step 5: Run focused tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryLlmGatewayTest,ContactMemoryConsolidationServiceTest,ContactMemoryAttemptServiceTest' test && mvn -DskipTests compile`

Expected: all outcome, prompt parser, confidence, evidence and output-bound tests pass.

### Task 4: Add atomic bootstrap worker and preserve ordinary incremental behavior

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapperSqlTest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryRetryService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryRetryServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/ContactMemoryConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemorySchedulerTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerServiceTest.java`

**Interfaces:**
- `ContactMemoryWorker.runBackfillOnce(Instant now, int contactLimit): int` claims only `NOT_STARTED/IN_PROGRESS` states and processes at most one page per contact.
- Add a dedicated fenced `claimBackfill` mapper operation: it must be able to claim eligible `CLEAN` states whose backfill status is `NOT_STARTED/IN_PROGRESS`, since migrated no-profile contacts may already have `CLEAN` processing state; it must reject `FAILED` until owner retry and exclude any live lease.
- `ContactMemoryWorker.runIncrementalOnce(Instant now): int` claims only `COMPLETE` history states eligible under the existing time window.
- New configuration: `bootstrap-enabled=false`; `bootstrap-contact-batch-size=5`, validated `1..50`; one active model request at a time per instance for the bootstrap worker. The scheduler must not claim bootstrap work when disabled.
- `ContactMemoryRetryService` owns retry authorization/state reset here; Task 5 only exposes its operation through the existing memory HTTP contract.

- [ ] **Step 1: Write failing worker/retry tests.** Cover disabled scheduler makes no backfill claim; dedicated claim can claim eligible migrated `CLEAN` rows; no-profile target capture both with null and non-null prior `last_success_cursor`; preservation of that prior cursor while pages commit; two-page history reaching target; message arriving after target; late inbox receipt without cursor movement; continuous page plus later inbox cannot skip a middle historical row; only model-input message IDs receive receipts; `NO_SIGNAL` atomic advancement; `FAILED` non-advancement and no receipt; lease loss rollback includes receipt rollback; trigger `APPLIED` does not mean memory receipt complete; mutual exclusion of backfill/increment claims; retry requires owner identity; retry preserves target plus both cursors.
- [ ] **Step 2: Run worker/scheduler tests and confirm the missing behavior.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryWorkerTest,ContactMemorySchedulerTest,ContactMemoryTriggerServiceTest' test`

Expected: bootstrap tests fail before implementation.

- [ ] **Step 3: Make each batch transactional.** Persist derived memory, attempt outcome, `memory_applied_at` for exactly the message IDs supplied to the model, `history_backfill_cursor`, and backfill state in one transaction. Advance `history_backfill_cursor` only to the last message of the fully consumed contiguous tuple page; supplemental late inbox IDs cannot move it. If a batch contains only late inbox, preserve both cursor values while committing its memory and receipts. Set `IN_PROGRESS` while the history cursor is below target; set `COMPLETE` only at/after target. On completion advance `last_success_cursor` to the tuple maximum of its old value and target; never clear or regress it. On failure or lease loss, roll back memory, attempt success, receipts, and cursor together; preserve the prior current profile.
- [ ] **Step 4: Schedule bounded backfill independently of the nightly gate.** Add a separate 600-second scheduled entry point outside the existing nightly-window guard, gated by `bootstrap-enabled`. Run at most 5 backfill contacts each tick, one page each, sequentially. Run regular incremental work only inside the existing configured start window and exclude incomplete backfill states.
- [ ] **Step 5: Keep new messages after the target pending.** Trigger writes during `IN_PROGRESS` update latest inbound time but cannot rewrite target; do not include post-cursor events as inbox supplements. After transition to `COMPLETE`, the exact tuple requeue query schedules the later messages.
- [ ] **Step 6: Run focused tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryWorkerTest,ContactMemorySchedulerTest,ContactMemoryTriggerServiceTest' test && mvn -DskipTests compile`

Expected: single-lease winner, tuple target, no-signal advancement, and failure rollback tests pass.

### Task 5: Expose backfill state through memory API and frontend

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx`

**Interfaces:**
- Add `historyBackfillStatus: NOT_STARTED | IN_PROGRESS | COMPLETE` to the existing memory response.
- Add owner-scoped `POST /api/contacts/{contactId}/memory/retry`; request body is empty and owner comes only from the authenticated principal.
- Do not expose raw cursor or source message body.
- `ContactMemoryRetryService` is created and unit-tested in Task 4; this Task wires it through `ContactMemoryController` and verifies HTTP authorization. Reuse the existing contact-detail memory projection and `fetchContactMemory` contract; do not create a parallel endpoint or frontend state model.

- [ ] **Step 1: Add API/UI tests.** Verify disabled `NOT_STARTED` renders as waiting/queued rather than complete and remains pending; `IN_PROGRESS` renders as history backfill in progress; `COMPLETE` is distinct from `CLEAN`; a terminal `FAILED` processing status retains its failure code and pending indicator.
- [ ] **Step 2: Run focused tests and verify they fail.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryControllerTest test`

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/ContactDetailPanel.test.tsx`

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/api/contact-memory-contract.test.ts`

Expected: response contract/type/UI assertion failures before implementation.

- [ ] **Step 3: Add the owner-scoped field and retry command; render translated state text and a retry action using existing component conventions.** Retry is enabled only for terminal failure, submits no owner/cursor, and refreshes the existing memory query after success. Do not add a second frontend state machine.
- [ ] **Step 4: Run focused API/UI tests and production build.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryControllerTest test`

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/ContactDetailPanel.test.tsx src/api/contact-memory-contract.test.ts && npm run build`

Expected: tests pass and build succeeds without warnings.

### Task 6: Integrate design docs and run final verification

**Files:**
- Create: `docs/superpowers/reviews/2026-09-23-contact-memory-bootstrap-verification.md`

- [ ] **Step 1: Run final isolated migration/worker integration tests on PostgreSQL Testcontainers.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryBackfillMigrationTest,ContactMemoryMapperSqlTest,ContactMemoryStateMapperSqlTest,ContactMemoryContextServiceTest,ContactMemoryWorkerTest,ContactMemorySchedulerTest,ContactMemoryEndToEndTest,ContactMemoryControllerTest,ContactMemoryRetryServiceTest' test`

Expected: failures `0`; report skipped test count explicitly. Do not use the drifted local database.

- [ ] **Step 2: Run full backend and frontend verification once.**

Run: `cd demo/message-center-spring/backend && mvn test`

Run: `cd demo/message-center-spring/frontend && npm test && npm run build`

Expected: report actual counts and any environment blockers; no unreported warning, skip or compile error.

- [ ] **Step 3: Verify migration chain and database boundary.** On a fresh isolated PostgreSQL schema, run Flyway validate/migrate through the selected new version (currently V99) and assert new columns/constraints. On the user's database, stop if V84/V89 validation drift is still unresolved; no repair, no schema mutation in this task.

- [ ] **Step 4: Write the verification record.** Record exact commands/results, Docker/Testcontainers skips, and local database migration blocker. Produce a read-only count of contacts lacking valid current profiles and historical page estimates, then calculate projected LLM usage/cost with the configured model's current pricing. If current token pricing is unavailable, report cost as N/A and keep `bootstrap-enabled=false`. Do not enable `bootstrap-enabled` until the user/deployment owner explicitly approves that estimate. Record current trigger-event row count/growth observability and verify events follow the existing `messages` FK cascade; do not add an independent TTL until a source-message retention contract exists.

- [ ] **Step 5: Review the final diff boundary.** `git status --short`, `git diff --check`, and explicit path review. Do not stage or commit implementation files outside the Task being completed.

## Plan Self-Review

- Design coverage: one-time snapshot, bounded ordered history pages, tuple-safe late inbox supplement, outcome distinction, atomic message receipt and cursor, retry, owner/fencing, query/UI visibility, cost cap, source-message-aligned inbox lifecycle, and Flyway safety map to Tasks 1–6.
- Targeted historical rewrite only: contacts with a valid current profile become `COMPLETE`; contacts without one backfill once, even if a prior successful cursor exists. The separate history cursor preserves the old success boundary until completion.
- No migration drift masking: the selected migration is additive (currently V99); V84/V89 and user-owned V97/V98 are not edited; the known local database is not a test target until its history is reconciled.
- Resource boundary: contact batch, per-contact page size, total prompt characters, model concurrency, and retry limits are bounded. Trigger-event rows follow the source message lifecycle and currently have no independent TTL; row count/growth must be observed, and any future retention change must preserve the dedupe/receipt contract.
- Scope boundary: no live-per-message LLM, no full daily recomputation, no manual-tag writes, no Topic or call-transcript trigger expansion.
