# AI Topic 手动整理与联系人合并拆分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留日常自动 Topic 更新的前提下，增加人工待确定整理，并让联系人合并/拆分安全地迁移 Topic 来源而不混入普通时间轴。

**Architecture:** `AiTopicService` 继续作为 Topic 业务 owner；数据库迁移增加 `REVIEW_PENDING` 与操作来源元数据，所有来源迁移在事务内执行并依赖 `ai_topic_items` 唯一约束。控制器只做权限和 DTO 映射，前端通过独立待确定区域和预览确认 API 消费结构化状态。

**Tech Stack:** Spring Boot, MyBatis-Plus, PostgreSQL/Flyway, React/TypeScript, Vitest, JUnit 5.

**执行状态（2026-09-02）：** Task 1-7 的实现和专项测试已完成，Task 8 的测试、构建、制品与验证记录已完成。由于当前 worktree 同时保留本任务链路此前的未提交改动，未机械执行每个 Task 的独立提交步骤，避免产生依赖不完整或混入用户 WIP 的提交；实际交付证据以对应 verification 文档为准。

## Global Constraints

- `READY`、`REVIEW_PENDING`、`STORED`、`ARCHIVED` 的语义与设计文档一致；自动 worker 只消费 `READY` 来源。
- `WECOM_GROUP` owner 永不参与个人联系人合并、拆分或融合。
- 一条消息、电话记录或企业微信摘要最多属于一个 `ai_topic_items` 记录；应用失败必须整体回滚。
- 单批手动整理最多 200 个来源；预览携带来源快照和 Topic 版本，过期即拒绝应用。
- 不修改已部署 Flyway 迁移，不手改前端 bundle；所有变更必须覆盖专项测试和文档。

---

### Task 1: 扩展 Topic 状态与审计元数据

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V40__ai_topic_review_pending.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicItemEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicReviewPendingSchemaContractTest.java`

**Interfaces:**
- Produce status string `REVIEW_PENDING` and enum `TopicReviewOrigin` values `MERGE_SOURCE`, `SPLIT_SOURCE`, `MANUAL_SELECTION`.
- Produce nullable topic columns `review_origin`, `review_source_contact_id`, `review_source_topic_id`, `review_operation_id`.
- Preserve existing source columns and unique indexes.

- [ ] **Step 1: Write the failing schema/entity contract test** asserting the migration contains `REVIEW_PENDING`, origin columns, owner index, and model accessors.
- [ ] **Step 2: Run `mvn -q -Dtest=AiTopicReviewPendingSchemaContractTest test` and verify RED.**
- [ ] **Step 3: Add V40 migration and model fields/enums.** The migration must drop/recreate only the current status check, add the four nullable metadata columns, add `ix_ai_topics_review_owner`, and add a check limiting `review_origin` to the three values or null.
- [ ] **Step 4: Run the contract test and Flyway migration parsing tests; verify GREEN.**
- [ ] **Step 5: Commit only Task 1 files with `git add <paths>` and `git commit -m "feat: add review pending topic state"`.**

### Task 2: Make Topic queries and automatic generation respect review state

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorker.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicReviewPendingServiceTest.java`

**Interfaces:**
- `AiTopicMapper.listReviewPendingForContact(UUID)` returns projections for only `REVIEW_PENDING` contact topics.
- `AiTopicService.getTopics` excludes review-pending topics; `getReviewPendingTopics` returns them separately.
- `AiTopicGenerationWorker` and `listReadyByOwner` continue to select only `READY`.

- [ ] **Step 1: Add failing tests for separate pending query and automatic worker exclusion.**
- [ ] **Step 2: Run the focused tests and verify RED.**
- [ ] **Step 3: Implement mapper SQL and service method, including permission checks and owner labels.**
- [ ] **Step 4: Verify focused tests plus existing `AiTopicGenerationWorkerTest`.**
- [ ] **Step 5: Commit Task 2.**

### Task 3: Change Topic merge to fusion with a new Topic ID

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicVersionMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicFusionMergeTest.java`

**Interfaces:**
- `mergeTopics` validates same owner and statuses `READY` or `REVIEW_PENDING`, creates a fresh UUID, migrates all items, archives every old topic, and writes two-way lineage versions.
- `WECOM_GROUP` and `CONTACT` owner mismatch must throw `TOPIC_MERGE_INVALID`.

- [ ] **Step 1: Replace the old target-reuse assertions with failing fusion tests.**
- [ ] **Step 2: Run `mvn -q -Dtest=AiTopicFusionMergeTest,AiTopicServiceMergeReconciliationTest test` and verify RED.**
- [ ] **Step 3: Implement transactionally: lock/validate versions, insert new topic, move items, archive old topics, write lineage versions, then project the new topic.**
- [ ] **Step 4: Run all Topic service tests and verify no source duplicates.**
- [ ] **Step 5: Commit Task 3.**

### Task 4: Reconcile Topic sources during contact merge and split

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceReviewPendingTest.java`

**Interfaces:**
- Contact merge transfers source `READY` topics to target owner and changes status to `REVIEW_PENDING` with `MERGE_SOURCE`; `STORED` remains stored.
- Contact split accepts the moved identity and partitions source items by identity/contact source. Full topics become pending; mixed topics keep remaining items and are regenerated; empty originals become `ARCHIVED`; stored extracted sources become pending.

- [ ] **Step 1: Add failing merge/split tests covering full, mixed, stored, and group-owner cases.**
- [ ] **Step 2: Run focused tests and verify RED.**
- [ ] **Step 3: Implement mapper operations for owner transfer, source partition, pending creation, and archive-on-empty.**
- [ ] **Step 4: Verify transaction rollback on uniqueness/version conflict and group topics untouched.**
- [ ] **Step 5: Commit Task 4.**

### Task 5: Add manual selection preview and apply API

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/AiTopicManualReviewRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/AiTopicManualReviewResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicReviewMapper.java`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V41__ai_topic_manual_review.sql`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicManualReviewControllerTest.java`

**Interfaces:**
- `GET /api/v1/contacts/{contactId}/topic-review/sources?from=&to=` returns at most 200 selectable sources and a reason for excluded WeCom summaries.
- `POST /api/v1/contacts/{contactId}/topic-review/preview` accepts selected source IDs, source fingerprint, and date range; returns assignments and expected versions.
- `POST /api/v1/contacts/{contactId}/topic-review/{previewId}/apply` validates the snapshot and applies source moves/new Topics atomically.

- [ ] **Step 1: Add controller/service tests for filtering, 200-source limit, preview persistence, version conflict, and apply idempotency.**
- [ ] **Step 2: Run focused tests and verify RED.**
- [ ] **Step 3: Add bounded preview tables/mapper and service transaction; reuse `TopicAiGateway` for assignment generation.**
- [ ] **Step 4: Verify permissions, stale-source rejection, and no changes on failed apply.**
- [ ] **Step 5: Commit Task 5.**

### Task 6: Expose pending actions and fusion preview endpoints

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerMixedScopeTest.java`

**Interfaces:**
- `GET /api/v1/contacts/{contactId}/topic-review/pending` lists pending topics.
- `POST /api/v1/topics/{topicId}/keep` transitions pending to `READY`; `POST /api/v1/topics/{topicId}/store` supports pending to `STORED`.
- Fusion preview/confirm endpoints use the existing operation idempotency contract and return the new Topic projection.

- [ ] **Step 1: Add failing MVC tests for pending list, keep, store, and fusion responses.**
- [ ] **Step 2: Run tests and verify RED.**
- [ ] **Step 3: Implement endpoints and state transitions with current-user authorization.**
- [ ] **Step 4: Run controller and service tests.**
- [ ] **Step 5: Commit Task 6.**

### Task 7: Build the Topic timeline pending area and manual review flow

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ConversationWorkspace.tsx`
- Test: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.manual-review.test.tsx`

**Interfaces:**
- API client exposes pending list, source query, preview, apply, keep, store, and fusion operations with typed responses.
- UI renders normal READY timeline separately from pending and repository; manual selection supports row checkboxes and “全选当前结果”, preview editing, and confirm.

- [ ] **Step 1: Add failing Vitest tests for separate pending section, checkbox/all-select behavior, disabled unsupported WeCom source, and fusion confirmation.**
- [ ] **Step 2: Run `npm run test -- --run src/components/AiTopicTimeline.manual-review.test.tsx` and verify RED.**
- [ ] **Step 3: Implement typed API calls and UI state; refresh only after final snapshot/SSE event.**
- [ ] **Step 4: Run focused frontend tests and build.**
- [ ] **Step 5: Commit Task 7.**

### Task 8: Documentation, regression, and release artifacts

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-ai-topic-manual-review-contact-merge-split-design.md`
- Create: `docs/superpowers/reviews/2026-09-02-ai-topic-manual-review-contact-merge-split-verification.md`
- Generated: `demo/message-center-spring/backend/target/message-center.jar`
- Generated: `demo/message-center-spring/frontend-dist-20260902-ai-topic-manual-review-r2.zip`

- [ ] **Step 1: Run backend Topic/contact focused tests.**
- [ ] **Step 2: Run frontend full Vitest suite and production build.**
- [ ] **Step 3: Package Jar into `backend/target/message-center.jar`, create ZIP from `frontend/dist`, and record SHA-256.**
- [ ] **Step 4: Write verification evidence, migration/version notes, and deployment commands.**
- [ ] **Step 5: Run `git status --short`, inspect staged paths, and report uncommitted artifact choices without staging unrelated user files.**
