# 企业微信渐进窗口渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信统一时间线改为每页读取 15 条、首屏和历史页按 5 至 8 条完整视口原子提交、最多 4 个 OpenDataFrame 并发且全局最多 30 个活跃组件的渐进窗口渲染。

**Architecture:** `UnifiedMessageStore` 继续拥有统一时间线排序和游标；viewer session 只接收当前时间线页中最多 15 个 `messageIds`，并在服务端重新校验联系人和员工归属。前端用稳定消息键复用时间线 DOM，在页面内暂存目标联系人首屏，通过官方 `handleMounted()` 或明确失败状态完成单条准备，凑够一个视口后原子切换或插入；加载状态只投影到联系人名称旁。

**Tech Stack:** Java 17、Gson、内嵌 HTML/CSS/JavaScript、企业微信 JSSDK 2.3.4、`jwxwork-1.0.0.js`、JUnit 5、Node `vm` 行为探针、Maven。

## Global Constraints

- 企业微信正文不进入数据库、普通时间线 payload、DOM 属性、本地存储或日志。
- 时间线接口每页固定 15 条；viewer `messageIds` 每批最多 15 个、去重、单项最多 256 字符。
- 冷联系人首屏优先准备时间线底部 5 至 8 条；OpenDataFrame 并发上限为 4，后台队列上限为 15，活跃组件全局上限为 30。
- 单条加载成功只认官方 `handleMounted()`；失败必须在原时间位置显示紧凑失败标识和单条重试入口。
- 后台刷新和历史分页不得重建已挂载的企业微信组件。
- 不修改 Email、ChatApp、电话记录的业务合同，不提交工作区其他改动。

---

### Task 1: Viewer 批次引用合同

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/LocalWeComDevelopmentService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/LocalWeComDevelopmentServiceTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`

**Interfaces:**
- Produces: `createViewerSession(String contactPointId, String viewerAuthToken, List<String> messageIds)`。
- Produces: `createSession(String contactPointId, String viewerAuthToken, List<String> messageIds)` for local mode。
- Consumes: POST body `messageIds: string[]`，1 至 15 项。

- [ ] **Step 1: 写失败测试**

覆盖默认值 15、配置值 16 被拒绝、只按请求顺序返回所需引用，以及空数组、重复 ID、超长 ID、跨联系人 ID、跨员工 ID 和不存在 ID 被拒绝。路由测试必须证明未知字段仍被拒绝，OpenAPI 要求 `messageIds` 且 `maxItems: 15`。

- [ ] **Step 2: 运行失败测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComViewerServiceTest,LocalWeComDevelopmentServiceTest,UnifiedMessageStoreTest test`

Expected: FAIL，因为现有 session 方法不接收 `messageIds`，配置默认值仍为 10。

- [ ] **Step 3: 实现批次校验和精确读取**

将 session owner 改为显式请求引用：

```java
public ViewerSessionResponse createViewerSession(String contactPointId,
        String viewerAuthToken, List<String> messageIds) throws Exception
```

先校验数组 1..15、无重复、每项 1..256，再扫描当前联系人的记录；只有全部请求 ID 同时满足联系人和员工归属时才创建 session，返回顺序与 `messageIds` 一致。`Config.wecomViewerMaxMessages()` 改为 `boundedInt(..., 15, 1, 15)`；本地服务执行相同合同。

- [ ] **Step 4: 接线 HTTP 和 OpenAPI**

`App` 只允许 `conversationId/contactPointId/viewerAuthToken/messageIds`，用结构化 Gson 数组解析器读取 ID；OpenAPI 将 `messageIds` 加入 required，并把 detail `messages.maxItems` 改为 15。

- [ ] **Step 5: 验证 Task 1**

Run: `cd demo/message-center-demo && mvn -q -Dtest=ConfigTest,WeComViewerServiceTest,LocalWeComDevelopmentServiceTest,UnifiedMessageStoreTest test`

Run: `cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs`

Expected: 全部 PASS。

---

### Task 2: 15 条分页与稳定键时间线

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Produces: `timelineItemKey(item): string`、`reconcileThreadMessages(contact, items, target): HTMLElement[]`。
- Consumes: 现有 `threadRenderKey`、`mergeThreadMessages` 和 `THREAD_PAGE_MAX_MESSAGES=200`。

- [ ] **Step 1: 写失败的前端合同和 Node 行为探针**

断言 `THREAD_PAGE_SIZE = 15`，同一稳定键的企业微信行在刷新和历史页插入后保持对象身份，旧组件宿主不被 `innerHTML` 销毁；不同 render key 的普通消息允许更新。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: FAIL，现有页大小为 10 且 `renderThreadMessages()` 整体替换 `innerHTML`。

- [ ] **Step 3: 实现稳定键协调**

为每个消息行写入 `data-timeline-key` 和不含密钥的 render fingerprint。协调器按后端 `type + sortId` 复用已有行；企业微信行只更新外层方向、元信息和选中态，不替换已挂载 host。新增历史行插入后继续使用现有滚动高度补偿。

- [ ] **Step 4: 验证分页和复用**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: PASS；Node 探针请求 URL 使用 `limit=15`，刷新前后企业微信宿主对象严格相等。

---

### Task 3: 渐进首屏、并发队列和失败恢复

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Produces: `prepareWeComBatch(contactPointId, hosts, detail, viewerAuthToken): Promise<BatchResult>`。
- Produces: `runWeComRenderQueue(jobs, concurrency = 4): Promise<RenderResult[]>`。
- Produces: `retryWeComMessage(messageId): Promise<void>`。
- Consumes: Task 1 的精确 `messageIds` session 与 Task 2 的稳定宿主。

- [ ] **Step 1: 写失败的渲染状态测试**

Node 探针使用假的 `createOpenDataFrame()`：记录并发数，手动触发 `handleMounted()` 和 `error()`，断言并发峰值为 4、首屏 5 至 8 条全部 settle 前不提交、失败原位显示重试、重试只创建该条组件、已 mounted 消息不重复创建。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: FAIL，现有代码同步遍历全部 host、没有 `handleMounted` 门、展开按钮仍存在。

- [ ] **Step 3: 实现页面级状态和视觉合同**

增加常量：

```javascript
const WECOM_RENDER_CONCURRENCY = 4;
const WECOM_VIEWPORT_COMMIT_MIN = 5;
const WECOM_VIEWPORT_COMMIT_MAX = 8;
const WECOM_ACTIVE_FRAME_LIMIT = 30;
```

联系人名称旁增加 12px 圆形 spinner 和失败重试图标。删除 88/360px 裁剪、展开按钮和大状态框；成功 host 使用 `width:fit-content`、自然高度、紧凑内边距和居中布局。失败 host 使用同尺寸紧凑行，包含警告图标、消息加载失败和图标重试按钮。

- [ ] **Step 4: 实现队列、原子首屏和历史页提交**

扫码成功后预加载两个 SDK。冷联系人先在连接到 top frame 的不可见暂存容器中准备底部首屏；5 至 8 条全部 settle 后一次替换聊天面板。历史页按距滚动锚点最近的顺序准备，凑够一个视口后一次插入并补偿 `scrollTop`。全局 registry 按最近访问淘汰至 30 个，最近一个联系人最多保留 8 个首屏组件。

- [ ] **Step 5: 实现失败和清理**

`handleMounted()` 原子标记 ready；模板 `binderror` 和顶层 `error` 只 settle 一次。登录失效清空 registry 并返回扫码；普通失败显示原位重试且脱敏上报。联系人取消准备、session 过期和页面卸载清理后台 host、引用和队列。

- [ ] **Step 6: 验证 Task 3**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: PASS，无展开按钮、无可见加载壳、并发和缓存边界探针通过。

---

### Task 4: 文档、视觉验收和打包

**Files:**
- Modify: `demo/message-center-demo/README.md`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`
- Modify: `docs/superpowers/plans/2026-08-07-wecom-progressive-window-rendering.md`

**Interfaces:**
- Consumes: Tasks 1 至 3 的最终行为。
- Produces: 本地运行、配置边界、失败恢复和真实浏览器证据。

- [ ] **Step 1: 回写用户与内部文档**

README 说明 15 条数据分页、首屏原子切换、4 并发、30 活跃组件和冷联系人无法保证绝对零等待；配置示例将 `WECOM_VIEWER_MAX_MESSAGES=15` 标为可选且硬上限 15。

- [ ] **Step 2: 运行完整自动化验收**

Run: `cd demo/message-center-demo && mvn -q test`

Run: `cd demo/message-center-demo && node contracts/openapi/message-center-v1.test.mjs`

Expected: 全部 PASS，无 warning 和失败。

- [ ] **Step 3: 启动隔离本地服务并做浏览器验收**

Run: `cd demo/message-center-demo && WEB_PORT=18109 LOCAL_DEV_MODE=true LOCAL_WECOM_DATA_SOURCE=fixture mvn -q exec:java -Dexec.args=web`

在桌面和移动 viewport 检查：联系人 spinner 不挤压名字；企业微信气泡与 Email/ChatApp 对齐；没有展开按钮和大空框；失败行文本不溢出；滚动加载不重建已就绪消息。

- [ ] **Step 4: 打包与边界复核**

Run: `cd demo/message-center-demo && mvn -q -DskipTests package`

Run: `git diff --check -- demo/message-center-demo docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md docs/superpowers/plans/2026-08-07-wecom-progressive-window-rendering.md`

Expected: JAR 生成成功，diff check 通过；只报告本任务相关文件，不清理其他用户改动。
