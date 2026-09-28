# 助手 `POST /api/assistant/messages` 改为 SSE 流式

> **For agentic workers:** 后端与前端均已完成（2026-09-24）。已知未覆盖项见「验收」一节末尾的环境限制。

**Goal:** 让一轮助手对话「边跑边说」—— 用户在等待期间能看到进度与答案逐字出现，而不是对着一个转圈的占位等几十秒。顺带把超时窗口从 30 秒放大到 300 秒（`assistant.timeout-seconds`）。

**Architecture:** 模型侧用 `stream: true` 拿增量，服务端在**读流的那条线程上**把增量翻译成 SSE 帧直接写响应（同步，不引异步线程）。权威结果仍只有一个：`AssistantTurnResult`，它作为最后一帧 `final` 发出，展示层必须用它**覆盖**片段。

**Tech Stack:** Java 17、Spring Boot 3（`JdkClientHttpRequest`）、Jackson、MockMvc；`text/event-stream`。

## Global Constraints

- **不保留非流式路径。** 两条语义相同的路径，后来人不知道用哪条，而它们迟早会走偏。
- **`require(...)` 与取身份必须留在 `AssistantEventStream.open` 之前。** 响应头一刷出去，`GlobalExceptionHandler` 就再也写不进去 —— 「功能没开」会从 503 变成一个 200 的流内错误。
- **`answerDelta` 给的是增量（不是累积文本）。** 展示层往后拼即可；服务端不重发前缀。
- **`final` 必发且永远是最后一帧。** 前端靠它区分「连接断了」与「这一轮失败了」。
- **旁路上报是尽力而为，不得影响结果，也不得抛异常**（抛出去会被当成供应商故障）。
- 前端请求**不要**设 `Accept: text/event-stream`（理由见下）。

## 事件协议

每帧一行 `data:` + 一个空行，节拍体是 JSON，用 `type` 判别：

| `type` | 载荷 | 何时发 |
| --- | --- | --- |
| `status` | `{"state":"thinking"}` | 一轮开始时，**至多一次**（重试与只读轮都不再发） |
| `status` | `{"state":"reading","tool":"todo.list"}` | 决定去查只读工具、**即将执行**时。带工具名是为了让「卡住了」能定位到步 |
| `delta` | `{"text":"你"}` | 答案的**新**片段。只在「已确认 `decision=reply`」的那一轮才可能有 |
| `reset` | `{}` | 信封写坏了要重问之前。展示层必须清空已显示的片段 |
| `final` | `AssistantTurnResult` 原样 | 必有且仅有一帧，永远是最后一帧 |

把事件名放进 JSON 的 `type` 而不是 SSE 自带的 `event:` 行：端点走 POST，浏览器 `EventSource` 只支持 GET，前端注定要自己读流解析，再加一层 `event:` 行等于把同一个判别信息写两遍。

## 三条设计决定

### 1. 响应头在开流时就刷出去（因此响应立刻提交）

前端 `fetch` 要等响应头到达才 resolve，「请求已被接受」本身就是一条信息。代价是响应提交后 `GlobalExceptionHandler` 再也插不上手，所有失败只能变成 `final` 事件 —— 由 `AssistantEventStream.run(Supplier)` 结构性保证（控制器没有机会忘掉那个 catch）。

**代价明说：** 供应商故障不再表现为 5xx，`REQUEST_INVALID` 不再是 400。运维要看日志（`event=assistant.turn_failed`）或流内 `errorCode`。换来的是前端只有一条失败通道。

### 2. 开流之前的失败仍走异常映射

`require(...)`（功能关闭 → 503）、取身份（未登录 → 401）都在 `open` 之前。这条时序由 `AssistantDisabledControllerTest` 钉住（那里没有替身，走的是真实路径）。

### 3. 错误响应显式声明 `application/json`

`GlobalExceptionHandler.handleAssistant` 现在带 `.contentType(APPLICATION_JSON)`。因为端点声明的 `produces` 只管**成功**路径；失败时异常处理器要写 JSON，而内容协商看的是请求的 `Accept` —— 客户端只写 `text/event-stream` 时，Jackson 转换器产不出它，会把 503 变成 **406 加一个空响应体**，于是「助手没开」看起来像「接口写错了」。

## 部署要求（nginx）

`deploy/README.md` 里的 `location ^~ /api/` 用的是默认值：`proxy_buffering on`（默认）+ `proxy_read_timeout 60s`（默认）。本端点需要：

- `proxy_buffering off;`（或依赖响应头 `X-Accel-Buffering: no`，本端点已加）—— 不关缓冲，整条流会被攒到结束才吐出来，「逐字」当场失效而**后端日志一切正常**；
- `proxy_read_timeout` ≥ `assistant.timeout-seconds`（默认已放大到 300s）—— 只读轮期间服务端可能几十秒不写一个字节，60s 会被 nginx 掐断成 504。

## 后端改动清单（已完成）

- 新增 `web/AssistantEventStream.java`：SSE 编码 + `AssistantTurnSink` 实现。
- 改 `web/AssistantController.java`：`/messages` 返回 `text/event-stream`，写 `HttpServletResponse`；构造器注入 `ObjectMapper`。
- 改 `web/GlobalExceptionHandler.java`：`handleAssistant` 显式 `application/json`。
- 改 `service/assistant/AssistantTurnResult.java`：注释里的「响应体」改为「`final` 事件的载荷」。
- 测试：`AssistantControllerTest`（23 例，解析 SSE 帧）、`AssistantReadLoopTest`（新增 3 例旁路上报）、`AssistantDisabledControllerTest`（新增 Accept 陷阱一例）、`AssistantLiveConversationTest`（`post` 助手改为会拆流）。

## 前端改动清单（已完成）

- 新增 `src/api/assistantStream.ts`：`fetch` + `ReadableStream` 的传输层，含 SSE 帧切分器
  `AssistantFrameReader`、`AssistantStreamFailure`、`streamAssistantMessage`。
- 新增 `src/api/session.ts`：`API_BASE` / `authToken()` / `handleUnauthorized()`。
  「认证失效怎么办」两个传输层共用同一份，而它**不能**住在 `client.ts` 里 ——
  三个既有用例 `vi.mock('./client', () => ({ default: … }))` 只给 default，
  往那里加导出会让它们报 `No "API_BASE" export is defined on the "./client" mock`（本轮实际发生过）。
- 改 `src/api/client.ts`：退回成「只管 axios 实例 + 两处拦截器」，行为不变，从 `session.ts` 取那三样。
- 改 `test/whatsapp-template-lifecycle-contract.test.mjs`：那条契约把「认证失效清哪三个键」钉在 `client.ts` 上，
  跟着搬到 `session.ts`；顺手加了 `assert.doesNotMatch(client, /localStorage\.removeItem/)` ——
  `client.ts` **不许再写第二份清理**。
- 改 `src/api/types.ts`：`AssistantTurnResult` 从「响应体」改成「`final` 帧的载荷」，
  新增四帧的协议类型。
- 改 `src/api/endpoints.ts`：`sendAssistantMessage(request, handlers?)` 转调传输层。
  保留这一层只是为了「助手这一组端点在哪儿」这个问题只有一个答案。
- 改 `src/components/assistant/useAssistant.ts`：**先占位、后覆盖**；
  `isRetryableResult` 取代「靠 HTTP 503 判可重试」；`applyResult` 不再无条件清 `unavailable`。
- 改 `src/components/assistant/AssistantPanel.tsx`：流式那一条的渲染（进度 / 正文 / 「还在推」不贴任何结论标签）；
  自动滚动跟着正文一起长。
- 测试：新增 `src/api/assistantStream.test.ts`（16 例）；`AssistantPanel.test.tsx` 新增 6 例。

## 前端的三条设计决定

### 1. 先占位、后覆盖：`delta` 是草稿，`final` 才是权威

`sendAssistantMessage` 现在多收一组回调（`onStatus` / `onDelta` / `onReset`）。
`dispatch` 先追加一条**空正文的占位记录**，片段到达就地往上长，`reset` 清空重来，
结论到达时整条**覆盖**（沿用占位的 id，React key 不变，行不会闪）。

- 占位不是装饰：从按下发送到第一段文字到达之间可以有几十秒静默，那段时间屏幕上必须有东西在动。
- **失败时那半截草稿必须消失**（`applyFailure(error, placeholderId)` 也走覆盖）。
  半句话会被当成整句话读，而「`final` 才是权威」这条不变式不允许屏幕上留一句无法闭合的草稿。

### 2. 失败不再以 HTTP 状态码到达 ⇒ 可重试判据回到「结果本身」

模型挂了、参数不合法、引用的待办没了，现在都是 200 + `final{kind=ERROR}`。于是：

- `isRetryableResult(result)`：`kind=ERROR && errorCode=ASSISTANT_UNAVAILABLE` 才给「重试」。
  以前这条判据长在 503 上；现在回到结果上其实更结实 —— 「哪一类失败值得重试」与它是怎么传过来的无关。
- `applyResult` 不再无条件 `setUnavailable(null)`：供应商不可达同样是 200，那条顶部横幅
  得由它自己立起来，否则会被同一行抹掉。
- 只有「响应还没开始写」的失败（未登录 401 / 开关没开 503 / `REQUEST_INVALID`）还保留真正的 HTTP 状态码，
  `classify()` 因此多认一种来源（`instanceof AssistantStreamFailure`），并且区分
  `reachedServer`：「流断在半路、结果未知」与「服务端明确答了错」不是一件事。

### 3. `fetch` 侧的两件小事，都必须与 axios 那条路保持一致

- **不设 `Accept`**（见 Global Constraints 与后端决定 3）。
- **401 与 axios 共用 `handleUnauthorized()`**：否则发消息那条路只会显示一句看不出原因的失败，
  而用户根本不知道自己该重新登录。
- 顺带：进度文案只翻译状态（「正在查资料…」），**不显示工具名** —— 那是内部标识，面板是给人看的。

## 验收

### 全量验收（前后端，2026-09-28）

后端在**工作树快照副本**里跑全量（原树有并行 WIP，不能原地跑）：

| 维度 | 结果 |
| --- | --- |
| 后端全量（2131 例） | **2 failures / 0 errors / 2 skipped** —— 两条 failure 都不在助手面 |
| └ 助手面（46 类） | **476 例 / 0 fail / 0 err / 2 skip** |
| 前端 `npm test` | 源码契约 37 例 + vitest 78 文件 / 419 例，**全通过** |
| `tsc --noEmit` | **0 错** |

两条 failure 的归因（都不是本次改动的回退）：

1. `ContactMemoryEndToEndTest.lateInboundMessageBehindSuccessCursorIsStillSentToTheModel` —— **已知红灯**，
   属 `2026-09-23-contact-memory-bootstrap.md` 的 Gate 0，落地前就该是红的。
2. `MessageSendApplicationServiceTest.duplicateClientRequestCreatesOnePendingMessageAndOutbox` ——
   属并行任务「chatapp 出站三件套迁移」：`MessageSendApplicationService` 的未提交 diff 正在摘除
   `duplicate = messageMapper.findByClientRequestId(...)` 幂等分支，而测试仍断言 `duplicate() == true`。
   判定依据：测试与生产类 mtime 均为 **09-22**（早于本轮任何改动）、**纯 Mockito 单测**
   （无 `@SpringBootTest` ⇒ 不加载 Spring 上下文）、测试类**零 `assistant` 引用**。

**副本怎么建的**（本仓特有，别照抄通用做法）：`git archive HEAD` 在这里**不可用** ——
HEAD（`3a8feade`, 09-23）远落后工作树，`service/assistant/` 整个模块几乎都是未跟踪新文件。
正确做法是从**工作树** `rsync`（`--exclude target`，排除 `.DS_Store`）再补同级 `scripts/`
（`UserChannelOwnerBackfillSchemaContractTest` 要读 `../scripts/verify-user-channel-owners.sql`）。
副本内还要删掉未提交的 `<testIncludes>` —— 留着它 `test-compile` 只编 2 个文件，
测试**永远不被编译**、surefire 拿旧 class 跑。

### 助手面定向回归

后端（在 `demo/message-center-spring/backend`）：

```bash
mvn -Dtest='Assistant*Test' test
```

结果：**258 例 / 0 失败 / 0 错误 / 2 skipped**（`AssistantLiveConversationTest` 需要 `AI_API_KEY`，默认跳过）。

前端（在 `demo/message-center-spring/frontend`）：

```bash
env -u NODE_OPTIONS PATH="/opt/homebrew/bin:/usr/bin:/bin" npm test
env -u NODE_OPTIONS PATH="/opt/homebrew/bin:/usr/bin:/bin" node_modules/.bin/tsc --noEmit -p tsconfig.json
```

- `npm test`（源码契约 `node --test test/*.test.mjs` + `vitest run`）：**78 个文件 / 419 例全通过**。
- `tsc --noEmit`：**0 错**。
- 协议层独占 16 例（`src/api/assistantStream.test.ts`：一字节一块地切帧、汉字跨块、
  未知帧、截断的 `final`、`reset` 顺序、不带 `Accept`、401/502/503/断网/无响应体）；
  面板新增 6 例（占位→增量→覆盖、`reset` 清屏、断流不留半句、流内 `ASSISTANT_UNAVAILABLE` 仍可重试、
  服务端已下结论则不给「重试」按钮、逐字那轮结束后对话照常可用）。

### 跑前端验收前必须先摘掉 `NODE_OPTIONS`（否则一条都跑不起来）

WorkBuddy 会给每个 Node 进程注入
`NODE_OPTIONS=--require="…/cli/vendor/shim/node-language-shim.cjs"`，它让**每次 `require` 贵约两个数量级**。
实测（同一台机器、同一份 `node_modules`）：

| 模块 | 带 shim | 去掉 `NODE_OPTIONS` |
| --- | --- | --- |
| `@testing-library/react` | 35 s | **135 ms** |
| `jsdom` | 187–207 s | **656 ms** |
| `antd` | — | 1.27 s |

不摘掉它，vitest 的 worker 根本起不来：启动超时 `START_TIMEOUT = 6e4` 只有 60 秒，
失败信息是 `[vitest-pool-runner]: Timeout waiting for worker to respond`
（连既有用例也一样，与本轮改动无关）。`tsc` 同理 —— 它要为 5500 个源文件逐个读盘。
