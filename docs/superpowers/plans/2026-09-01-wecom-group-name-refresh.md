# 企业微信群昵称异步刷新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让群会话名称以可审计的异步任务更新，并通过完整的外部客户群集合投影可靠区分内外部群。

**Architecture:** 群会话表持有最终名称与群类型投影，名称刷新任务和按安装分页的外部群同步分别持有自己的任务生命周期。HTTP 和 Topic worker 只投递名称任务；外部群同步 worker 持久化分页游标，且只有完整读取最后一页后才提交群类型。

**Tech Stack:** Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、JUnit 5、React、TypeScript、Ant Design、TanStack Query。

## Global Constraints

- 不保存企业微信消息正文、token、secret key 或 API key。
- 任务使用租约、最大尝试次数和有界退避；外部群列表只由独立分页 worker 完整扫描。
- 手动请求先服务端校验当前用户对群会话的访问权限。
- 前端不轮询、不乐观修改群名，只在异步完成 SSE 后重取最终数据。
- 外部群同步每页最多 1000 项、每轮最多 1000 页；失败或未完成时不得修改群类型。
- 只把完整同步后仍为 `UNKNOWN` 的未命中群改为 `INTERNAL`，已有 `EXTERNAL` 不降级。
- 当前工作区有用户 WIP；只修改本计划列出的文件，不回滚或暂存无关文件。

### Task 1: 群名称任务数据合同

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__wecom_group_name_refresh_jobs.sql`
- Modify: `backend/.../entity/WeComSourceConversationEntity.java`
- Create: `backend/.../entity/WeComGroupNameRefreshJobEntity.java`
- Create: `backend/.../mapper/WeComGroupNameRefreshJobMapper.java`
- Test: `backend/.../service/wecom/WeComGroupNameRefreshSchemaContractTest.java`

- [ ] 先写迁移合同测试，断言群会话状态字段、任务表、活跃任务唯一索引和租约字段存在。
- [ ] 运行该测试，确认因 `V34` 缺失而失败。
- [ ] 添加增量 Flyway 迁移、实体和 mapper：任务可被投递、领取、完成与退避，活跃任务按群会话去重。
- [ ] 重跑合同测试，确认通过。

### Task 2: 服务端投递、解析与权限 API

**Files:**
- Create: `backend/.../service/wecom/WeComGroupNameRefreshService.java`
- Create: `backend/.../service/wecom/WeComGroupNameRefreshWorker.java`
- Create: `backend/.../service/wecom/WeComGroupNameRefreshScheduler.java`
- Modify: `backend/.../service/wecom/WeComExternalContactService.java`
- Modify: `backend/.../service/aitopic/AiTopicGenerationWorker.java`
- Modify: `backend/.../web/WeComViewerController.java`
- Test: `backend/.../service/wecom/WeComGroupNameRefreshServiceTest.java`
- Test: `backend/.../service/wecom/WeComGroupNameRefreshWorkerTest.java`
- Test: `backend/.../web/WeComViewerControllerTest.java`

- [ ] 写失败测试：访问授权、24 小时自动冷却、手动投递去重、成功更新、不可获取终止与临时失败退避。
- [ ] 运行专项测试，确认新服务类不存在导致红灯。
- [ ] 实现投递服务、群名专用 lookup、worker/scheduler 与 `202` API；Topic worker 成功群任务后仅调用自动投递服务。
- [ ] 任务终态发布 `wecom-group-name-refresh-completed`，并重跑专项测试。

### Task 3: 群页面入口与最终快照刷新

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/components/wecom/WeComGroupHeader.tsx`
- Modify: `frontend/src/pages/ConversationWorkspace.tsx`
- Test: `frontend/src/components/wecom/WeComGroupHeader.test.tsx`
- Test: `frontend/src/pages/ConversationWorkspace.group-name-refresh.test.tsx`

- [ ] 写失败测试：按钮提交任务、禁用重复点击、仅收到当前群完成事件才失效对应详情。
- [ ] 运行目标 Vitest 测试，确认新 API/回调尚不存在导致红灯。
- [ ] 添加 `POST` API、任务投影、群头部刷新图标与 SSE 事件过滤；完成事件后调用已有最终详情重取回调。
- [ ] 重跑目标测试与前端构建。

### Task 4: 发布验证

**Files:**
- Modify: `docs/superpowers/specs/2026-09-01-wecom-group-name-refresh-design.md`

- [ ] 运行后端专项测试、完整测试、打包，并以实际配置启动 Jar 验证 Spring Context。
- [ ] 运行前端完整测试与构建，检查 `dist/index.html` 和资源中存在“刷新群昵称”入口。
- [ ] 将验证命令、结果和无法实机覆盖的企业微信 API 条件回写设计文档。

### Task 5: 外部客户群集合与原子群类型投影

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__wecom_external_group_sync.sql`
- Create: `backend/.../entity/WeComExternalGroupSyncEntity.java`
- Create: `backend/.../mapper/WeComExternalGroupSyncMapper.java`
- Create: `backend/.../service/wecom/WeComExternalGroupSyncWorker.java`
- Create: `backend/.../service/wecom/WeComExternalGroupSyncScheduler.java`
- Modify: `backend/.../service/wecom/WeComExternalContactService.java`
- Test: `backend/.../service/wecom/WeComExternalGroupSyncSchemaContractTest.java`
- Test: `backend/.../service/wecom/WeComExternalGroupSyncWorkerTest.java`

- [x] 写迁移合同失败测试，断言持久化游标、租约、页数上界、暂存集合和每安装唯一同步状态。
- [x] 写 worker 失败测试：中间页只保存游标，最后一页才提交投影，异常不提交，命中外部且仅未知未命中群变内部。
- [x] 实现客户群列表的同步专用分页接口、状态 mapper、单页 worker 和周期 scheduler。
- [x] 重跑后端专项测试；复核 SQL 展示合同与前端固定群名头部测试。
