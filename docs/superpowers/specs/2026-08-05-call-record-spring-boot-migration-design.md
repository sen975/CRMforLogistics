# 电话录音转录 Spring Boot 迁移设计

**日期：** 2026-08-05
**状态：** 草稿
**适用范围：** `demo/message-center-spring/` Spring Boot + React 运行面

本设计描述将 `demo/message-center-demo` 中已设计完成的电话录音上传、FunASR 转录、音频播放、人工修订、电话仓库和联系人时间线混排能力迁移到 Spring Boot + React 架构。

## 1. 源与目标

| 维度 | 源 (message-center-demo) | 目标 (message-center-spring) |
|---|---|---|
| 持久化 | 本地 JSON 文件 + 文件锁 | PostgreSQL + MyBatis-Plus |
| 音频存储 | 本地文件系统 `data/call-records/audio/` | MinIO (已配置 `MinioStorage`) |
| HTTP 层 | JDK `HttpServer` + `CallRecordHttpAdapter` | Spring MVC `@RestController` |
| 异步 worker | 自管理 `ThreadPoolExecutor` + `ScheduledExecutorService` | Spring `@Scheduled` 调度 + `ThreadPoolExecutor` |
| FunASR 客户端 | `java.net.http.HttpClient` | Spring `RestClient` |
| 前端 | `App.java` 内嵌 vanilla HTML/JS | React 18 + TypeScript + Ant Design 5 |
| 认证 | `X-WeCom-Viewer-Auth` header 解析 | 现有 `SecurityUtil.currentUserId()` |

## 2. 目标与非目标

### 2.1 目标

- 将电话录音上传、FunASR 转录、音频播放、人工修订、备注编辑闭环迁移到 Spring Boot
- 电话记录元数据存入 PostgreSQL，音频文件存入 MinIO
- 电话记录卡片与现有消息在联系人时间线中按发生时间混排
- 提供独立电话仓库页面，支持跨联系人查询、按号码/名称/备注检索
- 转录 worker 在 Spring 生命周期内启动/停止，支持租约、重试、启动恢复
- 音频播放保持短时 Cookie 会话机制，MinIO 音频通过服务端代理或预签名 URL 提供
- 前端提供完整的电话记录上传、列表、详情、播放、修订、备注编辑 UI

### 2.2 非目标

- 不从浏览器直接录音
- 不接电话系统、SIP、呼叫中心或通话结束回调
- 不做说话人分离、摘要、情绪分析
- 不把电话记录伪装成渠道消息写入 `messages` 表
- 不改变 FunASR 协议、音频上限、转录状态机合同
- 不实现电话记录删除（缺少权限 owner）

## 3. 数据库设计

### 3.1 新建表 `call_records`

```sql
CREATE TABLE call_records (
    id                  UUID PRIMARY KEY,
    contact_anchor_point_id VARCHAR(512) NOT NULL,
    phone_point_id      VARCHAR(512) NOT NULL,
    direction           VARCHAR(16) NOT NULL CHECK (direction IN ('inbound', 'outbound')),
    occurred_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(128) NOT NULL,
    client_request_id   VARCHAR(255) NOT NULL,
    note                TEXT NOT NULL DEFAULT '',

    -- audio asset
    audio_relative_path VARCHAR(512) NOT NULL,
    audio_original_file_name VARCHAR(255) NOT NULL,
    audio_size_bytes    BIGINT NOT NULL,
    audio_sha256        VARCHAR(64) NOT NULL,
    audio_content_type  VARCHAR(128) NOT NULL DEFAULT 'audio/mpeg',
    audio_duration_seconds DOUBLE PRECISION NOT NULL,
    audio_object_key    VARCHAR(255) NOT NULL,

    -- transcription
    transcription_state VARCHAR(32) NOT NULL DEFAULT 'queued'
        CHECK (transcription_state IN ('queued', 'processing', 'completed', 'failed')),
    transcription_model VARCHAR(128),
    transcription_attempts INT NOT NULL DEFAULT 0,
    transcription_lease_id VARCHAR(64),
    transcription_lease_worker_id VARCHAR(128),
    transcription_lease_expires_at TIMESTAMPTZ,
    transcription_next_attempt_at TIMESTAMPTZ,
    transcription_result_model VARCHAR(128),
    transcription_result_duration_seconds DOUBLE PRECISION,
    transcription_result_original_text TEXT,
    transcription_result_segments JSONB,
    transcription_result_completed_at TIMESTAMPTZ,
    transcription_error_code VARCHAR(128),
    transcription_error_message VARCHAR(2048),
    transcription_error_retryable BOOLEAN,

    current_revision_id UUID,
    version             BIGINT NOT NULL DEFAULT 1,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_call_records_anchor ON call_records (contact_anchor_point_id);
CREATE INDEX idx_call_records_phone ON call_records (phone_point_id);
CREATE INDEX idx_call_records_occurred ON call_records (occurred_at DESC, id DESC);
CREATE INDEX idx_call_records_state ON call_records (transcription_state);
CREATE UNIQUE INDEX idx_call_records_idempotency
    ON call_records (contact_anchor_point_id, client_request_id);
```

### 3.2 新建表 `call_transcript_revisions`

```sql
CREATE TABLE call_transcript_revisions (
    id              UUID PRIMARY KEY,
    call_record_id  UUID NOT NULL REFERENCES call_records(id),
    text            TEXT NOT NULL,
    edited_at       TIMESTAMPTZ NOT NULL,
    edited_by       VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_call_revisions_record ON call_transcript_revisions (call_record_id);
```

### 3.3 设计理由

- **单表存储**：电话记录元数据、音频资产和转录状态存于一张表，避免 1:1 JOIN。转录结果的分段（segments）使用 jsonb 存储，MySQL 不可用时需改用 text/json。
- **version 列**：沿用原有乐观锁语义，WHERE version = #{expectedVersion} 阻断并发丢更新。
- **修订一致性**：新增修订记录与更新 `call_records.current_revision_id` 必须位于同一事务，主记录更新语句必须持久化新的修订 ID。
- **幂等索引**：`(contact_anchor_point_id, client_request_id)` 唯一约束保证同一请求重复提交返回同一记录。
- **不存联系人名称**：联系人 displayName 从 `contacts` 表实时读取，电话记录不冗余存储可推导的展示字段。
- **音频双路径**：`audio_relative_path` 保留原始相对路径用于兼容/迁移对账，`audio_object_key` 为 MinIO 的 object key。

## 4. 架构与组件职责

```text
React Frontend
  ├── CallRecordUploadForm      电话录音上传（multipart + 表单字段）
  ├── CallRecordCard            时间线中的电话卡片
  ├── CallRecordDetailPanel     右侧详情（播放器 + 转录 + 修订 + 备注）
  └── PhoneRepositoryPage       独立电话仓库列表

Spring Boot Backend
  ├── CallRecordController      REST 端点
  ├── CallRecordService         业务逻辑、状态机、归属校验
  ├── CallRecordMapper          MyBatis-Plus mapper
  ├── CallRecordRepository      DB-backed CallRecordRepository 实现
  ├── TranscriptionWorker       异步转录调度 + 执行
  ├── FunAsrClient              FunASR HTTP client
  ├── CallAudioSessionService   内存短时播放会话
  ├── MinioAudioStore           音频存储 adapter（封装 MinioStorage）
  └── ContactTimelineService    消息+电话记录混排时间线
```

| 组件 | 唯一职责 | 禁止拥有的语义 |
|---|---|---|
| `CallRecordController` | HTTP 参数映射、multipart 解析、认证、错误投影 | 状态机、文件规则、FunASR 协议 |
| `CallRecordService` | 创建、归属校验、状态转换、重试、人工修订、备注 | multipart 解析、FunASR JSON 细节 |
| `CallRecordMapper` | SQL 映射、游标分页、状态查询 | 业务状态推断 |
| `TranscriptionWorker` | 租约、有限重试、调用 adapter、结果提交 | 联系人绑定、修订稿 |
| `FunAsrClient` | OpenAI 兼容 multipart 请求与响应映射 | 重试策略、任务状态 |
| `MinioAudioStore` | MinIO 上传、下载、Range 读取、临时文件管理 | 联系人归属、转录状态 |
| `CallAudioSessionService` | 短时会话创建、校验、过期清理、actor/全局上限 | 音频内容、文件路径 |
| `ContactTimelineService` | 消息和电话记录的稳定排序、分页与 revision | 修改电话记录或消息 |

## 5. HTTP 合同

沿用 `/api/v1/` 前缀的电话合同，与现有 `/api/` 前缀的消息 API 共存：

```text
POST   /api/v1/contacts/{contactId}/call-records       上传电话录音 (multipart)
GET    /api/v1/contacts/{contactId}/timeline            联系人时间线（消息+电话混排）
GET    /api/v1/call-records/{callRecordId}              电话记录详情
POST   /api/v1/call-records/{callRecordId}/audio-sessions  创建播放会话
GET    /api/v1/call-records/{callRecordId}/audio        音频内容（需 Cookie）
POST   /api/v1/call-records/{callRecordId}/retry        人工重试失败转录
PATCH  /api/v1/call-records/{callRecordId}/transcript   人工修订转录稿
PATCH  /api/v1/call-records/{callRecordId}/note         编辑备注
POST   /api/v1/phone-contacts                           绑定电话联系人
GET    /api/v1/phone-repository                         电话仓库查询
```

### 5.1 变更说明

- **创建**：`phonePointId` 和 `contactId` 均为必填，`note` 可选
- **详情**：使用 `SecurityUtil.currentUserId()` 替代 `X-WeCom-Viewer-Auth` header
- **音频播放**：服务端从 MinIO 读取、支持 HTTP Range，通过 Cookie 鉴权。不再暴露内部 object key。
- **浏览器写请求**：CORS 必须允许带凭证的 `PATCH`，并由 Spring Security 放行 `OPTIONS` 预检，否则人工修订请求会在进入 Controller 前返回 `Invalid CORS request`。
- **时间线**：在现有 `/api/threads` 基础上，增加 `/api/v1/contacts/{contactId}/timeline` 端点混合返回消息和电话卡片，或扩展 `ThreadResponse` 支持 `callRecord` 类型的 item

### 5.2 时间线 item 格式

```json
{
  "type": "message | callRecord",
  "occurredAt": "2026-08-05T10:00:00Z",
  "sortId": "...",
  "payload": { "..." }
}
```

## 6. 音频存储

### 6.1 MinioAudioStore

封装 `MinioStorage`，提供电话录音专用接口：

```java
public interface AudioStore {
    StagedAudio stage(InputStream source, String originalFileName, String contentType);
    AudioAsset publish(UUID callRecordId, StagedAudio staged);
    InputStream open(AudioAsset asset);
    void discard(StagedAudio staged);
    void delete(AudioAsset asset);
}
```

- **stage**: 流式写入临时文件（与原始 LocalAudioStore 相同），校验 MP3 签名/大小/时长
- **publish**: 上传到 MinIO，获取 object key，清理临时文件
- **open**: 从 MinIO 下载到临时文件或直接返回 InputStream（小文件可全内存）
- **临时文件目录**: `data/call-records/tmp/`，进程启动时清理

### 6.2 关键决策

- **保留临时文件阶段**：MP3 校验（mp3agic）需要 File 而非 InputStream，因此 stage 阶段仍需本地临时文件。publish 后立即清理。
- **不直接流式上传到 MinIO**：MinIO SDK 支持 InputStream 上传，但 FunASR 转录需要本地文件路径。可以在 publish 时同时上传 MinIO 并保留本地副本供 worker 使用，转录完成后再清理本地副本。或 worker 从 MinIO 下载到临时文件再传给 FunASR。
- **推荐方案**：publish 时上传 MinIO 并保留本地文件（与现有 audio/ 目录一致）。worker 使用本地文件路径调用 FunASR。转录完成后可选择性清理本地副本（保留 MinIO 为真源）。长期来看可配置 `CALL_RECORD_LOCAL_AUDIO_DIR` 为可选缓存。

## 7. 转录 Worker

### 7.1 Spring 集成

```java
@Component
public class TranscriptionWorker {
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    // ...

    @PostConstruct
    public void start() {
        // 恢复遗留 processing 记录
        callRecordMapper.recoverProcessing(clock.instant());
        scheduler.scheduleWithFixedDelay(this::dispatch, 0, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
```

### 7.2 与原始实现差异

- 使用 `@PostConstruct` / `@PreDestroy` 管理生命周期（替代 `CallRecordRuntime.open/close`）
- Worker 仍是自管理线程池，不依赖 Spring `@Async` 或 `@Scheduled`（保持租约和背压语义）
- 通过 `CallRecordMapper` 的 DB 查询替代 `FileCallRecordRepository.listRunnable/recoverProcessing`
- 转录结果写 DB（`UPDATE call_records SET transcription_state = 'completed', ... WHERE id = ? AND version = ?`）

### 7.3 状态机

状态机逻辑不变，完全保留 `CallRecordStateMachine` 的纯函数转换：

```text
queued -> processing -> completed
          |         \-> failed
          \-> queued             transient retry
failed -> queued                 manual retry
```

## 8. 音频播放安全

### 8.1 CallAudioSessionService

保留内存实现，与原始完全一致：

- `POST /audio-sessions` 创建会话，返回 `HttpOnly; SameSite=Strict` Cookie
- Cookie 路径限定为 `/api/v1/call-records/{callRecordId}/audio`
- TTL 默认 300 秒，每 actor 上限 8，全局上限 256
- 服务重启后所有会话失效，前端自动重建

### 8.2 音频 GET 代理

```
GET /api/v1/call-records/{callRecordId}/audio
Cookie: mc_call_audio=<token>
Range: bytes=0-65535
```

1. 校验 Cookie 与 `callRecordId` 双向绑定
2. 从 `call_records` 表获取 `audio_object_key`
3. 从 MinIO 读取音频，支持 HTTP Range（206 Partial Content）
4. 不暴露 MinIO 内部 URL 或 object key

### 8.3 预签名 URL（可选简化）

也可直接返回 MinIO 预签名 URL（有效期与 session TTL 一致），让浏览器直接从 MinIO 拉取音频，绕过服务端代理。优点：节省服务端带宽；缺点：暴露内部 MinIO endpoint。

**推荐**：先用服务端代理方案，音频文件通常 < 100 MiB，Nginx/Spring 可承受。

## 9. 联系人时间线混排

### 9.1 方案：扩展现有 ThreadController

在现有 `/api/threads`（cursor 分页、channelType 过滤）基础上，新增 `/api/v1/contacts/{contactId}/timeline` 端点：

```java
@GetMapping("/api/v1/contacts/{contactId}/timeline")
public TimelineResponse timeline(
        @PathVariable String contactId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit) {
    return contactTimelineService.page(contactId, cursor, limit);
}
```

### 9.2 排序

固定排序：`occurredAt ASC, typeRank ASC, sortId ASC`（消息 typeRank=0，电话记录 typeRank=1）

### 9.3 线程 revision

包含消息时间戳、电话记录 version，使轮询能检测电话状态变化。

## 10. 前端 UI

### 10.1 新增页面/组件

| 组件 | 路径 | 说明 |
|---|---|---|
| `PhoneRepositoryPage` | `frontend/src/pages/PhoneRepositoryPage.tsx` | 独立电话仓库，搜索+分页列表 |
| `CallRecordUploadForm` | `frontend/src/components/CallRecordUploadForm.tsx` | 上传表单：联系人选择、号码选择、方向、时间、备注、MP3 |
| `CallRecordCard` | `frontend/src/components/CallRecordCard.tsx` | 时间线内电话卡片 |
| `CallRecordDetail` | `frontend/src/components/CallRecordDetail.tsx` | 右侧详情：播放器、转录、分段、修订、备注 |

### 10.2 路由

- `/phone-repository` — 电话仓库页面
- 上传入口：在 `SendForm.tsx` 中新增 "电话记录" tab，或作为独立按钮
- 详情：在 `AppLayout.tsx` 的右侧 Sider 中，根据选中类型渲染 `CallRecordDetail`

### 10.3 关键交互

- 上传表单：联系人可搜索选择（已有联系人或新建）、号码可搜索选择或手动输入、MP3 文件选择器、上传进度
- 电话卡片：紧凑展示方向图标、号码、时长、状态
- 右侧详情：原生 `<audio>` 播放器、时间戳分段列表、机器原文/当前修订稿切换、备注编辑
- 音频播放前先调用 `/audio-sessions` 创建播放会话；会话就绪状态按 `callRecordId` 隔离，只有当前记录会话创建成功后才设置 `<audio src>`
- 每 4 分钟续期播放会话；详情关闭时停止续期

## 11. 错误语义

沿用原始设计的稳定错误码：

| code | HTTP | 含义 | 自动重试 |
|---|---|---|---|
| `INVALID_MP3` | 400 | 文件签名、结构或 MIME 无效 | 否 |
| `AUDIO_TOO_LARGE` | 413 | 超过 100 MiB | 否 |
| `AUDIO_TOO_LONG` | 400 | 超过 2 小时 | 否 |
| `CONTACT_BINDING_INVALID` | 400 | 联系人或号码不属于当前联系人组 | 否 |
| `PHONE_CONTACT_REQUIRED` | 400 | 联系人或电话号码缺失 | 否 |
| `PHONE_NUMBER_INVALID` | 400 | 号码格式错误 | 否 |
| `PHONE_POINT_CONFLICT` | 409 | 号码属于其他联系人 | 否 |
| `CALL_RECORD_NOT_FOUND` | 404 | 记录不存在 | 否 |
| `CALL_RECORD_STATE_INVALID` | 409 | 状态不允许此操作 | 否 |
| `CALL_RECORD_VERSION_CONFLICT` | 409 | 并发版本冲突 | 否 |
| `TRANSCRIPTION_QUEUE_FULL` | 429 | 待处理队列已满 | 否 |
| `FUNASR_UNAVAILABLE` | 503 | sidecar 不可用 | 是 |
| `FUNASR_TIMEOUT` | 504 | 转录超时 | 是 |
| `FUNASR_REJECTED` | 502 | sidecar 4xx 拒绝 | 否 |
| `FUNASR_INVALID_RESPONSE` | 502 | 响应格式不符合合同 | 否 |
| `TRANSCRIPT_VERSION_CONFLICT` | 409 | 修订并发冲突 | 否 |
| `AUTH_REQUIRED` | 401 | 无法取得认证身份 | 否 |
| `AUDIO_SESSION_EXPIRED` | 401 | 播放会话失效 | 否 |
| `AUDIO_RANGE_INVALID` | 416 | Range 无效 | 否 |

## 12. 配置

Spring Boot `application.yml` 新增：

```yaml
call-record:
  data-dir: data/call-records
  max-audio-bytes: 104857600       # 100 MiB
  max-duration-seconds: 7200        # 2 hours
  storage-max-bytes: 10737418240    # 10 GiB (本地临时+缓存)
  max-records: 10000
  queue-capacity: 64
  worker-concurrency: 1
  lease-seconds: 2100               # 35 minutes
  max-attempts: 3
  max-response-bytes: 10485760      # 10 MiB
  max-segments: 20000
  max-revisions: 20
  audio-session-ttl-seconds: 300
  audio-session-max-per-actor: 8
  audio-session-max-active: 256

funasr:
  base-url: http://funasr:8000
  model: sensevoice
  connect-timeout: 3s
  request-timeout: 1800s           # 30 minutes
```

## 13. 文件变更清单

### Backend 新建

| 文件 | 说明 |
|---|---|
| `entity/CallRecordEntity.java` | MyBatis-Plus 实体，映射 `call_records` 表 |
| `entity/CallTranscriptRevisionEntity.java` | MyBatis-Plus 实体，映射 `call_transcript_revisions` 表 |
| `mapper/CallRecordMapper.java` | MyBatis-Plus mapper |
| `mapper/CallTranscriptRevisionMapper.java` | MyBatis-Plus mapper |
| `dto/request/CreateCallRecordRequest.java` | 上传请求 DTO |
| `dto/response/CallRecordResponse.java` | 详情响应 DTO |
| `dto/response/TimelineResponse.java` | 时间线响应 DTO |
| `service/callrecord/CallRecordService.java` | 核心业务逻辑 |
| `service/callrecord/CallRecordStateMachine.java` | 纯状态转换函数 |
| `service/callrecord/MinioAudioStore.java` | MinIO 音频存储 adapter |
| `service/callrecord/TranscriptionWorker.java` | 异步转录 Worker |
| `service/callrecord/FunAsrClient.java` | FunASR HTTP client |
| `service/callrecord/CallAudioSessionService.java` | 内存播放会话 |
| `service/callrecord/ContactTimelineService.java` | 消息+电话混排时间线 |
| `service/callrecord/PhoneRepositoryService.java` | 电话仓库查询 |
| `web/CallRecordController.java` | REST controller |
| `config/CallRecordConfig.java` | 配置属性 |

### Backend 修改

| 文件 | 说明 |
|---|---|
| `db/migration/V2__call_records.sql` | Flyway 迁移 |

### Frontend 新建

| 文件 | 说明 |
|---|---|
| `pages/PhoneRepositoryPage.tsx` | 电话仓库页面 |
| `components/CallRecordUploadForm.tsx` | 上传表单 |
| `components/CallRecordCard.tsx` | 电话卡片 |
| `components/CallRecordDetail.tsx` | 详情面板 |

### Frontend 修改

| 文件 | 说明 |
|---|---|
| `App.tsx` | 注册 `/phone-repository` 路由 |
| `AppLayout.tsx` | 导航增加"电话仓库"入口；右侧面板支持电话详情 |
| `components/SendForm.tsx` | 新增"电话记录"tab |
| `api/endpoints.ts` | 新增电话 API 函数 |
| `api/types.ts` | 新增电话相关类型 |

## 14. 迁移步骤

### Phase 1: 后端核心（DB + Service + Worker）

1. 创建 Flyway 迁移（`call_records` + `call_transcript_revisions` 表）
2. 创建 `CallRecordEntity` / `CallTranscriptRevisionEntity`
3. 创建 `CallRecordMapper` / `CallTranscriptRevisionMapper`
4. 移植 `CallRecordStateMachine`（无外部依赖，直接复制）
5. 移植 `FunAsrClient`（改用 `RestClient`）
6. 创建 `MinioAudioStore`（封装 `MinioStorage` + mp3agic 校验）
7. 创建 `CallRecordService`
8. 创建 `TranscriptionWorker`（Spring 生命周期管理）
9. 创建 `CallAudioSessionService`（直接复制，无外部依赖）
10. 创建 `CallRecordController`

### Phase 2: 时间线集成

11. 创建 `ContactTimelineService`
12. 新增 `/api/v1/contacts/{contactId}/timeline` 端点
13. 创建 `PhoneRepositoryService`
14. 新增 `/api/v1/phone-contacts` 和 `/api/v1/phone-repository` 端点

### Phase 3: 前端

15. 新增 API types 和 endpoints
16. 创建 `PhoneRepositoryPage`
17. 创建 `CallRecordUploadForm`
18. 创建 `CallRecordCard`
19. 创建 `CallRecordDetail`
20. 修改 `SendForm` 增加电话 tab
21. 修改 `AppLayout` 注册路由和导航

## 15. 验收

- 上传中文 MP3 → 排队 → 转录完成 → 详情查看分段
- 音频播放、seek、暂停正常
- 人工修订保存成功，版本冲突正确返回 409
- 备注编辑成功
- 时间线中电话卡片与消息按时间混排
- 电话仓库按号码/名称检索正常
- 重启后 `processing` 状态记录恢复为 `queued`
- FunASR 不可用时记录进入 `failed`，恢复后人工重试成功
- 联系人合并后电话记录跟随号码进入合并后的时间线
- 现有消息/联系人功能不回归
