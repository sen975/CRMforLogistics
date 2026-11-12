# 联系人 AI 记忆首次历史回填设计

## 文档状态

提案。本文补充并仅替代《联系人 AI 标签、画像与增量记忆系统设计》中首次处理历史消息的边界；长期记忆模型、owner 隔离、标签分类、人工标签隔离、LLM 结构化合同和日常增量语义继续以原设计为准。

## 1. 问题

当前联系人记忆按 `last_success_cursor` 增量读取入站消息，每轮最多读取 50 条。没有游标时会从该联系人的入站历史最早处开始，但完成这一批后，游标会推进；后续轮次只读取游标之后的新消息，并依赖画像、观察、事实和 AI 标签延续上下文。

这带来三个风险：

1. 首轮历史多于一批时，剩余历史依赖补漏查询重新置脏；当前补漏仅比较时间戳，而读取游标是 `(received_at, message_id)`，相同时间戳跨批次时可能漏掉尾部消息。
2. 批次成功但模型没有产出画像时，游标仍可能推进。观察、事实或标签可以承接信息，但若均为空，原始消息不会自动再次进入后续模型上下文。
3. `CLEAN` 不能表达联系人是否完成首次历史回填，查询端无法区分“还没处理过历史”和“历史已完成、当前无待处理消息”。

目标不是每轮重读全部历史，而是让首次历史被有界、可观察地处理完，再继续现有增量流程。

## 2. 目标与非目标

### 目标

- 每个有 owner、且尚无有效 AI 画像的联系人，在画像首次建立前固定一个历史截止游标；既有成功游标不能代替“已有可用记忆”的证明。
- 以 keyset 分页处理截止游标之前的全部入站消息；单批的消息数、字符数、联系人批量数和 LLM 并发仍有上限。
- 只有一批结果已被持久化为记忆变更，或被明确记录为 `NO_SIGNAL`，才推进回填专用成功游标；普通增量游标在回填完成前保持原值。
- 处理失败不推进游标；重试同一输入边界。
- 用相同的 `(received_at, message_id)` 比较规则读取和补漏。
- 暴露 `NOT_STARTED / IN_PROGRESS / COMPLETE` 回填状态和待处理状态。
- 首次回填只运行一次；完成后不做每日全历史重算。

### 非目标

- 不逐条消息实时调用 LLM。
- 不删除或搬移原始消息，不用模拟数据替代历史输入。
- 不把一次性闲聊强制晋升为长期事实；长期事实继续遵守原设计的证据门槛。
- 不改变 AI/人工标签的数据域，不修改人工标签。
- 不把 Topic 或通话转录单独改成触发源；它们仍只是消息处理时可能使用的补充上下文。
- 不通过关闭 Flyway 校验或 `repair` 绕过当前本地数据库迁移漂移。

### rollout 前置风险

Gate 0 已在 PostgreSQL Testcontainers 复现失败：现有 `ContactMemoryContextService` 只按 `(received_at,message_id) > last_success_cursor` 取消息，而 `ContactMemoryTriggerService` 把事件置为已应用只代表状态行已置脏，不代表消息进入了记忆。因此迟到且排序落后的消息不会再次进入模型。修订方案复用已有、按消息唯一的 `contact_memory_trigger_events` 作为消息级记忆 inbox，增加独立 `memory_applied_at`；记忆批次成功时与观察/事实/标签/画像、attempt 和游标同事务标记 inbox 消息已纳入记忆。Gate 0 必须在该合同实现后转绿。

## 3. 推荐方案

采用“首次快照回填 + 消息级 durable inbox + 后续增量”的单一路线。首次历史目标是在状态行上记录一个固定的 `(received_at, message_id)` 上界。历史页只按回填专用成功游标读取该上界内连续、有序的 keyset page；另行补入 tuple 不晚于当前成功游标、但 `memory_applied_at IS NULL` 的迟到 inbox 消息，并按 message ID 去重。不能把游标之后的任意 inbox 行直接并入历史页，否则较新的 inbox 消息可能使游标越过尚未读取的中间历史消息。回填完成后，常规增量也只把 tuple 不晚于普通成功游标的未回执 inbox 消息作为迟到补漏；游标之后的消息由普通 tuple page 读取。每批先取最多 50 条连续 tuple page，再用剩余条数/字符预算补入迟到消息；补入项不改变连续页的输出游标。若连续页已用尽预算，迟到消息留在 inbox，由下一轮 `hasPendingInbound`/领取检查继续调度。

回填队列每 600 秒进行一次有界轮询，不受日常夜间增量窗口限制；每轮使用独立且较小的联系人批量上限，默认最多 5 个联系人，每个联系人至多处理一页。自动回填配置默认关闭。日常增量仍遵守现有时间窗口和批量上限。这样历史较长的联系人会跨多轮完成，不会启动时无界扫全库或一次性压垮 LLM。各实例仍依靠现有数据库租约与 fencing 保证同一联系人最多由一个有效 worker 提交。

启用前必须统计无有效画像的联系人数和入站历史页数，以当前模型/token 单价估算最坏初始费用及持续时间，并经部署负责人确认后显式开启。运行时由 600 秒轮询、每轮联系人上限、单页消息/字符/token 上限和串行模型调用限制速率；若部署方暂停回填，只停止领取新任务，不得丢弃已持久化游标或标成完成。每个实例的额度乘以活跃实例数，横向扩容前必须重新核算总并发和费用。

## 4. 状态与游标合同

### 4.1 首次回填状态

在 `contact_memory_states` 增加：

```text
history_backfill_status: NOT_STARTED | IN_PROGRESS | COMPLETE
history_backfill_cursor: nullable encoded (received_at, message_id)
history_backfill_target_cursor: nullable encoded (received_at, message_id)
```

在 `contact_memory_trigger_events` 增加：

```text
memory_applied_at: nullable timestamptz
```

该字段与既有 `status/applied_at` 语义分离：`applied_at` 只证明触发事件成功置脏；`memory_applied_at` 才证明此消息已被记忆 worker 成功处理或明确 `NO_SIGNAL`。

- `history_backfill_cursor` 是回填专用的成功边界，与普通增量 `last_success_cursor` 分开保存；每页记忆变更、attempt 结果和该回填游标必须同事务提交。
- 新联系人在首次入站时为 `NOT_STARTED`。
- 首次领取时，在 owner/contact 范围内读取最新入站 `(received_at, id)` 并原子固定为 target；没有入站消息则直接置 `COMPLETE`。
- 迁移时已有有效当前画像（`current_profile_version_id` 指向当前画像）的状态行设为 `COMPLETE`；没有有效当前画像的状态行设为 `NOT_STARTED`，即使其 `last_success_cursor` 非空，也要进行一次受限历史回填。
- 既有 `last_success_cursor` 保留，不清零、不伪造历史；无画像回填完成时，只在目标 tuple 大于原成功游标时把 `last_success_cursor` 推进到 target，不能回退。
- target 一经固定不得因新入站消息改写；只有回填专用成功游标到达或越过 target 才能完成回填。

### 4.2 游标比较

游标统一使用 `(received_at, message_id)` 字典序：

```text
(message.received_at, message.id) > (cursor.received_at, cursor.message_id)
```

历史页分页、target 截止、回填专用游标和增量游标必须使用相同 tuple 语义。迟到消息恢复不推进 tuple 游标：查询只补入 owner/contact scoped 且 `memory_applied_at IS NULL`、tuple 不晚于当前成功游标的 inbox message IDs，并按 message ID 去重。补漏存在性检查同时检查 tuple 新消息与未纳入记忆的迟到 inbox 行；禁止只比较时间戳或用 `MAX(received_at)` 推断无待处理消息。每批最多 50 条、总字符预算 50,000；迟到 inbox 只使用连续页剩余预算，不能挤掉连续页中的任何消息。若没有连续页但仍有迟到 inbox，成功处理这些 inbox 消息时游标保持不变，只提交记忆结果和对应回执。

首次回填每页从 `history_backfill_cursor` 之后读取，而非复用或重置 `last_success_cursor`。这样即使联系人过去已有成功但没有画像的批次，也能从历史起点完整补读，同时不破坏普通增量成功边界。历史页输出游标只取本次完整消费的连续 keyset page 最后一条；补入的迟到 inbox 行无论 tuple 新旧都不能改变该游标。回填完成时再将 target 与 `last_success_cursor` 取 tuple 最大值，避免已经回填的消息重新进入增量队列。target 后的消息在回填完成前不通过 inbox 提前推进回填游标，之后由普通增量读取。任何来源的入站消息即使 `received_at` 落在成功游标之前，只要 inbox 尚未标记 `memory_applied_at`，仍是候选输入。

target 到达后，状态切为 `COMPLETE`。回填期间 target 之后的新消息保留为增量待处理，不得被 target 推进吞掉。

## 5. LLM 批次结果与提交原子性

每批结果分类为：

- `MEMORY_UPDATED`：至少有一项有效观察、事实、AI 标签或画像变更；
- `NO_SIGNAL`：模型显式判定本批没有可沉淀的稳定记忆，并为本批入站消息提供合法的噪声/无信号证据引用；
- `FAILED`：模型调用、结构解析、owner 校验、证据校验、租约校验或持久化失败。

对 `MEMORY_UPDATED` 和 `NO_SIGNAL`，记忆写入、批次审计、本次实际作为 LLM 输入的 inbox 行 `memory_applied_at`、回填/增量状态及连续页成功游标在同一数据库事务内提交。成功只回执实际进入该批上下文的消息；没进入输入的 inbox 行保持未回执。`NO_SIGNAL` 不保存消息正文，只保存输入游标边界、输入条数、结果类别及已有受控审计信息；`memory_applied_at` 是消息处理回执，不保存额外正文。LLM 空画像本身不等于 `NO_SIGNAL`：只要本轮写入了事实或标签仍属于 `MEMORY_UPDATED`。若批次只含迟到 inbox，成功提交回执但不移动 tuple 游标。

`FAILED` 不推进游标，不覆盖上一版画像/标签/事实；释放租约并按当前有界重试策略记录失败。达到终态时状态与错误码对查询端可见。新增 owner-scoped `POST /api/contacts/{contactId}/memory/retry`，只有联系人 owner 可将终态失败重新置为可运行；回填 target 和成功游标不变，重试从失败批次边界继续。

## 6. 上下文与记忆规则

- 回填每批从回填专用成功游标之后继续；此前批次沉淀的画像、有效观察、长期事实和 AI 标签作为下一批稳定上下文。
- 每批继续受当前 `max-inbound-messages`、单消息截断和总字符预算约束；额外的 Topics、通话转录、人工标签仍为只读补充上下文。
- 同一消息不得因回填重试产生重复观察证据、标签证据或事实证据；写入保持幂等。Inbox 回执只在该消息作为批次输入且整批成功/合法无信号时提交，失败仍保持待处理。
- 观察过期规则、事实至少两条独立证据的晋升规则、画像 200 字限制、标签单轮上限和人工标签隔离沿用现有设计。
- 正常增量处理继续在普通 `last_success_cursor` 之后读取消息，并消费现存稳定记忆；回填完成时该游标只向前推进到至少 target，不重新扫描已完成回填的全部历史。

## 7. 数据展示与可观测性

联系人记忆查询在现有响应中增加 `historyBackfillStatus`，值为 `NOT_STARTED / IN_PROGRESS / COMPLETE`，并保留现有处理状态、最近尝试时间、失败码和 `hasPendingInbound`。不向前端暴露原始 target 游标或消息正文。回填失败时由联系人 owner 显式触发 retry 命令；读取状态不产生副作用。

前端明确显示回填中、已完成或失败/需重试状态。`CLEAN` 只表示当前没有可运行的记忆任务，不得再用它推断历史回填完成。

每次 attempt 记录 `MEMORY_UPDATED / NO_SIGNAL / FAILED`、输入条数、输入/输出游标、处理耗时、模型及现有变更计数。日志只记联系人/attempt 标识、阶段和结构化错误码，不记消息正文、提示词或密钥。

## 8. Owner、安全与并发

- 联系人 owner 唯一来自 `contacts.created_by`；回填 cursor/target、消息页、证据、状态更新必须使用同一个 owner/contact 范围。
- worker 使用现有租约 token 和 fencing；旧租约不得提交任何记忆写入或游标推进。
- retry 命令必须验证当前认证用户等于 `contacts.created_by`，只能重置该联系人的失败状态，不允许客户端传入 owner、target 或游标。
- 一个联系人同一时刻只有一个有效回填或增量租约。
- ChatApp、企业微信和邮件现有入站投影都必须原子 enqueue 对应 trigger event；新旧 status 与 `memory_applied_at` 分离，不可由 trigger replay 把记忆回执提前标记完成。状态补漏/调度必须检测 tuple 新消息和迟到未回执 inbox；即使 trigger event 已是 `APPLIED`，只要记忆回执为空仍须重新置脏或保持可领取。
- 批量任务有界；自动回填默认关闭；显式开启后每实例模型并发默认为 1，联系人领取上限默认 5，单联系人每轮最多一页 50 条；复用现有字符预算和 LLM 请求超时/响应大小上限。
- 回填阶段与日常增量使用互斥领取条件，避免同一联系人两个游标同时推进。

## 9. 迁移与兼容

使用新的前向 Flyway 迁移，不修改任何已经存在的迁移文件。当前工作区已有用户未提交的 V97/V98，版本选择以实施前重新审计后的下一个空闲号为准。当前用户数据库另存在 V84 缺失和 V89 checksum 漂移；在数据库历史完成独立核实/有记录修复前，不得对该数据库执行新迁移或把此功能声称为可本机启动验收。干净 PostgreSQL/Testcontainers 数据库用于验证新迁移链；已有数据库必须先通过只读 schema 对账和批准的迁移历史恢复步骤。

升级只初始化回填标记、回填专用游标和 inbox 记忆回执，不自动改写既有画像、标签、事实或消息，也不在迁移期调用 LLM。没有有效当前画像的联系人会在 worker 启动后进入一次性回填，包括过去已有成功游标但未形成画像的联系人；已有有效画像的联系人不回算。对已有画像联系人，迁移只把 tuple 不晚于已有成功游标的旧 trigger event 初始化为已纳入记忆；超出游标的 event 保持待处理。初始化回执时间取迁移时的 `now()`，表达“按旧成功游标推定已处理并从本合同起排除”，不得伪称为原始处理时间。该资格判断不能证明“旧画像信息完整”，它只针对已知无画像缺口，避免对全体联系人做历史重算。

event 回执与源消息共享生命周期：不单独定期删除 `memory_applied_at` 已完成的 event；源消息按现有数据保留策略删除时，由现有外键级联清除 event。当前未发现独立的消息保留期限，因此本次不新增 TTL 或清理 worker，以免削弱 message ID 的幂等窗口。上线前必须记录 event 行数/增速监控；如果后续定义源消息 TTL，应在同一数据保留合同中验证 event 清理与幂等要求。

## 10. 验收标准

1. 无有效画像的联系人（包含已有 `last_success_cursor` 的联系人）固定 target；历史消息按 `(received_at, id)` 分页，重复时间戳的批次尾部不丢失，既有成功游标不被清零。
2. 连续两批的输入不重叠，批间输出的记忆进入后批上下文；target 后的新消息留在增量队列。
3. 首批多于 50 条时多轮完成；完成前状态不是 `COMPLETE`，完成时普通成功游标不回退且不重复重放至 target，完成后不再做历史全量回算。
4. `NO_SIGNAL` 有独立审计且原子推进游标；画像为空但生成标签/事实时结果为 `MEMORY_UPDATED`。
5. LLM/解析/证据/数据库/租约失败不推进游标、不写入 inbox 回执、不覆盖上一版结果，失败可见且重试同一边界。
6. 同 owner/contact 隔离、租约 fencing、重复事件幂等和并发 worker 单赢家测试通过；迟到 inbox 只能占用连续 page 的剩余预算，不可导致 tuple 游标越过未输入的历史消息；仅对实际输入消息写回执。
7. API 与 UI 可区分历史回填进度、日常待处理、失败和完成。
8. 专项后端测试、迁移合同测试、前端测试、生产构建和 Testcontainers PostgreSQL 验收通过；实际报告 Skipped 数，不以跳过冒充通过。

## 11. 剩余限制与待关闭设计风险

LLM 对信息重要性的判断仍是模型判断，本机制无法证明模型永不漏掉语义；目标是让无有效画像联系人当前可见的入站历史被完整、分批地交给模型，合法无信号结果有记录，失败批次不被游标跳过。无画像联系人会有一次性的历史 LLM 成本，成本由联系人数量、历史页数及每页 token 预算共同决定；自动回填默认关闭，须先统计联系人/页数、估算费用并经部署负责人确认后启用，运行时仍受批次、字符和每实例并发限制。已有画像的联系人不会因此重算。模型召回质量仍需通过真实脱敏样例评估。

迟到入库且 `received_at` 落在已处理 tuple 之前的消息由 `memory_applied_at IS NULL` durable inbox 补偿；Gate 0 必须验证消息实际进入模型输入、成功原子写回执、失败保持待处理、重放幂等，并验证迟到消息不能让连续回填游标跳过历史行。event 当前随源消息生命周期保留，依赖 `messages` 外键级联清除；本项目尚无已确认的源消息 TTL，因此本次不引入独立清理期限或清理 worker。该取舍避免在没有替代去重账本时删除 message ID 回执；event 行数和增速必须可观测。模型召回质量及当前画像是否涵盖所有旧事实仍需使用真实脱敏样例评估。
