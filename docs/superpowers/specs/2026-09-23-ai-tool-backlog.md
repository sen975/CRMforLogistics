# AI 可操作面 · 工具清单（backlog）

> 2026-09-23 实测。项目根 `/Users/z/workItem/CRMforLogistics-message-center-presplit-runtime`，后端 `demo/message-center-spring/backend`。
> 本文只列**候选工具**与**现状/成本**，不含实现（例外：§7 是 P0 的实施与验收记录）。验收标准见 `2026-09-23-ai-operable-surface-tools-and-mcp.md`。

## 0. 实施状态（2026-09-23 更新）

P0 批次 **11 项全部落地**。其中 3 项撞了不同的墙，都靠**先补前置**才做成（见下表与 §7）：

| 批次 | 项 | 状态 |
|---|---|---|
| P0 | A1 A2 A3 A4 A5 + B1 B2 B3 | ✅ 已实现并验收（见 §7.1） |
| P0 | B4 `contact.topics_merge` | ✅ 已实现 —— 前置是**扩解析器支持「一组引用」**（§7.2） |
| P0 | F1 `wecom.summary_read` | ✅ 已实现 —— 前置是**补一条按 owner 过滤的读路径**（§7.3） |
| P0 | C4 `message.read` | ✅ 已实现 —— 前置是一次**口径拍板**：允许原文进上下文（§7.4） |
| P1 | C5 `message.send_email` + C6 `message.send_chatapp` | ✅ 已实现 —— 前置是**定「外向副作用的确认规格」**，并顺手撞了一次架构门禁（§7.5） |
| P1 | A6 `contact.memory_read`（补全）+ A7 `contact.refresh_memory` | ✅ 已实现 —— 前置是**三个口径拍板**（重算 = 催未处理的 / 只入队 / 顺手补读状态），并发现「判据的写法本身就是防线」（§9） |

**三条被前置拦住这件事本身是结论**：清单上的「🟢 = service 方法已存在」**不等于**「可以安全地暴露给助手」。
B4 差的是机制（候选比对对数组静默失效），F1 差的是不变量（读写都没有 `where user_id`），
C4 差的是决策（原文出不出边界）。三者在代码上都表现为「加个声明就能跑」，
而那正是最危险的一种「能跑」。

C5/C6（§7.5）拦下的东西又是另外两类：**规格**（不可撤回的后果怎么让用户确认）与**层次**
（业务域直接 import 渠道包，被 `ArchitectureBoundaryTest` 当场拦下）。前者清单里写了、后者谁都没想到 ——
「清单上的 🟢」只保证能力在，不保证它落在对的那一层。

---

## 1. 怎么读这份清单

**三档状态**（决定成本，不是决定要不要做）：

| 标记 | 含义 | 典型工作量 |
|---|---|---|
| 🟢 | **能力已在**：service 方法（多数还有 HTTP 端点）都已存在，缺的只是 `ToolDefinition` | 一个工具 = 声明 + 策略归类（+ 确认 case），几十行 |
| 🟡 | **缺入口**：底层链路完整，但没有「手动触发」的方法/端点，得先补一个薄入口 | 先补入口，再加工具 |
| 🔴 | **能力不存在**：要新建 service / 表 / 或先做边界决策 | 需求级，不是加工具 |

**「三件套」**（缺一不可，`AssistantPolicySelfCheck` 启动自检会拦不一致）：
1. **工具声明** —— `*AssistantTools.java` 里的 `ToolDefinition`（`readOnlyHint` / `destructiveHint` / `idempotentHint` 三个注解必填，不写按「不安全」算）
2. **策略归类** —— 进 `READ_ONLY_ALLOWLIST`（免确认+可循环）或 `AUTO_EXECUTE_ALLOWLIST`（免确认+本轮终止）或都不进（落确认卡片）。删改类**必须**落确认。
3. **确认卡片 case** —— 在 `AssistantPendingActionService` 的执行分发里加分支（只读与 AUTO 不需要）

**只读工具与写工具在「候选」上的差别**：写工具的引用参数（`CONTACT:<uuid>`）要绑**候选组**来防编造 id；
只读工具**分两种**，这个区别在 C4 之后才变清楚：

- **可检索的**（`conversation.search` / `contact.search` / `contact.topics_read`）：引用参数不是"挑一个"
  而是"按关键词查"，它本来就要**突破候选窗口**，所以不进候选、结果是**替换**候选；
- **不可检索的**（`contact.timeline` 的 `contactRef`、`message.read` 的 `messageRef`）：
  引用只能来自上一轮的结构化结果，这时候选比对是**唯一**一道程序化防线 —— 必须绑，
  否则一次都没有东西可查的对象反而成了唯一没有防护的入口。

> ⚠️ **这条差别曾有一个未覆盖的例外，已于 2026-09-23 修掉**：引用参数若是**数组**
> （「把这两条话题合并」），两道路径会**同时静默失效** —— 见 §7.2。

---

## 2. 现状快照（2026-09-23 实测）

**已注册 22 个工具**（`service/assistant/mcp/`；21 → 22 = 本轮新增 `contact.refresh_memory`）：

| 工具 | 域 | 性质 | 策略 |
|---|---|---|---|
| `todo.create` | 待办 | 写（只增） | **AUTO**（免确认，唯一一个） |
| `todo.complete` | 待办 | 写（可逆） | CONFIRM |
| `todo.update` | 待办 | 写 | CONFIRM |
| `todo.delete` | 待办 | 写（破坏性） | CONFIRM |
| `conversation.search` | 会话 | **只读** | READ |
| `conversation.pin` | 会话 | 写（可逆） | CONFIRM |
| `contact.search` | 联系人 | **只读** | READ |
| `contact.brief` | 联系人 | **只读**（含标签分类/置信度与记忆处理状态） | READ |
| `contact.refresh_memory` | 联系人 | 写（触发 AI 重算，**只入队**，等处理窗口） | CONFIRM |
| `contact.update_remark` | 联系人 | 写 | CONFIRM |
| `contact.update_profile` | 联系人 | 写 | CONFIRM |
| `contact.set_tags` | 联系人 | 写（整体替换） | CONFIRM |
| `contact.mark_read` | 联系人 | 写（可逆） | CONFIRM |
| `contact.timeline` | 联系人 | **只读**（不含正文，但产出 message 候选） | READ |
| `contact.topics_read` | 话题 | **只读** | READ |
| `contact.topics_retry` | 话题 | 写（触发 AI 计算） | CONFIRM |
| `contact.topic_update` | 话题 | 写 | CONFIRM |
| `contact.topics_merge` | 话题 | 写（合并，破坏性） | CONFIRM |
| `message.read` | 消息 | **只读**（**含原文**） | READ |
| `wecom.summary_read` | 企微 | **只读**（AI 摘要） | READ |
| `message.send_email` | 消息 | 写（**对外发信，不可撤回**） | CONFIRM |
| `message.send_chatapp` | 消息 | 写（**对外发信**，只入队，不可撤回） | CONFIRM |

- `READ_ONLY_ALLOWLIST` = **7 项**；`AUTO_EXECUTE_ALLOWLIST` = {`todo.create`}。
- 候选窗口：联系人 `LIMIT=20`、会话 `LIMIT=20`、待办 70、话题 30、**消息 20（`MessageCandidates`，无预置窗口）**。
- 5 组候选里只有 3 组有预置窗口（待办 / 会话 / 联系人）；**话题与消息的窗口只能由只读工具产生** ——
  「先查再改」「先查再读」在这两域是结构上强制的，不是提示词里的一句提醒。

---

## 3. 清单

### A. 联系人域（`contact.*`）

| # | 工具 | 用户说的话 | 底层能力 | 状态 |
|---|---|---|---|---|
| A1 | `contact.update_remark` | 「把老王的备注改成…」 | `ContactGroupService.updateRemark(:236)` + `POST /api/contacts/{id}/remark` | ✅ 已实现 |
| A2 | `contact.update_profile` | 「他显示名/职务写错了」 | `updateProfile(:290)` + `POST /{id}/profile` | ✅ 已实现 |
| A3 | `contact.set_tags` | 「给他打个标签」 | `updateTags(:246)` + `PUT /{id}/tags` | ✅ 已实现（**整体替换**语义，工具描述里明写） |
| A4 | `contact.mark_read` | 「把和他的消息标记已读」 | `ContactService.markAsRead(:280)` + `POST /{id}/mark-read` | ✅ 已实现 |
| A5 | `contact.timeline` | 「他最近都发生了什么」 | `GET /api/v1/contacts/{cid}/timeline`（`CallRecordController:82`） | ✅ 已实现（**已剥掉 `text`**） |
| A6 | `contact.memory_read` | 「他的画像/标签是什么」 | `ContactMemoryQueryService.findForOwner(:42)` | ✅ 已实现 —— **不新增工具**，把标签分类/置信度与记忆处理状态补进 `contact.brief`（§9.3） |
| A7 | `contact.refresh_memory` | **「重算他的 AI 画像 / AI 标签」** | 新增 `ContactMemoryRecomputeService.requestRecompute` + `ContactMemoryStateMapper.markDirtyForRecompute`（判据与自动路径 `markStaleDirty` 同源） | ✅ 已实现（§9） |
| A8 | `contact.merge` | 「这两个是同一个人，合并」 | `merge(:120)` + `POST /api/contact-groups/merge` | 🟢 但**高危** |
| A9 | `contact.split` | 「拆出一个人」 | `split(:161)` + `/split` | 🟢 但**高危** |
| A10 | `contact.delete` | 「删掉这个人」 | **无此能力**（`ContactService` 里 delete/remove/软删 0 命中） | 🔴 |

> A8/A9 是**不可逆的归属变更**（A9 还会重建 contact + identity），不是普通写。建议**不进助手**，留在人工后台；若要做，必须走确认卡片 + 明示「不可逆」。

### B. AI 画像 / 话题域（`contact.topics*`）

`AiTopicService` 是本项目**最完整的一套「AI 计算 + 人工复核」链路**，而且**已经有手动重算入口** —— 这是 A7 可以照抄的范式。

| # | 工具 | 用户说的话 | 底层能力 | 状态 |
|---|---|---|---|---|
| B1 | `contact.topics_read` | 「他最近聊了哪些话题」 | `getTopics(:145)` + `GET /contacts/{cid}/topics` | ✅ 已实现（产出 `TopicCandidates` 窗口） |
| B2 | `contact.topics_retry` | **「重新生成他的 AI 话题」** | `retryGeneration(:370)` + `POST /contacts/{cid}/topics/retry` | ✅ 已实现 |
| B3 | `contact.topic_update` | 「把这条话题标题改了」 | `updateTopic(:252)` + `PATCH /topics/{id}` | ✅ 已实现（**先读后写**，见 §7.1） |
| B4 | `contact.topics_merge` | 「这两条话题是同一件事」 | `mergeTopics(:282)` + `POST /topics/merge` | ✅ 已实现（**一组引用**：前置是扩解析器 + 命名约定，见 §7.2） |
| B5 | `contact.topic_review_apply` | 「按你给的复核结果应用」 | `applyManualReview(:989)` + `/topic-review/{pid}/apply` | 🟢 |
| B6 | `wecom.group.topics_retry` | 「重算这个群的话题」 | `retryGroupGeneration(:215)` + `/wecom/groups/{sid}/topics/retry` | 🟢 |
| B7 | `contact.topics_store` | 「把这条话题归档」 | `submitStore(:604)` / `storePending(:188)` | 🟢 |

> **B 域是全清单里性价比最高的一组** —— 既有手动入口，又是用户明确点名的诉求，且不碰任何边界决策。

### C. 会话 / 消息域

| # | 工具 | 用户说的话 | 底层能力 | 状态 |
|---|---|---|---|---|
| C1 | `conversation.hide` | 「这个会话我不想看了」 | `POST /api/conversations/preferences/delete`（**隐藏，非真删**） | 🟢 |
| C2 | `conversation.restore` | 「恢复刚才隐藏的」 | `/preferences/restore` | 🟢 |
| C3 | `conversation.reorder` | 「把它排到前面」 | `/preferences/order` | 🟢 可选 |
| C4 | `message.read` | 「把那条消息调出来」 | `GET /api/messages/{id}` | ✅ 已实现（**唯一故意回原文的只读工具**，口径见 §7.4） |
| C5 | `message.send_email` | **「替我回一封邮件」** | `EmailSendService.send(...)` + `POST /api/email/send`（真 SMTP） | ✅ 已实现（**无 `to` 参数**，地址服务端解析，见 §7.5） |
| C6 | `message.send_chatapp` | 「在 chatapp 里发给他」 | `POST /api/send/chatapp` | ✅ 已实现（同 C5 口径） |
| C7 | `message.search` | 「翻一下和他在邮件/电话里说过什么」 | 四渠道都进同一张 `messages`，**但助手侧无消息检索工具** | 🟡 |
| C8 | `contact.thread_summary` | **「总结我们和他在邮件/电话/chatapp/企微的往来」** | **不存在**；且要一次搬进 N 条原文 ⇒ 撞只读档第 3 条判据（见 §7.4） | 🔴 |

> C5/C6 是**助手第一次能对外产生不可撤回的副作用**（发出去了收不回）。规格已按上面那三条落地；
> 额外补了最重要的一条：**收件地址不做成参数**（能给的只有「发给哪个联系人」）—— 见 §7.5。
> C7 是 C8 的前置：连"取到内容"这步都还没有。

### D. 待办域（`todo.*`）—— 已齐

4 个工具全在。**但有一个结构性缺口**：

| # | 工具 | 用户说的话 | 现状 |
|---|---|---|---|
| D1 | `todo.create` 关联联系人 | 「提醒我后天跟进老王」 | **`TodoItemEntity` 里没有 `contactId`**（0 命中）⇒ 待办挂不到人身上 |

> D1 不是加工具能解决的 —— 要加列 + 迁移 + 列表展示。这是**产品级缺口**，是这轮盘点里最值得单独拎出来的一条。

### E. 通话域（`call.*`）

| # | 工具 | 用户说的话 | 底层能力 | 状态 |
|---|---|---|---|---|
| E1 | `call.retry_transcript` | 「这段转写重跑一遍」 | `POST /api/v1/call-records/{id}/retry` | 🟢 |
| E2 | `call.update_transcript` | 「这句转错了，改掉」 | `PATCH /call-records/{id}/transcript` | 🟢 |
| E3 | `call.update_note` | 「给这通电话加个备注」 | `PATCH /call-records/{id}/note` | 🟢 |
| E4 | `call.read` | 「那通电话说了什么」 | `GET /call-records/{id}`（含转写） | 🟢 只读（注意：转写 = 原文 ⇒ 口径同 C4，见 §7.4） |

### F. 企业微信域（`wecom.*`）

| # | 工具 | 用户说的话 | 底层能力 | 状态 |
|---|---|---|---|---|
| F1 | `wecom.summary_read` | 「那个群昨天聊了啥」 | `GET /api/v1/wecom/message-summaries`（**已有 AI 摘要**） | ✅ 已实现（原标 🟢 是标错的、实为 🟡：已补 owner 过滤读路径，见 §7.3） |
| F2 | `wecom.summary_retry` | 「重新总结一下」 | 只有 `Worker.runOnce(now)` / `Backfill.runOnce(...)`（定时/批），**无手动入口** | 🟡 |

> F1 的**摘要**（而非原文）正是 C8 该走的形态（见 §6）—— 但它的**读取路径本身**要先补用户隔离。

### G. 邮件域（`email.*`）

| # | 工具 | 现状 |
|---|---|---|
| G1 | 邮件检索 | 🔴 `EmailController` **只有 `send` / `sync`**，没有 list/search ⇒ 邮件正文要从 `messages` 表侧读（即 C7） |
| G2 | 邮件总结 | 🔴 不存在 |

### H. 账号与设置 —— **建议明确排除**

`AccountController`（改密/头像）、`ChannelSettingsController`（渠道账号凭据）、所有 `Admin*Controller`（跨用户操作）、`WhatsAppTemplate*`（模板管理）。
理由见 §4。

---

## 4. 刻意不做的（不是遗漏，是边界）

| 不做 | 理由 |
|---|---|
| 表级 CRUD（`contacts` 每个字段一个工具） | 工具面 = 模型提示词面。数量上升 ⇒ 选错率上升，而每次误选都可能是写操作。**要「一个意图一个工具」** |
| 渠道账号凭据读写（`ChannelSettingsController` 的 credentials 端点） | 凭据是 secrets，不该进模型上下文 |
| 所有 `Admin*` 端点 | 语义是跨用户/运维，助手的身份隔离是「只看自己的」（`where user_id`），塞进来会破坏这个不变量 |
| WhatsApp 模板管理 | 管理员专用，与「个人助手」定位不符 |
| 真删（联系人 / 消息 / 会话） | 前两者**本来就没有能力**；后者是隐藏语义。要做先出「可删清单 + 软删优先」 |
| `contact.merge` / `contact.split`（A8/A9） | 不可逆的归属变更，留在人工后台 |

> **这条不变量已经拦下一次误判**：F1 的端点没有 `where user_id`，若照原清单当 🟢 接进来，等于给所有用户开了对方的群摘要读取 —— 理由与拒 `Admin*` 完全一致。清单里的「🟢」只表示「service 方法已存在」，**不表示「可以安全地暴露给助手」**。F1 最终是按这条结论**补了一条 owner 过滤读路径**（§7.3），而不是把端点直接接上。

---

## 5. 建议批次

| 批次 | 内容 | 前置 |
|---|---|---|
| **P0** | ~~A1 A2 A3 A4 A5 + B1 B2 B3~~ ✅ 已完成；~~B4 / F1 / C4~~ ✅ 已完成（§7.2 / §7.3 / §7.4） | 三项各有一处前置：B4 扩解析器、F1 补 owner 过滤读路径、C4 口径拍板 —— **没有一项是「只加声明」** |
| **P1** | A7（画像重算，先补 `ContactMemory*` 手动入口）+ F2（企微摘要重算入口）+ E1 E2 E3 + C1 C2 | A7/F2 需要各补一个薄触发入口（照抄 `AiTopicService.retryGeneration` 的形状） |
| **P1** | ~~C5 C6（对外发信）~~ ✅ 已完成（§7.5） | 前置 = 定「外向副作用的确认规格」；**实际还要收口依赖层次** |
| **P2** | C7（消息检索） | C8 的前置 |
| **需求级** | C8（跨渠道总结：按新判据，一次性搬进 N 条原文**不算**「用户点名的那一份」，见 §7.4）+ D1（待办挂联系人，要迁移）+ A10（联系人删除，要设计软删） | 都要产品决策 |

**实施顺序上的一个硬约束**：P0 里每加一个工具，都要同步跑 `AssistantPolicySelfCheck`（启动自检）—— 它会在启动时拦「只读清单里写了未注册的名字」这类不一致。别等启动失败才发现。

> **好消息**：工具 bean、`ToolRegistry`、`AssistantPolicySelfCheck` 都是**无条件装配**的，只有编排链挂 `@ConditionalOnAssistantEnabled` ⇒ **验证自检不需要 AI 凭据，普通 dev 启动即可**，看两行日志（`已注册 N 个助手工具…` / `助手策略自检通过：只读 a 个、免确认 b 个`）。

---

## 6. 你没提、但盘点后应该补的

按「值不值得做」排序：

1. **`contact.timeline`（A5）** —— 「他最近怎么了」是 CRM 里最高频的一句话，而底层端点**已经存在**。不加这个工具，助手永远答不出这类问题。 ✅ 已做
2. **`contact.topics_retry`（B2）** —— 你已经点了「触发 AI 标签」，而话题域**已经有手动重算入口**，是全场最便宜的 AI 触发类工具。 ✅ 已做
3. **`message.send_email` / `send_chatapp`（C5/C6）** —— ✅ 已做（§7.5）。能力全在，不做的后果是助手能读懂一切、却一封回信都发不出去。
4. **待办挂联系人（D1）** —— 「提醒我跟进老王」现在只能存成一条没有主语的待办。**要加列，不是加工具**。
5. **会话隐藏/恢复（C1/C2）替代「删除」** —— 项目里**本来就没有真删**（会话是 `preferences/delete` = 隐藏）。把「隐藏」做成工具，既满足「删掉有的没的」的体感，又不引入新的破坏性面。
6. **`wecom.summary_read`（F1）** —— 唯一现成的内容总结能力。✅ 已做；前置确实是**补用户隔离**（§7.3），不是加个声明就完事。
7. **通话域三件套（E1–E3）** —— 转写纠错是人工高频操作，现在只有人在页面上点。
8. **`contact.memory_read`（A6）** —— 需先和 `contact.brief` 比对是否重复，避免两个工具答同一句话（工具面冗余会直接抬高模型选错率）。

---

## 7. P0 实施记录（2026-09-23）

**验收证据**（两轮，都在隔离副本 `/tmp/mc-f1c4` 里跑）：

- **第一轮**（A1–A5 / B1–B3）：`mvn -o test -Dtest='…assistant.**.*Test'` ⇒ **284 例 / 0 失败**；定向启动 ⇒ `已注册 16 个助手工具：…`、`助手策略自检通过：只读 5 个、免确认 1 个`、`Started App in 3.088 seconds`。
- **第二轮**（B4 / F1 / C4，见 §7.2–§7.4）：① 改动面 10 个测试类定向回归 ⇒ **123 例 / 0 失败**（含 `WeComModuleIsolationTest` 的模块门禁 5 例）；② 全量 `mvn -o test` ⇒ **1818 例 / 0 失败 / 1 error / 2 skipped**，唯一那条 error 是**副本假象** —— `UserChannelOwnerBackfillSchemaContractTest.verificationScriptOnlyReportsAggregatesAndDigests` 找不到 `../scripts/verify-user-channel-owners.sql`（真实工作树里有该文件，只 rsync `backend/` 的副本里没有）；③ 定向启动 ⇒ `已注册 19 个助手工具`（含 `message.read` / `wecom.summary_read`）、`助手策略自检通过：只读 7 个、免确认 1 个`、`Started App in 2.838 seconds`。

**新增代码**（第一轮）：`TopicCandidates`（第 4 组候选）、`ContactWriteAssistantTools`（A1–A4）、`ContactTimelineAssistantTools`（A5）、`AiTopicAssistantTools`（B1–B3）；改 `AssistantActionPolicy` / `AssistantPendingActionService` / `ToolInputValidator` / `AiTopicService`。

**新增代码**（第二轮）：`MessageCandidates`（第 5 组候选）、`MessageAssistantTools`（C4）、`WeComAssistantTools`（F1）、`WeComSummaryReadService`（F1 的 owner 过滤读路径）；改 `ContactTimelineAssistantTools`（产出 message 候选 + 回填 `messageRef`）/ `AssistantActionPolicy` / `AssistantDecisionParser`（数组型引用逐元素比对）/ `ToolRegistry`（`Ids` / `Refs` 后缀）/ `ToolInputValidator`（`minimum` / `maximum`）/ `ToolExecutionException`（`UNAVAILABLE`）。

**一条值得单独记的经验**：这 11 项里凡是「service 方法已存在」的都做得很快，**卡住的都是清单上看不出来的东西** —— B4 卡在既有防护对数组静默失效、F1 卡在缺 `where user_id`、C4 卡在没人敢拍的决策。所以「加一个工具」的成本从来不在声明那几十行。

### 7.1 两个防退化设计（顺手补的，不是清单要求的）

- **`array` 入参校验**：`contact.set_tags` 需要「一组自由文本」。原校验器只支持 `string|boolean|integer` ⇒ 已扩 `array`，且**只允许 `items.type=string`**（`[{"name":"x"}]` 也过得了外层检查，但会在服务层变成与用户输入无关的 500）、带 `maxItems` + **元素级 `maxLength`**（外层 `maxLength` 对数组没有意义，而元素才是自由文本）。
- **`contact.topic_update` 先读后写**：`updateEmployee` 的 SQL **无条件写 `confirmed_summary`** ⇒ 只改标题会把人工确认过的摘要**静默清空**。为此给 `AiTopicService` 加了 `getTopic(userId, topicId)`，工具先读当前值回填，并用 `current.version()` 当 `expectedVersion`（不让模型承担乐观锁）。测试 `changingOnlyTheTitleKeepsTheExistingSummary` 钉住。
- **`contact.timeline` 剥掉 `text`**：时间线的 payload 带消息正文，剥成白名单字段（`type/direction/durationSeconds/state/attempts/status`）。**这条没有被 C4 的口径变更推翻** —— 20 段正文不构成「用户点名的那一份」，所以它现在**仍然**剥（理由见 §7.4）。测试写法是**先证明夹具确实带 `text`、再断言输出没有 `text`** —— 否则夹具一改，边界断言会静默变成空断言。

### 7.2 B4 `contact.topics_merge` —— 卡在「一组引用」（已修）

它要的是 `List<UUID> topicIds`（2..20 条），而当时**候选比对路径只认标量**，两道路径同时对数组静默失效：

1. `AssistantDecisionParser` 是 `if (!(value instanceof String reference)) continue` ⇒ 绑定了候选的字段若值是 `List`，**整个比对被跳过**。声明了绑定却不生效，是最坏的一种假保证（测试断言 `referenceBindings()` 也会通过）。
2. `ToolRegistry.isReferenceShaped` 只认 `Id` / `Ref` **后缀** ⇒ 参数取名 `topicRefs` 时**连启动自检都不触发**。

**修法（两处都不许只判外层）**：

- 解析器抽出 `referencesOf(Object)`：标量走原路，`List` 则**逐元素**与候选比对（`AssistantDecisionParser:226-284`）。非字符串元素在这里**忽略**而不是报错 —— `ToolInputValidator` 已在更前面按 `items.type=string` 逐元素拒过，再报一次会把「参数不合法」与「引用不在候选里」混成一类，而两者处置完全不同。
- `isReferenceShaped` 补上复数后缀（`Ids` / `Refs`），自检的报错文案也改成四后缀并列 —— **少认一个后缀，就等于给最危险的那类参数开一条静默通道**。
- 定下命名约定：`*Ref` / `*Id` 单数、`*Refs` / `*Ids` 复数 = 一组引用（写进 `ToolRegistry.isReferenceShaped` 的注释：改它等于改约定，必须同时改所有工具声明）。

另外 `mergeTopics` 仍依赖 ai-topic 的 LLM 网关（`TOPIC_FUSION_UNAVAILABLE` → 「话题合并服务暂时不可用」，`AiTopicAssistantTools:412`），并要求**逐条** `expectedVersions`（缺失即 `TOPIC_MERGE_INVALID`）：工具在执行前逐条读一次取 `version`，与 B3 同一直觉 —— 不让模型背版本号。

### 7.3 F1 `wecom.summary_read` —— 原标注 🟢 是错的（已修）

现状读链路是 `WeComMessageSummaryController` → `repository.find(PageQuery(installationId,…))` → `mapper.search(installationId,…)`，**全链路没有一处 `where user_id`**；`installationId` 来自部署常量（`config.wecomSuiteId()` / `config.wecomLoginAuthCorpId()`），响应里还带 `rawRequestJson` / `rawResponseJson`。它的语义是「本部署内的全部摘要」——放在页面上是对的（本来就只给运维看），直接接进助手就破坏「只看自己的」不变量（与 §4 拒 `Admin*` 是同一条理由）。

**修法不是「加个声明」，而是补一条真·owner 过滤的读路径** `WeComSummaryReadService`：

- 授权判定**复用 `ConversationMapper.findAccessibleWeComGroup`**，刻意不新写第二份 SQL：企微可见性（绑定了这个企业 + 是这个群的已观测成员 + 群所属会话的归属规则）已经在那一处表达完整，而第二份实现必然与第一份漂移 —— 漂移的表现是「页面上看不到、助手里看得到」或反过来，**两种都不会有测试失败**。
- **`installationId` 只从授权结果里取**：这是与旧链路唯一但最关键的差别，附带好处是跨部署的摘要在查询条件里就被排除，不需要额外再判一次。
- 只回自己的 `Summary(occurredAt, summary)` 投影，**不回 `JobView`**（它带 `rawRequestJson` / `rawResponseJson`）。类型不同是刻意的：让「顺手把整个 JobView 塞进 data」这种写法连编译都过不了。
- **空结果必须分两种**：先查 `COMPLETED`；一条可读的都没有时再做一次很便宜的 `status=null` 探测 ⇒ `hasAnyJob` 区分「还在生成 / 生成失败」（对用户是同一句话：再等等）与「这段时间确实没有可总结的内容」。这两句回答完全不同，而只有空列表时模型只能瞎猜其中之一；截断也按「查询命中数 > 本次取回数」判并必须说出来。
- 引用复用 `ConversationCandidates` 的 `WECOM_GROUP:<uuid>`，**不新建候选组**（形状相同的第二组只会制造错配，而错配在提问路径就断了）；代价是工具要自己拒掉 `CONTACT:` 那一半候选，措辞里要说清「该用哪一种」。
- `days` 的上下界（1–30）写在 schema 的 `minimum` / `maximum` 上，且与 `WeComSummaryReadService.MIN_DAYS` / `MAX_DAYS` **同源**（两边各写一个 30，调大一侧就会得到一个「schema 说可以、服务层说不合法」的假配置）。这顺手补掉了校验器**静默忽略 `minimum` / `maximum`** 的缺口 —— 在那之前，写着 `maximum: 30` 的 schema 等于没有约束。

**顺手踩到的一道门禁**：`WeComModuleIsolationTest` 会扫 `service.wecom` 并断言每个 Spring Bean 都带 `@ConditionalOnWeComEnabled`。本类最初只靠「注入 `ObjectProvider`、装配不到就当没启用」兜底，被这道门禁**正确地**拒了 —— 那等于把模块边界降级成运行期分支，且每多一条调用路径就要重写一遍这段判断。改为**服务本身挂模块条件**（模块级事实用模块级注解），`ObjectProvider` 只留给「模块开了但没配 suite-id」（`MyBatisWeComMessageSummaryRepository` 的 `@ConditionalOnExpression`，配置级）这层真实存在的中间态；工具侧同样改用 `ObjectProvider<WeComSummaryReadService>`，因为**工具自己必须始终在册** —— `tools/list` 的形状要稳定，且只读清单里写着它的名字，工具缺席会让启动自检当场失败。

### 7.4 C4 `message.read` —— 撞待拍板决策（已拍板：允许原文进上下文）

它返回的是**消息正文**，即「原文」。`2026-09-23-ai-operable-surface-tools-and-mcp.md:128` 已写明「原文出不出边界」是**产品/合规决策，不是技术问题**。⇒ 不由实现方代决。

**2026-09-23 用户拍板：允许原文进上下文。** 于是只读档的第 3 条判据从「有没有原文」改成「**这份原文是不是用户点名要的那一份**」：

- **成立**：`message.read` 只回**一条**，且那条是模型从上一轮 `contact.timeline` 的 `messageRef` 里挑的 —— 也就是用户点名的那个对象。
- **不成立**：`contact.timeline` 一次 20 条，20 段正文不是「用户点名的那一份」，所以时间线仍然**剥掉 `text`**；C8（跨渠道总结）同理仍被挡住。
- 所以这次改的是**判据本身，不是删掉判据**：那种「既然允许原文了，把 20 条也顺手带上」的实现仍然该被拒。

**实现上没有新增读路径**：`MessageQueryService.getMessage(id, userId)` 本来就是 owner-scoped（`findByIdAndOwner` + 企微回退里的 `conversationAccessService.requireAccessible`），缺的只是候选组（`MessageCandidates`，第 5 组、**无预置窗口**）+ 声明 + 策略归类。配套改动是让 `ContactTimelineAssistantTools` 开始产出 `message` 候选、并给每条消息回填 `messageRef` —— **不产出候选，`message.read` 就没有合法入参**（「先查再读」在这两域是结构上强制的，不是提示词里的一句提醒）。

data 的形状也是这次口径的一部分：`messageText` 来自正文；`bodyHtml` **只在没有纯文本时**才带（HTML 是渲染产物，两者同时带会让一段真实内容被样式挤到 4000 字符截断线之外，而截断之后模型看到的是半段 HTML）；附件只给元数据（`fileName` / `mimeType` / `sizeBytes`，**不给 id、也不给下载地址**）—— 原文出边界不等于把附件内容与取件动作也交给模型。

---

## 附录：本轮盘点的三条否定性结论（反直觉，容易记错）

1. **联系人没有删除能力** —— `ContactService` 里 `delete|remove|deleted_at` 0 命中。合并时用的是 `setStatus("merged")`。所以「删联系人」是**新建**，不是暴露。
2. **邮件没有查询端点** —— `EmailController` 只有 `send` / `sync`。邮件内容要从 `messages` 表侧读。
3. **待办不关联联系人** —— `TodoItemEntity` 无 `contactId`。「提醒我跟进某人」目前无法表达。

以及一处**已修**的过时注释：`AssistantActionPolicy.java:97` 原写「当前四个」只读工具（实际当时是 3 项），现已改为与新 7 项清单一致。

---

## 8. §7.5 —— C5 / C6 对外发送（2026-09-23 落地）

**需求原话**：「可以但是需要明确确认之后才能发，比如，agent 给出要发的信件，但是还需询问是否发送。」

### 8.1 落成的规格（四条，缺一条就算没做完）

1. **必须确认**：两个工具都不进任何白名单 ⇒ `AssistantActionPolicy` 一律判 CONFIRM。
   权威在**策略类**，不在 `annotations` —— MCP 注解只是给模型的提示，客户端不得据此做安全判断。
   所以用例是**直接问策略要答案**，而不是断言 `destructiveHint=true`（那只能证明声明写得对）。
2. **确认卡片显示完整收件人与完整正文**（不是摘要）。发送不可撤回，卡片是「用户到底同意了什么」的唯一载体。
   `AssistantPendingActionService.SUMMARY_MAX = 4000`，配新增的 `CARD_NAME_MAX = 100` 保证最坏情况装得下
   （用例用长度算术钉住：姓名 100 + 地址 255 + 主题 300 + 正文 3000 + 固定文案）。
   没有地址时卡片**明说「现在发不出去」**，不装作正常。
3. **收件地址不是参数 —— 本批最重要的一条**。两个工具的 schema 只有 `contactRef`（绑 `ContactCandidates`）
   + 内容参数（`subject`/`body`，chatapp 只有 `text`）。地址由
   `OutboundMessageService.resolve(userId, contactId, channelType)` 从 `contact_identities` 解析
   （owner 谓词在 SQL 里，`findByContactIdAndOwner`）。
   「让模型给地址再校验」只能挡住格式不对的；**格式合法、却属于别人的地址是拦不住的**，而发出去收不回来。
   用例断的是**参数 key 集合** —— 有人「顺手加个 `to` 方便调试」会立刻红。
4. **失败要给出可执行的下一步**（`OutboundException` → `ToolExecutionException`）：
   `RECIPIENT_MISSING` → `INVALID_ARGUMENT`（去补档案；报成 `INTERNAL` 会让模型换个收件人重试，而问题不在收件人）／
   `CHANNEL_UNAVAILABLE` → `UNAVAILABLE`（找管理员，与「你没权限」区分开）／
   `OUTCOME_UNKNOWN` → **专用码 `SEND_OUTCOME_UNKNOWN`**（唯一一个「重试 = 第二次真实投递」的失败，回话明写「不要重发」）。

另外两条边角但重要的决定：

- **chatapp 的 `clientRequestId` 由服务端生成**，刻意不做参数：它是出站幂等键
  （`(channelAccountId, clientRequestId)` 去重）。让模型决定，它就可能（哪怕凑巧）交出一个用过的值，
  而那条路的去重是**静默成功**的 —— 调用方收到看起来正常的 `duplicate=true`，消息一条都没发出去。
- **accept ≠ delivered**：入队只是入队，回话只能说「已提交」，`status=pending` 原样进 `data` 供下一轮引用。

### 8.2 这次撞的门禁（清单里没写、但比代码更重要的产出）

第一版把「解析收件人 + 转发到渠道」写在 `service.message`，`ArchitectureBoundaryTest` 当场报 **14 条越界**
（构造器带 `EmailSendService`、返回 `EmailSendService.SendResult`、读 `EmailException.code()`）。
门禁的理由写得很明确：业务域应经由 `service.channel` 编排层访问渠道，**不要为了过测试加白名单**。

处置：搬进 `service.channel`（本就是为「协调渠道」而设的板块），并把渠道类型的回执映射成
`OutboundMessageService.EmailReceipt` / `ChatAppReceipt` ⇒ 助手侧不再 import `channel.*`，
也不再 import `service.chatapp.outbox.*`。**白名单一条没加。**
已回写 `docs/代码板块地图.md` 板块 12 与 §4.2。

> 这条比两个工具本身更值得记住：**清单上的 🟢 只保证「能力在」，不保证「它落在对的那一层」。**
> 门禁拦下新代码时，先问「它该住在哪一层」，而不是「它算不算例外」。

### 8.3 验收证据（隔离副本 `/tmp/mc-f1c4`）

- 定向回归 10 个类 ⇒ **155 例 / 0 失败**（含 `ArchitectureBoundaryTest` 1 例、
  `MessageSendAssistantToolsTest` 13 例、`OutboundMessageServiceTest` 13 例、`AssistantPendingActionServiceTest` 25 例）。
- 全量 `mvn -o test` ⇒ **1868 例 / 0 failure / 1 error / 2 skipped**，唯一那条 error 仍是
  `UserChannelOwnerBackfillSchemaContractTest` 的**副本假象**（副本缺 `../scripts/`）。
- 定向启动（8110）⇒ `已注册 21 个助手工具：… message.send_email, message.send_chatapp …`、
  `助手策略自检通过：只读 7 个、免确认 1 个`、`Started App in 3.204 seconds`。

### 8.4 新增 / 改动

**新增**：`service/channel/OutboundMessageService`（含 `Recipient` / `EmailReceipt` / `ChatAppReceipt`）、
`service/channel/OutboundException`（失败码词表）、`service/assistant/mcp/MessageSendAssistantTools`。
**改动**：`ToolExecutionException`（+ `SEND_OUTCOME_UNKNOWN`）、`AssistantPendingActionService`
（卡片两个分支 + `CARD_NAME_MAX` + 向 card/summarise 透传 `userId`）。

### 8.5 一条坑

把测试里的夹具类型做**全局替换**时，**stub 渠道 mock 的那两处必须留渠道类型**：
`when(emails.send(...))` 的返回值是 `EmailSendService.SendResult`（渠道的），
只有 `service.sendEmail(...)` 的返回类型才是 `EmailReceipt`（service 的）。
一刀切会让编译当场红 —— 这次就红了，反而是好事：编译期把「映射语义」逼明白了。

---

## 9. §8 之后 —— A6 / A7 联系人记忆（2026-09-23 落地）

**需求原话**：「AI标签呢？」→「标签和画像还是不能让他帮我做。」

盘点下来这不是「差个工具」，而是一处**能力面的不对称**：助手能**读**这个联系人的画像与标签
（A6，`contact.brief` 已覆盖），却**做不了任何事** —— 全项目没有第二处手动触发记忆重算的入口
（`ContactMemoryController` 只有 `GET /api/contacts/{id}/memory`；前端也没有重试按钮，已实测）。

### 9.1 三个口径（都是拍板过的，不是实现细节）

| 问题 | 拍板 | 为什么不是另一种 |
|---|---|---|
| 「重算」指什么 | **催未处理的**：只把还没折进画像的入站消息处理掉 | 全量重读历史会把同一条消息当新证据重复累积（置信度虚高、画像反复改写）—— 那是另一件要单独拍板的事 |
| 提交后何时生效 | **只入队**，等处理窗口（同 `contact.topics_retry`） | 在工具里同步跑一轮会调外部模型、耗时以十秒计，还要绕过 `ContactMemoryScheduler` 的处理窗口（默认每天 00:00–00:10）—— 那是改运行面，不是加工具 |
| 顺带补读 | **补进 `contact.brief`**（标签分类/置信度 + 处理状态），不新增读工具 | 多一个读工具 = 多一条身份防线要守；而这两个字段本来就在同一条 owner 闸门后面 |

### 9.2 落成的四件事

1. **手动入口的判据写在 SQL 里，不写在 Java 里。**
   `ContactMemoryStateMapper.markDirtyForRecompute(owner, contact, now)` 与自动路径 `markStaleDirty`
   **同一条判据**（`last_inbound_at > split_part(last_success_cursor,'|',1)::timestamptz`），
   只多两处限定：只针对单个联系人、额外放行 `FAILED`（自动路径刻意不重试终态失败，
   「人主动要求再来一次」正是它的存在理由）。
   ⇒ 「什么叫有东西可算」只有一份。`theManualPathSharesTheSameJudgeAsTheAutomaticPath`
   把两份 SQL 对钉，**另一侧改了就红** —— 这是刻意的绊线，不是重复断言。
2. **`last_inbound_at` 只能由 SQL 自己算，调用方连传都传不进来。**
   写成 `now` 会踩一个**静默死循环**：`complete()` 里 `last_inbound_at > processedAt ⇒ 状态回到 DIRTY`，
   于是每处理完一轮立刻又变脏 —— 每轮白烧一次 LLM 并把画像反复改写，而日志与状态看起来全正常。
   把参数从签名里去掉，这个错误就**写不出来**。三重钉住：`doesNotContain("#{inboundAt}")`
   + **对照组**（`markDirty` 确实含 `#{inboundAt}`，防「顺手复用」）+ 真库断言
   `lastInboundAt == 消息表的 received_at`。
3. **没有新内容 ⇒ 一个字节都不写。** `NOTHING_NEW` 时状态**保持 CLEAN 不动** ——
   改成 DIRTY 会让 worker 每天空跑一轮。回话因此能说真话：
   「没有新的往来内容，这次重算不会产生任何变化（没有做任何改动）」。
4. **回话不许承诺「已更新」。** 四条结果对应四句不同的话（`SUBMITTED` / `ALREADY_PENDING` /
   `IN_PROGRESS` / `NOTHING_NEW`），措辞都刻意避开「已更新」：入队 ≠ 生效（同 §8 的 accept ≠ delivered）。
   确认卡片写「由后台重算，提交后不会立刻生效」—— 那不是客气话，
   是防止卡片被读成「点完就好了」。用例对 `SUBMITTED` 同时断言 `contains("已提交")` 与
   `doesNotContain("已经更新")`。

### 9.3 「他的标签为什么没更新」现在有答案了

补进 `contact.brief` 的是 `memoryState` / `memoryFailureCode` 与 AI 标签的 `category` / `confidence`。
两条取数路径都是**结构性安全**的，不靠「记得别取」：

- 内容仍走 `ContactMemoryMapper.listStableContext`（结构上没有原文与转写可泄漏）；
- 状态走 `ContactMemoryStateMapper.findByOwnerAndContact` —— `contact_memory_states` 是**纯元数据表**，
  没有任何内容字段，也没有消息外键可 join。

`memoryVisible=false` 时状态**刻意留空**：别人名下那条流水线的状态，对当前用户既无用也不该给。

`appendMemoryState` 那句话说在**两个出口**上（有内容 / 一条都没有），因为「一条都没有 + FAILED」
正是最需要解释的组合 —— 只回「暂无画像」会让用户以为这个人本来就没什么可提炼，
而真相是提炼失败过：一个无解、一个能催，用户据此要做的决定完全不同。
`CLEAN` 不加话（最常见的状态，加了就是噪声）。失败**码**只进 `data`，不进面向用户的句子 ——
`briefNeverReadsTheFailureCodeOutLoud` 钉住这条（内部标识不许念给用户听）。

### 9.4 验收证据（隔离副本 `/tmp/mc-f1c4`）

- **定向 5 个类 ⇒ 55 例 / 0 失败**：`ContactMemoryStateMapperSqlTest` 5、`ContactMemoryAssistantToolsTest` 10、
  `ContactAssistantToolsTest` 16、`ContactBriefProviderTest` 16、`ContactMemoryRecomputeServiceTest` 8。
- **相邻守卫 10 个类 ⇒ 100 例 / 0 失败**：含 `ArchitectureBoundaryTest` 1、`AssistantPendingActionServiceTest` 25、
  `ToolRegistryTest` 22、`ToolInputValidatorTest` 14、`MessageSendAssistantToolsTest` 13、`AssistantActionPolicyTest` 11、
  `ContactMemoryControllerTest` 4、`ContactMemoryWorkerTest` 8、`ContactMemorySchedulerTest` 2。
- **真库 E2E** `ContactMemoryEndToEndTest` ⇒ 5 例 / 1 失败，那条失败**不是本批的**（见 §9.5）；
  本批新增的 `manualRecomputeMarksDirtyOnlyWhileSomethingIsStillWaitingToBeProcessed` 通过。
- **全量 `mvn -o test` ⇒ 1898 例 / 1 failure / 1 error / 2 skipped**（上轮 1868，本轮 +30）。
  1 error 仍是 `UserChannelOwnerBackfillSchemaContractTest` 的**副本假象**（副本缺 `../scripts/`）；
  1 failure 是 §9.5 那条别的任务的 Gate 0 红灯。
- **定向启动（8110）⇒ `已注册 22 个助手工具：… contact.refresh_memory …`、
  `助手策略自检通过：只读 7 个、免确认 1 个`**；起完即释放端口。

### 9.5 与并行任务的一处交叉（留给后来人）

同日的另一个任务 `docs/superpowers/plans/2026-09-23-contact-memory-bootstrap.md` 要把游标语义改成
「连续 keyset page + 消息级 durable inbox 回执」，并新增 `history_backfill_cursor`。
两处会碰到本批：

1. `markDirtyForRecompute` 与 `markStaleDirty` 共用的那条判据会被改写 ⇒ §9.2 的绊线用例会**先红**。
   这正是它存在的意义：**改一处等于改两处**。
2. 该计划 Task 5 要加「终态失败的重试入口」（前端）。本批的 `contact.refresh_memory` 已覆盖包括
   `FAILED` 在内的全部状态，且是助手侧唯一的入口 —— 两个入口必须是**同一条判据**，不要各写一份。

> **那 1 failure 是本仓的既有红灯，不是本批的回退**：它按计划写在工作区里，计划原文
> （`2026-09-23-contact-memory-bootstrap.md:43`）写明「原 Gate 0 测试已在 PostgreSQL Testcontainers
> 证实失败…**修复后必须**断言消息进入模型输入」，即该用例在合同落地前**就该是红的**。
> 复核结论：失败的用例只需要「`received_at` 小于游标的消息仍送达模型」，
> 而取数 SQL `ContactMemoryMapper.listInboundMessagesByCursor` 按 `(received_at, id) > 游标` 过滤，
> 结构上不可能返回它 —— 判据不在本批touch 的文件里（该 mapper 与 HEAD 逐字节一致，`git status` 干净）。


### 9.6 同日晚的后继修复（两起实机事故，详见评审 §9）

- **历史锚定**：模型照抄旧轮次自己的「我做不到」而不看工具清单 ⇒ 提示词硬规则 11（评审 §9.1）。
- **改备注覆盖昵称**：模型把「改备注」错调成 `contact.update_profile` ⇒ 工具描述纠偏 +
  确认卡片「改前」从库取真值（评审 §9.2）。bootstrap 任务若动 `ContactCandidateProvider`，
  注意 #23 的证据源约定。
