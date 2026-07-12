# 阿里云 ChatApp 发收消息 Demo

这个 demo 用 Java + 阿里云 CAMS SDK 做一个可测试闭环：

- `web`：启动本地页面和 webhook 服务，默认 `http://localhost:8077`
- `send`：通过 `SendChatappMessage` 发送 WhatsApp/ChatApp 消息
- `sync`：通过 `ListChatappMessage` 补拉阿里云 ChatApp 能查到的历史消息
- `sync-templates`：通过 `ListChatappTemplate` / `GetChatappTemplateDetail` 同步模板库
- `templates`：打印本地已同步的模板库
- `POST /webhook/chatapp`：接收上行消息和状态回调，并写入本地消息文件
- `phones`：查询当前客户空间下的号码
- `set-phone-webhook`：设置号码级回调地址
- `set-account-webhook`：设置账号级状态回调地址

## 配置

复制 `config.example.env` 为 `.env`，至少填写：

```env
ALIYUN_ACCESS_KEY_ID=...
ALIYUN_ACCESS_KEY_SECRET=...
CUST_SPACE_ID=...
CHATAPP_FROM=8613428277520
CHATAPP_TO=8613800000000
CHATAPP_CHANNEL_TYPE=whatsapp
CHATAPP_TYPE=message
CHATAPP_MESSAGE_TYPE=text
```

程序会先读 `.env`，再用当前 PowerShell 环境变量覆盖同名配置。

## 启动页面和接收服务

```powershell
.\chatapp-demo.ps1 web
```

打开：

```text
http://localhost:8077
```

如果用 cpolar 映射本机 8077：

```powershell
cd D:\cpolar
.\cpolar http 8077
```

把 cpolar 给你的公网地址填到 ChatApp 回调里：

```text
https://你的公网域名/webhook/chatapp
```

## 设置回调

号码级回调用于接收客户发来的消息：

```powershell
$env:WEBHOOK_URL="https://你的公网域名/webhook/chatapp"
.\chatapp-demo.ps1 set-phone-webhook
```

账号级回调用于状态类通知：

```powershell
$env:WEBHOOK_URL="https://你的公网域名/webhook/chatapp"
.\chatapp-demo.ps1 set-account-webhook
```

## 发送消息

页面里可以发，也可以命令行发：

```powershell
.\chatapp-demo.ps1 send
```

页面发送区支持两种模式：

- `普通文本`：选择左侧联系人后直接输入内容发送。
- `模板消息`：先运行 `.\chatapp-demo.ps1 sync-templates` 同步模板库，然后在页面选择模板、填写参数并发送。页面会用本地 `data/templates.json` 里的正文实时预览发送内容。

发送方号码不在页面里选择，统一使用 `.env` 里的 `CHATAPP_FROM`。

表情按钮使用 demo 内置的本地表情面板，不依赖外部网页或 CDN。

图片、视频、文件按钮已经接入真实发送链路：

1. 页面选择附件并点击发送。
2. Java 后端调用 `GetChatappUploadAuthorization` 获取临时 OSS 上传凭证。
3. 后端把附件上传到授权的 OSS 地址。
4. 后端调用 `SendChatappMessage`，按 `MessageType=image/video/document` 发送，并把 `Content` 组装成 `{"link":"...","caption":"...","fileName":"..."}`。
5. 本地 `data/messages.jsonl` 会保存 `mediaType/mediaUrl/fileName/mimeType/caption/objectKey`，页面会把图片、视频或文件链接展示在消息气泡里。

### 发送幂等与网络重试

- 页面为每个发送草稿生成稳定的 `clientRequestId`，文本、模板、图片、视频和文件共用同一套幂等合同。
- 请求发送期间按钮会被禁用；如果网络失败，未修改草稿时再次发送会复用原 `clientRequestId`，不会创建新的上游消息。
- 成功消息会把 `clientRequestId` 和请求指纹写入 `data/messages.jsonl`，服务端收到顺序重试时直接返回已保存结果。
- 同一个 `clientRequestId` 携带不同内容会返回 HTTP `409`，避免错误复用请求键。
- 上游调用结果不确定时，同键失败结果会在进程内保留 24 小时（最多 10000 条），这段时间内重试只返回原失败，不会再次调用 `SendChatappMessage`。
- 浏览器 multipart 上传按 UTF-8 解析普通字段、`filename` 和 `filename*`，中文说明与中文文件名不会再以 ISO-8859-1 乱码进入上游 payload。

### 私有 OSS 图片预览

- ChatApp 上传授权返回的 OSS 桶是私有桶，直接把对象 URL 放进 `<img>` 会收到 `403 AccessDenied`。
- 页面现在统一访问 `/api/media?id=<messageId>`，不再向浏览器暴露私有 OSS 地址或临时访问凭证。
- 新上传附件会同时保存到 `data/media/`，页面优先读取本地受控副本；该目录是运行数据，不应提交到 Git。
- CAMS 上传临时凭证只允许写入，真实 OSS 响应会拒绝 `oss:GetObject`；因此修改前已发送、且没有本地副本的历史图片无法恢复。
- 历史图片缺少本地副本时，页面会显示“历史图片未在本地留存，请重新发送”，不再渲染一个必然 403 的破损图片。
- 媒体代理只接受 `data/messages.jsonl` 中已有的消息 ID，不能用它读取任意 OSS 对象。

附件默认大小上限是 20MB，可在 `.env` 中调整：

```env
MEDIA_MAX_BYTES=20971520
```

普通文本模式下，程序会自动把：

```env
CHATAPP_MESSAGE=hello
```

转换成阿里云要求的：

```json
{"text":"hello"}
```

如果对方没有在 24 小时客服窗口内给你发过消息，WhatsApp 通常要求先发模板消息：

```env
CHATAPP_SEND_MODE=template
CHATAPP_TEMPLATE_CODE=...
CHATAPP_TEMPLATE_NAME=...
CHATAPP_LANGUAGE=zh_CN
CHATAPP_TEMPLATE_PARAMS_JSON={"1":"测试"}
```

注意：历史消息里通常只有模板名称/编号和参数，不会自动带完整正文。这个 demo 会先把模板库同步到本地，再用 `templateCode + languageCode` 找模板正文并替换参数。

## 同步模板库

先运行：

```powershell
.\chatapp-demo.ps1 sync-templates
```

它会调用 `ListChatappTemplate` 拿模板 code/name/language，再逐个调用 `GetChatappTemplateDetail` 拿正文和占位符，保存到：

```text
data/templates.json
```

查看本地模板库：

```powershell
.\chatapp-demo.ps1 templates
```

可选过滤条件写在 `.env`：

```env
TEMPLATE_FILE=data/templates.json
TEMPLATE_LANGUAGE=zh_CN
TEMPLATE_NAME=
TEMPLATE_CODE=
TEMPLATE_AUDIT_STATUS=
TEMPLATE_CATEGORY=
TEMPLATE_TYPE=
```

## 补拉历史消息

运行：

```powershell
.\chatapp-demo.ps1 sync
```

默认情况下，它会先刷新模板库，再调用 `ListChatappMessage`，把阿里云 ChatApp 能查到的消息写入：

```text
data/messages.jsonl
```

命令输出示例：

```json
{"fetched":12,"saved":3,"skipped":9,"templatesFetched":2,"templatesSaved":2,"templatesSkipped":0}
```

- `fetched`：接口返回条数
- `saved`：本次新写入本地的条数
- `skipped`：本地已经存在、被去重跳过的条数
- `templatesFetched/templatesSaved/templatesSkipped`：本次模板库同步结果

可选过滤条件写在 `.env`：

```env
SYNC_PAGE_SIZE=50
SYNC_MAX_PAGES=10
SYNC_LOOKBACK_DAYS=7
SYNC_USER_NUMBER=
SYNC_START_TIME=
SYNC_END_TIME=
SYNC_START_TIME_STR=2026-07-10 00:00:00
SYNC_END_TIME_STR=2026-07-10 23:59:59
SYNC_MESSAGE_STATUS=
SYNC_CLIENT_ACCEPT_STATUS=
SYNC_TEMPLATES_BEFORE_MESSAGES=true
```

`ListChatappMessage` 要求必须传开始时间。默认会补拉最近 7 天；如果你要拉更早的，把 `SYNC_LOOKBACK_DAYS` 改大，或者填写 `SYNC_START_TIME_STR` / `SYNC_END_TIME_STR`。

如果历史消息是模板消息，页面会优先用 `data/templates.json` 里的模板正文替换 `$(text)`、`$(text1)`、`{{1}}`、`{{text}}` 等占位符。找不到模板时，才回退显示类似 `模板消息 wl: 参数1 / 参数2 / 参数3`。

注意：`sync` 只能补拉阿里云 ChatApp/CAMS 能查到的记录，不能保证拉到 WhatsApp Business App 里更早的完整私聊历史。

## 本地模拟收消息

不用等真实 webhook，也可以先模拟一条进来的消息：

```powershell
.\chatapp-demo.ps1 mock-inbound
```

## 数据存储

消息存在：

```text
data/messages.jsonl
```

模板库存在：

```text
data/templates.json
```

`messages.jsonl` 每行一条 JSON。删除消息文件可以清空 demo 页面数据；删除模板文件后需要重新运行 `sync-templates`。
