# 企业微信每日摘要按源会话隔离设计

## 目标

Spring 版每日摘要同时支持员工与外部联系人单聊、企业微信群聊，并保证摘要按源会话和自然日隔离：

- 单聊：同一员工与同一外部联系人一天一份摘要。
- 群聊：同一群聊一天一份摘要。
- 多个群聊之间、群聊与单聊之间不得合并摘要。
- 单个会话超过官方单次输入上限时，只拆分提交批次，最终仍聚合为该会话当天的一份摘要。

## 当前问题

`WeComDailySummaryBatcher.loadDay()` 当前按 `userId + externalUserId` 排序和分组。群聊的 `externalUserId` 为空是合法数据，历史迁移数据的 `userId` 也可能为空；直接使用 `String.compareTo()` 会触发空指针，摘要任务在创建前失败。

现有 `wecom_daily_summary_jobs` 与 `wecom_daily_summaries` 的唯一键只表达员工、外部联系人和切片范围，无法表达群聊源会话。

## 方案

以 `source_conversation_id` 作为摘要分组的唯一会话主体，并显式保存 `conversation_type`：

- `DIRECT`：保留员工和外部联系人身份字段，源会话 ID 作为稳定分组键。
- `GROUP`：使用群聊源会话 ID 分组，不要求 `external_userid`，不按群成员拆分。

消息加载层向摘要批处理器提供源会话 ID、会话类型、员工 ID、外部联系人 ID、消息 ID 和解密后的临时密钥。批处理器先按有效源会话分组，再按发送时间和消息 ID 稳定排序；禁止对可能为空的身份字段直接调用自然排序。

## 数据库合同

新增 Flyway 迁移：

- `wecom_daily_summary_jobs` 增加 `source_conversation_id`、`conversation_type`。
- `wecom_daily_summaries` 增加 `source_conversation_id`、`conversation_type`。
- 对任务表和摘要表建立按会话类型分离的唯一约束，保证同一源会话同一天、同一切片只有一条任务，最终摘要只有一条记录。
- 群聊记录必须有有效的源会话 ID；无法解析源会话的历史记录不得进入摘要任务，并由批处理器按日期记录跳过数量（不记录消息正文或密钥）。
- 现有单聊任务数据保持可读；迁移不得删除历史摘要。

最终摘要聚合范围固定为：

```text
installation_id + auth_corp_id + summary_day + source_conversation_id
```

## 运行流程

1. 调度器在北京时间配置时间运行，读取前一自然日的消息引用。
2. 批处理器按源会话分组；群聊每群每天一组，单聊每对话每天一组。
3. 每组最多拆成 `WECOM_DAILY_SUMMARY_MAX_BATCHES` 个官方任务批次。
4. worker 提交 `create_summary_task`，保存官方 job ID。
5. worker 轮询 `get_summary_result`；同一源会话的批次全部结束后聚合摘要。
6. 一个会话失败只影响该会话；其他会话继续处理。

## 错误处理

- 群聊 `externalUserId=NULL` 不视为错误。
- 缺少 `source_conversation_id`、会话类型非法或消息引用漂移，标记对应任务失败并记录结构化错误码。
- 保持现有输入大小、批次上限、重试退避、截止时间和敏感字段不落日志策略。
- 不把群聊伪装成特殊的 `user_id` 或 `external_user_id` 字符串。

## 验收

- 批处理器对群聊空 `externalUserId` 不再抛出 NPE。
- 同一群同一天只创建一个逻辑摘要组；超过 1000 条消息时可有多个 job，但最终只有一条摘要。
- 两个群同一天生成两条互不混淆的摘要。
- 单聊摘要仍按员工、外部联系人和日期正确聚合。
- 数据库迁移、任务状态转换、官方提交/轮询适配和最终聚合测试通过。
- Spring 后端专项测试及生产构建通过。
