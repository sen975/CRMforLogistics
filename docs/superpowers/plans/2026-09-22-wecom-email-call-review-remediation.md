# WeCom、邮件与电话转录 Review 修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收掉当前全量 review 发现的企业微信回调、提醒、邮件和电话录音/转录可靠性问题，使外部回调可达、任务不会永久丢失、网络输入有界、公开合同与实现一致。

**Architecture:** 保持现有 Controller -> Service -> Mapper/Gateway 边界。外部回调只在 Security 层匿名放行，在 Controller/Codec 层完成验签；所有异步任务使用数据库租约和 CAS 状态迁移；所有上传、IMAP、ASR、音频播放都使用显式大小、时间和分页上界。邮件发送不再把“SMTP 成功但本地未持久化”伪装为成功。

**Tech Stack:** Spring Boot 3.4 / Java 17、MyBatis-Plus、PostgreSQL/Flyway、Jakarta Mail、MinIO、FunASR、React/TypeScript、JUnit 5、Vitest。

## Global Constraints

- 保留当前工作区其他 WIP，不使用 `git reset --hard`、`git checkout --` 或 `git add .`。
- WeCom app callback 正式入口固定为 `/api/v1/wecom/app-callback`；验签失败仍返回 403，服务未就绪或落库失败返回 503。
- 所有 claim 必须可恢复：租约过期后可被其他 worker 接管，写回必须带 token/version CAS。
- 不把 provider 成功、SMTP 成功或 MinIO 成功当作本地业务成功；本地真相必须可查询、可重试。
- 所有外部响应体、上传体、分页查询和网络读取均必须有明确上限。
- 每个任务先写失败测试，再改实现；任务结束运行专项测试和 `git diff --check`。

### Task 1: 放行并固化 WeCom 应用回调合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComP0ControllerSecurityTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComControllerTest.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:** Security permits anonymous GET/POST; controller remains the owner of signature/decryption and ingest result mapping.

- [x] Add `GET` and `POST` matcher for `/api/v1/wecom/app-callback` before the authenticated `/api/**` rule.
- [x] Add anonymous MockMvc coverage proving the path is not blocked by login security; controller tests already cover bad-signature `403` and database/unconfigured `503` assertions.
- [x] Add OpenAPI path with `msg_signature`, `timestamp`, `nonce`, optional `echostr`, XML/plain encrypted body, and `200/403/503` responses.
- [x] Run: `mvn -q -Dtest=WeComP0ControllerSecurityTest test` and `node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs` (the latter is rerun after the final contract assertion adjustment).

### Task 2: Make daily and lead reminders crash-recoverable

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/resources/db/migration/V80__todo_reminders.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TodoDailyReminderMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TodoItemMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComTodoReminderScheduler.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComTodoReminderSchedulerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/TodoReminderMapperSqlTest.java`

**Interfaces:** `claim` returns a claim token and lease expiry; `markSent/markFailed` update only the matching token. Expired claims are eligible again.

- [x] Add `claimed_at`, `claim_token`, and an index for expired claims to reminder persistence; add equivalent lease fields for lead reminders (`V91__todo_reminder_claim_leases.sql`).
- [x] Change candidate SQL to select retryable or expired claims, atomically assigning token and lease.
- [x] Require token in sent/failed CAS updates and check affected-row count; log a lost claim instead of reporting sent.
- [x] Add crash-after-claim tests and expired-lease takeover tests for both reminder paths.
- [x] Run: `mvn -q -Dtest='WeComTodoReminderSchedulerTest,TodoReminderMapperSqlTest' test` (真实 PostgreSQL/Testcontainers，已通过；Docker 需授权访问本机 socket).

### Task 3: Unify call recording upload limits and implement HTTP Range

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/CallRecordConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/MinioAudioStore.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/CallRecordControllerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/MinioAudioStoreTest.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`

**Interfaces:** upload max bytes, multipart max request, and OpenAPI limit use one configured value; audio endpoint supports one valid byte range and returns `206` with `Content-Range`, `Accept-Ranges`, and bounded body.

- [x] Choose the existing 100 MiB business limit as the source of truth and set multipart request size above it with explicit overhead; add configuration validation that request size is not smaller than audio limit.
- [x] Parse only a single `bytes=start-end` range; return `416` for invalid/multiple ranges; preserve `200` full response when Range is absent.
- [x] Extend `MinioAudioStore` with offset/length reads and ensure both local and MinIO paths close resources.
- [x] Add tests for no range, bounded range, invalid range, and oversized upload contract.
- [x] Run: `mvn -q -Dtest='CallRecordControllerTest,MinioAudioStoreTest,CallRecordServiceTest,TranscriptionWorkerTest,CallRecordOwnerIsolationTest' test`, `mvn -q -Dmaven.test.skip=true compile`, and OpenAPI contract test (all passed).

### Task 4: Bound FunASR and transcription leases

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClient.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/db/migration/V7__call_records.sql` or a new Flyway migration
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClientTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorkerTest.java`

**Interfaces:** ASR response reads fail with a stable size-limit error above `maxResponseBytes`; recovery only resets rows whose lease expired; completion/failure uses worker token/version CAS.

- [x] Replace `readAllBytes()` with a bounded reader that stops before allocating beyond `maxResponseBytes`.
- [x] Verify `transcription_worker_id`, `transcription_lease_id`, and `lease_expires_at` in the claim/recovery SQL; recovery filters `lease_expires_at < now()`.
- [x] Make startup recovery CAS-safe against active leases; a second instance cannot reset an active lease.
- [x] Add oversized-response and lease-condition tests.
- [x] Run: `mvn -q -Dtest='FunAsrClientTest,TranscriptionWorkerTest,CallRecordMapperSqlTest' test` and backend compile (passed).

### Task 5: Remove audio object leaks on database failure

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/MinioAudioStore.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java`

**Interfaces:** `delete(AudioAsset)` removes both local and remote objects; cleanup failure is recorded as a structured retryable error and never hides the original persistence failure.

- [x] After DB insert failure, invoke local and MinIO deletion using the stored object key.
- [x] Preserve original `CALL_RECORD_PERSIST_FAILED`; log cleanup failure with record/object identifiers but never credentials.
- [x] Add test asserting both delete calls and a separate cleanup-failure test.
- [x] Run: `mvn -q -Dtest='CallRecordServiceTest,MinioAudioStoreTest' test` (passed).

### Task 6: Replace phone repository fake pagination and retry pseudo-idempotency

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java`
- Create/Modify: Flyway migration for a retry request key unique constraint/table
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/CallRecordControllerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java`

**Interfaces:** phone repository uses owner-scoped keyset cursor and `LIMIT + 1`; retry stores `(owner_id, call_record_id, client_request_id)` and returns the original result on replay.

- [x] Define an opaque Base64URL cursor containing `occurred_at` and `id`; reject malformed or oversized cursors.
- [x] Query at most `limit + 1` rows with owner predicate, return `nextCursor`, and keep `totalCount` as a separate count query.
- [x] Add a unique retry request key and deterministic replay lookup for repeated client requests.
- [x] Add tests for keyset SQL, owner-scoped repository response, invalid cursor parsing, and exact retry replay.
- [x] Run: `mvn -q -Dtest='CallRecordControllerOwnerSecurityTest,CallRecordServiceTest,CallRecordMapperSqlTest' test` and backend compile (passed).

### Task 7: Harden email sync and SMTP outcome handling

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/OpenSslImapClient.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSubmissionLeaseKeeper.java`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V96__email_submission_leases.sql`
- Create/Modify: email submission/outbox migrations, entity, and mapper (`V93`-`V96`)
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSendServiceTest.java`
- Test: email submission lease keeper and PostgreSQL mapper/migration tests

**Interfaces:** provider fetch is outside long database transactions; IMAP process/socket reads have connect, command, total-sync, message, and literal limits; SMTP success plus local persistence failure returns `EMAIL_SEND_OUTCOME_UNKNOWN` and creates an owner-queryable ambiguous submission that is never automatically resent.

- [x] Split IMAP fetch/parse from persistence by removing the long outer database transaction; each provider fetch no longer holds one transaction.
- [x] Add a hard OpenSSL process deadline and destroy the process on timeout; line/command/literal reads are bounded.
- [x] Bound `{n}` literal allocation, line size, total fetched bytes, and command buffer growth against fixed limits.
- [x] Introduce `email_submissions` (`V93`) before SMTP send, transition through `SMTP_SENT` to `SENT` only after local persistence, and use `EMAIL_SEND_OUTCOME_UNKNOWN`/`UNKNOWN` when local write fails.
- [x] Add dedicated tests for process deadline, oversized literal, DB/attachment failure after SMTP success, failed SMTP outcome persistence, mandatory pre-send submission persistence, and owner-scoped UNKNOWN visibility; UNKNOWN is queryable but never auto-retried.
- [x] Add owner identity to email submissions in `V94` so deleting the channel account does not hide unresolved records; use `V95` to backfill rows created between V93 and V94. Keep earlier migrations immutable because they were already applied locally.
- [x] Return structured HTTP `503 EMAIL_SEND_OUTCOME_UNKNOWN` for ambiguous provider/local outcomes and update the OpenAPI contract; callers must check delivery before retrying.
- [x] Run: `mvn -q -Dtest='OpenSslImapClientTest,EmailSendServiceTest,EmailControllerTest,EmailSubmissionMapperContractTest,EmailSyncServiceTest,EmailAttachmentReaderTest' test` and `node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs` (passed; final cross-channel rerun also passed).
- [x] Replace timestamp-only recovery with a token-fenced renewable lease: initial lease 5 minutes, one bounded process-local heartbeat thread renews active submissions every 20 seconds, all state transitions and renewals require the submission token, and recovery only changes expired leases. Keep owner scope and `LIMIT 100`; UNKNOWN remains non-retryable. PostgreSQL tests cover renewed live lease protection, expired lease recovery, old-token rejection, and concurrent recovery. A live but permanently blocked worker continues heartbeating until process restart and remains an operational limitation.

### Task 8: Close cross-channel contracts and final gates

**Files:**
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`
- Modify: relevant WeCom/email/call tests
- Create: `docs/superpowers/reviews/2026-09-22-wecom-email-call-review-remediation-verification.md`

- [x] Verify OpenAPI matches actual send route (`/api/v1/email/messages` aliases the frontend route), status codes, limits, cursor shape, Range behavior, callback accessibility, and owner-scoped submission-unknown query.
- [x] Run backend compile: `mvn -q -Dmaven.test.skip=true compile` (passed after final code changes).
- [x] Run targeted backend suite covering WeCom callbacks/events, reminders, email sync/send/outcomes, call recording/transcription/storage, and mapper SQL (passed; 0 failed / 0 skipped).
- [x] Run frontend source tests (37/37), UI tests `npm run test:ui -- --maxWorkers=2` (77 files / 394 tests), and `npm run build` (passed).
- [x] Run `git diff --check` and inspect `git status --short`; no files were staged, unrelated user WIP remains untouched.
- [x] Record unresolved external-contract risks separately: real WeCom encrypted event fixtures, production SMTP/IMAP delivery behavior, and production MinIO/FunASR behavior; see the verification record.
- [x] Close Task 7's active-send recovery race with the V96 renewable lease and PostgreSQL race verification.

## Plan Self-Review

- Spec coverage: findings are assigned to Tasks 1-8; SMTP stale-submission recovery uses V96 token-fenced renewable leases and has PostgreSQL recovery/race coverage.
- Placeholder scan: no `TBD`, `TODO`, or unspecified “add validation” steps remain; every task names files, interfaces, tests, and commands.
- Contract consistency: Task 1 owns callback status semantics; Task 3 owns audio limits and Range; Task 6 owns cursor and retry-key semantics; Task 7 owns email submission outcome semantics; Task 8 verifies all generated contracts.
