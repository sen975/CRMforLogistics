# 电话自动联系人解析与历史回填 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让新入库电话自动进入当前用户电话通讯录，并用同一规则幂等回填已有孤立电话记录。

**Architecture:** 后端 `ChannelAddressBookService` 负责创建者用户作用域的电话身份解析/创建；`CallRecordService` 在电话入库事务中调用该解析器并写入 `contact_id`。历史记录通过独立的 backfill service/管理员运维接口批量调用同一解析器：已有联系人使用 `contacts.created_by`，孤立电话使用经校验的 `call_records.created_by`，无法证明归属的记录保持未处理。前端只消费统一的电话仓库、通讯录和联系人时间轴接口，不自行创建联系人。

**Tech Stack:** Spring Boot 3.4 / Java 17 / MyBatis-Plus / PostgreSQL / JUnit 5 + Mockito；React + TypeScript / Vitest。

## Global Constraints

- 电话身份的 `identity_scope` 始终为当前用户 UUID。
- 自动建档不得跨用户复用联系人或电话身份。
- 新数据不能接受请求体中的 owner；联系人创建者由认证用户决定。
- 历史记录无法证明联系人创建者时保持未绑定，不进入普通联系人业务 API。
- `contacts.created_by` 是联系人归属唯一真源；`owner_user_id` 字段仅兼容保留，不参与权限判断。
- 使用数据库唯一约束和 `insertIfAbsent` 收敛并发，不创建重复联系人/身份。
- 单条历史回填失败不得阻塞同一批次其他记录；返回处理、跳过、失败和未归属统计。
- 不修改邮件、WhatsApp、企业微信归属规则。

### Task 1: 统一电话地址解析器

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactIdentityMapper.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookServiceTest.java`

**Interfaces:**
- Consumes: authenticated creator `ownerId`, raw phone number, optional existing `contactId`.
- Produces: `ChannelAddressBookService.ResolvedContact` with contact UUID and identity UUID, or a typed conflict/ownership error.

- [ ] **Step 1: Write failing tests** for an existing creator-scoped phone identity, a new phone contact, an existing contact missing the phone identity, cross-creator rejection, and duplicate insert convergence.
- [ ] **Step 2: Run the focused test** with `mvn -q -Dtest=ChannelAddressBookServiceTest test`; confirm the new cases fail before implementation.
- [ ] **Step 3: Implement the minimal resolver** using a single normalization helper, `findByNormalizedValueInScope("phone", ownerId.toString(), digits)`, creator-scoped contact lookup, and `insertIfAbsent` conflict re-read.
- [ ] **Step 4: Run the focused test again** and verify all resolver cases pass.
- [ ] **Step 5: Commit** with `git add` limited to the resolver, mapper, and test files.

### Task 2: 新电话入库自动建档

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java`

**Interfaces:**
- Consumes: `CallRecordService.create(UUID ownerId, CreateCallRecordCommand, InputStream)`.
- Produces: persisted `CallRecordEntity` with non-null `contactId`, phone anchor, and phone point. `ownerUserId` may be populated for legacy compatibility but is not the contact ownership source.

- [ ] **Step 1: Write failing tests** proving a new phone upload without a pre-existing contact creates a number-named contact, reuses an existing owner phone contact, and rejects a supplied contact owned by another user.
- [ ] **Step 2: Run** `mvn -q -Dtest=CallRecordServiceTest test` and verify the new tests fail because the current create path requires an existing contact/identity.
- [ ] **Step 3: Implement** resolver invocation before staging audio; preserve idempotency and queue capacity checks; use the resolved contact UUID and normalized `phone:<digits>` anchor in the entity.
- [ ] **Step 4: Run** the focused service test and the existing call-record tests.
- [ ] **Step 5: Commit** only the service/controller/test changes for this task.

### Task 3: 历史电话回填服务

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordContactBackfillService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordContactBackfillServiceTest.java`

**Interfaces:**
- Consumes: bounded batch size and current time.
- Produces: `BackfillResult(int processed, int createdContacts, int linkedExistingContacts, int skippedUnowned, int failed)`.

- [ ] **Step 1: Write failing tests** for contact creator from `contacts.created_by`, orphan fallback from valid UUID `call_records.created_by`, existing `contact_id` identity repair, orphan record contact creation, and unowned skip.
- [ ] **Step 2: Run** `mvn -q -Dtest=CallRecordContactBackfillServiceTest test`; confirm failure before the service exists.
- [ ] **Step 3: Implement** a bounded mapper query for records missing contact/phone identity, derive the creator from `contacts.created_by` or, for unbound records only, a valid `call_records.created_by`, call the shared resolver, update `call_records` with optimistic version protection, and continue after per-record exceptions.
- [ ] **Step 4: Run** focused tests and verify rerunning the same fixture is idempotent.
- [ ] **Step 5: Commit** the backfill service, mapper methods, and tests.

### Task 4: 受控回填入口与审计响应

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AdminCallRecordBackfillController.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/AdminCallRecordBackfillControllerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java` only if an explicit admin route matcher is required.

**Interfaces:**
- Consumes: `POST /api/v1/admin/call-records/contact-backfill?limit=200`.
- Produces: JSON counts `processed`, `createdContacts`, `linkedExistingContacts`, `skippedUnowned`, `failed`.

- [ ] **Step 1: Write failing MockMvc tests** for admin access, non-admin denial, limit clamping, and stable JSON statistics.
- [ ] **Step 2: Run** `mvn -q -Dtest=AdminCallRecordBackfillControllerTest test` and verify the route is absent/denied.
- [ ] **Step 3: Implement** an admin-only bounded endpoint that delegates to the service; do not expose arbitrary owner IDs in the request.
- [ ] **Step 4: Run** the controller test and verify unauthenticated/non-admin requests cannot trigger backfill.
- [ ] **Step 5: Commit** the endpoint and tests.

### Task 5: 电话仓库、通讯录和时间轴一致性

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/ContactTimelineService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java`
- Modify: `demo/message-center-spring/frontend/src/pages/PhoneRepositoryPage.tsx` only if the new API needs a refresh action.
- Extend: existing phone repository and timeline tests.

**Interfaces:**
- Consumes: resolved `contact_id` and creator-scoped phone identities.
- Produces: one coherent contact view across `/phone-repository`, `/channel-address-books/phone`, and `/contacts/{id}/timeline`.

- [ ] **Step 1: Add failing contract tests** proving an auto-created contact appears in the phone address book, the repository returns its contact ID, and its call record appears in the timeline.
- [ ] **Step 2: Run** the focused backend/frontend tests and verify the contract fails for legacy orphan fixtures.
- [ ] **Step 3: Implement only the missing projections**; retain the existing anchor fallback and deduplication behavior.
- [ ] **Step 4: Run focused tests and the existing frontend source contracts.**
- [ ] **Step 5: Commit** the projection/test changes.

### Task 6: 迁移/运维 SQL 与文档

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V49__phone_contact_backfill_support.sql` only if schema support is required; otherwise add a read-only operational SQL script under `docs/ops/phone-contact-backfill.sql`.
- Create: `docs/ops/phone-contact-backfill.md`
- Modify: `docs/superpowers/specs/2026-09-07-phone-contact-auto-resolution-design.md` with final endpoint/command names if implementation differs.

- [ ] **Step 1: Add a read-only reconciliation SQL** showing total records, owned records, linked records, orphan records, and unowned records.
- [ ] **Step 2: Add deployment and rollback guidance** including the admin endpoint, batch limit, expected counters, and no-direct-update policy.
- [ ] **Step 3: Run SQL syntax checks against the project PostgreSQL container where available, otherwise document that only static validation was possible.**
- [ ] **Step 4: Commit the operational documentation separately.**

### Task 7: 全量验收与发布产物

**Files:**
- Generated: `demo/message-center-spring/backend/target/message-center.jar`
- Generated: `demo/message-center-spring/frontend-dist-20260907-phone-contact-auto-resolution-r1.zip`

- [ ] **Step 1: Run backend focused and full tests** with `mvn -q test`; if Testcontainers is unavailable, record the exact limitation and still run production compilation.
- [ ] **Step 2: Run frontend** `npm run test:source`, `npm run test:ui`, and `npm run build`.
- [ ] **Step 3: Build production Jar** with `mvn -q -DskipTests -Pproduction package` and verify `backend/target/message-center.jar` exists.
- [ ] **Step 4: Package `frontend/dist`** into the named ZIP and run `unzip -t`.
- [ ] **Step 5: Record SHA256 values**, review `git status --short`, and provide deployment commands without staging unrelated user WIP.
