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

## 邮件同步配置

开启 `APP_EMAIL_SYNC_ENABLED=true` 后，邮件同步至少需要 `IMAP_USER` 和 `IMAP_PASSWORD`。139 邮箱在
`IMAP_HOST`、`IMAP_PORT` 或 `MAIL_PROVIDER` 缺失、为空时分别使用 `imap.139.com`、`993` 和 `139`；也可用
`APP_IMAP_HOST`、`APP_IMAP_PORT`、`APP_MAIL_PROVIDER` 显式覆盖。密码只通过环境变量或后端 `.env` 注入。

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

每日摘要默认关闭；需要时再配置 `WECOM_DAILY_SUMMARY_ENABLED=true` 和对应能力 ID。API 地址、超时和 token 刷新提前量也可通过 `WECOM_API_BASE_URL`、`WECOM_API_TIMEOUT_SECONDS`、`WECOM_TOKEN_REFRESH_SKEW_SECONDS` 覆盖。

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
