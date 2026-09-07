# 企业微信群昵称异步刷新设计

## 目标

为企业微信群会话提供可追踪的群昵称解析和可靠的内外部群分类。任意拥有该群会话访问权的已绑定员工可手动提交外部群昵称刷新；群 Topic 成功更新后，距离上次实际名称查询满 24 小时才自动提交一次。独立的外部客户群同步任务按企业安装分页建立群 ID 集合，整轮成功后才提交群类型投影。

## 边界

- 只处理 `wecom_source_conversations.conversation_type = 'GROUP'`。
- 不在 ChatData 入库事务、Topic worker 或 HTTP 请求中直接请求企业微信。
- 只调用客户群接口 `externalcontact/groupchat/get`，请求 `need_name=1`；ChatData 仍不保存企业微信消息正文。
- 外围列表、Topic 和关联群不显示内部键：`INTERNAL` 固定为“内部群聊”，`EXTERNAL` 仅在已解析到真实名称时显示该名称，其余情况固定为“外部群聊”。
- 内部群的真实名称不通过刷新任务落库。进入会话后，由同一企业微信 OpenDataFrame 内固定的 `chatName` 头部展示，消息列表独立滚动。
- 客户群接口不能解析名称或无权限时，记录为不可获取，不做定时反复请求；手动刷新可重新发起一次显式尝试。
- 不在每个 Topic 生成时扫描外部群列表。外部群集合由独立异步 worker 每 24 小时按安装同步，每次只请求一页并持久化游标。
- 客户群详情调用成功可以立即把单群标记为 `EXTERNAL`；调用失败不能据此标记为 `INTERNAL`。

## 数据与状态

`wecom_source_conversations` 保存当前名称解析投影：

- `group_kind`: `INTERNAL`、`EXTERNAL`、`UNKNOWN`，与 `display_name` 分离，禁止将 `group:<chatId>` 用作展示名；
- `name_resolution_status`: `PENDING`、`RESOLVED`、`RETRY_WAIT`、`UNAVAILABLE`；
- `last_name_checked_at`: 每次实际调用开始前更新，用于 24 小时自动冷却；
- `name_next_retry_at` 与 `name_error_code`: 临时失败的可观测退避状态。

`wecom_group_name_refresh_jobs` 是审计与异步执行 owner，保存群会话、触发来源（`MANUAL`/`TOPIC_UPDATED`）、请求员工、状态、尝试次数、可运行时间、租约、企业微信错误码、诊断及完成时间。同一群同一时刻最多一个 `PENDING`、`RETRY_WAIT` 或 `PROCESSING` 任务。

`wecom_external_group_syncs` 是每个企业安装的外部群集合同步状态，持久化本轮 `sync_id`、分页游标、页数、租约、重试时间和错误码。`wecom_external_group_sync_items` 暂存本轮已确认的客户群 ID，并以 `(sync_id, chat_id)` 去重。worker 每次最多消费一页、每页最多 1000 个群，整轮最多 1000 页；超过上界按失败记录，不提交群类型。

只有读取到最后一页后才在同一事务语义中提交投影：本轮命中的已观测群写 `EXTERNAL`，仍为 `UNKNOWN` 且本轮未命中的群写 `INTERNAL`。已有 `EXTERNAL` 不因后续列表权限收窄或历史群退出列表而降级成内部群；失败或未完成的同步不改变任何 `group_kind`。

客户群接口成功响应中的非空 `group_chat.name` 写入 `display_name`、标记 `EXTERNAL` 与 `RESOLVED`。成功但没有名称、明确不存在或无权限标记 `UNAVAILABLE`。网络、超时、限流、5xx 和临时令牌问题标记 `RETRY_WAIT`，以有界指数退避重试。

## 流程与权限

`POST /api/v1/wecom/groups/{sourceConversationId}/name-refresh` 先复用 `ConversationMapper.findAccessibleWeComGroup` 验证当前账号访问该群，并确认该群所属安装与当前账号的企业微信绑定一致；验证通过后返回 `202 Accepted` 和任务投影。

群 Topic 生成任务完成后，`AiTopicGenerationWorker` 调用投递服务。服务仅在 owner 为 `WECOM_GROUP` 且 `last_name_checked_at` 早于 24 小时前时投递任务。数据库唯一约束负责并发去重，Topic worker 不调用企业微信。

独立 worker 领取任务后解析群会话的安装和 `group:<chatId>` 键，调用网关，写回投影和任务终态。终态完成后发布一次仅包含群 ID 的 `wecom-group-name-refresh-completed` SSE 事件。前端只在该事件对应当前群时失效群详情查询；不轮询，也不乐观改标题。

外部群集合 scheduler 仅为活跃安装创建到期任务。worker 使用安装实例 token 调用 `externalcontact/groupchat/list`，持久化每页结果和下一游标。新观测到的 `UNKNOWN` 群最迟在下一轮完整同步后得到类型；Topic 和 HTTP 请求只读取最终投影。

## 验收

- 手动请求未等待企业微信且访问权不足被拒绝。
- 24 小时内 Topic 更新不重复投递，满 24 小时投递一次。
- 成功名称更新群会话并发布一次完成事件。
- 明确不可获取停止自动重试；临时异常走有界退避。
- 外部群列表中断、越界或权限失败时不产生内部群判定；完整分页后才把未命中的 `UNKNOWN` 群标记为 `INTERNAL`。
- 内部群在外围始终显示“内部群聊”，进入会话后固定群名头部可见且不会随消息滚动消失。
- 群头部按钮显示提交中/已提交，事件完成后只重取当前群详情。

## 配置与验证记录

外部群集合 worker 默认每 60 秒检查一次到期安装，可通过
`WECOM_EXTERNAL_GROUP_SYNC_POLL_INTERVAL_SECONDS` 调整。每个安装完成一轮后冷却 24 小时；新出现且尚未被任何同步尝试覆盖的 `UNKNOWN` 群会提前触发一轮。

2026-09-01 本地验证：

- 后端群同步、群展示 SQL、客户群响应解析专项测试通过；
- 前端全量 49 个测试文件、242 项测试通过，生产构建通过；
- Spring Boot 可执行 Jar 构建通过，并确认 Jar 包含 `V37`、同步 mapper、worker、scheduler 与事务处理器；
- 后端全量运行 916 项，0 个断言失败、10 个 Testcontainers 环境错误；沙箱外复跑确认 Docker Desktop 当前未提供可用 Docker daemon，因此 PostgreSQL 实迁移集成测试和完整应用实启仍未覆盖。
