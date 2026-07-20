# Message Center PostgreSQL/MinIO 完整闭环实施计划

> **执行规则：** 仅在模块化迁移计划 Task M0–M9 全部通过后执行。用户已停用除头脑风暴和 plan 之外的自动 skill；执行时不得自动触发其他 skill。

**Goal:** 在新的 Spring Boot/React 模块结构下完成事件 inbox、事务 outbox、MinIO 附件、渠道 adapter、一次性导入、完整 API/UI、备份恢复和 8100 最终验收。

**Architecture:** PostgreSQL 保存全部业务与队列状态，MinIO 保存全部二进制对象。入站渠道只写事件 inbox，出站 API 只写 pending message 和 outbox；worker 有界消费。React 只通过 OpenAPI v1 client 和 SSE 访问后端。

**Tech Stack:** Spring Boot 3、Spring MVC、Spring Security、PostgreSQL 17.5、Flyway、MinIO、React 19、TanStack Query、Ant Design 5、Testcontainers、Playwright、Docker Compose。

## Global Constraints

- 前置条件：`docs/superpowers/plans/2026-07-20-message-center-modular-migration.md` 完成。
- 所有路径以 `demo/message-center-demo/backend` 和 `frontend` 为准；禁止回到旧扁平 package。
- PostgreSQL/MinIO 是唯一运行真源；JSONL 只允许显式一次性导入。
- 外部渠道 adapter 不直接创建联系人、消息、权限、未读或前端状态。
- worker 批次、lease、重试、退避、超时、对象大小和并发必须有上限。
- opaque session、CSRF、会话权限和审计边界不得绕过。
- 禁止 `git add .`、reset、checkout；禁止打印真实 secret 或完整签名 URL。
- 自动 Web 测试只用 8100，完成后关闭；8099 不用于自动化。
- 每个任务一个独立提交和独立验收记录。

---

## Task C1: 原始事件 Inbox、事务 Outbox 与有界 Worker

**Files:**
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/event/ChannelEvent.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/event/ChannelEventDraft.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/event/EventWriteResult.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/event/QueueStatus.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/event/EventRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/OutboxMessage.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/OutboxStatus.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/DispatchResult.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/SubmissionQueryResult.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/ProcessingFailure.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/domain/outbox/OutboxRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/SendMessageCommand.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/MessageCommandService.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/ChannelEventWorker.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/OutboxWorker.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/port/ChannelEventProjector.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/port/OutboundDispatcher.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/infrastructure/persistence/JdbcEventRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/infrastructure/persistence/JdbcOutboxRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/shared/security/SensitiveValueRedactor.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/messaging/EventOutboxConcurrencyIT.java`

**Interfaces:**

```java
public record ChannelEvent(
        UUID id, UUID channelAccountId, String providerEventId,
        String eventType, Instant occurredAt, String payloadJson,
        String payloadHash, int attemptCount) {}

public record ChannelEventDraft(
        UUID channelAccountId, String providerEventId, String eventType,
        Instant occurredAt, String payloadJson, String payloadHash) {}

public record SendMessageCommand(
        UUID userId, UUID conversationId, UUID channelAccountId,
        String clientRequestId, String messageKind, String subject,
        String bodyText, List<UUID> attachmentIds) {}

public record OutboxMessage(
        UUID jobId, UUID messageId, UUID channelAccountId,
        String channelType, String clientRequestId) {}

public record ProcessingFailure(String code, String message, boolean retryable) {}

public enum QueueStatus { RECEIVED, PROCESSING, RETRY_WAIT, PROCESSED, DEAD }
public enum OutboxStatus { PENDING, PROCESSING, RETRY_WAIT, COMPLETED, DEAD }

public interface EventRepository {
    EventWriteResult ingest(ChannelEventDraft draft) throws Exception;
    List<ChannelEvent> claimEvents(String leaseOwner, int limit, Duration lease) throws Exception;
    void complete(UUID eventId, String leaseOwner) throws Exception;
    void fail(UUID eventId, String leaseOwner, ProcessingFailure failure, Instant nextAttemptAt) throws Exception;
}

public interface OutboxRepository {
    List<OutboxMessage> claimOutbox(String leaseOwner, int limit, Duration lease) throws Exception;
    void complete(UUID jobId, String leaseOwner, DispatchResult result) throws Exception;
    void retry(UUID jobId, String leaseOwner, ProcessingFailure failure, Instant nextAttemptAt) throws Exception;
}

public interface OutboundDispatcher {
    DispatchResult dispatch(OutboxMessage message) throws Exception;
    SubmissionQueryResult querySubmission(OutboxMessage message) throws Exception;
}

public interface ChannelEventProjector {
    void project(ChannelEvent event) throws Exception;
}

public record EventWriteResult(UUID eventId, boolean inserted) {}
public record DispatchResult(String outcome, String providerMessageId, String status,
                             String code, String message, boolean retryable) {}
public record SubmissionQueryResult(String outcome, String providerMessageId,
                                    String status, String code, String message) {}
```

- [ ] **Step 1: Recover only reusable Task 7 evidence**

Inspect `/private/tmp/CRMforLogistics-message-center-task7` read-only. Copy algorithms/tests into the new files above; do not cherry-pick the old package layout and do not copy generated or build artifacts.

- [ ] **Step 2: Write concurrent claim RED tests**

Two threads claim the same event and outbox pool. Assert disjoint IDs, total claimed count equals inserted count, duplicate provider event ID/payload hash creates one event, and duplicate client request ID creates one pending message/outbox job.

- [ ] **Step 3: Implement bounded `SKIP LOCKED` claims**

```sql
WITH candidates AS (
  SELECT id FROM outbox_jobs
  WHERE status IN ('pending', 'retry_wait')
    AND next_attempt_at <= now()
    AND (lease_until IS NULL OR lease_until < now())
  ORDER BY next_attempt_at, created_at
  FOR UPDATE SKIP LOCKED
  LIMIT ?
)
UPDATE outbox_jobs job
SET status='processing', lease_owner=?, lease_until=?, updated_at=now()
FROM candidates
WHERE job.id=candidates.id
RETURNING job.*
```

Event claims use the same ordering and lease rules. Limits come from validated configuration and never exceed 100.

- [ ] **Step 4: Implement send transaction**

`MessageCommandService.queue` performs conversation send authorization, pending message insertion, initial status event, unique outbox insertion and audit in one Spring transaction. It never calls external channels.

- [ ] **Step 5: Implement retry and unknown submission**

`UNKNOWN_SUBMISSION` sets message status `submission_unknown` and job status `retry_wait`. The next action is provider query/callback reconciliation. A channel without stable `clientRequestId` cannot automatically resend.

- [ ] **Step 6: Verify and commit**

```bash
cd demo/message-center-demo/backend
mvn -q -Dtest=EventOutboxConcurrencyIT test
mvn -q test-compile
git diff --check
git commit -m "feat: add transactional event and outbox workers"
```

---

## Task C2: MinIO 附件真源与授权媒体流

**Files:**
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/domain/Attachment.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/domain/AttachmentRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/domain/ObjectStorage.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/domain/StoredObject.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/domain/StoredObjectStream.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/application/AttachmentService.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/application/AttachmentIngestWorker.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/infrastructure/JdbcAttachmentRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/media/infrastructure/MinioObjectStorage.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/media/web/AttachmentController.java`
- Delete after cutover: legacy OSS/CAMS request-time fallback code.
- Test: `media/AttachmentStorageIT.java`, `media/AttachmentAuthorizationWebIT.java`

**Interfaces:**

```java
public interface ObjectStorage {
    StoredObject put(String objectKey, InputStream content, long length, String contentType) throws Exception;
    StoredObjectStream get(String objectKey) throws Exception;
    void delete(String objectKey) throws Exception;
}

public record StoredObject(String objectKey, long length, String contentType, String sha256) {}

public record StoredObjectStream(InputStream content, long length, String contentType)
        implements AutoCloseable {
    @Override public void close() throws IOException { content.close(); }
}

public record Attachment(
        UUID id, UUID messageId, String objectKey, String originalFileName,
        String contentType, long sizeBytes, String sha256, String storageStatus) {}
```

- [ ] **Step 1: Write MinIO and authorization RED tests**

Upload a bounded fixture, assert SHA-256 and metadata, download through authorized conversation access, reject unrelated admin, reject oversized input before storage, and verify missing objects return structured `ATTACHMENT_UNAVAILABLE`.

- [ ] **Step 2: Implement object storage and repository**

Use deterministic object keys `attachments/{yyyy}/{MM}/{attachmentId}`. Never expose bucket credentials or presigned URLs to the browser. Store metadata and object status transactionally around worker state.

- [ ] **Step 3: Implement ingest worker**

External URLs are downloaded only by the attachment worker with connect/read timeout, maximum size, allowed content-type policy and bounded concurrency. The worker streams to MinIO while hashing and never buffers unbounded content.

- [ ] **Step 4: Implement authorized stream**

`AttachmentService.open` loads attachment → message/conversation → `ConversationAccessService.requireSensitiveRead` → audit → MinIO stream. Controller sets safe `Content-Disposition` and does not infer HTML from untrusted filename.

- [ ] **Step 5: Remove request-time OSS fallback**

```bash
rg -n 'generatePresignedUrl|oss|mediaUrl' backend/src/main/java/com/crmforlogistics/messagecenter/media
```

Expected: only explicit import/ingest adapter references; no controller/request-time fallback.

- [ ] **Step 6: Verify and commit**

```bash
mvn -q -Dtest=AttachmentStorageIT,AttachmentAuthorizationWebIT test
git diff --check
git commit -m "feat: store authorized attachments in minio"
```

---

## Task C3: Email、ChatApp 与 WeCom Adapter 接入 Inbox/Outbox

**Files:**
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/application/port/InboundChannelAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/application/port/OutboundChannelAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/application/port/FetchResult.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/infrastructure/ImapInboundAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/infrastructure/SmtpOutboundAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/infrastructure/CamsInboundAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/infrastructure/CamsOutboundAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/infrastructure/CamsSubmissionQueryAdapter.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/infrastructure/WeComInboundAdapter.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/channel/ChannelAdapterContractTest.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/channel/ChannelSyncIT.java`

**Interfaces:**

```java
public interface InboundChannelAdapter {
    FetchResult fetch(ChannelAccount account, SyncCursor cursor, int limit) throws Exception;
}

public interface OutboundChannelAdapter extends OutboundDispatcher {
    boolean supports(String channelType);
}

public record FetchResult(
        List<ChannelEventDraft> events, String nextCursor,
        Instant cursorTimestamp, boolean hasMore) {}
```

- [ ] **Step 1: Write adapter contract RED tests**

Assert inbound adapters return `ChannelEventDraft` without writing messages; outbound adapters receive `OutboxMessage`; failures return fixed code, sanitized message and retryable flag; secret arrays/maps are cleared after calls.

- [ ] **Step 2: Refactor Email sync/send**

IMAP fetch emits provider ID, occurred time, participants, body and attachment descriptors into event inbox. SMTP dispatch updates outbox result only; it does not write JSONL or UI projections.

- [ ] **Step 3: Refactor ChatApp sync/send**

CAMS history and webhook both ingest through the same idempotency key rules. Media URLs become attachment ingest descriptors. Dispatch uses stable client request ID and unknown-submission query before retry.

- [ ] **Step 4: Implement WeCom boundary**

WeCom read-only collection decrypts callback, validates `kf_msg_or_event`, uses Token/OpenKfId/cursor to call sync, stops on `has_more=false`, persists `next_cursor`, and emits only supported first-phase customer text events. Sending remains disabled until a separate approved contract exists.

- [ ] **Step 5: Verify runtime owner scans**

```bash
rg -n 'Files\.(write|writeString).*jsonl|StandardOpenOption\.APPEND' backend/src/main/java
```

Expected: no runtime adapter JSONL writes.

- [ ] **Step 6: Verify and commit**

```bash
mvn -q -Dtest='*AdapterTest,*SyncIT,*OutboxIT' test
git diff --check
git commit -m "feat: route channel adapters through event pipelines"
```

---

## Task C4: 一次性旧数据导入与对账

**Files:**
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/application/LegacyImportService.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/application/ImportReconciliationService.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/domain/ImportBatch.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/domain/ImportResult.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/domain/ImportSource.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/domain/LegacyRecord.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/infrastructure/legacy/JsonlLegacySource.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/importjob/infrastructure/legacy/LegacyMediaSource.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/bootstrap/cli/LegacyImportCommand.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/importjob/LegacyImportIT.java`

**Interfaces:**

```java
public record ImportResult(
        UUID batchId, long sourceCount, long insertedCount,
        long updatedCount, long skippedCount, long failedCount,
        String status) {}

public interface ImportSource extends AutoCloseable {
    String identifier();
    String sha256() throws Exception;
    long sourceCount() throws Exception;
    Stream<LegacyRecord> records() throws Exception;
}

public record LegacyRecord(
        String sourceType, String sourceId, String payloadJson,
        Instant occurredAt, Path mediaPath) {}
```

- [ ] **Step 1: Write repeatable import RED test**

Import a bounded fixture twice. First run inserts contacts/messages/attachments; second run skips identical records. Assert source hash, batch counts, identity mapping, message idempotency, attachment SHA-256 and no runtime bean references legacy sources.

- [ ] **Step 2: Implement explicit CLI-only command**

Command requires source path arguments, verifies readable regular files, computes source SHA-256 before mutation, creates `data_import_batches`, and exits non-zero on failed reconciliation. It does not start web controllers or workers.

- [ ] **Step 3: Implement identity/message/media mapping**

Normalize identities with current domain rules. Provider IDs and payload hashes drive idempotency. Historical inbound messages set `counts_as_unread=false`. Media cache files stream into MinIO with hash validation.

- [ ] **Step 4: Implement reconciliation**

Compare source and target counts by contacts, identities, conversations, messages and attachments. Sample object hashes. Any unexplained mismatch marks the batch failed and blocks JSONL retirement.

- [ ] **Step 5: Verify and commit**

```bash
mvn -q -Dtest=LegacyImportIT test
rg -n 'importjob\.infrastructure\.legacy' backend/src/main/java --glob '!**/importjob/**'
git diff --check
git commit -m "feat: add explicit legacy data import"
```

Expected: test PASS; dependency scan has no output.

---

## Task C5: 完整 OpenAPI、Spring MVC 与 React 功能接线

**Files:**
- Modify: `contracts/openapi/message-center-v1.yaml`
- Modify: backend contact/company/messaging/channel/media controllers created in Task M7.
- Create/modify: `backend/src/main/java/com/crmforlogistics/messagecenter/contact/application/ContactApplicationService.java`
- Create/modify: `backend/src/main/java/com/crmforlogistics/messagecenter/company/application/CompanyApplicationService.java`
- Create/modify: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/MessageQueryService.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/messaging/application/MessageCommandService.java`
- Create/modify: `backend/src/main/java/com/crmforlogistics/messagecenter/channel/application/ChannelSyncService.java`
- Modify: `frontend/src/features/auth`, `contact-profile`, `message-send`, `channel-sync`, `media-preview`
- Modify: `frontend/src/pages/MessageCenterPage.tsx` and message-center widgets.
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/web/MessageCenterApiContractIT.java`
- Test: `frontend/e2e/message-center.spec.ts`

**Interfaces:**
- Produces complete login, contacts, companies, timeline, sending, unread, sync, attachment and SSE user flows.

- [ ] **Step 1: Write backend contract RED tests**

Cover login/logout/session, contact cursor, company details, conversation timeline, send pending result, read cursor, sync result, attachment content, permission denial and SSE envelope. Every error asserts `code/message/traceId/fieldErrors`.

- [ ] **Step 2: Complete application/query services**

Controllers call application use cases only. Contact list and timeline return user-scoped projections. Send returns pending message immediately. Sync endpoints enqueue/fetch bounded work and return visible structured status.

- [ ] **Step 3: Complete React features**

Use generated client hooks/wrappers for auth, contacts, company profiles, timeline, send, sync and media. TanStack Query owns server state. SSE invalidates precise keys. Keep unread badges, profile/message-detail switching, account selection, composer behavior, emoji picker and media preview.

- [ ] **Step 4: Write frontend tests**

Vitest/RTL covers loading, empty, error, denied, stale event and reconnect states. Playwright covers login, three-pane navigation, sending, unread clear, sync feedback, permission denial and attachment preview.

- [ ] **Step 5: Verify 8100 and close**

```bash
MESSAGE_CENTER_PORT=8100 docker compose up -d --build backend frontend
cd frontend && npx playwright test
cd .. && docker compose stop frontend backend
lsof -nP -iTCP:8100 -sTCP:LISTEN
```

Expected: Playwright PASS; final `lsof` has no output.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: connect message center api and react ui"
```

---

## Task C6: Images, Migration Service, Backup/Restore, Docs and Final Gate

**Files:**
- Finalize `backend/Dockerfile`, `frontend/Dockerfile`, `compose.yaml`.
- Create: `scripts/backup.sh`, `scripts/restore-verify.sh`.
- Modify: PowerShell launcher, README, config example, PRD, architecture/data specs and master roadmap.

**Interfaces:**
- Produces services `db-migrate`, `backend`, `frontend`, on-demand `backup` and restore verification tooling.

- [ ] **Step 1: Add migration service**

`db-migrate` uses backend image with web disabled, runs Flyway and exits. Backend depends on migration completed successfully plus PostgreSQL/MinIO health.

- [ ] **Step 2: Implement joint backup**

`backup.sh` creates one UTC batch directory containing PostgreSQL custom-format dump, MinIO mirror and SHA-256 manifest. It reads secrets from mounted files, never echoes values, and fails atomically if either data source fails.

- [ ] **Step 3: Implement isolated restore verification**

`restore-verify.sh` restores into a new database and bucket, verifies users, contacts, companies, messages, attachments, references and sampled object hashes, then removes only its isolated verification resources.

- [ ] **Step 4: Run complete gates**

```bash
cd demo/message-center-demo/backend && mvn -q verify
cd ../frontend && npm ci && npm run generate:api && npm run lint && npm run typecheck && npm test && npm run build
cd .. && docker compose config --quiet
```

- [ ] **Step 5: Run prohibited-path scans**

```bash
rg -n 'com\.sun\.net\.httpserver|pageHtml\(|/api/contacts|Files\.(write|writeString).*jsonl|StandardOpenOption\.APPEND' backend frontend
rg -n -i 'chatwoot|redis|rocketmq|elasticsearch|pgvector' .
```

Expected: first scan no output. Second scan only finds explicit non-goal statements in approved docs.

- [ ] **Step 6: Run final 8100 acceptance and close**

Start full Compose on 8100, run Playwright and API checks, verify PostgreSQL/MinIO health, migration exit, backup and restore verify. Stop user-facing services and confirm 8100 has no listener.

- [ ] **Step 7: Final Git boundary and commit**

```bash
git status --short
git diff --check
git diff --cached --name-only
```

Stage only Task C6 files and commit:

```bash
git commit -m "build: complete message center deployment"
```

## Completion Criteria

- Tasks C1–C6 each have an isolated commit and recorded verification.
- PostgreSQL and MinIO are the only runtime truth sources.
- Event/outbox workers are bounded, idempotent and recoverable.
- All sensitive reads and mutations are authorized and audited.
- OpenAPI, backend, frontend, Compose, backup/restore and 8100 E2E gates pass.
- No automated process used 8099.
- No secret or complete signed URL appears in output or repository artifacts.
