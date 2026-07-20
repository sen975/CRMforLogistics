# Message Center PostgreSQL and MinIO Implementation Plan

> **执行状态（2026-07-20）：** Task 1–6 保留为历史执行依据；Task 7–12 已被 `docs/superpowers/plans/2026-07-20-message-center-master-roadmap.md` 及其两个子计划替代。禁止继续按本文旧 package 路径实现 Task 7–12。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `demo/message-center-demo` 从 JSONL 文件真源迁移为 PostgreSQL + MinIO 的单租户多人消息中心，并闭合多账号、权限、未读、幂等、outbox、数据导入和 Docker Compose 运行链路。

**Architecture:** 保留当前 Java 17 demo 和现有 HTTP/UI 合同，先引入 PostgreSQL 规范化业务表、脱敏事件收件箱和事务型 outbox，再将联系人、消息、同步与发送逐条切换到 repository/service。所有二进制附件统一转存 MinIO；旧 JSONL 只通过一次性导入命令读取，运行时不保留双写或回退分支。

**Tech Stack:** Java 17、Maven、PostgreSQL 17、Flyway、PostgreSQL JDBC、HikariCP、JUnit 5、Testcontainers、MinIO Java SDK、Argon2id、AES-256-GCM、Docker Compose。

## Global Constraints

- 全程遵循 `/Users/z/workItem/CRMforLogistics/AGENTS.md`，不把 demo 当空项目重写。
- 只修改 `demo/message-center-demo`、本计划关联文档和当前 PRD；不触碰其他 demo 或用户无关改动。
- 工作区很脏：禁止 `git reset`、`git checkout --`、`git add .`，禁止覆盖未授权改动。
- 测试 Web 服务只能使用 `8100`，结束后必须关闭并确认无监听；`8099` 只供用户主动访问。
- 禁止打印 `.env`、Docker Secret、数据库密码、MinIO 凭据、OSS/CAMS 完整签名 URL 或解密后的渠道密钥。
- PostgreSQL 与 MinIO 是唯一运行真源；旧 JSONL 只允许显式导入，不保留运行时双写、回退读取或兼容开关。
- 不引入 Chatwoot、Redis、RocketMQ、Elasticsearch 或 pgvector。
- 密码使用 Argon2id；Session Token 只保存 SHA-256 哈希；渠道密钥使用 AES-256-GCM 可逆加密，主密钥只来自 Docker Secret。
- 所有外部输入、worker 批次、重试、超时、文件大小和并发必须有显式上限。
- Flyway 是 schema 唯一变更入口，已发布迁移不可修改。
- 执行阶段未经用户明确授权不得创建 Git commit；每项任务以精确 `git diff --check` 和文件清单作为 review checkpoint。
- 当前机器没有可用 Docker CLI；所有 Docker/Testcontainers 验收在用户准备好运行时后执行，不能以 mock 冒充集成通过。

---

## File Map

现有文件职责：

- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`：保留入口、HTTP 路由和现有页面，逐步改为调用 service。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`：保留对 UI 的统一联系人/时间线 facade，内部改为 PostgreSQL repository。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppSender.java`：最终只做 CAMS adapter，不再写 JSONL。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java`：最终只拉取并写入事件收件箱，不再写 JSONL。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailSyncService.java`：最终只拉取并写入事件收件箱，不再写 JSONL。
- `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MediaGateway.java`：最终只从 MinIO 读取已授权附件，不在请求链路直连 OSS。

新增生产文件按职责拆分：

- `Database.java`：连接池、事务和 schema 版本检查。
- `CredentialCipher.java`：AES-256-GCM 渠道配置加解密。
- `PasswordHasher.java`：Argon2id 密码哈希。
- `JdbcAuthRepository.java`、`SessionService.java`：用户、会话和登录。
- `JdbcContactRepository.java`：联系人、身份、标签、合并和拆分。
- `JdbcMessageRepository.java`：会话、消息、参与人、状态和阅读游标。
- `JdbcChannelAccountRepository.java`：渠道账号、模板和同步游标。
- `JdbcEventRepository.java`、`ChannelEventWorker.java`：入站事件收件箱。
- `JdbcOutboxRepository.java`、`OutboxWorker.java`：发送任务和有限重试。
- `AccessControlService.java`：团队、分配、授权和敏感原文访问判断。
- `ObjectStorage.java`、`MinioObjectStorage.java`、`JdbcAttachmentRepository.java`：附件元数据与 MinIO。
- `LegacyImportService.java`：一次性 JSONL、模板和媒体缓存导入。
- `MessageCommandService.java`：发送命令、pending 消息与 outbox 事务。
- `MessageQueryService.java`：联系人列表、底层会话和统一时间线查询。

## Task 1: Maven 测试基线与配置合同

**Files:**
- Modify: `demo/message-center-demo/pom.xml`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

**Interfaces:**
- Produces: `Config.databaseUrl()`, `databaseUser()`, `databasePasswordFile()`, `credentialMasterKeyFile()`, `minioEndpoint()`, `minioAccessKeyFile()`, `minioSecretKeyFile()`, `minioBucket()`, `workerBatchSize()`, `workerMaxAttempts()`。

- [ ] **Step 1: 写配置失败测试**

```java
@Test
void exposesDatabaseMinioAndBoundedWorkerSettings() {
    Config config = new Config(Map.of(
            "DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center",
            "DATABASE_USER", "message_center",
            "DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password",
            "CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key",
            "MINIO_ENDPOINT", "http://localhost:9000",
            "MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key",
            "MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key",
            "MINIO_BUCKET", "message-center",
            "WORKER_BATCH_SIZE", "25",
            "WORKER_MAX_ATTEMPTS", "6"
    ));

    assertEquals("jdbc:postgresql://localhost:5432/message_center", config.databaseUrl());
    assertEquals(25, config.workerBatchSize());
    assertEquals(6, config.workerMaxAttempts());
    assertEquals("message-center", config.minioBucket());
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest test`

Expected: FAIL，原因是 JUnit 依赖或新增配置方法不存在。

- [ ] **Step 3: 增加固定版本依赖与测试插件**

在 `pom.xml` 增加：PostgreSQL JDBC `42.7.5`、HikariCP `5.1.0`、Flyway Core/PostgreSQL `10.22.0`、MinIO SDK `8.5.14`、Argon2 JVM `2.11`、JUnit Jupiter `5.11.4`、Testcontainers core/PostgreSQL/JUnit `1.20.4`，并固定 Surefire/Failsafe `3.5.2`。生产依赖不得使用动态版本；Testcontainers 依赖必须是 `test` scope。MinIO 集成测试使用 Testcontainers `GenericContainer`，不依赖不存在或版本不确定的专用 MinIO module。

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.2</version>
  <configuration>
    <useModulePath>false</useModulePath>
  </configuration>
</plugin>
```

- [ ] **Step 4: 实现配置方法和安全文件读取 helper**

```java
public String databaseUrl() { return value("DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center"); }
public String databaseUser() { return value("DATABASE_USER", "message_center"); }
public Path databasePasswordFile() { return Path.of(value("DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password")); }
public Path credentialMasterKeyFile() { return Path.of(value("CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key")); }
public String minioEndpoint() { return value("MINIO_ENDPOINT", "http://localhost:9000"); }
public Path minioAccessKeyFile() { return Path.of(value("MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key")); }
public Path minioSecretKeyFile() { return Path.of(value("MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key")); }
public String minioBucket() { return value("MINIO_BUCKET", "message-center"); }
public int workerBatchSize() { return boundedInt("WORKER_BATCH_SIZE", 25, 1, 100); }
public int workerMaxAttempts() { return boundedInt("WORKER_MAX_ATTEMPTS", 6, 1, 20); }
```

`readSecret(Path)` 只返回去除末尾换行后的内容；异常只包含 secret 名称和路径，不包含文件内容。

- [ ] **Step 5: 运行测试与现有回归**

Run: `cd demo/message-center-demo && mvn -q test`

Expected: PASS；现有 `UnifiedMessageStoreTest` 仍可通过原命令运行。

- [ ] **Step 6: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/pom.xml demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`

Expected: 无输出。

## Task 2: Docker Compose 基础设施与本地 Secret 初始化

**Files:**
- Create: `demo/message-center-demo/compose.yaml`
- Create: `demo/message-center-demo/.dockerignore`
- Create: `demo/message-center-demo/secrets/.gitignore`
- Create: `demo/message-center-demo/scripts/init-local-secrets.sh`
- Create: `demo/message-center-demo/scripts/init-local-secrets.ps1`
- Modify: `demo/message-center-demo/config.example.env`

**Interfaces:**
- Produces: Compose 服务 `postgres`、`minio`、`minio-init`；只读 secret 文件 `postgres_password`、`minio_access_key`、`minio_secret_key`、`credential_master_key`、`bootstrap_admin_password`。

- [ ] **Step 1: 写 Compose 静态验证脚本**

```bash
docker compose -f compose.yaml config --services
```

Expected after implementation: 输出 `postgres`、`minio`、`minio-init`，且不输出 secret 内容。

- [ ] **Step 2: 创建仅基础设施的 Compose**

使用固定镜像：`postgres:17.5-bookworm`、`minio/minio:RELEASE.2025-04-22T22-12-26Z`、`minio/mc:RELEASE.2025-04-16T18-13-26Z`。PostgreSQL 使用 `POSTGRES_PASSWORD_FILE`，MinIO 使用 secret file 环境入口；端口只绑定 `127.0.0.1`。健康检查分别使用 `pg_isready` 与 MinIO health endpoint。

```yaml
services:
  postgres:
    image: postgres:17.5-bookworm
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
    secrets: [postgres_password]
    volumes: [postgres_data:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U message_center -d message_center"]
      interval: 5s
      timeout: 3s
      retries: 20
  minio:
    image: minio/minio:RELEASE.2025-04-22T22-12-26Z
    command: server /data --console-address :9001
    ports:
      - "127.0.0.1:${MINIO_PORT:-9000}:9000"
      - "127.0.0.1:${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes: [minio_data:/data]
```

- [ ] **Step 3: 创建跨平台 Secret 初始化脚本**

Shell 使用 `openssl rand`，PowerShell 使用 `RandomNumberGenerator.Fill`；数据库密码和管理员密码至少生成 32 bytes，MinIO Access Key 生成 20 bytes，MinIO Secret Key 生成 40 bytes，credential master key 生成 32 bytes 后以 Base64 保存。脚本只报告文件名和创建状态，不打印生成值。文件权限在 Unix 设置为 `0600`。已存在文件不得覆盖。

- [ ] **Step 4: 保护 Secret 文件与构建上下文**

`secrets/.gitignore` 忽略目录内所有内容，仅保留自身；`.dockerignore` 排除 `.env`、`secrets/`、`data/`、`target/` 和本地媒体缓存。

- [ ] **Step 5: 验证配置**

Run after Docker is available: `cd demo/message-center-demo && ./scripts/init-local-secrets.sh && docker compose config --quiet`

Expected: exit 0；命令输出不包含任何 secret 值。

- [ ] **Step 6: Review checkpoint**

Run: `git status --short -- demo/message-center-demo/compose.yaml demo/message-center-demo/.dockerignore demo/message-center-demo/secrets/.gitignore demo/message-center-demo/scripts demo/message-center-demo/config.example.env`

Expected: 只列出本任务文件，不列出 `secrets` 实际内容。

## Task 3: Flyway Schema 与数据库事务边界

**Files:**
- Create: `demo/message-center-demo/src/main/resources/db/migration/V1__identity_and_contact.sql`
- Create: `demo/message-center-demo/src/main/resources/db/migration/V2__channel_conversation_message.sql`
- Create: `demo/message-center-demo/src/main/resources/db/migration/V3__events_storage_import.sql`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Database.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/DatabaseSchemaIT.java`

**Interfaces:**
- Produces: `Database.open(Config)`, `Database.migrate()`, `Database.read(SqlFunction<T>)`, `Database.transaction(SqlFunction<T>)`。
- Produces tables and constraints exactly defined in `docs/superpowers/specs/2026-07-17-message-center-database-design.md` sections 5–10。

- [ ] **Step 1: 写空库迁移集成测试**

```java
@Testcontainers
class DatabaseSchemaIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    @Test
    void migratesEveryRequiredTableAndConstraint() throws Exception {
        Config config = databaseConfig(postgres);
        try (Database database = Database.open(config)) {
            database.migrate();
            assertTables(database, "users", "roles", "user_roles", "user_sessions", "teams",
                    "team_members", "companies", "contacts", "contact_identities", "channel_accounts",
                    "channel_sync_cursors", "message_templates", "conversations", "conversation_access_grants",
                    "conversation_read_states", "messages", "message_participants", "message_status_events",
                    "attachments", "channel_events", "outbox_jobs", "audit_logs",
                    "data_import_batches", "data_import_errors");
        }
    }

    private static Config databaseConfig(PostgreSQLContainer<?> postgres) throws Exception {
        Path passwordFile = Files.createTempFile("message-center-db-password", ".txt");
        Files.writeString(passwordFile, postgres.getPassword(), StandardCharsets.UTF_8);
        passwordFile.toFile().deleteOnExit();
        return new Config(Map.of(
                "DATABASE_URL", postgres.getJdbcUrl(),
                "DATABASE_USER", postgres.getUsername(),
                "DATABASE_PASSWORD_FILE", passwordFile.toString()
        ));
    }

    private static void assertTables(Database database, String... tableNames) throws Exception {
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select to_regclass(?)")) {
                for (String tableName : tableNames) {
                    statement.setString(1, "public." + tableName);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertNotNull(result.getString(1), tableName);
                    }
                }
            }
            return null;
        });
    }
}
```

- [ ] **Step 2: 运行集成测试并确认前置失败**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=DatabaseSchemaIT test`

Expected: FAIL，原因是 migrations 或 `Database` 不存在。

- [ ] **Step 3: 编写 V1 身份、组织、企业和联系人 schema**

迁移必须创建 `pgcrypto`、`pg_trgm`，以及 `users`、`roles`、`user_roles`、`user_sessions`、`teams`、`team_members`、`companies`、企业标签关系、`contacts`、联系人标签关系、`company_contacts`、`contact_identities`、`phone_notes`。固定角色通过 SQL seed 插入，使用 `ON CONFLICT DO NOTHING`。

关键约束必须包含：

```sql
CREATE UNIQUE INDEX ux_users_username_normalized
  ON users (username_normalized) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_contact_identity_normalized
  ON contact_identities (channel_type, identity_scope, normalized_value)
  WHERE deleted_at IS NULL;
ALTER TABLE contacts
  ADD CONSTRAINT ck_contacts_merge_target
  CHECK ((status = 'merged' AND merged_to_id IS NOT NULL) OR status <> 'merged');
```

- [ ] **Step 4: 编写 V2 渠道、会话和消息 schema**

创建 `channel_accounts`、`channel_sync_cursors`、`message_templates`、`conversations`、`conversation_access_grants`、`conversation_read_states`、`messages`、`message_participants`、`message_status_events`。

关键约束必须包含：

```sql
ALTER TABLE conversations ADD CONSTRAINT uq_conversation_account_identity
  UNIQUE (channel_account_id, contact_identity_id);
ALTER TABLE conversations ADD CONSTRAINT uq_conversation_account_pair
  UNIQUE (id, channel_account_id);
ALTER TABLE messages ADD CONSTRAINT fk_message_conversation_account
  FOREIGN KEY (conversation_id, channel_account_id)
  REFERENCES conversations (id, channel_account_id);
CREATE UNIQUE INDEX ux_messages_provider_id
  ON messages (channel_account_id, provider_message_id)
  WHERE provider_message_id IS NOT NULL;
CREATE UNIQUE INDEX ux_messages_client_request_id
  ON messages (channel_account_id, client_request_id)
  WHERE client_request_id IS NOT NULL;
CREATE UNIQUE INDEX ux_messages_ingest_sequence
  ON messages (conversation_id, ingest_sequence);
```

- [ ] **Step 5: 编写 V3 事件、outbox、附件、审计和导入 schema**

创建 `channel_events`、`outbox_jobs`、`attachments`、`audit_logs`、`data_import_batches`、`data_import_errors`，并增加领取、搜索和未读部分索引。

```sql
CREATE INDEX ix_channel_events_claim
  ON channel_events (processing_status, next_attempt_at, received_at)
  WHERE processing_status IN ('received', 'retry_wait');
CREATE INDEX ix_outbox_jobs_claim
  ON outbox_jobs (status, next_attempt_at, created_at)
  WHERE status IN ('pending', 'retry_wait');
CREATE INDEX ix_messages_unread
  ON messages (conversation_id, ingest_sequence)
  WHERE direction = 'inbound' AND counts_as_unread;
ALTER TABLE attachments ADD CONSTRAINT uq_attachment_object UNIQUE (bucket, object_key);
```

- [ ] **Step 6: 实现连接池、迁移和事务 helper**

```java
public final class Database implements AutoCloseable {
    @FunctionalInterface
    public interface SqlFunction<T> { T apply(Connection connection) throws Exception; }

    public static Database open(Config config) throws Exception;
    public void migrate();
    public <T> T read(SqlFunction<T> work) throws Exception;
    public <T> T transaction(SqlFunction<T> work) throws Exception;
    public void close();
}
```

`transaction` 必须显式关闭 auto-commit，成功 commit，异常 rollback，并保留原始异常 cause；错误不得包含密码或 JDBC URL查询参数。

- [ ] **Step 7: 运行 schema、约束和回归测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=DatabaseSchemaIT test`

Expected: PASS；重复 identity、重复 provider message ID、错误组合外键均由数据库拒绝。

- [ ] **Step 8: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/resources/db/migration demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Database.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/DatabaseSchemaIT.java`

Expected: 无输出。

## Task 4: 凭据加密与多渠道账号 Repository

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/CredentialCipher.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAccountRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcChannelAccountRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAccountService.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/CredentialCipherTest.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/JdbcChannelAccountRepositoryIT.java`

**Interfaces:**
- Produces: `CredentialCipher.encrypt(Map<String,String>)`, `decrypt(String)`。
- Produces: `ChannelAccountRepository.create/update/find/findActive/list/updateCursor/upsertTemplates`。
- Produces: `ChannelAccountService.validate(UUID, CredentialValidator)`，验证时在内存解密并调用只读渠道校验器。

- [ ] **Step 1: 写 AES-GCM 行为和防泄漏测试**

```java
@Test
void encryptsWithRandomNonceAndDecryptsOnlyWithMasterKey() throws Exception {
    CredentialCipher cipher = CredentialCipher.fromBase64Key(testKey());
    String first = cipher.encrypt(Map.of("accessKeySecret", "secret-value"));
    String second = cipher.encrypt(Map.of("accessKeySecret", "secret-value"));
    assertNotEquals(first, second);
    assertEquals("secret-value", cipher.decrypt(first).get("accessKeySecret"));
    assertFalse(first.contains("secret-value"));
}
```

- [ ] **Step 2: 实现 AES-256-GCM envelope**

```java
record EncryptedConfig(String algorithm, int keyVersion, String nonce, String ciphertext) {}
```

使用 12-byte 随机 nonce、128-bit GCM tag、`AES/GCM/NoPadding`，algorithm 固定 `AES-256-GCM`，keyVersion 初始为 `1`。解密异常返回结构化 `CredentialDecryptionException`，不得包含密文或明文。

- [ ] **Step 3: 写 repository 集成测试**

覆盖多账号唯一约束、密文落库、游标账号隔离、模板按账号和语言幂等更新。直接 SQL 检查 `encrypted_config::text` 不含测试明文。

- [ ] **Step 4: 实现 repository 与验证服务**

```java
public interface ChannelAccountRepository {
    ChannelAccount create(ChannelAccountDraft draft, String encryptedConfig) throws Exception;
    ChannelAccount update(UUID id, ChannelAccountPatch patch, String encryptedConfig) throws Exception;
    Optional<ChannelAccount> find(UUID id) throws Exception;
    List<ChannelAccount> list() throws Exception;
    SyncCursor upsertCursor(UUID accountId, String type, String scope, String value, Instant timestamp) throws Exception;
    void upsertTemplates(UUID accountId, List<MessageTemplate> templates) throws Exception;
}

public interface CredentialValidator {
    ValidationResult validate(ChannelAccount account, Map<String, String> decryptedSecrets) throws Exception;
}

record ChannelAccount(UUID id, String channelType, String name, String accountIdentifier,
                      String authStatus, String syncStatus, String encryptedConfig) {}
record ChannelAccountDraft(String channelType, String name, String accountIdentifier) {}
record ChannelAccountPatch(String name, String accountIdentifier, String authStatus) {}
record SyncCursor(UUID accountId, String type, String scope, String value, Instant timestamp) {}
record MessageTemplate(String providerTemplateId, String languageCode, String name,
                       String body, String status, Instant providerUpdatedAt) {}
record ValidationResult(boolean valid, String code, String message) {}
```

普通标识、显示名和状态进入明确列；只有敏感字段进入 `encrypted_config`。

- [ ] **Step 5: 实现 ChatApp 校验语义**

`ChannelAccountService.validate` 从 repository 读取密文，以 Docker Secret 主密钥解密，在内存构建 CAMS client，调用只读低成本接口验证 AccessKey 与 `CUST_SPACE_ID`。响应只返回 `valid/code/message`；日志不得记录请求签名、AccessKey Secret 或完整 URL。

- [ ] **Step 6: 运行测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=CredentialCipherTest test`

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=JdbcChannelAccountRepositoryIT test`

Expected: PASS。

- [ ] **Step 7: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/CredentialCipher.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAccountRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcChannelAccountRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAccountService.java`

Expected: 无输出。

## Task 5: 企业、联系人、会话与消息 Repository

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/CompanyRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcCompanyRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ContactRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcContactRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MessageRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcMessageRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/PhoneNoteRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcPhoneNoteRepository.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessage.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/JdbcContactRepositoryIT.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/JdbcMessageRepositoryIT.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/JdbcCompanyPhoneNoteRepositoryIT.java`

**Interfaces:**
- Produces: explicit company-contact visibility, stable contact UUIDs, identity reassignment, phone notes, channel conversation upsert, message idempotency, status append, unified contact timeline query。
- Preserves: `UnifiedMessageStore.contacts()`, `thread(String)`, `findMessage(String)`, merge/split/profile public behavior used by `App`。

- [ ] **Step 1: 写联系人合并/拆分集成测试**

```java
@Test
void mergeMovesIdentitiesWithoutRewritingMessagesAndSplitRestoresOwnership() throws Exception {
    UUID emailContact = contacts.create("Buyer");
    UUID chatContact = contacts.create("Buyer WA");
    UUID emailIdentity = contacts.attachIdentity(emailContact, email("buyer@example.com"));
    UUID chatIdentity = contacts.attachIdentity(chatContact, whatsapp("8613800000000"));
    UUID messageId = messages.insertInbound(conversation(accountId, chatIdentity), inbound("hello"));

    contacts.merge(emailContact, chatContact, actorId);
    assertEquals(emailContact, contacts.findIdentity(chatIdentity).contactId());
    assertEquals(messageId, messages.find(messageId).id());

    UUID splitContact = contacts.splitIdentity(chatIdentity, "Buyer WA", actorId);
    assertEquals(splitContact, contacts.findIdentity(chatIdentity).contactId());
    assertEquals(messageId, messages.find(messageId).id());
}
```

同时写企业关系测试：未建立 `company_contacts` 前企业详情查询不返回联系人或会话；建立后返回；解除后停止返回但消息与电话纪要仍保留。

- [ ] **Step 2: 写消息幂等、排序和状态乱序测试**

覆盖相同 `(channel_account_id, provider_message_id)` 只插入一次、不同 client request 不冲突、展示按 `occurred_at`、未读按 `ingest_sequence`、旧状态事件不让当前状态倒退。

- [ ] **Step 3: 定义 repository 合同**

```java
public interface ContactRepository {
    List<UnifiedContact> listForUser(UUID userId, ContactQuery query) throws Exception;
    UUID create(String displayName, UUID actorId) throws Exception;
    void merge(UUID targetContactId, UUID sourceContactId, UUID actorId) throws Exception;
    UUID splitIdentity(UUID identityId, String displayName, UUID actorId) throws Exception;
    void updateProfile(UUID contactId, String displayName, String remark, List<String> tags, UUID actorId) throws Exception;
}

public interface CompanyRepository {
    List<Company> listForUser(UUID userId, CompanyQuery query) throws Exception;
    UUID create(CompanyDraft draft, UUID actorId) throws Exception;
    void update(UUID companyId, CompanyPatch patch, UUID actorId) throws Exception;
    void linkContact(UUID companyId, UUID contactId, String relationType,
                     boolean primary, String remark, UUID actorId) throws Exception;
    void unlinkContact(UUID companyId, UUID contactId, UUID actorId) throws Exception;
}

public interface PhoneNoteRepository {
    UUID create(PhoneNoteDraft draft, UUID actorId) throws Exception;
    List<PhoneNote> listByContact(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception;
}

public interface MessageRepository {
    UUID getOrCreateConversation(UUID accountId, UUID identityId) throws Exception;
    MessageWriteResult insert(MessageDraft draft) throws Exception;
    void appendStatus(UUID messageId, MessageStatusEvent event) throws Exception;
    List<UnifiedMessage> thread(UUID userId, UUID conversationId, MessageCursor cursor, int limit) throws Exception;
    List<UnifiedMessage> unifiedTimeline(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception;
    Optional<UnifiedMessage> findAuthorized(UUID userId, UUID messageId) throws Exception;
}

record ContactQuery(String search, Instant beforeLastMessageAt, UUID beforeId, int limit) {}
record MessageCursor(Instant occurredAt, UUID id) {}
record MessageDraft(UUID conversationId, UUID channelAccountId, UUID sourceEventId,
                    String providerMessageId, String clientRequestId, String direction,
                    String messageKind, String subject, String bodyText, String bodyHtml,
                    Instant occurredAt, boolean countsAsUnread, UUID createdByUserId) {}
record MessageWriteResult(UUID messageId, boolean inserted) {}
record MessageStatusEvent(String status, Instant occurredAt, String providerEventId,
                          String reasonCode, String reasonMessage) {}
record CompanyQuery(String search, Instant beforeUpdatedAt, UUID beforeId, int limit) {}
record CompanyDraft(String name, String typeCode, String country, String city,
                    String website, UUID ownerId, String remark) {}
record CompanyPatch(String name, String typeCode, String country, String city,
                    String website, UUID ownerId, String remark, String status) {}
record PhoneNoteDraft(UUID contactId, UUID companyId, UUID phoneIdentityId,
                      Instant occurredAt, String summary, String nextStep) {}
record Company(UUID id, String name, String typeCode, String country, String city,
               String website, UUID ownerId, String remark, String status) {}
record PhoneNote(UUID id, UUID contactId, UUID companyId, UUID phoneIdentityId,
                 Instant occurredAt, String summary, String nextStep, UUID createdBy) {}
```

- [ ] **Step 4: 实现 JDBC repository 与事务锁**

会话序列使用：

```sql
UPDATE conversations
SET next_ingest_sequence = next_ingest_sequence + 1, version = version + 1
WHERE id = ?
RETURNING next_ingest_sequence;
```

消息、参与人、初始状态和会话 last message 投影必须在同一事务提交。统一时间线必须先应用用户数据范围，再跨身份聚合。

- [ ] **Step 5: 将 `UnifiedMessageStore` 改为数据库 facade**

构造器显式接收 repositories/services；保留仅用于旧测试 fixture 的 legacy parser 代码到 Task 10 前，不允许 Web 路由继续调用文件读取路径。`UnifiedMessage` 增加 `boolean countsAsUnread` 作为导入和未读投影字段。不得增加 `STORAGE_MODE` 双路径配置。

- [ ] **Step 6: 运行测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=JdbcContactRepositoryIT,JdbcMessageRepositoryIT,JdbcCompanyPhoneNoteRepositoryIT test`

Expected: PASS。

- [ ] **Step 7: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/*Repository.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/Jdbc*RepositoryIT.java`

Expected: 无输出。

## Task 6: 本地登录、权限、分配与个人未读

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/PasswordHasher.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcAuthRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/SessionService.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AccessControlService.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditService.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AuthAndAccessIT.java`

**Interfaces:**
- Produces: `SessionService.login/logout/authenticate/bootstrapAdmin`。
- Produces: `AccessControlService.requireConversationRead/requireConversationSend/markRead`。

- [ ] **Step 1: 写密码、Session 和权限矩阵测试**

```java
@Test
void agentSupervisorAdminAndOwnerRespectDataScope() throws Exception {
    AuthenticatedSession agent = login("agent", "correct-password");
    assertTrue(access.canRead(agent.userId(), assignedConversation));
    assertFalse(access.canRead(agent.userId(), otherTeamConversation));
    assertTrue(access.canRead(supervisorId, teamConversation));
    assertFalse(access.canRead(adminId, sensitiveConversation));
    access.grant(sensitiveConversation, adminId, ownerId, "incident review");
    assertTrue(access.canRead(adminId, sensitiveConversation));
}
```

另测错误密码、锁定用户、过期/撤销 Session、两个用户独立阅读游标和历史补录不新增未读。

- [ ] **Step 2: 实现 Argon2id 与 Session Token**

```java
public interface PasswordHasher {
    String hash(char[] password);
    boolean verify(String encodedHash, char[] password);
}

public record IssuedSession(String rawToken, Instant expiresAt, UUID userId) {}
public record AuthenticatedSession(UUID sessionId, UUID userId, Instant expiresAt) {}
```

Argon2id 参数固定为 memory 65536 KiB、iterations 3、parallelism 1，编码串包含版本、salt 和参数，便于以后渐进升级。Session 原始 token 至少 256 bit，只在登录响应设置一次；数据库保存 SHA-256。所有 `char[]` 和临时 secret byte array 在 finally 中清零。

- [ ] **Step 3: 实现服务端会话和管理员 bootstrap CLI**

`bootstrap-admin` 仅在无用户时读取 `BOOTSTRAP_ADMIN_USERNAME` 与 `/run/secrets/bootstrap_admin_password` 创建管理员；已有用户时返回结构化 no-op，不覆盖密码，不打印 secret。

- [ ] **Step 4: 实现权限与阅读游标**

权限查询顺序：明确撤销/禁用 → 当前 assignee → 所属团队主管 → 有效 access grant → 拒绝。管理员不拥有隐式原文读取权。`markRead` 只能把阅读序列推进到当前已授权会话的最大可见序列，不能倒退。

- [ ] **Step 5: 实现强制审计**

登录、失败登录、退出、敏感消息/附件读取、联系人合并拆分、分配、授权和渠道配置修改调用 `AuditService.record`。审计 before/after 必须脱敏。

- [ ] **Step 6: 运行测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=AuthAndAccessIT test`

Expected: PASS。

- [ ] **Step 7: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/PasswordHasher.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcAuthRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/SessionService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AccessControlService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AuditService.java`

Expected: 无输出。

## Task 7: 原始事件收件箱、事务型 Outbox 与有界 Worker

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelEvent.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcEventRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelEventWorker.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcOutboxRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/OutboxWorker.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/OutboundDispatcher.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelEventProjector.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/SensitiveValueRedactor.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MessageCommandService.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/EventOutboxConcurrencyIT.java`

**Interfaces:**
- Produces: `ingest(ChannelEventDraft)`, `claimEvents(limit, lease)`, `queueMessage(SendMessageCommand)`, `claimOutbox(limit, lease)`。
- Consumes: `MessageRepository`, `ChannelAccountRepository`；通过本任务定义的 `OutboundDispatcher` 和 `ChannelEventProjector` 与 Task 9 adapter 解耦。

- [ ] **Step 1: 写幂等与并发领取测试**

两个并发 worker 同时领取同一批事件/outbox，断言每行只被一个 lease owner 获得；重复 provider event ID和重复 payload hash 只生成一个事件；重复 client request ID只生成一个 pending 消息。

- [ ] **Step 2: 定义共享状态和结构化失败**

```java
public record ProcessingFailure(String code, String message, boolean retryable) {}
public enum QueueStatus { RECEIVED, PROCESSING, RETRY_WAIT, PROCESSED, DEAD }
public enum OutboxStatus { PENDING, PROCESSING, RETRY_WAIT, COMPLETED, DEAD }

public interface OutboundDispatcher {
    DispatchResult dispatch(OutboxMessage message) throws Exception;
    SubmissionQueryResult querySubmission(OutboxMessage message) throws Exception;
}

public interface ChannelEventProjector {
    void project(ChannelEvent event) throws Exception;
}

public record OutboxMessage(UUID jobId, UUID messageId, UUID channelAccountId,
                            String channelType, String clientRequestId) {}
public record DispatchResult(String outcome, String providerMessageId, String status,
                             String code, String message, boolean retryable) {}
public record SubmissionQueryResult(String outcome, String providerMessageId,
                                    String status, String code, String message) {}
public record ChannelEvent(UUID id, UUID channelAccountId, String providerEventId,
                           String eventType, Instant occurredAt, String payloadJson,
                           String payloadHash, int attemptCount) {}
public record SendMessageCommand(UUID userId, UUID conversationId, UUID channelAccountId,
                                 String clientRequestId, String messageKind, String subject,
                                 String bodyText, List<UUID> attachmentIds) {}
```

错误 message 必须先经过 `SensitiveValueRedactor`，移除 Authorization、AK Secret、签名查询参数和数据库凭据。

- [ ] **Step 3: 实现 `FOR UPDATE SKIP LOCKED` 领取**

```sql
WITH candidates AS (
  SELECT id FROM outbox_jobs
  WHERE status IN ('pending', 'retry_wait')
    AND next_attempt_at <= now()
    AND (lease_until IS NULL OR lease_until < now())
  ORDER BY next_attempt_at, created_at
  FOR UPDATE SKIP LOCKED
  LIMIT ?
)
UPDATE outbox_jobs job
SET status = 'processing', lease_owner = ?, lease_until = ?, updated_at = now()
FROM candidates
WHERE job.id = candidates.id
RETURNING job.*;
```

事件收件箱使用同样模式。批次、lease 时长、最大重试和退避上限来自有界配置。

- [ ] **Step 4: 实现发送事务**

`MessageCommandService.queue` 在同一数据库事务中校验权限、创建 `pending` 消息、初始状态事件和唯一 outbox job。API 不直接调用外部渠道。

- [ ] **Step 5: 实现超时歧义处理**

adapter 返回 `UNKNOWN_SUBMISSION` 时，消息进入 `submission_unknown`，outbox 进入 `retry_wait` 但下一次动作是查询/等待回调；没有稳定 `client_request_id` 的渠道不得自动重发。

- [ ] **Step 6: 运行并发测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=EventOutboxConcurrencyIT test`

Expected: PASS，且不存在重复业务消息或重复 completed job。

- [ ] **Step 7: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcEventRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelEventWorker.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcOutboxRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/OutboxWorker.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MessageCommandService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/SensitiveValueRedactor.java`

Expected: 无输出。

## Task 8: MinIO 附件真源与授权媒体网关

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ObjectStorage.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MinioObjectStorage.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcAttachmentRepository.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AttachmentService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MediaGateway.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/MinioAttachmentIT.java`

**Interfaces:**
- Produces: `ObjectStorage.put/get/delete/stat`。
- Produces: `AttachmentService.storeInbound/storeOutbound/openAuthorized/retryFailed`。
- Preserves: `/api/media?id=...` 前端入口，但读取来源改为 MinIO。

- [ ] **Step 1: 写 MinIO 集成与越权测试**

```java
@Test
void storesObjectAndRejectsUserWithoutConversationAccess() throws Exception {
    Attachment attachment = service.storeOutbound(authorizedUser, messageId,
            new Upload("invoice.pdf", "application/pdf", bytes.length,
                    new ByteArrayInputStream(bytes)));
    assertEquals(StorageStatus.READY, attachment.status());
    assertArrayEquals(bytes, service.openAuthorized(authorizedUser, attachment.id()).bytes());
    assertThrows(ForbiddenException.class,
            () -> service.openAuthorized(otherUser, attachment.id()));
}
```

另测大小上限、MIME 不匹配、MinIO 超时、数据库事务失败后的孤儿清理标记。

- [ ] **Step 2: 定义对象存储接口**

```java
public interface ObjectStorage {
    StoredObject put(String bucket, String objectKey, String contentType, long size, InputStream input) throws Exception;
    StoredObjectStream get(String bucket, String objectKey) throws Exception;
    Optional<StoredObject> stat(String bucket, String objectKey) throws Exception;
    void delete(String bucket, String objectKey) throws Exception;
}

record StoredObject(String bucket, String objectKey, long size, String contentType, String etag) {}

record StoredObjectStream(InputStream input, long size, String contentType) implements AutoCloseable {
    @Override public void close() throws Exception { input.close(); }
}

record Upload(String fileName, String contentType, long size, InputStream input) {}
record Attachment(UUID id, UUID messageId, String bucket, String objectKey,
                  String originalName, String mimeType, long size, String sha256,
                  StorageStatus status) {}
enum StorageStatus { PENDING, READY, FAILED, DELETED }
```

实现必须流式传输，不把无界文件整体读入内存。

- [ ] **Step 3: 实现稳定对象键与元数据事务**

对象键格式固定为 `messages/{yyyy}/{MM}/{messageId}/{attachmentId}-{safeFileName}`。文件名只用于展示和后缀，不参与权限。SHA-256 在流式上传中计算。

- [ ] **Step 4: 收敛 `MediaGateway`**

`MediaGateway` 根据消息查附件 → 调 `AccessControlService` → 从 MinIO stream。删除请求链路中的 CAMS/OSS fresh-sign fallback；外部附件下载只允许在入站 attachment worker 或显式迁移任务中执行。

- [ ] **Step 5: 运行测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=MinioAttachmentIT test`

Expected: PASS；测试输出不包含 MinIO Secret 或签名 URL。

- [ ] **Step 6: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ObjectStorage.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MinioObjectStorage.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcAttachmentRepository.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/AttachmentService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MediaGateway.java`

Expected: 无输出。

## Task 9: Email 与 ChatApp Adapter 切换到事件/Outbox

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAdapter.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChannelAdapterRegistry.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppSender.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailSyncService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailInboxWriter.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MailSender.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAdapter.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ChannelAdapterContractTest.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAdapterContractTest.java`

**Interfaces:**
- Produces: shared `validate`, `sync`, `send`, `querySubmission` adapter contract。
- Produces: `ChannelAdapterRegistry implements OutboundDispatcher, ChannelEventProjector`。
- Consumes: event inbox for inbound and outbox for outbound；不得写 JSONL。

- [ ] **Step 1: 写 adapter 合同测试**

```java
public interface ChannelAdapter {
    String channelType();
    ValidationResult validate(ChannelAccount account, Map<String, String> decryptedSecrets) throws Exception;
    SyncResult sync(ChannelAccount account, SyncCursor cursor, ChannelEventSink sink) throws Exception;
    SendResult send(ChannelAccount account, OutboundMessage message, Map<String, String> decryptedSecrets) throws Exception;
    SubmissionQueryResult querySubmission(ChannelAccount account, String clientRequestId,
                                          Map<String, String> decryptedSecrets) throws Exception;
}

public interface ChannelEventSink {
    boolean ingest(ChannelEventDraft event) throws Exception;
}

public record ChannelEventDraft(UUID channelAccountId, String providerEventId,
                                String eventType, Instant occurredAt,
                                String sanitizedPayloadJson, String payloadHash) {}
public record SendResult(String outcome, String providerMessageId, String status,
                         String code, String message, boolean retryable) {}
```

`ChannelAdapter` 同时实现 Task 4 的 `CredentialValidator` 语义；`ChannelAdapterRegistry` 按 `channelType` 路由，并实现 Task 7 的 `OutboundDispatcher` 与 `ChannelEventProjector`。

合同测试断言 adapter 返回结构化错误，不把 secret、Authorization 或完整 URL 放入 message。

- [ ] **Step 2: 改 ChatApp 历史同步**

`ChatAppHistorySyncService` 只负责 CAMS 分页和投影为脱敏 `ChannelEventDraft`。每页事件成功写入后再更新该账号 cursor；附件引用进入事件元数据，由 attachment worker 转存 MinIO。

- [ ] **Step 3: 改 ChatApp 发送**

`ChatAppSender` 只实现 adapter `send/querySubmission/validate`。移除 `appendLocal`、`containsMessage` 和任何 JSONL 写入；外部响应 ID通过 `SendResult` 返回给 outbox worker。

- [ ] **Step 4: 改 Email 同步与发送**

`EmailSyncService` 将 IMAP Message 投影为带稳定 Message-ID 的事件；`EmailInboxWriter` 改名或收敛为纯投影 helper，不写文件。`MailSender` 只实现 SMTP adapter；发送成功信息由 outbox worker 持久化。

- [ ] **Step 5: 实现企业微信客服 adapter**

`WeComAdapter` 实现回调验签解密、`kf_msg_or_event` 投影、按账号和 OpenKfId 保存 `sync_msg` cursor、以 `has_more` 结束分页、文本 `send_msg` 和发送结果查询。外部 `external_userid` 使用账号作用域生成 `contact_identities.identity_scope`，不得按全局 ID合并。

- [ ] **Step 6: 运行 adapter 与既有投影回归**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ChannelAdapterContractTest,WeComAdapterContractTest test`

Run: `cd demo/message-center-demo && mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest" "-Dexec.classpathScope=test"`

Expected: adapter 合同 PASS；既有媒体 caption、模板渲染和状态投影语义在新 repository fixture 中保持。

- [ ] **Step 7: Review checkpoint**

Run: `rg -n 'Files\.(write|writeString)|StandardOpenOption\.(APPEND|TRUNCATE_EXISTING)' demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppSender.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistorySyncService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailSyncService.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/EmailInboxWriter.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MailSender.java`

Expected: 无运行时 JSONL 写入匹配。另运行 `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAdapter.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAdapterContractTest.java`，期望无输出。

## Task 10: 一次性旧数据导入与单一真源切换

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/LegacyImportService.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/LegacyImportReport.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ChatAppHistoryStore.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/TemplateStore.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/LegacyImportServiceIT.java`

**Interfaces:**
- Produces CLI command: `import-legacy`。
- Consumes explicit source paths from current `Config`，但运行时 Web/service 不再读取这些路径。

- [ ] **Step 1: 写完整 fixture 导入测试**

fixture 包含：两封同 Message-ID 邮件、ChatApp 消息与 status 行、合并联系人、标签、模板、图片缓存和一条损坏行。断言：

```java
assertEquals(1, report.duplicateMessages());
assertEquals(1, report.errorCount());
assertFalse(importedMessages.stream().anyMatch(message -> message.countsAsUnread));
assertEquals(1, report.readyAttachments());
assertEquals(firstRun.batchId(), secondRun.batchId());
```

`LegacyImportReport` 使用完整不可变合同：

```java
public record LegacyImportReport(UUID batchId, String status, int sourceMessages,
                                 int insertedMessages, int updatedMessages, int skippedMessages,
                                 int duplicateMessages, int insertedContacts, int insertedIdentities,
                                 int readyAttachments, int errorCount) {}
```

- [ ] **Step 2: 实现批次与源文件校验**

每个源文件计算 SHA-256，写 `data_import_batches`。重复运行相同哈希批次返回已完成结果；不得重新插入。错误行只保存行号、脱敏记录 ID和结构化错误，不保存敏感原文。

- [ ] **Step 3: 实现导入顺序**

顺序固定为：渠道账号 → 联系人组/身份/标签 → 模板 → 会话/消息/参与人 → 状态事件 → 媒体缓存上传。历史消息统一 `counts_as_unread=false`。

- [ ] **Step 4: 实现对账和停止条件**

导入报告必须包含源记录数、插入、更新、跳过、错误、消息外部 ID冲突、身份归属冲突、附件缺失和 MinIO 对象校验。存在未解释冲突时 batch 状态为 `failed`，CLI exit 非零，禁止切换。

- [ ] **Step 5: 移除运行时文件 owner**

`ChatAppHistoryStore` 和 `TemplateStore` 的运行时读写调用全部替换为 PostgreSQL repository；如类只剩导入用途，移动逻辑到 `LegacyImportService` 后删除旧类。不得留下 `STORAGE_MODE=jsonl` 或 fallback。

- [ ] **Step 6: 运行导入集成测试**

Run after Docker is available: `cd demo/message-center-demo && mvn -q -Dtest=LegacyImportServiceIT test`

Expected: PASS；第二次导入无重复；损坏行被记录但不会泄漏内容。

- [ ] **Step 7: Review checkpoint**

Run: `rg -n 'emailInboxFile\(|chatappDataFile\(|contactGroupFile\(' demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter`

Expected: 只在 `Config` 与 `LegacyImportService` 中出现。

## Task 11: HTTP API、登录页面状态与现有三栏 UI 接线

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/HttpSessionAuth.java`
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MessageQueryService.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Create: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AppApiIT.java`

**Interfaces:**
- Adds: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`。
- Changes existing APIs to require session and return only authorized data。
- Adds: company/contact relation, phone note, read state, assignment, access grant, channel account and import status endpoints required by the approved design。

- [ ] **Step 1: 写 API 权限和 8100 端到端集成测试**

测试以随机测试数据库启动应用在 `8100`，覆盖未登录 401、登录 Cookie、越权会话 403、授权后 200、发送返回 pending、mark-read 只更新当前用户、附件越权 403。

```java
assertEquals(401, get("/api/contacts").statusCode());
String cookie = login("agent", "correct-password");
assertEquals(403, get(cookie, "/api/threads?conversationId=" + forbiddenId).statusCode());
assertEquals(200, post(cookie, "/api/conversations/" + allowedId + "/read", "{}").statusCode());
```

- [ ] **Step 2: 建立请求级 Session 认证**

Cookie 名固定 `MC_SESSION`，属性 `HttpOnly; SameSite=Strict; Path=/`，非本地明文 HTTP时增加 `Secure`。所有写请求验证 `Origin` 与 `Host`；API 错误使用结构化 code，不返回异常堆栈或 SQL。

- [ ] **Step 3: 将路由接到 query/command service**

联系人、线程、消息、合并拆分、发送、同步和媒体路由不得直接访问 JDBC 或文件。发送 API 返回 pending 消息；SSE 状态更新来自数据库 worker 结果。

企业、企业联系人关系和电话纪要 API 只调用 `CompanyRepository`/`PhoneNoteRepository` service；企业详情沟通记录必须通过显式关系过滤。`/webhook/wecom` 从 501 placeholder 改为调用 `WeComAdapter` 的验签、事件入箱和结构化响应。

- [ ] **Step 4: 调整页面登录和个人未读状态**

保留现有三栏布局、发送账号选择和媒体预览。新增登录态/退出入口；删除浏览器内存 `unreadByContact` owner，联系人未读数字完全使用后端返回。页面不得保存密码或渠道密钥。

- [ ] **Step 5: 增加 CLI 命令**

`App.main` 支持：`migrate`、`bootstrap-admin`、`import-legacy`、`web`、`receive`、`sync`。所有命令返回结构化摘要，禁止输出配置密文或签名 URL。

- [ ] **Step 6: 运行 API 与 UI 回归**

Run after Docker is available: `cd demo/message-center-demo && WEB_PORT=8100 MESSAGE_CENTER_PORT=8100 mvn -q -Dtest=AppApiIT test`

Run: `lsof -nP -iTCP:8100 -sTCP:LISTEN`

Expected: 测试 PASS；第二条命令无输出。

- [ ] **Step 7: Review checkpoint**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/HttpSessionAuth.java demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/MessageQueryService.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/AppApiIT.java`

Expected: 无输出。

## Task 12: 应用镜像、迁移/备份服务、文档与最终门禁

**Files:**
- Create: `demo/message-center-demo/Dockerfile`
- Modify: `demo/message-center-demo/compose.yaml`
- Create: `demo/message-center-demo/scripts/backup.sh`
- Create: `demo/message-center-demo/scripts/restore-verify.sh`
- Modify: `demo/message-center-demo/message-center-demo.ps1`
- Modify: `demo/message-center-demo/README.md`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `docs/项目细节PRD.md`
- Modify: `docs/superpowers/specs/2026-07-17-message-center-database-design.md`

**Interfaces:**
- Produces Compose 服务 `db-migrate`、`message-center`、`backup`，以及明确的开发/用户运行命令。
- Produces final acceptance evidence without touching `8099` during tests。

- [ ] **Step 1: 创建多阶段 Java 镜像**

构建阶段固定 `maven:3.9.9-eclipse-temurin-17`，运行阶段固定 Temurin 17 JRE。镜像使用非 root 用户，复制应用依赖和 classes，不包含 `.env`、secret、data、测试 fixture 或 Maven cache。

- [ ] **Step 2: 完成 Compose 依赖链**

`db-migrate` 使用应用镜像运行 `migrate` 并成功退出；`message-center` 依赖 PostgreSQL/MinIO healthy 和 migration completed。`backup` 只在显式 profile/命令下运行，不常驻轮询。

`message-center` 将 `${MESSAGE_CENTER_PORT:-8099}` 同时传入容器 `WEB_PORT` 并映射到同一宿主端口，确保执行 `MESSAGE_CENTER_PORT=8100 docker compose up` 时应用监听和端口映射一致。

- [ ] **Step 3: 实现联合备份与恢复校验**

`backup.sh` 创建带 UTC 批次 ID的 PostgreSQL custom-format dump、MinIO mirror 和 SHA-256 清单，不输出凭据。`restore-verify.sh` 恢复到隔离数据库/bucket，验证用户数、联系人、消息、附件引用和抽样对象哈希；禁止覆盖当前运行卷。

- [ ] **Step 4: 更新运行文档**

README 明确：Docker 前置条件、secret 初始化、基础设施开发模式、全 Compose 用户模式、Flyway、管理员 bootstrap、旧数据导入、对账、备份/恢复、8100 测试限制和 8099 用户访问。配置示例只列键名与非敏感默认值。

- [ ] **Step 5: 运行最小到完整门禁**

Run: `cd demo/message-center-demo && mvn -q test`

Expected: PASS，无 warning。

Run after Docker is available: `cd demo/message-center-demo && docker compose config --quiet && docker compose up -d postgres minio minio-init db-migrate`

Expected: services healthy，migration exit 0。

Run after Docker is available: `cd demo/message-center-demo && mvn -q verify`

Expected: unit + integration PASS。

- [ ] **Step 6: 使用 8100 做真实渲染与 API 验收**

启动：`cd demo/message-center-demo && WEB_PORT=8100 MESSAGE_CENTER_PORT=8100 docker compose up -d message-center`

验证登录、企业关系、联系人、统一时间线、电话纪要、Email/ChatApp/企业微信同步、发送 pending/状态更新、未读、权限拒绝、附件预览、同步失败态。不得使用 8099。

关闭：`cd demo/message-center-demo && docker compose stop message-center`

确认：`lsof -nP -iTCP:8100 -sTCP:LISTEN`

Expected: 无输出。

- [ ] **Step 7: 敏感信息与旧真源扫描**

Run: `rg -n 'Files\.(write|writeString).*jsonl|StandardOpenOption\.APPEND' demo/message-center-demo/src/main/java`

Expected: 无运行时 JSONL 写入。

Run: `rg -n -i 'chatwoot|redis|rocketmq' demo/message-center-demo docs/项目细节PRD.md docs/superpowers/specs/2026-07-17-message-center-database-design.md`

Expected: 只允许设计文档中“明确不引入”的边界句，不得存在依赖、服务或候选路线。

- [ ] **Step 8: 最终 Git 边界复核**

Run: `git status --short -- demo/message-center-demo docs/项目细节PRD.md docs/superpowers/specs/2026-07-17-message-center-database-design.md docs/superpowers/plans/2026-07-17-message-center-postgres-minio.md`

Run: `git diff --check -- demo/message-center-demo docs/项目细节PRD.md docs/superpowers/specs/2026-07-17-message-center-database-design.md docs/superpowers/plans/2026-07-17-message-center-postgres-minio.md`

Expected: 只包含本任务文件；diff check 无输出。未经用户明确授权不 stage、不 commit。

## Execution Stop Conditions

出现以下任一情况立即停止并报告证据，不得添加兼容分支绕过：

- Docker 运行时仍不可用，导致 PostgreSQL/MinIO/Testcontainers 集成证据无法产生。
- 当前源码、已批准设计与旧数据实际字段冲突，且会改变唯一 owner 或导入结果。
- 同一规范化身份映射到多个联系人且无法自动判定。
- 外部消息 ID相同但方向、正文或业务时间不一致。
- 数据导入数量、附件引用或 MinIO 对象对账无法解释地不一致。
- 需要删除旧 JSONL、媒体缓存、API、字段、脚本或旧前端资产。
- 需要修改 Docker 部署边界、权限模型、公开 API 或加密方案。
- 任何测试、日志、API 或数据库检查发现密钥、明文密码或完整签名 URL。
