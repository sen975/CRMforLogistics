# 企业微信本地审计日志治理设计

**日期：** 2026-08-13

**状态：** 已实施，全量验收完成

**适用运行面：** `demo/message-center-demo`

## 1. 背景与问题

本地 demo 当前把企业微信 viewer 审计和服务商授权审计分别追加到以下 JSONL 文件：

- `data/wecom-viewer-audit.jsonl`
- `data/wecom-authorization-audit.jsonl`

`WeComViewerAuditTrail` 和 `WeComAuthorizationAuditTrail` 都采用“当前文件大小加新事件大小超过上限就抛出异常”的实现。默认上限各为 1 MiB，达到上限后不会轮转、压缩或清理，因此审计写入会永久失败，最终影响 viewer 或使授权动作发生但审计缺失。

授权链路还存在独立的一致性缺口：`WeComAuthorizationService` 当前会先修改授权安装状态，再写审计，并吞掉审计异常。单纯替换文件写入器不能解决这一缺口，授权关键动作必须同时改为可对账的预写协议。

## 2. 目标与非目标

### 2.1 目标

- 当前审计文件达到大小上限或跨 UTC 日期时自动轮转。
- 历史文件使用 gzip 压缩，当前文件保持可直接 `tail` 的 JSONL。
- 每条审计流只保留最近 7 个 UTC 自然日，并受独立总容量预算约束。
- 在磁盘不足、压缩失败、并发写入、进程崩溃和遗留临时文件等情况下保持可诊断、可恢复且不静默丢失。
- viewer 普通观测事件审计失败时业务可继续，但必须产生限频结构化告警。
- 授权安装、撤销和凭据变化等关键动作必须先留下可持久化的审计意图，才允许修改状态。
- 提供只读 `audit-status` CLI，供本地和服务器运维检查健康状态。
- 保持现有事件字段白名单、长度限制和敏感信息禁写约束。

### 2.2 非目标

本设计不治理以下数据或运行面：

- PostgreSQL 中的 `audit_logs`。
- `wecom-messages.jsonl`、Email、ChatApp 等业务数据。
- 附件、同步游标和电话录音。
- Java stdout/stderr 的文件轮转。
- Docker 容器日志。
- HTTP 状态接口或管理页面。
- 将本地审计迁入 PostgreSQL。
- 支持两个独立 JVM 同时写入同一审计流。

## 3. 方案选择

### 3.1 日志存储治理

选择应用内共享轮转器 `BoundedAuditFile`。

未选择 Linux `logrotate`，因为本地、Windows 和容器运行方式不同，外部轮转无法统一关键动作的写入故障语义和崩溃恢复。未选择数据库审计，因为它会扩大当前 demo 的运行依赖，并超出本阶段边界。

`BoundedAuditFile` 是审计文件生命周期的唯一 owner；两个 AuditTrail 只负责事件合同、字段校验和脱敏，不再各自实现大小检查、文件追加、轮转或清理。

### 3.2 授权状态与审计一致性

选择“审计预写 + 可恢复对账”协议。

未选择跨两个文件模拟事务或失败后回写旧 snapshot，因为进程在任意文件操作之间崩溃时仍无法证明两边一致，补偿反而可能覆盖更晚的官方回调。未选择数据库事务，因为授权安装状态和授权审计同时迁库超出已确认范围。

文件存储无法提供授权状态文件与审计文件之间的严格原子提交。本设计保证的是：没有持久化预写审计就不执行关键动作；如果状态已修改但最终审计失败，现场可被明确识别，并能通过官方回调重放完成对账。若未来要求任意崩溃点都严格原子，必须将两者迁入同一个数据库事务，不能继续叠加文件补偿逻辑。

## 4. 组件边界

```text
WeComViewerAuditTrail -------------------- best effort
          \
           -> BoundedAuditFile -> current .jsonl
          /                    -> archive .jsonl.gz
WeComAuthorizationAuditTrail ------------ required
          |
          -> AuthorizationAuditIndex -> attempt 分配与开放尝试索引
          -> WeComAuthorizationService 预写与结果对账

AuditStatusReporter --------------------- 只读扫描与 JSON 状态
```

### 4.1 `BoundedAuditFile`

职责：

- 规范化审计文件绝对路径，并通过进程级 registry 共享锁、文件通道、`FileLock` 和引用计数。
- 获取并持有该流的跨进程文件锁。
- 原子追加完整 UTF-8 JSONL 行。
- 判断 UTC 日期轮转和大小轮转。
- 生成 gzip 归档、执行保留期清理和容量检查。
- 恢复自身产生的 `.rotating` 和 gzip 临时文件。
- 向调用者返回结构化存储失败，不决定业务是否继续。

它不负责构造业务事件、判断 viewer 权限、修改授权安装状态或吞掉错误。

### 4.2 两个 AuditTrail

`WeComViewerAuditTrail` 和 `WeComAuthorizationAuditTrail` 继续拥有各自事件 schema、字段白名单、长度限制及敏感字段禁写规则。它们把一条完整事件编码后交给 `BoundedAuditFile`。

viewer AuditTrail 采用 best-effort 策略；授权 AuditTrail 提供 required 写入并把失败交回 `WeComAuthorizationService`。字段校验失败与文件 I/O 失败都必须进入同一结构化故障报告，不能静默忽略。

`AuthorizationAuditIndex` 由授权 AuditTrail 独占管理。启动时从有上界的当前文件和合法归档建立索引，之后每次成功追加同步更新内存状态。下一 `attempt` 的分配与对应 `accepted` 追加必须在同一个授权流写锁内完成，避免并发重复编号。CLI 使用独立只读快照，不共享或修改 writer 的内存索引。

### 4.3 `AuditStatusReporter`

只读扫描两个配置的审计流，统计文件、容量、保留期、临时文件和授权未闭合事件。它不获取 writer 独占锁，不触发轮转、压缩、恢复或删除，因此可以在 web JVM 正常运行时由另一个 CLI JVM 调用。

## 5. 配置合同

保留两个流的当前文件路径配置，并新增统一治理配置：

```env
WECOM_VIEWER_AUDIT_FILE=data/wecom-viewer-audit.jsonl
WECOM_AUTHORIZATION_AUDIT_FILE=data/wecom-authorization-audit.jsonl

AUDIT_RETENTION_DAYS=7
AUDIT_FILE_MAX_BYTES=1048576
AUDIT_STREAM_MAX_BYTES=8388608
AUDIT_MIN_FREE_DISK_BYTES=67108864
AUDIT_WARNING_INTERVAL_SECONDS=3600
```

默认预算为：

- 当前单文件最多 1 MiB。
- 单条审计流包含当前文件、归档和遗留恢复文件在内最多 8 MiB。
- 两条流正常总预算约 16 MiB。
- 保留当前 UTC 日期及之前 6 个 UTC 日期，共 7 个自然日。
- 可用磁盘低于 64 MiB 时拒绝新的 required 写入，best-effort 写入降级。
- 同一审计流、同一错误码每小时最多输出一次重复告警。

配置必须为十进制正整数，并满足：

- `AUDIT_RETENTION_DAYS` 范围为 1 至 365。
- `AUDIT_FILE_MAX_BYTES` 范围为 4096 字节至 20 MiB。
- `AUDIT_STREAM_MAX_BYTES` 不小于 `AUDIT_FILE_MAX_BYTES`，且不超过 1 GiB。
- `AUDIT_MIN_FREE_DISK_BYTES` 不小于 `AUDIT_FILE_MAX_BYTES`，且不超过 1 TiB。
- `AUDIT_WARNING_INTERVAL_SECONDS` 范围为 60 至 86400 秒。
- 两个审计流规范化后的绝对路径不得相同，文件名必须以 `.jsonl` 结尾。

以下旧配置被删除，不保留别名或回退路径：

```env
WECOM_VIEWER_AUDIT_MAX_BYTES
WECOM_AUTHORIZATION_AUDIT_MAX_BYTES
```

`.env` 或进程环境中只要出现任一旧键，即使值为空，`Config.load()` 也必须以明确错误列出旧键并启动失败。所有命令共享这项验证，避免 web 与 CLI 使用不同配置解释。

## 6. 文件命名与轮转

当前文件名保持不变，历史文件使用 UTC 日期和当日递增序号：

```text
wecom-viewer-audit.jsonl
wecom-viewer-audit.2026-08-13.001.jsonl.gz
wecom-viewer-audit.2026-08-13.002.jsonl.gz
wecom-viewer-audit.2026-08-12.001.jsonl.gz
```

授权审计使用相同规则和自己的文件名前缀。序号按同一流、同一 UTC 日期下已有合法归档的最大序号加一生成，禁止覆盖已有归档。

写入一条事件时按以下顺序执行：

1. AuditTrail 完成字段校验、脱敏和单行 UTF-8 编码。
2. 拒绝大于 `AUDIT_FILE_MAX_BYTES` 的单条编码事件，避免无限轮转。
3. 取得该绝对路径对应的进程内锁，并确认 writer 仍持有跨进程锁。
4. 恢复本流遗留的合法 `.rotating` 或 gzip 临时文件；非法或损坏文件保持原样并报告。
5. 清理早于 7 日窗口的合法 gzip 归档。
6. 检查可用磁盘、当前流实际占用和本次写入的最坏预算。
7. 当前文件非空且所属 UTC 日期已变化，或追加后会超过 1 MiB 时，执行同步轮转。
8. 循环写完完整事件行；best-effort 写入在返回前执行 `FileChannel.force(false)`，required 写入执行 `FileChannel.force(true)`。

当前文件的所属日期取第一条合法事件的 `occurredAt` UTC 日期；空文件属于本次写入日期。启动后不得仅依赖进程内日期或文件 mtime 判断，避免重启和人工复制改变轮转语义。当前文件出现无法解析的完整行时停止写入并报告 `failed`，不得在损坏现场继续追加。

轮转在同一目录内完成：当前文件先以原子移动改名为带 UTC 日期和序号的唯一 `.rotating` 文件，再生成同名 gzip 临时文件，写入后强制落盘，校验 gzip 可读取后原子发布为最终 `.jsonl.gz`，最后删除已归档的 `.rotating`。只有最终归档建立成功后才创建新的当前文件。底层文件系统不支持同目录原子移动时，writer 启动失败；不使用复制后删除的弱化路径。

如果升级时发现现有当前文件已经大于新的 `AUDIT_FILE_MAX_BYTES`，只允许先将其作为一个超限历史文件完成轮转；不得拆分或改写其中事件。该归档仍计入 8 MiB 流预算，预算不足时 required 写入失败、best-effort 降级。

升级前授权审计行没有 `eventId` 和 `attempt`。这些字段缺失但符合旧字段白名单的行视为合法 legacy 证据，不参与开放尝试索引，也不被改写。授权 writer 接受第一条新协议事件前先轮转包含 legacy 行的非空当前文件，使新的当前文件只包含本协议事件。除此以外不保留旧写入路径。

压缩同步执行并受 1 MiB 当前文件上界约束。这样调用者只有在归档真正完成后才看到写入成功，避免后台压缩失败形成不可见缺口。

## 7. 保留期、流预算与磁盘保护

在 UTC 日期 `2026-08-13`，合法保留窗口是 `2026-08-07` 至 `2026-08-13`；日期不晚于 `2026-08-06` 的合法 gzip 归档可以清理。

清理规则：

- 只自动删除名称合法、gzip 校验成功且已经超过保留期的归档。
- 7 日窗口内的归档不得为了腾出空间而删除。
- `.rotating`、gzip 临时文件、损坏文件和以本流前缀开头的非法命名文件不得被保留策略删除。
- 当前文件、合法归档、恢复文件、损坏文件和以本流前缀开头的未知文件都计入流预算；锁文件不计入。

每次轮转或写入前先清理已超期的合法归档，然后重新计算实际占用。如果预计写入仍会超过 `AUDIT_STREAM_MAX_BYTES`，则拒绝本次写入。可用磁盘低于 `AUDIT_MIN_FREE_DISK_BYTES` 时同样先清理超期归档并复查；仍不足则拒绝写入。任何情况下都不得删除保留期内的审计证据。

压缩期间的 `.rotating` 与 gzip 临时文件会短时共存。gzip 临时文件不得超过源文件字节数加 64 KiB，轮转前必须把这一峰值计入流预算；超过即中止发布并保留 `.rotating` 供恢复。读取 gzip 时每个归档的解压上限为 `AUDIT_STREAM_MAX_BYTES`，避免损坏归档或压缩炸弹造成无界内存和磁盘使用。

## 8. 并发与进程模型

同一 JVM 内，所有指向同一规范化绝对路径的 `BoundedAuditFile` 实例必须从 registry 取得同一个共享 handle。handle 同时拥有进程内锁、文件通道、跨进程锁和引用计数，确保多个 viewer service 或 sync service 不会重复申请重叠 `FileLock`、交错写行或竞争轮转。最后一个引用关闭时才释放通道和跨进程锁。

writer 启动时还必须在同目录获取并持有一个伴随锁文件的独占 `FileLock`，直到服务关闭。第二个 writer JVM 无法获取锁时必须启动失败，错误中给出审计流名称和文件路径，但不输出事件内容。只读 `audit-status` 不申请该锁。

本阶段不支持网络文件系统、多主机共享目录或多个容器共享写入同一文件。需要这些能力时必须改用数据库或专用日志接收器。

## 9. 崩溃恢复

writer 启动或首次写入时检查本流产生的恢复文件：

- 合法 `.rotating` 且最终归档不存在：重新压缩并校验后发布。
- 合法 gzip 临时文件且对应 `.rotating` 仍存在：校验成功则发布，失败则保留两者并报告。
- 最终归档已经存在且内容可读：不得覆盖；对重复 `.rotating` 报告人工检查。
- 文件名非法、gzip 损坏或 JSONL 当前文件损坏：保持现场，不自动删除或拼接，状态至少为 `degraded`；当前文件损坏或导致预算无法写入时状态为 `failed`。

恢复过程本身仍受进程内锁、跨进程锁和原子移动约束。恢复失败对 required 授权写入是阻断，对 viewer 写入是降级。

## 10. 授权关键动作预写协议

所有 `wecom.authorization.*` 回调都采用 required 审计。每个官方回调生成稳定 `eventId`：对回调类型、suite ID、企业 ID、官方时间戳以及回调携带票据或 auth code 的摘要做带长度分隔的 SHA-256。日志只保存最终 `eventId`，不保存原始票据、auth code、suite ticket、permanent code 或可独立复用的凭据摘要。

一次官方回调允许因队列、上游或本地故障产生多次处理尝试。稳定 `eventId` 标识官方回调，正整数 `attempt` 标识本地尝试；同一次尝试的阶段使用 `result` 关联：

```json
{"eventId":"sha256:<hex>","attempt":1,"action":"wecom.authorization.cancel_auth","result":"accepted"}
{"eventId":"sha256:<hex>","attempt":1,"action":"wecom.authorization.cancel_auth","result":"pending"}
{"eventId":"sha256:<hex>","attempt":1,"action":"wecom.authorization.cancel_auth","result":"succeeded"}
```

流程如下：

1. 回调入口从审计索引计算下一 `attempt`，在入队或同步处理前 required 写入 `result=accepted`。
2. `accepted` 写入失败时不入队、不修改 Store，并向企业微信返回可重试结果。
3. `create_auth`、`change_auth`、`reset_permanent_code` 仍使用有界串行 worker，避免在官方回调请求内等待多次上游 API；成功写入 `accepted` 且入队后按现有快速应答模型确认接收。
4. worker 取得上游结果并确定具体 Store 变更后，在修改 Store 前 required 写入 `result=pending`。`pending` 只包含白名单身份、目标状态和预期版本，不包含 permanent code、密文或请求响应正文。
5. `WeComAuthorizationStore` 在同一次原子 snapshot 替换中写入业务状态、`lastAuthorizationEventId` 和 `lastAuthorizationEventAt`。同一 `eventId` 再次执行时返回现有结果，不递增版本；时间早于已应用事件的回调不得覆盖较新状态。
6. Store 修改成功后 required 写入同一 `(eventId, attempt)` 的 `result=succeeded`；动作执行失败则写入 `result=failed` 和白名单错误字段。
7. `cancel_auth` 在同步撤销前同样写入 `accepted` 和 `pending`，Store 撤销与事件标记原子提交。`suite_ticket` 不修改 Store，但在更新内存票据前写入 `accepted` 和 `pending`，更新后写入 `succeeded`。
8. 队列已满时对本次 attempt 追加 `failed` 并返回重试；若 `failed` 也无法写入，保留 `accepted` 作为未闭合证据并进入 required 故障闸门。

Store 的两个新增元数据字段是授权幂等 owner，不是新的审计正文。已有安装记录缺少这两个字段时读取为空；下一次合法 Store 原子替换写入新 schema。这是保护现有授权安装数据所需的单向 schema 演进，不保留旧写入合同。

required 最终结果写入失败时不得伪装回滚已经完成的 Store 修改。服务进入授权故障闸门：后续不同 `eventId` 不得修改 Store；每次授权回调开始时先尝试有界对账。若日志存在 `pending` 且 Store 的 `lastAuthorizationEventId` 相同，可以确定 Store 修改已提交并补写 `succeeded`；如果只有 `accepted`，按协议可以确定尚未修改 Store，在服务重启恢复时补写 `failed`，错误码为 `WECOM_AUTHORIZATION_PROCESS_INTERRUPTED`。`suite_ticket` 的 pending 在进程重启后只能补写 `failed`，因为内存票据已经丢失。

授权服务启动顺序固定为：取得 writer handle、只读建立保留窗口及现存超期归档的审计索引、执行对账、确认没有无法证明的开放尝试，然后才允许 `BoundedAuditFile` 清理超期归档或接受新授权事件。这样即使未闭合事件已经超过 7 日，也会先得到最终记录或进入故障闸门，不会先删除对账证据。

如果 `pending` 与 Store 标记冲突、Store 不可读或存在无法证明的状态，系统不得猜测成功或自动回滚，保持故障闸门并报告人工处理。官方回调重放产生同一 `eventId`：已有任意 attempt 成功时直接幂等确认；只有失败 attempt 时创建下一 attempt 并重新处理。原回调已经快速应答后发生的 worker 故障无法追溯修改 HTTP 响应，因此恢复依赖本地 Store 标记、启动对账及后续官方重放，而不是虚构“已经要求原请求重试”。

`create_auth` 和 `reset_permanent_code` 的 auth code 可能被上游按一次性凭据处理。如果进程在成功兑换 auth code 之后、写入 `pending` 之前崩溃，本地可证明 Store 尚未修改并记录失败，但不能保证同一 auth code 能再次兑换。此时需要企业微信重新投递可用回调或重新发起授权；本设计不把不可恢复的上游一次性凭据写入磁盘来换取自动重放。

现有“先改 Store，再调用吞异常的 `recordAudit()`”路径和仅存在于内存的成功去重语义必须删除，不能与新协议并存。审计索引扫描当前文件和保留期内合法归档，最大扫描量受 8 MiB 单流预算约束。

同一 `(eventId, attempt)` 未找到 `succeeded` 或 `failed` 时称为开放尝试。开放尝试在 60 秒处理宽限期内只计入状态明细，不降低整体健康状态；超过 60 秒后为未闭合尝试，整体状态至少为 `degraded`。CLI 只读报告，不执行补写或 Store 对账；实际恢复由授权服务启动和回调入口完成。

## 11. Viewer 降级与告警

viewer 打开、登录交换、会话创建、显示刷新、同步诊断和组件错误属于 best-effort 审计。审计失败不得改变原本的 HTTP 结果或阻断正常页面操作。

失败时向 stderr 输出单行结构化 JSON，至少包含：

```json
{
  "event": "wecom.audit.write",
  "stream": "wecom-viewer",
  "status": "degraded",
  "errorCode": "AUDIT_STREAM_BUDGET_EXCEEDED"
}
```

告警不得包含用户 ID、联系人 ID、session ID、事件正文、路径中的敏感查询参数或任何凭据。同一流和同一 `errorCode` 在 `AUDIT_WARNING_INTERVAL_SECONDS` 内最多输出一次。发生过失败后第一次成功写入时输出一次 `status=recovered`，随后清除该失败状态；不同错误码分别限频和恢复。

授权 required 写入失败使用相同无敏感字段的告警格式，但 `status=failed`，并由业务调用者执行重试或阻断语义。

## 12. `audit-status` CLI 合同

新增命令：

```bash
java -cp "message-center.jar:lib/*" \
  com.crmforlogistics.messagecenter.App audit-status
```

命令只接受 `audit-status`，不接受额外参数；stdout 只输出一个 JSON 对象，合同结构为：

```json
{
  "status": "healthy",
  "checkedAt": "2026-08-13T08:00:00Z",
  "streams": [
    {
      "name": "wecom-viewer",
      "currentFile": "/absolute/path/data/wecom-viewer-audit.jsonl",
      "currentBytes": 123,
      "archiveCount": 4,
      "archiveBytes": 456,
      "recoveryFileCount": 0,
      "unknownFileCount": 0,
      "retentionDays": 7,
      "streamMaxBytes": 8388608,
      "remainingBudgetBytes": 8388039,
      "openAttemptCount": 0,
      "staleOpenAttemptCount": 0,
      "oldestStaleOpenAttemptAt": null
    }
  ],
  "issues": []
}
```

viewer 流的三个 attempt 字段固定为 0、0 和 `null`，因为只有授权流使用预写协议。CLI 不输出审计正文、事件 ID、用户 ID、企业 ID、会话 ID 或任何事件字段值。

`remainingBudgetBytes` 按 `streamMaxBytes` 减去当前文件、归档、恢复文件和未知文件的实际总字节数计算，最小为 0。出现问题时，`issues` 元素只允许 `severity`、`stream` 和稳定 `code`，例如 `{"severity":"degraded","stream":"wecom-authorization","code":"AUDIT_OPEN_ATTEMPT_STALE"}`；不携带自由文本、异常消息、事件内容或文件内容。同一 `(stream, code)` 只输出一次，并按严重级别、流名、错误码稳定排序。

为避免 CLI 与 writer 并发时把正在追加的最后一行误判为损坏，扫描当前文件前后都读取文件大小和最后修改时间；发生变化时最多重试三次。三次仍无法取得稳定快照时报告 `AUDIT_SCAN_BUSY` 并将状态置为 `degraded`，不把现场判为损坏，也不阻塞 writer。

退出码：

- `0`：`healthy`，配置、当前文件、归档、容量和磁盘检查均正常，无超过 60 秒的未闭合授权尝试。
- `2`：`degraded`，存在未闭合授权事件、可恢复临时文件、损坏或非法归档等需要处理但当前仍可读取的情况。
- `3`：`failed`，配置非法、当前文件损坏或不可读、目录不可写、预算已耗尽、磁盘低于阈值等会阻止下一次 required 写入的情况。

若多个问题并存，整体状态和退出码取最严重级别。配置无法建立两个 stream 时仍要输出不含敏感值的失败 JSON，不打印 Java 堆栈到 stdout。

## 13. 运维合同

查看当前审计：

```bash
tail -f data/wecom-viewer-audit.jsonl
jq -c . data/wecom-authorization-audit.jsonl | tail
```

查看历史归档：

```bash
gzip -cd data/wecom-viewer-audit.2026-08-12.001.jsonl.gz | jq -c .
```

检查状态：

```bash
java -cp "message-center.jar:lib/*" \
  com.crmforlogistics.messagecenter.App audit-status
```

运维人员不得通过截断当前文件解决容量问题，也不得删除 7 日窗口内归档。损坏或未知文件应先复制到受控备份位置并完成调查，再由运维显式移走；应用不会替运维删除证据。

## 14. 安全与隐私

任何当前文件、归档、CLI 输出或 stderr 告警都不得记录：

- access token、suite secret、corp secret、permanent code、suite ticket。
- auth code、`secretKey`、私钥、viewerAuthToken。
- 完整企业微信请求体、响应体、消息正文或 OpenDataFrame URL。

AuditTrail 继续使用字段白名单，不接受任意 Map 透传。错误只保存既有允许字段，如受限格式的 `errorCode`、`upstreamErrcode`、`upstreamPath`、HTTP 状态和 hint。归档只是当前 JSONL 的压缩形式，不增加新字段。

## 15. 测试与验收

实施必须使用可注入 `Clock`、磁盘空间探针和临时目录完成确定性测试，至少覆盖：

1. 当前文件追加未越界时不轮转，完整行不交错。
2. 追加将超过 1 MiB 时先轮转，再写入新当前文件。
3. UTC 日期变化触发轮转，同一日序号单调递增且不覆盖。
4. gzip 解压后的每一行、顺序和原文件完全一致。
5. 7 日边界精确清理，窗口内归档绝不因容量压力被删除。
6. 当前、归档、恢复和未知文件共同受 8 MiB 预算约束。
7. 64 MiB 磁盘阈值前后的 required 与 best-effort 行为。
8. 多线程并发追加不丢行、不拆行、不重复归档。
9. 第二个 writer JVM 或模拟文件锁无法启动，CLI 仍可只读检查。
10. 在原子改名、gzip 写入、归档发布和新当前文件创建等故障点恢复 `.rotating`。
11. gzip 临时文件峰值和解压上限生效；损坏 gzip、非法命名和损坏当前 JSONL 被保留并正确分级。
12. viewer 写入失败不改变原业务结果，告警按流和错误码限频，恢复只报告一次。
13. 授权 `accepted` 失败时不入队、不修改 Store；`pending` 失败时不修改 Store；成功或失败均关联同一 `(eventId, attempt)`。
14. 并发回调的 attempt 原子分配，legacy 授权行在升级后保留且不进入开放尝试索引。
15. Store 的事件标记与状态原子替换；最终授权审计失败后故障闸门阻断不同事件，启动对账和官方重放能够闭合，且同一事件不重复递增 Store 版本。
16. 授权启动对账先于超期归档清理，未闭合证据不会在对账前消失。
17. 两个旧环境变量只要出现就导致配置失败，不发生静默回退。
18. `audit-status` JSON、稳定快照重试、60 秒宽限、issue 去重排序、字段脱敏、整体状态和退出码符合合同，执行过程不修改文件。
19. 对当前文件、gzip 解压内容、CLI stdout 和 stderr fixture 执行敏感字段回归扫描。
20. 更新后的 Maven 测试、打包和 demo CLI 实际执行通过，无 warning、临时文件或生成物漂移。

## 16. 实施触达范围

实施计划预计只触达本地 demo 的以下边界：

- 新增共享审计文件 owner、状态扫描器及其单元测试。
- 修改两个 AuditTrail 接入共享 owner。
- 修改 `WeComAuthorizationService` 的预写、重放、恢复闸门和失败语义，并为 Store 增加持久化幂等标记。
- 修改 `Config` 和配置测试，删除两个旧配置读取入口。
- 修改 `App` 增加 `audit-status` 命令和退出码。
- 更新 `config.example.env` 与 `README.md` 的配置、启动和运维说明。

不得借此修改 Spring 版数据库审计、消息同步、企业微信展示组件、业务日志框架或部署平台日志策略。

## 17. 完成定义

只有在以下条件同时满足时，本任务才算完成：

- 两条审计流不会因达到当前文件上限而永久停写。
- 7 日保留、gzip 压缩、单流预算和磁盘阈值均有自动化证据。
- viewer 审计故障不会中断业务且不会告警风暴。
- 授权关键动作不存在“未预写审计就修改状态”的路径。
- 授权最终审计缺失可由 CLI 发现，并能通过启动对账或官方回调重放闭合；上游一次性 auth code 已失效的边界被明确报告而非伪造恢复。
- `audit-status` 可在 web 服务运行时只读执行，输出不泄露审计正文。
- 旧配置被明确拒绝，文档与实际配置合同一致。
- 仅本设计范围内文件进入提交，工作区其他未提交修改保持原样。

## Task 8 实施证据

- RED：首次运行 `cd demo/message-center-demo && mvn -q -Dtest=AuditSensitiveFieldRegressionTest test` 以缺少 `produceAndCollectAllAuditSurfaces()` helper 编译失败，退出码 1。
- GREEN：补齐真实 viewer/authorization/current/gzip/warning/status/stdout/stderr 采集后，运行同一命令通过；独立审查后又加入授权最终审计写入失败时 `WeComAuthorizationService` 的独立 stderr 路径，异常携带敏感 fixture 而输出只保留稳定错误码。非静默运行结果为 `Tests run: 1, Failures: 0, Errors: 0`，`BUILD SUCCESS`。
- 配置与文档门禁：README/config 的旧键搜索无命中；五个 `AUDIT_*` 键及 Java/PowerShell `audit-status` 搜索均有命中。
- brief 给出的 `README.md config.example.env src/main src/test` 旧键全仓搜索仍命中既有 `Config` 旧键拒绝逻辑和回归断言；该逻辑属于批准合同且不在 Task 8 允许修改范围，未删除。
- `git diff --check` 针对 tracked 配置、README 和规格退出 0；新增回归测试的 `git diff --no-index --check` 没有 whitespace 诊断（因文件存在差异按 no-index 语义返回 1）；本轮未执行 stage/commit。

## Task 9 全量验收证据

- 审计专项命令覆盖 10 个测试类，共 `128` 项测试，`0` failures、`0` errors；其中 `BoundedAuditFileTest` 的 `32` 项测试包含跨进程 writer 锁、轮转、gzip、恢复、预算和磁盘阈值合同。
- `mvn -q test` 在默认沙箱首次因本地 HTTP 测试服务器无法 bind 产生 `52` 个 `SocketException: Operation not permitted`；允许本地端口后原命令重跑为 `435` 项测试全部通过，`0` failures、`0` errors。
- 首次 `mvn -q verify` 发现 `WeComAuthorizationServiceTest` 在 Store 版本更新后、异步公钥注册触发前立即断言的竞态。失败用例可单独稳定复现；测试改为等待其实际断言的注册副作用后，单用例连续运行 `20` 次无失败，授权 service 测试 `16/16` 通过。随后 `mvn -q verify` 通过，Surefire `435/435`，Failsafe 完成 `24` 项集成测试且 `0` failures、`0` errors。
- `mvn -q -DskipTests package` 通过，生成 `demo/message-center-demo/target/message-center-demo-0.1.0.jar`，大小约 `898 KiB`；用 ZIP 目录验证 `App`、`AuditStatusReporter` 和 `BoundedAuditFile` class 已进入 JAR。
- 隔离临时目录 CLI 实测：空审计目录返回 `healthy`/exit `0`；写入超过 60 秒的合法开放授权 attempt 返回 `degraded`/exit `2` 和 `AUDIT_OPEN_ATTEMPT_STALE`；仅设置旧 max-bytes 键返回 `failed`/exit `3` 和 `AUDIT_CONFIGURATION_INVALID`。三种场景 stderr 均为空，0/2 场景执行前后审计文件列表、大小和 mtime 不变。
- `AuditSensitiveFieldRegressionTest` 扫描 current JSONL、gzip 解压内容、viewer warning、授权 final 写失败 stderr 和 status stdout，`1/1` 通过；README/config 的两个旧键零命中。
- 任务范围 `git diff --check` 通过；嵌套仓仅 `target/phone-call-transcription-runtime`，未暂存、未提交。Docker/Testcontainers 在 verify 中可用，没有未运行的外部依赖门禁。
