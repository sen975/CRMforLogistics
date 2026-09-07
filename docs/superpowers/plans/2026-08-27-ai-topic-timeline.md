# AI Topic 时间轴实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Spring 消息中心中为联系人自动生成并持久化 ChatApp、邮件、电话 Topic，在右侧联系人面板按时间轴展示，并支持员工编辑与合并；企业微信永不进入 AI 输入。

**Architecture:** Spring 后端拥有 Topic、来源关联、生成任务和版本历史。`AiTopicService` 在联系人权限边界内读取 `messages` 与 `call_records`，通过有界 worker 调用通用 OpenAI-compatible provider，使用输入指纹保证首次生成和增量生成幂等；React 只消费 Topic API 状态并发出编辑/合并命令。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、Flyway、Spring `RestClient`、Jackson、JUnit 5、React 18、TypeScript、Ant Design、TanStack React Query、Vitest、Testing Library。

## Global Constraints

- AI 输入渠道严格为 `chatapp`、`email`、`phone`；`wecom` 不进入查询、指纹、provider payload、Topic 来源或摘要。
- API Key 只从后端环境变量/配置读取；前端 bundle、日志、审计和错误响应不得包含密钥、完整正文或完整模型响应。
- 首次打开联系人只幂等创建异步 `INITIAL` 任务，不同步阻塞消息时间线；新消息通过输入指纹创建 `INCREMENTAL` 任务。
- 同一联系人同一输入指纹只能存在一个未完成任务；worker 必须有最大尝试次数、退避和租约恢复，禁止无限重试和无界线程。
- AI 输出必须是结构化 JSON；未知来源、WeCom 来源、重复来源、空标题、超长文本或非法 JSON 均不得落库。
- 所有 Topic 读取、编辑、合并接口必须复用联系人访问校验和乐观锁；前端隐藏不是安全边界。
- 保留现有未提交企业微信 WIP；每个任务只修改本计划列出的文件，不执行 `git add .` 或破坏性回滚。
- 生成完成前必须运行专项测试；最终运行后端 `mvn -q test`、生产构建和前端 `npm test`、`npm run build`，并完成桌面/移动浏览器验收。

---

## 文件地图

**后端新增：**

- `backend/src/main/resources/db/migration/V26__ai_topics.sql`：Topic、来源、任务、版本表及约束/索引。
- `backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java`、`AiTopicItemEntity.java`、`AiTopicGenerationJobEntity.java`、`AiTopicVersionEntity.java`：MyBatis-Plus 持久化实体。
- `backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`、`AiTopicItemMapper.java`、`AiTopicGenerationJobMapper.java`、`AiTopicVersionMapper.java`：查询、幂等插入、租约和乐观锁 SQL。
- `backend/src/main/java/com/crmforlogistics/messagecenter/config/AiTopicConfig.java`：`ai-topic` 配置和边界校验。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java`：输入来源、Topic 分配、状态和 API projection 的共享类型。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`：按联系人读取并过滤三类来源，计算稳定指纹和有界批次。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGateway.java`、`TopicAiResponseParser.java`：provider HTTP 映射和 JSON 校验。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`：访问校验、初始/增量任务创建、Topic 查询、编辑和合并。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorker.java`、`AiTopicGenerationScheduler.java`：有界领取、执行、退避、失败和增量竞态处理。
- `backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java`：Topic GET/PATCH/merge/retry 路由。

**后端修改：**

- `backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java` 或新的 configuration properties 注册入口：启用 `AiTopicConfig` 扫描。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java`：发布脱敏 `topic.updated` 事件（只在现有事件模式需要时改）。
- `backend/src/main/resources/application-dev.yml`、`README.md`：AI 配置样例和运行说明。

**后端测试新增：**

- `backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParserTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorkerTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicSchemaContractTest.java`

**前端新增/修改：**

- `frontend/src/api/types.ts`：Topic response、来源、生成状态和命令类型。
- `frontend/src/api/endpoints.ts`：`fetchContactTopics`、`updateTopic`、`mergeTopics`、`retryTopicGeneration`。
- `frontend/src/components/AiTopicTimeline.tsx`：右侧 Topic 时间轴、状态、编辑和多选合并 UI。
- `frontend/src/components/AiTopicTimeline.test.tsx`：状态、渠道过滤、来源定位、编辑和合并交互测试。
- `frontend/src/components/ContactDetailPanel.tsx`：接入 Topic 查询、SSE/失效刷新和来源选择。
- `frontend/src/components/ContactDetailPanel.test.tsx`：Topic 混合渠道和仅 WeCom 空态回归。
- `frontend/src/hooks/useTopicTimeline.ts`：React Query 查询、重试和合并 mutation（若现有 hooks 结构需要拆分）。

---

### Task 1: 数据库合同与领域类型

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V26__ai_topics.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicItemEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationJobEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicVersionEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicSchemaContractTest.java`

**Interfaces:**
- Produces `AiTopicModels.SourceItem(UUID id, SourceType sourceType, String channelType, Instant occurredAt, String direction, String subject, String text)` and `AiTopicModels.TopicAssignment(String topicKey, String title, String summary, double relevance, List<UUID> sourceIds)`.
- Produces statuses `GenerationStatus { NOT_STARTED, GENERATING, READY, FAILED }` and persistence statuses `PENDING, PROCESSING, RETRY_WAIT, COMPLETED, FAILED`.

- [ ] **Step 1: Write the failing schema contract test**

```java
@Test
void migrationDefinesBoundedTopicSourcesAndWeComExclusion() {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V26__ai_topics.sql"));
    assertThat(sql).contains("CREATE TABLE ai_topics");
    assertThat(sql).contains("CREATE TABLE ai_topic_items");
    assertThat(sql).contains("CREATE TABLE ai_topic_generation_jobs");
    assertThat(sql).contains("CREATE TABLE ai_topic_versions");
    assertThat(sql).contains("channel_type IN ('chatapp', 'email', 'phone')");
    assertThat(sql).contains("CHECK ((message_id IS NOT NULL) <> (call_record_id IS NOT NULL))");
    assertThat(sql).contains("UNIQUE (contact_id, input_fingerprint)");
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicSchemaContractTest test`

Expected: FAIL because `V26__ai_topics.sql` and the referenced contract do not exist.

- [ ] **Step 3: Add the migration and entity fields**

Create four tables with UUID foreign keys, `READY/ARCHIVED` Topic status, `INITIAL/INCREMENTAL` job kind, lease/attempt columns, version history, source-type XOR constraint, channel CHECK, source partial unique indexes, `(contact_id, input_fingerprint)` uniqueness, and indexes on `(contact_id, last_occurred_at DESC)` and runnable jobs. Map Java fields with `@TableName`, `@TableId(type = IdType.ASSIGN_UUID)`, getters/setters matching column names used by existing entities.

- [ ] **Step 4: Run the schema test and compile**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicSchemaContractTest test`

Expected: PASS with no warnings; Flyway parser accepts V26.

- [ ] **Step 5: Commit only Task 1 files**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V26__ai_topics.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicItemEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicGenerationJobEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AiTopicVersionEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicSchemaContractTest.java
git commit -m "feat: add ai topic persistence contract"
```

### Task 2: 来源读取、指纹与 MyBatis 持久化

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicGenerationJobMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicVersionMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AiTopicConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`

**Interfaces:**
- `AiTopicInputService.collect(UUID contactId, UUID userId, Optional<Instant> after)` returns `AiTopicModels.InputBatch(List<SourceItem> items, String fingerprint, boolean hasMore)`.
- `AiTopicGenerationJobMapper.findOrCreate(UUID contactId, String kind, String fingerprint, Instant now)` is idempotent and returns the existing job when the unique key already exists.
- `AiTopicMapper.listForContact(UUID contactId, UUID userId)` returns only accessible, non-archived topics.

- [ ] **Step 1: Write failing source-filter and fingerprint tests**

```java
@Test
void collectIgnoresWeComAndProducesStableChronologicalFingerprint() {
    InputBatch batch = service.collect(contactId, userId, Optional.empty());
    assertThat(batch.items()).extracting(SourceItem::channelType)
        .containsExactly("email", "phone", "chatapp");
    assertThat(batch.items()).allMatch(item -> !item.channelType().equals("wecom"));
    assertThat(batch.fingerprint()).hasSize(64);
    assertThat(service.collect(contactId, userId, Optional.empty()).fingerprint())
        .isEqualTo(batch.fingerprint());
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceTest test`

Expected: FAIL because `AiTopicInputService` and its mappers do not exist.

- [ ] **Step 3: Implement bounded source collection**

Join accessible contact conversations to `messages`, select only `channel_type IN ('chatapp','email')`; query `call_records` through the contact's `phone:` identities and include `note` plus completed current transcript text. Normalize null text to empty, sort by `occurred_at ASC, id ASC`, enforce `ai-topic.max-input-records` and `max-input-bytes`, and hash canonical `sourceType|id|occurredAt|channel|direction|subject|text` lines with SHA-256. Never select WeCom rows.

- [ ] **Step 4: Implement mapper SQL and configuration bounds**

Bind `AiTopicConfig` to `ai-topic` with defaults `base-url`, `api-key`, `model`, timeout 30 seconds, max records 200, max bytes 256 KiB, threshold 0.65, worker concurrency 1, max attempts 3, lease 120 seconds. Reject non-positive timeout/limits and thresholds outside `0..1` at construction.

- [ ] **Step 5: Run focused tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceTest,AiTopicSchemaContractTest test`

Expected: PASS and no test warnings.

- [ ] **Step 6: Commit Task 2**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopic*.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AiTopicConfig.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceTest.java \
  demo/message-center-spring/backend/src/main/resources/application-dev.yml
git commit -m "feat: collect bounded non-wecom topic sources"
```

### Task 3: OpenAI-compatible provider 与结构化解析

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGateway.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParser.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParserTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGatewayTest.java`

**Interfaces:**
- `TopicAiGateway.generate(AiTopicModels.GenerationInput input)` returns `AiTopicModels.GenerationOutput(List<TopicAssignment> assignments)`.
- `TopicAiResponseParser.parse(String json, Set<UUID> allowedSourceIds, boolean incremental)` returns `GenerationOutput` or throws `AiTopicException("AI_RESPONSE_INVALID", retryable=false)`.

- [ ] **Step 1: Write failing parser tests**

```java
@Test
void parserRejectsUnknownWeComOrDuplicateSources() {
    String json = "{\"topics\":[{\"topicKey\":\"a\",\"title\":\"报价\",\"summary\":\"...\",\"relevance\":0.9,\"sourceIds\":[\"" + UUID.randomUUID() + "\"]}]}";
    assertThatThrownBy(() -> parser.parse(json, Set.of(UUID.randomUUID()), false))
        .isInstanceOf(AiTopicException.class)
        .hasMessage("AI_RESPONSE_INVALID");
}
```

- [ ] **Step 2: Run parser tests and verify they fail**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=TopicAiResponseParserTest,OpenAiCompatibleTopicGatewayTest test`

Expected: FAIL because provider and parser classes do not exist.

- [ ] **Step 3: Implement strict JSON contract**

Accept only `{ "topics": [{ "topicKey": string, "title": string, "summary": string, "relevance": number, "sourceIds": [uuid] }] }`; require 1..20 topics, title <= 200 code points, summary <= 4000 code points, relevance `0..1`, source IDs unique and contained in allowed set. Use Jackson `ObjectMapper.readTree`, never regex or string slicing.

- [ ] **Step 4: Implement bounded provider request**

Use `RestClient` with JDK request factory and configured connect/read timeout. POST `${baseUrl}/v1/chat/completions` with model, one system message requiring JSON, one user message containing bounded source fields and existing Topic summaries for incremental mode, and `response_format` JSON object when provider supports it. Send `Authorization: Bearer <api-key>` only when configured; map 408/429/5xx to retryable `AI_PROVIDER_UNAVAILABLE`, other 4xx to non-retryable `AI_PROVIDER_REJECTED`; never log request body or response body.

- [ ] **Step 5: Run focused tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=TopicAiResponseParserTest,OpenAiCompatibleTopicGatewayTest test`

Expected: PASS, including timeout, 429, malformed JSON and secret-redaction assertions.

- [ ] **Step 6: Commit Task 3**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParser.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/TopicAiResponseParserTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/OpenAiCompatibleTopicGatewayTest.java
git commit -m "feat: add structured topic ai gateway"
```

### Task 4: Topic 服务、初始/增量聚合与任务 worker

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorker.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationScheduler.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorkerTest.java`

**Interfaces:**
- `AiTopicService.getTopics(UUID userId, UUID contactId)` returns `AiTopicModels.TopicTimelineResponse` and idempotently enqueues initial/incremental work.
- `AiTopicService.updateTopic(UUID userId, UUID topicId, String title, String confirmedSummary, long expectedVersion)` returns `TopicProjection`.
- `AiTopicService.mergeTopics(UUID userId, List<UUID> topicIds, Map<UUID, Long> expectedVersions)` returns `List<TopicProjection>`.
- `AiTopicService.retryGeneration(UUID userId, UUID contactId)` returns `GenerationProjection`.
- `AiTopicGenerationWorker.runOnce()` claims at most `worker-concurrency` jobs and returns a count.

- [ ] **Step 1: Write failing service/worker tests**

```java
@Test
void firstReadCreatesOnlyOneInitialJobAndWeComOnlyContactCreatesNone() {
    TopicTimelineResponse first = service.getTopics(userId, mixedContactId);
    TopicTimelineResponse second = service.getTopics(userId, mixedContactId);
    assertThat(first.generation().status()).isEqualTo(GenerationStatus.GENERATING);
    verify(jobMapper, times(1)).insertInitial(any());
    assertThat(service.getTopics(userId, weComOnlyContactId).topics()).isEmpty();
    verify(jobMapper, never()).insertInitialFor(weComOnlyContactId);
}

@Test
void workerKeepsEmployeeSummaryAndCreatesNewTopicForLowRelevanceIncrement() {
    worker.runOnce();
    verify(topicMapper).insertSourceItems(argThat(items -> items.stream().noneMatch(i -> "wecom".equals(i.getChannelType()))));
    verify(versionMapper).insert(argThat(version -> "AI_GENERATED".equals(version.getChangeType())));
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicServiceTest,AiTopicGenerationWorkerTest test`

Expected: FAIL because service, worker and task state transitions do not exist.

- [ ] **Step 3: Implement access-controlled query and task creation**

Call existing `ConversationAccessService`/`ContactService` boundary before any Topic read. Collect current fingerprint; return existing Topics sorted by `lastOccurredAt DESC`. If no READY Topic and sources exist, insert one `INITIAL` job with `ON CONFLICT (contact_id,input_fingerprint) DO NOTHING`. If READY Topics exist and fingerprint differs, insert one `INCREMENTAL` job for only sources after the last processed source cursor. Only contacts with no supported ChatApp/email/phone identity and a WeCom identity return `NOT_STARTED` and `WECOM_AI_UNSUPPORTED`; mixed contacts with no new unassigned sources return ordinary `NOT_STARTED` or existing READY Topics.

- [ ] **Step 4: Implement transactional worker state machine**

Claim runnable job with `SELECT ... FOR UPDATE SKIP LOCKED`, set `PROCESSING` and lease, call gateway outside the database transaction, then in a transaction validate assignments, insert/update Topics and items, preserve `confirmed_summary`, append `AiTopicVersionEntity` rows, mark job `COMPLETED`, and publish only a `{contactId, jobId, status}` event. On retryable errors set `RETRY_WAIT` with exponential backoff capped at 900 seconds; after max attempts set `FAILED`. Recompute the fingerprint after completion and enqueue the next incremental job when new sources arrived during processing.

- [ ] **Step 5: Implement merge and edit contracts**

Require at least two Topic IDs for one contact and current non-archived status. Use earliest `firstOccurredAt` as target, lock all rows in deterministic UUID order, merge source items, archive other Topics, preserve target employee summary until new AI output succeeds, append `MERGED`, and return projections. Edit checks `expectedVersion`, changes only title/confirmed summary, appends `EMPLOYEE_EDITED`, and increments version.

- [ ] **Step 6: Run focused backend tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicServiceTest,AiTopicGenerationWorkerTest,AiTopicInputServiceTest,TopicAiResponseParserTest test`

Expected: PASS with no warnings; assertions cover WeCom exclusion, idempotency, low relevance new Topic, merge history, retry and optimistic-lock conflict.

- [ ] **Step 7: Commit Task 4**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationScheduler.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicGenerationWorkerTest.java
git commit -m "feat: generate and merge ai topics asynchronously"
```

### Task 5: HTTP API 与事件接线

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java` only if topic event publication needs a typed helper.

**Interfaces:**
- `GET /api/v1/contacts/{contactId}/topics` -> `TopicTimelineResponse`.
- `PATCH /api/v1/topics/{topicId}` body `{title, confirmedSummary, expectedVersion}` -> `TopicProjection`.
- `POST /api/v1/topics/merge` body `{topicIds, expectedVersions}` -> `List<TopicProjection>`.
- `POST /api/v1/contacts/{contactId}/topics/retry` -> `GenerationProjection`.

- [ ] **Step 1: Write failing controller tests**

```java
@Test
void topicsEndpointReturnsGenerationStateAndNeverAcceptsWeComInput() throws Exception {
    when(service.getTopics(any(), eq(contactId))).thenReturn(fixtureResponse);
    mockMvc.perform(get("/api/v1/contacts/{id}/topics", contactId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generation.status").value("GENERATING"))
        .andExpect(jsonPath("$.topics").isArray());
    verify(service).getTopics(any(), eq(contactId));
}
```

- [ ] **Step 2: Run test and verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicControllerTest test`

Expected: FAIL because `AiTopicController` and routes do not exist.

- [ ] **Step 3: Implement routes and error mapping**

Use `SecurityUtil.currentUserId()`, UUID path binding, request DTO records with Bean Validation bounds, and existing exception handling conventions. Map access denial to 403, optimistic-lock conflict to 409, invalid merge to 400, and retryable provider state to 202/200 generation response without leaking provider details.

- [ ] **Step 4: Add topic.updated event payload**

Publish only event name and `{contactId, jobId, status}` after committed writes; do not publish source text or provider response. Keep existing `/api/events` contract compatible.

- [ ] **Step 5: Run focused tests and commit**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicControllerTest test`

Expected: PASS for GET, PATCH, merge, retry, 403, 409 and redacted error responses.

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AiTopicController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AiTopicControllerTest.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java
git commit -m "feat: expose ai topic api"
```

### Task 6: 前端 Topic 时间轴与联系人右侧面板

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Create: `demo/message-center-spring/frontend/src/hooks/useTopicTimeline.ts`
- Create: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.tsx`
- Create: `demo/message-center-spring/frontend/src/components/AiTopicTimeline.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx`

**Interfaces:**
- `fetchContactTopics(contactId: string): Promise<ContactTopicsResponse>`.
- `updateTopic(topicId: string, data: UpdateTopicRequest): Promise<TopicProjection>`.
- `mergeTopics(data: MergeTopicsRequest): Promise<TopicProjection[]>`.
- `useTopicTimeline(contactId?: string)` returns `{data, isLoading, isFetching, refetch, update, merge, retry}`.
- `AiTopicTimeline` props: `{contactId: string; timeline: ContactTopicsResponse | undefined; onSourceClick(source: TopicSourceItem): void}`.

- [ ] **Step 1: Write failing UI tests**

```tsx
it('shows only non-WeCom sources and supports confirmed merge', async () => {
  render(<AiTopicTimeline contactId="c1" timeline={mixedTopics} onSourceClick={onSourceClick} />);
  expect(screen.getByText('美国海运报价')).toBeInTheDocument();
  expect(screen.getByText('邮件')).toBeInTheDocument();
  expect(screen.queryByText('企业微信')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '合并 Topic' }));
  expect(screen.getByRole('dialog')).toBeInTheDocument();
});

it('renders WeCom-only unsupported state without generation controls', () => {
  render(<AiTopicTimeline contactId="c1" timeline={weComOnlyTopics} onSourceClick={onSourceClick} />);
  expect(screen.getByText('当前渠道暂不支持 AI Topic 总结')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run UI tests and verify they fail**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- AiTopicTimeline.test.tsx`

Expected: FAIL because Topic types, hook and component do not exist.

- [ ] **Step 3: Add API types/endpoints and query hook**

Define `ContactTopicsResponse`, `TopicProjection`, `TopicSourceItem`, `TopicGenerationProjection`, `UpdateTopicRequest` and `MergeTopicsRequest` in `api/types.ts`; call `/v1/contacts/{id}/topics`, `/v1/topics/{id}`, `/v1/topics/merge`, and `/v1/contacts/{id}/topics/retry` using existing Axios client. Invalidate `['contact-topics', contactId]` after mutations and on SSE callback.

- [ ] **Step 4: Implement Topic timeline UI**

Render statuses `GENERATING`, `READY`, `FAILED`, and `WECOM_AI_UNSUPPORTED`; sort by `lastOccurredAt DESC`; show title, summary, date range, channels and source count. Filter any unexpected `wecom` source defensively before rendering. Source buttons call `onSourceClick`; edit uses controlled fields and version; merge mode uses checkboxes, requires at least two selected Topics, shows Ant Design confirmation, and calls `mergeTopics`.

- [ ] **Step 5: Integrate into `ContactDetailPanel`**

Place Topic timeline above `Descriptions`, query with the current `contactId`, preserve existing contact/message detail behavior, and pass a source click handler that calls `selectMessage` or `selectCallRecord` based on `sourceType`. Keep WeCom contact profile/identity rendering unchanged; Topic area must not be mounted as an AI input path for WeCom-only contacts.

- [ ] **Step 6: Run focused UI tests and build**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- AiTopicTimeline.test.tsx ContactDetailPanel.test.tsx && npm run build`

Expected: PASS and TypeScript/Vite build succeeds without warnings.

- [ ] **Step 7: Commit Task 6**

```bash
git add demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/hooks/useTopicTimeline.ts \
  demo/message-center-spring/frontend/src/components/AiTopicTimeline.tsx \
  demo/message-center-spring/frontend/src/components/AiTopicTimeline.test.tsx \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx \
  demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx
git commit -m "feat: show ai topic timeline in contact panel"
```

### Task 7: SSE 刷新、文档与最终门禁

**Files:**
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx` only if the existing SSE callback needs to invalidate Topic queries globally.
- Modify: `demo/message-center-spring/frontend/src/hooks/useSse.ts` only if event filtering is required by the existing hook contract.
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`
- Modify: `demo/message-center-spring/README.md`
- Modify: `docs/superpowers/README.md` to add the implementation plan under current implementation.
- Test: existing frontend/backend suites plus browser evidence.

**Interfaces:**
- Existing SSE callback remains backward compatible; `topic.updated` invalidates `['contact-topics', activeContactId]` without replacing message/thread refresh behavior.

- [ ] **Step 1: Write the SSE regression test**

Extend `ThreadPage.wecom.test.tsx` or add `ThreadPage.topic.test.tsx` with a mocked SSE callback that asserts a Topic query invalidation/refetch occurs while `fetchThread` and `refreshCallRecords` still run.

- [ ] **Step 2: Run the regression test and verify it fails**

Run: `cd demo/message-center-spring/frontend && npm run test:ui -- ThreadPage.topic.test.tsx`

Expected: FAIL because Topic query invalidation is not connected to SSE.

- [ ] **Step 3: Implement refresh and documentation**

Handle `topic.updated` through the current SSE hook or invalidate the Topic query for every existing message event. Add all `AI_*` environment variables, defaults, secret handling, provider endpoint shape, worker limits, and explicit WeCom exclusion to Spring README and `application-dev.yml` without writing sample secrets.

- [ ] **Step 4: Run complete verification**

Run:

```bash
cd demo/message-center-spring/backend
mvn -q test
mvn -q -Pproduction -DskipTests package

cd ../frontend
npm test
npm run build
```

Expected: all tests pass, production JAR and frontend bundle build, no lint/type/warning output attributable to this feature.

- [ ] **Step 5: Browser acceptance**

Start backend and frontend using the documented commands. Verify desktop and mobile contacts with ChatApp+email+phone, mixed WeCom, and WeCom-only data. Capture evidence that the right panel shows Topic time order, generating/failed states, source navigation, edit, confirmed merge, and no WeCom Topic input. Inspect Network requests to confirm no AI Key and no WeCom message body are sent.

- [ ] **Step 6: Review Git boundary and commit documentation**

Run `git status --short`, `git diff --check`, and `git diff --stat`; confirm only planned files are staged and existing WeCom WIP remains untouched. Commit only documentation and Task 7 files:

```bash
git add demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/README.md \
  docs/superpowers/README.md \
  demo/message-center-spring/frontend/src/pages/ThreadPage.tsx \
  demo/message-center-spring/frontend/src/hooks/useSse.ts \
  demo/message-center-spring/frontend/src/pages/ThreadPage.topic.test.tsx
git commit -m "docs: document ai topic configuration and verification"
```

## Self-review

- 设计覆盖：持久化/来源过滤在 Task 1-2；OpenAI-compatible provider、结构化 JSON、超时与脱敏在 Task 3；首次/增量、指纹、租约、重试、低关联新 Topic、编辑、合并和版本在 Task 4；权限与 HTTP 合同在 Task 5；右侧时间轴、状态、来源定位和移动 Drawer 复用在 Task 6；SSE、README、构建和浏览器验收在 Task 7。
- 完整性扫描：计划中没有缺失任务引用或含糊步骤；版本号使用当前源码最高迁移 V25 后的明确 V26。
- 类型一致性：Task 1 定义 `SourceItem`、`TopicAssignment`、`GenerationStatus`；Task 2 返回 `InputBatch`；Task 3 消费/返回 `GenerationInput/Output`；Task 4 使用这些类型产生 `TopicTimelineResponse/TopicProjection`；Task 5/6 以同名响应和命令接线。
- 停止条件：若 V26 已被用户 WIP 占用、数据库 schema 与计划冲突、或 AI 配置需要新增外部权限，必须停下报告证据，不覆盖用户文件或擅自删除旧合同。
