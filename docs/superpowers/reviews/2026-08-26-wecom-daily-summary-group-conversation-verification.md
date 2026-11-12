# 企业微信每日摘要群聊隔离验收

## 已验证

- `WeComDailySummaryBatcherTest`：群聊 `externalUserId=null` 不再触发排序 NPE；同群消息聚合；不同群隔离；单聊保持；无源会话历史行被跳过并记录数量。
- `WeComDailySummarySchemaContractTest`：V25 增加源会话字段、会话类型约束和新旧部分唯一索引，不删除历史列。
- `WeComDailySummaryServiceTest`：群聊任务 key 使用 `sourceConversationId + conversationType`，不依赖旧身份字段。
- `MyBatisWeComDailySummaryRepositoryTest`：群聊空 `userId/externalUserId` 可创建任务并写入源会话字段。
- `mvn -q -DskipTests compile`：通过。
- `mvn -q -Pproduction -DskipTests package`：通过。

## 环境限制

`mvn -q test` 已启动 779 个测试，但 22 个依赖 Testcontainers/Docker socket 的集成测试因当前环境禁止访问 Docker 而失败；这不是本次摘要代码的断言失败。专项摘要测试在无 Docker 条件下通过。

## 线上验收

本地构建尚未替换服务器 `/data/project_testing/source/message-center.jar`。发布后需先执行 Flyway V25，再重启 `spring_message-center.service`，并观察：

```bash
tail -f /www/wwwlogs/java/springboot/message-center.log
```

数据库应检查 `wecom_daily_summary_jobs` 和 `wecom_daily_summaries` 的 `conversation_type`、`source_conversation_id`，确认同一群同一天只有一条最终摘要。
