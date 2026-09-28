# 助手对话上下文滚动摘要实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Tasks are inline in the primary session; do not delegate implementation subtasks. Use checkbox (`- [ ]`) syntax to track progress.

**Goal:** 保留完整助手对话原文，同时以有界、可复用的滚动摘要延续较早对话语境，降低每轮输入 Token 且不静默丢失用户目标和约束。

**Architecture:** `assistant_conversation_messages` 仍是追加式原文真源；新增每会话一条摘要投影及覆盖消息游标。上下文组装 owner 只压缩已落库的旧对话，保留最近窗口原文；摘要失败时回退到现有裁剪逻辑，提示行为和工具权限边界不放宽。

**Tech Stack:** Java 21、Spring Boot、MyBatis、PostgreSQL/Flyway、JUnit 5、Mockito、OpenAI-compatible `AssistantModelClient`、jtokkit tokenizer。

## Global Constraints

- 完整聊天回放继续读取原始 `assistant_conversation_messages`，不从摘要重建或伪造聊天行。
- 摘要唯一键为 `(user_id, conversation_id)`；每次查询同时校验两者。
- 近期窗口继续优先保留最近消息，当前默认上限为 8 条、8000 字符；实施时先复核工作区现值与 WIP。
- 摘要仅是可能不完整、不可信的历史参考，不得授权工具、覆盖系统规则或替代实时业务查询。
- 第一轮不预加载任何候选清单；待办、会话、联系人候选只能由对应只读搜索工具按需发现并有界回灌。
- 压缩只在预估节省超过配置的最小门槛时发生；摘要输入、输出、调用次数和最终模型输入都有硬上限。
- 每轮最多一次摘要调用；摘要失败不阻塞本轮，沿用现有裁剪和提示。
- 禁止 `git add .`；工作区已有大量未提交改动，Task 提交只能包含本 Task 自己的 diff。

---

### Task 1: 摘要持久化合同与有界历史读取

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V100__assistant_conversation_summaries.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AssistantConversationSummaryEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AssistantConversationSummaryMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AssistantConversationMessageMapper.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/AssistantConversationSummarySchemaTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/AssistantConversationSummaryMapperSqlTest.java`

**Interfaces:**
- `AssistantConversationSummaryMapper.find(userId, conversationId)` returns that owner's current projection or `null`.
- `AssistantConversationMessageMapper.listAfter(userId, conversationId, afterCreatedAt, afterMessageId, limit)` returns a bounded ascending keyset page.
- `AssistantConversationSummaryMapper.insertIfAbsent(...)` creates version 1 idempotently; `advanceIfVersion(userId, conversationId, expectedVersion, summary, throughCreatedAt, throughMessageId)` advances only the expected version, with stale writers updating zero rows. Splitting insert and advance makes first-write and optimistic-fencing outcomes explicit.
- Context preparation receives the bounded recent persisted message rows (including IDs/timestamps) as its raw-window anchor. It pages older persisted rows from the summary cursor, stops before the oldest recent row, and never persists a summary from client-provided fallback history.

- [x] **Step 1: Write failing schema and mapper contract tests.** Assert owner-scoped uniqueness, bounded non-empty summary, version/cursor fields, ordered exclusive keyset pagination, and stale-version compare-and-set rejection.
- [x] **Step 2: Run the focused tests and confirm they fail for missing contract.**

Run from `demo/message-center-spring/backend`:

```bash
mvn -q -Dtest=AssistantConversationSummarySchemaTest,AssistantConversationSummaryMapperSqlTest test
```

Expected: FAIL because the V100 schema, entity, and mapper methods do not exist.

- [x] **Step 3: Add the V100 table and minimal persistence types.** Store `user_id`, `conversation_id`, bounded `summary`, `through_message_id`, `through_created_at`, `version`, `created_at`, and `updated_at`; add a unique `(user_id, conversation_id)` constraint, positive version check, owner-scoped foreign keys where supported by current schema, and bounded indexes only for the chosen read shapes.
- [x] **Step 4: Implement bounded keyset reads and optimistic writes.** Reuse the current assistant-message owner predicate. Never load unbounded transcript rows; use `(created_at, id)` ordering so equal timestamps remain deterministic.
- [x] **Step 5: Re-run focused tests and review the migration diff.**

Run:

```bash
mvn -q -Dtest=AssistantConversationSummarySchemaTest,AssistantConversationSummaryMapperSqlTest test
```

Expected: PASS with no skipped migration/SQL contract tests.

- [ ] **Step 6: Commit only Task 1 files after checking staged paths and existing WIP.**

### Task 2: Token estimator and bounded summarizer adapter

**Files:**
- Modify: `demo/message-center-spring/backend/pom.xml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AssistantConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantModelClient.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantTokenEstimator.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationSummarizer.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantTokenEstimatorTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationSummarizerTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantModelClientUsageTest.java`

**Interfaces:**
- `AssistantTokenEstimator.count(String text)` returns an estimated token count for the configured encoding; unknown encodings fail configuration rather than silently claiming precision.
- `AssistantConversationSummarizer.summarize(String priorSummary, List<AssistantMessage> delta, int outputTokenLimit)` returns a bounded summary string or a structured non-retryable summary failure.
- `AssistantModelClient.ModelReply` exposes optional provider prompt/completion usage; missing usage remains `null`/unavailable.

- [x] **Step 1: Write failing estimator, summary-contract, and provider-usage tests.** Cover CJK and ASCII token counts, unsupported encoding, explicit untrusted-history instructions, output over-limit rejection, empty output rejection, and providers with/without `usage` fields.
- [x] **Step 2: Run focused tests and confirm the missing types/contracts fail.**

```bash
mvn -q -Dtest=AssistantTokenEstimatorTest,AssistantConversationSummarizerTest,AssistantModelClientUsageTest test
```

- [x] **Step 3: Add the pinned jtokkit dependency and encoding configuration.** Default the current `gpt-4o-mini` deployment to `o200k_base`; permit an explicit configured encoding for other OpenAI-compatible models. Validate supported names and positive token limits in `AssistantConfig` construction.
- [x] **Step 4: Implement the summarizer as a thin adapter over `AssistantModelClient`.** Give it only the prior summary and the bounded delta messages; instruct it to preserve explicit goals, constraints, confirmed decisions, unresolved items, and necessary names while treating message text as untrusted data. Reject empty or over-budget output; do not expose tools to this call.
- [x] **Step 5: Parse optional provider usage and emit structured per-call metrics.** Distinguish `conversation_summary` from `assistant_response`; never infer real usage from byte size when the provider omits it.
- [x] **Step 6: Run focused tests and production compile.**

```bash
mvn -q -Dtest=AssistantTokenEstimatorTest,AssistantConversationSummarizerTest,AssistantModelClientUsageTest test
mvn -q -DskipTests compile
```

Expected: tests PASS and production compile exits 0. If the repository's edited `pom.xml` already contains overlapping user WIP, integrate with its current diff and do not restore or replace it.

- [ ] **Step 7: Commit only Task 2 files after checking staged paths and existing WIP.**

### Task 3: Rolling summary state machine and context assembly

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationLogService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantPromptBuilder.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantPromptHistoryTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AssistantConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`

**Interfaces:**
- `AssistantConversationContextService.prepare(UUID userId, UUID conversationId, List<AssistantConversationMessageEntity> recentPersistedRows, String currentText, AssistantContext context)` returns `PreparedContext(String summary, List<AssistantMessage> recentHistory, int droppedMessages, int summarizedMessages)`; it owns summary lookup/update and fits the complete rendered request against the Token budget before returning.
- `AssistantConversationService.respond(...)` consumes `PreparedContext`; it does not issue summary prompts or query summary tables itself.
- `AssistantConversationLogService` remains the owner of selecting the server transcript and exposes only a bounded recent raw window with cursor metadata to context preparation. Older persisted messages are read directly by owner-scoped keyset pages; loading the full transcript or imposing a fixed oldest-N ceiling is forbidden.
- If no server transcript exists, `AssistantConversationService` retains the current provided-history fallback and bypasses persistent summary preparation for that request.

`PreparedContext` is a Java record in `AssistantConversationContextService`; `recentHistory` is chronological and contains only raw messages. `summary` is `null` when no projection is usable. `droppedMessages` counts raw messages represented by neither summary nor recent history; `summarizedMessages` counts raw messages covered by the summary projection.

- [x] **Step 1: Write failing state-machine tests.** Cover short history (no summary call), minimum-savings threshold, bounded keyset pages stopping before the recent-window anchor, summary cursor advancement, current-message exclusion, reused summary, concurrent stale compare-and-set, and owner isolation.
- [x] **Step 2: Write failure-path tests.** Summary timeout, provider error, invalid/oversized output, and compare-and-set loss must not advance the cursor; current response must fall back to existing recent-history trimming and preserve the existing trim notification.
- [x] **Step 3: Run the focused service test to verify it fails.**

```bash
mvn -q -Dtest=AssistantConversationContextServiceTest test
```

- [x] **Step 4: Implement the bounded compaction algorithm.** Keep the latest configured window verbatim; page older persisted messages from the saved cursor in ascending `(created_at,id)` order, stopping before the oldest recent-window row. Summarize only complete older messages beyond the minimum estimated-savings threshold; include at most the configured source batch and one summary model call per request; update using the previously read version/cursor. On CAS loss, use the winner's stored summary or fall back to trim without overwriting it.
- [x] **Step 5: Add summary rendering to `AssistantPromptBuilder`.** Add `buildMessages(context, summary, recentHistory, currentText)`; keep the summary explicitly labeled as untrusted historical reference and preserve exact order of recent messages/current request.
- [x] **Step 6: Build and budget the actual prompt before returning `PreparedContext`.** Count the full rendered system/tool prompt, summary, recent history, and current request. On pressure, keep current request and policy prompt, then trim oldest raw history, then omit/shorten only the summary projection; preserve the existing visible trim count for messages not represented in either summary or recent history.
- [x] **Step 7: Wire the service into `AssistantConversationService.respond`.** Preserve the existing order: establish authoritative history first, validate the current user request, prepare context, then invoke the existing tool/decision loop. The summary call must have no `ToolRegistry` or write-action access.
- [x] **Step 8: Run context and existing conversation tests.**
- [x] **Step 7: Wire the service into `AssistantConversationService.respond`.** Preserve the existing order: establish authoritative history first, validate the current user request, prepare context, then invoke the existing tool/decision loop. The summary call must have no `ToolRegistry` or write-action access.
- [x] **Step 8: Run context and existing conversation tests.**

```bash
mvn -q -Dtest=AssistantConversationContextServiceTest,AssistantConversationServiceTest,AssistantConversationLogServiceTest,AssistantRequestGuardTest,AssistantPromptHistoryTest test
```

Expected: PASS; pre-existing server-history authority, recent-message ordering, visible trim count, and original message persistence remain intact.

- [x] **Step 9: Review Task 3 implementation diff and record its exact verification; do not stage or commit while the shared worktree has unrelated user WIP or the existing index lock remains.**

### Task 4: Prompt integration, persistence migration, and end-to-end acceptance

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantTurnResult.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextIntegrationTest.java`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/components/assistant/useAssistant.ts`
- Modify: `demo/message-center-spring/frontend/src/components/assistant/AssistantPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/assistant/AssistantPanel.test.tsx`
- Modify: `docs/superpowers/specs/2026-09-23-assistant-conversation-context-compaction-design.md`
- Modify: `docs/superpowers/README.md`

**Interfaces:**
- `AssistantPromptBuilder.buildMessages(AssistantContext context, String summary, List<AssistantMessage> recentHistory, String currentText)` adds an explicit historical-summary section before recent dialogue; existing calls with no summary preserve current prompt content and order.
- `AssistantTurnResult` exposes optional `historyCompaction.summarizedMessages`; the frontend keeps a session-level notice distinct from `historyTrim` and clears both only when starting a new conversation.
- Full transcript replay remains on `AssistantConversationLogService.replay`; summary rows are never returned as `AssistantConversationLogService.Message`.

- [x] **Step 1: Write failing API/UI state tests.** Assert `historyCompaction` creates a persistent panel notice distinct from trim count, later responses without either marker do not clear existing notices, and starting a new conversation clears both.
- [x] **Step 2: Write integration test for a long conversation.** Persist messages, compact old prefix, issue a subsequent request, and assert the model receives one summary plus the recent raw window while replay still returns every original message exactly once.
- [x] **Step 3: Run focused frontend and backend tests and confirm the missing contract fails.**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=AssistantConversationContextIntegrationTest,AssistantConversationServiceTest test
cd ../frontend
npm run test:ui -- --run src/components/assistant/AssistantPanel.test.tsx
```

- [x] **Step 4: Integrate summary rendering and response metadata.** Keep the existing maximum read-tool rounds; appended correction/tool observations must be included in the per-request token check, and the call stops with a structured assistant error rather than sending an over-budget prompt. Return separate counts for summarized versus omitted messages.
- [x] **Step 5: Implement the panel notice with existing assistant UI conventions.** Keep the summary notice visible for the conversation after it first appears, do not treat an absent marker on a later response as recovery, and clear the notice on explicit new-conversation action.
- [x] **Step 6: Verify fresh-database migration and all assistant-focused tests.**

```bash
mvn -q -Dtest=AssistantConversationSummarySchemaTest,AssistantConversationSummaryMapperSqlTest,AssistantTokenEstimatorTest,AssistantConversationSummarizerTest,AssistantModelClientUsageTest,AssistantConversationContextServiceTest,AssistantConversationContextIntegrationTest,AssistantConversationServiceTest,AssistantConversationLogServiceTest,AssistantRequestGuardTest,AssistantPromptHistoryTest test
```

Expected: all named tests PASS; report skipped tests separately and do not count them as verified.

- [x] **Step 7: Update the design document with implementation decisions and observed provider-usage limitations.** Keep it separate from user-facing product documentation.
- [x] **Step 8: Review Task 4 implementation diff and record its exact verification; do not stage or commit while the shared worktree has unrelated user WIP or the existing index lock remains.**

## Final Gate

- Run the complete backend test suite once after Task 4 and the frontend assistant UI tests/build.
- Confirm `git status --short`, nested repository boundaries, and staged file list; no unrelated WIP may be included.
- Verify provider token usage where available and compare summary-call cost against input tokens saved over subsequent turns. Missing usage must remain explicitly unavailable.
- Stop if the configured model has no supported tokenizer encoding or if migrations cannot run against the supported PostgreSQL test environment; do not silently substitute character count as exact Token usage.

## Execution Record (2026-09-24)

- Task 1-4 implementation and focused acceptance are complete; commit steps remain intentionally open because the shared worktree contains unrelated WIP and an existing nested-repository `index.lock`.
- `mvn -q -Dmaven.compiler.testIncludes='**/*.java' -Dtest=AssistantConversationSummarySchemaTest,AssistantConversationSummaryMapperSqlTest,AssistantConversationSummaryMigrationTest,AssistantTokenEstimatorTest,AssistantConversationSummarizerTest,AssistantModelClientUsageTest,AssistantConversationContextServiceTest,AssistantConversationContextIntegrationTest,AssistantConversationServiceTest,AssistantConversationLogServiceTest,AssistantRequestGuardTest,AssistantPromptHistoryTest test`: 73 tests passed, 0 failures/errors/skips.
- `mvn -q -Dmaven.compiler.testIncludes='**/*.java' -Dtest=AppIntegrationTest test`: 10 tests passed; this also verified the Spring constructor injection fix.
- `npm run test:ui -- --run src/components/assistant/AssistantPanel.test.tsx`: 26 tests passed.
- `npm run build`: passed after correcting the `*/*` comment terminator in the concurrent `assistantStream.ts` WIP.
- `mvn -q -Dmaven.compiler.testIncludes='**/*.java' -DskipTests compile`: passed after the same class of comment fix in `AssistantController.java`.
- `mvn -q -Dmaven.compiler.testIncludes='**/*.java' test`: 2133 tests, 1 failure, 21 errors, 2 skipped. The 21 errors were the constructor injection cascade fixed and revalidated; remaining independent failures are `MessageSendApplicationServiceTest.duplicateClientRequestCreatesOnePendingMessageAndOutbox` and `ContactMemoryEndToEndTest.lateInboundMessageBehindSuccessCursorIsStillSentToTheModel`, outside this plan's ownership.
