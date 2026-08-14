# 企业微信 Spring 完整迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `demo/message-center-spring` 独立提供可选企业微信登录、已有账号扫码绑定、可靠授权、加密 chatdata、统一时间线和官方 OpenDataFrame 展示，不依赖旧 demo 进程。

**Architecture:** Spring Security 和 `user_sessions` 继续拥有 CRM 登录，`wecom_user_bindings` 拥有企业微信身份映射；授权、凭据、chatdata 和审计以 PostgreSQL 为真源。React 复用现有 Ant Design UI，企业微信登录和消息正文分别使用官方 WWLoginPanel 与 OpenDataFrame。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring MVC、Spring Security、MyBatis-Plus、PostgreSQL 16、Flyway、JUnit 5、Mockito、React 18、TypeScript、Vite、Ant Design、TanStack Query、Vitest、企业微信官方 `wecom-jssdk-2.3.4.js` 与 `jwxwork-1.0.0.js`。

## Global Constraints

- Spring 是唯一企业微信运行面，不启动、不代理、不调用 `demo/message-center-demo`。
- 登录页保留账号密码和企业微信扫码两个并列入口；首次扫码用户默认只获得现有 `agent` 角色。
- 一个 Spring 用户只能绑定一个企业微信身份，一个企业微信身份只能绑定一个 Spring 用户。
- 新增 UI 复用 Spring 现有 Ant Design 组件、token、间距、圆角和响应式布局；企业微信扫码区域必须保持官方组件样式。
- 企业微信原文只由官方 `ww-open-message` 展示；数据库和 React 不保存、伪造或截断原文。
- `permanent_code`、`secret_key`、access token、auth code、suite ticket、AES key 和 modal URL 不得进入普通日志、前端持久存储或错误响应。
- 授权 audit 是 required；viewer audit 是 best effort。
- 所有生产行为先写失败测试并观察正确失败，再写最小实现。
- 当前工作区含大量用户 WIP；禁止 `git add .`，每个任务只精确暂存计划列出的文件。
- 不重新设计角色系统，不增加每日摘要 UI，不引入 Redis 或外部消息队列。

---

### Task 1: 固化数据库身份、授权与迁移合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V16__wecom_spring_completion.sql`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComUserBindingEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserBindingMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationAuditEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComInstallationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComAuthorizationAuditMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/RoleMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WeComSpringCompletionMigrationTest.java`

**Interfaces:**
- Consumes: existing `users`, `roles`, `user_roles`, `wecom_installations`, `wecom_authorization_audit`.
- Produces: `wecom_user_bindings`; installation event/version mutation SQL; audit attempt transition SQL; credential migration marker.

- [ ] **Step 1: Write the failing Flyway contract test**

```java
class WeComSpringCompletionMigrationTest {
    private final String sql = resource("db/migration/V16__wecom_spring_completion.sql").toLowerCase();

    @Test void definesOneToOneWeComBinding() {
        assertThat(sql).contains("create table wecom_user_bindings");
        assertThat(sql).contains("unique (user_id)");
        assertThat(sql).contains("unique (suite_id, auth_corp_id, wecom_user_id)");
        assertThat(sql).contains("provisioning_source");
        assertThat(sql).contains("auto_created").contains("bound_existing");
        assertThat(sql).contains("alter column permanent_code type text");
        assertThat(sql).contains("alter column secret_key type text");
    }

    @Test void extendsAuthorizationStateForReplayAndPendingAudit() {
        assertThat(sql).contains("last_authorization_event_id");
        assertThat(sql).contains("last_authorization_event_at");
        assertThat(sql).contains("event_id");
        assertThat(sql).contains("attempt");
        assertThat(sql).contains("target_status");
        assertThat(sql).contains("expected_version");
        assertThat(sql).contains("'pending'");
    }

    private static String resource(String name) {
        try (var in = WeComSpringCompletionMigrationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: Run the contract test and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComSpringCompletionMigrationTest test`

Expected: FAIL because `V16__wecom_spring_completion.sql` does not exist.

- [ ] **Step 3: Add the schema and mapper contracts**

```sql
CREATE TABLE wecom_user_bindings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    suite_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    wecom_user_id varchar(128) NOT NULL,
    provisioning_source varchar(20) NOT NULL,
    bound_at timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id),
    UNIQUE (suite_id, auth_corp_id, wecom_user_id),
    CONSTRAINT ck_wecom_user_binding_source
        CHECK (provisioning_source IN ('AUTO_CREATED', 'BOUND_EXISTING'))
);

ALTER TABLE wecom_installations
    ALTER COLUMN permanent_code TYPE text;

ALTER TABLE wecom_chatdata_messages
    ALTER COLUMN secret_key TYPE text;

ALTER TABLE wecom_installations
    ADD COLUMN last_authorization_event_id varchar(80),
    ADD COLUMN last_authorization_event_at timestamptz;

ALTER TABLE wecom_authorization_audit
    DROP CONSTRAINT ck_wecom_auth_audit_result,
    ADD COLUMN event_id varchar(80),
    ADD COLUMN attempt integer,
    ADD COLUMN target_status varchar(20),
    ADD COLUMN expected_version bigint,
    ADD CONSTRAINT ck_wecom_auth_audit_result
        CHECK (result IN ('accepted', 'pending', 'succeeded', 'failed')),
    ADD CONSTRAINT ck_wecom_auth_audit_attempt
        CHECK ((event_id IS NULL AND attempt IS NULL)
            OR (event_id IS NOT NULL AND attempt >= 1));

CREATE UNIQUE INDEX ux_wecom_auth_audit_phase
    ON wecom_authorization_audit(event_id, attempt, result)
    WHERE event_id IS NOT NULL;

CREATE TABLE wecom_credential_migration_markers (
    migration_name varchar(100) PRIMARY KEY,
    completed_at timestamptz NOT NULL
);
```

Add mapper methods with these exact signatures:

```java
Optional<WeComUserBindingEntity> findByIdentity(String suiteId, String authCorpId, String wecomUserId);
Optional<WeComUserBindingEntity> findByUserId(UUID userId);
int insert(WeComUserBindingEntity entity);
int touchLogin(UUID id, Instant loggedInAt, long expectedVersion);

int updateForEvent(UUID id, String agentId, String permanentCode, String authStatus,
                   Instant authorizedAt, String eventId, Instant eventAt, long expectedVersion);
int updateStatusForEvent(UUID id, String authStatus, String eventId,
                         Instant eventAt, long expectedVersion);

Optional<RoleEntity> findByCode(String code);
```

- [ ] **Step 4: Run migration and compile checks**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComSpringCompletionMigrationTest test && mvn -q -DskipTests compile`

Expected: PASS with no compilation warnings.

- [ ] **Step 5: Commit Task 1**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V16__wecom_spring_completion.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComUserBindingEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationAuditEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserBindingMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComInstallationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComAuthorizationAuditMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/RoleMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WeComSpringCompletionMigrationTest.java
git commit -m "feat: define spring wecom identity schema"
```

### Task 2: 加密 permanent code 与 chatdata secret key

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialProtector.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationRunner.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComStartupGate.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComCredentialMigrationMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComCredentialRow.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/CredentialCipher.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComInstallationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialProtectorTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationServiceTest.java`

**Interfaces:**
- Consumes: existing `CredentialCipher` envelope API and Task 1 migration marker table.
- Produces: `protectPermanentCode`, `revealPermanentCode`, `protectSecretKey`, `revealSecretKey`; bounded startup migration.

- [ ] **Step 1: Write failing encryption and migration tests**

```java
@Test void protectsAndRevealsTypedSecrets() throws Exception {
    var cipher = CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    var protector = new WeComCredentialProtector(cipher);
    String permanent = protector.protectPermanentCode("permanent-value");
    String secret = protector.protectSecretKey("message-secret");
    assertThat(permanent).doesNotContain("permanent-value");
    assertThat(secret).doesNotContain("message-secret");
    assertThat(protector.revealPermanentCode(permanent)).isEqualTo("permanent-value");
    assertThat(protector.revealSecretKey(secret)).isEqualTo("message-secret");
}

@Test void migratesAtMostTwoHundredRowsPerBatchAndMarksCompletion() {
    when(mapper.nextInstallations(null, 200))
            .thenReturn(List.of(new WeComCredentialRow(INSTALLATION_ID, "plain")), List.of());
    service.migrateAll();
    verify(mapper).replaceInstallation(INSTALLATION_ID, "plain", encrypted("plain"));
    verify(mapper).insertMarker("wecom-credentials-v1");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComCredentialProtectorTest,WeComCredentialMigrationServiceTest test`

Expected: FAIL because the protector and migration service do not exist.

- [ ] **Step 3: Implement typed envelopes and bounded migration**

```java
@Service
public final class WeComCredentialProtector {
    private final CredentialCipher cipher;

    public WeComCredentialProtector(CredentialCipher cipher) { this.cipher = cipher; }

    public String protectPermanentCode(String value) {
        return encrypt("permanentCode", value, 512);
    }
    public String revealPermanentCode(String envelope) {
        return decrypt("permanentCode", envelope, 512);
    }
    public String protectSecretKey(String value) {
        return encrypt("secretKey", value, 512);
    }
    public String revealSecretKey(String envelope) {
        return decrypt("secretKey", envelope, 512);
    }

    private String encrypt(String key, String value, int max) {
        require(value, max);
        try { return cipher.encrypt(Map.of(key, value)); }
        catch (Exception e) { throw new WeComException("WECOM_CREDENTIAL_ENCRYPTION_FAILED", 500, "企业微信凭据加密失败", e); }
    }
    private String decrypt(String key, String envelope, int max) {
        try {
            Map<String, String> values = cipher.decrypt(envelope);
            String value = values.size() == 1 ? values.get(key) : null;
            require(value, max);
            return value;
        } catch (Exception e) {
            throw new WeComException("WECOM_CREDENTIAL_DECRYPTION_FAILED", 500, "企业微信凭据解密失败", e);
        }
    }
    private static void require(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("WECOM_CREDENTIAL_INVALID");
    }
}
```

Add public `CredentialCipher.requireEnvelope(String)` using the same strict four-field AES-256-GCM envelope validation as `decrypt`, without decrypting the payload. `WeComCredentialMigrationMapper` exposes these exact methods; replacement SQL includes the previous value in the `WHERE` clause so concurrent changes cannot be overwritten:

```java
List<WeComCredentialRow> nextInstallations(UUID afterId, int limit);
List<WeComCredentialRow> nextChatDataMessages(UUID afterId, int limit);
int replaceInstallation(UUID id, String expectedValue, String encryptedValue);
int replaceChatDataMessage(UUID id, String expectedValue, String encryptedValue);
boolean markerExists(String migrationName);
int insertMarker(String migrationName);
```

`WeComCredentialMigrationService` iterates UUID-primary-key ordered batches of at most 200 rows. A valid envelope is skipped; non-empty plaintext up to 512 characters is encrypted; empty, overlong, structurally envelope-like but invalid, or concurrently changed values fail with `WECOM_CREDENTIAL_MIGRATION_FAILED`. The marker `wecom-credentials-v1` is written only after both tables complete. `WeComCredentialMigrationRunner` uses `@Order(Ordered.HIGHEST_PRECEDENCE)`, keeps `WeComStartupGate` closed until migration succeeds, and rethrows failures to stop startup. Callback, schedulers and viewer endpoints require that gate before processing. Modify installation/chatdata writes to encrypt before Mapper calls and decrypt only inside installation/viewer service boundaries.

- [ ] **Step 4: Run targeted tests and compilation**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComCredentialProtectorTest,WeComCredentialMigrationServiceTest test && mvn -q -DskipTests compile`

Expected: PASS; tests assert that plaintext is absent from stored values and exception messages.

- [ ] **Step 5: Commit Task 2**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialProtector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationRunner.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComStartupGate.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComCredentialMigrationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComCredentialRow.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/CredentialCipher.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComInstallationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialProtectorTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComCredentialMigrationServiceTest.java
git commit -m "feat: encrypt spring wecom credentials"
```

### Task 3: 实现 required 授权审计与有界回调队列

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationMutationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationRecovery.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationAuditTrail.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComAuthorizationAuditMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationRecoveryTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComControllerTest.java`

**Interfaces:**
- Consumes: Task 1 event/version SQL and Task 2 encrypted installation store.
- Produces: `CallbackAck handle(DecodedCallback)` and asynchronous authorization worker.

- [ ] **Step 1: Write failing state-machine tests**

```java
@Test void acceptedIsRequiredBeforeQueueing() {
    doThrow(new IllegalStateException("db down")).when(audit).begin(callback);
    CallbackAck ack = service.handle(callback);
    assertThat(ack.success()).isFalse();
    verifyNoInteractions(queue, installationStore);
}

@Test void workerWritesPendingThenCommitsVersionedMutationAndSuccess() {
    when(audit.begin(callback)).thenReturn(new Attempt("sha256:" + "a".repeat(64), 1));
    assertThat(service.handle(callback).success()).isTrue();
    worker.runNext();
    InOrder order = inOrder(audit, installationStore);
    order.verify(audit).pending(any(), eq("wwcorp"), eq("ACTIVE"), eq(3L));
    order.verify(installationStore).applyActive(any(), eq(3L));
    order.verify(audit).succeeded(any(), eq("wwcorp"));
}

@Test void queueFullClosesAttemptAndRequestsRetry() {
    when(queue.offer(any())).thenReturn(false);
    assertThat(service.handle(callback).success()).isFalse();
    verify(audit).failed(any(), anyString(), argThat(e -> e.code().equals("WECOM_AUTHORIZATION_QUEUE_FULL")));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAuthorizationServiceTest,WeComAuthorizationRecoveryTest,WeComControllerTest test`

Expected: FAIL because Spring still performs synchronous installation mutation and has no accepted/pending protocol.

- [ ] **Step 3: Implement the authorization owner**

```java
public interface AuthorizationAudit {
    Attempt begin(WeComCallbackCodec.DecodedCallback callback);
    boolean alreadySucceeded(String eventId);
    void pending(Attempt attempt, String corpId, String targetStatus, long expectedVersion);
    void succeeded(Attempt attempt, String corpId);
    void failed(Attempt attempt, String corpId, WeComException failure);
}

public record Attempt(String eventId, int attempt) {}

public record CallbackAck(boolean success) {
    public static CallbackAck accepted() { return new CallbackAck(true); }
    public static CallbackAck retry() { return new CallbackAck(false); }
}

public CallbackAck handle(WeComCallbackCodec.DecodedCallback callback) {
    Attempt attempt;
    try { attempt = audit.begin(callback); }
    catch (RuntimeException e) { return CallbackAck.retry(); }
    if (audit.alreadySucceeded(attempt.eventId())) return CallbackAck.accepted();
    if (!isQueuedMutation(callback)) return processSynchronously(callback, attempt);
    if (!queue.offer(new AuthorizationEvent(callback, attempt))) {
        audit.failed(attempt, callback.authCorpId(), queueFull());
        return CallbackAck.retry();
    }
    return CallbackAck.accepted();
}

private static WeComException queueFull() {
    return new WeComException("WECOM_AUTHORIZATION_QUEUE_FULL", 503,
            "企业微信授权队列已满，请重试");
}
```

`isQueuedMutation` returns true only for delegated-suite `create_auth`, `change_auth` and a `reset_permanent_code` that contains `authCode`, matching the demo owner. `suite_ticket` and `cancel_auth` execute synchronously but still write `accepted -> pending -> succeeded/failed`; cancellation uses the same optimistic version SQL and must never bypass required audit. Unknown events close their attempt as failed.

Use an `ArrayBlockingQueue` sized by `wecomAuthorizationQueueCapacity`, one daemon worker, and `@PreDestroy` bounded shutdown. `WeComAuthorizationMutationService` owns the public `@Transactional` methods that apply installation mutation and final `pending -> succeeded` audit transition in the same database transaction; do not rely on private or self-invoked `@Transactional` methods. It selects only the first valid Agent and records that agent once. `WeComAuthorizationRecovery` runs after credential migration and before callback readiness; unresolved pending attempts close `WeComStartupGate` and reject new mutation events with `WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED`.

- [ ] **Step 4: Run authorization tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAuthorizationServiceTest,WeComAuthorizationRecoveryTest,WeComControllerTest test`

Expected: PASS; callback test asserts `200 success` only for accepted work and `503` for retry.

- [ ] **Step 5: Commit Task 3**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationRecovery.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationAuditTrail.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComInstallationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComAuthorizationAuditMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationRecoveryTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComControllerTest.java
git commit -m "feat: harden spring wecom authorization"
```

### Task 4: 实现企业微信首次建号和已有账号绑定

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserBindingService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginApplicationService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComAuthController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComLoginResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComBindingResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginAttemptService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/UserMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/RoleMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserBindingServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginApplicationServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComAuthControllerSecurityTest.java`

**Interfaces:**
- Consumes: Task 1 binding mapper, `AuthSessionService.issue`, `PasswordEncoder`, existing viewer code exchange.
- Produces: optional login and authenticated binding REST APIs.

- [ ] **Step 1: Write failing account lifecycle tests**

```java
@Test void firstWeComLoginCreatesAgentUserBindingAndSessionAtomically() {
    when(bindingMapper.findByIdentity(SUITE, CORP, USER)).thenReturn(Optional.empty());
    WeComLoginResponse result = service.login(identity, requestMetadata);
    assertThat(result.roles()).containsExactly("AGENT");
    verify(userMapper).insert(argThat(u -> u.getUsername().matches("wecom_[0-9a-f]{24}")));
    verify(roleMapper).insertUserRole(any(), eq(agentRoleId));
    verify(bindingMapper).insert(argThat(b -> b.getWecomUserId().equals(USER)
            && "AUTO_CREATED".equals(b.getProvisioningSource())));
    verify(sessionService).issue(any(), any(), any());
}

@Test void authenticatedUserCanBindButCannotStealExistingIdentity() {
    when(bindingMapper.findByIdentity(SUITE, CORP, USER)).thenReturn(Optional.of(otherBinding));
    assertThatThrownBy(() -> service.bind(currentUserId, identity))
            .isInstanceOf(WeComException.class)
            .extracting("code").isEqualTo("WECOM_IDENTITY_ALREADY_BOUND");
}

@Test void autoCreatedUserCannotRemoveItsOnlyLoginMethod() {
    when(binding.getProvisioningSource()).thenReturn("AUTO_CREATED");
    assertThatThrownBy(() -> service.unbind(user.getId()))
            .extracting("code").isEqualTo("WECOM_LAST_LOGIN_METHOD");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComUserBindingServiceTest,WeComLoginApplicationServiceTest,WeComAuthControllerSecurityTest test`

Expected: FAIL because binding owner and HTTP endpoints do not exist.

- [ ] **Step 3: Implement login purposes, provisioning and binding**

```java
public enum Purpose { LOGIN, BIND }

public record AttemptContext(Purpose purpose, UUID targetUserId,
                             String installationId, long installationVersion,
                             String suiteId, String authCorpId, String agentId) {}
public record BoundIdentity(UUID userId, String suiteId, String authCorpId,
                            String wecomUserId, String provisioningSource,
                            WeComLoginAttemptService.InstallationBinding installationBinding) {}
public record ResolvedLoginIdentity(String suiteId, String authCorpId,
                                    String wecomUserId, String displayName,
                                    WeComLoginAttemptService.InstallationBinding installationBinding) {}
public record RequestMetadata(String remoteAddress, String userAgent) {}
public record WeComLoginResponse(String token, String username, List<String> roles,
                                 String viewerAuthToken, int viewerExpiresIn) {}

@Transactional
public BoundIdentity resolveOrCreate(ResolvedLoginIdentity identity) {
    return bindingMapper.findByIdentity(identity.suiteId(), identity.authCorpId(), identity.wecomUserId())
            .map(this::existingBoundUser)
            .orElseGet(() -> createAgentUser(identity));
}

@Transactional
public WeComBindingResponse bind(UUID currentUserId, ResolvedLoginIdentity identity) {
    bindingMapper.findByIdentity(identity.suiteId(), identity.authCorpId(), identity.wecomUserId())
            .ifPresent(existing -> { throw conflict(existing.getUserId(), currentUserId); });
    bindingMapper.findByUserId(currentUserId)
            .ifPresent(existing -> { throw new WeComException("WECOM_USER_ALREADY_BOUND", 409, "当前账号已绑定企业微信"); });
    bindingMapper.insert(newBinding(currentUserId, identity, "BOUND_EXISTING"));
    return response(currentUserId, identity);
}
```

`createAgentUser` must calculate `wecom_<24 lowercase sha256 hex>`, encode a random 32-byte password with the configured `PasswordEncoder`, insert the user, assign exactly role code `agent`, insert an `AUTO_CREATED` binding, then issue a normal CRM session. Existing-account binding writes `BOUND_EXISTING`; unbind checks this explicit field and never infers account origin from the username. Controller request bodies accept only `{code,state}`; binding target user comes from `SecurityUtil.currentUserId()`.

Extend `WeComLoginAttemptService` so every attempt stores `purpose`, optional `targetUserId`, installation ID/version and expiry. `createLoginAttempt(remoteAddress)` and `createBindingAttempt(currentUserId, remoteAddress)` enforce the existing global pending cap plus a per-source sliding-window limit configured by `app.wecom-login-attempt-rate-limit-per-minute` with default `20`. `consume(state, expectedPurpose, currentUserId)` removes the state before upstream exchange, rejects purpose/user mismatches, and remains idempotent only through the resulting identity binding/session transaction.

Security rules:

```java
.requestMatchers("/api/auth/login", "/api/auth/wecom/attempts", "/api/auth/wecom/exchange").permitAll()
.requestMatchers("/api/account/wecom-binding/**").authenticated()
```

- [ ] **Step 4: Run login and filter-chain tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComUserBindingServiceTest,WeComLoginApplicationServiceTest,WeComAuthControllerSecurityTest,AuthControllerSecurityTest test`

Expected: PASS; tests assert that request-provided user IDs are rejected as undeclared fields.

- [ ] **Step 5: Commit Task 4**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserBindingService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginApplicationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginAttemptService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComAuthController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComLoginResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComBindingResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/UserMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/RoleMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserBindingServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComLoginApplicationServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComAuthControllerSecurityTest.java
git commit -m "feat: add optional spring wecom login"
```

### Task 5: 暴露绑定用户的 viewer HTTP API

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComViewerController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerSessionRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerEventRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerReferenceLeaseRegistry.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/LocalWeComDevelopmentService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComViewerControllerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerServiceTest.java`

**Interfaces:**
- Consumes: current CRM actor, `WeComUserBindingService.requireByUserId`, existing viewer/chatdata services.
- Produces: bootstrap, JS-SDK config, manual sync, session create/read and client event endpoints.

- [ ] **Step 1: Write failing controller tests**

```java
@Test void bootstrapUsesAuthenticatedUserBindingNotRequestIdentity() throws Exception {
    mvc.perform(post("/api/v1/wecom/conversation-view/bootstrap")
            .header("Authorization", "Bearer crm-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewerAuthToken").isString());
    verify(bindingService).requireByUserId(AUTHENTICATED_USER_ID);
}

@Test void sessionCreationRejectsUnboundUser() throws Exception {
    when(bindingService.requireByUserId(any())).thenThrow(new WeComException("WECOM_USER_NOT_BOUND", 403, "账号尚未绑定企业微信"));
    mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
            .header("Authorization", "Bearer crm-token")
            .header("X-WeCom-Viewer-Token", "viewer-token")
            .contentType(APPLICATION_JSON)
            .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"]}"))
        .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComViewerControllerTest,WeComViewerServiceTest test`

Expected: FAIL because no viewer Controller or binding-based bootstrap exists.

- [ ] **Step 3: Implement viewer bootstrap and endpoints**

```java
@PostMapping("/bootstrap")
public WeComViewerService.LoginExchangeResponse bootstrap() {
    UUID userId = SecurityUtil.currentUserId();
    WeComUserBindingService.BoundIdentity binding = bindings.requireByUserId(userId);
    return viewer.issueViewerAuth(binding.wecomUserId(), binding.installationBinding());
}

@PostMapping("/sessions")
public WeComViewerService.ViewerSessionResponse createSession(
        @RequestHeader("X-WeCom-Viewer-Token") String viewerToken,
        @Valid @RequestBody WeComViewerSessionRequest request) {
    WeComUserBindingService.BoundIdentity binding = bindings.requireByUserId(SecurityUtil.currentUserId());
    if (!binding.wecomUserId().equals(viewer.requireViewerActor(viewerToken))) {
        throw new WeComException("WECOM_VIEWER_ACTOR_MISMATCH", 403, "企业微信展示身份不匹配");
    }
    return viewer.createViewerSession(request.contactPointId(), viewerToken, request.messageIds());
}
```

Controller base path is `/api/v1/wecom/conversation-view`; JS-SDK config remains `/api/v1/wecom/js-sdk-config`. Add `issueViewerAuth(String wecomUserId, WeComLoginAttemptService.InstallationBinding binding)` to `WeComViewerService`; it reuses the existing bounded viewer-token store without performing another code exchange. Every sync, JS-SDK, session read/create and event endpoint resolves the current CRM binding and verifies that the viewer token actor equals the bound `wecomUserId`. Viewer token is accepted only in `X-WeCom-Viewer-Token`, never in query parameters or ordinary logs. Session request accepts exactly `contactPointId` and up to 15 `messageIds`. `WeComViewerReferenceLeaseRegistry` tracks the bounded message IDs held by unread viewer sessions until read, expiry or eviction so Task 7 retention cannot delete them. Viewer auth tokens may be returned to React memory but are never persisted in localStorage. Local dev implementation must expose the same response contracts.

- [ ] **Step 4: Run viewer tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComViewerControllerTest,WeComViewerServiceTest,WeComLoginAttemptServiceTest test`

Expected: PASS with unauthorized, unbound, expired, single-use and rate-limit cases covered.

- [ ] **Step 5: Commit Task 5**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComViewerController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerSessionRequest.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerEventRequest.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerReferenceLeaseRegistry.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/LocalWeComDevelopmentService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComViewerControllerTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerServiceTest.java
git commit -m "feat: expose spring wecom viewer api"
```

### Task 6: 将 chatdata 原子投影到统一消息时间线

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageQueryService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadServiceWeComTest.java`

**Interfaces:**
- Consumes: Task 2 encrypted reference store, existing contact/conversation/message owners.
- Produces: `MessageResponse.sourceId` and generic WeCom messages visible in `ThreadPage`.

- [ ] **Step 1: Write failing atomic projection tests**

```java
@Test void publishesReferenceProjectionAndCursorInOneTransaction() {
    PublishResult result = store.publishPage(key, "next", List.of(decrypted("m1", "external", "employee", 100L)));
    assertThat(result.stored()).isEqualTo(1);
    verify(projector).project(argThat(m -> m.msgid().equals("m1") && m.direction().equals("outbound")));
    InOrder order = inOrder(chatDataMessageMapper, projector, cursorMapper);
    order.verify(chatDataMessageMapper).insertIgnore(any());
    order.verify(projector).project(any());
    order.verify(cursorMapper).upsert(anyString(), eq("next"));
}

@Test void projectionFailurePreventsCursorAdvance() {
    doThrow(new IllegalStateException("projection failed")).when(projector).project(any());
    assertThatThrownBy(() -> store.publishPage(key, "next", List.of(decrypted("m1", "external", "employee", 100L))));
    verify(cursorMapper, never()).upsert(anyString(), anyString());
}

@Test void threadResponseIncludesWeComSourceIdWithoutPlaintext() {
    MessageResponse item = service.threadPage(userId, contactId, null, null, 20).items().get(0);
    assertThat(item.channelType()).isEqualTo("wecom");
    assertThat(item.sourceId()).isEqualTo("m1");
    assertThat(item.bodyText()).isEmpty();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComMessageProjectorTest,WeComChatDataStoreTest,ThreadServiceWeComTest test`

Expected: FAIL because chatdata is not projected and `MessageResponse` lacks source ID.

- [ ] **Step 3: Implement the projector and DTO projection**

```java
@Transactional(propagation = Propagation.MANDATORY)
public ProjectionResult project(WeComProjectedMessage item) {
    ChannelAccountEntity account = channelAccounts.requireSingleActive("wecom");
    ContactIdentityEntity identity = findOrCreateIdentity(account, item.externalUserId());
    ConversationEntity conversation = conversations.getOrCreateConversation(account.getId(), identity.getId());
    if (messages.findByProviderMessageId(account.getId(), item.msgid()).isPresent()) return new ProjectionResult(false);
    MessageEntity message = new MessageEntity();
    message.setId(UUID.randomUUID());
    message.setChannelAccountId(account.getId());
    message.setConversationId(conversation.getId());
    message.setProviderMessageId(item.msgid());
    message.setDirection(item.direction());
    message.setMessageKind("text");
    message.setBodyText("");
    message.setOccurredAt(Instant.ofEpochSecond(item.sendTime()));
    message.setCountsAsUnread("inbound".equals(item.direction()));
    message.setCurrentStatus("outbound".equals(item.direction()) ? "sent" : "delivered");
    message.setMetadataJsonb("{\"wecomReference\":true}");
    messages.insertWithSequence(message);
    return new ProjectionResult(true);
}
```

Annotate `publishPage` with `@Transactional`; encrypt and insert the chatdata reference, then call projector, then update the cursor. Add `String sourceId` to `MessageResponse`, populated from the raw `providerMessageId` required by OpenDataFrame, and update all constructors/tests. Register one `TransactionSynchronization.afterCommit` callback that publishes `message-new` only when at least one projection was new; rollback or duplicate-only pages publish nothing.

- [ ] **Step 4: Run projection and thread tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComMessageProjectorTest,WeComChatDataStoreTest,ThreadServiceWeComTest,MessageQueryServiceTest test`

Expected: PASS; a forced projector exception leaves cursor unchanged.

- [ ] **Step 5: Commit Task 6**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataSyncService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageQueryService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadServiceWeComTest.java
git commit -m "feat: project wecom messages into spring timeline"
```

### Task 7: 修正 token、公钥、自动同步和资源上界

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAccessTokenService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataPublicKeyRegistrar.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationMutationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerReferenceLeaseRegistry.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAccessTokenServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataPublicKeyRegistrarTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetentionTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountServiceWeComTest.java`

**Interfaces:**
- Consumes: reliable authorization completion callback and encrypted resolved installation.
- Produces: demo-compatible developed-app token, immediate public-key trigger, bounded store retention and real channel sync.

- [ ] **Step 1: Write failing runtime tests**

```java
@Test void accessTokenUsesInstallationPermanentCodeAsDevelopedAppSecret() {
    service.accessToken(installation, Duration.ofSeconds(4));
    verify(gateway).getDevelopedAppToken(CORP, PERMANENT_CODE, Duration.ofSeconds(4));
    verify(gateway, never()).getCorpToken(anyString(), anyString());
}

@Test void successfulAuthorizationRequestsImmediatePublicKeyRegistration() {
    worker.process(event);
    verify(registrar).requestRegistration();
}

@Test void weComChannelSyncUsesChatdataService() throws Exception {
    service.sync(weComAccountId);
    verify(weComSync).syncSystem();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAccessTokenServiceTest,WeComChatDataPublicKeyRegistrarTest,WeComChatDataRetentionTest,ChannelAccountServiceWeComTest test`

Expected: FAIL because the gateway has no real socket timeout, registrar has no immediate signal API, retention is absent, and channel sync rejects WeCom. The token-path assertion already passes and protects the demo-compatible `/cgi-bin/gettoken` contract from regression.

- [ ] **Step 3: Implement runtime corrections**

```java
WeComAuthorizationGateway.CorpTokenResponse response = gateway.getDevelopedAppToken(
        installation.authCorpId(), installation.permanentCode(), timeout);
```

Do not switch this path to `/cgi-bin/service/get_corp_token`: the migrated demo and the verified debug flow use the installation `permanent_code` as the代开发应用 Secret with `/cgi-bin/gettoken`. Construct each WeCom `RestClient` with a shared `JdkClientHttpRequestFactory` whose connect and read timeouts both use `wecomApiTimeoutSeconds`; the per-call `Duration` remains the outer deadline.

Registrar exposes:

```java
public void requestRegistration() {
    if (registrationQueued.compareAndSet(false, true)) {
        try {
            registrationExecutor.execute(() -> {
                try { registerIfNeeded(); }
                finally { registrationQueued.set(false); }
            });
        } catch (RejectedExecutionException rejected) {
            registrationQueued.set(false);
        }
    }
}
```

The executor is a named single-thread `ThreadPoolExecutor` with queue capacity 1 and bounded `@PreDestroy` shutdown; repeated signals coalesce. Authorization calls it only after committed `succeeded`. Retention executes after page commit, reads oldest candidates in batches, excludes IDs leased by `WeComViewerReferenceLeaseRegistry`, deletes with optimistic IDs, and stops only when both configured message and byte budgets are satisfied. Replace the dead `wecomSyncEnabled` field with the existing real `wecomChatDataAutoSyncEnabled` contract. Route `ChannelAccountService.sync` case `wecom` to a dedicated system sync facade.

- [ ] **Step 4: Run runtime tests**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComAccessTokenServiceTest,WeComChatDataPublicKeyRegistrarTest,WeComChatDataRetentionTest,ChannelAccountServiceWeComTest,WeComChatDataSyncServiceTest test`

Expected: PASS with exact token gateway verification and bounded deletion assertions.

- [ ] **Step 5: Commit Task 7**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAccessTokenService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataPublicKeyRegistrar.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComAuthorizationMutationService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerReferenceLeaseRegistry.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAccessTokenServiceTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataPublicKeyRegistrarTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataRetentionTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountServiceWeComTest.java
git commit -m "fix: complete spring wecom runtime wiring"
```

### Task 8: 在现有登录页接入官方企业微信登录组件

**Files:**
- Create: `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts`
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/LoginPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/hooks/useAuth.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.test.tsx`
- Test: `demo/message-center-spring/frontend/src/wecom/wecomSdk.test.ts`
- Test: `demo/message-center-spring/frontend/src/pages/LoginPage.test.tsx`

**Interfaces:**
- Consumes: Task 4 login attempt/exchange API.
- Produces: official WWLoginPanel optional login and standard local auth state.

- [ ] **Step 1: Write failing UI tests**

```tsx
it('keeps password login and mounts the official WeCom panel', async () => {
  render(<LoginPage />);
  expect(screen.getByRole('tab', { name: '账号密码' })).toBeVisible();
  await userEvent.click(screen.getByRole('tab', { name: '企业微信登录' }));
  await waitFor(() => expect(createWWLoginPanel).toHaveBeenCalledWith(expect.objectContaining({
    el: expect.any(String),
    params: expect.objectContaining({ panel_size: 'small', lang: 'zh', redirect_type: 'callback' }),
  })));
});

it('stores only the CRM session and keeps viewer token in memory', async () => {
  await auth.loginWithWeCom('code', 'state');
  expect(localStorage.getItem('token')).toBe('crm-token');
  expect(localStorage.getItem('wecomViewerAuthToken')).toBeNull();
});
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComLoginPanel.test.tsx src/pages/LoginPage.test.tsx`

Expected: FAIL because the official panel wrapper and auth method do not exist.

- [ ] **Step 3: Implement the official panel without restyling it**

```tsx
export function WeComLoginPanel({ purpose, onAuthenticated }: Props) {
  const hostId = useId().replace(/:/g, '');
  useEffect(() => {
    let disposed = false;
    void createWeComAttempt(purpose).then(async attempt => {
      const ww = await loadWeComSdk();
      if (disposed) return;
      ww.createWWLoginPanel({
        el: `#${hostId}`,
        params: {
          login_type: attempt.loginType,
          appid: attempt.appId,
          redirect_uri: attempt.redirectUri,
          state: attempt.state,
          redirect_type: 'callback',
          panel_size: 'small',
          lang: 'zh',
        },
        onLoginSuccess: ({ code }: { code: string }) => onAuthenticated(code, attempt.state),
        onLoginFail: () => setStatus('企业微信登录失败，请重试'),
      });
    });
    return () => { disposed = true; document.getElementById(hostId)?.replaceChildren(); };
  }, [hostId, onAuthenticated, purpose]);
  return <div id={hostId} aria-label="企业微信官方登录组件" />;
}
```

`wecomSdk.ts` loads only the official `https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js` script, deduplicates concurrent loads, times out after 10 seconds, clears a failed promise for retry, and rejects when `window.top !== window.self`. Use existing `Card`, `Tabs`, `App.useApp()` and responsive width from `LoginPage`; move authenticated navigation into `useEffect` instead of navigating during render. Do not add custom QR markup, purple/gradient styling, or a nested decorative card around the official panel.

- [ ] **Step 4: Run login UI tests**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/wecom/wecomSdk.test.ts src/components/wecom/WeComLoginPanel.test.tsx src/pages/LoginPage.test.tsx && npm run build`

Expected: PASS; build has no TypeScript errors.

- [ ] **Step 5: Commit Task 8**

```bash
git add demo/message-center-spring/frontend/src/wecom/wecomSdk.ts \
  demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.tsx \
  demo/message-center-spring/frontend/src/pages/LoginPage.tsx \
  demo/message-center-spring/frontend/src/hooks/useAuth.tsx \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/wecom/wecomSdk.test.ts \
  demo/message-center-spring/frontend/src/components/wecom/WeComLoginPanel.test.tsx \
  demo/message-center-spring/frontend/src/pages/LoginPage.test.tsx
git commit -m "feat: add official wecom login option"
```

### Task 9: 增加现有账号的企业微信绑定 UI

**Files:**
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.tsx`
- Create: `demo/message-center-spring/frontend/src/components/AccountPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.test.tsx`
- Test: `demo/message-center-spring/frontend/src/components/AppLayout.test.tsx`

**Interfaces:**
- Consumes: Task 4 binding status/attempt/exchange/delete API and Task 8 official panel wrapper.
- Produces: Spring-styled account drawer with binding state and official扫码 flow.

- [ ] **Step 1: Write failing binding UI tests**

```tsx
it('shows current binding and does not expose credential fields', async () => {
  mockBinding({ bound: true, wecomUserId: 'employee-1' });
  render(<WeComBindingPanel />);
  expect(await screen.findByText('已绑定企业微信')).toBeVisible();
  expect(screen.queryByLabelText(/suite secret/i)).not.toBeInTheDocument();
});

it('uses official login panel when binding an existing account', async () => {
  mockBinding({ bound: false });
  render(<WeComBindingPanel />);
  await userEvent.click(screen.getByRole('button', { name: '绑定企业微信' }));
  expect(await screen.findByLabelText('企业微信官方登录组件')).toBeVisible();
});
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComBindingPanel.test.tsx src/components/AppLayout.test.tsx`

Expected: FAIL because the account and binding panels do not exist.

- [ ] **Step 3: Implement account drawer using existing Ant Design tokens**

```tsx
export function WeComBindingPanel() {
  const { data, refetch } = useQuery({ queryKey: ['wecom-binding'], queryFn: fetchWeComBinding });
  if (data?.bound) {
    return (
      <Descriptions size="small" column={1}>
        <Descriptions.Item label="企业微信">已绑定</Descriptions.Item>
        <Descriptions.Item label="成员标识">{data.wecomUserId}</Descriptions.Item>
        <Descriptions.Item><Button danger onClick={() => unbindWeCom().then(() => refetch())}>解除绑定</Button></Descriptions.Item>
      </Descriptions>
    );
  }
  return binding ? (
    <WeComLoginPanel
      purpose="BIND"
      onAuthenticated={(code, state) => exchangeWeComBinding({ code, state }).then(() => refetch())}
    />
  ) : (
    <Button icon={<WechatOutlined />} onClick={() => setBinding(true)}>绑定企业微信</Button>
  );
}
```

Place `AccountPanel` in the existing header through an icon button with tooltip and responsive `Drawer`; use `theme.useToken()`, current border and spacing patterns. The official panel remains unmodified inside the drawer body. A `WECOM_LAST_LOGIN_METHOD` response keeps the binding visible and shows the structured error; the UI never assumes解除成功 before the server response.

- [ ] **Step 4: Run binding UI tests and build**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComBindingPanel.test.tsx src/components/AppLayout.test.tsx && npm run build`

Expected: PASS on desktop and mobile component states.

- [ ] **Step 5: Commit Task 9**

```bash
git add demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.tsx \
  demo/message-center-spring/frontend/src/components/AccountPanel.tsx \
  demo/message-center-spring/frontend/src/components/AppLayout.tsx \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/components/wecom/WeComBindingPanel.test.tsx \
  demo/message-center-spring/frontend/src/components/AppLayout.test.tsx
git commit -m "feat: add spring wecom account binding"
```

### Task 10: 在 React 时间线接入官方 OpenDataFrame

**Files:**
- Create: `demo/message-center-spring/frontend/src/wecom/segmentWeComTimeline.ts`
- Create: `demo/message-center-spring/frontend/src/wecom/WeComFrameRegistry.ts`
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx`
- Create: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/MessageBubble.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Test: `demo/message-center-spring/frontend/src/wecom/segmentWeComTimeline.test.ts`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`

**Interfaces:**
- Consumes: Task 5 viewer endpoints and Task 6 `MessageResponse.sourceId` timeline entries.
- Produces: standalone and mixed WeCom segments with official frame lifecycle.

- [ ] **Step 1: Write failing segmentation and lifecycle tests**

```ts
it.each([
  [15, [5, 5, 5]],
  [16, [5, 5, 6]],
  [17, [5, 6, 6]],
  [18, [6, 6, 6]],
])('balances %i mixed WeCom messages', (count, expected) => {
  expect(segmentWeComTimeline(messages(count), 'mixed').map(x => x.items.length)).toEqual(expected);
});

it('breaks segments at every non-WeCom timeline item', () => {
  expect(segmentWeComTimeline([wecom('1'), email('e'), wecom('2')], 'mixed')).toHaveLength(3);
});

it('keeps frame hidden until handleMounted and destroys it on unmount', async () => {
  const { unmount } = render(<WeComTimelineSegment segment={segment} />);
  expect(screen.queryByTestId('wecom-frame-visible')).not.toBeInTheDocument();
  act(() => frameOptions.handleMounted());
  expect(screen.getByTestId('wecom-frame-visible')).toBeVisible();
  unmount();
  expect(frame.destroy).toHaveBeenCalled();
});
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/wecom/segmentWeComTimeline.test.ts src/components/wecom/WeComTimelineSegment.test.tsx src/pages/ThreadPage.wecom.test.tsx`

Expected: FAIL because no React viewer projection exists.

- [ ] **Step 3: Implement segmentation, viewer hook and frame lifecycle**

```ts
export function segmentWeComTimeline(items: TimelineItem[], mode: 'standalone' | 'mixed'): TimelineBlock[] {
  if (mode === 'standalone') {
    const wecom = items.filter(isWeComMessage).slice(-15);
    return wecom.length ? [{ kind: 'wecom', id: signature(wecom), items: wecom }] : [];
  }
  const result: TimelineBlock[] = [];
  let run: MessageResponse[] = [];
  const flush = () => {
    for (const group of balance(run, 6)) result.push({ kind: 'wecom', id: signature(group), items: group });
    run = [];
  };
  for (const item of items) {
    if (isWeComMessage(item)) run.push(item);
    else { flush(); result.push(item); }
  }
  flush();
  return result;
}
```

`useWeComViewer` keeps viewer auth in React memory, bootstraps only for bound users, requests session references for each stable segment, and reports component events without secrets. `wecomSdk.ts` additionally loads official `https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js`. `WeComTimelineSegment` calls `ww.register`, `ww.initOpenData`, then `ww.createOpenDataFrameFactory().createOpenDataFrame(...)` with one `wx:for` template per segment. It uses the current Spring message-row direction, existing neutral/green bubble colors and `theme.useToken()` for outer state only. It must never render an empty `MessageBubble` for channel `wecom`.

`WeComFrameRegistry` enforces the migrated demo bounds exactly: at most 30 mounted frames globally, at most 15 segments for the active contact, at most 3 cached contacts, render queue capacity 15 and render concurrency 4. LRU eviction destroys the Frame and any detail iframe. A segment remains absent until `handleMounted`; first-load failure renders only a compact chronological failure marker and retry action, while refresh failure preserves an already mounted Frame.

- [ ] **Step 4: Run WeCom UI tests and browser verification**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/wecom/segmentWeComTimeline.test.ts src/components/wecom/WeComTimelineSegment.test.tsx src/pages/ThreadPage.wecom.test.tsx && npm run build`

Expected: PASS. Then start the existing Vite server and use the Browser plugin to verify desktop 1440x900 and mobile 390x844: no overlap, official login untouched, mixed segments remain in chronological position, standalone frame fills the thread viewport.

- [ ] **Step 5: Commit Task 10**

```bash
git add demo/message-center-spring/frontend/src/wecom/segmentWeComTimeline.ts \
  demo/message-center-spring/frontend/src/wecom/WeComFrameRegistry.ts \
  demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx \
  demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts \
  demo/message-center-spring/frontend/src/pages/ThreadPage.tsx \
  demo/message-center-spring/frontend/src/components/MessageBubble.tsx \
  demo/message-center-spring/frontend/src/api/endpoints.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/wecom/segmentWeComTimeline.test.ts \
  demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx \
  demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx
git commit -m "feat: render wecom timeline in spring ui"
```

### Task 11: 清理配置、兼容 callback 路径并补齐合同门禁

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`
- Modify: `demo/message-center-spring/backend/src/main/resources/application-dev.yml`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/WeComSensitiveConfigTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComCallbackCompatibilityTest.java`
- Create: `demo/message-center-spring/frontend/test/wecom-contract.test.mjs`
- Modify: `demo/message-center-spring/README.md`

**Interfaces:**
- Consumes: all completed backend/frontend endpoints.
- Produces: environment-only secrets, callback compatibility and deployable documentation.

- [ ] **Step 1: Write failing config and route tests**

```java
@Test void trackedYamlContainsNoLiteralWeComSecrets() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application-dev.yml"));
    assertThat(yaml).contains("wecom-suite-secret: ${WECOM_SUITE_SECRET:}");
    assertThat(yaml).contains("wecom-login-suite-secret: ${WECOM_LOGIN_SUITE_SECRET:}");
    assertThat(yaml).contains("wecom-token: ${WECOM_TOKEN:}");
    assertThat(yaml).contains("wecom-encoding-aes-key: ${WECOM_ENCODING_AES_KEY:}");
    assertThat(yaml).doesNotContain("permanent_code");
}

@Test void supportsCanonicalLegacyAndHookCallbackPaths() throws Exception {
    for (String path : List.of("/api/wecom/callback", "/api/v1/wecom/authorization/callback", "/hook_path")) {
        mvc.perform(get(path).param("msg_signature", "sig").param("timestamp", "1")
                .param("nonce", "n").param("echostr", "echo"))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=WeComSensitiveConfigTest,WeComCallbackCompatibilityTest test`

Expected: FAIL because dev YAML contains literals and alternate callback paths are absent.

- [ ] **Step 3: Replace secrets with environment contracts and add aliases**

```yaml
app:
  wecom-suite-id: ${WECOM_SUITE_ID:}
  wecom-suite-secret: ${WECOM_SUITE_SECRET:}
  wecom-token: ${WECOM_TOKEN:}
  wecom-encoding-aes-key: ${WECOM_ENCODING_AES_KEY:}
  wecom-callback-receive-id: ${WECOM_CALLBACK_RECEIVE_ID:}
  wecom-login-suite-id: ${WECOM_LOGIN_SUITE_ID:}
  wecom-login-suite-secret: ${WECOM_LOGIN_SUITE_SECRET:}
  wecom-login-auth-corp-id: ${WECOM_LOGIN_AUTH_CORP_ID:}
  wecom-login-redirect-uri: ${WECOM_LOGIN_REDIRECT_URI:http://localhost:5173/login}
  wecom-allowed-jsapi-origins: ${WECOM_ALLOWED_JSAPI_ORIGINS:}
  wecom-chatdata-program-id: ${WECOM_CHATDATA_PROGRAM_ID:}
  wecom-chatdata-ability-id: ${WECOM_CHATDATA_ABILITY_ID:conversation_viewer_sync}
  wecom-chatdata-private-key-file: ${WECOM_CHATDATA_PRIVATE_KEY_FILE:}
```

Remove the class-level `/api/wecom` mapping from the existing `WeComController`. Map its GET/POST callback methods directly to `{ "/api/wecom/callback", "/api/v1/wecom/authorization/callback", "/hook_path" }`, and map send directly to `/api/wecom/send`; this preserves one Controller and one callback implementation without exposing send beneath callback aliases. README documents required variables, optional variables, credential rotation warning, login/binding flow and local verification commands. The Node contract test asserts all new frontend endpoint strings and absence of plaintext secrets.

- [ ] **Step 4: Run contract, secret scan and packaging checks**

Run:

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=WeComSensitiveConfigTest,WeComCallbackCompatibilityTest test
mvn -q -DskipTests package
cd ../frontend
node --test test/wecom-contract.test.mjs
npm run build
cd ../../..
rg -n 'wecom-(suite-secret|login-suite-secret|secret|token|encoding-aes-key): [^$]' demo/message-center-spring/backend/src/main/resources
```

Expected: tests/build/package PASS; final `rg` returns no matches.

- [ ] **Step 5: Commit Task 11**

```bash
git add demo/message-center-spring/backend/src/main/resources/application.yml \
  demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/WeComSensitiveConfigTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComCallbackCompatibilityTest.java \
  demo/message-center-spring/frontend/test/wecom-contract.test.mjs \
  demo/message-center-spring/README.md
git commit -m "docs: complete spring wecom deployment contract"
```

### Task 12: 全链验收、真实 UI 检查与迁移完成审计

**Files:**
- Modify only when verification exposes a defect: files owned by Tasks 1-11.
- Update: `docs/superpowers/specs/2026-08-14-wecom-spring-complete-migration-design.md` status and evidence section.
- Create: `docs/superpowers/reviews/2026-08-14-wecom-spring-complete-migration-verification.md`

**Interfaces:**
- Consumes: all Task 1-11 deliverables.
- Produces: reproducible verification evidence and final migration readiness decision.

- [ ] **Step 1: Run backend focused suites before full suite**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest='*WeCom*Test' test
mvn -q test
mvn -q -DskipTests package
```

Expected: zero failures, zero errors, zero warnings attributable to this migration.

- [ ] **Step 2: Run frontend focused and full suites**

```bash
cd demo/message-center-spring/frontend
npx vitest run src/components/wecom src/wecom src/pages/LoginPage.test.tsx src/pages/ThreadPage.wecom.test.tsx
npm test
npm run build
```

Expected: all tests and production build PASS.

- [ ] **Step 3: Run repository and sensitive-field gates**

```bash
cd ../../..
git diff --check
rg -n 'permanentCode|secretKey|access_token|suite_ticket' demo/message-center-spring/backend/src/main/java \
  | rg 'log\.|System\.(out|err)|body\(|Map\.of' || true
rg -n 'wecom-(suite-secret|login-suite-secret|secret|token|encoding-aes-key): [^$]' \
  demo/message-center-spring/backend/src/main/resources
git status --short
git diff --cached --name-only
```

Expected: no sensitive output path; no accidental staged files; all intended WeCom source/migration/test files are tracked.

- [ ] **Step 4: Perform browser QA and record external-only gaps**

Start Spring backend and Vite on unused local ports. Use the Browser plugin at 1440x900 and 390x844 to verify password login, official WeCom tab, account drawer, unbound state, thread loading, mixed timeline and responsive layout. Record screenshots and console results. Mark real QR exchange, official OpenDataFrame content, upstream public-key registration and real chatdata sync as `需要真实企业微信环境` unless valid credentials are available; never replace them with fake success.

- [ ] **Step 5: Write evidence and commit final verification docs**

```bash
git add docs/superpowers/specs/2026-08-14-wecom-spring-complete-migration-design.md \
  docs/superpowers/reviews/2026-08-14-wecom-spring-complete-migration-verification.md
git commit -m "docs: verify spring wecom migration"
```

The verification document must list every command, exit result, browser viewport, screenshot path, unresolved external dependency and deployment prerequisite. Change the design status to `自动化验收完成，真实企业微信环境待验收` only if all local gates pass.
