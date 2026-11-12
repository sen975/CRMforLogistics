# 企业微信客户联系事件接入设计

## 文档状态

设计修订版，待阶段 0 前置验证（见实施文档）。本设计**新增**一条应用级回调通道，不改写现有模板级授权回调链路（`WeComController` / `WeComCallbackCodec` / `WeComAuthorizationService` 的行为保持不变）。阶段 1 的可靠性真源是数据库持久化 inbox；内存队列不能作为回包后的唯一存储。

## 背景

系统是**代开发应用**（服务商为企业代开发），已接入 7 类企微能力。客户联系（外部联系人 / 客户群）目前只有 6 个**查询**接口，且全部是实时透传：

- `WeComExternalContactService` 的 `list` / `get` / `batchGet` / `remark` / `groupList` / `groupGet` 六个方法全部 `return execute(...)` 直返 `JsonNode`，除 60 分钟 TTL 的内存 `displayNameCache` 外**不落任何库**。
- 前端 `/settings/wecom` → 「客户联系」Tab（`WeComExternalContactPanel`）每次进页面现拉 `/cgi-bin/externalcontact/list`。

由此产生两个结构性缺口：

1. **没有时间维度。** 快照只回答「现在有哪些客户」，客户被删后下次拉列表就少一行，**没有任何痕迹**——「新增」「流失」这两个概念在当前模型里无法表达。
2. **看得见、点不进去。** `WeComContactLinkService` 的类注释明确写了永不创建联系人，映射来源是 `wecom_source_participants`，即只有在会话里出现过的客户才有 `contactId`；新加但还没聊过的客户在面板里可见但无法打开会话。

事件回调补齐的正是这两点：把「当前状态」变成「状态 + 变化」。

## 目标

接收并持久化企业微信**客户联系事件**，使系统能够回答：

- 某客户是什么时候、由哪位成员、通过哪个渠道（`State`）加进来的；
- 某客户是什么时候被删除或停止跟进的；
- 某个客户群什么时候创建、变更、解散。

## 产品边界（明确不做）

- 不做企微消息类事件（`text` / `image` / `voice` 等）。会话内容真源仍然是 chatdata 会话存档，本能力不碰消息域。
- 不做 `subscribe` / `enter_agent` / `click` / `view` 等成员交互事件。
- 不做 OA 审批、日程、微信客服事件。
- 不自动创建 CRM 联系人。事件只记录关系变化，`contacts` / `contact_identities` 的创建仍然由既有链路负责（本设计提到的「预热 identity」列为后续可选阶段，不在首期）。
- 不做已读/未读、不做事件的人工补录、不做历史事件回溯（企微不提供事件的历史查询接口，只能从现在开始收）。
- 不做外部联系人标签（`remark`）的写入回企微。
- 首期不做「新客户 N 天未跟进自动待办」的具体策略，只把事件数据准备好（策略属第二阶段）。

## 前置事实（代开发 API 核查结论）

以下是本次核查的**实测/原文**结论，是设计的前提，不是推测。

### 代开发应用有两条独立的回调通道

| 维度 | 模板回调通道（已接入） | 应用回调通道（本设计新增） |
|---|---|---|
| 用途 | 平台级事件：凭证与授权 | 业务级事件：消息、客户联系、审批 |
| 事件类型 | `suite_ticket` / `create_auth` / `change_auth` / `cancel_auth` / `reset_permanent_code` | `change_external_contact` / `change_external_chat` 等 |
| 加密密钥 | 模板级 Token + EncodingAESKey | **应用级 Token + EncodingAESKey** |
| 解密后校验的 receiveid | `suite_id` | **`auth_corp_id`（密文 corpid）** |
| 配置位置 | 服务商后台 → 代开发模板 → 回调配置 | 服务商后台 → 代开发应用 → 数据回调配置 |

官方依据：`自建应用代开发`（doc 31281）在「开始代开发应用」流程中列出**代开发应用回调 URL** 配置项，并注明「会默认填写代开发模版的信息，可以进行修改」，即代开发应用层可以持有与模板层不同的回调配置。官方客户联系事件文档（doc 92130）的权限表对**代开发应用**写明「具有 `客户联系->客户基础信息` 权限」，且「接收事件开关」一列为**无**——即代开发应用不需要像自建应用那样去「客户联系-客户」页面另开一个开关。

> **待确认（阶段 0 阻塞项）**：应用级 Token / EncodingAESKey 是否**独立于**模板级一套。官方 31281 只明确「回调 URL 可修改」，未逐字说明 Token/AESKey 是否也独立。本设计用**命名通道**抽象吸收这个不确定性（见下节）——若实际只有一套，两条通道填相同凭据即可，无需改代码。

### 加密与签名

与现有模板回调完全一致的 AES-256-CBC 规范：`Key = Base64Decode(EncodingAESKey + "=")`（32 字节），`IV = Key` 前 16 字节，明文结构 `random(16) + msg_len(4, 网络字节序) + msg + receiveid`，PKCS#7 填充。签名 `msg_signature = sha1(sort(token, timestamp, nonce, encrypt))`。

**唯一差异是 receiveid**：模板通道校验 `suite_id`，应用通道校验 `auth_corp_id`。

### 事件 XML 结构

```xml
<xml>
  <ToUserName>wpxxxxxxxx</ToUserName>       <!-- 密文 corpid，= receiveid -->
  <FromUserName>sys</FromUserName>          <!-- 系统事件固定 sys -->
  <CreateTime>1403610513</CreateTime>
  <MsgType>event</MsgType>
  <Event>change_external_contact</Event>
  <ChangeType>add_external_contact</ChangeType>
  <UserID>woYYYYYYYY</UserID>                <!-- 服务人员的密文 userid -->
  <ExternalUserID>wmZZZZZZZZ</ExternalUserID><!-- 外部联系人，非成员账号 -->
  <State>渠道标识</State>                     <!-- 来自「联系我」配置或获客链接 customer_channel -->
  <WelcomeCode>欢迎语code</WelcomeCode>       <!-- 可用于发送欢迎语 -->
</xml>
```

**没有 `InfoType` 字段，也没有 `SuiteId` 字段。** 这是现有解码器无法处理的直接原因（见下节）。

### 事件清单（规划目录）

| 分组 | 事件 | `Event` / `ChangeType` |
|---|---|---|
| 客户关系（6） | 添加企业客户 | `change_external_contact` / `add_external_contact` |
| | 编辑企业客户 | `change_external_contact` / `edit_external_contact` |
| | 外部联系人免验证添加成员 | `change_external_contact` / `add_half_external_contact` |
| | 删除企业客户 | `change_external_contact` / `del_external_contact` |
| | 删除跟进成员 | `change_external_contact` / `del_follow_user` |
| | 客户接替失败 | `change_external_contact` / `transfer_fail` |
| 客户群（3） | 创建 / 变更 / 解散 | `change_external_chat` / `create` `update` `dismiss` |
| 客户标签（4） | 创建 / 变更 / 删除 / 重排 | `change_external_tag` / `create` `update` `delete` `shuffle` |

> V81 首期只处理**客户关系（6 个）**。客户群与标签事件只是后续规划目录，不进入本次解码器、数据表或 API 合同；它们必须在字段核对完成后使用独立迁移和独立 fixture。

### 权限与硬约束

- 权限：代开发应用需具有 **`客户联系 → 客户基础信息`**。
  - 接收**客户接替失败**事件另需「客户联系 → 分配在职或离职成员的客户」。
  - 接收**标签类**事件另需「客户联系 → 管理企业客户标签」。
- **回包必须在 1 秒内返回**，否则企微会屏蔽该事件一段时间。这是本设计的核心工程约束。
- 成员必须在应用可见范围内，否则事件不推送。
- **互通账号 license**：代开发 / 第三方应用做客户联系需购买互通账号许可，按账号数计费。

> **待确认（阶段 0 阻塞项）**：license 是**接收事件**的前置，还是只影响**主动调用**客户联系接口。若影响接收，则本能力有商务前置条件。

## 架构

### 命名通道抽象

把回调按「通道」建模，每条通道自带 URL 路径、凭据与 receiveid 校验规则：

```java
enum WeComCallbackChannel {
    SUITE,  // path=/api/wecom/callback,                receiveid = suite_id
    APP     // path=/api/v1/wecom/app-callback,        receiveid = auth_corp_id（密文）
}
```

这样做的理由：**用配置吸收「企微实际给几套凭据」这个不确定性**。若确认 Token/AESKey 独立，两条通道填不同值；若共用，填相同值。代码不需要改。

### 不改写现有链路

现有 `WeComCallbackCodec` 对模板回调是**硬校验**语义：

- `decode()` 第 103 行 `decrypt(encrypted, allowedSuiteIds)` —— receiveid 必须在 suiteId 集合内；
- `parse()` 第 258–263 行要求 `SuiteId` 非空、在 allowlist 内、**且等于 receiveid**；
- 第 265 行 `require(field(document, "InfoType"), "InfoType", 64)` —— InfoType **必填**。

客户联系事件的 XML 里既无 `SuiteId` 也无 `InfoType`，会在 `parse()` 第一步就抛 `WeComCallbackFailure.Stage.SUITE_ID`。所以：

**不修改 `WeComCallbackCodec`，新增平行的 `WeComAppEventCodec`。** 理由：

1. 授权链路是生产关键路径（`suite_ticket` 每 10 分钟推送一次，丢了会影响所有企业的 access_token），不应为业务事件承担回归风险。
2. 两条通道的校验语义本就不同（suite_id vs auth_corp_id），混在一个类里会让 `parse()` 的分支复杂化。
3. 现有类已有 `public static String sha1(...)`，说明共享工具函数的做法在项目里是被接受的。

具体做法：把 `sha1` / AES 解密 / PKCS#7 去填充 / `encryptedValue` 提取抽为包内静态工具（`WeComCallbackCipher`），`WeComCallbackCodec` 改为委托该工具（行为不变），`WeComAppEventCodec` 复用同一工具。

### 解密与 receiveid 校验策略

应用通道的 receiveid 是**密文 corpid**，而密文 corpid 是授权回调时才知道的值，**不能预先枚举**（企业数会增长）。因此校验顺序必须是「先解密、后校验」：

```text
1. 验签：sha1(appToken, timestamp, nonce, encrypt) == msg_signature
2. AES 解密（用 appAesKey）→ 明文 XML + receiveid
3. 从明文 XML 取 ToUserName，断言 receiveid == ToUserName   ← 自洽校验，不依赖外部集合
4. 用当前配置的 suite_id + ToUserName（密文 corpid）去 installations.find(...)
   ├─ 找到 → 得到唯一 installation_id
   └─ 找不到 → 记录结构化失败并返回 503，让授权竞态或配置错误可重试
```

第 3 步的关键：**receiveid 与 XML 里的 ToUserName 必须相等**，这是企微加解密协议的固有自洽性，用它做校验既安全又不需要预知企业列表。

## 数据模型

### 新表 `wecom_contact_events`（迁移 V81）

事件流水表，是「客户关系变化」的唯一真源。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | uuid PK | |
| `installation_id` | uuid NOT NULL FK | `wecom_installations.id`，事件的唯一安装归属，删除策略为 RESTRICT |
| `suite_id` | varchar(128) NOT NULL | 服务商侧模板 ID快照 |
| `auth_corp_id` | varchar(128) NOT NULL | 密文 corpid 快照 |
| `event` | varchar(64) NOT NULL | 首期固定为 `change_external_contact` |
| `change_type` | varchar(64) NOT NULL | `add_external_contact` 等 |
| `wecom_user_id` | varchar(128) | 服务人员密文 userid（可空） |
| `external_user_id` | varchar(128) | 外部联系人（可空，群事件无） |
| `chat_id` | varchar(128) | 客户群 chatid（可空） |
| `state` | varchar(256) | 渠道标识（可空） |
| `welcome_code` | varchar(256) | 欢迎语 code（可空，仅用于后续发送） |
| `fail_reason` | varchar(256) | `transfer_fail` 的失败原因（其他事件为空） |
| `provider_created_at` | timestamptz NOT NULL | 事件 XML 的 `CreateTime` |
| `received_at` | timestamptz NOT NULL | 服务端接收时刻 |
| `dedupe_key` | varchar(64) NOT NULL | sha256 去重键，**唯一** |
| `ingest_status` | varchar(24) | `RECEIVED` / `PROCESSING` / `PROCESSED` / `RETRY` / `DEAD_LETTER`，默认 `RECEIVED` |
| `attempt_count` | integer | 后续动作尝试次数，默认 0 |
| `last_error` | varchar(256) | 最近一次后续处理失败原因，可空 |
| `processed_at` | timestamptz | 后续动作处理完成时刻，可空 |
| `created_at` | timestamptz | |

约束与索引：

- `UNIQUE (dedupe_key)`
- `dedupe_key` 的规范化输入必须包含 `installation_id`；全局唯一约束即可。
- `INDEX (installation_id, external_user_id, provider_created_at DESC)` —— 支撑「某客户的动态时间线」
- `INDEX (installation_id, provider_created_at DESC)` —— 支撑「最近的客户动态」列表
- `CHECK (event = 'change_external_contact')`
- `CHECK (ingest_status IN ('RECEIVED','PROCESSING','PROCESSED','RETRY','DEAD_LETTER'))`

首期不把客户群、客户标签事件写入这张表；它们需要独立的字段合同和后续迁移，不能仅靠当前通用列宣称支持。

### 去重键的构造（关键设计点）

**企微事件没有事件 ID。** 官方事件 XML 只有 `CreateTime`，没有 `EventId` 之类的唯一标识；而 `ChatAppWebhookInboxService` 的幂等是建立在 WhatsApp 的 `EventId` 之上的，**这个先例不能照搬**。

因此幂等键必须自造，取「事件的全部语义标识」拼接后哈希：

```text
dedupe_key = sha256(canonical_json({
  installation_id, event, change_type, wecom_user_id, external_user_id,
  chat_id, state, welcome_code, fail_reason, provider_created_at
}))
```

写入用 `INSERT ... ON CONFLICT (dedupe_key) DO NOTHING`，冲突即视为重复投递，直接 ack。规范化 JSON 必须保留空值与大小写，不做密文 ID 大小写归一化；这样 `state`、欢迎语 code 和失败原因不会被去重逻辑吞掉。

选用 `CreateTime` 而非 `received_at` 入键的理由：企微重推时 `CreateTime` 不变，而 `received_at` 每次都变。同一秒内同一成员对同一客户产生两次同类型事件（真实场景几乎不存在）会被误判为重复——这个误差方向是**宁可少记也不重复记**，与关系流水的语义一致。

### 关系状态投影（第二阶段，不在首期）

若需要「当前是否仍是客户」这个查询，再引入 `wecom_contact_relations`：

- `add_external_contact` / `add_half_external_contact` → upsert 关系行，`started_at`
- `del_external_contact` / `del_follow_user` → 回填 `ended_at`

**首期不做**：先把流水采准，投影表在没有消费方之前不建，避免重演 `media_json`（「存了没人消费」）。

## 处理流程

```text
企微 POST /api/v1/wecom/app-callback?msg_signature&timestamp&nonce
  │
  ├─ WeComController（新方法，复用 startupGate.requireOpen()）
  │
  ├─ WeComAppEventCodec.decode(...)       验签 → 解密 → 自洽校验 → 解析事件
  │    失败 → 403，记 WeComCallbackFailure.Stage，不回 retry（非法请求重试无意义）
  │
  ├─ 解析 installation（当前配置 suite_id + 密文 corpid → installation_id）
  │    找不到 → 503 retry，并记录结构化失败
  │
  ├─ 在受控数据库/连接池/语句超时预算内同步事务 INSERT inbox（含 installation_id、完整字段、dedupe_key）
  │    commit 成功/重复 → 200 "success"
  │    数据库不可用/事务超时 → 503 "retry"
  │
  └─ 后续动作（第二阶段）从 durable inbox 领取 pending 行
       ├─ 使用有限租约、重试次数和 dead-letter 状态
       └─ 进程重启后可从数据库恢复，不能依赖进程内队列
```

**为什么必须先持久化再 ack**：回包超 1 秒会导致事件被屏蔽一段时间，但入队后进程崩溃同样会造成永久丢失。因此必须在 1 秒预算内完成一次有界数据库 INSERT 并提交；数据库连接获取、SQL 执行和事务提交都要有低于平台 1 秒限制的明确超时预算。只有提交成功或幂等冲突后才返回 200。数据库不可用、未知 installation 或服务未就绪统一返回 503，让企微保留重试机会。

当前运行面是单个配置 `app.wecom-suite-id`；所有安装解析和查询都必须显式带该 suite_id。若未来支持多 suite，API 和绑定模型必须迁移到 installation_id，不能继续只用 auth_corp_id。

## 前端承接

### 首期

在 `/settings/wecom` 新增一个 **「客户动态」Tab**，而不是改造现有「客户联系」面板。理由：现有面板是「当前客户列表」（快照语义），动态是「流水语义」，两者并列比混在一个列表里更清晰，也避免动到现有面板的既有行为。

后端接口：

```text
GET /api/v1/wecom/installations/{authCorpId}/contact-events?since&changeType&limit
  → List<WeComContactEventResponse>
    { externalUserId, changeType, wecomUserId, state, failReason, providerCreatedAt }
```

- `limit` 上限 200，默认 50；`since` 为可选时间下界。
- 接口实际路径为 `/api/v1/wecom/installations/{authCorpId}/contact-events`；服务端用当前 suite_id 解析 installation，并复用现有 `WeComUserBindingService` 校验当前用户绑定的企业。首期不提供 displayName，不在列表接口触发企微实时查询。
- 前端只展示事件 ID 或已有本地名称；未识别的 `externalUserId` 显示「未获取昵称」。名称批量补齐另立接口，并必须有并发、总耗时和失败预算。

### 第二阶段（列选项，不在首期）

- 现有「客户联系」面板增加「何时加入」列（从流水取最早的 `add_external_contact`）。
- 客户详情抽屉展示「该客户的动态时间线」。
- 客户群 Tab 状态实时化（复用 V37 `wecom_external_group_syncs`）。

## 与动作系统的连接（价值所在）

**只落库不产生动作，这件事的收益配不上成本。** 事件的真正价值是把「客户变化」变成「有人要做的事」。两条现成通路：

1. **待办**：`todo_items`（V75/V80）已有完整生命周期与提醒调度（0 点汇总 + 开始前 3 小时）。`add_external_contact` → 生成「新客户首次跟进」待办，负责人取该客户会话的 `conversations.assigned_user_id`。
2. **企微通知**：`wecom_user_notifications`（V67）已有「非企微渠道入站消息提醒会话负责人」的机制与 worker。客户新增/流失可复用同一套投递，不必新造通知链路。

两条都列为第二阶段，且**必须有明确的产品规则后再做**（例如「几天未跟进算逾期」），否则就是无意义的自动待办。

## 未决项与风险

### 阶段 0 必须先验证（阻塞实施）

1. **应用级回调的配置位置与凭据独立性**：在服务商后台确认代开发应用的「数据回调配置」在哪、Token/EncodingAESKey 是否独立于模板。若无法配置应用级回调，本设计整体不成立。
2. **license 是否为接收前置**：若接收事件也需购买互通账号许可，则本能力有商务前置，需先决策。
3. **事件字段核对**：按官方 doc 92130 逐个核对 6 个客户关系事件的字段集，特别是 `edit_external_contact`、`transfer_fail`、`add_half_external_contact` 是否有额外字段。
4. **一次真实的添加客户事件**：用测试企业互删重试，确认能收到 `add_external_contact` 且能解开（社区中「收不到回调」的常见原因是应用可见范围、客户联系权限、IP 白名单、license 未购买四项）。

### 已知风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 回包超 1 秒 | 企微屏蔽该事件一段时间 | 有界同步 INSERT；数据库失败返回 503 |
| 事件被屏蔽期间的变化丢失 | 流水有缺口 | 企微不提供历史事件查询，无法补齐；在文档与前端明示「动态自接入日始，非全量历史」 |
| 无事件 ID 导致去重不准 | 极端情况下漏记同秒同类型事件 | 宁可漏记不重复记；用 `CreateTime` 入键 |
| 代开发加密 corpid 大小写敏感 | 查 installation 失败 | 密文 ID **不得**做 toLowerCase/toUpperCase 归一化（官方明确提示） |
| 后续动作失败 | 事件已落库但动作未完成 | durable 状态、租约、有限重试和 dead-letter；监控 retry/dead-letter |
| 事件表无限增长 | 存储 | 第二阶段扩展既有 retention owner 覆盖本表；配置项必须和实际清理任务同批交付 |

## 与既有设计的一致性声明

- 不改写任何现有链路：模板回调、授权队列、chatdata 同步、消息投影、通知链路均不动。
- 新代码所在包（`service.wecom` / `channel.wecom`）在 ArchUnit 门禁白名单内（`CHANNEL_AWARE_SERVICE_PACKAGES` 含 `service.wecom`），不新增越界。
- 不在 `service.todo` 等业务域直接依赖 `channel.wecom`；若第二阶段生成待办，由 `service.wecom` 侧调用待办服务，方向为业务域被调用而非反向依赖。
