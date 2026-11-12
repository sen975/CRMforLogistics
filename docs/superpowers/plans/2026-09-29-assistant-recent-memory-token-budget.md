# 助手近期记忆双 Token 预算实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将助手近期原文与滚动摘要改为两个独立的 tokenizer token 预算，消除按轮数/字符数作为语义窗口的行为，同时保留完整消息和最终 prompt 总预算门禁。

**Architecture:** `AssistantRequestGuard` 负责对客户端 fallback history 按近期原文 token 预算保留完整消息；`AssistantConversationContextService` 是服务端持久历史与滚动摘要的唯一 owner，分别应用原文预算和摘要预算；`AssistantConversationLogService` 只负责按有界分页读取足够的服务端原文，不决定语义窗口。最终 prompt 仍由上下文服务做整体 token 校验。

**Tech Stack:** Java 21、Spring Boot、MyBatis、JUnit 5、Mockito、jtokkit `o200k_base`。

## Global Constraints

- 近期原文 `assistant.recent-memory-token-budget` 默认 `8192`，范围 `1024..16384`。
- 滚动摘要 `assistant.summary-memory-token-budget` 默认 `2048`，范围 `256..8192`。
- 两个预算互不扣减；最终 system prompt、工具合同、摘要、近期原文和当前请求仍不得超过 `assistant.compaction.max-input-tokens`。
- token 估算统一使用 `AssistantTokenEstimator` 配置的 `o200k_base`，不得用字符数替代语义预算。
- 消息必须完整保留；单条消息超过对应预算时不截半条，返回结构化降级状态。
- `max-history-turns` 与 `max-history-chars` 只保留为分页/资源保护，不得参与语义裁剪。
- 工作区已有邮件收件人边界 WIP，不得回滚、覆盖或整体暂存。

### Task 1: 配置和客户端 fallback history 的 token 预算

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AssistantConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantRequestGuard.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/AssistantConfigTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantRequestGuardTest.java`

- [ ] 先写配置默认值、边界和 fallback history 按 `o200k_base` 保留完整消息的失败测试。
- [ ] 运行上述专项测试，确认旧的轮数/字符数断言失败。
- [ ] 在 `AssistantConfig` 增加两个带范围校验的字段，并在 yml 增加环境变量绑定。
- [ ] 让 `AssistantRequestGuard` 用 `AssistantTokenEstimator` 从最新消息向前累加；轮数/字符数只作为读取保护，不参与语义窗口。
- [ ] 对单条超预算消息返回结构化降级计数，不截半条；保持当前请求超长仍返回 `REQUEST_INVALID`。
- [ ] 重跑配置与 guard 测试，确认通过。

### Task 2: 服务端上下文 owner 的双预算装配

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationContextIntegrationTest.java`

- [ ] 先添加失败测试：8192 原文预算与 2048 摘要预算独立，摘要不能挤掉近期原文；摘要超过 2048 时省略并保留原文。
- [ ] 运行上下文专项测试确认失败原因是当前 `recentLimit`/共享最终预算逻辑。
- [ ] 将 context service 构造参数替换为 `recentMemoryTokenBudget`、`summaryMemoryTokenBudget`，保留摘要输出上限不超过摘要预算。
- [ ] 移除 `recentLimit` 对 persisted rows 和 guarded history 的语义截取；按 token 预算保留完整消息。
- [ ] 让摘要注入单独受摘要预算限制；摘要过长时不字符串截断，按结构化状态省略。
- [ ] 保持最终 rendered prompt 总预算检查，并在整体超限时按原文、摘要的已有降级顺序处理。
- [ ] 重跑上下文单测与集成测试。

### Task 3: 服务端读取分页与编排接线

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationLogService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantPromptHistoryTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantConversationLogServiceTest.java`

- [ ] 先补测试证明编排层读取的服务端历史不再固定为 8 条，并仍受日志层最大分页保护。
- [ ] 让 context owner 获得有界服务端原文页，再按 token 选择；不把分页上限当作语义预算。
- [ ] 保持服务端历史优先于客户端伪造 history、完整回放和摘要持久化边界不变。
- [ ] 更新相关测试中的构造参数和断言，确认历史排序、裁剪提示、摘要提示未回归。

### Task 4: 文档、专项验收和回归

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-assistant-conversation-context-compaction-design.md`
- Modify: `docs/superpowers/README.md`
- Modify: `docs/superpowers/plans/2026-09-23-assistant-conversation-context-compaction.md`

- [ ] 将旧实施计划中的“8 条/8000 字符”替换为双 token 预算合同。
- [ ] 运行 assistant 配置、guard、context、prompt history 及相关集成专项测试。
- [ ] 运行后端编译与 assistant 专项测试，记录失败/跳过数量；不把无关全量失败伪装成通过。
- [ ] 用 `git diff --check` 和 `git status --short` 复核只包含本任务文档/代码 diff，保留邮件 WIP。
