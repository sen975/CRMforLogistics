# WhatsApp Business API Direct Demo

这个 demo 不调用 Chatwoot。它用 Python 直接对接 Meta WhatsApp Business Cloud API，实现和 Chatwoot WhatsApp 通道类似的能力：

- 发送文本消息
- 发送模板消息
- 发送图片/文件/音频/视频链接消息
- 接收 Meta Webhook 入站消息
- 接收消息状态回执
- SQLite 本地存储和检索
- `dry_run` 模式查看真实请求体，不实际发送
- `real` 模式直连 Meta Graph API

## 启动

```powershell
cd D:\WorkItems\CRMforLogistics\demo\whatsapp-business-api-demo
python app.py --host 127.0.0.1 --port 8072
```

打开：

```text
http://127.0.0.1:8072
```

## 需要的 Meta 配置

从 Meta for Developers / WhatsApp Cloud API 后台获取：

- `phone_number_id`: WhatsApp 测试号或正式号的 Phone number ID
- `access_token`: Cloud API 访问令牌
- `verify_token`: 你自己设置的一串字符串，用于 Meta webhook 验证
- `graph_version`: 默认 `v20.0`，可按你的 Meta 应用版本调整
- `business_account_id`: 可选，后续做模板列表/模板管理时需要

## 真实发送怎么测

1. 在页面把 `mode` 改成 `real`。
2. 填 `phone_number_id` 和 `access_token`。
3. 先用 Meta 测试号码允许的 recipient 测试。
4. 点击“发送文本”或“发送模板”。

主动首发给客户通常要使用已审核 WhatsApp Template；普通文本适合 24 小时客户服务窗口内的会话。

## Webhook 怎么配

本地地址：

```text
http://127.0.0.1:8072/webhook/whatsapp
```

Meta 云端无法访问你的 `127.0.0.1`，真实 webhook 需要部署到服务器或使用内网穿透。

服务器启动示例：

```powershell
python app.py --host 0.0.0.0 --port 8072
```

Meta Webhook Callback URL 示例：

```text
https://你的域名/webhook/whatsapp
```

Verify Token 填页面配置里的同一个 `verify_token`。

## API

- `GET /api/status`
- `POST /api/config`
- `POST /api/send-text`
- `POST /api/send-template`
- `POST /api/send-media`
- `GET /webhook/whatsapp` Meta webhook 验证
- `POST /webhook/whatsapp` Meta webhook 事件接收
- `GET /api/messages?q=keyword`
- `GET /api/events`
- `GET /api/statuses`

## 测试

```powershell
python -m unittest discover -s tests -v
```