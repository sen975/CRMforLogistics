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

ChatApp 发送最少需要：

```env
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
CUST_SPACE_ID=
CHATAPP_FROM=
CHATAPP_LANGUAGE=zh_CN
```

模板发送依赖旧 ChatApp demo 的模板缓存：

```env
CHATAPP_TEMPLATE_FILE=../chatapp-send-receive-demo/data/templates.json
```

附件发送会先调用 CAMS 的 `GetChatappUploadAuthorization`，上传到授权 OSS，再调用 `SendChatappMessage`。

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
```

PowerShell 里如果直接用 Maven，参数要加引号：

```powershell
mvn -q exec:java "-Dexec.args=web"
```
