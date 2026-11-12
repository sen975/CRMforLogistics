# AI Topic 企业微信混合归属与静默重构设计

## 1. 目标

将企业微信数据与智能专区的单条官方摘要纳入 AI Topic，同时保留 ChatApp、邮件和电话 Topic。Topic 按归属范围生成并保存：个人沟通生成个人 Topic，企业微信群沟通只生成一份群 Topic。联系人右侧时间轴可以直接引用其参与的群 Topic，但不复制 Topic、摘要或来源。

Topic 重构由后台异步完成。每个个人联系人或企业微信群在最新事件后连续 6 分钟没有新事件时，执行一次静默重构。企业微信摘要如果在原消息静默截止时间之后才完成，则摘要完成后立即触发重构。

## 2. 产品边界

- 个人 Topic 输入：ChatApp 原消息、邮件原消息、电话转录/备注、企业微信一对一会话的已完成单条官方摘要。
- 群 Topic 输入：该群会话的已完成单条官方摘要。
- 企业微信 `PENDING`、`SUBMITTED`、`RETRY_WAIT`、`FAILED` 摘要任务不进入 Topic。
- 企业微信群消息不会进入群成员个人 Topic；个人消息也不会与群 Topic 做关联。
- 8107 不保存企业微信消息正文，只使用已保存的官方摘要和必要元数据。
- Topic 核心模型只有一个；不建立个人 Topic 表与群 Topic 表两套并行业务逻辑。
- Topic 的产品动作使用“入库”，移除旧的弃用产品语义。合并产生的历史状态仍使用 `ARCHIVED`；历史弃用状态统一迁移为 `STORED`。

## 3. Topic 归属模型

`ai_topics` 增加显式归属范围：

| owner_type | owner_id | 语义 |
| --- | --- | --- |
| `CONTACT` | `contacts.id` | 个人 Topic |
| `WECOM_GROUP` | `wecom_source_conversations.id` | 企业微信群 Topic |

现有个人 Topic 回填为 `CONTACT`。群 Topic 的 `owner_id` 指向群的 `source_conversation_id`，不指向任何成员联系人。

生成任务、输入指纹、租约和并发唯一性都以 `(owner_type, owner_id)` 为边界。同一归属范围同一时刻最多一个未完成重构任务。

### 3.1 来源模型

`ai_topic_items` 支持以下互斥来源：

- 普通消息 `message_id`，渠道为 `chatapp` 或 `email`；
- 电话记录 `call_record_id`，渠道为 `phone`；
- 企业微信摘要 `wecom_message_summary_job_id`，渠道为 `wecom`。

来源必须恰好存在一种。企业微信摘要通过摘要任务 UUID 稳定关联，不能只使用 `msgid` 字符串。企业微信来源的事件时间取摘要任务的原始 `send_time`。

普通消息和电话记录的部分唯一索引继续保证一个来源最多归入一个 Topic；企业微信摘要任务增加同等唯一约束。

## 4. 输入与混合时间轴

### 4.1 个人 Topic 输入

个人输入 adapter 查询当前联系人可访问且尚未出现在任何 `ai_topic_items` 的：

1. ChatApp 消息；
2. 邮件消息；
3. 电话转录或人工备注；
4. 与联系人绑定的一对一企业微信源会话中，状态为 `COMPLETED` 且 `summary` 非空的 `wecom_message_summary_jobs`。

企业微信群源会话明确排除。个人 Topic 的候选 Topic 只来自同一个 `CONTACT` owner 且状态为 `READY` 的 Topic。

### 4.2 群 Topic 输入

群输入 adapter 查询指定 `source_conversation_id` 下尚未关联的企业微信摘要任务，仅接收 `COMPLETED` 且 `summary` 非空的任务。群 Topic 的候选 Topic 只来自同一个 `WECOM_GROUP` owner 且状态为 `READY` 的 Topic。

### 4.3 联系人页面投影

联系人右侧时间轴返回：

- 该联系人的 `CONTACT` Topic；
- 该联系人参与的企业微信群对应的 `WECOM_GROUP` Topic。

群 Topic 只返回同一条 Topic 投影，并增加所属群名称、群身份和 `isReferencedGroupTopic` 标记。不得创建联系人副本或复制 `ai_topic_items`。

Topic 仓库同样保留原始 owner：个人 Topic 展示联系人备注；无备注时展示具体渠道和渠道昵称；群 Topic 展示群名称，缺失时展示稳定的群会话标识。

## 5. Topic 状态与入库审批

`ai_topics.status` 语义如下：

| 状态 | 活跃时间轴 | AI 候选 | Topic 仓库 | 含义 |
| --- | --- | --- | --- | --- |
| `READY` | 展示 | 可参与 | 不展示 | 活跃 Topic |
| `STORED` | 不展示 | 不参与 | 展示 | 已入库 Topic |
| `ARCHIVED` | 不展示 | 不参与 | 不展示 | 被合并后的历史 Topic |

个人 Topic 的入库操作由有权限的员工提交，创建异步操作任务；任务完成后才从 `READY` 变为 `STORED`。

群 Topic 的入库必须经过审批：

1. 员工提交入库申请，创建唯一的待审批申请；Topic 仍为 `READY`，继续展示和参与群消息归类。
2. 管理员批准后创建异步入库任务。
3. 入库任务完成后 Topic 变为 `STORED`，同时从群页面和所有相关联系人页面的活跃时间轴消失。
4. 管理员驳回则申请结束，Topic 保持 `READY`。

个人页面引用的群 Topic 为只读投影；编辑、合并、申请入库和审批都在群 Topic owner 的页面执行。

恢复将 `STORED` 变为 `READY`，但历史来源保持已归类，不能重新进入 AI 输入。群 Topic 恢复仅允许管理员执行。

## 6. 六分钟静默调度

每个 `(owner_type, owner_id)` 维护一条静默调度状态，至少包含：最新事件时间、静默截止时间、版本水位、调度状态、关联生成任务、租约和重试字段。

### 6.1 活动记录

支持渠道消息入库、电话转录/备注完成、企业微信摘要任务进入 `COMPLETED` 时，调用统一的 owner activity service，在同一事务或可靠的事务后事件中更新静默状态。活动记录只推进 owner 的事件水位，不直接修改 Topic 快照。

事件时间规则：

```text
quiet_deadline = max(当前 quiet_deadline, occurred_at + 6 分钟)
latest_event_at = max(当前 latest_event_at, occurred_at)
```

这样迟到到达的历史消息不会把已存在的更晚截止时间向前回拨。

### 6.2 worker 执行

静默 worker 周期性领取到期 owner：

1. 以租约和乐观版本领取一条到期状态；
2. 重新读取 owner 的最新事件水位；
3. 若发现新事件或截止时间未到，释放本轮并等待；
4. 否则创建一次 `INITIAL` 或 `INCREMENTAL` Topic 生成任务；
5. 生成任务只读取该 owner 尚未归类的来源和同 owner 的 `READY` 候选；
6. 任务完成或最终失败后释放静默状态并发布一次快照完成事件。

企业微信摘要完成时：

- 若 `now >= send_time + 6 分钟`，立即让对应 owner 进入可执行队列；
- 若尚未到达截止时间，调度到 `send_time + 6 分钟`；
- 不因摘要完成重新等待完整 6 分钟。

### 6.3 去重与并发

同一 owner 同一输入指纹只能存在一个等价生成任务。任务执行时再次检查来源唯一约束和 owner 版本。并发冲突只提交实际成功关联的来源，不创建空 Topic。

### 6.4 新联系方式接入与联系人合并

联系方式绑定或联系人合并成功后，必须触发一次目标 `CONTACT` owner 的增量重算。该事件不是单条消息事件；它代表新联系方式的历史消息已经进入目标联系人范围。

合并事务先迁移联系方式归属，并将来源联系人的 `READY` Topic 原位转移到目标联系人；不归档、不复制来源，也不等待 AI 重算才能显示：

1. Topic 的 ID、版本、人工编辑记录、操作审计和原有 `ai_topic_items` 关联保持不变，仅更新联系人 owner 到目标联系人。
2. 目标联系人已有 `READY` Topic 与转移过来的 Topic 同时保留，避免合并后历史 Topic 从活动时间轴消失。
3. 合并完成后仍记录目标联系人 owner 活动，供后续新消息触发增量 AI 重算；重算只处理尚未归类来源，不会删除或隐藏已转移 Topic。
4. 已经处于 `ARCHIVED`/`STORED` 的来源 Topic 不因合并被自动恢复，继续保留在 Topic 仓库中，确保生命周期和审计语义不变。
5. 群 Topic 不迁移到个人 Topic；其他联系人的 Topic 不得作为候选。重复的合并事件按 owner 更新和来源唯一约束幂等处理。

合并和增量重算必须在同一联系人 owner 边界内执行。群 Topic 不迁移到个人 Topic；其他联系人的 Topic 不得作为候选。重复的合并事件按来源唯一约束和 owner 活动版本幂等处理。

## 7. 关联度与 AI 合同

第一版使用模型语义关联度，不引入 embedding 或向量数据库。模型输入包含：新来源的渠道、主题/正文或官方摘要、事件时间，以及同 owner `READY` Topic 的标题、概要和时间范围。

模型必须将每个输入来源恰好分配一次：

- 复用 Topic：返回候选 Topic 的 UUID 和 `relevance`；
- 新建 Topic：返回本批临时 key、标题、概要和来源集合。

后端只接受以下复用结果：候选 UUID 有效、属于当前 owner、状态为 `READY`、且 `relevance >= AI_TOPIC_MATCH_THRESHOLD`。否则创建新 Topic。

解析和业务提交前强制校验：

- 来源 ID 必须来自本批；
- 每个来源不能重复，也不能遗漏；
- 渠道和来源类型必须匹配 owner；
- 标题、概要和来源集合不能为空；
- 单批记录数、字节数、标题/概要长度和 Topic 数量有上限。

校验失败时整批不写入 Topic，等待有界重试或人工重试。

## 8. 审批、生成和摘要审计

所有企业微信摘要、Topic 生成和 Topic 操作都保留可排障记录：

- 脱敏后的实际请求；
- provider 或官方能力原始响应；
- 解析后的结构化结果；
- validation stage、错误码、失败状态、尝试次数、耗时和时间戳；
- owner 类型、owner ID、输入指纹和任务 ID。

不得保存 API Key、access token、私钥、明文 `secret_key` 或企业微信消息正文。摘要任务和 Topic 审计记录不因原始消息保留策略自动删除。

审批和操作任务使用租约、最大尝试次数、退避、幂等键和乐观版本。AI/provider 失败不改变上一份已提交 Topic 快照。

## 9. API 与前端行为

### 9.1 API

- `GET /api/v1/contacts/{contactId}/topics`：返回个人 Topic 和可引用的群 Topic；只返回 `READY` 活跃快照及生成状态。
- 群 Topic 查询使用群会话 owner 的权限校验。
- `POST /api/v1/contacts/{contactId}/topics/retry` 与 `POST /api/v1/wecom/groups/{sourceConversationId}/topics/retry` 均将当前输入指纹对应的失败任务，或已完成但未生成 Topic 的空结果任务，重置为可领取的 `PENDING`；前端失败卡片必须提供重试按钮，并在请求期间禁用该按钮。
- 个人入库使用统一 Topic operation endpoint；群入库先创建审批申请，管理员批准后再执行入库任务。
- Topic 仓库分页检索同时支持联系人和群 owner，并返回原归属投影。
- 所有变更端点返回 `202 Accepted` 和任务投影，不等待 AI 或审批完成。

### 9.2 前端

- 不使用 Topic 轮询、持续流式更新或 optimistic update。
- Topic 时间轴整体可折叠；单个 Topic 默认折叠，收起状态仍显示标题、概要和事件时间。
- 展开后显示完整概要、时间范围和可折叠来源列表。
- 企业微信来源只显示 `wecom + 事件时间`。
- 群 Topic 在个人页面标注所属群且只读；操作入口定位到群 Topic 页面。
- 只有收到一次无敏感数据的 `topic-snapshot-completed` SSE 事件后，才重新读取最终快照。

## 10. 迁移与兼容边界

- 新迁移扩展 Topic 状态约束，增加 `STORED` 并将历史弃用状态转换为 `STORED`。
- 增加 owner 类型、owner ID、群来源关联和企业微信摘要来源字段及索引/唯一约束。
- 生成任务与操作任务增加 owner 维度；同联系人现有任务回填为 `CONTACT`。
- 产品 API、前端类型、按钮文案、错误码和文档统一使用“入库”，不保留旧弃用接口或状态双路径。
- 已部署迁移不重写；新增 Flyway 迁移只做增量约束和数据转换。

## 11. 测试与验收

### 后端

- 输入 adapter 正确区分个人一对一摘要与群摘要。
- 群摘要只产生一个 `WECOM_GROUP` Topic；联系人查询只引用，不复制。
- 未完成或失败摘要永不进入输入。
- 新消息连续 6 分钟静默后才触发；摘要迟到按原消息时间立即触发。
- 只使用同 owner 的 `READY` Topic 做关联；`STORED`、`ARCHIVED` 和其他 owner 永不参与。
- 重复、遗漏、未知来源和非法 Topic 输出整批拒绝且保存审计。
- 个人入库无需审批；群入库需管理员批准；批准完成后所有活跃时间轴一致消失。
- 恢复后历史来源不回流，后续新来源可关联。
- owner 级租约、幂等、乐观锁、重试和快照事件各自有专项测试。

### 前端

- 联系人页面显示个人 Topic 与引用的群 Topic，群 Topic 不重复生成。
- 群 Topic 页面支持申请入库，管理员支持批准/驳回；状态变化只在异步完成事件后刷新。
- 时间轴整体折叠、Topic 折叠、来源展开、事件时间和 `wecom + 时间` 展示正确。
- Topic 仓库跨联系人/群检索，保留原归属对象并支持恢复。
- 不存在 Topic 查询轮询或乐观列表删除；桌面和移动布局不溢出。

### 发布门禁

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest='*AiTopic*Test,*WeComMessageSummary*Test' test
mvn -q test
mvn -q -DskipTests package

cd ../frontend
npm run test -- --run
npm run build
```

发布前检查数据库迁移、后端 Jar、前端 `dist` 和实际服务器快照；未能运行的实机或 Docker 门禁必须单独记录，不得以构建通过替代。

## 12. 非目标

- 不修改企业微信官方摘要能力协议。
- 不把企业微信正文同步到 PostgreSQL 或前端。
- 不为群成员复制群 Topic、摘要或来源。
- 不在本轮引入向量检索、跨群自动合并、跨联系人 Topic 合并或自动回复。
