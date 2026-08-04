# ChatApp 消息自动同步设计

**状态：** 已实施，待发布包由用户另行构建

**日期：** 2026-07-31

## 1. 背景与目标

消息中心当前只会在用户点击“同步 WhatsApp”、调用 `POST /api/sync/chatapp` 或执行 `sync` CLI 时向阿里云 CAMS 拉取消息。页面每 5 秒执行的刷新只读取本地消息文件，不会主动调用阿里云。

本设计把 ChatApp 历史消息同步变为 web 运行时的后台能力：HTTP 服务监听成功后异步执行首轮拉取，每轮完全结束后等待 5 秒再执行下一轮。后台同步和手动 HTTP 同步共享同一个进程内同步 owner；CLI 使用同一 owner 合同，并通过跨进程同步锁与 web 进程互斥。所有入口都不能并发请求阿里云或并发提交消息文件。

本轮只修改源码、测试、示例配置和文档，不执行 Maven `package`，不修改 `demo/message-center-demo/release/` 下的任何发布物。

## 2. 范围

### 2.1 本轮包含

- web 模式启动后的异步首轮 ChatApp 消息同步。
- 每轮结束后固定等待 5 秒再运行下一轮。
- 后台、HTTP 手动同步的共享单飞实例，以及 CLI 共用的同步 owner 合同。
- 每个 `ListChatappMessage` 请求 15 秒超时和 Future 取消。
- ChatApp 消息文件的进程内互斥、同目录跨进程锁和原子更新。
- 发送、Webhook、手动同步和自动同步共享同一个消息存储并发边界。
- 可收敛关闭、结构化日志、配置和回归测试。

### 2.2 本轮不包含

- 不改变模板库自动同步的 5 分钟运行时。
- 不新增消息 SSE 类型；页面继续按现有 5 秒周期读取本地消息。
- 不改变阿里云分页、增量时间窗口、媒体预缓存或现有 HTTP/CLI 成功响应字段。
- 不引入 cron、systemd timer、外部队列或新 Maven 依赖。
- 不打包、不覆盖或修补现有 release JAR。

## 3. 已确认行为

### 3.1 调度

- `CHATAPP_MESSAGE_AUTO_SYNC_ENABLED` 默认 `true`，显式设置 `false` 时关闭后台消息同步。
- 自动同步只在 `web` 命令中运行；`sync` CLI 只执行当前显式调用，不创建调度器。
- `server.start()` 成功后启动 runtime，首轮以零延迟异步执行，不阻塞 HTTP 监听。
- 采用固定延迟，不采用固定频率。本轮结束后等待 5 秒再开始下一轮。
- 一轮耗时 12 秒时，下一轮最早在第 17 秒开始；不补跑错过的周期。
- 同步失败后同样等待 5 秒再重试，不立即重试。

### 3.2 单飞

- `ChatAppMessageSynchronizer` 是启动一轮消息同步的唯一 owner。
- web runtime 和 `POST /api/sync/chatapp` 委托同一个 owner 实例；CLI `sync` 在独立 JVM 中创建同一类型的 owner，并由跨进程同步锁参与同一互斥合同。
- 同一进程已有一轮运行时，另一个触发立即返回 `lock_busy` 结构化结果，不排队、不访问阿里云。
- 跨进程同步使用目标 `CHATAPP_DATA_FILE` 旁的 `<文件名>.sync.lock` 非阻塞轮次锁，防止两个 JVM 同时拉取同一消息空间。锁忙时本轮返回 `lock_busy`。
- 单飞锁覆盖完整的阿里云同步轮次，但不阻塞发送或 Webhook 写入；短文件提交由独立存储锁保护。

### 3.3 阿里云请求

- 复用现有增量窗口：`SYNC_INCREMENTAL`、`SYNC_OVERLAP_MINUTES`、`SYNC_LOOKBACK_DAYS`、显式开始/结束时间和分页过滤继续生效。
- 每个 `ListChatappMessage` Future 最多等待 15 秒。
- 超时时调用 `future.cancel(true)` 并返回安全的同步失败；中断时同样取消 Future 并恢复线程中断标记。
- 失败不删除、不回滚或清空本地已有消息；已经成功提交的单条消息保持可读。
- 日志和错误不得输出 AccessKey、凭据、完整上游响应、消息正文或 `raw`。

## 4. 架构和 owner

### 4.1 `ChatAppMessageSyncRuntime`

负责 web 生命周期、固定延迟调度和结构化日志，不拥有阿里云请求、消息投影或文件写入规则。

生产入口为：

```java
ChatAppMessageSyncRuntime.open(Config config, ChatAppMessageSynchronizer synchronizer)
```

生命周期为 `NEW -> STARTED -> CLOSING -> CLOSED`：

- `start()` 只允许从 `NEW` 启动一次。
- runtime 使用单线程守护 `ScheduledExecutorService` 和 `scheduleWithFixedDelay(..., 0, 5, SECONDS)`。
- `close()` 先切换到 `CLOSING`，调用 `shutdownNow()`，等待当前同步离开可提交边界后再转为 `CLOSED`。
- 并发调用 `close()` 的线程都等待首个关闭流程完成。
- 关闭线程被中断时继续完成关闭，并在返回前恢复中断标记。

### 4.2 `ChatAppMessageSynchronizer`

负责同步轮次单飞和跨进程同步锁，只委托现有消息同步服务执行具体工作。

结构化结果区分：

- `SUCCEEDED`：轮次已完成，结果包含现有 `SyncResult`。
- `LOCK_BUSY`：同进程或跨进程已有同步，未访问阿里云。

runtime 只记录结果，不把后台结果暴露为新 HTTP 接口。手动 HTTP 和 CLI 继续序列化现有 `SyncResult` 字段，并通过既有 `message` 字段明确表达 `lock_busy`，避免破坏已有客户端合同。

### 4.3 `ChatAppHistorySyncService`

继续负责：

- 计算增量时间窗口。
- 创建阿里云 client、分页调用 `ListChatappMessage`。
- 将上游记录投影为本地消息。
- 委托 `ChatAppHistoryStore` 提交消息。
- 按现有配置处理媒体预缓存。

它不再被 `App` 的多个入口直接作为并发 owner 使用。阿里云 Future 的 15 秒超时和取消在这一层实现，因为这里拥有具体 SDK 请求。

### 4.4 `ChatAppHistoryStore`

它是 `CHATAPP_DATA_FILE` 的唯一写入 owner。所有发送、Webhook 和同步写入都通过该类。

每次 `appendResult` 采用以下短事务：

1. 取得 JVM 内按规范化绝对文件路径共享的进程锁。
2. 取得目标 `CHATAPP_DATA_FILE` 同目录的 `<文件名>.lock` 阻塞文件锁。
3. 重新读取当前文件并按消息 ID 执行插入、合并或跳过。
4. 内容变化时写入同目录临时文件，`FileChannel.force(true)` 后使用 `ATOMIC_MOVE + REPLACE_EXISTING` 替换目标文件。
5. 释放文件锁和进程锁。

锁等待受线程中断和关闭边界约束；获取锁后必须重新读取文件，不能使用锁外旧快照。原子移动不可用时整次写入失败，不降级为直接截断覆盖。

`latestTimestamp()` 使用同一个进程锁读取一致快照，但不长期持有跨进程同步轮次锁。

## 5. 数据流

```text
HTTP 监听完成
  -> ChatAppMessageSyncRuntime.start()
  -> ChatAppMessageSynchronizer.trySync()
  -> 取得进程单飞 + <CHATAPP_DATA_FILE 文件名>.sync.lock
  -> ChatAppHistorySyncService.syncMessages()
  -> ListChatappMessage（每页最多等待 15 秒）
  -> ChatAppHistoryStore.appendResult()
  -> 取得 <CHATAPP_DATA_FILE 文件名>.lock
  -> 原子提交单条新增或更新
  -> 释放存储锁
  -> 完成整轮并释放同步轮次锁
  -> 等待 5 秒
  -> 下一轮
```

发送和 Webhook 不等待整轮同步，只在各自短文件提交期间竞争 `<CHATAPP_DATA_FILE 文件名>.lock`。

## 6. 失败与关闭语义

- 缺少 `CUST_SPACE_ID` 时 runtime 不创建调度任务，只记录一次 `skipped_not_configured`；Web 服务继续启动。
- 同步轮次锁忙时记录 `lock_busy`，不访问阿里云，不写文件。
- 阿里云错误、15 秒超时、无效响应、文件锁失败或原子替换失败时记录 `failed`，保留已有本地消息。
- runtime 日志事件为 `started`、`succeeded`、`failed`、`lock_busy` 和 `skipped_not_configured`。
- 日志只包含耗时、页数、抓取数、新增数、更新数、跳过数、媒体排队/失败数、失败 stage 和异常类型。
- `close()` 进入 `CLOSING` 后，不允许新一轮同步进入提交，也不允许新的媒体缓存任务排队。
- 已经取得存储提交许可的短文件操作必须在 `close()` 返回前结束。
- 正在等待阿里云的调用由中断和 15 秒 Future 超时共同收敛；关闭返回后不得再提交消息或排入媒体任务。
- 15 秒只约束单个 `ListChatappMessage` 请求，不是 runtime 的强制关闭截止；`close()` 优先等待活动轮次和 executor 真正退出，确保返回后没有迟到提交。

## 7. 配置合同

新增：

```env
CHATAPP_MESSAGE_AUTO_SYNC_ENABLED=true
```

固定常量：

| 项目 | 值 | 原因 |
| --- | --- | --- |
| 每轮完成后的延迟 | 5 秒 | 用户确认的自动同步频率 |
| 单个列表请求超时 | 15 秒 | 防止后台任务永久卡住 |

现有 `SYNC_PAGE_SIZE`、`SYNC_MAX_PAGES`、`SYNC_INCREMENTAL`、`SYNC_OVERLAP_MINUTES`、`SYNC_LOOKBACK_DAYS` 和过滤配置保持原语义。本轮不新增可低于 5 秒的间隔配置，也不把请求超时开放为无界环境变量。

## 8. 测试设计

### 8.1 配置

- 默认启用消息自动同步。
- 显式 `false` 可以关闭。
- 非严格布尔值被拒绝。
- 缺少 `CUST_SPACE_ID` 时 runtime 不调度。

### 8.2 调度和生命周期

- HTTP 监听后才启动异步首轮。
- 使用固定延迟：本轮结束前不开始下一轮，结束后完整等待 5 秒。
- 失败后完整等待 5 秒再重试。
- 重复 `start()` 只创建一个任务。
- `start()` 与 `close()` 竞争不恢复已关闭 runtime。
- 关闭、关闭线程中断和并发关闭后均无迟到同步、提交或媒体排队。

### 8.3 单飞与跨进程锁

- 后台与 HTTP/CLI 同时触发时只有一个调用访问阿里云。
- 同一 JVM 单飞锁在成功和失败后都释放。
- 跨进程同步锁忙时返回 `lock_busy`，不等待完整轮次。

### 8.4 上游超时

- 列表 Future 超过 15 秒时取消。
- 中断时取消 Future 并恢复线程中断标记。
- 超时和上游失败不损坏已有消息文件。

### 8.5 消息文件

- 自动同步、发送和 Webhook 并发写入不会丢记录。
- 两个独立 `ChatAppHistoryStore` 实例写同一路径时共享进程锁。
- 跨进程文件锁串行化读改写。
- 更新已有消息使用原子替换；移动失败时旧文件字节不变。
- 获取文件锁后重新读取，能保留另一个写入者刚提交的记录。

### 8.6 集成和门禁

- `App.startWeb()` 创建唯一 `ChatAppMessageSynchronizer`，同时注入 runtime 和 HTTP 手动同步入口。
- CLI `sync` 创建同一类型的 owner、遵守同一个跨进程同步锁合同，但不启动 runtime。
- 页面继续读取本地消息，不新增前端主动拉阿里云逻辑。
- 运行针对性 JUnit、完整 `mvn -q test`、`UnifiedMessageStoreTest` 自运行探针、OpenAPI 合同和 `git diff --check`。
- 不运行 `mvn package`，不检查或修改 release JAR。

## 9. 风险与控制

- **阿里云限流：** 固定延迟和单飞阻止重叠；失败不立即重试。
- **文件丢写：** 所有 ChatApp 写入统一到存储 owner，并用进程锁、文件锁和原子替换闭合读改写。
- **关闭迟到写入：** runtime 生命周期 gate 阻止 `CLOSING` 后的新提交，关闭等待已取得提交许可的短操作。
- **媒体缓存压力：** 保留现有有界线程数和队列；关闭后不接受新任务。
- **工作树污染：** 当前 `App.java`、README、配置和测试含有用户 WeCom WIP；实施时必须使用精确 diff 和文件边界，不得整文件提交无关改动。

## 10. 验收标准

- Web 监听后自动异步拉取一次 ChatApp 消息。
- 每轮结束后至少等待 5 秒再开始下一轮，无重叠、无补跑。
- 上游请求单次最长等待 15 秒，失败后 Web 和旧消息继续可用。
- 后台、手动 HTTP 和 CLI 不会并发执行完整同步轮次。
- 自动同步、发送和 Webhook 并发写入不丢消息。
- runtime 关闭返回后不再写消息或排入媒体缓存。
- 所有相关测试和现有完整门禁通过。
- `demo/message-center-demo/release/` 未发生任何修改。
