# 联系人 AI 记忆审查修复验收记录

日期：2026-09-15

## 文档状态

当前验收记录，对应
`plans/2026-09-14-contact-ai-memory-review-fixes.md`。
本记录只覆盖 Task 1–7 的联系人 AI 记忆修复，不改变 WhatsApp、Topic 或其他用户 WIP 的验收边界。

## 已完成实现

- worker lease 使用独立 fencing token；完成和失败更新要求 token、处理中状态和未过期租约。
- 消息增量游标使用 `received_at + message_id`，覆盖延迟入库消息。
- 入站触发写入 durable event，event 可幂等领取、失败退避和重放。
- observation 跨轮累计 evidence，过期 observation 不参与事实晋升；事实、画像和 AI 标签写入保持 owner/contact 归属。
- LLM 上下文包含只读人工标签；AI 标签颜色由服务端 category 映射，画像限制 200 code point，输入和输出均有资源上限。
- attempt 审计覆盖成功、失败和跳过；成功游标、画像指针与记忆写入保持同一事务。
- evidence 使用联系人和 owner 复合外键；AI 标签查询支持 opaque cursor 分页，人工标签保持独立。

## 本轮新增验收

新增 `ContactMemoryConcurrencyTest`，覆盖真实 PostgreSQL/Testcontainers 场景：

- 过期 worker 不能在新 worker 取得 lease 后提交旧 cursor。
- 第一轮 observation evidence 与第二轮 evidence 可在数据库中累计并指向新 fact。
- trigger event 失败后可再次领取，成功应用后标记 `APPLIED`，状态置为 `DIRTY`。

更新 `ContactMemoryEndToEndTest`：

- 先重放 durable trigger event，再运行 worker。
- 成功 cursor 断言使用 `received_at|message_id`。
- LLM 失败保留画像、事实、AI 标签和成功 cursor，同时新增一条 `FAILED` attempt 审计。

## 实际命令

生产编译：

```bash
cd demo/message-center-spring/backend
mvn -DskipTests compile
```

结果：退出码 `0`，`BUILD SUCCESS`。

新增测试源码独立编译：

```bash
javac --release 17 ... ContactMemoryConcurrencyTest.java ContactMemoryEndToEndTest.java
```

结果：退出码 `0`。由于工作区另有用户 WIP 测试的缺失 import，Maven `testCompile` 不能作为目标类编译入口；本次只将目标测试类和既有测试配置类直接编译到测试输出目录用于运行检查。

真实数据库专项运行：

```bash
mvn -Dtest='ContactMemoryConcurrencyTest,ContactMemoryEndToEndTest' surefire:test
```

结果：未进入测试方法。Testcontainers 报告 `Could not find a valid Docker environment`，Docker socket 返回 `Operation not permitted`。这是环境阻断，不是测试通过，也没有断言失败证据。

前端既有专项结果：

- 前端测试与构建在 Task 6 实现后已通过；本轮未修改前端文件。
- 本轮未重新执行前端命令，因为 Task 7 改动仅涉及后端测试和内部验收文档。

## 未关闭门禁

- 必须在 Docker daemon 可用的环境执行 `ContactMemoryConcurrencyTest`、`ContactMemoryEndToEndTest` 和完整 `ContactMemory*Test`。
- 后端全量 Maven 测试仍受用户 WIP 测试编译错误阻断：`EmailSyncServiceTest`、`ChatAppWebhookProjectorTest`、`WeComMessageProjectorTest` 缺少 `isNull`/`Instant` 导入；本 Task 未修改这些文件。
- 未使用真实 LLM 凭据或生产数据；测试 gateway 使用 Mockito 替身。
- 未进行浏览器人工验收；Task 7 没有前端行为改动。

因此当前状态为 **Not ready to merge**，原因仅为真实 PostgreSQL 门禁和既有用户 WIP 测试编译阻断。

## 工作区边界

本 Task 只包含联系人记忆测试和本验收记录。WhatsApp、Topic、压缩包、`backend/data/` 及其他未授权修改均未纳入提交。
