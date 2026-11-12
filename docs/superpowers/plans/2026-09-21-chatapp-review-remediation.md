# ChatApp Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 收敛 ChatApp 出站、轮询、模板同步、Webhook 和 CAMS 回调配置的可靠性风险，保证 provider 超时、并发和同步失败都能被明确记录、重试或人工处理。

**Architecture:** provider 调用保持在 adapter 层，但所有外部 Future 都使用统一有界超时。数据库事务只负责短事务的版本/租约校验和结果落库，禁止包裹 CAMS 网络调用。消息轮询使用按账号持久化的时间水位，并把投影失败写入统一 `channel_events` 重试链路；模板同步保留结构化失败状态，不再把 provider 错误伪装成空成功。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、阿里云 CAMS SDK、React/TypeScript、Vitest、Maven。

## Global Constraints

- 不修改或回滚工作区中与本计划无关的用户改动。
- ChatApp 模板身份是 `templateCode + languageCode`；不同语言是不同模板，不调整前端模板键语义。
- provider 调用必须有连接、响应和 Future 等待上界；超时只能进入 `submission_unknown` 或结构化 retry，不得自动当作成功。
- 任何 provider 已成功、数据库未确认的状态都必须保留 request id 和可补偿入口。
- 所有新增行为必须有专项测试；全量回归前先通过对应模块测试。

## 当前明确撤销项

审查中“前端模板选择键缺少语言”不成立。CAMS 将不同语言视为不同模板，现有 `templateCode` 选择行为不在本轮修复范围；只保留账号切换时清理旧模板值的修复。

### Task 1: 为所有单聊 CAMS 出站调用增加统一超时

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppOutboundGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppOutboundGatewayTest.java`

**Implementation:**

- 从 `AppConfig` 注入一个有明确上下限的 ChatApp API timeout，默认 30 秒；有效范围固定为 1-120 秒，超出范围启动时拒绝配置。
- 将 `sendText`、`sendTemplate`、上传授权和发送媒体的 `.get()` 改为 `.get(timeout, TimeUnit.MILLISECONDS)`。
- 在 SDK `ClientOverrideConfiguration` 同时设置 connect timeout 和 response timeout。
- 捕获 `TimeoutException`、`SocketTimeoutException` 和其包装异常，统一转换为 `SubmissionUnknownException`；保留现有 429/服务不可用 retry 语义。
- 不在超时后自动再次发送同一个 provider task，交给已有状态回查链路。

**验收:**

```bash
mvn -q -Dtest='ChatAppSendServiceTest,AliyunChatAppOutboundGatewayTest' test
```

必须覆盖：Future 挂起在有限时间内返回、提交状态变为 `submission_unknown`、媒体授权超时和正常响应。

### Task 2: 拆除出站和广播的长事务 provider 调用

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/outbox/MessageOutboxWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorker.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolver.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSubmissionContext.java` as the immutable account/credential snapshot passed to provider adapters
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/outbox/MessageOutboxWorkerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorkerTest.java`

**Implementation:**

- 第一段短事务只读取并校验账号 owner/version，生成不可变 account/credential snapshot；事务结束后调用 CAMS。`ChatAppOutboundGateway.Command` 和 `ChatAppBroadcastGateway.BroadcastSubmission` 必须接收该 snapshot，不能在 provider 调用期间再次查询或锁账号。
- 第二段短事务使用 `message_id + lease_owner` 或 `broadcast_id + job lease` 做 compare-and-set，只有仍持有租约时才写入 submitted/unknown 状态。
- provider 调用期间不持有 `FOR UPDATE` 行锁，不在事务中打开外部网络连接。
- 版本不匹配统一落 `WHATSAPP_ACCOUNT_REASSIGNED`，provider 结果未知则保留 request/task id 并进入 reconciliation。

**验收:**

```bash
mvn -q -Dtest='MessageOutboxWorkerTest,ChatAppBroadcastWorkerTest' test
```

增加测试断言：provider 阻塞时账号行锁已释放；租约丢失后不能覆盖另一 worker 的结果。

### Task 3: 为消息轮询建立持久水位并处理投影失败

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSyncScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChannelSyncCursorEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/SyncCursorMapper.java`
- Verify: `demo/message-center-spring/backend/src/main/resources/db/migration/V2__channel_conversation_message.sql` already provides `channel_sync_cursors`; its existing `cursor_timestamp` plus encoded `cursor_value` must represent the CAMS `(sendTime,messageId)` watermark, so no migration is expected for this task.
- Remove before implementation: unshipped temporary `V84__chatapp_message_sync_cursor.sql` and the
  `channel_accounts.message_last_synced_at` field/path; the existing `channel_sync_cursors` table is
  the sole owner of message polling watermarks.
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookRetryWorker.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppPollingFailureInboxService.java` with `@Transactional(propagation = REQUIRES_NEW)` failure persistence
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppPollingFailureInboxServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/SyncCursorMapperSqlTest.java`

**Implementation:**

- 每个 channel account 保存 CAMS `ListChatappMessageResponseBody.Data.sendTime` 解析出的时间水位，并以 `messageId/uniqueMessageId` 作为同一时间的稳定 tie-break；查询窗口从水位开始并留固定重叠窗口，依靠 event/message 幂等去重。
- 保留 provider 分页游标或时间+ID推进；达到最大页数时返回 `incomplete=true` 并记录告警，不得报告普通成功。
- 每次成功处理页后推进水位，只有投影结果已落库或已进入 durable event 才允许推进。
- 将 `ChatAppPollingProjector` 的异常转换为 `channel_events`，带 provider id、payload、attempt、nextAttemptAt，交由 retry worker；不能仅增加 skipped 计数。由于当前 projector 事务失败会回滚事件插入，必须新增独立的 `REQUIRES_NEW` inbox 写入服务或在外层捕获后单独落库，确保失败事件本身不会随投影事务回滚。
- 继续保留单账号并发保护，并增加每轮总预算，防止 100 个账号串行拖垮调度周期。

**验收:**

```bash
mvn -q -Dtest='ChatAppMessageSyncServiceTest,ChatAppPollingProjectorTest,ChatAppSyncSchedulerTest,*ChannelSyncCursor*Test' test
```

必须覆盖：重启后从水位继续、满页返回 incomplete、单行投影失败可重试、重复轮询不重复生成消息。

### Task 4: 修复模板同步错误语义和 `allowSend` fallback

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`
- Modify: `demo/message-center-spring/frontend/src/components/SendForm.tsx` to clear template form/state when the effective ChatApp account changes; do not change the template identity key
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java` and `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSyncScheduler.java` for HTTP/scheduler error mapping
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java` and `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java` for template-sync status fields
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V88__chatapp_template_sync_status.sql` with `template_sync_status`, `template_last_synced_at`, and `template_last_error_code` (`V83` and `V86` are already occupied by assistant migrations)
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/template/AliyunChatAppTemplateGatewayTest.java`
- Test: `demo/message-center-spring/frontend/src/components/SendForm.test.tsx`

**Implementation:**

- provider list、凭据、详情请求失败时返回结构化 `syncFailed/errorCode/retryable/lastSuccessfulAt`，定时同步写失败状态；手动同步映射为 502/503，不返回空成功。
- `lastSuccessfulAt` 必须来自持久化的模板同步字段，不能复用消息同步的 `last_synced_at`，避免两个任务互相覆盖状态。
- `V88` 的状态值固定为 `NEVER_SYNCED/SYNCING/SUCCEEDED/FAILED`，并由 `ChatAppSyncScheduler` 在开始、成功、失败三个阶段更新；失败时保留上一次成功时间。
- 详情失败时保留已有 `allowSend`，新模板不能静默写成“永久停用”。approved utility 是否可按 audit status 推导为可发送，必须先以当前 SDK 字段定义和真实 CAMS fixture 确认；确认后再固化该规则，marketing 仍必须依赖 provider `allowSend=true`。
- 保存 detail failure reason 和最后一次成功同步时间，前端可区分空目录与同步故障。
- 不改变模板身份：`templateCode + languageCode` 仍是数据库和 provider 的唯一键。

**验收:**

```bash
mvn -q -Dtest='WhatsAppTemplateReconciliationServiceTest,ChatAppTemplateServiceTest,AliyunChatAppTemplateGatewayTest' test
```

必须覆盖：provider timeout 不返回 200 空成功、详情暂时失败不把已发送模板改成停用、marketing/utility 策略分别正确。

### Task 5: 统一 ChatApp webhook ingress 和账号路由

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookInboxService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java` and `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookControllerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookInboxServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java`

**Implementation:**

- 默认只把 `/api/v1/webhooks/chatapp` 作为 CAMS provider ingress；旧 `/api/chatapp/webhook` 先改为明确 deprecated 响应或统一委托 inbox。是否彻底删除旧路由属于删除旧 API 的单独决策，实施前必须取得确认。
- 所有 ingress 统一验签、body 上限、事件落库、幂等和 retry 行为。
- 账号路由使用 provider scope/custSpace/phone 绑定；若 provider payload 没有 scope，必须在配置层保证号码全局唯一并把冲突返回为结构化 unresolved，而不是静默丢弃。
- 保留原始 body hash 与结构化投影字段，确保错误码、状态原因等影响语义的字段不会因 canonical 白名单丢失。

**验收:**

```bash
mvn -q -Dtest='ChatAppWebhookControllerTest,ChatAppWebhookInboxServiceTest,ChatAppWebhookProjectorTest' test
node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
```

### Task 6: 修复回调配置并发和字段合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCallbackConfigService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackConfigMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunWhatsAppCallbackGateway.java`
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V89__whatsapp_cams_callback_apply_claim.sql` for `APPLYING`/claim-token fields; do not edit `V79__whatsapp_cams_webhook_configuration.sql` after it has shipped (`V82` and `V86` are already occupied by assistant migrations).
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppCallbackConfigController.java`, `demo/message-center-spring/frontend/src/components/whatsapp/AdminWhatsAppCallbackPanel.tsx`, and the OpenAPI contract
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCallbackConfigServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunWhatsAppCallbackGatewayTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppCallbackConfigControllerTest.java`

**Implementation:**

- provider 更新前先 claim 当前版本，使用新增的 `apply_token` 和 `last_apply_status='APPLYING'`；provider 成功后用 claim token/版本 CAS 写入，失败或超时进入 `FAILED/SUBMISSION_UNKNOWN`，提供重试入口。迁移必须同步扩展现有 `last_apply_status` check constraint，不能只改 Java 常量。
- `V89` 必须新增 `apply_token uuid`、`apply_started_at timestamptz`，并把 `last_apply_status` check 扩展为 `NEVER_APPLIED/APPLYING/SUCCEEDED/FAILED/SUBMISSION_UNKNOWN`；claim 与结果更新都必须同时匹配 `id + version + apply_token`。
- `applyPhone`/`applyAccount` 不能继续用一个覆盖 provider 调用的 `@Transactional`；claim、provider 调用、结果 CAS 和 audit 必须拆成独立短事务，避免配置行锁跨网络请求。
- 不允许 `httpFlag=Y` 时 URL 为空；清空 URL 必须同时显式关闭对应 HTTP 通道，或定义 provider 明确支持的 clear 合同。
- 统一接受 CAMS 实际成功码，并保留 request id；以 SDK/真实 fixture 为准，不凭猜测扩展成功码。
- audit 写入不能覆盖 provider 状态；审计失败应有独立告警和补偿策略。

**验收:**

```bash
mvn -q -Dtest='WhatsAppCallbackConfigServiceTest,AliyunWhatsAppCallbackGatewayTest,WhatsAppCallbackConfigControllerTest' test
```

必须覆盖：并发更新、provider 成功后 CAS 冲突、超时、清空 URL、非法 flag 和真实响应 code。

### Task 7: 收尾清理和全量验收

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`（统一 `chatapp/whatsapp` 账号类型）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/outbox/MessageSendApplicationService.java`（删除重复幂等查询）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java` and `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Test: existing ChatApp backend tests under `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/` and `service/chatapp/`

**验收命令:**

```bash
mvn -q -DskipTests compile
mvn -q -Dtest='*ChatApp*Test,*WhatsApp*Test' test
npm test
node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
```

全量测试若仍被工作区中无关的 WeCom 测试编译错误阻断，必须单独记录文件、行号、影响范围，不得宣称全量通过。

## 停止条件

- Task 1 的超时和 Task 2 的长事务未收敛前，不进入发布或实机验收。
- 没有真实 CAMS webhook 签名和回调 payload fixture 时，不宣称 webhook 已生产就绪。
- provider 已成功但数据库 CAS 失败的场景没有补偿入口时，不宣称回调配置功能完成。

## Implementation Checkpoint (2026-09-22)

当前代码已按 Task 1-7 主线落地并完成针对性验证：

- Task 1：单聊 CAMS Future、连接/响应等待均有界；超时进入 `submission_unknown`。
- Task 2：出站和广播 provider 调用使用不可变凭据快照，网络调用不在数据库事务内；结果用租约 CAS 落库。
- Task 3：消息水位唯一保存在 `channel_sync_cursors`；`cursor_value` 编码为 `epochMillis|messageId`，投影失败在没有 durable inbox 时不会推进水位。
- Task 4：模板同步失败有独立状态；详情失败不把已有模板改为停用，新模板在详情不可用时不创建伪停用记录。
- Task 5：`/api/v1/webhooks/chatapp` 是唯一正式入口；旧 `/api/chatapp/webhook` 公开返回 HTTP 410，避免被安全过滤器先返回 401。
- Task 6：CAMS 回调更新使用 `apply_token`、版本 CAS 和五分钟过期 claim；明确失败与 `SUBMISSION_UNKNOWN`，审计异常不覆盖主状态。
- Task 7：删除重复幂等查询，统一 `chatapp/whatsapp` 账号读取路径，补充上述合同测试。

已验证命令：

```bash
mvn -q -Dmaven.test.skip=true compile
mvn -q -Dtest='WhatsAppCallbackConfigServiceTest,WhatsAppCallbackSchemaContractTest,AliyunWhatsAppCallbackGatewayTest,ChatAppMessageSyncServiceTest,SyncCursorMapperSqlTest,ChatAppWebhookControllerTest,MessageOutboxWorkerTest,ChatAppBroadcastWorkerTest,ChatAppWorkerSchedulingTest' test
npm test -- --run src/components/SendForm.test.tsx src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx
node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs
git diff --check
```

全量 `*ChatApp*Test,*WhatsApp*Test` 已尝试，但当前环境存在与本计划无关的 Testcontainers/Docker 不可用、OSS socket 权限和其他模块类文件缺失；因此不能将全量结果宣称为通过。真实 CAMS `UpdatePhoneWebhook`/`UpdateAccountWebhook` 成功码以及生产 webhook 签名 fixture 仍需在官方文档或实机回调中确认。
