# AI 可操作面：工具面扩张 vs MCP 传输层

> **触发问题**：「能不能把项目里所有的增删查改都变成 MCP」——希望 AI 能改某人的备注、触发 AI 画像/标签、删掉一些东西、总结邮件/电话/chatapp/wecom 的内容。
>
> **一句话结论**：**MCP 改变的是「谁来调用」，不改变「能做什么」。** 你列的 4 件事里有 **3 件的能力已经存在**，缺的只是「工具声明」；而唯一真正的架构级阻塞是**身份**。

---

## 0. 结论摘要

1. **MCP 骨架已经写好了，只是被刻意关着。** `ToolDefinition` 直接复用官方 SDK 的 `McpSchema.Tool`；`InProcessToolAdapter` 已提供 `tools/list` / `tools/call` 语义。其 javadoc 原文：

   > 这不是"没做 MCP"。值钱的是声明模型，不是字节。…将来补一个 HTTP 适配层时，本类**一行都不用改**，工具与策略代码也不用改。

   `/mcp` 被明确推到「阶段 5」，理由见 §1.4。

2. **所以「做 MCP」对「助手能不能改备注」零帮助。** 应用内助手与 MCP 客户端走的是**同一套** `ToolRegistry`。要做的是**加工具**（现在 8 个），这件事不需要 MCP。

3. **不要「把所有增删查改都变成 MCP」。** 工具面 = 模型的提示词面。表级 CRUD 摊开会让模型选错工具，并把破坏性动作的确认成本压到零。正确姿势是**一个意图一个工具**。

4. **唯一的架构级阻塞是身份**：全项目只有浏览器会话 token，**PAT / service account / API token 全项目 0 命中**。而 MCP 客户端不是浏览器里登录的人。

5. **`/mcp` 一旦暴露就是公开端点**：`SecurityConfig#75` 是 `anyRequest().permitAll()`，而 `/mcp` 不在 `/api/**` 下。

6. **两个必须你拍板的决策**（都不是技术问题）：① 要不要让模型读到消息**原文**（与 `listStableContext` 的刻意设计相反）；② 删除要不要做（**会话/消息根本没有删除能力**）。

---

## 1. 现状（实测，含证据）

### 1.1 工具面只有 8 个，写工具只覆盖待办

`service/assistant/mcp/` 下三个 `…AssistantTools.java`，每个工具一个 `@Bean ToolDefinition`：

| 工具 | 类型 | 注解 |
|---|---|---|
| `todo.create` | 写（只增） | 非破坏、非幂等 — **唯一免确认** |
| `todo.complete` | 写 | 破坏性、幂等 |
| `todo.delete` | 写 | 破坏性、幂等 |
| `todo.update` | 写 | 破坏性、幂等 |
| `conversation.search` | **只读** | — |
| `conversation.pin` | 写（可逆） | — |
| `contact.search` | **只读** | — |
| `contact.brief` | **只读** | — |

**联系人域 2 个工具全是只读** —— 这就是「改不了备注」的直接原因。

策略 `AssistantActionPolicy`：`AUTO_EXECUTE_ALLOWLIST = {todo.create}`；`READ_ONLY_ALLOWLIST = {conversation.search, contact.search, contact.brief}`；其余默认 `CONFIRM`。白名单写死、不采信注解（清单内若未声明 `readOnlyHint=true` 直接抛异常），并有启动自检 `AssistantPolicySelfCheck`。

确认与审计：`assistant_pending_actions`(V82) + `assistant_action_audit`(V83)。

### 1.2 你要的四件事 · 真实状态

| 你的诉求 | 能力是否存在 | 缺什么 |
|---|---|---|
| **改某人的备注** | **已存在**：`ContactGroupService.updateRemark(contactId, remark, userId)`（`:236`，内含 `requireContact` 归属校验）；端点 `POST /api/contacts/{id}/remark` | 只缺一个 `ToolDefinition` |
| **触发 AI 画像 / AI 标签** | **能力已在，但只有自动档**：`contact_profile_versions` / `contact_memory_facts` / `contact_ai_labels`（V70）齐备；链路 = `ContactMemoryTriggerService.markInboundPersisted` → `contact_memory_trigger_events`(V72) → `ContactMemoryScheduler` → `ContactMemoryWorker` → LLM 网关 → `ContactMemoryMutationService` | **无手动触发入口**。但项目已有现成范式可抄：`POST /api/v1/call-records/{id}/retry`、`AiTopicService.enqueueOperation` |
| **删掉一些有的没的** | **面不齐**：todo 硬删（且已有 `todo.delete` 工具）；联系人**无删除**（只有 `setStatus("merged")`）；**会话 / 消息完全无删除能力**；渠道账号 unbind 散在多个 controller（WeCom 是硬删 `deleteById`） | 「可删清单」+ 软删 / 恢复策略。且 REST 侧删除**无二次确认、无审计** |
| **总结邮件 / 电话 / chatapp / wecom** | **内容早就已经在一起了**：四渠道都经 `MessageMapper.insertMessage` 写进同一张 `messages`；`listInboundMessagesByCursor` 读 `messages` **不带渠道过滤**；电话走 `call_transcript_revisions`。汇聚点是 `ContactMemoryContextService.load(ownerUserId, contactId, cutoff)`（`:63`） | **边界是故意设的**：`load(...)` 的唯一调用方是后台记忆 worker（`ContactMemoryWorker:96`）；助手侧 `contact.brief` 走的是 `listStableContext`（**不含消息原文与通话转写**），见 `ContactBriefProvider:122` 与 `ContactBrief` 的 javadoc（计划 §6 B2 的刻意改动） |

> **⇒「他都做不到」的真因不是「没做成 MCP」，而是工具面太窄**：8 个工具，写工具只在待办，联系人域 2 个全是只读。
>
> （*这是 2026-09-23 上午盘点时的状态。当晚 P0 已把工具面扩到 **19 个**、只读档 7 项，实施与验收记录见 `2026-09-23-ai-tool-backlog.md` §7。*）

### 1.3 身份：只有浏览器会话

- `TokenAuthenticationFilter` 读 `Authorization: Bearer` → `AuthSessionService`（登录会话令牌）。
- `personalAccessToken` / `serviceAccount` / `apiToken` 等关键词在全项目 **0 命中**。
- 全项目的可见性防线是 `where user_id` / `owner_id` 这类**处处硬编码**的 owner 过滤。例：`ContactMapper.findAccessibleById` 是 createdBy / assignedUser / teamMembers / conversationAccessGrants 四路并集；`ContactMemoryMapper:36` 是 `and c.created_by = #{ownerUserId}::uuid`。

### 1.4 `/mcp` 为什么被刻意关着

`InProcessToolAdapter` 的 javadoc 写得很明白，三条理由：

1. SDK **没有**进程内传输，走 HTTP 就得真起一个 Servlet、多一跳网络、多一份 session 生命周期 —— 而调用方（编排层）与工具本来就在同一个请求线程里；
2. 当前唯一的消费者是本进程的助手，**没有外部 host 要用这个端点**；
3. **`/mcp` 一旦暴露还必须单独加认证规则**，否则会被 `SecurityConfig` 的 `anyRequest().permitAll()` 变成公开端点。

---

## 2. 为什么「把所有增删查改都变成 MCP」是反模式

1. **MCP 是传输，不是能力。** 两个入口共用同一个 `ToolRegistry`；加 MCP 不会多出任何一个工具。
2. **工具面即提示词面。** 把表级 CRUD 摊开就是几十上百个工具，模型的工具选择错误率会随数量上升 —— 而每一个误选都可能是一次写操作。
3. **破坏性动作的确认成本会被压到零。** 现有设计刻意把 `todo.delete` 这类放进 `CONFIRM` + pending + audit；「全 CRUD 都变 MCP」等于把这道闸门默认敞开。
4. **粒度不对。** 用户说的是「改某某人的备注」，不是「update contacts 的 8 个字段」。应该是**一个意图一个工具**。

---

## 3. 路线（三阶段，阶段 1 不需要 MCP）

### 阶段 1 · 扩工具面（立刻可用，不碰 MCP）

复用现有 service，只加「工具声明 + 策略归类 + 确认卡片文案」。建议 5 个：

| 工具 | 复用 | 类型 |
|---|---|---|
| `contact.update_remark` | `ContactGroupService.updateRemark` | 写，CONFIRM |
| `contact.update_profile` | `ContactGroupService.updateProfile`（别名 / 职位） | 写，CONFIRM |
| `contact.refresh_memory` | `ContactMemoryTriggerService`：给该联系人 enqueue 一条 trigger 事件 + 立刻 `replayDue` | 写但幂等，可进 AUTO |
| `contact.tags.set` | `ContactTagMapper`（人工标签，非 AI 标签） | 写，可逆，CONFIRM |
| `contact.thread_summary` | `ContactMemoryContextService.load(...)` 或其裁剪版 | 只读（**取决于 §4 决策 ①**） |

每个工具三件套，缺一不可：

1. **只走 service，不碰 Mapper** —— 归属防线在 service 里（`requireContact` / `findAccessibleById`）；
2. **策略显式归类** —— 默认落 `CONFIRM` 即可，无需改 `AssistantActionPolicy`；
3. **确认卡片 `switch(toolName)` 加 case** —— 否则会走 default 显示裸参数（`{"remark":"…"}` 这种给用户看等于把复核成本抬高到没人会做）。

「加工具」这条路径已跑通三次（todo → conversation → contact）：**编排层、解析器、提示词一行不改**。若开新候选域，另需 `CandidateSet` 实现 + Provider + 在 `AssistantContextBuilder.build()` 注册。

### 阶段 2 · MCP 传输层（只在需要「外部 AI 直接操作 CRM」时才做）

1. **先做身份**：PAT 表（哈希存储、作用域、有效期、可吊销）+ 映射到**真实 user_id**。**不要服务账号** —— 那等于绕过全项目所有 `where user_id` 防线。
2. **给 `/mcp` 加显式认证规则**，别落进 `anyRequest().permitAll()`。
3. 接 `InProcessToolAdapter` 的 HTTP 适配 —— 工具与策略代码**零改动**。
4. 写操作仍走 pending + audit，或至少强制确认。

### 阶段 3 · 删除（先定策略，再做工具）

定义「可删清单 + 软删优先 + 恢复窗口」，再逐类做工具。**会话 / 消息的删除能力现在根本不存在**，这是新增功能，不是「暴露已有接口」。

---

## 4. 待你拍板

| # | 决策 | 影响 |
|---|---|---|
| ① | **目标是哪个？**（a）应用内助手变强 ⇒ **阶段 1 就够，与 MCP 完全无关**；（b）让外部 AI（WorkBuddy / Claude 等）直接操作 CRM ⇒ 阶段 1 + 2 | 决定要不要做身份设施 |
| ② | **原文出不出边界？** 「总结四渠道内容」意味着模型上下文里会出现客户原话，与 `listStableContext` 的刻意设计相反。可选：只给已有的画像/事实/标签 + 统计，不给原文 | **2026-09-23 已拍板：允许原文进上下文，但只允许「用户点名的那一份」**（口径与落地见 `2026-09-23-ai-tool-backlog.md` §7.4） |
| ③ | **删除做不做？** 做的话，先给「可删清单」 | 决定阶段 3 是否启动 |

---

## 附：本文证据位置速查

| 事实 | 位置 |
|---|---|
| 工具声明与注册 | `service/assistant/mcp/{Todo,Conversation,Contact}AssistantTools.java`、`ToolRegistry.java` |
| 传输层（未开） | `service/assistant/mcp/InProcessToolAdapter.java`（javadoc L11-36） |
| 策略与自检 | `service/assistant/AssistantActionPolicy.java`、`AssistantPolicySelfCheck.java` |
| 待确认动作与审计 | `db/migration/V82__assistant_pending_actions.sql`、`V83`（audit） |
| 改备注 | `service/contact/ContactGroupService.java:236`、`web/ContactController.java:90` |
| 助手侧联系人简报（不含原文） | `service/assistant/ContactBriefProvider.java:34,122` |
| 跨渠道汇聚 | `service/contactmemory/ContactMemoryContextService.java:63`、`mapper/ContactMemoryMapper.java` |
| 唯一汇聚调用方 | `service/contactmemory/ContactMemoryWorker.java:96` |
| 授权规则 | `config/SecurityConfig.java:74-75` |
