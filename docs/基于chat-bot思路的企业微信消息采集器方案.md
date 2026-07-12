# 基于 chat-bot 思路的企业微信消息采集器方案

## 1. 文档定位

本文用于说明：在跨境物流 AI 协作型 CRM 项目中，是否可以借鉴 `wangrongding/wechat-bot` 这类开源微信 bot 项目的架构，设计一个企业微信消息采集器。

本文不是企业微信私有协议实现说明，也不是自动发送方案。当前项目第一阶段仍以 Java CRM 后端和 React 销售工作台为主，企业微信方向坚持以下边界：

1. 不把 Chatwoot 作为 MVP 核心底座。
2. 不把企业微信客服 WeCom KF 当成销售个人沟通能力。
3. 不代替销售个人向个人微信客户发送企业微信一对一消息。
4. 不把非官方采集作为正式交付主线或唯一数据源。
5. 可以在授权测试环境中验证只读消息采集，并将结果进入 CRM 沟通时间线。

## 2. 核心结论

可以复用 `chat-bot` 的架构思想，但不建议直接复用其微信协议实现。

可复用的是：

```text
消息事件入口
  -> 消息标准化
  -> 本地缓存 / 队列
  -> CRM 接收 API
  -> 联系人匹配
  -> 沟通时间线
  -> AI 摘要 / 跟进 / 回复草稿
```

不应复用为：

```text
个人微信扫码登录能力
  -> 企业微信私聊稳定接入
  -> 自动代替销售发送
  -> 正式客户交付承诺
```

推荐方案是做一个渠道中立的 `WeCom Collector Agent`，让它只负责采集和上报标准化消息。CRM 的联系人、企业、权限、时间线和 AI 能力仍由 Java 后端统一拥有。

## 3. 与 chat-bot 项目的关系

`chat-bot` 类项目通常具备以下设计：

| chat-bot 能力 | 是否可借鉴 | 在本项目中的处理 |
| --- | --- | --- |
| bot 生命周期管理 | 可借鉴 | 转换为采集器实例生命周期管理 |
| `on(message)` 消息事件 | 可借鉴 | 转换为 `collector_message_event` |
| 本地 JSONL / messageStore | 可借鉴 | 用作本地 outbox 和故障补偿思路 |
| 命令路由 / 自动回复 | 不建议复用 | MVP 禁止自动回复企业微信 |
| `sendMessage` | 不复用 | 企业微信销售场景只做 AI 草稿和复制话术 |
| 个人微信登录协议 | 不复用 | 企业微信需独立来源，优先官方能力 |
| 微信联系人缓存 | 部分借鉴 | 只能作为弱匹配线索，不能直接作为 CRM 真相 |

因此，正确的二开方式不是 fork 后改协议，而是抽象出类似下面的采集器骨架：

```text
Collector Runtime
  -> Source Plugin
  -> Message Normalizer
  -> Local Outbox
  -> CRM Uploader
  -> Heartbeat Reporter
```

不同消息来源只作为 `Source Plugin` 接入，不能污染 CRM 核心模型。

## 4. 推荐总体架构

```text
                 企业微信客户联系 API
                         ↓
                 WeCom Contact Sync
                         ↓
企业 / 联系人 / 渠道身份 / 销售归属  ← Java CRM Core
                         ↑
                         │
WeCom Collector Agent -> Channel Event API -> Message Normalize Service
                         ↓
              Contact Identity Match Service
                         ↓
             Communication Timeline Service
                         ↓
        React 销售沟通工作台 / AI 协作层
```

架构分成三层：

1. CRM 核心层：Java Spring Boot，拥有企业、联系人、渠道身份、消息、权限和审计。
2. 渠道接入层：官方 API、会话内容存档、实验性客户端采集都只通过统一事件接口进入 CRM。
3. 前端工作台层：React 只展示会话、联系人和 AI 草稿，不直接接触采集协议。

## 5. 两条线路

### 5.1 正式产品线

正式产品线用于客户交付和 MVP 主线：

```text
企业微信客户联系 API
  -> 同步销售成员
  -> 同步外部联系人
  -> 同步客户归属
  -> 同步标签 / 备注
  -> 绑定 CRM 联系人
  -> AI 草稿 / 复制话术 / 唤起企业微信
```

如客户已开通企业微信会话内容存档，则增加：

```text
企业微信会话内容存档
  -> 拉取聊天记录
  -> 解密 / 标准化
  -> 联系人匹配
  -> CRM 时间线
```

正式产品线的优点：

1. 合规边界清晰。
2. 稳定性可解释。
3. 适合写入 PRD 和客户合同。
4. 可作为长期数据来源。

### 5.2 技术探索线

技术探索线只用于授权测试环境，不作为 MVP 成败条件：

```text
企业微信只读采集器 PoC
  -> 采集测试账号消息
  -> 标准化为内部事件
  -> 写入待归档池
  -> 人工匹配联系人
  -> 展示实验性来源
```

技术探索线必须满足：

1. 只读，不发送。
2. 使用测试账号，不使用正式销售主号。
3. 有开关和停止条件。
4. 采集失败不影响 CRM 主系统。
5. 页面明确展示 `experimental` 或 `非官方实验来源`。

## 6. Collector Agent 设计

### 6.1 职责

`WeCom Collector Agent` 是一个独立进程或本地服务，职责是：

1. 管理采集实例生命周期。
2. 接收某个消息来源的原始事件。
3. 转换为 CRM 统一消息事件。
4. 写入本地 outbox，防止网络失败丢消息。
5. 上传到 Java CRM 的 Channel Event API。
6. 上报心跳、版本、登录状态和错误状态。

### 6.2 不负责的事情

采集器不负责：

1. 判断联系人属于哪家企业。
2. 修改 CRM 联系人主数据。
3. 生成 AI 回复。
4. 自动发送企业微信消息。
5. 处理销售权限。
6. 保存长期业务真相。

这些都必须归 Java CRM 后端负责。

### 6.3 Source Plugin

采集器内部建议保留 Source Plugin 接口：

```text
Source Plugin
  -> start()
  -> stop()
  -> getStatus()
  -> onRawEvent(event)
```

第一阶段建议实现两个插件：

| 插件 | 作用 | 风险 |
| --- | --- | --- |
| `simulator_source` | 模拟企业微信消息，用于前后端联调 | 低 |
| `wecom_client_capture_poc` | 授权测试环境的只读采集入口 | 高 |

后续可追加：

| 插件 | 作用 | 风险 |
| --- | --- | --- |
| `wecom_archive_source` | 企业微信会话内容存档 | 低到中 |
| `import_file_source` | 手动导入聊天记录 | 低 |

这样做的好处是：即使某个非官方采集源失效，CRM 主系统也不会被推翻。

## 7. CRM 接收 API

建议 Java 后端提供统一接收接口，而不是为每种来源设计一套表。

### 7.1 消息事件接收

```http
POST /api/channel-events/wecom/messages
```

请求体示例：

```json
{
  "tenantId": "tenant_001",
  "collectorInstanceId": "collector_001",
  "channel": "wecom",
  "sourceType": "wecom_client_capture",
  "officialSource": false,
  "riskLevel": "experimental",
  "salesUserId": "sales_001",
  "rawConversationKey": "raw_conv_abc",
  "rawMessageId": "raw_msg_123",
  "direction": "inbound",
  "messageType": "text",
  "content": "客户发来的消息",
  "senderHint": "客户昵称或标识线索",
  "receiverHint": "销售账号线索",
  "sentAt": "2026-07-02T10:00:00+08:00",
  "capturedAt": "2026-07-02T10:00:03+08:00",
  "rawRef": {
    "sourceVersion": "poc-0.1.0"
  }
}
```

### 7.2 心跳上报

```http
POST /api/channel-events/wecom/collector-heartbeats
```

请求体示例：

```json
{
  "tenantId": "tenant_001",
  "collectorInstanceId": "collector_001",
  "salesUserId": "sales_001",
  "status": "online",
  "collectorVersion": "0.1.0",
  "sourceType": "wecom_client_capture",
  "lastMessageAt": "2026-07-02T10:00:00+08:00",
  "lastErrorCode": null,
  "lastErrorMessage": null,
  "reportedAt": "2026-07-02T10:00:05+08:00"
}
```

## 8. 核心数据模型

### 8.1 Collector Instance

| 字段 | 说明 |
| --- | --- |
| `id` | 采集器实例 ID |
| `tenantId` | 租户 ID |
| `salesUserId` | 对应销售 |
| `sourceType` | `wecom_client_capture` / `wecom_archive` / `simulator` |
| `status` | `created` / `login_required` / `online` / `offline` / `blocked` / `disabled` |
| `lastHeartbeatAt` | 最近心跳时间 |
| `lastMessageAt` | 最近消息时间 |
| `lastErrorCode` | 最近错误码 |
| `lastErrorMessage` | 最近错误描述 |
| `enabled` | 是否启用 |

### 8.2 Channel Identity

| 字段 | 说明 |
| --- | --- |
| `id` | 渠道身份 ID |
| `contactId` | CRM 联系人 ID |
| `channel` | `wecom` / `whatsapp` / `email` / `phone` |
| `identityType` | `official_external_contact` / `client_capture_hint` |
| `externalUserId` | 企业微信官方外部联系人 ID，如可得 |
| `salesUserId` | 销售 ID |
| `displayName` | 显示名 |
| `remarkName` | 销售备注名 |
| `sourceType` | 来源类型 |
| `matchStatus` | `matched` / `ambiguous` / `unmatched` / `manually_bound` |

### 8.3 Communication Message

| 字段 | 说明 |
| --- | --- |
| `id` | 消息 ID |
| `tenantId` | 租户 ID |
| `contactId` | 联系人 ID，可为空 |
| `companyId` | 企业 ID，由联系人企业关系推导 |
| `channel` | 渠道 |
| `sourceType` | 来源类型 |
| `officialSource` | 是否官方来源 |
| `riskLevel` | `official` / `experimental` |
| `direction` | `inbound` / `outbound_observed` / `system` |
| `messageType` | `text` / `image` / `voice` / `file` / `link` / `system` |
| `content` | 文本内容 |
| `rawConversationKey` | 原始会话标识 |
| `rawMessageId` | 原始消息 ID |
| `salesUserId` | 对应销售 |
| `sentAt` | 消息发生时间 |
| `capturedAt` | 采集时间 |
| `createdAt` | CRM 入库时间 |

建议唯一键：

```text
tenantId + sourceType + salesUserId + rawConversationKey + rawMessageId
```

Redis 去重只能作为加速，数据库唯一约束必须作为最终事实。

## 9. 联系人匹配规则

企业详情页不能直接展示所有采集消息，必须先经过联系人归属。

推荐规则：

1. 采集消息先进入销售沟通工作台或待归档池。
2. 优先使用企业微信客户联系 API 的 `externalUserId` 匹配联系人。
3. 其次使用销售备注、手机号、邮箱、WhatsApp 账号、昵称等弱线索。
4. 弱匹配不能自动进入企业详情。
5. 人工绑定后，后续同一 `rawConversationKey` 可继续归档到同一联系人。
6. 联系人只有被使用者设定到某个企业后，相关消息才出现在企业详情。

匹配状态：

```text
unmatched      未匹配
ambiguous      多个候选，需要人工确认
matched        系统确定匹配
manually_bound 人工绑定
ignored        忽略
```

## 10. 前端最小能力

React 前端第一阶段只需要支持采集器相关的最小闭环：

1. 渠道配置页：展示企业微信客户联系配置和采集器状态。
2. 采集器状态页：在线、离线、需要登录、已禁用、最近错误。
3. 待归档消息池：展示未匹配或模糊匹配的企业微信消息。
4. 联系人绑定弹窗：将消息会话绑定到已有联系人或新建联系人。
5. 联系人详情时间线：展示已归档企业微信消息。
6. 企业详情时间线：只展示已归属联系人的消息。
7. AI 草稿弹窗：生成回复建议，提供复制话术和尝试唤起企业微信客户端。

页面必须明确区分：

| 来源 | 页面展示建议 |
| --- | --- |
| 企业微信客户联系 API | 官方资料同步 |
| 企业微信会话内容存档 | 官方聊天归档 |
| 企业微信客户端采集 PoC | 实验性采集来源 |

## 11. 部署建议

### 11.1 正式部署

正式部署以 Java CRM 服务为中心：

```text
Java CRM API
  -> PostgreSQL / MySQL
  -> Redis
  -> 对象存储
  -> React Web
  -> 官方渠道连接器
```

企业微信客户联系 API 和会话内容存档连接器建议部署在服务端。

### 11.2 探索部署

企业微信只读采集器 PoC 建议独立部署：

```text
销售测试机 / 测试服务器
  -> WeCom Collector Agent
  -> 本地 outbox
  -> HTTPS 上传 CRM
```

PoC 采集器必须具备：

1. 一键停用开关。
2. 本地日志脱敏。
3. 上传失败重试。
4. 心跳上报。
5. 版本记录。
6. 明确测试账号绑定。

## 12. 风险与控制

| 风险 | 等级 | 控制方式 |
| --- | --- | --- |
| 企业微信协议或客户端变化 | 高 | 不把采集器作为正式主线，Source Plugin 可替换 |
| 账号触发安全校验 | 高 | 只使用测试账号，异常立即停用 |
| 消息漏采 | 中 | 心跳、断点、outbox、人工对账 |
| 消息重复 | 中 | DB 唯一键幂等 |
| 联系人匹配错误 | 高 | 弱匹配进待归档池，人工确认后入企业详情 |
| 合规争议 | 高 | 授权、告知、审计、数据最小化 |
| 明文敏感数据泄露 | 高 | 日志脱敏、密钥加密、权限控制 |
| 产品承诺漂移 | 高 | 文档和页面明确标注实验性来源 |

## 13. 实施阶段

### 阶段 0：架构闭环

目标：不接真实企业微信，先跑通标准事件进入 CRM。

交付：

1. Channel Event API。
2. `Communication Message` 入库。
3. 模拟消息源。
4. 待归档池。
5. 联系人绑定。
6. 联系人/企业时间线展示。

验收：

1. 模拟 30 条企业微信消息。
2. 未匹配消息进入待归档池。
3. 人工绑定后进入联系人时间线。
4. 联系人设定企业后进入企业详情。
5. 重复事件不重复入库。

### 阶段 1：官方企业微信资料同步

目标：用官方客户联系 API 建立联系人匹配基础。

交付：

1. 企业微信成员同步。
2. 外部联系人同步。
3. 客户归属同步。
4. 标签同步。
5. CRM 联系人绑定。

验收：

1. 系统可看到销售名下外部联系人。
2. 可把外部联系人绑定到 CRM 联系人。
3. 可基于官方身份辅助消息匹配。

### 阶段 2：只读采集 PoC

目标：在授权测试环境验证企业微信消息只读进入 CRM。

交付：

1. Collector Agent 骨架。
2. Source Plugin 接口。
3. 本地 outbox。
4. 心跳上报。
5. 实验性来源展示。

验收：

1. 测试账号文本消息可进入 CRM。
2. 能区分客户发给销售和销售发给客户。
3. 能拿到稳定会话键和消息键。
4. 断线重启后不重复写入。
5. 页面明确标注实验性来源。

### 阶段 3：官方会话内容存档接入

目标：如客户开通官方能力，则把聊天记录来源切换到正式通道。

交付：

1. 会话内容存档连接器。
2. 消息解密与标准化。
3. 与 `Communication Message` 共用同一模型。
4. 来源标记为 `official`。

验收：

1. 官方归档消息进入同一时间线。
2. 与实验采集来源可区分。
3. 幂等键稳定。
4. 联系人匹配规则复用。

## 14. 停止条件

出现以下任一情况，应停止企业微信只读采集 PoC：

1. 客户要求自动发送、群发或绕过风控。
2. 必须使用正式销售主号验证。
3. 无法取得企业内部授权和告知。
4. 测试账号出现安全提醒或登录异常。
5. 消息标识无法稳定去重。
6. 联系人匹配准确率不足，且无法通过人工确认兜底。
7. 采集器失效会影响正式 CRM 主流程。

## 15. 推荐决策

推荐采用以下方案：

```text
Java CRM 作为唯一业务真相
  + 官方企业微信客户联系同步作为正式主线
  + 会话内容存档作为正式聊天归档来源
  + chat-bot 思路改造成只读 Collector Agent 架构
  + 企业微信客户端采集只作为授权测试 PoC
```

这样既保留了探索企业微信消息读取的空间，又不会把 CRM 主系统绑定在不稳定的非官方链路上。

最终产品口径建议：

```text
系统支持企业微信客户资料同步和沟通记录归档能力。正式消息归档优先采用企业微信会话内容存档；在授权测试环境中，可评估企业微信客户端只读采集器，用于验证销售沟通消息进入 CRM 时间线的可行性。系统不代替销售个人发送企业微信消息。
```
