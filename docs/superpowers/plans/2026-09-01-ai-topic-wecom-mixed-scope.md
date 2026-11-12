# AI Topic 企业微信混合归属与静默重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信单条官方摘要纳入个人/群 Topic，统一 Topic owner，按 6 分钟静默异步重构，并实现群 Topic 引用、入库审批、仓库保留和最终快照刷新。

**Architecture:** 以 `owner_type + owner_id` 作为 Topic、生成任务和静默窗口的统一边界；`CONTACT` owner 聚合 ChatApp/邮件/电话及企业微信一对一摘要，`WECOM_GROUP` owner 只聚合该群企业微信摘要。联系人页面查询个人 Topic 与参与群 Topic 的同一份投影，不复制数据。消息/摘要完成只推进 owner 静默队列，后台 worker 在事件时间后 6 分钟执行增量 AI 任务，Topic 操作和群审批都通过异步任务提交最终快照。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、Jackson、JUnit 5、React、TypeScript、TanStack Query、Ant Design、Vite。

## Global Constraints

- AI 输入只允许 ChatApp、邮件、电话，以及企业微信 `COMPLETED` 且 `summary` 非空的单条摘要。
- 企业微信群只生成一份 `WECOM_GROUP` Topic；联系人页面只能引用，禁止复制 Topic 或来源。
- 企业微信消息正文、`secret_key`、access token、私钥和 API Key 不得写入 Topic 输入审计或普通日志。
- 所有 Topic 生成、入库、审批、恢复均异步；后台完成后只发布一次无敏感数据的 `topic-snapshot-completed` 事件。
- 活跃 Topic 为 `READY`，入库 Topic 为 `STORED`，合并历史为 `ARCHIVED`；不保留旧弃用产品入口。
- 已归类来源、`STORED`、`ARCHIVED` 和其他 owner 的 Topic 不参与后续关联。
- 单批记录数、请求/响应审计大小、并发、租约、重试次数、退避和分页必须有界。
- 工作区存在用户未提交 WIP；每个任务只 stage 本任务文件，不使用 `git add .`，不回滚其他改动。

---

### Task 1: 数据库 owner、来源和入库审批合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V32__ai_topic_mixed_scope.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicItemEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationJobEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicOperationJobEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationAttemptEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicOwnerActivityEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicInboxRequestEntity.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicMixedScopeSchemaContractTest.java`

**Interfaces:**
- `AiTopicEntity.ownerType` accepts `CONTACT` or `WECOM_GROUP`; `ownerId` is the contact UUID or source conversation UUID.
- `AiTopicItemEntity.wecomMessageSummaryJobId` is mutually exclusive with `messageId` and `callRecordId`.
- `AiTopicOwnerActivityEntity` stores `ownerType`, `ownerId`, `latestEventAt`, `quietDeadline`, `activityVersion`, `status`, `leaseOwner`, `leaseUntil`.
- `AiTopicInboxRequestEntity` stores `topicId`, `requestedByUserId`, `status` (`PENDING`, `APPROVED`, `REJECTED`), reviewer and timestamps.

- [ ] **Step 1: Write failing schema contract tests**

```java
@Test
void schemaContractRequiresMixedScopeAndWecomSourceColumns() {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V32__ai_topic_mixed_scope.sql"));
    assertThat(sql).contains("owner_type", "owner_id", "WECOM_GROUP", "STORED",
            "wecom_message_summary_job_id", "quiet_deadline", "ai_topic_inbox_requests");
}
```

- [ ] **Step 2: Run focused test and verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicMixedScopeSchemaContractTest test`

Expected: FAIL because `V32__ai_topic_mixed_scope.sql` and the new entities do not exist.

- [ ] **Step 3: Add the migration**

The migration must:

1. Add `owner_type` and `owner_id` to `ai_topics` and `ai_topic_generation_jobs`, backfilling existing rows as `CONTACT` with the existing `contact_id`; add nullable `wecom_group_source_conversation_id` foreign keys for group referential integrity.
2. Make the owner columns non-null, make the legacy `contact_id` nullable for group rows, and add checks that `CONTACT` owners match `contact_id` while `WECOM_GROUP` owners match `wecom_group_source_conversation_id`.
3. Extend Topic status to `READY`, `STORED`, `ARCHIVED`; convert historical retired rows to `STORED`.
4. Add `wecom_message_summary_job_id` to `ai_topic_items`, extend channel check with `wecom`, and add a partial unique index.
5. Add owner-scoped generation uniqueness and indexes.
6. Create `ai_topic_owner_activity` and `ai_topic_inbox_requests` with bounded status checks, leases, indexes, and idempotency uniqueness.
7. Extend operation kind with `STORE`, `RESTORE`, and group approval operations without retaining a second product-facing discard route.
8. Add `trigger_source` (`AUTO`, `MANUAL`) to generation jobs; automatic quiet-window jobs have no employee actor, while manual retries retain `created_by_user_id` after access validation.
9. Add owner type/ID to generation attempts so diagnostics remain queryable even if a job is later cleaned up.

The owner/source portion should follow this shape, with the migration ordering adjusted to the deployed V26-V31 schema:

```sql
ALTER TABLE ai_topics ADD COLUMN owner_type varchar(20);
ALTER TABLE ai_topics ADD COLUMN owner_id uuid;
ALTER TABLE ai_topics ADD COLUMN wecom_group_source_conversation_id uuid
  REFERENCES wecom_source_conversations(id) ON DELETE CASCADE;
UPDATE ai_topics SET owner_type = 'CONTACT', owner_id = contact_id WHERE owner_type IS NULL;
ALTER TABLE ai_topics ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topics ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topics ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE ai_topics ADD CONSTRAINT ck_ai_topics_owner_type
  CHECK (owner_type IN ('CONTACT', 'WECOM_GROUP'));
ALTER TABLE ai_topics ADD CONSTRAINT ck_ai_topics_owner_contact
  CHECK ((owner_type = 'CONTACT' AND contact_id = owner_id)
      OR (owner_type = 'WECOM_GROUP' AND contact_id IS NULL
          AND wecom_group_source_conversation_id = owner_id));

ALTER TABLE ai_topic_generation_jobs ADD COLUMN owner_type varchar(20);
ALTER TABLE ai_topic_generation_jobs ADD COLUMN owner_id uuid;
ALTER TABLE ai_topic_generation_jobs ADD COLUMN wecom_group_source_conversation_id uuid
  REFERENCES wecom_source_conversations(id) ON DELETE CASCADE;
ALTER TABLE ai_topic_generation_jobs ADD COLUMN trigger_source varchar(20) NOT NULL DEFAULT 'MANUAL';
UPDATE ai_topic_generation_jobs SET owner_type = 'CONTACT', owner_id = contact_id WHERE owner_type IS NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE ai_topic_items ADD COLUMN wecom_message_summary_job_id uuid
  REFERENCES wecom_message_summary_jobs(id) ON DELETE RESTRICT;
ALTER TABLE ai_topic_items DROP CONSTRAINT ck_ai_topic_items_source;
ALTER TABLE ai_topic_items ADD CONSTRAINT ck_ai_topic_items_source
  CHECK (((message_id IS NOT NULL)::int + (call_record_id IS NOT NULL)::int
        + (wecom_message_summary_job_id IS NOT NULL)::int) = 1);
CREATE UNIQUE INDEX ux_ai_topic_items_wecom_summary
  ON ai_topic_items(wecom_message_summary_job_id)
  WHERE wecom_message_summary_job_id IS NOT NULL;
```

- [ ] **Step 4: Add Java fields and accessors**

Add the following fields to the entities and keep MyBatis property names aligned with SQL columns:

```java
private String ownerType;
private UUID ownerId;
private UUID wecomMessageSummaryJobId;
```

Add explicit entity fields for the activity and approval tables, including status, version, lease and timestamp fields.

- [ ] **Step 5: Run focused test and migration parser**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicMixedScopeSchemaContractTest test`

Expected: PASS, including checks that all three source columns are mutually exclusive and `STORED` is accepted.

- [ ] **Step 6: Commit**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V32__ai_topic_mixed_scope.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicItemEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationJobEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicOperationJobEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationAttemptEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicOwnerActivityEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicInboxRequestEntity.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicMixedScopeSchemaContractTest.java
git commit -m "feat: add mixed-scope ai topic schema"
```

### Task 2: Owner resolution and six-minute quiet-window service

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityWorker.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityScheduler.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityBackfill.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/WeComSummaryTopicActivityBridge.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicOwnerActivityMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AiTopicConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorker.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/WeComSummaryTopicActivityBridgeTest.java`

**Interfaces:**
- `OwnerRef resolveContact(UUID contactId)` returns `OwnerRef("CONTACT", contactId)`.
- `OwnerRef resolveWeComConversation(UUID sourceConversationId)` returns `CONTACT` for `DIRECT` mappings and `WECOM_GROUP` for `GROUP`.
- `void recordActivity(OwnerRef owner, Instant occurredAt)` performs the monotonic upsert:
  `latestEventAt=max(existing, occurredAt)` and `quietDeadline=max(existing, occurredAt.plus(6 minutes))`.
- `List<DueOwner> leaseDueOwners(Instant now, int limit)` claims only owners whose deadline is due and whose lease is free/expired.
- `void completeActivity(OwnerRef owner, long activityVersion)` clears the lease only when the version still matches.
- `void completeSummary(LeasedJob job, String summary, String rawResponseJson, String validationStage, Instant completedAt)` commits the summary result and owner activity update in one transaction.

- [ ] **Step 1: Write failing quiet-window tests**

Cover: first activity schedules `occurredAt+6m`; a newer event extends the deadline; a late older event never moves it backward; an owner with a changed activity version is not dispatched; direct and group conversations resolve to different owner types; completed summaries atomically create direct/group activity; failed/non-terminal summaries do not; historical unassigned sources are backfilled in bounded pages.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicOwnerActivityServiceTest,WeComSummaryTopicActivityBridgeTest test`

Expected: FAIL because owner/activity classes and mapper methods do not exist.

- [ ] **Step 3: Implement owner resolution and atomic upsert**

Use the existing conversation/source-conversation mappers for direct/group classification. Put the `INSERT ... ON CONFLICT ... DO UPDATE` and lease SQL in `AiTopicOwnerActivityMapper`; do not calculate quiet state in React or controllers.

Add `AI_TOPIC_QUIET_WINDOW_SECONDS` to `AiTopicConfig` with default `360` and validate it within `60..86400`. The scheduler poll interval stays independently bounded.

- [ ] **Step 4: Wire committed message/call events**

Call `recordActivity` only after the corresponding message or call row has successfully persisted. Cover inbound/outbound ChatApp, email sync/send, phone transcription/note completion, and use a transaction-synchronized event where the producer cannot call the service inside its transaction. The call must include `occurredAt`, not `Instant.now()` unless the stored event time is absent by contract.

For enterprise WeCom summaries, replace the worker's direct `repository.markCompleted(...)` call with `WeComSummaryTopicActivityBridge.completeSummary(...)`. The bridge joins one Spring transaction, marks the job `COMPLETED`, resolves direct/group owner from `source_conversation_id`, and records activity using the original `send_time`.

- [ ] **Step 5: Implement scheduler/worker**

The worker leases a bounded batch, rechecks `activityVersion` and deadline in one transaction, creates an `AUTO` initial/incremental generation request through `AiTopicService`, then completes the activity lease. Automatic jobs keep `createdByUserId=null` and use internal owner-scoped queries rather than impersonating an administrator. Manual retry remains actor-bound. A failed generation leaves the owner eligible for bounded retry without changing the previous Topic snapshot.

`AiTopicOwnerActivityBackfill` scans unassigned supported sources in bounded pages at startup and a fixed interval. It upserts owner activity with each source's event time, making existing installations generate Topics without opening the contact page.

- [ ] **Step 6: Run tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicOwnerActivityServiceTest,WeComSummaryTopicActivityBridgeTest test`

Expected: PASS.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityScheduler.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityBackfill.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/WeComSummaryTopicActivityBridge.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicOwnerActivityMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AiTopicConfig.java \
  demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorker.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerActivityServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/WeComSummaryTopicActivityBridgeTest.java
git commit -m "feat: add owner quiet window scheduling"
```

### Task 3: Mixed-scope Topic input adapters and fingerprints

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceTest.java`

**Interfaces:**
- Add `SourceType.WECOM_SUMMARY`.
- Add `OwnerType.CONTACT` and `OwnerType.WECOM_GROUP`.
- `InputBatch collect(OwnerRef owner, UUID userId, Optional<Instant> after)` returns a bounded, ordered batch.
- `SourceItem` for WeCom has `id=summaryJob.id`, `sourceType=WECOM_SUMMARY`, `channelType="wecom"`, `occurredAt=sendTime`, and `text=summary`.

- [ ] **Step 1: Write failing input tests**

Assert that direct completed summaries are included for a contact, group summaries are excluded from that contact, group collection includes only the selected group, non-terminal/failed/blank summaries are excluded, and fingerprints change when summary text or job ID changes.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceTest test`

Expected: FAIL because owner-aware collection and `WECOM_SUMMARY` do not exist.

- [ ] **Step 3: Implement the owner-aware input contract**

Reuse existing access predicates from `ConversationMapper`, `WeComChatDataMessageMapper` and `WeComMessageSummaryJobMapper`. Every query must include `status='COMPLETED'`, `summary IS NOT NULL`, `btrim(summary) <> ''`, source conversation type, and `NOT EXISTS` on all three `ai_topic_items` source columns.

- [ ] **Step 4: Update canonical fingerprinting**

Prefix canonical input with owner type and owner UUID. Include source type, source ID, event time, channel, subject and text; for WeCom the source ID is the summary job UUID and text is the saved official summary.

- [ ] **Step 5: Run tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceTest test`

Expected: PASS.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceTest.java
git commit -m "feat: add mixed-scope topic inputs"
```

### Task 4: AI generation, strict assignment validation and audit

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParser.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationAuditService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParserTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorkerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceRegressionTest.java`

**Interfaces:**
- `TopicAiGateway.generate(GenerationInput input, GenerationAuditContext audit)` accepts owner-scoped inputs and same-owner `READY` contexts only.
- `TopicAiResponseParser.parse(String json, Set<UUID> allowedSourceIds, boolean incremental)` accepts `WECOM_SUMMARY` IDs exactly like other source IDs while preserving strict one-to-one assignment.
- `AiTopicService.generate(AiTopicGenerationJobEntity job, UUID userId, TopicAiGateway gateway)` applies only sources belonging to the job owner and inserts `wecom_message_summary_job_id` for WeCom items.

- [ ] **Step 1: Add failing mixed-scope gateway/worker tests**

Cover: payload contains `channelType=wecom` with summary text and event time; group jobs never include contact contexts; low relevance creates a new Topic; a stored or other-owner Topic UUID cannot be reused; duplicate/missing/unknown source IDs reject the entire response; audit stores request, raw response, parsed response and validation stage.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=TopicAiResponseParserTest,AiTopicGenerationWorkerTest,AiTopicServiceRegressionTest test`

Expected: FAIL on missing owner/source handling and group constraints.

- [ ] **Step 3: Implement owner-scoped generation**

Use `OwnerRef` in `GenerationInput`, filter existing contexts by exact owner and `READY`, and resolve reuse only when UUID, owner type, owner ID and threshold all match. Insert the first source and Topic in one transaction; delete a newly allocated empty Topic after source uniqueness conflict.

- [ ] **Step 4: Preserve all AI diagnostics**

Keep the existing bounded audit tables and write the actual serialized request, provider response and parsed output at every stage: `PROVIDER_CALL`, `RESPONSE_PARSE`, `RESPONSE_VALIDATE`, `BUSINESS_APPLY`. Redact secrets before persistence.

- [ ] **Step 5: Run tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=TopicAiResponseParserTest,AiTopicGenerationWorkerTest,AiTopicServiceRegressionTest test`

Expected: PASS.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParserTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorkerTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceRegressionTest.java
git commit -m "feat: generate owner-scoped mixed topics"
```

### Task 5: Topic projection, store operations and group approval

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOperationWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOperationScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicInboxRequestMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicStoreApprovalServiceTest.java`

**Interfaces:**
- `TopicTimelineResponse getTopics(UUID userId, UUID contactId)` returns personal `READY` topics plus referenced group `READY` topics, with `isReferencedGroupTopic` and group owner projection.
- `TopicOperationProjection submitStore(UUID userId, UUID topicId, String idempotencyKey)` directly queues personal store; for group topics it creates one pending inbox request.
- `TopicOperationProjection approveStore(UUID adminId, UUID requestId, String idempotencyKey)` queues the actual group store operation.
- `TopicOperationProjection rejectStore(UUID adminId, UUID requestId, String reason)` closes the request and leaves the Topic `READY`.
- `TopicOperationProjection restore(UUID userId, UUID topicId, String idempotencyKey)` changes `STORED` to `READY` asynchronously; group restore requires admin.

- [ ] **Step 1: Write failing service tests**

Cover direct personal store, group request without immediate disappearance, admin approval followed by stored status, rejection, restore authorization, same projection returned to all participating contacts, and no candidate reuse after store.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicStoreApprovalServiceTest test`

Expected: FAIL because store/approval commands and owner-aware projection do not exist.

- [ ] **Step 3: Implement projection queries**

Join contact identities/source participants to find groups visible to the current contact and return each group Topic once. Keep source items intact and project WeCom source as `channelType=wecom`, `sourceType=WECOM_SUMMARY`, and original event time.

- [ ] **Step 4: Implement asynchronous operation semantics**

Replace product-facing discard operation with `STORE`. For groups, persist an approval request first; only an authorized admin can enqueue the `STORE` operation. Worker rechecks owner access, current status and expected version in the transaction, writes `ai_topic_versions`, and publishes the snapshot event once.

- [ ] **Step 5: Run tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicStoreApprovalServiceTest test`

Expected: PASS.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOperationWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOperationScheduler.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicInboxRequestMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicStoreApprovalServiceTest.java
git commit -m "feat: add topic store approval workflow"
```

### Task 6: HTTP API, authorization and repository owner projection

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java` (only if owner display projection is shared here)
- Create/Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerMixedScopeTest.java`
- Modify: `demo/message-center-spring/README.md`

**Interfaces:**
- `GET /api/v1/contacts/{contactId}/topics` returns active personal and referenced group Topics; it never blocks for generation.
- `POST /api/v1/topics/{topicId}/store` returns `202` with operation/request projection.
- `POST /api/v1/topic-inbox/{requestId}/approve` and `/reject` require admin and return `202`.
- `POST /api/v1/topics/{topicId}/restore` returns `202`; group restore requires admin.
- `GET /api/v1/topic-repository?search=&ownerType=&page=&size=` returns paged `STORED` Topics with contact/group owner details.
- `GET /api/v1/topic-inbox/requests` requires admin for pending group approvals.

- [ ] **Step 1: Write failing controller/security tests**

Assert all mutations return `202`, unauthenticated/unauthorized users receive the project’s structured errors, a personal user cannot approve group requests, a group user cannot mutate a referenced read-only projection, and repository results retain owner labels and channel nickname fallback.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicControllerMixedScopeTest test`

Expected: FAIL on new routes and owner-aware authorization.

- [ ] **Step 3: Implement routes and structured errors**

Map request bodies to owner-aware service calls; require `Idempotency-Key` on mutations; preserve the existing authentication filter and contact access checks. Remove product-facing `/discard` route and old unsupported-WeCom response fields.

- [ ] **Step 4: Implement repository query**

Filter only `STORED`, apply contact/group access predicates in SQL, support bounded search/pagination, and return the original owner object. Do not copy message正文 into the repository projection.

- [ ] **Step 5: Update external configuration documentation**

Document `AI_TOPIC_QUIET_WINDOW_SECONDS=360`, owner behavior, WeCom summary prerequisites, store/approval routes, and diagnostic SQL in `demo/message-center-spring/README.md`. Do not document deleted product terms as supported configuration.

- [ ] **Step 6: Run tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicControllerMixedScopeTest test`

Expected: PASS.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerMixedScopeTest.java
git commit -m "feat: expose mixed-scope topic APIs"
```

### Task 7: React mixed timeline, store approval UI and repository

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useTopicTimeline.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useTopicRepository.ts`
- Modify: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TopicRepositoryPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TopicRepositoryPage.test.tsx`

**Interfaces:**
- `TopicProjection` adds `ownerType`, `ownerId`, `ownerLabel`, `isReferencedGroupTopic`, `sourceType='WECOM_SUMMARY'` and `channelType='wecom'` support.
- `ContactTopicsResponse` no longer uses an unsupported-WeCom product branch.
- `useTopicTimeline` exposes `store`, `requestGroupStore`, `retry`; it invalidates only on `topic-snapshot-completed`.
- `useTopicRepository` exposes `restore`, `approve`, `reject` and retains owner projection.

- [ ] **Step 1: Write failing component tests**

Cover: personal Topic and one referenced group Topic render together; group Topic is labeled with its group owner and is read-only; personal store button submits without removing the card; group button says申请入库; admin approval controls render only for admin; collapsed cards preserve title, summary and event time; WeCom source renders exactly `wecom + event time`.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd demo/message-center-spring/frontend && npm run test -- --run src/components/AiTopicTimeline.test.tsx src/pages/TopicRepositoryPage.test.tsx`

Expected: FAIL on missing types/actions/rendering.

- [ ] **Step 3: Implement API types/endpoints/hooks**

Use `202` mutation responses, no optimistic cache updates, no `refetchInterval`, and invalidate the mounted query once on the global SSE event. Keep all source rows in response state even when the source list is collapsed.

- [ ] **Step 4: Implement timeline and repository UI**

Use existing Ant Design Collapse/Popconfirm patterns. Show event time in the collapsed header and render WeCom source labels from `occurredAt`. Add group owner label and a navigation action to the group workspace, while keeping mutation controls out of the referenced contact projection.

- [ ] **Step 5: Run focused tests/build and commit**

Run: `cd demo/message-center-spring/frontend && npm run test -- --run src/components/AiTopicTimeline.test.tsx src/pages/TopicRepositoryPage.test.tsx && npm run build`

Expected: PASS and Vite emits a fresh `dist` bundle containing the mixed Topic labels and store actions.

```bash
git add demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/hooks/useTopicTimeline.ts \
  demo/message-center-spring/frontend/src/hooks/useTopicRepository.ts \
  demo/message-center-spring/frontend/src/components/AiTopicTimeline.tsx \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx \
  demo/message-center-spring/frontend/src/pages/TopicRepositoryPage.tsx \
  demo/message-center-spring/frontend/src/router.tsx \
  demo/message-center-spring/frontend/src/components/AiTopicTimeline.test.tsx \
  demo/message-center-spring/frontend/src/pages/TopicRepositoryPage.test.tsx
git commit -m "feat: show mixed contact and group topics"
```

### Task 8: Full verification, documentation index and release artifacts

**Files:**
- Modify: `docs/superpowers/README.md`
- Modify: `demo/message-center-spring/README.md` if configuration or route details changed during implementation
- Create: `docs/superpowers/reviews/2026-09-01-ai-topic-wecom-mixed-scope-verification.md`
- Generate: `demo/message-center-spring/backend/target/message-center.jar`
- Generate: `demo/message-center-spring/frontend/dist/`

**Interfaces:**
- Verification record lists exact commands, pass/fail output, migration result, AI audit evidence and any unavailable Docker/server gate.
- Release artifacts are generated from source; do not hand-edit `target` or `frontend/dist`.

- [ ] **Step 1: Run backend focused regression**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='*AiTopic*Test,*WeComMessageSummary*Test' test`

Expected: PASS with owner, quiet-window, input, generation, approval and audit tests.

- [ ] **Step 2: Run frontend regression and build**

Run: `cd demo/message-center-spring/frontend && npm run test -- --run && npm run build`

Expected: PASS and `dist/assets` contains the new owner/store/WeCom projection strings.

- [ ] **Step 3: Run full backend test and package**

Run: `cd demo/message-center-spring/backend && mvn -q test && mvn -q -DskipTests package`

Expected: PASS; `target/message-center.jar` is regenerated from the current source.

- [ ] **Step 4: Verify database and runtime contracts**

Run the project’s Flyway migration against an empty schema and a copy of the current schema. Verify SQL for one direct WeCom summary, one group summary, a stored Topic, a pending group approval and a referenced group Topic returned to two contacts. Confirm no source duplication and no sensitive audit fields.

- [ ] **Step 5: Write verification record and update index**

Record exact command output, SHA-256 of the generated Jar/frontend archive if produced, and any unavailable real-server or Docker checks. Add this design and plan to `docs/superpowers/README.md`; mark the older contact-only Topic design as absorbed.

- [ ] **Step 6: Commit documentation only**

```bash
git add docs/superpowers/README.md docs/superpowers/reviews/2026-09-01-ai-topic-wecom-mixed-scope-verification.md
git commit -m "docs: verify mixed-scope ai topics"
```

## Self-Review Checklist

- Spec coverage: Tasks 1-3 cover owner schema, WeCom summary source, direct/group separation and 6-minute timing; Tasks 4-5 cover strict AI assignment, audit, store and admin approval; Tasks 6-7 cover authorized API, repository owner projection and final-snapshot UI; Task 8 covers build, migration and release evidence.
- Placeholder scan: no unfinished or unspecified implementation step is required; every task names files, interfaces, tests and commands.
- Type consistency: `OwnerRef`, `OwnerType`, `WECOM_SUMMARY`, `STORED`, `ai_topic_owner_activity`, and `ai_topic_inbox_requests` are introduced in Task 1-2 and consumed by later tasks.
- Scope: no change is planned for the enterprise WeCom official summary protocol, message正文 storage, vector search, or automatic replies.
