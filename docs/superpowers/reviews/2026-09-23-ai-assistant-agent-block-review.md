# AI 助手（agent 块）代码与架构评审

- 日期：2026-09-23
- 范围：`service/assistant/**`（含 `mcp/**`）、`web/AssistantController.java`、前端 `components/assistant/**`
- 基准文本：`specs/2026-09-21-ai-assistant-mcp-tools-design.md`、`plans/2026-09-21-ai-assistant-mcp-tools-implementation.md`、`plans/2026-09-22-assistant-decision-loop-l0-to-l2.md`、`plans/2026-09-22-assistant-memory-and-conversation-design.md`
- 取证方式：只读走查（Read/Grep 逐文件），不含真实模型调用；**未跑测试**，测试覆盖是清点用例名得到的

---

## 0 · 结论

**这个模块的架构是这套代码库里少见的「约束写在代码里、而不是写在文档里」的那种。**设计文档承诺的 18 条不变量里，16 条在代码上站得住且有测试钉住；2 条只在**注释**上成立（`ToolInputValidator` 的未知关键字、README 之外的人工 checklist）。

**没有发现 P1 缺陷。** 5 条值得记的发现里，最有价值的一条是**我自己上一轮引入的**：把「历史以库为准」这条规则放进了 controller，而不是编排层（§3.1）。它目前只有一个调用点，所以不是活 bug，但它是一处**将来会静默退化的接缝**。

> **修复进度（2026-09-23）**：§3.1（P2-1）**已修**；同日又修掉了三条 ——
> §3.2（关键字白名单门禁 + **真的实现** `format`）、§3.3（截断收口 + 给模型看的截断补标记）、
> §3.5（`maxReadTurns` 的非单调性写进配置注释）。各自见该节末尾的「已修」块。
> **仍未动**：§3.4（供应商失败在库里没有痕迹）、§3.6（提示词没有总量口径）、§3.7（小事三则）——
> 都是「该记下来」的性质，不构成阻塞。
> 本文其余部分保持评审当时的原样（**行号是修复前的快照**），
> 便于把「当时的判断」与「之后的改动」分开读。

**三条结构性优点值得保留并对抗将来的「优化」冲动**（§4）：候选集抽象、策略双清单 + 启动自检、确认路径与提问路径分离。这三条都有「删掉它不会有任何测试变红、但会静默失去一道防线」的性质，因此它们的价值必须写在文档里。

---

## 1 · 不变量对照表

设计文档承诺 → 代码事实 → 守卫。**「守卫」一栏写的是「删掉它会不会有人发现」。**

| # | 承诺 | 代码事实 | 守卫 |
|---|---|---|---|
| 1 | 身份不经模型/工具参数 | 工具 schema 无身份字段；`userId` 只由 `SecurityUtil` 向下传参；`ToolRegistry.invoke` 对 `userId == null` **抛**而不是降级（`ToolRegistry:117-121`） | `AssistantIdentityBoundaryTest` 4 例（请求体塞 userId 被忽略、schema 无身份字段、历史里的 system 角色不能伪装） |
| 2 | 写动作每轮至多一个 | `respond` 循环只在 `Decision.READ` 分支 `continue`（`AssistantConversationService:155-181`），写动作一律 `return`（184-185） | `AssistantReadLoopTest.aReadRoundThenAWriteActionBothHappenInTheSameRequest` |
| 3 | 每轮重新过 policy | `policy.decide(definition)` 在 `while` 循环体内（153 行），无缓存 | `AssistantReadLoopTest` 多条路径 |
| 4 | 只有只读工具可循环 | 由 2 + 3 结构性成立（非 READ 即 return） | 同上 |
| 5 | 只读性不采信注解 | `READ_ONLY_ALLOWLIST` 是代码权威；注解只做一致性校验，不一致**抛**而不是退保守（`AssistantActionPolicy:117-124`） | `AssistantActionPolicyTest` 10 例 + `AssistantPolicySelfCheck` 启动自检 |
| 6 | 清单与注册表不一致即启动失败 | 清单里名字不存在 / `readOnlyHint` 缺失 / 与 AUTO 清单重叠 → 构造期抛（`AssistantPolicySelfCheck:33-59`） | 该类**无条件装配**（与功能开关无关），无测试但启动即验 |
| 7 | `additionalProperties:false` 是身份防线的硬机制 | 注册表自检强制要求（`ToolRegistry:164-170`），校验器据此拒越界字段（`ToolInputValidator:84-89`） | `ToolRegistryTest` 17 例、`TodoAssistantToolsTest` |
| 8 | 引用参数必须命中本轮候选 | 字段级 `x-candidateSet`；集合缺席 → 拒（fail-closed），不是放行（`AssistantDecisionParser:225-242`） | `AssistantReadLoopTest.aConversationRefThatIsInNoCandidateGroupIsRejected`、`ToolRegistry` 的「形如 `*Id`/`*Ref` 未声明绑定即起不来」（201-211） |
| 9 | 确认路径**不**比对候选清单 | `validateConfirmedCall` 传 `context = null`（`AssistantDecisionParser:194-196`）；`AssistantPendingActionService` **不持有** `AssistantContextBuilder`（编译期事实） | `AssistantDecisionParserTest` 对照断言、`AssistantPendingActionServiceTest.confirmationDoesNotDependOnTheCandidateWindow` |
| 10 | 确认时仍校验结构 | 两条路径共用 `validate(...)`，只差候选比对（`AssistantDecisionParser:202`） | `validateConfirmedCallStillRejectsStructuralProblems` |
| 11 | 双击确认只执行一次 | `markDecided(..., 'CONFIRMED')` 带 `where status='PENDING'` 原子抢占，影响 0 行即 409；**抢占在校验之前**（付出「失败的确认消耗掉授权」的代价） | `AssistantPendingActionServiceTest` 21 例 |
| 12 | 越权由 SQL 兜 | 助手域 SQL 的归属谓词只有一份（`WHERE_OWNED_BY_USER` 常量，mapper 抽常量防漏写）；联系人走 `findAccessibleById`、会话走 `listUnified`（均沿用既有谓词，不重写） | `AssistantConversationLogServiceTest`、`ContactBriefProviderTest` |
| 13 | 解析器永不信任模型输出 | 以 `decision` 为唯一分支依据；`call` 分支失败**不重试**（`AssistantDecisionParser:30-46`）；工具名不在注册表 → 整轮作废不执行 | `AssistantDecisionParserTest` 32 例 + `anInventedToolNameIsRejectedAndIsNotRetriedIntoSomeOtherTool` |
| 14 | 只读工具失败即终止、不回灌 | `AssistantConversationService:166-171` 直接返回诚实错误文案；类注释写清代价（模型失去「换个词再试」的能力） | `aFailingReadToolEndsTheTurnSoTheModelNeverGetsToSpinItAsSuccess` |
| 15 | 轮次上限用尽时诚实终止 | `exhausted()` 直接返回，**不再问模型**（195-205），审计记 `READ/REJECTED` | `whenTheReadBudgetRunsOutTheTurnEndsHonestlyInsteadOfAskingAgain`、`zeroReadTurnsDisablesTheReadTrackEntirely` |
| 16 | 提示词硬规则是受测件 | 10 条硬规则 + 分隔符声明都在 `buildSystemPrompt`（116-129）；说明段用占位词 `名字`，与具名锚点 `<<<CANDIDATES:todo` 天然不撞 | `AssistantPromptBuilderTest` 16 例 |
| 17 | 时区必须显式取 | `ZonedDateTime.now(clock.withZone(zone))`，zone 取自 `app.todo-reminder-zone`（与提醒链路同源，刻意不新增配置） | `AssistantContextBuilderTest` 6 例 |
| 19 | **不可撤回的外向动作必须确认，且收件人不经模型参数** | 两个发送工具都不进任何白名单 ⇒ `AssistantActionPolicy` 一律 CONFIRM；schema 里**没有** `to`/`recipient` 字段，地址由 `OutboundMessageService.resolve(userId, contactId, channelType)` 从联系人档案解析（owner 谓词在 SQL 里） | `MessageSendAssistantToolsTest` 13 例 —— 其中最关键的**不是**「地址取对了」而是**参数 key 集合**（`containsExactlyInAnyOrder("contactRef","subject","body")`）：有人顺手加个 `to` 会立刻红。**必须确认**这件事直接问 `AssistantActionPolicy` 要答案，不采信注解 |
| 18 | 合规口径 b（只出结构化事实与摘要） | 全包 grep `inboundMessages / callTranscripts / transcripts / last_text` **零代码命中**（只在类注释里作为「为什么不这么做」的依据出现）；联系人走 `listStableContext`，会话投影显式排除 `last_text` | `ContactBriefProviderTest` 11 例（含最坏情况不触发截断） |

| 20 | 联系人记忆的「重算」是**增量**语义，且判据只有一份 | 手动入口的判据写在 SQL 里、与自动路径**同源**（`ContactMemoryStateMapper.markDirtyForRecompute` ↔ `markStaleDirty`，同为 `last_inbound_at > split_part(last_success_cursor,'|',1)`），只限定单个联系人、额外放行 `FAILED` | `ContactMemoryStateMapperSqlTest.theManualPathSharesTheSameJudgeAsTheAutomaticPath`（两份 SQL 对钉，改一侧即红）+ `ContactMemoryRecomputeServiceTest` 8 例 |
| 21 | 「有没有东西可算」**不由调用方给的时间决定** | `markDirtyForRecompute(owner, contact, now)` 的签名里**没有** `lastInboundAt`：SQL 自己取 `max(m.received_at)`。参数存在一天，就会有人传 `now`，而 `now` 会踩 `complete()` 的 `last_inbound_at > processedAt ⇒ DIRTY` 死循环（每轮白烧 LLM、画像反复改写，**日志与状态看起来全正常**） | `doesNotContain("#{inboundAt}")` + **对照组**（`markDirty` 确实含 `#{inboundAt}`）+ 真库断言 `lastInboundAt == 消息表的 received_at` |
| 22 | **工具清单 outranks 历史里的自我否认**（提示词硬规则 11） | 模型会照抄自己旧轮次的「我做不到」而不看工具清单（2026-09-23 同进程 A/B 实证：空历史答「可以」，带否认历史答「做不到」）。硬规则 11 写明「工具清单是当前能力的唯一事实来源……不要照抄历史的否认」 | `AssistantPromptBuilderTest.theToolListOutranksTheModelsOwnPastDenials` 存在性断言 + 探针 70 格全安全（新规则未破坏对抗面）+ 修复后 A/B 对照：否认历史组也能答出「能触发重算」 |
| 23 | **写工具确认卡片的「改前」必须是库里的真值**；「选哪个工具」的纠偏写在工具描述里 | `update_profile` 卡片的改前曾一律「当前未知」，用户点确认时看不到自己即将覆盖掉的昵称（2026-09-23 事故：模型把「改备注」错调成 `contact.update_profile`）。现在 `profileChanges` 经 `ContactMapper.findByIdAndOwner`（与重算入口同一归属判据）取显示名/职务真值，查不到才留 null；纠偏文案写在两个工具的 description 里（模型只看得到声明） | `AssistantPendingActionServiceTest.aProfileCardShowsTheDisplayNameTheServerWillOverwrite` + `aProfileCardLeavesTheBeforeUnknownWhenTheContactIsNotYoursOrIsMissing` + `ContactWriteAssistantToolsTest.theProfileToolDescriptionRedirectsRemarkRequestsToTheRemarkTool` |
**两条只在注释上成立（见 §3.2、§3.3）**：#18 的「只读工具返回值不得越出口径 b」在 `AssistantActionPolicy:89-90` 自认是人工 checklist；#7 的「不认识的关键字要报错」只覆盖了 `type` 的取值。

---

## 2 · 与既有教训的一致性核查

项目里栽过的三个坑，助手域里逐条查过：

| 坑 | 核查结果 |
|---|---|
| `Optional.map(...).orElseThrow(...)` 把「取不到」与「不存在」合成一个出口 | ✅ 助手域全包**零命中**（grep `\.map(` ∩ `orElseThrow`）。`ContactBriefProvider:106` 是 `findAccessibleById(...).orElseThrow(...)`，无 `map` 链 |
| 面向用户的回话吐内部标识 | ✅ `conversation.pin` 已改为从授权查询带回显示名（`ConversationAssistantTools:142-149`）；取不到名字退回 ref 而不是编（`144`）。`todo` 侧摘要取不到候选时退回 `"待办 " + todoId`（`AssistantPendingActionService:335`）—— 难看但诚实，可接受 |
| 「失败就退化成看起来正常」的 catch 必须留痕 | ✅ 三处 catch 各自留痕：`AssistantAuditService:70` ERROR、`AssistantConversationLogService:88` ERROR、前端 `useAssistant.loadHistory` 的 `console.warn` + 可见化。**唯一例外**见 §3.4（模型供应商失败时整轮不留痕） |
| 事务边界 | ✅ 助手域**无** `@Transactional`。工具执行、审计、会话记录各自独立事务 —— 与 `AssistantAuditService` 类注释「宁可少一条审计，也不能让用户看到假失败」一致。代价（动作生效但无审计行）已写明 |

---

## 3 · 发现（按严重度）

严重度定义：**P1** = 现在就错，会在真实使用中产生错误结果；**P2** = 现在不错，但接缝会在下一次改动时静默退化；**P3** = 该记下来，等下一个顺风车。

### 3.1 P2（架构）· 「历史以库为准」这条规则装在了 web 层

**事实**：`AssistantController.historyFor`（`AssistantController.java:108-120`）决定「模型看到什么历史」，`AssistantConversationService.respond(userId, conversationId, history, text)`（`AssistantConversationService.java:118`）只是接收。生产代码里 `respond` 只有这一个调用点（grep 验证）。

**为什么这是问题**，不是洁癖：

1. `AssistantConversationService` 的类注释第一句是「一轮对话的编排：上下文 → 模型 → 解析 → 策略 → 执行」—— 历史的来源属于「上下文」，按这条分工它应该在编排层。
2. 这条规则的正确性**没有类型或测试保护**：第二个入口（原文档阶段 4 的语音端点、将来的定时触发、任何内部调用）只要忘了自己加载历史，就静默回到「浏览器说了算」—— 而那是这条规则存在的唯一理由（`AssistantController.java:95-99` 自己写了这个理由）。
3. 它是我上一轮（`aafeb0bf`）引入的，当时只想着「API 边界上做最少的改动」，没有把它当成一条**领域规则**看待。

**建议**：抽 `AssistantHistorySource`（或直接把 `historyFor` 的逻辑搬进 `AssistantConversationService`，签名改为 `respond(userId, conversationId, requestHistory, text)`），controller 只负责把请求体交下去。约 30 行，可加一条守卫：「第二条入口也能拿到库里的历史」。

**已修（2026-09-23）** —— 取的是第二条路（搬进编排层），理由如下：

- **抽一个 `AssistantHistorySource` 只解决一半。** 规则有了自己的类与测试，但第二个入口仍可能
  **忘记调用它** —— `respond(history, text)` 签名照旧接受一份来历不明的历史。
  「签名上无法表达『跳过选源』」才是这条发现真正要求的东西。
- 现签名 `respond(UUID userId, UUID conversationId, List<AssistantMessage> providedHistory, String text)`：
  选源 → 裁剪 → 附加 `historyTrim` 三步收在 `respond` 一处，`runTurn` 私有方法里只剩原来的编排循环。
  控制器只剩两件事：把线上形状（小写 `role` 字符串、`HistoryTurn`）归一成领域对象、
  判定依赖缺失时返回 503（`require(...)` 仍先于一切，所以「功能没开启」照样压过「入参不合法」）。
- **顺带纠正了一处**：`AssistantRequestGuard` 的调用点随之从控制器移到编排服务 ——
  顺序不允许拆开（先定下「哪一份历史」再谈「留几条」，否则报告的数字算的是那份**没被采用**的）。
  两个类的注释（`AssistantRequestGuard`、`AssistantTurnResult.withTrimmedHistory`）与
  设计文档 `plans/2026-09-22-assistant-memory-and-conversation-design.md` ① ② 两节已同步改写。

**守卫（新类 `AssistantPromptHistoryTest`，5 例）**：`theServerSideHistoryWinsOverTheProvidedOne`、
`theProvidedHistoryIsUsedWhenTheServerHasNoRecord`、
**`noShapeOfProvidedHistoryCanBypassTheServerRecord`**（`null` / 空表 / 伪造的 / 另一句原话，
四种形状都不能让库里的记录输掉 —— 这条就是「第二条入口拿得到库里的历史」的形式化）、
`aTrimmedHistoryIsReportedOnTheTurnResult`、`nothingIsReportedAsTrimmedWhenEverythingFits`。

**断言落在「发给模型的那串提示词」上**（捕获 `complete(messages)` 实参后拼接），
而不是编排层内部的中间变量：「哪一份历史」唯一有意义的含义就是「模型看到了什么」。

**测试面的取舍**：web 切片里原有的 4 条（服务端优先 / 请求体回退 / 裁剪报出 / 一条没丢）
随规则迁出，控制器侧只留下**契约**断言（`aRequestInvalidExceptionBecomes400`、
`theTrimReportedByTheServiceReachesTheResponseUnchanged`）—— 留下原来那几条会退化成
「把一份历史原样转交给 mock」，那是在测传参，不是在测规则。
「超长 → 400 且不惊动模型」那条移到 `AssistantConversationServiceTest`，
断言从 `verifyNoInteractions(conversations)` 换成 `verifyNoInteractions(modelClient)`（更有分量）。
web 切片里那份只服务于它的 `AssistantMessageLimitTestConfiguration` 随之删除。

### 3.2 P2（正确性）· 校验器静默忽略不认识的**约束关键字**

**事实**：`ToolInputValidator.checkProperty`（`ToolInputValidator.java:129-161`）只处理 `type / maxLength / enum`；`ToolRegistry.selfCheck`（153-218）只校验 schema 级关键字（`type / properties / required / additionalProperties / x-requiresAtLeastOneOf / x-unboundIds`）。

**因此**：某个工具若声明 `"pattern"`、`"minimum"`、`"format"`、`"minItems"`、`"default"`，校验器**会当作它不存在**。

**这与类注释自相矛盾**：`ToolInputValidator.java:22-23` 写着「静默忽略一个约束比直接报不支持更危险……遇到不认识的关键字类型会报 `INTERNAL`」。那条防线只覆盖了 **`type` 的取值**（`136-142` 的 `default ->` 分支），没覆盖**约束关键字本身**。

**当前零触发**（4 个域的工具只用了 `type/description/maxLength/enum/x-candidateSet`），所以是「零成本可修」。

**建议**：在 `ToolRegistry.selfCheck` 里对每个 property 的 key 集合做白名单（`type / description / maxLength / enum / x-candidateSet`），出现其他 key 即启动失败。这与该类既有的哲学（「宁可起不来」）完全一致，且能挡住**将来**有人写了 `pattern` 却以为它在生效。

**已修（2026-09-23）**：

1. 白名单落地为 `ToolInputValidator.SUPPORTED_PROPERTY_KEYS` / `SUPPORTED_ITEM_KEYS`（代码权威，
   实现方与消费方同一处定义）+ `rejectUnsupportedKeys(...)`；由 `ToolRegistry.selfCheck` 对
   每个字段、以及 `items` 内的元素 schema 逐个比对，出现清单外的 key 即启动失败。
2. **上面「当前零触发」这句是错的。** `todo.create` 的 `date` / `time` 从第一天起就写着
   `format`，而本类当时不认这个关键字 —— 那两条声明的实际效果是**零**。真正拦住
   「2026-13-45」与「明天下午」的是服务层的 `LocalDate.parse` / `LocalTime.parse`，
   外加工具侧把 `DateTimeParseException` 收成 `INVALID_ARGUMENT` 的兜底（`TodoAssistantTools.guarded`）。
   ⇒ 所以这次不是「加个门禁就完事」，而是**把 `format(date|time)` 真的实现了**：
      `checkFormat` 与服务层用同一个解析器、同样先 `trim`，判据**等价而非更严**
      （校验层若比服务层更严，就成了在校验层偷偷改业务规则）。
   同时给 `format` 的**取值**也加了启动期检查 —— `format: "email"` 与「写了个 `pattern`」
   是同一个错误，只做 key 白名单会漏掉这一半。
3. 白名单刻意**保守**：它也会拒掉 `title` / `examples` 这类**纯注释**关键字（无约束语义，
   写了不生效也不产生假保证）。代价是想写 `title` 的人要改一行；收益是不会漏掉任何一个
   约束类关键字。两个方向的代价不对称 —— 漏掉约束类是**静默**的，误拒注释类是**响亮**的。
4. 守卫：`ToolRegistryTest` +4 例（写了 `pattern` 起不来 / `format: "email"` 起不来 /
   `items` 内写 `pattern` 起不来 / 真实 todo 声明能过门禁，防「门禁上线把既有工具一起拦死」）；
   `ToolInputValidatorTest` +4 例（date 与 time 的正反边界、`trim` 判据、没声明 `format`
   就不该凭空产生约束）。启动自检实测：**19 个真实工具全部通过门禁**。

### 3.3 P2（一致性）· 「截断必须说出来」这条立场只落实了一半

项目在 observation 那处做得对：`AssistantPromptBuilder.renderResultPayload`（`AssistantPromptBuilder.java:263-267`）截断后补了「…（结果过长已截断，如需更精确的结果请缩小检索范围）」，理由写在 `OBSERVATION_MAX_CHARS` 的注释里（67-69）。

但另外四处是**裸截断**，且其中一处正是「给模型看的数据」：

| 位置 | 上限 | 谁在看 | 有标记吗 |
|---|---|---|---|
| `AssistantPromptBuilder:266` | 4000 | 模型 | ✅ 有 |
| `ContactBriefProvider.truncate`（238-243） | 画像 400 / 备注 200 / 事实值 90 / 标签 24 / 话题小结 120 | **模型** | ❌ **无** |
| `AssistantPendingActionService.card`（274-276） | 4000 | 人（确认卡片） | ❌ 无 |
| `AssistantConversationLogService.truncate`（175-178） | 8000 | 人（回放） | ❌ 无 |
| `AssistantAuditService.truncate`（180-183） | 2000 | 排障 | ❌ 无 |

**最值得修的是 `ContactBriefProvider`**：画像被截到 400 字之后，模型看到的是一个戛然而止的句子，而它**无从知道被截断**——这与 observation 那处「静默截断会让模型把『只看到一半』当成『就这么多』」的论证是同一个论证，只是这里没执行。

**另外**：五处都用 `String.substring`，在多字节（emoji / 代理对）上会切出半个字符。低危，但既然要改就该一起收口成一个 `Texts.truncate(value, max)` / `truncateForModel(value, max)`。

**建议**：① `ContactBriefProvider` 加截断标记（`…（已截断）`）；② 五处实现收口成一处工具类。日志/审计/卡片保持裸截断（那是给我们自己看的，标记只添噪音）。

**已修（2026-09-23）**：

1. 新建 `Texts`（`service/assistant/`）：`truncate(value, max)` 裸截断（**代理对安全** ——
   切点落在高代理项上时退一位，宁可少一个字也不产生半个字符）与
   `truncateForModel(value, max[, marker])`（追加标记，**标记计入上限**：上限是给模型的预算，
   不该因为多了句说明就突破）。7 处实现全部收口到它 —— `service/assistant/**` 下带切点的
   `substring(0, N)` 现在只剩 `Texts` 内部那一处（其余 `substring` 都是 ref 前缀解析）。
2. **实测是 7 处而不是 5 处**：评审漏了 `ContactCandidateProvider`（候选清单里的备注，60 字）
   与 `AssistantPromptBuilder` 的显式拼接分支。前者也是给模型看的，但它的用途是**指认对象**
   （「这是谁」），所以按下面的判据走裸截断。
3. 加不加标记的判据定成一句话：**模型会不会把它当作一条完整的事实用来回答用户**。
   据此 —— `ContactBriefProvider` 的画像正文 / 备注 / 事实值 / 话题小结 → **带标记**；
   显示名 / 职务 / 标签名 / 话题标题 / 事实类别 → 裸截断（它们只用来指认，加标记既不改变
   判断又让清单变难读）。候选清单备注、确认卡片、回放、审计 → 裸截断（给人看的）。
4. 一个必须一起改的副作用：`ContactBriefProviderTest.aWorstCaseBriefStillFitsInASingleObservation`
   原先拿「不含『已截断』」当「整条 observation 没被截断」的**代理判据**；字段自己开始带这个
   标记之后，该判据会恒假。处理方式是**改判据而不是改文案** —— 换成 `AssistantPromptBuilder`
   独有的「结果过长」。这条也顺带说明：用一句文案当代理判据，在文案被复用后会静默变成空断言。
5. 守卫：新建 `TextsTest` 8 例（null 语义 / 未超长原样返回 / 标记计入上限 /
   上限连标记都放不下时降级为裸截断 / 代理对不切半 / 自定义标记）；
   `ContactBriefProviderTest` +2 例（画像截断带标记且长度仍等于上限、标识类字段不带标记）。

### 3.4 P3 · 模型供应商失败在库里**没有任何痕迹**

**事实**：`AssistantConversationService.respond` 第 133 行第一次 `modelClient.complete(messages)` 抛出 `ASSISTANT_UNAVAILABLE` 时，异常直接冒泡到 controller → 503。因此：

- `finish()`（263-268）不执行 ⇒ 用户那句话**不进会话记录**；
- 循环内所有 `audit.record` 也不执行 ⇒ **审计表零行**。

只有一行 WARN 日志（`AssistantModelClient:128`）。

**为什么值得记**：验收标准里有一条是「能回答 AI 做了什么」。在这条路径上，正确答案是「什么都没做，因为供应商挂了」——而库里查不出来。这与 `AssistantAuditService` 认定的「动作生效但无审计行」是同一类缺口，只是这一条**每次供应商抖动都会发生**，不是极端情况。

**建议**：provider 失败时补一行审计（`decision=null / outcome=FAILED / errorCode=ASSISTANT_UNAVAILABLE / latencyMs`），不动会话记录 —— 那一轮确实没发生对话，`AssistantConversationLogService` 的「没有对话就不写」是对的。

### 3.5 P3 · `maxReadTurns` 是**非单调**的旋钮，而这个性质没写进配置

**事实**：`AssistantPromptBuilder.readTurnContract`（183-199）把上限数字渲染给模型。走查实测（决策环文档 §6.2 场景 6/7）：`=1` 时模型**干脆不检索**，直接答「清单里没有」。

**所以**：这个配置在 `1` 处有一个反向区间 —— 不是「少一轮」而是「只读能力事实上不可用」。§8 已经写了「回滚用 0」，但 `AssistantConfig.maxReadTurns` 的注释（37-43）没有，`AssistantConfigTest` 也没有锁住这一点。

**建议（分两步）**：现在只需在配置注释里补一句「1 与 0 都不可用于『部分回滚』，回滚请用 0」；干净的做法是**提示词不渲染具体数字**（改成「最多若干次」），让上限只由服务端闸门表达 —— 那样 1 与 3 的差别就只体现在闸门上。后者要重跑 0.3 探针，成本不小，建议与下一次「必须动提示词」的改动合并做。

**已修第一步（2026-09-23）**：

- `AssistantConfig.maxReadTurns` 的 javadoc 补了「**这个旋钮不单调，别拿它做部分回滚**」，
  连同理由（配 `1` 时模型干脆不检索、直接答「清单里没有」—— 那不是「少查一轮」而是只读能力
  事实上不可用）与正确做法（回滚用 `0`）。
- 构造函数里那段「0 是允许的，而且是回滚开关」补了第二句：**1 也允许**，且下界不能顺手提到
  `2` —— 那会让一个真实跑过探针的档位静默消失。
- 守卫加在 `AssistantConfigTest`：+2 例（`0` 与 `1` 都必须能配出来、范围外的值构造期抛），
  并给默认值补了 `maxReadTurns == 3` 的断言 —— **原先这个默认值在测试里完全没有断言**。
- **第二步（提示词不渲染具体数字）仍未做**，理由不变：要重跑 0.3 探针，应与下一次
  「必须动提示词」的改动合并。

### 3.6 P3 · 提示词体积是「各段上限之和」，没有任何一处表达总量

算术（最坏情况，全部上限同时撑满）：

| 段 | 上限来源 | 最坏 |
|---|---|---|
| 待办候选 | 70 条 × 标题 ≤200 字符（`TodoAssistantTools:52`） | ≈ 17 KB |
| 会话候选 | 20 条（`ConversationCandidateProvider.LIMIT`） | ≈ 3 KB |
| 联系人候选 | 20 条 | ≈ 2 KB |
| 工具清单 | 8 个工具的 name/title/description/schema/注解 | ≈ 5 KB |
| 历史 | `max-history-chars = 8000` | ≈ 8 KB |
| 本轮原话 | `max-message-chars = 2000` | 2 KB |
| observation ×3 | 每条 4000 | 12 KB |
| **合计** | — | **≈ 49 KB ≈ 12k token** |

每一段都有界，**但没有任何一行代码或测试表达「一次请求的提示词总量上限」**。设计文档 §7.2/§12 说的「爆炸半径被工具集合限制住」仍然成立（这是权限论证），但「成本与注入面有界」这句是各段拼接的结果，不是一条可审阅、可断言的约束。

**建议**：不必现在动。真要收口，加一条**组合最坏情况测试**（各段都撑满）断言总字符数 —— 把它从「算术」变成一个会红的数字。

### 3.7 P3 · 小事三则

1. `AssistantConversationService:152` 用了无参 `orElseThrow()`。它其实不可达（`parser.validate` 已保证工具存在），但真触发会得到一条**无消息**的 `NoSuchElementException` → 无信息的 500。既然不可达，给它一句消息成本为零。
2. 服务端历史路径上「条数裁剪」永远不会触发：controller 读 `maxHistoryTurns` 条（`AssistantController:113`），guard 又按同一上限裁一次（`AssistantRequestGuard:87`）。无害（guard 必须对请求体路径有效），但两处各自解释同一个配置，值得在 guard 注释里点一句。
3. `ContactBriefProvider` 的四个投影循环用 `continue` 而非 `break` 处理「已达上限」（151-153、173-175、190-192、207-209）。行为正确（只是继续遍历），但在「有上限」的循环里 `continue` 是个会让人停下来读两遍的写法。

---

## 4 · 结构性优点（附证据，供对抗将来的「优化」冲动）

**4.1 候选集抽象：三次检验都成立**

`CandidateSet` 只声明五件事（`name / heading / limit / items / contains`），**刻意不提供** `resolve(userId, text)`（`CandidateSet.java:30-34` 写明理由：检索需要仓储，留在接口里会让候选集变成第二个数据访问层）。

证据：接入 `conversation` 域（阶段 B）与 `contact` 域（阶段 D）时，**编排层、解析器、提示词三处一行未改**——两个阶段的实现记录都明确写了这一点。这不是设计意图的宣称，是两次落地的事实。

**4.2 策略双清单 + 启动自检：把「清单写错」变成起不来**

`READ_ONLY_ALLOWLIST` / `AUTO_EXECUTE_ALLOWLIST` 是代码权威，注解只做一致性校验。**两个方向的错误处置刻意不同**（`AssistantActionPolicy:31-55`）：AUTO 档不一致 → 退保守 + WARN；READ 档不一致 → **抛**。理由是本设计里最关键的一句判断：只读动作是「免确认**且可循环**」的，一个写动作混进只读清单就能在无人复核下连续执行 —— 这个方向不能靠「退保守」兜住。

**4.3 确认路径与提问路径分离：语义不同就不共用实现**

`validateCall` 比对候选（防编造 id），`validateConfirmedCall` 不比对（候选是「能看到什么」的窗口，不是「存在」也不是「权限」）。这个缺陷曾经真实存在：**卡片显示正常、一点确认必失败**，而三个测试类各自「正确地」钉住了错误行为（决策环文档 §6.2 有完整取证链）。

更值得学的是修法的第二步：**删掉 `AssistantPendingActionService` 对 `AssistantContextBuilder` 的依赖** —— 让「确认路径不重建候选上下文」成为**编译期事实**，而不是一条容易再写错的约定。这条「用类型系统表达约定，而不是用注释」的手法，是本期最可复用的工程经验。

**4.4 只读结果的失败不回灌：明知代价仍选择诚实**

回灌之后模型**可以**无视错误继续编，用户在界面上看到的是一个看起来正常的回答。实现选择「不给它说错话的机会」（`AssistantConversationService:50-58`），代价（模型失去换检索词重试的能力）写在同一个注释块里。这个取舍我认为是对的：只读失败在本系统里意味着服务端故障或参数错误，两者都不是「换个说法就能好」。

---

## 5 · 规模与测试覆盖

| 层 | 文件 | 行 |
|---|---|---|
| 编排与策略 | `AssistantConversationService` 274 / `AssistantDecisionParser` 336 / `AssistantPendingActionService` 482 / `AssistantActionPolicy` 156 / `AssistantContextBuilder` 101 / `AssistantPolicySelfCheck` 65 / `AssistantRequestGuard` 119 / `AssistantTurnResult` 130 / `AssistantContext` 111 / `CandidateSet` 52 / `AssistantConfig` 87 | — |
| 数据与投影 | `ContactBriefProvider` 244 / `ContactBrief` 107 / `ContactCandidateProvider` 121 / `ContactCandidates` 93 / `ConversationCandidateProvider` 92 / `ConversationCandidates` 111 / `TodoCandidates` 44 | — |
| 工具层 | `ToolRegistry` 224 / `ToolInputValidator` 189 / `ToolDefinition` 78 / `ToolResult` 54 / 三个 `*AssistantTools` 844 | — |
| 会话与审计 | `AssistantConversationLogService` 179 / `AssistantAuditService` 184 / `AssistantModelClient` 188 | — |
| **合计（含 mcp 子包）** | **33 个源文件** | **≈ 5090** |

**测试（按 `@Test` 计数，助手域 18 个测试类共 227 例）**：

| 测试类 | 例 | 守什么 |
|---|---|---|
| `AssistantDecisionParserTest` | 32 | 信封分支、重试边界、候选比对、确认路径 |
| `AssistantPendingActionServiceTest` | 21 | 抢占、过期、摘要与 changes、跨窗口确认 |
| `ToolRegistryTest` | 17 | 启动自检的每一条（含 `*Id` 未绑定） |
| `TodoAssistantToolsTest` | 17 | 4 个写工具的 schema 与执行 |
| `AssistantPromptBuilderTest` | 16 | 硬规则与分隔符存在（把提示词当受测件） |
| `AssistantConversationServiceTest` | 15 | 单轮全路径 + 审计行 |
| `AssistantConversationLogServiceTest` | 14 | 三个读方法的方向与上限 |
| `ContactAssistantToolsTest` | 12 | 联系人域（含群 ref 被拒） |
| `AssistantReadLoopTest` | 11 | **循环的四条不变量**（每轮过 policy / 写即终止 / 预算尽诚实 / 失败即终止） |
| `AssistantRequestGuardTest` | 11 | 裁剪与「丢了多少条」 |
| `ContactBriefProviderTest` | 11 | 合规投影 + 最坏情况不触发截断 |
| 其余 7 个类 | 50 | 策略、上下文、会话工具、注解缺省、适配器、审计、候选 |

单看用例数不够，关键在**有没有守到那些「删掉不会红」的性质**。逐条对照 §1 的「守卫」一栏：18 条承诺里 16 条有至少一条测试专门钉住，**且**其中 5 条（2、8、9、14、15）的守卫是**跨过确认/循环走完整条链**的集成式用例，而不是单点断言 —— 这正是上一轮那个 P0 缺陷「单测全绿、功能坏掉」的修法。

**唯一的覆盖空白**：`AssistantPolicySelfCheck` 无常驻测试（它只在启动时验）。决策环文档 §7.5 用一次定向启动间接验过「8 个工具 + 只读 3 个」的自检通过，但仓库里**没有**一个「全上下文 + 助手启用」的 `@SpringBootTest`（`AssistantControllerTest` 是 `@WebMvcTest` 收窄上下文；`AssistantLiveConversationTest` 带环境变量条件）。⇒ **改清单时的验证方式仍然是「起一次实例」**，属于已知且已被记录的口径。

> **更正（2026-09-23）**：上面「**没有**一个……`@SpringBootTest`」这句**不准确**，作废。
> `AssistantLiveConversationTest` 正是这样一个测试：`@SpringBootTest(classes = {App.class, ...})` +
> `assistant.enabled=true` + 真模型 + 真数据库。**但它默认不跑** ——
> `@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")` 与
> `@Testcontainers(disabledWithoutDocker = true)` 两道门都必须过，而 `.env` 不会被 Spring
> 的测试上下文读成环境变量（2026-09-23 实测：只 `cp .env` 进副本时该整类 `Skipped: 2`）。
> 所以**操作性结论不变**（常规构建里它等于不存在，改清单仍要起一次实例），
> 变的是措辞：不是「没有这个测试」，而是「有这个测试、但默认不生效」。
> 要跑它：`source <一份含 AI_BASE_URL/AI_API_KEY/AI_MODEL 的 env>` 后
> `mvn -o test -Dtest=AssistantLiveConversationTest`（会打外网、耗时数十秒）。

---

## 6 · 未验证的部分（诚实声明）

- **未跑测试**：本评审是只读走查，§5 的用例数是 **grep `@Test`** 得到的，不代表全绿。上一次全量观测（2026-09-22）是 1846 例 / 1 failure + 1 error，两个残留失败在 HEAD 上同样红（属于既有 WIP）。
- **未做真实模型调用**：§1 的 16 条里，「模型在自然问法下会不会选对工具」这一类属于模型行为，只有 0.3/B5 探针的实测记录（`deepseek-chat`，顺语义 + 对抗 + 只读轮共 70 次调用，0 危险 0 可疑），本次未复跑。
- **未走查浏览器**：前端部分（`useAssistant.ts` 三程取号、`historyError` 可见化）在上一轮（`ef2a799c`）有 25 例单测 + `tsc` 把关，但没有真实浏览器走查记录；`trimmedHistory` 刷新即消失这一已知局限仍然在。
- **§3 的严重度是我一个人的判断**，没有第二个人复核。P2 三条都是「现在不错、下次会错」的性质 —— 若你不同意其中某条该修，理由应当写进本文档，而不是让它消失。

---

## 7 · 补充（2026-09-23 晚）· C5/C6 落地后

本文档写于 C5/C6（`message.send_email` / `message.send_chatapp`）**之前**。那批工具落地后，
新增了一条不变量（§1 的 #19）和一条不属于「代码正确性」的教训，记在这里。

### 7.1 `send_email` / `send_chatapp` —— 助手第一次造成不可撤回的后果

规格与验收证据见 `specs/2026-09-23-ai-tool-backlog.md` §8。对本文档有两处增量：

- **§1 新增 #19**：不可撤回的外向动作必须确认；**收件地址不做成参数**。
  这条比「确认卡片显示全文」更硬 —— 前者是流程约束，后者是**结构性约束**：
  「编造一个收件人」不再是「需要被检出的错误」，而是**无法被表达的行为**。
- **§5 的用例数已作废**（那节的数字是当时的快照，不只因为本批）。
  本批实测：助手域相关 10 个测试类定向回归 **155 例 / 0 失败**；全量 **1868 例 / 0 failure / 1 error / 2 skipped**
  （唯一 error 是 `UserChannelOwnerBackfillSchemaContractTest` 的副本假象）；
  定向启动 ⇒ `已注册 21 个助手工具`、`助手策略自检通过：只读 7 个、免确认 1 个`。

### 7.2 一条本文档原本覆盖不到的失败模式：**层次放错**

§2 那节核查的是「与既有教训的一致性」，§1 那节守的是「承诺 ↔ 守卫」。
C5/C6 撞上的是**第三种**：逻辑全对、测试全绿、**但住错了包**。

第一版把「解析收件人 + 转发到渠道」写在 `service.message`，`ArchitectureBoundaryTest` 当场报 **14 条越界**
（构造器带 `EmailSendService`、返回 `EmailSendService.SendResult`、读 `EmailException.code()`）。
处置是**搬进 `service.channel`** 并把渠道类型的回执映射掉，**没有加白名单**。

> 教训一句话：**「清单上的 🟢」只说明能力在，不说明它落在对的那一层。**
> 门禁拦下新代码时，第一反应应该是「它该住在哪一层」，而不是「它算不算例外」。

§6 的诚实声明对本批依然成立并且更强：**没有跑过真实模型调用**，所以「模型会不会在用户说
『帮我写封回信』时就直接调用发送工具」这件事**没有被验证过** —— 目前只有工具描述里的
`WHEN_TO_CALL` 与确认卡片两道软硬防线。这是本批最大的未验证面。

---

## 8 · 补充（2026-09-23 晚二）· A6/A7 落地后：`contact.refresh_memory`

规格与验收见 `specs/2026-09-23-ai-tool-backlog.md` §9。对本文档的增量是两条不变量与一处公开冲突面。

### 8.1 §1 新增 #20 —— 「催一下」必须是增量语义

`contact.refresh_memory` 的诚实语义是「把还没折进画像的入站消息尽快处理掉」，
**不是**「把历史重算一遍」。后者会把同一条消息当**新证据**重复累积（置信度虚高、画像被反复改写）——
而在代码上它表现为「反正画像变好了」，没有任何断言会失败。所以这个取舍落成了文档，
而不是靠后来人从方法名里猜。

同族的一半是**判据只能有一份**：手动路径与自动路径共用那条 SQL 判断。
`theManualPathSharesTheSameJudgeAsTheAutomaticPath` 把两份 SQL 对钉，**改一侧就红** ——
它是刻意的绊线（§9.5 说明了它在另一任务里会先红，那正是它的用途）。

### 8.2 §1 新增 #21 —— 防线是「参数不存在」，不是「记得别传」

这条与 §7.1 的「收件地址不是参数」是同一个手法，这次护的是一个**静默死循环**：
`now` 一旦能进 `last_inbound_at`，`complete()` 的
`last_inbound_at > processedAt ⇒ 状态回到 DIRTY` 就会在每轮跑完后立刻把状态改脏 ——
每轮白烧一次 LLM、画像反复改写，**而日志、状态、指标全都正常**。
把参数从签名里删掉，这个错误就**写不出来**；再加一对照组（`markDirty` 确实含 `#{inboundAt}`）
挡住「顺手把新方法简化成调用 markDirty」。

> 一句话：**能删掉的参数，比能写对的注释可靠。**

### 8.3 一处公开的冲突面（留给 bootstrap 任务）

同日另一任务 `plans/2026-09-23-contact-memory-bootstrap.md` 要改游标语义（连续 keyset page +
消息级 `memory_applied_at` 回执）并新增 `history_backfill_cursor`。两处交叉：

1. #20 那条判据会被改写 ⇒ 绊线用例先红（设计意图）；
2. 该计划 Task 5 要加前端「重试」入口 —— 与本批的 `contact.refresh_memory` 是**同一条判据的两个入口**，
   不要各写一份。

另：该任务的 Gate 0 红灯 `lateInboundMessageBehindSuccessCursorIsStillSentToTheModel`
**在本文档全量回归里出现，不是本批的回退**（计划原文写明「修复后必须」转绿 ⇒ 现在就该是红的）。
复核方式与结论见 backlog §9.5。

### 8.4 §5 / §6 的数字更新

- 全量 **1898 例 / 1 failure / 1 error / 2 skipped**（上轮 1868 ⇒ +30，其中本批 +28）。
  1 error 仍是 `UserChannelOwnerBackfillSchemaContractTest` 的**副本假象**；
  **1 failure 是另一任务的既有红灯**（见 8.3）。
- 助手工具数 **21 → 22**；`READ_ONLY_ALLOWLIST` 仍 **7 项**、`AUTO_EXECUTE_ALLOWLIST` 仍 **1 项**。
  本批只加了 CONFIRM 档 —— 这恰好反向验证了「三档是分开的」：加一个写工具**不会**因为「反正它很安全」
  而被塞进只读清单（启动自检 + 策略清单两道都在）。
- 定向启动 ⇒ `已注册 22 个助手工具：… contact.refresh_memory …`、
  `助手策略自检通过：只读 7 个、免确认 1 个`、`Started App in 3.147 seconds`。
- §6 的诚实声明继续成立：**仍未跑过真实模型调用**。「模型会不会在用户问『他的标签怎么这么旧』时
  就自己调重算工具」这件事没有被验证过。


## 9 · 补充（2026-09-23 晚三）· 两起真实事故的修复

这两起都不是「想象中的风险」，是当天实机上真实发生、有审计记录的缺陷。

### 9.1 事故一：模型照抄自己旧轮次的否认（历史锚定）

**现象**：用户问「根据邮件内容生成画像和标签」，16:55（新代码已启动、22 个工具在注册表里）
模型答「我没有这个能力」。取证链：`jcmd VM.class_hierarchy` 确认 `ContactMemoryAssistantTools`
及其 lambda 已加载 → 新会话探针一口气列出 22 个工具 → **A/B 对照**：同进程同问题，
空历史答「可以的……触发一次重算」，历史里塞进它 16:12（旧构建时期）的否认就答「做不到」。

**根因**：服务端历史以库为准，旧构建时期的否认回答留在同一会话里，模型**照抄自己**
而非看系统提示词里的工具清单。这不是装配问题，是提示词缺一条仲裁规则。

**修复**：硬规则 11（不变量 #22）。提示词是安全关键件，按规程走完：
`AssistantPromptBuilderTest` 断言 + `assistant-prompt-regression.py` 吃**生产提示词**真跑
（70 次调用 / 危险 0 / 可疑 0）+ 修复后 A/B：否认历史组不再停在否认，能答出
「做不到直接读邮件写画像，**能触发系统的画像重算**」—— 这恰好是诚实且正确的答案。

**运维教训**：换代升级工具后旧会话不会自动变聪明，**开新会话即恢复**。

### 9.2 事故二：「改备注」被模型调成了 `contact.update_profile`（覆盖昵称）

**现象**（审计流水）：17:06 模型两次调 `update_profile` 把 `displayName` 从渠道同步来的昵称
覆盖成备注文本；17:08 又两次改回。数据自愈了，缺陷还在：用户说「改备注」时模型选错工具，
而旧卡片的「改前」一栏是「当前未知」—— **用户点确认时根本看不到自己即将覆盖掉什么**。

**修复（三层各归其位）**：

1. **工具描述**（模型唯一看得到的层）：`update_profile` 明写「显示名通常承载渠道同步来的昵称，
   改备注用 `contact.update_remark`」；`update_remark` 明写「不会改动显示名」。钉在
   `theProfileToolDescriptionRedirectsRemarkRequestsToTheRemarkTool`。
2. **确认卡片**：`profileChanges` 改前值改从 `ContactMapper.findByIdAndOwner` 查库取真值
   （归属判据与重算入口一致），查不到才留 null —— 「未知好过错值」的原则不变，但
   「有更好的证据源时必须用」（不变量 #23）。
3. **数据**：无需人工修 —— 17:08 的两次调用已把昵称改回（`calm1026`），审计可查。

### 9.3 本批验证数字

- 定向 10 类 **148 例 / 0 失败**（含提示词断言、卡片全生命周期、读写工具、策略、注册表）。
- 探针 `assistant-prompt-regression.py` 吃修复后的生产提示词：**70 次调用 / 危险 0 / 可疑 0**。
- 定向启动 8110 ⇒ `已注册 22 个助手工具`、`助手策略自检通过：只读 7 个、免确认 1 个`；
  A/B 对照通过后释放端口，探针账号及关联行清零。
- 全量回归数字见 §8.4 更新规则（新批 +4 例）。
