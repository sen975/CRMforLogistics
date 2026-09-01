# AI Topic 联系方式合并重关联实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 联系方式或联系人合并后，自动把新联系方式的全部历史来源与目标联系人现有 Topic 重新做关联，高关联度并入旧 Topic，低关联度创建新 Topic。

**Architecture:** `ContactGroupService` 是联系人合并 owner；合并事务移动联系方式归属并把来源联系人的活跃 Topic 归档，但保留其 `ai_topic_items` 作为待重关联来源。`AiTopicInputService` 在目标联系人增量任务中同时收集未归类来源和被合并来源联系人归档 Topic 的来源，`AiTopicService` 在 AI 输出通过整批校验后原位移动旧来源项或插入新来源项。合并完成通过 `AiTopicOwnerActivityService` 进入现有静默窗口，不新增即时 AI 调用链。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、JUnit 5、Mockito。

## Global Constraints

- 只有目标 `CONTACT` owner 的 `READY` Topic 可作为 AI 候选；群 Topic、`STORED`、`ARCHIVED` 和其他联系人 Topic 不得复用。
- 合并来源不会直接塞入目标 Topic，必须等 AI 输出整批校验成功后再原位迁移。
- 每条消息、电话记录或企业微信摘要只能归属一个 `ai_topic_items`；不得复制来源记录或修改来源 ID。
- 合并、重关联和 Topic 写入必须幂等；AI 失败时保留原归档 Topic 来源关系，下一次任务可重试。
- 不保存企业微信正文、密钥或 token；保持现有输入上界、租约、重试和审计合同。
- 工作区存在用户 WIP；只 stage 本计划及本任务相关文件，不使用 `git add .`。

### Task 1: 合并触发与来源 Topic 归档

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java`

**Interfaces:**
- `AiTopicMapper.archiveReadyByContact(UUID contactId)` 将来源联系人所有 `READY` Topic 标记为 `ARCHIVED`，不删除 Topic 或 `ai_topic_items`。
- `ContactGroupService` 注入可选的 `AiTopicOwnerActivityService`，合并成功后对目标 `CONTACT` owner 调用 `recordActivity(owner, Instant.now())`。

- [ ] **Step 1: Write the failing test**

测试 `mergeArchivesSourceTopicsAndRecordsTargetActivity`：mock 来源/目标联系人和 identity 移动，断言来源 Topic 归档、目标 owner 活动记录一次；相同联系人仍拒绝。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=ContactGroupServiceTopicReconciliationTest test`

Expected: FAIL，因为合并服务尚未归档来源 Topic 或记录 Topic owner activity。

- [ ] **Step 3: Implement minimal merge integration**

在现有 `@Transactional merge` 中完成 identity 移动并验证来源联系人存在后，调用 `archiveReadyByContact(sourceContactId)`，再将来源联系人标记为 merged，最后记录目标 owner activity。归档调用必须发生在事务内；activity 使用合并时刻，确保历史来源进入一次增量重算。

- [ ] **Step 4: Run focused test**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=ContactGroupServiceTopicReconciliationTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java
git commit -m "feat: trigger topic reconciliation after contact merge"
```

### Task 2: 收集合并来源联系人历史 Topic 来源

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceMergeReconciliationTest.java`

**Interfaces:**
- `AiTopicInputService.collect(OwnerRef owner, UUID userId, Optional<Instant> after)` 返回目标联系人未归类来源加上“已合并来源联系人 `ARCHIVED` Topic 中的来源”。
- 各 mapper 新增 owner-scoped 查询，返回统一的 `SourceItem` 所需字段，并限定来源联系人通过 `contacts.merged_to_id = targetContactId` 关联；群来源永不加入。

- [ ] **Step 1: Write the failing test**

测试目标联系人已有 READY Topic、来源联系人已 merged 且其 archived Topic 有邮件和 WeCom 摘要时，collect 返回这些来源；目标联系人现有已归类来源、群摘要和其他联系人的来源不返回。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceMergeReconciliationTest test`

Expected: FAIL，因为输入服务当前只查询 `NOT EXISTS ai_topic_items` 的来源。

- [ ] **Step 3: Implement bounded merge-source queries**

为消息、电话记录、企业微信摘要各增加“归档 Topic 来源”查询，按 `occurred_at/send_time` 排序并复用现有 `maxInputRecords`、`maxInputBytes` 上界；目标联系人 owner 的正常未归类查询保持不变。去重键使用来源类型加来源 UUID，避免同一来源同时从普通查询和合并查询进入批次。

- [ ] **Step 4: Run focused tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicInputServiceMergeReconciliationTest,AiTopicInputServiceTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputServiceMergeReconciliationTest.java
git commit -m "feat: include merged contact history in topic input"
```

### Task 3: AI 成功后原位迁移来源项

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceMergeReconciliationTest.java`

**Interfaces:**
- `AiTopicItemMapper.moveExistingSourceToTopic(UUID topicId, UUID messageId, UUID callRecordId, UUID wecomSummaryJobId)` 在来源仍属于被合并联系人归档 Topic 时更新 `topic_id`；目标已存在同来源时返回 0 且不产生重复。
- `AiTopicService.generate` 对每个 AI assignment 先尝试插入新来源；插入冲突时调用受 owner 限制的原位迁移方法，禁止无条件覆盖其他 Topic。

- [ ] **Step 1: Write the failing test**

覆盖三种结果：高关联来源移动到已有目标 Topic；低关联来源创建新 Topic；AI 输出非法、超阈值失败或网关异常时，来源仍留在归档 Topic 且目标没有半成品来源。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicServiceMergeReconciliationTest test`

Expected: FAIL，因为当前冲突只会跳过 `insertIfAbsent`，不会移动归档来源。

- [ ] **Step 3: Implement transactional source transfer**

在 `generate` 的已有事务中增加来源迁移分支；只有来源类型与 owner、归档 Topic 通过 SQL 条件校验时才更新 `topic_id`。迁移完成后更新目标 Topic 的 `last_occurred_at`、版本和 AI 生成版本；不删除来源 Topic，保留其历史版本和审计。

- [ ] **Step 4: Run focused tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicServiceMergeReconciliationTest,AiTopicServiceRegressionTest,AiTopicGenerationWorkerTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicModels.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicServiceMergeReconciliationTest.java
git commit -m "feat: reassign merged topic sources after ai matching"
```

### Task 4: 幂等、审计和发布验收

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `docs/superpowers/specs/2026-09-01-ai-topic-wecom-mixed-scope-design.md`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicMergeReconciliationContractTest.java`

**Interfaces:**
- 同一合并事件重复执行不会重复创建 Topic、Topic item 或 generation job。
- AI 输入/输出审计记录 owner、来源类型、来源 UUID 和校验阶段，不记录正文之外的敏感信息。
- 目标联系人 Topic 查询只展示目标 `CONTACT` owner 的 READY Topic；旧归档 Topic 不会在目标页面重复展示。

- [ ] **Step 1: Write contract tests**

验证重复活动只产生一个 owner-scoped generation job；生成失败后重新执行仍能读取归档来源；成功后再次 collect 不返回已迁移来源；群 Topic 和其他联系人来源始终排除。

- [ ] **Step 2: Run focused and full tests**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest='*AiTopic*Test,*ContactGroup*Test' test
mvn -q test
mvn -q -DskipTests package
```

Expected: 全部通过，生成 `backend/target/message-center.jar`。

- [ ] **Step 3: Verify documentation and git boundary**

运行 `git diff --check`、检查 `git status --short`、确认只包含本任务文件；更新部署说明时只引用新 Jar，不修改前端或专区镜像协议。

- [ ] **Step 4: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java \
  docs/superpowers/specs/2026-09-01-ai-topic-wecom-mixed-scope-design.md \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicMergeReconciliationContractTest.java
git commit -m "test: verify topic reconciliation idempotency"
```

## 停止条件

- 任何迁移 SQL 无法区分目标 owner 与归档来源 owner 时停止，不用无条件 `UPDATE ai_topic_items`。
- AI 输出不能表达来源类型或来源归属时，保持现有严格 assignment 合同，不用模糊匹配或前端补偿。
- 数据库唯一约束与原位迁移发生冲突时先补事务级迁移测试，不删除约束、不复制来源。

## 执行记录

- Task 1-3 已在提交 `697c402` 完成。
- Task 4 已补充 `AiTopicMergeReconciliationContractTest`，覆盖 owner/指纹幂等、归档来源归属、个人企业微信 DIRECT 隔离、原位迁移边界和 READY 时间轴过滤。
- `AiTopicInputService` 已改为先汇总普通来源与合并来源，再统一应用 `maxInputRecords`/`maxInputBytes`，避免普通来源先占满上限导致合并历史被截断。
- 后端专项测试通过；全量测试 `926` 个测试中 `0` 个断言失败，剩余 `10` 个错误均为当前环境无法提供有效 Docker daemon 的 Testcontainers 集成测试。
- `mvn -q -DskipTests package` 已生成 `backend/target/message-center.jar`。
