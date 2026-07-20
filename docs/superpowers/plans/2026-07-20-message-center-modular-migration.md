# Message Center 模块化前后端迁移实施计划

> **执行规则：** 按任务顺序执行并逐项提交。用户已停用除头脑风暴和 plan 之外的自动 skill；执行时不得自动触发其他 skill。

**Goal:** 将当前扁平 Java/JDK HttpServer demo 迁移为同仓库独立 React 前端、Spring Boot 后端、OpenAPI v1 合同和业务模块内分层结构，同时保持已验证业务行为。

**Architecture:** OpenAPI 是前后端唯一 HTTP 合同。后端按 `web → application → domain` 与 `infrastructure → domain` 分层；前端按 app/pages/widgets/features/entities/shared 分层。迁移完成后删除旧 HttpServer、旧 `/api/*` 和内嵌前端，不保留双运行路径。

**Tech Stack:** Java 17、Maven 3.9、Spring Boot 3、Spring MVC、Spring Security、Flyway、PostgreSQL 17、React 19、Vite、TypeScript strict、TanStack Query 5、Ant Design 5、OpenAPI Generator、ArchUnit、Vitest、React Testing Library、Playwright、Nginx、Docker Compose。

## Global Constraints

- 只修改 `demo/message-center-demo`、本计划关联文档和已确认 PRD/架构真源。
- 原工作区很脏；只在 `/private/tmp/CRMforLogistics-message-center-postgres-minio` 工作。
- 禁止 `git reset`、`git checkout --`、`git add .`。
- 不打印 `.env`、Docker Secret、数据库密码、MinIO 凭据、渠道密钥和完整签名 URL。
- 自动 Web 测试只能使用 8100，结束后必须关闭并确认无监听；8099 只供用户主动访问。
- PostgreSQL 和 MinIO 是唯一运行真源；JSONL 不得进入新运行时。
- 不引入 Chatwoot、Redis、RocketMQ、Elasticsearch、pgvector、JWT 或第二套 session schema。
- 前端依赖由 `package-lock.json` 精确锁定；禁止提交 `node_modules`。
- OpenAPI 生成目录只由生成命令写入，禁止手改。
- 每个任务一个独立提交；文件移动与行为修改不得混在同一提交。

## Target File Map

### Repository Roots

- Create: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Create: `demo/message-center-demo/backend/pom.xml`
- Move: `demo/message-center-demo/src` → `demo/message-center-demo/backend/src`
- Create: `demo/message-center-demo/frontend/package.json`
- Create: `demo/message-center-demo/frontend/package-lock.json`
- Create: `demo/message-center-demo/frontend/src/**`
- Create: `demo/message-center-demo/deploy/nginx.conf`
- Modify: `demo/message-center-demo/compose.yaml`

### Backend Module Owners

```text
bootstrap/       MessageCenterApplication, CLI commands, composition
shared/          config, database infrastructure, error envelope, trace
audit/           audit application service and JDBC implementation
auth/            users, roles, password, session, Spring Security
contact/         contacts, identities, merge/split, tags
company/         companies, links, phone notes
messaging/       conversations, messages, status, unread, access, inbox/outbox
channel/         channel accounts, templates, cursors, Email/ChatApp/WeCom adapters
media/           attachment metadata, MinIO, authorized stream
importjob/       explicit one-shot imports and reconciliation
```

### Frontend Owners

```text
app/             providers, router, auth boundary, theme, error boundary
pages/           LoginPage, MessageCenterPage
widgets/         ContactListPane, ConversationPane, ContactDetailPane, MessageComposer
features/        auth, contact editing, sending, synchronization, media preview
entities/        contact, company, conversation, message, channel-account view models
shared/          generated-client wrapper, SSE, common UI and formatting
generated/       OpenAPI-generated client and models
```

---

## Task M0: 闭合 Task 6 行为基线

**Files:**
- Commit staged Task 6 files listed by `git diff --cached --name-only`.
- Read: `.superpowers/sdd/task-6-report.md`
- Read: `.superpowers/sdd/task-6-review.md`

**Interfaces:**
- Produces stable local auth, opaque session, audit, assignment and read-access behavior before any file movement.

- [ ] **Step 1: Confirm staged boundary**

Run:

```bash
git diff --cached --name-only
git diff --cached --check
```

Expected: exactly the 13 Task 6 Java/test files; no docs, Task 7 files or other demos; diff check has no output.

- [ ] **Step 2: Run focused behavior gate**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,AuthAndAccessIT,JdbcContactRepositoryIT,JdbcChannelAccountRepositoryIT test
```

Expected: PASS against PostgreSQL 17.5 Testcontainers.

- [ ] **Step 3: Run full backend baseline**

Run:

```bash
mvn -q verify
```

Expected: PASS. The known JNA native-access warning is recorded as external toolchain debt and must not be attributed to application logging.

- [ ] **Step 4: Commit behavior only**

```bash
git commit -m "fix: harden auth and audit boundaries"
```

Expected: one Task 6 fix commit after `6368bc6`.

---

## Task M1: OpenAPI v1 Contract and Generation Gates

**Files:**
- Create: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Create: `demo/message-center-demo/contracts/openapi/.spectral.yaml`
- Test: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- Produces `/api/v1` operations, `ApiError`, cursor pages, auth Cookie, attachment stream and `SseEvent` schemas.
- Produces operation IDs used by generated Java and TypeScript clients.

- [ ] **Step 1: Write contract structure test**

```javascript
import assert from 'node:assert/strict';
import fs from 'node:fs';

const contract = fs.readFileSync(new URL('./message-center-v1.yaml', import.meta.url), 'utf8');
for (const value of [
  'openapi: 3.1.0',
  '/api/v1/auth/login:',
  '/api/v1/auth/csrf:',
  '/api/v1/contacts:',
  '/api/v1/companies:',
  '/api/v1/conversations/{conversationId}/messages:',
  '/api/v1/attachments/{attachmentId}/content:',
  '/api/v1/events:',
  '/api/v1/webhooks/{channelType}:',
  'ApiError:',
  'SseEvent:'
]) assert.ok(contract.includes(value), `missing ${value}`);
assert.ok(!contract.includes('/api/contacts:'), 'legacy unversioned route is forbidden');
```

- [ ] **Step 2: Verify RED**

Run:

```bash
node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
```

Expected: FAIL because `message-center-v1.yaml` does not exist.

- [ ] **Step 3: Write the contract**

The contract starts with:

```yaml
openapi: 3.1.0
info:
  title: CRM for Logistics Message Center API
  version: 1.0.0
servers:
  - url: /
paths:
  /api/v1/auth/login:
    post:
      operationId: login
  /api/v1/auth/logout:
    post:
      operationId: logout
  /api/v1/auth/session:
    get:
      operationId: getSession
  /api/v1/auth/csrf:
    get:
      operationId: getCsrfToken
  /api/v1/contacts:
    get:
      operationId: listContacts
  /api/v1/contacts/{contactId}:
    get:
      operationId: getContact
    patch:
      operationId: updateContact
  /api/v1/contacts/{contactId}/merge:
    post:
      operationId: mergeContact
  /api/v1/contact-identities/{identityId}/split:
    post:
      operationId: splitContactIdentity
  /api/v1/companies:
    get:
      operationId: listCompanies
    post:
      operationId: createCompany
  /api/v1/conversations/{conversationId}/messages:
    get:
      operationId: listMessages
    post:
      operationId: sendMessage
  /api/v1/conversations/{conversationId}/read:
    post:
      operationId: markConversationRead
  /api/v1/attachments/{attachmentId}/content:
    get:
      operationId: getAttachmentContent
  /api/v1/channel-accounts:
    get:
      operationId: listChannelAccounts
  /api/v1/sync/{channelType}:
    post:
      operationId: synchronizeChannel
  /api/v1/events:
    get:
      operationId: streamEvents
  /api/v1/webhooks/{channelType}:
    post:
      operationId: receiveChannelWebhook
components:
  securitySchemes:
    sessionCookie:
      type: apiKey
      in: cookie
      name: MESSAGE_CENTER_SESSION
  schemas:
    ApiError:
      type: object
      required: [code, message, traceId, fieldErrors]
    SseEvent:
      type: object
      required: [type, version, resourceId, sequence, occurredAt, payload]
```

Every operation defines request/response schemas, authentication, `ApiError` responses and explicit cursor limits. Media response uses bounded documented content types and `application/octet-stream` fallback.

- [ ] **Step 4: Add lint rules**

```yaml
extends: spectral:oas
rules:
  operation-operationId: error
  operation-tags: error
  no-$ref-siblings: error
```

- [ ] **Step 5: Verify GREEN and commit**

```bash
node demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
git add demo/message-center-demo/contracts/openapi/message-center-v1.yaml demo/message-center-demo/contracts/openapi/.spectral.yaml demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
git commit -m "docs: define message center api v1"
```

---

## Task M2: Physical Backend and Frontend Project Split

**Files:**
- Move: `demo/message-center-demo/pom.xml` → `demo/message-center-demo/backend/pom.xml`
- Move: `demo/message-center-demo/src` → `demo/message-center-demo/backend/src`
- Modify: `demo/message-center-demo/message-center-demo.ps1`
- Modify: `demo/message-center-demo/README.md`
- Create: `demo/message-center-demo/frontend/package.json`
- Create: `demo/message-center-demo/frontend/package-lock.json`
- Create: `demo/message-center-demo/frontend/.gitignore`
- Create: `demo/message-center-demo/frontend/.nvmrc`
- Create: `demo/message-center-demo/frontend/tsconfig.json`
- Create: `demo/message-center-demo/frontend/vite.config.ts`
- Create: `demo/message-center-demo/frontend/src/main.tsx`
- Create: `demo/message-center-demo/frontend/src/app/App.tsx`
- Test: `demo/message-center-demo/frontend/src/app/App.test.tsx`

**Interfaces:**
- Preserves the current Java package and runtime behavior during this physical-only move.
- Produces independent `backend` Maven and `frontend` npm build roots.

- [ ] **Step 1: Record physical baseline**

```bash
cd demo/message-center-demo
mvn -q test
```

Expected: PASS before moving files.

- [ ] **Step 2: Move backend without changing Java packages**

```bash
git mv demo/message-center-demo/pom.xml demo/message-center-demo/backend/pom.xml
git mv demo/message-center-demo/src demo/message-center-demo/backend/src
```

Update Maven contract path to `${project.basedir}/../contracts/openapi/message-center-v1.yaml`. Do not rename Java packages in this task.

- [ ] **Step 3: Create frontend build root**

`package.json` defines exact scripts:

```json
{
  "private": true,
  "scripts": {
    "generate:api": "rimraf src/generated && openapi-generator-cli generate -i ../contracts/openapi/message-center-v1.yaml -g typescript-fetch -o src/generated --additional-properties=supportsES6=true,typescriptThreePlus=true",
    "predev": "npm run generate:api",
    "pretypecheck": "npm run generate:api",
    "pretest": "npm run generate:api",
    "prebuild": "npm run generate:api",
    "dev": "vite",
    "lint": "eslint . --max-warnings 0",
    "typecheck": "tsc --noEmit",
    "test": "vitest run",
    "build": "tsc -b && vite build"
  }
}
```

Install React 19, Vite, TypeScript strict, TanStack Query 5, Ant Design 5, Vitest, React Testing Library, ESLint 9, `rimraf` and OpenAPI Generator CLI; commit `package-lock.json`. `.nvmrc` contains `22`; `.gitignore` contains `node_modules/`, `dist/` and `src/generated/`.

- [ ] **Step 4: Write frontend shell test**

```tsx
import { render, screen } from '@testing-library/react';
import { App } from './App';

it('renders the message center application shell', () => {
  render(<App />);
  expect(screen.getByRole('main')).toBeInTheDocument();
});
```

- [ ] **Step 5: Verify both roots**

```bash
cd demo/message-center-demo/backend && mvn -q test
cd ../frontend && npm ci && npm run typecheck && npm test && npm run build
```

Expected: all PASS.

- [ ] **Step 6: Commit physical split**

Stage only moved backend paths, frontend build files, README and PowerShell launcher.

```bash
git commit -m "chore: split message center frontend and backend"
```

---

## Task M3: Shared, Audit and Auth Module Packages

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/bootstrap/MessageCenterApplication.java` as Spring test/composition root; keep old `App` as the selected web runtime until Task M7.
- Move `Config.java` → `backend/src/main/java/com/crmforlogistics/messagecenter/shared/config/MessageCenterProperties.java`
- Move `Database.java` → `backend/src/main/java/com/crmforlogistics/messagecenter/shared/database/Database.java`
- Move `JsonSupport.java` → `backend/src/main/java/com/crmforlogistics/messagecenter/shared/json/JsonSupport.java`
- Move `AuditService.java` → `backend/src/main/java/com/crmforlogistics/messagecenter/audit/application/AuditService.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/audit/domain/AuditEntry.java`, `AuditRepository.java`
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/audit/infrastructure/JdbcAuditRepository.java`
- Move `JdbcAuthRepository.java` → `backend/src/main/java/com/crmforlogistics/messagecenter/auth/infrastructure/JdbcAuthRepository.java`
- Move `PasswordHasher.java` into auth domain/infrastructure files.
- Move `SessionService.java` into auth application/domain files.
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/auth/domain/AuthRepository.java`
- Create: `backend/src/test/java/com/crmforlogistics/messagecenter/architecture/ModuleArchitectureTest.java`

**Interfaces:**
- Produces public auth records/interfaces in one file each: `UserDraft`, `AuthUser`, `BootstrapResult`, `IssuedSession`, `AuthenticatedSession`, `PasswordSupplier`, `PasswordHashSupplier`.
- Produces `PasswordHasher` domain port and `Argon2idPasswordHasher` infrastructure implementation.

```java
public interface AuthRepository {
    UUID createUser(UserDraft draft, String passwordHash) throws Exception;
    Optional<AuthUser> findUser(String username) throws Exception;
    BootstrapResult bootstrapAdmin(UserDraft draft, PasswordHashSupplier supplier) throws Exception;
    void createSession(UUID userId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) throws Exception;
    Optional<AuthenticatedSession> findSession(byte[] tokenHash, Instant now) throws Exception;
    Optional<AuthenticatedSession> revokeSession(byte[] tokenHash, Instant now) throws Exception;
}

public interface AuditRepository {
    void append(AuditEntry entry) throws Exception;
}

public record AuditEntry(
        UUID actorUserId, String action, String resourceType, UUID resourceId,
        Map<String, ?> before, Map<String, ?> after, String result,
        Instant occurredAt) {}
```

- [ ] **Step 1: Write ArchUnit RED test**

```java
@AnalyzeClasses(packages = "com.crmforlogistics.messagecenter")
class ModuleArchitectureTest {
    @ArchTest
    static final ArchRule root_contains_no_classes = noClasses()
            .should().resideInAPackage("com.crmforlogistics.messagecenter");

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "java.sql..", "io.minio..");
}
```

Run `mvn -q -Dtest=ModuleArchitectureTest test`; expected FAIL while classes remain in the root package.

- [ ] **Step 2: Add Spring transaction foundation**

Set Spring Boot parent to `3.5.4`, Java release 17, and add JDBC, Flyway, test and ArchUnit dependencies. `MessageCenterApplication` is available to integration tests, but Maven/launcher continues selecting the old `App` runtime until Task M7 so no second HTTP server starts.

- [ ] **Step 3: Split package-private contracts**

Create one public type per file. Preserve exact record components already verified by Task 6; do not add legacy wrapper records in the old package.

- [ ] **Step 4: Separate auth and audit repositories**

`JdbcAuthRepository` implements `AuthRepository` with Spring JDBC and participates in the caller transaction. `JdbcAuditRepository` implements `AuditRepository`. `auth.application.SessionService` is the transaction owner: session create/revoke/bootstrap and `AuditService.record` occur in one `@Transactional` method. Audit failure rolls back auth state. `Argon2idPasswordHasher` implements `PasswordHasher`.

- [ ] **Step 5: Move shared/audit/auth classes and tests**

Rename `ConfigTest` to `MessageCenterPropertiesTest`, move `AuthAndAccessIT` into the auth test package, and move `CredentialCipherTest` with the channel security implementation in Task M5. Preserve all Testcontainers assertions.

- [ ] **Step 6: Verify and commit**

```bash
cd demo/message-center-demo/backend
mvn -q -Dtest=ModuleArchitectureTest,MessageCenterPropertiesTest,AuthAndAccessIT test
mvn -q test-compile
git diff --check
git commit -m "refactor: separate shared audit and auth modules"
```

---

## Task M4: Contact and Company Module Packages

**Files:**
- Move `ContactRepository.java`, `JdbcContactRepository.java`, `ContactPoint.java`, `ContactPointUtil.java` into `contact/domain` and `contact/infrastructure`.
- Move `UnifiedContact.java` to `contact/application/model/UnifiedContact.java`.
- Move `CompanyRepository.java`, `JdbcCompanyRepository.java`, `PhoneNoteRepository.java`, `JdbcPhoneNoteRepository.java` into `company/domain` and `company/infrastructure`.
- Split every repository record into one public domain file.
- Move matching integration tests into module packages.

**Interfaces:**
- Produces `contact.domain.ContactRepository` and public contact contracts.
- Produces `company.domain.CompanyRepository`, `PhoneNoteRepository` and public company contracts.

- [ ] **Step 1: Extend architecture RED tests**

```java
@ArchTest
static final ArchRule contact_does_not_depend_on_company_infrastructure = noClasses()
        .that().resideInAPackage("..contact..")
        .should().dependOnClassesThat().resideInAPackage("..company.infrastructure..");
```

Run the rule before moving; expected FAIL or no matching modular classes.

- [ ] **Step 2: Move contact contracts and implementation**

Use exact file-per-type names: `ContactRepository.java`, `ContactQuery.java`, `ContactIdentity.java`, `ContactIdentityDraft.java`, `UnifiedContact.java`, `JdbcContactRepository.java`.

- [ ] **Step 3: Move company contracts and implementation**

Use exact file-per-type names: `CompanyRepository.java`, `CompanyQuery.java`, `CompanyDraft.java`, `CompanyPatch.java`, `Company.java`, `CompanyContactLink.java`, `CompanyDetails.java`, `PhoneNoteRepository.java`, `PhoneNote.java`, `PhoneNoteDraft.java`, `JdbcCompanyRepository.java`, `JdbcPhoneNoteRepository.java`.

- [ ] **Step 4: Verify and commit**

```bash
mvn -q -Dtest=JdbcContactRepositoryIT,JdbcCompanyPhoneNoteRepositoryIT,ModuleArchitectureTest test
mvn -q test-compile
git diff --check
git commit -m "refactor: separate contact and company modules"
```

---

## Task M5: Messaging, Channel, Media and Import Module Packages

**Files:**
- Move `MessageRepository.java`, `JdbcMessageRepository.java`, `UnifiedMessageStore.java`, `MessageTime.java`, `AccessControlService.java` into messaging layers.
- Move `UnifiedMessage.java` to `messaging/application/model/UnifiedMessage.java`.
- Move channel repository/service/capability, ChatApp/Email send/sync classes and template store into channel layers.
- Move `CredentialCipher.java` and `CredentialCipherTest.java` to `channel/infrastructure/security`.
- Move `MediaGateway.java` into media infrastructure/application.
- Move legacy JSONL stores/writers under `importjob.infrastructure.legacy`; they are not injected into runtime Spring beans.
- Move matching tests.

**Interfaces:**
- Produces `messaging.domain.MessageRepository` and public message records.
- Produces `messaging.application.ConversationAccessService` replacing the old class name `AccessControlService` with no old-package wrapper.
- Produces `channel.domain.ChannelAccountRepository` and adapter ports.

- [ ] **Step 1: Write module dependency RED rules**

```java
@ArchTest
static final ArchRule messaging_domain_does_not_depend_on_channel_infrastructure = noClasses()
        .that().resideInAPackage("..messaging.domain..")
        .should().dependOnClassesThat().resideInAPackage("..channel.infrastructure..");

@ArchTest
static final ArchRule legacy_import_is_not_application_runtime = noClasses()
        .that().resideOutsideOfPackage("..importjob..")
        .should().dependOnClassesThat().resideInAPackage("..importjob.infrastructure.legacy..");
```

- [ ] **Step 2: Split message contracts**

Create exact public files: `MessageRepository`, `MessageCursor`, `MessageDraft`, `MessageWriteResult`, `MessageStatusEvent`, `UnifiedMessage`, `ReadState`.

- [ ] **Step 3: Move messaging application services**

Move query projection from `UnifiedMessageStore` into `MessageQueryService`. Move conversation permission and read state into `ConversationAccessService`. Delete database/file fallback branching from application packages; legacy reads remain only under importjob.

- [ ] **Step 4: Move channel and media classes**

Create channel subpackages `channel.email.infrastructure`, `channel.chatapp.infrastructure`, `channel.wecom.infrastructure`. Move `MediaGateway` into `media.infrastructure` but do not complete MinIO cutover until completion plan Task C2.

- [ ] **Step 5: Verify and commit**

```bash
mvn -q -Dtest=JdbcMessageRepositoryIT,JdbcChannelAccountRepositoryIT,MessageQueryServiceTest,CredentialCipherTest,ModuleArchitectureTest test
mvn -q test-compile
git diff --check
git commit -m "refactor: separate messaging channel and media modules"
```

---

## Task M6: React Application and Existing UI Behavior Port

**Files:**
- Create: `frontend/src/app/App.tsx`, `router.tsx`, `providers.tsx`, `theme.ts`
- Create: `frontend/src/pages/LoginPage.tsx`, `MessageCenterPage.tsx`
- Create: `frontend/src/widgets/ContactListPane.tsx`, `ConversationPane.tsx`, `ContactDetailPane.tsx`, `MessageDetailPane.tsx`, `MessageComposer.tsx`
- Create: `frontend/src/entities/contact/ContactCard.tsx`, `frontend/src/entities/message/MessageBubble.tsx`
- Create: `frontend/src/features/auth/**`, `contact-profile/**`, `message-send/**`, `channel-sync/**`, `media-preview/**`
- Create: `frontend/src/shared/api/client.ts`, `queryKeys.ts`, `frontend/src/shared/sse/useMessageCenterEvents.ts`
- Create: `frontend/src/test/msw/handlers.ts`, `server.ts`, `renderMessageCenter.tsx`
- Test: `frontend/src/pages/MessageCenterPage.test.tsx`, feature tests with Vitest and React Testing Library.

**Interfaces:**
- Consumes generated TypeScript client and `SseEvent`.
- Produces UI parity without depending on the old embedded page.

- [ ] **Step 1: Write page RED tests**

```tsx
it('renders contacts, selected conversation and contact details', async () => {
  renderMessageCenter();
  expect(await screen.findByRole('list', { name: '联系人' })).toBeVisible();
  expect(screen.getByRole('region', { name: '消息' })).toBeVisible();
  expect(screen.getByRole('complementary', { name: '联系人资料' })).toBeVisible();
});

it('switches the right pane from contact profile to message details', async () => {
  renderMessageCenter();
  await userEvent.click(await screen.findByText('测试消息'));
  expect(screen.getByRole('heading', { name: '消息详情' })).toBeVisible();
});
```

- [ ] **Step 2: Create app shell**

`app/App.tsx` contains only Router and providers. `pages/MessageCenterPage.tsx` composes widgets. No page file owns API fetch details.

- [ ] **Step 3: Port three-pane UI**

Create `ContactListPane`, `ConversationPane`, `ContactDetailPane`, `MessageDetailPane`, `MessageComposer`, `MessageBubble`, `MediaPreview`, `ChannelAccountSelect`. Preserve unread badges, detail-pane transitions, media preview, fixed composer height, short-message fit-content bubbles and the 56-emoji picker.

- [ ] **Step 4: Add TanStack Query and SSE cache updates**

`shared/api/queryKeys.ts` defines stable keys. `shared/sse/useMessageCenterEvents.ts` parses versioned events and invalidates only affected contacts, conversations, messages or channel-account queries.

- [ ] **Step 5: Verify frontend**

```bash
cd demo/message-center-demo/frontend
npm run generate:api
npm run lint
npm run typecheck
npm test
npm run build
```

Expected: all PASS with zero warnings.

- [ ] **Step 6: Commit frontend behavior port**

```bash
git add demo/message-center-demo/frontend/package.json demo/message-center-demo/frontend/package-lock.json demo/message-center-demo/frontend/.gitignore demo/message-center-demo/frontend/.nvmrc demo/message-center-demo/frontend/tsconfig.json demo/message-center-demo/frontend/vite.config.ts demo/message-center-demo/frontend/src/main.tsx demo/message-center-demo/frontend/src/app demo/message-center-demo/frontend/src/pages demo/message-center-demo/frontend/src/widgets demo/message-center-demo/frontend/src/features demo/message-center-demo/frontend/src/entities demo/message-center-demo/frontend/src/shared demo/message-center-demo/frontend/src/test
git commit -m "feat: add react message center frontend"
```

Do not delete the old embedded page in this task; deletion occurs atomically with backend/API cutover in Task M7.

---

## Task M7: Spring Boot, Spring MVC and Spring Security Cutover

**Files:**
- Modify: `backend/pom.xml`
- Modify: `bootstrap/MessageCenterApplication.java`
- Create: `shared/web/ApiExceptionHandler.java`, `ApiErrorMapper.java`, `TraceIdFilter.java`
- Create: `auth/web/AuthController.java`, `auth/web/CsrfController.java`
- Create: `auth/infrastructure/security/SecurityConfiguration.java`, `SessionAuthenticationFilter.java`, `SessionPrincipal.java`
- Create: `contact/web/ContactController.java`, `company/web/CompanyController.java`
- Create: `messaging/web/ConversationController.java`, `MessageController.java`, `EventController.java`
- Create: `channel/web/ChannelAccountController.java`, `SyncController.java`, `WebhookController.java`
- Create: `media/web/AttachmentController.java`
- Delete: old `App.java` and old JDK HttpServer helpers.

**Interfaces:**
- Consumes generated OpenAPI Java interfaces.
- Produces only `/api/v1` routes and the `MESSAGE_CENTER_SESSION` cookie.

- [ ] **Step 1: Write Spring RED tests**

```java
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationWebIT {
    @Autowired MockMvc mvc;

    @Test
    void unauthenticatedContactsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/contacts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
```

Add tests for login Cookie attributes, CSRF rejection, `/api/v1` error envelope, cursor bounds, SSE content type and attachment access denial.

- [ ] **Step 2: Add web and security dependencies**

Keep the Spring Boot `3.5.4` parent established in Task M3. Add web, security, validation and actuator starters; retain JDBC, Flyway, PostgreSQL, MinIO, Argon2id, mail and CAMS dependencies. Configure test JVM native-access settings so Testcontainers/JNA produces no warning on the supported toolchain.

- [ ] **Step 3: Implement opaque session security**

```java
@Bean
SecurityFilterChain security(HttpSecurity http, SessionAuthenticationFilter sessionFilter) throws Exception {
    return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf", "/actuator/health").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
}
```

The filter hashes the Cookie token, authenticates through `SessionService`, clears temporary bytes and never logs raw credentials. `CsrfController` forces token creation; the frontend reads only the non-sensitive `XSRF-TOKEN` Cookie and sends `X-XSRF-TOKEN` for unsafe requests. The session Cookie remains HttpOnly.

- [ ] **Step 4: Implement controllers and error mapping**

Controllers implement generated API interfaces, call application services and never instantiate repositories/adapters directly. `ApiExceptionHandler` maps structured domain/application errors to OpenAPI `ApiError`.

- [ ] **Step 5: Delete old runtime**

Delete `App.java` JDK HttpServer routing, embedded HTML/CSS/JS, old `/api/*`, `/events` and `/webhook/*` dispatch. Webhooks reappear only as explicit `/api/v1/webhooks/{channelType}` controller operations defined in OpenAPI.

- [ ] **Step 6: Verify backend and commit**

```bash
cd demo/message-center-demo/backend
mvn -q verify
rg -n 'com\.sun\.net\.httpserver|/api/contacts|pageHtml\(' src/main/java
```

Expected: Maven PASS; `rg` has no output.

```bash
git commit -m "feat: cut over to spring boot api"
```

---

## Task M8: Nginx and Compose Frontend/Backend Runtime Split

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `deploy/nginx.conf`
- Modify: `compose.yaml`
- Modify: `message-center-demo.ps1`
- Modify: `README.md`

**Interfaces:**
- Produces Compose services `backend` and `frontend`.
- Exposes only frontend/Nginx on `${MESSAGE_CENTER_PORT:-8099}`.

- [ ] **Step 1: Write Nginx config**

```nginx
location /api/v1/events {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_read_timeout 3600s;
}

location /api/v1/ {
    proxy_pass http://backend:8080;
}

location = /healthz {
    proxy_pass http://backend:8080/actuator/health;
}

location / {
    try_files $uri /index.html;
}
```

- [ ] **Step 2: Add multi-stage images**

Backend build uses Maven 3.9 + Temurin 17 and a non-root Temurin 17 runtime. Frontend build uses `node:22.17.0-bookworm-slim` and Nginx non-root/runtime configuration. Neither image copies `.env`, secrets, test fixtures, Maven cache or `node_modules`.

- [ ] **Step 3: Update Compose**

`backend` depends on healthy PostgreSQL/MinIO and completed migration. `frontend` depends on healthy backend and maps `127.0.0.1:${MESSAGE_CENTER_PORT:-8099}:8080`. Backend has no host `ports` entry.

- [ ] **Step 4: Verify configuration and 8100 runtime**

```bash
cd demo/message-center-demo
docker compose config --quiet
MESSAGE_CENTER_PORT=8100 docker compose up -d --build postgres minio minio-init backend frontend
curl -fsS http://127.0.0.1:8100/
curl -fsS http://127.0.0.1:8100/healthz
docker compose stop frontend backend
lsof -nP -iTCP:8100 -sTCP:LISTEN
```

Expected: config/build/health PASS; final `lsof` has no output.

- [ ] **Step 5: Commit runtime split**

```bash
git commit -m "build: split frontend and backend runtime"
```

---

## Task M9: Architecture Migration Final Gate

**Files:**
- Modify: `README.md`
- Modify: `docs/项目细节PRD.md`
- Modify: architecture design and master roadmap status.
- Test: backend architecture/contract suites and frontend UI suites.

**Interfaces:**
- Produces a clean handoff point for completion plan Task C1.

- [ ] **Step 1: Run contract and backend gates**

```bash
node contracts/openapi/message-center-v1.test.mjs
cd backend
mvn -q verify
```

- [ ] **Step 2: Run frontend gates**

```bash
cd ../frontend
npm ci
npm run generate:api
npm run lint
npm run typecheck
npm test
npm run build
```

- [ ] **Step 3: Run structure scans**

```bash
test "$(find backend/src/main/java/com/crmforlogistics/messagecenter -maxdepth 1 -type f ! -name 'package-info.java' | wc -l | tr -d ' ')" = "0"
rg -n 'com\.sun\.net\.httpserver|pageHtml\(|/api/contacts|/api/threads|/api/messages' backend frontend
rg -n 'Files\.(write|writeString).*jsonl|StandardOpenOption\.APPEND' backend/src/main/java
```

Expected: root package count zero; all scans have no prohibited runtime result.

- [ ] **Step 4: Run 8100 smoke test and close**

Use Compose to validate login page, message center shell, unauthenticated API denial and Nginx SSE proxy on 8100. Stop services and confirm no 8100 listener.

- [ ] **Step 5: Update docs and commit**

Record commands, results, known external toolchain warnings and Task 7 resume boundary.

```bash
git commit -m "docs: complete modular message center migration"
```

## Completion Criteria

- Task M0–M9 each has one reviewable commit and passing task gate.
- `backend` and `frontend` are independent build roots.
- OpenAPI v1 is the only API contract.
- Spring Boot is the only backend runtime.
- React/Nginx is the only browser UI runtime.
- Root Java package, old HttpServer, old routes and embedded UI are absent.
- The next executable task is completion plan Task C1.
