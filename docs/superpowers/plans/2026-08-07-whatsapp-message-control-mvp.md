# WhatsApp 固定号码消息中控 MVP 实施记录

**日期：** 2026-08-07

**范围：** 固定一个现有 ChatApp/WhatsApp 号码；暂停号码注册、验证码、销售绑定和多号码治理。

## Owner

- MessageSendApplicationService：本地消息、初始状态和 outbox 的事务 owner。
- MessageOutboxWorker：有界领取、CAMS 提交、有限重试和提交结果未知处理。
- ChatAppMessageApplicationService：固定账号、联系人身份和会话解析。
- ChatAppWebhookInboxService：验签、请求上界、原始事件幂等收件。
- ChatAppWebhookProjector：入站文本和消息状态投影。
- ChatAppPollingProjector：将 ListChatappMessage 行转换为 channel event，并复用 Webhook projector。
- AliyunChatAppOutboundGateway：内部消息命令到 CAMS SDK 的唯一 adapter。
- ConversationAccessService：发送、媒体读取和单消息详情读取的会话授权 owner。
- AuthSessionService：opaque token 签发、校验、过期和吊销 owner。

## 已实现

- [x] 文本和模板旧入口改为 outbox 受理。
- [x] 图片、视频和文件先持久化到 MinIO，再进入同一 outbox。
- [x] 媒体授权在 MinIO 写入前完成；消息、附件和 outbox 同事务提交，失败清理本次对象。
- [x] 媒体下载按 conversation 权限查询，不再公开 `/api/media/**`。
- [x] 新增 POST /api/v1/whatsapp/messages 统一会话发送入口。
- [x] 复用 messages、outbox_jobs、message_status_events 和 channel_events。
- [x] 数据库唯一键与会话锁共同保护 channelAccountId + clientRequestId 幂等。
- [x] outbox 批量领取上限 50、30 秒租约、最多 3 次尝试和最大 300 秒退避；租约过期的 `processing` 不自动重发，先进入 `submission_unknown` 对账态。
- [x] CAMS 网络调用位于数据库事务外，结果写入使用独立显式事务。
- [x] channel event 的过期 processing 租约可重新领取；outbox 的过期 processing 只恢复为 dead/`submission_unknown`，不再重新领取调用 CAMS。
- [x] CAMS 超时或无法分类的提交结果进入 submission_unknown 并停止自动重发。
- [x] 新增 POST /api/v1/webhooks/chatapp，最大请求体 1 MiB，验签失败返回 401。
- [x] Webhook provider event/payload hash 幂等，失败投影最多重试 5 次。
- [x] Webhook 请求流在绑定前限制为 1 MiB；持久化 payload 只保留 projector 必需字段。
- [x] 入站文本、submitted/sent/delivered/read/failed 状态进入统一消息投影。
- [x] ListChatappMessage 转换为 channel event 并复用同一 projector；submission_unknown 可按 taskId 对账，已知收件人的孤儿 outbound 也会导入历史消息。
- [x] 轮询状态归一化忽略 Unread 等负向读标志，不误投影为 read。
- [x] assigned user、team member、有效 grant 和 admin 复用现有权限模型，发送前最终 `FOR UPDATE` 复核。
- [x] 固定账号必须为 active，且数据库号码与 `app.chatapp-from` 一致。
- [x] 登录签发 32-byte opaque token，`user_sessions` 只保存 SHA-256；过期或吊销 token 不可鉴权。
- [x] logout 吊销当前服务端 session，前端随后清理本地认证状态。
- [x] 错误登录凭据返回结构化 401，不再被全局异常兜底误报为 500；未知 HTTP 路由返回 404。
- [x] 本地 pending 事务提交后发布 SSE 失效事件，前端持续展示 pending、submission_unknown、submitted、sent、delivered、read、failed 和 cancelled。
- [x] 新迁移允许既有代码使用的 contact_identities.channel_type=chatapp，不修改历史迁移。
- [x] 单消息详情在解析或返回正文前执行会话权限校验，非 owner 返回 403。
- [x] 模板同步按 CAMS `AuditStatus` 映射 APPROVED/REJECTED/PENDING/SUSPENDED/UNKNOWN，并保存原始审核状态与原因；只有 APPROVED 可发送。
- [x] `/api/channel-accounts/**` 仅管理员可访问；固定 ChatApp/WhatsApp 号码不能通过设置接口修改，管理员仍可改显示名称。
- [x] 凭据主密钥缺失时 fail-closed，不再使用代码内固定回退值。

## 验收结果

- 后端定向测试覆盖：消息受理、outbox worker、固定账号解析、会话权限、媒体受理、Webhook 验签/投影、轮询投影和 opaque session。
- PostgreSQL 17.5 Testcontainers 集成测试 4/4 通过，Flyway 从空库成功执行 v1-v8；Docker 29.6.1 需用 `mvn -q -Dapi.version=1.44 -Dtest=AppIntegrationTest test` 运行，避免 docker-java 默认 API 低于 daemon 最小版本。
- 最终门禁命令：`mvn -q -Dtest='*,!AppIntegrationTest' test`、`mvn -q -Dapi.version=1.44 -Dtest=AppIntegrationTest test`、`npm test`、`npm run build` 和相关路径 `git diff --check`。
- 前端构建存在改动前已有的 Vite 500 kB chunk warning，该 warning 不由本轮引入。

## 未闭合门禁

- PostgreSQL/Flyway 启动和迁移链已有集成证据，但 `FOR UPDATE SKIP LOCKED` 的真实并发竞争仍未建立专用容器测试。
- CAMS SDK 没有给出回调签名合同；真实环境必须校准 X-CAMS-Signature、X-CAMS-Timestamp 与 canonical string。
- application-dev.yml 中既有明文第三方凭据仍需轮换并迁移到环境变量，此安全问题不属于号码注册功能，但上线前必须处理。
- 项目既有 `MyBatisPlusConfig` 与未提交的 `MyBatisMapperConfig` 同时扫描 mapper，集成启动会输出重复注册 warning；该共享配置冲突不属于本轮消息中控 owner，需在独立配置治理任务中收敛为唯一扫描入口。
- 前端大 chunk warning 为既存债务，不影响本轮消息链路，但发布优化应单独处理。

## 2026-08-10 幂等事故收口

一次模板任务出现重复发送的根因是：CAMS 已接受提交后，本地 JSONB 整行回写失败，30 秒租约过期又重新领取并再次调用 CAMS。当前实现已改为窄 SQL 状态更新、租约 owner 条件终态写入，以及过期 outbox 转 `submission_unknown`；重复发送风险只能通过 task/provider ID 对账解决，不能靠盲目重试。

轮询同步不再静默丢弃所有未关联 outbound：只要 CAMS 返回明确客户号码，就用稳定 provider event key 写入 channel event，并由共享 projector 创建 outbound 历史消息；缺少收件人仍保守跳过，避免写入错误会话。

本轮验证：定向消息/轮询/projector/mapper 测试全部通过；PostgreSQL 17.5 Testcontainers 的 `AppIntegrationTest` 5/5 通过并确认 Flyway v1-v8 与恢复查询可启动。当时全量非集成测试仍有一个工作区既有的 `MessageControllerAuthorizationTest` 失败（未授权读取实际返回 200 而非预期 403）；该问题已在下述阻断收口中修复。

## 2026-08-10 MVP 阻断收口

- 消息详情复用 `ConversationAccessService`，越权请求不再得到消息正文。
- 模板同步不再把所有上游模板硬编码为 APPROVED。CAMS `pass/fail/auditing/unaudit` 分别映射为 APPROVED/REJECTED/PENDING/SUSPENDED，空值或未知值映射为 UNKNOWN；原始 `auditStatus` 和 `reason` 写入 `metadata_jsonb`。详情 API 失败时仍更新审核状态并保留已有正文，避免旧 APPROVED 继续可发送；历史消息恢复正文使用不受当前审核状态限制的展示查询。
- 渠道设置的列表、更新、同步和凭据接口统一要求 `ROLE_ADMIN`。固定 ChatApp/WhatsApp 的 `accountIdentifier` 不可修改；数据库中的加密凭据当前也不代表 CAMS 运行时切换，CAMS 发送与同步仍由 `AppConfig` 提供运行时配置。
- `CredentialConfig` 删除固定开发主密钥回退。IDEA 或其他方式启动后端前必须设置 `CREDENTIAL_MASTER_KEY`，其值为 Base64 编码的 32 字节密钥；缺失时应用以 `CREDENTIAL_MASTER_KEY_REQUIRED` 停止启动。
- 本轮没有修改 `application-dev.yml`，其中既有明文凭据治理仍保留为上线阻断。

本轮验证：四项组合测试通过；全量非集成测试通过；PostgreSQL 17.5 Testcontainers `AppIntegrationTest` 6/6 通过，Flyway 从空库执行 v1-v8，并真实验证模板从 APPROVED 降级为 REJECTED 时的状态、metadata JSONB 冲突更新与正文保留。

## 停止条件

未完成真实 CAMS 回调合同校准、真实账号消息收发回执和生产凭据治理前，只能称为“代码层 MVP 已实现”，不能称为“生产上线完成”。
