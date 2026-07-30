# 企业微信官方每日会话摘要设计

## 1. 目标

在不把企业微信原始会话正文保存到消息中心数据库的前提下，使用企业微信数据与智能专区提供的官方“会话摘要模型”，为每个员工与每个外部联系人的前一自然日会话生成一条可持久化摘要。

首期固定口径：

- 时区为 `Asia/Shanghai`。
- 每天 `00:05` 处理前一自然日。
- 员工 A 与外部联系人 B 每天最多一条最终摘要记录。
- 员工 A 与外部联系人 C 每天最多一条最终摘要记录。
- 只处理员工与外部联系人的单聊；群聊、员工内部会话和外部联系人之间的会话不进入首期。
- PostgreSQL 只保存摘要、覆盖范围和任务状态，不保存原始正文、语音转写或附件内容。

## 2. 官方依据

本设计以下列企业微信官方文档为准：

- 数据与智能专区接入指引：`https://developer.work.weixin.qq.com/document/path/100008`
- 应用同步调用专区程序：`https://developer.work.weixin.qq.com/document/path/100020`
- 获取会话记录：`https://developer.work.weixin.qq.com/document/path/100023`
- 会话摘要模型：`https://developer.work.weixin.qq.com/document/path/99980`
- 会话展示组件：`https://developer.work.weixin.qq.com/document/path/100049`
- 镜像文件配置指引：`https://developer.work.weixin.qq.com/document/path/100009`
- 专区程序 SDK 和示例：`https://developer.work.weixin.qq.com/document/path/100250`

已确认的官方约束：

- 代开发应用可以调用官方会话摘要模型。
- 摘要任务使用 `create_summary_task` 创建，使用 `get_summary_result` 查询结果。
- 每次任务最多传入 1000 条消息。
- 输入总长度受大约 30000 个 UTF-8 字符限制。
- 每企业每天摘要调用不超过 10000 次。
- 模型任务是异步任务；资源紧张时可能排队，超过 24 小时仍无资源才失败。
- 应用调用专区程序时同时传入 `program_id` 和 `ability_id`，因此一个应用关联的同一个专区程序可以通过不同固定能力承担不同职责。

## 3. 已否定路线

本阶段明确不采用以下路线：

- 不把 Qwen、llama.cpp 或其他模型权重打进专区镜像。
- 不创建第二个专区程序或第二个镜像。
- 不让摘要能力复用 `conversation_viewer_sync` 的输入输出合同。
- 不把消息正文拉到 8107、浏览器、PostgreSQL、日志或审计中；现有 viewer 为了渲染组件而保存的最小 `secret_key` 引用 JSONL 保持不变，摘要链路不得复制或扩大该数据。
- 不生成 Topic、客户画像、下一步建议或销售阶段判断。
- 不修改公开 OpenAPI，不新增用户可调用的摘要 HTTP 接口。
- 不把数据库变成会话展示 viewer 的运行前提。

## 4. 单程序双能力

当前专区镜像从“只允许一个能力”调整为“只允许两个编译期固定能力”：

| 能力 ID | 唯一职责 | 允许调用的专区 SDK |
| --- | --- | --- |
| `conversation_viewer_sync` | 获取会话展示所需的最小消息引用 | `sync_msg` |
| `conversation_daily_summary` | 创建和查询官方会话摘要任务 | `create_summary_task`、`get_summary_result` |

程序不得从环境变量读取任意 ability ID，也不得接受任意 SDK 接口名。入口根据企业微信回调中的 `ability_id` 精确分派到对应 handler；不在固定白名单中的能力失败关闭。

两个能力共享企业微信官方 Java SDK、加密 HTTP server、线程池和 `/app/start`，但不共享输入合同、输出合同或业务状态。

## 5. Owner 边界

| 真相 | 唯一 owner | 禁止成为 owner 的位置 |
| --- | --- | --- |
| 会话索引分页与最小字段投影 | `conversation_viewer_sync` 专区能力 | 摘要能力、前端、数据库 migration |
| 官方摘要任务创建与结果查询 | `conversation_daily_summary` 专区能力 | 8107 HTTP controller、前端 |
| 每日分组口径与北京时间窗口 | 8107 每日摘要服务 | 专区 handler、浏览器 |
| 任务幂等、租约、重试和最终摘要持久化 | PostgreSQL repository | JSONL、内存 map、审计日志 |
| 原始正文读取与模型分析 | 企业微信专区和官方摘要模型 | 8107、PostgreSQL、浏览器 |
| 会话展示原文渲染 | 企业微信 `ww-open-message` | 摘要表、Vue 状态、普通 DOM |

## 6. 总体数据流

```text
北京时间每天 00:05
  -> 8107 为授权企业同步最新会话引用
  -> 从最小消息引用中选择前一自然日的员工-外部联系人单聊
  -> 按 authCorpId + employeeUserId + externalUserId + summaryDate 分组
  -> 每组按 send_time、msgid 稳定排序并拆成有界批次
  -> 8107 调用同一个专区程序的 conversation_daily_summary 能力
  -> 专区程序调用 create_summary_task
  -> 8107 保存 jobid 和待查询状态
  -> 后台 worker 有界轮询 conversation_daily_summary
  -> 专区程序调用 get_summary_result
  -> 完成后只返回官方摘要文本
  -> 8107 将同一分组的批次摘要按顺序合并为一条数据库记录
```

8107 不接收模型读取到的原始正文。专区程序也不把正文写入本地文件或日志。

## 7. 摘要能力合同

### 7.1 输入协议

后台为 `conversation_daily_summary` 配置一个带操作类型的固定协议：

```json
{
  "operation": "submit 或 poll",
  "jobid": "poll 时必填",
  "msg_list": [
    {
      "msgid": "submit 时必填",
      "secret_key": "submit 时必填"
    }
  ]
}
```

规则：

- `operation=submit` 时，`msg_list` 必填，数量为 1 到 1000，`jobid` 不得出现。
- `operation=poll` 时，`jobid` 必填，`msg_list` 不得出现。
- 拒绝未知字段、空字符串、重复 `msgid`、超长字段和超过上限的请求。
- `secret_key` 只在当前 SDK 调用内使用，不记录、不回显、不写文件。

### 7.2 输出协议

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "status": 0,
  "jobid": "JOBID",
  "summary": "仅完成时返回"
}
```

`status` 与官方任务状态保持一致：

- `0`：任务未完成或刚提交。
- `1`：任务完成，必须返回非空 `summary`。
- `2`：任务失败，不返回 `summary`。

专区程序只投影 `errcode`、`errmsg`、`status`、`jobid` 和完成后的摘要文本。不得把官方完整响应、消息列表、密钥或失败消息明细带出专区。

单次输入 JSON 和 SDK 响应各自最多 1 MiB，最终摘要最多 64 KiB UTF-8；超过上限失败关闭，不截断后冒充完整摘要。

## 8. 消息分组

分组只依赖 `conversation_viewer_sync` 已返回的元数据，不读取正文：

- `sender.type=1` 表示员工，`sender.type=2` 表示外部联系人。
- `receiver_list` 中寻找会话另一方。
- 只接受恰好能解析为一个员工和一个外部联系人的消息。
- `chatid` 非空的消息视为群聊，首期跳过。
- `send_time` 必须落入前一日 `[00:00, 24:00)` 的北京时间窗口。
- 同一消息只属于一个员工-外部联系人分组。
- 无法唯一确定双方、字段越界或类型未知时跳过并记录脱敏计数，不猜测身份。

数据库最终唯一键：

```text
(suite_id, auth_corp_id, employee_user_id, external_user_id, summary_date)
```

同一日期同一双方无论调度、重试或进程重启多少次，最多产生一条最终摘要记录。

## 9. 超限拆批

官方模型无法在提交前告诉调用方原文字符总数，因此采用失败驱动的有界拆批：

1. 每个分组先按最多 1000 条消息切批。
2. 若官方返回输入过长错误 `790040`，对当前批次按消息顺序二分后重新提交。
3. 最小批次为 1 条；单条消息仍因输入过长失败时，该分组标记为部分失败，不伪造摘要。
4. 每个分组最多产生 32 个批次，超过时停止并标记 `failed_limit`，防止无界任务增长。
5. 多个成功批次的摘要按原消息时间顺序合并到同一条最终记录，批次之间使用明确分隔，不把多个数据库行伪装成每日多条摘要。

最终记录必须保存：输入消息总数、成功覆盖消息数、批次数、最早和最晚消息时间、是否完整覆盖。用户看到摘要时可以判断是否存在截断或失败，不能把部分结果标记为完整。

## 10. PostgreSQL 持久化

新增 Flyway migration，至少包含两个 owner 表：

### 10.1 `wecom_daily_summary_jobs`

用于任务编排和重启恢复，保存：

- 授权企业、员工、外部联系人和摘要日期。
- 批次序号和消息引用集合的不可逆摘要值。
- 企业微信 `jobid`。
- `pending`、`submitted`、`polling`、`completed`、`retry_wait`、`failed` 状态。
- 有界重试次数、下次执行时间、租约 owner、租约截止时间。
- 结构化错误码和脱敏错误说明。

任务表不得保存 `secret_key`、消息正文或官方完整响应。

### 10.2 `wecom_daily_summaries`

用于最终业务持久化，保存：

- 唯一分组键。
- 摘要文本。
- 输入消息数、成功覆盖数和批次数。
- 最早/最晚消息时间。
- `complete`、`partial`、`failed` 覆盖状态。
- 官方模型类型标识 `wecom_summary`。
- 创建、更新时间和版本号。

最终表不保存 `jobid`、密钥、消息 ID 列表或正文。

## 11. 调度与恢复

- 配置显式开启后才启动每日摘要 worker；默认关闭，不改变现有 demo 启动行为。
- 调度时区固定为 `Asia/Shanghai`，不能依赖服务器默认时区。
- 每天 `00:05` 创建前一日任务。
- 首次启用不自动追溯超过前一日的数据，避免意外扩大调用量。
- PostgreSQL 唯一键负责幂等，租约负责多实例互斥。
- 进程重启后恢复 `submitted`、`polling` 和 `retry_wait` 任务。
- 查询未完成任务采用有上限的退避，不持续占用请求线程。
- 单任务从首次提交起最多等待 24 小时；超过后标记失败，等待人工重跑。
- 单企业提交和查询并发均设上限，不能用无界线程池追赶积压。

## 12. 配置

新增配置建议：

```text
WECOM_DAILY_SUMMARY_ENABLED=false
WECOM_DAILY_SUMMARY_ABILITY_ID=conversation_daily_summary
WECOM_DAILY_SUMMARY_HOUR=0
WECOM_DAILY_SUMMARY_MINUTE=5
WECOM_DAILY_SUMMARY_MAX_BATCHES_PER_PAIR=32
WECOM_DAILY_SUMMARY_MAX_TRANSIENT_FAILURES=20
WECOM_DAILY_SUMMARY_POLL_INITIAL_SECONDS=30
WECOM_DAILY_SUMMARY_POLL_MAX_SECONDS=900
WECOM_DAILY_SUMMARY_MAX_WAIT_HOURS=24
```

`program_id` 继续复用现有 `WECOM_CHATDATA_PROGRAM_ID`，因为 viewer 和摘要属于同一个应用关联程序。摘要功能启用时必须有 PostgreSQL；关闭时 viewer 仍可继续使用本地最小消息引用文件。

## 13. 错误与审计

错误必须区分：

- 专区程序未配置或能力未关联。
- 官方任务创建失败。
- 官方任务仍在排队。
- 输入过长触发拆批。
- 官方任务失败或超过 24 小时。
- 数据库租约、写入或唯一键竞争。
- 消息分组字段不合法。

日志和审计只允许包含：企业安装记录标识、摘要日期、分组的不可逆摘要、消息数量、批次数、任务状态、错误码和 traceId。禁止包含正文、摘要全文、`secret_key`、`jobid`、access token、permanent code 或完整用户 ID。

数据库持久化失败时失败关闭：不得把已经得到的摘要仅保存在内存或普通日志后宣称完成。

## 14. 测试与验收

### 14.1 专区程序

- 两个固定 ability ID 可正确分派，其他 ID 全部拒绝。
- viewer 能力只能调用 `sync_msg`。
- summary submit 只能调用 `create_summary_task`。
- summary poll 只能调用 `get_summary_result`。
- 输入字段、数量、大小、重复 ID 和未知字段失败关闭。
- SDK 非零返回、响应超限、状态非法、完成时摘要为空均失败关闭。
- 测试和日志中不出现 `secret_key`。

### 14.2 8107

- 北京时间跨日边界正确，服务器处于其他时区也不改变窗口。
- A-B 与 A-C 分别生成一条记录，双向消息进入同一分组。
- 群聊和身份不明确消息被跳过。
- 同一日重复调度、进程重启和多实例竞争不产生重复摘要。
- 1000 条边界、`790040` 二分、32 批上限和部分失败状态正确。
- pending 任务可恢复，24 小时后停止重试。
- 数据库中不存在正文、消息 ID 列表或密钥。
- 摘要功能关闭时 viewer 回归通过，且不要求数据库新增运行依赖。

### 14.3 真实验收

真实服务器验收必须使用代开发授权企业：

1. 同一员工分别与两个外部联系人产生前一日会话。
2. viewer 仍可展示双方原始消息。
3. `00:05` 后观察摘要任务创建、轮询和完成。
4. PostgreSQL 中 A-B、A-C 各只有一条摘要。
5. 检查数据库、8107 日志和专区程序日志，确认没有原始正文或密钥。

本机只能使用模拟 SDK 和模拟时钟验证代码，不把模拟摘要当成真实模型验收证据。

## 15. 完成边界

本地完成只能声称：单程序双能力镜像、8107 调度与持久化、测试、文档和可复现构建通过。

真实功能完成还依赖以下企业微信外部状态：

- 同一专区程序中已创建并审核通过两个固定能力。
- 应用已关联该程序和官方会话摘要模型。
- 授权企业已授权数据与智能专区和会话内容。
- RSA 公钥、专区 SDK 和会话存档工作正常。
- 8107 已配置 PostgreSQL 并执行新增 migration。
