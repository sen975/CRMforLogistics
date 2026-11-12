# 联系人 AI 标签、画像与增量记忆系统验收记录

日期：2026-09-14

## 文档状态

当前验收记录，对应设计
`specs/2026-09-10-contact-ai-memory-design.md`
和实施计划
`plans/2026-09-11-contact-ai-memory.md`。

## 验收范围

- 入站消息成功入库后将联系人记忆状态置为 `DIRTY`。
- 午夜后的 worker 领取租约，读取受限增量上下文并调用结构化 LLM gateway。
- 在同一事务中写入观察、长期事实、AI 标签、标签/事实证据、画像版本、审计和成功游标。
- LLM 失败时保留上一版画像、事实、AI 标签和成功游标，并进入重试状态。
- AI 标签与人工标签分离，联系人查询按 `contacts.created_by` 隔离 owner。
- 联系人详情展示画像、AI 标签和状态；人工标签保持原有响应和写入路径。

## 已验证

后端生产代码编译：

```bash
cd demo/message-center-spring/backend
mvn -DskipTests compile
```

退出码为 `0`。

端到端测试源码已完成编译，测试内容覆盖真实 PostgreSQL/Testcontainers
链路、人工标签隔离、AI 标签颜色/证据、画像版本、owner 隔离和 LLM 失败原子性：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ContactMemoryEndToEndTest test
```

测试编译成功，但运行前置阶段被本机 Docker 环境阻断，未进入测试方法执行。

前端：

```bash
cd demo/message-center-spring/frontend
npm test
npm run build
```

`npm test` 通过：源码测试 37/37，UI 测试文件 63/63、测试 273/273。
生产构建退出码为 `0`。

## 环境阻断

- Testcontainers 无法获得可用 Docker daemon。Docker socket 返回的 daemon
  元数据为空，Testcontainers 报告 `Could not find a valid Docker environment`；
  因此无法在当前环境启动 PostgreSQL 并完成端到端数据库验收。
- 联系人记忆单元测试命令未能执行，因为 Maven testCompile 同时编译了工作区
  中的 WhatsApp WIP 测试，`WhatsAppAccountControllerTest` 在第 62 行调用了
  不存在的 `verify(WhatsAppAccountLifecycleService)` 方法。
- 上述 WhatsApp 测试错误和 Docker 环境不属于本 Task，未修改用户 WIP。

## 未执行门禁

- 未完成本机 PostgreSQL/Testcontainers 端到端测试方法执行。
- 未完成后端全量 `mvn clean test`；当前受上述测试编译错误和 Docker 环境共同影响。
- 未使用真实 LLM 凭据或线上数据执行记忆生成；本轮仅使用测试替身验证 gateway 合同。
- 未进行浏览器人工点击验收；前端自动化测试和生产构建已通过。

## 工作区边界

本 Task 只新增端到端测试和本验收记录，并登记验收文档索引。
工作区中已有的 WhatsApp、Topic、压缩包和 `backend/data/` 改动均未纳入本 Task。
