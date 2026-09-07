# 企业微信消息级摘要验收记录

## 本轮实现

- `V30__wecom_message_summary_jobs.sql` 建立消息级幂等任务和诊断字段。
- `WeComChatDataStore` 在三条消息落库路径中同事务写入 `PENDING` 任务。
- `WeComMessageSummaryWorker` 单条提交/轮询官方能力，保存官方响应、摘要、校验阶段和错误码。
- 保留策略排除 `PENDING`、`SUBMITTED`、`RETRY_WAIT` 对应消息；每日 scheduler 不再创建新按日批量任务。
- 有界历史补偿和后台单条/分页查询接口已接通。

## 实际命令和结果

专项门禁：

```text
mvn -Dtest=WeComModuleIsolationTest,WeComMessageSummaryControllerTest,WeComMessageSummaryWorkerTest,WeComMessageSummaryBackfillTest,WeComChatDataRetentionSummaryProtectionTest,WeComChatDataStoreMessageSummaryEnqueueTest,WeComMessageSummaryRepositoryTest,WeComMessageSummarySchemaContractTest test
```

结果：`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`。

全量回归：

```text
mvn test
```

结果：编译完成，但全量测试未通过。已确认的失败是：

- `TopicAiResponseParserTest.rejectsAssignmentsThatDoNotCoverEveryAllowedSource`：现有 Topic WIP 的断言失败，本轮未修改该模块。
- 23 个 Testcontainers/HTTP 测试无法启动 Docker 或受限 Unix socket，错误为 `Could not find a valid Docker environment` / `Operation not permitted`。
- 其余本轮摘要专项测试通过。

## 发布前剩余验收

具备 PostgreSQL 和 Docker 环境后执行：

```bash
cd demo/message-center-spring/backend
mvn test
mvn -Pproduction -DskipTests package
```

确认 Flyway 从 V29 升级到 V30、全量测试通过，并检查 `target/message-center.jar`。部署后查询 `/api/v1/wecom/message-summaries/{msgid}`，验证状态从 `PENDING` 到 `SUBMITTED`/`COMPLETED` 或带阶段的 `RETRY_WAIT`/`FAILED`，同时确认数据库审计字段没有凭据和正文。

## 2026-09-02 生产故障修复验收

针对已提交官方任务在 `RETRY_WAIT` 时无法完成，以及历史 `DIRECT` 会话没有
`contact_identity_id` 导致 Topic 下游投影失败的问题，执行：

```text
mvn -q -Dtest=WeComMessageSummarySchemaContractTest,WeComDirectConversationIdentityBackfillSchemaTest,WeComMessageSummaryRepositoryTest,WeComMessageSummaryWorkerTest,WeComMessageSummaryControllerTest test
mvn -q -DskipTests package
```

结果：专项测试通过，产物 `target/message-center.jar` 包含 `V38__backfill_wecom_direct_contact_identity.sql`
和 `V39__wecom_message_summary_error_diagnostic.sql`。本机完整 Spring 启动到 Web 容器后被环境的
MinIO socket 限制阻断；该限制与企业微信摘要 worker、Flyway 迁移和 `ContactService` 构造器无关。

部署后应检查目标任务的 `status`、`summary`、`validation_stage`、`last_error_code` 和
`last_error_diagnostic`。预期有官方 job id 且官方已完成的任务最终为 `COMPLETED`，并清空错误字段。
