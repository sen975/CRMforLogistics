# ChatApp 消息自动同步实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 让消息中心 web 在监听成功后异步拉取 ChatApp 消息，并在每轮结束 5 秒后继续下一轮，同时保证后台、HTTP、CLI、发送和 Webhook 不会并发破坏同一消息文件。

**架构：** `ChatAppMessageSynchronizer` 是完整同步轮次的唯一 owner，负责 JVM 单飞、跨进程轮次锁和关闭 gate；`ChatAppMessageSyncRuntime` 只负责 web 固定延迟调度。`ChatAppHistoryStore` 是消息文件唯一写 owner，通过按绝对路径共享的 JVM 锁、同目录文件锁和原子替换提交；阿里云 SDK 隐藏在可测试 gateway 后，并对每个列表 Future 强制执行 15 秒超时。

**技术栈：** Java 17、Maven、JUnit 5、阿里云 CAMS AsyncClient、JDK `ScheduledExecutorService`、`FileChannel`/`FileLock`、Gson、Node.js OpenAPI 合同测试。

## 全局约束

- 仅修改源码、测试、示例配置和文档；禁止运行 `mvn package` 或 `mvn -DskipTests package`。
- 禁止修改、复制或校验 `demo/message-center-demo/release/` 下的任何发布物。
- `CHATAPP_MESSAGE_AUTO_SYNC_ENABLED` 默认 `true`，只接受严格的 `true`/`false`。
- 首轮在 `server.start()` 成功后异步执行；使用 `scheduleWithFixedDelay(..., 0, 5, TimeUnit.SECONDS)`。
- 每个 `ListChatappMessage` Future 最多等待 15 秒；超时和中断都必须 `cancel(true)`，中断必须恢复标记。
- 同一进程和不同进程的同步轮次均不排队；锁忙立即返回 `lock_busy`，且不访问阿里云。
- 发送、Webhook、HTTP 手动同步、CLI 和后台同步只能通过 `ChatAppHistoryStore` 写 `CHATAPP_DATA_FILE`。
- `close()` 返回后不得再提交消息或排入新的媒体缓存任务。
- 保留现有 `SyncResult` JSON 字段；不新增消息 SSE；页面继续每 5 秒读取本地消息。
- 当前 `App.java`、`Config.java`、README、配置和 `UnifiedMessageStoreTest.java` 含有用户 WeCom WIP。对这些文件只能精确修改和逐段复核，禁止回退、整文件暂存或将无关改动带入提交。
- 本计划执行期间不自动提交共享脏文件。每个任务的“提交边界”只记录预期文件；实际提交须在最终 diff 按任务拆分并得到用户确认后进行。

---

## 文件结构

- 新建 `ChatAppHistoryStoreTest.java`：消息文件 JVM/文件锁、原子替换和并发回归。
- 新建 `ChatAppMessageGateway.java`：核心同步服务消费的分页请求/响应合同。
- 新建 `AliyunChatAppMessageGateway.java`：AsyncClient、15 秒 Future 等待、取消和响应校验。
- 新建 `AliyunChatAppMessageGatewayTest.java`：超时和中断取消 Future 的适配器测试。
- 新建 `ChatAppMessageSyncLock.java`：`<CHATAPP_DATA_FILE 文件名>.sync.lock` 非阻塞轮次锁。
- 新建 `ChatAppMessageSynchronizer.java`：进程内单飞、跨进程互斥、提交 gate 和关闭 owner。
- 新建 `ChatAppMessageSynchronizerTest.java`：单飞、锁忙、失败释放、关闭和迟到提交测试。
- 新建 `ChatAppMessageSyncRuntime.java`：web 固定延迟调度和结构化日志。
- 新建 `ChatAppMessageSyncRuntimeTest.java`：调度、生命周期、失败延迟和关闭竞争测试。
- 修改 `Config.java`、`ConfigTest.java`：新增严格布尔配置访问器。
- 修改 `ChatAppHistoryStore.java`：统一原子读改写事务。
- 修改 `ChatAppSender.java`：发送和 Webhook 委托 store，删除直接写文件逻辑。
- 修改 `ChatAppHistorySyncService.java`：消费 gateway、提交 gate、15 秒超时参数和媒体 executor 关闭。
- 修改 `App.java`：CLI、web runtime 和 HTTP 手动同步统一接入 synchronizer。
- 修改 `UnifiedMessageStoreTest.java`：保留现有自运行探针并补发送/Webhook 统一 owner 回归。
- 修改 `README.md`、`config.example.env`：记录自动同步、关闭开关、5 秒固定延迟、15 秒超时和本地测试方式。

### Task 1：配置合同

**文件：**
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- 修改：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**接口：**
- 产出：`boolean Config.chatappMessageAutoSyncEnabled()`
- 产出：`boolean Config.hasChatAppMessageSyncConfiguration()`

- [x] **Step 1：先写失败测试**

在 `ConfigTest` 增加以下三个测试，并复用该文件现有的临时目录/config 构造方式：

```java
@Test
void chatAppMessageAutoSyncIsEnabledByDefault() {
    assertTrue(new Config(new HashMap<>()).chatappMessageAutoSyncEnabled());
}

@Test
void chatAppMessageAutoSyncCanBeDisabled() {
    assertFalse(new Config(new HashMap<>(Map.of(
            "CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", "false"
    ))).chatappMessageAutoSyncEnabled());
}

@Test
void chatAppMessageAutoSyncRejectsNonBooleanValues() {
    Config config = new Config(new HashMap<>(Map.of(
            "CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", "yes"
    )));
    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            config::chatappMessageAutoSyncEnabled);
    assertEquals("CHATAPP_MESSAGE_AUTO_SYNC_ENABLED must be true or false", error.getMessage());
}
```

- [x] **Step 2：运行测试并确认失败原因**

运行：

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest test
```

预期：编译失败，提示 `chatappMessageAutoSyncEnabled()` 尚不存在。

- [x] **Step 3：实现最小配置访问器**

在模板同步配置访问器旁增加：

```java
public boolean chatappMessageAutoSyncEnabled() {
    return strictBoolean("CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", true);
}

public boolean hasChatAppMessageSyncConfiguration() {
    return !value("CUST_SPACE_ID", "").isBlank();
}
```

不要增加可配置的 5 秒间隔或 15 秒请求超时，二者在 owner 中保持固定常量。

- [x] **Step 4：运行配置测试**

运行：

```bash
mvn -q -Dtest=ConfigTest test
```

预期：`ConfigTest` 全部通过，无 warning。

- [x] **Step 5：复核提交边界**

运行：

```bash
git diff -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java
```

预期：只新增消息自动同步配置合同；现有模板和 WeCom 配置改动保持原样且不被回退。本任务不暂存共享脏文件。

### Task 2：消息文件唯一写 owner

**文件：**
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistoryStore.java`
- 新建：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppHistoryStoreTest.java`

**接口：**
- 保留：`WriteResult appendResult(...) throws Exception`
- 产出：同一规范化绝对路径共享 JVM `ReentrantLock`
- 产出：`<文件名>.lock` 阻塞文件锁和同目录原子提交
- 测试 seam：包级构造器 `ChatAppHistoryStore(Config, AtomicCommitter)`

- [x] **Step 1：写两个 store 实例并发写入的失败测试**

```java
@Test
void twoStoreInstancesDoNotLoseConcurrentInserts() throws Exception {
    Path messages = tempDir.resolve("messages.jsonl");
    Config config = Config.forTests(tempDir, tempDir.resolve("email"), messages,
            tempDir.resolve("templates.json"));
    ChatAppHistoryStore first = new ChatAppHistoryStore(config);
    ChatAppHistoryStore second = new ChatAppHistoryStore(config);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
        Future<?> one = pool.submit(() -> append(first, "one", start));
        Future<?> two = pool.submit(() -> append(second, "two", start));
        start.countDown();
        one.get(2, TimeUnit.SECONDS);
        two.get(2, TimeUnit.SECONDS);
    } finally {
        pool.shutdownNow();
    }
    List<String> lines = Files.readAllLines(messages, StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"one\"")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"two\"")));
}

private static void append(ChatAppHistoryStore store, String id, CountDownLatch start) {
    try {
        start.await();
        store.appendResult(id, "inbound", id, "business", id, "Received",
                "2026-07-31T00:00:00Z", "{}", Map.of());
    } catch (Exception exception) {
        throw new CompletionException(exception);
    }
}
```

- [x] **Step 2：写原子移动失败保留旧字节的失败测试**

```java
@Test
void failedAtomicMoveLeavesExistingFileUnchanged() throws Exception {
    Path messages = tempDir.resolve("messages.jsonl");
    byte[] original = ("{\"id\":\"kept\",\"direction\":\"inbound\","
            + "\"timestamp\":\"2026-07-31T00:00:00Z\"}\n")
            .getBytes(StandardCharsets.UTF_8);
    Files.write(messages, original);
    Config config = Config.forTests(tempDir, tempDir.resolve("email"), messages,
            tempDir.resolve("templates.json"));
    ChatAppHistoryStore store = new ChatAppHistoryStore(config, (source, target) -> {
        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
    });

    assertThrows(AtomicMoveNotSupportedException.class, () -> store.appendResult(
            "new", "inbound", "user", "business", "new", "Received",
            "2026-07-31T00:00:01Z", "{}", Map.of()));
    assertArrayEquals(original, Files.readAllBytes(messages));
}
```

- [x] **Step 3：运行 store 测试并确认失败**

运行：

```bash
mvn -q -Dtest=ChatAppHistoryStoreTest test
```

预期：并发测试会暴露丢写，且测试 seam 构造器尚不存在。

- [x] **Step 4：实现统一短事务**

在 `ChatAppHistoryStore` 中增加以下 owner 结构，并让 `latestTimestamp()` 与 `appendResult()` 使用规范化 `file` 和共享锁：

```java
private static final ConcurrentMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

@FunctionalInterface
interface AtomicCommitter {
    void move(Path source, Path target) throws IOException;
}

private final Path file;
private final ReentrantLock processLock;
private final AtomicCommitter atomicCommitter;

public ChatAppHistoryStore(Config config) {
    this(config, (source, target) -> Files.move(source, target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
}

ChatAppHistoryStore(Config config, AtomicCommitter atomicCommitter) {
    this.file = config.chatappDataFile().toAbsolutePath().normalize();
    this.processLock = PROCESS_LOCKS.computeIfAbsent(file, ignored -> new ReentrantLock());
    this.atomicCommitter = Objects.requireNonNull(atomicCommitter);
}
```

`appendResult()` 的完整提交顺序必须是：

```java
processLock.lockInterruptibly();
try {
    Files.createDirectories(file.getParent());
    Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
    try (FileChannel lockChannel = FileChannel.open(lockFile,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         FileLock ignored = lockChannel.lock()) {
        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Mutation mutation = mergeOrAppend(lines, record);
        if (mutation.result() == WriteResult.SKIPPED) {
            return WriteResult.SKIPPED;
        }
        Path temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            byte[] bytes = serializeLines(lines);
            try (FileChannel output = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(ByteBuffer.wrap(bytes));
                output.force(true);
            }
            atomicCommitter.move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
        return mutation.result();
    }
} finally {
    processLock.unlock();
}
```

`mergeOrAppend` 必须在拿到文件锁后读取的 `lines` 上按 ID 合并；新增和更新都走上述原子替换。`serializeLines` 对每一行写 UTF-8，并保持文件末尾一个系统换行。`latestTimestamp()` 至少持有同一 `processLock` 读取一致快照。原子移动不支持时直接失败，不降级为截断覆盖。

- [x] **Step 5：补齐锁后重读与重复 ID 测试**

同 JVM 的重叠 `FileLock` 会抛出 `OverlappingFileLockException`，不能模拟跨进程等待。测试必须用 `ProcessBuilder` 启动 `ChatAppHistoryStoreTest.LockHolder` 子 JVM：子进程取得 `<文件名>.lock` 后写 ready 标记并等待 release 标记；父进程启动 store 写入并确认尚未完成，然后让子进程在锁内原子写入一条 `external` 记录、创建 release 标记并退出。父进程等待 store 完成后断言结果同时包含 `external` 和 store 新记录，证明获取文件锁后重新读取。子进程使用 `System.getProperty("java.home") + "/bin/java"` 和 `System.getProperty("java.class.path")`，所有等待均限制在 2 秒内并在 `finally` 销毁进程。再断言同 ID 相同内容返回 `SKIPPED`，状态或正文变化返回 `UPDATED` 且记录总数不变。

- [x] **Step 6：运行 store 测试**

运行：

```bash
mvn -q -Dtest=ChatAppHistoryStoreTest test
```

预期：并发、失败保留、锁后重读、`INSERTED/UPDATED/SKIPPED` 全部通过，无遗留 `.tmp` 文件。

- [x] **Step 7：记录提交边界**

预期文件仅为 `ChatAppHistoryStore.java` 和新测试。新测试可单独暂存；实现文件在最终集成 diff 后再决定提交。

### Task 3：发送与 Webhook 接入 store owner

**文件：**
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppSender.java`
- 修改：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**接口：**
- 保留：`public ChatAppSender(Config config)`
- 产出：包级 `ChatAppSender(Config config, ChatAppHistoryStore historyStore)`
- 删除：`appendLocal(...)` 和 `containsMessage(...)` 的直接文件写路径

- [x] **Step 1：写 Webhook 委托 store 的失败回归**

在现有自运行探针中增加一个带计数的测试 store，调用 `appendWebhook` 后断言 `appendResult` 被调用一次，并断言消息可从真实测试文件读取。测试 store 不伪造成功数据，仍调用 `super.appendResult(...)`：

```java
AtomicInteger writes = new AtomicInteger();
ChatAppHistoryStore history = new ChatAppHistoryStore(config) {
    @Override
    public WriteResult appendResult(String id, String direction, String from, String to,
                                    String text, String status, String timestamp,
                                    String raw, Map<String, String> extra) throws Exception {
        writes.incrementAndGet();
        return super.appendResult(id, direction, from, to, text, status, timestamp, raw, extra);
    }
};
ChatAppSender sender = new ChatAppSender(config, history);
sender.appendWebhook("{\"MessageId\":\"webhook-owner-1\",\"From\":\"user\","
        + "\"To\":\"business\",\"Message\":\"hello\"}");
assertEquals("1", Integer.toString(writes.get()));
assertContains(Files.readString(config.chatappDataFile()), "webhook-owner-1");
```

- [x] **Step 2：运行自运行探针并确认构造器缺失**

运行：

```bash
mvn -q -DskipTests test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
```

预期：首次在 test-compile 阶段因注入构造器不存在而失败。

- [x] **Step 3：注入 store 并删除直接文件逻辑**

构造器改为：

```java
private final ChatAppHistoryStore historyStore;

public ChatAppSender(Config config) {
    this(config, new ChatAppHistoryStore(config));
}

ChatAppSender(Config config, ChatAppHistoryStore historyStore) {
    this.config = Objects.requireNonNull(config);
    this.templateStore = new TemplateStore(config.chatappTemplateFile());
    this.historyStore = Objects.requireNonNull(historyStore);
}
```

把所有 `appendLocal(...)` 调用替换为：

```java
historyStore.appendResult(messageId, direction, from, to, text, status,
        Instant.now().toString(), raw, extra);
```

删除 `appendLocal`、`containsMessage` 及其只为直接文件写入服务的 imports。Webhook 状态 ID、方向和 `findStored` 行为保持原语义。

- [x] **Step 4：运行探针和 store 测试**

运行：

```bash
mvn -q -Dtest=ChatAppHistoryStoreTest test
mvn -q -DskipTests test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
```

预期：全部通过；发送/Webhook 不再包含 `Files.writeString(config.chatappDataFile()`。

- [x] **Step 5：静态确认唯一写 owner**

运行：

```bash
rg -n "writeString\(.*chatappDataFile|Files\.write\(.*chatappDataFile|APPEND" \
  src/main/java/com/crmforlogistics/messagecenter
```

预期：ChatApp 消息文件没有绕过 `ChatAppHistoryStore` 的写入路径。

### Task 4：阿里云消息 gateway 与 15 秒上界

**文件：**
- 新建：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageGateway.java`
- 新建：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AliyunChatAppMessageGateway.java`
- 新建：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AliyunChatAppMessageGatewayTest.java`
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java`

**接口：**
- `MessagePage ChatAppMessageGateway.listMessages(MessageRequest request, Duration timeout) throws Exception`
- `ChatAppMessageGateway.Factory.open()`
- `ChatAppHistorySyncService` 实现 `AutoCloseable`
- `SyncResult syncMessages(CommitGate commitGate) throws Exception`

- [x] **Step 1：定义核心 gateway 合同**

新文件内容：

```java
package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import java.time.Duration;
import java.util.List;

interface ChatAppMessageGateway extends AutoCloseable {
    MessagePage listMessages(MessageRequest request, Duration timeout) throws Exception;

    @Override
    void close();

    record MessageRequest(long startTime, long endTime, int pageIndex, int pageSize,
                          String custSpaceId, String channelType, String businessNumber,
                          String userNumber, String messageStatus, String clientAcceptStatus) {}

    record MessagePage(List<ListChatappMessageResponseBody.Data> messages) {}

    interface Factory {
        ChatAppMessageGateway open() throws Exception;
    }
}
```

- [x] **Step 2：先写 Future 超时和中断取消测试**

`AliyunChatAppMessageGateway` 暴露包级构造器，接收 `ListCall` 和 `Runnable closeAction`。测试使用记录型 `Future`：

```java
@Test
void timeoutCancelsAliyunFuture() {
    RecordingFuture future = new RecordingFuture(false);
    AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(
            request -> future, () -> {});
    assertThrows(TimeoutException.class, () -> gateway.listMessages(request(), Duration.ofMillis(1)));
    assertTrue(future.cancelledWithInterrupt);
}

@Test
void interruptionCancelsFutureAndRestoresInterruptFlag() throws Exception {
    RecordingFuture future = new RecordingFuture(true);
    AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(
            request -> future, () -> {});
    assertThrows(InterruptedException.class,
            () -> gateway.listMessages(request(), Duration.ofSeconds(15)));
    assertTrue(future.cancelledWithInterrupt);
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
}
```

`RecordingFuture.get(long, TimeUnit)` 分别抛出 `TimeoutException` 或 `InterruptedException`，`cancel(boolean)` 记录参数。

- [x] **Step 3：实现 Aliyun adapter**

关键等待逻辑必须完整如下：

```java
Future<ListChatappMessageResponse> future = listCall.execute(toAliyunRequest(request));
final ListChatappMessageResponse response;
try {
    response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
} catch (TimeoutException exception) {
    future.cancel(true);
    throw exception;
} catch (InterruptedException exception) {
    future.cancel(true);
    Thread.currentThread().interrupt();
    throw exception;
}
ListChatappMessageResponseBody body = response == null ? null : response.getBody();
if (body == null) {
    throw new IllegalStateException("ListChatappMessage returned empty body");
}
assertOk("ListChatappMessage", body.getCode(), body.getMessage());
return new MessagePage(body.getData() == null ? List.of() : body.getData());
```

生产 `open(Config)` 创建 AsyncClient，将 `client::listChatappMessage` 注入 `ListCall`，并用 `client::close` 关闭。请求构建严格映射现有过滤字段，不记录 URL、签名、响应正文或凭据。

- [x] **Step 4：让同步服务消费 gateway 和提交 gate**

在 `ChatAppHistorySyncService` 增加：

```java
private static final Duration LIST_REQUEST_TIMEOUT = Duration.ofSeconds(15);

@FunctionalInterface
interface CommitAction {
    void run() throws Exception;
}

@FunctionalInterface
interface CommitGate {
    void commit(CommitAction action) throws Exception;
}
```

构造器注入 `ChatAppMessageGateway.Factory`。提供包级构造器
`ChatAppHistorySyncService(Config, ChatAppHistoryStore, TemplateStore, MediaCacher, ChatAppTemplateSynchronizer, ChatAppMessageGateway.Factory)`，现有构造器均委托它；`defaultMediaCacher(Config)` 调整为包级静态方法，供 `App` 在共享 store 接线时复用。`syncMessages()` 保留为兼容入口并委托：

```java
public SyncResult syncMessages() throws Exception {
    return syncMessages(CommitAction::run);
}
```

分页循环改为 `gateway.listMessages(request, LIST_REQUEST_TIMEOUT)`。每条消息投影后，通过 gate 完成消息提交和媒体排队边界：

```java
ProjectedChatAppMessage message = project(row);
commitGate.commit(() -> appendAndPrecache(message, result));
```

同步服务实现 `AutoCloseable`；`close()` 原子禁止新媒体任务，执行 `mediaExecutor.shutdownNow()`，最多等待 15 秒，处理中断时完成关闭后恢复中断标记。`queueMediaCache` 在关闭后返回结构化 media failure，不接受新任务。

- [x] **Step 5：增加 service gateway 参数测试**

用 fake gateway 记录每页收到的 timeout 和 request，断言：timeout 恒为 15 秒；分页/开始结束时间/过滤字段保持现有语义；第二页返回空列表时停止；gateway 总会关闭。

- [x] **Step 6：运行 adapter 与现有同步测试**

运行：

```bash
mvn -q -Dtest=AliyunChatAppMessageGatewayTest test
mvn -q -DskipTests test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
```

预期：超时/中断取消测试、分页和现有媒体预缓存探针全部通过。

### Task 5：完整同步轮次 owner

**文件：**
- 新建：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageSyncLock.java`
- 新建：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageSynchronizer.java`
- 新建：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppMessageSynchronizerTest.java`

**接口：**
- `static ChatAppMessageSynchronizer create(Config config, ChatAppHistorySyncService syncService)`
- `Outcome sync() throws Exception`
- `void close()`
- `Outcome(Status status, SyncResult result)`，`Status` 为 `SUCCEEDED`、`LOCK_BUSY`

- [x] **Step 1：实现独立跨进程轮次锁文件名合同**

`ChatAppMessageSyncLock.tryAcquire(Path messageFile)` 规范化绝对路径，并使用：

```java
Path lockFile = target.resolveSibling(target.getFileName() + ".sync.lock");
```

通过 `FileChannel.tryLock()` 非阻塞获取；`null` 和 `OverlappingFileLockException` 都返回 `Optional.empty()`；其他异常关闭 channel 后继续抛出。`Handle.close()` 幂等释放 lock 和 channel。

- [x] **Step 2：写单飞和锁忙失败测试**

```java
@Test
void concurrentTriggersCallServiceOnlyOnce() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    ChatAppMessageSynchronizer synchronizer = synchronizer(gate -> {
        calls.incrementAndGet();
        entered.countDown();
        release.await();
        return result("done");
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
        Future<ChatAppMessageSynchronizer.Outcome> running = pool.submit(synchronizer::sync);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ChatAppMessageSynchronizer.Outcome busy = synchronizer.sync();
        assertEquals(ChatAppMessageSynchronizer.Status.LOCK_BUSY, busy.status());
        assertEquals("lock_busy", busy.result().message);
        assertEquals(1, calls.get());
        release.countDown();
        assertEquals(ChatAppMessageSynchronizer.Status.SUCCEEDED,
                running.get(2, TimeUnit.SECONDS).status());
    } finally {
        release.countDown();
        synchronizer.close();
        pool.shutdownNow();
    }
}
```

再由测试直接持有 `<messages>.sync.lock`，断言 `sync()` 返回 `LOCK_BUSY` 且 fake service 调用数为 0。

- [x] **Step 3：实现 synchronizer 生命周期和结果合同**

核心结构：

```java
public final class ChatAppMessageSynchronizer implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final Path messageFile;
    private final SyncAction syncAction;
    private final CloseAction closeAction;
    private Lifecycle lifecycle = Lifecycle.OPEN;
    private Thread activeThread;
    private boolean running;

    public record Outcome(Status status, SyncResult result) {}
    public enum Status { SUCCEEDED, LOCK_BUSY }
}
```

`sync()` 在 `lifecycleLock` 下同时检查 `OPEN` 和 `running`。已运行则返回：

```java
SyncResult result = new SyncResult("chatapp");
result.message = "lock_busy";
return new Outcome(Status.LOCK_BUSY, result);
```

取得进程内 owner 后再尝试 `ChatAppMessageSyncLock`；跨进程锁忙返回相同结果。完整同步调用必须使用提交 gate：

```java
SyncResult result = syncAction.run(this::commitIfOpen);
return new Outcome(Status.SUCCEEDED, result);
```

`commitIfOpen` 必须在 `lifecycleLock` 下检查 `OPEN`，并在同一个临界区执行短 `CommitAction`，确保关闭状态与文件提交/媒体排队之间没有检查后竞态。`finally` 必须释放 `running`、清空 `activeThread` 并 `notifyAll()`，成功和失败都不能遗留单飞状态。

- [x] **Step 4：实现关闭测试和关闭逻辑**

测试应让 fake sync 停在提交 gate 前，再调用 `close()`：关闭必须中断 active thread，迟到提交抛出 `SyncFailure(stage=cancelled)`，文件写计数保持 0；并发两个 close 调用都必须等同一 active sync 退出。

`close()` 顺序：设置 `CLOSING`、中断 `activeThread`、等待 `running=false`、调用一次 `closeAction` 关闭媒体 executor、设置 `CLOSED` 并唤醒并发关闭者。等待时若关闭线程被中断，记录标记但继续关闭，返回前恢复中断。关闭后调用 `sync()` 返回结构化取消异常，不访问阿里云。

- [x] **Step 5：运行 synchronizer 测试**

运行：

```bash
mvn -q -Dtest=ChatAppMessageSynchronizerTest test
```

预期：进程内单飞、跨进程锁、失败后释放、关闭中断、无迟到提交和并发关闭全部通过。

### Task 6：5 秒固定延迟 runtime

**文件：**
- 新建：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageSyncRuntime.java`
- 新建：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppMessageSyncRuntimeTest.java`

**接口：**
- `static ChatAppMessageSyncRuntime open(Config config, ChatAppMessageSynchronizer synchronizer)`
- `void start()`
- `boolean enabled()`
- `void close()`

- [x] **Step 1：先写调度参数测试**

使用记录型 `ScheduledExecutorService`，调用生产参数构造的 runtime 后断言唯一调度调用为：initial delay `0`、delay `5`、unit `SECONDS`，并且调用的是 `scheduleWithFixedDelay` 而非 `scheduleAtFixedRate`。

```java
runtime.start();
runtime.start();
assertEquals(1, executor.fixedDelayCalls.get());
assertEquals(0L, executor.initialDelay);
assertEquals(5L, executor.delay);
assertEquals(TimeUnit.SECONDS, executor.unit);
```

- [x] **Step 2：写真实时序的短间隔构造器测试**

包级测试构造器允许传入 `long delay, TimeUnit delayUnit`，但生产 `open` 固定使用 `5, TimeUnit.SECONDS`。测试让首轮阻塞 150ms、测试参数为 `80, TimeUnit.MILLISECONDS`，记录两轮开始时间；断言第二轮未在第一轮结束前开始，且两轮之间至少 80ms。失败轮次也必须等待完整 delay 后再运行下一轮。

- [x] **Step 3：实现 runtime**

生产 open：

```java
public static ChatAppMessageSyncRuntime open(
        Config config, ChatAppMessageSynchronizer synchronizer) {
    boolean requested = config.chatappMessageAutoSyncEnabled();
    boolean configured = config.hasChatAppMessageSyncConfiguration();
    boolean enabled = requested && configured;
    if (requested && !configured) {
        System.err.println("chatapp.message_sync event=skipped_not_configured stage=config");
    }
    ScheduledExecutorService executor = enabled
            ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chatapp-message-sync");
                thread.setDaemon(true);
                return thread;
            })
            : null;
    return new ChatAppMessageSyncRuntime(enabled, 5, TimeUnit.SECONDS,
            synchronizer::sync, synchronizer::close, executor);
}
```

`start()` 只能从 `NEW` 转到 `STARTED`，并执行：

```java
executor.scheduleWithFixedDelay(this::runOnce, 0, delay, delayUnit);
```

生产调度必须精确使用 `5, TimeUnit.SECONDS`。`runOnce` 捕获异常，输出结构化事件 `started/succeeded/failed/lock_busy`，只记录 duration、pages、fetched、saved、updated、skipped、mediaQueued、mediaFailed、stage 和异常类型，不记录消息正文、raw、请求 URL 或凭据。

- [x] **Step 4：实现关闭竞争测试**

覆盖：关闭前未 start、重复 start、start/close 竞争、运行中 close、关闭线程被中断、两个 close 并发等待。`close()` 必须先转 `CLOSING`，`shutdownNow()` 停止调度，再调用 synchronizer close action 终止共享 owner，然后等待 executor 退出并转 `CLOSED`；close action 只执行一次。关闭后调度 runnable 即使被测试手动调用也不得开始新同步。

- [x] **Step 5：运行 runtime 测试**

运行：

```bash
mvn -q -Dtest=ChatAppMessageSyncRuntimeTest test
```

预期：固定延迟、零延迟首轮、失败后延迟、幂等 start、并发 close 和无关闭后同步全部通过。

### Task 7：App 接线、HTTP/CLI 合同与文档

**文件：**
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- 修改：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/SyncResult.java`（仅当需要集中创建 `lock_busy`，否则不改）
- 修改：`demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- 修改：`demo/message-center-demo/contracts/openapi/message-center-v1.yaml`（仅核对现有响应合同，字段不变时不改）
- 修改：`demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`（仅在现有合同缺少 `lock_busy` 兼容断言时补测试）
- 修改：`demo/message-center-demo/config.example.env`
- 修改：`demo/message-center-demo/README.md`
- 修改：`docs/superpowers/specs/2026-07-31-chatapp-message-auto-sync-design.md`

**接口：**
- web runtime 和 `POST /api/sync/chatapp` 共享一个 `ChatAppMessageSynchronizer`
- CLI `sync` 创建同一 owner 类型，但不启动 runtime
- HTTP/CLI 继续返回现有 `SyncResult` JSON

- [x] **Step 1：写 App 接线测试 seam**

把 route 参数从 `ChatAppHistorySyncService` 改为 `ChatAppMessageSynchronizer`。在现有 route 测试方式下触发 `POST /api/sync/chatapp`，用 fake synchronizer 返回 `LOCK_BUSY`，断言 HTTP 200 且响应仍是 `SyncResult` 字段，`message` 等于 `lock_busy`，fake service 未被直接调用。

- [x] **Step 2：接入 CLI owner**

`sync` 分支改为：

```java
ChatAppTemplateSynchronizer templates = ChatAppTemplateSynchronizer.create(config);
try (ChatAppMessageSynchronizer synchronizer =
             ChatAppMessageSynchronizer.create(config, templates)) {
    System.out.println(GSON.toJson(synchronizer.sync().result()));
}
return;
```

CLI 不创建 scheduler。跨进程锁忙时正常输出带 `message=lock_busy` 的现有 JSON，不打印栈或凭据。

- [x] **Step 3：接入 web 共享 owner 和 runtime**

在 `startWeb` 中只创建一次 `ChatAppHistoryStore`，并注入 sender 与同步 service：

```java
ChatAppHistoryStore chatAppHistoryStore = new ChatAppHistoryStore(config);
ChatAppSender chatAppSender = new ChatAppSender(config, chatAppHistoryStore);
ChatAppTemplateSynchronizer templateSynchronizer = ChatAppTemplateSynchronizer.create(config);
ChatAppHistorySyncService chatAppSyncService = new ChatAppHistorySyncService(
        config, chatAppHistoryStore, new TemplateStore(config.chatappTemplateFile()),
        ChatAppHistorySyncService.defaultMediaCacher(config), templateSynchronizer,
        () -> AliyunChatAppMessageGateway.open(config));
ChatAppMessageSynchronizer messageSynchronizer =
    ChatAppMessageSynchronizer.create(config, chatAppSyncService);
ChatAppMessageSyncRuntime messageRuntime =
        ChatAppMessageSyncRuntime.open(config, messageSynchronizer);
```

若为保持构造器边界而让 `create(config, templateSynchronizer)` 内部创建 store/service，则必须确保 sender 显式共享同一路径 store owner；不能让 route 直接持有 service。

`server.start()` 成功后按以下顺序启动：

```java
server.start();
templateRuntime.start();
messageRuntime.start();
```

shutdown hook 先 `messageRuntime.close()`，再关闭模板 runtime、其他运行时、event hub 和 server。`POST /api/sync/chatapp` 改为：

```java
SyncResult result = messageSynchronizer.sync().result();
events.publish(syncEvent("chatapp", result.message));
writeJson(exchange, 200, result);
```

- [x] **Step 4：核对不新增消息 SSE 和浏览器阿里云轮询**

运行：

```bash
rg -n "scheduleWithFixedDelay|sync/chatapp|setInterval|templates-changed" \
  src/main/java/com/crmforlogistics/messagecenter/App.java \
  src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageSyncRuntime.java
```

预期：阿里云消息同步只由 server runtime 或手动 HTTP/CLI 触发；页面 `setInterval` 继续读取本地 API，没有新增自动调用 `/api/sync/chatapp`，也没有新增 message SSE 类型。

- [x] **Step 5：更新示例配置和运维文档**

在 `config.example.env` 的 ChatApp 同步段增加：

```env
# Web 启动后异步拉取消息；每轮完成 5 秒后开始下一轮
CHATAPP_MESSAGE_AUTO_SYNC_ENABLED=true
```

README 明确写出：默认开启、缺少 `CUST_SPACE_ID` 时跳过但 web 正常启动、固定延迟 5 秒、单请求 15 秒上限、手动按钮与后台锁忙返回 `lock_busy`、页面仍每 5 秒读本地文件。补充本地源码测试命令，但不得给出或执行 release JAR 打包步骤。

把设计文档状态更新为：

```markdown
**状态：** 已实施，待发布包由用户另行构建
```

只有完整验收通过后才更新此状态；验收未通过时保持“实施中”并写明失败门禁。

- [x] **Step 6：运行 App 相关针对性测试**

运行：

```bash
mvn -q -Dtest=ConfigTest,ChatAppHistoryStoreTest,AliyunChatAppMessageGatewayTest,ChatAppMessageSynchronizerTest,ChatAppMessageSyncRuntimeTest test
mvn -q -DskipTests test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
node contracts/openapi/message-center-v1.test.mjs
```

预期：全部通过；OpenAPI 响应字段未漂移。

### Task 8：完整验收与 git 边界复核

**文件：**
- 不新增功能文件
- 只修复本轮测试实际发现的阻断问题

- [x] **Step 1：运行完整 Maven 测试**

运行：

```bash
cd demo/message-center-demo
mvn -q test
```

预期：全部测试通过，无 warning。若受 sandbox 回环端口限制失败，使用获批的相同命令重新运行并记录原因；不得用跳过测试代替。

- [x] **Step 2：运行完整编译和自运行探针**

运行：

```bash
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test exec:java
node contracts/openapi/message-center-v1.test.mjs
```

预期：编译、探针、合同测试全部通过。

- [x] **Step 3：检查格式和禁止项**

运行：

```bash
cd ../..
git diff --check
git status --short
git diff --name-only -- demo/message-center-demo/release
rg -n "scheduleAtFixedRate" \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppMessageSyncRuntime.java
rg -n "\.get\(\)" \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AliyunChatAppMessageGateway.java
```

预期：`git diff --check` 无输出；release diff 无输出；runtime 不含固定频率；消息列表 gateway 不含无超时 `Future.get()`。

- [x] **Step 4：逐文件复核本轮 diff**

运行：

```bash
git diff -- \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistoryStore.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppSender.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java \
  demo/message-center-demo/README.md \
  demo/message-center-demo/config.example.env
```

预期：没有回退或吸入 WeCom WIP，没有改动模板同步 5 分钟语义，没有旧/新双路径消息写入。

- [x] **Step 5：报告验证证据和剩余发布边界**

最终报告必须列出本轮实际运行命令及结果，并明确：源码自动同步已实现；未执行 Maven package；未修改 release JAR；服务器生效仍需用户以后基于当前源码自行构建并部署。若任何关键测试未运行或失败，不得宣称“完成”。

## 自审结果

- 设计覆盖：配置、异步首轮、5 秒固定延迟、进程内/跨进程单飞、15 秒 Future 超时、统一存储 owner、关闭 gate、HTTP/CLI 接线、文档和 release 禁止项均有对应任务。
- owner 一致性：只有 `ChatAppHistoryStore` 写消息文件；只有 `ChatAppMessageSynchronizer` 启动完整同步轮次；runtime、HTTP 和 CLI 均为薄接线。
- 类型一致性：后续任务统一使用 `ChatAppMessageSynchronizer.Outcome(Status, SyncResult)`、`ChatAppHistorySyncService.CommitGate` 和 `ChatAppMessageGateway.MessageRequest/MessagePage`。
- 工作树边界：共享脏文件不自动整文件暂存；release 目录不读写；最终必须逐文件复核 WeCom WIP。
