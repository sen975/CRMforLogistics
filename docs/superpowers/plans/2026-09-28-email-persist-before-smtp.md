# 邮件发送先落库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅在邮件提交记录、出站消息和所有附件已持久化且提交成功后调用 SMTP。

**Architecture:** `EmailSendService` 统一调度人工和 AI 的发送；出站预处理通过独立事务提交记录和消息，附件存储在 SMTP 前完成；现有租约/CAS 状态机记录发送结果。数据库与 MinIO/SMTP 无分布式事务，发送后异常保留 UNKNOWN 并禁止自动重试。

**Tech Stack:** Spring Boot、MyBatis、PostgreSQL、MinIO、Jakarta Mail、JUnit 5/Mockito、Maven。

## Global Constraints

- 保留用户现有未提交改动，不重写 `EmailSendService` 的现有提交/租约逻辑。
- 不新增公开 API、表结构或自动重试；旧的已发邮件不重发。
- 无 owner 的既有入口也必须先解析邮件账号，找不到则 SMTP 零调用。
- `messages.counts_as_unread=false`、`current_status_at` 非空，发送前状态为 `pending`。
- 附件对象和元数据全部完成持久化后才允许 SMTP；失败时尽力补偿，禁止发送。

---

### Task 1: 出站消息与附件预处理

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailAttachmentStore.java`（仅在外层提交失败需要补偿时）
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSendServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailOwnerIsolationTest.java`

**Interfaces:** 消费 `EmailSubmissionMapper.insert`、`MessageMapper.insertWithSequence`、`EmailAttachmentStore.store`；生成固定 `Message-ID`，返回已提交的消息 UUID 与提交记录。

- [ ] **Step 1: 先添加失败测试。** 在 `EmailSendServiceTest` 用可注入的 `SmtpSender` 记录调用，分别让消息插入返回 0/抛异常、附件存储抛异常；调用 `send(ownerId,...)` 并断言 `EMAIL_SUBMISSION_PERSIST_FAILED` 或明确的预处理错误码，以及 SMTP 零调用。成功用例在 sender 内断言消息已 `pending`、`countsAsUnread=false`、`currentStatusAt!=null`、provider ID 等于 MIME Message-ID，并用 Mockito `InOrder` 证明附件先于 SMTP。
- [ ] **Step 2: 跑红灯。** `mvn -q -Dtest=EmailSendServiceTest,EmailOwnerIsolationTest test`，预期新测试因旧顺序或缺失字段失败，确认不是编译错误。
- [ ] **Step 3: 最小实现。** 在发送前选定账号，`beginSubmission` 和消息插入位于独立、同步完成的数据库事务；检查 `insertWithSequence(...) == 1`，消息状态 `pending`、未读为 `false`、填入 `currentStatusAt` 与 MIME Message-ID。事务完成后调用 `attachmentStore.store(messageId, attachments, true)`，失败时不调用 SMTP，并把已经提交的记录尽力标为 `FAILED`/`failed`；附件对象在异常时做既有补偿。避免外部 SMTP 调用处于数据库事务中。
- [ ] **Step 4: 跑绿灯和编译。** `mvn -q -Dtest=EmailSendServiceTest,EmailOwnerIsolationTest,EmailAttachmentStoreTest test` 与 `mvn -q -DskipTests compile`，预期零失败。
- [ ] **Step 5: 精确暂存本 Task 的文件/差异；若文件中用户 WIP 无法与本轮修改拆开，保留未提交并记录边界，不吸入 WIP。**

### Task 2: SMTP 后状态与失败合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/EmailSubmissionMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSendServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/EmailSubmissionMapperContractTest.java`

**Interfaces:** `PENDING -> FAILED` 仅允许 SMTP 前失败；SMTP 成功后 `PENDING -> SMTP_SENT -> SENT`，消息 `pending -> sent`；异常时 `UNKNOWN`/`submission_unknown`。

- [ ] **Step 1: 添加失败测试。** 证明预处理失败可写 `FAILED` 且 SMTP 未调用；发送成功时按 `SMTP_SENT`、消息 `sent`、提交 `SENT` 的次序更新；SMTP 抛异常、发送后任何更新失败时均不二次调用 SMTP，结果为 `EMAIL_SEND_OUTCOME_UNKNOWN`。Mapper 合同测试断言 token/租约围栏内的 `PENDING -> FAILED`。
- [ ] **Step 2: 跑红灯。** `mvn -q -Dtest=EmailSendServiceTest,EmailSubmissionMapperContractTest test`，新测试应因现有状态次序或缺少 FAILED 转换失败。
- [ ] **Step 3: 最小实现。** 只扩充 `EmailSubmissionMapper.update` 的受限 `FAILED` 转换。`EmailSendService` 在发送后先记 `SMTP_SENT`、再更新消息 `sent`、最后记 `SENT`；失败分支尽力改成 UNKNOWN，保留已记为 `sent` 的消息事实，不重发。
- [ ] **Step 4: 跑绿灯、编译和真实库专项（数据库可用时）。** `mvn -q -Dtest=EmailSendServiceTest,EmailSubmissionMapperContractTest,EmailSubmissionMapperSqlTest test`、`mvn -q -DskipTests compile`；核对测试报告中的 skipped 数，不能将跳过算通过。
- [ ] **Step 5: 复核任务差异与用户 WIP；仅在能精确提交本 Task 差异时提交。**

### Task 3: 合同与交付验收

**Files:**
- Modify: `docs/superpowers/README.md`（保留现有 WIP，只增当前设计索引）
- Modify: `docs/superpowers/reviews/2026-09-22-wecom-email-call-review-remediation-verification.md`（如已有用户改动，改用新增本轮验收记录）
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSendServiceTest.java`

**Interfaces:** 输出可核查的预处理/SMTP/最终状态结果，不改外部 API 合同。

- [ ] **Step 1: 全量回归仅在本最终 Task 执行一次。** `mvn -q test`（从 `demo/message-center-spring/backend` 运行）；记录成功/失败/跳过总数，环境阻断须写明。
- [ ] **Step 2: 文档回写。** 更新设计索引及邮件验收记录，写明命令、结果、真实 SMTP/MinIO 未覆盖的环境边界；不改动原有用户 WIP。
- [ ] **Step 3: 校验 git 范围。** `git diff --check`、`git status --short`、`git diff --cached --name-only`；不使用 `git add .`，无法单独提交的混合 WIP 保持未提交。
