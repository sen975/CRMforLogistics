# 企微账户互通 + 会话存档媒体 · 实施方案评估

> **状态**：评估稿，未动代码。待确认第 6 节的两个决策点后再实施。

**目标**

1. **账户互通**：让 CRM 账号与企微 `userid` 能批量对齐，摆脱「每个员工必须自己扫码登录一次」的唯一路径。
2. **会话存档媒体**：让客户在企微里发的图片/文件真正落盘，而不是只留一个 `sdkfileid` 指针。

**结论先行**：两块难度差一个数量级。账户互通是**低风险改动**（1 迁移 + 3~4 个类 + 1 个管理入口），可先做；会话存档媒体**存储层现成、但前置依赖 SDK 集成**，且官方 SDK 没有 macOS 版本，属于独立工程量，需先做技术验证。

---

## 1. 约束（全部来自实测，非推断）

### 1.1 接入模式是代开发应用，ID 全是密文

`WeComAuthorizationGateway` 走的是 `service/get_suite_token` + `service/v2/get_permanent_code`（套件 + 永久授权码），不是自建应用的 `corpsecret`。后果：

| 项 | 影响 |
|---|---|
| `corpid` | 变 `wp` 前缀密文 |
| `userid` | 变 `wo` 前缀定长密文（`open_userid`） |
| `external_userid` | `wo` / `wm` 前缀；机器人 `wb` 前缀 |
| 员工敏感信息 | **通讯录接口不返回**手机号、邮箱、地址、头像、性别、企微二维码 |

代码印证：`WeComLoginApplicationService:83` 把 `displayName` 直接传成 `upstream.userId()` —— 因为代开发/第三方应用自 2020-06-30 起不再返回真实姓名。

**这条推翻了一个看似显然的方案**：不能「从企微通讯录拉员工手机号」，只能**反向**用手机号/邮箱去换 `open_userid`。

### 1.2 架构门禁（硬门禁，无冻结基线）

`ArchitectureBoundaryTest` 只放行 `service.channel` / `service.wecom` / `service.chatapp` 依赖顶层 `channel` 包。新增代码放 `service.wecom` + `channel.wecom` 是合法的，无需改白名单。

### 1.3 现有封装范式（必须沿用）

三层，职责清晰：

```
channel/wecom/XxxGateway     纯协议映射：拼路径 + 调 WeComApiClient，不含业务判断
service/wecom/XxxService     输入校验 + 安装解析 + 审计 owner + 频控
web/ 或 WeComController      HTTP 入口
```

审计统一走 `WeComApiAuditTrail.begin/success/failed`，每个动作有稳定 action 名（如 `wecom.api.directory.member_get`）+ `traceId`。

### 1.4 频控红线（实现时必须处理）

`/cgi-bin/user/getuserid` 与 `/cgi-bin/user/get_userid_by_email` 官方文档都写明：

> 请确保手机号/邮箱的正确性，若出错的次数超出企业人数上限的 20%，会导致 **1 天不可调用**。

这是**惩罚性封禁**，不是普通限流。批量场景下若无脑重试，一次错误导入就能把整个企业的接口封掉一天。方案 A 的成败几乎全在这个点上。

### 1.5 关键数据缺口：`users` 表没有手机号，也没有邮箱

`users`（V1 + V46）实际列：`id / username / username_normalized / password_hash / display_name / status / last_login_at / created_at / updated_at / deleted_at / avatar_object_key / avatar_mime_type`。

**没有任何手机号/邮箱列。** 所以「用 CRM 里已有的手机号反查」这句话现在不成立 —— CRM 里压根没存。这是方案 A 必须先解决的前置问题。

---

## 2. 方案 A：账户互通对齐

### 2.1 原理

```
管理员录入 (CRM 用户 ↔ 手机号/邮箱)
        │
        ▼
POST /cgi-bin/user/getuserid            {mobile}
POST /cgi-bin/user/get_userid_by_email  {email, email_type}
        │  返回密文 open_userid（woXXX）
        ▼
写入 wecom_user_bindings (user_id, wecom_user_id, ...)
```

成立的前提：`getuserid` 返回的密文 userid 与登录时 `auth/getuserinfo` 返回的 userid 是**同一套服务商主体加密**，因此能命中 `wecom_user_bindings` 的 `UNIQUE (suite_id, auth_corp_id, wecom_user_id)` 约束。此前提需在联调时用真实数据验证一次（拿一个已知已绑定的员工，用其手机号反查，比对是否等于库里 `wecom_user_id`）——**这是实施第一步，不通过则整个方案作废**。

### 2.2 为什么用独立表，而不是给 `users` 加列

给 `users` 加 `mobile` / `email` 会牵动 `UserEntity`、Mapper、DTO、前端表单、以及账号生命周期相关逻辑，影响面大；而手机号在这里只是**一次性的匹配凭据**，不是用户主数据。

改用独立表，好处是：天然承载频控状态（尝试次数、下次可试时间）、可审计谁在何时导入、失败条目可人工复核而不会被自动重试。

### 2.3 表结构（新增迁移 V81）

```sql
CREATE TABLE wecom_account_matches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id            varchar(128) NOT NULL,
    auth_corp_id        varchar(128) NOT NULL,
    crm_user_id         uuid NOT NULL REFERENCES users(id),
    match_type          varchar(16)  NOT NULL,     -- MOBILE | EMAIL
    match_value_enc     text         NOT NULL,     -- 密文存储，见 2.5
    match_value_hint    varchar(32)  NOT NULL,     -- 仅末四位/域名，供界面回显
    status              varchar(20)  NOT NULL DEFAULT 'PENDING',
    resolved_wecom_user_id varchar(128),
    attempt_count       int NOT NULL DEFAULT 0,
    last_error_code     varchar(64),
    last_attempt_at     timestamptz,
    bound_at            timestamptz,
    created_by          uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_wecom_account_matches_type   CHECK (match_type IN ('MOBILE','EMAIL')),
    CONSTRAINT ck_wecom_account_matches_status CHECK (
        status IN ('PENDING','MATCHED','FAILED','BOUND','CANCELLED')),
    CONSTRAINT ck_wecom_account_matches_attempts CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX ux_wecom_account_matches_active
    ON wecom_account_matches (suite_id, auth_corp_id, crm_user_id)
    WHERE status IN ('PENDING','MATCHED','FAILED');

CREATE INDEX ix_wecom_account_matches_pending
    ON wecom_account_matches (suite_id, auth_corp_id, status)
    WHERE status = 'PENDING';
```

同时需要扩展绑定表的来源枚举：

```sql
ALTER TABLE wecom_user_bindings DROP CONSTRAINT ck_wecom_user_binding_source;
ALTER TABLE wecom_user_bindings ADD CONSTRAINT ck_wecom_user_binding_source
    CHECK (provisioning_source IN ('AUTO_CREATED','BOUND_EXISTING','BULK_MATCHED'));
```

> `wecom_user_bindings.provisioning_source` 原约束只允许 `AUTO_CREATED` / `BOUND_EXISTING`，且 `WeComUserBindingService.unbind()` 对 `AUTO_CREATED` 有特殊分支 —— 新增取值时**必须同步检查 `unbind` 与 `resolveOrCreate` 的分支逻辑**，避免批量绑定的记录被当成自动创建的孤儿用户删掉。

### 2.4 代码改动位置

| 层 | 文件 | 内容 |
|---|---|---|
| Gateway（改） | `channel/wecom/WeComDirectoryGateway` | 加 `getUserIdByMobile(installation, mobile, timeout)`、`getUserIdByEmail(installation, email, emailType, timeout)` |
| Service（新） | `service/wecom/WeComAccountMatchingService` | 批次编排、频控熔断、审计、写绑定 |
| Mapper（新） | `mapper/WeComAccountMatchMapper` | 条目 CRUD + 抢占式领取（`FOR UPDATE SKIP LOCKED`，沿用 outbox 范式） |
| Entity（新） | `entity/WeComAccountMatchEntity` | — |
| Web（新） | `web/WeComAccountMatchingController`（管理端） | 导入、查看批次、重试单条、取消 |
| 配置（改） | `AppConfig` + `application.yml` | `wecom.account-matching.batch-size`、`max-attempts`、`failure-circuit-threshold` |

放在 `WeComDirectoryGateway` 而非新建 Gateway 的理由：这两个接口同属通讯录范畴（`user/getuserid`、`user/get_userid_by_email`），与已有的 `user/get`、`user/simplelist` 归在一起，避免网关碎片化。业务编排独立成 Service。

### 2.5 频控与失败策略（方案 A 的核心）

三层防护，缺一不可：

1. **条目级**：`attempt_count` 达 `max-attempts`（建议 1，因为「错就是错」）即置 `FAILED`，不再自动重试，转人工复核。
2. **批次级熔断**：一批内连续失败达 `failure-circuit-threshold`（建议 5）立即中止整批，把剩余条目留在 `PENDING`。因为 20% 的封禁阈值是按企业人数算的，小企业可能只错 2~3 个就触发。
3. **结果缓存**：成功的条目不再查询；`MATCHED → BOUND` 之间不重复调用。

其它硬性要求：

- 导入时先做**格式校验**（手机号纯数字位数、邮箱格式），格式错误的条目直接拒收，**不消耗接口调用**。这是防止误导入触发封禁的第一道闸。
- 调用前对同一 `(suite_id, auth_corp_id)` 加互斥，避免多个管理员同时触发批次。
- 全量审计：每条反查都写 `WeComApiAuditTrail`，action 名建议 `wecom.api.account.mobile_lookup` / `email_lookup`，便于事后追查是不是我们把接口打挂的。
- `match_value_enc` 用与 `WeComCredentialProtector` 同级的方式加密存储；界面只回显 `match_value_hint`（手机号末四位 / 邮箱域名），避免手机号在库里裸奔。

### 2.6 回滚

- 已写 `BULK_MATCHED` 的绑定可批量解绑（`DELETE /account/wecom-binding` 逐条已有）。迁移不做数据破坏，回滚只需停用入口。
- 条目表可整体 `CANCELLED`，不影响已有绑定。

---

## 3. 方案 B：会话存档媒体落盘

### 3.1 现状：这是刻意设计，不是遗漏

`media_json` 由 `V35__wecom_media_descriptor.sql` 加入，列注释原文：

> `Bounded provider media identifiers only; never message body or credentials`

即**有意只存标识符、不存文件体**。但实测 `getMediaJson()` / `mediaJson` 在**主代码里零读取点** —— 描述符存下来后从未被任何方消费，前端也没有任何媒体展示。所以这块是「设计了一半」。

这决定了方案 B 的真实命题不是「加个下载」，而是**先确定谁来消费**。

### 3.2 真正的成本在 SDK，不在 HTTP

官方文档（path/91774）原文：

> 业务方通过企业微信提供的 **sdk**，可以进行会话记录数据的获取、媒体数据的获取。

SDK 提供的接口里第 4 项即为「对图片、文件等媒体数据，拉取媒体数据内容的 sdk 接口」。并且：

- SDK 是 **C 接口**（`WeWorkFinanceSdk_t*` / `int Init(...)` / `GetChatData(...)`），非 REST
- 官方发布物只有 **Linux x86、Linux arm、Windows** 三份
- **没有 macOS 版本**

最后一条对开发方式影响很大：本机是 Apple Silicon macOS，**本地跑不了 SDK**，所有涉及媒体的联调必须在 Linux 容器或远端环境做。

另外注意：本项目的消息拉取走的是 HTTP 的 `chatdata/sync_call_program`，而 SDK 用的是 `GetChatData`（seq 游标模型）——**两者是两套并行的拉取模型**。做媒体时若也走 SDK，要决定是用 SDK 一并替换现有拉取，还是只借用 SDK 的媒体接口（后者更稳，不动已在跑的链路）。

### 3.3 SDK 集成方案对比

| 方案 | 做法 | 优点 | 缺点 | 判断 |
|---|---|---|---|---|
| **A. JNI 直连** | 把 `libWeWorkFinanceSdk` 用 JNI 包进来 | 无额外进程、无网络开销 | 平台绑定（要维护多份 so）、C 库段错误会**直接带走 JVM**、容器镜像要带动态库、无 macOS 版导致本地不可调试 | 不推荐 |
| **B. sidecar 进程（推荐）** | 独立小服务（C++/Go 封装官方 demo）暴露 HTTP，Java 侧只调 HTTP | 崩溃隔离、可独立限流与重启、天然容器化正好绕开 macOS 缺失、可先用官方 demo 起步 | 多一个部署单元，要管端口/健康检查/日志 | **推荐** |
| C. 找现成第三方 HTTP 封装 | 直接用开源封装（如 WeWorkFinanceSDK 系列） | 上手快 | 存档数据属高敏感，引入第三方二进制要做供应链与合规审查 | 仅在 POC 阶段可接受 |

推荐 B 的理由集中在一处：**存档 SDK 是 C 库，段错误会带走整个 JVM**。对一个已经有 22 条调度链、多个渠道在跑的 Spring 服务来说，把 native 崩溃面隔离在 JVM 之外，比省一个进程重要得多。而且 sidecar 天然容器化，正好解决没有 macOS SDK 的问题。

### 3.4 存储落点：不复用 `attachments`

`attachments` 表（V3）的 `message_id` 是 `uuid NOT NULL REFERENCES messages (id)`。而企微存档消息在 `wecom_chatdata_messages`，且**非文本消息根本不投影进 `messages`** —— 投影与摘要都只处理文本（`WeComChatDataMessageMapper:87` 的 `msgtype = '1' OR lower(msgtype) = 'text'`）。

所以复用 `attachments` 是错的设计：会迫使要么放开外键，要么先伪造 `messages` 行。

**改为独立表**，只复用底层存储客户端 `infrastructure.MinioStorage`（与本项目任何具体渠道无关的通用 S3/MinIO 封装，已有 `store/get/remove/bucketName`，支持流式写入）：

```sql
CREATE TABLE wecom_chatdata_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL,
    msgid       varchar(256) NOT NULL,
    sdk_file_id text NOT NULL,
    media_kind  varchar(20)  NOT NULL,     -- image | voice | video | file
    storage_provider varchar(20) NOT NULL DEFAULT 'minio',
    bucket      varchar(100)  NOT NULL,
    object_key  varchar(1024) NOT NULL,
    mime_type   varchar(255),
    size_bytes  bigint,
    md5_sum     varchar(64),               -- 企微 media_json 里给的 md5sum，下载后可校验
    status      varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count int NOT NULL DEFAULT 0,
    failure_code varchar(100),
    failure_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    ready_at   timestamptz,
    CONSTRAINT uq_wecom_chatdata_media_file UNIQUE (installation_id, msgid, sdk_file_id),
    CONSTRAINT uq_wecom_chatdata_media_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_wecom_chatdata_media_kind CHECK (
        media_kind IN ('image','voice','video','file')),
    CONSTRAINT ck_wecom_chatdata_media_status CHECK (
        status IN ('PENDING','DOWNLOADING','READY','FAILED'))
);
```

对象键建议 `wecom/chatdata/{installationId}/{msgid}/{sha256-prefix}-{filename}`，与其它渠道按前缀隔离，便于按前缀做生命周期清理与合规删除。

**注意合规**：存档媒体是员工与客户的原始通信内容。落盘前必须明确**保留期**与**删除机制**（项目已有 `WeComAuditRetentionService` / `WeComChatDataRetention` 可参照），否则「存起来取不到」会变成「存起来删不掉」。

### 3.5 消费场景 —— 没有消费方就不该做

这是方案 B 最容易被忽略、却决定成败的一环。当前：

- 前端**没有任何媒体展示**（`ThreadPage` / `ConversationWorkspace` 均无媒体渲染）
- 摘要 worker 对非文本消息直接标 `UNSUPPORTED_MEDIA` 跳过

所以即使文件下下来了，也没地方用。必须先选消费方：

| 消费场景 | 价值 | 成本 |
|---|---|---|
| 会话工作台展示图片/文件（含下载/预览） | 高 —— 客户发的报关单、箱单、提单照片终于能看到 | 中：前端媒体组件 + 鉴权下载接口 |
| AI 摘要/抽取识别图片内容 | 高 —— 物流单据信息自动进 CRM | 高：媒体下载 + OCR/多模态 + 摘要 worker 改造 |
| 仅归档留存供合规审计 | 中 | 低：只落盘 + 管理端检索 |

建议**按「展示」起步**：它价值明确、不需要引入 OCR，且能顺带把落盘链路的正确性验证掉。

---

## 4. 分阶段实施建议

| 阶段 | 内容 | 前置依赖 | 风险 |
|---|---|---|---|
| **0** | 验证前提：拿一个已绑定员工，用手机号反查，比对结果是否等于库里 `wecom_user_id` | 账户互通已开通 | 失败则方案 A 作废，**先做这个** |
| **1** | 账户互通：V81 迁移 + Gateway 两个方法 + Service + 频控 + 管理端导入 | 阶段 0 通过 | 低（注意封禁红线） |
| **2** | 媒体 POC：在 Linux 容器里跑通官方 SDK 拉一张图片，产出 sidecar 最小可用版 | 会话存档已开通、一台 Linux 环境 | 中（native 集成不确定） |
| **3** | 媒体落盘：V82 迁移 + 下载 worker + sidecar 调用 + 保留期策略 | 阶段 2 | 中 |
| **4** | 消费：前端媒体展示 + 鉴权下载接口 | 阶段 3 | 低 |

阶段 1 与阶段 2 之间没有依赖，可并行。

---

## 5. 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | 反查返回的密文 userid 与登录时的不一致 | 方案 A 完全失效 | 阶段 0 先验证，不通过不开工 |
| 2 | 手机号/邮箱错误率超 20% 被封 1 天 | 整个企业接口不可用 | 条目级 + 批次级熔断；导入先做格式校验不消耗调用 |
| 3 | 存档 SDK 无 macOS 版 | 本地无法调试，只能容器/远端 | 采用 sidecar，本地只调 HTTP |
| 4 | native 段错误带走 JVM | 服务整体不可用 | sidecar 进程隔离 |
| 5 | 存档媒体属敏感通信内容 | 合规风险 | 落盘前定义保留期与删除机制，参照现有 retention 服务 |
| 6 | 复用 `attachments` 的诱惑 | 外键冲突、要伪造 `messages` 行 | 明确用独立表（3.4 已述） |
| 7 | `provisioning_source` 新增取值影响解绑分支 | 批量绑定记录被误删 | 改动时同步检查 `WeComUserBindingService.unbind` / `resolveOrCreate` |

---

## 6. 待决策

1. **账户互通的匹配凭据用哪个**：只支持手机号、只支持邮箱、还是两者都支持？（手机号反查更直观，邮箱支持 `email_type` 区分企业邮箱/个人邮箱，需确认你企业通讯录里哪个字段是可靠的）
2. **媒体落盘后的第一消费方**：会话工作台展示、AI 识别、还是仅归档？

---

## 7. 明确不做的事

- **不碰邮件链路**。本方案的媒体落盘只用 `MinioStorage` 这个通用存储客户端，与 `EmailAttachmentStore`、`attachments` 表、邮件同步无关。企微媒体有独立表与独立对象前缀。
- 不改动现有的 `chatdata/sync_call_program` 消息拉取链路（媒体走增量，不动存量）。
- 不做通讯录写能力（`user/create|update|delete`、`batch/syncuser`）—— 主数据源未定，反向写会与企微管理端冲突。
- 不引入 `spring.task.scheduling` 之外的调度框架；下载任务注册进现有 `AdaptivePollingScheduler`（与待办提醒同范式）。
