# ChatApp 群发会话消息投影与对账修复设计

**状态：** 已确认，待实施

**日期：** 2026-08-17

## 1. 关系与适用范围

本文补充并收紧 `2026-08-13-chatapp-contacts-template-creation-mass-messaging-design.md`
中的群发闭环。原设计继续拥有群发创建、提交、批次状态、失败明细和仅失败重发；本文新增并拥有：

1. 群发收件人到统一会话消息的投影合同。
2. `ListChatappMessage + GroupMessageId` 对账响应的部分结果保留规则。
3. 群发对账失败的 Provider 诊断和有界重新对账。
4. 群发专用对账与普通消息历史同步之间的去重合同。

本文不改变群发最多 1000 人、只允许已审核且允许发送的模板、提交结果未知时禁止盲目重发等
既有边界。

## 2. 问题与当前证据

当前群发和普通会话使用两条没有闭合的数据链：

```text
SendChatappMassMessage
  -> chatapp_broadcasts
  -> chatapp_broadcast_recipients
  -> GroupMessageId 对账

contacts
  -> contact_identities
  -> conversations
  -> messages
```

`ChatAppBroadcastWorker` 在提交成功后只保存 `GroupMessageId` 并创建 `RECONCILE` job；对账结果只
更新 `chatapp_broadcast_recipients`。普通会话和联系人时间线只读取统一 `messages`，因此即使群发
对账成功，当前实现也没有把每个收件人的发送事实写入其会话。

当前数据库中的异常批次为：

```text
broadcast id: c06366f2-f9e1-44f5-97a2-d78be833f3bc
status: STATUS_UNKNOWN
recipient_count: 3
success_count: 0
failed_count: 0
processing_count: 3
provider_group_message_id: 已保存
recipient provider_message_id: 0 条
unified messages: 0 条
SUBMIT job: SUCCEEDED
RECONCILE job: DEAD, 10 / 10
error_code: CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE
```

用户侧 Provider 原始消息证据表明，同一批次实际是两条发送成功，一条因发送给当前业务账号自身
而失败。正确聚合应为 `PARTIALLY_FAILED`，而不是 `STATUS_UNKNOWN`。这证明发送动作已经发生，
当前主要故障位于对账响应接受、收件人匹配、状态持久化或其可观测性。

当前 `AliyunChatAppBroadcastGateway.parseReconciliation()` 还存在两个明确风险：

- 要求 `Success == true`、`Code` 为空或 `OK`、`Data != null` 同时成立，否则丢弃整页。
- `UserNumber` 无法归一化时直接跳过该行，不保存行级诊断。

网关将 SDK、网络和 Provider 异常统一压缩为
`CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE`，没有保留 Provider code、RequestId 或安全错误原因，
因此现有数据库无法区分参数、权限、Provider 拒绝、响应合同异常和临时网络故障。

## 3. 产品语义

1. Provider 接受整批群发并返回 `GroupMessageId` 后，每个收件人的对应会话必须立即出现一条
   `processing` 的 outbound 模板消息。
2. 后续 `sent / delivered / read / failed` 只更新同一条消息，不能创建第二条历史记录。
3. 群发批次继续表达整批执行状态；统一 `messages` 继续是联系人会话历史的唯一真源。
4. 对账暂时失败时，已创建的消息保持可见并显示“处理中”或“状态未知”，不能从会话消失。
5. Provider 明确返回的单个收件人失败只影响该 recipient 和对应消息，不得把整批覆盖为失败或
   未知。
6. 发给业务账号自身导致的 Provider 拒绝是正常的收件人级失败证据：保存失败状态和
   `FailReason`，不伪装成系统异常。

## 4. 唯一 owner

### 4.1 群发执行 owner

`ChatAppBroadcastApplicationService` 和 `ChatAppBroadcastWorker` 继续拥有批次、收件人、提交 job、
对账 job、聚合状态及失败重发。它们不得直接拼装页面消息，也不得让前端从群发表模拟会话历史。

### 4.2 会话投影 owner

新增 `ChatAppBroadcastMessageProjector`，作为群发 recipient 到统一消息的唯一桥梁。它接收结构化
命令：broadcast、recipient、模板正文快照、模板参数、提交时间和 Provider 对账证据；即时投影时
对账证据为空，对账更新时必须提供，负责：

- 获取或创建当前 `channel_account_id + contact_identity_id` 的 conversation。
- 幂等创建 outbound template message。
- 将 recipient 与 message 建立持久关联。
- 绑定 Provider message ID 并单调更新消息状态。
- 写入 `message_status_events`，并刷新来源和目标 conversation 的 `last_message_id/last_message_at` 摘要。
- 发布现有 `message-new` 事件，使会话页面沿用统一刷新机制。

Controller、scheduler、前端和 Mapper 不得自行实现群发消息投影语义。

### 4.3 outbound 状态 owner

抽取共享的 outbound 消息关联和状态推进逻辑，供 `ChatAppBroadcastMessageProjector`、
`ChatAppPollingProjector` 和 `ChatAppWebhookProjector` 复用。状态只能单调推进；晚到的低等级状态
不能覆盖 `delivered/read`，明确失败不能覆盖已确认的 `delivered/read`。

## 5. 数据合同

新增下一顺序 Flyway 迁移，不修改已应用的 V15。

### 5.1 `chatapp_broadcasts`

增加：

```text
template_body_snapshot text
last_reconciliation_request_id varchar(255)
last_reconciliation_provider_code varchar(100)
```

所有新群发在创建时必须保存非空模板正文快照。历史 V15 数据无法保证模板仍存在，因此迁移字段
允许旧行为空；旧行恢复时优先从当前账号、模板代码和语言对应的模板读取正文。仍无法取得正文时，
消息正文明确显示“模板内容不可用”，并在 metadata 中记录结构化原因，禁止伪造正文。

提交成功的 `provider_request_id/provider_code` 继续表达提交调用，不得被对账信息覆盖。

### 5.2 `chatapp_broadcast_recipients`

增加：

```text
message_id uuid references messages(id) on delete set null
```

为非空 `message_id` 建立唯一索引，保证一个统一消息最多属于一个群发 recipient。recipient 保存批次
执行事实和收件人快照，message 保存用户可见会话历史；`message_id` 只是两个 owner 的持久关联，
不构成重复业务含义。

### 5.3 对账证据

新增 `chatapp_broadcast_reconciliation_evidence` 表保存每次对账的有界、可脱敏行级证据：

```text
id uuid primary key
broadcast_id uuid not null references chatapp_broadcasts(id)
job_id uuid not null references chatapp_broadcast_jobs(id)
provider_request_id varchar(255)
page_number integer not null
row_number integer not null
user_number varchar(50)
provider_message_id varchar(255)
provider_unique_message_id varchar(255)
provider_status varchar(100)
failure_reason varchar(1000)
matched_recipient_id uuid references chatapp_broadcast_recipients(id)
diagnostic_code varchar(100)
created_at timestamptz not null default now()
```

表只保存号码、ID、状态和截断后的失败原因，不保存凭据或完整消息正文；同一 job 的同一页同一行
可通过唯一约束幂等写入。`chatapp_broadcasts` 的最近错误字段用于列表快速展示，证据表用于详情、
审计和恢复排查。每页使用 `row_number=0` 保存 envelope 诊断，真实数据行从 1 开始；对外响应必须
遮蔽完整号码。

### 5.4 统一消息字段

Provider 返回 `GroupMessageId` 后，每个 recipient 创建：

```text
direction         = outbound
message_kind      = template
current_status    = processing
counts_as_unread  = false
occurred_at       = broadcast.submitted_at
created_by_user_id = broadcast.created_by_user_id
client_request_id = broadcast:{broadcastId}:recipient:{recipientId}
body_text         = template_body_snapshot + recipient template params 的渲染结果
```

`metadata_jsonb` 至少保存：

```text
broadcastId
broadcastRecipientId
providerGroupMessageId
templateCode
templateName
languageCode
templateParams
```

`client_request_id` 继续受现有
`messages(channel_account_id, client_request_id)` 唯一索引保护，不新增平行幂等机制。

## 6. 提交与即时投影流程

Provider 调用不能与数据库事务原子提交，因此流程必须避免“Provider 已接受，但本地事务失败后
重新发送整批”：

1. worker 在数据库事务外调用 `SendChatappMassMessage`。
2. 收到有效 `GroupMessageId` 后，先在一个短事务中完成 SUBMIT job、保存 Provider 提交证据、
   将 recipients 标记为 `PROCESSING` 并创建 `RECONCILE` job。
3. 提交证据落库后，调用投影 owner 为尚无 `message_id` 的 recipients 创建 `processing` 消息。
4. 正常路径立即完成投影；进程在第 2、3 步之间崩溃时，`RECONCILE` job 在调用 Provider 前先补齐
   未投影 recipients。
5. 投影失败只能重试本地投影或对账 job，禁止重新进入 SUBMIT。

每个 recipient 的消息创建与 `message_id` 关联在同一事务内完成。重试先检查 recipient 的
`message_id`，再检查稳定 `client_request_id`；已存在时按幂等成功处理。

投影事务同时插入一条 `message_status_events(status=processing)`，并按现有会话规则更新
`last_message_id/last_message_at`。对账状态更新在同一事务内插入对应状态事件、更新消息状态、
更新 recipient 以及重算会话摘要。

## 7. Provider 对账解析

### 7.1 响应信封

解析器按以下顺序判断：

1. body 为空：响应无效。
2. Provider code 明确非空且非 `OK`：Provider 拒绝，保存 code、message 和 RequestId。
3. `Data` 存在：解析并持久化行级事实。`Success` 缺失或与 `Code=OK + Data` 冲突时记录合同警告，
   不得直接丢弃非空数据。
4. `Data` 为空或缺失：记录页码、code、Success、RequestId 和结构化原因，再进入有界重试。

日志和持久诊断不得保存 AccessKey、完整凭据或完整消息正文。

### 7.2 行级映射

每一行保留：

```text
UserNumber
MessageId
UniqueMessageId
MessageStatus / MessageStatusName
ClientAcceptStatusName
ClientReadStatus / ClientReadStatusName
FailReason
SendTime
```

状态映射：

```text
PROCESSING       -> processing
SENT             -> sent
DELIVERED        -> delivered
READ             -> read
FAILED_RECIPIENT -> failed
```

`UserNumber` 缺失或无法归一化时不得静默跳过。解析结果必须带行号、可用 Provider ID 和跳过原因；
可按 Provider ID 精确关联时继续更新，否则保留为未匹配证据。

### 7.3 部分结果

每页先持久化所有可精确匹配的行，再计算未匹配项：

- 三条均匹配，二成功一失败：批次为 `PARTIALLY_FAILED`，计数为 2/1/0。
- 两条匹配、一条没有最终状态：保留两条事实，剩余 recipient 继续 `PROCESSING` 并重试。
- 行存在但无法确认属于哪个 recipient：已匹配事实不回滚，批次进入 `STATUS_UNKNOWN`，详情显示
  未匹配数量和原因。
- Provider 接口整体不可用：不修改已有 recipient/message 事实，只重试 job。

每个响应页的 envelope 诊断和每一行的匹配/跳过结果先写入证据表，再更新批次聚合；证据写入
失败视为本地事务失败，不得宣称该页已完成对账。

## 8. 消息去重与普通历史同步

群发专用对账按 `broadcast_id + normalized recipient number` 精确定位 recipient，再使用
`recipient.message_id` 更新对应消息。

普通 `ChatAppPollingProjector` 处理 outbound 行时按以下优先级解析：

1. `channel_account_id + provider_message_id` 已存在：更新该消息。
2. Provider `UniqueMessageId` 能命中普通单条发送 `client_request_id`：更新该消息。
3. 根据账号、归一化 `UserNumber`、模板代码、语言和发送时间找到唯一的未绑定群发候选：调用共享
   关联器绑定现有群发消息。
4. 存在多个候选：返回结构化 `AMBIGUOUS_BROADCAST_RECIPIENT`，等待 `GroupMessageId` 专用对账，
   禁止猜测或导入第二条消息。
5. 没有群发候选：沿用现有孤儿 outbound 历史导入。

若普通同步先导入了带 Provider ID 的历史消息，群发对账必须优先复用并关联该消息，不能再创建
processing 副本。共享关联器必须覆盖“只有本地群发消息”“只有 Provider 历史消息”“两者已是同一
消息”和“两个候选冲突”四种情况；冲突时保留证据并停止自动合并，不删除消息。

## 9. 重新对账与错误可见性

增加受现有群发权限保护的只读操作：

```text
POST /api/chatapp/broadcasts/{broadcastId}/reconcile
```

该操作只创建新的 `RECONCILE` job，不调用 `SendChatappMassMessage`。允许范围：

- 已保存 `provider_group_message_id`。
- 状态为 `SUBMITTED`、`RECONCILING` 或 `STATUS_UNKNOWN`。
- 当前不存在 active RECONCILE job。

数据库唯一索引继续阻止同一批次并发存在多个 active job。每个新 job 仍有最大 10 次尝试、指数
退避和最大 300 秒间隔；不得通过按钮形成无界请求或绕过 Provider 限流。

群发详情新增并展示：

- 最近对账错误码和安全错误信息。
- 最近 Provider code 和 RequestId。
- 已匹配、未匹配和仍处理中的数量。
- recipient 的统一 `messageId`、最终状态和 `FailReason`。

`STATUS_UNKNOWN` 显示“重新对账”，不显示“重新发送整批”。失败重发继续只允许从明确
`FAILED_RECIPIENT` 快照创建一个新群发任务。

## 10. 当前异常批次恢复

实施和部署完成后，对批次 `c06366f2-f9e1-44f5-97a2-d78be833f3bc` 执行一次有授权的只读重新
对账：

1. 确认 `provider_group_message_id`、账号范围和三个 recipient 快照不变。
2. 创建新的 RECONCILE job，不创建 SUBMIT job。
3. 调用 `ListChatappMessage` 并保存 Provider RequestId、行数和解析诊断。
4. 将两条成功结果和一条发给自身账号失败结果关联到三个 recipients。
5. 创建或关联三个统一消息；失败消息保存 Provider `FailReason`。
6. 验证批次为 `PARTIALLY_FAILED`，计数为 2/1/0。

恢复过程禁止调用任何 Provider 写接口。若真实响应仍无法匹配，停止在结构化
`STATUS_UNKNOWN`，保留完整脱敏诊断，不猜测号码或手工篡改最终状态。

## 11. 前端行为

群发列表继续以批次为主，不与普通会话列表混合。群发详情补充：

- 批次状态、成功/失败/处理中计数。
- Provider GroupMessageId、最近 RequestId 和结构化错误。
- 每个 recipient 的消息关联状态和失败原因。
- 对 `STATUS_UNKNOWN` 的重新对账命令及 pending/disabled 状态。

联系人会话无需读取群发表。统一消息接口返回投影后的 template message，现有消息气泡负责显示
渲染正文和状态。本轮不增加群发来源标识，也不新增第二套消息组件。

## 12. 测试与验收

### 12.1 单元和合同测试

- gateway 请求包含正确账号、业务号码、GroupMessageId 和分页字段。
- `Code=OK + Data` 的有效响应不因 `Success` 缺失而被丢弃。
- 三人响应二成功一失败聚合为 `PARTIALLY_FAILED` 和 2/1/0。
- 缺失 `UserNumber` 的行产生结构化未匹配诊断，不静默消失。
- Provider 非 OK 响应和 SDK 异常保留 code、RequestId 和安全 message。
- 消息状态单调推进，晚到低等级状态不回退已读/已送达消息。
- `message_status_events` 和 conversation 摘要与消息状态同步更新。

### 12.2 worker 和投影测试

- Provider 返回 GroupMessageId 后创建三个 processing 消息。
- 重跑投影不重复插入消息或递增错误的会话序号。
- 对账更新 recipient 和关联 message 的 Provider ID、状态和时间。
- 投影失败后只重试本地投影/对账，不再次调用 submit。
- DEAD/STATUS_UNKNOWN 可创建一个新的 RECONCILE job；已有 active job 时幂等返回。
- 普通同步和群发对账以不同顺序到达时仍只有一条统一消息。
- 多个群发候选时普通同步停止猜测，并等待 GroupMessageId 对账。

### 12.3 数据库和集成测试

- Flyway 从 V1 到新迁移在 PostgreSQL Testcontainers 完整执行。
- recipient `message_id` 唯一约束和外键有效。
- 对账证据表的 job/page/row 幂等约束有效，失败原因被截断且不含凭据。
- 每个 recipient、message 和 conversation 的账号/身份范围一致。
- 批次、recipient 和 message 的最终计数与状态一致。
- 当前异常批次只读恢复后为 2 成功、1 失败、0 处理中，统一 messages 增加或关联三条且无重复。

### 12.4 前端验收

- 群发详情正确显示部分失败、失败原因、RequestId 和重新对账。
- 重新对账 pending 时按钮不可重复触发；失败后保留诊断和可恢复状态。
- 三个联系人会话分别显示对应 outbound 模板消息和最终状态。
- 桌面和 `390x844` 移动端检查加载态、空态、错误态、长失败原因和按钮不溢出。

## 13. 非目标

- 不重新设计群发创建页面、模板库或联系人选择器。
- 不把群发任务改造成 1000 个单条 outbox 发送。
- 不自动拆分超过 1000 人的批次。
- 不通过前端合并群发表和消息表。
- 不自动重新提交 `SUBMISSION_UNKNOWN` 或 `STATUS_UNKNOWN` 批次。
- 不删除现有消息、状态事件、附件或历史群发数据。

## 14. 停止条件

出现以下任一情况时停止自动恢复并保留诊断：

- Provider 返回的收件人无法唯一匹配本地 recipient。
- 同一 Provider message ID 已关联两个不同本地消息，且不能无损确定 canonical message。
- 账号、联系人身份、conversation 或 message 的账号范围不一致。
- Provider 响应合同与当前 SDK 模型不一致，导致关键字段不可证明。
- 实库恢复需要重新调用 `SendChatappMassMessage`。
