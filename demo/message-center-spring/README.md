# Spring 消息中心

`demo/message-center-spring` 是企业微信功能的唯一运行面。Spring 后端直接负责授权回调、扫码登录、已有账号绑定、会话专区同步、统一时间线投影和官方 OpenDataFrame 展示；运行时不启动、不代理、也不调用旧的 `demo/message-center-demo`。

当前运行面只支持一个 active 企业微信授权企业。企业内可以有多个成员，每个成员可以关联各自的 WhatsApp Business 渠道账号；这些账号通过联系人身份的 `identityScope` 精确关联，不代表多个企业微信租户。

## 本地启动

先启动 PostgreSQL、MinIO，以及需要转录时使用的 FunASR，然后分别启动后端和前端：

```bash
cd demo/message-center-spring/backend
mvn spring-boot:run
```

```bash
cd demo/message-center-spring/frontend
npm install
npm run dev
```

默认地址：

- 前端：`http://127.0.0.1:5173`
- 后端：`http://127.0.0.1:8099`
- 前端开发代理：`/api` 转发至后端 `8099`

## WhatsApp 共享模板

同一 CAMS `custSpaceId` 的 WhatsApp 模板存储在系统共享目录中，`message_templates` 是唯一真源；模板不按用户或渠道账号复制。所有已登录用户可浏览、同步和使用允许发送的共享模板，也可直接向 CAMS 申请新模板。渠道账号、联系人、消息和凭证仍按用户隔离。

普通用户对已有模板的修改、发送权限、停用/删除及媒体绑定只会创建内部变更申请。管理员可批准、拒绝或重试申请，并可用自己的有效 WhatsApp 账号直接执行变更。批准后的申请始终使用申请时明确的账号；该账号失效或 scope 不匹配时操作以结构化错误失败，绝不替换为其他账号。

共享模板 API：

```text
GET  /api/v1/whatsapp/templates
GET  /api/v1/whatsapp/templates/{templateId}
POST /api/v1/whatsapp/templates/applications
POST /api/v1/whatsapp/templates/sync
POST /api/v1/whatsapp/templates/{templateId}/change-requests
GET  /api/v1/whatsapp/template-change-requests/mine
```

管理员审批 API：

```text
GET  /api/v1/admin/whatsapp/template-change-requests
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/approve
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/reject
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/retry
```

旧的账号级 `/api/v1/channel-accounts/{accountId}/whatsapp/templates/**` 管理入口已退出，不提供兼容路径。应用启动时会回填账号 scope、归并历史副本并以官方详情对账；任何有效 WhatsApp 账号出现不同 `custSpaceId` 时，共享模板门禁保持关闭。

## 会话列表个性化

联系人和企业微信群共用左侧会话列表。当前账号可以在列表项上点击右键执行“置顶/取消置顶”或“删除”；这里的删除只写入当前用户的会话偏好，不删除联系人、群聊、消息或 Topic。隐藏项仍可通过搜索找到，隐藏后新入库的消息也会让该项自动重新出现。

拖动列表项到另一项上方或下方的间隙会调整当前账号的列表顺序，拖到联系人中心区域才会合并联系人；企业微信群不能参与合并。用户级状态由 `conversation_preferences` 统一保存，对应接口为：

```text
POST /api/conversations/preferences/pin
POST /api/conversations/preferences/delete
POST /api/conversations/preferences/order
```

排序接口只接收相邻放置意图，不接收客户端生成的整页排名：

```json
{
  "sourceType": "CONTACT",
  "sourceId": "00000000-0000-0000-0000-000000000001",
  "targetType": "WECOM_GROUP",
  "targetId": "00000000-0000-0000-0000-000000000002",
  "placement": "BEFORE"
}
```

`placement` 仅支持 `BEFORE` 或 `AFTER`。后端在当前用户的事务锁内读取完整可排序序列并统一生成排名，禁止跨置顶与非置顶区域排序。搜索状态下前端禁用拖动，避免用局部搜索结果改变完整列表顺序。

macOS 本机只启动企业微信客户端、不指定联系人或企业时，运行：

```bash
./demo/message-center-spring/scripts/start-wecom-client.sh
```

## 联系人备注与 CRM 标签

联系人列表和详情接口返回 `remark` 与 `tags`。前端展示名称按 `remark`、`displayName`、`未命名` 的顺序选择；不会修改
`contacts.display_name` 的原始语义。CRM 标签独立存储于 `contact_tags` / `contact_taggings`，可通过以下接口整体替换：

```text
GET /api/contacts
GET /api/contacts/{contactId}
PUT /api/contacts/{contactId}/tags
```

`PUT` 请求体为 `{ "tags": [{ "name": "重点客户", "color": "blue" }] }`。传空数组会清空联系人标签；标签名称大小写不敏感去重，
并在服务端按当前用户的联系人访问权限校验。

## 邮件同步配置

开启 `APP_EMAIL_SYNC_ENABLED=true` 后，邮件同步至少需要 `IMAP_USER` 和 `IMAP_PASSWORD`。139 邮箱在
`IMAP_HOST`、`IMAP_PORT` 或 `MAIL_PROVIDER` 缺失、为空时分别使用 `imap.139.com`、`993` 和 `139`；也可用
`APP_IMAP_HOST`、`APP_IMAP_PORT`、`APP_MAIL_PROVIDER` 显式覆盖。密码只通过环境变量或后端 `.env` 注入。

同步会读取收件箱和发件箱。`APP_SENT_FOLDER`（或渠道账号中的 `sentFolder`）优先使用配置值；配置目录不存在或为空时，会依次尝试 `Sent`、`已发送`、`Sent Items`、`INBOX.Sent` 和 `INBOX/已发送`。139 邮箱的 OpenSSL IMAP fallback 会对中文目录名使用 IMAP Modified UTF-7 编码。应用自身通过 SMTP 发出的邮件在发送时已经写入数据库，不依赖发件箱再次同步。

## AI Topic 配置

联系人右侧的 Topic 时间轴会在首次打开联系人时异步整理历史沟通，后续新消息触发增量关联。AI 仅读取
ChatApp、邮件和电话转录/备注；企业微信不会进入 AI 输入。后端使用通用 OpenAI-compatible 接口，配置通过
环境变量注入：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `AI_BASE_URL` | 空 | provider 根地址 |
| `AI_API_KEY` | 空 | 服务端 API Key，不写入前端或日志 |
| `AI_MODEL` | `gpt-4o-mini` | 模型名称 |
| `AI_TIMEOUT_SECONDS` | `30` | 单次请求超时 |
| `AI_MAX_INPUT_RECORDS` | `200` | 单批最大来源数 |
| `AI_MAX_INPUT_BYTES` | `262144` | 单批最大输入字节数 |
| `AI_TOPIC_MATCH_THRESHOLD` | `0.65` | 增量 Topic 关联阈值 |
| `AI_TOPIC_WORKER_CONCURRENCY` | `1` | 后台 worker 并发数 |
| `AI_TOPIC_MAX_ATTEMPTS` | `3` | 最大尝试次数 |
| `AI_TOPIC_LEASE_SECONDS` | `120` | 任务租约时长 |
| `AI_TOPIC_POLL_INTERVAL_SECONDS` | `30` | 任务轮询间隔 |
| `AI_TOPIC_AUDIT_MAX_REQUEST_BYTES` | `262144` | AI 请求审计快照上限；超限保存带 SHA-256 的截断快照 |
| `AI_TOPIC_AUDIT_MAX_RESPONSE_BYTES` | `524288` | AI 原始响应审计上限；超限保存截断正文 |

未配置 `AI_BASE_URL` 时，Topic 任务会以 `AI_NOT_CONFIGURED` 失败，已有消息收发和时间线不受影响。

`AI_PROVIDER_UNAVAILABLE` 表示请求已进入后台 worker，但 provider 网络调用不可用。服务日志中的
`event=ai_topic_generation_failed` 会输出不含 API Key、请求正文和响应正文的 `diagnostic`；任务表的
`last_error_message` 保存相同分类：`HTTP_5xx`、`DNS_ERROR`、`TIMEOUT`、`CONNECT_ERROR`、
`TLS_ERROR` 或 `CLIENT_ERROR`。可用以下 SQL 查看最近任务：

```sql
select status, last_error_code, last_error_message, attempt_count, updated_at
from ai_topic_generation_jobs
order by updated_at desc
limit 20;
```

每次 AI 尝试还会独立写入 `ai_topic_generation_attempts`，即使 Topic 事务回滚也保留。记录中的
`stage` 可区分 `PROVIDER_CALL`、`RESPONSE_PARSE`、`RESPONSE_VALIDATE` 和 `BUSINESS_APPLY`；
`raw_response_body` 保存 provider 原始响应，`request_payload` 保存脱敏请求快照，认证头和 API Key 永不保存。
联系人级诊断接口为 `GET /api/v1/contacts/{contactId}/topics/attempts?limit=20`，也可直接查询：

```sql
select attempt_number, stage, status, response_status, error_code, error_diagnostic,
       request_truncated, response_truncated, duration_ms, created_at, completed_at
from ai_topic_generation_attempts
where contact_id = '<ContactId>'
order by created_at desc
limit 20;
```

发布前端时必须从当前源码重新生成 `frontend/dist`，服务器需要替换整个静态目录；只更新后端 Jar 或继续使用旧的 `frontend.zip` 不会出现 Topic 界面。构建后可用下面的命令确认产物已包含 Topic 代码：

```bash
cd demo/message-center-spring/frontend
npm run build
rg -l "Topic 时间轴|contact-topics" dist/assets
```

将新的 `dist/` 全量上传到 Web 服务器静态根目录后，执行强制刷新（或清理 CDN/反向代理缓存）。

## 企业微信 P0 管理工作台

管理员从“渠道设置”页面企业微信行点击“管理”，或直接打开 `/settings/wecom`。页面使用服务端返回的
非敏感安装摘要选择授权企业，并提供四个标签页：应用群聊、客户联系、客户群和通讯录。

工作台只调用 Spring `/api/v1/wecom/...` 路由，不直连企业微信。所有入口要求 `ROLE_ADMIN`；普通用户即使
手动输入路由也会收到 403。应用群聊按 `chatId` 操作，客户和客户群遵循企业微信 cursor 分页，通讯录仅提供
成员、部门、标签读取。

P0 管理路由包括：

```text
GET   /api/v1/wecom/installations
POST  /api/v1/wecom/installations/{authCorpId}/app-chats
GET   /api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}
PATCH /api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}
POST  /api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}/messages
GET   /api/v1/wecom/installations/{authCorpId}/external-contacts
GET   /api/v1/wecom/installations/{authCorpId}/external-contacts/{externalUserId}
POST  /api/v1/wecom/installations/{authCorpId}/external-contacts:batchGet
PATCH /api/v1/wecom/installations/{authCorpId}/external-contacts/{externalUserId}/remark
POST  /api/v1/wecom/installations/{authCorpId}/customer-groups:search
GET   /api/v1/wecom/installations/{authCorpId}/customer-groups/{chatId}
GET   /api/v1/wecom/installations/{authCorpId}/directory/members/{userId}
GET   /api/v1/wecom/installations/{authCorpId}/directory/members
GET   /api/v1/wecom/installations/{authCorpId}/directory/departments
GET   /api/v1/wecom/installations/{authCorpId}/directory/tags
GET   /api/v1/wecom/installations/{authCorpId}/directory/tags/{tagId}
```

请求体上限为 256 KiB，上游响应上限为 2 MiB。调用前后写入脱敏 `wecom_api_audit`，并与授权、viewer
审计共用分批清理预算；审计记录不保存 token、永久授权码、客户正文或手机号。

## 企业微信配置

敏感值只允许通过环境变量或后端 `.env` 注入，不要写入 YAML、前端代码、普通日志或打包产物。
`WECOM_ENABLED=false` 会整体停用企业微信模块，相关 Repository、worker、Topic 桥接和 Web 接口均不参与 Spring 启动。

授权回调必需：

| 环境变量 | 用途 |
| --- | --- |
| `WECOM_SUITE_ID` | 服务商应用 Suite ID |
| `WECOM_SUITE_SECRET` | 服务商应用 Suite Secret |
| `WECOM_TOKEN` | 回调签名 Token |
| `WECOM_ENCODING_AES_KEY` | 回调消息 EncodingAESKey |
| `WECOM_CALLBACK_RECEIVE_ID` | 回调 ReceiveId；按企业微信应用类型配置 |

扫码登录和账号绑定必需：

| 环境变量 | 用途 |
| --- | --- |
| `WECOM_LOGIN_AUTH_CORP_ID` | 登录安装对应的授权企业 ID |
| `WECOM_LOGIN_REDIRECT_URI` | 登录回调页面，例如 `https://crm.example.com/login` |
| `WECOM_ALLOWED_JSAPI_ORIGINS` | 允许签发 JS-SDK 配置的 HTTPS Origin；多个值用逗号分隔 |

企业微信扫码使用官方 `CorpApp` 组件，`appid` 和 `agentid` 均由服务端从
`WECOM_LOGIN_AUTH_CORP_ID` 对应的有效授权安装记录取得。该记录必须已有
`agentId` 与加密保存的 `permanent_code`；后端使用安装记录中的
`authCorpId + permanent_code` 调用 `/cgi-bin/gettoken`，再调用 `/cgi-bin/auth/getuserinfo` 获取成员
`userid`。不要配置第二套登录 Suite，也不要把 `permanent_code`、access token 或
扫码 code 写入环境变量、前端或日志。

会话专区和 OpenDataFrame 必需：

| 环境变量 | 用途 |
| --- | --- |
| `WECOM_CHATDATA_PROGRAM_ID` | 会话专区程序 ID |
| `WECOM_CHATDATA_ABILITY_ID` | 同步能力 ID，默认 `conversation_viewer_sync` |
| `WECOM_CHATDATA_PRIVATE_KEY_FILE` | RSA 私钥文件的服务端绝对路径 |
| `WECOM_CHATDATA_PUBLIC_KEY_VERSION` | 已注册公钥版本，默认 `1` |
| `WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER` | 是否在启动时自动注册公钥 |
| `WECOM_CHATDATA_AUTO_SYNC_ENABLED` | 是否自动轮询会话专区 |
| `WECOM_CHATDATA_AUTO_SYNC_INTERVAL_SECONDS` | 自动同步间隔，默认 `60` 秒 |

消息级摘要默认关闭；需要时配置 `WECOM_MESSAGE_SUMMARY_ENABLED=true`，能力 ID 通过 `WECOM_MESSAGE_SUMMARY_ABILITY_ID` 覆盖。开启后，新企业微信消息入库事务会幂等写入 `PENDING` 摘要任务，后台 worker 按单条消息异步提交 `conversation_daily_summary`，保存摘要、官方原始响应和校验阶段。8107 不保存消息正文，任务记录不随消息保留策略删除；摘要未完成时对应消息不会被清理。配置如下：

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `WECOM_MESSAGE_SUMMARY_ENABLED` | `false` | 开启每条消息独立摘要 worker 与补偿任务 |
| `WECOM_MESSAGE_SUMMARY_ABILITY_ID` | `conversation_daily_summary` | 企业微信数据与智能专区能力 ID |
| `WECOM_MESSAGE_SUMMARY_POLL_INTERVAL_SECONDS` | `1` | worker 调度/官方轮询间隔 |
| `WECOM_MESSAGE_SUMMARY_BATCH_SIZE` | `20` | 单轮最多领取任务 |
| `WECOM_MESSAGE_SUMMARY_MAX_CONCURRENCY` | `2` | 单实例并发上限 |
| `WECOM_MESSAGE_SUMMARY_MAX_TRANSIENT_ATTEMPTS` | `20` | 临时错误最大重试次数 |
| `WECOM_MESSAGE_SUMMARY_MAX_BACKOFF_SECONDS` | `900` | 重试退避上限 |
| `WECOM_MESSAGE_SUMMARY_BACKFILL_BATCH_SIZE` | `200` | 历史消息每轮补偿上限 |

查询单条消息诊断：

```bash
curl -H 'Authorization: Bearer <crm-token>' \
  'http://localhost:8107/api/v1/wecom/message-summaries/<msgid>'
```

返回的 `validationStage` 用于区分消息引用、官方错误、响应数据和字段校验失败；`rawRequestJson` 只包含操作类型和 `msgid`，不会包含 `secret_key`、access token、私钥或消息正文。分页检索使用同一路由的 `conversationId`、`status`、`from`、`to`、`page` 和 `size` 参数，`size` 最大为 100。

## 企业微信回调地址

GET 验证和 POST 事件均由同一个 Controller 处理，以下三个路径语义一致：

- `/api/wecom/callback`
- `/api/v1/wecom/authorization/callback`
- `/hook_path`

新部署优先使用 `/api/v1/wecom/authorization/callback`。`/hook_path` 用于企业微信审核或既有配置暂时无法修改的环境。发送接口仍是需要 CRM 登录的 `/api/wecom/send`，不会随 callback alias 一起公开。

## 登录与绑定

- 登录页保留账号密码和企业微信官方扫码两个入口。
- 首次企业微信扫码会自动创建 CRM 账号，并只授予现有 `agent` 角色。
- 已有 CRM 账号从右上角“账号”抽屉发起扫码绑定；一个 CRM 账号和一个企业微信身份均只能绑定一次。
- viewer token 只保存在 React 内存中；退出登录、刷新页面或过期后由绑定关系重新 bootstrap。
- 企业微信原文只交给官方 `ww-open-message` 展示，不保存到 React、localStorage 或普通消息正文。
- OpenDataFrame 创建后 15 秒仍未收到 `handleMounted` 时，页面终止空白加载并显示脱敏的失败阶段与重试入口。

## 导入已有代开发安装凭据

如果已有服务器文件 `wecom-install.properties`，首次切换到 Spring 版时使用一次性导入命令：

```properties
authCorpId=wwxxxxxxxxxxxxxxxx
agentId=1000247
permanentCode=...
```

文件必须只允许服务账号读取（建议权限 `600`），不要提交到 Git、`.env` 或前端目录。先确保
`WECOM_SUITE_ID`、数据库连接和 `CREDENTIAL_MASTER_KEY` 已配置，再执行：

```bash
java -jar message-center.jar \
  wecom-import-install \
  --file /secure/path/wecom-install.properties
```

命令使用非 Web Spring 上下文，按 `suite_id + authCorpId` 幂等写入
`wecom_installations`，并使用当前主密钥加密 `permanentCode`。重复导入相同内容会返回
`unchanged`；凭据变化会保留安装 ID 并递增版本。输出不会包含 `permanentCode`。

应用启动时会读取最多两条 active 安装记录进行渠道账号对账：没有记录时不处理，唯一记录会幂等补齐
`channel_accounts` 中的企业微信账号；如果出现两条 active 安装，启动会以
`WECOM_SINGLE_CORP_VIOLATION` 失败，避免在单企业运行面中静默选错授权。已有安装记录升级到新版本时不需要重新授权。

导入后只查询非敏感字段确认状态：

```sql
SELECT id, suite_id, auth_corp_id, agent_id, auth_status, version
FROM wecom_installations
WHERE suite_id = '<SuiteId>' AND auth_corp_id = '<CorpId>';
```

确认 `auth_status = 'ACTIVE'` 后，将 Properties 文件移出运行目录或安全删除；应用运行时只从
数据库安装记录读取加密凭据。

## 凭据轮换

轮换 Suite Secret、回调 Token、EncodingAESKey 或专区私钥时，应先更新部署环境，再滚动重启 Spring 实例。不要在应用日志中打印 token、`permanent_code`、`secret_key`、授权 code 或 modal URL。轮换后检查授权审计、viewer 审计和 callback 验证，再恢复自动同步。

## 企业微信审计保留

Spring 每小时自动分批清理 `wecom_viewer_audit` 和 `wecom_authorization_audit`，默认保留 7 天。清理使用 PostgreSQL advisory lock，单轮最多执行 32 批、每批每张表最多删除 500 行；多实例不会同时清理。只有凭据迁移和授权恢复完成、启动闸门打开后才会运行。

| 环境变量 | 默认值 | 允许范围 |
| --- | ---: | ---: |
| `WECOM_AUDIT_RETENTION_DAYS` | `7` | `1..365` 天 |
| `WECOM_AUDIT_CLEANUP_INTERVAL_SECONDS` | `3600` | `60..86400` 秒 |
| `WECOM_AUDIT_CLEANUP_BATCH_SIZE` | `500` | `1..2000` |
| `WECOM_AUDIT_CLEANUP_MAX_BATCHES` | `32` | `1..64` |

未闭合的授权 `event_id + attempt` 会整体保留：只要存在 `accepted` 或 `pending` 且没有 `succeeded`/`failed`，该 attempt 的所有阶段都不会被删除。清理不处理通用 `audit_logs`，也不处理业务消息、应用 stdout/stderr 或 Docker 日志。

运维可查询每个 stream 最近一次清理状态：

```sql
SELECT stream, last_started_at, last_completed_at, deleted_count,
       status, error_code, updated_at
FROM wecom_audit_retention_state
ORDER BY stream;
```

`budget_remaining` 表示本轮达到删除预算，下轮会继续；`skipped_locked` 表示其他实例正在清理；`failed` 表示数据库清理或状态写入失败。失败不会阻断 viewer/chatdata，但必须结合结构化日志和 PostgreSQL 表/磁盘增长告警排查。授权审计的 required 写入语义保持不变，磁盘耗尽时仍会阻止授权状态变更。

## 验证

```bash
cd demo/message-center-spring/backend
mvn -q test
mvn -q -Pproduction -DskipTests package
# 生产部署产物：target/message-center.jar

cd ../frontend
npm test
npm run build
```

部署前还应执行敏感配置扫描：

```bash
rg -n 'wecom-(suite-secret|login-suite-secret|secret|token|encoding-aes-key): [^$]' \
  demo/message-center-spring/backend/src/main/resources
```

## 私有渠道历史归属回填

部署包含 `V48__backfill_user_channel_owners.sql` 的后端前，必须先完成数据库备份。Flyway 会在正常后端启动时自动执行该迁移；不要手工拼写或执行反向更新。迁移只使用可审计的私有渠道账号、消息创建人和电话记录创建人回填 `chatapp`、邮件、电话的 owner；不会根据昵称、备注、地址或消息正文推断归属，也不会改写企业微信数据。

部署前可执行只读预检，确认可归属和冲突的历史量：

```bash
psql "$SPRING_DATASOURCE_URL" -f scripts/backfill-user-channel-owners.sql
```

后端启动并完成 Flyway 后，执行只读对账：

```bash
psql "$SPRING_DATASOURCE_URL" -f scripts/verify-user-channel-owners.sql
```

无法可靠归属的历史记录将保留 `owner_user_id = NULL`，且不会进入按用户隔离的业务 API 或启动后续同步。回滚只能从部署前数据库备份恢复，禁止手写反向 SQL。

### AI Topic 生命周期

Topic 按 owner 聚合：个人 owner（`CONTACT`）合并 ChatApp、邮件、电话和企业微信一对一官方单条摘要；群 owner（`WECOM_GROUP`）只生成一份群 Topic，联系人时间轴仅引用该 Topic，不复制来源。企业微信摘要必须为 `COMPLETED` 且 `summary` 非空，正文和 `secret_key` 不进入 Topic 输入或普通日志。消息/摘要入库后推进 owner 静默窗口，默认静默 360 秒（`AI_TOPIC_QUIET_WINDOW_SECONDS`）后由后台异步重构；读取联系人页面不会同步触发 AI。

`AI_TOPIC_MATCH_THRESHOLD` 是 AI 返回的关联度分数阈值。增量任务只把新来源与同 owner 的 `READY` Topic 比较；`STORED`、`ARCHIVED` 或其他 owner 的 Topic 不参与后续判断。Topic 编辑、合并、入库、恢复和群入库审批均返回 `202 Accepted`，最终状态通过一次 `topic-snapshot-completed` SSE 事件通知前端，前端不轮询也不乐观移除卡片。个人 Topic 可直接入库；群 Topic 先在 `GET /api/v1/topic-inbox/requests` 中形成待审批申请，管理员通过 `POST /api/v1/topic-inbox/{requestId}/approve` 后才异步入库，拒绝使用同路径 `reject`。

跨联系人仓库使用 `GET /api/v1/topic-repository?search=&ownerType=&page=&size=`，始终保留原始联系人或群 owner 标签；恢复使用 `POST /api/v1/topics/{topicId}/restore`。诊断时可查询 `ai_topic_generation_attempts` 的 `raw_request_json`、`raw_response_json`、`validation_stage`、`error_code` 和 `diagnostic`，这些字段经过大小上限和脱敏处理。
