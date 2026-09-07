# 企业微信每日摘要源会话实现计划

> **执行要求：** 按任务顺序实施，每个任务先写失败测试，再实现；不修改服务器 `.env`、systemd unit 或已生成的前端压缩包。

**目标：** 修复每日摘要对空 `externalUserId` 的 NPE，并让单聊与群聊都以稳定的源会话为边界生成摘要；群聊严格做到“一群一天一份最终摘要”。

**架构：** `wecom_chatdata_messages.source_conversation_id` 与 `wecom_source_conversations.conversation_type` 是摘要会话身份的唯一真源。批处理器按 `(sourceConversationId, summaryDay)` 聚合消息，官方任务仍可因 1000 条限制或 `790040` 拆分，但仓储层按源会话重新聚合为一条 `wecom_daily_summaries`。`user_id/external_user_id` 只保留为单聊展示字段，不再参与任务或摘要唯一键。

**技术栈：** Java 17、Spring Boot 3.4、MyBatis-Plus、PostgreSQL、Flyway、JUnit 5、Mockito。

## 全局约束

- `source_conversation_id` 是新摘要任务必填字段，`conversation_type` 仅允许 `DIRECT|GROUP`。
- 群聊允许 `user_id/external_user_id` 为空；单聊继续保留这两个展示字段，但不以它们分组。
- 同一安装、企业、日期、源会话只能生成一份最终摘要。
- 任务切片仍以 `slice_start/slice_end` 标识；拆分任务最终按源会话聚合。
- 缺失或无法解析源会话的历史消息不进入官方任务，批处理器返回可观测的跳过数量，不能抛 NPE 中断全部会话。
- 不记录 `secretKey`、明文消息或企业微信凭据。
- 保持 `790040` 拆分、轮询、退避、deadline 和最大批次数语义不变。

### Task 1：锁定批处理分组合同

**文件：**

- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryBatcher.java`
- 新增：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryBatcherTest.java`

**步骤：**

- [ ] 为 `StoredMessageReference` 增加 `UUID sourceConversationId` 和 `String conversationType`。
- [ ] 编写失败测试：群聊 `externalUserId=null` 不抛异常；同群同日聚成一组；不同群分开；单聊仍按源会话分组；空源会话被计为跳过。
- [ ] 修改 `WeComChatDataStore.load()`，通过消息与 `wecom_source_conversations` 的映射返回会话类型。
- [ ] 修改 `ConversationDay`、`SummaryBatch` 和排序器，以源会话为身份并对可空展示字段使用 `nullsLast`。
- [ ] 运行：`cd demo/message-center-spring/backend && mvn -q -Dtest=WeComDailySummaryBatcherTest test`，预期全部通过且无 NPE。

### Task 2：迁移摘要数据库合同

**文件：**

- 新增：`demo/message-center-spring/backend/src/main/resources/db/migration/V25__wecom_daily_summary_source_conversation.sql`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComDailySummaryJobEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComDailySummaryEntity.java`
- 新增：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummarySchemaContractTest.java`

**迁移合同：**

- 两张摘要表增加 `source_conversation_id uuid` 与 `conversation_type varchar(20)`。
- 不自动回填历史摘要的源会话：旧任务的 `installation_id` 是字符串身份，无法在迁移中安全假设其与消息表 UUID 安装键一致；历史行保留旧字段且新源会话字段为空，由应用继续只读兼容。
- 删除旧唯一约束 `uq_wecom_daily_summary_job_slice`、`uq_wecom_daily_summary_group`，建立两个部分唯一索引：新行按源会话唯一，历史空源会话行继续按旧单聊字段唯一。
- 新应用生成的任务由 Java 校验源会话必填，不依赖数据库允许空值来创建旧式任务。

**步骤：**

- [ ] 编写迁移合同测试，断言新列、会话类型约束、新旧部分唯一索引和群聊可空展示字段。
- [ ] 运行失败测试确认 V25 缺失。
- [ ] 编写迁移和实体字段。
- [ ] 运行：`cd demo/message-center-spring/backend && mvn -q -Dtest=WeComDailySummarySchemaContractTest test`。

### Task 3：将摘要服务合同切换到源会话

**文件：**

- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryRepository.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryService.java`
- 新增：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryServiceTest.java`

**接口：**

- `DailySummaryKey`、`DailyConversationKey` 增加 `UUID sourceConversationId` 与 `String conversationType`。
- `loadSlice()` 只按 `sourceConversationId` 找会话，不再对可空 `externalUserId` 调用 `.equals()`。
- `DailyRunResult` 增加 `skippedMessages`，供调度日志观察缺失源会话的历史消息。

**步骤：**

- [ ] 编写失败测试：群聊任务 key 正确；两个群任务互不混合；切片加载按源会话；单聊保持；缺失源会话不会阻断其他会话。
- [ ] 修改领域 records、服务创建任务和切片查找逻辑。
- [ ] 保持提交、轮询、摘要结果解析、重试和 `790040` 处理不变。
- [ ] 运行：`cd demo/message-center-spring/backend && mvn -q -Dtest=WeComDailySummaryServiceTest test`。

### Task 4：改造 MyBatis 任务与最终聚合

**文件：**

- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComDailySummaryMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComDailySummaryRepository.java`
- 新增：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComDailySummaryRepositoryTest.java`

**步骤：**

- [ ] 为 insert/select/lock/count/aggregate/split/finalize 查询增加 `source_conversation_id` 与 `conversation_type`。
- [ ] 所有任务组查询只以安装、企业、日期、源会话匹配，展示字段不参与相等判断。
- [ ] `insertSummary` 以源会话部分唯一索引幂等；`combined_summary` 继续按 `slice_start` 有序拼接。
- [ ] 编写仓储测试覆盖群聊空展示字段、两群隔离、多批次最终一行、部分失败为 `PARTIAL`、拆分后仍聚合到原群。
- [ ] 运行：`cd demo/message-center-spring/backend && mvn -q -Dtest=MyBatisWeComDailySummaryRepositoryTest test`。

### Task 5：调度观测、文档与完整验证

**文件：**

- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryScheduler.java`
- 修改：`docs/superpowers/specs/2026-08-26-wecom-daily-summary-group-conversation-design.md`
- 新增：`docs/superpowers/reviews/2026-08-26-wecom-daily-summary-group-conversation-verification.md`

**步骤：**

- [ ] 调度成功日志记录日期、会话数、批次数、缺失源会话跳过数，不记录消息标识或密钥。
- [ ] 运行摘要专项测试：`cd demo/message-center-spring/backend && mvn -q -Dtest='WeComDailySummary*Test,MyBatisWeComDailySummaryRepositoryTest' test`。
- [ ] 运行后端全量测试：`cd demo/message-center-spring/backend && mvn -q test`。
- [ ] 运行生产构建：`cd demo/message-center-spring/backend && mvn -q -Pproduction -DskipTests package`。
- [ ] 检查 `git status --short`，只记录本轮文件，不吸入用户已有 zip、文档或其他 WIP。

## 服务器验收

发布新 JAR 并执行 Flyway V25 后，使用实际 unit `spring_message-center.service` 重启。日志查看：

```bash
tail -f /www/wwwlogs/java/springboot/message-center.log
```

任务验收：

```sql
SELECT summary_day, conversation_type, source_conversation_id,
       status, wecom_job_id, last_error_code, failure_state
FROM wecom_daily_summary_jobs
ORDER BY created_at DESC;
```

最终摘要验收：

```sql
SELECT summary_day, conversation_type, source_conversation_id,
       summary, message_count, completeness, generated_at
FROM wecom_daily_summaries
ORDER BY generated_at DESC;
```

验收标准：同一 `GROUP` 的同一天只有一个非空 `source_conversation_id` 的最终摘要；两个群的 ID 不同；单聊摘要仍存在；日志中不再出现 `StoredMessageReference::externalUserId` 排序 NPE。
