# 消息中心 PostgreSQL 与 MinIO 数据库设计

日期：2026-07-17
状态：已批准
适用范围：`demo/message-center-demo` 向第一版多人消息中心演进，以及正式 CRM 消息中心主线

## 1. 目标

第一版建立一个单租户、多用户、多渠道、多账号的消息中心：

- PostgreSQL 是联系人、渠道身份、渠道账号、会话、消息、状态、权限、未读和审计的唯一业务真源。
- MinIO 保存所有图片、视频、文档、语音和其他二进制附件。
- Java 后端拥有业务合同、权限、事务和状态机。
- IMAP、SMTP、CAMS、企业微信客服等 adapter 只负责协议映射、同步和发送。
- Docker Compose 统一管理 PostgreSQL、MinIO、初始化、迁移、应用和备份入口。
- 旧 JSONL 一次性导入并对账后停止读写，不保留长期双写路径。

## 2. 明确边界

### 2.1 当前主线

- 一个 Docker 部署只服务一个企业组织，不引入 `tenant_id`。
- 第一版支持多个用户、团队、角色、会话分配、额外授权和个人未读。
- 每个渠道支持多个业务账号，消息与同步游标必须关联明确的 `channel_account_id`。
- 底层会话按“渠道账号 + 联系人渠道身份”建立。
- 联系人统一时间线是跨底层会话的后端聚合结果，不是另一份消息真源。
- 联系人合并或拆分只改变身份归属，不改写历史消息。
- 第一版不引入 Redis、消息队列、Elasticsearch 或 pgvector。

### 2.2 非目标

- 不保存附件二进制到 PostgreSQL。
- 不允许前端直连渠道 API、数据库或 MinIO 管理接口。
- 不允许 adapter、controller、UI 或脚本拥有联系人合并、权限、未读或消息状态真相。
- 不保存明文密码、Token、AccessKey、会话令牌或完整签名 URL。
- 不为旧 JSONL 保留永久兼容读写、双写或静默降级路径。
- 不在第一版加入 AI 向量、Topic、任务、看板或复杂 BI 表。

## 3. Owner 与组件

### 3.1 PostgreSQL

负责：

- 用户、角色、团队和服务端登录会话。
- 企业、联系人、标签、关系和渠道身份。
- 渠道账号、加密配置、同步游标和消息模板。
- 会话、消息、参与人、状态历史、个人未读和额外授权。
- 脱敏渠道事件收件箱、事务型 outbox、审计和数据导入记录。
- MinIO 附件元数据和对象引用。

### 3.2 MinIO

负责：

- 图片、视频、文档、压缩包、语音和其他附件本体。
- 稳定对象键和对象级校验。
- 通过后端鉴权后的代理下载或极短期访问授权。

MinIO 不负责联系人、会话、消息状态、权限或附件业务归属。

### 3.3 Java 消息中心

负责：

- API、权限判断、身份归属、会话聚合和消息状态机。
- 渠道事件幂等接收、事务处理和失败重试。
- outbox 领取、渠道发送、超时歧义处理和状态回写。
- 附件上传、下载、转存、鉴权和清理。
- 数据导入、对账、审计和结构化错误。

### 3.4 渠道 adapter

负责：

- 验签、解密、拉取、协议字段解析和发送调用。
- 将外部载荷投影为共享消息合同。
- 返回稳定外部消息 ID、状态、错误码和可重试判断。

adapter 不直接向 UI 返回渠道原始载荷，不创建并行业务表。

## 4. 数据库通用约定

- PostgreSQL 版本使用 17 系列固定版本，不使用 `latest`。
- 启用 `pgcrypto` 生成 UUID，启用 `pg_trgm` 支持联系人和中文消息模糊搜索。
- 业务主键统一为 UUID。
- 时间统一使用 `timestamptz`，应用和数据库按 UTC 持久化。
- 固定生命周期状态使用 `varchar` 加 `check` 约束，不使用 PostgreSQL enum。
- 渠道可变字段只进入明确命名的 `metadata_jsonb`，核心查询字段不得只藏在 JSONB。
- 所有唯一约束按规范化值建立，展示值与规范化值分开保存。
- 用户、企业、联系人、身份和渠道账号允许软删除；消息、状态事件和审计记录不可原地改写或直接硬删除。
- 需要更新的聚合行使用 `version bigint` 做乐观锁。
- 签名 URL 只在请求期内生成，不进入数据库、日志或前端持久状态。

## 5. 身份、权限与组织表

### 5.1 `users`

关键字段：

- `id uuid` 主键。
- `username varchar(100)` 登录名。
- `username_normalized varchar(100)` 小写规范化登录名，唯一。
- `password_hash varchar(255)` 使用 Argon2id 编码串，不保存可逆密码。
- `display_name varchar(100)`。
- `status varchar(20)`：`active`、`locked`、`disabled`。
- `last_login_at timestamptz`。
- `created_at`、`updated_at`、`deleted_at`。

### 5.2 `roles` 与 `user_roles`

`roles` 保存 `agent`、`supervisor`、`admin`、`owner` 等稳定角色代码和显示名。`user_roles(user_id, role_id)` 使用组合唯一约束。前端从能力接口读取权限结果，不硬编码角色判断。

### 5.3 `user_sessions`

关键字段：

- `id uuid`。
- `user_id uuid`。
- `token_hash bytea`，唯一，只保存令牌哈希。
- `issued_at`、`expires_at`、`last_seen_at`、`revoked_at`。
- `ip_address inet`、`user_agent varchar(500)`，用于安全审计。

会话令牌由密码学安全随机源生成，至少 256 bit；服务端根据 SHA-256 哈希查找会话。退出、禁用用户或安全事件可显式撤销。

### 5.4 `teams` 与 `team_members`

`teams` 保存团队名称、状态和主管。`team_members(team_id, user_id)` 唯一，并保存成员在团队内的职责与加入时间。

访问规则：

- 坐席查看分配给自己或明确授权的会话。
- 主管查看所属团队会话。
- 管理员管理账号和配置，但不因角色自动拥有全部消息原文权限。
- 负责人查看明确授权范围及汇总。

## 6. 企业与联系人表

### 6.1 `companies`

保存企业名称、类型、国家、城市、网站、负责人、备注、状态、创建人和软删除时间。企业详情只通过显式关系查询联系人和沟通记录。

### 6.2 企业标签

- `company_tags`：名称、颜色、状态。
- `company_taggings(company_id, tag_id)`：组合唯一。

### 6.3 `contacts`

关键字段：

- `id uuid`。
- `display_name varchar(100)`。
- `role_title varchar(100)`。
- `remark text`。
- `status varchar(20)`：`active`、`disabled`、`merged`。
- `merged_to_id uuid` 自引用，可空。
- `created_by`、`created_at`、`updated_at`、`deleted_at`。

合并时源联系人标记 `merged`，渠道身份转移到目标联系人，并写审计。消息仍通过原会话和身份追溯，不批量改写消息行。

### 6.4 联系人标签

- `contact_tags`：名称、颜色、状态。
- `contact_taggings(contact_id, tag_id)`：组合唯一。

### 6.5 `company_contacts`

保存 `company_id`、`contact_id`、关系类型、是否主联系人、备注、创建人和建立时间。`(company_id, contact_id)` 唯一。解除关系不删除联系人、身份或历史消息。

### 6.6 `contact_identities`

关键字段：

- `id uuid`。
- `contact_id uuid`，未知身份时可空。
- `channel_type varchar(30)`：`email`、`whatsapp`、`wecom`、`phone`。
- `identity_scope varchar(255)`：全局身份使用 `global`，账号或企业范围身份使用稳定作用域。
- `identity_value varchar(255)`：展示值。
- `normalized_value varchar(255)`：查询与唯一约束值。
- `display_name varchar(100)`。
- `is_primary boolean`。
- `verify_status varchar(20)`：`unverified`、`verified`。
- `source varchar(30)`：`manual`、`synced`、`imported`。
- `created_at`、`updated_at`、`deleted_at`。

唯一约束：`(channel_type, identity_scope, normalized_value)` 在未软删除记录中唯一。

### 6.7 `phone_notes`

保存联系人、可选企业、可选电话渠道身份、沟通时间、纪要、下一步、创建人和创建时间。电话纪要属于人工沟通记录，不伪装为渠道消息；联系人或企业关系解除不删除历史纪要。

## 7. 渠道账号、游标与模板

### 7.1 `channel_accounts`

关键字段：

- `id uuid`。
- `channel_type varchar(30)`。
- `name varchar(100)`。
- `account_identifier varchar(255)`。
- `account_identifier_normalized varchar(255)`。
- `auth_status varchar(30)`：`unbound`、`active`、`expired`、`failed`、`disabled`。
- `sync_status varchar(30)`：`idle`、`syncing`、`success`、`failed`。
- `encrypted_config jsonb`：只保存密文、nonce、算法和密钥版本。
- `last_synced_at`、`created_at`、`updated_at`、`deleted_at`。

唯一约束：未软删除记录中 `(channel_type, account_identifier_normalized)` 唯一。

敏感配置使用 AES-256-GCM 加密，每次写入使用独立 nonce，并保存密钥版本以支持轮换。加密主密钥不进入该表，只通过 Docker Secret 注入应用。

### 7.2 `channel_sync_cursors`

关键字段：

- `id uuid`。
- `channel_account_id uuid`。
- `cursor_type varchar(50)`：如 `imap_folder`、`cams_history`、`wecom_sync_msg`。
- `scope_key varchar(255)`：如 `INBOX`、`SENT` 或 OpenKfId。
- `cursor_value text`。
- `cursor_timestamp timestamptz`。
- `updated_at`、`version`。

唯一约束：`(channel_account_id, cursor_type, scope_key)`。

同步游标只有在对应事件已可靠写入 `channel_events` 后才能推进。

### 7.3 `message_templates`

按渠道账号保存模板代码、模板名、语言、正文、状态、渠道更新时间、脱敏元数据和最近同步时间。唯一约束为 `(channel_account_id, provider_template_id, language_code)`。

## 8. 会话、消息与未读

### 8.1 `conversations`

关键字段：

- `id uuid`。
- `channel_account_id uuid`。
- `contact_identity_id uuid`。
- `status varchar(20)`：`open`、`closed`、`archived`。
- `assigned_team_id uuid`、`assigned_user_id uuid`，可空。
- `next_ingest_sequence bigint`，事务内原子递增。
- `last_message_id uuid`，可空。
- `last_message_at timestamptz`。
- `created_at`、`updated_at`、`version`。

唯一约束：`(channel_account_id, contact_identity_id)`。

### 8.2 `conversation_access_grants`

保存 `conversation_id`、`user_id`、授权人、授权原因、授权时间、过期时间和撤销时间。未撤销授权中 `(conversation_id, user_id)` 唯一。

### 8.3 `conversation_read_states`

关键字段：

- `conversation_id uuid`。
- `user_id uuid`。
- `last_read_sequence bigint`。
- `last_read_message_id uuid`，可空。
- `last_read_at timestamptz`。
- `updated_at`。

主键：`(conversation_id, user_id)`。

未读数只统计 `ingest_sequence > last_read_sequence`、`direction = inbound` 且 `counts_as_unread = true` 的消息。历史导入和补拉消息设置 `counts_as_unread = false`。

### 8.4 `messages`

关键字段：

- `id uuid`。
- `conversation_id uuid`。
- `channel_account_id uuid`。
- `source_event_id uuid`，可空。
- `provider_message_id varchar(255)`，可空。
- `client_request_id varchar(255)`，主动发送时可空。
- `direction varchar(20)`：`inbound`、`outbound`、`system`。
- `message_kind varchar(30)`：`text`、`template`、`image`、`video`、`document`、`email`、`system`。
- `subject text`、`body_text text`、`body_html text`。
- `occurred_at timestamptz`：渠道业务时间。
- `received_at timestamptz`：系统接收时间。
- `ingest_sequence bigint`：会话内接收序列。
- `counts_as_unread boolean`。
- `current_status varchar(30)`。
- `current_status_at timestamptz`。
- `created_by_user_id uuid`，主动发送时可空。
- `metadata_jsonb jsonb`：只保存已脱敏的渠道扩展字段。
- `created_at timestamptz`。

约束：

- `conversations` 建立 `(id, channel_account_id)` 唯一键，`messages(conversation_id, channel_account_id)` 使用组合外键，阻止账号漂移。
- `(conversation_id, ingest_sequence)` 唯一。
- 非空外部消息 ID按 `(channel_account_id, provider_message_id)` 唯一。
- 非空客户端请求 ID按 `(channel_account_id, client_request_id)` 唯一。

展示顺序使用 `(occurred_at, id)`；未读使用 `ingest_sequence`，避免延迟到达或历史补拉扰乱阅读游标。

### 8.5 `message_participants`

保存 `message_id`、角色 `sender/to/cc/bcc`、渠道身份 ID、展示地址和规范化地址。一个消息可有多个收件人、抄送和密送，兼容邮件模型。

### 8.6 `message_status_events`

关键字段：

- `id uuid`、`message_id uuid`。
- `status varchar(30)`。
- `occurred_at timestamptz`、`received_at timestamptz`。
- `provider_event_id varchar(255)`，可空。
- `reason_code varchar(100)`、`reason_message text`。
- `metadata_jsonb jsonb`，必须脱敏。

状态事件只追加。`messages.current_status` 是事务维护的当前投影；较旧状态事件不得覆盖较新的确定状态。

状态集合：`pending`、`processing`、`submission_unknown`、`submitted`、`sent`、`delivered`、`read`、`failed`、`cancelled`。

## 9. 附件与 MinIO

### 9.1 `attachments`

关键字段：

- `id uuid`、`message_id uuid`。
- `storage_provider varchar(20)`，第一版固定为 `minio`。
- `bucket varchar(100)`、`object_key varchar(1024)`。
- `original_name varchar(500)`、`mime_type varchar(255)`。
- `size_bytes bigint`、`sha256 char(64)`。
- `media_kind varchar(30)`：`image`、`video`、`audio`、`document`、`archive`、`other`。
- `width int`、`height int`、`duration_ms bigint`，可空。
- `storage_status varchar(20)`：`pending`、`ready`、`failed`、`deleted`。
- `failure_code varchar(100)`、`failure_message text`。
- `created_at`、`ready_at`、`deleted_at`。

唯一约束：`(bucket, object_key)`。`sha256` 建索引用于重复内容检测，但不同业务附件可以引用相同内容哈希。

### 9.2 访问规则

- 上传前校验文件大小、类型和调用者权限。
- 入站外部附件由服务端下载并转存 MinIO，失败不阻断消息正文入库。
- 下载前校验用户对消息所属会话的访问权限。
- 后端可直接代理文件，或返回极短期 MinIO 授权；授权地址不入库、不写日志。
- 上传成功但数据库事务失败的孤儿对象由有界清理任务根据对象时间和引用状态清理。

## 10. 事件、outbox 与审计

### 10.1 `channel_events`

关键字段：

- `id uuid`、`channel_account_id uuid`。
- `provider_event_id varchar(255)`，可空。
- `event_type varchar(100)`。
- `occurred_at`、`received_at`。
- `payload_jsonb jsonb`：入库前已移除凭据和签名参数。
- `payload_hash char(64)`：脱敏规范载荷哈希。
- `processing_status varchar(20)`：`received`、`processing`、`processed`、`retry_wait`、`dead`。
- `attempt_count int`、`next_attempt_at timestamptz`。
- `last_error_code varchar(100)`、`last_error_message text`。
- `processed_at timestamptz`、`trace_id varchar(100)`。

幂等约束：

- 有外部事件 ID 时，`(channel_account_id, provider_event_id)` 唯一。
- 无外部事件 ID 时，`(channel_account_id, event_type, payload_hash)` 唯一。

worker 使用 `FOR UPDATE SKIP LOCKED` 有界领取，单批数量、并发、超时、重试次数和退避上限均由明确配置控制。

### 10.2 `outbox_jobs`

关键字段：

- `id uuid`、`message_id uuid` 唯一。
- `job_type varchar(50)`。
- `status varchar(20)`：`pending`、`processing`、`retry_wait`、`completed`、`dead`。
- `attempt_count int`、`max_attempts int`。
- `next_attempt_at timestamptz`。
- `lease_owner varchar(100)`、`lease_until timestamptz`。
- `last_error_code varchar(100)`、`last_error_message text`。
- `created_at`、`updated_at`、`completed_at`。

网络超时且无法确认渠道是否已接收时，消息进入 `submission_unknown`。worker 必须使用 `client_request_id`、渠道查询或后续回调对账，禁止盲目重发。

### 10.3 `audit_logs`

保存操作者、动作、资源类型、资源 ID、脱敏前后摘要、结果、IP、User-Agent、traceId 和发生时间。以下动作必须审计：

- 登录、失败登录、退出和会话撤销。
- 查看敏感消息原文和附件。
- 联系人合并、拆分和身份改绑。
- 会话分配、额外授权和权限变更。
- 渠道账号新增、修改、测试和禁用。
- 数据导入、备份和恢复。

## 11. 入站与出站数据流

### 11.1 入站

1. adapter 验签、解密并规范化输入。
2. 敏感字段和签名 URL 查询参数在持久化前移除。
3. 事件幂等写入 `channel_events` 后，同步游标才允许推进。
4. worker 解析事件，查找或创建渠道身份和底层会话。
5. 在数据库事务中写入消息、参与人、状态历史和附件元数据。
6. 附件异步转存 MinIO；失败记录结构化原因并有限重试。
7. 事件处理成功后标记 `processed`，失败超过上限转 `dead` 并进入管理员处理入口。

### 11.2 出站

1. API 验证用户、团队、会话授权和渠道账号状态。
2. 同一事务创建 `pending` 消息、初始状态事件和 `outbox_jobs`。
3. worker 领取任务、解密渠道配置并调用对应 adapter。
4. 成功后保存外部消息 ID并追加状态事件。
5. 明确失败按是否可重试进入 `retry_wait` 或 `dead`。
6. 不确定结果进入 `submission_unknown` 并执行对账，不直接重复发送。

## 12. 搜索、分页与索引

- 联系人名称、备注、身份展示值和标签使用 `pg_trgm`。
- 中文消息使用 `pg_trgm` 子串搜索；空格分词语言可额外维护 `tsvector(simple)`。
- 联系人和会话列表按 `last_message_at DESC` 建索引。
- 消息列表按 `(conversation_id, occurred_at, id)` 建索引。
- 未读按 `(conversation_id, ingest_sequence)` 建部分索引，只覆盖有效入站消息。
- `channel_events` 和 `outbox_jobs` 按 `(status, next_attempt_at)` 建领取索引。
- 列表统一使用游标分页，不使用深层 `OFFSET`。
- 第一版不引入 Elasticsearch 或独立搜索服务。

## 13. Docker Compose 设计

服务组成：

- `postgres`：PostgreSQL 17，独立数据卷。
- `minio`：附件对象存储，独立数据卷。
- `minio-init`：一次性创建 bucket 和基础策略。
- `db-migrate`：一次性运行 Flyway，成功后退出。
- `message-center`：API 与有界后台 worker。
- `backup`：按需执行 PostgreSQL 备份和 MinIO 对象镜像。

运行规则：

- 开发时可只启动 PostgreSQL、MinIO、初始化和迁移，本地 Maven 启动应用。
- 正式本地运行由 Compose 启动全部服务。
- PostgreSQL 和 MinIO 管理端口只绑定本机。
- 应用等待 PostgreSQL、MinIO 健康且 Flyway 成功后启动。
- 镜像固定版本，不使用 `latest`。
- 数据库密码、MinIO 凭据和应用加密主密钥使用 Docker Secret。
- 应用默认 `8099` 只供用户访问；自动化或临时验收只使用 `8100`，完成后必须关闭。

当前机器未发现 Docker CLI 或运行时。实施前需由用户选择并安装可用 Docker 环境；该外部前置条件不通过代码绕过。

## 14. 旧数据迁移

### 14.1 数据源

- 邮件 `inbox.jsonl`。
- ChatApp `messages.jsonl`。
- 联系人分组 `contact-groups.jsonl`。
- ChatApp 模板文件。
- 本地媒体缓存。

### 14.2 迁移表

`data_import_batches` 保存批次类型、源文件标识、源文件哈希、开始/结束时间、读取数、插入数、更新数、跳过数、失败数、状态和执行人。

`data_import_errors` 保存批次、源行号、脱敏记录标识、错误码、错误信息和发生时间，不保存敏感原文。

### 14.3 顺序

1. 停止旧写入，生成只读快照、文件哈希和数量清单。
2. 执行 Flyway 从空库建立 schema。
3. 建立渠道账号。
4. 导入联系人分组、联系人、渠道身份和标签。
5. 导入模板、会话、消息、参与人和状态历史。
6. 将媒体缓存上传 MinIO 并写入附件元数据。
7. 对邮件 Message-ID、ChatApp 外部消息 ID、联系人分组、消息数量和附件引用进行对账。
8. 只有全部阻断校验通过后才切换 PostgreSQL 单一读写。
9. 旧 JSONL 作为只读迁移档案保留，不继续双写，不擅自删除。

### 14.4 停止条件

出现以下任一情况不得切换：

- 同一规范化渠道身份落入多个未合并联系人且无法确定归属。
- 外部消息 ID冲突但正文、方向或时间不一致。
- 导入数量与源清单无法解释地不一致。
- 附件数据库引用与 MinIO 对象无法对账。
- Flyway、权限矩阵或核心集成测试失败。
- 日志、API 或数据库检查发现密钥或完整签名 URL。

## 15. 备份与恢复

- PostgreSQL 使用一致性逻辑备份。
- MinIO 使用对象镜像备份。
- 同一备份批次保存数据库备份、对象镜像、批次时间和校验清单。
- 第一版不在 Compose 内放置无界常驻定时器；宿主机以明确计划调用 `backup` 服务。
- 保留周期由配置明确给出，删除备份必须记录审计。
- 恢复验收必须验证用户登录、权限、联系人、会话、消息、未读和附件读取。

## 16. 验收设计

### 16.1 数据库与迁移

- Flyway 可从空库完整建库。
- 连续升级不会修改已发布迁移。
- 所有外键、唯一约束、部分索引和状态检查生效。
- 旧数据导入可重复执行且不制造重复消息或身份。

### 16.2 权限与多人状态

- 坐席只能查看分配或授权会话。
- 主管只能查看所属团队会话。
- 管理员不会因管理权限自动获得全部原文权限。
- 不同用户拥有独立阅读游标和未读数。
- 联系人合并、拆分、授权和敏感查看均产生审计记录。

### 16.3 消息可靠性

- 重复 webhook、重复同步和重叠时间窗口只生成一条业务消息。
- 多 worker 并发领取不会重复处理同一事件或 outbox。
- 渠道超时歧义不会直接触发重复发送。
- 状态乱序到达不会让当前状态倒退。
- 历史导入和补拉不制造未读。

### 16.4 MinIO

- 上传大小、类型、超时和权限限制生效。
- 无会话权限用户无法读取附件。
- 附件转存失败不丢失消息正文，并可有限重试。
- 数据库附件引用和 MinIO 对象可对账。

### 16.5 安全与运行

- 密码只保存强哈希，会话令牌只保存哈希，渠道凭据只保存应用密文。
- Docker Secret 不进入镜像、Git、普通 artifact 或日志。
- 日志、API、数据库和前端状态不包含完整签名 URL。
- PostgreSQL、MinIO、迁移和应用健康检查生效。
- 浏览器端到端验收只使用 `8100`，测试结束后确认无监听；不在 `8099` 测试。

## 17. 完成定义

数据库接入只有同时满足以下条件才算完成：

- PostgreSQL 与 MinIO 成为唯一运行真源，旧 JSONL 不再读写。
- Flyway、Docker Compose、数据导入、对账和联合恢复均通过。
- 多账号、多人权限、独立未读、幂等、outbox 和附件权限均有自动化证据。
- 当前 PRD、数据库设计、配置示例和运行文档保持一致。
- 不存在未解释的迁移失败、数据数量偏差、敏感信息泄漏或本轮遗留占位项。
