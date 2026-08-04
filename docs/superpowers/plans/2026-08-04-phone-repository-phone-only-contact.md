# 电话仓库与电话唯一联系人实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持只有电话号码的联系人、联系人和号码必填的电话录音上传、可选电话备注，以及跨联系人的电话仓库查询。

**Architecture:** `UnifiedMessageStore` 继续拥有联系人组和 `phone:` 身份；`CallRecordService` 扩展备注和必填号码校验；新增 `PhoneRepository` 只做电话记录全局查询投影，不建立第二套联系人主数据。`CallRecordHttpAdapter` 提供联系人绑定、电话仓库列表和备注更新接口，`App.java` 只负责页面和路由接线。

**Tech Stack:** Java 17、JDK `HttpServer`、Gson、JUnit 5、内嵌 HTML/JavaScript、OpenAPI YAML/Node 合同测试、FunASR 现有异步 worker。

## Global Constraints

- 电话身份统一为 `phone:<6-20 位纯数字>`，规范化由服务端完成。
- 新电话记录必须同时具有联系人身份和 `phonePointId`；不能创建无联系人或无电话号码记录。
- 只有电话身份的联系人仍是正式联系人，不得伪装成 WhatsApp、企业微信或邮件。
- 不建立第二套联系人主数据；联系人合并/拆分继续由 `UnifiedMessageStore` owner 处理。
- 备注最多 4,000 个 Unicode code points，更新必须带 `expectedVersion`。
- 所有电话写接口继续要求现有 viewer 鉴权；请求体不得提供 actor 作为权限来源。
- 保留 MP3、100 MiB、2 小时、播放 Cookie、Range、FunASR 队列和重试边界。
- 禁止 `git add .`；每个任务只暂存其明确文件。

---

### Task 1: 电话身份联系人 owner

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/ContactPointUtil.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Add `UnifiedMessageStore.ensurePhoneContact(String contactPointId, String phoneNumber, String displayName)` returning a normalized contact projection.
- Add `UnifiedMessageStore.bindPhonePoint(String contactPointId, String phoneNumber)` returning `phone:<digits>`.
- Extend file-mode `contacts()` to include contact groups whose only point is `phone:` even when no message exists.

- [ ] **Step 1: Write failing tests** for creating a phone-only group, idempotent rebind, cross-group conflict, and inclusion in `contacts()` with zero messages.
- [ ] **Step 2: Run `mvn -q -Dtest=UnifiedMessageStoreTest test`** and verify the new tests fail on missing owner methods.
- [ ] **Step 3: Implement server-side phone normalization using `ContactPointUtil.normalizePointId`, atomic group-file rewrite, duplicate detection, and display-name profile persistence. Do not add a second contact file.**
- [ ] **Step 4: Run the focused test again and verify phone-only contacts, conflict `PHONE_POINT_CONFLICT`, and idempotency pass.**
- [ ] **Step 5: Run `git diff --check` and commit only the three Task 1 files.**

### Task 2: CallRecord model and service contract

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecord.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepository.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepositoryTest.java`

**Interfaces:**
- Add `String note` to `CallRecord` and its JSON snapshot projection.
- Add `note` to `CallRecordService.CreateCallRecordCommand`.
- Add `CallRecordService.reviseNote(UUID id, String note, String actor, long expectedVersion)`.
- Make `phonePointId` required in `validateCreate`; resolve the contact group and require the normalized phone point to belong to it.

- [ ] **Step 1: Add failing tests** for missing phone rejection, note length/blank handling, note persistence, and optimistic version conflict.
- [ ] **Step 2: Run `mvn -q -Dtest=CallRecordServiceTest,FileCallRecordRepositoryTest test`** and verify failures identify the missing field and method.
- [ ] **Step 3: Implement note validation, JSON persistence, versioned note revision, and mandatory phone binding without changing transcription state transitions.**
- [ ] **Step 4: Run the focused tests and verify old transcription, retry, and audio persistence tests still pass.**
- [ ] **Step 5: Commit only the Task 2 model/service/repository/test files.**

### Task 3: Phone repository query owner

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/PhoneRepository.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/FileCallRecordRepository.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/PhoneRepositoryTest.java`

**Interfaces:**
- Add `PhoneRepository.page(String cursor, int limit, String query, Set<String> allowedAnchors)` returning stable `PhoneRecordPage`.
- Sort by `occurredAt DESC, id DESC`; search normalized phone, contact display name, and note.
- Use the existing `CallRecordRepository` snapshots and contact-group owner; do not duplicate record facts.

- [ ] **Step 1: Write failing tests** for empty page, stable cursor, phone-only records, note search, and exclusion of unauthorized anchors.
- [ ] **Step 2: Run `mvn -q -Dtest=PhoneRepositoryTest test`** and verify the new repository is absent.
- [ ] **Step 3: Implement bounded scan, cursor encoding/decoding, contact display projection, and query filtering.**
- [ ] **Step 4: Run focused tests and verify no unbounded record scan or page growth is introduced.**
- [ ] **Step 5: Commit the repository and test.**

### Task 4: HTTP API and OpenAPI contract

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapter.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapterTest.java`

**Interfaces:**
- Add `POST /api/v1/phone-contacts` with `{contactId?, contactName?, phoneNumber}`.
- Extend multipart upload with required `phonePointId` and optional `note`.
- Add `GET /api/v1/phone-repository?cursor=&limit=&query=`.
- Add `PATCH /api/v1/call-records/{callRecordId}/note` with `{note, expectedVersion}`.
- Map errors to `PHONE_CONTACT_REQUIRED`, `PHONE_NUMBER_INVALID`, `CONTACT_NOT_FOUND`, `PHONE_POINT_CONFLICT`, and `CALL_RECORD_NOTE_INVALID`.

- [ ] **Step 1: Add failing HTTP tests** for new-contact creation, existing-contact binding, mandatory fields, repository pagination, note update, and all error codes.
- [ ] **Step 2: Run `mvn -q -Dtest=CallRecordHttpAdapterTest test`** and `node contracts/openapi/message-center-v1.test.mjs`; verify contract failures.
- [ ] **Step 3: Implement thin route mapping, viewer authentication, request limits, JSON/multipart field allowlists, and response projections.**
- [ ] **Step 4: Update OpenAPI paths, schemas, required fields, and operation count assertions to match the implemented routes.**
- [ ] **Step 5: Run focused HTTP and OpenAPI tests, then commit only API, adapter, and contract files.**

### Task 5: Phone repository and upload UI

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Add a phone repository view independent of `state.selectedPointId`.
- Make contact and phone controls searchable/selectable and manually editable, both required for upload.
- Add optional note textarea and send it in the multipart form.

- [ ] **Step 1: Extend the Node VM behavior probe** to assert phone-only contact creation, manual contact/phone input, required-field blocking, note submission, and repository rendering.
- [ ] **Step 2: Run `mvn -q -Dtest=UnifiedMessageStoreTest test`** and confirm the probe fails before UI changes.
- [ ] **Step 3: Implement the repository tab, new-contact flow, phone normalization response handling, note field, and failure-state preservation. Keep `CallRecordHttpAdapter` as the only API owner.**
- [ ] **Step 4: Run the VM probe and verify existing call detail/player/retry/revision behavior is unchanged.**
- [ ] **Step 5: Run `git diff --check` and commit only the UI and behavior-test file.**

### Task 6: Runtime wiring and migration behavior

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/callrecord/CallRecordHttpAdapter.java`
- Modify: `demo/message-center-demo/README.md`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/callrecord/ContactTimelineServiceTest.java`

**Interfaces:**
- Wire the shared contact-group resolver into phone repository and call-record authorization.
- Make phone-only contacts visible without fabricating ordinary messages.
- Keep bound records in both repository and contact timeline; keep records outside current contact groups out of that timeline.

- [ ] **Step 1: Add failing integration tests** for phone-only contact listing, timeline projection, merge/split behavior, and restart loading of records with notes.
- [ ] **Step 2: Run the focused integration tests and confirm the current message-only contact projection fails the phone-only cases.**
- [ ] **Step 3: Wire the existing runtime owner, preserve atomic file updates, and document environment/runtime commands for the new phone repository.**
- [ ] **Step 4: Run focused integration tests and verify no production WeCom or FunASR route is changed.**
- [ ] **Step 5: Commit runtime wiring and documentation.**

### Task 7: Full verification and release artifact

**Files:**
- Modify only files required by prior tasks; do not include unrelated worktree files.

- [ ] **Step 1: Run `mvn -q test` from `demo/message-center-demo`. Expected: all tests pass with zero failures and zero errors.**
- [ ] **Step 2: Run `mvn -q test-compile` and `mvn -q package`. Expected: exit code 0 and a new JAR under `target/`.**
- [ ] **Step 3: Run `node contracts/openapi/message-center-v1.test.mjs`. Expected: all operations and schemas pass.**
- [ ] **Step 4: Run the independent message-store and phone-repository acceptance tests with isolated temporary directories.**
- [ ] **Step 5: Run `git diff --check`, inspect `git status --short`, verify staged file lists, and confirm JDK 17 class major version 61 for the release JAR.**
- [ ] **Step 6: Record test counts, JAR SHA-256, remaining risks, and exact deployment files; do not claim production deployment without user authorization.**
