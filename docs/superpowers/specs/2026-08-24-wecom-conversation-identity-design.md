# 企业微信会话与身份资料统一设计

日期：2026-08-24  
状态：已完成方案讨论，待书面设计复核  
适用范围：单企业安装、多个企业成员、成员绑定、成员单聊、外部联系人单聊、企业微信群聊、官方会话展示组件

## 1. 背景与问题

当前实现从存储到投影都假定企业微信会话只能是“企业成员与外部联系人”的一对一消息：

- `WeComChatDataStore` 只接受一个接收者以及 `MEMBER(type=1) <-> EXTERNAL(type=2)` 组合。
- 成员与成员的消息、多人群聊会被计为 `skipped`，但同步游标仍然前进。
- `wecom_chatdata_messages.external_userid` 为非空字段，数据表无法表达成员单聊和群聊。
- CRM `conversations` 强制绑定一个 `contact_identity_id`，无法表达不属于单个联系人的群会话。
- viewer session 以 `contactPointId` 为授权对象，并在同一 token 创建新 session 时删除旧 session。联系人切换中的异步请求会相互淘汰。
- 企业微信绑定响应只返回技术 ID，未返回本人昵称、头像和企业名称。
- 授权响应已经包含 `auth_corp_info`，但当前代码丢弃了 `corp_name`。

上述问题属于数据合同和 owner 设计错误，不能通过继续追加 `external_userid` 分支或前端临时兼容修复。

## 2. 目标与边界

### 2.1 目标

1. 正式支持成员与成员的一对一会话。
2. 正式支持企业微信群聊以及参与者变化。
3. 继续支持成员与外部联系人的一对一会话。
4. 每条上游消息必须成功落库或形成持久化失败记录，不再静默跳过。
5. 普通成员只能查看自己参与的会话；具备独立会话存档审计权限的用户可以受审计地查看其他会话。
6. 联系人切换、单聊与群聊切换时，官方会话展示组件可以可靠销毁并重新挂载。
7. 绑定区域和内部成员资料显示真实昵称、头像和企业名称。
8. 对旧游标已经越过的数据提供可审计、幂等的历史回补。

### 2.2 非目标

1. 不把企业微信内部消息正文长期明文保存到 CRM 普通业务表。
2. 不把群聊伪造成联系人。
3. 不让普通 `admin` 角色自动获得全部内部消息查看权限。
4. 不恢复或新增以 `/cgi-bin/service/get_suite_token` 获取登录、成员资料或 viewer token 的错误链路。
5. 不保留以 `external_userid` 或 `contactPointId` 为核心的并行旧合同。
6. 不承诺恢复企业微信上游已经超过保留期限的数据。

## 3. 架构与唯一 owner

系统分为三个清晰层次：

1. **企业微信源事实层**：拥有参与者、源会话、源消息引用、参与关系和同步失败事实。
2. **CRM 通用投影层**：拥有联系人列表、统一会话列表、消息顺序、未读和用户可见投影。
3. **HTTP/UI adapter 层**：只做权限后的合同映射和展示，不推导参与关系或消息真实性。

`WeComChatDataStore` 的职责调整为事务性保存规范化源事实。`WeComMessageProjector` 只把已经规范化并通过校验的源会话投影到通用模型。前端不得根据 `userid`、`external_userid` 或 `chatid` 自行构造业务会话。

## 4. 企业微信源数据合同

### 4.1 `wecom_parties`

统一表示企业微信消息参与者。

关键字段：

- `id uuid`
- `installation_id uuid`
- `party_type varchar`：`MEMBER` 或 `EXTERNAL`
- `provider_party_id varchar`
- `display_name varchar null`
- `avatar_url text null`
- `profile_status varchar`：`READY`、`DEGRADED`、`PENDING`
- `profile_error_code varchar null`
- `profile_synced_at timestamptz null`
- `created_at/updated_at`

唯一键为 `(installation_id, party_type, provider_party_id)`。技术 ID 是稳定身份键，昵称和头像只是可刷新资料快照。

### 4.2 `wecom_source_conversations`

表示企业微信真实会话。

关键字段：

- `id uuid`
- `installation_id uuid`
- `conversation_kind varchar`：`DIRECT` 或 `GROUP`
- `provider_conversation_key varchar`
- `chat_id varchar null`
- `title varchar null`
- `title_source varchar`：`UPSTREAM`、`DERIVED`、`PENDING`
- `crm_conversation_id uuid`
- `status varchar`
- `first_seen_at/last_seen_at`
- `created_at/updated_at`

群聊使用 `(installation_id, chat_id)` 作为稳定来源键。无 `chatid` 且只有一个接收者的单聊，使用双方 party 稳定键的规范排序生成来源键，消息方向不影响会话身份。

### 4.3 `wecom_source_conversation_participants`

表示源会话参与关系。

关键字段：

- `source_conversation_id uuid`
- `party_id uuid`
- `membership_status varchar`：`OBSERVED`、`LEFT`
- `first_seen_at/last_seen_at`
- `left_at timestamptz null`

主键为 `(source_conversation_id, party_id)`。参与者变化按同步时间更新，不回写或删除历史消息发送者。消息接收者列表只能证明成员在该时点出现；除非上游提供明确退群事实，否则不得因后续消息未出现该成员而推断 `LEFT`。

### 4.4 `wecom_chatdata_messages`

从外部联系人专用引用表升级为通用源消息引用表。

关键字段：

- `id uuid`
- `installation_id uuid`
- `source_conversation_id uuid`
- `sender_party_id uuid`
- `msgid varchar`
- `secret_key text`：继续使用项目凭据保护器加密
- `send_time bigint`
- `msgtype varchar`
- `created_at/updated_at`

幂等键为 `(installation_id, msgid)`。`external_userid`、`userid` 和固定 `direction` 不再是源事实 owner。方向由当前查看者与发送者关系在查询时计算。

### 4.5 `wecom_chatdata_ingest_failures`

保存不能立即规范化的上游消息事实。

关键字段：

- 安装、程序、能力和游标范围
- `msgid` 摘要或受限标识
- 发送者类型、接收者类型和数量
- 失败阶段、结构化错误码、是否可重试
- 尝试次数、首次/最后失败时间、解决时间

错误表不得保存正文、明文 `secretKey` 或其他不必要的敏感数据。

## 5. CRM 通用模型调整

`conversations` 增加：

- `conversation_kind`：`DIRECT` 或 `GROUP`
- `provider_conversation_key`
- `display_name`
- `avatar_url`

`DIRECT` 会话可以关联 `contact_identity_id`。`GROUP` 会话的 `contact_identity_id` 必须为空，名称和参与者来自企业微信源会话。唯一性从单纯的 `(channel_account_id, contact_identity_id)` 调整为以渠道账号和稳定来源会话键为主。

企业微信内部消息和群消息没有全局固定的收发方向。通用 `messages.direction` 增加准确的 `participant` 值，发送者通过源消息的 `sender_party_id` 以及通用 `message_participants` 表达。认证后的查询 adapter 才能相对当前绑定成员投影 `inbound/outbound`；审计查看保持 `participant` 并显示真实发送者，禁止把审计员伪装成收发方。

内部成员投影为 CRM 联系人，其企业微信身份标记为内部成员。外部联系人沿用现有联系人模型。群聊只进入统一会话列表，不生成虚假联系人。

左侧列表返回判别联合：

- `CONTACT`：普通联系人、外部联系人或内部成员，继续进入 `/thread/:contactId`。
- `WECOM_GROUP`：企业微信群聊，进入独立群会话页面。

同一个外部联系人与多个企业成员分别沟通时，源会话保持独立。普通成员只看到与自己相关的会话，审计员可以按内部参与成员区分会话。

## 6. 同步与事务语义

每页消息按以下顺序处理：

1. 解析发送者和 `receiver_list`。
2. 规范化并 upsert `wecom_parties`。
3. 有 `chatid` 时解析为群聊；无 `chatid` 且只有一个接收者时解析为单聊。
4. upsert 源会话和参与关系。
5. 按 `(installation_id, msgid)` 幂等写入源消息引用。
6. 投影到 CRM 通用会话和消息引用。
7. 整页所有消息均已成功保存或已形成持久化失败事实后，才推进增量游标。

同步结果使用明确计数：`stored`、`direct`、`group`、`duplicate`、`failed`。删除语义含糊的 `skipped` 成功口径。

如果失败记录本身无法落库，整页事务回滚，游标不得推进。可重试失败由有次数、并发和时间上界的 worker 处理；永久不支持的数据进入管理端异常列表。

## 7. 消息内容安全边界

CRM 长期保存消息引用和结构化索引，不把企业微信内部消息正文解密后写入普通 `messages.body_text/body_html`。

保存内容包括 `msgid`、受保护的 `secretKey`、发送者、会话、参与者、时间和消息类型。页面通过企业微信官方会话展示组件按权限渲染正文。

正文搜索、AI 摘要或其他内容处理能力不属于本设计。后续如需实现，必须单独定义授权、审计、数据保留和删除合同。

## 8. 身份、昵称、头像与企业名称

### 8.1 企业名称

授权响应的 `auth_corp_info.corp_name` 是企业名称真源。`WeComAuthorizationGateway.PermanentCodeResponse` 和授权 mutation 合同增加 `authCorpName`，并保存到 `wecom_installations.auth_corp_name`。

企业名称提取复用已经发生的授权响应，不为企业名称新增 suite token 请求。现有安装没有企业名称时，在下一次有效授权事件自动回填；回填前显示“企业名称待同步”，并允许管理员手工补录，不强制重新绑定。

### 8.2 成员资料

`WeComDirectoryService` 使用安装对应的代开发应用 token 调用 `/cgi-bin/user/get`，解析成员 `name`、`avatar` 或 `thumb_avatar`。禁止在成员资料链路调用 `/cgi-bin/service/get_suite_token`。

绑定本人资料和会话参与者共用 `wecom_parties` 资料快照。绑定交换或绑定状态刷新取得成员资料后，必须按安装与成员 ID upsert 对应 `MEMBER` party，不能在绑定服务中形成第二份资料 owner。目录同步采用批量发现、按需详情刷新、有限并发、超时和重试上限，避免群成员数量造成无界上游请求。外部联系人资料继续由现有外部联系人服务负责。

### 8.3 绑定响应合同

`GET /api/account/wecom-binding` 和绑定交换响应增加：

```json
{
  "bound": true,
  "userId": "...",
  "authCorpId": "...",
  "wecomUserId": "...",
  "displayName": "张三",
  "avatarUrl": "https://...",
  "corpName": "某某物流有限公司",
  "profileStatus": "READY",
  "profileErrorCode": null,
  "provisioningSource": "BOUND_EXISTING"
}
```

成员资料失败时仍返回 `bound=true`。昵称依次回退 CRM 姓名和成员 ID；头像回退姓名首字；企业名称未回填时返回待同步状态。技术 ID 只在 UI 折叠详情中展示。

## 9. 查看权限与审计

普通 CRM 用户必须绑定企业微信成员身份，并且该成员是源会话的有效参与者，才能查看消息正文。

普通 `admin` 角色不自动拥有内部消息查看权限。跨成员查看必须具备独立的“企业微信会话存档审计”能力。参与成员查看、审计员查看和拒绝查看都记录：操作者、绑定成员、源会话、消息集合摘要、时间、结果和 traceId。

审计权限在服务端 viewer session 创建和读取边界执行，不能只依赖页面隐藏或路由守卫。

## 10. Viewer API 与组件生命周期

viewer session 创建请求改为：

```json
{
  "sourceConversationId": "...",
  "messageIds": ["..."]
}
```

服务端验证源会话、消息归属、参与关系或审计权限。当前以 `contactPointId` 为授权核心的 session 合同被正式替代，不保留双路径。

同一 viewer token 可以拥有有限数量的未消费 session。每个 session 仍然单次消费并短时过期，但创建新 session 不再删除其他尚未读取的 session。服务端同时保留全局、每 token 和每分钟创建上限。

前端切换会话时：

1. 使用 `AbortController` 取消旧 HTTP 请求。
2. 使用递增请求代次拒绝迟到响应。
3. 以 `sourceConversationId` 作为 SDK frame registry key 的核心部分。
4. 离开会话时明确执行 `dispose`。
5. session 过期只自动重建一次，之后显示结构化错误和重试按钮。

组件不应出现空白失败状态。无权限、session 失效、SDK 初始化失败、frame 挂载超时都必须显示可理解的错误和重试入口。

## 11. 历史迁移与回补

### 11.1 旧数据迁移

1. 创建新源表并扩展通用会话模型。
2. 将每条现有外部联系人消息转换为 `MEMBER + EXTERNAL + DIRECT` 源会话。
3. 对账旧表和新表的行数、`msgid` 集合、时间范围和密钥引用摘要。
4. 任何不一致均阻止发布。
5. 新代码切换后移除旧字段 owner 和旧查询路径，不双写。

旧迁移历史文件保持只读。使用新的 Flyway migration 完成 schema 演进和数据转换。

### 11.2 历史回补

历史回补使用独立游标，不修改在线增量游标：

1. 从空游标执行 dry-run，报告上游可重放数量、成员单聊、群聊、外部联系人会话、重复和失败数量。
2. 用户确认后正式回补。
3. 回补与在线增量同步共用规范化写入服务和 `(installation_id, msgid)` 幂等键。
4. 回补完成后输出恢复范围、永久缺口、失败明细和操作者审计。

企业微信上游已经过期的数据记录为明确缺口，不伪造消息或成功状态。

## 12. 错误与降级

- 无查看权限：返回结构化无权限错误，UI 显示“无权查看此企业微信会话”。
- session 失效：自动重建一次，仍失败则显示重试。
- SDK/frame 失败：返回或展示阶段、结构化错误码，不留空白容器。
- 群名不可得：根据已知成员昵称生成“张三、李四等 5 人”，并标记来源为 `DERIVED`。
- 成员资料不可得：显示成员 ID，消息同步和引用保存继续执行。
- 企业名称不可得：绑定保持有效，显示“企业名称待同步”。
- 未知参与者类型或异常接收者结构：写入 ingest failure；错误事实保存失败时回滚当前页事务，游标不得推进。

## 13. 测试与验收门禁

### 13.1 后端

- 单元测试：成员单聊、外部联系人单聊、内部群聊、含外部联系人的群聊、未知参与者、重复消息和群成员变化。
- 事务测试：成功落库或失败记录持久化前，游标不能推进。
- 权限测试：参与成员、非参与成员、普通管理员、会话存档审计员。
- viewer 测试：同 token 多个有限未消费 session、单次消费、过期、并发和速率限制。
- 身份测试：授权保存企业名称、成员昵称头像、资料失败降级、缓存刷新和代开发 token 链路。
- 迁移测试：旧消息零丢失、零重复、无孤儿参与者或会话、密钥引用仍可解密。
- 回补测试：dry-run 不写业务数据、正式回补幂等、在线增量与回补并发不重复。

### 13.2 前端

- 绑定面板显示头像、昵称和企业名称；资料降级不显示未绑定。
- 统一列表正确区分联系人和群聊。
- A -> B -> A、快速连续切换、迟到响应、单聊与群聊互切。
- frame 销毁、加载、无权限、失败、超时和重试状态。
- 桌面与移动端真实浏览器渲染，确保组件铺满时间轴区域且无重叠、空白或滚动位置异常。

### 13.3 发布

- 执行专项单元与集成测试。
- 执行后端全量测试和 Jar 构建。
- 执行前端测试、类型检查和生产构建。
- 校验 Jar 和前端压缩包 SHA-256。
- 部署后验证数据库迁移、绑定资料、同步统计、联系人切换、群聊切换和审计日志。

## 14. 实施阶段与停止条件

1. **Schema 与规范化合同**：迁移和纯领域解析测试通过后停止评审。
2. **同步与投影**：四类会话、失败事实和游标事务测试通过后停止评审。
3. **权限与 viewer session**：服务端权限和并发 session 测试通过后停止评审。
4. **身份资料**：企业名称、昵称、头像和降级测试通过后停止评审。
5. **前端列表与组件切换**：浏览器完成 A -> B -> A 和群聊验收后停止评审。
6. **历史回补与发布**：dry-run 报告通过用户确认，完成全量回归和构建后交付。

任何阶段出现数据对账不一致、游标提前推进、普通管理员越权、旧 token 链路回归或 UI 空白，均为阻断问题，不得进入下一阶段。
