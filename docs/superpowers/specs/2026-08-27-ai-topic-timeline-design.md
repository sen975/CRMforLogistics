# ChatApp、邮件与电话 AI Topic 时间轴设计

## 1. 目标与边界

在 Spring 消息中心的联系人工作区中，首次打开联系人时自动根据已有沟通内容生成 Topic；后续有新消息时，基于已保存 Topic 判断关联度，关联度不足时创建新 Topic。Topic 在右侧联系人展示框中以时间轴形式展示，并允许员工手工合并 Topic。

本次只开放以下 AI 输入渠道：

- `chatapp`
- `email`
- `phone`（电话录音转录文本和人工备注）

企业微信 `wecom` 明确不进入 Topic AI 输入、指纹、关联、摘要和来源条目。企业微信仍按现有专区/时间线能力运行，不因本功能改变。

本次不实现：自动发送回复、企业微信 AI、Topic 拆分、拖拽排序、跨联系人合并、任务/阶段自动创建、联系人画像和背调。

## 2. 当前系统事实

- Spring 后端的统一消息入口是 `ThreadService`/`ThreadController`，前端联系人消息页是 `ThreadPage`。
- `ThreadPage` 已把消息和 `useCallRecordTimeline` 返回的电话记录按 `occurredAt` 合并展示。
- 右侧桌面面板由 `AppLayout` 承载，默认渲染 `ContactDetailPanel`；电话详情仍由 `CallRecordDetail` 承载。
- 邮件和 ChatApp 统一存放在 `messages`，渠道由关联的 `channel_accounts.channel_type` 决定；电话记录存放在 `call_records`。
- 企业微信混合联系人可出现在统一列表，但企业微信专区有独立的 `WeComConversationPanel`，不能被 Topic 服务隐式纳入。

## 3. 推荐架构

Topic 由 Spring 后端作为唯一 owner。前端只消费结构化 Topic 状态、来源投影和命令，不自行判断关联度或拼接摘要。

数据流：

1. `GET /api/v1/contacts/{contactId}/topics` 先执行联系人访问校验。
2. 服务查询该联系人已有 Topic 和生成任务；若没有任何有效输入指纹，则幂等创建一次初始生成任务并立即返回 `GENERATING`。
3. 有界 worker 领取任务，按联系人读取 ChatApp、邮件和电话来源，排除 WeCom，生成输入指纹后调用通用 OpenAI-compatible provider。
4. provider 返回结构化 JSON；服务校验来源 ID、渠道、大小、Topic 数量和文本长度后，在一个事务中写入 Topic、来源关联和版本记录。
5. 新消息到达时，SSE/前端刷新 Topic 查询。服务比较最新输入指纹与已处理指纹，只为新增来源创建增量任务。
6. 右侧面板展示任务状态和已持久化 Topic；任务执行不阻塞消息时间线。

### 3.1 持久化模型

新增 Flyway 迁移（版本号以实施时当前最高版本为准）：

`ai_topics`

- `id uuid primary key`
- `contact_id uuid not null references contacts(id)`
- `title varchar(200) not null`
- `ai_summary text not null`
- `confirmed_summary text`
- `status varchar(20) not null`：`READY` 或 `ARCHIVED`
- `first_occurred_at timestamptz not null`
- `last_occurred_at timestamptz not null`
- `input_fingerprint char(64) not null`
- `version bigint not null default 1`
- `created_at`、`updated_at`

`ai_topic_items`

- `id uuid primary key`
- `topic_id uuid not null references ai_topics(id)`
- `message_id uuid references messages(id)`
- `call_record_id uuid references call_records(id)`
- `occurred_at timestamptz not null`
- `channel_type varchar(20) not null`，只允许 `chatapp`、`email`、`phone`
- `created_at timestamptz not null`
- CHECK 保证 `message_id` 与 `call_record_id` 恰好一个非空。
- 部分唯一索引保证一个消息或电话记录最多属于一个未归档 Topic。

`ai_topic_generation_jobs`

- `id uuid primary key`
- `contact_id uuid not null references contacts(id)`
- `job_kind varchar(20) not null`：`INITIAL` 或 `INCREMENTAL`
- `input_fingerprint char(64) not null`
- `status varchar(20) not null`：`PENDING`、`PROCESSING`、`RETRY_WAIT`、`COMPLETED`、`FAILED`
- `attempt_count integer not null`
- `next_attempt_at`、`lease_until`、`lease_owner`
- `last_error_code varchar(100)`、`last_error_message varchar(1000)`
- `created_at`、`updated_at`、`completed_at`
- `(contact_id, input_fingerprint)` 唯一约束保证同一输入不会重复调用模型。

`ai_topic_versions`

- `id uuid primary key`
- `topic_id uuid not null references ai_topics(id)`
- `version bigint not null`
- `change_type varchar(30) not null`：`AI_GENERATED`、`EMPLOYEE_EDITED`、`MERGED`
- `title`、`summary`、`source_topic_ids jsonb`
- `actor_user_id uuid references users(id)`
- `created_at timestamptz not null`
- 保留 AI 原始结果和人工确认结果的版本关系，不把模型输出覆盖成不可追溯的单一字段。

查询和写入必须通过联系人访问边界；不能仅依赖前端隐藏企业微信或 Topic 控件。

### 3.2 AI provider 合同

后端增加通用 OpenAI-compatible 配置，配置项通过环境变量注入：

- `AI_BASE_URL`
- `AI_API_KEY`
- `AI_MODEL`
- `AI_TIMEOUT_SECONDS`
- `AI_MAX_INPUT_RECORDS`
- `AI_MAX_INPUT_BYTES`
- `AI_TOPIC_MATCH_THRESHOLD`（默认 `0.65`）

API Key 只存在后端配置和内存，请求日志、审计日志、异常响应和前端 payload 均不得包含它。provider 使用后端现有 `RestClient` 体系，并设置连接/读取超时。

初始生成和增量生成均要求模型返回 JSON，服务端使用结构化解析器校验。模型只能引用输入中的来源 ID；未知来源、WeCom 来源、重复来源、空标题、超长文本或非法 JSON 均视为失败，不落库。

初始请求的逻辑输入为：时间、渠道、方向、邮件主题、消息正文、电话转录/备注和稳定来源 ID。增量请求额外包含现有 Topic 的标题、当前摘要、时间范围和已关联来源摘要，模型返回新增来源到既有 Topic 或新 Topic 的分配结果及关联度。

输入按时间正序、记录数和总字节数有界；超过上限时按时间窗口拆分任务，不把无限历史发送给模型。

## 4. HTTP API

### 4.1 查询与自动触发

`GET /api/v1/contacts/{contactId}/topics`

响应：

```json
{
  "contactId": "uuid",
  "generation": {
    "status": "NOT_STARTED|GENERATING|READY|FAILED",
    "jobId": "uuid|null",
    "errorCode": "string|null",
    "updatedAt": "timestamp|null"
  },
  "topics": [
    {
      "id": "uuid",
      "title": "美国海运报价",
      "summary": "客户正在比较美国航线报价，等待确认出货时间。",
      "summarySource": "AI|EMPLOYEE",
      "firstOccurredAt": "timestamp",
      "lastOccurredAt": "timestamp",
      "channels": ["email", "phone"],
      "sourceCount": 4,
      "sourceItems": [
        { "id": "uuid", "sourceType": "message|callRecord", "occurredAt": "timestamp", "channelType": "email" }
      ],
      "version": 2
    }
  ]
}
```

没有有效 Topic 且存在可用输入时，第一次查询幂等创建 `INITIAL` 任务；返回不等待模型。仅有 WeCom 输入时返回 `NOT_STARTED`、空 Topics 和明确的 `WECOM_AI_UNSUPPORTED` 能力标识，不创建任务。

### 4.2 员工编辑

`PATCH /api/v1/topics/{topicId}`

请求只允许修改 `title`、`confirmedSummary` 和 `expectedVersion`。服务端校验权限和乐观锁，写入 `ai_topic_versions(change_type=EMPLOYEE_EDITED)`；AI 后续更新保留员工确认字段，不静默覆盖。

### 4.3 Topic 合并

`POST /api/v1/topics/merge`

请求：`{ "topicIds": ["uuid", "uuid"], "expectedVersions": { "uuid": 2 } }`。

服务端要求至少两个同一联系人、未归档 Topic。以最早 `first_occurred_at` 的 Topic 作为规范目标，合并来源关联并归档其余 Topic；所有来源仍可追溯，写入目标 Topic 的 `MERGED` 版本和审计记录。合并后异步重新生成目标摘要，失败时保留合并前可读摘要。

## 5. 右侧 UI

`ContactDetailPanel` 顶部增加 `Topic 时间轴` 区域，联系人资料和消息详情继续保留在下方。桌面端使用现有右侧 Sider，移动端使用现有 Drawer。

每个 Topic 卡片显示：标题、最近更新时间、时间范围、摘要、渠道标签、来源数量和状态。Topic 按 `lastOccurredAt` 倒序排列；来源条目按 `occurredAt` 正序展示，点击来源调用现有选择消息/电话记录能力定位中间时间线。

状态行为：

- `NOT_STARTED`：仅在确实没有有效来源时显示空态；有来源时查询会自动转为 `GENERATING`。
- `GENERATING`：显示“正在整理历史沟通”，不阻塞消息区域。
- `READY`：显示 Topic 时间轴和“编辑/合并”操作。
- `FAILED`：显示脱敏错误原因和“重试”，已有 Topic 继续可读。
- 仅 WeCom：显示“当前渠道暂不支持 AI Topic 总结”，不伪造生成成功或空 Topic。混合联系人即使当前没有未归属的新来源，也不能被判定为 WeCom-only；应继续展示已有 Topic，或返回普通的 `NOT_STARTED` 空态。

手工合并采用编辑模式下的多选确认，不做拖拽和自动合并。合并操作需要二次确认，完成后刷新 Topic 列表并保留历史版本。

新消息到达后复用现有 SSE 刷新联系人、线程和电话记录；Topic 查询在输入指纹变化时显示增量生成状态。AI 失败不能影响发送、收取和中间时间线。

## 6. 并发、失败与安全

- 同一联系人只有一个未完成生成任务；数据库唯一约束和 worker 租约共同防止重复调用。
- worker 有界运行，任务有最大尝试次数、退避和租约过期恢复；不得用无限线程、永久重试或阻塞 HTTP 请求实现生成。
- provider 超时、连接错误、限流和非法 JSON 进入 `RETRY_WAIT` 或 `FAILED`，由结构化错误码区分可重试和不可重试原因。
- 新消息在任务执行期间到达不会丢失：完成提交后重新计算指纹，若仍有新增来源则创建下一次增量任务。
- AI 输入只包含必要沟通字段；日志不写完整正文、API Key 或完整模型响应。审计只记录任务 ID、联系人 ID、状态、错误码和版本摘要。
- 所有接口复用现有登录、联系人访问和角色边界；Topic ID、来源 ID 和合并请求都必须服务端重新校验归属。

## 7. 测试与验收

后端单元/契约测试：

- Topic 初始分组按时间和模型分配结果正确落库。
- WeCom 消息不会进入新的输入、指纹、Topic 来源或 provider payload。纯 WeCom Topic 不展示；若历史数据存在混合渠道 Topic，保留非 WeCom 的总结和来源入口，并隐藏 WeCom 来源按钮。
- 首次查询只创建一个任务；相同指纹不会重复调用。
- 增量任务只携带新增 ChatApp/邮件/电话来源；低关联度创建新 Topic。
- 输入超限按有界批次处理；超时、限流、非法 JSON 正确进入失败/重试状态。
- 员工编辑、乐观锁冲突、合并和版本历史正确；不同联系人或无权限用户不能读取/合并。

前端测试与浏览器验收：

- 右侧显示生成中、完成、失败、仅 WeCom 空态和混合渠道过滤。
- Topic 来源点击能定位消息/电话；多选合并需要确认并刷新结果。
- 桌面右侧 Sider 和移动 Drawer 不遮挡消息时间线，长标题/摘要不溢出。
- 新消息 SSE 后状态与 Topic 列表更新，生成中消息区域仍可滚动和操作。

命令门禁：

```bash
cd demo/message-center-spring/backend
mvn -q test
mvn -q -Pproduction -DskipTests package

cd ../frontend
npm test
npm run build
```

实现完成前还需在浏览器检查联系人包含 ChatApp、邮件、电话和 WeCom 的桌面/移动布局，并确认 Network 请求没有前端 AI Key 或 WeCom Topic 输入。

## 8. 实施顺序

1. 新增迁移、实体/Mapper、Topic 查询与访问服务，先用失败测试锁定渠道过滤、幂等和版本合同。
2. 增加 provider 配置、结构化 JSON 解析、任务 worker、重试/租约和初始/增量聚合服务。
3. 接入 Topic HTTP Controller 和前端 API 类型，完成右侧 Topic 时间轴、状态和合并交互。
4. 接入 SSE/查询刷新、来源定位、失败重试和移动端 Drawer。
5. 执行专项测试、构建和浏览器验收，更新 Spring README 的 AI 配置说明。
