# 助手对话上下文滚动摘要验收记录

日期：2026-09-24

## 已通过

- 摘要专项后端：73/73 通过，0 failures，0 errors，0 skipped。
- V100：PostgreSQL 17.5 Testcontainers 空库执行 Flyway 全量迁移至 v100，1/1 通过。
- Spring 装配：`AppIntegrationTest` 10/10 通过，验证上下文 owner 的显式构造器注入。
- 前端助手面板：26/26 通过。
- 前端生产构建：`npm run build` 通过。
- 摘要数据库故障降级：摘要投影读取/分页数据访问异常时保留近期原文，不把客户端 fallback history 写入摘要。

## 全量回归边界

后端全量命令运行 2133 tests，结果为 1 failure、21 errors、2 skipped。21 个 errors 是上下文服务构造器未显式注入造成的 Spring ApplicationContext 连锁错误，已由 `@Autowired` 修复并通过 `AppIntegrationTest` 复验。剩余两个独立失败不属于本计划：

- `MessageSendApplicationServiceTest.duplicateClientRequestCreatesOnePendingMessageAndOutbox`
- `ContactMemoryEndToEndTest.lateInboundMessageBehindSuccessCursorIsStillSentToTheModel`

未因本轮摘要改动修改这两个测试或对应业务域。

## 未完成事项

共享工作区存在大量既有 WIP 和嵌套 worktree 的 `index.lock`，本轮未暂存、提交或清理任何文件。真实 provider usage 在 provider 不返回 usage 时继续标记为 unavailable；未用字节数推算真实 token usage。
