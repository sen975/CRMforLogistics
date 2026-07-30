# 企业微信 & ChatApp(CAMS) API 参考（实现 Adapter 用）

> 整理时间：2026-07-23

---

## 如何使用本文档

**不要逐个手写 HTTP 请求。** 两个渠道都有成熟 Java SDK，SDK 的方法签名就是入参出参。本文档提供：

| 内容 | 作用 |
|------|------|
| SDK Maven 坐标 | 直接 import 到项目里 |
| 官方文档链接 | 查每个 API 的完整字段定义 |
| P0 核心 API 详细 spec | sync_msg / send_msg / SendChatappMessage 等高频调用 |
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

## 二、企业微信 — wecom-sdk + 官方文档

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

### 已实现的 API（你项目代码）

| API | 你的代码位置 |
|-----|------------|
| gettoken | `JdkWeComApiTransport.java` |
| sync_msg（微信客服消息轮询） | `WeComInboundAdapter.java` |

### P0 — 微信客服（消息收发核心）

| API | HTTP | 官方文档 |
|-----|------|---------|
| sync_msg（拉取消息） | POST `/kf/sync_msg` | https://developer.work.weixin.qq.com/document/path/94670 |
| send_msg（发送消息） | POST `/kf/send_msg` | https://developer.work.weixin.qq.com/document/path/94677 |
| send_msg_on_event（事件响应） | POST `/kf/send_msg_on_event` | https://developer.work.weixin.qq.com/document/path/95122 |
| 添加客服账号 | POST `/kf/account/add` | https://developer.work.weixin.qq.com/document/path/94662 |
| 删除客服账号 | POST `/kf/account/del` | https://developer.work.weixin.qq.com/document/path/94663 |
| 修改客服账号 | POST `/kf/account/update` | https://developer.work.weixin.qq.com/document/path/94664 |
| 获取客服账号列表 | GET `/kf/account/list` | https://developer.work.weixin.qq.com/document/path/94661 |
| 添加接待人员 | POST `/kf/servicer/add` | https://developer.work.weixin.qq.com/document/path/94646 |
| 删除接待人员 | POST `/kf/servicer/del` | https://developer.work.weixin.qq.com/document/path/94647 |
| 获取接待人员列表 | GET `/kf/servicer/list` | https://developer.work.weixin.qq.com/document/path/94645 |
| 分配客服会话 | POST `/kf/service_state/trans` | https://developer.work.weixin.qq.com/document/path/94669 |
| 获取客户基础信息 | POST `/kf/customer/batchget` | https://developer.work.weixin.qq.com/document/path/95159 |

### P1 — 客户联系

| API | 官方文档 |
|-----|---------|
| 获取客户列表 | https://developer.work.weixin.qq.com/document/path/92113 |
| 获取客户详情 | https://developer.work.weixin.qq.com/document/path/92114 |
| 批量获取客户详情 | https://developer.work.weixin.qq.com/document/path/92994 |
| 修改客户备注 | https://developer.work.weixin.qq.com/document/path/92115 |
| 客户联系总入口 | https://developer.work.weixin.qq.com/document/path/92109 |

### P1 — 应用消息发送

| API | 官方文档 |
|-----|---------|
| 发送应用消息 | https://developer.work.weixin.qq.com/document/path/90236 |
| 撤回应用消息 | https://developer.work.weixin.qq.com/document/path/94867 |
| 接收消息与事件 | https://developer.work.weixin.qq.com/document/path/90238 |

### P2 — 通讯录

| API | 官方文档 |
|-----|---------|
| 成员管理入口 | https://developer.work.weixin.qq.com/document/path/90195 |
| 部门管理入口 | https://developer.work.weixin.qq.com/document/path/90206 |
| 标签管理入口 | https://developer.work.weixin.qq.com/document/path/90210 |

### P2 — 其他模块

| 模块 | 官方文档入口 |
|------|------------|
| 客户群管理 | https://developer.work.weixin.qq.com/document/path/92116 |
| 企业支付 | https://developer.work.weixin.qq.com/document/path/90273 |
| 审批 | https://developer.work.weixin.qq.com/document/path/91853 |
| 打卡 | https://developer.work.weixin.qq.com/document/path/93386 |
| 会议 | https://developer.work.weixin.qq.com/document/path/93627 |
| 日程 | https://developer.work.weixin.qq.com/document/path/93624 |
| 文档 | https://developer.work.weixin.qq.com/document/path/97392 |
| 微盘 | https://developer.work.weixin.qq.com/document/path/93654 |
| 素材管理 | https://developer.work.weixin.qq.com/document/path/90253 |
| 上下游 | https://developer.work.weixin.qq.com/document/path/94205 |

---

## 三、P0 核心 API 详细 Spec

### 3.1 WeCom: sync_msg（轮询消息 — 你的 WeComInboundAdapter 核心调用）

**HTTP:** `POST https://qyapi.weixin.qq.com/cgi-bin/kf/sync_msg?access_token=ACCESS_TOKEN`

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| access_token | string(query) | 是 | 调用凭据 |
| cursor | string | 否 | 上次返回的 next_cursor；首次不填则从 3 天内最早消息开始。≤64 字节 |
| token | string | 否 | 回调事件返回的 token，10 分钟内有效；不填则有严格频率限制 |
| limit | uint32 | 否 | 期望条数，默认/最大均为 1000 |
| voice_format | uint32 | 否 | 0=Amr, 1=Silk, 默认 0 |
| open_kfid | string | 是 | 指定拉取的客服账号 ID |

#### 返回参数

| 字段 | 类型 | 说明 |
|------|------|------|
| errcode | int32 | 0=成功 |
| errmsg | string | 错误描述 |
| next_cursor | string | 下次调用带上此值继续拉取，需持久化保存 |
| has_more | uint32 | 0=无更多, 1=有更多 |
| msg_list | obj[] | 消息列表 |

#### msg_list[] 通用字段

| 字段 | 类型 | 说明 |
|------|------|------|
| msgid | string | 消息 ID（event 类型不返回） |
| open_kfid | string | 客服账号 ID |
| external_userid | string | 客户 userid |
| send_time | uint64 | 消息发送时间（unix 时间戳） |
| origin | uint32 | 3=微信客户发, 4=系统事件, 5=接待人员在企微客户端发 |
| servicer_userid | string | 仅 origin=5 时返回 |
| msgtype | string | 消息类型，决定下面具体结构 |

#### 按 msgtype 的消息结构

**text — 文本消息**
```json
{ "text": { "content": "文本内容", "menu_id": "菜单ID(可选)" } }
```

**image — 图片**
```json
{ "image": { "media_id": "图片文件id" } }
```

**voice — 语音**
```json
{ "voice": { "media_id": "语音文件id" } }
```

**video — 视频**
```json
{ "video": { "media_id": "文件id" } }
```

**file — 文件**
```json
{ "file": { "media_id": "文件id" } }
```

**location — 位置**
```json
{ "location": { "latitude": 1.0, "longitude": 1.0, "name": "位置名", "address": "地址说明" } }
```

**link — 链接**
```json
{ "link": { "title": "标题", "desc": "描述", "url": "链接", "pic_url": "缩略图" } }
```

**business_card — 名片**
```json
{ "business_card": { "userid": "名片userid" } }
```

**miniprogram — 小程序**
```json
{ "miniprogram": { "title": "标题", "appid": "小程序appid", "pagepath": "路径", "thumb_media_id": "封面mediaid" } }
```

**msgmenu — 菜单消息**
```json
{
  "msgmenu": {
    "head_content": "起始文本",
    "list": [{ "type": "click", "click": { "id": "菜单ID", "content": "显示内容" } }],
    "tail_content": "结束文本"
  }
}
```
list[].type 可选: `click`(回复菜单), `view`(超链接), `miniprogram`(小程序)

**merged_msg — 聊天记录**
```json
{ "merged_msg": { "title": "标题", "item": [{ "send_time": 0, "msgtype": 1, "sender_name": "发送者", "msg_content": "JSON消息内容" }] } }
```

**channels_shop_product — 视频号商品**
```json
{ "channels_shop_product": { "product_id": "商品ID", "head_image": "图片", "title": "标题", "sales_price": "价格(分)", "shop_nickname": "店铺名", "shop_head_image": "店铺头像" } }
```

**channels_shop_order — 视频号订单**
```json
{ "channels_shop_order": { "order_id": "订单号", "product_titles": "商品名", "price_wording": "价格描述", "state": "状态", "image_url": "缩略图", "shop_nickname": "店铺名" } }
```

#### 事件消息 (msgtype=event)

| event.event_type | 含义 | 关键字段 |
|------------------|------|---------|
| enter_session | 用户进入会话 | scene, scene_param, welcome_code, wechat_channels |
| msg_send_fail | 消息发送失败 | fail_msgid, fail_type(0-13 见下表) |
| servicer_status_change | 接待人员状态变更 | status(1=接待中,2=停止), stop_type, open_kfid |
| session_status_change | 会话状态变更 | change_type(1=接入,2=转接,3=结束,4=重新接入), msg_code |
| user_recall_msg / servicer_recall_msg | 撤回消息 | recall_msgid |
| reject_customer_msg_switch_change | 拒收变更 | reject_switch(0=取消拒收,1=拒收) |

**fail_type 枚举：**
0=未知, 1=客服账号已删, 2=应用已关闭, 4=会话过期(>48h), 5=会话已关闭, 6=超5条限制, 8=主体未验证, 10=用户拒收, 11=企业未有成员登录企微App, 12=消息类型被禁, 13=安全限制

### 3.2 WeCom: send_msg（发送消息）

**HTTP:** `POST https://qyapi.weixin.qq.com/cgi-bin/kf/send_msg?access_token=ACCESS_TOKEN`
**完整文档:** https://developer.work.weixin.qq.com/document/path/94677

#### 通用请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| touser | string | 是 | 客户 external_userid |
| open_kfid | string | 是 | 客服账号 ID |
| msgid | string | 否 | 关联的客户消息 ID，非必填可用于追踪来源 |
| msgtype | string | 是 | 消息类型: text/image/voice/video/file/link/miniprogram/msgmenu/location |

#### 各消息类型请求体

```json
// text
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "text", "text": { "content": "文本内容" } }

// image
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "image", "image": { "media_id": "MEDIA_ID" } }

// voice
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "voice", "voice": { "media_id": "MEDIA_ID" } }

// video
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "video", "video": { "media_id": "MEDIA_ID", "thumb_media_id": "缩略图MEDIA_ID" } }

// file
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "file", "file": { "media_id": "MEDIA_ID" } }

// link
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "link", "link": { "title": "标题", "desc": "描述", "url": "https://...", "pic_url": "缩略图url", "thumb_media_id": "缩略图media_id" } }

// miniprogram
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "miniprogram", "miniprogram": { "appid": "小程序appid", "title": "标题", "thumb_media_id": "封面media_id", "pagepath": "页面路径" } }

// location
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "location", "location": { "name": "位置名", "address": "地址", "latitude": 22.5, "longitude": 114.0 } }

// msgmenu
{ "touser": "xxx", "open_kfid": "xxx", "msgtype": "msgmenu", "msgmenu": { "head_content": "头部", "tail_content": "尾部", "list": [{ "type": "click", "click": { "content": "选项1", "id": "menu_1" } }] } }
```

#### 返回参数

| 字段 | 类型 | 说明 |
|------|------|------|
| errcode | int32 | 0=成功 |
| errmsg | string | 错误描述 |
| msgid | string | 发送成功返回的消息 ID |

### 3.3 WeCom: sync_msg 回调触发流程

```
企微服务器 → (POST XML 到你的回调 URL)
  ↓
你的回调服务收到: { "MsgType": "event", "Event": "kf_msg_or_event", "Token": "xxx", "OpenKfId": "xxx" }
  ↓
你的 InboundAdapter 调用 POST /kf/sync_msg { "cursor": "上次的next_cursor", "open_kfid": "xxx", "limit": 1000 }
  ↓
返回 msg_list[] → 转为 ChannelEventDraft → 写入 eventRepository
```

---

## 四、核心 API 汇总速查

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

### WeCom ~450 个 API 分组

| 分组 | 数量 | 核心场景 |
|------|------|---------|
| 通讯录管理 | ~40 | 成员/部门/标签/导入导出/异步 |
| 身份验证 | ~6 | OAuth / Web 登录 / 二次验证 |
| 消息推送 | ~12 | 应用消息 / 群聊 / Webhook / 智能机器人 |
| **微信客服** | **~18** | **账号/接待/消息收发/统计 → 你的核心场景** |
| **客户联系** | **~50** | **客户管理/标签/继承/客户群/朋友圈/获客 → CRM 核心** |
| 上下游/企业互联 | ~15 | 企业间通讯录 |
| 会话内容存档 | ~10 | 合规审计 |
| 应用管理 | 6 | 菜单/工作台 |
| 素材管理 | 5 | 上传/下载临时素材 |
| 会议 | ~60 | 预约/Webinar/Rooms/录制/布局 |
| 文档/智能表格 | ~40 | 文档管理/表格CRUD/收集表/权限 |
| 微盘 | ~15 | 空间/文件管理 |
| 邮件 | ~20 | 收发邮件/群组/公共邮箱 |
| 审批 | ~10 | 模板/提交/查询/假期 |
| 打卡 | ~12 | 规则/排班/数据/补卡 |
| 日程 | 8 | 日历/日程管理 |
| 其他(直播/支付/汇报/待办等) | ~100 | OA 场景 |
