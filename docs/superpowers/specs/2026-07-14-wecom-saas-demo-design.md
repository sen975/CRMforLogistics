# 企业微信 SaaS Demo 设计

日期：2026-07-14

## 目标

新建 `demo/wecom-saas-demo`，作为企业微信 SaaS 系统原型。它展示服务商给客户企业提供的核心工作台能力：客户管理、消息接收与发送、附件上传、微信客服、会话内容存档、数据 API 同步，以及消息详情 JSON 检查。

这个 demo 的第一闭环是“能在本地启动、看到完整产品形态、用模拟数据和本地存储跑通主要链路、为真实企业微信 API 接入预留清晰 adapter”。它不直接改造 `demo/message-center-demo`，也不把邮件或 ChatApp 逻辑混入企业微信 SaaS 主线。

## 非目标

- 不在第一版实现完整第三方服务商代开发安装、授权回调和多企业上线流程。
- 不硬编码企业微信密钥、会话存档 Secret、RSA 私钥或客户真实数据。
- 不把会话内容存档当成普通消息回调；两者是独立链路。
- 不引入 Spring Boot 等重框架；除非后续用户明确要求生产化改造。

## 推荐架构

使用 Java 17 + Maven + 内置 HTTP Server，沿用仓库现有 demo 的轻量风格。数据先落本地 JSON/JSONL，便于调试和查看。

主要 owner：

- `TenantRegistry`：租户与企业微信授权配置。记录 `tenantId`、`corpId`、`agentId`、`suiteId`、安装状态、能力范围和 token 缓存状态。
- `ContactStore`：客户、外部联系人、企业成员、微信客服访客、标签和客户群的本地投影。
- `MessageStore`：统一消息模型。支持 `wecom_app`、`wecom_kf`、`archive`、`data_api` 四类来源，保存方向、状态、正文、附件、raw JSON 和关联客户。
- `WecomApiClient`：服务端 API adapter。第一版提供清晰接口和模拟实现，后续接真实 `access_token`、上传临时素材、发送应用消息、微信客服消息。
- `WebhookController`：接收企业微信应用回调和微信客服回调，保存原始 payload，并投影为统一消息。
- `DataSyncCenter`：数据 API 同步中心。记录客户、客户群、标签、成员等同步任务的 cursor、状态、最近执行时间和错误。
- `ArchiveImportCenter`：会话内容存档导入中心。第一版从本地 JSONL 导入或展示模拟 archive 消息，后续可读取 `weworkapi_python_yuewei/runtime/wecom/archive/messages.jsonl`。
- `UiRenderer`：三栏 SaaS 工作台页面。

## 页面设计

第一屏就是可用工作台，不做营销落地页。

布局参考用户截图：

- 左栏：租户切换、客户/会话列表、客户来源筛选。
- 中栏：消息时间线、渠道标签、发送区。
- 右栏：当前客户资料、消息详情、raw JSON、同步状态。

顶部或侧边使用业务标签切换：

- 客户
- 消息
- 微信客服
- 会话存档
- 数据同步
- 设置

发送区第一版支持文本发送模拟和附件上传模拟。附件先保存到 demo 本地 `data/uploads/`，消息里记录文件名、MIME、大小和本地 URL。后续真实接入时由 `WecomApiClient` 替换为企业微信素材上传。

## 数据流

应用回调：

1. 企业微信回调进入 `/webhook/wecom/app`。
2. `WebhookController` 保存 raw payload。
3. `MessageStore` 生成统一消息。
4. UI 刷新消息线程和右侧详情。

微信客服回调：

1. 回调进入 `/webhook/wecom/kf`。
2. 系统识别访客、客服账号和消息。
3. 写入 `ContactStore` 与 `MessageStore`。

发送消息：

1. UI 调用 `/api/messages/send`。
2. `WecomApiClient` 根据 channel 选择应用消息或微信客服消息。
3. 第一版写入本地成功/失败状态；真实接入后写入企业微信响应和错误码。

附件上传：

1. UI 调用 `/api/attachments`。
2. 文件保存到本地 upload 目录。
3. 生成附件记录并可被消息发送引用。

数据 API 同步：

1. UI 或命令触发 `/api/sync/run`。
2. `DataSyncCenter` 拉取或模拟企业客户、客户群、标签、成员数据。
3. 同步结果更新客户投影和任务状态。

会话存档：

1. `ArchiveImportCenter` 从本地 archive JSONL 或 seed 数据导入。
2. 解密和拉取仍由独立 worker 负责，不在 UI 进程里直接调用 Finance SDK。
3. 导入后的 archive 消息进入 `MessageStore`，并保留 raw JSON。

## API 草案

- `GET /`：工作台页面。
- `GET /api/tenants`：租户列表和安装状态。
- `GET /api/contacts`：客户/访客/成员投影。
- `GET /api/messages?contactId=...`：联系人消息线程。
- `GET /api/messages/{id}`：单条消息详情。
- `POST /api/messages/send`：发送应用消息或微信客服消息。
- `POST /api/attachments`：上传附件到本地 demo 存储。
- `GET /api/sync/jobs`：数据同步任务状态。
- `POST /api/sync/run`：触发一次数据 API 同步。
- `POST /api/archive/import`：导入会话存档 JSONL。
- `POST /webhook/wecom/app`：企业微信应用回调入口。
- `POST /webhook/wecom/kf`：微信客服回调入口。

## 错误处理

所有 API 返回结构化 JSON：

```json
{
  "error": "WecomApiUnavailable",
  "message": "企业微信真实 API 未配置，已使用本地模拟发送。",
  "context": {
    "channel": "wecom_kf"
  }
}
```

配置缺失、API 未启用、附件过大、未知租户、未知联系人、同步失败和导入失败都必须在 UI 上可见，不能静默吞掉。

## 验收

第一版完成后至少运行：

- `mvn -q test`
- `mvn -q compile`
- `mvn -q exec:java "-Dexec.args=web"`

手动验收：

- 打开 `http://localhost:8098` 能看到三栏工作台。
- 左侧能切换客户/访客。
- 中间能查看消息线程并模拟发送文本。
- 能上传附件并在消息详情里看到附件元数据。
- 微信客服、会话存档、数据同步页面有真实状态和示例数据。
- 右侧能看到选中消息的 raw JSON。

## 后续阶段

第二阶段再接真实企业微信：

- 第三方服务商 suite ticket、授权安装和 access_token 缓存。
- 企业微信服务端 API 的真实请求签名、错误码映射和重试。
- 微信客服真实收发。
- 数据 API 增量同步。
- 从服务器会话存档 worker 输出目录自动导入 archive 消息。
