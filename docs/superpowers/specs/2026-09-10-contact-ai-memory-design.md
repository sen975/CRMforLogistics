# 联系人 AI 标签、画像与增量记忆系统设计

## 1. 文档定位

本文是联系人 AI 标签、联系人画像和 LLM 增量记忆处理的当前设计真源。

本文覆盖：

- 联系人级 AI 标签的生成、更新、失效和恢复；
- 联系人画像的首次生成和增量重写；
- 入站消息触发、夜间批处理、游标和租约；
- LLM 输入输出合同、证据和失败处理；
- 人工标签与 AI 标签的隔离；
- 后端模块、查询投影、数据库结构和验收边界。

本文不覆盖 WhatsApp 账号绑定、模板审批、Topic 生命周期本身或通话转写生成。Topic 和通话转写只作为联系人记忆的受限上下文来源。

## 2. 目标与产品边界

系统根据联系人已有消息、相关 Topic 和可靠的通话转写，逐步维护两类 AI 产物：

1. 面向销售阅读的联系人画像，最多 200 个汉字；
2. 具有分类、证据和生命周期的 AI 标签。

系统只沉淀跨多次交流仍有业务价值的稳定特征，不把每次闲聊、一次性情绪或临时上下文变成联系人标签。

本期采用夜间增量处理：

- 客户入站消息成功入库后只标记联系人待更新；
- 每天 `00:00` 后由 worker 批量处理；
- 当天没有新入站消息的联系人不调用 LLM；
- 出站消息、Topic 更新和通话转写不会单独触发 LLM；
- 它们可以在有入站消息的夜间批处理中作为补充上下文；
- 不采用逐消息实时 LLM 调用；
- 不采用每日全量重算；
- 不让 AI 修改、删除或失效人工标签；
- 不设置 AI 标签总数量上限，限制来自证据门槛、单轮变更上限和上下文预算。

## 3. 领域归属与唯一 Owner

### 3.1 业务 Owner

新增 `contactmemory` 模块作为联系人记忆业务语义的唯一 owner。Controller、前端、scheduler、消息接入链和 LLM gateway 只能调用该模块提供的结构化合同，不得自行判断标签稳定性、是否触发处理或如何推进游标。

### 3.2 联系人数据归属

联系人记忆按联系人当前业务 owner 隔离：

```text
owner_user_id = contacts.created_by
```

每个联系人、每个 owner 只有一份联系人记忆状态。worker 使用系统身份执行任务，但不能改变业务 owner。

没有有效 `contacts.created_by` 的联系人：

- 不创建自动处理状态；
- 不进入 LLM 队列；
- 查询时返回结构化的 owner 缺失状态；
- 不通过猜测或最近操作人补全 owner。

联系人转移、合并或拆分时，记忆数据必须遵守现有联系人 owner 迁移规则。记忆迁移不是本设计的独立入口；涉及联系人身份变化时，由联系人领域服务在同一事务或明确的迁移合同中调用 `contactmemory` owner 能力。

### 3.3 人工标签隔离

人工标签继续由现有 `contact_tags` 和 `contact_taggings` 管理。AI 记忆服务不得向人工标签表写入 AI 数据，不得通过增加来源字段把两种写路径重新混合。

查询层可以统一返回人工标签和 AI 标签，但每条标签必须带有明确来源：

```text
MANUAL | AI
```

人工标签的创建、修改、删除仍由现有人工标签服务负责。AI 永远不能修改、删除、失效或恢复人工标签。

## 4. 数据模型

所有新表使用项目现有数据库迁移和命名约定。下列字段是业务合同的最小集合，具体公共审计字段沿用项目既有规范。

### 4.1 `contact_memory_states`

每个联系人和 owner 一行，记录增量处理状态：

```text
id
contact_id
owner_user_id
status
last_inbound_at
last_success_cursor
current_profile_version_id
retry_count
next_retry_at
last_failure_code
last_failure_message
lease_owner
lease_acquired_at
lease_expires_at
created_at
updated_at
```

唯一约束：

```text
contact_id + owner_user_id
```

状态集合：

```text
CLEAN
DIRTY
PROCESSING
RETRY_WAIT
FAILED
```

`last_success_cursor` 只代表已经和画像、标签、证据同一事务提交成功的入站消息边界，不代表“已经看到过”或“已经领取过”。

### 4.2 `contact_profile_versions`

画像版本不可变，每次成功生成创建新版本；旧版本不覆盖、不删除：

```text
id
contact_id
owner_user_id
version
content
source_cursor
generation_batch_id
model
input_message_count
evidence_count
is_current
created_at
```

约束：

- `content` 不能为空；
- 服务端按汉字数量校验，最多 200 个汉字；
- 同一联系人同一 owner 的 `version` 递增；
- 只有一个版本可以为 `is_current = true`；
- 无实质变化时可以创建新处理记录并复用旧画像内容，但不强制创建重复画像版本，具体以实施计划中的幂等规则为准。

### 4.3 `contact_ai_labels`

AI 标签是联系人级独立实体，不复用人工标签：

```text
id
contact_id
owner_user_id
category
normalized_name
display_name
color_token
status
confidence
first_seen_at
last_seen_at
last_evidence_at
generation_batch_id
created_at
updated_at
```

唯一约束：

```text
contact_id + owner_user_id + category + normalized_name
```

标签状态：

```text
ACTIVE
STALE
INACTIVE
```

AI 标签总量没有固定业务上限。服务端必须限制每轮新增、更新、失效和恢复的数量，并限制名称长度、批量大小和数据库查询范围。

### 4.4 `contact_ai_label_evidence`

标签证据不可变，支持消息、Topic、通话转写和画像版本：

```text
id
label_id
contact_id
owner_user_id
evidence_type
evidence_id
evidence_excerpt
generation_batch_id
created_at
```

`evidence_type` 只允许：

```text
MESSAGE
TOPIC
CALL_TRANSCRIPT
PROFILE_VERSION
```

证据必须属于当前联系人和当前 owner。原始聊天正文不写入审计日志；证据表只保存受控短摘录或引用 ID，具体可见内容仍由权限查询层决定。

### 4.5 `contact_memory_attempts`

每次处理尝试保存不可变审计：

```text
id
contact_id
owner_user_id
generation_batch_id
input_cursor
output_cursor
status
failure_code
failure_message
model
duration_ms
input_message_count
output_label_change_count
profile_changed
retry_count
created_at
completed_at
```

该表用于诊断和统计，不参与联系人权限判断，也不替代 `contact_memory_states` 的当前状态。

## 5. AI 标签规则

### 5.1 分类与颜色

分类集合固定，分类内标签动态生成：

| 分类 | 枚举 | 固定颜色 token |
| --- | --- | --- |
| 身份 | `IDENTITY` | `blue` |
| 产品兴趣 | `PRODUCT_INTEREST` | `green` |
| 需求 | `NEED` | `orange` |
| 性格/沟通偏好 | `PERSONALITY_COMMUNICATION` | `purple` |
| 决策因素 | `DECISION_FACTOR` | `cyan` |
| 风险 | `RISK` | `red` |
| 关系阶段 | `RELATIONSHIP_STAGE` | `gray` |
| 其他稳定特征 | `OTHER_STABLE_TRAIT` | `brown` |

颜色由分类映射唯一决定，LLM 不能自行返回颜色。前端只消费服务端的 `color_token` 或其投影，不在页面中重新推断分类颜色。

### 5.2 生成门槛

标签名称应为简短词组，服务端限制长度，推荐 2 至 8 个汉字或等价的短词。名称必须先标准化再匹配：

- 去除首尾空白；
- 统一大小写和等价标点；
- 应用项目定义的同义词归一化；
- 不使用模糊相似度自动合并未经确认的不同标签。

只有满足以下条件之一才生成或更新 AI 标签：

- 在多次交流中重复出现；
- 在不同消息或 Topic 中得到相互支持；
- 明确表达并具有持续业务价值；
- 对当前销售关系或后续沟通具有稳定影响。

以下内容默认视为噪声：

- 一次性闲聊；
- 一次性情绪或临时抱怨；
- 单次地点、活动、天气或生活细节；
- 没有持续证据的猜测；
- 仅为了填满标签分类而生成的泛化描述。

价格、交期、规格和单次订单上下文只有在表现为持续需求、稳定偏好或明确决策因素时，才可以转化为标签。

### 5.3 生命周期

- `ACTIVE`：当前证据仍然有效；
- `STALE`：长期没有新证据，但尚不足以确认失效；
- `INACTIVE`：新信息明确否定，或长期缺乏有效证据后失效。

允许的 AI 操作：

```text
ADD
UPDATE
STALE
INACTIVATE
RESTORE
```

失效标签保留历史、原因和证据，不物理删除。新证据重新证明同一特征时恢复原标签，不创建重复标签。

每次变更保存：

- 变更前后值；
- 置信度；
- 证据引用；
- 生成批次；
- 模型；
- 简短原因。

## 6. 联系人画像

画像是面向销售阅读的短文本，不是消息逐条摘要，也不是标签名称列表。

内容优先描述：

- 联系人的身份或业务角色；
- 长期关注的产品、线路或服务；
- 稳定需求和决策因素；
- 沟通偏好；
- 当前关系阶段；
- 已确认的风险或限制。

画像不得写入：

- 无关闲聊；
- 一次性情绪；
- 未经证实的推断；
- 不必要的敏感结论；
- 可能让销售误认为事实的模型猜测。

### 6.1 首次画像

没有成功处理游标或当前画像时，使用：

- 有效历史入站消息；
- 相关 Topic；
- 已完成解析的可靠通话转写；
- 当前有效 AI 标签；
- 人工标签作为只读上下文；
- 其他稳定记忆。

首次处理使用全量有效历史的受限窗口，不把无限历史直接发送给 LLM。

### 6.2 增量画像

已有成功游标时，使用：

- 上次成功游标之后的新增入站消息；
- 当前画像；
- 当前有效 AI 标签；
- 必要的历史稳定记忆；
- 必要的相关 Topic 和通话转写。

LLM 返回完整新版画像，不返回局部字符串补丁。新增消息没有改变长期事实时，画像内容可以保持不变，但处理游标仍需推进。

## 7. 触发与状态机

### 7.1 入站触发

客户入站消息成功落库后调用 `ContactMemoryTriggerService`：

1. 根据 `contacts.created_by` 解析 owner；
2. owner 有效时创建或更新唯一记忆状态；
3. 将状态设为 `DIRTY`；
4. 更新 `last_inbound_at`；
5. 不调用 LLM。

同一联系人当天多条入站消息只更新同一状态，不创建无界任务记录。触发状态写入失败不能回滚消息入库，但必须记录可重放失败，避免静默丢失触发信号。

出站消息、Topic 更新和通话转写不单独创建触发任务。它们只在同一联系人已经因入站消息进入夜间处理时作为受限上下文。

### 7.2 夜间批处理

每天 `00:00` 后，`ContactMemoryScheduler` 扫描：

- `DIRTY`；
- 到达 `next_retry_at` 的 `RETRY_WAIT`；
- 租约已过期的 `PROCESSING`。

每次领取任务生成不可变的 `cutoff_at`。本次只消费成功游标之后且不晚于 `cutoff_at` 的入站消息。

处理期间到达的新入站消息不混入本次上下文。事务提交后如果发现 `cutoff_at` 之后存在新入站消息，状态继续为 `DIRTY`，等待下一轮。

没有新增入站消息的任务不调用 LLM，可以直接清理为 `CLEAN`；若任务已经有失败状态，则保留失败信息并按重试规则处理。

### 7.3 状态转换

正常路径：

```text
CLEAN -> DIRTY -> PROCESSING -> CLEAN
```

重试路径：

```text
PROCESSING -> RETRY_WAIT -> PROCESSING
```

超过重试上限：

```text
RETRY_WAIT -> FAILED
```

成功提交的必要条件是画像版本、AI 标签变更、证据、处理审计和成功游标在同一事务中完成。任一步失败，旧画像、旧标签和旧游标保持不变。

## 8. LLM 输入输出合同

### 8.1 上下文预算

服务端负责裁剪上下文，至少对以下维度设置硬上限：

- 本轮新增入站消息数量；
- 单条消息字符数；
- 本轮消息总字符数；
- Topic 数量和每条 Topic 字符数；
- 通话转写数量和每条转写字符数；
- 历史稳定记忆数量和字符数；
- 当前 AI 标签数量和名称长度；
- 单联系人单轮输出标签变更数量。

超出预算时按以下优先级保留：

1. 本轮新增入站消息；
2. 当前画像；
3. 当前有效 AI 标签；
4. 仍有效的稳定记忆；
5. 相关 Topic；
6. 通话转写和更早的补充历史。

每个联系人、每次处理和每个 LLM 请求都必须有超时、批量、重试和输出上界。

### 8.2 输入内容

输入包括：

- 联系人必要基础信息；
- 当前画像；
- 当前有效 AI 标签；
- 本轮新增入站消息；
- 必要的历史稳定记忆；
- 相关 Topic；
- 已解析的可靠通话转写；
- 人工标签只读副本，并明确标记为不可修改。

不发送其他 owner 的数据、无关联系人数据、访问凭证或完整无限历史。

### 8.3 输出结构

LLM 只返回结构化 JSON：

```json
{
  "profile": {
    "content": "不超过200字的联系人画像"
  },
  "labelChanges": [
    {
      "operation": "ADD",
      "category": "PRODUCT_INTEREST",
      "name": "冷链运输",
      "confidence": 0.86,
      "evidence": [
        {
          "type": "MESSAGE",
          "id": "message-id"
        }
      ],
      "reason": "多次明确询问冷链运输方案"
    }
  ],
  "noises": [
    {
      "evidence": [
        {
          "type": "MESSAGE",
          "id": "message-id"
        }
      ],
      "reason": "一次性闲聊"
    }
  ]
}
```

允许的 `operation` 只有：

```text
ADD
UPDATE
STALE
INACTIVATE
RESTORE
```

### 8.4 服务端校验

服务端在写入前必须校验：

- JSON 可解析且字段完整；
- 分类属于固定枚举；
- 操作属于固定枚举；
- 标签名称非空、长度合规且可标准化；
- 置信度位于 `0` 到 `1`；
- 证据 ID 属于本次输入上下文；
- 证据联系人和 owner 匹配；
- 画像正文最多 200 个汉字；
- 单轮变更数量不超过上限；
- 目标标签确实是 AI 标签；
- 不存在修改人工标签的请求；
- 不存在跨 owner 或跨联系人的引用。

LLM 无权直接访问数据库、消息队列或联系人接口，也无权决定服务端权限和最终状态。

## 9. 模块与数据流

### 9.1 模块职责

- `ContactMemoryTriggerService`：接收入站成功事件并标记 `DIRTY`；
- `ContactMemoryScheduler`：在夜间扫描、批量和并发控制；
- `ContactMemoryWorker`：领取租约、编排上下文、调用模型和提交结果；
- `ContactMemoryContextService`：查询、去重、裁剪上下文并生成证据索引；
- `ContactMemoryMutationService`：校验并原子写入画像、AI 标签、证据、审计和游标；
- `ContactMemoryQueryService`：返回画像、人工标签、AI 标签和处理状态投影；
- `ContactMemoryLlmGateway`：复用现有 OpenAI-compatible 配置，屏蔽模型供应商差异。

### 9.2 完整数据流

1. 客户入站消息成功落库；
2. 消息链调用 `ContactMemoryTriggerService`；
3. 服务按 `contact_id + owner_user_id` 创建或更新 `DIRTY` 状态；
4. 午夜后 scheduler 扫描可处理状态；
5. worker 获取租约和 `cutoff_at`；
6. `ContextService` 读取增量消息及受限历史上下文；
7. `LlmGateway` 返回结构化画像和标签变更；
8. 服务端校验输出、证据、owner 和状态转换；
9. `MutationService` 在一个事务中写入画像版本、标签变化、证据、审计和游标；
10. 查询层向前端返回人工标签和 AI 标签的结构化合并投影。

消息接入链只负责发出“入站消息已成功落库”的触发信号，不拥有记忆规则。

## 10. 并发、幂等与失败处理

### 10.1 租约

每个联系人最多一个有效处理租约。租约包含：

- `lease_owner`；
- `lease_acquired_at`；
- `lease_expires_at`。

事务提交时校验租约仍归当前 worker。租约过期后可以被其他 worker 回收；旧 worker 即使恢复，也不能覆盖已经提交的新结果。

### 10.2 幂等

处理批次使用联系人、owner、输入游标和 `cutoff_at` 形成幂等边界。重复投递不能重复生成画像版本、重复写入同一证据或重复推进游标。

只有整个事务成功后才推进 `last_success_cursor`。处理失败不得推进游标。

### 10.3 失败分类

统一记录以下结构化错误：

```text
CONTEXT_LOAD_FAILED
LLM_TIMEOUT
LLM_RATE_LIMITED
LLM_UNAVAILABLE
INVALID_OUTPUT
INVALID_EVIDENCE
OWNER_NOT_FOUND
PERSISTENCE_FAILED
LEASE_CONFLICT
```

### 10.4 重试

- 网关超时、限流和暂时不可用使用有限次数指数退避；
- 输出校验失败可以有限重试，并记录原始校验原因；
- owner 缺失、权限失败和数据证据损坏不无限重试；
- 默认最多重试 3 次，每次记录 `next_retry_at`；
- 超过上限进入 `FAILED`，旧画像、旧标签和旧游标保持不变；
- 后续新的入站消息可以重新将联系人标记为 `DIRTY`；
- 管理端重试入口只允许当前联系人 owner 或符合既有管理权限的服务端边界调用。

LLM 暂时不可用时不降级为猜测、字符串修补或覆盖旧结果。证据不足时可以返回无变更，并推进已经成功处理的游标。

## 11. 查询 API 与前端投影

联系人详情查询返回只读结构，至少包含：

- 当前画像；
- 人工标签列表；
- AI 标签列表；
- 每个标签的来源、分类、颜色和状态；
- 记忆处理状态；
- 最近成功处理时间；
- 最近失败的用户安全文案；
- 是否存在待处理入站消息。

展示规则：

- 人工标签和 AI 标签分区展示；
- 人工标签继续使用现有编辑入口；
- AI 标签按固定分类颜色展示；
- `STALE` 标签弱化展示；
- `INACTIVE` 默认不展示，但保留审计查询；
- AI 标签默认只读；
- 画像更新中继续展示旧画像并显示待更新状态；
- 处理失败时继续展示旧画像和安全失败状态；
- 没有画像时显示空状态，不显示模型原始错误；
- 证据查看遵守当前联系人 owner 权限。

前端不自行合并标签、不自行推断颜色、不自行决定状态或错误结论。所有这些信息由查询层结构化返回。

## 12. 可观测性与隐私

每次处理至少记录：

- 联系人和 owner；
- 处理批次、输入游标和输出游标；
- 状态和失败类型；
- 模型、耗时和 token 使用量；
- 输入消息数量；
- 输出标签变更数量；
- 画像是否变化；
- 重试次数；
- 创建和完成时间。

至少提供以下指标：

- 待处理联系人数量；
- 成功率、失败率和重试率；
- LLM 平均耗时和超时数量；
- 单轮平均标签变更数；
- `FAILED` 联系人数；
- 最老待处理任务年龄；
- 缺少 owner 的联系人数量。

日志和审计不得记录完整聊天正文、完整 prompt、完整画像历史或访问凭证，只记录 ID、阶段、数量和结构化错误原因。证据摘录必须有字符上限，并服从联系人权限。

## 13. 数据库迁移与上线

数据库迁移只创建新表、索引、约束和必要的状态枚举，不批量调用 LLM。

上线顺序：

1. 部署新表和查询模型；
2. 部署入站标脏逻辑；
3. 部署夜间 scheduler、worker 和失败诊断；
4. 部署联系人详情的画像与 AI 标签只读投影；
5. 在具备 LLM 配置的环境启用夜间处理；
6. 观察队列、失败、耗时和标签噪声指标。

新功能不要求一次性回算所有旧联系人。没有新入站消息的旧联系人不因部署自动调用 LLM。

## 14. 测试与验收

### 14.1 领域与服务测试

必须覆盖：

- 入站消息创建或合并 `DIRTY`；
- 出站消息不会单独触发 LLM；
- Topic 更新和通话转写不会单独触发 LLM；
- 当天没有新增入站消息时不调用 LLM；
- 首次画像读取受限的有效历史；
- 增量处理只消费成功游标之后的消息；
- 新入站消息在处理期间到达时下一轮仍可处理；
- 画像超过 200 个汉字时拒绝提交；
- 非法分类、非法操作和无效证据被拒绝；
- AI 标签不会写入或修改人工标签；
- 标签去重、同义词归一化、失效和恢复正确；
- LLM 失败时画像、标签和游标保持不变；
- 同一联系人并发 worker 只有一个可以提交；
- 租约过期后任务可以恢复；
- owner 隔离阻止跨用户读取和修改；
- 查询投影正确区分人工标签和 AI 标签。

### 14.2 数据库与合同测试

- 新表唯一约束和状态约束有效；
- 画像版本只能有一个当前版本；
- 标签证据关联的联系人和 owner 一致；
- 游标只在原子事务成功时推进；
- LLM JSON schema 和服务端校验保持一致；
- 所有输入、输出、批量和重试预算都有明确上界。

### 14.3 验收标准

本设计实现完成后必须满足：

- 新入站消息进入联系人记忆待处理状态；
- `00:00` 后 worker 能生成或更新画像与 AI 标签；
- 画像正文不超过 200 个汉字；
- AI 标签按固定分类颜色展示且没有业务总量上限；
- 闲聊不会持续制造低价值标签；
- 人工标签在整个流程中保持不变；
- 失败任务不会覆盖旧结果或推进错误游标；
- 画像版本、标签变更、证据和处理批次可追溯；
- 联系人详情页能区分人工标签、AI 标签和画像状态；
- 专项测试、编译和最终全量回归按项目实施计划执行。

## 15. 明确不做

- 不实时逐条调用 LLM；
- 不按每日全量历史重算；
- 不在没有入站消息时主动生成画像；
- 不让 AI 修改、删除、失效或恢复人工标签；
- 不提供 AI 标签人工编辑入口；
- 不设置 AI 标签总数量上限；
- 不把 Topic 活动表直接改造成联系人记忆表；
- 不把模型原始输出当作数据库事实；
- 不通过无界历史、无界队列或无限重试扩大运行面；
- 不把管理员或系统 worker 变成联系人记忆的业务 owner。
