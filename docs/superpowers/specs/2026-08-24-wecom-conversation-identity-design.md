# 企业微信会话与身份资料统一设计

日期：2026-08-24  
状态：实现中真源（2026-08-24 复核）  
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

### 3.1 代开发凭证的唯一主线

本项目的企业微信应用类型固定为**服务商代开发应用**。官方文档 97163 明确：代开发模板授权成功后，`/cgi-bin/service/v2/get_permanent_code` 返回的 `permanent_code` 就是该企业安装实例的代开发应用 `secret`；官方文档 97164 明确：运行时 access token 的获取方式与企业自建应用一致，必须调用：

```text
GET /cgi-bin/gettoken?corpid={authCorpId}&corpsecret={permanentCode}
```

凭证只允许形成以下单向链路：`dk...` 代开发模板 + `suite_secret` + `suite_ticket` -> `suite_access_token`（仅模板授权阶段） -> `auth_code` 换 `permanent_code`（该企业安装实例的应用 secret） -> `authCorpId + permanent_code` 换安装实例 `access_token` -> 登录、通讯录、客户联系、JSAPI ticket、数据与智能专区运行时 API。

`suite_access_token` 不得进入企业运行时业务调用。`permanent_code` 不得被当作第三方应用永久授权码继续调用 `/cgi-bin/service/get_corp_token` 一类第三方标准应用链路。运行时 token 的唯一 owner 是 `WeComAccessTokenService`，缓存键必须至少包含安装 ID 和安装版本；secret 重置、重新授权或安装版本变化必须使旧缓存失效。

### 3.2 代开发 API 凭证白名单

本表是本设计允许使用的企业微信官方接口。文档编号均来自企业微信开发者中心的“服务商代开发”文档，或其权限表明确列出“代开发应用”。表外接口不得直接进入实现。

| 用途 | 官方文档 | 官方接口或 SDK 能力 | 凭证与权限 | 当前调用点 / 实施结论 |
| --- | --- | --- | --- | --- |
| 获取代开发模板凭证 | 97162 | `POST /cgi-bin/service/get_suite_token` | `dk...` 模板 ID、模板 secret、最新 `suite_ticket` | 仅允许 `WeComAuthorizationGateway.suiteAccessToken` 在模板授权阶段使用 |
| 授权码换安装 secret | 97163 | `POST /cgi-bin/service/v2/get_permanent_code` | `suite_access_token` + 一次性 `auth_code`；返回的 `permanent_code` 即代开发应用 secret | 允许 `WeComAuthorizationGateway.getPermanentCode` 使用 |
| 查询企业授权信息 | 100795 | `POST /cgi-bin/service/v2/get_auth_info` | `suite_access_token` + `auth_corpid` + `permanent_code` | 仅允许授权安装、权限核验和企业资料刷新使用；需核验 `is_customized_app=true` |
| 获取安装实例 token | 97164 | `GET /cgi-bin/gettoken` | `authCorpId` + 该安装的 `permanent_code`；服务端缓存，禁止返回前端 | 所有运行时 API 的唯一 token 来源：`WeComAccessTokenService` |
| 获取登录成员身份 | 96442 | `GET /cgi-bin/auth/getuserinfo` | 安装实例 token + 5 分钟内一次性 code；可信域名必须匹配 | 允许登录和绑定交换使用 |
| 获取本人授权敏感资料 | 96443 | `POST /cgi-bin/auth/getuserdetail` | 安装实例 token + `user_ticket`；需要 `snsapi_privateinfo`、管理员选择字段且成员确认 | 用于绑定本人头像等敏感字段；该接口不返回姓名 |
| 批量发现成员 | 98980、96274、96259 | `GET /cgi-bin/department/list`、`GET /cgi-bin/user/simplelist` | 安装实例 token；基础部门管理权限只能取得可见范围内的部门 ID；姓名需企业确认对应自定义权限 | 先枚举可见部门 ID，再递归发现成员；这是 `/cgi-bin/user/list_id` 的正式替代链路，禁止假定总能返回真实姓名或部门名称 |
| 读取成员资料 | 96255 | `GET /cgi-bin/user/get` | 安装实例 token；应用须有成员查看权限；姓名需管理员授权，头像还需该成员 OAuth 授权 | 允许按需刷新；不得把它设计成所有成员头像的无条件来源 |
| 应用消息与应用群聊 | 97165、98980 | `POST /cgi-bin/message/send`、`POST /cgi-bin/appchat/create`、`GET /cgi-bin/appchat/get`、`POST /cgi-bin/appchat/update`、`POST /cgi-bin/appchat/send` | 安装实例 token；代开发应用与自建应用接口调用方式大部分一致；基础“消息推送”权限；应用群聊仍受应用自身可见范围和官方群聊约束 | 允许现有 `WeComSendService` 与 `WeComAppChatService` 使用；具体接口字段以企业微信应用群聊文档为准，必须保留最小实机验收，不得把权限表视为已成功调用的替代证据 |
| 部门与标签管理 | 98980、90208、90210 | `GET /cgi-bin/department/list`、`GET /cgi-bin/tag/list`、`GET /cgi-bin/tag/get` | 安装实例 token；基础部门管理和标签管理权限；部门名称、成员姓名等敏感字段仍受自定义权限影响 | 允许 `WeComDirectoryService` 使用；部门枚举只承诺 ID，标签成员姓名按实际返回处理 |
| 获取客户列表与详情 | 96314、96315、96316 | `/cgi-bin/externalcontact/list`、`get`、`batch/get_by_user` | 安装实例 token；代开发应用须有“客户基础信息”权限且成员在可见范围 | 允许外部联系人资料同步；官方明确代开发应用不可通过详情接口获取客户头像 |
| 获取客户群列表与详情 | 98980、96337、96338 | `POST /cgi-bin/externalcontact/groupchat/list`、`POST /cgi-bin/externalcontact/groupchat/get` | 安装实例 token；企业须确认“企业客户权限 -> 客户基础信息”，群主须在应用可见范围 | 允许 `WeComExternalContactService.groupList/groupGet` 获取客户群；该接口不用于企业内部群，内部群仍以专区 `get_group_chat` 为唯一来源 |
| 修改客户备注 | 98980、92115 | `POST /cgi-bin/externalcontact/remark` | 安装实例 token；企业确认“企业客户权限 -> 客户基础信息”；操作成员必须在应用可见范围 | 允许 `WeComExternalContactService.remark` 使用；失败必须记录上游 `errcode` 和审计 traceId |
| 设置会话存档公钥 | 99845 | `POST /cgi-bin/chatdata/set_public_key` | 安装实例 token；需“数据与智能专区权限” | 允许 `WeComChatDataPublicKeyRegistrar` 使用 |
| 获取存档授权成员 | 99846 | `POST /cgi-bin/chatdata/get_auth_user_list` | 安装实例 token；需“数据与智能专区权限” | 用于同步边界和权限对账，不替代 CRM 查看权限校验 |
| 调用专区程序 | 99811 | `POST /cgi-bin/chatdata/sync_call_program` | 安装实例 token + 已关联的 `program_id/ability_id`；需“数据与智能专区权限” | 允许 `WeComChatDataGateway` 使用 |
| 拉取会话记录 | 100023 | 专区 SDK `sync_msg` | 只能在已关联专区程序中调用；发送者或接收者须在会话内容授权范围 | 当前专区能力的核心输入；空游标只从最近 5 天最早消息开始，不是无限历史全量 |
| 获取内部群信息 | 100025 | 专区 SDK `get_group_chat` | 只能在专区程序中调用；内部群至少一名员工在授权范围；需“数据与智能专区权限” | 群主、成员和入群时间的正式来源；不能由 `receiver_list` 猜测完整成员关系 |
| 获取 JSAPI ticket | 90539 | `GET /cgi-bin/get_jsapi_ticket`、`GET /cgi-bin/ticket/get?type=agent_config` | 必须使用同一安装实例 token；服务端缓存并签名 | 只保留接收 `ResolvedInstallation` 的 gateway 方法；删除按全局 `WECOM_CORP_ID/WECOM_SECRET` 取 token 的无安装入口 |
| 初始化并展示会话与资料 | 100049、94325 | `ww.register`、`ww.initOpenData`、`ww.createOpenDataFrameFactory`、`ww-open-message`、`ww-open-data` | `corpId` 和授权后 `agentId` 必须来自同一安装；agentConfig 签名正常；企业授权“使用会话展示组件” | 使用 `@wecom/jssdk` 2.3.4；组件必须位于 top frame；不得混用登录套件或其他应用 agentId |

明确禁止进入本设计的路径：

- `/cgi-bin/service/get_suite_token` 用于登录、发消息、通讯录、客户联系、viewer、JSAPI ticket 或 ChatData 运行时调用。
- `/cgi-bin/service/get_corp_token` 等第三方标准应用的企业凭证链路。
- 使用普通自建应用 secret、`WECOM_LOGIN_SUITE_SECRET` 或未绑定安装版本的全局 `WECOM_SECRET` 作为运行时凭证回退。
- `/cgi-bin/user/list_id`。官方文档 96269 明确该接口只支持“通讯录同步 secret”，不属于本代开发应用 token 主线；成员枚举必须使用 `/cgi-bin/department/list`、`/cgi-bin/user/simplelist`，资料刷新使用 `/cgi-bin/user/get`。
- 为了兼容旧配置而并存 suite token、普通自建应用 token 和代开发安装 token 三条运行时路径。

### 3.3 接口准入门禁

新增或变更企业微信调用前，必须同时满足：

1. 在企业微信官方文档中明确处于“服务商代开发”入口，或权限表明确列出“代开发应用”。
2. 记录官方文档编号、精确请求路径、应用类型、凭证类型和权限名称。
3. 企业运行时接口只能从 `ResolvedInstallation -> WeComAccessTokenService` 取得 token。
4. 契约测试必须断言请求路径和 token provider；静态门禁扫描所有 `/cgi-bin/` 字面量并与本白名单对账。
5. 实机以一个有效安装完成最小调用，记录 `errcode`、traceId 和权限结果，但不记录 token、secret、ticket 或明文密钥。

任何一项不满足即停止实施，不通过增加 fallback 或换用其他应用类型绕过。

### 3.4 当前源码路径审计

截至本设计复核，源码中出现但尚未被本白名单准入的路径必须视为阻断项，不得因测试环境曾返回成功而默认属于代开发 API：

- `/cgi-bin/user/list_id`：官方文档 96269 明确“仅支持通过通讯录同步 secret”，禁止用代开发安装实例 token 替代；必须使用 3.2 的部门枚举、成员发现和按需详情组合。
- `RestWeComViewerHttpGateway` 中不带 `ResolvedInstallation` 的全局 `corpId/secret` ticket 方法，以及任何抛出 `WECOM_LOGIN_SUITE_NOT_CONFIGURED` 的旧登录方法：必须删除或改为安装实例 token 后，才能通过发布门禁。

该审计不是兼容清单。未准入路径要么被移除，要么补齐官方代开发证据并加入本表；禁止保留第三条隐式凭证链路。

### 3.5 历史目录与当前真源冲突

`demo/message-center-demo/API-CATALOG.md` 仍保留旧的接口说明。它不是当前准入真源；本设计已为 appchat、应用消息、部门、标签、客户群和客户备注补齐官方文档编号、权限名称和安装实例 token 合同，历史目录仍只能作为实现索引。

发布前必须完成以下静态对账：

1. 扫描 `demo/message-center-spring/backend/src/main/java` 下全部 `/cgi-bin/` 字面量。
2. 每条路径必须能在 3.2 白名单中找到精确匹配；仅出现在 3.4 阻断清单中的路径不得由生产路由调用。
3. 若历史目录、测试名称或接口注释与 3.2/3.4 冲突，以本设计和企业微信官方文档为准；同步实现前先修正文档漂移。
4. 对白名单中已准入但尚未完成实机验收的 appchat、`message/send`、标签、外部联系人备注接口，只能标记为“代码准入，待实机验收”，不得伪称企业实例已经成功调用。

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
- `profile_status varchar`：`READY`、`PARTIAL`、`DEGRADED`、`PENDING`
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

`WeComDirectoryService` 使用安装对应的代开发应用 token 调用 `/cgi-bin/user/get`，解析成员 `name`；头像只有在官方允许的授权条件满足时才保存。官方文档 96255 明确：新建代开发应用的头像、手机、邮箱等敏感字段不能由该接口无条件返回，头像需要管理员配置并由成员 OAuth 授权；绑定本人资料必须使用 96442 的 `user_ticket` 配合 96443 `/cgi-bin/auth/getuserdetail`。禁止在成员资料链路调用 `/cgi-bin/service/get_suite_token`。

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

成员资料失败时仍返回 `bound=true`。昵称依次回退 CRM 姓名和成员 ID；头像回退姓名首字；企业名称未回填时返回待同步状态。资料状态必须区分 `READY`（真实姓名和头像均可用）、`PARTIAL`（姓名可用但头像不可用）和 `DEGRADED`。技术 ID 只在 UI 折叠详情中展示。会话展示组件 100049 的 `ww-open-data` 仍可在授权环境内展示 `userName`、`userAvatar` 和 `chatName`，但不能把组件展示结果伪造写回服务端资料快照。

## 9. 查看权限与审计

### 9.1 实现复核结论

- 当前数据库迁移以 `V23__wecom_conversation_identity.sql` 和后续机器人类型迁移为实现真源，参与者类型使用 `EMPLOYEE`、`EXTERNAL_CONTACT`、`ROBOT`、`GROUP`；旧文档中的 `MEMBER`/`EXTERNAL` 仅为概念称呼，不得作为 SQL 值。`ROBOT` 只允许作为 ChatData 群参与者，不得投影为 CRM 联系人。
- 成员发现入口已经固定为 `department/list -> user/simplelist -> user/get`，并提供管理员触发的资料同步入口；生产代码不得重新引入 `user/list_id`。
- 企业微信 JSAPI ticket 只允许接收 `ResolvedInstallation` 的 gateway 方法；无安装实例的旧 ticket/login 接口不再作为运行时回退。
- 受限群消息先写入源会话和失败事实，再决定是否推进游标；群会话不创建 `contact_identity_id`。
- 历史回补使用独立游标键和五天窗口，不能复用在线增量游标。

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

历史回补使用独立游标，不修改在线增量游标。官方文档 100023 明确：`sync_msg` 只可获取 5 天内的会话记录；空游标只代表从该保留窗口内最早消息开始，不能承诺安装前或超过 5 天的无限历史全量：

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
- 未知参与者类型或异常接收者结构：写入 ingest failure；ChatData `sender.type=3` 已规范化为 `ROBOT`，不再作为未知类型丢弃。错误事实保存失败时回滚当前页事务，游标不得推进。

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
