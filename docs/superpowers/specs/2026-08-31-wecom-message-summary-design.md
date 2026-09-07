# 企业微信消息级官方摘要设计

## 目标

将当前企业微信数据与智能专区的 `conversation_daily_summary` 能力从“按天按会话批量摘要”改为“每条消息一条官方摘要任务”。消息入库成功后立即异步排队，worker 为每个 `msgid` 单独提交官方任务、轮询结果并保存后台记录。

## 边界

- 只使用企业微信数据与智能专区的官方 `conversation_daily_summary` 能力。
- 8107 不获取、不保存企业微信消息原文；官方专区根据 `msgid` 和 `secret_key` 读取原文并生成摘要。
- 企业微信 Topic AI 不使用这条能力链；ChatApp、邮件、电话的 Topic AI 不受影响。
- 新消息不再等待每天 `00:05` 才创建摘要任务。
- 历史消息通过有界补偿扫描逐条补建任务。
- 摘要任务记录和摘要结果不自动删除；原始消息仍受现有容量保留策略约束，但摘要未完成时必须保护对应原始消息。

## 当前事实

现有 `wecom_chatdata_messages` 保存消息引用和元数据：

- `msgid`
- `source_conversation_id`
- 发送方、收发方向和会话类型
- `send_time`、`msgtype`
- 应用再次加密后的 `secret_key`

表中没有正文列。`WeComChatDataSyncService` 从专区 viewer 能力取得 `encrypted_secret_key`，用本地 RSA 私钥解密，再通过 `WeComCredentialProtector` 加密后入库。摘要提交时只在内存中解密 `secret_key`，通过专区程序转给官方 `create_summary_task`。

现有 `WeComSummaryGateway` 已支持 `msg_list`，专区程序的摘要能力也接受一条消息的列表，因此不需要修改专区程序协议。

## 架构

```text
sync_msg
  -> WeComChatDataStore 事务
     -> wecom_chatdata_messages
     -> wecom_message_summary_jobs(PENDING)
  -> commit
  -> WeComMessageSummaryWorker
     -> submit(msg_list 只有一条)
     -> poll(jobid)
     -> 保存摘要、原始响应和诊断信息
```

消息行和 `PENDING` 任务在同一个数据库事务中写入。事务提交前 worker 不可见；任一写入失败则整个事务回滚，避免出现“消息已落库但没有摘要任务”的无记录状态。

## 数据模型

新增迁移 `V30__wecom_message_summary_jobs.sql`，创建 `wecom_message_summary_jobs`：

- 身份：`id`、`installation_id`、`auth_corp_id`、`source_conversation_id`、`msgid`、`send_time`
- 状态：`status`，允许 `PENDING`、`SUBMITTED`、`RETRY_WAIT`、`COMPLETED`、`FAILED`
- 官方任务：`wecom_job_id`
- 结果：`summary`、`raw_response_json`
- 请求审计：`raw_request_json`，只保留 `msgid` 和操作类型，不写入 `secret_key`
- 诊断：`validation_stage`、`last_error_code`、`failure_state`
- 调度：`attempt_count`、`next_attempt_at`、`lease_owner`、`lease_until`
- 时间：`created_at`、`updated_at`、`submitted_at`、`completed_at`

约束和索引：

- `(installation_id, msgid)` 唯一，保证重复同步只生成一条任务。
- `summary` 和两个 JSON 字段设置字节上限；超过上限进入校验失败，不截断后伪装成功。
- `attempt_count` 有上限；租约和 `next_attempt_at` 索引支持多实例 worker 安全领取。
- `source_conversation_id`、`send_time`、`status` 建索引，支持按会话、时间和状态查询。

摘要文本和原始官方响应保留在后台，便于判断失败发生在请求构造、HTTP、外层响应、专区响应还是业务字段校验阶段。响应中不得包含 access token、私钥或明文 `secret_key`。

## 入队

`WeComChatDataStore` 的三条消息入库路径都必须在 `messageMapper.insertIgnore` 成功后创建任务：

1. 标准群聊规范化路径；
2. 标准单聊规范化路径；
3. 兼容旧数据的直接投影路径。

重复消息不重复入队。消息入库成功但任务插入失败时，事务整体回滚，并记录结构化失败日志。任务入队不调用网络，不阻塞专区同步请求。

## Worker 生命周期

`WeComMessageSummaryWorker` 使用现有安装实例解析和 `WeComSummaryGateway`：

1. 领取一条或有界数量的可执行任务并设置租约；
2. 从 `wecom_chatdata_messages` 按 `msgid` 读取并在内存中解密 `secret_key`；
3. 生成脱敏请求快照；
4. 调用 `submit`，请求的 `msg_list` 只能包含这一条消息；
5. 保存官方 `jobid`，进入 `SUBMITTED`；
6. 到达轮询时间后调用 `poll`；
7. `status=1` 时保存摘要和原始响应并进入 `COMPLETED`；
8. 官方错误、网络超时或可重试校验失败进入 `RETRY_WAIT`，达到上限进入 `FAILED`。

worker 使用固定短间隔调度实现“立即异步”，但每轮领取量、并发数、超时、退避和最大尝试次数全部有界。服务重启后，过期租约自动重新可领取。

## 原始消息保留

现有 `WeComChatDataRetention` 按消息数量和字节预算删除最早消息。改造后删除候选必须排除存在非终态摘要任务（`PENDING`、`SUBMITTED`、`RETRY_WAIT`）的 `msgid`。摘要任务进入 `COMPLETED` 或 `FAILED` 后，原消息可按原有容量策略删除；任务、摘要、诊断记录不参与这套消息删除。

如果所有候选消息都被未完成摘要保护而仍超出预算，保留任务返回 `withinBudget=false` 并输出告警，不删除受保护消息。

## 可观测性和查询

新增结构化日志事件，字段只包含 `msgid`、任务 ID、状态、官方 jobid（如有）、尝试次数、校验阶段和错误码：

- `wecom.message_summary.enqueued`
- `wecom.message_summary.submitted`
- `wecom.message_summary.completed`
- `wecom.message_summary.retry_wait`
- `wecom.message_summary.failed`

新增后台查询接口，支持按 `msgid`、会话、时间区间和状态分页查询。单条查询必须同时返回消息是否存在、摘要任务状态、官方 jobid、最近错误、校验阶段和关键时间戳，使运维可以区分：消息未入库、未入队、等待提交、官方处理中、格式校验失败或最终失败。

## 配置

现有 `WECOM_DAILY_SUMMARY_ENABLED=true` 继续作为能力开关，避免服务器已有 `.env` 失效；开启后的语义改为“启用企业微信消息级官方摘要”。新增配置使用以下默认值：

```env
WECOM_MESSAGE_SUMMARY_POLL_INTERVAL_SECONDS=1
WECOM_MESSAGE_SUMMARY_BATCH_SIZE=20
WECOM_MESSAGE_SUMMARY_MAX_CONCURRENCY=2
WECOM_MESSAGE_SUMMARY_MAX_TRANSIENT_ATTEMPTS=20
WECOM_MESSAGE_SUMMARY_MAX_BACKOFF_SECONDS=900
WECOM_MESSAGE_SUMMARY_BACKFILL_BATCH_SIZE=200
```

`WECOM_DAILY_SUMMARY_ABILITY_ID` 继续固定为 `conversation_daily_summary`。现有每日 scheduler 不再创建新的批量摘要任务；worker 负责新消息和历史补偿任务。

## 失败分层

- 入队失败：事务回滚，消息和任务均不可见；记录 `ENQUEUE_TRANSACTION`。
- 消息引用缺失或密钥解密失败：任务进入 `FAILED`，记录 `MESSAGE_REFERENCE` 或 `SECRET_KEY_DECRYPT`。
- HTTP、access token、专区调用暂时失败：进入 `RETRY_WAIT`。
- 官方返回非零错误：记录 `OFFICIAL_ERROR`，按错误类型决定重试或最终失败。
- 外层 JSON、`response_data`、字段集合、状态或摘要长度校验失败：保存原始响应，记录精确 `validation_stage`，进入重试或最终失败。

## 测试和验收

- repository 测试：同一 `(installation_id, msgid)` 幂等；状态迁移、租约、重试、原始响应上限和摘要保护删除。
- store 测试：三条入库路径都在同一事务创建 `PENDING` 任务；重复消息不重复创建。
- gateway/worker 测试：提交请求严格为单条 `msg_list`；轮询完成保存摘要；官方错误、非法响应和超时按阶段记录。
- retention 测试：非终态任务对应消息不会删除，终态任务对应消息可按预算删除。
- web 测试：按 `msgid`、会话、状态查询返回完整诊断字段，且不泄露 `secret_key`、token 或私钥。
- 构建验收：后端专项测试、完整 Maven 测试和 Spring 打包必须通过；数据库迁移可在空库和已有 V29 数据上执行。

## 不在本次范围

- 不修改 `demo/wecom-chatdata-zone-program` 的官方摘要能力协议。
- 不把企业微信正文同步到 8107 或 PostgreSQL。
- 不把摘要结果自动转换为 Topic，也不改变 ChatApp、邮件、电话的 Topic 聚合逻辑。
- 不新增企业微信前端 Topic AI 入口；本次只提供后台消息摘要查询合同。

## 媒体消息边界（2026-09-01）

- 图片、语音、视频和文件消息不提交 `conversation_daily_summary`；已存在的媒体任务由 worker 以 `UNSUPPORTED_MEDIA` 终态收敛，避免重试占满摘要队列。
- worker 以单条任务作为故障隔离边界；某条任务读取、调用或状态迁移异常时，只保留该任务租约等待恢复，并继续领取本批次后续任务，禁止一条媒体失败阻断其他 `PENDING` 文本消息。
- 专区 viewer 只透传受限的 `media` 标识（如 `sdkfileid`、`media_id`、校验和、文件名和格式），8107 写入 `wecom_chatdata_messages.media_json`；不保存正文、令牌或私钥。
- MinIO 下载必须使用官方媒体下载接口及真实 `sdkfileid`/解密参数，不能从 `msgid` 推导。当前版本完成标识透传和摘要隔离，下载 worker 仍需在部署环境确认专区返回字段和官方媒体权限后接入。

## 生产修复：历史单聊身份关联和诊断（2026-09-02）

企业微信单聊不会按手机号、邮箱、昵称或其他渠道 identity 自动合并。每个单聊只通过
`wecom_source_conversations.contact_identity_id` 关联到同账号范围内的 `wecom` identity，
再由该 identity 的 `contact_id` 找到联系人。

早于单聊身份绑定逻辑的历史 `DIRECT` 会话可能缺少这个外键。`V38` 只会为同时满足以下
条件的会话回填：会话是 `DIRECT`、当前尚未关联 identity、参与者中有 `EXTERNAL_CONTACT`、
在活动企业微信账号范围内恰好匹配一条未删除的企业微信 identity。群聊、已有绑定和多候选
记录均不处理，避免猜测联系人归属。

官方任务已经提交后，瞬态错误可使任务进入 `RETRY_WAIT`；`V39` 后，轮询到完成结果允许
从该状态迁移至 `COMPLETED`。官方摘要先单独持久化，后续 Topic 活动投影失败只记结构化
警告，不回滚摘要或造成重复的官方轮询。`last_error_diagnostic` 保存脱敏的异常类型和短消息，
使 `MESSAGE_SUMMARY_RUNTIME_ERROR` 能定位到具体失败层，同时不保存 `secret_key`、token 或私钥。
