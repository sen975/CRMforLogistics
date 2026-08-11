# WhatsApp 模板媒体上传持久幂等实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 WhatsApp 模板媒体上传具备持久幂等和响应丢失后的可查询状态，并保证 CAMS/OSS 调用期间不持有数据库事务。

**Architecture:** `WhatsAppTemplateMediaUploadService` 保持在 `whatsapp-template` owner 内，编排有界文件校验、一次持久化预留、一次复合 provider 尝试和终态落库。`WhatsAppTemplateMediaUploadStore` 拥有短 `REQUIRES_NEW` 事务；PostgreSQL 通过 `(channel_account_id, client_request_id)` 仲裁并发，Controller 和 React UI 只映射、展示结构化状态。

**Tech Stack:** Java 17、Spring Boot 3.4.5、Spring Transaction、MyBatis-Plus 3.5.10、Flyway、PostgreSQL 17.5/Testcontainers、React 18.3、TypeScript 5.6、Axios 1.7、Vitest 4.1、Testing Library、Ant Design 5.24。

## Global Constraints

- `V9__whatsapp_template_lifecycle.sql` 不可修改；所有 schema 变更进入 `V10__whatsapp_template_media_idempotency.sql`。
- 同一 `(channelAccountId, clientRequestId)` 最多发起一次复合上传尝试；不得宣称 exactly-once。
- `templates/sync` 是只读对账，不得接受 `clientRequestId`。
- 不得使用 JVM Map、进程内锁或前端状态充当幂等 owner。
- `GetChatappUploadAuthorization` 或 OSS PUT 期间不得持有数据库事务。
- 文件上限保持 IMAGE 5 MiB、VIDEO 16 MiB、DOCUMENT 64 MiB，multipart 请求总上限保持现有 65 MiB。
- 超过 90 秒的 `PROCESSING` 在不调用 provider 的情况下收敛为 `SUBMISSION_UNKNOWN`。
- 浏览器每 2 秒查询一次，最多 45 次，绝不自动创建替代请求 ID。
- 不修改 `application-dev.yml`、不新增依赖、不记录 provider 凭据，未获平台管理员批准不得调用真实 CAMS 写接口。
- 保留所有无关 dirty-worktree 改动；每个任务只暂存列出的精确文件。

---

## 文件映射

- `backend/src/main/resources/db/migration/V10__whatsapp_template_media_idempotency.sql`：单向 schema 迁移和 V9 行回填。
- `backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java`：持久化上传请求与终态。
- `backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java`：原子预留与条件状态转换。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadStore.java`：短事务边界与上传审计写入。
- `backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadService.java`：校验、指纹、重放判断和 provider 编排。
- `backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`：multipart 与状态查询 HTTP 映射。
- `frontend/src/api/endpoints.ts` 和 `frontend/src/api/types.ts`：传输合同。
- `frontend/src/components/templates/templateMediaUpload.ts`：不私造业务状态的有界响应丢失恢复。
- `frontend/src/components/templates/TemplateEditorDrawer.tsx`：稳定请求 ID 生命周期和用户可见上传状态。

### Task 1：持久化媒体上传请求合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V10__whatsapp_template_media_idempotency.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppTemplateMediaMigrationTest.java`

**Interfaces:**
- Consumes：V9 `template_media_assets` 行和现有账号外键。
- Produces：供 Task 2 使用的 `insertProcessing(TemplateMediaAssetEntity)`、`findByClientRequestId(UUID,String)`、`markUploaded(UUID,String,String,Instant)`、`markFailed(UUID,String,String,Instant)`、`markUnknown(UUID,String,String,Instant)` 和 `expireProcessing(UUID,Instant,Instant)`。

- [ ] **Step 1：编写迁移 RED 测试**

创建 PostgreSQL 17.5 Testcontainers 测试：先把独立 schema 迁移到 V9，插入一条旧素材，再迁移到最新版本：

```java
@Testcontainers
class WhatsAppTemplateMediaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center").withUsername("test").withPassword("test");

    @Test
    void v10BackfillsLegacyRowsAndEnforcesAccountScopedRequestIds() {
        String schema = "media_v10";
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("9")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID accountId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".channel_accounts "
                + "(id, channel_type, name, account_identifier, account_identifier_normalized, auth_status, encrypted_config) "
                + "values (?, 'chatapp', 'Media test', ?, ?, 'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, provider_object_key, provider_url, media_format, content_type, "
                + "size_bytes, sha256, asset_status, created_at) values (?, ?, 'legacy/key', "
                + "'https://provider.invalid/legacy', 'IMAGE', 'image/png', 4, ?, 'UPLOADED', ?)",
                assetId, accountId, "0".repeat(64), Instant.parse("2026-08-10T00:00:00Z"));

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        Map<String, Object> row = jdbc.queryForMap("select client_request_id, started_at, updated_at "
                + "from " + schema + ".template_media_assets where id = ?", assetId);
        assertThat(row.get("client_request_id")).isEqualTo("legacy:" + assetId);
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".template_media_assets "
                + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                + "asset_status, started_at, created_at, updated_at) values (?, ?, ?, 'IMAGE', 'image/png', 4, ?, "
                + "'PROCESSING', now(), now(), now())", UUID.randomUUID(), accountId,
                "legacy:" + assetId, "1".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2：运行迁移测试并确认 RED**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest=WhatsAppTemplateMediaMigrationTest test`

预期：FAIL，因为 Flyway 尚未找到 V10，`client_request_id` 也不存在。

- [ ] **Step 3：增加带显式回填和约束的 V10**

按以下合同创建迁移：

```sql
ALTER TABLE template_media_assets ADD COLUMN client_request_id varchar(255);
ALTER TABLE template_media_assets ADD COLUMN error_code varchar(100);
ALTER TABLE template_media_assets ADD COLUMN error_message text;
ALTER TABLE template_media_assets ADD COLUMN trace_id varchar(100);
ALTER TABLE template_media_assets ADD COLUMN started_at timestamptz;
ALTER TABLE template_media_assets ADD COLUMN updated_at timestamptz;

ALTER TABLE template_media_assets ALTER COLUMN provider_object_key DROP NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN provider_url DROP NOT NULL;

UPDATE template_media_assets
SET client_request_id = 'legacy:' || id::text,
    started_at = created_at,
    updated_at = COALESCE(attached_at, created_at);

ALTER TABLE template_media_assets ALTER COLUMN client_request_id SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN started_at SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN started_at SET DEFAULT now();
ALTER TABLE template_media_assets ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE template_media_assets DROP CONSTRAINT ck_template_asset_status;
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_asset_status CHECK
    (asset_status IN ('PROCESSING','UPLOADED','FAILED','SUBMISSION_UNKNOWN',
                      'ATTACHED','ATTACHMENT_UNKNOWN','ORPHANED'));
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_media_request_id CHECK
    (char_length(client_request_id) BETWEEN 1 AND 255);
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_media_provider_result CHECK
    (asset_status IN ('PROCESSING','FAILED','SUBMISSION_UNKNOWN')
     OR (provider_object_key IS NOT NULL AND provider_url IS NOT NULL));

CREATE UNIQUE INDEX ux_template_media_assets_request
ON template_media_assets(channel_account_id, client_request_id);
```

- [ ] **Step 4：用精确状态转换方法扩展实体和 Mapper**

增加以下实体字段及普通 getter/setter：

```java
private String clientRequestId;
private String errorCode;
private String errorMessage;
private String traceId;
private Instant startedAt;
private Instant updatedAt;
```

增加以下 Mapper 签名，每个终态更新条件都必须包含 `asset_status = 'PROCESSING'`：

```java
@Insert("""
        insert into template_media_assets
            (id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256,
             asset_status, created_by_user_id, trace_id, started_at, created_at, updated_at)
        values (#{id}, #{channelAccountId}, #{clientRequestId}, #{mediaFormat}, #{contentType},
                #{sizeBytes}, #{sha256}, 'PROCESSING', #{createdByUserId}, #{traceId},
                #{startedAt}, #{createdAt}, #{updatedAt})
        on conflict (channel_account_id, client_request_id) do nothing
        """)
int insertProcessing(TemplateMediaAssetEntity asset);

@Select("select * from template_media_assets where channel_account_id = #{accountId}::uuid "
        + "and client_request_id = #{clientRequestId} limit 1")
Optional<TemplateMediaAssetEntity> findByClientRequestId(UUID accountId, String clientRequestId);

@Update("update template_media_assets set provider_object_key = #{objectKey}, provider_url = #{url}, "
        + "asset_status = 'UPLOADED', updated_at = #{updatedAt} where id = #{id}::uuid "
        + "and asset_status = 'PROCESSING'")
int markUploaded(UUID id, String objectKey, String url, Instant updatedAt);

@Update("update template_media_assets set asset_status = 'FAILED', error_code = #{errorCode}, "
        + "error_message = #{errorMessage}, updated_at = #{updatedAt} where id = #{id}::uuid "
        + "and asset_status = 'PROCESSING'")
int markFailed(UUID id, String errorCode, String errorMessage, Instant updatedAt);

@Update("update template_media_assets set asset_status = 'SUBMISSION_UNKNOWN', error_code = #{errorCode}, "
        + "error_message = #{errorMessage}, updated_at = #{updatedAt} where id = #{id}::uuid "
        + "and asset_status = 'PROCESSING'")
int markUnknown(UUID id, String errorCode, String errorMessage, Instant updatedAt);

@Update("update template_media_assets set asset_status = 'SUBMISSION_UNKNOWN', "
        + "error_code = 'TEMPLATE_MEDIA_SUBMISSION_UNKNOWN', "
        + "error_message = 'Upload result is unknown after process interruption', updated_at = #{updatedAt} "
        + "where id = #{id}::uuid and asset_status = 'PROCESSING' and started_at <= #{cutoff}")
int expireProcessing(UUID id, Instant cutoff, Instant updatedAt);
```

所有多参数方法必须使用显式 `@Param` 名称。更新 `markAttached` 和 `markOrphaned` 以写入 `updated_at`，并保留现有状态保护条件和方法签名。

- [ ] **Step 5：运行持久化验收**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest='WhatsAppTemplateMediaMigrationTest,AppIntegrationTest' test`

预期：PASS；V9 行完成回填，账号内重复请求 ID 被拒绝，完整应用 schema 到达 V10。

- [ ] **Step 6：只提交 Task 1**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V10__whatsapp_template_media_idempotency.sql
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateMediaAssetEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMediaAssetMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WhatsAppTemplateMediaMigrationTest.java
git commit -m "feat: persist whatsapp media upload requests"
```

### Task 2：实现短事务上传状态机

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadStore.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadIntegrationTest.java`

**Interfaces:**
- Consumes：Task 1 的 Mapper 状态转换和现有 `UploadedMedia WhatsAppTemplateGateway.upload(UUID,HeaderFormat,byte[],String,String)`。
- Produces：供 Task 3 使用的 `UploadResult upload(UUID,HeaderFormat,InputStream,long,String,String,String,UUID,String)` 和 `MediaAssetView find(UUID,String)`。

- [ ] **Step 1：编写状态机 RED 测试**

在创建 service 前先定义预期公开 record，并测试重复、指纹冲突、未知和失联行为：

```java
@Test
void sameFingerprintReturnsStoredUploadWithoutCallingGateway() {
    TemplateMediaAssetEntity existing = asset("request-1", "UPLOADED", "a".repeat(64));
    when(store.reserve(any())).thenReturn(new Reservation(existing, false));

    UploadResult result = service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
            new ByteArrayInputStream(new byte[]{1}), 1, "a.png", "image/png",
            "request-1", ACTOR_ID, "trace-2");

    assertThat(result.created()).isFalse();
    assertThat(result.asset().assetStatus()).isEqualTo(MediaAssetStatus.UPLOADED);
    verifyNoInteractions(gateway);
}

@Test
void reusedRequestIdWithDifferentFingerprintFailsClosed() {
    when(store.reserve(any())).thenReturn(new Reservation(
            asset("request-1", "UPLOADED", "0".repeat(64)), false));

    assertThatThrownBy(() -> service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
            new ByteArrayInputStream(new byte[]{1}), 1, "b.png", "image/png",
            "request-1", ACTOR_ID, "trace-2"))
            .isInstanceOf(WhatsAppTemplateException.class)
            .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    verifyNoInteractions(gateway);
}

@Test
void timeoutPersistsUnknownAndDoesNotReplay() {
    TemplateMediaAssetEntity reserved = asset("request-timeout", "PROCESSING",
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7c9b59e3246");
    when(store.reserve(any())).thenReturn(new Reservation(reserved, true));
    when(gateway.upload(any(), any(), any(), any(), any())).thenThrow(new WhatsAppTemplateException(
            "TEMPLATE_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "timeout", Map.of(), null, true));
    when(store.markUnknown(eq(reserved.getId()), any(), any())).thenReturn(
            asset("request-timeout", "SUBMISSION_UNKNOWN", reserved.getSha256()));

    UploadResult result = service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
            new ByteArrayInputStream(new byte[]{1}), 1, "a.png", "image/png",
            "request-timeout", ACTOR_ID, "trace-timeout");

    assertThat(result.asset().assetStatus()).isEqualTo(MediaAssetStatus.SUBMISSION_UNKNOWN);
    verify(gateway, times(1)).upload(any(), any(), any(), any(), any());
}

private static TemplateMediaAssetEntity asset(String requestId, String status, String sha256) {
    TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
    asset.setId(UUID.randomUUID());
    asset.setChannelAccountId(ACCOUNT_ID);
    asset.setClientRequestId(requestId);
    asset.setMediaFormat("IMAGE");
    asset.setContentType("image/png");
    asset.setSizeBytes(1L);
    asset.setSha256(sha256);
    asset.setAssetStatus(status);
    asset.setStartedAt(NOW);
    asset.setCreatedAt(NOW);
    asset.setUpdatedAt(NOW);
    if (!"PROCESSING".equals(status) && !"FAILED".equals(status)
            && !"SUBMISSION_UNKNOWN".equals(status)) {
        asset.setProviderObjectKey("templates/a.png");
        asset.setProviderUrl("https://provider.invalid/a.png");
    }
    return asset;
}
```

- [ ] **Step 2：运行 service 测试并确认 RED**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest=WhatsAppTemplateMediaUploadServiceTest test`

预期：编译 FAIL，因为媒体上传 service、store、状态枚举和结果 record 尚不存在。

- [ ] **Step 3：增加共享状态与 service 接口**

在 `WhatsAppTemplateModels` 中增加枚举：

```java
public enum MediaAssetStatus {
    PROCESSING, UPLOADED, FAILED, SUBMISSION_UNKNOWN,
    ATTACHED, ATTACHMENT_UNKNOWN, ORPHANED
}
```

由 `WhatsAppTemplateMediaUploadService` 暴露以下精确 record 和签名：

```java
public record MediaAssetView(
        UUID id, String clientRequestId, HeaderFormat format, String contentType,
        long sizeBytes, String sha256, String providerUrl, MediaAssetStatus assetStatus,
        String errorCode, String errorMessage, String traceId) { }

public record UploadResult(MediaAssetView asset, boolean created) { }

UploadResult upload(
        UUID accountId, HeaderFormat format, InputStream input, long declaredSize,
        String fileName, String contentType, String clientRequestId,
        UUID actorUserId, String traceId);

MediaAssetView find(UUID accountId, String clientRequestId);
```

content type 依次执行 trim、转小写和移除 `;` 后参数。预留前校验 `clientRequestId` 长度 1..255、声明大小大于零、流式读取大小和 MIME allowlist，再计算 SHA-256。复用现有 `WhatsAppTemplateApplicationService.validateAccount(accountId)` 作为模块账号就绪状态 owner，不复制规则。

两个公开方法都使用 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`，编排实现如下：

```java
public UploadResult upload(UUID accountId, HeaderFormat format, InputStream input, long declaredSize,
                           String fileName, String contentType, String clientRequestId,
                           UUID actorUserId, String traceId) {
    templateApplicationService.validateAccount(accountId);
    String normalizedContentType = normalizeContentType(contentType);
    byte[] bytes = readBoundedAndValidate(format, input, declaredSize, normalizedContentType);
    String digest = sha256(bytes);
    Instant now = clock.instant();
    TemplateMediaAssetEntity candidate = processingAsset(accountId, format, normalizedContentType,
            bytes.length, digest, clientRequestId, actorUserId, traceId, now);
    Reservation reservation = store.reserve(candidate);
    if (!sameFingerprint(reservation.asset(), candidate)) {
        throw new WhatsAppTemplateException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
                "clientRequestId is already bound to different media", Map.of(), null, false);
    }
    if (!reservation.created()) {
        return replay(reservation.asset());
    }
    try {
        UploadedMedia uploaded = gateway.upload(accountId, format, bytes, fileName, normalizedContentType);
        return new UploadResult(view(store.markUploaded(candidate.getId(), uploaded, clock.instant())), true);
    } catch (WhatsAppTemplateException error) {
        TemplateMediaAssetEntity terminal = error.retryable()
                ? store.markUnknown(candidate.getId(), error, clock.instant())
                : store.markFailed(candidate.getId(), error, clock.instant());
        if (!error.retryable()) throw error;
        return new UploadResult(view(terminal), true);
    }
}

public MediaAssetView find(UUID accountId, String clientRequestId) {
    templateApplicationService.validateAccount(accountId);
    TemplateMediaAssetEntity asset = store.find(accountId, requireRequestId(clientRequestId));
    Instant now = clock.instant();
    if ("PROCESSING".equals(asset.getAssetStatus())
            && !asset.getStartedAt().isAfter(now.minusSeconds(90))) {
        asset = store.expireIfStale(asset.getId(), now.minusSeconds(90), now);
    }
    return view(asset);
}
```

把现有有界读取、MIME allowlist、SHA-256 和媒体校验 helper 从 `WhatsAppTemplateApplicationService` 移入新 service。账号校验继续由 `WhatsAppTemplateApplicationService.validateAccount(UUID)` 持有。`replay(TemplateMediaAssetEntity)` 返回既有非失败状态；持久化 `FAILED` 通过穷举 code 到 HTTP status 的 `storedFailure(String,String)` 抛错，且绝不调用 gateway。

按以下精确语义增加请求构造和重复请求 helper：

```java
private TemplateMediaAssetEntity processingAsset(
        UUID accountId, HeaderFormat format, String contentType, long sizeBytes, String sha256,
        String clientRequestId, UUID actorUserId, String traceId, Instant now) {
    TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
    asset.setId(UUID.randomUUID());
    asset.setChannelAccountId(accountId);
    asset.setClientRequestId(requireRequestId(clientRequestId));
    asset.setMediaFormat(format.name());
    asset.setContentType(contentType);
    asset.setSizeBytes(sizeBytes);
    asset.setSha256(sha256);
    asset.setAssetStatus(MediaAssetStatus.PROCESSING.name());
    asset.setCreatedByUserId(actorUserId);
    asset.setTraceId(traceId);
    asset.setStartedAt(now);
    asset.setCreatedAt(now);
    asset.setUpdatedAt(now);
    return asset;
}

private static boolean sameFingerprint(TemplateMediaAssetEntity left, TemplateMediaAssetEntity right) {
    return Objects.equals(left.getMediaFormat(), right.getMediaFormat())
            && Objects.equals(left.getContentType(), right.getContentType())
            && Objects.equals(left.getSizeBytes(), right.getSizeBytes())
            && MessageDigest.isEqual(left.getSha256().getBytes(StandardCharsets.US_ASCII),
                    right.getSha256().getBytes(StandardCharsets.US_ASCII));
}

private UploadResult replay(TemplateMediaAssetEntity asset) {
    if (MediaAssetStatus.FAILED.name().equals(asset.getAssetStatus())) {
        throw storedFailure(asset.getErrorCode(), asset.getErrorMessage());
    }
    return new UploadResult(view(asset), false);
}

private static MediaAssetView view(TemplateMediaAssetEntity asset) {
    return new MediaAssetView(asset.getId(), asset.getClientRequestId(),
            HeaderFormat.valueOf(asset.getMediaFormat()), asset.getContentType(), asset.getSizeBytes(),
            asset.getSha256(), asset.getProviderUrl(), MediaAssetStatus.valueOf(asset.getAssetStatus()),
            asset.getErrorCode(), asset.getErrorMessage(), asset.getTraceId());
}

private static String requireRequestId(String value) {
    String requestId = value == null ? "" : value.trim();
    if (requestId.isEmpty() || requestId.length() > 255) {
        throw WhatsAppTemplateException.validation(Map.of("clientRequestId", "must contain 1 to 255 characters"));
    }
    return requestId;
}

private static WhatsAppTemplateException storedFailure(String code, String message) {
    HttpStatus status = switch (code == null ? "" : code) {
        case "PROVIDER_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
        case "PROVIDER_PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
        case "PROVIDER_AUTH_FAILED" -> HttpStatus.UNAUTHORIZED;
        default -> HttpStatus.BAD_GATEWAY;
    };
    return new WhatsAppTemplateException(code == null ? "TEMPLATE_MEDIA_UPLOAD_FAILED" : code,
            status, message == null ? "Media upload failed" : message, Map.of(), null, false);
}
```

- [ ] **Step 4：实现事务协作者与重复请求判断**

`WhatsAppTemplateMediaUploadStore` 必须是独立 Spring Bean，以确保代理事务语义生效：

```java
@Service
public class WhatsAppTemplateMediaUploadStore {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(TemplateMediaAssetEntity candidate) {
        boolean created = mediaMapper.insertProcessing(candidate) == 1;
        TemplateMediaAssetEntity stored = created ? candidate
                : mediaMapper.findByClientRequestId(candidate.getChannelAccountId(), candidate.getClientRequestId())
                    .orElseThrow(() -> new IllegalStateException("Reserved media upload is missing"));
        return new Reservation(stored, created);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markUploaded(UUID id, UploadedMedia uploaded, Instant now) {
        mediaMapper.markUploaded(id, uploaded.objectKey(), uploaded.url(), now);
        return required(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markFailed(UUID id, WhatsAppTemplateException error, Instant now) {
        mediaMapper.markFailed(id, error.code(), error.getMessage(), now);
        return required(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity markUnknown(UUID id, WhatsAppTemplateException error, Instant now) {
        mediaMapper.markUnknown(id, "TEMPLATE_MEDIA_SUBMISSION_UNKNOWN", error.getMessage(), now);
        return required(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TemplateMediaAssetEntity expireIfStale(UUID id, Instant cutoff, Instant now) {
        mediaMapper.expireProcessing(id, cutoff, now);
        return required(id);
    }

    @Transactional(readOnly = true)
    public TemplateMediaAssetEntity find(UUID accountId, String clientRequestId) {
        return mediaMapper.findByClientRequestId(accountId, clientRequestId)
                .orElseThrow(() -> new WhatsAppTemplateException("TEMPLATE_MEDIA_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Media upload was not found", Map.of(), null, false));
    }

    private TemplateMediaAssetEntity required(UUID id) {
        return Optional.ofNullable(mediaMapper.selectById(id))
                .orElseThrow(() -> new IllegalStateException("Media upload state disappeared"));
    }

    public record Reservation(TemplateMediaAssetEntity asset, boolean created) { }
}
```

每次 `markUploaded`、`markFailed`、`markUnknown` 或失联收敛更新，都在同一个 `REQUIRES_NEW` 事务内追加一条 `audit_logs`：action 为 `WHATSAPP_TEMPLATE_MEDIA_UPLOAD`，resource type 为 `TEMPLATE_MEDIA_ASSET`，actor 和 trace 来自素材记录，`afterSummaryJsonb` 由 `ObjectMapper.writeValueAsString(Map.of("status", assetStatus, "format", mediaFormat))` 生成。result 使用 `SUCCEEDED`、`FAILED` 或 `SUBMISSION_UNKNOWN`；不得包含文件字节、provider 凭据或 provider 原始载荷。

仅当 `Reservation.created()` 为 true 时，service 才调用 `gateway.upload(UUID,HeaderFormat,byte[],String,String)`。比较 `mediaFormat`、规范化 `contentType`、`sizeBytes` 和通过 `MessageDigest.isEqual` 比较的 `sha256`；不一致时抛出 `409 IDEMPOTENCY_KEY_REUSED`。既有 `FAILED` 根据持久化稳定 code 重建确定性的 `WhatsAppTemplateException`；`PROCESSING`、`SUBMISSION_UNKNOWN` 以及所有成功/附件状态直接返回持久化 view，不重放。

- [ ] **Step 5：增加真实 PostgreSQL 并发与事务测试**

使用 Spring Boot/Testcontainers 上下文和 `@Primary` mock gateway。在 gateway 内阻塞 owner 请求，让重复请求读取已经提交的预留记录：

```java
@Test
void concurrentSameRequestCallsGatewayOnceOutsideTransaction() throws Exception {
    CountDownLatch providerEntered = new CountDownLatch(1);
    CountDownLatch releaseProvider = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(gateway.upload(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        calls.incrementAndGet();
        providerEntered.countDown();
        assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
        return new UploadedMedia("templates/a.png", "https://provider.invalid/a.png",
                HeaderFormat.IMAGE, "image/png", 1,
                "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7c9b59e3246");
    });

    Future<UploadResult> first = executor.submit(() -> upload("same-request"));
    assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
    UploadResult duplicate = upload("same-request");
    assertThat(duplicate.asset().assetStatus()).isEqualTo(MediaAssetStatus.PROCESSING);
    releaseProvider.countDown();
    assertThat(first.get(5, TimeUnit.SECONDS).asset().assetStatus()).isEqualTo(MediaAssetStatus.UPLOADED);
    assertThat(calls).hasValue(1);
}

private UploadResult upload(String clientRequestId) {
    return mediaUploadService.upload(accountId, HeaderFormat.IMAGE,
            new ByteArrayInputStream(new byte[]{1}), 1, "a.png", "image/png",
            clientRequestId, actorUserId, "trace-" + clientRequestId);
}
```

另插入一条已有 91 秒的 `PROCESSING` 行，调用 `find(accountId, clientRequestId)`，断言返回 `SUBMISSION_UNKNOWN` 且 gateway 交互次数为零。

- [ ] **Step 6：运行 Task 2 验收**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest='WhatsAppTemplateMediaUploadServiceTest,WhatsAppTemplateMediaUploadIntegrationTest,WhatsAppTemplateApplicationServiceTest' test`

预期：PASS；并发重复请求只调用一次 gateway，超时可查询为未知，失联处理中记录完成收敛，gateway 观察不到活动事务。

- [ ] **Step 7：只提交 Task 2**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadStore.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateModels.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateMediaUploadIntegrationTest.java
git commit -m "feat: make whatsapp media uploads idempotent"
```

### Task 3：暴露上传与状态查询 HTTP 合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java`

**Interfaces:**
- Consumes：Task 2 的 `WhatsAppTemplateMediaUploadService.UploadResult` 和 `MediaAssetView`。
- Produces：供 Task 4 使用、携带 `clientRequestId` 的 multipart POST，以及仅管理员可用的 GET `/template-media/uploads/{clientRequestId}`。

- [ ] **Step 1：增加 HTTP RED 测试**

在现有 `@WebMvcTest` 中 mock `WhatsAppTemplateMediaUploadService`，断言精确请求/响应合同：

```java
private static final String ADMIN_ID_TEXT = "00000000-0000-0000-0000-000000000002";
private static final String BASE = "/api/v1/channel-accounts/" + ACCOUNT_ID + "/whatsapp";

@Test
@WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
void uploadRequiresRequestIdAndReturnsCreatedForNewSuccess() throws Exception {
    MediaAssetView asset = uploadedView("upload-1", MediaAssetStatus.UPLOADED);
    when(mediaUploadService.upload(eq(ACCOUNT_ID), eq(HeaderFormat.IMAGE), any(), eq(4L),
            eq("header.png"), eq("image/png"), eq("upload-1"), eq(ADMIN_ID), any()))
            .thenReturn(new UploadResult(asset, true));

    mvc.perform(multipart(BASE + "/template-media")
            .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1, 2, 3, 4}))
            .param("format", "IMAGE").param("clientRequestId", "upload-1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clientRequestId").value("upload-1"))
            .andExpect(jsonPath("$.assetStatus").value("UPLOADED"));
}

@Test
@WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
void processingUploadReturnsAcceptedAndCanBeQueried() throws Exception {
    MediaAssetView processing = uploadedView("upload-2", MediaAssetStatus.PROCESSING);
    when(mediaUploadService.upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
            .thenReturn(new UploadResult(processing, false));
    when(mediaUploadService.find(ACCOUNT_ID, "upload-2")).thenReturn(processing);

    mvc.perform(multipart(BASE + "/template-media")
            .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
            .param("format", "IMAGE").param("clientRequestId", "upload-2"))
            .andExpect(status().isAccepted());
    mvc.perform(get(BASE + "/template-media/uploads/upload-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assetStatus").value("PROCESSING"));
}

private static MediaAssetView uploadedView(String requestId, MediaAssetStatus status) {
    return new MediaAssetView(UUID.randomUUID(), requestId, HeaderFormat.IMAGE, "image/png", 4,
            "0".repeat(64), status == MediaAssetStatus.PROCESSING ? null
                    : "https://provider.invalid/header.png",
            status, null, null, "trace-1");
}
```

把状态 GET 加入匿名用户和销售角色的拒绝列表。增加缺少 `clientRequestId` 的 multipart 请求并断言 `400 BAD_REQUEST`。同时断言 sync 仍不接受请求 ID。

- [ ] **Step 2：运行 Controller 测试并确认 RED**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest=WhatsAppTemplateControllerTest test`

预期：编译 FAIL，因为 Controller 仍依赖 `WhatsAppTemplateApplicationService.uploadMedia`，且尚无状态查询接口。

- [ ] **Step 3：重接 Controller 与动态响应状态**

在现有模板和对账 service 旁注入 `WhatsAppTemplateMediaUploadService`。替换上传方法并增加查询：

```java
@PostMapping("/template-media")
public ResponseEntity<MediaAssetView> uploadMedia(
        @PathVariable UUID accountId,
        @RequestParam HeaderFormat format,
        @RequestParam String clientRequestId,
        @RequestParam("file") MultipartFile file,
        Authentication authentication,
        HttpServletRequest servletRequest) throws IOException {
    String traceId = traceId(servletRequest);
    String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
    try (InputStream input = file.getInputStream()) {
        UploadResult result = mediaUploadService.upload(accountId, format, input, file.getSize(), fileName,
                file.getContentType(), clientRequestId, actorUserId(authentication), traceId);
        HttpStatus status = result.created() && result.asset().assetStatus() == MediaAssetStatus.UPLOADED
                ? HttpStatus.CREATED
                : result.asset().assetStatus() == MediaAssetStatus.PROCESSING
                    || result.asset().assetStatus() == MediaAssetStatus.SUBMISSION_UNKNOWN
                        ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.asset());
    }
}

@GetMapping("/template-media/uploads/{clientRequestId}")
public MediaAssetView findMediaUpload(@PathVariable UUID accountId,
                                      @PathVariable String clientRequestId) {
    return mediaUploadService.find(accountId, clientRequestId);
}
```

记录不存在时抛出 HTTP 404 `TEMPLATE_MEDIA_NOT_FOUND`。请求 ID 指纹冲突时抛出 `409 IDEMPOTENCY_KEY_REUSED`。持久化明确失败必须复现稳定 code/status，且不调用 gateway。

- [ ] **Step 4：在同一可构建改动中删除被替代上传路径**

Controller 改用 `WhatsAppTemplateMediaUploadService` 后，从 `WhatsAppTemplateApplicationService` 删除两个 `uploadMedia` 重载、媒体字节上限常量/helper、SHA-256 helper 和 `MediaAssetView`。保留 `validateAccount(UUID)`、模板附件查询和附件状态转换。把旧媒体大小/上传测试从 `WhatsAppTemplateApplicationServiceTest` 移到 `WhatsAppTemplateMediaUploadServiceTest`，创建/修改附件测试保持不变。

- [ ] **Step 5：让安全 matcher 覆盖根路径和子路径**

用显式根路径和子路径覆盖替换现有精确 matcher：

```java
.requestMatchers(
        "/api/v1/channel-accounts/*/whatsapp/template-media",
        "/api/v1/channel-accounts/*/whatsapp/template-media/**")
    .hasRole("ADMIN")
```

保持现有模板管理 matcher 和通用登录 fallback 不变。

- [ ] **Step 6：运行后端合同验收**

工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest='WhatsAppTemplateControllerTest,WhatsAppTemplateMediaUploadServiceTest,WhatsAppTemplateMediaUploadIntegrationTest,WhatsAppTemplateApplicationServiceTest' test`

预期：PASS；POST/GET 仅管理员可用，POST 状态码反映持久化状态，缺少请求 ID 时绑定失败，sync 继续不携带请求 ID。

- [ ] **Step 7：只提交 Task 3**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerTest.java
git commit -m "feat: expose whatsapp media upload status"
```

### Task 4：在 React 编辑器中使用单一稳定请求 ID 恢复上传

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Create: `demo/message-center-spring/frontend/src/components/templates/templateMediaUpload.ts`
- Create: `demo/message-center-spring/frontend/src/components/templates/templateMediaUpload.test.ts`
- Modify: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`
- Modify: `docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md`

**Interfaces:**
- Consumes：Task 3 的 multipart POST 和上传状态 GET。
- Produces：复用一个请求 ID、最多读取 45 次状态且绝不静默重传的编辑器。

- [ ] **Step 1：编写传输与恢复 RED 测试**

使用 fake timer 和注入的传输函数创建聚焦 Vitest 测试：

```ts
it('polls with the same request id until the upload is available', async () => {
  vi.useFakeTimers();
  const upload = vi.fn().mockResolvedValue(asset('request-1', 'PROCESSING'));
  const find = vi.fn()
    .mockResolvedValueOnce(asset('request-1', 'PROCESSING'))
    .mockResolvedValueOnce(asset('request-1', 'UPLOADED'));

  const resultPromise = recoverTemplateMediaUpload({
    clientRequestId: 'request-1', upload, find, intervalMs: 2_000, maxPolls: 45,
  });
  await vi.advanceTimersByTimeAsync(4_000);

  await expect(resultPromise).resolves.toMatchObject({ assetStatus: 'UPLOADED' });
  expect(upload).toHaveBeenCalledTimes(1);
  expect(find).toHaveBeenNthCalledWith(1, 'request-1');
  expect(find).toHaveBeenNthCalledWith(2, 'request-1');
});

it.each(['FAILED', 'SUBMISSION_UNKNOWN'] as const)(
  'stops without retransmission on %s', async (status) => {
    const upload = vi.fn().mockResolvedValue(asset('request-2', status));
    const find = vi.fn();
    await expect(recoverTemplateMediaUpload({
      clientRequestId: 'request-2', upload, find, intervalMs: 1, maxPolls: 45,
    })).rejects.toMatchObject({ clientRequestId: 'request-2', assetStatus: status });
    expect(upload).toHaveBeenCalledTimes(1);
    expect(find).not.toHaveBeenCalled();
  },
);

it('stops after 45 reads and retains the request id', async () => {
  vi.useFakeTimers();
  const upload = vi.fn().mockRejectedValue(new TypeError('network response lost'));
  const find = vi.fn().mockResolvedValue(asset('request-3', 'PROCESSING'));
  const resultPromise = recoverTemplateMediaUpload({
    clientRequestId: 'request-3', upload, find, intervalMs: 2_000, maxPolls: 45,
  });
  await vi.advanceTimersByTimeAsync(90_000);
  await expect(resultPromise).rejects.toMatchObject({
    clientRequestId: 'request-3', assetStatus: 'PROCESSING', exhausted: true,
  });
  expect(upload).toHaveBeenCalledTimes(1);
  expect(find).toHaveBeenCalledTimes(45);
});

function asset(clientRequestId: string, assetStatus: TemplateMediaAssetStatus): TemplateMediaAsset {
  return {
    id: 'asset-1', clientRequestId, format: 'IMAGE', contentType: 'image/png', sizeBytes: 1,
    sha256: '0'.repeat(64), providerUrl: assetStatus === 'UPLOADED'
      ? 'https://provider.invalid/a.png' : null,
    assetStatus, errorCode: null, errorMessage: null, traceId: 'trace-1',
  };
}
```

更新 `TemplatesPage.test.tsx`，断言 `uploadTemplateMedia` 和每次 `fetchTemplateMediaUpload` 都收到同一个生成 ID。模拟 provider 明确失败并断言不调用 GET；模拟网络错误并断言进入查询。

- [ ] **Step 2：运行前端测试并确认 RED**

工作目录：`demo/message-center-spring/frontend`

运行：`npm run test:ui -- templateMediaUpload.test.ts TemplatesPage.test.tsx`

预期：FAIL，因为恢复模块、状态 endpoint 和请求 ID 参数尚不存在。

- [ ] **Step 3：扩展传输类型和 endpoint 签名**

provider URL 改为可空，并使用完整持久化状态联合类型：

```ts
export type TemplateMediaAssetStatus =
  | 'PROCESSING' | 'UPLOADED' | 'FAILED' | 'SUBMISSION_UNKNOWN'
  | 'ATTACHED' | 'ATTACHMENT_UNKNOWN' | 'ORPHANED';

export interface TemplateMediaAsset {
  id: string;
  clientRequestId: string;
  format: TemplateMediaFormat;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  providerUrl: string | null;
  assetStatus: TemplateMediaAssetStatus;
  errorCode: string | null;
  errorMessage: string | null;
  traceId: string | null;
}
```

修改并增加 endpoint 函数：

```ts
export async function uploadTemplateMedia(
  accountId: string, format: TemplateMediaFormat, file: File, clientRequestId: string,
): Promise<TemplateMediaAsset> {
  const formData = new FormData();
  formData.append('format', format);
  formData.append('file', file);
  formData.append('clientRequestId', clientRequestId);
  const res = await client.post<TemplateMediaAsset>(`${whatsappManagementBase(accountId)}/template-media`, formData);
  return res.data;
}

export async function fetchTemplateMediaUpload(
  accountId: string, clientRequestId: string,
): Promise<TemplateMediaAsset> {
  const encoded = encodeURIComponent(clientRequestId);
  const res = await client.get<TemplateMediaAsset>(
    `${whatsappManagementBase(accountId)}/template-media/uploads/${encoded}`,
  );
  return res.data;
}
```

- [ ] **Step 4：实现有界传输恢复**

`recoverTemplateMediaUpload` 只调用一次 POST。仅当 POST 返回 `PROCESSING` 或失败且没有 HTTP 响应时查询；`SUBMISSION_UNKNOWN` 立即停止。带 `response` 的 Axios 错误属于服务端明确结果，直接重新抛出且不查询。

```ts
import { isAxiosError } from 'axios';
import type { TemplateMediaAsset, TemplateMediaAssetStatus } from '../../api/types';

type RecoveryOptions = {
  clientRequestId: string;
  upload: (clientRequestId: string) => Promise<TemplateMediaAsset>;
  find: (clientRequestId: string) => Promise<TemplateMediaAsset>;
  intervalMs?: number;
  maxPolls?: number;
};

export class MediaUploadStateError extends Error {
  constructor(
    public readonly clientRequestId: string,
    public readonly assetStatus: TemplateMediaAssetStatus,
    public readonly exhausted: boolean,
  ) {
    super(exhausted ? 'Media upload is still processing' : `Media upload stopped in ${assetStatus}`);
  }
}

const isAvailable = (status: TemplateMediaAssetStatus) =>
  status === 'UPLOADED' || status === 'ATTACHED';

const isTerminalFailure = (status: TemplateMediaAssetStatus) =>
  status === 'FAILED' || status === 'SUBMISSION_UNKNOWN'
    || status === 'ATTACHMENT_UNKNOWN' || status === 'ORPHANED';

const delay = (milliseconds: number) =>
  new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds));

export async function recoverTemplateMediaUpload({
  clientRequestId, upload, find, intervalMs = 2_000, maxPolls = 45,
}: RecoveryOptions): Promise<TemplateMediaAsset> {
  let current: TemplateMediaAsset | null = null;
  try {
    current = await upload(clientRequestId);
  } catch (error) {
    if (isAxiosError(error) && error.response) throw error;
  }
  if (current && isAvailable(current.assetStatus)) return current;
  if (current && isTerminalFailure(current.assetStatus)) {
    throw new MediaUploadStateError(clientRequestId, current.assetStatus, false);
  }

  for (let attempt = 0; attempt < maxPolls; attempt += 1) {
    await delay(intervalMs);
    try {
      current = await find(clientRequestId);
    } catch (error) {
      if (isAxiosError(error) && error.response) throw error;
      continue;
    }
    if (isAvailable(current.assetStatus)) return current;
    if (isTerminalFailure(current.assetStatus)) {
      throw new MediaUploadStateError(clientRequestId, current.assetStatus, false);
    }
  }
  throw new MediaUploadStateError(clientRequestId, current?.assetStatus ?? 'PROCESSING', true);
}
```

`isAvailable` 接受 `UPLOADED` 和 `ATTACHED`。`FAILED`、`SUBMISSION_UNKNOWN`、`ATTACHMENT_UNKNOWN` 和 `ORPHANED` 立即停止。只有 `PROCESSING` 可以继续。helper 不得调用 POST 超过一次，也不得生成 ID。

- [ ] **Step 5：让编辑器持有一次请求 ID 生命周期**

把 Drawer prop 修改为：

```ts
uploadMedia: (
  format: 'IMAGE' | 'VIDEO' | 'DOCUMENT', file: File, clientRequestId: string,
) => Promise<TemplateMediaAsset>;
```

只在用户选择文件事件中生成 ID，并在本次请求及恢复路径持续保留：

```ts
const uploadRequestId = useRef<string | null>(null);

async function handleUpload(file: File) {
  if (!headerFormat || headerFormat === 'TEXT') return;
  const clientRequestId = requestId();
  uploadRequestId.current = clientRequestId;
  setUploading(true);
  setUploadError(null);
  try {
    const asset = await uploadMedia(headerFormat, file, clientRequestId);
    setMediaAsset(asset);
    setMediaAssetId(asset.id);
  } catch (error) {
    setUploadError(mediaUploadErrorMessage(error));
  } finally {
    setUploading(false);
  }
}

function mediaUploadErrorMessage(error: unknown): string {
  if (error instanceof MediaUploadStateError) {
    if (error.exhausted) return '素材仍在处理中，请稍后重新查询';
    if (error.assetStatus === 'SUBMISSION_UNKNOWN') return '上传结果未知，请勿自动重传';
    return '素材上传失败，请明确选择文件后重新上传';
  }
  const response = (error as { response?: { data?: { message?: string } } }).response;
  return response?.data?.message ?? '素材上传失败';
}
```

切换 Header 类型、选择替换、关闭 Drawer 或选择其他文件时清除旧展示状态；只有下一次明确选择文件才生成新 ID。明确失败、提交未知和查询耗尽必须展示不同文案。不得增加自动重试按钮。

- [ ] **Step 6：把页面接到 POST 与 GET 恢复流程**

复用现有账号边界并注入两个 endpoint 调用：

```tsx
uploadMedia={(format, file, clientRequestId) => recoverTemplateMediaUpload({
  clientRequestId,
  upload: (stableId) => uploadTemplateMedia(account!.id, format, file, stableId),
  find: (stableId) => fetchTemplateMediaUpload(account!.id, stableId),
})}
```

该上传外层不得启用 React Query mutation retry；恢复 helper 是唯一响应丢失传输循环。

- [ ] **Step 7：运行前端与后端完整验收**

前端工作目录：`demo/message-center-spring/frontend`

运行：`npm test`

运行：`npm run build`

后端工作目录：`demo/message-center-spring/backend`

运行：`mvn -q -Dtest='*Test,!ChatAppIntegrationTest' test`

预期：全部前端 source/UI 测试通过，TypeScript/Vite 构建通过且除已记录 bundle-size warning 外没有新 warning，全部后端测试在不调用真实 CAMS 写接口的情况下通过。

- [ ] **Step 8：用验收证据回写已批准设计**

只有 Step 7 通过后，才把设计文档状态从 `已确认，待实施` 改为 `已实现，待完整生命周期验收`。追加精确命令结果、测试数量、现有 Vite warning 状态，以及是否跳过真实 CAMS 门禁。未授权 provider 验收时不得标记生产就绪。

- [ ] **Step 9：运行 scoped diff 检查并只提交 Task 4**

```bash
git diff --check -- demo/message-center-spring/frontend demo/message-center-spring/backend docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md
git status --short
git diff --cached --name-only
git add demo/message-center-spring/frontend/src/api/types.ts
git add demo/message-center-spring/frontend/src/api/endpoints.ts
git add demo/message-center-spring/frontend/src/components/templates/templateMediaUpload.ts
git add demo/message-center-spring/frontend/src/components/templates/templateMediaUpload.test.ts
git add demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx
git add demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx
git add demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx
git add docs/superpowers/specs/2026-08-10-whatsapp-template-lifecycle-design.md
git commit -m "feat: recover whatsapp media uploads"
```

## Completion Definition

仅当 Tasks 1-4 均已提交、V10 同时通过空库和 V9 旧行升级、并发相同请求在事务外只调用一次上传 gateway、POST/GET 安全合同通过、前端使用一个稳定 ID 且最多读取 45 次、后端/前端完整验收通过，并且文档记录真实 CAMS 验收是否仍受门禁限制时，本子计划才算完成。
