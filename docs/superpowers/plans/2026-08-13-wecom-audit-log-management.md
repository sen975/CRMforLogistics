# 企业微信本地审计日志治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为本地 message-center demo 的企业微信 viewer 与授权审计建立有界轮转、gzip 归档、7 日保留、故障降级、授权预写对账和只读 `audit-status` CLI。

**Architecture:** `AuditFileSettings` 是共享配置合同，`BoundedAuditFile` 是当前 JSONL、gzip 归档、预算、磁盘阈值和 writer 锁的唯一 owner，`AuditRuntime` 是 web 进程唯一装配与关闭 owner。两个 AuditTrail 只构造白名单事件；授权流再由 `AuthorizationAuditIndex` 与 `WeComAuthorizationStore.lastAuthorizationEvent*` 共同完成预写、幂等和恢复对账；`AuditStatusReporter` 只读扫描稳定快照，不参与写入或清理。

**Tech Stack:** Java 17、NIO `FileChannel`/`FileLock`/`GZIPInputStream`/`GZIPOutputStream`、Gson 2.11、JUnit Jupiter 5.11、Maven Surefire 3.5、PowerShell 启动适配。

## Global Constraints

- 设计真源：`docs/superpowers/specs/2026-08-13-wecom-audit-log-management-design.md`。
- 只治理 `WECOM_VIEWER_AUDIT_FILE` 与 `WECOM_AUTHORIZATION_AUDIT_FILE`；不得触碰 PostgreSQL `audit_logs`、业务消息 JSONL、附件、stdout/stderr、Docker 日志或 HTTP API。
- 统一配置默认值固定为：保留 7 日、当前文件 1 MiB、单流 8 MiB、最小磁盘余量 64 MiB、告警间隔 3600 秒。
- 删除 `WECOM_VIEWER_AUDIT_MAX_BYTES` 与 `WECOM_AUTHORIZATION_AUDIT_MAX_BYTES`；任一旧键存在即配置失败，不保留别名。
- 当前文件保持 `.jsonl`；归档固定为 `<base>.<UTC-date>.<NNN>.jsonl.gz`。
- viewer 审计为 best-effort；授权审计为 required。不得用同一个 catch 分支吞掉两者失败。
- required 追加返回前执行 `FileChannel.force(true)`；best-effort 追加返回前执行 `force(false)`。
- 不记录 access token、Secret、ticket、auth code、permanent code、`secretKey`、请求/响应正文、消息正文或 OpenDataFrame URL。
- 不支持两个独立 JVM 同时写同一审计流；第二个 writer 必须失败，`audit-status` 仍须只读可用。
- 不引入第三方日志轮转库、数据库迁移、HTTP 状态接口或后台压缩线程。
- 当前工作区包含大量无关修改。每次提交必须使用精确路径 `git add`，不得使用 `git add .`，不得触碰 `demo/message-center-spring`。

## File Map

**新增生产文件：**

- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileSettings.java`：不可变共享配置与路径规范化。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStorageException.java`：稳定错误码和流名，不携带事件正文。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditDiskSpaceProbe.java`：磁盘余量可注入边界。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileCatalog.java`：纯只读文件分类、稳定快照和有界 gzip 读取。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/BoundedAuditFile.java`：writer registry、追加、轮转、压缩、恢复、清理和容量 owner。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditWarningReporter.java`：按 `(stream,errorCode)` 限频和 recovered 状态。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ViewerAuditSink.java`：viewer/login/sync 共同消费的最小审计接口。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuthorizationAuditIndex.java`：legacy/new 行解析、attempt 分配、开放尝试索引。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStatusReporter.java`：只读稳定快照和状态 JSON 数据模型。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditRuntime.java`：web 进程装配、共享 AuditTrail 和关闭顺序。

**新增测试文件：**

- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileTest.java`
- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileLockProbe.java`
- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditWarningReporterTest.java`
- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrailTest.java`
- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditStatusReporterTest.java`
- `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditSensitiveFieldRegressionTest.java`

**修改生产文件：**

- `Config.java`：统一配置和旧键失败。
- `WeComViewerAuditTrail.java`：白名单事件接入 shared writer，失败交给告警器。
- `WeComAuthorizationAuditTrail.java`：required 阶段事件与索引。
- `WeComAuthorizationStore.java`：持久化授权事件标记和幂等 mutation。
- `WeComAuthorizationService.java`：accepted/pending/final、故障闸门和启动对账。
- `WeComViewerService.java`、`WeComLoginAttemptService.java`、`WeComChatDataSyncService.java`：注入共享 viewer AuditTrail，不再自行打开文件。
- `App.java`：创建/关闭 `AuditRuntime`，新增 `audit-status` 命令和退出码。
- `message-center-demo.ps1`：允许 `audit-status`。
- `config.example.env`、`README.md`：新配置和运维合同。

**修改测试文件：**

- `ConfigTest.java`、`AppTest.java`、`WeComViewerAuditTrailTest.java`。
- `WeComAuthorizationStoreTest.java`、`WeComAuthorizationServiceTest.java`。
- `WeComViewerServiceTest.java`、`WeComLoginAttemptServiceTest.java`、`WeComChatDataSyncServiceTest.java` 及 `UnifiedMessageStoreTest.java` 中受构造器注入影响的调用点。

---

### Task 1: 锁定统一配置合同

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileSettings.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrail.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrail.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**Interfaces:**

- Produces: `AuditFileSettings(Path file, int retentionDays, long fileMaxBytes, long streamMaxBytes, long minFreeDiskBytes, Duration warningInterval)`。
- Produces: `Config.viewerAuditSettings()`、`Config.authorizationAuditSettings()`。
- Removes: `Config.wecomViewerAuditMaxBytes()`、`Config.wecomAuthorizationAuditMaxBytes()`。

- [ ] **Step 1: 写失败的默认值、范围、路径和旧键测试**

在 `ConfigTest` 增加以下测试结构；用 `HashMap` 覆盖单个值，避免 `Map.of` 无法表达空旧值：

```java
@Test
void exposesSharedAuditSettingsAndRejectsInvalidRelationships() {
    Config defaults = new Config(Map.of("DATA_DIR", tempDir.toString()));
    AuditFileSettings viewer = defaults.viewerAuditSettings();
    AuditFileSettings authorization = defaults.authorizationAuditSettings();

    assertEquals(tempDir.resolve("wecom-viewer-audit.jsonl").toAbsolutePath().normalize(), viewer.file());
    assertEquals(tempDir.resolve("wecom-authorization-audit.jsonl").toAbsolutePath().normalize(),
            authorization.file());
    assertEquals(7, viewer.retentionDays());
    assertEquals(1_048_576L, viewer.fileMaxBytes());
    assertEquals(8_388_608L, viewer.streamMaxBytes());
    assertEquals(67_108_864L, viewer.minFreeDiskBytes());
    assertEquals(Duration.ofSeconds(3_600), viewer.warningInterval());

    assertThrows(IllegalArgumentException.class, () -> new Config(Map.of(
            "AUDIT_FILE_MAX_BYTES", "1048576",
            "AUDIT_STREAM_MAX_BYTES", "1048575")).viewerAuditSettings());
    assertThrows(IllegalArgumentException.class, () -> new Config(Map.of(
            "AUDIT_FILE_MAX_BYTES", "1048576",
            "AUDIT_MIN_FREE_DISK_BYTES", "1048575")).viewerAuditSettings());
    assertThrows(IllegalArgumentException.class, () -> new Config(Map.of(
            "WECOM_VIEWER_AUDIT_FILE", "same.jsonl",
            "WECOM_AUTHORIZATION_AUDIT_FILE", "same.jsonl")).validateAuditConfiguration());
    assertThrows(IllegalArgumentException.class, () -> new Config(Map.of(
            "WECOM_VIEWER_AUDIT_FILE", "viewer.log")).viewerAuditSettings());
}

@Test
void rejectsLegacyAuditKeysEvenWhenEnvFileValueIsBlank() throws Exception {
    Path env = tempDir.resolve("legacy.env");
    Files.writeString(env, "WECOM_VIEWER_AUDIT_MAX_BYTES=\n");
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Config.load(env));
    assertTrue(error.getMessage().contains("WECOM_VIEWER_AUDIT_MAX_BYTES"));

    assertThrows(IllegalArgumentException.class, () -> new Config(Map.of(
            "WECOM_AUTHORIZATION_AUDIT_MAX_BYTES", "1048576")));
}
```

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest test
```

Expected: FAIL，编译错误指向缺少 `AuditFileSettings`、`viewerAuditSettings()`、`authorizationAuditSettings()` 或旧键未被拒绝。

- [ ] **Step 3: 实现不可变设置与集中验证**

新增 record，并在 compact constructor 中规范化绝对路径：

```java
record AuditFileSettings(Path file, int retentionDays, long fileMaxBytes,
                         long streamMaxBytes, long minFreeDiskBytes,
                         Duration warningInterval) {
    AuditFileSettings {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        warningInterval = Objects.requireNonNull(warningInterval, "warningInterval");
        if (!file.getFileName().toString().endsWith(".jsonl")) {
            throw new IllegalArgumentException("audit file must end with .jsonl");
        }
        if (retentionDays < 1 || retentionDays > 365
                || fileMaxBytes < 4_096L || fileMaxBytes > 20_971_520L
                || streamMaxBytes < fileMaxBytes || streamMaxBytes > 1_073_741_824L
                || minFreeDiskBytes < fileMaxBytes || minFreeDiskBytes > 1_099_511_627_776L
                || warningInterval.compareTo(Duration.ofSeconds(60)) < 0
                || warningInterval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("audit settings are invalid");
        }
    }
}
```

`Config` 构造时复制 Map 后立即检查两个旧键是否 `containsKey`；`Config.load` 必须保留 `.env` 空值的键存在性，并在合并进程环境后调用同一构造器。新增：

```java
public AuditFileSettings viewerAuditSettings() { return auditSettings(wecomViewerAuditFile()); }
public AuditFileSettings authorizationAuditSettings() { return auditSettings(wecomAuthorizationAuditFile()); }

public void validateAuditConfiguration() {
    AuditFileSettings viewer = viewerAuditSettings();
    AuditFileSettings authorization = authorizationAuditSettings();
    if (viewer.file().equals(authorization.file())) {
        throw new IllegalArgumentException("viewer and authorization audit files must differ");
    }
}

private AuditFileSettings auditSettings(Path file) {
    return new AuditFileSettings(file,
            boundedInt("AUDIT_RETENTION_DAYS", 7, 1, 365),
            boundedLong("AUDIT_FILE_MAX_BYTES", 1_048_576L, 4_096L, 20_971_520L),
            boundedLong("AUDIT_STREAM_MAX_BYTES", 8_388_608L, 4_096L, 1_073_741_824L),
            boundedLong("AUDIT_MIN_FREE_DISK_BYTES", 67_108_864L, 4_096L, 1_099_511_627_776L),
            Duration.ofSeconds(boundedInt("AUDIT_WARNING_INTERVAL_SECONDS", 3_600, 60, 86_400)));
}
```

删除两个旧 max-bytes getter；构造器最后调用 `rejectLegacyAuditKeys()`，`Config.load()` 返回前调用 `validateAuditConfiguration()`，确保所有 CLI 都共享验证。为保持本任务提交可编译，只把两个现有 AuditTrail 构造器的旧 getter 引用机械替换为对应 settings 的 `file()` 和 `fileMaxBytes()`；本任务不改变其追加和失败语义，Task 4/5 再分别接入 shared writer。

- [ ] **Step 4: 运行 GREEN 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test`

Expected: PASS，旧键、关系约束、路径冲突和默认值全部通过。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileSettings.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrail.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrail.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java
git commit -m "feat: define bounded audit configuration"
```

---

### Task 2: 实现 writer registry、原子追加与基础轮转

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStorageException.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditDiskSpaceProbe.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/BoundedAuditFile.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileTest.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileLockProbe.java`

**Interfaces:**

- Produces: `BoundedAuditFile.acquire(String stream, AuditFileSettings settings, Clock clock, AuditDiskSpaceProbe diskSpaceProbe)`。
- Produces: `append(byte[] jsonLine, Durability durability)`、`prepareForAuthorizationRecovery()`、`enableRetentionCleanup()`、`close()`；所有 acquire 初始都禁止保留期清理。
- Produces stable error codes: `AUDIT_WRITER_LOCKED`、`AUDIT_EVENT_TOO_LARGE`、`AUDIT_CURRENT_CORRUPTED`、`AUDIT_ROTATION_FAILED`、`AUDIT_STREAM_BUDGET_EXCEEDED`、`AUDIT_DISK_SPACE_LOW`。

- [ ] **Step 1: 写追加、大小轮转、UTC 轮转、并发和跨进程锁 RED 测试**

测试使用小而合法的 4096-byte 上限；通过填充安全 JSON 字段逼近边界。关键断言：

```java
@Test
void rotatesBeforeOverflowAndKeepsWholeLines() throws Exception {
    AuditFileSettings settings = settings(4_096, 32_768);
    try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
        for (int index = 0; index < 40; index++) {
            file.append(line(index, "x".repeat(160)), BoundedAuditFile.Durability.REQUIRED);
        }
    }
    List<Path> archives = archives("2026-08-13");
    assertFalse(archives.isEmpty());
    assertEquals(40, readAllJsonLines(settings.file()).size()
            + archives.stream().mapToInt(this::gzipLineCount).sum());
    assertTrue(Files.size(settings.file()) <= 4_096L);
}

@Test
void sharesOneHandleInsideJvmAndRejectsSecondWriterProcess() throws Exception {
    BoundedAuditFile first = open(settings(4_096, 32_768), fixed("2026-08-13T01:00:00Z"));
    BoundedAuditFile second = open(settings(4_096, 32_768), fixed("2026-08-13T01:00:00Z"));
    first.append(line(1, "a"), BoundedAuditFile.Durability.REQUIRED);
    second.append(line(2, "b"), BoundedAuditFile.Durability.REQUIRED);
    assertEquals(2, Files.readAllLines(first.path()).size());
    assertEquals(3, runLockProbe(first.path())); // probe contract: exit 3 means writer lock denied
    second.close();
    first.close();
    assertEquals(0, runLockProbe(first.path()));
}
```

`BoundedAuditFileLockProbe.main(String[])` 构造最小 settings，尝试 acquire 后立即关闭；`AuditStorageException.code()==AUDIT_WRITER_LOCKED` 时 `System.exit(3)`，其他失败 `System.exit(4)`。

- [ ] **Step 2: 运行 RED 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=BoundedAuditFileTest test`

Expected: FAIL，生产类型尚不存在。

- [ ] **Step 3: 实现最小 writer owner**

定义稳定异常和磁盘探针：

```java
final class AuditStorageException extends IOException {
    private final String code;
    private final String stream;
    AuditStorageException(String code, String stream, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.stream = stream;
    }
    String code() { return code; }
    String stream() { return stream; }
}

@FunctionalInterface
interface AuditDiskSpaceProbe {
    long usableBytes(Path directory) throws IOException;
    static AuditDiskSpaceProbe system() {
        return directory -> Files.getFileStore(directory).getUsableSpace();
    }
}
```

`BoundedAuditFile` 使用静态 `Map<Path, SharedHandle>` registry；registry 操作受单独 mutex 保护，handle 持有 `ReentrantLock`、lock channel、`FileLock`、引用计数和配置指纹。相同路径但不同 settings 必须抛 `AUDIT_SETTINGS_CONFLICT`。所有新 handle 的 `retentionCleanupEnabled=false`；调用者必须完成该流启动检查后显式启用。

```java
enum Durability { BEST_EFFORT, REQUIRED }

static BoundedAuditFile acquire(String stream, AuditFileSettings settings,
                                Clock clock, AuditDiskSpaceProbe diskSpaceProbe)
        throws AuditStorageException;

void append(byte[] jsonLine, Durability durability) throws AuditStorageException;
Path path();
```

`append` 必须验证：单行以一个 `\n` 结尾、内部没有换行、UTF-8 可解码、解析为 JSON object、包含合法 UTC `occurredAt`；循环 `channel.write(ByteBuffer)` 写完整行并按 durability force。当前文件第一条完整行决定所属日期；坏行返回 `AUDIT_CURRENT_CORRUPTED`。

归档 stem 等于当前文件名去掉末尾 `.jsonl`。例如 current 为 `wecom-viewer-audit.jsonl` 时，轮转固定采用：原子移动 current -> `wecom-viewer-audit.2026-08-13.001.jsonl.rotating`，同步 gzip 到 `wecom-viewer-audit.2026-08-13.001.jsonl.gz.tmp`，强制临时文件落盘，完整解压校验，原子移动为 `wecom-viewer-audit.2026-08-13.001.jsonl.gz`，删除 rotating，再创建 current。任何路径均不得覆盖已有文件。

- [ ] **Step 4: 写并发测试并验证通过**

使用 8 个线程各写 100 行，线程池有界为 8，测试结束后按 `id` 集合断言恰好 800 条且每行可由 Gson 解析。运行：

```bash
cd demo/message-center-demo
mvn -q -Dtest=BoundedAuditFileTest test
```

Expected: PASS；没有拆行、丢行、重复 id 或归档覆盖。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStorageException.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditDiskSpaceProbe.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/BoundedAuditFile.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileLockProbe.java
git commit -m "feat: add bounded audit file rotation"
```

---

### Task 3: 完成保留期、预算、恢复与 legacy 边界

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileCatalog.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/BoundedAuditFile.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileTest.java`

**Interfaces:**

- Consumes Task 2 `BoundedAuditFile`。
- Produces: `AuditFileCatalog.scanStable(AuditFileSettings, Clock)`，返回 `AuditFileSnapshot`，包含 current/archive/recovery/unknown 文件、字节和 issue codes；它不 acquire writer、不创建/恢复/删除文件，供 writer、授权索引和 Task 7 reporter 复用。
- Produces: `rotateLegacyCurrentBeforeFirstWrite()`，供授权 AuditTrail 升级使用。

- [ ] **Step 1: 写恢复、7 日边界、8 MiB 预算和磁盘阈值 RED 测试**

加入这些确定性场景：

```java
@Test
void deletesOnlyValidArchivesOlderThanSevenUtcDays() throws Exception {
    writeArchive("2026-08-06", 1, validLines());
    writeArchive("2026-08-07", 1, validLines());
    writeCorruptArchive("2026-08-05", 1);
    appendAt("2026-08-13T12:00:00Z");
    assertFalse(Files.exists(archive("2026-08-06", 1)));
    assertTrue(Files.exists(archive("2026-08-07", 1)));
    assertTrue(Files.exists(archive("2026-08-05", 1)));
}

@Test
void refusesWriteWhenBudgetOrDiskFloorWouldBeCrossed() throws Exception {
    AuditFileSettings settings = settings(4_096, 8_192);
    fillKnownAndUnknownFilesTo(8_000);
    AuditStorageException budget = assertThrows(AuditStorageException.class,
            () -> open(settings, fixedNow(), directory -> Long.MAX_VALUE)
                    .append(line(1, "x".repeat(500)), Durability.REQUIRED));
    assertEquals("AUDIT_STREAM_BUDGET_EXCEEDED", budget.code());

    AuditStorageException disk = assertThrows(AuditStorageException.class,
            () -> open(settings, fixedNow(), directory -> settings.minFreeDiskBytes() - 1)
                    .append(line(2, "ok"), Durability.REQUIRED));
    assertEquals("AUDIT_DISK_SPACE_LOW", disk.code());
}
```

另测 `.rotating` 恢复、有效 tmp 发布、坏 tmp 保留、超限旧 current 作为单个归档、gzip 临时峰值 `source+64KiB`、解压超过 `streamMaxBytes`、非法命名计入预算、坏 current 阻断。

- [ ] **Step 2: 运行 RED 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=BoundedAuditFileTest test`

Expected: FAIL，至少指向缺少清理、恢复、预算或 snapshot 行为。

- [ ] **Step 3: 按固定顺序实现写前治理**

`AuditFileCatalog` 只负责严格文件名分类、当前 JSONL 稳定读取和有上界 gzip 解压。`AuditFileSnapshot` 中保留不可变 `AuditFileEntry(path, kind, utcDate, sequence, bytes, issueCode)`，但 CLI projection 不暴露 entry path 或内容。

在同一 handle lock 内固定执行：

```java
recoverKnownArtifacts();
if (retentionCleanupEnabled) deleteExpiredValidArchives(clock.instant());
Usage usage = scanUsageWithUnknownFiles();
requireBudgetFor(usage, jsonLine.length, possibleRotationPeak());
requireFreeDiskFor(jsonLine.length, possibleRotationPeak());
rotateIfUtcDayChangedOrWouldOverflow(jsonLine.length);
appendFullyAndForce(jsonLine, durability);
```

合法文件名使用预编译、引用 base name 的正则和严格 UTC 日期解析。自动删除前必须完整读取 gzip 且解压不超过 `streamMaxBytes`；未知、损坏、恢复文件只计数和计费，不删除。

授权启动需要先对账再清理，因此 `prepareForAuthorizationRecovery()` 恢复 artifact 但保持 cleanup disabled；`enableRetentionCleanup()` 只能在对账成功后调用一次。viewer runtime 也要在 current/归档检查成功后显式调用 `enableRetentionCleanup()`，不存在 acquire 时自动清理的第二条路径。

- [ ] **Step 4: 运行完整 BoundedAuditFile 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=BoundedAuditFileTest test`

Expected: PASS，包含跨进程锁、并发、轮转、保留、预算、磁盘与恢复场景。

- [ ] **Step 5: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/BoundedAuditFile.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditFileCatalog.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/BoundedAuditFileTest.java
git commit -m "feat: recover and retain bounded audit files"
```

---

### Task 4: 接入 viewer best-effort 与限频告警

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditWarningReporter.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditRuntime.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ViewerAuditSink.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditWarningReporterTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrail.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrailTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataSyncService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify affected constructor calls in `WeComViewerServiceTest.java`, `WeComLoginAttemptServiceTest.java`, `WeComChatDataSyncServiceTest.java`, `UnifiedMessageStoreTest.java`, `WeComAuthorizationCallbackRouteTest.java`。

**Interfaces:**

- Produces: `AuditWarningReporter.reportFailure(stream, errorCode)`、`reportRecovered(stream, errorCode)`。
- Produces: `WeComViewerAuditTrail implements ViewerAuditSink`；`record*()` 不再抛 storage failure，字段验证错误仍是 programmer error。
- Produces injection overloads accepting `ViewerAuditSink` for viewer/login，sync production constructor receives the same sink and adapts it to its existing internal `AuditSink`.
- Produces: Task 4 阶段的 `AuditRuntime.openViewer(Config)`、`viewerTrail()`、`close()`，只 acquire viewer 流；Task 6 再扩展 authorization 流。

- [ ] **Step 1: 写限频、恢复和 best-effort RED 测试**

用 `ByteArrayOutputStream` 捕获告警，不替换全局 stderr：

```java
@Test
void limitsEachStreamAndCodeThenReportsOneRecovery() {
    Clock clock = new MutableClock("2026-08-13T00:00:00Z");
    AuditWarningReporter reporter = new AuditWarningReporter(clock, Duration.ofHours(1), lines::add);
    reporter.reportFailure("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
    reporter.reportFailure("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
    assertEquals(1, lines.size());
    reporter.reportRecovered("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
    reporter.reportRecovered("wecom-viewer", "AUDIT_DISK_SPACE_LOW");
    assertEquals(2, lines.size());
    assertFalse(String.join("", lines).contains("user"));
}
```

`WeComViewerAuditTrailTest` 注入一个总是抛 `AUDIT_DISK_SPACE_LOW` 的 writer seam，断言 `record` 不抛、业务调用仍返回原结果且只输出一次告警；writer 恢复后断言一次 recovered。

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=AuditWarningReporterTest,WeComViewerAuditTrailTest test
```

Expected: FAIL，缺少 reporter 和 best-effort seam。

- [ ] **Step 3: 实现稳定 JSON 告警与 AuditTrail 接线**

告警只允许以下固定字段：

```java
Map.of("event", "wecom.audit.write",
       "stream", stream,
       "status", status,
       "errorCode", errorCode)
```

`AuditWarningReporter` 用有界 Map 保存每个 `(stream,errorCode)` 的最后告警时刻与 failed 状态；合法 stream 固定为 `wecom-viewer`、`wecom-authorization`，错误码固定匹配 `[A-Z0-9_]{1,128}`。Map 最大项数等于内部稳定错误码数量，不接受外部自由值。

`WeComViewerAuditTrail` 先完成现有字段验证和 Gson 编码，再调用 `BoundedAuditFile.append(...BEST_EFFORT)`；只捕获 `AuditStorageException` 并报告，不能吞 `IllegalArgumentException`。

- [ ] **Step 4: 将生产服务改为注入共享 trail**

三类 service 的生产构造器增加明确参数，不再在内部 `new WeComViewerAuditTrail`：

```java
WeComViewerService(Config config, WeComAuthorizationStore store,
                   WeComAccessTokenService tokens, WeComAuthorizationGateway gateway,
                   ViewerAuditSink audit)

WeComLoginAttemptService(Config config, WeComAuthorizationStore store,
                         ViewerAuditSink audit)

WeComChatDataSyncService(Config config, WeComChatDataGateway gateway,
                         ViewerAuditSink audit)
```

`ViewerAuditSink` 精确声明现有五参数 `record(...)` 和十参数 diagnostic owner；两个短 diagnostic 形式只做默认转发，均不声明 checked exception：

```java
interface ViewerAuditSink {
    void record(String action, String result, String wecomUserId,
                String contactPointId, String viewerSessionId);

    default void recordDiagnostic(String action, String result, String wecomUserId,
                                  String contactPointId, String viewerSessionId,
                                  String errorCode, Integer upstreamErrcode,
                                  String upstreamPath) {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, null, null);
    }

    default void recordDiagnostic(String action, String result, String wecomUserId,
                                  String contactPointId, String viewerSessionId,
                                  String errorCode, Integer upstreamErrcode,
                                  String upstreamPath, Integer upstreamHttpStatus) {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, upstreamHttpStatus, null);
    }

    void recordDiagnostic(String action, String result, String wecomUserId,
                          String contactPointId, String viewerSessionId,
                          String errorCode, Integer upstreamErrcode,
                          String upstreamPath, Integer upstreamHttpStatus,
                          String upstreamHint);
}
```

sync 将该 sink 适配到现有内部 `AuditSink.record/recordFailure`，不让 sync 内部接口成为第二个业务 owner。测试 factory 接受显式 sink；不关心审计的测试在各测试类内使用匿名 no-op sink helper，生产代码不得提供测试专用 no-op 工厂或分支。

新增 `AuditRuntime.openViewer(Config)`：验证配置、acquire viewer writer、检查 current/归档、显式 `enableRetentionCleanup()`，创建 warning reporter 和唯一 viewer trail；失败时逆序关闭。`App.startWeb` 在构造 viewer/login/sync service 前打开 runtime 并注入同一 sink；HTTP server 创建失败、runtime 启动失败和 shutdown hook 都关闭它。此阶段 authorization service 继续使用现有授权 AuditTrail，Task 6 再一次性替换授权 owner，不产生同一流双 writer。

- [ ] **Step 5: 运行受影响测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=AuditWarningReporterTest,WeComViewerAuditTrailTest,WeComViewerServiceTest,WeComLoginAttemptServiceTest,WeComChatDataSyncServiceTest,UnifiedMessageStoreTest,WeComAuthorizationCallbackRouteTest test
```

Expected: PASS；现有 viewer 行为不变，存储失败不再改变业务结果。

- [ ] **Step 6: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditWarningReporter.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditRuntime.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ViewerAuditSink.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrail.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataSyncService.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditWarningReporterTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerAuditTrailTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerServiceTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataSyncServiceTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationCallbackRouteTest.java
git commit -m "feat: degrade viewer audit failures safely"
```

---

### Task 5: 建立授权审计索引与 Store 幂等标记

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuthorizationAuditIndex.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrailTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrail.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationStore.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationStoreTest.java`

**Interfaces:**

- Produces `AuthorizationAuditAttempt(eventId, attempt, action, callbackTimestamp)`。
- Produces `begin(callback) -> BeginResult(disposition, attempt)`；disposition 固定为 `NEW_ATTEMPT|OPEN_ALREADY_ACCEPTED|ALREADY_SUCCEEDED`。
- Produces `pending(attempt, corpId, targetStatus, expectedVersion)`、`succeeded(attempt, corpId)`、`failed(attempt, corpId, failure)`。
- Produces `OpenAttempt` index；只有不存在历史或所有历史 attempt 都已 failed 时，才在授权 writer 锁内分配下一 attempt 并追加 accepted。
- Extends `Installation` with nullable `lastAuthorizationEventId` and `lastAuthorizationEventAt`.
- Produces Store methods `upsertActiveForEvent(...)`、`updateStatusForEvent(...)` returning `MutationResult(installation, applied)`。

- [ ] **Step 1: 写 eventId、attempt、legacy 和敏感字段 RED 测试**

关键测试：同一 callback 第一次 `begin` 返回 `NEW_ATTEMPT/attempt=1`；attempt 仍开放时再次 begin 返回 `OPEN_ALREADY_ACCEPTED/attempt=1` 且不追加新行；attempt succeeded 后返回 `ALREADY_SUCCEEDED`；attempt failed 后下一次 begin 返回 `NEW_ATTEMPT/attempt=2`，再次 failed 后第三次 begin 返回 attempt 3。16 个不同 callback 并发 begin 的 eventId 各自稳定；legacy 行不报错且不进 open index；阶段行不含 auth code、ticket、permanent code。

```java
@Test
void reusesOpenAttemptAndAllocatesNextOnlyAfterFailure() throws Exception {
    DecodedCallback callback = callback("reset_permanent_code", "", "one-time-auth-code", "");
    BeginResult first = trail.begin(callback);
    BeginResult duplicate = trail.begin(callback);
    assertEquals(BeginDisposition.NEW_ATTEMPT, first.disposition());
    assertEquals(BeginDisposition.OPEN_ALREADY_ACCEPTED, duplicate.disposition());
    assertEquals(first.attempt(), duplicate.attempt());
    trail.failed(first.attempt(), "ww-corp", temporaryFailure());
    BeginResult retry = trail.begin(callback);
    assertEquals(2, retry.attempt().attempt());
    String all = readCurrentAndArchives();
    assertFalse(all.contains("one-time-auth-code"));
    assertFalse(all.contains("permanent-code"));
}
```

- [ ] **Step 2: 写 Store 原子标记与旧 schema RED 测试**

```java
@Test
void appliesAuthorizationEventOnceAndReadsLegacyInstallation() throws Exception {
    writeLegacyInstallationWithoutEventFields();
    WeComAuthorizationStore store = store();
    MutationResult first = store.updateStatusForEvent("suite", "corp", REVOKED,
            "sha256:event", Instant.parse("2026-08-13T01:00:00Z"));
    MutationResult replay = store.updateStatusForEvent("suite", "corp", REVOKED,
            "sha256:event", Instant.parse("2026-08-13T01:00:00Z"));
    assertTrue(first.applied());
    assertFalse(replay.applied());
    assertEquals(first.installation().version(), replay.installation().version());
    assertEquals("sha256:event", replay.installation().lastAuthorizationEventId());
}
```

- [ ] **Step 3: 运行 RED 测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComAuthorizationAuditTrailTest,WeComAuthorizationStoreTest test
```

Expected: FAIL，缺少索引、阶段 API 和 Store event 字段。

- [ ] **Step 4: 实现索引和 required 阶段事件**

eventId 编码必须用长度前缀，避免字段拼接碰撞：

```java
private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
}
```

对敏感 callback 字段先 SHA-256，再把摘要作为 eventId 输入字段；最终只输出 `sha256:<64 lowercase hex>`。`result` 允许 `accepted|pending|succeeded|failed`。`pending` 允许 `targetStatus` 和 `expectedVersion`，禁止凭据或任意 Map。

启动索引通过 `AuditFileCatalog` 读取当前与合法归档，单个 gzip 和总扫描均受 `streamMaxBytes` 约束。旧白名单行没有 eventId/attempt 时标记 legacy；新的非 legacy 行缺少任何阶段字段则 `AUDIT_AUTHORIZATION_ENTRY_INVALID`。`begin` 的索引检查、attempt 分配、accepted required 追加和内存索引更新必须持有同一授权流锁；append 失败不更新索引。

- [ ] **Step 5: 实现 Store 单向 schema 演进和幂等 mutation**

record 追加 nullable 字段：

```java
public record Installation(String installationId, String suiteId, String authCorpId,
        String agentId, String permanentCodeEncrypted, AuthStatus authStatus,
        Instant authorizedAt, Instant updatedAt, Instant lastSuiteTicketAt, long version,
        String lastAuthorizationEventId, Instant lastAuthorizationEventAt) {}

public record MutationResult(Installation installation, boolean applied) {}
```

在 record body 保留现有 10 参数构造器，并委托 canonical constructor 的最后两项为 null：

```java
public Installation(String installationId, String suiteId, String authCorpId, String agentId,
                    String permanentCodeEncrypted, AuthStatus authStatus, Instant authorizedAt,
                    Instant updatedAt, Instant lastSuiteTicketAt, long version) {
    this(installationId, suiteId, authCorpId, agentId, permanentCodeEncrypted, authStatus,
            authorizedAt, updatedAt, lastSuiteTicketAt, version, null, null);
}
```

这使现有无关测试 fixture 明确表示 legacy 安装，避免批量修改 `WeComAccessTokenServiceTest`、chatdata 和 daily-summary 测试。legacy JSON 缺字段时 Gson 得到 null，validator 接受二者同时为空；只允许二者同时有值或同时为空。同 eventId 返回现有 record 且 `applied=false`；较旧 event timestamp 抛 `WECOM_AUTHORIZATION_EVENT_STALE`；新 event 与状态在同一次 `writeSnapshot` 原子替换。

- [ ] **Step 6: 运行 GREEN 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationAuditTrailTest,WeComAuthorizationStoreTest test`

Expected: PASS，legacy 可读、并发 attempt 唯一、同 event 不重复递增版本。

- [ ] **Step 7: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuthorizationAuditIndex.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrail.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationStore.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationAuditTrailTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationStoreTest.java
git commit -m "feat: index required authorization audits"
```

---

### Task 6: 实现授权预写、故障闸门和启动对账

**Files:**

- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditRuntime.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java`

**Interfaces:**

- Consumes Task 5 `WeComAuthorizationAuditTrail` and Store mutation APIs.
- Extends Task 4 `AuditRuntime` with `authorizationTrail()` and authorization writer lifetime.
- Produces: `recoverOpenAttemptsAtStartup()` called before worker start and retention cleanup；`recoverAfterAuditFailure()` 仅在 fail-closed gate 已打开时调用。
- Produces: `AuthorizationEvent(callback, auditAttempt)`; removes in-memory `processed` as success truth owner.

- [ ] **Step 1: 写 required 故障时序 RED 测试**

不得给 AuditTrail 增加阶段切换式测试开关、测试 flag 或其他生产测试状态。在 `WeComAuthorizationAuditTrail` 内定义 package-private 函数式存储边界 `RequiredLineAppender.append(String line)`；生产构造器绑定为 `line -> boundedAuditFile.append(line, REQUIRED)`，测试构造器注入确定性 fake appender，由 fake 按第 N 次 append 或解析后的 `result` 阶段抛 `AuditStorageException`。阶段选择和计数只存在于测试 fake，生产接口只表达 required line append。至少新增：

```java
@Test
void neverMutatesStoreWhenAcceptedOrPendingCannotPersist() throws Exception {
    appender.failResult("accepted");
    assertFalse(service.handle(create).success());
    assertTrue(store.find("suite", "corp").isEmpty());

    appender.failResult("pending");
    assertTrue(service.handle(create).success()); // accepted and queued
    waitUntil(() -> service.pendingCount() == 0);
    assertTrue(store.find("suite", "corp").isEmpty());
}

@Test
void closesFinalWriteFailureFromStoreMarkerOnRestart() throws Exception {
    appender.failResult("succeeded");
    assertTrue(service.handle(create).success());
    waitUntil(() -> store.find("suite", "corp").isPresent());
    service.close();
    assertTrue(audit.index().hasStaleOpenAttempt());

    try (WeComAuthorizationService recovered = serviceWithSameFiles()) {
        assertFalse(audit.index().hasOpenAttempt());
        assertTrue(audit.readAll().contains("\"result\":\"succeeded\""));
    }
}
```

另测：只有 accepted 的重启补 `WECOM_AUTHORIZATION_PROCESS_INTERRUPTED`；suite_ticket pending 重启补 failed；pending/Store 标记冲突保持闸门；不同 event 在闸门时返回 retry；同 event replay 不增加 Store version；队列满写 failed；取消不会被更旧 change 覆盖；一次性 auth code 兑换后崩溃只报告失败、不持久化 code。

- [ ] **Step 2: 运行 RED 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComAuthorizationServiceTest test`

Expected: FAIL，现有服务仍先改 Store、吞 audit failure 或依赖内存 `processed`。

- [ ] **Step 3: 改造 callback 入口和队列事件**

先扩展 `AuditRuntime.open(Config)`：在既有 viewer handle 后 acquire authorization writer（cleanup disabled）、构造 `WeComAuthorizationAuditTrail`，并保持逆序关闭。`App.startWeb` 改用完整 `open`，把 `authorizationTrail()` 注入 service；关闭顺序固定为 authorization worker -> audit runtime。所有 startup failure 分支覆盖两者，Task 4 的 `openViewer` 仅保留 package-private 测试入口，不再由生产 `App` 调用。

固定顺序：

```java
if (auditGate.failed() && !recoverAfterAuditFailure()) return CallbackAck.retry();
BeginResult begin = auditTrail.begin(callback);
if (begin.disposition() == ALREADY_SUCCEEDED
        || begin.disposition() == OPEN_ALREADY_ACCEPTED) return CallbackAck.accepted();
AuthorizationAuditAttempt attempt = begin.attempt();
if (isQueued(callback)) {
    if (!queue.offer(new AuthorizationEvent(callback, attempt))) {
        auditTrail.failed(attempt, callback.authCorpId(), queueFullFailure());
        return CallbackAck.retry();
    }
    return CallbackAck.accepted();
}
return processSynchronously(callback, attempt);
```

accepted 写失败直接 retry。`OPEN_ALREADY_ACCEPTED` 不重复入队；`ALREADY_SUCCEEDED` 幂等确认。worker 在拿到所有上游结果、即将改 Store 时才写 pending；pending 写失败不得把安装标为 FAILED，因为那本身也是 Store mutation。

- [ ] **Step 4: 实现 Store mutation 与 final 阶段**

`create_auth`、`reset_permanent_code`、`change_auth` 调用 `upsertActiveForEvent`；`cancel_auth` 调用 `updateStatusForEvent`。业务上游失败在没有 Store mutation 时只写 failed，不再把已有安装状态强行改为 FAILED；只有业务合同明确要求的失败状态才能通过 pending + event mutation 写入。

final 写失败时设置进程内 fail-closed gate 并输出 `wecom-authorization/failed` 告警；不得捕获后返回正常 worker success。对已快速 ACK 的回调只保留本地开放尝试，不声称原 HTTP 已 retry。

- [ ] **Step 5: 实现启动和回调前有界对账**

`recoverOpenAttemptsAtStartup()` 在 worker 尚未启动时遍历全部开放尝试；`recoverAfterAuditFailure()` 只处理 gate 记录的 failed-final attempt，不扫描或关闭当前 worker 的其他活跃 attempt。恢复逻辑：

```java
if (open.phase() == ACCEPTED) {
    auditTrail.failed(open.attempt(), open.authCorpId(), interruptedFailure());
} else if (open.action().equals("wecom.authorization.suite_ticket")) {
    auditTrail.failed(open.attempt(), open.authCorpId(), interruptedFailure());
} else {
    Installation current = store.find(open.suiteId(), open.authCorpId()).orElse(null);
    if (current != null && open.eventId().equals(current.lastAuthorizationEventId())) {
        auditTrail.succeeded(open.attempt(), open.authCorpId());
    } else {
        gate.fail("WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED");
    }
}
```

启动对账完成后才调用授权 writer 的 `enableRetentionCleanup()` 并启动 worker。运行中没有 audit failure 时，普通 callback 不触发全量对账；人工不可证明冲突保持 gate，不自动回滚或猜测。

- [ ] **Step 6: 运行授权全链测试**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComAuthorizationServiceTest,WeComAuthorizationStoreTest,WeComAuthorizationAuditTrailTest,WeComAuthorizationCallbackRouteTest test
```

Expected: PASS；所有 Store mutation 前都有 pending，最终缺失可对账，原回调快速 ACK 语义被准确保留。

- [ ] **Step 7: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditRuntime.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java
git commit -m "feat: reconcile required authorization audits"
```

---

### Task 7: 增加只读状态报告与 CLI

**Files:**

- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStatusReporter.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditStatusReporterTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AppTest.java`
- Modify: `demo/message-center-demo/message-center-demo.ps1`

**Interfaces:**

- Produces `AuditStatusReporter.Report(status, checkedAt, streams, issues)` and `exitCode()`。
- Produces `App.run(String[], Path, PrintStream, PrintStream) -> int` 负责从显式 `.env` 路径加载配置和映射命令错误；另有 package-private `run(String[], Config, PrintStream, PrintStream)` test seam。`main` 传入 `Path.of(".env")`，只在 `audit-status` 返回 2/3 时调用 `System.exit`。

- [ ] **Step 1: 写 reporter 稳定快照、分级和脱敏 RED 测试**

测试 healthy、stale open attempt、损坏归档、坏 current、预算耗尽、低磁盘、并发 current 变化三次、issue 去重排序。关键断言：

```java
@Test
void reportsStableSanitizedJsonWithoutMutatingFiles() throws Exception {
    FileFingerprint before = fingerprintTree(dataDir);
    AuditStatusReporter.Report report = reporter.report();
    String json = GSON.toJson(report);
    assertEquals("degraded", report.status());
    assertEquals(2, report.exitCode());
    assertFalse(json.contains("eventId"));
    assertFalse(json.contains("corp-id"));
    assertFalse(json.contains("session"));
    assertEquals(before, fingerprintTree(dataDir));
}
```

当前文件扫描前后比较 size + mtime，变化则重试，第三次仍变化输出唯一 `AUDIT_SCAN_BUSY` degraded issue。开放 attempt 60 秒内只增加 `openAttemptCount`；超过 60 秒增加 `staleOpenAttemptCount` 并 degraded。

- [ ] **Step 2: 写 CLI stdout 和退出码 RED 测试**

`AppTest` 不直接调用 `System.exit`，测试带显式 Config 的 `App.run`：

```java
@Test
void auditStatusPrintsOneJsonDocumentAndReturnsContractExitCode() throws Exception {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exit = App.run(new String[]{"audit-status"}, config, new PrintStream(stdout), System.err);
    JsonObject json = JsonParser.parseString(stdout.toString(UTF_8).trim()).getAsJsonObject();
    assertEquals(0, exit);
    assertEquals("healthy", json.get("status").getAsString());
    assertEquals(2, json.getAsJsonArray("streams").size());
}
```

再测额外参数返回 3。配置失败测试调用负责 `Config.load` 的四参数 `App.run(args, tempDir.resolve(".env"), stdout, stderr)`，在该临时 env 写旧键，断言仍只输出一个失败 JSON、stdout 无堆栈。

- [ ] **Step 3: 运行 RED 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=AuditStatusReporterTest,AppTest test`

Expected: FAIL，缺少 reporter、CLI 分支和 `App.run` seam。

- [ ] **Step 4: 实现只读 reporter**

固定数据模型字段与设计一致；`Issue` 只包含 severity、stream、code。排序：severity `failed` 先于 `degraded`，再按 stream/code。整体退出码取最严重：0/2/3。

Reporter 只能调用 Task 3 的 `AuditFileCatalog.scanStable`；不得 acquire writer、恢复、轮转、force、创建目录或删除文件。文件不存在视为 0-byte healthy stream，目录不存在但其最近存在父目录可写时仍 healthy；无法确定可写性或低磁盘时 failed。

- [ ] **Step 5: 实现 App 的只读 CLI 生命周期**

Task 6 已完成 `AuditRuntime.open` 与 web 进程关闭顺序；本任务不得再次改写或复制该装配。`audit-status` 直接构造只读 reporter，不打开 `AuditRuntime`、不 acquire writer，也不触发授权启动对账。

`App.run(String[], Path, PrintStream, PrintStream)` 必须先识别原始 command 是否为 `audit-status`，再调用 `Config.load(envPath)`。若加载失败且 command 是 `audit-status`，stdout 输出一个 `status=failed`、issue code `AUDIT_CONFIGURATION_INVALID` 的 JSON 并返回 3；其他命令保持现有异常语义。加载成功后委托 Config overload。

`main` 固定为：

```java
public static void main(String[] args) throws Exception {
    int exitCode = run(args, Path.of(".env"), System.out, System.err);
    String command = args.length == 0 ? "web" : args[0];
    if ("audit-status".equalsIgnoreCase(command) && exitCode != 0) {
        System.exit(exitCode);
    }
}
```

CLI 分支：

```java
if ("audit-status".equalsIgnoreCase(command)) {
    if (args.length != 1) return printAuditFailure(stdout, "AUDIT_STATUS_ARGUMENT_INVALID");
    AuditStatusReporter.Report report = AuditStatusReporter.from(config, Clock.systemUTC(),
            AuditDiskSpaceProbe.system()).report();
    stdout.println(GSON.toJson(report));
    return report.exitCode();
}
```

PowerShell `ValidateSet` 增加 `audit-status`。Java usage 同步增加该命令。

- [ ] **Step 6: 运行 GREEN 测试和 CLI 实测**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=AuditStatusReporterTest,AppTest test
mvn -q -DskipTests compile
mvn -q exec:java "-Dexec.args=audit-status"
```

Expected: tests/compile PASS；CLI stdout 是一个 JSON object，健康空目录返回 exit 0。若当前本地 `.env` 有旧键，CLI 应返回 exit 3 的配置失败 JSON；用临时无旧键 `.env` 再验证 exit 0，不得修改用户 `.env`。

- [ ] **Step 7: 精确提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditStatusReporter.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditStatusReporterTest.java
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AppTest.java
git add demo/message-center-demo/message-center-demo.ps1
git commit -m "feat: expose audit status command"
```

---

### Task 8: 更新配置文档并建立敏感字段总门禁

**Files:**

- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditSensitiveFieldRegressionTest.java`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-08-13-wecom-audit-log-management-design.md` only to mark implemented evidence, without changing approved behavior.

**Interfaces:**

- Consumes all prior tasks.
- Produces operator-facing config, status, current/archive inspection and failure guidance.

- [ ] **Step 1: 写敏感字段 RED 回归测试**

测试真实构造 viewer diagnostic、authorization accepted/pending/failed、warning 和 status JSON，再扫描 current + gzip 解压 + captured stderr/stdout：

```java
private static final List<String> FORBIDDEN = List.of(
        "access-token-value", "suite-secret-value", "corp-secret-value",
        "permanent-code-value", "suite-ticket-value", "auth-code-value",
        "secret-key-value", "viewer-auth-token-value", "request-body-value",
        "response-body-value", "message-body-value", "modal-url-value");

@Test
void noAuditSurfaceContainsCredentialOrMessageFixtures() throws Exception {
    String allSurfaces = produceAndCollectAllAuditSurfaces();
    FORBIDDEN.forEach(value -> assertFalse(allSurfaces.contains(value), value));
}
```

Run: `cd demo/message-center-demo && mvn -q -Dtest=AuditSensitiveFieldRegressionTest test`

Expected: 首次若 test helper 尚未接到所有表面则 FAIL；不得删减 fixture 来让测试通过。

- [ ] **Step 2: 更新 env 示例并删除旧键**

在两处企业微信审计路径旁只保留路径；增加一个带中文注释的统一块：

```env
# 企业微信本地审计：当前文件最大 1 MiB，历史 gzip 保留最近 7 个 UTC 自然日
AUDIT_RETENTION_DAYS=7
AUDIT_FILE_MAX_BYTES=1048576
# 单条审计流的当前、归档、恢复和未知文件总预算 8 MiB
AUDIT_STREAM_MAX_BYTES=8388608
# 可用磁盘低于 64 MiB 时阻断授权审计写入，viewer 只降级并限频告警
AUDIT_MIN_FREE_DISK_BYTES=67108864
AUDIT_WARNING_INTERVAL_SECONDS=3600
```

确认整个 `config.example.env` 不再出现两个旧 max-bytes 键。

- [ ] **Step 3: 更新 README 的运行和运维合同**

改写现有“文件满后 viewer 失败关闭”的过期说明，写明：

- 当前 `.jsonl`、历史 `.jsonl.gz` 命名和 7 日边界。
- viewer best-effort、authorization required + 对账。
- `audit-status` Java 和 PowerShell 命令、0/2/3 退出码。
- `tail -f`、`jq`、`gzip -cd` 示例。
- 不手动截断 current、不删除保留期内归档；损坏文件先受控备份后人工移走。
- 两个旧 env 键会导致启动失败，部署前必须删除并添加统一配置。
- 一次性 auth code 在兑换后、pending 前崩溃时可能需要企业微信重新投递或重新授权。

- [ ] **Step 4: 运行文档和安全门禁**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=AuditSensitiveFieldRegressionTest test
! rg -n 'WECOM_(VIEWER|AUTHORIZATION)_AUDIT_MAX_BYTES' README.md config.example.env src/main src/test
rg -n 'AUDIT_RETENTION_DAYS|AUDIT_FILE_MAX_BYTES|AUDIT_STREAM_MAX_BYTES|AUDIT_MIN_FREE_DISK_BYTES|AUDIT_WARNING_INTERVAL_SECONDS|audit-status' README.md config.example.env
```

Expected: 敏感字段测试 PASS；旧键搜索无输出；五个新键与 CLI 均在用户文档中出现。

- [ ] **Step 5: 回写规格实现状态并精确提交**

规格顶部状态改为“已实施，待/已完成下方验收”，末尾追加实际 commit 和命令结果；不得修改已批准边界。

```bash
git add demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuditSensitiveFieldRegressionTest.java
git add demo/message-center-demo/config.example.env
git add demo/message-center-demo/README.md
git add docs/superpowers/specs/2026-08-13-wecom-audit-log-management-design.md
git commit -m "docs: document wecom audit operations"
```

---

### Task 9: 全量验收、打包与 Git 边界复核

**Files:**

- No production changes unless a verification failure proves a task-owned defect.
- Modify the relevant task-owned test/production file only when fixing such a defect; rerun its RED/GREEN gate.

- [ ] **Step 1: 运行审计专项测试**

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,BoundedAuditFileTest,AuditWarningReporterTest,WeComViewerAuditTrailTest,WeComAuthorizationAuditTrailTest,WeComAuthorizationStoreTest,WeComAuthorizationServiceTest,AuditStatusReporterTest,AuditSensitiveFieldRegressionTest,AppTest test
```

Expected: PASS，0 failures / 0 errors。

- [ ] **Step 2: 运行完整单元测试**

```bash
cd demo/message-center-demo
mvn -q test
```

Expected: PASS，不能把 Docker/Testcontainers 不可用误写成代码通过；若集成测试依赖不可用，分别记录单元测试结果和未运行的外部依赖门禁。

- [ ] **Step 3: 运行 verify 与打包**

```bash
cd demo/message-center-demo
mvn -q verify
mvn -q -DskipTests package
```

Expected: PASS；生成 `target/message-center-demo-0.1.0.jar`，无编译 warning、新增占位标记或测试残留文件。

- [ ] **Step 4: 在隔离临时目录实测 CLI 和轮转**

使用 `mktemp -d` 创建独立目录，不读取或改写用户现有 `.env`。通过进程环境显式传入全部审计配置，再生成明确 classpath 并从空临时目录运行 CLI：

```bash
audit_repo="$(git rev-parse --show-toplevel)"
audit_tmp="$(mktemp -d)"
mvn -q -f "$audit_repo/demo/message-center-demo/pom.xml" \
  dependency:build-classpath -Dmdep.outputFile="$audit_tmp/classpath.txt"
audit_dependencies="$(< "$audit_tmp/classpath.txt")"
cd "$audit_tmp"
env -u WECOM_VIEWER_AUDIT_MAX_BYTES -u WECOM_AUTHORIZATION_AUDIT_MAX_BYTES \
  DATA_DIR="$audit_tmp/data" \
  WECOM_VIEWER_AUDIT_FILE="$audit_tmp/data/wecom-viewer-audit.jsonl" \
  WECOM_AUTHORIZATION_AUDIT_FILE="$audit_tmp/data/wecom-authorization-audit.jsonl" \
  AUDIT_RETENTION_DAYS=7 AUDIT_FILE_MAX_BYTES=4096 \
  AUDIT_STREAM_MAX_BYTES=32768 AUDIT_MIN_FREE_DISK_BYTES=4096 \
  AUDIT_WARNING_INTERVAL_SECONDS=3600 \
  java -cp "$audit_repo/demo/message-center-demo/target/classes:$audit_dependencies" \
  com.crmforlogistics.messagecenter.App audit-status
```

Expected: 初始 healthy/exit 0；CLI 前后文件 hash、mtime 和列表不变。归档计数、轮转和 gzip 内容由 `BoundedAuditFileTest` 的真实 writer fixture 验证，CLI 烟测不得为了造数据打开 writer。

- [ ] **Step 5: 验证旧配置启动失败**

使用新临时目录和 Step 4 已生成的 classpath，通过进程环境只加入旧键并运行同一 CLI：

```bash
legacy_tmp="$(mktemp -d)"
cd "$legacy_tmp"
env -u WECOM_AUTHORIZATION_AUDIT_MAX_BYTES \
  WECOM_VIEWER_AUDIT_MAX_BYTES=1048576 \
  DATA_DIR="$legacy_tmp/data" \
  java -cp "$audit_repo/demo/message-center-demo/target/classes:$audit_dependencies" \
  com.crmforlogistics.messagecenter.App audit-status
legacy_exit=$?
test "$legacy_exit" -eq 3
```

Expected: exit 3；stdout 一个 JSON object，issue code 为稳定配置错误，不输出 Java stack trace，不包含旧值以外的环境内容。

- [ ] **Step 6: 检查工作区与提交边界**

```bash
git diff --check -- demo/message-center-demo \
  docs/superpowers/specs/2026-08-13-wecom-audit-log-management-design.md \
  docs/superpowers/plans/2026-08-13-wecom-audit-log-management.md
git status --short
git log --oneline --max-count=10
find . -type d -name .git -not -path './.git' -print
```

Expected: 本任务文件无 whitespace error；无关 dirty 文件仍原样存在；嵌套仓 `target/phone-call-transcription-runtime` 未被 stage 或提交。

- [ ] **Step 7: 记录验收证据**

在规格和本计划末尾记录：专项测试数量、完整 test/verify/package 结果、CLI 0/2/3 实测、跨进程锁测试、敏感字段扫描、生成 JAR 路径、未闭合外部依赖。若任何关键门禁未运行，明确写原因和影响，不使用“完成”措辞。

只有验证修复产生新 diff 时，返回对应 Task，复跑该 Task 的 RED/GREEN 命令，并使用该 Task 已列出的精确 `git add` 路径提交；不得在 Task 9 使用通配或临时决定扩大文件范围。提交消息使用 `fix: close wecom audit verification gaps`。

## Completion Gate

- 两条审计流按 UTC 日期或 1 MiB 上限轮转，gzip 可验证，窗口内审计不因容量压力删除。
- 单流 8 MiB、磁盘 64 MiB、gzip 峰值/解压和单事件大小都有可重复测试。
- 同 JVM 多 service 共享 handle，第二 writer JVM 失败，CLI 可并发只读。
- viewer 审计故障只限频告警并恢复，不改变业务结果。
- 每个授权 Store mutation 前都有 accepted/pending，Store event marker 与业务状态原子替换，最终审计缺失可对账或明确失败关闭。
- `audit-status` stdout/退出码、稳定快照、60 秒宽限和脱敏合同通过。
- 旧配置启动失败，新配置、README 和 PowerShell 命令一致。
- Maven 专项、完整 test、verify、package 和敏感字段门禁均有当轮证据。
- 只提交本任务文件，现有 Spring/ChatApp/WhatsApp/FunASR 等无关 dirty 修改未被纳入。

## Task 9 验收记录（2026-08-14）

- 审计专项：10 个测试类，`128` tests，`0` failures、`0` errors。
- 完整测试：默认沙箱因禁止本地 HTTP bind 出现 `52` 个环境错误；在允许本地端口的同一工作树重跑 `mvn -q test`，`435/435` 通过。
- Verify：首次暴露授权异步注册断言竞态；单用例修复前稳定失败，修复后连续 `20` 次通过，授权 service 测试 `16/16` 通过。最终 `mvn -q verify` 通过，Surefire `435/435`，Failsafe `24/24`。
- Package：`mvn -q -DskipTests package` 通过，生成 `target/message-center-demo-0.1.0.jar`（约 `898 KiB`），核心审计 class 已验证进入 JAR。
- CLI：隔离目录实测 `healthy=0`、`degraded=2`、`failed=3`；旧配置稳定返回 `AUDIT_CONFIGURATION_INVALID`，stale open attempt 返回 `AUDIT_OPEN_ATTEMPT_STALE`，stderr 为空且只读场景未修改审计文件。
- 安全与存储：敏感字段总门禁 `1/1` 通过；跨进程锁、gzip、保留期、预算、磁盘阈值和恢复由 `BoundedAuditFileTest` `32/32` 覆盖。
- Git：任务范围 whitespace 检查通过；嵌套仓 `target/phone-call-transcription-runtime` 未进入暂存或提交；用户原有 Spring/ChatApp/WhatsApp/FunASR 等脏改动保持原样。
- 外部依赖：Docker/Testcontainers 的 `24` 项集成门禁实际运行通过，无未闭合外部依赖验收。
