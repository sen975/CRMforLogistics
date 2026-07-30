# ChatApp 模板库自动同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让消息中心在 web 模式启动后自动拉取 ChatApp 模板库、每 5 分钟全量对账，并在成功变化时通过 SSE 精确刷新页面模板控件。

**Architecture:** 新增独立的阿里云模板网关、全量同步 owner、同目录锁和运行时调度器。同步 owner 只在完整上游结果成功取得后，才由 `TemplateStore` 原子替换本地快照；`App` 仅负责把运行时与现有 SSE、CLI 和内嵌页面接线。

**Tech Stack:** Java 17、Maven、阿里云 CAMS 5.0.5 SDK、JDK `HttpServer`/SSE、Gson、JUnit 5、内嵌 HTML/CSS/JavaScript。

## Global Constraints

- 自动同步只在 `web` 命令运行；启动后异步首轮同步，不能阻塞 HTTP 监听。
- `CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED` 默认 `true`；`CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS` 默认 `300`，只允许 `300～600`。
- 每次上游请求最多 15 秒，单轮最多 240 秒；`TEMPLATE_PAGE_SIZE` 只允许 `1～50`，`TEMPLATE_MAX_PAGES` 只允许 `1～40`，单轮最多 2,000 条模板语言记录。
- 第 40 页仍为满页或上游 `total` 大于 2,000 时，整轮失败，旧快照不变；不得保存截断数据。
- 快照只能由完整本轮上游结果替换，不得合并上一版；删除的模板语言记录必须消失。
- 业务内容相同不写文件、不发 SSE；比较忽略本地 `updatedAt`，保留上游 `raw`、正文和审核状态变化的检测能力。
- 原子写入使用模板文件同目录临时文件和 `ATOMIC_MOVE`；无法原子替换即失败，不降级为覆盖写。
- 同一快照通过进程内单飞和同目录 `.lock` 文件防止并发提交；锁忙时立即跳过。
- `/events` 新增 `event: templates-changed`，数据仅为 `{"count":N}`；页面只重新请求 `/api/templates`，不刷新消息列表、不显示“有新消息”。
- `ChatAppAudit` 回调不进入本轮实现；现有 `sync-templates` CLI 保留。`SYNC_TEMPLATES_BEFORE_MESSAGES` 保留为兼容触发入口，但必须委托新的同步 owner。
- 不新增 Maven 依赖、不输出凭据、模板正文或完整上游响应到日志。

## File Structure

| 文件 | 责任 |
| --- | --- |
| `src/main/java/.../Config.java` | 自动同步开关、间隔和分页配置的唯一校验入口。 |
| `src/main/java/.../TemplateStore.java` | 成功快照的规范化比较与原子替换。 |
| `src/main/java/.../ChatAppTemplateGateway.java` | 模板列表/详情的独立上游合同。 |
| `src/main/java/.../AliyunChatAppTemplateGateway.java` | CAMS SDK 请求、超时和响应投影。 |
| `src/main/java/.../ChatAppTemplateSyncLock.java` | 模板快照同目录的跨进程非阻塞锁。 |
| `src/main/java/.../ChatAppTemplateSynchronizer.java` | 全量对账、上限、单飞、结果和提交 owner。 |
| `src/main/java/.../ChatAppTemplateSyncRuntime.java` | web 生命周期、定时触发、日志和变更通知。 |
| `src/main/java/.../ChatAppHistorySyncService.java` | 保留旧 API，但只委托同步 owner，不再自行拉模板。 |
| `src/main/java/.../App.java` | 创建共享同步 owner、启动/关闭 runtime、发布 SSE、页面事件处理。 |
| `src/test/java/.../*Template*.java` | 配置、存储、网关投影、同步器和运行时的 JUnit 测试。 |
| `src/test/java/.../UnifiedMessageStoreTest.java` | 现有自运行 SSE/内嵌页面探针的扩展。 |
| `config.example.env`、`README.md`、设计文档 | 用户配置、运行语义和最终状态的真源回写。 |

---

### Task 1: 固化自动同步配置合同

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java:58-63`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**Interfaces:**
- Produces: `chatappTemplateAutoSyncEnabled(): boolean`、`chatappTemplateSyncIntervalSeconds(): int`、`chatappTemplatePageSize(): int`、`chatappTemplateMaxPages(): int`、`hasChatAppTemplateSyncConfiguration(): boolean`。
- Consumed by: `ChatAppTemplateSynchronizer`、`ChatAppTemplateSyncRuntime`、`AliyunChatAppTemplateGateway`。

- [ ] **Step 1: 写入失败的配置测试**

在 `ConfigTest` 新增以下两个 JUnit 测试：

```java
@Test
void exposesBoundedChatAppTemplateAutoSyncSettings() {
    Config defaults = new Config(Map.of("CUST_SPACE_ID", "space-1"));
    assertEquals(true, defaults.chatappTemplateAutoSyncEnabled());
    assertEquals(300, defaults.chatappTemplateSyncIntervalSeconds());
    assertEquals(50, defaults.chatappTemplatePageSize());
    assertEquals(40, defaults.chatappTemplateMaxPages());
    assertEquals(true, defaults.hasChatAppTemplateSyncConfiguration());

    Config configured = new Config(Map.ofEntries(
            Map.entry("CUST_SPACE_ID", "space-1"),
            Map.entry("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "false"),
            Map.entry("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "600"),
            Map.entry("TEMPLATE_PAGE_SIZE", "25"),
            Map.entry("TEMPLATE_MAX_PAGES", "12")
    ));
    assertEquals(false, configured.chatappTemplateAutoSyncEnabled());
    assertEquals(600, configured.chatappTemplateSyncIntervalSeconds());
    assertEquals(25, configured.chatappTemplatePageSize());
    assertEquals(12, configured.chatappTemplateMaxPages());
    assertEquals(false, new Config(Map.of()).hasChatAppTemplateSyncConfiguration());
}

@Test
void rejectsUnsafeChatAppTemplateAutoSyncSettings() {
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "yes"))
                    .chatappTemplateAutoSyncEnabled());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "299"))
                    .chatappTemplateSyncIntervalSeconds());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "601"))
                    .chatappTemplateSyncIntervalSeconds());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("TEMPLATE_PAGE_SIZE", "51"))
                    .chatappTemplatePageSize());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("TEMPLATE_MAX_PAGES", "41"))
                    .chatappTemplateMaxPages());
}
```

- [ ] **Step 2: 运行测试，确认因方法不存在失败**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test
```

Expected: 编译失败，提示新增的 `Config` 方法不存在。

- [ ] **Step 3: 在 `Config` 添加唯一的配置访问器**

紧接在 `chatappTemplateFile()` 后添加：

```java
public boolean chatappTemplateAutoSyncEnabled() {
    return strictBoolean("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", true);
}

public int chatappTemplateSyncIntervalSeconds() {
    return boundedInt("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", 300, 300, 600);
}

public int chatappTemplatePageSize() {
    return boundedInt("TEMPLATE_PAGE_SIZE", 50, 1, 50);
}

public int chatappTemplateMaxPages() {
    return boundedInt("TEMPLATE_MAX_PAGES", 40, 1, 40);
}

public boolean hasChatAppTemplateSyncConfiguration() {
    return !value("CUST_SPACE_ID", "").isBlank();
}
```

不要把 15 秒请求超时、240 秒整轮超时和 2,000 条记录上限变成无边界环境变量；它们在同步 owner 中作为不可绕过的常量。

- [ ] **Step 4: 运行配置测试，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test
```

Expected: exit code `0`。

- [ ] **Step 5: 提交配置合同**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java
git commit -m "feat: configure ChatApp template auto sync"
```

### Task 2: 让模板快照比较与写入原子化

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/TemplateStore.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/TemplateStoreTest.java`

**Interfaces:**
- Produces: `TemplateStore.ReplaceResult replaceIfChanged(List<TemplateRecord> records)`，其中 `changed()` 表示是否已提交，`changedRecords()` 统计新增、修改和删除的稳定键数，`recordCount()` 是最终快照条数。
- Produces: package-private `Path file()` 与可测试构造器 `TemplateStore(Path file, AtomicMover mover)`。
- Consumed by: `ChatAppTemplateSynchronizer`。

- [ ] **Step 1: 写入失败的存储测试**

创建 `TemplateStoreTest.java`，用临时目录覆盖三个不可替代的语义：忽略 `updatedAt`、删除上游已不存在的记录、替换失败保留旧文件。

```java
@TempDir
Path tempDir;

Path templateFile;

@BeforeEach
void setUp() {
    templateFile = tempDir.resolve("templates.json");
}

@Test
void replacesOnlyWhenBusinessContentChangesAndRemovesDeletedRecords() throws Exception {
    AtomicInteger moves = new AtomicInteger();
    TemplateStore store = new TemplateStore(templateFile,
            (source, target) -> {
                moves.incrementAndGet();
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            });

    assertTrue(store.replaceIfChanged(List.of(record("a", "first", "2026-07-01T00:00:00Z"),
            record("b", "second", "2026-07-01T00:00:00Z"))).changed());
    assertEquals(1, moves.get());
    assertFalse(store.replaceIfChanged(List.of(record("a", "first", "2026-07-30T00:00:00Z"),
            record("b", "second", "2026-07-30T00:00:00Z"))).changed());
    assertEquals(1, moves.get());

    TemplateStore.ReplaceResult result = store.replaceIfChanged(
            List.of(record("a", "changed", "2026-07-30T00:00:00Z")));
    assertTrue(result.changed());
    assertEquals(2, result.changedRecords());
    assertEquals(List.of("a"), store.readAll().stream().map(item -> item.templateCode).toList());
}

@Test
void keepsExistingSnapshotWhenAtomicMoveFails() throws Exception {
    TemplateStore healthy = new TemplateStore(templateFile);
    healthy.replaceIfChanged(List.of(record("a", "stable", "2026-07-01T00:00:00Z")));
    String before = Files.readString(templateFile, StandardCharsets.UTF_8);
    TemplateStore failing = new TemplateStore(templateFile,
            (source, target) -> { throw new IOException("forced move failure"); });

    assertThrows(IOException.class,
            () -> failing.replaceIfChanged(List.of(record("a", "new", "2026-07-30T00:00:00Z"))));
    assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
}

private static TemplateStore.TemplateRecord record(String code, String body, String updatedAt) {
    TemplateStore.TemplateRecord item = new TemplateStore.TemplateRecord();
    item.templateCode = code;
    item.templateName = code;
    item.languageCode = "zh_CN";
    item.body = body;
    item.raw = "{\"auditStatus\":\"pass\",\"body\":\"" + body + "\"}";
    item.updatedAt = updatedAt;
    return item;
}
```

- [ ] **Step 2: 运行测试，确认类/接口尚不存在**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=TemplateStoreTest test
```

Expected: 编译失败，提示 `ReplaceResult`、构造器或 `replaceIfChanged` 不存在。

- [ ] **Step 3: 实现规范化比较和同目录原子替换**

在 `TemplateStore` 添加以下接口和核心写入路径；原有 `saveAll` 改为调用 `replaceIfChanged`，不再使用 `TRUNCATE_EXISTING` 直接覆盖目标文件。

```java
interface AtomicMover {
    void move(Path source, Path target) throws IOException;
}

record ReplaceResult(boolean changed, int changedRecords, int recordCount) {}

private final AtomicMover mover;

public TemplateStore(Path file) {
    this(file, TemplateStore::defaultMove);
}

TemplateStore(Path file, AtomicMover mover) {
    this.file = file;
    this.mover = mover;
}

Path file() {
    return file;
}

public synchronized ReplaceResult replaceIfChanged(List<TemplateRecord> records) throws IOException {
    List<TemplateRecord> next = normalized(records);
    List<TemplateRecord> previous = normalized(readAll());
    int changedRecords = changedRecordCount(previous, next);
    if (changedRecords == 0) {
        return new ReplaceResult(false, 0, next.size());
    }
    writeAtomically(next);
    return new ReplaceResult(true, changedRecords, next.size());
}

private void writeAtomically(List<TemplateRecord> records) throws IOException {
    Path target = file.toAbsolutePath().normalize();
    Path parent = target.getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
    IOException writeFailure = null;
    try {
        Files.writeString(temporary, GSON.toJson(records), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        mover.move(temporary, target);
    } catch (IOException exception) {
        writeFailure = exception;
        throw exception;
    } finally {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            if (writeFailure != null) writeFailure.addSuppressed(cleanupFailure);
            else throw cleanupFailure;
        }
    }
}

private static void defaultMove(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
}
```

`normalized` 的实现必须等价于下面的确定性处理：

```java
private List<TemplateRecord> normalized(List<TemplateRecord> input) {
    List<TemplateRecord> result = new ArrayList<>();
    for (TemplateRecord source : input == null ? List.<TemplateRecord>of() : input) {
        TemplateRecord copy = GSON.fromJson(GSON.toJson(source), TemplateRecord.class);
        if (copy.placeholders == null || copy.placeholders.isEmpty()) {
            copy.placeholders = placeholders(copy.body == null ? "" : copy.body);
        }
        result.add(copy);
    }
    result.sort(Comparator.comparing((TemplateRecord item) -> firstNonBlank(item.templateName, ""))
            .thenComparing(item -> firstNonBlank(item.languageCode, ""))
            .thenComparing(item -> firstNonBlank(item.templateCode, "")));
    return result;
}

private int changedRecordCount(List<TemplateRecord> previous, List<TemplateRecord> next) {
    Map<String, String> before = semanticMap(previous);
    Map<String, String> after = semanticMap(next);
    Set<String> keys = new LinkedHashSet<>(before.keySet());
    keys.addAll(after.keySet());
    int changed = 0;
    for (String key : keys) {
        if (!Objects.equals(before.get(key), after.get(key))) changed++;
    }
    return changed;
}

private Map<String, String> semanticMap(List<TemplateRecord> records) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TemplateRecord source : records) {
        TemplateRecord copy = GSON.fromJson(GSON.toJson(source), TemplateRecord.class);
        copy.updatedAt = null;
        result.put(key(copy), GSON.toJson(copy));
    }
    return result;
}
```

补齐所需的 `ArrayList`、`Comparator`、`LinkedHashSet`、`Objects` 和 `Set` imports。`writeAtomically` 需要在 `Files.writeString` 后调用 `FileChannel.force(true)`；临时文件清理失败不能掩盖主写入异常。`saveAll` 继续存在，但直接调用 `replaceIfChanged` 并在 `changed=false` 时正常返回。

- [ ] **Step 4: 运行存储测试，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=TemplateStoreTest test
```

Expected: exit code `0`，且临时目录不保留 `.tmp` 文件。

- [ ] **Step 5: 提交原子快照 owner**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/TemplateStore.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/TemplateStoreTest.java
git commit -m "feat: atomically replace ChatApp template snapshots"
```

### Task 3: 提取有超时的阿里云模板网关

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateGateway.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AliyunChatAppTemplateGateway.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AliyunChatAppTemplateGatewayTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java:1-40,245-350`

**Interfaces:**
- Produces: `ChatAppTemplateGateway.Factory.open()`、`listTemplates(int,int,Duration)` 和 `getTemplateDetail(TemplateSummary,Duration)`。
- Produces: `TemplatePage(List<TemplateSummary> templates, Integer total)` 和 `TemplateSummary(String templateCode, String templateName, String language, String templateType)`。
- Consumed by: `ChatAppTemplateSynchronizer`；`ChatAppHistorySyncService` 不再直接持有 CAMS 模板 SDK 细节。

- [ ] **Step 1: 写入失败的投影测试**

创建 `AliyunChatAppTemplateGatewayTest.java`，覆盖列表语言/类型传递、BODY 正文优先和审核状态保留：

```java
@Test
void projectsTemplateSummaryAndDetailWithAuditState() {
    ListChatappTemplateResponseBody.ListTemplate row =
            ListChatappTemplateResponseBody.ListTemplate.builder()
                    .templateCode("code-1").templateName("shipping_notice")
                    .language("zh_CN").templateType("WHATSAPP").build();
    ChatAppTemplateGateway.TemplateSummary summary =
            AliyunChatAppTemplateGateway.toSummary(row);
    GetChatappTemplateDetailResponseBody.Data detail =
            GetChatappTemplateDetailResponseBody.Data.builder()
                    .templateCode("code-1").name("shipping_notice").language("zh_CN")
                    .auditStatus("pass")
                    .components(List.of(
                            GetChatappTemplateDetailResponseBody.Components.builder()
                                    .type("HEADER").text("Shipping update").build(),
                            GetChatappTemplateDetailResponseBody.Components.builder()
                                    .type("BODY").text("Order $(orderNo)").build()))
                    .build();

    TemplateStore.TemplateRecord record =
            AliyunChatAppTemplateGateway.toRecord(summary, detail);

    assertEquals("code-1", summary.templateCode());
    assertEquals("WHATSAPP", summary.templateType());
    assertEquals("Order $(orderNo)", record.body);
    assertTrue(record.raw.contains("\"auditStatus\":\"pass\""));
}
```

- [ ] **Step 2: 运行测试，确认网关类不存在**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=AliyunChatAppTemplateGatewayTest test
```

Expected: 编译失败，提示 `ChatAppTemplateGateway` 或 `AliyunChatAppTemplateGateway` 不存在。

- [ ] **Step 3: 实现网关合同和 SDK adapter**

创建以下完整合同：

```java
interface ChatAppTemplateGateway extends AutoCloseable {
    TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) throws Exception;
    TemplateStore.TemplateRecord getTemplateDetail(TemplateSummary summary, Duration timeout) throws Exception;

    @Override
    void close();

    record TemplatePage(List<TemplateSummary> templates, Integer total) {}
    record TemplateSummary(String templateCode, String templateName, String language, String templateType) {}

    interface Factory {
        ChatAppTemplateGateway open() throws Exception;
    }
}
```

`AliyunChatAppTemplateGateway` 必须从旧 `ChatAppHistorySyncService.syncTemplates()` 移动并收敛这些逻辑：

```java
static AliyunChatAppTemplateGateway open(Config config) {
    return new AliyunChatAppTemplateGateway(config, createClient(config));
}

public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) throws Exception {
    var future = client.listChatappTemplate(request(pageIndex, pageSize));
    ListChatappTemplateResponse response;
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
    ListChatappTemplateResponseBody body = requireSuccessfulBody(response.getBody(), "ListChatappTemplate");
    List<TemplateSummary> summaries = body.getListTemplate() == null ? List.of()
            : body.getListTemplate().stream().map(AliyunChatAppTemplateGateway::toSummary).toList();
    return new TemplatePage(summaries, body.getTotal());
}

public TemplateStore.TemplateRecord getTemplateDetail(TemplateSummary summary, Duration timeout) throws Exception {
    var future = client.getChatappTemplateDetail(detailRequest(summary));
    GetChatappTemplateDetailResponse response;
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
    GetChatappTemplateDetailResponseBody.Data data = requireDetail(response, summary.templateCode());
    return toRecord(summary, data);
}
```

`request` 必须保留现有 `TEMPLATE_LANGUAGE`、`TEMPLATE_NAME`、`TEMPLATE_CODE`、`TEMPLATE_AUDIT_STATUS`、`TEMPLATE_CATEGORY` 和 `TEMPLATE_TYPE` 过滤器。`detailRequest` 必须以列表返回的 code/name/language/type 构建。SDK Future 的超时、空 body、非 `OK` code 和 `success=false` 都抛出带 API 名称的异常；捕获 `TimeoutException` 或 `InterruptedException` 时调用 `future.cancel(true)`，并在中断异常路径恢复线程中断标记。`close()` 必须关闭 `AsyncClient`。

从 `ChatAppHistorySyncService` 删除模板专用 SDK imports、`fetchTemplateDetail` 和 `templateBody`；消息列表仍使用的 `assertOk` 与客户端创建逻辑必须保留，消息历史 API 的行为保持不动。

- [ ] **Step 4: 运行网关测试，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=AliyunChatAppTemplateGatewayTest test
```

Expected: exit code `0`。

- [ ] **Step 5: 提交上游 adapter 分离**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateGateway.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AliyunChatAppTemplateGateway.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AliyunChatAppTemplateGatewayTest.java
git commit -m "feat: isolate ChatApp template gateway"
```

### Task 4: 实现锁与全量模板同步 owner

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncLock.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSynchronizer.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppTemplateSynchronizerTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java`

**Interfaces:**
- Produces: `ChatAppTemplateSynchronizer.sync(): Outcome`。
- Produces: `Outcome(Status status, int fetched, int changed, int count, int pages, long durationMillis)`；`Status` 只能为 `CHANGED`、`UNCHANGED`、`LOCK_BUSY`。
- Produces: `ChatAppTemplateSyncLock.tryAcquire(Path): Optional<Handle>`。
- Consumed by: `ChatAppTemplateSyncRuntime`、`App` CLI 和兼容的 `ChatAppHistorySyncService.syncTemplates()`。

- [ ] **Step 1: 写入失败的全量对账、失败回滚、上限和锁测试**

在 `ChatAppTemplateSynchronizerTest` 创建可编程假网关，并添加以下测试：

```java
@TempDir
Path tempDir;

Path templateFile;
Config config;
TemplateStore store;

@BeforeEach
void setUp() {
    templateFile = tempDir.resolve("templates.json");
    config = new Config(Map.of(
            "CUST_SPACE_ID", "space-1",
            "CHATAPP_TEMPLATE_FILE", templateFile.toString()));
    store = new TemplateStore(templateFile);
}

@Test
void replacesSnapshotWithCompleteUpstreamResultSoDeletedTemplatesDisappear() throws Exception {
    store.replaceIfChanged(List.of(record("deleted", "old"), record("keep", "old")));
    FakeGateway gateway = new FakeGateway(List.of(
            new ChatAppTemplateGateway.TemplatePage(List.of(summary("keep")), 1),
            new ChatAppTemplateGateway.TemplatePage(List.of(), 1)));
    gateway.details.put("keep", record("keep", "new"));
    ChatAppTemplateSynchronizer synchronizer = synchronizer(gateway);

    ChatAppTemplateSynchronizer.Outcome outcome = synchronizer.sync();

    assertEquals(ChatAppTemplateSynchronizer.Status.CHANGED, outcome.status());
    assertEquals(List.of("keep"), store.readAll().stream().map(item -> item.templateCode).toList());
    assertEquals("new", store.readAll().get(0).body);
}

@Test
void reportsUnchangedWhenOnlyTheLocalSyncTimestampDiffers() throws Exception {
    TemplateStore.TemplateRecord existing = record("keep", "same");
    existing.updatedAt = "2026-07-01T00:00:00Z";
    store.replaceIfChanged(List.of(existing));
    FakeGateway gateway = new FakeGateway(List.of(
            new ChatAppTemplateGateway.TemplatePage(List.of(summary("keep")), 1)));
    gateway.details.put("keep", record("keep", "same"));

    ChatAppTemplateSynchronizer.Outcome outcome = synchronizer(gateway).sync();

    assertEquals(ChatAppTemplateSynchronizer.Status.UNCHANGED, outcome.status());
    assertEquals(0, outcome.changed());
    assertEquals(1, outcome.count());
}

@Test
void preservesOldSnapshotWhenAnyDetailFails() throws Exception {
    store.replaceIfChanged(List.of(record("stable", "old")));
    String before = Files.readString(templateFile, StandardCharsets.UTF_8);
    FakeGateway gateway = new FakeGateway(List.of(
            new ChatAppTemplateGateway.TemplatePage(List.of(summary("stable")), 1)));
    gateway.detailFailure = new IOException("detail unavailable");

    assertThrows(Exception.class, () -> synchronizer(gateway).sync());
    assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
}

@Test
void rejectsAFullFinalAllowedPageInsteadOfSavingTruncatedSnapshot() throws Exception {
    Config onePage = new Config(Map.ofEntries(
            Map.entry("CUST_SPACE_ID", "space-1"),
            Map.entry("CHATAPP_TEMPLATE_FILE", templateFile.toString()),
            Map.entry("TEMPLATE_PAGE_SIZE", "50"),
            Map.entry("TEMPLATE_MAX_PAGES", "1")));
    List<ChatAppTemplateGateway.TemplateSummary> summaries =
            IntStream.range(0, 50).mapToObj(index -> summary("code-" + index)).toList();
    FakeGateway gateway = new FakeGateway(List.of(
            new ChatAppTemplateGateway.TemplatePage(summaries, 50)));

    IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> synchronizer(onePage, gateway).sync());
    assertTrue(exception.getMessage().contains("final_page_is_full"));
    assertEquals(0, gateway.detailCalls.get());
    assertFalse(Files.exists(templateFile));
}

@Test
void rejectsReportedTotalsAboveTwoThousandBeforeFetchingDetails() throws Exception {
    FakeGateway gateway = new FakeGateway(List.of(
            new ChatAppTemplateGateway.TemplatePage(List.of(summary("code-1")), 2_001)));

    IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> synchronizer(gateway).sync());

    assertTrue(exception.getMessage().contains("total=2001"));
    assertEquals(0, gateway.detailCalls.get());
    assertFalse(Files.exists(templateFile));
}

@Test
void returnsLockBusyWithoutCallingTheGateway() throws Exception {
    try (ChatAppTemplateSyncLock.Handle ignored = ChatAppTemplateSyncLock.tryAcquire(templateFile).orElseThrow()) {
        FakeGateway gateway = new FakeGateway(List.of());
        ChatAppTemplateSynchronizer.Outcome outcome = synchronizer(gateway).sync();
        assertEquals(ChatAppTemplateSynchronizer.Status.LOCK_BUSY, outcome.status());
        assertEquals(0, gateway.listCalls.get());
    }
}

@Test
void concurrentTriggersUseTheInProcessSingleFlightGuard() throws Exception {
    store.replaceIfChanged(List.of(record("old", "old")));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ChatAppTemplateGateway blocking = new ChatAppTemplateGateway() {
        @Override
        public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout)
                throws Exception {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return new TemplatePage(List.of(), 0);
        }

        @Override
        public TemplateStore.TemplateRecord getTemplateDetail(
                TemplateSummary summary, Duration timeout) {
            throw new AssertionError("detail must not run");
        }

        @Override
        public void close() {}
    };
    ChatAppTemplateSynchronizer synchronizer = new ChatAppTemplateSynchronizer(
            config, store, () -> blocking, Clock.systemUTC(), Duration.ofSeconds(5));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
        Future<ChatAppTemplateSynchronizer.Outcome> first = executor.submit(synchronizer::sync);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ChatAppTemplateSynchronizer.Outcome duplicate = synchronizer.sync();
        assertEquals(ChatAppTemplateSynchronizer.Status.LOCK_BUSY, duplicate.status());
        release.countDown();
        assertEquals(ChatAppTemplateSynchronizer.Status.CHANGED, first.get().status());
    } finally {
        release.countDown();
        executor.shutdownNow();
    }
}
```

同一测试类添加以下 fixture；`lastTimeout` 用来断言每个请求 timeout 不超过 15 秒：

```java
private ChatAppTemplateSynchronizer synchronizer(FakeGateway gateway) {
    return synchronizer(config, gateway);
}

private ChatAppTemplateSynchronizer synchronizer(Config selectedConfig, FakeGateway gateway) {
    return new ChatAppTemplateSynchronizer(selectedConfig,
            new TemplateStore(selectedConfig.chatappTemplateFile()),
            () -> gateway, Clock.systemUTC(), Duration.ofSeconds(240));
}

private static ChatAppTemplateGateway.TemplateSummary summary(String code) {
    return new ChatAppTemplateGateway.TemplateSummary(code, code, "zh_CN", "WHATSAPP");
}

private static TemplateStore.TemplateRecord record(String code, String body) {
    TemplateStore.TemplateRecord item = new TemplateStore.TemplateRecord();
    item.templateCode = code;
    item.templateName = code;
    item.languageCode = "zh_CN";
    item.body = body;
    item.raw = "{\"auditStatus\":\"pass\",\"body\":\"" + body + "\"}";
    return item;
}

private static final class FakeGateway implements ChatAppTemplateGateway {
    private final ArrayDeque<TemplatePage> pages;
    private final Map<String, TemplateStore.TemplateRecord> details = new HashMap<>();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicInteger detailCalls = new AtomicInteger();
    private Duration lastTimeout = Duration.ZERO;
    private Exception detailFailure;

    FakeGateway(List<TemplatePage> pages) {
        this.pages = new ArrayDeque<>(pages);
    }

    @Override
    public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) {
        listCalls.incrementAndGet();
        lastTimeout = timeout;
        assertTrue(timeout.compareTo(Duration.ofSeconds(15)) <= 0);
        return pages.isEmpty() ? new TemplatePage(List.of(), 0) : pages.removeFirst();
    }

    @Override
    public TemplateStore.TemplateRecord getTemplateDetail(
            TemplateSummary summary, Duration timeout) throws Exception {
        detailCalls.incrementAndGet();
        lastTimeout = timeout;
        assertTrue(timeout.compareTo(Duration.ofSeconds(15)) <= 0);
        if (detailFailure != null) throw detailFailure;
        return details.get(summary.templateCode());
    }

    @Override
    public void close() {}
}
```

另加以下轮次 deadline 测试；它使用 package-private 的 50 毫秒构造器，不访问网络：

```java
@Test
void roundTimeoutPreservesTheOldSnapshot() throws Exception {
    store.replaceIfChanged(List.of(record("stable", "old")));
    String before = Files.readString(templateFile, StandardCharsets.UTF_8);
    ChatAppTemplateGateway slow = new ChatAppTemplateGateway() {
        @Override
        public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout)
                throws Exception {
            Thread.sleep(timeout.toMillis() + 10);
            throw new TimeoutException("forced timeout");
        }

        @Override
        public TemplateStore.TemplateRecord getTemplateDetail(
                TemplateSummary summary, Duration timeout) {
            throw new AssertionError("detail must not run");
        }

        @Override
        public void close() {}
    };
    ChatAppTemplateSynchronizer synchronizer = new ChatAppTemplateSynchronizer(
            config, store, () -> slow, Clock.systemUTC(), Duration.ofMillis(50));

    assertThrows(Exception.class, synchronizer::sync);
    assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
}
```

- [ ] **Step 2: 运行测试，确认同步 owner 不存在**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ChatAppTemplateSynchronizerTest test
```

Expected: 编译失败，提示 `ChatAppTemplateSynchronizer` 或 `ChatAppTemplateSyncLock` 不存在。

- [ ] **Step 3: 实现同目录非阻塞锁**

创建 `ChatAppTemplateSyncLock`，使用 `FileChannel.tryLock()`，并把同 JVM 的 `OverlappingFileLockException` 转换为“锁忙”：

```java
static Optional<Handle> tryAcquire(Path templateFile) throws IOException {
    Path target = templateFile.toAbsolutePath().normalize();
    Path lockFile = target.resolveSibling(target.getFileName() + ".lock");
    Files.createDirectories(lockFile.getParent());
    FileChannel channel = FileChannel.open(lockFile,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        return Optional.of(new Handle(channel, lock));
    } catch (OverlappingFileLockException exception) {
        channel.close();
        return Optional.empty();
    } catch (IOException | RuntimeException exception) {
        channel.close();
        throw exception;
    }
}
```

`Handle.close()` 必须先释放 `FileLock` 再关闭 `FileChannel`，并允许重复关闭。锁文件可以保留为空文件；它不是模板快照内容的一部分。

- [ ] **Step 4: 实现全量同步、单飞和提交算法**

创建 `ChatAppTemplateSynchronizer`，使用以下固定常量和单飞边界：

```java
private static final int MAX_TEMPLATE_RECORDS = 2_000;
private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(240);
private final AtomicBoolean running = new AtomicBoolean();

public Outcome sync() throws Exception {
    long started = System.nanoTime();
    if (!running.compareAndSet(false, true)) return Outcome.lockBusy(started);
    try {
        Optional<ChatAppTemplateSyncLock.Handle> acquired =
                ChatAppTemplateSyncLock.tryAcquire(templateStore.file());
        if (acquired.isEmpty()) return Outcome.lockBusy(started);
        try (ChatAppTemplateSyncLock.Handle ignored = acquired.orElseThrow()) {
            return synchronizeLocked(started);
        }
    } finally {
        running.set(false);
    }
}
```

`synchronizeLocked` 必须执行以下实际顺序：

```java
long deadlineNanos = System.nanoTime() + roundTimeout.toNanos();
Map<String, TemplateStore.TemplateRecord> records = new LinkedHashMap<>();
int fetched = 0;
int pages = 0;
try (ChatAppTemplateGateway gateway = gatewayFactory.open()) {
    for (int pageIndex = 1; pageIndex <= config.chatappTemplateMaxPages(); pageIndex++) {
        ChatAppTemplateGateway.TemplatePage page = gateway.listTemplates(pageIndex,
                config.chatappTemplatePageSize(), remainingTimeout(deadlineNanos));
        pages++;
        if (page.total() != null && page.total() > MAX_TEMPLATE_RECORDS) {
            throw failure("list", "template_limit_exceeded: total=" + page.total());
        }
        List<ChatAppTemplateGateway.TemplateSummary> rows =
                page.templates() == null ? List.of() : page.templates();
        if (fetched + rows.size() > MAX_TEMPLATE_RECORDS) {
            throw failure("list", "template_limit_exceeded: fetched=" + (fetched + rows.size()));
        }
        if (pageIndex == config.chatappTemplateMaxPages()
                && rows.size() == config.chatappTemplatePageSize()) {
            throw failure("list", "template_limit_exceeded: final_page_is_full");
        }
        for (ChatAppTemplateGateway.TemplateSummary row : rows) {
            TemplateStore.TemplateRecord record = gateway.getTemplateDetail(
                    row, remainingTimeout(deadlineNanos));
            record.updatedAt = clock.instant().toString();
            String key = templateStore.key(record);
            if (key.equals("|")) throw failure("detail", "template_detail_missing_key");
            if (records.putIfAbsent(key, record) != null) {
                throw failure("detail", "duplicate_template_key: " + key);
            }
            fetched++;
        }
        if (rows.size() < config.chatappTemplatePageSize()) break;
    }
}
TemplateStore.ReplaceResult committed = templateStore.replaceIfChanged(new ArrayList<>(records.values()));
return Outcome.from(committed, fetched, pages, elapsedMillis(started));
```

`remainingTimeout` 必须先检查 `Thread.currentThread().isInterrupted()`，再取 15 秒和 `deadlineNanos` 剩余时间的较小值；已中断时抛出 `failure("cancelled", "template_sync_cancelled")`，剩余时间不大于零时抛出 `failure("timeout", "template_sync_round_timeout")`。调用 `replaceIfChanged` 前再执行一次相同的中断检查，保证 runtime 关闭后不会进入提交。异常路径绝不能调用 `replaceIfChanged`。

定义 runtime 可读但不公开到 API 的嵌套异常：

```java
static final class SyncFailure extends IllegalStateException {
    private final String stage;

    SyncFailure(String stage, String code, Throwable cause) {
        super(code, cause);
        this.stage = stage;
    }

    String stage() {
        return stage;
    }
}

private static SyncFailure failure(String stage, String code) {
    return new SyncFailure(stage, code, null);
}
```

捕获网关和存储异常时分别用 `new SyncFailure("list"|"detail"|"commit", "template_sync_failed", cause)` 包装；runtime 只记录 `stage` 和异常类型，不记录上游 message 或模板数据。

`Outcome` 必须在同步器中定义为：

```java
public record Outcome(Status status, int fetched, int changed, int count,
                      int pages, long durationMillis) {
    static Outcome lockBusy(long started) {
        return new Outcome(Status.LOCK_BUSY, 0, 0, 0, 0,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    static Outcome from(TemplateStore.ReplaceResult result, int fetched,
                        int pages, long durationMillis) {
        return new Outcome(result.changed() ? Status.CHANGED : Status.UNCHANGED,
                fetched, result.changedRecords(), result.recordCount(), pages, durationMillis);
    }
}
```

生产工厂为 `static ChatAppTemplateSynchronizer create(Config config)`，依赖 `new TemplateStore(config.chatappTemplateFile())`、`() -> AliyunChatAppTemplateGateway.open(config)`、`Clock.systemUTC()` 和固定 240 秒 deadline。package-private 测试构造器必须接收 `Config`、`TemplateStore`、`ChatAppTemplateGateway.Factory`、`Clock` 和 `Duration roundTimeout`，测试不得访问真实阿里云网络。

在 `ChatAppHistorySyncService` 中添加字段和构造器委托，现有测试构造器保持可调用：

```java
private final ChatAppTemplateSynchronizer templateSynchronizer;

public ChatAppHistorySyncService(Config config) {
    this(config, ChatAppTemplateSynchronizer.create(config));
}

ChatAppHistorySyncService(Config config, ChatAppTemplateSynchronizer templateSynchronizer) {
    this(config, new ChatAppHistoryStore(config), new TemplateStore(config.chatappTemplateFile()),
            defaultMediaCacher(config), templateSynchronizer);
}

ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore,
                          TemplateStore templateStore) {
    this(config, historyStore, templateStore, defaultMediaCacher(config));
}

ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore,
                          TemplateStore templateStore, MediaCacher mediaCacher) {
    this(config, historyStore, templateStore, mediaCacher,
            ChatAppTemplateSynchronizer.create(config));
}

private ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore,
                                  TemplateStore templateStore, MediaCacher mediaCacher,
                                  ChatAppTemplateSynchronizer templateSynchronizer) {
    this.config = config;
    this.historyStore = historyStore;
    this.templateStore = templateStore;
    this.mediaCacher = Objects.requireNonNull(mediaCacher);
    this.templateSynchronizer = Objects.requireNonNull(templateSynchronizer);
    this.mediaExecutor = createMediaExecutor(config);
}
```

保留 `syncTemplates()` 的 `SyncResult` 输出合同，但只做结果映射：

```java
public SyncResult syncTemplates() throws Exception {
    ChatAppTemplateSynchronizer.Outcome outcome = templateSynchronizer.sync();
    SyncResult result = new SyncResult("chatapp-templates");
    result.fetched = outcome.fetched();
    result.saved = outcome.changed();
    result.skipped = outcome.status() == ChatAppTemplateSynchronizer.Status.UNCHANGED
            ? outcome.fetched() : Math.max(0, outcome.fetched() - outcome.changed());
    result.pages = outcome.pages();
    result.durationMillis = outcome.durationMillis();
    result.message = "template sync " + outcome.status().name().toLowerCase(Locale.ROOT)
            + ", fetched " + outcome.fetched() + ", changed " + outcome.changed()
            + ", count " + outcome.count();
    return result;
}
```

保留的 `SYNC_TEMPLATES_BEFORE_MESSAGES=true` 路径继续调用该 `syncTemplates()`，并映射 `templatesFetched/templatesSaved/templatesSkipped`；禁止复制分页、详情或写文件逻辑。

- [ ] **Step 5: 运行同步 owner 测试，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=TemplateStoreTest,ChatAppTemplateSynchronizerTest test
```

Expected: exit code `0`；失败、超时、满最终页和锁忙场景都不会改变旧快照。

- [ ] **Step 6: 提交全量对账 owner**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncLock.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSynchronizer.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppTemplateSynchronizerTest.java
git commit -m "feat: synchronize ChatApp templates atomically"
```

### Task 5: 添加可关闭的 web 模板同步运行时

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncRuntime.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncRuntimeTest.java`

**Interfaces:**
- Produces: `ChatAppTemplateSyncRuntime.open(Config, ChatAppTemplateSynchronizer, IntConsumer)`、`start()`、`enabled()`、`close()`。
- Produces for tests: package-private `SyncAction` 和构造器 `(boolean, int, SyncAction, IntConsumer, ScheduledExecutorService)`，不需要继承或 mock 具体同步器。
- Consumed by: `App.startWeb()`。

- [ ] **Step 1: 写入失败的运行时测试**

创建 `ChatAppTemplateSyncRuntimeTest.java`：

```java
@Test
void disabledOrUnconfiguredRuntimeDoesNotScheduleWork() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Config disabledConfig = new Config(Map.of(
            "CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "false", "CUST_SPACE_ID", "space-1",
            "CHATAPP_TEMPLATE_FILE", tempDir.resolve("disabled.json").toString()));
    try (ChatAppTemplateSyncRuntime disabled = ChatAppTemplateSyncRuntime.open(
            disabledConfig, synchronizerThatFailsIfCalled(disabledConfig, calls),
            count -> fail("must not publish"))) {
        disabled.start();
        assertFalse(disabled.enabled());
        assertEquals(0, calls.get());
    }
    Config missingConfig = new Config(Map.of(
            "CHATAPP_TEMPLATE_FILE", tempDir.resolve("missing.json").toString()));
    try (ChatAppTemplateSyncRuntime missingSpace = ChatAppTemplateSyncRuntime.open(
            missingConfig, synchronizerThatFailsIfCalled(missingConfig, calls),
            count -> fail("must not publish"))) {
        missingSpace.start();
        assertFalse(missingSpace.enabled());
    }
}

@Test
void startsAsynchronouslyPublishesOnlyChangesAndStopsAfterClose() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    AtomicInteger publishes = new AtomicInteger();
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    ChatAppTemplateSyncRuntime.SyncAction action = () -> {
        invoked.countDown();
        return new ChatAppTemplateSynchronizer.Outcome(
                ChatAppTemplateSynchronizer.Status.CHANGED, 2, 1, 2, 1, 1);
    };
    ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
            true, 300, action, count -> publishes.incrementAndGet(), executor);

    runtime.start();
    assertTrue(invoked.await(2, TimeUnit.SECONDS));
    runtime.close();
    runtime.runOnceForTests();
    assertEquals(1, publishes.get());
}

@Test
void closeInterruptsAnInFlightSyncBeforeReturning() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ChatAppTemplateSyncRuntime.SyncAction action = () -> {
        started.countDown();
        try {
            new CountDownLatch(1).await();
            throw new AssertionError("sync must be interrupted");
        } catch (InterruptedException exception) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            throw exception;
        }
    };
    ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
            true, 300, action, count -> fail("must not publish"),
            Executors.newSingleThreadScheduledExecutor());

    runtime.start();
    assertTrue(started.await(2, TimeUnit.SECONDS));
    runtime.close();
    assertTrue(interrupted.await(2, TimeUnit.SECONDS));
}

@Test
void unchangedSyncDoesNotPublishARefreshEvent() {
    AtomicInteger publishes = new AtomicInteger();
    ChatAppTemplateSyncRuntime runtime = new ChatAppTemplateSyncRuntime(
            true, 300,
            () -> new ChatAppTemplateSynchronizer.Outcome(
                    ChatAppTemplateSynchronizer.Status.UNCHANGED, 2, 0, 2, 1, 1),
            count -> publishes.incrementAndGet(),
            Executors.newSingleThreadScheduledExecutor());
    try {
        runtime.runOnceForTests();
        assertEquals(0, publishes.get());
    } finally {
        runtime.close();
    }
}
```

测试类添加 `@TempDir Path tempDir`，并用 Task 4 的 package-private 构造器实现：

```java
private ChatAppTemplateSynchronizer synchronizerThatFailsIfCalled(
        Config config, AtomicInteger calls) {
    return new ChatAppTemplateSynchronizer(config,
            new TemplateStore(config.chatappTemplateFile()),
            () -> {
                calls.incrementAndGet();
                throw new AssertionError("gateway must not open");
            }, Clock.systemUTC(), Duration.ofMillis(50));
}
```

上述 `UNCHANGED` 测试直接调用 `runOnceForTests()`，不等待 5 分钟调度周期。

- [ ] **Step 2: 运行测试，确认运行时类不存在**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ChatAppTemplateSyncRuntimeTest test
```

Expected: 编译失败，提示 `ChatAppTemplateSyncRuntime` 不存在。

- [ ] **Step 3: 实现运行时生命周期和结构化日志**

创建 `ChatAppTemplateSyncRuntime`：

```java
public final class ChatAppTemplateSyncRuntime implements AutoCloseable {
    @FunctionalInterface
    interface SyncAction {
        ChatAppTemplateSynchronizer.Outcome run() throws Exception;
    }

    private final SyncAction syncAction;
    private final IntConsumer changedPublisher;
    private final ScheduledExecutorService executor;
    private final int intervalSeconds;
    private final boolean enabled;
    private final AtomicBoolean closed = new AtomicBoolean();

    public static ChatAppTemplateSyncRuntime open(Config config,
                                                   ChatAppTemplateSynchronizer synchronizer,
                                                   IntConsumer changedPublisher) {
        boolean requested = config.chatappTemplateAutoSyncEnabled();
        boolean configured = config.hasChatAppTemplateSyncConfiguration();
        boolean enabled = requested && configured;
        if (requested && !configured) {
            System.err.println("chatapp.template_sync event=skipped_not_configured stage=config");
        }
        return new ChatAppTemplateSyncRuntime(enabled,
                config.chatappTemplateSyncIntervalSeconds(), synchronizer::sync, changedPublisher,
                enabled ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chatapp-template-sync");
                    thread.setDaemon(true);
                    return thread;
                }) : null);
    }

    ChatAppTemplateSyncRuntime(boolean enabled, int intervalSeconds,
                               SyncAction syncAction, IntConsumer changedPublisher,
                               ScheduledExecutorService executor) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.syncAction = Objects.requireNonNull(syncAction);
        this.changedPublisher = Objects.requireNonNull(changedPublisher);
        this.executor = executor;
    }

    public void start() {
        if (!enabled || closed.get()) return;
        executor.scheduleAtFixedRate(this::runOnce, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    public boolean enabled() {
        return enabled;
    }

    void runOnceForTests() { runOnce(); }

    private void runOnce() {
        if (closed.get()) return;
        long started = System.nanoTime();
        try {
            ChatAppTemplateSynchronizer.Outcome outcome = syncAction.run();
            if (outcome.status() == ChatAppTemplateSynchronizer.Status.CHANGED) {
                changedPublisher.accept(outcome.count());
            }
            log(outcome.status().name().toLowerCase(Locale.ROOT), outcome, null, started);
        } catch (Exception exception) {
            log("failed", null, exception, started);
        }
    }

    private static void log(String event, ChatAppTemplateSynchronizer.Outcome outcome,
                            Exception exception, long started) {
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String stage = exception instanceof ChatAppTemplateSynchronizer.SyncFailure failure
                ? failure.stage() : exception == null ? "none" : "unknown";
        String errorType = exception == null ? "none" : exception.getClass().getSimpleName();
        int fetched = outcome == null ? 0 : outcome.fetched();
        int changed = outcome == null ? 0 : outcome.changed();
        int count = outcome == null ? 0 : outcome.count();
        System.err.println("chatapp.template_sync event=" + event
                + " stage=" + stage + " durationMillis=" + durationMillis
                + " fetched=" + fetched + " changed=" + changed + " count=" + count
                + " errorType=" + errorType);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                System.err.println("chatapp.template_sync event=shutdown_timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`log` 必须只写 `event`、`durationMillis`、`fetched`、`changed`、`count` 和失败阶段/异常类型；不得打印模板正文、`raw` 或凭据。缺少 `CUST_SPACE_ID` 时 `open` 返回 disabled runtime 并只记录一次 `skipped_not_configured`。`LOCK_BUSY` 必须记录为 `lock_busy`，不发布事件。

- [ ] **Step 4: 运行运行时测试，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ChatAppTemplateSynchronizerTest,ChatAppTemplateSyncRuntimeTest test
```

Expected: exit code `0`；运行时关闭后不会再执行同步或发布事件。

- [ ] **Step 5: 提交后台运行时**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncRuntime.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChatAppTemplateSyncRuntimeTest.java
git commit -m "feat: schedule ChatApp template synchronization"
```

### Task 6: 接线 web 生命周期、typed SSE 与页面模板失效处理

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:60-70,110-165,2245-2375`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `ChatAppTemplateSynchronizer`、`ChatAppTemplateSyncRuntime`、`EventHub.publishTemplatesChanged(int)`。
- Produces: `event: templates-changed` / `data: {"count":N}`，以及浏览器 `EventSource.addEventListener('templates-changed', ...)`。

- [ ] **Step 1: 写入失败的 SSE 和页面契约探针**

在 `UnifiedMessageStoreTest.main` 的 SSE 测试后追加 `eventHubPublishesTemplateChanges();` 和 `templateRouteServesLastSuccessfulSnapshot();`，并新增：

```java
private static void eventHubPublishesTemplateChanges() throws Exception {
    Class<?> eventHubClass = Class.forName(App.class.getName() + "$EventHub");
    java.lang.reflect.Constructor<?> constructor = eventHubClass.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object hub = constructor.newInstance();
    java.lang.reflect.Method connect = eventHubClass.getDeclaredMethod("connect", HttpExchange.class);
    java.lang.reflect.Method publishTemplatesChanged = eventHubClass.getDeclaredMethod("publishTemplatesChanged", int.class);
    java.lang.reflect.Method close = eventHubClass.getDeclaredMethod("close");
    connect.setAccessible(true);
    publishTemplatesChanged.setAccessible(true);
    close.setAccessible(true);
    CloseAwareHttpExchange exchange = new CloseAwareHttpExchange("GET", "/events");
    try {
        connect.invoke(hub, exchange);
        publishTemplatesChanged.invoke(hub, 2);
        assertContains(exchange.responseText(), "event: templates-changed");
        assertContains(exchange.responseText(), "data: {\"count\":2}");
    } finally {
        close.invoke(hub);
    }

    String html = App.pageHtml();
    assertContains(html, "addEventListener('templates-changed'");
    assertContains(html, "await refreshTemplates()");
    int handlerStart = html.indexOf("addEventListener('templates-changed'");
    int handlerEnd = html.indexOf("});", handlerStart) + 3;
    String handler = html.substring(handlerStart, handlerEnd);
    assertNotContains(handler, "refreshAll(");
    assertNotContains(handler, "toast('有新消息')");
    assertNotContains(handler, "Notification");
}

private static void templateRouteServesLastSuccessfulSnapshot() throws Exception {
    Path dir = Files.createTempDirectory("message-center-template-route-test");
    Path emailData = dir.resolve("email");
    Path chatData = dir.resolve("chatapp");
    Files.createDirectories(emailData);
    Files.createDirectories(chatData);
    Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
    TemplateStore.TemplateRecord record = new TemplateStore.TemplateRecord();
    record.templateCode = "stable-template";
    record.templateName = "stable-template";
    record.languageCode = "zh_CN";
    record.body = "Stable body";
    record.raw = "{\"auditStatus\":\"pass\"}";
    new TemplateStore(config.chatappTemplateFile()).replaceIfChanged(List.of(record));
    FakeHttpExchange exchange = new FakeHttpExchange("GET", "/api/templates");

    invokeRoute(exchange, config, new UnifiedMessageStore(config));

    assertEquals(200, exchange.responseCode);
    assertContains(exchange.responseText(), "stable-template");
    assertContains(exchange.responseText(), "Stable body");
}
```

这些 substring 断言阻止模板变化走入消息刷新提示路径；如果事件处理函数不存在，前面的 `assertContains` 会先给出明确失败。

- [ ] **Step 2: 运行自运行探针，确认新事件尚不存在**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile && mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: 失败，提示 `publishTemplatesChanged` 或页面事件监听不存在。

- [ ] **Step 3: 在 `App` 创建唯一共享 owner 并管理关闭顺序**

在 `startWeb` 中先创建同步 owner，再把同一实例交给历史同步服务和 runtime：

```java
ChatAppTemplateSynchronizer templateSynchronizer = ChatAppTemplateSynchronizer.create(config);
ChatAppHistorySyncService chatAppSyncService = new ChatAppHistorySyncService(config, templateSynchronizer);
EventHub events = new EventHub();
ChatAppTemplateSyncRuntime templateRuntime = ChatAppTemplateSyncRuntime.open(
        config, templateSynchronizer, events::publishTemplatesChanged);
```

`server.start()` 成功后立即调用 `templateRuntime.start()`。关闭钩子中的顺序必须是：

```java
templateRuntime.close();
finalDailySummary.close();
events.close();
server.stop(0);
```

`sync-templates` CLI 分支继续调用 `new ChatAppHistorySyncService(config).syncTemplates()` 并序列化原有 `SyncResult`，保持现有 CLI 输出字段；该方法内部已经委托 `ChatAppTemplateSynchronizer`。不得直接把内部 `Outcome` 暴露为新的 CLI 合同。

- [ ] **Step 4: 发布 typed SSE，不改变消息事件语义**

在 `EventHub` 添加：

```java
void publishTemplatesChanged(int count) {
    String payload = "event: templates-changed\n"
            + "data: " + GSON.toJson(Map.of("count", count)) + "\n\n";
    publishBytes(payload.getBytes(StandardCharsets.UTF_8));
}
```

保留 `publish(UnifiedMessage)` 的默认 `data:` 事件格式和现有心跳，不给模板事件复用 `UnifiedMessage`。

- [ ] **Step 5: 在页面精确失效模板状态**

在 `connectEvents()` 前定义：

```javascript
async function refreshTemplates() {
  state.templates = await api('/api/templates');
  if (state.selectedChannel === 'chatapp' && state.selectedMode === 'template') {
    renderComposer();
  }
}
```

替换 `connectEvents()` 内部绑定为：

```javascript
state.eventSource = new EventSource('/events');
state.eventSource.onmessage = async () => { toast('有新消息'); await refreshAll(true); };
state.eventSource.addEventListener('templates-changed', async () => {
  try {
    await refreshTemplates();
  } catch (error) {
    console.error('template refresh failed', error);
  }
});
```

禁止模板事件调用 `refreshAll`、消息 toast 或系统通知。SSE 不可用时不新增轮询模板；页面下次加载仍从 `/api/templates` 获取成功快照。

- [ ] **Step 6: 运行 SSE/页面探针，确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q test-compile && mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: exit code `0`，输出包含现有探针成功信息，且不访问阿里云网络。

- [ ] **Step 7: 提交 web 接线**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: refresh templates after ChatApp sync events"
```

### Task 7: 回写配置文档并完成全量验收

**Files:**
- Modify: `demo/message-center-demo/config.example.env:88-102`
- Modify: `demo/message-center-demo/README.md:138-152,286-300`
- Modify: `docs/superpowers/specs/2026-07-30-chatapp-template-auto-sync-design.md:1-3`

**Interfaces:**
- Documents: 自动同步开关、5 分钟默认值、页/数量上限、旧快照降级、SSE 页面行为、CLI 保留和 ChatAppAudit 的非关键路径地位。

- [ ] **Step 1: 写入文档验收搜索**

Run:

```bash
rg -n "CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED|CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS|TEMPLATE_MAX_PAGES=40|templates-changed" demo/message-center-demo/config.example.env demo/message-center-demo/README.md
```

Expected: 当前尚无自动同步变量、`TEMPLATE_MAX_PAGES=40` 和 SSE 说明。

- [ ] **Step 2: 更新示例配置与 README**

在 `config.example.env` 模板配置段写入：

```env
CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED=true
CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS=300
TEMPLATE_PAGE_SIZE=50
TEMPLATE_MAX_PAGES=40
```

在 README 说明：web 服务监听后异步同步并每 5 分钟全量对账；上游正常时 5～10 分钟可见变化；任一失败、超时、锁忙或超过上限时继续使用上一版成功快照；`sync-templates` 保留为单次运行入口；`ChatAppAudit` 不是完整模板库回调，当前不要求配置。保留 `SYNC_TEMPLATES_BEFORE_MESSAGES`，但注明它只作为兼容入口并委托同一个同步器，不应作为自动更新机制。

把设计文档状态由“用户已确认设计，实施计划已制定，待实施”更新为“已实施，验收通过”，只在本任务所有验收命令成功后执行。

- [ ] **Step 3: 运行针对性 JUnit 验收**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,TemplateStoreTest,AliyunChatAppTemplateGatewayTest,ChatAppTemplateSynchronizerTest,ChatAppTemplateSyncRuntimeTest test
```

Expected: exit code `0`。

- [ ] **Step 4: 运行完整 Java 测试与内嵌页面探针**

Run:

```bash
cd demo/message-center-demo && mvn -q test
cd demo/message-center-demo && mvn -q test-compile && mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
```

Expected: 三个命令均 exit code `0`。若 Docker 相关既有集成测试受环境阻断，记录精确测试名和错误，但不能把它标记为通过。

- [ ] **Step 5: 检查 diff、文档与暂存边界**

Run:

```bash
git diff --check
rg -n "T[B]D|T[O]DO|待[定]|后续再[补]" docs/superpowers/specs/2026-07-30-chatapp-template-auto-sync-design.md demo/message-center-demo/README.md demo/message-center-demo/config.example.env
git status --short
```

Expected: `git diff --check` 无输出；搜索无输出；状态只包含本计划列出的源码、测试、配置、README 和设计文档改动。

- [ ] **Step 6: 提交文档与最终验收结果**

```bash
git add demo/message-center-demo/config.example.env demo/message-center-demo/README.md docs/superpowers/specs/2026-07-30-chatapp-template-auto-sync-design.md
git commit -m "docs: describe ChatApp template auto sync"
```

## Plan Self-Review

| 设计要求 | 对应任务 |
| --- | --- |
| 启动异步同步、5 分钟调度和可关闭生命周期 | Task 1、Task 5、Task 6 |
| 全量构造、删除同步、原子替换和失败保留旧快照 | Task 2、Task 4 |
| 15 秒请求、240 秒轮次、40 页/2,000 条上限 | Task 1、Task 3、Task 4 |
| 进程内单飞与跨进程锁 | Task 4 |
| 只在内容变化时发模板 SSE | Task 2、Task 4、Task 5、Task 6 |
| 页面只刷新模板控件 | Task 6 |
| CLI 和旧消息同步开关的兼容委托 | Task 4、Task 6、Task 7 |
| 不接入 ChatAppAudit、回写用户文档 | Task 7 |
| 单元、运行时、SSE、页面和全量验收 | Task 1 至 Task 7 |

占位符扫描、接口命名和任务依赖已复核：`ChatAppTemplateGateway`、`ChatAppTemplateSynchronizer.Outcome`、`TemplateStore.ReplaceResult` 和 `ChatAppTemplateSyncRuntime` 在首次使用前均已在前序任务定义。本计划不包含视觉布局改动，因此不需要浏览器截图验收；内嵌页面的行为由现有自运行 VM/SSE 探针覆盖。
