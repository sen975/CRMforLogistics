# 跨境物流 AI 协作型 CRM 落地版 PRD

## 一、概述

### 1.1 背景

跨境物流业务高度依赖员工在 WhatsApp、企业微信、邮件、电话纪要等多渠道中持续开发客户、维护供应商、对接海外代理和推进合作。当前信息分散在不同账号、不同渠道和不同员工手中，导致联系人身份难以统一、企业沟通历史难以沉淀、后续 AI 分析和 CRM 管理缺少稳定数据基础。

本项目长期目标仍是建设一套跨境物流 AI 协作型 CRM，但当前第一阶段 MVP 不再直接实现完整 AI 销售开发系统，而是优先实现 CRM 内嵌的多渠道客服中控系统。第一阶段先把沟通入口、联系人、多渠道身份、企业归属和收发消息链路跑通，为后续 AI 摘要、Topic 时间线、任务阶段、团队看板和业务开发辅助能力打基础。

### 1.2 第一阶段 MVP 目标

第一阶段 MVP 的目标是：

1. 以企业和联系人分离管理为基础，建立跨境物流 CRM 主数据雏形。
2. 联系人支持多个标签、多个企业关系、多个渠道身份。
3. 企业详情只展示人工关联到该企业的联系人和沟通记录。
4. 打通 WhatsApp、企业微信客服、邮件的信息收发。
5. 支持电话聊天记录/电话纪要人工录入。
6. 基于 Chatwoot 的会话、收件箱、消息、坐席、渠道抽象能力做封装和二次开发接入。
7. Java 后端作为主业务系统和 API 层，封装 Chatwoot 二开能力并对 React 前端提供稳定业务接口。

一句话目标：

> 先做一个 Java 主系统封装 Chatwoot 多渠道能力的跨境物流客服中控，把 WhatsApp、企业微信、邮件和电话记录沉淀到联系人，再由使用者把联系人归属到企业，为后续 AI 协作型 CRM 打基础。

### 1.3 第一阶段范围

第一阶段必须包含：

- 企业管理。
- 企业标签/企业类型。
- 联系人管理。
- 联系人标签。
- 联系人与企业关系。
- 联系人多渠道身份。
- 多渠道客服中控。
- WhatsApp 信息收发。
- 企业微信客服信息收发。
- 邮件信息收发。
- 电话聊天记录/电话纪要录入。
- 基础账号、角色、权限和审计。
- Chatwoot 基础模块 API 封装和企业微信客服二开接入。

第一阶段不包含：

- 完整客户开发流程。
- 完整供应商资源库。
- Topic 时间线自动推进。
- AI 自动识别联系人身份。
- AI 背调和开发建议。
- AI 自动生成任务和阶段建议。
- 老板团队看板。
- 复杂 BI 和经营分析。
- 订单、报价、财务、TMS、WMS。
- 电话录音自动转写。
- 自动拉取员工个人 WhatsApp 历史聊天记录。

这些能力作为后续阶段扩展，不能污染第一阶段 MVP 的数据模型和交付验收。

### 1.4 本期优先级

P0：Java 主系统、React 前端、Chatwoot API 封装、企业管理、联系人管理、联系人多渠道身份、联系人企业关系、客服中控、企业微信客服收发、WhatsApp 收发、邮件收发、电话记录。

P1：企业/联系人详情聚合视图、消息归属整理、基础审计、渠道配置管理、失败重试和运维状态。

P2：AI 摘要、AI 归属建议、Topic 时间线、任务阶段、团队看板、背调建议、问题经验库、报价机会和订单推进。

## 二、技术栈设计

### 2.1 技术路线结论

技术栈保持不变，仍采用“前后端分离 + Java/Spring Boot 模块化单体 + React 前端 + PostgreSQL/pgvector + Redis + RocketMQ + MinIO + Docker Compose 私有化部署”的路线。

但第一阶段架构主线调整为：

```text
React 前端
  -> Java/Spring Boot 业务 API
  -> CRM 业务数据层（企业、联系人、关系、标签、权限）
  -> Chatwoot API/二开模块（收件箱、会话、消息、坐席、渠道）
  -> WhatsApp / 企业微信客服 / 邮件等外部渠道
```

Java 后端是本项目的主业务 owner，负责向前端提供稳定 API、维护企业/联系人/关系/标签等 CRM 业务真相，并封装 Chatwoot 的会话和渠道能力。Chatwoot 是多渠道客服基础设施，不直接成为 CRM 业务真相 owner。

P0 技术栈确认如下：

- 前端：React + TypeScript + Vite + Ant Design + TanStack Query + React Router + Axios。
- 后端：Java 21，最低兼容 Java 17；Spring Boot 3；Spring Security。
- Chatwoot：作为客服中控基础能力来源，封装其 API，并对企业微信客服做二开渠道接入。
- ORM：MyBatis-Plus + XML Mapper。
- 数据库：PostgreSQL，保留 pgvector 扩展能力，为后续 AI/RAG 预留。
- 缓存与锁：Redis。
- 异步任务：RocketMQ。
- 文件存储：MinIO。
- 数据库迁移：Flyway。
- 部署：Docker Compose 单机私有化部署。

技术栈边界：

- PostgreSQL 是 CRM 正式业务真源。
- Chatwoot 保存客服会话、消息、收件箱和渠道侧客服数据；Java 后端通过同步、映射或 API 查询形成业务视图。
- Java 后端保存企业、联系人、标签、联系人企业关系、多渠道身份映射、权限和审计。
- Redis 只做 token、黑名单、分布式锁、限流和短缓存，不保存不可恢复的正式业务数据。
- RocketMQ 承载渠道同步、Chatwoot 事件同步、消息归属整理、后续 AI 任务等异步流程，不作为业务真源。
- MinIO 保存附件和导入文件，文件访问必须经过后端鉴权。
- pgvector 和 AI 数据平面保留技术能力，但不作为第一阶段 MVP 的验收主线。

### 2.2 前端技术栈

前端采用：

- React。
- TypeScript。
- Vite。
- Ant Design。
- React Router。
- TanStack Query。
- Axios 封装。
- Zustand 或 React Context 管理轻量全局状态，P0 推荐 Zustand。

前端职责：

- 企业列表、详情、新建、编辑。
- 联系人列表、详情、新建、编辑。
- 联系人标签和企业标签展示/维护。
- 联系人多渠道身份管理。
- 联系人与企业关系维护。
- 客服中控会话列表、消息区、回复框、筛选器。
- 电话记录录入。
- 渠道配置入口。

前端约束：

- 前端不得写死可配置枚举，企业类型、联系人标签、关系类型、渠道状态等应从后端接口读取。
- 前端只做展示、交互和接口调用，不拥有企业归属、联系人可见性、消息归属、权限判断的业务真相。
- 企业详情中能看到什么联系人和沟通，必须以后端返回为准。
- 所有页面必须具备空状态、加载中、失败、无权限、网络异常反馈。
- 客服中控必须展示消息发送中、发送成功、发送失败、可重试等状态。
- 前端只消费 typed API 封装，不在页面中直接拼接 URL。

### 2.3 后端技术栈

后端采用：

- Java 21，最低兼容 Java 17。
- Spring Boot 3。
- Spring Security。
- MyBatis-Plus + XML Mapper。
- Flyway 管理数据库迁移。
- RocketMQ 承载异步任务。
- Redis 承载 token、黑名单、锁、限流和短缓存。
- MinIO SDK 管理附件。
- Chatwoot API Client 封装客服会话、消息、收件箱、联系人、渠道等能力。
- 企业微信客服 API Client 封装 `sync_msg`、`send_msg` 和回调验签解密。
- WhatsApp API/服务商 Client 封装消息收发。
- Mail Client 封装 IMAP/SMTP 或服务商邮件 API。

后端模块边界：

| 模块 | 职责 |
| --- | --- |
| auth | 登录、刷新 token、退出、当前用户、角色权限 |
| organization | 用户、团队、数据范围 |
| company | 企业、企业标签、企业状态 |
| contact | 联系人、联系人标签、联系人合并 |
| contact-identity | 联系人多渠道身份 |
| company-contact | 联系人与企业关系 |
| conversation | 客服中控业务视图，会话列表、会话详情、归属关系 |
| message | 消息展示、发送请求、状态回写 |
| phone-note | 电话记录/电话纪要 |
| channel-account | WhatsApp、企业微信、邮件账号配置 |
| chatwoot | Chatwoot API 封装、事件同步、ID 映射 |
| wecom | 企业微信客服回调、sync_msg、send_msg、身份映射 |
| whatsapp | WhatsApp webhook、发送、身份映射 |
| mail | 邮件接收、发送、线程/会话映射 |
| audit | 敏感查看、配置变更、归属变更审计 |
| storage | 附件元数据、MinIO 访问 |
| mq | RocketMQ producer、consumer、重试、死信和幂等 |

后端约束：

- Java 后端是业务 API owner，Chatwoot 不直接暴露给前端。
- Controller 只做接口映射、参数校验和响应转换，不承载业务状态机。
- Service/Domain 层负责企业归属、联系人关系、渠道身份绑定、消息归属、权限和事务边界。
- Mapper 只做 SQL，不写业务判断。
- Chatwoot API 调用必须通过 `chatwoot` 模块封装，不得散落在业务模块各处。
- 渠道密钥、邮箱授权、WhatsApp token、企业微信 secret 必须加密保存。
- 所有外部回调必须验签、记录 traceId，并保证幂等。

### 2.4 Chatwoot 封装与二开边界

Chatwoot 作为客服中控基础能力来源，第一阶段优先复用：

- Inbox/收件箱。
- Conversation/会话。
- Contact/客服联系人基础能力。
- Contact Inbox/联系人渠道身份关系。
- Message/消息。
- Agent/坐席。
- Assignment/分配。
- Channel/渠道抽象。
- Webhook/外部事件入口。
- API Channel 或标准 Channel 扩展能力。

本项目在 Chatwoot 之上增加：

- 企业档案。
- 企业标签/类型。
- 联系人标签。
- 联系人与企业关系。
- 联系人多渠道身份统一视图。
- 企业维度沟通归档视图。
- 跨境物流 CRM 后续 AI/业务字段。

边界规则：

- Chatwoot 负责客服会话运行面。
- Java 后端负责 CRM 业务真相。
- React 前端只调用 Java API，不直接调用 Chatwoot API。
- Chatwoot ID 与本系统 ID 必须建立映射表，例如 `chatwoot_conversation_id`、`chatwoot_contact_id`、`chatwoot_message_id`。
- 后续裁剪 Chatwoot 无关模块时，不应破坏 Java 业务 API 合同。

### 2.5 认证与权限

认证采用 Spring Security + JWT Access Token + Redis Refresh Token/黑名单。

权限分两层：

- 角色权限：employee、supervisor、boss、admin 控制功能入口。
- 数据范围：员工看本人/被分配会话/被授权企业，主管看团队，老板看汇总或授权范围，管理员管理配置但不默认拥有全部原文查看权。

权限落地要求：

- 数据范围必须在服务端 Service/Mapper 查询条件中落地。
- 企业详情只展示与该企业建立关系的数据。
- 联系人多渠道身份归并必须可追溯。
- 查看敏感沟通原文必须写审计日志。
- 前端隐藏按钮不是权限真相。

### 2.6 数据库、Redis、RocketMQ、MinIO

PostgreSQL：保存 CRM 业务真相，包括企业、联系人、标签、关系、渠道身份、映射、电话记录、渠道配置、审计、异步任务状态。

Redis：保存登录 token、黑名单、短缓存、分布式锁、渠道同步锁和限流键。

RocketMQ：承载 Chatwoot 事件同步、渠道 webhook 后处理、消息归属整理、发送状态回写、后续 AI 任务。

MinIO：保存邮件附件、聊天附件、导入文件和后续背调资料。第一阶段附件可先做元数据和基础上传/下载能力，复杂解析后置。

## 三、第一阶段数据流

### 3.1 总体链路

第一阶段主链路为：

```text
外部渠道消息
  -> Chatwoot/渠道回调入口
  -> Java 后端同步或接收事件
  -> 会话/消息业务视图
  -> 联系人渠道身份绑定
  -> 联系人与企业人工关联
  -> 企业/联系人详情聚合展示
```

### 3.2 新消息进入系统

1. WhatsApp、企业微信客服或邮件产生新消息。
2. 消息进入 Chatwoot 会话，或由渠道回调先进入 Java 后端再同步到 Chatwoot。
3. Java 后端记录 Chatwoot 会话/消息 ID 映射。
4. 系统根据外部账号查找 `contact_identities`。
5. 如果身份已绑定联系人，则会话展示联系人信息。
6. 如果联系人已关联企业，则可在企业详情中展示。
7. 如果身份未绑定联系人，则进入未知身份/待整理状态。
8. 员工可将该渠道身份绑定到已有联系人或新建联系人。
9. 员工可将联系人关联到一个或多个企业。

### 3.3 企业详情数据规则

企业详情的数据来自人工建立的关系和归属结果：

- 只展示 `company_contacts.company_id = 当前企业.id` 的联系人。
- 只展示已归属到当前企业的会话、消息和电话记录。
- 未关联企业的联系人不展示在企业详情。
- 未归属企业的消息仍可在客服中控或待整理队列中展示。
- 不能因为邮箱域名、WhatsApp 号码、企业微信 external_userid 或电话相似就自动进入企业详情。

### 3.4 坐席回复链路

1. 员工在 React 客服中控中打开会话。
2. 前端调用 Java API 发送消息。
3. Java 后端根据会话渠道选择发送适配器。
4. 企业微信走企业微信客服 `send_msg`。
5. WhatsApp 走 WhatsApp Business/服务商发送接口。
6. 邮件走 SMTP/服务商邮件发送接口。
7. Java 后端同步或回写 Chatwoot 消息状态。
8. 前端展示发送中、成功、失败和重试。

### 3.5 企业微信客服链路

企业微信方向使用企业微信客服 / WeCom KF，不使用企业微信自建应用普通消息 API。

关键链路：

1. 企业微信 POST 加密事件 XML 到回调入口。
2. 系统验签并解密 XML。
3. 解密后事件类型为 `kf_msg_or_event`。
4. 系统根据 Token、OpenKfId 和 cursor 调用 `/cgi-bin/kf/sync_msg` 拉取真实消息。
5. 保存 `next_cursor`。
6. 拉取循环停止条件以 `has_more` 为准。
7. 只处理第一阶段需要的客户文本消息，复杂事件后置。
8. 发送消息使用 `/cgi-bin/kf/send_msg`。

## 四、数据库设计

### 4.1 设计原则

第一阶段数据库只承载 MVP 必需对象：企业、联系人、标签、关系、渠道身份、会话映射、消息映射、电话记录、渠道配置、审计和异步任务。

设计原则：

- 企业和联系人分离。
- 联系人与企业关系显式建立。
- 渠道身份与联系人分离。
- 消息和会话可以复用 Chatwoot，但本系统必须保存业务映射。
- 企业详情可见性以后端关系数据为准。
- 可变枚举和标签走配置，不写死在前端。
- AI、Topic、任务、看板相关表不进入第一阶段核心迁移，可后续追加。

### 4.2 核心表总览

| 表 | 作用 |
| --- | --- |
| `users` | 系统用户 |
| `teams` | 团队和主管关系 |
| `companies` | 企业档案 |
| `company_tags` | 企业标签 |
| `company_taggings` | 企业与标签关系 |
| `contacts` | 联系人档案 |
| `contact_tags` | 联系人标签 |
| `contact_taggings` | 联系人与标签关系 |
| `company_contacts` | 联系人与企业关系 |
| `contact_identities` | 联系人多渠道身份 |
| `channel_accounts` | 渠道账号配置 |
| `chatwoot_mappings` | 本系统对象与 Chatwoot 对象映射 |
| `conversation_views` | 会话业务视图/缓存，可选 |
| `message_mappings` | 消息映射和状态，可选 |
| `phone_notes` | 电话聊天记录/电话纪要 |
| `audit_logs` | 审计日志 |
| `async_jobs` | 异步任务状态 |
| `attachments` | 附件元数据，可选 |
| `dictionary_categories` | 字典分类 |
| `dictionary_items` | 字典项 |

### 4.3 企业表 `companies`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 企业 ID |
| name | varchar(150) | 是 | 企业名称 |
| type_code | varchar(50) | 否 | 客户、供应商、代理、内部、未知等，可由标签替代 |
| country | varchar(100) | 否 | 国家/地区 |
| city | varchar(100) | 否 | 城市或港口 |
| website | varchar(255) | 否 | 官网 |
| owner_id | uuid | 否 | 负责人 |
| remark | text | 否 | 备注 |
| status | varchar(20) | 是 | active/disabled |
| created_by | uuid | 是 | 创建人 |
| created_at | timestamptz | 是 | 创建时间 |
| updated_at | timestamptz | 是 | 更新时间 |
| deleted_at | timestamptz | 否 | 软删除时间 |

### 4.4 联系人表 `contacts`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 联系人 ID |
| name | varchar(100) | 是 | 姓名、昵称或称呼 |
| role_title | varchar(100) | 否 | 职位/角色 |
| remark | text | 否 | 备注 |
| status | varchar(20) | 是 | active/disabled/merged |
| merged_to_id | uuid | 否 | 合并目标联系人 |
| created_by | uuid | 是 | 创建人 |
| created_at | timestamptz | 是 | 创建时间 |
| updated_at | timestamptz | 是 | 更新时间 |
| deleted_at | timestamptz | 否 | 软删除时间 |

### 4.5 标签表

`company_tags` 和 `contact_tags` 分开，避免企业标签和联系人标签语义混乱。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 标签 ID |
| name | varchar(50) | 是 | 标签名 |
| color | varchar(30) | 否 | 前端展示颜色 |
| status | varchar(20) | 是 | active/disabled |
| created_at | timestamptz | 是 | 创建时间 |

关系表：

- `company_taggings(company_id, tag_id)`。
- `contact_taggings(contact_id, tag_id)`。

企业标签示例：客户、供应商、海外代理、内部、同行、重点、潜在、暂停。

联系人标签示例：客户联系人、供应商联系人、决策人、财务、操作、英文沟通、重点跟进。

### 4.6 联系人与企业关系表 `company_contacts`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 关系 ID |
| company_id | uuid | 是 | 企业 ID |
| contact_id | uuid | 是 | 联系人 ID |
| relation_type | varchar(50) | 否 | 客户方、供应商方、代理方、内部协作、未知 |
| is_primary | boolean | 是 | 是否主联系人 |
| remark | text | 否 | 关系备注 |
| created_by | uuid | 是 | 创建人 |
| created_at | timestamptz | 是 | 建立时间 |

约束：

- `company_id + contact_id` 建议唯一，避免同一联系人重复挂到同一企业。
- 删除关系不删除联系人，也不删除历史消息。
- 企业详情只通过该表决定联系人可见性。

### 4.7 联系人渠道身份表 `contact_identities`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 渠道身份 ID |
| contact_id | uuid | 否 | 归属联系人，未知身份时可为空 |
| channel_type | varchar(30) | 是 | wecom/whatsapp/email/phone |
| identity_value | varchar(255) | 是 | 邮箱、手机号、WhatsApp ID、企微 external_userid |
| display_name | varchar(100) | 否 | 渠道显示名 |
| is_primary | boolean | 是 | 是否主账号 |
| verify_status | varchar(20) | 是 | unverified/verified |
| source | varchar(30) | 是 | manual/synced/imported |
| created_at | timestamptz | 是 | 创建时间 |
| updated_at | timestamptz | 是 | 更新时间 |

约束：

- `channel_type + identity_value` 建议唯一或准唯一。
- 一个联系人可以绑定多个同类型身份。
- 未绑定联系人时，该身份可以进入待整理队列。

### 4.8 渠道账号表 `channel_accounts`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 渠道账号 ID |
| channel_type | varchar(30) | 是 | wecom/whatsapp/email |
| name | varchar(100) | 是 | 账号名称 |
| account_identifier | varchar(255) | 是 | 邮箱、业务号、open_kfid 等 |
| auth_status | varchar(30) | 是 | unbound/binding/active/expired/failed/disabled |
| sync_status | varchar(30) | 是 | idle/syncing/success/failed |
| encrypted_config | text | 否 | 加密配置 |
| last_synced_at | timestamptz | 否 | 最近同步时间 |
| created_at | timestamptz | 是 | 创建时间 |
| updated_at | timestamptz | 是 | 更新时间 |

### 4.9 Chatwoot 映射表 `chatwoot_mappings`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 映射 ID |
| local_type | varchar(50) | 是 | company/contact/conversation/message/channel_account |
| local_id | uuid | 是 | 本系统 ID |
| chatwoot_type | varchar(50) | 是 | contact/conversation/message/inbox/channel |
| chatwoot_id | varchar(100) | 是 | Chatwoot 对象 ID |
| metadata_json | jsonb | 否 | 扩展映射信息 |
| created_at | timestamptz | 是 | 创建时间 |
| updated_at | timestamptz | 是 | 更新时间 |

### 4.10 电话记录表 `phone_notes`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | uuid | 是 | 电话记录 ID |
| contact_id | uuid | 是 | 联系人 ID |
| company_id | uuid | 否 | 企业 ID |
| phone_identity_id | uuid | 否 | 电话渠道身份 ID |
| occurred_at | timestamptz | 是 | 沟通时间 |
| summary | text | 是 | 电话纪要 |
| next_step | text | 否 | 下一步备注 |
| created_by | uuid | 是 | 录入人 |
| created_at | timestamptz | 是 | 录入时间 |
| updated_at | timestamptz | 是 | 更新时间 |

## 五、API 合同

### 5.1 API 总原则

接口统一前缀：

```text
/api/v1
```

规则：

- React 前端只调用 Java API。
- Java API 封装 Chatwoot API，不向前端暴露 Chatwoot 内部细节。
- 列表接口必须分页。
- 写接口必须做参数校验、权限校验、状态校验和幂等校验。
- 所有响应返回 `traceId`。
- 敏感操作写审计日志。

统一成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "01HZY6Q8F5P4EXAMPLE",
  "timestamp": "2026-07-01T10:00:00+08:00"
}
```

统一错误响应：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "参数校验失败",
  "details": [
    {
      "field": "name",
      "reason": "企业名称不能为空"
    }
  ],
  "retryable": false,
  "traceId": "01HZY6Q8F5P4EXAMPLE",
  "timestamp": "2026-07-01T10:00:00+08:00"
}
```

### 5.2 第一阶段核心接口

| 模块 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| auth | POST | /api/v1/auth/login | 登录 |
| auth | POST | /api/v1/auth/refresh-token | 刷新 token |
| auth | POST | /api/v1/auth/logout | 退出 |
| auth | GET | /api/v1/auth/me | 当前用户 |
| company | GET | /api/v1/companies | 企业列表 |
| company | POST | /api/v1/companies | 新建企业 |
| company | GET | /api/v1/companies/{id} | 企业详情 |
| company | PATCH | /api/v1/companies/{id} | 更新企业 |
| company | GET | /api/v1/companies/{id}/contacts | 企业联系人 |
| company | GET | /api/v1/companies/{id}/conversations | 企业沟通记录 |
| contact | GET | /api/v1/contacts | 联系人列表 |
| contact | POST | /api/v1/contacts | 新建联系人 |
| contact | GET | /api/v1/contacts/{id} | 联系人详情 |
| contact | PATCH | /api/v1/contacts/{id} | 更新联系人 |
| contact | GET | /api/v1/contacts/{id}/identities | 联系人渠道身份 |
| contact | POST | /api/v1/contacts/{id}/identities | 新增渠道身份 |
| contact | GET | /api/v1/contacts/{id}/companies | 联系人关联企业 |
| relation | POST | /api/v1/company-contacts | 建立联系人企业关系 |
| relation | DELETE | /api/v1/company-contacts/{id} | 解除联系人企业关系 |
| tag | GET | /api/v1/contact-tags | 联系人标签列表 |
| tag | POST | /api/v1/contact-tags | 新建联系人标签 |
| tag | GET | /api/v1/company-tags | 企业标签列表 |
| tag | POST | /api/v1/company-tags | 新建企业标签 |
| conversation | GET | /api/v1/conversations | 客服中控会话列表 |
| conversation | GET | /api/v1/conversations/{id} | 会话详情 |
| message | GET | /api/v1/conversations/{id}/messages | 会话消息 |
| message | POST | /api/v1/conversations/{id}/messages | 发送消息 |
| phone-note | GET | /api/v1/phone-notes | 电话记录列表 |
| phone-note | POST | /api/v1/phone-notes | 新建电话记录 |
| channel-account | GET | /api/v1/channel-accounts | 渠道账号列表 |
| channel-account | POST | /api/v1/channel-accounts | 新建渠道账号 |
| channel-account | PATCH | /api/v1/channel-accounts/{id} | 更新渠道账号 |
| channel-account | POST | /api/v1/channel-accounts/{id}/test | 测试渠道配置 |

### 5.3 Chatwoot 封装接口边界

Java 后端内部封装 Chatwoot API，包括：

- 创建/查询 Chatwoot contact。
- 查询 inbox。
- 查询 conversation。
- 查询 message。
- 发送 message。
- 同步 conversation 状态。
- 同步 assignee。
- 处理 Chatwoot webhook 事件。

这些封装不直接暴露给 React 前端，前端只看到本系统的 `conversation`、`message`、`contact`、`company` 业务 API。

## 六、功能详细说明

### 6.1 企业管理

功能说明：管理客户、供应商、海外代理、同行、内部等企业主体。

MVP 功能：

- 新建企业。
- 编辑企业。
- 企业列表搜索。
- 企业标签维护。
- 企业详情查看。
- 查看该企业已关联联系人。
- 查看该企业已归属沟通记录。

验收：

- 未关联联系人不会出现在企业详情。
- 企业可拥有多个标签，例如客户、供应商、其他、重点等。
- 企业详情数据由后端关系接口返回。

### 6.2 联系人管理

功能说明：管理具体沟通对象，联系人与企业分离。

MVP 功能：

- 新建联系人。
- 编辑联系人。
- 联系人列表搜索。
- 联系人标签维护。
- 联系人详情查看。
- 维护多个渠道身份。
- 维护一个或多个关联企业。

验收：

- 一个联系人可以有多个标签。
- 一个联系人可以有多个邮箱、多个 WhatsApp、多个企业微信 external_userid、多个电话。
- 联系人是否出现在企业详情取决于显式关系，不取决于邮箱域名或号码相似。

### 6.3 联系人企业关系

功能说明：建立联系人与企业之间的人工归属关系。

MVP 功能：

- 从联系人详情关联企业。
- 从企业详情添加联系人。
- 设置关系类型。
- 设置是否主联系人。
- 解除关系。

验收：

- 建立关系后，企业详情展示该联系人。
- 解除关系后，企业详情不再展示该联系人。
- 历史审计仍能追溯谁建立或解除关系。

### 6.4 多渠道身份管理

功能说明：把邮箱、WhatsApp、企业微信、电话等外部身份绑定到联系人。

MVP 功能：

- 新增渠道身份。
- 编辑渠道身份。
- 标记主账号。
- 未知身份绑定到联系人。
- 支持渠道身份来源：人工录入、消息同步、导入。

验收：

- 同一联系人可以绑定多个同类型身份。
- 未绑定联系人身份可以进入待整理状态。
- 渠道身份绑定后，新消息能展示联系人信息。

### 6.5 多渠道客服中控

功能说明：统一处理 WhatsApp、企业微信、邮件和电话记录。

MVP 功能：

- 会话列表。
- 渠道筛选。
- 状态筛选。
- 联系人/企业筛选。
- 消息详情。
- 文本回复。
- 发送状态显示。
- 失败重试。

验收：

- WhatsApp 消息能进来并回复。
- 企业微信客服消息能进来并回复。
- 邮件能展示并回复。
- 电话记录能在同一联系人/企业维度查看。

### 6.6 企业微信客服接入

功能说明：基于企业微信客服 API 做标准渠道接入。

MVP 功能：

- 配置企业微信客服账号。
- 回调验签。
- XML 解密。
- `kf_msg_or_event` 处理。
- `sync_msg` 拉取消息。
- 保存 cursor。
- `send_msg` 发送文本消息。
- external_userid 绑定联系人身份。

验收：

- 不能使用企业微信自建应用普通消息 API 替代客服 API。
- 拉取循环以 `has_more` 为停止依据。
- 消息必须能映射到 Chatwoot 会话或本系统会话视图。

### 6.7 WhatsApp 接入

功能说明：接收和发送 WhatsApp Business 或服务商消息。

MVP 功能：

- 配置账号。
- 接收 webhook。
- 发送文本消息。
- 保存 WhatsApp 联系人身份。
- 绑定系统联系人。

边界：

- 不拉取个人 WhatsApp 历史。
- 不绕过官方或服务商合规限制。

### 6.8 邮件接入

功能说明：将邮件纳入客服中控。

MVP 功能：

- 配置邮箱账号。
- 接收邮件。
- 发送邮件。
- 展示邮件主题、正文、发件人、收件人、附件元数据。
- 邮箱地址绑定联系人。

边界：

- 复杂邮件营销、群发、模板审批不进入 MVP。

### 6.9 电话记录

功能说明：人工录入电话聊天记录/电话纪要。

MVP 功能：

- 选择联系人。
- 可选选择企业。
- 选择或录入电话身份。
- 记录沟通时间。
- 录入纪要和下一步。

边界：

- 不采集录音。
- 不做自动转写。

## 七、测试与验收

### 7.1 技术验收

- 前端构建通过。
- 后端测试通过。
- 后端打包通过。
- Flyway 迁移成功。
- Docker Compose 启动成功。
- Java API 可访问。
- Chatwoot API 封装可调用。
- Redis、PostgreSQL、RocketMQ、MinIO 健康检查正常。

### 7.2 业务验收用例

1. 创建企业 A。
2. 创建联系人 John。
3. 给 John 添加多个标签。
4. 给企业 A 添加客户、供应商等标签。
5. 给 John 绑定两个邮箱、一个 WhatsApp 账号、一个企业微信 external_userid、一个电话。
6. 未关联企业 A 前，企业 A 详情不展示 John。
7. 手动把 John 关联到企业 A。
8. 企业 A 详情展示 John。
9. WhatsApp 收到 John 的消息后，客服中控出现会话。
10. 员工从系统回复 WhatsApp，显示成功或失败原因。
11. 企业微信客服收到 John 的消息后，客服中控出现会话。
12. 员工从系统回复企业微信，显示成功或失败原因。
13. John 邮箱发来邮件后，系统展示邮件沟通。
14. 员工从系统发送邮件回复。
15. 员工录入一次电话纪要并关联 John 和企业 A。
16. 联系人详情中可以看到 John 的 WhatsApp、企业微信、邮件和电话记录。
17. 企业 A 详情中可以看到归属到企业 A 的 John 相关沟通。
18. 解除 John 与企业 A 的关系后，企业 A 详情不再展示 John。

### 7.3 错误码

| 错误码 | 场景 |
| --- | --- |
| AUTH_UNAUTHORIZED | 未登录或 token 失效 |
| AUTH_FORBIDDEN | 无权限 |
| VALIDATION_ERROR | 参数校验失败 |
| DUPLICATE_RECORD | 重复数据 |
| CHANNEL_CONFIG_INVALID | 渠道配置错误 |
| CHANNEL_SEND_FAILED | 渠道发送失败 |
| CHANNEL_SYNC_FAILED | 渠道同步失败 |
| CHATWOOT_API_FAILED | Chatwoot API 调用失败 |
| STATE_CONFLICT | 状态冲突 |
| SERVER_ERROR | 服务端异常 |

## 八、部署架构

第一阶段采用 Docker Compose 单机私有化部署。

服务组成：

- nginx：前端静态资源和反向代理。
- web：React 构建后的前端资源。
- api：Java Spring Boot 主业务服务。
- chatwoot：Chatwoot 服务及二开模块。
- postgres：业务数据库，可按部署方案决定与 Chatwoot DB 分库或同实例分库。
- redis：缓存、token、锁。
- rocketmq-namesrv：RocketMQ NameServer。
- rocketmq-broker：RocketMQ Broker。
- minio：附件对象存储。

部署要求：

- 提供 `.env` 示例。
- 提供数据库初始化和 Flyway 迁移命令。
- 提供 Chatwoot 初始化和 API token 配置说明。
- 提供企业微信、WhatsApp、邮件渠道配置说明。
- 生产镜像固定 tag，禁止使用 latest。
- PostgreSQL、Redis、RocketMQ、MinIO、Chatwoot 数据必须配置数据卷。
- 健康检查覆盖 api、chatwoot、postgres、redis、rocketmq、minio。

## 九、后续阶段方向

第一阶段完成后再逐步扩展：

1. AI 对联系人和企业进行摘要。
2. AI 根据多渠道沟通建议联系人标签和企业归属。
3. AI 生成沟通摘要。
4. Topic 时间线。
5. 客户开发任务和阶段。
6. 老板/主管团队看板。
7. 背调和开发建议。
8. 问题经验库。
9. 报价机会和订单推进。

后续扩展必须基于第一阶段沉淀的多渠道消息、联系人身份、联系人企业关系和企业维度沟通历史，不再建立并行数据模型。

## 十、附件：前端最小 MVP 字段表

### A.1 企业 Company

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 编辑、详情、关联时使用 |
| `name` | string | 是 | Input | 企业名称 |
| `tags` | string[] | 否 | Select multiple/TagInput | 企业多标签，例如客户、供应商、其他 |
| `type` | string | 否 | Select | 可选企业类型，后续可由标签替代 |
| `country` | string | 否 | Select/Input | 国家或地区 |
| `city` | string | 否 | Input | 城市、港口或服务区域 |
| `website` | string | 否 | Input | 官网 |
| `ownerId` | string | 否 | Select | 负责人，MVP 可默认当前用户 |
| `remark` | string | 否 | TextArea | 人工备注 |
| `status` | string | 是 | Select/Tag | `active` / `disabled` |
| `createdAt` | string | 否 | Display | 创建时间 |
| `updatedAt` | string | 否 | Display | 更新时间 |

### A.2 联系人 Contact

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 联系人 ID |
| `name` | string | 是 | Input | 姓名、昵称或称呼 |
| `roleTitle` | string | 否 | Input | 职位/角色 |
| `tags` | string[] | 否 | Select multiple/TagInput | 联系人多标签 |
| `remark` | string | 否 | TextArea | 备注 |
| `status` | string | 是 | Select/Tag | `active` / `disabled` / `merged` |
| `channelIdentities` | ChannelIdentity[] | 否 | 子表格 | 多邮箱、多 WhatsApp、多企微、多电话 |
| `companies` | ContactCompanyRelation[] | 否 | 子表格 | 已关联企业 |
| `createdAt` | string | 否 | Display | 创建时间 |
| `updatedAt` | string | 否 | Display | 更新时间 |

### A.3 联系人渠道身份 ChannelIdentity

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 渠道身份 ID |
| `contactId` | string | 是 | 隐藏 | 归属联系人 |
| `channelType` | string | 是 | Select | `wecom` / `whatsapp` / `email` / `phone` |
| `identityValue` | string | 是 | Input | 邮箱、手机号、WhatsApp ID、企微 external_userid 等 |
| `displayName` | string | 否 | Input | 渠道显示名 |
| `isPrimary` | boolean | 否 | Switch | 是否主账号 |
| `verifyStatus` | string | 否 | Tag/Select | `unverified` / `verified` |
| `source` | string | 否 | Tag | `manual` / `synced` / `imported` |

### A.4 联系人与企业关系 ContactCompanyRelation

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 关系 ID |
| `contactId` | string | 是 | Select/隐藏 | 联系人 |
| `companyId` | string | 是 | Select | 企业 |
| `companyName` | string | 否 | Display | 前端展示用 |
| `relationType` | string | 否 | Select | 客户方、供应商方、代理方、内部协作、未知 |
| `isPrimary` | boolean | 否 | Switch | 是否该企业主联系人 |
| `remark` | string | 否 | Input/TextArea | 关系备注 |
| `createdAt` | string | 否 | Display | 建立时间 |

### A.5 客服中控会话 Conversation

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 是 | 隐藏 | 会话 ID |
| `channelType` | string | 是 | Icon/Tag | `wecom` / `whatsapp` / `email` / `phone` |
| `inboxId` | string | 否 | 隐藏/筛选 | 收件箱 |
| `contactId` | string | 否 | Link/Select | 关联联系人 |
| `contactName` | string | 否 | Display | 联系人名称 |
| `companyId` | string | 否 | Link/Select | 归属企业 |
| `companyName` | string | 否 | Display | 企业名称 |
| `subject` | string | 否 | Display | 邮件主题或会话标题 |
| `lastMessagePreview` | string | 否 | Display | 最后一条消息摘要 |
| `lastMessageAt` | string | 否 | Display | 最后消息时间 |
| `unreadCount` | number | 否 | Badge | 未读数 |
| `assigneeId` | string | 否 | Select | 当前坐席 |
| `status` | string | 是 | Tabs/Tag | `open` / `pending` / `resolved` |
| `canReply` | boolean | 否 | 控制按钮 | 是否允许回复 |

### A.6 消息 Message

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 是 | 隐藏 | 消息 ID |
| `conversationId` | string | 是 | 隐藏 | 所属会话 |
| `channelType` | string | 是 | 隐藏/Tag | 渠道 |
| `direction` | string | 是 | 气泡方向 | `incoming` / `outgoing` |
| `senderName` | string | 否 | Display | 发送方 |
| `content` | string | 是 | MessageBubble/TextArea | 文本内容 |
| `attachments` | Attachment[] | 否 | Upload/List | 附件，MVP 可先只展示 |
| `externalMessageId` | string | 否 | 隐藏 | 渠道消息 ID |
| `sendStatus` | string | 否 | Tag | `sending` / `sent` / `failed` |
| `errorMessage` | string | 否 | Tooltip | 失败原因 |
| `createdAt` | string | 是 | Display | 消息时间 |

### A.7 电话记录 PhoneNote

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 电话记录 ID |
| `contactId` | string | 是 | Select | 联系人 |
| `companyId` | string | 否 | Select | 企业 |
| `phoneIdentityId` | string | 否 | Select/Input | 对应电话身份 |
| `occurredAt` | string | 是 | DateTimePicker | 沟通时间 |
| `summary` | string | 是 | TextArea | 电话纪要 |
| `nextStep` | string | 否 | TextArea | 下一步备注 |
| `createdBy` | string | 否 | Display | 录入人 |
| `createdAt` | string | 否 | Display | 录入时间 |

### A.8 渠道配置 ChannelAccount

| 前端字段 | 类型 | 必填 | 控件 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 隐藏 | 渠道账号 ID |
| `channelType` | string | 是 | Select | `wecom` / `whatsapp` / `email` |
| `name` | string | 是 | Input | 账号名称 |
| `accountIdentifier` | string | 是 | Input | 邮箱地址、业务号、open_kfid 等 |
| `status` | string | 是 | Tag | `active` / `expired` / `failed` / `disabled` |
| `lastSyncedAt` | string | 否 | Display | 最近同步时间 |
| `remark` | string | 否 | TextArea | 备注 |

## 十一、最终边界结论

第一阶段 MVP 的核心不是完整 CRM，也不是完整 AI 销售秘书，而是先把跨境物流业务最基础、最高频、最能沉淀数据的沟通入口做好。

当前主线是：

> React 前端 + Java 主业务 API + Chatwoot 封装/二开 + 企业微信客服/WhatsApp/邮件/电话记录，把每条沟通路径沉淀到联系人，再由使用者把联系人归属到企业，为后续 AI 协作型 CRM 打基础。
