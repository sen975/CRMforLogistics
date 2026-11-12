# 企业微信客户联系事件接入实施计划

配套设计文档：`docs/superpowers/specs/2026-09-21-wecom-customer-contact-events-design.md`

## 文档状态

实施计划，**未开始编码**。阶段 0 是阻塞门，未通过前不进入阶段 1。

## 总体原则

- **不改写现有链路**。模板回调、授权队列、chatdata 同步、消息投影、通知链路的行为保持不变；`WeComCallbackCodec` 只做「抽工具类 + 委托」，不改变对外行为。
- **每阶段可独立验收、独立提交**。阶段之间不共享未完成的半成品。
- **默认关闭**。新增能力由配置开关控制，默认 `false`，可在不发布前端的情况下先灰度后端。
- **先持久化真源，再谈消费**。回调在数据库事务提交或幂等冲突后才返回 200；进程内队列只能做后续动作加速，不能承担可靠接收。
- **当前运行面是单 suite**。所有安装解析、查询和权限校验都使用 `config.wecomSuiteId()`；若未来支持多 suite，必须改为 installation_id 路由。

---

## 阶段 0 · 前置验证（不改代码，阻塞门）

目的：用真实环境确认设计成立。**任一项不通过，本计划暂停。**

### 0.1 在服务商后台确认应用级回调配置

- 进入服务商后台 → 应用管理 → 应用代开发 → 选择模板 → 「开始代开发应用」/代开发应用详情
- 记录以下事实：
  1. 「代开发应用回调 URL」在哪个页面、字段名是什么；
  2. 该页面是否同时提供独立的 **Token** 与 **EncodingAESKey**；
  3. 若只提供 URL 而无独立凭据，记录它继承自模板的哪一项。
- **产出**：确认「应用级凭据是否独立」。若独立 → 设计中的命名通道各填一套；若共用 → 两条通道填相同值，代码不变。

### 0.2 确认 license 是否为接收前置

- 向企微商务/服务商后台确认：未购买互通账号 license 时，**客户联系事件推送**是否会发生。
- 若能拿到书面/后台截图结论最好。
- **产出**：本能力是否需要商务前置。若需要，先决策再继续。

### 0.3 核对 6 个客户关系事件的字段

- 按官方 doc 92130 逐个核对：`add_external_contact` / `edit_external_contact` / `add_half_external_contact` / `del_external_contact` / `del_follow_user` / `transfer_fail`。
- 重点确认：是否每个都有 `UserID` + `ExternalUserID`；`transfer_fail` 是否带 `FailReason`；是否有事件带 `WelcomeCode` 以外的额外字段。
- **产出**：一份字段核对表，作为 `WeComAppEventCodec` 解析字段的准绳。**不凭记忆写解析。**

### 0.4 打通一次真实事件

前置：测试企业 + 应用可见范围内成员 + 客户联系权限已勾选 + IP 白名单已配。

1. 在服务商后台把「代开发应用回调 URL」临时指向一个可打印请求的调试端点（本机 ngrok 或测试环境）。
2. 用测试账号添加一位外部联系人为客户。
3. 确认收到 `event=change_external_contact&change_type=add_external_contact`，且**能解开**（拿到的明文 XML 与 0.3 的字段表一致）。
4. 再执行删除客户，确认收到 `del_external_contact`。
5. **产出**：一份真实事件样本（脱敏后）存入 `docs/`，作为测试夹具的真源。必须遮蔽或替换 `ToUserName`、`UserID`、`ExternalUserID`、`State`、`WelcomeCode` 等敏感值，不把可直接调用的密文或凭据写入仓库。

> 社区里「收不到回调」的四个常见原因，按此顺序排查：成员不在应用可见范围 → 应用无客户联系权限 → IP 白名单 → 未购买 license。另外「平均回包耗时过高会屏蔽事件一段时间」，调试端点必须在 1 秒内回包。

### 阶段 0 通过标准

四项全部有明确结论，且 0.4 拿到了真实事件样本。否则停止，回到设计讨论。

---

## 阶段 1 · 后端打通（解码 + 落库）

目标：事件能收、能解、能落库、能去重，并满足 1 秒回包。**不含前端。**

### 1.1 数据库迁移

新增 `backend/src/main/resources/db/migration/V81__wecom_contact_events.sql`：

- `CREATE TABLE wecom_contact_events`，列与约束见设计文档「数据模型」。
- `installation_id` 必须外键引用 `wecom_installations(id)`；事件查询必须通过当前 suite_id + authCorpId 解析到该安装后执行。
- 迁移编号接续现状（当前最新为 `V80__todo_reminders.sql`）。
- 遵循既有迁移风格：`timestamptz`、`gen_random_uuid()`、显式 `CHECK` 约束、列注释说明语义（参考 `V35__wecom_media_descriptor.sql` 的注释写法）。

### 1.2 抽出共享加解密工具

新增 `channel/wecom/WeComCallbackCipher.java`（包内可见）：

- `static String sha1(String token, String timestamp, String nonce, String encrypt)`
- `static String encryptedValue(String body)` —— 从信封 XML 取 `Encrypt`，含 XXE 防护
- `static byte[] aesDecrypt(byte[] aesKey, String base64Ciphertext)` —— 含 padding 校验
- `static Decrypted decrypt(String aesKeyBase64, String base64Ciphertext)` → `xml` + `receiveId`

改造 `channel/wecom/WeComCallbackCodec.java`：把 `sha1` / `encryptedValue` / AES 解密段替换为对 `WeComCallbackCipher` 的委托。

**硬性要求**：改造后 `WeComCallbackCodec` 的**对外行为必须完全不变**。验收方式是先跑现有回调测试，改造后再跑一次，两者结果必须一致。

### 1.3 新增应用级解码器

新增 `channel/wecom/WeComAppEventCodec.java`：

- 构造器接收 `appToken` / `appEncodingAesKey` / `Clock`（照 `WeComCallbackCodec` 的多构造器模式，便于测试注入固定时钟）。
- `decode(msgSignature, timestamp, nonce, encryptXml)`：
  1. 输入校验（长度上限，与 codec 一致）
  2. `encryptedValue` 取 Encrypt
  3. 时间窗校验（±900 秒，复用现有策略）
  4. 验签
  5. AES 解密
  6. **自洽校验**：取明文 XML 的 `ToUserName`，断言 `receiveId.equals(toUserName)` —— 不依赖外部企业集合
  7. 解析事件字段；`transfer_fail` 必须保留 `FailReason` 到 `failReason`
- 解析后**必须校验 `MsgType` 为 `event`**，非 event 直接拒绝（本能力不处理消息类）。
- 失败复用 `WeComCallbackFailure`，但新增 stage：`APP_EVENT_MISSING_CHANGE_TYPE`、`APP_EVENT_UNSUPPORTED`，避免与 suite 链路的 stage 混淆。

返回记录：

```java
record DecodedAppEvent(
    String toUserName,        // 密文 corpid
    String fromUserName,      // sys
    String event,
    String changeType,
    String userId,
    String externalUserId,
    String chatId,
    String state,
    String welcomeCode,
    String failReason,
    Instant providerCreatedAt
) {}
```

字段长度上限参照现有 `MAX_FIELD = 512`；`state` 按官方文档放宽到 256，`welcomeCode` 256。

### 1.4 回调端点

修改 `channel/wecom/WeComController.java`，**新增**两个方法（不动现有 `/api/wecom/callback`）：

```text
POST /api/v1/wecom/app-callback   msg_signature / timestamp / nonce + body
GET  /api/v1/wecom/app-callback   msg_signature / timestamp / nonce / echostr   （URL 验证）
```

- 复用现有 `startupGate.requireOpen()` 语义：未就绪返回 503。
- POST：解码 → 按 `config.wecomSuiteId()` 解析 installation → 同步写入 durable inbox → 事务提交后 `200 "success"`；数据库失败、未知 installation 或服务未就绪 → `503 "retry"`。
- 解码失败 → `403`（非法请求重试无意义），并记录 stage 与脱敏后的 receiveId 摘要（复用 `WeComCallbackFailure.receiveIdSha256()` 的脱敏思路）。
- GET（URL 验证）：验签 + 解密 echostr，返回明文。

> 端点路径是新的，与模板回调的 `/api/wecom/callback` 并列。这样做的前提是企微允许为两个配置填不同 URL —— 阶段 0.1 已确认过。若企微只允许填一个 URL，则必须在阶段 0 回到设计讨论，不能未经验证地在现有模板链路中增加二次解密分流。

### 1.5 事件落库服务

新增 `service/wecom/WeComContactEventService.java`：

- POST 路径中同步执行 `INSERT ... ON CONFLICT (dedupe_key) DO NOTHING`，提交成功或冲突后才返回 200；数据库不可用返回 503。
- 事件表必须包含 `installation_id` 外键、`fail_reason`、`ingest_status`、`attempt_count`、`last_error` 和 `processed_at`，用于后续动作恢复；V81 的 `event` 只允许 `change_external_contact`。
- **去重键构造必须集中在一个方法里**（`dedupeKey(...)`），使用包含全部合同字段的规范化 JSON；单测覆盖空值、大小写和字段顺序。
- 首期不创建以内存队列为真源的 worker。第二阶段消费时可使用有界队列作为加速，但必须从数据库领取带租约的 pending 行，并支持重启恢复和 dead-letter。
- 开关：`app.wecom-contact-event-enabled`，默认 `false`；关闭时返回 503，不确认事件，避免主动丢失。
- **密文 ID 一律不做大小写归一化**（官方明确提示密文大小写敏感）—— 在代码里加注释说明，防止后续有人「顺手」加 `toLowerCase()`。

新增实体与 Mapper：

- `channel/wecom/WeComContactEventEntity.java`
- `mapper/WeComContactEventMapper.java`（`insertIgnore` + 时间线查询）

### 1.6 配置项

`config/AppConfig.java` 与 `application.yml` 新增：

```yaml
app:
  wecom-app-token: ${WECOM_APP_TOKEN:}
  wecom-app-encoding-aes-key: ${WECOM_APP_ENCODING_AES_KEY:}
  wecom-contact-event-enabled: ${WECOM_CONTACT_EVENT_ENABLED:false}
```

- 两个凭据为空时，`WeComAppEventCodec` **不注册**（用 `@ConditionalOnExpression` 与既有风格一致），端点返回 503，避免空密钥导致的启动失败。
- `AppConfigTest` 同步增补。

### 1.7 阶段 1 测试清单

| 测试 | 覆盖 |
|---|---|
| `WeComAppEventCodecTest` | 验签失败 / 时间窗超界 / AES 失败 / padding 非法 / **receiveid 与 ToUserName 不等**（必须拒绝）/ 非 event 的 MsgType 拒绝 / 6 个 changeType 正常解析 / 字段超长拒绝 |
| `WeComCallbackCodecTest`（既有） | 改造后必须**原样通过**（回归保护） |
| `WeComContactEventServiceTest` | INSERT 提交后 ack / 数据库失败 → retry / 重复 dedupe_key 冲突跳过 / installation 隔离 / 未知 installation → retry / 开关关闭 → retry |
| `WeComContactEventMapperSqlTest` | **真 PG**：installation 外键、`ON CONFLICT DO NOTHING`、完整字段落库、状态约束、时间线查询排序、索引命中；同 authCorpId 不同 suite 的事件不能串读 |
| `WeComContactEventCrashContractTest` | 模拟 INSERT 提交前失败、提交后重启；验证只有已提交或幂等冲突才返回 success，后续动作可从 durable 行恢复 |
| `ArchitectureBoundaryTest`（既有） | 4/4 通过（新代码在 `service.wecom` / `channel.wecom` 白名单内） |
| `WeComAppEventCodecTest` 中的加密用例 | 用**阶段 0.4 拿到的真实事件样本**加密后回放，确保解析真实数据而非自造数据 |

> 加密测试夹具：用与应用级解码器**独立实现**的加密函数生成密文（不要用被测代码自己加密再解密，那是同义反复）。若 `WeComCallbackCipher` 的加密方向未被生产使用，测试里手写一份 PKCS#7 + AES-CBC 加密即可。

### 1.8 阶段 1 验收标准

1. `mvn clean test` 全绿（含既有全部用例）。
2. 门禁 4/4 通过。
3. 真 PG 迁移可正向执行；重复部署时由 Flyway 保证不重复应用，不能把 versioned migration 当作可重复执行脚本。
4. 用手工构造的合法密文 POST `/api/v1/wecom/app-callback`，在真 PG 上完成 INSERT 提交，**响应时间 < 1 秒**，并在并发压测下验证 p95/p99 仍低于平台限制，事件落库。
5. 重复 POST 同一事件，库中只有一行。
6. 数据库不可用、未知 installation、服务未就绪或关闭开关时 POST，均返回 503，不返回成功 ack。
7. 模拟进程在回调处理期间重启，验证已提交事件可查询，未提交事件由企微重试。

**Commit 建议**：`feat(wecom): ingest customer contact events via app-level callback`

---

## 阶段 2 · 前端「客户动态」

目标：事件在界面上可见。

### 2.1 后端只读接口

在 `web/WeComP0Controller.java` 新增：

```text
GET /api/v1/wecom/installations/{authCorpId}/contact-events?since&changeType&limit
```

- `limit` 默认 50、上限 200；`since` 可选。
- 返回 `List<WeComContactEventResponse>`，新增 `dto/response/WeComContactEventResponse.java`。
- 列表查询**必须单次数据库查询完成**，不得逐条回查企微。
- 首期响应不包含 `displayName`，避免一页最多 200 条事件触发外部 API 调用；名称补齐另立批量接口，并定义并发、总耗时和失败预算。
- 当前用户必须通过 `WeComUserBindingService` 绑定到相同 suite_id + authCorpId；首期不增加管理员绕过绑定的路径。

### 2.2 前端

- `frontend/src/api/endpoints.ts`：新增 `listWeComContactEvents`。
- `frontend/src/api/types.ts`：新增 `WeComContactEvent` 类型。
- 新增 `frontend/src/components/wecom/WeComContactEventPanel.tsx`。
- `frontend/src/pages/WeComManagementPage.tsx`：新增「客户动态」Tab（与「客户联系」「客户群」并列）。

界面要求：

- 沿用现有面板的风格（`wecom-panel` class、`WeComProviderView` 降级、`wecomAvatarColor`）。
- 空态文案区别于「客户联系」面板：动态为空时说明「自接入日起无客户关系变化」，**并明确提示不含接入前的历史**（企微不提供历史事件查询）。
- 名称拿不到时显示「未获取昵称」，不伪造。
- 分页/加载更多，不做无限滚动。

### 2.3 阶段 2 测试与验收

- `WeComContactEventPanel.test.tsx`：空态 / 有数据 / 名称缺失降级 / 加载失败。
- 前端全量用例通过（当前基线 357 例，新增后必须全绿）。
- `npm run build` 通过，dist 产物更新。
- 人工验收：真实事件能在页面上看到。

**Commit 建议**：`feat(wecom): show customer contact event feed in wecom console`

---

## 阶段 3 · 关系状态投影（可选，需先有消费方）

只在确认需要「当前是否仍是客户」这一查询时再做。

- 新增 `V82__wecom_contact_relations.sql`。
- 事件消费时按 `provider_created_at` 和稳定 tie-breaker 处理乱序/晚到事件，再 upsert 关系行（`started_at` / `ended_at`）。关系主键至少包含 installation、external_user 和 follow_user，不能把 `del_follow_user` 直接等同于客户流失。
- 前端：客户列表增加「流失」标记与筛选。

**不做的判据**：如果没有任何页面或接口需要「当前关系状态」，就不做——流水表本身已能回答历史问题，投影表是纯冗余。

**Commit 建议**：`feat(wecom): project customer relation state from contact events`

---

## 阶段 4 · 动作连接（可选，需先有产品规则）

### 4.1 新客户自动待办

- `add_external_contact` → 生成 `todo_items` 行。负责人规则必须先定义“已有会话负责人 / 绑定成员 / 未分配队列”的优先级，不得假设新客户一定已有 conversation。
- **必须产品先定规则**：几天未跟进算逾期、是否每人每天汇总、是否去重（同一客户重复添加不重复建待办）。
- 依赖方向：由 `service.wecom` 调用待办服务，**不得**让 `service.todo` 反向依赖 `channel.wecom`（门禁会拦）。

### 4.2 客户流失通知

- `del_external_contact` / `del_follow_user` → 复用 `wecom_user_notifications`（V67）的投递链路通知负责人。
- 复用而非新造，避免第三套通知机制。

**Commit 建议**：`feat(wecom): trigger todo and notification from customer contact events`

---

## 回滚方案

| 层面 | 回滚方式 |
|---|---|
| 功能开关 | `WECOM_CONTACT_EVENT_ENABLED=false`，端点返回 503 让企微重试；已落库事件不删除 |
| 前端 | 撤回 Tab 注册（一个数组元素），不影响后端 |
| 代码 | 按阶段 revert 对应 commit；阶段之间无未完成依赖 |
| 数据库 | 阶段 1 只新增表，**不删表**（保留已采数据）；如需清理，先导出再 drop |

**不回滚的部分**：阶段 0 拿到的真实事件样本与字段核对表，是长期资产。

---

## 明确不做

- 不做消息类事件（`text` / `image` / `voice` / `link` 等）——会话真源仍是 chatdata。
- 不做成员交互事件（`subscribe` / `enter_agent` / `click` / `view`）。
- 不做 OA 审批 / 日程 / 微信客服事件。
- 不自动创建 CRM 联系人（`WeComContactLinkService` 的「永不创建联系人」契约保持）。
- 不做历史事件回溯（企微无此接口）。
- 不做事件的已读/未读、人工补录、管理员重放。
- 首期不做保留期清理，也不添加无消费者的 retention 配置；第二阶段扩展既有 `WeComAuditRetentionService/Scheduler` 覆盖本表，并与配置、批量删除、状态观测同批交付。
