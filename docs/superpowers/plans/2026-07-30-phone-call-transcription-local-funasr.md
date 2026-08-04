# 本地电话录音 FunASR 转录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `demo/message-center-demo` 当前可运行聊天框内上传一个本地 MP3，创建与联系人绑定的电话卡片，经内网 FunASR 异步转录，并在右侧详情安全播放录音、查看分段和维护可审计修订稿。

**Architecture:** 电话记录由独立 `callrecord` 核心拥有，本期使用原子本地文件 repository 与本地音频目录，不连接 PostgreSQL 或 MinIO。`App.java` 只装配核心服务并映射 `/api/v1`；联系人时间线服务把现有消息与电话卡片稳定混排；FunASR、multipart 和短时播放 Cookie 都是薄 adapter。

**Tech Stack:** Java 17、JDK `HttpServer`、Gson 2.11、Apache Commons FileUpload 1.6.0 流式 API、mp3agic 0.9.1、`java.net.http.HttpClient`、JUnit 5、Node OpenAPI 合同测试、Docker Compose、FunASR OpenAI-compatible HTTP API。

## Global Constraints

- 只接受一个 MP3；单文件最大 `104857600` 字节，解码时长最大 `7200` 秒。
- 本地 `data/call-records` 是本期唯一电话真源；禁止 PostgreSQL/MinIO 双写或 DashScope 回退。
- 电话记录绑定规范化渠道身份锚点；有电话号码时必须选号码，没有时使用当前联系人 primary point。
- 电话 identity 的规范形式固定为 `phone:` 加纯数字串；联系人 projection 必须输出 `channel=phone,type=phone`，不得落入 `unknown`。
- 电话不是渠道消息；时间线 item 必须以 `type=message|callRecord` 显式判别。
- 默认 worker 并发 1、队列 64、三次尝试、连接超时 3 秒、单次转录超时 1800 秒。
- FunASR 固定 `sensevoice + verbose_json`；不做说话人分离，不伪造空转录。
- 原始转录只读；修订稿最多 20 版，保存服务端 actor、时间与 expected version。
- 除音频 GET 外，电话接口必须使用 `X-WeCom-Viewer-Auth`；actor 只能来自服务端 token。
- 音频 GET 只接受 5 分钟短时、HttpOnly、SameSite=Strict、路径限定的播放 Cookie，并支持单区间 `Range`。
- 本地总容量默认 10 GiB，记录扫描最多 10000；任何文件、响应、分段、会话与队列都必须有上界。
- `App.java` 只能做装配、参数映射、响应映射和 UI 接线；禁止把状态机或路径规则写回该文件。
- 电话 HTTP adapter 只按 `HttpExchange.getRequestURI().getRawPath()` 匹配；动态段严格 UTF-8 解码恰好一次，编码斜杠、反斜杠、NUL、无效 `%` 与二次解码全部禁止。
- 不修改现有 `phone_notes`、数据库 migration 或消息渠道状态机。
- 每个任务都先写失败测试、确认失败原因、做最小实现、运行针对性门禁，再只暂存该任务文件。
- 设计真源：`docs/superpowers/specs/2026-07-30-phone-call-transcription-local-funasr-design.md`。

## 文件与 Owner Map

| 路径 | 职责 |
|---|---|
| `callrecord/CallRecord.java` | 电话记录、音频、转录、分段、修订和错误值对象 |
| `callrecord/CallRecordRepository.java` | 本地/未来数据库共同遵守的持久化 port |
| `callrecord/FileCallRecordRepository.java` | JSON 快照、索引、锁、原子写、恢复与对账 |
| `callrecord/LocalAudioStore.java` | MP3 流式落盘、签名/时长/容量校验和安全读取 |
| `callrecord/CallRecordService.java` | 联系人绑定、创建幂等、状态机、重试和修订 |
| `callrecord/FunAsrClient.java` | OpenAI-compatible multipart adapter |
| `callrecord/TranscriptionWorker.java` | 有界领取、租约、重试、超时与结果提交 |
| `callrecord/CallRecordRuntime.java` | repository 锁、worker 生命周期和降级装配 |
| `callrecord/ContactTimelineService.java` | 消息与电话卡片混排、cursor、分页和 revision |
| `callrecord/CallAudioSessionService.java` | 短时播放会话、Cookie token、TTL 与淘汰 |
| `callrecord/CallRecordHttpAdapter.java` | 路由匹配、流式 multipart、JSON、Range 响应 |
| `ContactPointUtil.java` | 所有渠道 identity 的规范化与 `ContactPoint` projection，包括 `phone:` |
| `Config.java` | 所有电话上界与 FunASR URL 配置真源 |
| `WeComViewerService.java` | 复用 viewer token，新增只读 actor 解析 port |
| `UnifiedMessageStore.java` | 只暴露既有消息页给时间线服务，不拥有电话记录 |
| `App.java` | 装配 runtime、挂载 adapter、电话卡片/右侧详情 UI |

---

### Task 1: 锁定依赖、配置与结构化错误

**Files:**
- Modify: `demo/message-center-demo/pom.xml`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordException.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordExceptionTest.java`

**Interfaces:**
- Produces: `Config.callRecordDataDir()`、`callRecordMaxAudioBytes()`、`callRecordMaxDurationSeconds()`、`callRecordStorageMaxBytes()`、`callRecordMaxRecords()`、`callRecordQueueCapacity()`、`callRecordWorkerConcurrency()`、`callRecordLeaseSeconds()`、`callRecordMaxAttempts()`、`callRecordMaxResponseBytes()`、`callRecordMaxSegments()`、`callRecordMaxRevisions()`、`callAudioSessionTtlSeconds()`、`callAudioSessionMaxPerActor()`、`callAudioSessionMaxActive()`、`callAudioCookieSecure()`、`funAsrBaseUri()`、`funAsrModel()`、`funAsrConnectTimeout()`、`funAsrRequestTimeout()`。
- Produces: `CallRecordException(String code, int httpStatus, String message, boolean retryable)` 与只读 accessors。
- Consumed by: Tasks 2-10。

- [ ] **Step 1: 写配置和错误类型失败测试**

在 `ConfigTest` 新增：

```java
@Test
void exposesBoundedLocalCallRecordAndFunAsrSettings() {
    Config config = new Config(Map.ofEntries(
            Map.entry("DATA_DIR", tempDir.toString()),
            Map.entry("CALL_RECORD_MAX_AUDIO_BYTES", "104857600"),
            Map.entry("CALL_RECORD_MAX_DURATION_SECONDS", "7200"),
            Map.entry("CALL_RECORD_STORAGE_MAX_BYTES", "10737418240"),
            Map.entry("CALL_RECORD_MAX_RECORDS", "10000"),
            Map.entry("CALL_RECORD_QUEUE_CAPACITY", "64"),
            Map.entry("CALL_RECORD_WORKER_CONCURRENCY", "1"),
            Map.entry("CALL_RECORD_LEASE_SECONDS", "2100"),
            Map.entry("CALL_RECORD_MAX_ATTEMPTS", "3"),
            Map.entry("CALL_RECORD_MAX_RESPONSE_BYTES", "10485760"),
            Map.entry("CALL_RECORD_MAX_SEGMENTS", "20000"),
            Map.entry("CALL_RECORD_MAX_REVISIONS", "20"),
            Map.entry("CALL_AUDIO_SESSION_TTL_SECONDS", "300"),
            Map.entry("CALL_AUDIO_SESSION_MAX_PER_ACTOR", "8"),
            Map.entry("CALL_AUDIO_SESSION_MAX_ACTIVE", "256"),
            Map.entry("FUNASR_BASE_URL", "http://funasr:8000"),
            Map.entry("FUNASR_MODEL", "sensevoice"),
            Map.entry("FUNASR_CONNECT_TIMEOUT_SECONDS", "3"),
            Map.entry("FUNASR_REQUEST_TIMEOUT_SECONDS", "1800")
    ));

    assertEquals(tempDir.resolve("call-records"), config.callRecordDataDir());
    assertEquals(104857600L, config.callRecordMaxAudioBytes());
    assertEquals(7200, config.callRecordMaxDurationSeconds());
    assertEquals(10737418240L, config.callRecordStorageMaxBytes());
    assertEquals(64, config.callRecordQueueCapacity());
    assertEquals(1, config.callRecordWorkerConcurrency());
    assertEquals(3, config.callRecordMaxAttempts());
    assertFalse(config.callAudioCookieSecure());
    assertEquals(java.net.URI.create("http://funasr:8000"), config.funAsrBaseUri());
    assertEquals("sensevoice", config.funAsrModel());
    assertEquals(java.time.Duration.ofSeconds(3), config.funAsrConnectTimeout());
    assertEquals(java.time.Duration.ofSeconds(1800), config.funAsrRequestTimeout());
}

@Test
void rejectsUnsafeCallRecordAndFunAsrSettings() {
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("CALL_RECORD_WORKER_CONCURRENCY", "0"))
                    .callRecordWorkerConcurrency());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("FUNASR_BASE_URL", "http://user:pass@funasr:8000"))
                    .funAsrBaseUri());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("FUNASR_MODEL", "unknown"))
                    .funAsrModel());
    assertThrows(IllegalArgumentException.class,
            () -> new Config(Map.of("FUNASR_BASE_URL", "https://api.example.com"))
                    .funAsrBaseUri());
}
```

创建 `CallRecordExceptionTest`：

```java
@Test
void carriesStableHttpAndRetrySemantics() {
    CallRecordException error = new CallRecordException(
            "FUNASR_TIMEOUT", 504, "FunASR timed out", true);
    assertEquals("FUNASR_TIMEOUT", error.code());
    assertEquals(504, error.httpStatus());
    assertTrue(error.retryable());
}
```

- [ ] **Step 2: 运行测试确认缺少配置与错误类型**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,CallRecordExceptionTest test
```

Expected: FAIL，编译错误包含 `callRecordDataDir` 或 `CallRecordException`。

- [ ] **Step 3: 添加明确依赖，不引入 Web 框架**

在 `pom.xml` 增加：

```xml
<dependency>
  <groupId>commons-fileupload</groupId>
  <artifactId>commons-fileupload</artifactId>
  <version>1.6.0</version>
</dependency>
<dependency>
  <groupId>com.mpatric</groupId>
  <artifactId>mp3agic</artifactId>
  <version>0.9.1</version>
</dependency>
```

`commons-fileupload` 只用于 `FileUpload.getItemIterator(RequestContext)` 流式读取，禁止调用 `parseRequest()` 生成完整内存 item 列表。使用 1.6.0 的 part-header 上界能力，并在 Task 10 显式设置 `setPartHeaderSizeMax(8192)`。

- [ ] **Step 4: 实现严格配置 getters**

在 `Config` 复用现有 `boundedInt` / `boundedLong`，新增：

```java
public Path callRecordDataDir() {
    return Path.of(value("CALL_RECORD_DATA_DIR", dataDir().resolve("call-records").toString()));
}
public long callRecordMaxAudioBytes() {
    return boundedLong("CALL_RECORD_MAX_AUDIO_BYTES", 104_857_600L, 1_048_576L, 104_857_600L);
}
public int callRecordMaxDurationSeconds() {
    return boundedInt("CALL_RECORD_MAX_DURATION_SECONDS", 7_200, 1, 7_200);
}
public long callRecordStorageMaxBytes() {
    return boundedLong("CALL_RECORD_STORAGE_MAX_BYTES", 10_737_418_240L,
            104_857_600L, 1_099_511_627_776L);
}
public int callRecordMaxRecords() {
    return boundedInt("CALL_RECORD_MAX_RECORDS", 10_000, 1, 100_000);
}
public int callRecordQueueCapacity() {
    return boundedInt("CALL_RECORD_QUEUE_CAPACITY", 64, 1, 1_024);
}
public int callRecordWorkerConcurrency() {
    return boundedInt("CALL_RECORD_WORKER_CONCURRENCY", 1, 1, 4);
}
public int callRecordLeaseSeconds() {
    return boundedInt("CALL_RECORD_LEASE_SECONDS", 2_100, 60, 7_200);
}
public int callRecordMaxAttempts() {
    return boundedInt("CALL_RECORD_MAX_ATTEMPTS", 3, 1, 10);
}
public long callRecordMaxResponseBytes() {
    return boundedLong("CALL_RECORD_MAX_RESPONSE_BYTES", 10_485_760L, 1_024L, 10_485_760L);
}
public int callRecordMaxSegments() {
    return boundedInt("CALL_RECORD_MAX_SEGMENTS", 20_000, 1, 20_000);
}
public int callRecordMaxRevisions() {
    return boundedInt("CALL_RECORD_MAX_REVISIONS", 20, 1, 20);
}
public int callAudioSessionTtlSeconds() {
    return boundedInt("CALL_AUDIO_SESSION_TTL_SECONDS", 300, 60, 600);
}
public int callAudioSessionMaxPerActor() {
    return boundedInt("CALL_AUDIO_SESSION_MAX_PER_ACTOR", 8, 1, 32);
}
public int callAudioSessionMaxActive() {
    return boundedInt("CALL_AUDIO_SESSION_MAX_ACTIVE", 256, 8, 1_024);
}
public boolean callAudioCookieSecure() {
    return "https".equalsIgnoreCase(java.net.URI.create(wecomLoginRedirectUri()).getScheme());
}
public java.net.URI funAsrBaseUri() {
    java.net.URI uri = java.net.URI.create(value("FUNASR_BASE_URL", "http://funasr:8000"));
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (!(scheme.equals("http") || scheme.equals("https")) || host.isBlank()
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(path.isBlank() || path.equals("/")) || !isPrivateServiceHost(host)) {
        throw new IllegalArgumentException("FUNASR_BASE_URL must be an absolute HTTP(S) service URL");
    }
    return uri;
}
public String funAsrModel() {
    String model = value("FUNASR_MODEL", "sensevoice").trim();
    if (!model.equals("sensevoice")) {
        throw new IllegalArgumentException("FUNASR_MODEL must be sensevoice");
    }
    return model;
}
public java.time.Duration funAsrConnectTimeout() {
    return java.time.Duration.ofSeconds(boundedInt("FUNASR_CONNECT_TIMEOUT_SECONDS", 3, 1, 30));
}
public java.time.Duration funAsrRequestTimeout() {
    return java.time.Duration.ofSeconds(boundedInt("FUNASR_REQUEST_TIMEOUT_SECONDS", 1_800, 30, 3_600));
}
```

`isPrivateServiceHost` 不做 DNS 解析，避免启动时网络依赖；只允许 `localhost`、单标签 Compose DNS、以 `.internal`/`.local` 结尾的内网 DNS，以及 IPv4 loopback/RFC1918 字面量。明确拒绝公网域名、IPv6 global 地址和含 path 的 base URL。若部署必须使用其他内部 DNS 后缀，应先把允许规则作为显式配置合同评审，不能临时放开任意 host。

实现异常：

```java
package com.crmforlogistics.messagecenter.callrecord;

public final class CallRecordException extends Exception {
    private final String code;
    private final int httpStatus;
    private final boolean retryable;

    public CallRecordException(String code, int httpStatus, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public CallRecordException(String code, int httpStatus, String message,
                               boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public boolean retryable() { return retryable; }
}
```

- [ ] **Step 5: 运行针对性测试和依赖树检查**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,CallRecordExceptionTest test
mvn -q dependency:tree -Dincludes=commons-fileupload:commons-fileupload,com.mpatric:mp3agic
```

Expected: tests PASS；依赖树只出现 `commons-fileupload:1.6.0`、其 `commons-io` 传递依赖和 `mp3agic:0.9.1`，不出现 Spring/Servlet 容器。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/pom.xml \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordException.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordExceptionTest.java
git commit -m "feat: define bounded call transcription settings"
```

### Task 2: 定义电话记录领域合同与状态机

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecord.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordStateMachine.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordStateMachineTest.java`

**Interfaces:**
- Produces: immutable records `CallRecord`、`AudioAsset`、`Transcription`、`TranscriptionResult`、`TranscriptSegment`、`TranscriptRevision`、`CallRecordError`、`CallRecordLease`。
- Produces: `CallRecordRepository.find(UUID)`、`findByIdempotency(String,String)`、`listByAnchors(Set<String>)`、`countPending()`、`saveNew(CallRecord)`、`replace(CallRecord,long)`、`recoverProcessing(Instant)`。
- Produces: state transition methods used by Task 5 and Task 7。

- [ ] **Step 1: 写状态机失败测试**

```java
@Test
void leasesQueuedRecordAndRejectsStaleCompletion() throws Exception {
    CallRecord queued = Fixtures.queuedRecord();
    Instant now = Instant.parse("2026-07-30T10:00:00Z");
    CallRecord processing = CallRecordStateMachine.lease(
            queued, "worker-1", now, now.plusSeconds(2100));

    assertEquals("processing", processing.transcription().state());
    assertEquals(1, processing.transcription().attempts());
    assertEquals(queued.version() + 1, processing.version());
    assertThrows(CallRecordException.class, () -> CallRecordStateMachine.complete(
            processing, "wrong-lease", Fixtures.result(), now.plusSeconds(10)));
}

@Test
void retryableFailureReturnsToQueuedAndFinalFailureStops() throws Exception {
    CallRecord processing = Fixtures.processingRecord(1, "lease-1");
    CallRecord retry = CallRecordStateMachine.fail(processing, "lease-1",
            new CallRecordError("FUNASR_TIMEOUT", "FunASR timed out", true),
            Instant.parse("2026-07-30T10:00:00Z"), 3);
    assertEquals("queued", retry.transcription().state());
    assertNotNull(retry.transcription().nextAttemptAt());

    CallRecord finalAttempt = Fixtures.processingRecord(3, "lease-3");
    CallRecord failed = CallRecordStateMachine.fail(finalAttempt, "lease-3",
            new CallRecordError("FUNASR_TIMEOUT", "FunASR timed out", true),
            Instant.parse("2026-07-30T10:00:00Z"), 3);
    assertEquals("failed", failed.transcription().state());
}
```

- [ ] **Step 2: 运行测试确认领域类型不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CallRecordStateMachineTest test`

Expected: FAIL，编译错误包含 `CallRecordStateMachine`。

- [ ] **Step 3: 创建不可变领域 records**

`CallRecord.java` 定义：

```java
package com.crmforlogistics.messagecenter.callrecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CallRecord(
        UUID id, String contactAnchorPointId, String phonePointId,
        String direction, Instant occurredAt, Instant createdAt, String createdBy,
        String clientRequestId, AudioAsset audio, Transcription transcription,
        List<TranscriptRevision> revisions, UUID currentRevisionId, long version) {
    public CallRecord {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
    }
}

record AudioAsset(String relativePath, String originalFileName, long sizeBytes,
                  String sha256, String contentType, double durationSeconds) {}
record Transcription(String state, String model, int attempts,
                     CallRecordLease lease, Instant nextAttemptAt,
                     TranscriptionResult result, CallRecordError error) {}
record CallRecordLease(String id, String workerId, Instant expiresAt) {}
record TranscriptionResult(String model, double durationSeconds, String originalText,
                           List<TranscriptSegment> segments, Instant completedAt) {
    TranscriptionResult {
        segments = List.copyOf(segments);
    }
}
record TranscriptSegment(double startSeconds, double endSeconds, String text) {}
record TranscriptRevision(UUID id, String text, Instant editedAt, String editedBy) {}
record CallRecordError(String code, String message, boolean retryable) {}
```

同包测试可直接使用 package-private 值对象；HTTP adapter 在同包内转换为 JSON DTO，不公开可变字段。

- [ ] **Step 4: 定义 repository port 与显式 CAS**

```java
package com.crmforlogistics.messagecenter.callrecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CallRecordRepository extends AutoCloseable {
    Optional<CallRecord> find(UUID id) throws CallRecordException;
    Optional<CallRecord> findByIdempotency(String anchorPointId, String clientRequestId)
            throws CallRecordException;
    List<CallRecord> listByAnchors(Set<String> anchorPointIds) throws CallRecordException;
    int countPending() throws CallRecordException;
    void saveNew(CallRecord record) throws CallRecordException;
    CallRecord replace(CallRecord replacement, long expectedVersion) throws CallRecordException;
    List<CallRecord> recoverProcessing(Instant now) throws CallRecordException;
    List<CallRecord> listRunnable(Instant now, int limit) throws CallRecordException;
    @Override void close() throws CallRecordException;
}
```

`replace` 版本不匹配抛 `CALL_RECORD_VERSION_CONFLICT` / 409。`saveNew` 保持 `void`：它在 repository 写锁内同时检查 record id 与 `(anchorPointId,clientRequestId)` 唯一键；幂等键冲突时不写任何 record/index，抛 `CALL_RECORD_IDEMPOTENCY_CONFLICT` / 409。Task 5 捕获该 code 后调用 `findByIdempotency` 读取 winner；若 winner 不存在则按 `CALL_RECORD_STORE_CORRUPT` 失败关闭。这样接口签名、竞争返回语义与音频补偿顺序一致。

- [ ] **Step 5: 实现纯状态机**

`CallRecordStateMachine` 只接收 record 并返回新 record。必须提供：

```java
static CallRecord lease(CallRecord current, String workerId, Instant now, Instant expiresAt)
static CallRecord complete(CallRecord current, String leaseId,
                           TranscriptionResult result, Instant completedAt)
static CallRecord fail(CallRecord current, String leaseId, CallRecordError error,
                       Instant now, int maxAttempts)
static CallRecord manualRetry(CallRecord current, Instant now)
static CallRecord appendRevision(CallRecord current, String text, String actor,
                                 Instant editedAt, int maxRevisions)
static CallRecord recover(CallRecord current, Instant now)
```

所有入口校验 state、lease、空文本、segment 时间顺序和 revision 上限；错误使用稳定 code，不使用裸 `IllegalStateException`。

- [ ] **Step 6: 运行领域测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CallRecordStateMachineTest test`

Expected: PASS，覆盖 queued/processing/completed/failed、transient retry、manual retry、stale lease、revision 20 上限。

- [ ] **Step 7: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecord.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRepository.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordStateMachine.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordStateMachineTest.java
git commit -m "feat: define call record state machine"
```

### Task 3: 实现本地 MP3 存储与严格校验

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/LocalAudioStore.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/LocalAudioStoreTest.java`
- Test fixture: `demo/message-center-demo/src/test/resources/callrecord/one-second-tone.mp3`

**Interfaces:**
- Produces: `LocalAudioStore.stage(InputStream,String,String)` → `StagedAudio`。
- Produces: `publish(UUID,StagedAudio)` → `AudioAsset`、`open(AudioAsset)`、`path(AudioAsset)`、`discard(StagedAudio)`、`delete(AudioAsset)`。
- Consumed by: Task 5、Task 6、Task 9。

- [ ] **Step 1: 添加最小合法 MP3 fixture**

使用 ffmpeg 生成一次并提交二进制 fixture，不在测试运行时依赖 ffmpeg：

```bash
mkdir -p demo/message-center-demo/src/test/resources/callrecord
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=1000:duration=1' \
  -codec:a libmp3lame -b:a 64k \
  demo/message-center-demo/src/test/resources/callrecord/one-second-tone.mp3
```

验收：`file` 输出 MPEG Layer III，fixture 小于 32 KiB，时长约 1 秒。

- [ ] **Step 2: 写失败测试**

```java
@Test
void stagesValidMp3ComputesHashAndPublishesServerOwnedPath() throws Exception {
    Config config = config(tempDir, 104857600L, 10737418240L, 7200);
    LocalAudioStore store = new LocalAudioStore(config);
    try (InputStream input = fixture("one-second-tone.mp3")) {
        LocalAudioStore.StagedAudio staged = store.stage(input, "call.mp3", "audio/mpeg");
        AudioAsset asset = store.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), staged);
        assertEquals("audio/00000000-0000-0000-0000-000000000001.mp3", asset.relativePath());
        assertTrue(asset.durationSeconds() >= 0.9 && asset.durationSeconds() <= 1.1);
        assertEquals(64, asset.sha256().length());
    }
}

@Test
void rejectsNonMp3OversizeOverlongAndPathNames() throws Exception {
    LocalAudioStore store = new LocalAudioStore(config(tempDir, 8, 1024, 1));
    assertEquals("INVALID_MP3", assertThrows(CallRecordException.class,
            () -> store.stage(new ByteArrayInputStream("not mp3".getBytes()),
                    "../../escape.mp3", "audio/mpeg")).code());
}
```

另加容量竞争、临时清理、publish 失败回滚、`../` 原名不进入路径测试。

- [ ] **Step 3: 运行测试确认类不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=LocalAudioStoreTest test`

Expected: FAIL，编译错误包含 `LocalAudioStore`。

- [ ] **Step 4: 实现流式 stage 与 MP3 解析**

核心构造和 API：

```java
public final class LocalAudioStore {
    private final Path root;
    private final long maxAudioBytes;
    private final long storageMaxBytes;
    private final int maxDurationSeconds;
    private final Object publishLock = new Object();

    public LocalAudioStore(Config config) { /* resolve normalized root and bounds */ }

    public StagedAudio stage(InputStream source, String originalFileName, String contentType)
            throws CallRecordException { /* stream to root/tmp UUID file */ }
    public AudioAsset publish(UUID callRecordId, StagedAudio staged) throws CallRecordException { /* atomic move */ }
    public InputStream open(AudioAsset asset) throws CallRecordException { /* normalized child-only path */ }
    public Path path(AudioAsset asset) throws CallRecordException { /* child-only path */ }
    public void discard(StagedAudio staged) { /* delete exact staged path */ }
    public void delete(AudioAsset asset) throws CallRecordException { /* exact server path */ }

    public record StagedAudio(Path path, String originalFileName, long sizeBytes,
                              String sha256, double durationSeconds) {}
}
```

流式复制使用 64 KiB buffer，每次写入前检查累计字节；超过上限立即关闭并删除临时文件。写完后：

1. 检查前 10 字节为 `ID3` 或 MPEG sync `0xFFE`。
2. `new Mp3File(path.toString())` 读取 `getLengthInMilliseconds()`。
3. 时长 `<=0` 或 `> maxDurationSeconds * 1000L` 拒绝。
4. `MessageDigest SHA-256` 在同一次写入中计算。

`publish` 在 `publishLock` 内递归统计 `audio/*.mp3` 当前字节，加 staged 大小超过容量即抛 `LOCAL_STORAGE_FULL`；目标路径固定为 `audio/` 加 record UUID 再加 `.mp3`，使用 `ATOMIC_MOVE`，文件系统不支持时失败关闭，不降级为非原子覆盖。

- [ ] **Step 5: 运行针对性测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=LocalAudioStoreTest test`

Expected: PASS；测试确认大输入不会创建残留 temp，非法文件不会进入 `audio`。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/LocalAudioStore.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/LocalAudioStoreTest.java \
  demo/message-center-demo/src/test/resources/callrecord/one-second-tone.mp3
git commit -m "feat: validate and store local call audio"
```

### Task 4: 实现原子文件 Repository、索引和恢复

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepository.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepositoryTest.java`

**Interfaces:**
- Implements: Task 2 `CallRecordRepository`。
- Produces: `FileCallRecordRepository.open(Config, Clock)`，成功持有 `runtime.lock`；`close()` 释放锁。
- Consumed by: Task 5、Task 7、Task 10。

- [ ] **Step 1: 写原子存储与恢复失败测试**

```java
@Test
void savesReadsIndexesAndCompareAndSwapsRecords() throws Exception {
    try (FileCallRecordRepository repository = FileCallRecordRepository.open(config(tempDir), fixedClock())) {
        CallRecord record = Fixtures.queuedRecord("phone:8613800000000", "request-1");
        repository.saveNew(record);
        assertEquals(record.id(), repository.find(record.id()).orElseThrow().id());
        assertEquals(record.id(), repository.findByIdempotency(
                "phone:8613800000000", "request-1").orElseThrow().id());
        assertEquals(List.of(record.id()), repository.listByAnchors(
                Set.of("phone:8613800000000")).stream().map(CallRecord::id).toList());
        assertThrows(CallRecordException.class,
                () -> repository.replace(record, record.version() + 1));
    }
}

@Test
void refusesSecondWriterAndRecoversEveryProcessingRecordAfterRestart() throws Exception {
    FileCallRecordRepository first = FileCallRecordRepository.open(config(tempDir), fixedClock());
    assertEquals("CALL_RECORD_STORE_BUSY", assertThrows(CallRecordException.class,
            () -> FileCallRecordRepository.open(config(tempDir), fixedClock())).code());
    first.saveNew(Fixtures.processingRecord(1, "dead-lease"));
    first.close();

    try (FileCallRecordRepository restarted = FileCallRecordRepository.open(config(tempDir), fixedClock())) {
        List<CallRecord> recovered = restarted.recoverProcessing(fixedClock().instant());
        assertEquals("queued", recovered.get(0).transcription().state());
        assertNull(recovered.get(0).transcription().lease());
    }
}
```

增加同一 canonical anchor/clientRequestId 用不同 UUID 竞争时第二次 `saveNew` 抛 `CALL_RECORD_IDEMPOTENCY_CONFLICT` 且 winner 不变，以及损坏 JSON、超过 10000 记录、索引缺失重建、索引引用缺失记录、孤立音频对账、临时文件清理测试。

- [ ] **Step 2: 运行测试确认 repository 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=FileCallRecordRepositoryTest test`

Expected: FAIL，编译错误包含 `FileCallRecordRepository`。

- [ ] **Step 3: 实现目录锁、快照与索引**

关键实现约束：

```java
public static FileCallRecordRepository open(Config config, Clock clock)
        throws CallRecordException
```

- 创建 `records`、`indexes`、`tmp`、`audio`。
- `FileChannel.open(runtime.lock, CREATE, WRITE).tryLock()`；null 或 `OverlappingFileLockException` 映射 `CALL_RECORD_STORE_BUSY`。
- 启动扫描 `records/*.json`，只接受 UUID 文件名；超过 `callRecordMaxRecords` 失败关闭。
- Gson 使用固定领域类型；JSON 解析错误返回 `CALL_RECORD_STORE_CORRUPT`，不得跳过后伪装健康。
- 索引名为规范化 anchor 的 SHA-256，内容 `{ "anchor": "...", "recordIds": [...] }`；读取时校验 anchor 防哈希碰撞/损坏。
- 快照和索引统一 `writeTempAndMove(Path target, String json)`：在 target 同目录写 UUID temp，flush `FileChannel.force(true)`，再 `ATOMIC_MOVE + REPLACE_EXISTING`。
- 同一 JVM 内所有公开读写方法通过 repository 的 `ReentrantReadWriteLock`；`replace` 在写锁内比较 version。
- `saveNew` 先查 canonical `anchor + clientRequestId`；只要该唯一键已存在就不写新快照/索引，并抛 `CALL_RECORD_IDEMPOTENCY_CONFLICT`。winner 只能由调用方再通过 `findByIdempotency` 获取，repository 不返回隐式双态结果。
- `recoverProcessing` 把所有 processing 经 `CallRecordStateMachine.recover` 改回 queued，并逐条原子提交。

- [ ] **Step 4: 实现启动对账**

启动时执行：

1. 删除 `tmp` 下超过 1 小时的普通文件，拒绝符号链接。
2. 从 records 重建内存 `id -> record`、`idempotency -> id` 和 `anchor -> ids`。
3. 原子重写不一致或缺失的 anchor 索引。
4. `audio/*.mp3` 若没有任何 record 引用且 mtime 超过 1 小时则删除；1 小时内保留以避免与正在创建的请求竞争。
5. record 引用不存在的音频时失败关闭为 `CALL_RECORD_STORE_CORRUPT`。

- [ ] **Step 5: 运行 repository 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=FileCallRecordRepositoryTest test`

Expected: PASS；重复运行三次均无锁泄漏、残留 temp 或非确定顺序。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepository.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepositoryTest.java
git commit -m "feat: persist call records atomically"
```

### Task 5: 实现联系人绑定、创建幂等、重试与修订 Service

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ContactPointUtil.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `CallRecordRepository`、`LocalAudioStore`、`UnifiedMessageStore.contactGroup(String)`、公开只读的 `ContactPointUtil.normalizePointId(String)`。
- Produces: `create(CreateCallRecordCommand, InputStream)`、`detail(UUID,Set<String>)`、`retry(UUID,String,String)`、`revise(UUID,String,String,long)`、`list(Set<String>)`。
- Produces: `CreateCallRecordCommand(contactId,phonePointId,direction,occurredAt,clientRequestId,originalFileName,contentType,actor)`。
- Produces: `phone:` 加纯数字串的 canonical identity 与 `ContactPoint(id,"phone","phone",digits,digits)` projection。
- Consumed by: Task 8-10。

- [ ] **Step 1: 写绑定与幂等失败测试**

```java
@Test
void bindsToSelectedPhoneAndCreatesExactlyOnce() throws Exception {
    ContactGroups groups = contactId -> List.of(
            "email:buyer@example.com", "phone:+8613800000000");
    CallRecordService service = service(groups);
    CreateCallRecordCommand command = new CreateCallRecordCommand(
            "email:buyer@example.com", "phone:+8613800000000", "inbound",
            Instant.parse("2026-07-30T09:00:00Z"), "request-1", "call.mp3",
            "audio/mpeg", "zhangsan");

    UUID first = service.create(command, fixture("one-second-tone.mp3")).id();
    UUID replay = service.create(command, fixture("one-second-tone.mp3")).id();
    assertEquals(first, replay);
    assertEquals(1, repository.countRecords());
    assertEquals(1, audioStore.publishedCount());
}

@Test
void requiresPhoneWhenContactHasOneAndRejectsForeignPhone() {
    // current group normalizes phone:+8613800000000 to phone:8613800000000
    assertEquals("CONTACT_BINDING_INVALID", assertThrows(CallRecordException.class,
            () -> service.create(commandWithPhone(""), fixture())).code());
    assertEquals("CONTACT_BINDING_INVALID", assertThrows(CallRecordException.class,
            () -> service.create(commandWithPhone("phone:+8613900000000"), fixture())).code());
}

@Test
void projectsPhoneIdentityAsPhoneInsteadOfUnknown() {
    assertEquals("phone:8613800000000",
            ContactPointUtil.normalizePointId("PHONE:+86 138-0000-0000"));
    assertEquals("", ContactPointUtil.normalizePointId("phone:---"));

    ContactPoint point = ContactPointUtil.fromId("phone:+86 138-0000-0000", null);
    assertEquals("phone:8613800000000", point.id);
    assertEquals("phone", point.channel);
    assertEquals("phone", point.type);
    assertEquals("8613800000000", point.value);
    assertEquals("8613800000000", point.label);
}

@Test
void keepsPhoneProjectionAcrossMergeAndSplit() throws Exception {
    store.mergeContacts("email:buyer@example.com", "phone:+86 138-0000-0000");
    UnifiedContact merged = findContact(store.contacts(), "email:buyer@example.com");
    assertTrue(merged.points.stream().anyMatch(point ->
            point.id.equals("phone:8613800000000")
                    && point.channel.equals("phone")
                    && point.type.equals("phone")));

    store.splitContact("email:buyer@example.com", "phone:8613800000000");
    UnifiedContact split = findContact(store.contacts(), "phone:8613800000000");
    assertEquals("phone", split.points.get(0).channel);
    assertEquals("phone", split.points.get(0).type);
}
```

另测无 phone 时 anchor=current primary、direction 必选、occurredAt 未来超过 5 分钟拒绝、clientRequestId 上限 255、修订 actor/version、失败才能重试、queue 64 满时上传前拒绝。

- [ ] **Step 2: 运行测试确认 service 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CallRecordServiceTest test`

Expected: FAIL，编译错误包含 `CallRecordService`。

- [ ] **Step 3: 定义联系人 group port，避免 service 依赖整个 Store**

在 `CallRecordService.java` 内定义 package-private functional interface：

```java
@FunctionalInterface
interface ContactGroups {
    List<String> points(String contactId) throws Exception;
}
```

生产装配传 `store::contactGroup`；测试传固定 lambda。`CallRecordService` 对返回 point 全部调用 `ContactPointUtil.normalizePointId` 后生成 `LinkedHashSet`。

`callrecord` 是子包，不能访问当前 package-private helper。因此只扩大必要的共享入口：把 `ContactPointUtil` 改为 `public final class`，把 `normalizePointId` 改为 `public static`；其他方法保持 package-private。给 `normalizePointId` 增加大小写不敏感的 `phone:` 分支，调用现有 `normalizePhone`；纯数字结果为空时返回空字符串，否则唯一 canonical id 为 `phone:` 加纯数字。给 `fromId` 增加 `phone:` projection，精确返回 `new ContactPoint(normalized, "phone", "phone", digits, digits)`。`UnifiedMessageStore.ContactAccumulator.toContact` 已对 group 中每个 identity 调用 `fromId`，所以 merge/split 后继续走同一 projection；先在 `UnifiedMessageStoreTest` 增加 email/chatapp/wecom/phone 规范化、projection 与联系人合并/拆分回归，禁止为电话另写 normalizer 或在 UI 猜测 `phone:`。

- [ ] **Step 4: 实现创建事务补偿顺序**

```java
public CallRecord create(CreateCallRecordCommand command, InputStream input)
        throws CallRecordException
```

顺序固定：

1. 校验 actor、direction、occurredAt、clientRequestId、当前 group 和 phone 归属。
2. 决定 anchor：有 phone 用 phone，否则用规范化 current contact primary。
3. 先 `findByIdempotency`；存在则直接返回，不读上传流。
4. `countPending >= capacity` 时抛 `TRANSCRIPTION_QUEUE_FULL`，不写 temp。
5. `audioStore.stage`，再生成 UUID 并 `publish`。
6. 构造 queued record，调用 `repository.saveNew`。
7. save 失败先 `audioStore.delete(asset)`；若 code 是 `CALL_RECORD_IDEMPOTENCY_CONFLICT`，再用同一 canonical anchor/clientRequestId 调 `findByIdempotency` 并返回 winner；其他错误原样抛出。winner 缺失按 repository 损坏处理，不吞错。

不可把 `InputStream` 或绝对路径保存在 record。

- [ ] **Step 5: 实现 detail/retry/revise/list**

- `detail` / `list` 要求 record anchor 在当前 group anchors 中。
- `retry` 校验 actor 与 `clientRequestId`，使用 `manualRetry` + CAS。
- `revise` 校验文本 1..100000 字符、actor 1..128、expectedVersion；追加 revision，不覆盖 originalText。
- 同一 expectedVersion 冲突返回 `TRANSCRIPT_VERSION_CONFLICT` / 409。

- [ ] **Step 6: 运行 service 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CallRecordServiceTest test`

Expected: PASS，验证幂等重放不会读取第二个 InputStream，可用一个读取即抛错的 stream 断言。

- [ ] **Step 7: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordService.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ContactPointUtil.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordServiceTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: own call record lifecycle"
```

### Task 6: 实现有上界的 FunASR OpenAI-compatible Adapter

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FunAsrClient.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/FunAsrClientTest.java`

**Interfaces:**
- Produces: `FunAsrClient(Config,HttpClient)`、`transcribe(Path,String)` → `TranscriptionResult`。
- Consumed by: Task 7。

- [ ] **Step 1: 写真实 HTTP 合同失败测试**

使用 JDK `HttpServer` 绑定 `127.0.0.1:0`，handler 断言：

```java
assertEquals("POST", exchange.getRequestMethod());
assertTrue(exchange.getRequestHeaders().getFirst("Content-Type")
        .startsWith("multipart/form-data; boundary="));
byte[] body = readBounded(exchange.getRequestBody(), 65536);
String multipart = new String(body, StandardCharsets.ISO_8859_1);
assertTrue(multipart.contains("name=\"model\"\r\n\r\nsensevoice"));
assertTrue(multipart.contains("name=\"response_format\"\r\n\r\nverbose_json"));
assertTrue(multipart.contains("name=\"file\"; filename=\"recording.mp3\""));
```

返回：

```json
{"text":"你好","duration":1.0,"model":"sensevoice","segments":[{"text":"你好","start":0.0,"end":1.0}]}
```

断言 result。另测 400→`FUNASR_REJECTED` non-retryable、503→`FUNASR_UNAVAILABLE` retryable、超时→`FUNASR_TIMEOUT`、10 MiB+1、20001 segments、倒置时间、空 text、非 JSON。

- [ ] **Step 2: 运行测试确认 client 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=FunAsrClientTest test`

Expected: FAIL，编译错误包含 `FunAsrClient`。

- [ ] **Step 3: 实现流式 multipart BodyPublisher**

不要 `Files.readAllBytes`。使用 `HttpRequest.BodyPublishers.concat`：

```java
HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
        ofText("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"),
        ofText("--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "verbose_json\r\n"),
        ofText("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"recording.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"),
        HttpRequest.BodyPublishers.ofFile(audioPath),
        ofText("\r\n--" + boundary + "--\r\n"));
```

`ofText` 固定 UTF-8。请求 URI 用 `base.resolve("/v1/audio/transcriptions")`，timeout 取配置；不设置 Authorization。

- [ ] **Step 4: 实现有界响应读取和严格 JSON 校验**

使用 `BodyHandlers.ofInputStream()`，逐块读取到 `ByteArrayOutputStream`，累计超过上限立即关闭并抛 `FUNASR_INVALID_RESPONSE`。解析时：

- text 1..1,000,000 字符。
- duration `>0 && <=7200`。
- model 必须非空且 <=128；保存响应实际 model。
- segments 数量 1..20000；每段 text 非空 <=100000，`0<=start<=end<=duration+1`，并按 start 非递减。
- 不输出原 body 到 exception 或日志。

- [ ] **Step 5: 运行 adapter 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=FunAsrClientTest test`

Expected: PASS；测试服务收到的 file bytes 与 fixture SHA-256 一致，证明未损坏 multipart。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FunAsrClient.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/FunAsrClientTest.java
git commit -m "feat: call FunASR transcription endpoint"
```

### Task 7: 实现持久队列、租约、有限重试与 Runtime 生命周期

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/TranscriptionWorker.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRuntime.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/TranscriptionWorkerTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRuntimeTest.java`

**Interfaces:**
- Consumes: repository、audio store、FunASR client、Task 2 state machine。
- Produces: `TranscriptionWorker.start()` / `close()`，`CallRecordRuntime.open(Config,UnifiedMessageStore,Consumer<CallRecordEvent>)`。
- Produces: runtime 只暴露 `service()`、`audioStore()`、`available()`、`startupFailure()`；时间线与播放会话由 Tasks 8-9 创建后在 Task 10 组合，避免反向依赖未来类型。
- Produces event: `CallRecordEvent(String callRecordId,String contactAnchorPointId,String state,long version)`，供 App SSE。

- [ ] **Step 1: 写 worker 失败测试**

```java
@Test
void completesQueuedRecordAndPublishesOnlyAfterDurableSave() throws Exception {
    repository.saveNew(Fixtures.queuedRecord());
    CountDownLatch event = new CountDownLatch(1);
    TranscriptionWorker worker = worker(repository, audioStore,
            (path, model) -> Fixtures.result(), ignored -> event.countDown());
    worker.start();
    assertTrue(event.await(2, TimeUnit.SECONDS));
    worker.close();
    assertEquals("completed", repository.find(Fixtures.ID).orElseThrow()
            .transcription().state());
}

@Test
void retriesTransientFailureThreeTimesAndStopsOnPermanentFailure() throws Exception {
    // inject a client throwing retryable timeout three times
    // advance MutableClock over nextAttemptAt and signal worker
    assertEquals("failed", repository.find(Fixtures.ID).orElseThrow()
            .transcription().state());
    assertEquals(3, repository.find(Fixtures.ID).orElseThrow()
            .transcription().attempts());
}
```

另测 close 中断 in-flight `HttpClient` future、旧 lease 完成被拒、restart recovery、queue full 不开新线程、worker 并发严格等于配置。

- [ ] **Step 2: 运行测试确认 worker/runtime 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=TranscriptionWorkerTest,CallRecordRuntimeTest test`

Expected: FAIL，编译错误包含 `TranscriptionWorker`。

- [ ] **Step 3: 实现 repository 驱动队列，不创建第二份持久真源**

`TranscriptionWorker` 使用固定线程池和一个 scheduler；repository 中 state 是唯一队列真相。每次 signal 或定时 tick：

1. `listRunnable(now, capacity)`（在 repository port 增加此精确方法，按 `nextAttemptAt,createdAt,id` 排序，最多 queue capacity）。
2. 对每条用 CAS 写 `processing + lease`。
3. worker 打开 audio path，调用 client。
4. 成功 `complete` + CAS，再 publish event。
5. `CallRecordException.retryable()` 进入 state machine fail；其他异常统一包装 `FUNASR_UNAVAILABLE`，不泄露堆栈到 record。
6. retry backoff 固定为 5 秒、30 秒；第三次失败终止。

不要用无界 `Executors.newCachedThreadPool`、无界 `LinkedBlockingQueue` 或 sleep。

- [ ] **Step 4: 实现可降级 Runtime**

```java
public final class CallRecordRuntime implements AutoCloseable {
    public static CallRecordRuntime open(Config config, UnifiedMessageStore store,
                                         Consumer<CallRecordEvent> events);
    public boolean available();
    public CallRecordService service();
    public LocalAudioStore audioStore();
    public CallRecordException startupFailure();
    @Override public void close();
}
```

- 配置/目录锁/损坏导致电话子系统 unavailable，但不抛出阻断 App 启动。
- available=false 时所有电话路由返回 startup failure 的稳定 code；消息路由不受影响。
- available=true 时先 `recoverProcessing(now)`，再 start worker。
- close 顺序：停止接收、worker close、repository close；每步异常聚合 suppressed。

- [ ] **Step 5: 运行 worker/runtime 测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=TranscriptionWorkerTest,CallRecordRuntimeTest test`

Expected: PASS；无测试线程泄漏，Surefire 进程正常退出。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/TranscriptionWorker.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRuntime.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRepository.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepository.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/TranscriptionWorkerTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordRuntimeTest.java
git commit -m "feat: process call transcriptions with bounded workers"
```

### Task 8: 实现联系人统一时间线投影与稳定分页

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `UnifiedMessageStore.thread(String)`、`contactGroup(String)`、`CallRecordService.list(Set<String>)`。
- Produces: `TimelinePage page(String contactId,String cursor,int limit)`。
- Produces DTO: `TimelineItem(type,occurredAt,sortId,message,callRecordCard)`、`CallRecordCard`、`TimelinePage(items,nextCursor,itemCount,threadRevision)`。
- Consumed by: Task 9-10。

- [ ] **Step 1: 写混排分页失败测试**

```java
@Test
void interleavesMessagesAndCallCardsByOccurredAtWithStableCursor() throws Exception {
    ContactTimelineService service = fixtureTimeline(
            message("m1", "2026-07-30T09:00:00Z"),
            call("c1", "2026-07-30T09:05:00Z", "completed", 4),
            message("m2", "2026-07-30T09:10:00Z"));

    TimelinePage latest = service.page(CONTACT, "", 2);
    assertEquals(List.of("c1", "m2"), latest.items().stream()
            .map(TimelineItem::sortId).toList());
    TimelinePage older = service.page(CONTACT, latest.nextCursor(), 2);
    assertEquals(List.of("m1"), older.items().stream()
            .map(TimelineItem::sortId).toList());
}

@Test
void callVersionChangesThreadRevisionWithoutChangingMessageCount() throws Exception {
    String queued = service.page(CONTACT, "", 10).threadRevision();
    repository.replace(completedCall(), queuedCall().version());
    assertNotEquals(queued, service.page(CONTACT, "", 10).threadRevision());
}
```

另测相同时间 `typeRank(message=0,callRecord=1), sortId`、非法 cursor、合并后出现、拆分后跟 anchor 移动、每页 1..100。

- [ ] **Step 2: 运行测试确认 timeline service 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ContactTimelineServiceTest test`

Expected: FAIL，编译错误包含 `ContactTimelineService`。

- [ ] **Step 3: 给 Store 暴露只读消息快照，不嵌入电话逻辑**

保留现有 `threadPage` 兼容旧测试；新增 package-public：

```java
public List<UnifiedMessage> timelineMessages(String contactPointId) throws IOException {
    return List.copyOf(thread(contactPointId));
}
```

电话 service 通过 port/lambda 消费它。不要让 `UnifiedMessage` 增加 call 字段。

- [ ] **Step 4: 实现统一排序、cursor 和 revision**

- message item occurredAt=`MessageTime.parseInstant(timestamp)`，sortId=`id/sourceId`。
- call item occurredAt=`CallRecord.occurredAt`，sortId=`id.toString()`。
- comparator：`occurredAt`、typeRank、sortId。
- cursor JSON `{occurredAt,typeRank,sortId}` → UTF-8 → URL-safe Base64 无 padding；解码长度最大 2048。
- page 从完整有界快照尾部取最近 limit，nextCursor 指向本页第一项。
- revision 使用 SHA-256，逐项写 `type:id:version-or-message-fields`；不得依赖 `List.hashCode()`。
- card 只含 id/direction/phonePointId/occurredAt/duration/state/errorCode/version，不含原始转录或本地路径。

- [ ] **Step 5: 运行 timeline 与旧分页回归**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ContactTimelineServiceTest test
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test
```

Expected: PASS；旧 `/api/threads` 分页行为未变，新的 timeline 测试全通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineService.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineServiceTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: project calls into contact timelines"
```

### Task 9: 实现 actor 认证、短时播放会话和 Range

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallAudioSessionService.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallAudioSessionServiceTest.java`

**Interfaces:**
- Produces: `WeComViewerService.requireViewerActor(String token)` → `String wecomUserId`。
- Produces: `CallAudioSessionService.create(actor,callRecordId)` → `AudioSessionCookie`；Secure 固定来自 `Config.callAudioCookieSecure()`，不信任 `X-Forwarded-Proto`。
- Produces: `consume(cookieValue,callRecordId)` → `AudioAuthorization`（会话不一次性消费，TTL 内可多次 Range）。
- Consumed by: Task 10 HTTP adapter。

- [ ] **Step 1: 写 actor 与会话失败测试**

```java
@Test
void resolvesOnlyLiveViewerTokenToActor() throws Exception {
    WeComViewerService service = viewerWithSuccessfulLogin("zhangsan");
    String token = service.exchangeLoginCode("code").viewerAuthToken();
    assertEquals("zhangsan", service.requireViewerActor(token));
    clock.advance(Duration.ofSeconds(301));
    assertThrows(SecurityException.class, () -> service.requireViewerActor(token));
}

@Test
void createsPathBoundHttpOnlyCookieAndRejectsOtherRecord() throws Exception {
    CallAudioSessionService sessions = service(fixedClock());
    AudioSessionCookie cookie = sessions.create("zhangsan", CALL_ID);
    assertTrue(cookie.headerValue().contains("HttpOnly"));
    assertTrue(cookie.headerValue().contains("SameSite=Strict"));
    assertTrue(cookie.headerValue().contains("Path=/api/v1/call-records/" + CALL_ID + "/audio"));
    assertEquals(CALL_ID, sessions.authorize(cookie.value(), CALL_ID).callRecordId());
    assertThrows(CallRecordException.class,
            () -> sessions.authorize(cookie.value(), OTHER_CALL_ID));
}
```

另以 `WECOM_LOGIN_REDIRECT_URI=https://crm.example.com/` 测 Secure，以 localhost HTTP 测无 Secure；覆盖 TTL 300、每 actor 8 淘汰最旧、全局 256、随机 token 不可预测、服务重启后失效。严禁读取 `X-Forwarded-Proto` 决定 Cookie 属性。

- [ ] **Step 2: 运行测试确认方法和类不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComViewerServiceTest,CallAudioSessionServiceTest test`

Expected: FAIL，编译错误包含 `requireViewerActor` 或 `CallAudioSessionService`。

- [ ] **Step 3: 暴露最小 actor 解析 port**

在 `WeComViewerService` 新增：

```java
public String requireViewerActor(String viewerAuthToken) throws Exception {
    return resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond()).wecomUserId();
}
```

不返回完整 `ViewerAuth`，不延长 token TTL，不接受 body actor。

- [ ] **Step 4: 实现内存播放会话**

`CallAudioSessionService` 使用 `SecureRandom` 生成 32 bytes URL-safe Base64 token，只在 `ConcurrentHashMap` 保存 SHA-256(token) → session。Cookie 名固定 `mc_call_audio`；header：

```text
mc_call_audio=base64url-session-token; Max-Age=300; Path=/api/v1/call-records/550e8400-e29b-41d4-a716-446655440000/audio; HttpOnly; SameSite=Strict[; Secure]
```

每次 create 先清理过期，再淘汰当前 actor 最旧直到 `<8`，再淘汰全局最旧直到 `<256`。authorize 用 `MessageDigest.isEqual` 或 hash map 精确 hash，比对 recordId 和 expiresAt；失败 `AUDIO_SESSION_EXPIRED` / 401。

- [ ] **Step 5: 运行认证与会话测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComViewerServiceTest,CallAudioSessionServiceTest test`

Expected: PASS；既有 viewer 登录/会话测试不回归。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallAudioSessionService.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerServiceTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallAudioSessionServiceTest.java
git commit -m "feat: authorize short-lived call audio playback"
```

### Task 10: 接入 `/api/v1` HTTP、流式 multipart 与音频 Range

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapter.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapterTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: runtime service/audio store、`ContactTimelineService`、`CallAudioSessionService`、`WeComViewerService.requireViewerActor`。
- Produces the seven `/api/v1` operations in the design, including `POST /audio-sessions`。
- Produces: `boolean handle(HttpExchange)`，false 表示非电话路由。

- [ ] **Step 1: 写路由与 multipart 失败测试**

启动真实 JDK `HttpServer` + adapter，测试：

```java
HttpRequest request = HttpRequest.newBuilder(server.resolve(
        "/api/v1/contacts/email%3Abuyer%40example.com/call-records"))
        .header("X-WeCom-Viewer-Auth", "viewer-token")
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(streamingMultipart(fixturePath, boundary, Map.of(
                "direction", "inbound",
                "occurredAt", "2026-07-30T09:00:00Z",
                "clientRequestId", "request-1")))
        .build();
HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
assertEquals(202, response.statusCode());
assertEquals("queued", JsonParser.parseString(response.body()).getAsJsonObject()
        .get("state").getAsString());
```

另测无 token 401、foreign phone 400、oversize 用 Content-Length 先拒绝、两个 file part 拒绝、未知 part 拒绝、JSON body 上限、详情/list/retry/revise。

对 raw path 增加独立回归，必须通过真实 `HttpServer` 请求覆盖：

```java
assertEquals("email:buyer@example.com",
        CallRecordHttpAdapter.decodeRawPathSegment("email%3Abuyer%40example.com"));
assertEquals("phone:8613800000000",
        CallRecordHttpAdapter.decodeRawPathSegment("phone%3A8613800000000"));

for (String raw : List.of(
        "email%2fbuyer", "email%2Fbuyer", "email%5cbuyer", "email%5Cbuyer",
        "email%00buyer", "email%252Fbuyer", "email%255cbuyer", "%", "%2", "%GG", "%FF")) {
    assertEquals("ROUTE_PATH_INVALID", assertThrows(CallRecordException.class,
            () -> CallRecordHttpAdapter.decodeRawPathSegment(raw)).code());
}
```

再向真实 server 请求 `/api/v1/contacts/email%3Abuyer%40example.com/call-records`，断言进入 create handler；请求含 `%2F`、`%252F`、`%5C`、`%00` 的同位置路径均返回 400 `ROUTE_PATH_INVALID`，且 service 调用计数保持 0。这样同时证明合法 contact id 可达、编码斜杠不会改变路由边界、也不存在下游二次解码。

- [ ] **Step 2: 写 Range 失败测试**

1. `POST /audio-sessions` 带 viewer token，断言 `204` 与 Set-Cookie。
2. `GET /audio` 带 Cookie 与 `Range: bytes=10-19`，断言 206、10 bytes、`Content-Range` 等于 `bytes 10-19/` 加 fixture 的 `Files.size(audioPath)`、`Accept-Ranges: bytes`。
3. `Range: bytes=0-1,4-5` 返回 416 + `AUDIO_RANGE_INVALID`。
4. 无 Cookie、过期 Cookie、其他 call 的 Cookie 返回 401。
5. HEAD/POST audio 返回 405，不泄露文件长度给未授权请求。

- [ ] **Step 3: 运行测试确认 adapter 不存在**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CallRecordHttpAdapterTest test`

Expected: FAIL，编译错误包含 `CallRecordHttpAdapter`。

- [ ] **Step 4: 实现路由匹配和统一错误 envelope**

`handle` 只接受以下路由形状，UUID 用 `UUID.fromString` 后重新输出规范格式：

```text
POST  ^/api/v1/contacts/([^/]+)/call-records$
GET   ^/api/v1/contacts/([^/]+)/timeline$
GET   ^/api/v1/call-records/([0-9a-f-]{36})$
POST  ^/api/v1/call-records/([0-9a-f-]{36})/audio-sessions$
GET   ^/api/v1/call-records/([0-9a-f-]{36})/audio$
POST  ^/api/v1/call-records/([0-9a-f-]{36})/retry$
PATCH ^/api/v1/call-records/([0-9a-f-]{36})/transcript$
```

上面的表达式只是合同说明，不能直接对 `getPath()` 或整条解码后的 path 做 regex。实现先读取 `exchange.getRequestURI().getRawPath()`，要求以一个 `/` 开头、无空段或尾随 `/`，再用 `split("/", -1)` 保留原始边界。`api`、`v1`、`contacts`、`call-records`、`timeline`、`audio-sessions`、`audio`、`retry`、`transcript` 等固定段必须按 raw ASCII 精确匹配；只把 contact id 动态段传入下列 package-private helper，call record id raw 段禁止 `%` 后再解析 UUID：

```java
static String decodeRawPathSegment(String raw) throws CallRecordException {
    if (raw == null || raw.isEmpty() || raw.length() > 2_048) {
        throw routePathInvalid();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
    for (int index = 0; index < raw.length(); index++) {
        char current = raw.charAt(index);
        if (current == '%') {
            if (index + 2 >= raw.length()) throw routePathInvalid();
            int high = Character.digit(raw.charAt(index + 1), 16);
            int low = Character.digit(raw.charAt(index + 2), 16);
            if (high < 0 || low < 0) throw routePathInvalid();
            int decodedByte = (high << 4) | low;
            if (decodedByte == '/' || decodedByte == '\\' || decodedByte == 0) {
                throw routePathInvalid();
            }
            bytes.write(decodedByte);
            index += 2;
            continue;
        }
        if (current > 0x7f || current == '\\' || current == 0) {
            throw routePathInvalid();
        }
        bytes.write(current);
    }

    final String decoded;
    try {
        decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
    } catch (CharacterCodingException exception) {
        throw routePathInvalid();
    }
    String lower = decoded.toLowerCase(Locale.ROOT);
    if (decoded.isBlank() || decoded.equals(".") || decoded.equals("..")
            || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
            || decoded.indexOf(0) >= 0 || lower.contains("%2f")
            || lower.contains("%5c") || lower.contains("%00")) {
        throw routePathInvalid();
    }
    return decoded;
}
```

helper 不得调用 `URLDecoder`（它会把 `+` 当空格），返回值不得再交给任何 decode API。`%252F` 单次解码后仍含 `%2F`，因此由末尾检查拒绝，而不是再次解码。`routePathInvalid()` 固定构造 `CallRecordException("ROUTE_PATH_INVALID",400,"请求路径无效",false)`。

错误 JSON 固定：

```json
{"code":"INVALID_MP3","message":"MP3 文件无效","traceId":"uuid","context":{}}
```

context 只允许有界标量；绝对路径、FunASR body、token、转录内容禁止进入响应。

- [ ] **Step 5: 实现 Commons FileUpload 流式 adapter**

自定义 `RequestContext` 包装 `HttpExchange`，`getInputStream()` 返回 request body；设置：

```java
FileUpload upload = new FileUpload();
upload.setFileSizeMax(config.callRecordMaxAudioBytes());
upload.setSizeMax(config.callRecordMaxAudioBytes() + 65_536L);
upload.setPartHeaderSizeMax(8_192);
FileItemIterator items = upload.getItemIterator(context);
```

逐 part 处理：字段最多 8 KiB、总字段最多 8 个、file 必须恰好 1 个且最后只允许 EOF。字段名只接受 `file,phonePointId,direction,occurredAt,clientRequestId`。file stream 直接传 service；不调用 `readAllBytes` 或 `parseRequest`。

注意：为让 service 先完成幂等/队列检查再读 file，adapter 先缓存小字段；要求客户端 file 为最后一 part。否则返回 `MULTIPART_ORDER_INVALID` / 400，并在 OpenAPI 描述该约束。

- [ ] **Step 6: 实现单 Range 响应**

解析：

- 无 Range：200，全文件；仍要求播放 Cookie。
- `bytes=start-end`、`bytes=start-`、`bytes=-suffix`：206。
- 多区间、非法数字、start>=size：416，设置 `Content-Range` 为 `bytes */` 加实际 `Files.size(audioPath)`。
- 使用 `FileChannel.position(start)` + 64 KiB buffer 精确写 length，不把文件读入堆。
- 响应头 `Content-Type: audio/mpeg`、`Accept-Ranges: bytes`、`Content-Length`、`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`。

- [ ] **Step 7: 在 App 只做装配与转发**

`startWeb` 创建 `CallRecordRuntime`，再以 `runtime.service()` 组装 `ContactTimelineService`、以配置组装 `CallAudioSessionService`，最后创建 `CallRecordHttpAdapter`；root handler 在旧路由之前调用：

```java
if (callRecordHttp.handle(exchange)) return;
```

`CallRecordEvent` 通过既有 `publishEvent` 发布有界 payload。shutdown hook 关闭 runtime。不要在 `App` 解析 multipart、Range 或状态机。

- [ ] **Step 8: 运行 HTTP 与旧路由回归**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=CallRecordHttpAdapterTest,WeComViewerServiceTest test
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test
```

Expected: PASS；旧 `/api/threads`、企业微信 viewer、邮件/WhatsApp 发送测试不回归。

- [ ] **Step 9: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapter.java \
  demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapterTest.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: expose call transcription HTTP APIs"
```

### Task 11: 在聊天框渲染电话卡片、上传表单与右侧详情

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: Task 10 API、现有 `state.wecomAuth.viewerAuthToken`、SSE 与线程分页状态。
- Produces: 电话记录页签、XHR 上传进度、电话卡片、右侧详情、播放续期/一次恢复、重试和修订 UI。

- [ ] **Step 1: 写内嵌 UI 合同失败测试**

在 `UnifiedMessageStoreTest` 增加静态合同断言：

```java
assertContains(html, "data-channel=\"callRecord\"");
assertContains(html, "accept=\"audio/mpeg,.mp3\"");
assertContains(html, "function uploadCallRecord()");
assertContains(html, "function renderCallRecordCard(item)");
assertContains(html, "function openCallRecordDetail(callRecordId)");
assertContains(html, "function renewCallAudioSession(callRecordId)");
assertContains(html, "CALL_AUDIO_RENEW_MS = 240000");
assertContains(html, "X-WeCom-Viewer-Auth");
assertNotContains(html, "viewerAuthToken=");
```

Node VM 行为测试覆盖：上传成功插入 queued card；卡片点击只更新右栏；切换联系人清 timer；audio error 最多恢复一次并恢复 currentTime；SSE refresh 保持滚动位置。

增加 phone projection/UI 合同：给 selected contact 注入 `points=[{id:"phone:8613800000000",channel:"phone",type:"phone",value:"8613800000000",label:"8613800000000"}]`，断言号码 select 的 option value 是 canonical `phone:8613800000000`、可见文本不是 `unknown`，提交 FormData 的 `phonePointId` 也是该值。再注入无 phone 的联系人，断言显示“未绑定电话”且不 append 空 `phonePointId`。

- [ ] **Step 2: 运行 UI 合同测试确认失败**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test
```

Expected: FAIL，断言缺少 `uploadCallRecord`。

- [ ] **Step 3: 将线程请求切换到统一 timeline API**

`threadPageUrl(id,cursor)` 改为 `/api/v1/contacts/{encoded}/timeline?limit=10`；前端 items 按 `type` 分派。保留旧 `/api/threads` 后端供现有非 UI 调用，但当前页面只消费新合同。

`mergeThreadMessages` 的 key 改为 `${item.type}:${item.sortId}`；thread render key 包含 call card `state/version`。

- [ ] **Step 4: 增加电话记录页签和 XHR 流式上传**

电话记录是固定页签，即使联系人没有 phone identity 也显示。表单：

```html
<input id="callFile" type="file" accept="audio/mpeg,.mp3">
<select id="callDirection"><option value="">选择方向</option>...</select>
<input id="callOccurredAt" type="datetime-local">
<select id="callPhonePoint">...</select>
<progress id="callUploadProgress" max="100" value="0"></progress>
```

使用 `XMLHttpRequest.upload.onprogress`，而不是 fetch 假进度。FormData append 小字段后最后 append file；请求头只设置 `X-WeCom-Viewer-Auth`，浏览器自己生成 boundary。客户端先检查 `.mp3` 与 `file.size<=104857600`，但服务端仍是唯一校验真相。

号码选项只能消费联系人 owner 已投影的 `contact.points`：过滤 `point.channel === 'phone' && point.type === 'phone' && point.id.startsWith('phone:')`，`option.value=point.id`，显示 `point.label || point.value`。有一个以上号码时必须选择；只有一个时默认选中；没有时显示禁用的“未绑定电话”，上传时省略 `phonePointId`，由 service 绑定 current primary。禁止从消息文本抽号码、禁止把 `unknown` identity 当号码、禁止在 JS 中再次规范化号码。

- [ ] **Step 5: 渲染紧凑电话卡片**

`renderThreadMessages` 分支：

```javascript
if (item.type === 'callRecord') return renderCallRecordCard(item);
const m = item.message;
```

卡片显示电话图标、呼入/呼出、号码/未指定、occurredAt、duration、状态；`data-call-record-id` 点击 `openCallRecordDetail`。不渲染 originalText。

- [ ] **Step 6: 实现右栏详情、播放会话和修订**

打开详情顺序：

1. 清除旧详情 polling、audio renew timer 和一次恢复标记。
2. 带 auth header GET detail，渲染元数据、segments、originalText、current revision/history。
3. 带 auth header POST `/audio-sessions`，成功后才设置 `<audio src=".../audio">`。
4. `setInterval(..., 240000)` 旋转 Cookie；`visibilitychange` hidden 时停止，visible 且仍选中时立即续期再重启 timer。
5. queued/processing 每 3 秒 poll detail；completed/failed 停止。
6. audio `error` 时若未恢复过：保存 `currentTime`，重新 POST session，reload，`loadedmetadata` 后恢复并 play；第二次失败展示错误，不循环。
7. retry 和 PATCH revise 都带 auth header、幂等/expectedVersion，成功后重载详情和当前 timeline。

- [ ] **Step 7: 样式与移动约束**

复用现有 CSS token，新增 `.call-card`、`.call-state`、`.call-detail`、`.transcript-segment`、`.call-upload-progress`。桌面卡片最大宽度与普通 msg 一致；移动端右栏沿现有单列/抽屉规则，不固定绝对宽度。所有按钮有 `aria-label`，状态不仅靠颜色。

- [ ] **Step 8: 运行 UI 合同和行为测试**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test
```

Expected: PASS；Node VM 测试确认没有 token query、timer 泄漏或重复恢复。

- [ ] **Step 9: 提交**

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: show call cards in message timelines"
```

### Task 12: 补齐 OpenAPI 唯一合同

**Files:**
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- Produces: 7 个电话 operation，总 operation 数从 27 增至 34。
- Produces schemas: `ContactTimelinePage`、`ContactTimelineItem`、`CallRecordCard`、`CallRecordDetail`、`TranscriptSegment`、`TranscriptRevision`、`CallRecordCreateResponse`、`CallRecordRetryRequest`、`TranscriptRevisionRequest`。

- [ ] **Step 1: 先写 OpenAPI 失败断言**

在合同测试增加：

```javascript
const createCall = operation(operations, 'post',
  '/api/v1/contacts/{contactId}/call-records');
assert.match(createCall.body, /multipart\/form-data:/);
assert.match(createCall.body, /x-max-body-bytes: 104923136/);
assert.match(createCall.body, /file part must be last/i);
assert.match(createCall.body, /^        - name: X-WeCom-Viewer-Auth$/m);

const audioSession = operation(operations, 'post',
  '/api/v1/call-records/{callRecordId}/audio-sessions');
assert.match(audioSession.body, /Set-Cookie/);
const audio = operation(operations, 'get',
  '/api/v1/call-records/{callRecordId}/audio');
assert.match(audio.body, /audio\/mpeg/);
assert.match(audio.body, /206/);
assert.doesNotMatch(audio.body, /viewerAuthToken|token.*query/i);
assert.equal(operations.length, 34);
```

所有新 object schema 断言 `additionalProperties: false`；segments maxItems 20000，revisions maxItems 20，MP3 maxLength 104857600。

- [ ] **Step 2: 运行合同测试确认缺少 operations**

Run: `node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

Expected: FAIL，`missing POST /api/v1/contacts/{contactId}/call-records`。

- [ ] **Step 3: 更新 OpenAPI paths 与 schemas**

关键合同：

- create multipart body required `[file,direction,occurredAt,clientRequestId]`，file `format: binary`、`contentMediaType: audio/mpeg`、`maxLength: 104857600`；描述 file 必须最后。
- timeline item 用 `oneOf` + discriminator `type`，不能把 message 与 call 字段平铺。
- detail 不返回 `relativePath`；audio URL 由固定 path 推导。
- audio-session `204` 声明 `Set-Cookie`，不在 body 返回 token。
- audio GET 声明 Cookie security/说明、200/206/401/416、`Accept-Ranges` / `Content-Range`。
- retry request required `clientRequestId`；revision request required `text,expectedVersion`。
- 所有字符串、数组、数字有 spec 对应上界。

- [ ] **Step 4: 运行 OpenAPI 测试和可选 Spectral**

Run:

```bash
node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
cd demo/message-center-demo && npx --yes @stoplight/spectral-cli lint \
  -r contracts/openapi/.spectral.yaml contracts/openapi/message-center-v1.yaml
```

Expected: `validated 34 OpenAPI operations`；Spectral 0 error/0 warning。若网络不可用且 spectral 未缓存，记录该外部门禁未运行，但 Node 合同必须通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-demo/contracts/openapi/message-center-v1.yaml \
  demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
git commit -m "docs: define call transcription API contract"
```

### Task 13: 部署 sidecar、示例配置与产品文档回写

**Files:**
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/compose.yaml`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/项目细节PRD.md`
- Modify: `docs/superpowers/specs/2026-07-30-phone-call-transcription-local-funasr-design.md`
- Create: `demo/message-center-demo/funasr/audit-environment.sh`
- Create: `demo/message-center-demo/funasr/lock-requirements.sh`
- Create after successful audit: `demo/message-center-demo/funasr/Dockerfile`
- Create from resolver report: `demo/message-center-demo/funasr/requirements.lock`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**Interfaces:**
- Produces: documented local directory, all exact env vars, private sidecar network, health check, backup/privacy/runbook and revised PRD boundary。

- [ ] **Step 1: 写文档/compose 合同失败测试**

在 `ConfigTest` 增加读取文件的断言：

```java
String env = Files.readString(Path.of("config.example.env"));
assertTrue(env.contains("CALL_RECORD_MAX_AUDIO_BYTES=104857600"));
assertTrue(env.contains("FUNASR_BASE_URL=http://funasr:8000"));
String compose = Files.readString(Path.of("compose.yaml"));
assertTrue(compose.contains("funasr:"));
assertFalse(funAsrServiceBlock(compose).contains("ports:"));
String audit = Files.readString(Path.of("funasr/audit-environment.sh"));
assertTrue(audit.contains("nvidia-smi"));
assertTrue(audit.contains("command -v funasr-server"));
assertTrue(audit.contains("/v1/models"));
assertTrue(audit.contains("^nvidia/cuda:"));
```

另断言 README 包含 `100 MiB`、`2 小时`、`data/call-records`、备份和短时播放 Cookie；PRD 不再把电话自动转写笼统列为非目标，而明确“本地 MP3 + FunASR”例外。

- [ ] **Step 2: 运行测试确认配置/文档缺失**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test`

Expected: FAIL，缺少 `FUNASR_BASE_URL` 或 compose service。

- [ ] **Step 3: 更新示例配置**

把设计第 16 节全部 env 原样加入 `config.example.env`，不加入真实地址/凭据。新增注释：本地开发若 FunASR 运行在宿主机，可显式覆盖 base URL；生产 Compose 使用 `http://funasr:8000`。

- [ ] **Step 4: 编写可执行环境审计与 lock 生成器**

仓库不预填任何环境相关 CUDA tag 或 digest。部署环境必须提供真实 `FUNASR_CUDA_BASE_IMAGE`，格式严格为 `nvidia/cuda:` 开头、同时包含非空 tag 和 `@sha256:` 加 64 位小写十六进制 digest；只给 floating tag、只给 digest、Docker Hub 短名或其他 registry 都失败关闭。`audit-environment.sh` 使用 `set -euo pipefail`，按以下顺序执行并把非敏感结果写到 `target/funasr-audit/report.txt`：

```bash
#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
report_dir="$repo_root/target/funasr-audit"
mkdir -p "$report_dir"
: "${FUNASR_CUDA_BASE_IMAGE:?set an immutable nvidia/cuda tag and sha256 digest}"

if [[ ! "$FUNASR_CUDA_BASE_IMAGE" =~ ^nvidia/cuda:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$ ]]; then
  printf '%s\n' 'FUNASR_CUDA_BASE_IMAGE must start with nvidia/cuda:, include a tag, and end with @sha256: plus 64 lowercase hex characters' >&2
  exit 64
fi
command -v docker >/dev/null
command -v nvidia-smi >/dev/null
nvidia-smi --query-gpu=driver_version,name --format=csv,noheader >"$report_dir/gpu.csv"
docker info --format '{{json .Runtimes}}' >"$report_dir/docker-runtimes.json"
if ! tr '[:upper:]' '[:lower:]' <"$report_dir/docker-runtimes.json" | grep -q 'nvidia'; then
  printf '%s\n' 'Docker nvidia runtime is unavailable' >&2
  exit 69
fi

docker pull "$FUNASR_CUDA_BASE_IMAGE"
requested_digest="${FUNASR_CUDA_BASE_IMAGE##*@sha256:}"
resolved_digests="$(docker image inspect "$FUNASR_CUDA_BASE_IMAGE" \
  --format '{{range .RepoDigests}}{{println .}}{{end}}')"
if [[ "$resolved_digests" != *"@sha256:$requested_digest"* ]]; then
  printf '%s\n' 'Pulled image digest does not match FUNASR_CUDA_BASE_IMAGE' >&2
  exit 65
fi
docker run --rm --gpus all "$FUNASR_CUDA_BASE_IMAGE" nvidia-smi \
  >"$report_dir/container-gpu.txt"

"$repo_root/funasr/lock-requirements.sh"
docker run --rm message-center-funasr:audit sh -ceu \
  'command -v funasr-server; funasr-server --help >/tmp/funasr-help.txt; test -s /tmp/funasr-help.txt'
```

脚本随后用唯一名称启动 `message-center-funasr:audit`，不发布宿主机端口；最多轮询 180 次、每次间隔 2 秒，通过 `docker exec` 分别执行 `curl -fsS http://localhost:8000/health` 和 `/v1/models`，要求 health body 去除空白后精确等于 `ok`、models JSON 中存在 id/name 为 `sensevoice` 的条目。用 `trap` 按明确 container name 清理；180 次后退出 70。不得用固定 `ok`、mock server 或跳过 `funasr-server --help` 代替。

`lock-requirements.sh` 复用同一个 `FUNASR_CUDA_BASE_IMAGE`，在临时容器的相同 Python 平台运行：

```bash
python3 -m pip install --dry-run --ignore-installed --report /work/resolve-report.json \
  funasr fastapi uvicorn python-multipart
```

随后用 Python 标准库读取 report 的 `install[]`；每项必须同时有规范化 package name、精确 version 和 `download_info.archive_info.hashes.sha256`，否则退出 66。按 package name 排序，把 report 中的真实 SHA-256 写成 pip 的 `name==version --hash=sha256:...` 格式到临时文件，检查无重复 package 后原子移动为 `funasr/requirements.lock`。生成器最后用 `mktemp -d` 创建隔离 build context，复制 lock 并写入与 Step 5 相同包安装步骤的 probe Dockerfile，基于同一镜像实际运行 `pip3 install --require-hashes -r requirements.lock`，构建结果固定标记为 `message-center-funasr:audit`；缺少任何传递依赖或 hash 必须失败，不能手工补版本或 hash。这样 Step 4 不依赖尚未创建的生产 Dockerfile，只有 probe 完整通过后才进入 Step 5。

先运行 shell 语法门禁：

```bash
cd demo/message-center-demo
bash -n funasr/audit-environment.sh funasr/lock-requirements.sh
```

Expected: exit 0。环境审计本身在 Step 7 使用真实镜像引用执行。

- [ ] **Step 5: 增加不暴露端口的 sidecar**

生产 Dockerfile 使用 required build arg，不在仓库中伪造主机相关镜像值：

```dockerfile
ARG CUDA_BASE_IMAGE
FROM ${CUDA_BASE_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip curl \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.lock /opt/funasr/requirements.lock
RUN pip3 install --no-cache-dir --require-hashes -r /opt/funasr/requirements.lock
EXPOSE 8000
CMD ["funasr-server", "--device", "cuda", "--port", "8000"]
```

Compose build args 使用 required interpolation：

```yaml
    build:
      context: ./funasr
      args:
        CUDA_BASE_IMAGE: ${FUNASR_CUDA_BASE_IMAGE:?run funasr/audit-environment.sh with an immutable nvidia/cuda tag and digest}
```

`FUNASR_CUDA_BASE_IMAGE` 的唯一来源是部署环境或 ignored 的本地 env 文件，并且必须先通过 Step 4 脚本；`config.example.env` 只说明约束与审计命令，不提供示例 digest。Dockerfile 通过 required build arg 消费审计通过的完整引用，镜像 provenance 因而保留实际 repo digest；禁止在 Dockerfile、Compose、README 或计划中复制一个环境无关的假值。

`compose.yaml`：

```yaml
  funasr:
    build:
      context: ./funasr
      args:
        CUDA_BASE_IMAGE: ${FUNASR_CUDA_BASE_IMAGE:?run funasr/audit-environment.sh with an immutable nvidia/cuda tag and digest}
    command: ["funasr-server", "--device", "cuda", "--port", "8000"]
    expose: ["8000"]
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8000/health"]
      interval: 10s
      timeout: 5s
      retries: 30
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
    volumes:
      - funasr_models:/root/.cache
    restart: unless-stopped
```

不得增加 `ports`。Compose build 必须复用已锁定 Dockerfile，不允许在 compose 中覆盖成另一套未验证启动命令。

- [ ] **Step 6: 回写 README、PRD 与设计状态**

README 写清：页面操作、状态、失败重试、本地目录权限、10 GiB 上限、备份不含 `.env`、恢复对账、FunASR 健康检查、无公网端口、认证 Cookie。PRD 将原“电话录音自动转写”非目标改为：仅本设计所述本地 MP3/FunASR 闭环进入当前 pre-split 运行面，电话系统自动采集、说话人分离和 AI 分析仍非目标。

设计文档状态改为“已确认，实施中”，并链接本计划。

- [ ] **Step 7: 验证 compose 和文档**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest test
bash -n funasr/audit-environment.sh funasr/lock-requirements.sh
test -n "${FUNASR_CUDA_BASE_IMAGE:-}"
./funasr/audit-environment.sh
docker compose config --quiet
docker compose config > target/funasr-audit/compose.rendered.yaml
if rg -n '^\s+ports:' target/funasr-audit/compose.rendered.yaml; then exit 1; fi
rg -n 'healthcheck:|capabilities:|gpu|funasr_models|expose:' \
  target/funasr-audit/compose.rendered.yaml
git check-ignore target/funasr-audit/report.txt
```

Expected: 全部 exit 0；审计报告给出真实 host/container GPU、不可变 digest、`funasr-server --help`、health 与 models 证据；生成的 lock 能通过干净 `--require-hashes` 构建；渲染的 FunASR service 没有宿主机 `ports`，有 healthcheck、GPU reservation 和 model volume。若 `FUNASR_CUDA_BASE_IMAGE` 未设置，`test -n` 明确失败并停止，不进入 Compose 或生产 Dockerfile 验收。

- [ ] **Step 8: 提交**

```bash
git add demo/message-center-demo/funasr/Dockerfile \
  demo/message-center-demo/funasr/audit-environment.sh \
  demo/message-center-demo/funasr/lock-requirements.sh \
  demo/message-center-demo/funasr/requirements.lock \
  demo/message-center-demo/config.example.env \
  demo/message-center-demo/compose.yaml \
  demo/message-center-demo/README.md \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java \
  docs/项目细节PRD.md \
  docs/superpowers/specs/2026-07-30-phone-call-transcription-local-funasr-design.md
git commit -m "docs: deploy local FunASR call transcription"
```

### Task 14: 全量门禁、真实浏览器与真实 FunASR 验收

**Files:**
- Modify only if evidence exposes a defect: files owned by Tasks 1-13
- Create: `demo/message-center-demo/docs/verification/2026-07-30-call-transcription.md`
- Create screenshots under: `demo/message-center-demo/docs/verification/screenshots/`

**Interfaces:**
- Consumes: completed Tasks 1-13。
- Produces: reproducible automated, browser and real-sidecar evidence; no mock-only completion claim。

- [ ] **Step 1: 审计任务边界和残留标记**

Run:

```bash
git status --short
rg -n "TODO|TBD|FIXME|<<<<<<<|=======|>>>>>>>" \
  demo/message-center-demo/src \
  demo/message-center-demo/contracts/openapi \
  demo/message-center-demo/README.md \
  docs/项目细节PRD.md
git diff --check
```

Expected: 无本功能 TODO/冲突/空白错误；工作树中的外部改动单独列出，不暂存、不回滚。

- [ ] **Step 2: 运行全部自动化门禁**

Run:

```bash
cd demo/message-center-demo
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest \
  -Dexec.classpathScope=test
mvn -q test
mvn -q -DskipTests compile
node contracts/openapi/message-center-v1.test.mjs
docker compose config --quiet
```

Expected: 全部 exit 0，无 warning。Testcontainers 因 Docker 不可用失败时，记录具体测试与替代证据，不能声称数据库集成门禁通过；本功能本地存储测试必须通过。

- [ ] **Step 3: 运行隔离 HTTP adapter 闭环**

运行 Task 10 的真实 JDK `HttpServer` adapter 集成夹具：使用隔离 `CALL_RECORD_DATA_DIR`、注入固定 actor resolver，并让假 FunASR 按上传 SHA 返回 verbose JSON。通过真实 HTTP 验证 multipart 上传、poll completed、timeline card、audio session、Range、revision、503 与 retry。该夹具不能加入生产路由或测试专用生产方法。把命令和实际响应 code/record ID（不含 token/原文）写入 verification 文档。

- [ ] **Step 4: 用浏览器验收真实渲染**

必须检查并截图：

1. 桌面：电话页签、上传进度、queued、processing、completed、failed。
2. 电话卡片与消息按 occurredAt 混排。
3. 点击卡片后右栏播放器、segments、原文、修订历史。
4. audio seek 触发 206；5 分钟会话通过测试配置缩短 TTL 验证续期与一次恢复。
5. 切换联系人/关闭详情后 polling 和 renew 请求停止。
6. 移动 viewport：表单、卡片、详情不重叠，不覆盖发送区。

保存桌面和移动截图；不得用 DOM 字符串或源码断言代替真实渲染。

- [ ] **Step 5: 启动真实 FunASR sidecar**

Run:

```bash
cd demo/message-center-demo
docker compose build funasr
docker compose up -d funasr
docker compose exec -T funasr curl -fsS http://localhost:8000/health
docker compose exec -T funasr curl -fsS http://localhost:8000/v1/models
```

Expected: health 返回 `ok`，models 包含 `sensevoice`。若 GPU、镜像、模型下载或 `funasr-server` 命令不可用，停止并报告外部阻断；禁止用假健康代替。

- [ ] **Step 6: 用真实中文 MP3 验收转录与恢复**

上传一段已知中文 MP3，记录：大小、时长、状态时间线、FunASR model、segment 数和人工核对摘要；不在 verification 文档保存完整敏感原文。停止 sidecar，上传第二段并等待三次有限失败；恢复 sidecar，点击人工重试，确认完成。

- [ ] **Step 7: 验证重启恢复与本地真源**

在 processing 时安全停止 App，保留 `data/call-records`，重启后确认任务立即回到 queued；已完成音频仍可播放，hash 未变。尝试第二个 App 指向同目录，确认电话子系统返回 `CALL_RECORD_STORE_BUSY`，其他消息接口仍可用。

- [ ] **Step 8: 写 verification 记录并最终复核 Git**

verification 文档必须列出实际命令、exit code、截图路径、真实 sidecar 结果、未运行门禁与剩余风险。然后：

```bash
git status --short
git diff --check
task_commits="$(sed -n 's/^- Task [0-9][0-9]* commit: `\([0-9a-f][0-9a-f]*\)`$/\1/p' \
  demo/message-center-demo/docs/verification/2026-07-30-call-transcription.md)"
test "$(printf '%s\n' "$task_commits" | sed '/^$/d' | wc -l | tr -d ' ')" = 13
git show --stat --oneline $task_commits
```

每个任务完成时按 `- Task N commit: `加反引号 hash 加反引号` 的固定格式把实际 commit hash 记入 verification 文档。Expected: 正好解析出 13 个任务提交；每个本功能提交只含计划列出的文件；不包含 `.env`、`data/call-records`、真实业务 MP3、token、外部 Daily Summary 或 ChatApp 用户改动。

- [ ] **Step 9: 提交验证证据**

```bash
git add demo/message-center-demo/docs/verification/2026-07-30-call-transcription.md \
  demo/message-center-demo/docs/verification/screenshots
git commit -m "test: verify local call transcription flow"
```

## 实施停止条件

遇到以下任一情况时停止当前任务并向用户报告证据、影响和推荐方向，不增加兼容分支：

- `funasr-server` 自部署命令或返回合同与用户提供的信息不一致。
- 无法获得真实 viewer actor，导致写操作只能信任客户端人员 ID。
- Commons FileUpload 无法在 JDK `HttpServer` 上保持 100 MiB 流式上界。
- mp3agic 无法稳定解析目标 MP3 或需要把整个文件读入堆。
- 当前联系人 group 在 merge/split 后不能稳定解析 anchor。
- 文件系统不支持当前目录的原子移动或独占锁。
- `<audio>` 在真实浏览器中无法使用路径限定 Cookie 完成 Range/seek。
- FunASR GPU、模型下载或真实 sidecar 属于外部阻断。
- 发现会要求删除旧 API、迁移历史、用户数据或现有认证模型。
