# WhatsApp 模板默认启用与状态对账设计

**状态：** 已确认，待实施

**日期：** 2026-08-15

## 1. 问题与证据

当前账号 `8613266259485` 有 3 个本地模板：2 个状态为 `APPROVED`，1 个状态为 `REJECTED`。
后台同步后两个已审核模板的 `allow_send` 都是 `false`。

`WhatsAppTemplateReconciliationService` 每次同步都会使用 CAMS 详情中的 `AllowSend` 覆盖本地
`message_templates.allow_send`。因此只在页面点击开关或只把数据库改成 `true` 都不能形成持久
规则；下一次同步仍会恢复 CAMS 返回的实际状态。

## 2. 已确认行为

- 新发现或现有的 `APPROVED` 模板默认期望启用。
- `PENDING`、`REJECTED` 和已删除模板不调用 CAMS 启用。
- 用户手动关闭模板后，重启和定时同步都保持关闭。
- 用户再次手动开启后，系统恢复自动对账，直到 CAMS 实际状态为开启。
- 页面不能把“期望开启”冒充为“CAMS 已开启”。

## 3. 状态模型

`message_templates.allow_send` 继续表示 CAMS 最近一次确认的实际发送权限，不改变现有发送门禁。
新增持久化字段：

- `desired_allow_send boolean not null default true`：用户或默认策略期望的状态。
- `permission_sync_status varchar(20) not null default 'IDLE'`：`IDLE`、`PENDING`、`FAILED`。
- `permission_sync_attempt_count integer not null default 0`：连续失败次数。
- `permission_sync_next_attempt_at timestamptz`：下一次允许重试时间。
- `permission_sync_error_code varchar(100)`：最近一次结构化错误码。
- `permission_sync_error_message text`：经过清理的最近一次错误说明。

约束：失败次数为非负数；非 `FAILED` 状态不保留错误字段；删除模板不参与对账。

数据库迁移时，历史模板统一写入 `desired_allow_send=true`。审核状态不是 `APPROVED` 的模板虽然
保留默认期望，但在其未来通过审核前不触发 provider 写操作。这样模板通过审核后会自动启用，
同时不对被拒绝模板发起无效请求。

## 4. 唯一 owner

`WhatsAppTemplateApplicationService` 继续拥有用户启停命令和实际发送资格。
`WhatsAppTemplatePermissionReconciliationService` 作为自动权限对账 owner，负责选择待处理模板、
调用现有 `WhatsAppTemplateGateway.setSendPermission`、更新实际状态及退避信息。

`ChatAppTemplateSyncService` 和 scheduler 只触发同步/对账；Controller、前端和 Mapper 不实现
状态机。

## 5. 用户操作

用户切换模板开关时按以下顺序处理：

1. 校验模板属于当前账号且未删除。
2. 开启请求只允许 `APPROVED` 模板；关闭请求允许当前未删除模板。
3. 先持久化 `desired_allow_send` 和 `permission_sync_status=PENDING`。
4. 同步调用 CAMS `setSendPermission`。
5. 成功后使用响应中的权限更新 `allow_send`，并清空失败计数、下次重试时间和错误字段。
6. 明确失败时保留期望状态，写入 `FAILED` 和错误；HTTP 返回失败，不把实际状态改成期望值。
7. 提交结果未知时沿用现有模板 operation 对账合同，同时保留 `PENDING`。

用户手动关闭会把 `desired_allow_send=false`。因此后续重启不会再次开启该模板。

## 6. 启动和定时对账

模板只读同步完成后及既有定时任务运行时，选择满足以下条件的模板：

- 未删除；
- `status=APPROVED`；
- `desired_allow_send != allow_send`；
- `permission_sync_next_attempt_at` 为空或不晚于当前时间。

每个账号单轮最多处理 20 个模板，同一模板同一时刻只允许一个 worker 持有。调用 CAMS 成功后
更新实际状态；失败后递增连续失败次数并使用退避：1 分钟、5 分钟、15 分钟、1 小时、6 小时，
之后固定为 24 小时。单轮失败不阻断其他模板。

用户再次切换开关会把失败次数清零并立即进行一次同步调用。provider 只读同步如果已经返回与
期望一致的状态，也会清除旧失败状态。

## 7. 发送门禁与页面投影

模板发送资格仍要求：

- 审核状态为 `APPROVED`；
- `allow_send=true`；
- 未删除且属于当前 ChatApp 账号。

`desired_allow_send=true` 但 `allow_send=false` 的模板不能发送，避免本地声称可用而 CAMS 实际
拒绝。

模板管理响应增加 `desiredAllowSend`、`permissionSyncStatus`、`permissionSyncError`。前端开关
表达用户期望状态，并同时展示实际结果：

- 期望与实际一致：正常开启或关闭。
- `PENDING`：开关禁用并显示同步中。
- `FAILED`：保留期望位置，显示失败标记和 provider 错误，可再次切换或重试。
- 审核未通过：开关禁用，不能显示为已启用。

销售发送选择器仍只消费实际可发送模板，不读取期望状态。

## 8. 错误、幂等与观测

- 自动对账使用模板 ID、目标状态和期望状态版本生成幂等键，避免同一状态重复创建操作记录。
- CAMS 返回明确拒绝时记录结构化错误，不把本地实际状态改写为成功。
- 网络中断或响应不确定时进入现有 `SUBMISSION_UNKNOWN` 对账，不重复盲目提交。
- 日志记录账号 ID、模板 ID、目标状态、尝试次数、错误码和 provider request ID，不输出凭据或
  完整请求载荷。
- 对账结果提供扫描数、成功数、跳过数、失败数和待重试数。

## 9. 测试与验收

后端测试覆盖：

- 数据迁移后历史模板默认 `desired_allow_send=true`。
- `APPROVED + desired=true + actual=false` 会调用 CAMS 开启。
- `REJECTED/PENDING/DELETED` 不调用 CAMS。
- 用户手动关闭后重启/同步不重新开启。
- 用户手动开启成功后实际状态立即更新，不读取可能滞后的详情覆盖写结果。
- provider 失败保留实际状态并按规定退避。
- provider 只读同步返回期望状态后清除失败信息。
- 发送选择器拒绝仅有期望状态、实际仍关闭的模板。

前端测试覆盖开关正常、同步中、失败、审核未通过四种状态。浏览器验收包括：重启后两个已审核
模板自动变为 CAMS 实际开启；手动关闭其中一个后再次重启仍保持关闭；被拒绝模板始终不可开启。
