# WeCom SaaS Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `demo/wecom-saas-demo`，做一个可本地运行的企业微信 SaaS 工作台原型，覆盖客户、消息收发、附件上传、微信客服、会话存档导入和数据 API 同步。

**Architecture:** 使用 Java 17 + Maven + 内置 HTTP Server。核心业务放在小而明确的 Java 类里，HTTP 和 UI 只做接线与展示；数据先落本地 JSON/JSONL，真实企业微信请求通过 `WecomApiClient` adapter 隔离。

**Tech Stack:** Java 17、Maven、Gson 2.11.0、`com.sun.net.httpserver.HttpServer`、本地 JSON/JSONL 文件、普通 Java `main` 测试类。

## Global Constraints

- 新 demo 路径固定为 `demo/wecom-saas-demo`。
- 不修改 `demo/message-center-demo`、`demo/chatapp-send-receive-demo` 或 `weworkapi_python_yuewei`。
- 不硬编码企业微信密钥、会话存档 Secret、RSA 私钥或客户真实数据。
- 不把会话内容存档当成普通消息回调；`ArchiveImportCenter` 只导入 worker 已输出的 JSONL 或 seed 数据。
- 第一版不实现完整第三方服务商代开发安装、授权回调和多企业上线流程。
- 第一版不引入 Spring Boot。
- 页面第一屏必须是可用工作台，不做营销落地页。
- 每次 git stage 必须点名文件，禁止 `git add .`。

---

## File Structure

- Create: `demo/wecom-saas-demo/pom.xml`  
  Maven 项目定义，设置 Java 17、Gson 和 exec 插件。
- Create: `demo/wecom-saas-demo/config.example.env`  
  本地配置样例，只放空值或安全默认值。
- Create: `demo/wecom-saas-demo/wecom-saas-demo.ps1`  
  PowerShell 入口，支持 `web`、`test`、`compile`。
- Create: `demo/wecom-saas-demo/README.md`  
  启动、配置、页面能力、API、验收命令。
- Create: `demo/wecom-saas-demo/.gitignore`  
  忽略 `.env`、`data/`、`target/`。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/App.java`  
  命令入口，启动 HTTP server。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Config.java`  
  读取 `.env` 和环境变量，提供端口、目录、上传大小等配置。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/JsonSupport.java`  
  Gson、JSONL 读写、JSON 对象工具。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ApiError.java`  
  结构化错误对象和 HTTP 状态。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Tenant.java`  
  租户/授权状态模型。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Contact.java`  
  客户、成员、微信客服访客、客户群本地投影。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageRecord.java`  
  统一消息模型。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/AttachmentRecord.java`  
  本地附件记录。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/SyncJob.java`  
  数据 API / 会话存档同步状态。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/TenantRegistry.java`  
  租户和授权状态 owner。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ContactStore.java`  
  客户、成员、微信客服访客、客户群投影 owner。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageStore.java`  
  统一消息和附件 owner。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DemoStore.java`  
  聚合 `TenantRegistry`、`ContactStore`、`MessageStore` 和同步任务，提供测试与路由使用的 facade。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomApiClient.java`  
  企业微信服务端 API adapter 接口。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/LocalWecomApiClient.java`  
  第一版本地模拟企业微信发送和素材上传。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomSaasService.java`  
  发送、附件、同步、存档导入、回调投影业务入口。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DataSyncCenter.java`  
  数据 API 同步状态 owner。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ArchiveImportCenter.java`  
  会话内容存档 JSONL 导入 owner。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MultipartForm.java`  
  解析浏览器 multipart 上传。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Router.java`  
  HTTP 路由、JSON 响应、上传处理。
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/UiRenderer.java`  
  三栏 SaaS 工作台 HTML/CSS/JS。
- Create: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`  
  普通 Java main 测试类，覆盖核心合同、API 投影和页面文本。

---

### Task 1: Project Scaffold, Config, JSON, Errors

**Files:**
- Create: `demo/wecom-saas-demo/pom.xml`
- Create: `demo/wecom-saas-demo/.gitignore`
- Create: `demo/wecom-saas-demo/config.example.env`
- Create: `demo/wecom-saas-demo/wecom-saas-demo.ps1`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Config.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/JsonSupport.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ApiError.java`
- Create: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`

**Interfaces:**
- Produces: `Config.load(Path envFile)`, `Config.forTests(Path dataDir)`, `Config.webPort()`, `Config.dataDir()`, `Config.uploadDir()`, `Config.maxUploadBytes()`, `Config.wecomArchiveJsonl()`
- Produces: `JsonSupport.GSON`, `JsonSupport.readJsonl(Path, Class<T>)`, `JsonSupport.appendJsonl(Path, Object)`, `JsonSupport.map(String, Object...)`
- Produces: `ApiError extends RuntimeException`, `ApiError.status()`, `ApiError.body()`

- [ ] **Step 1: Create Maven scaffold and write the failing scaffold/config test**

Create `demo/wecom-saas-demo/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.crmforlogistics.demo</groupId>
  <artifactId>wecom-saas-demo</artifactId>
  <version>0.1.0</version>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <exec.mainClass>com.crmforlogistics.wecomsaas.App</exec.mainClass>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.11.0</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.3.0</version>
        <configuration>
          <mainClass>${exec.mainClass}</mainClass>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <source>17</source>
          <target>17</target>
          <encoding>UTF-8</encoding>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Create `demo/wecom-saas-demo/.gitignore`:

```gitignore
.env
data/
target/
```

Create `demo/wecom-saas-demo/config.example.env`:

```env
WEB_PORT=8098
DATA_DIR=data
MAX_UPLOAD_BYTES=10485760
WECOM_ARCHIVE_JSONL=../weworkapi_python_yuewei/runtime/wecom/archive/messages.jsonl

WECOM_CORP_ID=
WECOM_AGENT_ID=
WECOM_SUITE_ID=
WECOM_APP_SECRET=
WECOM_KF_SECRET=
```

Create `demo/wecom-saas-demo/wecom-saas-demo.ps1`:

```powershell
param(
    [ValidateSet("web", "test", "compile")]
    [string]$Command = "web"
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ($Command -eq "test") {
    mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
    exit $LASTEXITCODE
}

if ($Command -eq "compile") {
    mvn -q -DskipTests compile
    exit $LASTEXITCODE
}

mvn -q exec:java "-Dexec.args=$Command"
exit $LASTEXITCODE
```

Create `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java` with:

```java
package com.crmforlogistics.wecomsaas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WecomSaasDemoTest {
    public static void main(String[] args) throws Exception {
        loadsConfigFromEnvFileAndKeepsSafeDefaults();
        writesAndReadsJsonlRecords();
        rendersStructuredApiError();
    }

    private static void loadsConfigFromEnvFileAndKeepsSafeDefaults() throws Exception {
        Path dir = Files.createTempDirectory("wecom-saas-config-test");
        Path env = dir.resolve(".env");
        Files.writeString(env, ""
                + "WEB_PORT=8123\n"
                + "DATA_DIR=" + dir.resolve("data") + "\n"
                + "MAX_UPLOAD_BYTES=12345\n"
                + "WECOM_ARCHIVE_JSONL=" + dir.resolve("archive.jsonl") + "\n", StandardCharsets.UTF_8);

        Config config = Config.load(env);

        assertEquals("8123", Integer.toString(config.webPort()));
        assertEquals(dir.resolve("data").toString(), config.dataDir().toString());
        assertEquals(dir.resolve("data").resolve("uploads").toString(), config.uploadDir().toString());
        assertEquals("12345", Long.toString(config.maxUploadBytes()));
        assertEquals(dir.resolve("archive.jsonl").toString(), config.wecomArchiveJsonl().toString());
    }

    private static void writesAndReadsJsonlRecords() throws Exception {
        Path dir = Files.createTempDirectory("wecom-saas-json-test");
        Path file = dir.resolve("records.jsonl");

        JsonSupport.appendJsonl(file, JsonSupport.map("id", "one", "name", "客户A"));
        JsonSupport.appendJsonl(file, JsonSupport.map("id", "two", "name", "客户B"));

        List<java.util.Map> rows = JsonSupport.readJsonl(file, java.util.Map.class);
        assertEquals("2", Integer.toString(rows.size()));
        assertEquals("客户A", String.valueOf(rows.get(0).get("name")));
    }

    private static void rendersStructuredApiError() {
        ApiError error = new ApiError(422, "AttachmentTooLarge", "附件超过本地 demo 限制",
                JsonSupport.map("maxBytes", 10));

        java.util.Map<String, Object> body = error.body();

        assertEquals("AttachmentTooLarge", String.valueOf(body.get("error")));
        assertEquals("附件超过本地 demo 限制", String.valueOf(body.get("message")));
        assertEquals("422", Integer.toString(error.status()));
        assertContains(JsonSupport.GSON.toJson(body), "maxBytes");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertContains(String value, String expected) {
        if (value == null || !value.contains(expected)) {
            throw new AssertionError("Expected [" + value + "] to contain [" + expected + "]");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: FAIL with compiler errors for missing `Config`, `JsonSupport`, and `ApiError`.

- [ ] **Step 3: Create support classes**

Create `Config.java`, `JsonSupport.java`, and `ApiError.java` with the interfaces listed above.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: command exits 0 with no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/pom.xml demo/wecom-saas-demo/.gitignore demo/wecom-saas-demo/config.example.env demo/wecom-saas-demo/wecom-saas-demo.ps1 demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Config.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/JsonSupport.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ApiError.java demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java
git commit -m "feat: scaffold wecom saas demo"
```

---

### Task 2: Domain Models and Local Store

**Files:**
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Tenant.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Contact.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageRecord.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/AttachmentRecord.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/SyncJob.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/TenantRegistry.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ContactStore.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageStore.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DemoStore.java`
- Modify: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`

**Interfaces:**
- Consumes: `Config.forTests(Path dataDir)`, `JsonSupport.readJsonl`, `JsonSupport.appendJsonl`
- Produces: `TenantRegistry(Config config)`, `TenantRegistry.tenants()`, `TenantRegistry.ensureSeedTenants()`
- Produces: `ContactStore(Config config)`, `ContactStore.contacts()`, `ContactStore.find(String contactId)`, `ContactStore.upsert(Contact contact)`, `ContactStore.ensureSeedContacts()`
- Produces: `MessageStore(Config config)`, `MessageStore.messagesForContact(String contactId)`, `MessageStore.findMessage(String id)`, `MessageStore.appendMessage(MessageRecord message)`, `MessageStore.attachments()`, `MessageStore.appendAttachment(AttachmentRecord attachment)`, `MessageStore.ensureSeedMessages()`
- Produces: `DemoStore(Config config)`, `DemoStore.ensureSeedData()`, `tenants()`, `contacts()`, `messagesForContact(String contactId)`, `findMessage(String id)`, `appendMessage(MessageRecord message)`, `attachments()`, `syncJobs()`, `upsertSyncJob(SyncJob job)`
- Produces: public mutable data classes `Tenant`, `Contact`, `MessageRecord`, `AttachmentRecord`, `SyncJob`

- [ ] **Step 1: Add failing store tests**

Append these calls to `main`:

```java
seedsTenantContactsMessagesAndSyncJobs();
appendsMessagesWithoutDroppingRawJson();
```

Add test methods:

```java
private static void seedsTenantContactsMessagesAndSyncJobs() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-store-test");
    DemoStore store = new DemoStore(Config.forTests(dir));
    store.ensureSeedData();

    assertEquals("1", Integer.toString(store.tenants().size()));
    assertEquals("4", Integer.toString(store.contacts().size()));
    assertEquals("2", Integer.toString(store.syncJobs().size()));

    List<MessageRecord> thread = store.messagesForContact("contact-ext-001");
    assertEquals("2", Integer.toString(thread.size()));
    assertEquals("archive", thread.get(1).channel);
    assertContains(thread.get(1).rawJson, "encrypt_random_key");
}

private static void appendsMessagesWithoutDroppingRawJson() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-message-test");
    DemoStore store = new DemoStore(Config.forTests(dir));
    MessageRecord message = MessageRecord.outbound("tenant-demo", "contact-ext-001",
            "wecom_kf", "客服您好", "{\"source\":\"unit-test\"}");

    store.appendMessage(message);

    MessageRecord saved = store.findMessage(message.id);
    assertEquals("wecom_kf", saved.channel);
    assertEquals("outbound", saved.direction);
    assertContains(saved.rawJson, "unit-test");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: FAIL with compiler errors for missing model and store classes.

- [ ] **Step 3: Implement models and `DemoStore`**

Implement:

```java
public class Tenant {
    public String id;
    public String name;
    public String corpId;
    public String agentId;
    public String suiteId;
    public boolean installed;
    public List<String> scopes = new ArrayList<>();
    public String tokenStatus;
}
```

```java
public class Contact {
    public String id;
    public String tenantId;
    public String displayName;
    public String type;
    public String externalUserId;
    public String memberUserId;
    public String kfOpenId;
    public List<String> tags = new ArrayList<>();
    public List<String> channels = new ArrayList<>();
    public String lastText;
    public String lastAt;
    public String rawJson;
}
```

```java
public class MessageRecord {
    public String id;
    public String tenantId;
    public String contactId;
    public String channel;
    public String direction;
    public String text;
    public String status;
    public String timestamp;
    public String rawJson;
    public AttachmentRecord attachment;

    public static MessageRecord outbound(String tenantId, String contactId, String channel, String text, String rawJson) {
        MessageRecord message = new MessageRecord();
        message.id = "msg-" + java.util.UUID.randomUUID();
        message.tenantId = tenantId;
        message.contactId = contactId;
        message.channel = channel;
        message.direction = "outbound";
        message.text = text;
        message.status = "local_success";
        message.timestamp = java.time.Instant.now().toString();
        message.rawJson = rawJson;
        return message;
    }
}
```

Implement `AttachmentRecord`, `SyncJob`, `TenantRegistry`, `ContactStore`, `MessageStore`, and `DemoStore` so seed data creates one tenant, four contacts, at least four messages, and two sync jobs named `external_contact_sync` and `archive_import`. `DemoStore` must delegate tenant methods to `TenantRegistry`, contact methods to `ContactStore`, and message/attachment methods to `MessageStore`.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: command exits 0 with no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Tenant.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Contact.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageRecord.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/AttachmentRecord.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/SyncJob.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/TenantRegistry.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ContactStore.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MessageStore.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DemoStore.java demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java
git commit -m "feat: add wecom saas local store"
```

---

### Task 3: Service Layer for Send, Upload, Sync, Webhooks, Archive Import

**Files:**
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomApiClient.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/LocalWecomApiClient.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DataSyncCenter.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ArchiveImportCenter.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomSaasService.java`
- Modify: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`

**Interfaces:**
- Consumes: `DemoStore`, `MessageRecord`, `AttachmentRecord`, `SyncJob`
- Produces: `WecomApiClient.sendMessage(String tenantId, String contactId, String channel, String text, AttachmentRecord attachment)`, `WecomApiClient.uploadAttachment(AttachmentRecord attachment)`
- Produces: `DataSyncCenter.run(String jobName)`, `ArchiveImportCenter.importJsonl(Path source)`
- Produces: `WecomSaasService.sendMessage(...)`, `saveAttachment(...)`, `runSync(String jobName)`, `acceptWebhook(String channel, String rawJson)`, `importArchiveJsonl(Path source)`

- [ ] **Step 1: Add failing service tests**

Append these calls to `main`:

```java
sendsLocalWecomMessageWithAdapterRawJson();
savesAttachmentMetadataAndRejectsOversizedUpload();
runsDataSyncAndImportsArchiveJsonl();
projectsWebhookPayloadIntoMessage();
```

Add test methods:

```java
private static void sendsLocalWecomMessageWithAdapterRawJson() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-send-test");
    DemoStore store = new DemoStore(Config.forTests(dir));
    store.ensureSeedData();
    WecomSaasService service = new WecomSaasService(store, new LocalWecomApiClient());

    MessageRecord message = service.sendMessage("tenant-demo", "contact-ext-001", "wecom_kf", "您好，报价已更新", null);

    assertEquals("wecom_kf", message.channel);
    assertEquals("outbound", message.direction);
    assertContains(message.rawJson, "local_wecom_api");
}

private static void savesAttachmentMetadataAndRejectsOversizedUpload() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-attachment-test");
    Config config = Config.forTests(dir);
    DemoStore store = new DemoStore(config);
    WecomSaasService service = new WecomSaasService(store, new LocalWecomApiClient());

    AttachmentRecord attachment = service.saveAttachment("tenant-demo", "报价 单.pdf",
            "application/pdf", "hello".getBytes(StandardCharsets.UTF_8));

    assertEquals("报价 单.pdf", attachment.fileName);
    assertEquals("5", Long.toString(attachment.size));
    assertContains(attachment.localUrl, "/uploads/");

    try {
        service.saveAttachment("tenant-demo", "big.bin", "application/octet-stream",
                new byte[(int) config.maxUploadBytes() + 1]);
        throw new AssertionError("Expected oversized upload to fail");
    } catch (ApiError error) {
        assertEquals("413", Integer.toString(error.status()));
        assertEquals("AttachmentTooLarge", String.valueOf(error.body().get("error")));
    }
}

private static void runsDataSyncAndImportsArchiveJsonl() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-sync-test");
    DemoStore store = new DemoStore(Config.forTests(dir));
    store.ensureSeedData();
    WecomSaasService service = new WecomSaasService(store, new LocalWecomApiClient());

    SyncJob sync = service.runSync("external_contact_sync");
    assertEquals("success", sync.status);
    assertContains(sync.lastResult, "contacts");

    Path archive = dir.resolve("archive.jsonl");
    Files.writeString(archive, "{\"msgid\":\"archive-001\",\"from\":\"zhangsan\",\"tolist\":[\"lisi\"],\"text\":{\"content\":\"存档消息\"},\"encrypt_random_key\":\"sample\"}\n", StandardCharsets.UTF_8);
    int imported = service.importArchiveJsonl(archive);

    assertEquals("1", Integer.toString(imported));
    assertContains(store.messagesForContact("contact-archive").get(0).rawJson, "archive-001");
}

private static void projectsWebhookPayloadIntoMessage() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-webhook-test");
    DemoStore store = new DemoStore(Config.forTests(dir));
    WecomSaasService service = new WecomSaasService(store, new LocalWecomApiClient());

    MessageRecord message = service.acceptWebhook("wecom_kf",
            "{\"tenantId\":\"tenant-demo\",\"contactId\":\"contact-kf-001\",\"text\":\"访客进线\",\"msgid\":\"kf-msg-1\"}");

    assertEquals("inbound", message.direction);
    assertEquals("wecom_kf", message.channel);
    assertContains(message.rawJson, "kf-msg-1");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: FAIL with compiler errors for missing service and adapter classes.

- [ ] **Step 3: Implement service and local adapter**

Implement `LocalWecomApiClient` so it returns raw JSON with:

```json
{"provider":"local_wecom_api","status":"success"}
```

Implement `DataSyncCenter.run(String jobName)` to update `SyncJob.status`, `SyncJob.lastSyncAt`, and `SyncJob.lastResult`. Implement `ArchiveImportCenter.importJsonl(Path source)` to parse one JSON object per line, map each item to `channel="archive"` and `contactId="contact-archive"`, append to `MessageStore`, and return the imported count.

Implement `WecomSaasService` validations and delegation:

- Unknown channel must throw `new ApiError(422, "UnsupportedChannel", "不支持的企业微信消息渠道", JsonSupport.map("channel", channel))`.
- Missing contact must throw `new ApiError(404, "ContactNotFound", "找不到联系人", JsonSupport.map("contactId", contactId))`.
- Upload larger than `Config.maxUploadBytes()` must throw `new ApiError(413, "AttachmentTooLarge", "附件超过本地 demo 限制", JsonSupport.map("maxBytes", config.maxUploadBytes()))`.
- `importArchiveJsonl` must keep raw JSON in `MessageRecord.rawJson`.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: command exits 0 with no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomApiClient.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/LocalWecomApiClient.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/DataSyncCenter.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/ArchiveImportCenter.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/WecomSaasService.java demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java
git commit -m "feat: add wecom saas service layer"
```

---

### Task 4: HTTP Router and Multipart Upload

**Files:**
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MultipartForm.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Router.java`
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/App.java`
- Modify: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`

**Interfaces:**
- Consumes: `WecomSaasService`, `DemoStore`, `ApiError`
- Produces: `Router.handle(HttpExchange exchange)`, `Router.Response forTests(String method, String path, String query, byte[] body, String contentType)`
- Produces: `MultipartForm.parse(String contentType, byte[] body, long maxBytes)`
- Produces: `App.main(String[] args)` with command `web`

- [ ] **Step 1: Add failing HTTP tests**

Append these calls to `main`:

```java
routesCoreJsonApisForWorkbench();
parsesMultipartUploadWithUtf8FileName();
```

Add test methods:

```java
private static void routesCoreJsonApisForWorkbench() throws Exception {
    Path dir = Files.createTempDirectory("wecom-saas-router-test");
    Config config = Config.forTests(dir);
    DemoStore store = new DemoStore(config);
    store.ensureSeedData();
    Router router = new Router(config, store, new WecomSaasService(store, new LocalWecomApiClient()));

    Router.Response contacts = router.forTests("GET", "/api/contacts", "", new byte[0], "");
    assertEquals("200", Integer.toString(contacts.status));
    assertContains(contacts.body, "深圳海运客户");

    Router.Response sent = router.forTests("POST", "/api/messages/send", "",
            "{\"tenantId\":\"tenant-demo\",\"contactId\":\"contact-ext-001\",\"channel\":\"wecom_app\",\"text\":\"应用消息\"}".getBytes(StandardCharsets.UTF_8),
            "application/json");
    assertEquals("200", Integer.toString(sent.status));
    assertContains(sent.body, "应用消息");

    Router.Response missing = router.forTests("GET", "/api/missing", "", new byte[0], "");
    assertEquals("404", Integer.toString(missing.status));
    assertContains(missing.body, "NotFound");
}

private static void parsesMultipartUploadWithUtf8FileName() throws Exception {
    String boundary = "----crm-boundary";
    String payload = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"tenantId\"\r\n\r\n"
            + "tenant-demo\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"报价单.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n"
            + "abc\r\n"
            + "--" + boundary + "--\r\n";

    MultipartForm form = MultipartForm.parse("multipart/form-data; boundary=" + boundary,
            payload.getBytes(StandardCharsets.UTF_8), 1024);

    assertEquals("tenant-demo", form.fields.get("tenantId"));
    assertEquals("报价单.pdf", form.fileName);
    assertEquals("application/pdf", form.contentType);
    assertEquals("3", Integer.toString(form.fileBytes.length));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: FAIL with compiler errors for missing `Router`, `MultipartForm`, and `App`.

- [ ] **Step 3: Implement router, multipart parser, and app entry**

Implement route map:

- `GET /` returns `UiRenderer.pageHtml()`.
- `GET /api/tenants` returns `store.tenants()`.
- `GET /api/contacts` returns `store.contacts()`.
- `GET /api/messages?contactId=...` returns `store.messagesForContact(contactId)`.
- `GET /api/messages/{id}` returns `store.findMessage(id)`.
- `POST /api/messages/send` calls `service.sendMessage(...)`.
- `POST /api/attachments` parses multipart and calls `service.saveAttachment(...)`.
- `GET /api/sync/jobs` returns `store.syncJobs()`.
- `POST /api/sync/run` calls `service.runSync(jobName)`.
- `POST /api/archive/import` calls `service.importArchiveJsonl(config.wecomArchiveJsonl())`.
- `POST /webhook/wecom/app` calls `service.acceptWebhook("wecom_app", rawJson)`.
- `POST /webhook/wecom/kf` calls `service.acceptWebhook("wecom_kf", rawJson)`.

Implement JSON errors by catching `ApiError` and returning `error.status()` plus `error.body()`.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: command exits 0 with no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/MultipartForm.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/Router.java demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/App.java demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java
git commit -m "feat: expose wecom saas api routes"
```

---

### Task 5: Three-Column Workbench UI

**Files:**
- Create: `demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/UiRenderer.java`
- Modify: `demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java`

**Interfaces:**
- Consumes: `/api/tenants`, `/api/contacts`, `/api/messages`, `/api/messages/send`, `/api/attachments`, `/api/sync/jobs`, `/api/sync/run`, `/api/archive/import`
- Produces: `UiRenderer.pageHtml()`

- [ ] **Step 1: Add failing UI test**

Append this call to `main`:

```java
rendersThreeColumnChineseWorkbench();
```

Add test method:

```java
private static void rendersThreeColumnChineseWorkbench() {
    String html = UiRenderer.pageHtml();

    assertContains(html, "企业微信 SaaS 工作台");
    assertContains(html, "客户");
    assertContains(html, "消息");
    assertContains(html, "微信客服");
    assertContains(html, "会话存档");
    assertContains(html, "数据同步");
    assertContains(html, "raw JSON");
    assertContains(html, "grid-template-columns:320px minmax(460px, 1fr) 380px");
    assertContains(html, "fetch('/api/contacts')");
    assertContains(html, "fetch('/api/messages/send'");
    assertNotContains(html, "缁");
    assertNotContains(html, "閿");
}

private static void assertNotContains(String value, String unexpected) {
    if (value != null && value.contains(unexpected)) {
        throw new AssertionError("Expected [" + value + "] to not contain [" + unexpected + "]");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: FAIL with compiler error for missing `UiRenderer`.

- [ ] **Step 3: Implement UI renderer**

Create `UiRenderer.pageHtml()` with:

- Full-height shell: `.shell { height:100vh; min-height:680px; display:grid; grid-template-columns:320px minmax(460px, 1fr) 380px; }`
- Left pane: tenant status, customer list, filters for `all`、`external`、`kf_visitor`、`member`、`group`。
- Middle pane: tabs `客户`、`消息`、`微信客服`、`会话存档`、`数据同步`、`设置`， message thread, text send form, attachment upload form.
- Right pane: contact profile, selected message detail, raw JSON `<pre>`.
- JS functions: `loadContacts()`, `selectContact(id)`, `loadMessages()`, `sendMessage()`, `uploadAttachment()`, `runSync(jobName)`, `importArchive()`, `selectMessage(id)`.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
```

Expected: command exits 0 with no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/src/main/java/com/crmforlogistics/wecomsaas/UiRenderer.java demo/wecom-saas-demo/src/test/java/com/crmforlogistics/wecomsaas/WecomSaasDemoTest.java
git commit -m "feat: add wecom saas workbench ui"
```

---

### Task 6: Documentation and Final Verification

**Files:**
- Create: `demo/wecom-saas-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-14-wecom-saas-demo-design.md`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: user-facing runbook and spec status update.

- [ ] **Step 1: Write README**

Create `demo/wecom-saas-demo/README.md` with sections:

```markdown
# wecom-saas-demo

企业微信 SaaS 工作台 demo，用于演示服务商系统里的客户、消息、微信客服、附件、会话存档和数据 API 同步。

## 边界

- 本 demo 独立运行，不修改 message-center-demo。
- 第一版使用本地 JSON/JSONL 和模拟企业微信 adapter。
- 普通回调和会话内容存档是两条链路。
- 不保存真实密钥；生产前需要轮换所有曾经暴露过的密钥。

## 启动

```bash
cd /Users/z/workItem/CRMforLogistics/demo/wecom-saas-demo
cp config.example.env .env
mvn -q exec:java "-Dexec.args=web"
```

默认地址：

```text
http://localhost:8098
```

## 测试

```bash
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
mvn -q test
mvn -q compile
```

## API

- `GET /api/tenants`
- `GET /api/contacts`
- `GET /api/messages?contactId=...`
- `POST /api/messages/send`
- `POST /api/attachments`
- `GET /api/sync/jobs`
- `POST /api/sync/run`
- `POST /api/archive/import`
- `POST /webhook/wecom/app`
- `POST /webhook/wecom/kf`
```

- [ ] **Step 2: Mark design spec as implemented for first local demo pass**

Append to `docs/superpowers/specs/2026-07-14-wecom-saas-demo-design.md`:

```markdown
## 第一版实现记录

本地 demo 第一版落在 `demo/wecom-saas-demo`。它实现本地 JSON/JSONL 存储、模拟企业微信 adapter、三栏工作台、消息发送模拟、附件本地保存、微信客服回调入口、会话存档 JSONL 导入和数据同步状态。真实第三方服务商授权安装和真实企业微信 API 请求仍属于第二阶段。
```

- [ ] **Step 3: Run full verification**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
mvn -q test
mvn -q compile
```

Expected: all commands exit 0.

- [ ] **Step 4: Start local web server**

Run:

```bash
cd demo/wecom-saas-demo
mvn -q exec:java "-Dexec.args=web"
```

Expected output includes:

```text
WeCom SaaS demo started: http://localhost:8098
```

Stop the server after confirming startup.

- [ ] **Step 5: Commit**

```bash
git add demo/wecom-saas-demo/README.md docs/superpowers/specs/2026-07-14-wecom-saas-demo-design.md
git commit -m "docs: document wecom saas demo"
```

---

## Self-Review

- Spec coverage: Tasks cover new demo creation, local JSON/JSONL storage, customer/contact projection, unified messages, sending, upload, 微信客服回调, 会话存档导入, 数据 API 同步状态, three-column UI, README, and verification commands.
- Scope: The plan keeps full service-provider authorization, real suite ticket processing, and real Finance SDK execution outside the first local demo.
- Interface consistency: `Config`, `TenantRegistry`, `ContactStore`, `MessageStore`, `DemoStore`, `DataSyncCenter`, `ArchiveImportCenter`, `WecomSaasService`, `Router`, and `UiRenderer` names are introduced before use in later tasks.
- Test strategy: Each implementation task begins with a failing Java main test and ends with the exact Maven command that must pass.
