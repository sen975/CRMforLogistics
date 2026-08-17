# ChatApp 群发会话消息投影与对账修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 让每个 ChatApp 群发收件人在 Provider 接受批次后立即进入对应会话，并通过 GroupMessageId 对账更新同一条消息，同时保留部分成功、单个失败和真实 Provider 诊断。

**架构：** `ChatAppBroadcastApplicationService/Worker` 继续拥有批次与 recipient 状态；新增 `ChatAppBroadcastMessageProjector` 作为 recipient 到统一 `messages` 的唯一桥梁。Provider 对账保存有界证据，普通 polling 与群发对账复用 outbound 关联和状态推进逻辑，禁止产生第二条会话消息。

**技术栈：** Java 17、Spring Boot 3.4.5、MyBatis-Plus 3.5.10、PostgreSQL 17.5、Flyway、Aliyun CAMS SDK 5.0.5、JUnit 5、Mockito、Testcontainers、React 18、TypeScript 5.6、Ant Design 5、TanStack Query 5、Vitest 4、Vite 6。

## 全局约束

- 实施真源是 `docs/superpowers/specs/2026-08-17-chatapp-broadcast-conversation-projection-design.md`。
- 不修改已应用的 V15；当前最新迁移为 V19，新迁移固定使用 V20。
- Provider 返回 `GroupMessageId` 后，每个 recipient 必须创建一条 `processing` outbound template message。
- `messages` 是会话历史唯一真源；前端不得把群发表拼成第二套消息历史。
- `STATUS_UNKNOWN` 重新对账只调用 `ListChatappMessage`，禁止调用 `SendChatappMassMessage`。
- 每个 recipient 只关联一个 message；幂等键固定为 `broadcast:{broadcastId}:recipient:{recipientId}`。
- 部分结果必须先保存可匹配事实；二成功一失败聚合为 `PARTIALLY_FAILED` 和 2/1/0。
- Provider code、RequestId、页码、行号和截断失败原因必须可审计；不得记录 AccessKey、密钥或完整消息正文。
- 普通 polling 只有在候选唯一时才能关联群发消息；候选不唯一时返回结构化未决原因，禁止猜测。
- 自动测试禁止调用真实 CAMS 写接口；实库恢复前再次确认只读范围。
- 工作区存在大量既有改动。每次只 stage 本任务列出的文件，禁止 `git add .`，禁止回滚其他改动。

---

### Task 1：V20 数据合同、实体和持久化门禁

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/resources/db/migration/V20__chatapp_broadcast_message_projection.sql`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastReconciliationEvidenceEntity.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastReconciliationEvidenceMapper.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/ChatAppBroadcastMessageProjectionMigrationTest.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastRecipientEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastPersistenceIntegrationTest.java`

**接口：**
- Produces：`ChatAppBroadcastEntity.templateBodySnapshot/lastReconciliationRequestId/lastReconciliationProviderCode`。
- Produces：`ChatAppBroadcastRecipientEntity.messageId`。
- Produces：`int upsert(ChatAppBroadcastReconciliationEvidenceEntity evidence)`、`long countUnmatched(UUID broadcastId)` 和 `List<ChatAppBroadcastReconciliationEvidenceEntity> findLatest(UUID broadcastId, int limit)`。
- Consumes：现有 `messages`、`chatapp_broadcasts`、`chatapp_broadcast_recipients`、`chatapp_broadcast_jobs`。

- [ ] **Step 1：写 V20 静态红灯合同**

```java
@Test
void v20AddsProjectionLinkAndBoundedReconciliationEvidence() throws Exception {
    String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V20__chatapp_broadcast_message_projection.sql"));
    String normalized = sql.toUpperCase(Locale.ROOT);
    assertThat(normalized)
            .contains("ADD COLUMN TEMPLATE_BODY_SNAPSHOT TEXT")
            .contains("ADD COLUMN MESSAGE_ID UUID")
            .contains("REFERENCES MESSAGES(ID) ON DELETE SET NULL")
            .contains("CREATE UNIQUE INDEX UX_CHATAPP_BROADCAST_RECIPIENT_MESSAGE")
            .contains("CREATE TABLE CHATAPP_BROADCAST_RECONCILIATION_EVIDENCE")
            .contains("UNIQUE (JOB_ID, PAGE_NUMBER, ROW_NUMBER)")
            .contains("FAILURE_REASON VARCHAR(1000)")
            .doesNotContain("DROP TABLE")
            .doesNotContain("DELETE FROM MESSAGES");
}
```

- [ ] **Step 2：运行测试并确认 RED**

Run（`demo/message-center-spring/backend`）：

```bash
mvn -Dtest=ChatAppBroadcastMessageProjectionMigrationTest test
```

Expected：FAIL，原因是 `V20__chatapp_broadcast_message_projection.sql` 不存在。

- [ ] **Step 3：创建 V20 迁移**

```sql
ALTER TABLE chatapp_broadcasts
    ADD COLUMN template_body_snapshot text,
    ADD COLUMN last_reconciliation_request_id varchar(255),
    ADD COLUMN last_reconciliation_provider_code varchar(100);

ALTER TABLE chatapp_broadcast_recipients
    ADD COLUMN message_id uuid REFERENCES messages(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_chatapp_broadcast_recipient_message
    ON chatapp_broadcast_recipients(message_id)
    WHERE message_id IS NOT NULL;

CREATE TABLE chatapp_broadcast_reconciliation_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id uuid NOT NULL REFERENCES chatapp_broadcasts(id) ON DELETE CASCADE,
    job_id uuid NOT NULL REFERENCES chatapp_broadcast_jobs(id) ON DELETE CASCADE,
    provider_request_id varchar(255),
    page_number integer NOT NULL,
    row_number integer NOT NULL,
    user_number varchar(50),
    provider_message_id varchar(255),
    provider_unique_message_id varchar(255),
    provider_status varchar(100),
    failure_reason varchar(1000),
    matched_recipient_id uuid REFERENCES chatapp_broadcast_recipients(id) ON DELETE SET NULL,
    diagnostic_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_chatapp_broadcast_evidence_page CHECK (page_number BETWEEN 1 AND 20),
    CONSTRAINT ck_chatapp_broadcast_evidence_row CHECK (row_number BETWEEN 0 AND 100),
    UNIQUE (job_id, page_number, row_number)
);

CREATE INDEX ix_chatapp_broadcast_evidence_listing
    ON chatapp_broadcast_reconciliation_evidence(broadcast_id, created_at DESC, id DESC);
```

- [ ] **Step 4：补齐实体和 Mapper**

`ChatAppBroadcastReconciliationEvidenceEntity` 必须精确包含迁移字段。Mapper 使用结构化参数：

```java
int upsert(ChatAppBroadcastReconciliationEvidenceEntity evidence);
long countUnmatched(@Param("broadcastId") UUID broadcastId);
List<ChatAppBroadcastReconciliationEvidenceEntity> findLatest(
        @Param("broadcastId") UUID broadcastId, @Param("limit") int limit);
```

`upsert` 使用 `ON CONFLICT (job_id, page_number, row_number) DO UPDATE`，更新 RequestId、号码、Provider IDs、状态、失败原因、匹配 recipient、diagnostic code 和 created_at；同一 job 的后续尝试必须刷新证据，不能被第一次失败永久遮挡。

```java
@Insert("insert into chatapp_broadcast_reconciliation_evidence (id, broadcast_id, job_id, " +
        "provider_request_id, page_number, row_number, user_number, provider_message_id, " +
        "provider_unique_message_id, provider_status, failure_reason, matched_recipient_id, " +
        "diagnostic_code, created_at) values (#{id}::uuid, #{broadcastId}::uuid, #{jobId}::uuid, " +
        "#{providerRequestId}, #{pageNumber}, #{rowNumber}, #{userNumber}, #{providerMessageId}, " +
        "#{providerUniqueMessageId}, #{providerStatus}, left(#{failureReason}, 1000), " +
        "#{matchedRecipientId}::uuid, #{diagnosticCode}, #{createdAt}) " +
        "on conflict (job_id, page_number, row_number) do update set " +
        "provider_request_id = excluded.provider_request_id, user_number = excluded.user_number, " +
        "provider_message_id = excluded.provider_message_id, " +
        "provider_unique_message_id = excluded.provider_unique_message_id, " +
        "provider_status = excluded.provider_status, failure_reason = excluded.failure_reason, " +
        "matched_recipient_id = excluded.matched_recipient_id, " +
        "diagnostic_code = excluded.diagnostic_code, created_at = excluded.created_at")
int upsert(ChatAppBroadcastReconciliationEvidenceEntity evidence);
```

`ChatAppBroadcastRecipientMapper` 增加：

```java
Optional<ChatAppBroadcastRecipientEntity> findByIdForUpdate(@Param("id") UUID id);
int linkMessageIfAbsent(@Param("id") UUID id, @Param("messageId") UUID messageId,
                        @Param("updatedAt") Instant updatedAt);
List<ChatAppBroadcastRecipientEntity> findWithoutMessage(
        @Param("broadcastId") UUID broadcastId, @Param("limit") int limit);
```

`ChatAppBroadcastReconciliationEvidenceMapper.countUnmatched()` 必须只统计真实 Provider 行，不得把 `row_number=0` envelope 算入未匹配数量：

```java
@Select("select count(*) from chatapp_broadcast_reconciliation_evidence "
        + "where broadcast_id = #{broadcastId}::uuid and row_number > 0 "
        + "and matched_recipient_id is null")
long countUnmatched(@Param("broadcastId") UUID broadcastId);
```

`ChatAppBroadcastMapper.insertIfAbsent()` 必须写入 `template_body_snapshot`，不得在后续任务用单独 UPDATE 补快照。

- [ ] **Step 5：扩展 PostgreSQL 实测**

在 `ChatAppBroadcastPersistenceIntegrationTest` 注册 recipient/evidence mapper，并增加：

```java
@Test
void recipientLinksOneMessageAndEvidenceRowsAreIdempotent() {
    ChatAppBroadcastEntity broadcast = broadcast("projection-request");
    broadcast.setTemplateBodySnapshot("订单 $(order) 已发货");
    assertThat(broadcastMapper.insertIfAbsent(broadcast)).isEqualTo(1);
    UUID broadcastId = broadcast.getId();
    UUID contactId = UUID.randomUUID();
    UUID identityId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-17T08:00:00Z");
    jdbc.update("insert into contacts (id, display_name, created_by) values (?, 'Recipient', ?)",
            contactId, actorId);
    jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, " +
                    "identity_value, normalized_value) values (?, ?, 'chatapp', ?, " +
                    "'60111111111', '60111111111')",
            identityId, contactId, accountId.toString());
    jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id, " +
                    "next_ingest_sequence) values (?, ?, ?, 1)",
            conversationId, accountId, identityId);
    jdbc.update("insert into messages (id, conversation_id, channel_account_id, client_request_id, " +
                    "direction, message_kind, body_text, occurred_at, ingest_sequence, counts_as_unread, " +
                    "current_status, current_status_at) values (?, ?, ?, ?, 'outbound', 'template', " +
                    "'订单 SO-1 已发货', ?, 1, false, 'processing', ?)",
            messageId, conversationId, accountId,
            "broadcast:" + broadcastId + ":recipient:" + recipientId,
            Timestamp.from(now), Timestamp.from(now));
    ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
    recipient.setId(recipientId);
    recipient.setBroadcastId(broadcastId);
    recipient.setContactId(contactId);
    recipient.setContactIdentityId(identityId);
    recipient.setRecipientNameSnapshot("Recipient");
    recipient.setRecipientNumberSnapshot("60111111111");
    recipient.setTemplateParamsJsonb("{\"order\":\"SO-1\"}");
    recipient.setStatus("PROCESSING");
    recipient.setCreatedAt(now);
    recipient.setUpdatedAt(now);
    recipient.setVersion(0L);
    recipientMapper.insert(recipient);
    UUID jobId = insertJob(broadcastId, "RECONCILE", "PROCESSING", now.plusSeconds(60));
    assertThat(recipientMapper.linkMessageIfAbsent(recipientId, messageId, Instant.now())).isEqualTo(1);

    ChatAppBroadcastReconciliationEvidenceEntity envelope = new ChatAppBroadcastReconciliationEvidenceEntity();
    envelope.setId(UUID.randomUUID());
    envelope.setBroadcastId(broadcastId);
    envelope.setJobId(jobId);
    envelope.setProviderRequestId("request-1");
    envelope.setPageNumber(1);
    envelope.setRowNumber(0);
    envelope.setDiagnosticCode("CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING");
    envelope.setCreatedAt(now);
    ChatAppBroadcastReconciliationEvidenceEntity unmatched = new ChatAppBroadcastReconciliationEvidenceEntity();
    unmatched.setId(UUID.randomUUID());
    unmatched.setBroadcastId(broadcastId);
    unmatched.setJobId(jobId);
    unmatched.setProviderRequestId("request-1");
    unmatched.setPageNumber(1);
    unmatched.setRowNumber(1);
    unmatched.setUserNumber("60122222222");
    unmatched.setDiagnosticCode("CHATAPP_BROADCAST_RECIPIENT_UNMATCHED");
    unmatched.setCreatedAt(now);
    assertThat(evidenceMapper.upsert(envelope)).isEqualTo(1);
    envelope.setDiagnosticCode("CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT");
    assertThat(evidenceMapper.upsert(envelope)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from chatapp_broadcast_reconciliation_evidence " +
                    "where job_id = ? and page_number = 1 and row_number = 0",
            Integer.class, jobId)).isEqualTo(1);
    assertThat(evidenceMapper.findLatest(broadcastId, 10).stream()
            .filter(item -> item.getRowNumber() == 0).findFirst().orElseThrow().getDiagnosticCode())
            .isEqualTo("CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT");
    assertThat(evidenceMapper.upsert(unmatched)).isEqualTo(1);
    assertThat(evidenceMapper.countUnmatched(broadcastId)).isEqualTo(1);

    UUID secondIdentityId = UUID.randomUUID();
    jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, " +
                    "identity_value, normalized_value) values (?, ?, 'chatapp', ?, " +
                    "'60133333333', '60133333333')",
            secondIdentityId, contactId, accountId.toString());
    ChatAppBroadcastRecipientEntity second = new ChatAppBroadcastRecipientEntity();
    second.setId(UUID.randomUUID());
    second.setBroadcastId(broadcastId);
    second.setContactId(contactId);
    second.setContactIdentityId(secondIdentityId);
    second.setRecipientNameSnapshot("Recipient 2");
    second.setRecipientNumberSnapshot("60133333333");
    second.setTemplateParamsJsonb("{\"order\":\"SO-2\"}");
    second.setStatus("PROCESSING");
    second.setCreatedAt(now.plusMillis(1));
    second.setUpdatedAt(now);
    second.setVersion(0L);
    recipientMapper.insert(second);
    assertThatThrownBy(() -> recipientMapper.linkMessageIfAbsent(
            second.getId(), messageId, Instant.now()))
            .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
}
```

- [ ] **Step 6：运行 Task 1 门禁**

```bash
mvn -Dtest=ChatAppBroadcastMessageProjectionMigrationTest,ChatAppBroadcastPersistenceIntegrationTest test
```

Expected：PASS；Testcontainers PostgreSQL 17.5 完整应用 V1-V20。

- [ ] **Step 7：提交 Task 1**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V20__chatapp_broadcast_message_projection.sql
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastRecipientEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppBroadcastReconciliationEvidenceEntity.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastReconciliationEvidenceMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/ChatAppBroadcastMessageProjectionMigrationTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastPersistenceIntegrationTest.java
git commit -m "feat: add ChatApp broadcast projection persistence"
```

---

### Task 2：统一 outbound 状态策略和即时会话消息投影

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageStateMachine.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjector.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageStateMachineTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjectorTest.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java`

**接口：**
- Produces：`ChatAppOutboundMessageStateMachine.advance(String current, String next) -> String`。
- Produces：`TemplateMessageTextResolver.renderSnapshot(String body, Map<String, ?> parameters) -> String`。
- Produces：`ChatAppBroadcastMessageProjector.ensureProcessing(UUID broadcastId, UUID recipientId) -> ProjectionResult`。
- Produces：projector 内嵌 record `ProjectionResult(UUID messageId, boolean created, String status)`。
- Produces：Task 4 扩展的 projector 内嵌 record `ReconciliationProjectionResult(UUID messageId, boolean created, boolean updated, String diagnosticCode)`。
- Produces：`MessageMapper.findAllByProviderMessageId(UUID channelAccountId, String providerMessageId)`，返回全部历史候选，不能用 `Optional` 掩盖重复 Provider ID。
- Consumes：Task 1 的 snapshot、recipient.messageId 和 Mapper。

- [ ] **Step 1：写状态单调推进红灯测试**

```java
@ParameterizedTest
@CsvSource({
    "pending,processing,processing",
    "processing,submitted,submitted",
    "processing,sent,sent",
    "sent,delivered,delivered",
    "delivered,read,read",
    "read,delivered,read",
    "delivered,failed,delivered",
    "failed,delivered,delivered"
})
void advancesWithoutRegressingConfirmedDelivery(String current, String next, String expected) {
    assertThat(ChatAppOutboundMessageStateMachine.advance(current, next)).isEqualTo(expected);
}
```

- [ ] **Step 2：写即时投影红灯测试**

```java
@Test
void createsOneProcessingTemplateMessageAndLinksRecipient() {
    ProjectionResult first = projector.ensureProcessing(broadcastId, recipientId);
    ProjectionResult second = projector.ensureProcessing(broadcastId, recipientId);

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.messageId()).isEqualTo(first.messageId());
    ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageMapper, times(1)).insertWithSequence(inserted.capture());
    assertThat(inserted.getValue().getBodyText()).isEqualTo("订单 SO-1 已发货");
    assertThat(inserted.getValue().getCountsAsUnread()).isFalse();
    assertThat(inserted.getValue().getCurrentStatus()).isEqualTo("processing");
    JsonNode metadata = objectMapper.readTree(inserted.getValue().getMetadataJsonb());
    assertThat(metadata.path("broadcastId").asText()).isEqualTo(broadcastId.toString());
    assertThat(metadata.path("broadcastRecipientId").asText()).isEqualTo(recipientId.toString());
    assertThat(metadata.path("providerGroupMessageId").asText()).isEqualTo("group-1");
    assertThat(metadata.path("templateCode").asText()).isEqualTo("shipping_notice");
    assertThat(metadata.path("languageCode").asText()).isEqualTo("zh_CN");
    assertThat(metadata.path("templateParams").path("order").asText()).isEqualTo("SO-1");
    verify(statusEventMapper, times(1)).insertIgnore(argThat(e -> "processing".equals(e.getStatus())));
verify(conversationMapper).recomputeProjection(conversationId);
}

```

- [ ] **Step 3：运行 RED**

```bash
mvn -Dtest=ChatAppOutboundMessageStateMachineTest,ChatAppBroadcastMessageProjectorTest test
```

Expected：FAIL，缺少两个新 service。

- [ ] **Step 4：实现状态策略和模板快照渲染**

```java
private static final Map<String, Integer> SUCCESS_RANK = Map.of(
        "pending", 0,
        "processing", 1,
        "submitted", 2,
        "sent", 3,
        "delivered", 4,
        "read", 5);

public static String advance(String current, String next) {
    String currentValue = normalize(current);
    String nextValue = normalize(next);
    if (nextValue.isBlank()) return currentValue;
    if (currentValue.isBlank()) return nextValue;
    if ("failed".equals(nextValue)) {
        return rank(currentValue) >= rank("delivered") ? currentValue : "failed";
    }
    if ("failed".equals(currentValue) && SUCCESS_RANK.containsKey(nextValue)) {
        return nextValue;
    }
    return rank(nextValue) >= rank(currentValue) ? nextValue : currentValue;
}

private static int rank(String status) {
    return SUCCESS_RANK.getOrDefault(status, -1);
}

private static String normalize(String status) {
    return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
}

public String renderSnapshot(String body, Map<String, ?> parameters) {
    if (body == null || body.isBlank()) return "模板内容不可用";
    return render(OWNED_PLACEHOLDER, body, parameters);
}
```

不得复制 `ChatAppWebhookProjector.shouldAdvance()`；Task 5 会删除该私有重复逻辑并改用共享状态策略。

- [ ] **Step 5：实现 `ensureProcessing`**

事务顺序固定为：

```java
ChatAppBroadcastRecipientEntity recipient = recipientMapper.findByIdForUpdate(recipientId)
        .orElseThrow(() -> new IllegalStateException("CHATAPP_BROADCAST_RECIPIENT_NOT_FOUND"));
ChatAppBroadcastEntity broadcast = broadcastMapper.selectById(broadcastId);
if (broadcast == null) throw new IllegalStateException("CHATAPP_BROADCAST_NOT_FOUND");
if (recipient.getMessageId() != null) return existing(recipient.getMessageId());
String clientRequestId = "broadcast:" + broadcastId + ":recipient:" + recipientId;
Optional<MessageEntity> duplicate = messageMapper.findByClientRequestId(
        broadcast.getChannelAccountId(), clientRequestId);
ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
        broadcast.getChannelAccountId(), recipient.getContactIdentityId(), broadcast.getCreatedByUserId());
if (duplicate.isPresent()) {
    recipientMapper.linkMessageIfAbsent(recipientId, duplicate.orElseThrow().getId(), clock.instant());
    return new ProjectionResult(duplicate.orElseThrow().getId(), false, duplicate.orElseThrow().getCurrentStatus());
}
MessageEntity message = processingMessage(broadcast, recipient, conversation, clientRequestId);
messageMapper.insertWithSequence(message);
recipientMapper.linkMessageIfAbsent(recipientId, message.getId(), clock.instant());
statusEventMapper.insertIgnore(processingEvent(broadcast, recipient, message));
conversationMapper.recomputeProjection(conversation.getId());
return new ProjectionResult(message.getId(), true, "processing");
```

`processingEvent.providerEventId` 固定为 `broadcast:{broadcastId}:recipient:{recipientId}:processing`。`processingMessage` 必须设置：`direction=outbound`、`messageKind=template`、`currentStatus=processing`、`countsAsUnread=false`、`occurredAt=broadcast.submittedAt`、`createdByUserId=broadcast.createdByUserId`、稳定 `clientRequestId` 和渲染后的 `bodyText`；metadata 精确保存 `broadcastId/broadcastRecipientId/providerGroupMessageId/templateCode/templateName/languageCode/templateParams`。历史 broadcast 快照为空时，projector 调用 `templateMapper.findForDisplay(accountId, templateCode, languageCode)`；仍无正文时使用明确文本“模板内容不可用”并在 metadata 保存 `bodyUnavailableReason=CHATAPP_BROADCAST_TEMPLATE_SNAPSHOT_MISSING`。

`ensureProcessing()` 使用 `@Transactional`，消息插入、recipient 关联、processing 状态事件和 conversation 摘要更新必须在同一事务中完成。事务成功后发布现有 `eventHub.publish("message-new", "{}")`；重复调用若没有写入任何事实则不重复发布。

`MessageMapper` 增加以下查询，确保对 Provider ID 重复有可证明的停止条件；Task 4/5 的测试通过该查询验证冲突停止：

```java
@Select("select * from messages where channel_account_id = #{channelAccountId}::uuid "
        + "and provider_message_id = #{providerMessageId} order by created_at, id")
List<MessageEntity> findAllByProviderMessageId(
        @Param("channelAccountId") UUID channelAccountId,
        @Param("providerMessageId") String providerMessageId);
```

`ChatAppBroadcastApplicationService.createInternal()` 在读取 sendable template 后设置：

```java
broadcast.setTemplateBodySnapshot(template.getBody());
```

新群发若模板正文为空，继续返回 `CHATAPP_BROADCAST_TEMPLATE_NOT_SENDABLE`，不得创建空快照。

- [ ] **Step 6：运行 Task 2 门禁**

```bash
mvn -Dtest=ChatAppOutboundMessageStateMachineTest,ChatAppBroadcastMessageProjectorTest,ChatAppBroadcastApplicationServiceTest,TemplateMessageTextResolverTest test
```

Expected：PASS。

- [ ] **Step 7：提交 Task 2**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageStateMachine.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjector.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/TemplateMessageTextResolver.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageStateMachineTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjectorTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java
git commit -m "feat: project ChatApp broadcasts into conversations"
```

---

### Task 3：Provider 响应解析、真实错误和行级诊断

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastException.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastGateway.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppBroadcastGateway.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppBroadcastGatewayTest.java`

**接口：**
- Produces：`ProviderDiagnostic(String providerCode, String providerMessage, String providerRequestId, String diagnosticCode)`。
- Produces：`ReconciliationItem(int rowNumber, String recipientNumber, String providerMessageId, String providerUniqueMessageId, RecipientStatus status, String rawProviderStatus, String failureReason, Instant providerSentAt, String diagnosticCode)`。
- Produces：`ReconciliationPage(List<ReconciliationItem> items, int page, boolean hasNext, ProviderDiagnostic diagnostic)`。
- Produces：`ChatAppBroadcastException.providerCode()/providerRequestId()/safeMessage()`。

- [ ] **Step 1：写真实部分响应红灯 fixture**

```java
@Test
void acceptsCodeOkDataWhenSuccessIsMissingAndKeepsTwoSuccessesAndOneFailure() {
    var rows = List.of(
        row("60111111111", "wamid-1", "Delivered", null),
        row("60122222222", "wamid-2", "Read", null),
        row("60199999999", "wamid-3", "Failed", "cannot send to self")
    );
    var page = AliyunChatAppBroadcastGateway.parseReconciliation(
            ListChatappMessageResponseBody.builder()
                    .code("OK").requestId("request-real-1").data(rows).build(), 1, 100);

    assertThat(page.items()).extracting(ReconciliationItem::status)
            .containsExactly(DELIVERED, READ, FAILED_RECIPIENT);
    assertThat(page.diagnostic().providerRequestId()).isEqualTo("request-real-1");
    assertThat(page.diagnostic().diagnosticCode()).isEqualTo("CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING");
}

@Test
void preservesProviderFieldsForNonOkResponse() {
    var body = ListChatappMessageResponseBody.builder()
            .success(false).code("InvalidParameter").message("group message id invalid")
            .requestId("request-error-1").build();
    assertThatThrownBy(() -> AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100))
            .isInstanceOfSatisfying(ChatAppBroadcastException.class, error -> {
                assertThat(error.providerCode()).isEqualTo("InvalidParameter");
                assertThat(error.providerRequestId()).isEqualTo("request-error-1");
                assertThat(error.safeMessage()).isEqualTo("group message id invalid");
            });
}

@Test
void keepsMissingRecipientAsDiagnosticRow() {
    var body = ListChatappMessageResponseBody.builder().success(true).code("OK")
            .requestId("request-row-1")
            .data(List.of(row(null, "wamid-missing", "Failed", "invalid recipient"))).build();
    var page = AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100);
    assertThat(page.items()).singleElement().satisfies(item -> {
        assertThat(item.rowNumber()).isEqualTo(1);
        assertThat(item.recipientNumber()).isBlank();
        assertThat(item.diagnosticCode()).isEqualTo("CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING");
    });
}

@Test
void keepsEmptyDataAsRetryableEnvelopeDiagnostic() {
    var body = ListChatappMessageResponseBody.builder().success(true).code("OK")
            .requestId("request-empty-1").data(List.of()).build();
    var page = AliyunChatAppBroadcastGateway.parseReconciliation(body, 1, 100);
    assertThat(page.items()).isEmpty();
    assertThat(page.diagnostic().diagnosticCode())
            .isEqualTo("CHATAPP_BROADCAST_RECONCILIATION_DATA_EMPTY");
}

private static ListChatappMessageResponseBody.Data row(
        String number, String messageId, String status, String failureReason) {
    return ListChatappMessageResponseBody.Data.builder()
            .userNumber(number).messageId(messageId).uniqueMessageId("unique-" + messageId)
            .messageStatusName(status).failReason(failureReason).sendTime("1786932000000").build();
}
```

- [ ] **Step 2：运行 RED**

```bash
mvn -Dtest=AliyunChatAppBroadcastGatewayTest test
```

Expected：FAIL，当前实现要求 `Success == true` 且异常没有 Provider 字段。

- [ ] **Step 3：扩展异常和 gateway records**

```java
public record ProviderDiagnostic(
        String providerCode,
        String providerMessage,
        String providerRequestId,
        String diagnosticCode) {
}

public record ReconciliationItem(
        int rowNumber,
        String recipientNumber,
        String providerMessageId,
        String providerUniqueMessageId,
        RecipientStatus status,
        String rawProviderStatus,
        String failureReason,
        Instant providerSentAt,
        String diagnosticCode) {
}

public record ReconciliationPage(
        List<ReconciliationItem> items,
        int page,
        boolean hasNext,
        ProviderDiagnostic diagnostic) {
    public ReconciliationPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

public ChatAppBroadcastException(
        String code, HttpStatus status, boolean resultUnknown, boolean retryable,
        String providerCode, String providerRequestId, String safeMessage, Throwable cause) {
    super(code, cause);
    this.status = Objects.requireNonNull(status);
    this.resultUnknown = resultUnknown;
    this.retryable = retryable;
    this.providerCode = bounded(providerCode, 100);
    this.providerRequestId = bounded(providerRequestId, 255);
    this.safeMessage = bounded(safeMessage, 1000);
}

public ChatAppBroadcastException(String code, HttpStatus status) {
    this(code, status, false, false, "", "", code, null);
}

public ChatAppBroadcastException(
        String code, HttpStatus status, boolean resultUnknown, boolean retryable, Throwable cause) {
    this(code, status, resultUnknown, retryable, "", "", code, cause);
}

public String providerCode() { return providerCode; }
public String providerRequestId() { return providerRequestId; }
public String safeMessage() { return safeMessage; }

private static String bounded(String value, int maxLength) {
    if (value == null) return "";
    String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
}
```

所有旧构造器按上面代码委托到完整构造器，保持调用点编译；但 Provider adapter 必须使用完整构造器。

- [ ] **Step 4：重写响应接受规则**

```java
if (body == null) throw invalid("CHATAPP_BROADCAST_RECONCILIATION_BODY_MISSING", null, null, null);
String code = value(body.getCode());
if (!code.isBlank() && !"OK".equalsIgnoreCase(code)) {
    throw rejected(code, body.getRequestId(), body.getMessage());
}
if (body.getData() == null) {
    return new ReconciliationPage(
            List.of(), page, false,
            new ProviderDiagnostic(code, body.getMessage(), body.getRequestId(),
                    "CHATAPP_BROADCAST_RECONCILIATION_DATA_MISSING"));
}
if (body.getData().isEmpty()) {
    return new ReconciliationPage(
            List.of(), page, false,
            new ProviderDiagnostic(code, body.getMessage(), body.getRequestId(),
                    "CHATAPP_BROADCAST_RECONCILIATION_DATA_EMPTY"));
}
String diagnosticCode = body.getSuccess() == null
        ? "CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING"
        : Boolean.FALSE.equals(body.getSuccess())
            ? "CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT" : "";
// Data 非空时继续逐行解析；rowNumber 从 1 开始，缺字段生成 diagnostic item。
```

行映射只保留一个实现：`AliyunChatAppBroadcastGateway.public static ReconciliationItem parseRow(ListChatappMessageResponseBody.Data row, int rowNumber)`。`parseReconciliation()` 和 Task 5 linker 都调用它；状态字段优先级固定为 `ClientReadStatusName -> MessageStatusName -> ClientAcceptStatusName -> MessageStatus -> ClientReadStatus`，`rawProviderStatus` 保存第一个非空原值。

SDK/网络异常遍历 cause chain，提取 `PopClientException/PopServerException` 的 code、RequestId 和 message；日志只记录 code 和 RequestId。

提取方法必须使用当前依赖真实存在的 `getErrCode()/getRequestId()/getErrMessage()`：

```java
private static ProviderFailure providerFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
        if (current instanceof PopClientException client) {
            return new ProviderFailure(
                    value(client.getErrCode()), value(client.getRequestId()), value(client.getErrMessage()));
        }
        if (current instanceof PopServerException server) {
            return new ProviderFailure(
                    value(server.getErrCode()), value(server.getRequestId()), value(server.getErrMessage()));
        }
        current = current.getCause();
    }
    return new ProviderFailure("", "", "");
}

private record ProviderFailure(String code, String requestId, String message) {
}
```

- [ ] **Step 5：运行 Task 3 门禁**

```bash
mvn -Dtest=AliyunChatAppBroadcastGatewayTest test
```

Expected：PASS。

- [ ] **Step 6：提交 Task 3**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastException.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastGateway.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppBroadcastGateway.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppBroadcastGatewayTest.java
git commit -m "fix: preserve ChatApp broadcast reconciliation evidence"
```

---

### Task 4：Worker 即时投影、部分对账和安全重试

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorker.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorkerTest.java`

**接口：**
- Consumes：Task 2 `ProjectionResult ensureProcessing(UUID broadcastId, UUID recipientId)`。
- Consumes：Task 3 `ReconciliationPage/ProviderDiagnostic`。
- Produces：`ChatAppBroadcastMessageProjector.applyReconciliation(UUID broadcastId, UUID recipientId, ReconciliationItem item, Instant reconciledAt) -> ReconciliationProjectionResult`。
- Produces：`ChatAppBroadcastMapper.updateReconciliationDiagnostic(UUID id, String requestId, String providerCode, String errorCode, String errorMessage, Instant updatedAt)`。

- [ ] **Step 1：写提交后即时投影红灯测试**

```java
@Test
void submissionPersistsGroupBeforeProjectingAndNeverResubmitsAfterProjectionFailure() {
    when(gateway.submit(any())).thenReturn(new SubmissionResult("group-1", "request-1", "OK"));
    doThrow(new IllegalStateException("local projection failed"))
            .when(messageProjector).ensureProcessing(broadcastId, recipientId);

    worker.runAvailable("worker-1", 10);

    verify(broadcastMapper).markSubmitted(broadcastId, "group-1", "request-1", "OK", NOW);
    verify(gateway, times(1)).submit(any());
    verify(jobMapper).insert(argThat(job -> "RECONCILE".equals(job.getJobType())));
}
```

测试必须证明下一轮只处理 RECONCILE，不会创建第二个 SUBMIT job。

- [ ] **Step 2：写 2 成功 + 1 失败红灯测试**

```java
@Test
void reconciliationPersistsTwoSuccessesAndOneRecipientFailure() {
    when(gateway.reconcile(any())).thenReturn(page(List.of(
            item(1, "60111111111", "wamid-1", DELIVERED, null),
            item(2, "60122222222", "wamid-2", READ, null),
            item(3, "60199999999", "wamid-3", FAILED_RECIPIENT, "cannot send to self")
    )));

    worker.runAvailable("worker-1", 10);

    verify(broadcastMapper).updateAggregate(
            broadcastId, "PARTIALLY_FAILED", 2, 1, 0, NOW, null, null, NOW);
    verify(messageProjector, times(3)).applyReconciliation(eq(broadcastId), any(), any(), eq(NOW));
}

@Test
void unmatchedRowKeepsMatchedFactsAndMarksStatusUnknown() {
    when(gateway.reconcile(any())).thenReturn(page(List.of(
            item(1, "60111111111", "wamid-1", DELIVERED, null),
            item(2, "60122222222", "wamid-2", READ, null),
            item(3, "", "wamid-3", FAILED_RECIPIENT, "recipient missing")
    )));
    worker.runAvailable("worker-1", 10);
    verify(messageProjector, times(2)).applyReconciliation(eq(broadcastId), any(), any(), eq(NOW));
    verify(broadcastMapper).markError(eq(broadcastId), eq("STATUS_UNKNOWN"),
            eq("CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT"), any(), eq(NOW));
    verify(evidenceMapper, times(4)).upsert(any());
}

@Test
void providerFailureDoesNotMutateRecipientOrMessage() {
    when(gateway.reconcile(any())).thenThrow(new ChatAppBroadcastException(
            "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
            false, true, "Throttling", "request-throttle", "rate limited", null));
    worker.runAvailable("worker-1", 10);
    verify(messageProjector, never()).applyReconciliation(any(), any(), any(), any());
    verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
    verify(evidenceMapper).upsert(argThat(evidence -> evidence.getRowNumber() == 0
            && "request-throttle".equals(evidence.getProviderRequestId())
            && "Throttling".equals(evidence.getProviderStatus())));
}

@Test
void reconciliationReusesProviderHistoryWhenProjectionHasNotBeenCreated() {
    recipient.setMessageId(null);
    when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
            .thenReturn(List.of(providerHistoryMessage));

    ReconciliationProjectionResult result = messageProjector.applyReconciliation(
            broadcastId, recipientId,
            item(1, "60111111111", "wamid-1", DELIVERED, null), NOW);

    assertThat(result.messageId()).isEqualTo(providerHistoryMessage.getId());
    assertThat(result.diagnosticCode()).isBlank();
    verify(recipientMapper).linkMessageIfAbsent(recipientId, providerHistoryMessage.getId(), NOW);
}

@Test
void reconciliationStopsWhenProviderIdAlreadyBelongsToAnotherMessage() {
    recipient.setMessageId(localProcessingMessage.getId());
    when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
            .thenReturn(List.of(providerHistoryMessage));

    ReconciliationProjectionResult result = messageProjector.applyReconciliation(
            broadcastId, recipientId,
            item(1, "60111111111", "wamid-1", DELIVERED, null), NOW);

    assertThat(result.diagnosticCode()).isEqualTo("CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
    verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
    verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
}

@Test
void reconcileBackfillsMissingProcessingMessagesBeforeCallingProvider() {
    when(recipientMapper.findWithoutMessage(broadcastId, 1000)).thenReturn(List.of(recipient));
    when(gateway.reconcile(any())).thenReturn(page(List.of()));

    worker.runAvailable("worker-1", 10);

    InOrder order = inOrder(messageProjector, gateway);
    order.verify(messageProjector).ensureProcessing(broadcastId, recipientId);
    order.verify(gateway).reconcile(any());
}

private static ReconciliationItem item(int row, String number, String messageId,
                                       RecipientStatus status, String failureReason) {
    return new ReconciliationItem(row, number, messageId, "unique-" + messageId, status,
            status.name(), failureReason, NOW, number.isBlank()
            ? "CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING" : "");
}

private static ReconciliationPage page(List<ReconciliationItem> items) {
    return new ReconciliationPage(items, 1, false,
            new ProviderDiagnostic("OK", "", "request-2", ""));
}
```

- [ ] **Step 3：运行 RED**

```bash
mvn -Dtest=ChatAppBroadcastWorkerTest test
```

Expected：FAIL，worker 未注入 projector/evidence mapper，且部分结果仍被压成泛化错误。

- [ ] **Step 4：实现提交后的两阶段本地持久化**

Provider submit 返回后先完成现有短事务：完成 SUBMIT、markSubmitted、recipient PROCESSING、插入 RECONCILE job。事务提交后：

```java
for (ChatAppBroadcastRecipientEntity recipient : recipientMapper.findWithoutMessage(broadcast.getId(), 1000)) {
    messageProjector.ensureProcessing(broadcast.getId(), recipient.getId());
}
```

投影异常由已存在的 RECONCILE job 接管；worker 不得回到 `failSubmission()`。

RECONCILE 处理的核心循环固定为：

```java
boolean unmatched = false;
boolean hasNext = true;
int page = 1;
while (hasNext && page <= MAX_RECONCILE_PAGES) {
    ReconciliationPage result = gateway.reconcile(query(page));
    unmatched |= persistReconciliationPage(job, broadcast, result);
    hasNext = result.hasNext();
    page++;
}
finalizeReconciliation(job, broadcast, page > MAX_RECONCILE_PAGES && hasNext, unmatched);
```

Provider 调用在事务外，单页持久化和最终聚合在事务内；因此后页网络异常只进入有界重试，不回滚 `unmatched` 之前已经保存的页。

- [ ] **Step 5：实现对账投影的四种唯一关联结果**

`applyReconciliation()` 使用 `@Transactional`，按以下固定顺序处理：

```java
ChatAppBroadcastRecipientEntity recipient = recipientMapper.findByIdForUpdate(recipientId)
        .orElseThrow(() -> new IllegalStateException("CHATAPP_BROADCAST_RECIPIENT_NOT_FOUND"));
ChatAppBroadcastEntity broadcast = broadcastMapper.selectById(broadcastId);
if (broadcast == null || !broadcastId.equals(recipient.getBroadcastId())) {
    throw new IllegalStateException("CHATAPP_BROADCAST_SCOPE_MISMATCH");
}

List<MessageEntity> providerMatches = item.providerMessageId().isBlank()
        ? List.of()
        : messageMapper.findAllByProviderMessageId(
                broadcast.getChannelAccountId(), item.providerMessageId());
if (providerMatches.size() > 1) {
    return new ReconciliationProjectionResult(
            recipient.getMessageId(), false, false, "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
}

MessageEntity linked = recipient.getMessageId() == null
        ? null : messageMapper.selectById(recipient.getMessageId());
MessageEntity providerMessage = providerMatches.isEmpty() ? null : providerMatches.get(0);
if (linked != null && providerMessage != null && !linked.getId().equals(providerMessage.getId())) {
    return new ReconciliationProjectionResult(
            linked.getId(), false, false, "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
}

boolean created = false;
MessageEntity target = linked != null ? linked : providerMessage;
if (target == null) {
    ProjectionResult projection = ensureProcessingInternal(broadcast, recipient);
    target = messageMapper.selectById(projection.messageId());
    created = projection.created();
} else if (recipient.getMessageId() == null) {
    ConversationEntity expectedConversation = conversationMapper.getOrCreateConversationForSender(
            broadcast.getChannelAccountId(), recipient.getContactIdentityId(),
            broadcast.getCreatedByUserId());
    if (!broadcast.getChannelAccountId().equals(target.getChannelAccountId())
            || !expectedConversation.getId().equals(target.getConversationId())
            || recipientMapper.linkMessageIfAbsent(recipientId, target.getId(), reconciledAt) != 1) {
        return new ReconciliationProjectionResult(
                target.getId(), false, false, "CHATAPP_BROADCAST_MESSAGE_SCOPE_CONFLICT");
    }
}

String nextStatus = recipientMessageStatus(item.status());
String advanced = ChatAppOutboundMessageStateMachine.advance(target.getCurrentStatus(), nextStatus);
messageMapper.updateDeliveryStatus(target.getId(), item.providerMessageId(), advanced, reconciledAt);
recipientMapper.updateProviderStatus(
        recipientId, item.providerMessageId(), item.providerUniqueMessageId(),
        item.status().name(), bounded(item.failureReason(), 1000),
        item.providerSentAt(), reconciledAt);
statusEventMapper.insertIgnore(reconciliationEvent(target, item, advanced, reconciledAt));
conversationMapper.recomputeProjection(target.getConversationId());
return new ReconciliationProjectionResult(target.getId(), created, true, "");
```

四种结果必须由测试覆盖：仅本地 processing 消息、仅 Provider 历史消息、两者是同一消息、两者冲突。冲突不得删除或自动合并消息，返回结构化 diagnostic，由 worker 保存证据并把批次置为 `STATUS_UNKNOWN`。`ensureProcessingInternal()` 是 projector 内部方法，`ensureProcessing()` 和 `applyReconciliation()` 复用它，避免同 bean 自调用绕过事务边界。

- [ ] **Step 6：实现页级证据和部分结果事务**

将原有汇总式持久化拆为 `persistReconciliationPage(ChatAppBroadcastJobEntity job, ChatAppBroadcastEntity broadcast, ReconciliationPage page) -> boolean pageUnmatched` 与 `finalizeReconciliation(ChatAppBroadcastJobEntity job, ChatAppBroadcastEntity broadcast, boolean pageLimitReached, boolean unmatched)`。每个 `gateway.reconcile()` 返回后立刻调用前者；不得等所有页返回后才写库。每页由 `TransactionOperations` 开启独立短事务，保存 envelope 和行事实后提交；每个 item 保存真实行号。随后：

```java
boolean pageUnmatched = false;
evidenceMapper.upsert(evidence(job, page, 0, page.diagnostic(), null, null, ""));
for (ReconciliationItem item : page.items()) {
    Optional<ChatAppBroadcastRecipientEntity> stored = item.recipientNumber().isBlank()
            ? Optional.empty()
            : recipientMapper.findByNumber(broadcast.getId(), item.recipientNumber());
    evidenceMapper.upsert(evidence(
            job, page, item.rowNumber(), page.diagnostic(), stored.orElse(null), item, ""));
    if (stored.isPresent()) {
        ReconciliationProjectionResult projection = messageProjector.applyReconciliation(
                broadcast.getId(), stored.orElseThrow().getId(), item, now);
        if (!projection.diagnosticCode().isBlank()) {
            evidenceMapper.upsert(evidence(
                    job, page, item.rowNumber(), page.diagnostic(), stored.orElse(null),
                    item, projection.diagnosticCode()));
            pageUnmatched = true;
        }
    } else {
        pageUnmatched = true;
    }
}
```

helper 签名固定为 `evidence(ChatAppBroadcastJobEntity job, ReconciliationPage page, int rowNumber, ProviderDiagnostic diagnostic, ChatAppBroadcastRecipientEntity matched, ReconciliationItem item, String diagnosticOverride)`。最后一个参数非空时覆盖 item 的 diagnosticCode；helper 必须对 RequestId、号码、Provider ID、原始状态和失败原因统一调用 `bounded()`，并让 `failureReason` 最大 1000 字符。`pageUnmatched` 必须返回给外层循环累计到该 job 的最终判断，不能仅保存在方法局部后丢失。

`finalizeReconciliation()` 重新读取 recipient 状态聚合。只要仍有 PROCESSING 就继续有界对账；无 PROCESSING 且有失败则 `PARTIALLY_FAILED`；全部成功则 `SUCCEEDED`。任一页出现未匹配、投影冲突或页数达到上限时，不回滚已匹配事实，并将批次标记 `STATUS_UNKNOWN`。

worker 每次进入 RECONCILE 时，必须先对 `findWithoutMessage(broadcastId, 1000)` 的结果调用 `ensureProcessing()`，完成后才能调用 Provider。后页调用失败不得回滚前页已确认事实。

- [ ] **Step 7：保存 Provider 诊断**

`updateReconciliationDiagnostic()` 只写 `last_reconciliation_request_id/last_reconciliation_provider_code/error_code/error_message`，不得覆盖提交的 `provider_request_id/provider_code`。

Provider 非 OK 或 SDK 异常进入 catch 时，worker 已知当前页码，必须在重试 job 前用短事务保存一条 `row_number=0` evidence：`provider_request_id=error.providerRequestId()`、`provider_status=error.providerCode()`、`failure_reason=error.safeMessage()`、`diagnostic_code=error.getMessage()`。随后调用 `updateReconciliationDiagnostic()` 和现有指数退避；不得修改 recipient/message 状态。

- [ ] **Step 8：运行 Task 4 门禁**

```bash
mvn -Dtest=ChatAppBroadcastWorkerTest,ChatAppBroadcastMessageProjectorTest,AliyunChatAppBroadcastGatewayTest test
```

Expected：PASS。

- [ ] **Step 9：提交 Task 4**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorker.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastMessageProjector.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastWorkerTest.java
git commit -m "fix: reconcile ChatApp broadcasts into messages"
```

---

### Task 5：普通 polling 与群发消息的唯一关联

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageLinker.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageLinkerTest.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java`

**接口：**
- Produces：`LinkResult resolve(UUID channelAccountId, ListChatappMessageResponseBody.Data row)`。
- Produces：`LinkResult.Kind = EXISTING_PROVIDER | EXISTING_CLIENT_REQUEST | UNIQUE_BROADCAST | AMBIGUOUS_BROADCAST | PROVIDER_CONFLICT | ORPHAN`。
- Produces：`LinkResult(Kind kind, UUID messageId, String reason)`，其中 `messageResolved()` 仅在 messageId 非空时返回 true。
- Consumes：Task 2 projector/state machine 和 Task 4 provider binding。

- [ ] **Step 1：写顺序竞争红灯测试**

```java
@Test
void pollingLinksTheOnlyPendingBroadcastMessageInsteadOfImportingAnOrphan() {
    candidate.setMessageId(null);
    when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1")).thenReturn(List.of());
    when(messageMapper.findByClientRequestId(accountId, "unique-1")).thenReturn(Optional.empty());
    when(recipientMapper.findPendingCandidates(
            accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
            .thenReturn(List.of(candidate));
    when(messageProjector.ensureProcessing(candidate.getBroadcastId(), candidate.getId()))
            .thenReturn(new ChatAppBroadcastMessageProjector.ProjectionResult(
                    messageId, true, "processing"));
    when(messageProjector.applyReconciliation(
            eq(candidate.getBroadcastId()), eq(candidate.getId()), any(), eq(sentAt)))
            .thenReturn(new ChatAppBroadcastMessageProjector.ReconciliationProjectionResult(
                    messageId, true, true, ""));

    LinkResult result = linker.resolve(accountId, row);

    assertThat(result.kind()).isEqualTo(UNIQUE_BROADCAST);
    assertThat(result.messageId()).isEqualTo(messageId);
    InOrder order = inOrder(messageProjector);
    order.verify(messageProjector).ensureProcessing(candidate.getBroadcastId(), candidate.getId());
    verify(messageProjector).applyReconciliation(
            eq(candidate.getBroadcastId()), eq(candidate.getId()),
            argThat(item -> "wamid-1".equals(item.providerMessageId())
                    && "60111111111".equals(item.recipientNumber())), eq(sentAt));
}

@Test
void multiplePendingBroadcastsStayAmbiguous() {
    when(recipientMapper.findPendingCandidates(
            accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
            .thenReturn(List.of(firstCandidate, secondCandidate));
    LinkResult result = linker.resolve(accountId, row);
    assertThat(result.kind()).isEqualTo(AMBIGUOUS_BROADCAST);
    verifyNoInteractions(messageProjector);
}

@Test
void noPendingBroadcastReturnsOrphan() {
    when(recipientMapper.findPendingCandidates(
            accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
            .thenReturn(List.of());
    assertThat(linker.resolve(accountId, row).kind()).isEqualTo(ORPHAN);
}

@Test
void existingProviderMessageWinsBeforeBroadcastCandidateLookup() {
    when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
            .thenReturn(List.of(existingMessage));
    LinkResult result = linker.resolve(accountId, row);
    assertThat(result.kind()).isEqualTo(EXISTING_PROVIDER);
    verify(recipientMapper, never()).findPendingCandidates(any(), any(), any(), any(), any(), anyInt());
}

@Test
void duplicateProviderIdStopsBeforeAnyCandidateGuess() {
    when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
            .thenReturn(List.of(firstProviderMessage, secondProviderMessage));
    LinkResult result = linker.resolve(accountId, row);
    assertThat(result.kind()).isEqualTo(PROVIDER_CONFLICT);
    assertThat(result.reason()).isEqualTo("CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
    verifyNoInteractions(messageProjector);
}

// ChatAppPollingProjectorTest
@Test
void existingOrdinaryMessageStillProjectsTheProviderStatusEvent() {
    when(linker.resolve(accountId, row))
            .thenReturn(new LinkResult(EXISTING_PROVIDER, existingMessage.getId(), ""));
    when(messageMapper.selectById(existingMessage.getId())).thenReturn(existingMessage);

    var result = pollingProjector.project(row, accountId);

    assertThat(result.messageSaved()).isTrue();
    verify(eventMapper).insertIgnore(any());
    verify(webhookProjector).project(any());
}
```

- [ ] **Step 2：运行 RED**

```bash
mvn -Dtest=ChatAppOutboundMessageLinkerTest,ChatAppPollingProjectorTest test
```

Expected：FAIL，linker 和候选查询不存在。

- [ ] **Step 3：实现有界候选查询**

`List<ChatAppBroadcastRecipientEntity> findPendingCandidates(UUID channelAccountId, String normalizedNumber, String templateCode, String languageCode, Instant providerSentAt, int limit)` 必须使用：

```sql
select r.*
from chatapp_broadcast_recipients r
join chatapp_broadcasts b on b.id = r.broadcast_id
where b.channel_account_id = #{channelAccountId}
  and r.recipient_number_snapshot = #{normalizedNumber}
  and b.template_code = #{templateCode}
  and b.language_code = #{languageCode}
  and r.provider_message_id is null
  and r.status = 'PROCESSING'
  and (#{providerSentAt} is null or b.submitted_at between #{providerSentAt} - interval '24 hours' and #{providerSentAt} + interval '5 minutes')
order by b.submitted_at desc, r.id
limit 2
```

限制返回 2 条即可区分 0、1、多条，禁止无界扫描。

- [ ] **Step 4：实现共享 linker 的固定解析顺序**

```java
public record LinkResult(Kind kind, UUID messageId, String reason) {
    public enum Kind {
        EXISTING_PROVIDER,
        EXISTING_CLIENT_REQUEST,
        UNIQUE_BROADCAST,
        AMBIGUOUS_BROADCAST,
        PROVIDER_CONFLICT,
        ORPHAN
    }

    public boolean messageResolved() {
        return messageId != null;
    }
}

public LinkResult resolve(UUID channelAccountId, ListChatappMessageResponseBody.Data row) {
    String providerMessageId = firstNonBlank(row.getMessageId(), row.getUniqueMessageId());
    List<MessageEntity> providerMatches = providerMessageId.isBlank()
            ? List.of() : messageMapper.findAllByProviderMessageId(channelAccountId, providerMessageId);
    if (providerMatches.size() > 1) {
        return new LinkResult(PROVIDER_CONFLICT, null, "CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
    }
    if (providerMatches.size() == 1) {
        return new LinkResult(EXISTING_PROVIDER, providerMatches.get(0).getId(), "");
    }

    String providerClientRequestId = value(row.getUniqueMessageId());
    if (!providerClientRequestId.isBlank()) {
        Optional<MessageEntity> ordinary = messageMapper.findByClientRequestId(
                channelAccountId, providerClientRequestId);
        if (ordinary.isPresent()) {
            return new LinkResult(EXISTING_CLIENT_REQUEST, ordinary.orElseThrow().getId(), "");
        }
    }

    String number = ContactPointUtil.normalizePhone(row.getUserNumber());
    Instant sentAt = parseInstant(row.getSendTime());
    List<ChatAppBroadcastRecipientEntity> candidates = recipientMapper.findPendingCandidates(
            channelAccountId, number, value(row.getTemplateCode()),
            value(row.getLanguageCode()), sentAt, 2);
    if (candidates.size() > 1) {
        return new LinkResult(AMBIGUOUS_BROADCAST, null, "AMBIGUOUS_BROADCAST_RECIPIENT");
    }
    if (candidates.isEmpty()) {
        return new LinkResult(ORPHAN, null, "");
    }

    ChatAppBroadcastRecipientEntity candidate = candidates.get(0);
    messageProjector.ensureProcessing(candidate.getBroadcastId(), candidate.getId());
    ReconciliationProjectionResult projection = messageProjector.applyReconciliation(
            candidate.getBroadcastId(), candidate.getId(), reconciliationItem(row), sentAt);
    if (!projection.diagnosticCode().isBlank()) {
        return new LinkResult(PROVIDER_CONFLICT, null, projection.diagnosticCode());
    }
    return new LinkResult(UNIQUE_BROADCAST, projection.messageId(), "");
}
```

`reconciliationItem(row)` 必须直接调用 Task 3 的 `AliyunChatAppBroadcastGateway.parseRow(row, 1)`，禁止在 linker 再写一套状态解析。候选查询不要求 `message_id is not null`：若 polling 先于本地即时投影到达，linker 必须先 `ensureProcessing()`，再绑定 Provider 状态，从而避免先导入孤儿消息。

- [ ] **Step 5：接入 polling 和 webhook 状态策略**

`ChatAppPollingProjector.project()` 在构造孤儿 outbound event 前调用 linker：

```java
LinkResult link = outboundMessageLinker.resolve(channelAccountId, row);
if (link.kind() == AMBIGUOUS_BROADCAST) return skipped("AMBIGUOUS_BROADCAST_RECIPIENT");
if (link.kind() == PROVIDER_CONFLICT) return skipped(link.reason());
if (link.kind() == UNIQUE_BROADCAST) {
    return new ProjectionResult(true, false, "");
}
Optional<MessageEntity> local = link.messageResolved()
        ? Optional.ofNullable(messageMapper.selectById(link.messageId()))
        : Optional.empty();
if (link.messageResolved() && local.isEmpty()) {
    return skipped("CHATAPP_OUTBOUND_LINK_TARGET_MISSING");
}
// EXISTING_PROVIDER / EXISTING_CLIENT_REQUEST 继续走现有 status event + webhook 投影；
// ORPHAN 保持 local 为空，沿用现有孤儿 outbound 导入。
return projectStatusEvent(row, channelAccountId, local);
```

不得对 `EXISTING_PROVIDER/EXISTING_CLIENT_REQUEST` 提前返回：它们仍需要把本次 Provider 状态写入 `message_status_events` 并单调更新已有消息。只有 `UNIQUE_BROADCAST` 已由 `applyReconciliation()` 完成消息、recipient 和状态事件更新，可以直接结束本次 polling 投影。

删除 `ChatAppWebhookProjector.shouldAdvance()` 的重复实现，改用 `ChatAppOutboundMessageStateMachine.advance()`；仅当返回状态不同才执行数据库 UPDATE。

- [ ] **Step 6：运行 Task 5 门禁**

```bash
mvn -Dtest=ChatAppOutboundMessageLinkerTest,ChatAppPollingProjectorTest,ChatAppWebhookProjectorTest,ChatAppMessagePeerReconciliationServiceTest test
```

Expected：PASS；身份对账回归继续通过。

- [ ] **Step 7：提交 Task 5**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageLinker.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastRecipientMapper.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOutboundMessageLinkerTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java
git commit -m "fix: deduplicate ChatApp broadcast polling"
```

---

### Task 6：重新对账命令、详情合同和权限

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastModels.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastJobMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastReconciliationEvidenceMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppBroadcastController.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppBroadcastControllerTest.java`

**接口：**
- Produces：`BroadcastView` 增加 `providerRequestId/providerCode/lastReconciliationRequestId/lastReconciliationProviderCode`。
- Produces：`RecipientView` 增加 `messageId`。
- Produces：`ReconciliationSummary(long evidenceRows, long matchedRows, long unmatchedRows, int processingRecipients, String latestDiagnosticCode)`。
- Produces：`BroadcastDetail(BroadcastView broadcast, List<RecipientView> recipients, ReconciliationSummary reconciliation)`。
- Produces：`BroadcastView requestReconciliation(UUID broadcastId, UUID actorUserId)`。

- [ ] **Step 1：写 service 红灯测试**

```java
@Test
void statusUnknownWithGroupIdCreatesOneReadOnlyReconciliationJob() {
    broadcast.setStatus("STATUS_UNKNOWN");
    broadcast.setProviderGroupMessageId("group-1");
    when(jobMapper.insertReconcileIfAbsent(any())).thenReturn(1);

    BroadcastView result = service.requestReconciliation(broadcastId, actorId);

    assertThat(result.id()).isEqualTo(broadcastId);
    verify(jobMapper).insertReconcileIfAbsent(argThat(job ->
            "RECONCILE".equals(job.getJobType()) && "PENDING".equals(job.getStatus())));
}

@ParameterizedTest
@EnumSource(value = BroadcastStatus.class, names = {"SUBMITTED", "RECONCILING", "STATUS_UNKNOWN"})
void reconciliationRequestAcceptsOnlyRecoverableStatuses(BroadcastStatus status) {
    broadcast.setStatus(status.name());
    broadcast.setProviderGroupMessageId("group-1");
    when(jobMapper.insertReconcileIfAbsent(any())).thenReturn(1);
    assertThat(service.requestReconciliation(broadcastId, actorId).id()).isEqualTo(broadcastId);
}

@Test
void reconciliationRequestRejectsMissingGroupIdAndInvalidStatuses() {
    broadcast.setProviderGroupMessageId(null);
    assertThatThrownBy(() -> service.requestReconciliation(broadcastId, actorId))
            .isInstanceOfSatisfying(ChatAppBroadcastException.class, error ->
                    assertThat(error.getMessage()).isEqualTo("CHATAPP_BROADCAST_GROUP_ID_MISSING"));
    broadcast.setProviderGroupMessageId("group-1");
    for (String status : List.of("FAILED", "SUBMISSION_UNKNOWN")) {
        broadcast.setStatus(status);
        assertThatThrownBy(() -> service.requestReconciliation(broadcastId, actorId))
                .isInstanceOfSatisfying(ChatAppBroadcastException.class, error ->
                        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}

@Test
void activeReconciliationJobIsIdempotentAndUnauthorizedActorIsRejected() {
    when(jobMapper.insertReconcileIfAbsent(any())).thenReturn(0);
    assertThat(service.requestReconciliation(broadcastId, actorId).id()).isEqualTo(broadcastId);
    verify(jobMapper, times(1)).insertReconcileIfAbsent(any());
    assertThatThrownBy(() -> service.requestReconciliation(broadcastId, foreignActorId))
            .isInstanceOfSatisfying(ChatAppBroadcastException.class, error ->
                    assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
}
```

- [ ] **Step 2：写 controller 红灯测试**

```java
mvc.perform(post("/api/v1/chatapp/broadcasts/{id}/reconcile", BROADCAST_ID)
        .with(user(ACTOR_ID.toString()).roles("BROADCAST_SENDER")))
    .andExpect(status().isAccepted())
    .andExpect(jsonPath("$.id").value(BROADCAST_ID.toString()));
verify(service).requestReconciliation(BROADCAST_ID, ACTOR_ID);
```

- [ ] **Step 3：运行 RED**

```bash
mvn -Dtest=ChatAppBroadcastApplicationServiceTest,ChatAppBroadcastControllerTest test
```

Expected：FAIL，接口和响应字段不存在。

- [ ] **Step 4：实现原子 job 创建**

`ChatAppBroadcastJobMapper` 增加：

```java
@Insert("insert into chatapp_broadcast_jobs (id, broadcast_id, job_type, status, " +
        "attempt_count, max_attempts, next_attempt_at, created_at, updated_at) " +
        "select #{job.id}::uuid, #{job.broadcastId}::uuid, 'RECONCILE', 'PENDING', " +
        "0, 10, #{job.nextAttemptAt}, #{job.createdAt}, #{job.updatedAt} " +
        "where not exists (select 1 from chatapp_broadcast_jobs " +
        "where broadcast_id = #{job.broadcastId}::uuid and job_type = 'RECONCILE' " +
        "and status in ('PENDING','PROCESSING','FAILED')) on conflict do nothing")
int insertReconcileIfAbsent(@Param("job") ChatAppBroadcastJobEntity job);
```

Service 在 `findByIdForUpdate()` 后执行 access、账号、状态和 GroupMessageId 校验。允许状态精确为 `SUBMITTED/RECONCILING/STATUS_UNKNOWN`；`FAILED/SUBMISSION_UNKNOWN/SUCCEEDED/PARTIALLY_FAILED/CANCELLED` 返回 `409 CONFLICT`。新 job：attemptCount=0、maxAttempts=10、nextAttemptAt=clock.instant()。`insertReconcileIfAbsent()` 返回 0 代表已有 active job，按幂等成功返回当前 BroadcastView，不再创建第二个 job。

- [ ] **Step 5：扩展详情投影**

详情返回 recipient.messageId、最近 Provider 诊断和 evidence 汇总；完整号码继续使用现有 mask，不通过详情泄露 `user_number`。

`ChatAppBroadcastModels` 的响应合同必须精确更新为：

```java
public record BroadcastView(
        UUID id,
        UUID channelAccountId,
        String name,
        String templateCode,
        String templateName,
        String languageCode,
        int recipientCount,
        int successCount,
        int failedCount,
        int processingCount,
        BroadcastStatus status,
        String providerGroupMessageId,
        String providerRequestId,
        String providerCode,
        String lastReconciliationRequestId,
        String lastReconciliationProviderCode,
        String errorCode,
        String errorMessage,
        UUID retriesBroadcastId,
        UUID createdByUserId,
        Instant submittedAt,
        Instant reconciledAt,
        Instant createdAt,
        Instant updatedAt) {
}

public record RecipientView(
        UUID id,
        UUID contactId,
        UUID contactIdentityId,
        String recipientName,
        String maskedNumber,
        Map<String, String> templateParams,
        UUID messageId,
        String providerMessageId,
        String providerUniqueMessageId,
        RecipientStatus status,
        String failureReason,
        Instant providerSentAt,
        Instant lastReconciledAt) {
    public RecipientView {
        templateParams = templateParams == null ? Map.of() : Map.copyOf(templateParams);
    }
}

public record ReconciliationSummary(
        long evidenceRows,
        long matchedRows,
        long unmatchedRows,
        int processingRecipients,
        String latestDiagnosticCode) {
}

public record BroadcastDetail(
        BroadcastView broadcast,
        List<RecipientView> recipients,
        ReconciliationSummary reconciliation) {
    public BroadcastDetail {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
```

Evidence mapper 增加并由详情 service 一次性读取：

```java
long countByBroadcastId(@Param("broadcastId") UUID broadcastId);
long countMatched(@Param("broadcastId") UUID broadcastId);
long countUnmatched(@Param("broadcastId") UUID broadcastId);
Optional<ChatAppBroadcastReconciliationEvidenceEntity> findLatestDiagnostic(
        @Param("broadcastId") UUID broadcastId);
```

`countMatched/countUnmatched` 都只统计 `row_number > 0`；前者要求 `matched_recipient_id is not null`，后者要求为空。`processingRecipients` 使用 broadcast 聚合字段，不额外扫描 recipient。`latestDiagnosticCode` 取最新非空 diagnostic；没有证据时返回 `null`。Controller 精确新增：

```java
@PostMapping("/{id}/reconcile")
@ResponseStatus(HttpStatus.ACCEPTED)
public BroadcastView requestReconciliation(@PathVariable UUID id) {
    return service.requestReconciliation(id, SecurityUtil.currentUserId());
}
```

- [ ] **Step 6：运行 Task 6 门禁**

```bash
mvn -Dtest=ChatAppBroadcastApplicationServiceTest,ChatAppBroadcastControllerTest,ChatAppBroadcastPersistenceIntegrationTest test
```

Expected：PASS。

- [ ] **Step 7：提交 Task 6**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastModels.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationService.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastJobMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppBroadcastReconciliationEvidenceMapper.java
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppBroadcastController.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/broadcast/ChatAppBroadcastApplicationServiceTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppBroadcastControllerTest.java
git commit -m "feat: add ChatApp broadcast reconciliation command"
```

---

### Task 7：群发详情、对账诊断和移动端界面

**文件：**
- 修改：`demo/message-center-spring/frontend/src/api/types.ts`
- 修改：`demo/message-center-spring/frontend/src/api/endpoints.ts`
- 修改：`demo/message-center-spring/frontend/src/pages/BroadcastsPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/pages/BroadcastsPage.test.tsx`

**接口：**
- Consumes：Task 6 `BroadcastDetail` 和 `POST /api/v1/chatapp/broadcasts/{id}/reconcile`。
- Produces：`fetchChatAppBroadcastDetail(id)` 和 `requestChatAppBroadcastReconciliation(id)`。

- [ ] **Step 1：写 UI 红灯测试**

先扩展现有 hoisted API mock 和共享 fixture，避免新增必填类型让既有测试失真。向现有 `vi.hoisted` object literal 增加：

```tsx
fetchChatAppBroadcastDetail: vi.fn(),
requestChatAppBroadcastReconciliation: vi.fn(),
```

向现有 `broadcast` object literal 增加：

```tsx
providerRequestId: 'submit-request-1',
providerCode: 'OK',
lastReconciliationRequestId: 'request-1',
lastReconciliationProviderCode: 'OK',
```

向现有 `fetchChatAppBroadcastFailures` recipient fixture 增加：

```tsx
messageId: 'message-2',
```

```tsx
it('shows reconciliation diagnostics and retries status without resending', async () => {
  api.fetchChatAppBroadcastDetail.mockResolvedValue({
    broadcast: {
      id: 'broadcast-1', channelAccountId: 'account-1', name: '八月通知',
      templateCode: 'shipping_notice', templateName: 'Shipping Notice', languageCode: 'zh_CN',
      recipientCount: 3, successCount: 0, failedCount: 0, processingCount: 3,
      status: 'STATUS_UNKNOWN', providerGroupMessageId: 'group-1',
      providerRequestId: 'submit-request-1', providerCode: 'OK',
      lastReconciliationRequestId: 'request-2', lastReconciliationProviderCode: 'InvalidParameter',
      errorCode: 'CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE',
      errorMessage: 'ListChatappMessage failed', retriesBroadcastId: null, createdByUserId: 'user-1',
      submittedAt: '2026-08-17T05:00:00Z', reconciledAt: null,
      createdAt: '2026-08-17T05:00:00Z', updatedAt: '2026-08-17T05:05:00Z',
    },
    recipients: [{
      id: 'recipient-1', contactId: 'contact-1', contactIdentityId: 'identity-1',
      recipientName: '张三', maskedNumber: '*******1111', templateParams: { order: 'SO-1' },
      messageId: 'message-1', providerMessageId: null, providerUniqueMessageId: null,
      status: 'PROCESSING', failureReason: null, providerSentAt: null, lastReconciledAt: null,
    }],
    reconciliation: {
      evidenceRows: 3, matchedRows: 2, unmatchedRows: 1, processingRecipients: 3,
      latestDiagnosticCode: 'CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT',
    },
  });
  renderPage();
  await user.click(await screen.findByRole('button', { name: '查看群发 八月通知' }));
  const drawer = await screen.findByRole('dialog', { name: '群发详情' });
  expect(within(drawer).getByText('request-2')).toBeInTheDocument();
  await user.click(within(drawer).getByRole('button', { name: '重新对账' }));
  await waitFor(() => expect(api.requestChatAppBroadcastReconciliation).toHaveBeenCalledWith('broadcast-1'));
  expect(api.retryChatAppBroadcastFailures).not.toHaveBeenCalled();
});
```

增加 `390x844` 约束对应的 DOM 断言：Drawer width `min(720px, 100vw)`，表格使用横向 scroll，长失败原因可换行，按钮文字不溢出。

- [ ] **Step 2：运行 RED**

```bash
npm run test:ui -- src/pages/BroadcastsPage.test.tsx
```

Expected：FAIL，详情 API 和重新对账按钮不存在。

- [ ] **Step 3：扩展前端类型和 endpoints**

```ts
// 在现有 ChatAppBroadcast 接口中新增：
providerRequestId: string | null;
providerCode: string | null;
lastReconciliationRequestId: string | null;
lastReconciliationProviderCode: string | null;

// 在现有 ChatAppBroadcastRecipient 接口中新增：
messageId: string | null;

export interface ChatAppBroadcastDetail {
  broadcast: ChatAppBroadcast;
  recipients: ChatAppBroadcastRecipient[];
  reconciliation: {
    evidenceRows: number;
    matchedRows: number;
    unmatchedRows: number;
    processingRecipients: number;
    latestDiagnosticCode: string | null;
  };
}

export async function fetchChatAppBroadcastDetail(id: string): Promise<ChatAppBroadcastDetail> {
  return (await client.get<ChatAppBroadcastDetail>(`${chatAppBroadcastBase}/${id}`)).data;
}

export async function requestChatAppBroadcastReconciliation(id: string): Promise<ChatAppBroadcast> {
  return (await client.post<ChatAppBroadcast>(`${chatAppBroadcastBase}/${id}/reconcile`)).data;
}
```

- [ ] **Step 4：实现详情 Drawer**

列表增加 `EyeOutlined` 图标按钮，tooltip/aria-label 为 `查看群发 {name}`。Drawer 展示：状态、2/1/0 计数、GroupMessageId、最近 RequestId、Provider code、结构化错误、evidence/已匹配/未匹配/仍处理中数量和全部 recipient。

仅当 `status === 'STATUS_UNKNOWN' && providerGroupMessageId` 时显示“重新对账”。mutation pending 时按钮 disabled/loading；成功后刷新列表和详情；失败后保留 Drawer 和错误 Alert。

失败重发仍只出现在明确失败明细中，不与重新对账按钮合并。

- [ ] **Step 5：运行 Task 7 门禁**

```bash
npm run test:ui -- src/pages/BroadcastsPage.test.tsx
npm run test:source
npm run build
```

Expected：BroadcastsPage 测试 PASS；source 合同测试 PASS；生产构建 PASS。

- [ ] **Step 6：提交 Task 7**

```bash
git add demo/message-center-spring/frontend/src/api/types.ts
git add demo/message-center-spring/frontend/src/api/endpoints.ts
git add demo/message-center-spring/frontend/src/pages/BroadcastsPage.tsx
git add demo/message-center-spring/frontend/src/pages/BroadcastsPage.test.tsx
git commit -m "feat: show ChatApp broadcast reconciliation details"
```

---

### Task 8：全链路回归、浏览器验收、文档回写和存量恢复

**文件：**
- 修改：`docs/superpowers/specs/2026-08-13-chatapp-contacts-template-creation-mass-messaging-design.md`
- 修改：`docs/superpowers/specs/2026-08-17-chatapp-broadcast-conversation-projection-design.md`
- 修改：`docs/superpowers/plans/2026-08-17-chatapp-broadcast-conversation-projection.md`

**接口：**
- Consumes：Tasks 1-7 全部产物。
- Produces：自动化证据、桌面/移动端截图证据、当前异常批次只读恢复结果。

- [ ] **Step 1：运行后端全链路 focused 门禁**

```bash
mvn -Dtest=ChatAppBroadcastMessageProjectionMigrationTest,ChatAppBroadcastPersistenceIntegrationTest,ChatAppOutboundMessageStateMachineTest,ChatAppBroadcastMessageProjectorTest,AliyunChatAppBroadcastGatewayTest,ChatAppBroadcastWorkerTest,ChatAppOutboundMessageLinkerTest,ChatAppPollingProjectorTest,ChatAppWebhookProjectorTest,ChatAppBroadcastApplicationServiceTest,ChatAppBroadcastControllerTest test
mvn -DskipTests package
```

Expected：focused tests 全部 PASS；package PASS；没有编译 warning 或测试 warning。

- [ ] **Step 2：运行前端全门禁**

```bash
npm test
npm run build
```

Expected：source 和 UI 测试全部 PASS；build PASS。既有 chunk warning 若仍存在，必须在验收记录中标记为非本轮债务，不能冒充本轮通过项。

- [ ] **Step 3：启动本地服务并做真实浏览器验收**

后端使用项目现有不启用 WeCom 的 profile，若 8099 已占用先只读确认 PID，再使用未占用端口；前端启动 Vite 后使用 Browser 插件检查桌面和 `390x844`：

- 群发列表能打开详情。
- `STATUS_UNKNOWN` 展示诊断和重新对账。
- mutation pending 时按钮不能重复点击。
- recipient 表包含 messageId、处理中/成功/失败和长失败原因。
- 会话页面显示 outbound 模板正文与最终状态。
- 页面无 console error、文本重叠和横向页面溢出；表格内部允许横向滚动。

- [ ] **Step 4：执行当前异常批次只读恢复前检查**

仅在用户再次确认当前运行实例、账号和只读 Provider 范围后执行。先查询：

```sql
select id, channel_account_id, status, provider_group_message_id,
       recipient_count, success_count, failed_count, processing_count
from chatapp_broadcasts
where id = 'c06366f2-f9e1-44f5-97a2-d78be833f3bc';

select id, recipient_number_snapshot, status, provider_message_id, message_id
from chatapp_broadcast_recipients
where broadcast_id = 'c06366f2-f9e1-44f5-97a2-d78be833f3bc'
order by created_at, id;
```

停止条件：GroupMessageId 缺失、账号范围变化、recipient 不再是 3 条、发现未知用户改动，或恢复需要调用 Provider 写接口。

- [ ] **Step 5：触发一次只读重新对账并验证结果**

通过已登录管理 API 调用：

```http
POST /api/v1/chatapp/broadcasts/c06366f2-f9e1-44f5-97a2-d78be833f3bc/reconcile
```

等待 RECONCILE job 终态后查询：

```sql
select status, success_count, failed_count, processing_count,
       last_reconciliation_request_id, last_reconciliation_provider_code,
       error_code, error_message
from chatapp_broadcasts
where id = 'c06366f2-f9e1-44f5-97a2-d78be833f3bc';

select r.status, r.failure_reason, r.provider_message_id, r.message_id,
       m.current_status, m.provider_message_id, m.conversation_id
from chatapp_broadcast_recipients r
left join messages m on m.id = r.message_id
where r.broadcast_id = 'c06366f2-f9e1-44f5-97a2-d78be833f3bc'
order by r.created_at, r.id;
```

Expected：批次 `PARTIALLY_FAILED`、2/1/0；3 个 recipient 都有 messageId；2 条消息为 sent/delivered/read，1 条 failed 且保存自身账号失败原因；Provider message ID 无重复。

若 Provider 仍返回不可匹配结果，Expected 改为结构化 `STATUS_UNKNOWN`：evidence 表有 envelope/row 证据、错误包含 RequestId，且绝不手工改成成功。

- [ ] **Step 6：回写文档状态**

`2026-08-13` 设计增加指向本设计的补充说明；本设计状态改为“已实施”或“已实施，实库恢复受阻”，并记录实际命令、测试数量、浏览器视口和存量批次结果。计划勾选实际完成项，不填写未执行证据。

- [ ] **Step 7：Git 边界和最终提交**

```bash
git status --short
git diff --cached --name-status
git diff --check
```

Tasks 1-7 已各自提交代码，因此这里只 stage 下列三份文档；确认没有其他工作区改动进入 staged 后提交：

```bash
git add docs/superpowers/specs/2026-08-13-chatapp-contacts-template-creation-mass-messaging-design.md
git add docs/superpowers/specs/2026-08-17-chatapp-broadcast-conversation-projection-design.md
git add docs/superpowers/plans/2026-08-17-chatapp-broadcast-conversation-projection.md
git commit -m "docs: record ChatApp broadcast projection verification"
```

- [ ] **Step 8：请求最终代码审查**

使用 `requesting-code-review`，审查重点固定为：Provider 调用是否可能重复发送、recipient/message 是否一对一、部分结果是否被保留、普通 polling 是否可能重复导入、真实错误是否泄露敏感信息、重新对账是否有界。
