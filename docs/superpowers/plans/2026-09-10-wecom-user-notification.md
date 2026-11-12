# 非企业微信渠道新消息的企业微信应用消息提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当 ChatApp/WhatsApp 或邮件有入站消息落库时，通过企业微信应用消息提醒该会话的负责人，同一会话的短期消息聚合成一条。

**Architecture:** 入站消息落库的同一个事务内，把一条待发提醒 upsert 进新表 `wecom_user_notifications`（部分唯一索引保证同会话同收件人只有一行 PENDING），聚合窗口 `send_after` 内只累加计数不延期。一个 `@Scheduled` scheduler 配套 worker 扫描到期行，claim 后调用已有的 `WeComSendService.send(...)`。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis-Plus（注解式 SQL）、PostgreSQL + Flyway、JUnit 5 + AssertJ + Mockito、Maven。

## Global Constraints

- 参考 spec：`docs/superpowers/specs/2026-09-10-wecom-user-notification-design.md`（当前真源，冲突以 spec 为准）。
- 后端根目录：`demo/message-center-spring/backend`。所有 `mvn` 命令都在该目录下执行。
- 新迁移是 `V67__wecom_user_notifications.sql`（当前最高为 V66）。
- 通知对象**只取** `conversations.assigned_user_id`；为空或未绑定企业微信时**不写任何行**，只记 debug 日志。
- 只有 `direction='inbound'` 且 `counts_as_unread=true` 且渠道属于 `chatapp`/`whatsapp`/`email` 的消息才触发。
- 企业微信自有入站（chatdata 投影）**不触发**。
- 未绑定判断必须用 `WeComUserBindingMapper.findByUserId`（返回 `Optional`），**禁止**用会抛 `WECOM_USER_NOT_BOUND` 的 `WeComUserBindingService.requireByUserId`。
- 入队与解析的异常必须被吞掉并记日志，绝不能影响入站消息落库事务。
- 配置默认关闭：`app.wecom-user-notification-enabled` 默认 `false`。
- 测试风格沿用仓库现状：mapper 用反射断言注解 SQL 的契约测试（不连库），service/worker 用 Mockito（不起 Spring 容器）。
- 提交信息用英文，前缀 `feat:` / `test:` / `docs:`，与仓库现有提交一致。

---

### Task 1: 迁移 V67 建表

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V67__wecom_user_notifications.sql`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WeComUserNotificationMigrationContractTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 表 `wecom_user_notifications`，字段 `id, conversation_id, channel_account_id, recipient_user_id, recipient_wecom_user_id, auth_corp_id, agent_id, channel_type, contact_label, message_count, last_preview, first_message_at, send_after, status, attempt_count, last_error, sent_at, created_at, updated_at, version`

- [ ] **Step 1: 写失败的契约测试**

创建 `src/test/java/com/crmforlogistics/messagecenter/WeComUserNotificationMigrationContractTest.java`：

```java
package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationMigrationContractTest {

    @Test
    void v67DefinesPendingNotificationTableWithAggregationUniqueness() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V67__wecom_user_notifications.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();

            assertThat(sql).contains("create table wecom_user_notifications");
            assertThat(sql).contains("agent_id");
            assertThat(sql).contains("send_after");
            assertThat(sql).contains("message_count integer not null default 1");
            assertThat(sql).contains("on wecom_user_notifications (conversation_id, recipient_user_id)");
            assertThat(sql).contains("where status = 'pending'");
            assertThat(sql).contains(
                    "check (status in ('pending', 'sending', 'sent', 'failed'))");
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationMigrationContractTest
```

Expected: FAIL — `assertThat(stream).isNotNull()` 失败，因为迁移文件还不存在。

- [ ] **Step 3: 写迁移文件**

创建 `src/main/resources/db/migration/V67__wecom_user_notifications.sql`：

```sql
CREATE TABLE wecom_user_notifications (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    recipient_user_id uuid NOT NULL,
    recipient_wecom_user_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    agent_id varchar(64) NOT NULL,
    channel_type varchar(32) NOT NULL,
    contact_label varchar(200) NOT NULL,
    message_count integer NOT NULL DEFAULT 1,
    last_preview varchar(200),
    first_message_at timestamptz NOT NULL,
    send_after timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error varchar(500),
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

ALTER TABLE wecom_user_notifications
    ADD CONSTRAINT ck_wecom_user_notification_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'));

-- 同一会话同一收件人在待发状态下只能有一行，聚合 upsert 依赖这个索引。
CREATE UNIQUE INDEX ux_wecom_user_notification_pending
    ON wecom_user_notifications (conversation_id, recipient_user_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_wecom_user_notification_due
    ON wecom_user_notifications (send_after)
    WHERE status = 'PENDING';
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationMigrationContractTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V67__wecom_user_notifications.sql \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/WeComUserNotificationMigrationContractTest.java
git commit -m "feat: add wecom_user_notifications schema"
```

---

### Task 2: 实体与 Mapper

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComUserNotificationEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapperSqlTest.java`

**Interfaces:**
- Consumes: Task 1 的表
- Produces:
  - `WeComUserNotificationEntity`（含全部字段的 getter/setter）
  - `int WeComUserNotificationMapper.upsertPending(WeComUserNotificationEntity entity)`
  - `List<WeComUserNotificationEntity> WeComUserNotificationMapper.listDue(Instant now, int batchSize)`
  - `int claim(UUID id)` / `int markSent(UUID id, Instant sentAt)` / `int retryLater(UUID id, Instant sendAfter, String lastError)` / `int markFailed(UUID id, String lastError)`
  - `List<UUID> recoverStuck(Instant staleBefore)`

- [ ] **Step 1: 写失败的契约测试**

创建 `src/test/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapperSqlTest.java`：

```java
package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationMapperSqlTest {

    @Test
    void upsertMergesIntoThePendingRowWithoutExtendingTheWindow() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "upsertPending", WeComUserNotificationEntity.class);

        String sql = method.getAnnotation(Insert.class).value()[0];

        assertThat(sql).contains(
                "on conflict (conversation_id, recipient_user_id) where status = 'PENDING'");
        assertThat(sql).contains(
                "message_count = wecom_user_notifications.message_count + 1");
        assertThat(sql).doesNotContain("send_after = excluded.send_after");
        assertThat(sql).doesNotContain("first_message_at = excluded.first_message_at");
        assertThat(sql).doesNotContain("recipient_wecom_user_id = excluded.recipient_wecom_user_id");
    }

    @Test
    void dueScanOnlyLooksAtPendingRowsWhoseSendAfterHasPassed() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "listDue", Instant.class, int.class);

        String sql = method.getAnnotation(Select.class).value()[0];

        assertThat(sql).contains("status = 'PENDING' and send_after <= #{now}");
        assertThat(sql).contains("order by send_after");
    }

    @Test
    void claimOnlyTransitionsFromPending() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod("claim", UUID.class);

        String sql = method.getAnnotation(Update.class).value()[0];

        assertThat(sql).contains("status = 'SENDING'");
        assertThat(sql).contains("and status = 'PENDING'");
    }

    @Test
    void terminalTransitionsOnlyApplyToClaimedRows() throws Exception {
        Method sent = WeComUserNotificationMapper.class.getMethod(
                "markSent", UUID.class, Instant.class);
        assertThat(sent.getAnnotation(Update.class).value()[0])
                .contains("status = 'SENT'")
                .contains("and status = 'SENDING'");

        Method failed = WeComUserNotificationMapper.class.getMethod(
                "markFailed", UUID.class, String.class);
        assertThat(failed.getAnnotation(Update.class).value()[0])
                .contains("status = 'FAILED'")
                .contains("and status = 'SENDING'");

        Method retry = WeComUserNotificationMapper.class.getMethod(
                "retryLater", UUID.class, Instant.class, String.class);
        String retrySql = retry.getAnnotation(Update.class).value()[0];
        assertThat(retrySql).contains("status = 'PENDING'");
        assertThat(retrySql).contains("send_after = #{sendAfter}");
        assertThat(retrySql).contains("and status = 'SENDING'");
    }

    @Test
    void recoverStuckReturnsLeakedSendingRowsToPending() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "recoverStuck", Instant.class);

        String sql = method.getAnnotation(Select.class).value()[0];

        assertThat(sql).contains("status = 'SENDING' and updated_at < #{staleBefore}");
        assertThat(sql).contains("for update skip locked");
        assertThat(sql).contains("set status = 'PENDING'");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationMapperSqlTest
```

Expected: 编译失败 — `WeComUserNotificationMapper` / `WeComUserNotificationEntity` 尚不存在。

- [ ] **Step 3: 写实体**

创建 `src/main/java/com/crmforlogistics/messagecenter/entity/WeComUserNotificationEntity.java`：

```java
package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("wecom_user_notifications")
public class WeComUserNotificationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID conversationId;
    private UUID channelAccountId;
    private UUID recipientUserId;
    private String recipientWecomUserId;
    private String authCorpId;
    private String agentId;
    private String channelType;
    private String contactLabel;
    private Integer messageCount;
    private String lastPreview;
    private Instant firstMessageAt;
    private Instant sendAfter;
    private String status;
    private Integer attemptCount;
    private String lastError;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(UUID recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getRecipientWecomUserId() { return recipientWecomUserId; }
    public void setRecipientWecomUserId(String recipientWecomUserId) { this.recipientWecomUserId = recipientWecomUserId; }
    public String getAuthCorpId() { return authCorpId; }
    public void setAuthCorpId(String authCorpId) { this.authCorpId = authCorpId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getContactLabel() { return contactLabel; }
    public void setContactLabel(String contactLabel) { this.contactLabel = contactLabel; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getLastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
    public Instant getFirstMessageAt() { return firstMessageAt; }
    public void setFirstMessageAt(Instant firstMessageAt) { this.firstMessageAt = firstMessageAt; }
    public Instant getSendAfter() { return sendAfter; }
    public void setSendAfter(Instant sendAfter) { this.sendAfter = sendAfter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
```

- [ ] **Step 4: 写 Mapper**

创建 `src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapper.java`：

```java
package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface WeComUserNotificationMapper extends BaseMapper<WeComUserNotificationEntity> {

    @Insert("insert into wecom_user_notifications (id, conversation_id, channel_account_id, "
            + "recipient_user_id, recipient_wecom_user_id, auth_corp_id, agent_id, channel_type, "
            + "contact_label, message_count, last_preview, first_message_at, send_after, status) "
            + "values (#{id}::uuid, #{conversationId}::uuid, #{channelAccountId}::uuid, "
            + "#{recipientUserId}::uuid, #{recipientWecomUserId}, #{authCorpId}, #{agentId}, "
            + "#{channelType}, #{contactLabel}, #{messageCount}, #{lastPreview}, #{firstMessageAt}, "
            + "#{sendAfter}, 'PENDING') "
            + "on conflict (conversation_id, recipient_user_id) where status = 'PENDING' "
            + "do update set message_count = wecom_user_notifications.message_count + 1, "
            + "last_preview = excluded.last_preview, updated_at = now()")
    int upsertPending(WeComUserNotificationEntity entity);

    @Select("select * from wecom_user_notifications "
            + "where status = 'PENDING' and send_after <= #{now} "
            + "order by send_after, created_at limit #{batchSize}")
    List<WeComUserNotificationEntity> listDue(@Param("now") Instant now,
                                              @Param("batchSize") int batchSize);

    @Update("update wecom_user_notifications set status = 'SENDING', version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'PENDING'")
    int claim(@Param("id") UUID id);

    @Update("update wecom_user_notifications set status = 'SENT', sent_at = #{sentAt}, "
            + "attempt_count = attempt_count + 1, last_error = null, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int markSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);

    @Update("update wecom_user_notifications set status = 'PENDING', send_after = #{sendAfter}, "
            + "attempt_count = attempt_count + 1, last_error = #{lastError}, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int retryLater(@Param("id") UUID id, @Param("sendAfter") Instant sendAfter,
                   @Param("lastError") String lastError);

    @Update("update wecom_user_notifications set status = 'FAILED', "
            + "attempt_count = attempt_count + 1, last_error = #{lastError}, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int markFailed(@Param("id") UUID id, @Param("lastError") String lastError);

    @Select("with stuck as ("
            + "select id from wecom_user_notifications "
            + "where status = 'SENDING' and updated_at < #{staleBefore} "
            + "for update skip locked"
            + ") update wecom_user_notifications n set status = 'PENDING', updated_at = now(), "
            + "version = version + 1 from stuck where n.id = stuck.id returning n.id")
    List<UUID> recoverStuck(@Param("staleBefore") Instant staleBefore);
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationMapperSqlTest
```

Expected: PASS（5 个测试全绿）。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComUserNotificationEntity.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapper.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WeComUserNotificationMapperSqlTest.java
git commit -m "feat: add wecom user notification entity and mapper"
```

---

### Task 3: 提醒文案渲染

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationText.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationTextTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `static String WeComUserNotificationText.channelLabel(String channelType)`
  - `static String WeComUserNotificationText.truncatePreview(String bodyText, String subject)`
  - `static String WeComUserNotificationText.render(String channelType, String contactLabel, int messageCount, String preview)`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationTextTest.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationTextTest {

    @Test
    void singleMessageRendersContactAndPreview() {
        String text = WeComUserNotificationText.render("chatapp", "张三", 1, "你好，想问下运费");

        assertThat(text).isEqualTo("【WhatsApp】张三：你好，想问下运费");
    }

    @Test
    void aggregateRendersCountAndLatestPreview() {
        String text = WeComUserNotificationText.render("whatsapp", "张三", 3, "你好，想问下运费");

        assertThat(text).isEqualTo("【WhatsApp】张三 给你发了 3 条消息，最近一条：你好，想问下运费");
    }

    @Test
    void aggregateWithoutPreviewOmitsTheDanglingLabel() {
        String text = WeComUserNotificationText.render("email", "a@b.com", 2, "");

        assertThat(text).isEqualTo("【邮件】a@b.com 给你发了 2 条消息");
    }

    @Test
    void blankContactLabelFallsBackToPlaceholder() {
        String text = WeComUserNotificationText.render("email", "  ", 1, "hi");

        assertThat(text).isEqualTo("【邮件】未知联系人：hi");
    }

    @Test
    void previewCollapsesWhitespaceAndTruncates() {
        String body = "第一行\n第二行\t第三行 " + "x".repeat(80);

        String preview = WeComUserNotificationText.truncatePreview(body, "主题");

        assertThat(preview).doesNotContain("\n").doesNotContain("\t");
        assertThat(preview).hasSize(61);
        assertThat(preview).endsWith("…");
    }

    @Test
    void previewFallsBackToSubjectWhenBodyIsBlank() {
        assertThat(WeComUserNotificationText.truncatePreview("   ", "运费问题"))
                .isEqualTo("运费问题");
    }

    @Test
    void channelLabelsCoverTheSupportedChannels() {
        assertThat(WeComUserNotificationText.channelLabel("chatapp")).isEqualTo("WhatsApp");
        assertThat(WeComUserNotificationText.channelLabel("whatsapp")).isEqualTo("WhatsApp");
        assertThat(WeComUserNotificationText.channelLabel("email")).isEqualTo("邮件");
        assertThat(WeComUserNotificationText.channelLabel(null)).isEqualTo("消息");
        assertThat(WeComUserNotificationText.channelLabel("unknown")).isEqualTo("消息");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationTextTest
```

Expected: 编译失败 — `WeComUserNotificationText` 尚不存在。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationText.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import java.util.Locale;
import java.util.Map;

public final class WeComUserNotificationText {

    static final int PREVIEW_LIMIT = 60;

    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "chatapp", "WhatsApp",
            "whatsapp", "WhatsApp",
            "email", "邮件");

    private WeComUserNotificationText() {
    }

    public static String channelLabel(String channelType) {
        if (channelType == null || channelType.isBlank()) {
            return "消息";
        }
        return CHANNEL_LABELS.getOrDefault(channelType.toLowerCase(Locale.ROOT), "消息");
    }

    public static String truncatePreview(String bodyText, String subject) {
        String source = bodyText == null || bodyText.isBlank() ? subject : bodyText;
        if (source == null || source.isBlank()) {
            return "";
        }
        String flattened = source.replaceAll("\\s+", " ").trim();
        if (flattened.length() <= PREVIEW_LIMIT) {
            return flattened;
        }
        return flattened.substring(0, PREVIEW_LIMIT) + "…";
    }

    public static String render(String channelType, String contactLabel, int messageCount,
                                String preview) {
        String channel = channelLabel(channelType);
        String contact = contactLabel == null || contactLabel.isBlank()
                ? "未知联系人" : contactLabel.trim();
        boolean hasPreview = preview != null && !preview.isBlank();
        if (messageCount <= 1) {
            return "【" + channel + "】" + contact + (hasPreview ? "：" + preview : "");
        }
        return "【" + channel + "】" + contact + " 给你发了 " + messageCount + " 条消息"
                + (hasPreview ? "，最近一条：" + preview : "");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationTextTest
```

Expected: PASS（7 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationText.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationTextTest.java
git commit -m "feat: render wecom user notification text"
```

---

### Task 4: 配置项与入队服务

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`（在 `chatappWebhookMaxSkewSeconds` 之后追加 4 个字段）
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`（在 `wecom-message-summary-backfill-batch-size` 之后插入）
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/AppConfigTest.java`（追加一个测试方法）

**Interfaces:**
- Consumes: Task 2 的 `WeComUserNotificationMapper.upsertPending`、Task 3 的 `WeComUserNotificationText.truncatePreview`
- Produces:
  - `AppConfig.wecomUserNotificationEnabled() : boolean`、`wecomUserNotificationWindowMs() : long`、`wecomUserNotificationWorkerIntervalMs() : long`、`wecomUserNotificationWorkerInitialDelayMs() : long`
  - `void WeComUserNotificationService.enqueueInbound(WeComUserNotificationService.InboundMessage message)`
  - `record InboundMessage(UUID conversationId, UUID channelAccountId, String channelType, String contactLabel, String subject, String bodyText, Instant occurredAt)`

- [ ] **Step 1: 给 AppConfigTest 追加失败的配置测试**

在 `src/test/java/com/crmforlogistics/messagecenter/config/AppConfigTest.java` 的最后一个 `}` 之前插入：

```java
    @Test
    void weComUserNotificationIsOffByDefaultWithBoundedWindow() {
        context.run(application -> {
            AppConfig config = application.getBean(AppConfig.class);

            assertThat(config.wecomUserNotificationEnabled()).isFalse();
            assertThat(config.wecomUserNotificationWindowMs()).isEqualTo(90_000L);
            assertThat(config.wecomUserNotificationWorkerIntervalMs()).isEqualTo(1_000L);
            assertThat(config.wecomUserNotificationWorkerInitialDelayMs()).isEqualTo(1_000L);
        });
    }

    @Test
    void bindsExplicitWeComUserNotificationSettings() {
        context.withPropertyValues(
                        "app.wecom-user-notification-enabled=true",
                        "app.wecom-user-notification-window-ms=30000")
                .run(application -> {
                    AppConfig config = application.getBean(AppConfig.class);

                    assertThat(config.wecomUserNotificationEnabled()).isTrue();
                    assertThat(config.wecomUserNotificationWindowMs()).isEqualTo(30_000L);
                });
    }
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=AppConfigTest
```

Expected: 编译失败 — `AppConfig` 还没有这些访问器。

- [ ] **Step 3: 给 AppConfig 追加字段**

修改 `src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`，把 `chatappWebhookMaxSkewSeconds` 那一行的末尾逗号保留，并在其后追加四行，使其成为：

```java
        @DefaultValue("") String chatappWebhookSecret,
        @DefaultValue("300") int chatappWebhookMaxSkewSeconds,
        // 非企业微信渠道入站消息的企微应用消息提醒
        @DefaultValue("false") boolean wecomUserNotificationEnabled,
        @DefaultValue("90000") long wecomUserNotificationWindowMs,
        @DefaultValue("1000") long wecomUserNotificationWorkerIntervalMs,
        @DefaultValue("1000") long wecomUserNotificationWorkerInitialDelayMs
) {
```

（record 组件顺序即构造参数顺序；仓库里没有任何 `new AppConfig(...)` 位置构造，因此追加到末尾是安全的。）

- [ ] **Step 4: 运行配置测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=AppConfigTest
```

Expected: PASS。

- [ ] **Step 5: 写失败的入队服务测试**

创建 `src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationServiceTest.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComUserNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final UUID CONVERSATION = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ASSIGNEE = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private final WeComUserNotificationMapper notifications = mock(WeComUserNotificationMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final WeComUserBindingMapper bindings = mock(WeComUserBindingMapper.class);
    private final WeComInstallationService installations = mock(WeComInstallationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void enqueuesAPendingRowForTheBoundAssignee() {
        AppConfig config = config(true, 90_000L);
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.of(binding("zhangsan")));
        when(installations.find("suite-1", "corp-1")).thenReturn(installation("1000002"));
        WeComUserNotificationService service =
                new WeComUserNotificationService(config, notifications, conversations, bindings,
                        installations, clock);

        service.enqueueInbound(inbound("张三", "运费问题", "你好，想问下运费"));

        ArgumentCaptor<WeComUserNotificationEntity> captor =
                ArgumentCaptor.forClass(WeComUserNotificationEntity.class);
        verify(notifications).upsertPending(captor.capture());
        WeComUserNotificationEntity row = captor.getValue();
        assertThat(row.getConversationId()).isEqualTo(CONVERSATION);
        assertThat(row.getChannelAccountId()).isEqualTo(ACCOUNT);
        assertThat(row.getRecipientUserId()).isEqualTo(ASSIGNEE);
        assertThat(row.getRecipientWecomUserId()).isEqualTo("zhangsan");
        assertThat(row.getAuthCorpId()).isEqualTo("corp-1");
        assertThat(row.getAgentId()).isEqualTo("1000002");
        assertThat(row.getChannelType()).isEqualTo("chatapp");
        assertThat(row.getContactLabel()).isEqualTo("张三");
        assertThat(row.getMessageCount()).isEqualTo(1);
        assertThat(row.getLastPreview()).isEqualTo("你好，想问下运费");
        assertThat(row.getFirstMessageAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(row.getSendAfter()).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void writesNothingWhenTheNotificationFeatureIsDisabled() {
        WeComUserNotificationService service = new WeComUserNotificationService(
                config(false, 90_000L), notifications, conversations, bindings, installations, clock);

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verifyNoInteractions(notifications, conversations, bindings, installations);
    }

    @Test
    void writesNothingWhenTheConversationHasNoAssignee() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(null));
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void writesNothingWhenTheAssigneeHasNoWeComBinding() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.empty());
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void writesNothingWhenTheInstallationCannotBeResolved() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.of(binding("zhangsan")));
        when(installations.find("suite-1", "corp-1")).thenReturn(null);
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void swallowsRepositoryFailuresSoInboundPersistenceIsNeverBroken() {
        when(conversations.selectById(CONVERSATION))
                .thenThrow(new IllegalStateException("db down"));
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    private WeComUserNotificationService service() {
        return new WeComUserNotificationService(
                config(true, 90_000L), notifications, conversations, bindings, installations, clock);
    }

    private static AppConfig config(boolean enabled, long windowMs) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomUserNotificationEnabled()).thenReturn(enabled);
        lenient().when(config.wecomUserNotificationWindowMs()).thenReturn(windowMs);
        return config;
    }

    private static WeComUserNotificationService.InboundMessage inbound(
            String contactLabel, String subject, String body) {
        return new WeComUserNotificationService.InboundMessage(
                CONVERSATION, ACCOUNT, "chatapp", contactLabel, subject, body, NOW.minusSeconds(5));
    }

    private static ConversationEntity conversation(UUID assignee) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(CONVERSATION);
        conversation.setAssignedUserId(assignee);
        return conversation;
    }

    private static WeComUserBindingEntity binding(String wecomUserId) {
        WeComUserBindingEntity binding = new WeComUserBindingEntity();
        binding.setUserId(ASSIGNEE);
        binding.setSuiteId("suite-1");
        binding.setAuthCorpId("corp-1");
        binding.setWecomUserId(wecomUserId);
        return binding;
    }

    private static WeComInstallationEntity installation(String agentId) {
        WeComInstallationEntity installation = new WeComInstallationEntity();
        installation.setAgentId(agentId);
        return installation;
    }
}
```

**注意：** `AppConfig` 是 record，用 `mock(AppConfig.class)` + `when(...)` 构造是仓库既有写法（见 `WeComMessageSummaryControllerTest`、`WeComViewerControllerTest`），不要手写全参数构造器。测试类需要 `@ExtendWith(MockitoExtension.class)`，或者按本文件写法直接用 `mock(...)` 静态方法 + 在方法内 stub。

`windowMs` 的 stub 用 `lenient()`：在"功能关闭"的用例里服务会提前返回，永远不会读 `windowMs`，严格模式下会被判为多余 stub。

需要在 import 区补 `import static org.mockito.Mockito.lenient;`（`mock` / `when` 已在上面 import）。

- [ ] **Step 6: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationServiceTest
```

Expected: 编译失败 — `WeComUserNotificationService` 尚不存在。

- [ ] **Step 7: 写入队服务**

创建 `src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationService.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 把一条非企业微信渠道的入站消息折算成一条待发的企微应用消息提醒。
 * 解析失败一律吞掉：这个调用点位于入站消息落库事务内，不能反过来影响消息入库。
 */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComUserNotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(WeComUserNotificationService.class);

    private final AppConfig config;
    private final WeComUserNotificationMapper notificationMapper;
    private final ConversationMapper conversationMapper;
    private final WeComUserBindingMapper bindingMapper;
    private final WeComInstallationService installationService;
    private final Clock clock;

    public WeComUserNotificationService(AppConfig config,
                                        WeComUserNotificationMapper notificationMapper,
                                        ConversationMapper conversationMapper,
                                        WeComUserBindingMapper bindingMapper,
                                        WeComInstallationService installationService,
                                        Clock clock) {
        this.config = config;
        this.notificationMapper = notificationMapper;
        this.conversationMapper = conversationMapper;
        this.bindingMapper = bindingMapper;
        this.installationService = installationService;
        this.clock = clock;
    }

    public void enqueueInbound(InboundMessage message) {
        if (!config.wecomUserNotificationEnabled()) {
            return;
        }
        try {
            WeComUserNotificationEntity row = buildRow(message);
            if (row == null) {
                return;
            }
            notificationMapper.upsertPending(row);
        } catch (RuntimeException e) {
            LOG.warn("event=wecom.notification_enqueue_failed conversationId={} channelType={}",
                    message.conversationId(), message.channelType(), e);
        }
    }

    private WeComUserNotificationEntity buildRow(InboundMessage message) {
        ConversationEntity conversation = conversationMapper.selectById(message.conversationId());
        UUID assignee = conversation == null ? null : conversation.getAssignedUserId();
        if (assignee == null) {
            LOG.debug("event=wecom.notification_skipped reason=no_assignee conversationId={}",
                    message.conversationId());
            return null;
        }
        Optional<WeComUserBindingEntity> binding = bindingMapper.findByUserId(assignee);
        if (binding.isEmpty()) {
            LOG.debug("event=wecom.notification_skipped reason=assignee_unbound conversationId={} userId={}",
                    message.conversationId(), assignee);
            return null;
        }
        WeComUserBindingEntity bound = binding.get();
        WeComInstallationEntity installation =
                installationService.find(bound.getSuiteId(), bound.getAuthCorpId());
        if (installation == null || installation.getAgentId() == null
                || installation.getAgentId().isBlank()) {
            LOG.debug("event=wecom.notification_skipped reason=installation_unresolved conversationId={} authCorpId={}",
                    message.conversationId(), bound.getAuthCorpId());
            return null;
        }

        Instant now = clock.instant();
        WeComUserNotificationEntity row = new WeComUserNotificationEntity();
        row.setId(UUID.randomUUID());
        row.setConversationId(message.conversationId());
        row.setChannelAccountId(message.channelAccountId());
        row.setRecipientUserId(assignee);
        row.setRecipientWecomUserId(bound.getWecomUserId());
        row.setAuthCorpId(bound.getAuthCorpId());
        row.setAgentId(installation.getAgentId());
        row.setChannelType(message.channelType());
        row.setContactLabel(message.contactLabel() == null ? "" : message.contactLabel());
        row.setMessageCount(1);
        row.setLastPreview(
                WeComUserNotificationText.truncatePreview(message.bodyText(), message.subject()));
        row.setFirstMessageAt(message.occurredAt());
        row.setSendAfter(now.plusMillis(config.wecomUserNotificationWindowMs()));
        row.setStatus("PENDING");
        row.setAttemptCount(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setVersion(0L);
        return row;
    }

    public record InboundMessage(UUID conversationId, UUID channelAccountId, String channelType,
                                 String contactLabel, String subject, String bodyText,
                                 Instant occurredAt) {}
}
```

`send_after` 从 `clock.instant()` 起算而不是从消息时间起算：窗口的目的是约束「我们这边多久内一定发出去」，用处理时间才是对的；`first_message_at` 仍然记录真实消息时间，供排查用。

- [ ] **Step 8: 运行测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationServiceTest
```

Expected: PASS（6 个测试全绿）。

- [ ] **Step 9: 追加 application.yml 配置**

修改 `src/main/resources/application.yml`，在 `wecom-message-summary-backfill-batch-size` 行之后、`chatapp-outbox-enabled` 行之前插入：

```yaml
  # 非企业微信渠道入站消息的企微应用消息提醒，默认关闭。
  wecom-user-notification-enabled: ${WECOM_USER_NOTIFICATION_ENABLED:false}
  wecom-user-notification-window-ms: ${WECOM_USER_NOTIFICATION_WINDOW_MS:90000}
  wecom-user-notification-worker-interval-ms: ${WECOM_USER_NOTIFICATION_WORKER_INTERVAL_MS:1000}
  wecom-user-notification-worker-initial-delay-ms: ${WECOM_USER_NOTIFICATION_WORKER_INITIAL_DELAY_MS:1000}
  wecom-user-notification-max-attempts: ${WECOM_USER_NOTIFICATION_MAX_ATTEMPTS:3}
  wecom-user-notification-retry-backoff-seconds: ${WECOM_USER_NOTIFICATION_RETRY_BACKOFF_SECONDS:10}
```

**注意：** 最后两项由 `WeComUserNotificationWorker` 的 `@Value` 直接读取，**不**加进 `AppConfig`（与 `wecom-external-group-sync-poll-interval-seconds` 的处理方式一致），写进 yml 只是为了让运维可见。

- [ ] **Step 10: 跑一次 config 相关测试确认 yml 未破坏绑定**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest='AppConfigTest,EmailAttachmentConfigTest'
```

Expected: PASS。

- [ ] **Step 11: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java \
        demo/message-center-spring/backend/src/main/resources/application.yml \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationService.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationServiceTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/AppConfigTest.java
git commit -m "feat: enqueue wecom notifications for non-wecom inbound messages"
```

---

### Task 5: 发送 Worker 与 Scheduler

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorker.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationScheduler.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorkerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `WeComUserNotificationMapper`、Task 3 的 `WeComUserNotificationText.render`、Task 4 的 `AppConfig.wecomUserNotificationWindowMs()`
- Produces:
  - `void WeComUserNotificationWorker.runAvailable(String workerId, int batchSize)`
  - `int WeComUserNotificationWorker.recoverStuck()`

- [ ] **Step 1: 写失败的 worker 测试**

创建 `src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorkerTest.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComUserNotificationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final UUID ROW = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final WeComUserNotificationMapper notifications = mock(WeComUserNotificationMapper.class);
    private final WeComSendService sendService = mock(WeComSendService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sendsAggregatedTextThroughTheExistingWeComSendService() {
        WeComUserNotificationEntity row = row(3, "你好，想问下运费");
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new WeComSendService.SendResult("msg-1", "1000002", "zhangsan", "x", "sent"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(sendService).send("corp-1", "1000002", "zhangsan",
                "【WhatsApp】张三 给你发了 3 条消息，最近一条：你好，想问下运费");
        verify(notifications).markSent(ROW, NOW);
    }

    @Test
    void skipsARowAlreadyClaimedByAnotherWorker() {
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row(1, "hi")));
        when(notifications.claim(ROW)).thenReturn(0);
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(sendService, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(notifications, never()).markSent(any(), any());
    }

    @Test
    void retriesUntilTheAttemptBudgetIsSpent() {
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row(1, "hi")));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).retryLater(eq(ROW), eq(NOW.plus(Duration.ofSeconds(10))),
                anyString());
        verify(notifications, never()).markFailed(any(), any());
    }

    @Test
    void failsTheRowOnceTheAttemptBudgetIsExhausted() {
        WeComUserNotificationEntity row = row(1, "hi");
        row.setAttemptCount(2);
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).markFailed(ROW, "WECOM_SEND_FAILED");
        verify(notifications, never()).retryLater(any(), any(), anyString());
    }

    @Test
    void aFailureOnOneRowDoesNotStopTheRestOfTheBatch() {
        WeComUserNotificationEntity failing = row(1, "boom");
        WeComUserNotificationEntity ok = row(1, "fine");
        UUID okId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        ok.setId(okId);
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(failing, ok));
        when(notifications.claim(ROW)).thenReturn(1);
        when(notifications.claim(okId)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), eq("【WhatsApp】张三：boom")))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        when(sendService.send(anyString(), anyString(), anyString(), eq("【WhatsApp】张三：fine")))
                .thenReturn(new WeComSendService.SendResult("msg-2", "1000002", "zhangsan", "x", "sent"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).retryLater(eq(ROW), any(), anyString());
        verify(notifications).markSent(okId, NOW);
    }

    @Test
    void recoversRowsLeakedInSending() {
        when(notifications.recoverStuck(NOW.minus(Duration.ofSeconds(60))))
                .thenReturn(List.of(ROW));
        WeComUserNotificationWorker worker = worker();

        assertThat(worker.recoverStuck()).isEqualTo(1);
    }

    private WeComUserNotificationWorker worker() {
        // maxAttempts=3, retryBackoffSeconds=10
        return new WeComUserNotificationWorker(notifications, sendService, clock, 3, 10L);
    }

    private static WeComUserNotificationEntity row(int messageCount, String preview) {
        WeComUserNotificationEntity row = new WeComUserNotificationEntity();
        row.setId(ROW);
        row.setChannelType("chatapp");
        row.setContactLabel("张三");
        row.setMessageCount(messageCount);
        row.setLastPreview(preview);
        row.setAuthCorpId("corp-1");
        row.setAgentId("1000002");
        row.setRecipientWecomUserId("zhangsan");
        row.setAttemptCount(0);
        return row;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationWorkerTest
```

Expected: 编译失败 — `WeComUserNotificationWorker` 尚不存在。

- [ ] **Step 3: 写 worker**

创建 `src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorker.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.wecom-user-notification-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComUserNotificationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WeComUserNotificationWorker.class);
    private static final Duration STUCK_AFTER = Duration.ofSeconds(60);

    private final WeComUserNotificationMapper notificationMapper;
    private final WeComSendService sendService;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryBackoff;

    public WeComUserNotificationWorker(WeComUserNotificationMapper notificationMapper,
                                       WeComSendService sendService,
                                       Clock clock,
                                       @Value("${app.wecom-user-notification-max-attempts:3}")
                                       int maxAttempts,
                                       @Value("${app.wecom-user-notification-retry-backoff-seconds:10}")
                                       long retryBackoffSeconds) {
        this.notificationMapper = notificationMapper;
        this.sendService = sendService;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = Duration.ofSeconds(retryBackoffSeconds);
    }

    public int recoverStuck() {
        List<UUID> recovered = notificationMapper.recoverStuck(
                clock.instant().minus(STUCK_AFTER));
        if (!recovered.isEmpty()) {
            LOG.warn("event=wecom.notification_stuck_recovered count={}", recovered.size());
        }
        return recovered.size();
    }

    public void runAvailable(String workerId, int batchSize) {
        Instant now = clock.instant();
        for (WeComUserNotificationEntity row : notificationMapper.listDue(now, batchSize)) {
            if (notificationMapper.claim(row.getId()) != 1) {
                continue;
            }
            dispatch(row);
        }
    }

    private void dispatch(WeComUserNotificationEntity row) {
        String text = WeComUserNotificationText.render(
                row.getChannelType(), row.getContactLabel(), row.getMessageCount(),
                row.getLastPreview());
        try {
            sendService.send(row.getAuthCorpId(), row.getAgentId(),
                    row.getRecipientWecomUserId(), text);
            notificationMapper.markSent(row.getId(), clock.instant());
        } catch (WeComException e) {
            recordFailure(row, e.getCode());
        } catch (RuntimeException e) {
            recordFailure(row, "WECOM_NOTIFICATION_SEND_FAILED");
        }
    }

    private void recordFailure(WeComUserNotificationEntity row, String errorCode) {
        int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        if (attempts + 1 < maxAttempts) {
            notificationMapper.retryLater(row.getId(),
                    clock.instant().plus(retryBackoff), errorCode);
            LOG.warn("event=wecom.notification_retry notificationId={} attempt={} code={}",
                    row.getId(), attempts + 1, errorCode);
            return;
        }
        notificationMapper.markFailed(row.getId(), errorCode);
        LOG.error("event=wecom.notification_failed notificationId={} code={}",
                row.getId(), errorCode);
    }
}
```

**注意：** 只用这一个构造器。测试直接传 `maxAttempts=3, retryBackoffSeconds=10`，Spring 通过 `@Value` 注入同样的两个值（默认 `3` 和 `10` 秒）。不要为了测试再写一个重载构造器 —— 两个五参构造器会让 Spring 无法选择，且极易写出自递归的委托。

**注意：** `WeComException.getCode()` 必须存在。实现前先确认：

```bash
grep -n "getCode" demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComException.java
```

如果没有 `getCode()`，就把 `recordFailure(row, e.getCode())` 换成 `recordFailure(row, "WECOM_NOTIFICATION_SEND_FAILED")` 并在日志里带 `e`。

- [ ] **Step 4: 运行测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=WeComUserNotificationWorkerTest
```

Expected: PASS（6 个测试全绿）。

- [ ] **Step 5: 写 scheduler**

创建 `src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationScheduler.java`：

```java
package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.wecom-user-notification-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComUserNotificationScheduler {
    private static final int BATCH_SIZE = 20;

    private final WeComUserNotificationWorker worker;

    public WeComUserNotificationScheduler(WeComUserNotificationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${app.wecom-user-notification-worker-interval-ms:1000}",
            initialDelayString = "${app.wecom-user-notification-worker-initial-delay-ms:1000}")
    public void run() {
        worker.recoverStuck();
        worker.runAvailable("wecom-user-notification-" + UUID.randomUUID(), BATCH_SIZE);
    }
}
```

- [ ] **Step 6: 全量编译并跑相关测试**

```bash
cd demo/message-center-spring/backend
mvn -q -DskipTests compile
mvn -q test -Dtest='WeComUserNotification*,AppConfigTest'
```

Expected: 编译通过；列出的测试全绿。

- [ ] **Step 7: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorker.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationScheduler.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComUserNotificationWorkerTest.java
git commit -m "feat: send aggregated wecom notifications from a scheduled worker"
```

---

### Task 6: 接入两个入站投影

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java`（构造函数工厂 + 新增 1 个测试）
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java`（8 处构造函数调用 + 参数个数断言 + 新增 1 个测试）
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailOwnerIsolationTest.java`（1 处构造函数调用）

**Interfaces:**
- Consumes: Task 4 的 `WeComUserNotificationService.enqueueInbound(InboundMessage)` 与 `InboundMessage`
- Produces: 无新公开接口；两个投影在入站消息落库后触发入队

**为什么用 `ObjectProvider`：** `WeComUserNotificationService` 是条件 Bean（未启用企业微信时不存在），而 `ChatAppWebhookProjector` 和 `EmailSyncService` 是无条件 Bean。用 `ObjectProvider<T>.getIfAvailable()` 拿可空引用，与 `WeComViewerController` 的现有写法一致。

- [ ] **Step 1: 改 ChatAppWebhookProjectorTest 的 mock 与工厂（先让它能编译）**

修改 `src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java`：

在 import 区加入：

```java
import com.crmforlogistics.messagecenter.service.wecom.WeComUserNotificationService;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
```

在现有 `@Mock` 字段区末尾（`addressBookService` 之后）加入两个 mock：

```java
    @Mock ChannelAddressBookService addressBookService;
    @Mock ObjectProvider<WeComUserNotificationService> notificationProvider;
    @Mock WeComUserNotificationService notificationService;
```

`notificationProvider` 在没被 stub 的用例里，`getIfAvailable()` 返回 `null`，正好等价于"未启用企业微信"，因此现有用例全部不受影响。

把工厂方法 `projector()` 里的构造调用多传一个参数：

```java
        return new ChatAppWebhookProjector(
                channelEventMapper, messageMapper, statusEventMapper,
                conversationMapper, eventHub, new ObjectMapper(), broadcastRecipientMapper,
                broadcastMessageProjector, topicActivityRecorder, channelAccountMapper,
                addressBookService, notificationProvider);
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=ChatAppWebhookProjectorTest
```

Expected: 编译失败 — `ChatAppWebhookProjector` 的构造器还不接受第 12 个参数。

- [ ] **Step 3: 改 ChatAppWebhookProjector**

在 `src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java` 里：

加 import：

```java
import com.crmforlogistics.messagecenter.service.wecom.WeComUserNotificationService;
import org.springframework.beans.factory.ObjectProvider;
```

加字段（放在 `addressBookService` 之后）：

```java
    private final ObjectProvider<WeComUserNotificationService> notificationProvider;
```

构造器签名末尾加参数，并赋值（放在 `addressBookService` 赋值之后，**不加** `Objects.requireNonNull`）：

```java
                                   ChannelAddressBookService addressBookService,
                                   ObjectProvider<WeComUserNotificationService> notificationProvider) {
```

```java
        this.notificationProvider = notificationProvider;
```

在 `projectInbound` 里 `messageMapper.insertWithSequence(message);` 之后、`recordTopicActivity(conversation, occurredAt);` 之前插入：

```java
        enqueueNotification(account, conversation, resolved, message, displayName, from, occurredAt);
```

并新增方法：

```java
    private void enqueueNotification(ChannelAccountEntity account, ConversationEntity conversation,
                                     ChannelAddressBookService.ResolvedContact resolved,
                                     MessageEntity message, String displayName, String from,
                                     Instant occurredAt) {
        WeComUserNotificationService notifications = notificationProvider.getIfAvailable();
        if (notifications == null) {
            return;
        }
        notifications.enqueueInbound(new WeComUserNotificationService.InboundMessage(
                conversation.getId(), account.getId(), account.getChannelType(),
                displayName.isBlank() ? from : displayName,
                message.getSubject(), message.getBodyText(), occurredAt));
    }
```

**注意：** 这里的插入位置在 `projectInbound` 的入站分支内，该分支只处理入站消息，因此不需要再判断 `direction`。重复消息在更早处就已 `return`，也不会走到这里。

- [ ] **Step 4: 给 ChatAppWebhookProjectorTest 加入队断言测试**

在 `ChatAppWebhookProjectorTest` 里追加（stub 组合照抄同文件已有的
`inboundMessageRecordsTopicActivityAfterMessagePersistence`，只多两处：会话带负责人、provider 返回通知服务）：

```java
    @Test
    void inboundProjectionEnqueuesAWeComNotificationForTheAssignee() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setAssignedUserId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-9"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        when(notificationProvider.getIfAvailable()).thenReturn(notificationService);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-9\",\"From\":\"60123456789\","
                + "\"Message\":\"hello\",\"ContactName\":\"张三\"}");

        projector().project(event);

        ArgumentCaptor<WeComUserNotificationService.InboundMessage> captor =
                ArgumentCaptor.forClass(WeComUserNotificationService.InboundMessage.class);
        verify(notificationService).enqueueInbound(captor.capture());
        assertThat(captor.getValue().conversationId()).isEqualTo(conversation.getId());
        assertThat(captor.getValue().channelAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().channelType()).isEqualTo("chatapp");
        assertThat(captor.getValue().contactLabel()).isEqualTo("张三");
        assertThat(captor.getValue().bodyText()).isEqualTo("hello");
        assertThat(captor.getValue().occurredAt())
                .isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void inboundProjectionSucceedsWhenWeComNotificationsAreNotEnabled() {
        UUID accountId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(messageMapper.findByProviderMessageId(accountId, "inbound-10"))
                .thenReturn(Optional.empty());
        when(addressBookService.resolveOrCreateInbound(
                any(UUID.class), eq("chatapp"), eq(accountId), eq("60123456789"), any(String.class)))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(
                        UUID.randomUUID(), identity.getId(), false));
        when(conversationMapper.getOrCreateConversation(accountId, identity.getId()))
                .thenReturn(conversation);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setChannelAccountId(accountId);
        event.setOccurredAt(Instant.parse("2026-09-01T08:00:00Z"));
        event.setPayloadJsonb("{\"MessageId\":\"inbound-10\",\"From\":\"60123456789\","
                + "\"Message\":\"hello\"}");

        var result = projector().project(event);

        assertThat(result.type()).isEqualTo("message");
        verify(notificationService, never()).enqueueInbound(any());
        verify(messageMapper).insertWithSequence(any(MessageEntity.class));
    }
```

`projector()` 里 `notificationProvider` 未被 stub 时 `getIfAvailable()` 返回 `null`，第二个测试正是靠这一点验证"未启用企业微信时入站投影照常工作"。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest=ChatAppWebhookProjectorTest
```

Expected: PASS。

- [ ] **Step 6: 改 EmailSyncService 的构造器与调用点**

在 `src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java` 里：

加 import：

```java
import com.crmforlogistics.messagecenter.service.wecom.WeComUserNotificationService;
import org.springframework.beans.factory.ObjectProvider;
```

加字段：

```java
    private final ObjectProvider<WeComUserNotificationService> notificationProvider;
```

在 `@Autowired` 构造器签名末尾加 `ObjectProvider<WeComUserNotificationService> notificationProvider`，并在当前委托构造器（调用 `this(config, ..., topicActivityRecorder)` 的那两个）里追加对应实参：宽的那个传 `null`，窄的那个继续委托。

**具体做法：** 让窄构造器继续调用宽构造器，并在宽构造器末尾多传一个 `null`：

```java
    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper, ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper, ContactMapper contactMapper,
                            EventHub eventHub) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                contactMapper, eventHub, null, null, null, null);
    }

    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper, ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper, ContactMapper contactMapper,
                            EventHub eventHub, CredentialCipher credentialCipher,
                            EmailAttachmentStore attachmentStore) {
        this(config, messageMapper, conversationMapper, channelAccountMapper, contactIdentityMapper,
                contactMapper, eventHub, credentialCipher, attachmentStore, null, null);
    }

    @Autowired
    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            ContactMapper contactMapper,
                            EventHub eventHub,
                            CredentialCipher credentialCipher,
                            EmailAttachmentStore attachmentStore,
                            AiTopicActivityRecorder topicActivityRecorder,
                            ObjectProvider<WeComUserNotificationService> notificationProvider) {
        ...
        this.topicActivityRecorder = topicActivityRecorder;
        this.notificationProvider = notificationProvider;
    }
```

在 `appendReceived` 里 `messageMapper.insertWithSequence(entity);` 之后插入：

```java
            enqueueNotification(account, conversation, identity, entity, contactSource, contactEmail);
```

并新增方法：

```java
    private void enqueueNotification(ChannelAccountEntity account, ConversationEntity conversation,
                                     ContactIdentityEntity identity, MessageEntity entity,
                                     String contactSource, String contactEmail) {
        if (!"inbound".equals(entity.getDirection()) || notificationProvider == null) {
            return;
        }
        WeComUserNotificationService notifications = notificationProvider.getIfAvailable();
        if (notifications == null) {
            return;
        }
        String name = ContactPointUtil.extractName(contactSource, contactEmail);
        notifications.enqueueInbound(new WeComUserNotificationService.InboundMessage(
                conversation.getId(), account.getId(), account.getChannelType(),
                name.isBlank() ? contactEmail : name,
                entity.getSubject(), entity.getBodyText(), entity.getOccurredAt()));
    }
```

`ContactPointUtil` 已经在 `EmailSyncService` 里被使用（`extractEmail`），若 import 缺失则补上 `import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;`。

**注意两条路径的事务语义不同：** `ChatAppWebhookProjector.project` 有 `@Transactional`，`EmailSyncService.appendReceived` 没有。因此 ChatApp 路径的入队与消息入库同生共死；邮件路径的入队是"消息插入成功后的尽力而为"，入队异常已被 `enqueueInbound` 内部吞掉，不会影响已入库的邮件。不要为了对齐语义给 `appendReceived` 补 `@Transactional` —— 那会改变现有邮件同步的回滚行为，超出本功能范围。

- [ ] **Step 7: 修 EmailSyncServiceTest 与 EmailOwnerIsolationTest**

在 `src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java` 里：

把 8 处 `new EmailSyncService(...)` 调用全部补上末尾两个 `null`（或按当前参数个数补齐到 11 个）。例如第 81-82 行改为：

```java
        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper, eventHub,
                null, null, null, null);
```

并把 `springInjectionConstructorMustIncludeAttachmentStore` 里的断言从 10 改成 11，并补一个类型断言：

```java
        assertEquals(11, injectionConstructor.getParameterCount());
        assertEquals(CredentialCipher.class, injectionConstructor.getParameterTypes()[7]);
        assertEquals(EmailAttachmentStore.class, injectionConstructor.getParameterTypes()[8]);
        assertEquals(AiTopicActivityRecorder.class, injectionConstructor.getParameterTypes()[9]);
        assertTrue(ObjectProvider.class.isAssignableFrom(injectionConstructor.getParameterTypes()[10]));
```

（按需补 `import static org.junit.jupiter.api.Assertions.assertTrue;`。）

在 `src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailOwnerIsolationTest.java` 里，把第 124 行的 `new EmailSyncService(...)` 同样补上末尾两个 `null`。

**注意：** 不要用 `mock(ObjectProvider.class)` 之外的技巧；这两处只关心构造成功，传 `null` 即可，`enqueueNotification` 已经对 `notificationProvider == null` 做了保护。

- [ ] **Step 8: 跑测试确认通过**

```bash
cd demo/message-center-spring/backend
mvn -q test -Dtest='EmailSyncServiceTest,EmailOwnerIsolationTest,EmailControllerTest,EmailSyncSchedulerOwnerTest,ChatAppWebhookProjectorTest,ChatAppPollingProjectorTest'
```

Expected: PASS。

- [ ] **Step 9: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjectorTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncServiceTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailOwnerIsolationTest.java
git commit -m "feat: enqueue wecom notifications from inbound projections"
```

---

## 收尾验证

全部 Task 完成后，在 `demo/message-center-spring/backend` 下执行：

```bash
mvn -q test -Dtest='WeComUserNotification*,AppConfigTest,EmailSyncServiceTest,EmailOwnerIsolationTest,EmailControllerTest,EmailSyncSchedulerOwnerTest,ChatAppWebhookProjectorTest,ChatAppPollingProjectorTest'
```

Expected: 全绿。

然后跑一次全量测试，确认没有回归：

```bash
mvn -q test
```

Expected: 全绿。若失败，先确认失败用例是否也曾在改动前失败（`git stash` 后复跑对比），只修本次引入的回归。

### 验收标准覆盖对照

| spec 验收标准 | 覆盖它的测试 |
| --- | --- |
| 1 入站后有绑定负责人则 90 秒内收到提醒 | 逻辑由 `WeComUserNotificationServiceTest.enqueuesAPendingRowForTheBoundAssignee` + `WeComUserNotificationWorkerTest.sendsAggregatedTextThroughTheExistingWeComSendService` 分段覆盖；端到端需人工验证 |
| 2 90 秒内 3 条只发一条、正文显示 3 条 | `WeComUserNotificationPersistenceIntegrationTest.upsertMergesIntoThePendingRowWithoutExtendingTheWindow`（真实 PostgreSQL 上同窗口 upsert 递增 `message_count`）、`WeComUserNotificationWorkerTest.sendsAggregatedTextThroughTheExistingWeComSendService`、`WeComUserNotificationTextTest.aggregateRendersCountAndLatestPreview` |
| 3 窗口起点固定，不因后续消息推迟 | `WeComUserNotificationPersistenceIntegrationTest.upsertMergesIntoThePendingRowWithoutExtendingTheWindow`（第二次 upsert 后 `send_after`、`first_message_at`、`recipient_wecom_user_id` 仍是首次插入值） |
| 4 负责人为空或未绑定时不写行 | `WeComUserNotificationServiceTest.writesNothingWhenTheConversationHasNoAssignee`、`writesNothingWhenTheAssigneeHasNoWeComBinding` |
| 5 企微 chatdata 入站不产生提醒 | 结构上保证：本功能只在 `ChatAppWebhookProjector` 与 `EmailSyncService` 两处接入，`WeComMessageProjector`/`WeComChatDataStore` 未被改动。可在 review 时用 `git diff --stat` 核对这两个文件未出现在 diff 里 |
| 6 邮件入站同样触发 | `EmailSyncServiceTest` 新增用例 + `WeComUserNotificationServiceTest` |
| 7 失败重试 3 次上限、超限 FAILED、SENDING 卡住被捞回 | `WeComUserNotificationPersistenceIntegrationTest.recoverStuckReturnsOnlyStaleSendingRowsToPending`、`claimAndTerminalTransitionsOnlyApplyFromTheExpectedStatus`、`WeComUserNotificationWorkerTest.retriesUntilTheAttemptBudgetIsSpent`、`failsTheRowOnceTheAttemptBudgetIsExhausted`、`recoversRowsLeakedInSending` |
| 8 两个 worker 并发不重复发送 | `WeComUserNotificationPersistenceIntegrationTest.concurrentClaimOfOnePendingRowHasExactlyOneWinner`（真实 PostgreSQL 上两个并发 `claim` 只有一个成功） |
| 9 开关关闭时完全不产生行 | `WeComUserNotificationServiceTest.writesNothingWhenTheNotificationFeatureIsDisabled`、`ChatAppWebhookProjectorTest.inboundProjectionSucceedsWhenWeComNotificationsAreNotEnabled` |
| 10 ChatApp 事务回滚不留提醒行 | `WeComUserNotificationPersistenceIntegrationTest.upsertMergesIntoThePendingRowWithoutExtendingTheWindow`（真实 PostgreSQL 上验证入队写行所用的 upsert 可执行且幂等）。入队仍位于 `project()` 的 `@Transactional` 事务内，回滚边界本身是结构性保证，回滚后的端到端行为仍需人工验证。邮件路径不适用（无事务边界） |

**未覆盖、需要人工验证的部分：**

1. 真实企业微信环境下的端到端投递 —— 需要一个已绑定 `wecom_user_bindings` 的负责人、一个已安装的代开发应用、以及 `app.wecom-user-notification-enabled=true`。dev 环境手工验证：发一条 WhatsApp 入站消息，确认 90 秒内负责人收到企微应用消息，且 `wecom_user_notifications` 里该行是 `SENT`。
2. 验收标准 10 的事务回滚行为。
