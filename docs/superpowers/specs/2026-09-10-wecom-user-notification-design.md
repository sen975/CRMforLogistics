# 非企业微信渠道新消息的企业微信应用消息提醒设计

## 文档状态

当前真源。本设计新增一条提醒链路，不改写现有渠道入站、消息投影、SSE 推送和出站发送路径。

## 目标

当非企业微信渠道（ChatApp/WhatsApp、邮件）有新的入站消息落库时，通过企业微信**应用消息**（`/cgi-bin/message/send`，`msgtype=text`）提醒该会话的负责人。短时间内同一会话的多条消息合并为一条提醒，避免刷屏，并天然形成限频。

## 产品边界

- 只发企业微信应用消息。不做小程序订阅消息、机器人 webhook、短信、邮件或 Web Push。
- 不做移动端 H5 会话页；通知正文是纯文本，不带跳转链接。
- 通知对象只取 `conversations.assigned_user_id`。不做团队、`channel_accounts.owner_user_id` 或管理员的兜底。
- 会话负责人为空或未绑定企业微信时不产生任何通知行，只记 debug 日志。
- 企业微信自身的入站消息（chatdata 投影）不触发本能力。
- 不做用户级免打扰、工作时间窗口和会话级静音；只提供全局开关。
- 不做通知的已读/未读状态、重发入口或管理员补发。
- 投递语义为 **at-least-once**：提醒行在发送成功后、状态回写前进程退出时可能重复提醒。不做跨重启去重。

## 唯一真源与职责

`conversations.assigned_user_id` 是收件人的唯一真源。本能力只读该字段，不改写会话归属，也不回退到其他候选人。

`wecom_user_bindings` 是「CRM 用户 → 企业微信 userid」的唯一真源，由现有登录绑定流程维护，本能力只读。

`wecom_user_notifications` 是新表，是「待发提醒」的唯一真源。它只表达本能力的投递状态，不参与消息、会话和联系人语义。

`messages`、`conversations`、`contacts`、`contact_identities` 在触发提醒时只读；本能力不写入这四个域的任何字段。

## 触发的消息范围

只有同时满足以下条件的消息才可能触发提醒：

- `messages.direction = 'inbound'`；
- `messages.counts_as_unread = true`；
- 所属 `channel_accounts.channel_type` 属于 `chatapp`、`whatsapp`、`email` 之一；
- 该消息不是投影层已判定的重复消息（幂等跳过的入站不触发）。

触发点在入站消息落库之后立即调用，但两条路径的事务语义不同：

- **ChatApp/WhatsApp**：`ChatAppWebhookProjector.project` 标注了 `@Transactional`。入队调用发生在该事务内、`messageMapper.insertWithSequence(message)` 之后，因此消息回滚时提醒行一并回滚。
- **邮件**：`EmailSyncService.appendReceived` **没有**事务边界。入队调用发生在消息插入成功之后，是尽力而为的：入队失败只记日志，不影响已入库的邮件；反之邮件插入失败时入队根本不会执行。这条路径不提供「回滚时提醒一并回滚」的保证。

企业微信 chatdata 投影（`WeComMessageProjector` / `WeComChatDataStore`）不调用本能力。

## 收件人解析

```text
conversationId -> conversations.assigned_user_id
  null -> 结束（不写行，debug 日志）
  -> wecom_user_bindings.findByUserId(userId)        # 返回 Optional，不是 requireByUserId
       空 -> 结束（不写行，debug 日志）
       -> 解析 WeCom installation 得到 agentId
          -> recipient_user_id / recipient_wecom_user_id / auth_corp_id 写入通知行
```

必须使用 `WeComUserBindingMapper.findByUserId` 这一返回 `Optional` 的查询。**不能**复用 `WeComUserBindingService.requireByUserId`：该方法在未绑定时抛出 `WECOM_USER_NOT_BOUND`，与本能力「未绑定不写行」的要求相反。同理，installation 解析也要用不抛异常的路径。

解析失败不抛异常、不阻断入站消息落库。整个解析与写入必须被 try/catch 包住，任何异常只记日志，不能影响消息投影事务。

## 聚合窗口

窗口长度由 `app.wecom-user-notification-window-ms` 控制，默认 90000（90 秒）。

**固定窗口**：窗口起点为该会话第一条触发消息的时间，`send_after = first_message_at + window`。窗口内后续消息只递增 `message_count`、更新 `last_preview` 和 `updated_at`，**不延长 `send_after`**。因此单条提醒的延迟上界是确定的 90 秒，不会因为客户持续发消息而无限推迟。

`send_after` 是一个统一的「不早于此时刻发送」时间戳：聚合窗口到期时它就是窗口终点；发送失败重试时它被改写为退避后的时刻。发送扫描只需一个条件。

聚合键是 `(conversation_id, recipient_user_id)`。部分唯一索引保证同一会话同一收件人在 PENDING 状态下只有一行：

```sql
create unique index ux_wecom_user_notification_pending
  on wecom_user_notifications (conversation_id, recipient_user_id)
  where status = 'PENDING';
```

入站事务内的写入使用 upsert：

```sql
insert into wecom_user_notifications (...) values (...)
on conflict (conversation_id, recipient_user_id) where status = 'PENDING'
do update set message_count = wecom_user_notifications.message_count + 1,
              last_preview  = excluded.last_preview,
              updated_at    = now();
```

`excluded` 行的 `first_message_at`、`send_after`、`recipient_wecom_user_id` 不参与更新。

## 发送

`WeComUserNotificationScheduler` 按 `app.wecom-user-notification-worker-interval-ms`（默认 1000）扫描到期行：

```sql
select * from wecom_user_notifications
 where status = 'PENDING' and send_after <= now()
 order by send_after
 limit :batchSize;
```

发送前先 claim：`update ... set status = 'SENDING', version = version + 1 where id = ? and status = 'PENDING'`。影响行数为 0 表示已被其他 worker 取走，跳过。随后调用现有 `WeComSendService.send(authCorpId, agentId, recipientWecomUserId, text)`。

`agent_id` 在入队时随行冗余存储，发送时不再回查 installation，避免发送期的额外失败点。

`SENDING` 超过 60 秒未落终态的行由 `recoverStuck` 捞回 `PENDING`，沿用 `ChatAppBroadcastWorker.recoverExpiredSubmissions` 的做法。

失败处理：递增 `attempt_count` 并写 `last_error`。少于 3 次则回到 `PENDING`，并把 `send_after` 改写为退避后的时刻（重试会重新进入到期扫描）；达到 3 次置 `FAILED`，不再重试。

`WeComSendService` 标注了 `@ConditionalOnWeComEnabled`，因此 scheduler 与 worker 必须挂同一个启用条件，未启用企业微信时不加载这些 bean。

## 消息文案

`msgtype = "text"`，正文控制在约 200 字节内。

- 单条（`message_count = 1`）：`【WhatsApp】张三：你好，想问下运费`
- 聚合（`message_count > 1`）：`【WhatsApp】张三 给你发了 3 条消息，最近一条：你好，想问下运费`

渠道标签：`chatapp` / `whatsapp` → `WhatsApp`；`email` → `邮件`。

联系人标签由调用方传入并在入队时冗余存储，发送时不再回查。两个调用点传入的都是地址簿已经算好的显示名：ChatApp 用入站负载里的 `ContactName`（为空时退到发件号码），邮件用发件人显示名。这与 `ChannelAddressBookService.resolveOrCreateInbound` 落库时写入 `contact_identities.display_name` 的值一致，因此提醒里的名字和界面显示一致。

预览取 `messages.body_text`，无正文时退到 `subject`；去掉换行并截断到 60 字。

## 数据表

新增迁移 `V67__wecom_user_notifications.sql`，只新增表，不改动已有表和已有数据。

主要字段：`id`、`conversation_id`、`channel_account_id`、`recipient_user_id`、`recipient_wecom_user_id`、`auth_corp_id`、`agent_id`、`channel_type`、`contact_label`、`message_count`、`last_preview`、`first_message_at`、`send_after`、`status`、`attempt_count`、`last_error`、`sent_at`、`created_at`、`updated_at`、`version`。

索引：部分唯一索引 `ux_wecom_user_notification_pending`，以及到期扫描用的 `(send_after) where status = 'PENDING'`。

`status` 取值：`PENDING`、`SENDING`、`SENT`、`FAILED`。

## 配置

```yaml
app:
  wecom-user-notification-enabled: ${WECOM_USER_NOTIFICATION_ENABLED:false}
  wecom-user-notification-window-ms: ${WECOM_USER_NOTIFICATION_WINDOW_MS:90000}
  wecom-user-notification-worker-interval-ms: ${WECOM_USER_NOTIFICATION_WORKER_INTERVAL_MS:1000}
  wecom-user-notification-worker-initial-delay-ms: ${WECOM_USER_NOTIFICATION_WORKER_INITIAL_DELAY_MS:1000}
  wecom-user-notification-max-attempts: ${WECOM_USER_NOTIFICATION_MAX_ATTEMPTS:3}
  wecom-user-notification-retry-backoff-seconds: ${WECOM_USER_NOTIFICATION_RETRY_BACKOFF_SECONDS:10}
```

前四项是 `AppConfig` 的配置属性。后两项由 worker 通过 `@Value` 直接读取，因为目前只有 worker 一个消费者。默认 `false`，先在 dev 打开验证，不改变现有部署的默认行为。

## 错误与观测

错误不返回给入站调用方，只落在 `wecom_user_notifications.last_error` 和日志里：

- `WECOM_NOTIFICATION_RECIPIENT_UNBOUND`：收件人无法解析（不写行）。
- `WECOM_NOTIFICATION_SEND_FAILED`：发送阶段失败，来自 `WeComSendService` 的 `WECOM_SEND_FAILED`。

日志只记录会话 ID、收件人用户 ID、渠道类型、聚合条数、耗时和有限的企业微信错误码；不记录消息正文全文、企业微信 access token 或联系人敏感信息。

## 迁移与兼容边界

历史入站消息不补发提醒。未绑定企业微信的历史会话负责人在完成绑定后，只会收到后续新消息的提醒，不追溯。

关闭 `app.wecom-user-notification-enabled` 后，入站消息照常落库并推送 SSE，只是不再产生提醒行；已存在的 PENDING 行不再被扫描，重新开启后按原窗口继续发送。

本能力不改变现有 SSE `message-new` 事件的发布位置和语义。

## 验收标准

1. WhatsApp 入站消息落库后，若该会话有已绑定企业微信的负责人，90 秒内收到一条企微应用消息。
2. 同一会话 90 秒内连续 3 条入站消息只产生一条提醒，正文显示 3 条。
3. 窗口起点固定：第 80 秒到达的第 4 条消息不会把发送推迟到第 170 秒。
4. 会话负责人为空或未绑定企业微信时，不产生任何 `wecom_user_notifications` 行，入站消息仍正常落库。
5. 企业微信 chatdata 投影的入站消息不产生提醒。
6. 邮件入站消息按同样规则产生提醒。
7. 发送失败按 3 次上限重试，超限后状态为 `FAILED` 且不再重试；`SENDING` 卡住的行会被捞回。
8. 两个 worker 并发扫描同一批到期行时不重复发送。
9. `app.wecom-user-notification-enabled=false` 时完全不产生提醒行，且现有消息落库、SSE 和出站发送行为不变。
10. ChatApp/WhatsApp 入站消息事务回滚时不留下提醒行（该路径有事务边界）。邮件路径没有事务边界，不适用此条，只保证「入队失败不影响邮件入库」。

## 非目标

- 企业微信小程序、小程序订阅消息或 H5 移动端会话页。
- 团队、渠道账号 owner 或管理员的兜底通知。
- 用户级免打扰、工作时间窗口、会话级静音和会话级订阅。
- 短信、邮件、浏览器 Web Push 等其他提醒通道。
- 通知的已读回执、重发、撤回和管理员补发入口。
- 企业微信自身入站消息的提醒。
- 提醒投递的跨重启精确一次语义。
