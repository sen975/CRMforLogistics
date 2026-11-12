# 企业微信消息级官方摘要 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信数据与智能专区的 `conversation_daily_summary` 从按天批量摘要改为每条消息异步生成并永久保存可诊断的摘要记录。

**Architecture:** 消息落库和 `PENDING` 摘要任务在 `WeComChatDataStore` 同一事务中完成，提交后由有界 worker 逐条调用现有 `WeComSummaryGateway` 并轮询官方结果。摘要任务独立于原始消息保留策略，保存脱敏请求、原始响应、校验阶段和失败原因；未完成任务对应的消息在容量清理时受保护，后台通过分页接口查询诊断状态。

**Tech Stack:** Java 17、Spring Boot 3.4.5、MyBatis-Plus、PostgreSQL、Flyway、JUnit 5/Mockito、现有 `RestClient` 企业微信专区网关。

## Global Constraints

- 8107 不读取或保存企业微信消息正文；摘要原文仍由官方专区根据 `msgid` 与内存中的 `secret_key` 读取。
- 每次官方请求的 `msg_list` 必须只有一项，且数据库审计快照不得写入 `secret_key`、access token、私钥或明文正文。
- `(installation_id, msgid)` 是唯一幂等键；任务记录、摘要、原始响应和诊断字段不自动删除。
- 任务状态只允许 `PENDING`、`SUBMITTED`、`RETRY_WAIT`、`COMPLETED`、`FAILED`，领取使用租约，重试次数、退避、并发、批量和响应大小均有上界。
- `WECOM_DAILY_SUMMARY_ENABLED=true` 继续作为开关，`WECOM_DAILY_SUMMARY_ABILITY_ID` 继续为 `conversation_daily_summary`；每日批量 scheduler 不再创建新任务。
- 本次不修改 `demo/wecom-chatdata-zone-program` 协议，不将摘要转成 Topic，不改变 ChatApp、邮件、电话 Topic 聚合，不增加企业微信前端 Topic AI 入口。
- 保留现有工作区用户改动；每个任务只修改其列出的文件，不使用 `git add .` 或破坏性 git 命令。

---

### Task 1: 建立消息级摘要数据合同和数据库迁移

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V30__wecom_message_summary_jobs.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComMessageSummaryJobEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryRepository.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryRepositoryTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummarySchemaContractTest.java`

**Interfaces:**
- `WeComMessageSummaryRepository.enqueueIfAbsent(WeComMessageSummaryRepository.EnqueueCommand)` 返回 `boolean`，仅首次插入返回 `true`。
- `leaseNext(String owner, Instant now, Duration lease)` 返回 `Optional<LeasedJob>`；`markSubmitted`、`markCompleted`、`markRetry`、`markFailed` 负责受约束的状态迁移。
- `find(PageQuery)` 返回 `PageResult<JobView>`，`findByMsgid(UUID installationId, String msgid)` 返回包含消息存在性和完整诊断字段的 `Optional<JobView>`。
- `countNonTerminalByMsgids(UUID installationId, List<String> msgids)` 为保留策略提供受保护消息集合。

- [ ] **Step 1: 写失败的 schema 合同测试**

```java
@Test
void migrationDefinesIdempotencyStatusAndDiagnosticColumns() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V30__wecom_message_summary_jobs.sql"));
    assertTrue(sql.contains("wecom_message_summary_jobs"));
    assertTrue(sql.contains("UNIQUE (installation_id, msgid)"));
    assertTrue(sql.contains("validation_stage"));
    assertTrue(sql.contains("raw_request_json"));
    assertTrue(sql.contains("raw_response_json"));
    assertTrue(sql.contains("CHECK (status IN ('PENDING','SUBMITTED','RETRY_WAIT','COMPLETED','FAILED'))"));
}
```

- [ ] **Step 2: 运行合同测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummarySchemaContractTest test`

Expected: FAIL because `V30__wecom_message_summary_jobs.sql` and the test class do not yet exist.

- [ ] **Step 3: 添加迁移、实体、Mapper 和 repository 实现**

迁移创建 `wecom_message_summary_jobs`，字段包括 `id`、安装实例/会话/`msgid`、`send_time`、`status`、`wecom_job_id`、`summary`、`raw_request_json`、`raw_response_json`、`validation_stage`、`last_error_code`、`failure_state`、`attempt_count`、`next_attempt_at`、`lease_owner`、`lease_until`、创建/更新/提交/完成时间。对摘要和 JSON 文本设置明确字节上限；为幂等键、状态租约、会话时间建立索引。Mapper 使用参数化 SQL 完成 `INSERT ... ON CONFLICT DO NOTHING`、租约领取、条件状态迁移和分页查询。repository 在写入前校验长度、状态和时间边界，在读出时将数据库行映射为不可变 record。

- [ ] **Step 4: 运行 repository 单元测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryRepositoryTest,WeComMessageSummarySchemaContractTest test`

Expected: PASS；重复 `(installation_id,msgid)` 只保留一行，非法状态/超限响应被拒绝，租约过期任务可再次领取，终态迁移不可回退。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V30__wecom_message_summary_jobs.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComMessageSummaryJobEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryRepository.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryRepositoryTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummarySchemaContractTest.java
git commit -m "feat: add wecom message summary job contract"
```

### Task 2: 在消息入库事务中幂等入队

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataMessageEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreMessageSummaryEnqueueTest.java`

**Interfaces:**
- `WeComChatDataStore` 构造函数注入 `WeComMessageSummaryRepository`；兼容现有测试构造函数时传入 `null`，生产 bean 必须使用完整构造函数。
- 新增私有 `enqueueSummary(WeComMessageSummaryRepository.EnqueueCommand)`，只在 `insertIgnore` 返回 1 后调用；repository 插入失败抛出运行时异常，使 `@Transactional publishPage` 回滚。

- [ ] **Step 1: 写三条入库路径的失败测试**

```java
@Test
void everyNewMessagePathEnqueuesOnePendingJobInSameTransaction() {
    store.publishPage(installation, key, "next", List.of(groupMessage, directMessage, legacyMessage));
    verify(summaryRepository, times(3)).enqueueIfAbsent(any());
}

@Test
void duplicateMessageDoesNotEnqueueAgain() {
    when(messageMapper.insertIgnore(any())).thenReturn(0);
    store.publishPage(installation, key, "next", List.of(legacyMessage));
    verifyNoInteractions(summaryRepository);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComChatDataStoreMessageSummaryEnqueueTest test`

Expected: FAIL because `WeComChatDataStore` has no summary repository dependency or enqueue call.

- [ ] **Step 3: 接入入队并保持同一事务**

在 `publishNormalizedGroup`、`publishNormalizedDirect` 和兼容投影三处保存成功分支中调用入队；入队命令携带安装实例、`authCorpId`、会话 ID、`msgid`、发送时间和 `raw_request_json` 的脱敏操作快照。不要在事务内发起网络请求；不要为重复消息创建任务。入队异常直接向上抛出，保证消息行、游标和任务一起回滚。

- [ ] **Step 4: 运行 store 测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComChatDataStoreMessageSummaryEnqueueTest test`

Expected: PASS；三条路径均只为新消息入队，重复消息不产生第二条任务，入队异常使事务失败。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataMessageEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreMessageSummaryEnqueueTest.java
git commit -m "feat: enqueue wecom message summaries on ingest"
```

### Task 3: 实现单条提交、轮询和失败分层 worker

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComSummaryGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/message-center/config/AppConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorkerTest.java`

**Interfaces:**
- worker 暴露 `runOnce(Instant now)` 供 scheduler 和测试调用；内部使用 `@Scheduled(fixedDelayString=...)` 或现有调度模式触发。
- `WeComSummaryGateway.submit` 接收 `List<SummaryMessage>` 且 worker 传入列表长度恒为 1；`poll` 返回包含官方状态、摘要和原始脱敏 JSON 的结果对象。
- 所有异常映射为 `MESSAGE_REFERENCE`、`SECRET_KEY_DECRYPT`、`HTTP`、`OFFICIAL_ERROR`、`OUTER_JSON`、`RESPONSE_DATA`、`FIELD_VALIDATION` 之一，并按可重试性进入 `RETRY_WAIT` 或 `FAILED`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void submitsExactlyOneMessageAndStoresCompletedSummary() {
    when(repository.leaseNext(anyString(), any(), any())).thenReturn(Optional.of(pendingJob));
    when(messageRepository.findReference(any(), eq("m-1"))).thenReturn(Optional.of(reference));
    when(gateway.submit(any(), argThat(messages -> messages.size() == 1 && messages.get(0).msgid().equals("m-1"))))
            .thenReturn(new SubmitResult("job-1", "{}"));
    when(gateway.poll(any(), eq("job-1"))).thenReturn(new PollResult(1, "摘要", "{}"));
    worker.runOnce(now);
    verify(repository).markCompleted(eq(pendingJob.id()), eq("摘要"), eq("{}"), eq("OFFICIAL_RESULT"), any());
}

@Test
void invalidOfficialPayloadPersistsValidationStageAndRetries() {
    // poll 返回缺少 summary 字段的响应
    worker.runOnce(now);
    verify(repository).markRetry(eq(pendingJob.id()), eq("AI_RESPONSE_INVALID"), eq("RESPONSE_DATA"), any());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryWorkerTest test`

Expected: FAIL because worker、单条提交契约和结果校验尚不存在。

- [ ] **Step 3: 实现 worker 和配置**

按配置限制领取批量和并发；先读取消息引用，再在内存中解密 `secret_key`，构造不含密钥的请求快照，调用 `submit` 后保存官方 `jobid`，到轮询时间调用 `poll`。保存每次官方原始响应但拒绝超限数据；校验外层 JSON、`response_data`、状态、摘要字段和摘要长度，失败阶段写入 repository。使用指数退避并封顶 `WECOM_MESSAGE_SUMMARY_MAX_BACKOFF_SECONDS`，达到最大尝试次数进入 `FAILED`。worker 只记录 `msgid`、任务 ID、状态、官方 jobid、尝试次数、阶段和错误码。

- [ ] **Step 4: 运行 worker 测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryWorkerTest test`

Expected: PASS；请求恰有一条消息，完成、官方错误、HTTP 超时、非法响应和密钥解密失败分别落到正确阶段和状态，日志不包含密钥或 token。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorker.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComSummaryGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorkerTest.java
git commit -m "feat: process wecom message summaries asynchronously"
```

### Task 4: 保护未完成摘要消息并停止每日批量创建

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetention.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryBatcher.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetentionSummaryProtectionTest.java`

**Interfaces:**
- retention 从 `WeComMessageSummaryRepository.countNonTerminalByMsgids` 得到保护集合；删除 SQL 必须排除集合中的 `msgid`。
- 每日 scheduler 保留读取旧历史任务的兼容查询，但不再调用 `ensureDailyJob` 创建新批量任务。

- [ ] **Step 1: 写失败测试**

```java
@Test
void retentionSkipsMessagesWithPendingOrSubmittedSummary() {
    when(summaryRepository.countNonTerminalByMsgids(any(), anyList())).thenReturn(Set.of("protected"));
    RetentionResult result = retention.enforce();
    verify(messageMapper, never()).deleteByMsgids(argThat(ids -> ids.contains("protected")));
    assertFalse(result.deletedMsgids().contains("protected"));
}

@Test
void dailySchedulerDoesNotCreateNewBatchJobs() {
    scheduler.runOnce(clock.instant());
    verifyNoInteractions(dailySummaryRepository);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComChatDataRetentionSummaryProtectionTest test`

Expected: FAIL because retention 当前只按容量删除，scheduler 仍创建每日批处理。

- [ ] **Step 3: 实现保护和停批处理**

删除候选先查询任务非终态集合；若预算仍超出且所有候选都被保护，返回 `withinBudget=false` 并记录结构化告警，不删除受保护消息。将 `WECOM_DAILY_SUMMARY_ENABLED` 的新语义接到消息级 worker；每日批处理只保留历史数据读取能力。

- [ ] **Step 4: 运行专项测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComChatDataRetentionSummaryProtectionTest,WeComDailySummaryBatcherTest,WeComDailySummaryServiceTest test`

Expected: PASS；非终态对应消息不删除，终态消息仍服从容量策略，新同步不产生每日批量任务。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetention.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryScheduler.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryBatcher.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetentionSummaryProtectionTest.java
git commit -m "feat: protect pending wecom summaries from retention"
```

### Task 5: 增加历史消息有界补偿

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryBackfill.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryBackfillTest.java`

**Interfaces:**
- `runOnce(UUID installationId, Instant now)` 每次最多扫描 `WECOM_MESSAGE_SUMMARY_BACKFILL_BATCH_SIZE` 条消息；按 `send_time,id` 游标推进，遇到已有任务只跳过，不修改其状态。

- [ ] **Step 1: 写失败测试**

```java
@Test
void backfillCreatesOnlyMissingJobsWithinConfiguredBound() {
    when(messageMapper.findForSummaryBackfill(any(), any(), eq(200))).thenReturn(List.of(oldMessage, existingMessage));
    when(repository.exists(any(), eq(existingMessage.getMsgid()))).thenReturn(true);
    backfill.runOnce(installationId, now);
    verify(repository).enqueueIfAbsent(argThat(command -> command.msgid().equals(oldMessage.getMsgid())));
    verify(repository, times(1)).enqueueIfAbsent(any());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryBackfillTest test`

Expected: FAIL because没有有界补偿服务和消息查询 SQL。

- [ ] **Step 3: 实现补偿扫描**

按索引分页读取消息引用，仅为缺失任务插入 `PENDING`；每轮有明确上限和游标，服务重启后可继续。补偿不读取正文、不调用官方接口，网络动作全部交给 worker。

- [ ] **Step 4: 运行补偿测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryBackfillTest test`

Expected: PASS；单轮不超过配置批量，已有 `(installation_id,msgid)` 不重复入队，游标稳定推进。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryBackfill.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryBackfillTest.java
git commit -m "feat: backfill missing wecom message summary jobs"
```

### Task 6: 提供后台摘要状态和诊断查询接口

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComMessageSummaryResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComMessageSummaryController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComMessageSummaryControllerTest.java`

**Interfaces:**
- `GET /api/v1/wecom/message-summaries?msgid=&conversationId=&status=&from=&to=&page=&size=` 返回分页任务，不返回正文或密钥。
- `GET /api/v1/wecom/message-summaries/{msgid}` 返回 `messageExists`、任务状态、摘要、官方 jobid、`validationStage`、错误码、失败状态、尝试次数和关键时间戳。

- [ ] **Step 1: 写失败 Web 测试**

```java
@Test
void readsSummaryDiagnosticsWithoutSecrets() throws Exception {
    mockMvc.perform(get("/api/v1/wecom/message-summaries/m-1").with(user("admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.validationStage").value("RESPONSE_DATA"))
            .andExpect(jsonPath("$.secretKey").doesNotExist())
            .andExpect(jsonPath("$.rawRequestJson").value("{\"operation\":\"submit\",\"msgid\":\"m-1\"}"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryControllerTest test`

Expected: FAIL because路由、DTO和查询映射尚不存在。

- [ ] **Step 3: 实现分页和单条查询**

控制器复用现有认证/错误处理，限制 `page size <= 100`、时间范围和状态枚举；repository 返回消息是否存在及任务诊断字段。响应序列化时显式排除 `secret_key`、token、私钥和正文。

- [ ] **Step 4: 运行 Web 测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageSummaryControllerTest test`

Expected: PASS；可按 `msgid`、会话、状态、时间分页查询，敏感字段不出现在响应和错误详情中。

- [ ] **Step 5: 提交本任务**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComMessageSummaryResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComMessageSummaryController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComMessageSummaryRepository.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComMessageSummaryControllerTest.java
git commit -m "feat: expose wecom message summary diagnostics"
```

### Task 7: 配置、运维文档和发布验收

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`
- Modify: `demo/message-center-spring/README.md`
- Create: `docs/superpowers/reviews/2026-08-31-wecom-message-summary-verification.md`

**Interfaces:**
- 默认配置必须可由环境变量覆盖：

```env
WECOM_MESSAGE_SUMMARY_POLL_INTERVAL_SECONDS=1
WECOM_MESSAGE_SUMMARY_BATCH_SIZE=20
WECOM_MESSAGE_SUMMARY_MAX_CONCURRENCY=2
WECOM_MESSAGE_SUMMARY_MAX_TRANSIENT_ATTEMPTS=20
WECOM_MESSAGE_SUMMARY_MAX_BACKOFF_SECONDS=900
WECOM_MESSAGE_SUMMARY_BACKFILL_BATCH_SIZE=200
```

- [ ] **Step 1: 写配置合同测试**

```java
@Test
void messageSummaryDefaultsAreBoundAndBounded() {
    assertThat(config.messageSummaryPollIntervalSeconds()).isBetween(1, 60);
    assertThat(config.messageSummaryBatchSize()).isBetween(1, 100);
    assertThat(config.messageSummaryMaxConcurrency()).isBetween(1, 8);
    assertThat(config.messageSummaryMaxTransientAttempts()).isBetween(1, 100);
}
```

- [ ] **Step 2: 运行全链路专项测试确认缺口**

Run: `cd demo/message-center-spring/backend && mvn -Dtest='*WeComMessageSummary*' test`

Expected: 配置测试在新增绑定前失败，其余已完成任务测试保持通过。

- [ ] **Step 3: 写入配置、运维说明和验收记录模板**

文档说明 `.env` 变量、worker 日志事件、查询接口、任务状态含义、原始响应保留规则、消息保留保护和诊断排障顺序。验收记录只填写实际执行过的命令、迁移结果、测试结果和打包路径，不写推测性通过结论。

- [ ] **Step 4: 运行完整验收**

```bash
cd demo/message-center-spring/backend
mvn test
mvn -Pproduction -DskipTests package
git diff --check
```

Expected: Flyway 在空库和已有 V29 数据上升级到 V30；全量测试通过；生成 `target/message-center.jar`；`git diff --check` 无输出。若本地数据库、官方专区或依赖网络不可用，记录具体失败命令、影响范围和替代证据。

- [ ] **Step 5: 复核 git 边界并提交本任务**

```bash
git status --short
git diff --stat
git add demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/README.md \
  docs/superpowers/reviews/2026-08-31-wecom-message-summary-verification.md
git commit -m "docs: document wecom message summary operations"
```

停止条件：所有专项测试、全量测试、生产打包和迁移验证均有真实输出；任何失败、warning、未跟踪生成物或敏感字段泄露都必须在宣布完成前处理或明确记录为本轮阻断。
