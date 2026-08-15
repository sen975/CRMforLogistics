# ChatApp 会话身份对账修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 以 CAMS `UserNumber` 为唯一对端真源，修复当前 132/134 混合会话，并让后续消息同步自动纠正错误会话身份。

**架构：** 新增 `ChatAppMessagePeerReconciliationService` 作为消息与会话身份对账 owner。CAMS adapter 和 polling projector 只负责协议映射；owner 负责账号范围校验、身份查找/创建、事务性移动消息、会话摘要重算和有界结果。管理员历史对账入口复用同一 owner，不引入旧 JSONL 运行时依赖。

**技术栈：** Java 17、Spring Boot 3.4.5、MyBatis-Plus、PostgreSQL、Flyway、JUnit 5、Mockito、Testcontainers。

## 全局约束

- CAMS `UserNumber` 是 ChatApp 对端号码唯一真源；`BusinessNumber` 只有在 `UserNumber` 相同的自发自收记录中才是联系人身份。
- 不删除消息、附件、状态事件或历史联系人；无法从 provider 证明收件人的记录保持原状并进入未决结果。
- 每次历史对账最多 50 页、每页 100 条；同一账号同一时刻只能有一个对账执行实例。
- 不引入旧 demo 的 JSONL 文件作为 Spring 运行时输入。
- 所有 SQL 和测试必须按账号作用域过滤，禁止跨 ChatApp 账号复用身份。

---

### 任务 1：写入身份归属失败测试

**文件：**
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessagePeerReconciliationServiceTest.java`

**接口：**
- 消费：`ListChatappMessageResponseBody.Data`、现有消息 ID、CAMS `UserNumber`。
- 产生：`PeerReconciliationResult`，包含 `UNCHANGED`、`MOVED`、`UNRESOLVED` 三种结果及结构化原因。

- [ ] **步骤 1：先写失败测试**

```java
@Test
void outboundRowUsesUserNumberAsPeerEvenWhenBusinessNumberIsDifferent() {
    UUID accountId = UUID.randomUUID();
    var row = ListChatappMessageResponseBody.Data.builder()
            .messageId("provider-134")
            .messageSource("outbound")
            .businessNumber("8613266259485")
            .userNumber("8613428277520")
            .messageStatusName("DELIVERED")
            .build();

    assertThat(projector.project(row, accountId).contactProjected()).isTrue();
    ArgumentCaptor<ChannelEventEntity> event = ArgumentCaptor.forClass(ChannelEventEntity.class);
    verify(eventMapper).insertIgnore(event.capture());
    assertThat(event.getValue().getPayloadJsonb()).contains("\"To\":\"8613428277520\"");
}

@Test
void selfSendUsesBusinessNumberAsPeerOnlyWhenUserNumberMatchesIt() {
    UUID accountId = UUID.randomUUID();
    var command = new PeerReconciliationCommand(
            accountId, UUID.randomUUID(), "provider-self", "8613266259485");

    when(messageMapper.findByProviderMessageId(accountId, "provider-self"))
            .thenReturn(Optional.empty());
    when(identityMapper.findByNormalizedValueInScope(
            "chatapp", accountId.toString(), "8613266259485"))
            .thenReturn(Optional.empty());

    PeerReconciliationResult result = service.reconcile(command);

    assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.UNCHANGED);
    verify(identityMapper).insert(argThat(identity ->
            identity.getIdentityValue().equals("8613266259485")));
}

@Test
void mismatchedExistingMessageIsMovedWithoutChangingMessageId() {
    // Arrange a message in the 132 identity conversation and provider evidence for 134.
    // Assert updateConversationAndSequence receives the same message ID and target conversation.
}
```

- [ ] **步骤 2：运行失败测试**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ChatAppPollingProjectorTest,ChatAppMessagePeerReconciliationServiceTest test
```

预期：失败，原因是 `ChatAppMessagePeerReconciliationService`、`PeerReconciliationCommand` 和移动接口尚未存在。

- [ ] **步骤 3：确认失败属于缺失行为而非测试错误**

检查失败堆栈只包含缺失类型/方法或未满足断言，不修改测试来适配现有错误行为。

### 任务 2：增加消息移动与会话摘要 Mapper 合同

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ChatAppPeerReconciliationMapperSqlTest.java`

**接口：**
- `MessageMapper.updateConversationAndSequence(UUID messageId, UUID sourceConversationId, UUID targetConversationId, long targetSequence)`：带源会话条件更新一条消息。
- `MessageMapper.findMaxIngestSequence(UUID conversationId)`：读取目标序号上界。
- `ConversationMapper.recomputeProjection(UUID conversationId)`：按 `occurred_at desc, id desc` 更新 `last_message_id`、`last_message_at`、`updated_at`、`version`。

- [ ] **步骤 1：写 Mapper SQL 失败测试**

用 Testcontainers PostgreSQL 建立两个会话和一条消息，调用 Mapper，断言消息换会话后仍保留 provider ID、状态和消息 ID，两个会话摘要正确。

- [ ] **步骤 2：运行失败测试**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ChatAppPeerReconciliationMapperSqlTest test
```

预期：失败，原因是 Mapper 方法尚未定义。

- [ ] **步骤 3：实现带源条件的 SQL**

```java
@Update("update messages set conversation_id = #{targetConversationId}::uuid, "
        + "ingest_sequence = #{targetSequence} "
        + "where id = #{messageId}::uuid "
        + "and conversation_id = #{sourceConversationId}::uuid")
int updateConversationAndSequence(@Param("messageId") UUID messageId,
                                  @Param("sourceConversationId") UUID sourceConversationId,
                                  @Param("targetConversationId") UUID targetConversationId,
                                  @Param("targetSequence") long targetSequence);

@Select("select coalesce(max(ingest_sequence), 0) from messages "
        + "where conversation_id = #{conversationId}::uuid")
long findMaxIngestSequence(@Param("conversationId") UUID conversationId);
```

会话摘要 SQL 必须使用同一排序规则，并在同一事务中执行；不回退源会话 `next_ingest_sequence`。

- [ ] **步骤 4：运行 Mapper 测试并确认绿色**

运行同一 `mvn -Dtest=ChatAppPeerReconciliationMapperSqlTest test`，预期通过；若 Docker 不可用，记录环境阻断并用 SQL 合同测试替代，不能伪装为通过。

### 任务 3：实现对账 owner

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessagePeerReconciliationService.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/PeerReconciliationModels.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppMessagePeerReconciliationServiceTest.java`

**接口：**

```java
public record PeerReconciliationCommand(
        UUID channelAccountId,
        UUID messageId,
        String providerMessageId,
        String userNumber) {}

public record PeerReconciliationResult(
        Kind kind,
        UUID identityId,
        UUID conversationId,
        String reason) {
    public enum Kind { UNCHANGED, MOVED, UNRESOLVED }
}

@Transactional
public PeerReconciliationResult reconcile(PeerReconciliationCommand command)
```

- [ ] **步骤 1：实现号码归一化和作用域校验**

调用现有 `ContactPointUtil.normalizePhone`；空值或非法值返回 `UNRESOLVED`，不写联系人、不写事件。所有查询必须使用 `channelAccountId` 的 UUID 字符串作为 `identity_scope`。

- [ ] **步骤 2：实现目标身份和会话获取**

先调用 `findByNormalizedValueInScope("chatapp", accountId.toString(), normalized)`；不存在时创建 `ContactEntity` 和 `ContactIdentityEntity`，身份 `source=synced`、`identity_value=normalized`、`display_name=normalized`，再调用 `ConversationMapper.getOrCreateConversation`。

- [ ] **步骤 3：实现已有消息幂等移动**

读取消息所属会话并锁定源/目标会话，锁顺序按 UUID 排序。若当前身份已等于目标身份，返回 `UNCHANGED`；否则取目标最大序号加一，调用 `updateConversationAndSequence`，重算两个会话摘要并返回 `MOVED`。更新行数为 0 时重新读取消息，已被其他 worker 移动则按幂等成功返回。

- [ ] **步骤 4：运行 owner 测试**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ChatAppMessagePeerReconciliationServiceTest test
```

预期通过，覆盖 134 外发、132 自发自收、错误会话移动、重复对账和非法号码。

### 任务 4：接入 polling projector 的在线自愈

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjector.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppPollingProjectorTest.java`

- [ ] **步骤 1：在已有本地消息路径调用 owner**

当 `local.isPresent()` 且 `row.getUserNumber()` 合法时，先调用
`peerReconciliationService.reconcile(new PeerReconciliationCommand(channelAccountId, local.get().getId(), providerMessageId, row.getUserNumber()))`，再构造状态事件。消息不存在时保持现有 webhook 投影路径，由 `To=userNumber` 创建正确身份。

- [ ] **步骤 2：添加回归断言**

验证已有错误会话在状态同步后先完成移动，`webhookProjector.project` 仍被调用一次，状态事件不会创建第二条消息。

- [ ] **步骤 3：运行 polling 回归测试**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ChatAppPollingProjectorTest,ChatAppMessageSyncServiceTest test
```

### 任务 5：增加有界管理员历史对账入口

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppHistoryReconciliationResult.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppHistoryReconciliationControllerTest.java`

- [ ] **步骤 1：增加显式时间范围的同步方法**

新增 `runAccount(UUID accountId, Instant startTime, Instant endTime, int maxPages)`，校验 `endTime > startTime`、时间范围不超过 90 天、`maxPages` 在 1 到 50 之间。现有定时 `runAccount(UUID)` 继续使用配置的 30 天窗口。

- [ ] **步骤 2：增加管理员 POST 接口**

在 `/api/chatapp/sync/messages/reconcile` 接收 `accountId`、`startTime`、`endTime`，只允许管理员，返回扫描、未变、移动、未决和失败计数。禁止前端普通联系人页面调用。

- [ ] **步骤 3：先做 dry-run 再执行当前修复**

先调用 dry-run/只读统计命令确认当前账号受影响 provider ID 数量；执行时使用 CAMS 返回的 `UserNumber`，不读取旧 JSONL。无法返回的 12 条消息进入 `unresolved`，不移动。

- [ ] **步骤 4：运行 Controller 权限测试**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=ChatAppHistoryReconciliationControllerTest test
```

验证普通用户返回 403、管理员范围校验通过、超过 90 天或 50 页返回 400。

### 任务 6：当前数据库实测与文档回写

**文件：**
- 修改：`docs/superpowers/specs/2026-08-15-chatapp-conversation-identity-reconciliation-design.md`

- [ ] **步骤 1：备份受影响行的只读快照**

使用 `pg_dump --data-only` 或等价 SQL 输出两个联系人、两个会话及相关消息/附件/状态事件的快照，保存到受保护的临时目录，不纳入 Git。

- [ ] **步骤 2：执行管理员历史对账**

时间范围覆盖 2026-07-01 至当前时间，最多 50 页；记录原始结果 JSON 和命令输出。

- [ ] **步骤 3：验证数据守恒**

核对两个身份、会话消息总数、provider ID 集合、附件数量、状态事件数量、最后消息和联系人名称。总消息数必须守恒；未决消息必须列出 provider ID。

- [ ] **步骤 4：运行完整后端回归**

```bash
cd demo/message-center-spring/backend
mvn test
```

Docker/Testcontainers 不可用时，报告具体失败测试和环境原因，不宣称完整通过。

- [ ] **步骤 5：更新设计文档验收结果并复核 Git 边界**

只 stage 本任务文件和必要源码/测试；不得使用 `git add .`，不得覆盖其他线程改动。
