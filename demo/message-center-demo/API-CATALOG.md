# 企业微信 & ChatApp(CAMS) API 参考（实现 Adapter 用）

> 整理时间：2026-07-23

---

## 如何使用本文档

**不要逐个手写 HTTP 请求。** 两个渠道都有成熟 Java SDK，SDK 的方法签名就是入参出参。本文档提供：

| 内容 | 作用 |
|------|------|
| SDK Maven 坐标 | 直接 import 到项目里 |
| 官方文档链接 | 查每个 API 的完整字段定义 |
| P0 核心 API 详细 spec | SendChatappMessage 等高频调用（微信客服 sync_msg/send_msg 已从本篇移除） |
| 已知示例 | 你项目里已有代码的参考 |

---

## 一、ChatApp (CAMS) — Aliyun SDK

### Maven 坐标

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>cams20200606</artifactId>
    <version>1.x.x</version> <!-- 用最新版 -->
</dependency>
```

### 官方文档入口

**总览（点任一 API 名进入完整 schema）：**
https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-overview

### 已实现的 API（你项目代码参考）

| API | 你的代码调用位置 |
|-----|-----------------|
| SendChatappMessage | `CamsOutboundAdapter.java` — 发送消息 |
| GetChatappUploadAuthorization | `MediaGateway.java` — 获取文件上传鉴权 |

### 待实现 API & 官方文档直达链接

#### P0 — 模板管理

| API | 官方文档 |
|-----|---------|
| CreateChatappTemplate | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-createchatapptemplate |
| GetChatappTemplateDetail | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-getchatapptemplatedetail |
| ListChatappTemplate | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-listchatapptemplate |
| ModifyChatappTemplate | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-modifychatapptemplate |
| DeleteChatappTemplate | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-deletechatapptemplate |

#### P1 — 号码管理

| API | 官方文档 |
|-----|---------|
| QueryChatappPhoneNumbers | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-querychatappphonenumbers |
| QueryChatappBindWaba | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-querychatappbindwaba |
| GetWhatsappHealthStatus | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-getwhatsapphealthstatus |

#### P2 — 其他

| API | 官方文档 |
|-----|---------|
| SendChatappMassMessage | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-sendchatappmassmessage |
| IM 群组 API 列表 | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-overview 下 "IM群组管理" 分类 |
| 商品目录 | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-overview 下 "商品目录" 分类 |
| 统计指标 | https://help.aliyun.com/zh/chatapp/developer-reference/api-cams-2020-06-06-overview 下 "统计指标" 分类 |

### SDK 调用示例（来自你现有代码）

```java
// CamsOutboundAdapter.java 中的模式
SendChatappMessageRequest req = new SendChatappMessageRequest()
    .setType("message")
    .setChannelType("whatsapp")
    .setFrom(phoneNumber)
    .setTo(customerNumber)
    .setContent(content);
SendChatappMessageResponse resp = client.sendChatappMessage(req);
```

---

## 二、企业微信 — 代开发应用（服务商代开发）

> 定位：**代开发应用**（服务商代企业开发的自建应用）。企业授权后，服务商持 `suite_access_token`，用 `POST /cgi-bin/service/get_corp_token`（请求体 `auth_corpid` + `permanent_code`）换取企业 `access_token`，之后调用自建应用接口；登录身份再用 `GET /cgi-bin/auth/getuserinfo` 换成员 `userid`。
> 代开发应用 ≈ 自建应用：内部群聊（appchat）、客户联系、通讯录读、应用消息、会话存档等都可用；唯一明确限制是**无通讯录编辑权限**、**不可调「设置应用」接口**。

### 凭证模型（代开发应用）

| 步骤 | 接口 | 说明 |
|------|------|------|
| 1. 企业授权 | — | 服务商生成授权二维码 → 企业管理员扫码 → 回调返回 `permanent_code`（永久授权码，一次有效） |
| 2. 获取企业 access_token | `POST /cgi-bin/service/get_corp_token?suite_access_token=...`（body `auth_corpid`+`permanent_code`） | 服务商接口，返回 `access_token`（7200s） |
| 3. 换取成员身份 | `GET /cgi-bin/auth/getuserinfo?access_token=...&code=...` | 登录 code 换真实 `userid` |

- 后续所有业务接口都用这个企业 access_token；成员用**真实 `userid`**（不是 open_userid）。
- 官方文档：获取企业凭证 https://developer.work.weixin.qq.com/document/path/90605 ｜ 获取访问用户身份 https://developer.work.weixin.qq.com/document/path/91023 ｜ Web 登录组件（CorpApp） https://developer.work.weixin.qq.com/document/path/98171

### Maven 坐标

```xml
<!-- 方案 A: 纯企业微信 SDK（280+ 接口） -->
<dependency>
    <groupId>cn.felord</groupId>
    <artifactId>wecom-sdk</artifactId>
    <version>1.3.3</version>
</dependency>

<!-- 方案 B: WxJava 全微信生态（含企业微信） -->
<dependency>
    <groupId>com.github.binarywang</groupId>
    <artifactId>weixin-java-cp</artifactId>
    <version>4.7.0</version>
</dependency>
```

### 已实现

| API | 你的代码位置 |
|-----|------------|
| 企业 access_token（get_corp_token） | `WeComAuthorizationGateway#getCorpToken`（`/cgi-bin/service/get_corp_token`） |
| 应用消息发送 | `channel/wecom/WeComSendService.java`（`/cgi-bin/message/send`） |
| 会话内容存档 | `channel/wecom/WeComChatDataGateway.java` + `WeComChatDataPublicKeyGateway.java` + `service/wecom/WeComChatDataSyncService.java` |
| JS-SDK 签名（jsapi_ticket） | `channel/wecom/RestWeComViewerHttpGateway.java` |

### Spring P0 接入状态（2026-08）

以下 P0 能力已在 `demo/message-center-spring` 闭合到服务端 API 和管理员工作台。生产凭证仍统一从
`wecom_installations` 解析，经 `service/get_corp_token` 获取企业 token；前端不接触 token、secret 或
`permanent_code`。

| 能力 | Spring owner | 管理页 |
|------|--------------|--------|
| 共享上游调用、token 失效重试 | `channel/wecom/WeComApiClient.java` | — |
| 应用群聊 create/get/update/send | `channel/wecom/WeComAppChatGateway.java` + `service/wecom/WeComAppChatService.java` | 应用群聊 |
| 客户联系 list/get/batch-get/remark | `channel/wecom/WeComExternalContactGateway.java` + `service/wecom/WeComExternalContactService.java` | 客户联系 |
| 客户群 list/get | `channel/wecom/WeComExternalContactGateway.java` + `service/wecom/WeComExternalContactService.java` | 客户群 |
| 成员、部门、标签只读 | `channel/wecom/WeComDirectoryGateway.java` + `service/wecom/WeComDirectoryService.java` | 通讯录 |
| 管理员 HTTP 合同 | `web/WeComP0Controller.java` | `/settings/wecom` |
| API 审计和自清理 | `WeComApiAuditTrail` + `wecom_api_audit` | — |

P0 HTTP 路由统一以 `/api/v1/wecom/installations/{authCorpId}` 开头，全部要求 `ROLE_ADMIN`。应用群聊
没有官方列表接口，工作台只支持按真实 `chatId` 查询、创建、修改和发送，不伪造群聊列表。

> ⚠️ `/cgi-bin/gettoken`（`getDevelopedAppToken`）只留给明确配置的自建应用，代开发安装记录一律走 `get_corp_token`；登录身份走 `getuserinfo`，不再有 `getuserinfo3rd` 分支。

### P0 — 第一阶段

#### 应用群聊会话（内部群聊）★

| API | 说明 |
|-----|------|
| `POST /cgi-bin/appchat/create` | 创建群聊 |
| `POST /cgi-bin/appchat/send` | 发送群消息 |
| `GET /cgi-bin/appchat/get` | 获取群聊会话 |
| `POST /cgi-bin/appchat/update` | 修改群聊会话 |

> 内部群聊是自建应用（含代开发）专属，第三方应用不可调。完整字段见官方「应用群聊会话」文档。

#### 客户联系（读客户 / 客户群，CRM 核心）

| API | 官方文档 |
|-----|---------|
| 客户联系总入口 | https://developer.work.weixin.qq.com/document/path/92109 |
| 获取客户列表 | https://developer.work.weixin.qq.com/document/path/92113 |
| 获取客户详情 | https://developer.work.weixin.qq.com/document/path/92114 |
| 批量获取客户详情 | https://developer.work.weixin.qq.com/document/path/92994 |
| 修改客户备注 | https://developer.work.weixin.qq.com/document/path/92115 |
| 客户群列表 / 详情 | https://developer.work.weixin.qq.com/document/path/92116 |

#### 通讯录（读成员 / 部门）

| API | 官方文档 |
|-----|---------|
| 成员管理（读） | https://developer.work.weixin.qq.com/document/path/90195 |
| 部门管理（读） | https://developer.work.weixin.qq.com/document/path/90206 |
| 标签管理 | https://developer.work.weixin.qq.com/document/path/90210 |

> 代开发读通讯录返回真实 `userid`；但**无通讯录编辑权限**。

#### 身份验证（getuserinfo）

| API | 说明 |
|-----|------|
| `GET /cgi-bin/auth/getuserinfo` | 用 access_token + code 换成员 `userid`（代开发/自建通用） |

### P1 — 客户群发 + 素材管理

| API | 官方文档 / 说明 |
|-----|---------|
| 客户群发 `add_msg_template` | 客户联系 → 群发（AI 话术建议 + 人工确认后触达客户） |
| 素材上传 / 下载 | https://developer.work.weixin.qq.com/document/path/90253 |

### P2 — 获客助手 / 客户朋友圈 / 审批打卡（可选）

| 模块 | 说明 |
|------|------|
| 获客助手（数据专区） | 需管理员定期授权 |
| 客户朋友圈 | 需专门权限 |
| 审批 / 打卡 | 需单独申请权限 |

---

## 三、核心 API 汇总速查

### ChatApp 89 个 API 分组

| 分组 | 数量 | 核心 API |
|------|------|---------|
| IM 群组管理 | 8 | AddChatGroup, ListChatGroup, DeleteChatGroup |
| 实例管理 | 5 | CreateInstance, ListInstance |
| 消息发送 | 3 | **SendChatappMessage**, **SendChatappMassMessage**, WhatsappCall |
| 模板管理 | 6 | **CreateChatappTemplate**, GetChatappTemplateDetail, ListChatappTemplate, DeleteChatappTemplate |
| 号码管理 | 11 | QueryChatappPhoneNumbers, QueryChatappBindWaba |
| 嵌入集成 | 22 | ChatappBindWaba, AddChatappPhoneNumber, ChatappVerifyAndRegister |
| WhatsApp Flow | 10 | CreateFlow, PublishFlow, TriggerChatFlow |
| 商品目录 | 3 | ListProductCatalog, ListProduct |
| Messenger 营销 | 10 | CreateMessageCampaign, CreateCustomAudience |
| 统计指标 | 3 | GetChatappPhoneNumberMetric, GetChatappTemplateMetric |
| 机器人 | 2 | BeeBotChat, BeeBotAssociate |
| Flow 触发器 | 1 | TriggerChatFlow |
| Viber | 3 | AddAuditViberOpen |
| 其他 | 2 | GetPreValidatePhoneId |

### WeCom 代开发应用可用范围

> 代开发应用通过服务商 `get_corp_token`（`auth_corpid` + `permanent_code`）获取企业 access_token，登录身份走 `getuserinfo`，可用以下模块。

| 分组 | 说明 |
|------|------|
| 基础（凭证） | 服务商 get_corp_token（auth_corpid + permanent_code） |
| 应用群聊会话 | 内部群聊（创建 / 发送 / 获取 / 修改） |
| 客户联系 | 客户 / 客户群 / 朋友圈 / 获客助手 → CRM 核心 |
| 消息推送 | 应用消息发送 / 撤回 / 接收 |
| 通讯录 | 读成员 / 部门（无编辑权限） |
| 身份验证 | getuserinfo（access_token + code 换 userid） |
| 会话内容存档 | 企业开通 + 服务商后台配置 |
| 素材管理 | 上传 / 下载 |
| 审批 / 打卡 | 需单独申请权限 |
