# message-center-demo

统一消息中心 demo，用一个联系人列表合并展示邮件、ChatApp/WhatsApp 和企业微信消息，并提供企业微信会话展示组件入口。

## 边界

- 不改旧 demo 的代码。
- 邮件读取 `../email-send-receive-demo/data/inbox.jsonl`。
- ChatApp 读取 `../chatapp-send-receive-demo/data/messages.jsonl`。
- ChatApp 模板读取 `../chatapp-send-receive-demo/data/templates.json`。
- 联系人合并关系只写入本 demo 的 `data/contact-groups.jsonl`。
- 企业微信会话展示组件只作为受控查看入口，不把官方组件中的完整内容复制成消息中心自己的消息真相。

## 启动

```powershell
cd D:\WorkItems\CRMforLogistics\demo\message-center-demo
Copy-Item .\config.example.env .\.env
.\message-center-demo.ps1 web
```

默认地址：

```text
http://localhost:8099
```

如果 8099 被占用，在 `.env` 里修改：

```env
WEB_PORT=8077
```

## 页面能力

- 左侧统一联系人列表。
- 拖动一个联系人到另一个联系人上会合并联系方式。
- 点击头像可以查看该联系人已合并的联系方式，并拆分。
- 邮件和 ChatApp 消息在同一个聊天时间线里按时间穿插。
- 选择联系人时默认只加载最近 10 条消息，向上滚到消息区顶部会继续按 10 条加载更早消息。
- 点击消息可在右侧查看完整内容、From、To、状态和 raw。
- 图片、视频、文件附件通过本 demo 后端代理下载到本地缓存，再在消息里预览或打开，不再让浏览器直连 OSS 临时链接。
- 顶部“收取邮件”会用本 demo 的 IMAP 配置拉取 INBOX 和 SENT_FOLDER，并写回旧邮件 demo 的 `inbox.jsonl`。
- 顶部“同步 WhatsApp”会用本 demo 的 CAMS 配置补拉 ChatApp 历史消息，并在拉消息前同步模板库；历史消息里带有效附件地址时会立即下载到本地缓存，下载失败不会阻断消息写入。
- 选择联系人后，发送区按该联系人已有联系方式显示“邮件 / WhatsApp”切换页。
- 邮件发送使用主题和正文，发送成功后会同步写入旧邮件 demo 的 `inbox.jsonl`。
- WhatsApp 支持文本、模板、图片、视频、文件发送。
- ChatApp 模板发送从本地模板库生成下拉选项。
- 企业微信 tab 会在当前发送区内打开会话展示组件容器，不改变原有三栏布局和消息滚动方式。
- 浏览器页签打开时，后端 webhook 和本地数据轮询都会触发“有新消息”提示。

## 配置

`.env` 支持从环境变量覆盖同名配置。

邮件发送最少需要：

```env
SMTP_HOST=
SMTP_PORT=465
SMTP_USERNAME=
SMTP_PASSWORD=
MAIL_FROM=
```

邮件收取最少需要：

```env
MAIL_PROVIDER=auto
IMAP_HOST=
IMAP_PORT=993
IMAP_SSL=true
IMAP_USERNAME=
IMAP_PASSWORD=
INBOX_FOLDER=INBOX
SENT_FOLDER=Sent
RECEIVE_LIMIT=10
```

139 邮箱收取使用应用内兼容模式，直接连 993，不需要 stunnel：

```env
MAIL_PROVIDER=139
IMAP_HOST=imap.139.com
IMAP_PORT=993
IMAP_SSL=true
IMAP_USERNAME=你的139邮箱
IMAP_PASSWORD=你的139授权码或密码
```

ChatApp 发送最少需要：

```env
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
CUST_SPACE_ID=
CHATAPP_FROM=
CHATAPP_LANGUAGE=zh_CN
CHATAPP_TYPE=message
CHATAPP_TEMPLATE_TYPE=template
```

模板消息使用 `CHATAPP_TEMPLATE_TYPE=template`，请求里不会发送 `MessageType` 字段。

模板发送依赖旧 ChatApp demo 的模板缓存：

```env
CHATAPP_TEMPLATE_FILE=../chatapp-send-receive-demo/data/templates.json
```

附件发送会先调用 CAMS 的 `GetChatappUploadAuthorization`，上传到授权 OSS，再调用 `SendChatappMessage`。

附件预览和打开使用本地缓存：

```env
MEDIA_CACHE_DIR=data/media-cache
MEDIA_PROXY_TIMEOUT_SECONDS=15
CHATAPP_MEDIA_PRESIGNED_TIMEOUT_SECONDS=5
CHATAPP_MEDIA_USE_CAMS_PRESIGNED=true
CHATAPP_MEDIA_PRECACHE_MODE=background
CHATAPP_MEDIA_PRECACHE_SKIPPED=false
CHATAPP_MEDIA_PRECACHE_THREADS=1
CHATAPP_MEDIA_PRECACHE_QUEUE_SIZE=100
CHATAPP_MEDIA_SIGNED_URL_TTL_SECONDS=900
```

当历史消息里的 OSS STS 链接失效时，后端会优先用 CAMS 官方 `GeneratePresignedUrl` 根据消息里的 `objectKey` 或原 URL 路径生成新的下载链接，再下载到 `MEDIA_CACHE_DIR`。前端仍然只访问本地 `/api/media`，不直接暴露 CAMS/OSS 链接。

历史同步默认只对新增或被 CAMS 刷新的消息预缓存附件，并且使用后台队列下载，不阻塞“同步 WhatsApp”按钮返回。已经跳过的旧消息不会在每次同步时反复下载，避免过期 OSS 链接或权限问题拖慢同步。如果你要强制重试所有旧附件，可临时设置 `CHATAPP_MEDIA_PRECACHE_SKIPPED=true`；如果你要完全关闭同步时预缓存，可设置 `CHATAPP_MEDIA_PRECACHE_MODE=off`。

如果 CAMS 生成链接失败或超时，后端会先用服务端 AK 生成新的 OSS 短时 GET 签名，再最后尝试消息原始 `mediaUrl`。原始 `mediaUrl` 里经常是旧 STS 临时链接，通常只作为最后兜底；如果 bucket 或 endpoint 不能从原链接推断，可显式配置：

```env
CHATAPP_MEDIA_BUCKET=bucket-chatapp-file-internal
CHATAPP_MEDIA_ENDPOINT=oss-ap-southeast-1.aliyuncs.com
```

ChatApp 历史同步使用：

```env
SYNC_PAGE_SIZE=20
SYNC_MAX_PAGES=2
SYNC_LOOKBACK_DAYS=1
SYNC_INCREMENTAL=true
SYNC_OVERLAP_MINUTES=30
SYNC_TEMPLATES_BEFORE_MESSAGES=false
```

默认同步 WhatsApp 历史消息时使用增量窗口：如果本地已有 ChatApp 消息，就从最后一条消息时间往前重叠 `SYNC_OVERLAP_MINUTES` 分钟开始查，避免每次重新扫 10 天历史。首次没有本地消息时使用 `SYNC_LOOKBACK_DAYS`。需要一次性补历史时，可临时设置 `SYNC_INCREMENTAL=false`、调大 `SYNC_LOOKBACK_DAYS` 和 `SYNC_MAX_PAGES`。

默认同步 WhatsApp 历史消息时不再先拉模板详情，避免每次消息同步都被模板接口拖慢。需要刷新模板库时可单独运行 `mvn -q exec:java "-Dexec.args=sync-templates"`，或临时设置 `SYNC_TEMPLATES_BEFORE_MESSAGES=true`。如果需要限定某个客户号码，可设置 `SYNC_USER_NUMBER`。如果需要固定时间窗口，可设置 `SYNC_START_TIME_STR` / `SYNC_END_TIME_STR`，格式如 `2026-07-10 00:00:00`。

企业微信会话展示组件按服务商代开发应用接入。服务商先在 `.env` 填写：

```env
WECOM_SUITE_ID=代开发应用模板的SuiteID
WECOM_SUITE_SECRET=代开发应用模板的SuiteSecret
WECOM_TOKEN=授权回调配置的Token
WECOM_ENCODING_AES_KEY=授权回调配置的EncodingAESKey
WECOM_CALLBACK_RECEIVE_ID=指令回调解密后的receiveId；与SuiteID一致时可留空
WECOM_AUTHORIZATION_INSTALLATIONS_FILE=data/wecom-authorization-installations.jsonl
WECOM_LOGIN_AUTH_CORP_ID=登录首屏唯一对应的授权企业CorpID
WECOM_AUTHORIZATION_QUEUE_CAPACITY=64
WECOM_ALLOWED_JSAPI_ORIGINS=http://localhost:8099
WECOM_LOGIN_REDIRECT_URI=http://localhost:8099/
WECOM_LOGIN_ATTEMPT_TTL_SECONDS=300
WECOM_LOGIN_MAX_PENDING=256
```

企业微信服务商后台的授权回调 URL 填：

```text
https://你的公网域名/api/v1/wecom/authorization/callback
```

保存该 URL 时，企业微信会先发送带 `msg_signature`、`timestamp`、`nonce`、`echostr` 的 GET 校验请求。服务会按 `WECOM_TOKEN` 和 `WECOM_ENCODING_AES_KEY` 验签、解密 `echostr`，并以纯文本返回解密结果；校验通过后，企业微信才会继续向同一路径发送 POST 授权事件。GET 校验不依赖安装记录，只有回调编解码配置缺失时才返回结构化 503。

部分服务商“登录配置/指令回调”的 GET 加密明文 `receiveId` 与业务 SuiteID 不同。此时 `WECOM_SUITE_ID` 仍填写页面 SuiteID，另将 GET 校验接收方 ID 填入 `WECOM_CALLBACK_RECEIVE_ID`；POST 授权事件仍按 `WECOM_SUITE_ID` 校验。未配置该项时 GET 默认使用 `WECOM_SUITE_ID`。

企业首次安装代开发应用后，企业微信会把 `create_auth` 回调发到该地址。后端使用 Suite 凭证换取授权企业的 `authCorpId`、AgentID 和 `permanent_code`，并把永久码用 `CREDENTIAL_MASTER_KEY_FILE` 指向的 32 字节 Base64 主密钥加密后写入安装 JSONL。`WECOM_LOGIN_AUTH_CORP_ID` 必须与其中一条 active 安装记录精确匹配；浏览器不能提交 CorpID 覆盖它。安装尚未完成、记录已撤销、文件损坏或主密钥不匹配时，登录首屏返回结构化错误并失败关闭。

`WECOM_AGENT_ID`、`WECOM_SECRET` 和 `WECOM_CORP_ID` 不再是代开发认证配置，也不会作为回退。AgentID 和应用级永久凭据只来自授权安装记录。

普通桌面浏览器首屏使用企业微信官方 `ww.createWWLoginPanel()` 展示二维码，参数固定为代开发安装对应的 `CorpApp` 和 `redirect_type=callback`。真实扫码时，`WECOM_LOGIN_REDIRECT_URI` 与 `WECOM_ALLOWED_JSAPI_ORIGINS` 必须同时改成企业微信后台已配置的同一公网 HTTPS 域名；登录面板和会话展示组件必须在该域名的 top frame 中运行。`http://localhost` 只用于本地开发错误态和合同测试。

真实链路由专区程序同步 `msgid + encrypted_secret_key`，8107 解密并原子发布到 `WECOM_DATA_FILE`。人工 JSONL 只用于本地单元测试，不能代替真实企业微信验收。配置：

```env
WECOM_DATA_FILE=wecom-messages.jsonl
WECOM_CHATDATA_PROGRAM_ID=企业微信后台关联后的程序ID
WECOM_CHATDATA_ABILITY_ID=conversation_viewer_sync
WECOM_CHATDATA_PRIVATE_KEY_FILE=/www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
WECOM_CHATDATA_PUBLIC_KEY_VERSION=1
WECOM_CHATDATA_CURSOR_FILE=/www/wwwroot/message-center/data/wecom-chatdata-cursor.json
WECOM_CHATDATA_SYNC_LIMIT=200
WECOM_CHATDATA_SYNC_MAX_PAGES=5
WECOM_CHATDATA_SYNC_TIMEOUT_SECONDS=15
WECOM_CHATDATA_STORE_MAX_MESSAGES=5000
WECOM_CHATDATA_STORE_MAX_BYTES=8388608
WECOM_VIEWER_SESSION_TTL_SECONDS=300
WECOM_VIEWER_MAX_MESSAGES=10
WECOM_VIEWER_SESSION_RATE_LIMIT=10
WECOM_VIEWER_AUDIT_FILE=data/wecom-viewer-audit.jsonl
WECOM_VIEWER_AUDIT_MAX_BYTES=1048576
WECOM_TOKEN_REFRESH_SKEW_SECONDS=300
```

`WECOM_DATA_FILE` 和 `WECOM_CHATDATA_CURSOR_FILE` 使用相对路径时统一解析到 `DATA_DIR`；服务器也可以直接配置绝对路径。RSA 私钥路径必须是绝对路径。

最小专区程序位于 `../wecom-chatdata-zone-program`。按该目录 README 使用企业微信官方 Java 1.4.0 示例构建后，上传：

```text
demo/wecom-chatdata-zone-program/target/wecom-chatdata-zone-program-linux-amd64.tar
```

该 tar 按官方示例由 `docker export` 生成；专区后台启动命令填写 `/app/start`，启动参数留空。把审核通过并关联到当前代开发应用后的最终 program ID、ability ID 写回 8107 配置。

每个授权企业先生成独立 RSA-2048 PKCS#8 密钥对，私钥只留在 8107：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
openssl pkey \
  -in /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem \
  -pubout \
  -out /www/wwwroot/message-center/secrets/wecom_chatdata_public_key.pem
chmod 600 /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
chown www:www /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
```

Linux 上私钥若存在 group/other 权限或 owner execute 权限，8107 会失败关闭，不会尝试加载。

使用该授权安装的应用 access token 调官方 `POST /cgi-bin/chatdata/set_public_key`，body 为 `public_key` 完整 PEM 文本和 `public_key_ver=1`。官方明确规定设置公钥后消息才开始存档；8107 的 `WECOM_CHATDATA_PUBLIC_KEY_VERSION` 必须与之完全一致。后续换钥时版本号必须递增，首版 8107 不自动尝试多把私钥。

点击 Viewer 后，8107 每页最多 200、最多 5 页、总超时 15 秒调用 `sync_call_program`，专区程序独占固定 `mode=0`。只保存一对一员工/外部联系人的展示引用，不保存正文；消息快照先于 cursor 发布。达到页上限返回 409，用户再次点击会从已保存 cursor 继续。

同一个专区程序还必须关联第二个固定能力 `conversation_daily_summary`，输入输出协议见专区程序 README。开启官方每日摘要后，8107 在北京时间每天 `00:05` 为前一自然日的每个员工-外部联系人单聊创建任务，调用企业微信官方摘要模型，并把同一对话的最终摘要写入 PostgreSQL。数据库只保存任务状态、不可逆消息引用摘要、覆盖统计和摘要文本，不保存 `secret_key`、原始 msgid 列表或会话正文。

摘要默认关闭，关闭时不会打开 PostgreSQL，也不影响 viewer。服务器确认 PostgreSQL 配置可用后再增加：

```env
WECOM_DAILY_SUMMARY_ENABLED=true
WECOM_DAILY_SUMMARY_ABILITY_ID=conversation_daily_summary
WECOM_DAILY_SUMMARY_HOUR=0
WECOM_DAILY_SUMMARY_MINUTE=5
WECOM_DAILY_SUMMARY_MAX_BATCHES=32
WECOM_DAILY_SUMMARY_MAX_TRANSIENT_ATTEMPTS=20
WECOM_DAILY_SUMMARY_POLL_INITIAL_SECONDS=30
WECOM_DAILY_SUMMARY_MAX_BACKOFF_SECONDS=900
WECOM_DAILY_SUMMARY_MAX_WAIT_HOURS=24
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/message_center
DATABASE_USER=message_center
DATABASE_PASSWORD_FILE=/www/wwwroot/message-center/secrets/postgres_password
```

`WECOM_CHATDATA_PROGRAM_ID` 继续使用 viewer 所在的同一个程序 ID。官方返回输入过长错误 `790040` 时，8107 会按消息顺序二分并持久化新批次；每个员工-外部联系人每日最多 32 批，单条仍超限时标记部分失败，不伪造完整摘要。开启摘要后数据库或授权安装不可用会使摘要 runtime 失败关闭；HTTP viewer 在摘要关闭时仍保持原有无数据库路径。

前端只调用 JS-SDK 签名接口和短时 viewer session，不接触 `corpsecret`、`access_token` 或 `jsapi_ticket`。统一时间线首次只取最近 10 条，用户滚到顶部时按 10 条继续加载更早消息；官方 viewer 引用则按 `send_time` 去重排序，默认只挂载最近 10 条，配置硬上限为 20，不会一次读取并返回全部引用。

本地 demo 的 viewer 打开、拒绝、限流、签名和组件错误写入有上限的 `WECOM_VIEWER_AUDIT_FILE`，不记录 `viewerAuthToken`、ticket 或 `secretKey`。文件达到 `WECOM_VIEWER_AUDIT_MAX_BYTES` 后 viewer 操作失败关闭，避免静默丢审计或无界增长。组件错误只接受与同一短时 token 最近成功读取的 viewer session，并通过原子消费阻断并发或顺序重放；事件请求拒绝合同之外的字段。该文件只是本地 demo audit adapter；生产模块化运行面应把同一结构化事件交给现有 `AuditService`，这不构成本地 viewer 的数据库依赖。

## Webhook

ChatApp 回调地址：

```text
http://localhost:8099/webhook/chatapp
```

旧的企业微信 demo webhook、`sync_msg`、消息注入和 `gettoken` 模拟路由不再挂载到运行时，避免绕过专区同步或污染正式 `WECOM_DATA_FILE`；相关 helper 仅供隔离测试使用。企业微信公开接口只接受 OpenAPI 声明的 `/api/v1/wecom/*`，不再提供无版本 `/api/wecom/*` 别名。

企业微信会话展示组件：

```text
GET  https://你的公网域名/api/v1/wecom/authorization/callback?msg_signature=...&timestamp=...&nonce=...&echostr=...
POST https://你的公网域名/api/v1/wecom/authorization/callback
POST http://localhost:8099/api/v1/wecom/login/attempts
POST http://localhost:8099/api/v1/wecom/login/exchange  body={code,state}
GET  http://localhost:8099/api/v1/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F
POST http://localhost:8099/api/v1/wecom/conversation-view/sessions
GET  http://localhost:8099/api/v1/wecom/conversation-view/sessions/{viewerSessionId}
POST http://localhost:8099/api/v1/wecom/conversation-view/events
```

首版按代开发授权安装记录接入，并由 `WECOM_LOGIN_AUTH_CORP_ID` 唯一选择登录企业。页面首屏异步加载企业微信 JSSDK，10 秒未完成就显示可重试错误；扫码前不请求联系人、消息、模板或 SSE。登录面板回调的临时 code 与后端生成的一次性 state 只交换一次，返回的 `viewerAuthToken` 仅保存在当前页面 JavaScript 内存，不写入 URL、cookie、localStorage 或 sessionStorage；刷新或到期后重新扫码。这是企业微信 viewer 的最小短时授权入口，不是完整 CRM 全局认证。

用户点击 viewer 后，页面先显示“正在同步企业微信会话”，由 8107 完成有界专区同步后再创建 viewer session，并懒加载 `jwxwork-1.0.0.js`，调用 `ww.register()`、`ww.initOpenData()` 和 `ww.createOpenDataFrameFactory().createOpenDataFrame(...)`。消息用 `ww-open-message` 展示 `msgid + secretKey`；外部浏览器通过 `handleModal` 在 iframe 中预览图片、视频和聊天记录详情，模板 `binderror` 负责捕获组件错误。`42006`、`42003`、`40029` 或 `Missing open sid` 会清除内存授权并返回扫码首屏。后端把 `permanent_code` 交给官方 `service/get_corp_token` 获取代开发应用 access token，不把永久码当 `corpsecret`；token、密钥和上游响应不进入前端或错误消息。

## 验证

```powershell
.\message-center-demo.ps1 test
.\message-center-demo.ps1 compile
.\message-center-demo.ps1 receive
.\message-center-demo.ps1 sync
.\message-center-demo.ps1 sync-templates
```

PowerShell 里如果直接用 Maven，参数要加引号：

```powershell
mvn -q exec:java "-Dexec.args=web"
```
