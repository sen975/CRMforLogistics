# message-center-demo

统一消息中心 demo，用一个联系人列表合并展示邮件和 ChatApp/WhatsApp 消息，并预留企业微信接入位置。

## 边界

- 不改旧 demo 的代码。
- 邮件读取 `../email-send-receive-demo/data/inbox.jsonl`。
- ChatApp 读取 `../chatapp-send-receive-demo/data/messages.jsonl`。
- ChatApp 模板读取 `../chatapp-send-receive-demo/data/templates.json`。
- 联系人合并关系只写入本 demo 的 `data/contact-groups.jsonl`。
- 企业微信当前只保留 API 落点，不伪造收发能力。

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
- 点击消息可在右侧查看完整内容、From、To、状态和 raw。
- 图片、视频、文件附件通过本 demo 后端代理下载到本地缓存，再在消息里预览或打开，不再让浏览器直连 OSS 临时链接。
- 顶部“收取邮件”会用本 demo 的 IMAP 配置拉取 INBOX 和 SENT_FOLDER，并写回旧邮件 demo 的 `inbox.jsonl`。
- 顶部“同步 WhatsApp”会用本 demo 的 CAMS 配置补拉 ChatApp 历史消息，并在拉消息前同步模板库；历史消息里带有效附件地址时会立即下载到本地缓存，下载失败不会阻断消息写入。
- 选择联系人后，发送区按该联系人已有联系方式显示“邮件 / WhatsApp”切换页。
- 邮件发送使用主题和正文，发送成功后会同步写入旧邮件 demo 的 `inbox.jsonl`。
- WhatsApp 支持文本、模板、图片、视频、文件发送。
- ChatApp 模板发送从本地模板库生成下拉选项。
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
IMAP_HOST=
IMAP_PORT=993
IMAP_USERNAME=
IMAP_PASSWORD=
INBOX_FOLDER=INBOX
SENT_FOLDER=Sent
RECEIVE_LIMIT=10
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

## Webhook

ChatApp 回调地址：

```text
http://localhost:8099/webhook/chatapp
```

企业微信预留地址：

```text
http://localhost:8099/webhook/wecom
```

当前 `/webhook/wecom` 返回 501，后续接入企业微信 API 时把回调解密、消息投影和发送 adapter 接到这个位置。

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
