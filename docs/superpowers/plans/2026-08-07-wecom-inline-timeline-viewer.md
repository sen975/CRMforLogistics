# 企业微信消息原位时间线渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让企业微信官方 `ww-open-message` 在统一联系人时间线的对应消息节点内展示，不再作为发送区下方的独立消息列表。

**Architecture:** `ContactTimelineService` 和现有 `/timeline` 合同继续唯一负责 Email、ChatApp、企业微信和电话记录的稳定时间排序。前端 viewer session 仅提供短时 `msgid + secretKey` 映射；时间线为企业微信消息创建原位宿主节点，并在用户加载会话后为当前可见的每条企业微信消息分别挂载官方 OpenDataFrame。底部企业微信面板只保留加载命令和状态，不保存或复制官方组件正文。

**Tech Stack:** Java 17、内嵌 HTML/CSS/JavaScript、企业微信 JSSDK 2.3.4、`jwxwork-1.0.0.js`、JUnit 5、Maven。

## Global Constraints

- 不修改专区同步输入输出、viewer 权限、登录、token、session 或审计合同。
- `secretKey` 仅存在于 viewer session 响应和 OpenDataFrame 入参，不进入 DOM 属性、全局持久化、日志或普通时间线 payload。
- 统一时间线继续由后端按 `occurredAt + typeRank + sortId` 排序。
- 每个企业微信消息节点只挂载与其 `sourceId/msgid` 匹配的官方组件。
- 翻页、静默刷新或联系人切换后，只为当前联系人和当前 DOM 重新挂载；旧异步结果不得污染新联系人。
- 不提交工作区现有无关改动。

---

### Task 1: 锁定原位渲染前端合同

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `App.pageHtml(): String`
- Produces: 企业微信时间线宿主、按 `msgid` 匹配、逐项 OpenDataFrame、底部无独立消息容器的字符串契约。

- [x] **Step 1: 写失败测试**

在 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中断言：

```java
assertContains(html, "data-wecom-message-id");
assertContains(html, "function mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId)");
assertContains(html, "detail.messages.find(item => item.msgid === messageId)");
assertContains(html, "factory.createOpenDataFrame({");
assertContains(html, "data: { message: reference }");
assertNotContains(html, "id=\"wecomViewerContainer\"");
assertNotContains(html, "style: `.msg { height: 100%; overflow: auto; }`");
```

- [x] **Step 2: 运行测试并确认因缺少原位挂载合同而失败**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: FAIL，缺少 `data-wecom-message-id` 或 `mountWeComTimelineMessages`。

---

### Task 2: 在统一时间线原位挂载官方消息

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: 时间线 `item.payload.channel/sourceId/id`、`ViewerSessionDetail.messages`、当前 `state.selectedPointId`。
- Produces: `weComMessageId(message): String`、`mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId): Promise<void>`。

- [x] **Step 1: 为企业微信时间线消息生成稳定宿主**

`renderThreadMessages()` 遇到 `m.channel === 'wecom'` 时，在原有 `message-row` 内输出带 `data-wecom-message-id` 的宿主；宿主只包含加载/未加载状态，不输出 `secretKey`。

- [x] **Step 2: 保存当前联系人短时 viewer 引用并逐项挂载**

`loadWeComViewer()` 完成 session 和 SDK 初始化后调用：

```javascript
await mountWeComTimelineMessages(detail, login.viewerAuthToken, contact.id);
```

函数先确认 `state.selectedPointId === contactPointId`，再遍历当前时间线企业微信宿主，按 `msgid` 精确找引用，为每个宿主创建一个自然高度 OpenDataFrame。模板只渲染单个：

```html
<ww-open-message message-id="{{data.message.msgid}}" secret-key="{{data.message.secretKey}}"
  open-type="viewMessage" binderror="handleMessageError" />
```

- [x] **Step 3: 让分页和重绘恢复当前联系人已加载的官方消息**

短时引用只保存在页面内存的联系人级 viewer 状态中。`renderThreadMessages()` 完成后调用恢复挂载函数；联系人切换或登录失效时清除该状态，避免跨联系人复用。

- [x] **Step 4: 将底部面板改为入口和状态**

删除 `wecomViewerContainer`，保留“加载企业微信消息”按钮与 `wecomViewerStatus`。加载、空结果和失败仅更新状态；官方消息内容只出现在时间线节点。

- [x] **Step 5: 运行针对性测试**

Run: `cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test`

Expected: PASS。

---

### Task 3: 回写真源并完成验证打包

**Files:**
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`
- Modify: `demo/message-center-demo/README.md`

**Interfaces:**
- Consumes: Task 2 的最终行为。
- Produces: 当前 viewer UI、分页重挂载和底部入口边界的运维说明。

- [x] **Step 1: 更新设计真源和 README**

明确官方消息正文按统一时间线中的 `msgid` 原位挂载；底部不再拥有第二条消息列表；单条组件失败不改变后端时间线排序或其他渠道展示。

- [x] **Step 2: 运行完整测试**

Run: `cd demo/message-center-demo && mvn -q test`

Expected: PASS，无测试失败。

- [x] **Step 3: 打包并核对生成物**

Run: `cd demo/message-center-demo && mvn -q -DskipTests package`

Expected: exit 0，生成 `target/message-center-demo-0.1.0.jar`。

- [x] **Step 4: 检查 diff 与哈希**

Run: `git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/README.md docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md docs/superpowers/plans/2026-08-07-wecom-inline-timeline-viewer.md`

Run: `shasum -a 256 demo/message-center-demo/target/message-center-demo-0.1.0.jar`

Expected: diff check exit 0，并输出新 JAR 的 SHA-256。

---

## Follow-up: Automatic Sync and Compact Viewer Rendering

**Goal:** 后端在服务运行期间自动轮询专区程序，前端把官方消息渲染为紧凑的时间线内容，不再依赖手动按钮或显示大空白框。

### Task 4: 后台企业微信同步 runtime

**Files:**
- Create: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataSyncRuntime.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataSyncRuntimeTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`

**Contract:**
- 每轮从 `WeComAuthorizationStore.resolveActive(config.wecomSuiteId(), config.wecomLoginAuthCorpId())` 解析 active 安装。
- 使用 `new WeComViewerService.ViewerSyncContext("system:auto-sync", resolvedInstallation)` 调用既有 `WeComChatDataSyncService.sync(...)`。
- `WECOM_CHATDATA_AUTO_SYNC_ENABLED` 默认 `true`，轮询间隔 `WECOM_CHATDATA_AUTO_SYNC_INTERVAL_SECONDS` 默认 `60`，范围 `15..3600`。
- 未配置安装、专区程序或私钥时 runtime 记录一次 `skipped_not_configured` 并继续服务；同步失败不影响 HTTP 服务。
- 生命周期与 `ChatAppMessageSyncRuntime` 一致：首次启动立即异步执行，固定延迟不重叠，关闭等待当前同步完成。

### Task 5: 紧凑官方组件显示与自动页面刷新

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Contract:**
- `wecom-message-host` 使用自然高度、最大宽度和内部间距，不设置固定高度或 `height:100%`；官方组件容器不再复用普通 `.msg` 的大块布局。
- viewer 面板显示最近同步状态和时间；手动按钮只触发立即同步，不是唯一数据入口。
- 现有静默刷新在企业微信登录有效时刷新联系人和当前线程；不把 secretKey 写入 DOM 或普通 payload。
- viewer session 创建与专区同步解耦；当前联系人引用每 60 秒刷新一次，并通过单一 in-flight Promise 去重。
- 官方组件默认限制为 88px，显式展开后最多 360px 并在内部滚动。

### Task 6: 文档与验收

- 更新 README 和设计真源的自动同步配置、首次轮询、失败降级和 viewer 紧凑渲染说明。
- 运行 `mvn -q -Dtest=WeComChatDataSyncRuntimeTest,UnifiedMessageStoreTest test`。
- 运行 `mvn -q test` 与 `mvn -q -DskipTests package`。
- 用隔离端口访问 `/api/contacts`、`/api/threads`，确认后台同步日志与时间线方向不回退。

**2026-08-07 验收记录：**

- [x] `WeComChatDataSyncRuntimeTest`、`ConfigTest`、`UnifiedMessageStoreTest` 定向通过。
- [x] 沙箱外完整 Maven 测试 342 项通过，0 failures / 0 errors。
- [x] 本地 fixture 服务在 `127.0.0.1:18099` 启动，页面返回 200；生成 2 个企业微信联系人，同一联系人时间线包含 `inbound` 和 `outbound`。
- [x] `mvn -q -DskipTests package` 生成最新 JAR。
- [ ] 当前会话没有可用浏览器实例，未取得桌面/移动截图或展开按钮点击证据；不得用字符串测试替代这项视觉验收。
