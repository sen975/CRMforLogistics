# 联系人 AI 标签、画像与增量记忆系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变人工标签和 Topic owner 的前提下，为联系人建立五层 AI 记忆、夜间增量画像、证据驱动的 AI 标签和可追溯查询投影。

**Architecture:** 新增 `contactmemory` 业务模块作为联系人记忆唯一 owner。消息投影成功后只调用触发服务把联系人状态置为 `DIRTY`；午夜后的 scheduler 领取租约，读取受限增量上下文，调用独立的 `ContactMemoryLlmGateway`，由服务端完成观察去重、长期事实晋升、标签投影和画像版本写入。观察、事实、标签、画像、审计和成功游标在同一事务中提交，失败时保持旧展示和旧游标。

**Tech Stack:** Java 17、Spring Boot 3.4.5、MyBatis-Plus 3.5.10、PostgreSQL、Flyway、Jackson、Spring `RestClient`、JUnit 5/Mockito、React 18、TypeScript、Ant Design、TanStack Query、Vitest。

## Global Constraints

- `owner_user_id = contacts.created_by`；没有有效 owner 的联系人不创建自动处理状态、不进入 LLM 队列。
- AI 标签和人工标签必须使用独立数据表和独立写路径；AI 不得修改、删除、失效或恢复人工标签。
- 只有客户入站消息成功入库后才标记 `DIRTY`；出站消息、Topic 更新、通话转写不单独触发 LLM。
- 每天 `00:00` 后批量处理；没有成功游标之后新增入站消息的联系人不得调用 LLM。
- 每次处理必须有 `cutoff_at`、输入输出上界、租约、最多 3 次重试和指数退避。
- 画像正文最多 200 个汉字；AI 标签名称为短词组；颜色由服务端分类映射决定，LLM 不返回颜色。
- 画像版本不可变；成功游标只在画像、标签、事实、证据和审计同一事务提交成功后推进。
- 证据只能引用本次受限上下文中的消息、Topic、可靠通话转写或服务端确认的长期事实；不得跨联系人或跨 owner。
- 本工作区已经存在大量与 WhatsApp、模板、历史同步和前端构建相关的用户 WIP；禁止 `git add .`、禁止回滚用户修改、每个 Task 只 stage 自己声明的文件。
- 每个 Task 完成后只运行该 Task 的专项测试和编译，再按精确文件列表提交；最终全量回归只在最后一个 Task 执行。

## 文件与模块地图

### 新增后端模块

- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryStateEntity.java`：联系人处理状态、游标、重试和租约。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryObservationEntity.java`：短期观察。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryObservationEvidenceEntity.java`：观察证据。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryFactEntity.java`：长期事实。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryFactEvidenceEntity.java`：长期事实证据。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemoryAttemptEntity.java`：不可变处理审计。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactProfileVersionEntity.java`：不可变画像版本。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactAiLabelEntity.java`：AI 标签展示投影。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactAiLabelEvidenceEntity.java`：AI 标签证据。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`：状态领取、标脏、成功/失败转换。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`：观察、事实、画像、标签和证据查询/写入。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`：枚举、输入输出 record、游标和错误码。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java`：入站消息后的唯一触发入口。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`：owner 校验、增量消息、补充上下文和预算裁剪。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`：观察去重、晋升、冲突和生命周期。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`：单事务写入所有记忆结果。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGateway.java`：LLM 结构化调用合同。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java`：复用 `AiTopicConfig` 的 OpenAI-compatible 实现。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`：领取租约、编排处理和有限重试。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java`：午夜后轮询。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`：联系人详情记忆投影和 owner 隔离。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java`：必要的只读诊断/重试边界。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java`：前端查询合同。

### 修改现有入口

- `demo/message-center-spring/backend/src/main/resources/db/migration/V70__contact_ai_memory.sql`：新表、约束、索引和触发状态。
- `demo/message-center-spring/backend/src/main/resources/application.yml`、`application-dev.yml`：记忆预算、worker、租约、重试和夜间起始时间配置。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`：入站消息落库后调用触发服务。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java`：沿用 webhook 投影时只修改共享注入合同（若当前调用链确认需要）。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`：入站邮件落库后调用触发服务。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`：客户入站消息落库后调用触发服务。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`：组合记忆查询结果到联系人响应。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`：增加画像、AI 标签和处理状态字段，同时保持人工 `tags` 字段不变。
- `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`：保持现有联系人查询入口，仅接入结构化记忆投影。
- `demo/message-center-spring/frontend/src/api/types.ts`、`api/endpoints.ts`：增加记忆响应类型；不在前端推断颜色或状态。
- `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`：分离人工标签、AI 标签和画像状态。

### 测试与文档

- `demo/message-center-spring/backend/src/test/resources/db/migration/` 下的 SQL 合同测试（沿用项目现有测试布局）。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/`：领域、上下文、LLM、worker 和事务测试。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java`：权限和响应合同。
- `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java`、`channel/email/EmailSyncServiceTest.java`、`service/wecom/WeComMessageProjectorTest.java`：入站触发回归。
- `demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx`：画像和 AI/人工标签展示。
- `demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts`：前端类型/端点合同。
- `docs/superpowers/reviews/2026-09-11-contact-ai-memory-verification.md`：最终专项和全量验收记录。
- `docs/superpowers/README.md`：登记当前实施计划和验收记录。

---

### Task 1: 建立数据库五层记忆合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V70__contact_ai_memory.sql`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemorySchemaContractTest.java`
- Test/verify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemorySchemaContractTest.java` 使用 `@Testcontainers`、`PostgreSQLContainer`、`Flyway` 和 `JdbcTemplate`，沿用现有迁移合同测试的独立 schema 模式

**Interfaces:**
- Produces tables `contact_memory_states`, `contact_memory_observations`, `contact_memory_observation_evidence`, `contact_memory_facts`, `contact_memory_fact_evidence`, `contact_profile_versions`, `contact_ai_labels`, `contact_ai_label_evidence`, `contact_memory_attempts`.
- Produces unique key `(contact_id, owner_user_id)` for state, fact semantic uniqueness, AI label semantic uniqueness, and one-current-profile partial unique index.
- Produces constraints for `CLEAN/DIRTY/PROCESSING/RETRY_WAIT/FAILED`, observation lifecycle, fact lifecycle, label lifecycle and evidence types.

- [ ] **Step 1: Write the failing schema contract tests.**

```java
@Test
void createsAllMemoryTablesAndCriticalConstraints() {
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables " +
        "where table_schema = current_schema() and table_name in " +
        "('contact_memory_states','contact_memory_observations'," +
        "'contact_memory_observation_evidence','contact_memory_facts'," +
        "'contact_memory_fact_evidence','contact_profile_versions'," +
        "'contact_ai_labels','contact_ai_label_evidence','contact_memory_attempts')",
        Integer.class)).isEqualTo(9);
}

@Test
void permitsOnlyOneCurrentProfilePerContactOwner() {
    UUID contactId = insertContact();
    UUID ownerId = ownerOf(contactId);
    insertProfile(contactId, ownerId, true);
    assertThatThrownBy(() -> insertProfile(contactId, ownerId, true))
        .isInstanceOf(DataAccessException.class);
}

@Test
void aiLabelStorageIsSeparateFromManualTagStorage() {
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns " +
        "where table_schema = current_schema() and table_name='contact_ai_labels' " +
        "and column_name='source'",
        Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables " +
        "where table_schema = current_schema() and table_name='contact_taggings'",
        Integer.class)).isEqualTo(1);
}
```

- [ ] **Step 2: Run the focused schema test and confirm it fails because `V70` does not exist.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemorySchemaContractTest test`

Expected: `FAIL` with missing-table or migration-contract failure.

- [ ] **Step 3: Add `V70` with bounded columns, foreign keys, check constraints, indexes and the partial current-profile index.**

The migration must include `owner_user_id`, `generation_batch_id`, bounded evidence excerpt columns, `source_cursor`, `retry_count`, lease columns, `next_retry_at`, all lifecycle checks, and indexes for runnable states, owner/contact reads and evidence lookups. Do not add triggers that call LLM or backfill old contacts.

- [ ] **Step 4: Re-run the focused schema test and add explicit duplicate/current-state assertions until it passes.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemorySchemaContractTest test`

Expected: `PASS`, with no migration warning.

- [ ] **Step 5: Commit only the migration and schema test.**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V70__contact_ai_memory.sql \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemorySchemaContractTest.java
git commit -m "feat: add contact memory schema"
```

### Task 2: 定义实体、枚举和 Mapper 合同

**Files:**
- Create: the nine entity files listed in the file map.
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryStateMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapperSqlTest.java`

**Interfaces:**
- `ContactMemoryStateMapper.markDirty(UUID contactId, UUID ownerUserId, Instant inboundAt): int`
- `ContactMemoryStateMapper.listRunnable(Instant now, Instant after, int limit): List<ContactMemoryStateEntity>`
- `ContactMemoryStateMapper.claim(UUID id, String leaseOwner, Instant leaseUntil): int`
- `ContactMemoryStateMapper.complete(UUID id, String leaseOwner, String cursor, Instant processedAt): int`
- `ContactMemoryStateMapper.fail(UUID id, String leaseOwner, String code, String message, int retryCount, Instant retryAt, boolean terminal): int`
- `ContactMemoryMapper.listInboundMessages(UUID ownerUserId, UUID contactId, Instant after, Instant cutoff, int limit): List<MessageEntity>`
- `ContactMemoryMapper.listStableContext(UUID ownerUserId, UUID contactId, int limit): ContactMemoryModels.StableContext`
- All writes accept both `contactId` and `ownerUserId` and return affected-row counts.

- [ ] **Step 1: Write SQL contract tests for owner predicates, cutoff ordering, runnable states and bounded limits.**

```java
@Test
void inboundMessageQueryUsesContactCreatedByAndCutoff() {
    String sql = readMapperAnnotation(ContactMemoryMapper.class, "listInboundMessages");
    assertThat(sql).contains("c.created_by = #{ownerUserId}::uuid")
                   .contains("m.direction = 'inbound'")
                   .contains("m.occurred_at <= #{cutoff}")
                   .contains("limit #{limit}");
}
```

- [ ] **Step 2: Run the focused mapper test and confirm it fails because the contracts do not exist.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryMapperSqlTest test`

Expected: `FAIL` with missing mapper methods/entities.

- [ ] **Step 3: Add minimal entities, records and mapper methods matching `V70` exactly.**

Use explicit `@Select`, `@Insert`, `@Update` and `@Delete` SQL for critical owner/cursor/lease operations. Never use a generic `selectById` for an owner-protected memory read.

- [ ] **Step 4: Run mapper SQL and entity mapping tests.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemory*Test' test`

Expected: `PASS`.

- [ ] **Step 5: Commit only entity/model/mapper files and their tests.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactMemory*Entity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactProfileVersionEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactAiLabelEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMemory*.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryModels.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMemoryMapperSqlTest.java
git commit -m "feat: add contact memory persistence contracts"
```

### Task 3: 实现上下文预算、游标和入站触发

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`

**Interfaces:**
- `ContactMemoryTriggerService.markInboundPersisted(UUID contactId, Instant occurredAt): void`
- `ContactMemoryContextService.load(UUID ownerUserId, UUID contactId, Instant cutoff): ContactMemoryModels.Context`
- `ContactMemoryModels.Context` contains bounded inbound messages, current profile, active facts, active AI labels, stable context, topics, call transcripts, `inputCursor` and `outputCursor`.

- [ ] **Step 1: Write failing tests for idempotent `DIRTY`, owner absence, outbound non-trigger, cutoff exclusion, first-load versus incremental-load and hard budgets.**

```java
@Test
void repeatedInboundMessagesMergeIntoOneDirtyState() {
    service.markInboundPersisted(contactId, first);
    service.markInboundPersisted(contactId, second);
    verify(states).markDirty(contactId, ownerId, second);
}

@Test
void contextExcludesMessagesAfterCutoffAndNeverReadsAnotherOwner() {
    ContactMemoryModels.Context context = service.load(ownerId, contactId, cutoff);
    assertThat(context.inboundMessages()).allMatch(item -> !item.occurredAt().isAfter(cutoff));
    verify(memory).listInboundMessages(ownerId, contactId, cursor, cutoff, 50);
}
```

- [ ] **Step 2: Run focused tests and verify expected failures.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryTriggerServiceTest,ContactMemoryContextServiceTest' test`

Expected: `FAIL` because services/configuration are missing.

- [ ] **Step 3: Implement trigger and context services with fixed defaults and configuration bounds.**

Use `maxInboundMessages=50`, `maxMessageChars=4000`, `maxTotalChars=50000`, `maxTopics=20`, `maxTopicChars=1000`, `maxCallTranscripts=10`, `maxTranscriptChars=4000`, `maxFacts=100`, `maxLabels=100`, `maxObservationChars=500`, and `observationTtlDays=30` as validated defaults. Keep stable-context priority exactly as the spec: new inbound messages, current profile, observations, active facts, active labels, stable memory, Topics, transcripts.

- [ ] **Step 4: Wire trigger calls only after successful inbound insert in ChatApp, email and WeCom projectors.**

The trigger call is best-effort and replayable: an exception must be logged with contact ID and not undo the already committed message. Outbound paths must not call it.

- [ ] **Step 5: Run focused trigger/context and projector regression tests.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryTriggerServiceTest,ContactMemoryContextServiceTest,ChatAppWebhookProjectorTest,EmailSyncServiceTest,WeComMessageProjectorTest' test`

Expected: `PASS`.

- [ ] **Step 6: Commit exact Task 3 files, excluding all user WIP.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryTriggerService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryContextService.java \
  demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorTest.java
git commit -m "feat: trigger bounded contact memory from inbound messages"
```

### Task 4: 实现观察整合、长期事实和 AI 标签投影

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java`

**Interfaces:**
- `consolidate(Context context, ContactMemoryModels.LlmOutput output): ConsolidationResult`
- `persist(UUID ownerUserId, UUID contactId, Lease lease, ConsolidationResult result): MutationResult`
- `normalizeKey(String category, String value): String`
- `colorToken(String category): String`

- [ ] **Step 1: Write failing tests for observation deduplication, 30-day expiry, two-independent-evidence promotion, conflict handling, label restore, fixed colors and manual-tag isolation.**

```java
@Test
void sameSemanticObservationIsMergedAndOnlyStableEvidencePromotesFact() {
    ConsolidationResult result = service.consolidate(contextWithRepeatedObservation(),
            outputWithRepeatedObservation());
    assertThat(result.observations()).hasSize(1);
    assertThat(result.facts()).isEmpty();
    assertThat(service.consolidate(contextWithIndependentEvidence(),
            outputWithIndependentEvidence()).facts()).singleElement()
            .extracting(FactCandidate::status).isEqualTo(ACTIVE);
}

@Test
void aiLabelUsesCategoryColorAndNeverCallsManualTagMapper() {
    ConsolidationResult result = service.consolidate(contextWithStableFact(),
            outputWithLabelCandidate());
    assertThat(result.labels()).singleElement()
            .extracting(LabelCandidate::colorToken).isEqualTo("green");
    verifyNoInteractions(manualTagMapper);
}

@Test
void profileEvidenceCannotPretendToBeLongTermFactEvidence() {
    assertThatThrownBy(() -> mutation.persist(ownerId, contactId, lease,
            resultWithProfileOnlyFactEvidence()))
        .isInstanceOf(ContactMemoryModels.ValidationException.class);
}
```

- [ ] **Step 2: Run focused tests and verify they fail for missing consolidation/mutation behavior.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryConsolidationServiceTest,ContactMemoryMutationServiceTest' test`

Expected: `FAIL`.

- [ ] **Step 3: Implement normalization, observation lifecycle and fact promotion.**

Use fixed categories `IDENTITY`, `PRODUCT_INTEREST`, `NEED`, `PERSONALITY_COMMUNICATION`, `DECISION_FACTOR`, `RISK`, `RELATIONSHIP_STAGE`, `OTHER_STABLE_TRAIT`; use `blue`, `green`, `orange`, `purple`, `cyan`, `red`, `gray`, `brown`. Do not fuzzy-merge unconfirmed labels. Handle explicit negation by moving old facts to `CONFLICTED`/`INACTIVE` only after the new fact passes promotion rules.

- [ ] **Step 4: Implement atomic mutation transaction.**

The transaction writes observations, observation evidence, fact changes, fact evidence, AI labels, label evidence, profile version, attempts and cursor. It validates lease ownership, input evidence ownership, 200-character profile limit, per-round label change limit and idempotency before any mutation. `PROFILE_VERSION` may supplement label audit evidence but cannot satisfy the required `fact_id` or become a long-term fact.

- [ ] **Step 5: Run focused tests and verify old state remains unchanged on any failure.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryConsolidationServiceTest,ContactMemoryMutationServiceTest' test`

Expected: `PASS`.

- [ ] **Step 6: Commit only consolidation, mutation and tests.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryConsolidationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryMutationServiceTest.java
git commit -m "feat: consolidate contact memory facts and ai labels"
```

### Task 5: 接入结构化 LLM gateway 和严格输出校验

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGateway.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGatewayTest.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`

**Interfaces:**
- `ContactMemoryModels.LlmOutput generate(ContactMemoryModels.Context context)`
- `ContactMemoryModels.LlmOutput` contains observations, profile candidate, label changes, noises, model and bounded raw diagnostics.
- `ContactMemoryLlmGateway` never returns a result with database IDs or permission decisions not present in input context.

- [ ] **Step 1: Write failing parser/gateway tests for valid JSON, code fences, invalid enum, invalid evidence, overlong profile, invalid confidence, cross-owner evidence and output-limit rejection.**

```java
@Test
void rejectsEvidenceIdOutsideTheInputContext() { }

@Test
void rejectsProfileLongerThanTwoHundredChineseCharacters() { }

@Test
void returnsStructuredUnavailableCodeForTimeout() { }
```

- [ ] **Step 2: Run focused gateway tests and confirm expected failure.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryLlmGatewayTest test`

Expected: `FAIL`.

- [ ] **Step 3: Implement an OpenAI-compatible request with a JSON-only system prompt and bounded payload.**

Reuse `AiTopicConfig` endpoint/key/model/timeout. Send only current contact owner, bounded context, current profile, active facts/labels, inbound messages, Topics, reliable transcripts and read-only manual tags. Do not send credentials, unlimited history or other contacts.

- [ ] **Step 4: Parse and validate output on the server.**

Require observations, profile and label-change structures; cap one-round label changes at 20; enforce category/operation enums, normalized names, confidence `0..1`, evidence membership, evidence owner/contact matching, bounded reasons and profile length. LLM cannot return category colors or direct fact status.

- [ ] **Step 5: Run gateway tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryLlmGatewayTest test && mvn -DskipTests compile`

Expected: `PASS`.

- [ ] **Step 6: Commit gateway and tests.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/OpenAiCompatibleContactMemoryGateway.java \
  demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryLlmGatewayTest.java
git commit -m "feat: add contact memory llm contract"
```

### Task 6: 实现夜间 scheduler、worker、租约和重试

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemorySchedulerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`

**Interfaces:**
- `ContactMemoryWorker.runOnce(Instant now): int`
- `ContactMemoryWorker.process(ContactMemoryStateEntity state, Instant now): void`
- `ContactMemoryScheduler.run(): void`

- [ ] **Step 1: Write failing tests for midnight gate, runnable state selection, single lease winner, no-inbound no-LLM path, cutoff behavior, retry/backoff and terminal failure.**

```java
@Test
void cleanStateWithNoNewInboundDoesNotCallGateway() { }

@Test
void failedMutationDoesNotAdvanceCursorAndRetriesAtBoundedBackoff() { }

@Test
void staleLeaseCannotCommitAfterAnotherWorkerClaimsTheContact() { }
```

- [ ] **Step 2: Run focused tests and confirm they fail.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryWorkerTest,ContactMemorySchedulerTest' test`

Expected: `FAIL`.

- [ ] **Step 3: Implement worker orchestration and lease checks.**

Claim only `DIRTY`, due `RETRY_WAIT`, or expired `PROCESSING`; create one `cutoff_at`; skip LLM if no inbound message is after the successful cursor; call context -> gateway -> consolidation -> mutation. On transient gateway errors retry up to 3 times with capped exponential backoff; on invalid evidence/owner loss/persistence error keep old result and record structured failure.

- [ ] **Step 4: Implement scheduler after `00:00` with bounded batch and no full historical sweep.**

Use `@Scheduled(fixedDelayString = "${contact-memory.poll-interval-seconds:600}000")`; gate with configured `start-hour=0`, `start-minute=0`, and process only states marked by inbound activity or due retries.

- [ ] **Step 5: Run worker/scheduler tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryWorkerTest,ContactMemorySchedulerTest' test && mvn -DskipTests compile`

Expected: `PASS`.

- [ ] **Step 6: Commit exact Task 6 files.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryScheduler.java \
  demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryWorkerTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemorySchedulerTest.java
git commit -m "feat: add nightly contact memory worker"
```

### Task 7: 接入联系人查询 API 与 owner 隔离

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`

**Interfaces:**
- `GET /api/contacts/{contactId}/memory` returns `profile`, `humanTags`, `aiTags`, `state`, `lastSuccessAt`, `lastFailureCode`, `pendingInbound`.
- Query owner is `contacts.created_by`; no admin bypass for memory data.
- `ContactResponse` adds `memory` as a nullable structured projection; existing `tags` remains the manual-tag field.

- [ ] **Step 1: Write failing controller/service tests for owner isolation, manual/AI separation, stale/inactive display and safe failure message.**

```java
@Test
void memoryEndpointRejectsContactOwnedByAnotherUser() { }

@Test
void contactResponseKeepsManualTagsSeparateFromAiLabels() { }
```

- [ ] **Step 2: Run focused API tests and confirm failure.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryControllerTest,ContactControllerTest' test`

Expected: `FAIL`.

- [ ] **Step 3: Implement query projection with owner predicates and stable category colors from stored server values.**

Return old profile while state is `DIRTY/PROCESSING`; return no model raw error; hide `INACTIVE` labels by default but preserve an audit-only query.

- [ ] **Step 4: Add the projection to `ContactResponse` without changing the meaning or write path of `tags`.**

- [ ] **Step 5: Run focused API tests and compile.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='ContactMemoryControllerTest,ContactControllerTest' test && mvn -DskipTests compile`

Expected: `PASS`.

- [ ] **Step 6: Commit only Task 7 files.**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactMemoryResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactMemoryController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactMemoryControllerTest.java
git commit -m "feat: expose contact memory projection"
```

### Task 8: 联系人详情前端展示 AI 画像和标签

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx`
- Create: `demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts`

**Interfaces:**
- Frontend consumes `ContactMemoryResponse` exactly as returned by backend.
- Human tags retain existing edit control; AI tags are read-only.
- Frontend renders server-provided `colorToken` and `status`, never maps category to color locally.

- [ ] **Step 1: Write failing UI tests for profile, separate tag sections, server color, stale styling, processing status and safe failure state.**

```tsx
it('shows profile and separates human tags from read-only ai tags', async () => {
  // render contact.memory with manualTags and aiTags
  expect(screen.getByText('AI 画像')).toBeInTheDocument();
  expect(screen.getByText('AI 标签')).toBeInTheDocument();
  expect(screen.getByText('人工标签')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run focused frontend tests and verify failure.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/ContactDetailPanel.test.tsx`

Expected: `FAIL`.

- [ ] **Step 3: Add TypeScript types and render the structured projection.**

Keep profile text capped by backend; show `暂无画像` when absent, `正在更新` for `DIRTY/PROCESSING`, and a safe Chinese failure message for `FAILED`. Do not add AI edit buttons or merge AI tags into `contact.tags`.

- [ ] **Step 4: Add endpoint/type contract test and update existing fixtures with optional `memory`.**

- [ ] **Step 5: Run focused UI tests, source tests and frontend build.**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- src/components/ContactDetailPanel.test.tsx src/api/contact-memory-contract.test.ts && npm run test:source && npm run build`

Expected: `PASS`.

- [ ] **Step 6: Commit only frontend memory files.**

```bash
git add demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx \
  demo/message-center-spring/frontend/src/api/contact-memory-contract.test.ts
git commit -m "feat: show contact ai memory in detail"
```

### Task 9: 观测、专项回归和最终验收

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryEndToEndTest.java`
- Create: `docs/superpowers/reviews/2026-09-11-contact-ai-memory-verification.md`
- Modify: `docs/superpowers/README.md`
- Modify: `docs/superpowers/specs/2026-09-10-contact-ai-memory-design.md` only if implementation evidence requires a clarified contract

**Interfaces:**
- End-to-end path: inbound insert -> `DIRTY` -> nightly worker -> gateway output -> observations/facts/labels/profile/audit/cursor -> owner-scoped query.
- Verification record lists exact commands, outputs, skipped external gates and remaining risks.

- [ ] **Step 1: Write failing end-to-end tests covering the complete happy path and failure atomicity.**

```java
@Test
void inboundMessageProducesVersionedProfileAndAiLabelWithoutChangingManualTags() { }

@Test
void llmFailureLeavesPreviousProjectionAndCursorUntouched() { }
```

- [ ] **Step 2: Run the focused end-to-end test and confirm failure before final wiring.**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=ContactMemoryEndToEndTest test`

Expected: `FAIL` if any cross-module contract is incomplete.

- [ ] **Step 3: Fix only memory-chain defects exposed by the focused test; do not absorb unrelated WhatsApp/Topic WIP.**

- [ ] **Step 4: Run backend专项 tests and frontend tests/build.**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest='ContactMemory*Test,ContactControllerTest,ChatAppWebhookProjectorTest,EmailSyncServiceTest,WeComMessageProjectorTest' test
cd ../frontend
npm test
npm run build
```

Expected: memory and touched-entry tests pass; any unrelated pre-existing failures are recorded with exact class names and cause.

- [ ] **Step 5: Run final backend compile/full test once, if the local environment supports it.**

Run: `cd demo/message-center-spring/backend && mvn clean test`

Expected: pass, or a verification record explicitly lists environment blockers and unrelated existing failures.

- [ ] **Step 6: Perform git boundary review and commit the verification record.**

Run: `git status --short`, `git diff --check`, `git diff --cached --stat`.

Stage only:

```bash
git add docs/superpowers/README.md \
  docs/superpowers/reviews/2026-09-11-contact-ai-memory-verification.md
git commit -m "docs: verify contact ai memory system"
```

## Spec Coverage Self-Review

- 五层记忆、观察/事实/标签/画像/审计表：Task 1-4。
- `contacts.created_by` owner、跨 owner 证据拒绝：Task 2-3、5、7。
- 只有入站消息触发、出站/Topic/转写不触发：Task 3 和入口回归测试。
- 午夜批处理、`cutoff_at`、游标、租约、重试和失败原子性：Task 3、6、9。
- 200 字画像、增量画像与首轮历史窗口：Task 3-6。
- AI 标签分类、颜色、简短名称、无总量上限和每轮上限：Task 4-5、8。
- 人工标签隔离：Task 4、7、8、9。
- 画像版本、标签证据、事实证据和审计可追溯：Task 1、4、9。
- 联系人查询 API、前端状态/错误/弱化展示：Task 7-8。
- 隐私、日志和上下文预算：Task 3、5、6、9。
- 不调用 LLM 的部署迁移和不做旧联系人全量回算：Task 1、6、9。

## Plan Self-Check

- Placeholder scan: no `TODO`, `TBD` or “implement later” instructions.
- Type consistency: all later tasks consume `ContactMemoryModels.Context`, `LlmOutput`, `ContactMemoryModels.Lease` and `ContactMemoryResponse` defined by earlier tasks.
- Existing WIP isolation: every commit uses explicit paths; no task stages ZIPs, `backend/data/` or unrelated WhatsApp/Topic files.
- Known correction before execution: Task 4 commit command must use the actual `ContactMemoryMutationServiceTest.java` path; the implementation worker must not copy the typo from an earlier draft.
