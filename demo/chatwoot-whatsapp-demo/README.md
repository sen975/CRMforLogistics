# Chatwoot WhatsApp Demo

这是一个独立的 WhatsApp 私发链路测试台，用来验证 CRM 复用 Chatwoot 的 WhatsApp 通道是否可行。

它不依赖企业微信会话存档，也不读取本地聊天缓存。默认使用 mock 模式，可以不配置 Chatwoot 直接测试：创建联系人、创建会话、发送文本、发送模板、接收 webhook、SQLite 本地留存和检索。

## 启动

```powershell
cd D:\WorkItems\CRMforLogistics\demo\chatwoot-whatsapp-demo
python app.py --host 127.0.0.1 --port 8071
```

打开：

```text
http://127.0.0.1:8071
```

## 配置项

- `mode`: `mock` 或 `real`
- `chatwoot_url`: Chatwoot 站点地址，例如 `https://chat.example.com`
- `account_id`: Chatwoot Account ID
- `inbox_id`: WhatsApp Inbox ID
- `api_token`: Chatwoot 用户 API Token
- `webhook_secret`: 预留给后续签名校验，目前仅保存展示状态

配置会保存到本目录的 `config.json`，token 只在接口返回中显示为 `configured` 或 `missing`。

## 真实 Chatwoot 测试

在页面切换到 `real` 后，需要填：

1. Chatwoot URL
2. Account ID
3. WhatsApp Inbox ID
4. API Token

然后可以点击“发送文本”或“发送模板”。主动给 WhatsApp 手机号首发通常需要使用已审核的 WhatsApp Template；已有会话窗口内才适合直接发普通文本。

## Webhook

本地接收地址：

```text
http://127.0.0.1:8071/webhooks/chatwoot
```

如果部署到服务器，把 Chatwoot 后台 Webhook URL 填成：

```text
http://服务器公网IP:8071/webhooks/chatwoot
```

生产环境建议放在 HTTPS 域名后面，并增加签名校验、鉴权和 IP 白名单。

## API

- `GET /api/status`
- `POST /api/config`
- `POST /api/send-text`
- `POST /api/send-template`
- `POST /webhooks/chatwoot`
- `GET /api/messages?q=keyword`
- `GET /api/webhooks`

## 测试

```powershell
cd D:\WorkItems\CRMforLogistics\demo\chatwoot-whatsapp-demo
python -m unittest discover -s tests -v
```