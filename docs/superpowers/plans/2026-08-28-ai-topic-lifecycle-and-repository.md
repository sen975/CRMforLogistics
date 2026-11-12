# AI Topic 生命周期与弃用仓库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** 将 Topic 改为只处理未归类消息的增量聚合，提供全异步编辑/合并/弃用/恢复、一次完成事件刷新、折叠时间轴和跨联系人弃用 Topic 仓库。

**Architecture:** Spring `aitopic` 包拥有状态、输入过滤、任务、事务和权限；AI 只接收未进入任意 `ai_topic_items` 的 ChatApp、邮件、电话来源，只有 `READY` Topic 是候选。React 只提交 `202 Accepted` 命令，收到无敏感数据的 `topic-snapshot-completed` SSE 后才读取已提交快照。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、Flyway、JUnit 5、React 18、TypeScript、Ant Design、TanStack React Query、Vitest、Testing Library。

## Global Constraints

- 不修改已部署的 `V26__ai_topics.sql`、`V27__ai_topic_job_actor.sql`；新增迁移从 `V28` 开始。
- `wecom` 永不进入输入、指纹、provider payload、Topic 来源、摘要、匹配或仓库。
- Topic 状态只有 `READY`、`ARCHIVED`、`DISCARDED`；`ARCHIVED` 只表示合并历史。
- 来源进入任意状态 Topic 后永不再次进入 AI 输入；弃用/恢复不删除关联。
- 所有 Topic 变更异步返回 `202`，不做 optimistic update；终态各发布一次 `topic-snapshot-completed`。
- 所有查询和 worker 复用联系人访问校验；不暂存或覆盖已有用户 WIP；禁止 `git add .`。
- 每个任务先写失败测试、确认失败、实现、专项测试、精确提交。

## 文件地图

数据库/领域：`backend/src/main/resources/db/migration/V28__ai_topic_lifecycle_and_operation_jobs.sql`；`entity/AiTopicOperationJobEntity.java`；`mapper/AiTopicOperationJobMapper.java`；修改 `AiTopicEntity.java`、`AiTopicModels.java`、`AiTopicMapper.java`、`AiTopicItemMapper.java`。

服务/API：修改 `AiTopicInputService.java`、`TopicAiResponseParser.java`、`AiTopicService.java`、`AiTopicGenerationWorker.java`、`AiTopicVersionMapper.java`；新增 `AiTopicOperationWorker.java`、`AiTopicOperationScheduler.java`；修改 `AiTopicController.java`。

前端：修改 `api/types.ts`、`api/endpoints.ts`、`hooks/useSse.ts`、`hooks/useTopicTimeline.ts`、`components/AiTopicTimeline.tsx`、`components/ContactDetailPanel.tsx`、`router.tsx`、`components/AppLayout.tsx`；新增 `hooks/useTopicRepository.ts`、`pages/TopicRepositoryPage.tsx`。

### Task 1: 生命周期数据库合同

**Files:** 上述 V28 迁移、操作任务实体/Mapper、`AiTopicEntity`、`AiTopicModels`、`AiTopicSchemaContractTest`。

**Interfaces:** `TopicOperationKind { EDIT, MERGE, DISCARD, RESTORE }`；`TopicOperationStatus { PENDING, PROCESSING, COMPLETED, FAILED }`；`TopicOperationProjection(UUID id, TopicOperationKind kind, TopicOperationStatus status, String errorCode, Instant createdAt, Instant completedAt)`。

- [ ] 写失败测试，断言迁移包含 `DISCARDED`、版本变更类型 `DISCARDED/RESTORED`、`ai_topic_operation_jobs`、`(created_by_user_id,idempotency_key)` 唯一约束和四种 kind。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -q -Dtest=AiTopicSchemaContractTest test`，确认因 V28 不存在而失败。
- [ ] 新增迁移：扩展两张 CHECK；操作表包含请求 JSON、预期版本 JSON、幂等键、租约、尝试次数、错误码、完成时间及 runnable/目标索引。实体字段与列一一映射；Mapper `findOrCreate` 必须按幂等键返回同一任务。
- [ ] 重新运行上述测试，确认 PASS；精确提交 `git add <V28> <entity> <mapper> <models> <test> && git commit -m "feat: add ai topic lifecycle contract"`。

### Task 2: 未归类来源隔离

**Files:** `AiTopicItemMapper.java`、`AiTopicInputService.java` 及其测试。

**Interfaces:** `AssignedSourceIds(Set<UUID> messageIds, Set<UUID> callRecordIds)`；`listAssignedSourceIds(UUID contactId)` 查询全部状态 Topic 的来源。

- [ ] 写失败测试：READY、ARCHIVED、DISCARDED 已关联消息均被 `collect` 排除，WeCom 仍被排除。
- [ ] 运行 `mvn -q -Dtest=AiTopicInputServiceTest test`，确认失败。
- [ ] 在 SQL 使用 `NOT EXISTS ai_topic_items`（不要只在内存裁剪后过滤），再做渠道、时间、记录数和字节上限；输入 fingerprint 只覆盖剩余来源。
- [ ] 运行 `mvn -q -Dtest=AiTopicInputServiceTest,AiTopicServiceRegressionTest test`，确认 PASS；精确提交 `git commit -m "fix: exclude assigned sources from ai topic input"`。

### Task 3: 严格分配与无空 Topic

**Files:** `TopicAiResponseParser.java`、`AiTopicService.java` 及 parser/regression 测试。

**Interfaces:** `parse(json, allowedSourceIds)` 要求 assignment 来源集合恰等于允许集合；生成只为成功声明来源的 Topic 写库。

- [ ] 写失败测试：模型遗漏/重复/未知来源拒绝；并发来源冲突不创建空 Topic；低阈值创建新 Topic，`DISCARDED` 不作为候选。
- [ ] 运行 `mvn -q -Dtest=TopicAiResponseParserTest,AiTopicServiceRegressionTest test`，确认失败。
- [ ] 解析累计来源并做集合相等校验；新 Topic 先尝试声明第一条来源，成功后才插入实体；已有 Topic 仅在成功声明来源后更新；每个成功 Topic 只写一条版本。
- [ ] 重跑专项测试并精确提交 `git commit -m "fix: enforce complete ai topic assignments"`。

### Task 4: 异步操作状态机

**Files:** 新增 `AiTopicOperationWorker.java`、`AiTopicOperationScheduler.java`；修改 `AiTopicService.java`、`AiTopicMapper.java`、`AiTopicVersionMapper.java`；新增 worker 测试。

**Interfaces:** `submitEdit/submitMerge/submitDiscard/submitRestore` 只创建任务并返回 `TopicOperationProjection`；`runOnce()` 有界领取任务。

- [ ] 写失败测试：弃用保留 `ai_topic_items` 并写 `DISCARDED` 版本；恢复写 `RESTORED`；过期 version 失败；每个终态只发布一次事件。
- [ ] 运行 `mvn -q -Dtest=AiTopicOperationWorkerTest test`，确认失败。
- [ ] 复用生成 worker 租约/重试；worker 事务内重做权限和版本校验，执行四种状态转换/编辑/合并；完成或最终失败调用 `eventHub.publish("topic-snapshot-completed", "{}")` 一次。
- [ ] 运行 `mvn -q -Dtest=AiTopicOperationWorkerTest,AiTopicServiceRegressionTest test`，确认 PASS；精确提交 `git commit -m "feat: process ai topic operations asynchronously"`。

### Task 5: 生成终态事件与 HTTP 合同

**Files:** `AiTopicGenerationWorker.java`、`AiTopicService.java`、`AiTopicMapper.java`、`AiTopicController.java` 及 controller lifecycle 测试。

**Interfaces:** `GET /contacts/{contactId}/topics` 返回已提交 READY 快照；编辑/合并/弃用/恢复端点均返回 `202` 操作投影；`GET /topic-repository?search=&page=&size=` 返回权限过滤分页。

- [ ] 写 MockMvc 失败测试，断言 discard 返回 `202`，缺失/超长 `Idempotency-Key` 被拒绝，仓库只查 DISCARDED。
- [ ] 运行 `mvn -q -Dtest=AiTopicControllerLifecycleTest test`，确认失败。
- [ ] 接入请求校验、幂等创建、仓库 SQL 的联系人权限 EXISTS；生成成功/永久失败同样发布一次完成事件；查询不等待、不轮询。
- [ ] 运行 `mvn -q -Dtest=AiTopicControllerLifecycleTest,AiTopicGenerationWorkerTest test`，确认 PASS；精确提交 `git commit -m "feat: expose async ai topic lifecycle api"`。

### Task 6: SSE 与无轮询 hooks

**Files:** `useSse.ts`、`useTopicTimeline.ts`；新增对应测试。

**Interfaces:** `useSse(onMessage, eventNames?)` 支持精确事件订阅；Topic hook 无 `refetchInterval`，mutation 只提交任务。

- [ ] 写失败测试：Topic 查询没有 3 秒轮询；只有 `topic-snapshot-completed` 事件才失效当前 Topic query。
- [ ] 运行 `cd demo/message-center-spring/frontend && npm test -- --run src/hooks/useTopicTimeline.test.tsx src/hooks/useSse.test.tsx`，确认失败。
- [ ] 精确注册 SSE 事件；移除 `refetchInterval` 和 mutation 成功立即失效；完成事件才 `invalidateQueries`。
- [ ] 重跑专项测试并精确提交 `git commit -m "fix: refresh topic snapshots only after completion"`。

### Task 7: 右侧折叠时间轴

**Files:** `api/types.ts`、`api/endpoints.ts`、`AiTopicTimeline.tsx`、其测试、`ContactDetailPanel.tsx`。

**Interfaces:** 时间轴接收 `discard` mutation；来源列表仍存在，只在单 Topic 展开时渲染。

- [ ] 写失败测试：整体折叠只留标题/数量；单项默认只留标题/概要；展开后来源按钮仍可定位；弃用提交后旧快照仍可见。
- [ ] 运行 `npm test -- --run src/components/AiTopicTimeline.test.tsx`，确认失败。
- [ ] 使用受控 Collapse/按钮实现两级折叠；保留来源列表、编辑、合并；弃用确认只提交异步命令，不乐观移除。
- [ ] 运行专项测试和 `npm run build`，确认 PASS；精确提交 `git commit -m "feat: add collapsible ai topic timeline"`。

### Task 8: 跨联系人 Topic 仓库

**Files:** 新增 `useTopicRepository.ts`、`TopicRepositoryPage.tsx` 及测试；修改 `router.tsx`、`AppLayout.tsx`。

**Interfaces:** 路由 `/topic-repository`；仓库 hook 接受 `{ search, page, size }`；恢复提交后不乐观修改列表。

- [ ] 写失败测试：跨联系人搜索、分页、来源展开；恢复后在完成事件前仍保留行。
- [ ] 运行 `npm test -- --run src/pages/TopicRepositoryPage.test.tsx`，确认失败。
- [ ] 使用现有 Ant Design Search/List/Pagination；仅展示 DISCARDED，来源展开保留定位信息；恢复确认后提交任务，完成事件才失效仓库 query；导航使用现有图标/Tooltip 模式。
- [ ] 运行 `npm test -- --run src/pages/TopicRepositoryPage.test.tsx src/router.test.tsx && npm run build`，确认 PASS；精确提交 `git commit -m "feat: add discarded topic repository"`。

### Task 9: 文档、产物与最终验收

**Files:** `demo/message-center-spring/README.md`、`docs/superpowers/README.md`、本计划勾选状态。

- [ ] README 说明 `AI_TOPIC_MATCH_THRESHOLD` 是模型分数接受阈值，不是本地相似度；说明三渠道、弃用隔离、异步命令和完成事件刷新。
- [ ] 后端门禁：`cd demo/message-center-spring/backend && mvn -q test && mvn -q -Pproduction -DskipTests package`，确认生成 `target/message-center.jar`。
- [ ] 前端门禁：`cd demo/message-center-spring/frontend && npm test && npm run build`，确认生成 `dist/`。
- [ ] 浏览器检查桌面/移动折叠、来源展开定位、异步弃用/恢复、仓库检索、WeCom 不进入 Topic；Network 不出现 API key。
- [ ] 仅提交本任务文档和计划；交付 Jar、完整 `dist` zip、SHA256 及带 stage/backup/nginx reload 的部署命令。
