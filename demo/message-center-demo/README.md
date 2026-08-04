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

### 本地企业微信开发模式

本地开发不需要企业微信扫码、access token、RSA 或专区程序。复制配置后仅在本机启用：

```env
LOCAL_DEV_MODE=true
WEB_BIND_ADDRESS=127.0.0.1
LOCAL_WECOM_DATA_SOURCE=fixture
```

`fixture` 会提供两个本地联系人和三条样例消息；使用 `jsonl` 时，将输入文件配置到
`LOCAL_WECOM_DATA_FILE`，每行至少包含 `msgid`、`external_userid`、`userid`、`send_time`、
`secret_key` 和 `msgtype`。本地模式仍执行 viewer token、过期和一次性会话校验，但页面会跳过企业微信脚本，直接展示本地消息引用。生产环境保持 `LOCAL_DEV_MODE=false`。

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
- web 监听成功后会自动异步补拉 ChatApp 历史消息；顶部“同步 WhatsApp”保留为同一同步 owner 的手动入口。历史消息里带有效附件地址时会进入本地缓存队列，下载失败不会阻断消息写入。
- 选择联系人后，发送区按该联系人已有联系方式显示“邮件 / WhatsApp”切换页。
- 邮件发送使用主题和正文，发送成功后会同步写入旧邮件 demo 的 `inbox.jsonl`。
- WhatsApp 支持文本、模板、图片、视频、文件发送。
- ChatApp 模板发送从最近一次成功同步的本地模板快照生成下拉选项；web 启动后会异步同步并定时对账。
- 企业微信 tab 会在当前发送区内打开会话展示组件容器，不改变原有三栏布局和消息滚动方式。
- 电话录音可作为联系人时间线中的独立卡片上传、异步转录、播放和人工修订；原始 MP3 只保存在本地受限目录，转录通过内部 FunASR 兼容服务完成。
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
CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED=true
CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS=300
TEMPLATE_PAGE_SIZE=50
TEMPLATE_MAX_PAGES=40
```

web 服务监听成功后会异步执行第一轮模板同步，之后默认每 300 秒全量对账一次；同步间隔只允许 `300～600` 秒。上游正常时，模板新增、修改和删除会在 5～10 分钟内进入本地快照。每页最多 50 条、最多 40 页、单轮最多 2,000 条模板语言记录；第 40 页仍是满页或上游总数超过 2,000 时，整轮失败，不保存截断数据。

任一列表或详情请求失败、超时、锁忙或原子替换失败时，`/api/templates` 继续提供上一版成功快照。只有业务内容确实变化时才写文件并发送 `templates-changed` SSE；页面收到该事件后只重新请求 `/api/templates` 并更新模板控件，不刷新消息列表，也不显示“有新消息”。`ChatAppAudit` 审核回调不是完整模板库变更流，本功能不要求配置该回调。

环境变量优先于 `.env`，相对路径按 Java 进程当前工作目录解析。服务器要求从 `app` 目录启动 web 时，建议把模板文件配置为绝对路径，例如：

```env
CHATAPP_TEMPLATE_FILE=/www/wwwroot/message-center/data/templates.json
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
CHATAPP_MESSAGE_AUTO_SYNC_ENABLED=true
SYNC_PAGE_SIZE=20
SYNC_MAX_PAGES=2
SYNC_LOOKBACK_DAYS=1
SYNC_INCREMENTAL=true
SYNC_OVERLAP_MINUTES=30
SYNC_TEMPLATES_BEFORE_MESSAGES=false
```

`CHATAPP_MESSAGE_AUTO_SYNC_ENABLED` 默认开启。HTTP 监听成功后异步开始首轮，不阻塞 web 启动；每一轮完全结束 5 秒后再开始下一轮，不重叠也不补跑。每个 CAMS `ListChatappMessage` 请求最多等待 15 秒，超时后保留现有本地消息，并在完整固定延迟后重试。缺少 `CUST_SPACE_ID` 时自动同步会记录 `skipped_not_configured` 并跳过调度，web 仍正常启动；显式设置为 `false` 可以关闭后台同步。

电话录音转录使用本地有界运行时，不依赖数据库、对象存储或公网模型地址：

```env
CALL_RECORD_DATA_DIR=data/call-records
CALL_RECORD_MAX_AUDIO_BYTES=104857600
CALL_RECORD_MAX_DURATION_SECONDS=7200
CALL_RECORD_QUEUE_CAPACITY=64
CALL_RECORD_WORKER_CONCURRENCY=1
CALL_RECORD_MAX_ATTEMPTS=3
CALL_AUDIO_SESSION_TTL_SECONDS=300
FUNASR_BASE_URL=http://funasr:8000
FUNASR_MODEL=sensevoice
FUNASR_CONNECT_TIMEOUT_SECONDS=3
FUNASR_REQUEST_TIMEOUT_SECONDS=1800
```

上传只接受 MP3，单文件最大 100 MiB、最长 2 小时。写操作和播放授权必须携带当前企业微信 viewer token；播放先创建路径绑定的短时 HttpOnly Cookie，再通过 `/api/v1/call-records/{callRecordId}/audio` 读取完整文件或单个 Range。转录完成前时间线只显示排队/转录中状态，失败时可重试，人工修订通过版本号进行乐观并发控制。真实 FunASR sidecar 和浏览器端播放仍需按部署环境单独验收。

后台同步、顶部按钮、`POST /api/sync/chatapp` 和 `sync` CLI 使用同一个轮次锁合同。同进程或另一进程已有同步时，手动入口仍返回现有 `SyncResult` JSON，只把 `message` 设为 `lock_busy`，不会并发访问阿里云。页面原有的 5 秒刷新只读取本地消息 API，本功能没有新增消息 SSE，也不会让浏览器主动调用阿里云同步接口。

默认同步 WhatsApp 历史消息时使用增量窗口：如果本地已有 ChatApp 消息，就从最后一条消息时间往前重叠 `SYNC_OVERLAP_MINUTES` 分钟开始查，避免每次重新扫 10 天历史。首次没有本地消息时使用 `SYNC_LOOKBACK_DAYS`。需要一次性补历史时，可临时设置 `SYNC_INCREMENTAL=false`、调大 `SYNC_LOOKBACK_DAYS` 和 `SYNC_MAX_PAGES`。

默认同步 WhatsApp 历史消息时不再先拉模板详情，避免每次消息同步都被模板接口拖慢。`SYNC_TEMPLATES_BEFORE_MESSAGES=true` 仅保留为兼容触发入口，它委托同一个模板同步器，不应作为自动更新机制。需要立即执行一次模板对账时，本地开发可运行 `mvn -q exec:java "-Dexec.args=sync-templates"`；服务器 thin JAR 发布目录可运行：

```bash
cd /www/wwwroot/message-center/app
sudo -u www java -cp "message-center.jar:../lib/*" \
  com.crmforlogistics.messagecenter.App sync-templates
```

`sync-templates` 继续保留原有 CLI 输出合同。历史消息同步如果需要限定某个客户号码，可设置 `SYNC_USER_NUMBER`；如果需要固定时间窗口，可设置 `SYNC_START_TIME_STR` / `SYNC_END_TIME_STR`，格式如 `2026-07-10 00:00:00`。

企业微信会话展示组件按服务商代开发应用接入。服务商先在 `.env` 填写：

```env
WECOM_SUITE_ID=代开发应用模板的SuiteID
WECOM_SUITE_SECRET=代开发应用模板的SuiteSecret
WECOM_TOKEN=授权回调配置的Token
WECOM_ENCODING_AES_KEY=授权回调配置的EncodingAESKey
WECOM_CALLBACK_RECEIVE_ID=指令回调解密后的receiveId；与SuiteID一致时可留空
WECOM_AUTHORIZATION_INSTALLATIONS_FILE=data/wecom-authorization-installations.jsonl
WECOM_AUTHORIZATION_AUDIT_FILE=data/wecom-authorization-audit.jsonl
WECOM_AUTHORIZATION_AUDIT_MAX_BYTES=1048576
WECOM_LOGIN_AUTH_CORP_ID=登录首屏唯一对应的授权企业CorpID
# 服务商“登录授权”的 SuiteID 和 SuiteSecret，两项必须同时填写
WECOM_LOGIN_SUITE_ID=登录授权SuiteID
WECOM_LOGIN_SUITE_SECRET=登录授权SuiteSecret
WECOM_AUTHORIZATION_QUEUE_CAPACITY=64
WECOM_ALLOWED_JSAPI_ORIGINS=http://localhost:8099
WECOM_LOGIN_REDIRECT_URI=http://localhost:8099/
WECOM_LOGIN_ATTEMPT_TTL_SECONDS=300
WECOM_LOGIN_MAX_PENDING=256
```

8107 会把已验签的 Suite 回调写入本地授权审计 JSONL。审计只包含 `InfoType`、SuiteID、授权企业、处理结果和脱敏的企业微信错误字段，不包含 AuthCode、permanent_code、ticket、签名或密文。可用下面的命令查看最近事件：

```bash
jq -c '.' data/wecom-authorization-audit.jsonl | tail
```

新接入时，企业微信服务商后台的指令回调 URL 填：

```text
https://你的公网域名/api/v1/wecom/authorization/callback
```

已有部署使用 `/hook_path` 时不需要在企业微信后台重新保存 URL。8107 同样处理：

```text
https://你的公网域名/hook_path
```

先部署新 JAR，再把 Nginx 中精确匹配 `/hook_path` 的上游从 8067 改为 8107：

```nginx
location = /hook_path {
    client_max_body_size 1m;
    proxy_pass http://127.0.0.1:8107;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

修改后必须先执行 Nginx 配置检查再 reload。公网 URL 没有变化，因此不触发企业微信后台的
GET 保存校验；下一次官方 GET/POST 会直接进入 8107。确认回调成功和安装版本更新前不要停止
8067 的进程，出现问题时可把这一条 `proxy_pass` 临时切回明确的 8067 上游。
8107 重启后内存中还没有 `suite_ticket`，必须先在 Nginx access log 看到 `/hook_path` 的一条
新周期 POST（企业微信通常每 10 分钟推送一次），再点击“重新获取 Secret”；否则本次 10 分钟
有效的 `AuthCode` 可能因缺少 Suite ticket 而无法换码。最终以安装 JSONL 中目标记录的
`version` 和 `updatedAt` 更新为成功证据，不以单独的 HTTP 200 作为凭证更新成功证据。

保存该 URL 时，企业微信会先发送带 `msg_signature`、`timestamp`、`nonce`、`echostr` 的 GET 校验请求。服务会按 `WECOM_TOKEN` 和 `WECOM_ENCODING_AES_KEY` 验签、解密 `echostr`，并以纯文本返回解密结果；校验通过后，企业微信才会继续向同一路径发送 POST 授权事件。GET 校验不依赖安装记录，只有回调编解码配置缺失时才返回结构化 503。

GET 回调校验只接受明确配置的代开发 SuiteID、登录授权 SuiteID，以及 `WECOM_CALLBACK_RECEIVE_ID`。部分服务商“登录配置/指令回调”的 GET 加密明文 `receiveId` 与业务 SuiteID 不同，此时把该接收方 ID 填入 `WECOM_CALLBACK_RECEIVE_ID`；未知 receiveId 仍会失败关闭。

企业首次安装代开发应用后，企业微信会把 `create_auth` 回调发到该地址。后端使用 Suite 凭证换取授权企业的 `authCorpId`、AgentID 和 `permanent_code`。按企业微信[代开发授权应用 Secret 的获取](https://developer.work.weixin.qq.com/document/path/97163)定义，该 `permanent_code` 就是本次代开发应用安装的 Secret；后端用 `CREDENTIAL_MASTER_KEY_FILE` 指向的 32 字节 Base64 主密钥加密后写入安装 JSONL。`WECOM_LOGIN_AUTH_CORP_ID` 必须与其中一条 active 安装记录精确匹配；浏览器不能提交 CorpID 覆盖它。安装尚未完成、记录已撤销、文件损坏或主密钥不匹配时，登录首屏返回结构化错误并失败关闭。

在代开发应用详情点击“重新获取 Secret”会推送 `reset_permanent_code`。8107 在 1 秒内把事件放入有界队列并返回 `success`，随后使用 10 分钟内有效的 `AuthCode` 获取最新 `permanent_code`。返回的 CorpID 必须匹配已有且未撤销的安装记录；新 Secret 经授权信息校验后加密覆盖，保留原 `installationId` 并递增 `version`，使旧 access-token 缓存自动失效。未知企业、空 AuthCode、错误 Suite 或上游失败都不能创建或覆盖安装记录。

登录认证和代开发认证是两套 Suite 上下文：`WECOM_LOGIN_SUITE_ID/WECOM_LOGIN_SUITE_SECRET` 只用于浏览器 Web 登录组件的 `ServiceApp` 二维码和 `service/auth/getuserinfo3rd` 换码；`WECOM_SUITE_ID/WECOM_SUITE_SECRET` 只用于代开发模板授权和取得安装 Secret。会话展示、JS-SDK 签名、公钥注册和专区同步所需的应用 access token，按企业微信[代开发授权应用 access_token 的获取](https://developer.work.weixin.qq.com/document/path/97164)要求，以安装记录中的 `authCorpId + permanent_code` 调用 `GET /cgi-bin/gettoken` 获取。两套 Suite 的 `suite_ticket` 分开缓存并按 SuiteID 精确选择 Secret。登录 Suite 配置缺失或只填一项时失败关闭，不提供 CorpApp 回退。

旧的 `WECOM_AGENT_ID`、`WECOM_SECRET` 和 `WECOM_CORP_ID` 不会被当作代开发安装凭据；代开发应用 Secret 只来自授权回调并加密保存在安装记录中，不需要在 `.env` 重复填写。生产配置应明确使用上述两套 owner，避免把登录 Secret 发送到会话展示链路。

普通桌面浏览器首屏使用企业微信官方 `ww.createWWLoginPanel()` 展示二维码，参数为 `login_type=ServiceApp`、`appid=WECOM_LOGIN_SUITE_ID` 和 `redirect_type=callback`，不传 `agentid`。真实扫码时，`WECOM_LOGIN_REDIRECT_URI` 与 `WECOM_ALLOWED_JSAPI_ORIGINS` 必须同时改成企业微信后台已配置的同一公网 HTTPS 域名；登录面板和会话展示组件必须在该域名的 top frame 中运行。扫码返回身份的 `corpid` 必须与 `WECOM_LOGIN_AUTH_CORP_ID` 绑定的 active 安装一致，否则返回 403。`http://localhost` 只用于本地开发错误态和合同测试。

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
chmod 600 /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
chown www:www /www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
```

Linux 上私钥若存在 group/other 权限或 owner execute 权限，企业微信公钥注册和会话密钥解密会拒绝加载该私钥。

8107 可以在收到真实 `suite_ticket` 后，用同一进程内存中的 ticket 异步完成官方 `POST /cgi-bin/chatdata/set_public_key`。在服务器 `.env` 增加：

```env
WECOM_CHATDATA_PRIVATE_KEY_FILE=/www/wwwroot/message-center/secrets/wecom_chatdata_private_key.pem
WECOM_CHATDATA_PUBLIC_KEY_VERSION=1
WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER=true
WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE=/www/wwwroot/message-center/data/wecom-chatdata-public-key-registration.json
```

重启 8107 后，在企业微信服务商后台手动刷新一次 suite ticket，或等待下一次约 10 分钟的官方推送。回调会立即返回 `success`；后台 worker 再依次获取 suite access token、授权企业 access token 并注册从私钥派生的 X.509 PEM 公钥。成功后生成脱敏状态文件：

```json
{"authCorpId":"ww...","publicKeyVersion":1,"publicKeySha256":"...","registeredAt":"..."}
```

该文件不含 suite ticket、access token、permanent code、私钥或完整公钥。同一企业、版本和公钥摘要已经成功时会跳过重复注册；上游失败时不写成功状态，也不把授权安装标为失败，等待下一次 ticket 或授权变更事件重试。8107 的 `WECOM_CHATDATA_PUBLIC_KEY_VERSION` 必须与官方记录完全一致，后续换钥时版本号必须递增。

`WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER=true` 不会让消息中心网页依赖私钥启动。8107 启动时不读取 RSA 私钥；worker 收到真实 ticket 或授权安装事件后才加载。私钥路径、权限、格式或 RSA 位数错误时，只输出不含路径和凭据的 `wecom.chatdata.public_key_registration` 失败事件，网页、登录、Email 和 ChatApp 继续运行。修复私钥后无需再次关闭网页服务，等待下一次 ticket 或授权安装事件即可重试。公钥注册成功前，企业微信会话同步和 viewer 仍不可用。

这项变更只发生在 8107。专区程序能力和镜像未变化，部署时只需替换最新 `message-center.jar` 与现有 `lib/` 配套依赖，不需要重传专区镜像。

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

本地 demo 的 viewer 打开、拒绝、限流、签名和组件错误写入有上限的 `WECOM_VIEWER_AUDIT_FILE`，不记录 `viewerAuthToken`、ticket 或 `secretKey`。会话同步失败时只追加安全诊断字段 `errorCode`、`upstreamErrcode`、`upstreamPath` 和 `upstreamHttpStatus`，不记录 access token、Secret、私钥、请求正文、响应正文或消息内容。文件达到 `WECOM_VIEWER_AUDIT_MAX_BYTES` 后 viewer 操作失败关闭，避免静默丢审计或无界增长。组件错误只接受与同一短时 token 最近成功读取的 viewer session，并通过原子消费阻断并发或顺序重放；事件请求拒绝合同之外的字段。该文件只是本地 demo audit adapter；生产模块化运行面应把同一结构化事件交给现有 `AuditService`，这不构成本地 viewer 的数据库依赖。

## Webhook

ChatApp 回调地址：

```text
http://localhost:8099/webhook/chatapp
```

旧的企业微信 demo webhook、`sync_msg`、消息注入和 `gettoken` 模拟路由不再挂载到运行时，避免绕过专区同步或污染正式 `WECOM_DATA_FILE`；相关 helper 仅供隔离测试使用。企业微信公开接口只接受 OpenAPI 声明的 `/api/v1/wecom/*`，不再提供无版本 `/api/wecom/*` 别名。`/hook_path` 仅是企业微信官方 Suite 指令回调的稳定 ingress，不是业务 API，也不恢复旧模拟能力。

企业微信会话展示组件：

```text
GET  https://你的公网域名/api/v1/wecom/authorization/callback?msg_signature=...&timestamp=...&nonce=...&echostr=...
POST https://你的公网域名/api/v1/wecom/authorization/callback
GET  https://你的公网域名/hook_path?msg_signature=...&timestamp=...&nonce=...&echostr=...  # 已有部署兼容入口
POST https://你的公网域名/hook_path                                                     # 已有部署兼容入口
POST http://localhost:8099/api/v1/wecom/login/attempts
POST http://localhost:8099/api/v1/wecom/login/exchange  body={code,state}
POST http://localhost:8099/api/v1/wecom/conversation-view/sync  header=X-WeCom-Viewer-Auth: <viewerAuthToken> body={}
GET  http://localhost:8099/api/v1/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F
POST http://localhost:8099/api/v1/wecom/conversation-view/sessions
GET  http://localhost:8099/api/v1/wecom/conversation-view/sessions/{viewerSessionId}
POST http://localhost:8099/api/v1/wecom/conversation-view/events
```

首版按代开发授权安装记录接入，并由 `WECOM_LOGIN_AUTH_CORP_ID` 唯一选择登录企业。页面首屏异步加载企业微信 JSSDK，10 秒未完成就显示可重试错误；扫码前不请求联系人、消息、模板或 SSE。登录面板回调的临时 code 与后端生成的一次性 state 只交换一次，返回的 `viewerAuthToken` 仅保存在当前页面 JavaScript 内存，不写入 URL、cookie、localStorage 或 sessionStorage；刷新或到期后重新扫码。这是企业微信 viewer 的最小短时授权入口，不是完整 CRM 全局认证。

首次登录后如果联系人列表还没有企业微信联系人，先点击顶部“同步企业微信会话”。页面调用独立的 `POST /api/v1/wecom/conversation-view/sync`，8107 使用当前登录的短时 viewer token 执行有界专区同步，并把消息引用写入 `WECOM_DATA_FILE`；同步完成后刷新联系人列表，生成 `wecom:<external_userid>` 联系人。之后点击企业微信 tab，页面再创建 viewer session 并显示会话展示组件。已有企业微信联系人时，点击 viewer 会自动执行同样的同步再创建 session。页面懒加载 `jwxwork-1.0.0.js`，调用 `ww.register()`、`ww.initOpenData()` 和 `ww.createOpenDataFrameFactory().createOpenDataFrame(...)`。消息用 `ww-open-message` 展示 `msgid + secretKey`；外部浏览器通过 `handleModal` 在 iframe 中预览图片、视频和聊天记录详情，模板 `binderror` 负责捕获组件错误。`42006`、`42003`、`40029` 或 `Missing open sid` 会清除内存授权并返回扫码首屏。后端把授权企业 CorpID 和安装记录中的 `permanent_code`（代开发应用 Secret）交给官方 `/cgi-bin/gettoken` 获取应用 access token；不会调用第三方应用的 `/cgi-bin/service/get_corp_token`。token、密钥和上游响应不进入前端或错误消息。

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

本地源码可直接运行消息自动同步的针对性测试和完整测试，不需要生成发布包：

```powershell
mvn -q "-Dtest=ConfigTest,ChatAppHistoryStoreTest,AliyunChatAppMessageGatewayTest,ChatAppMessageSynchronizerTest,ChatAppMessageSyncRuntimeTest" test
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
node contracts/openapi/message-center-v1.test.mjs
```
