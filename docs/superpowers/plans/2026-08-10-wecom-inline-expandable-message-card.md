# 企业微信连续消息段渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 修复企业微信消息空白和点击无变化问题，同时保持 Email、ChatApp、电话记录与企业微信消息的统一时间顺序。

**Architecture:** 父页面先把时间线划分为普通条目和最大连续企业微信消息段；连续段超过 15 条时继续切块。每个段块创建一个遵循企业微信官方示例的 OpenDataFrame，模板用 `wx:for` 渲染段内消息。段 Frame 在 `handleMounted` 前隐藏，按稳定段签名复用，并由父页面负责段级失败、重试、详情 iframe 和资源清理。

**Tech Stack:** Java 17、内嵌 HTML/CSS/JavaScript、企业微信 JSSDK 2.3.4、`jwxwork-1.0.0.js`、JUnit 5、Node `vm` 行为探针、Maven。

**状态：** Task 1～10 已实现并通过自动化验收；真实企业微信授权浏览器仍待部署后验收。

## Global Constraints

- 不使用 `display-type`，不使用裸 `ww-open-message { ... }` 样式选择器。
- 遇到任意非企业微信时间线条目立即断段；每个段块最多 15 条消息。
- Email、ChatApp、电话记录不得进入 OpenDataFrame，跨渠道顺序必须保持。
- 段 Frame `handleMounted` 前不可见，不显示空 iframe 或大占位框。
- 段签名未变化时必须复用实例；变化时新实例成功后再替换旧实例。
- `binderror` 无法稳定解析 `msgid` 时使用段级失败，不伪造单条错误关联。
- `secretKey` 和 `modalUrl` 不进入日志、DOM data 属性、审计或持久化缓存。
- 当前联系人最多 15 个活动段 Frame，三个联系人缓存合计最多 30 个段 Frame。
- `App.java` 和 `UnifiedMessageStoreTest.java` 已包含任务前未提交改动。实施阶段只做路径级和 hunk 级复核，不提交包含其他改动的整文件。

---

### Task 1: 时间线连续分段合同

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Create: `demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs`

**Interfaces:**
- Produces: `segmentTimelineItems(items, maxWeComMessages = WECOM_SEGMENT_MESSAGE_LIMIT): Array<TimelineRenderUnit>`。
- Produces: `weComSegmentId(contactPointId, messages): string`。
- Produces: 企业微信段宿主属性 `data-wecom-segment-id`，只包含稳定段 ID，不包含密钥。

- [x] **Step 1: 写失败的静态合同断言**

在 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中替换旧逐条 Frame 断言：

```java
assertContains(html, "const WECOM_SEGMENT_MESSAGE_LIMIT = 15;");
assertContains(html, "function segmentTimelineItems(items,");
assertContains(html, "data-wecom-segment-id");
assertNotContains(html, "display-type=\\\"text\\\"");
assertNotContains(html, "ww-open-message {");
assertNotContains(html, "data-wecom-message-id");
```

- [x] **Step 2: 新增失败的分段行为探针**

`wecom-segmentation-probe.mjs` 提取 `segmentTimelineItems` 和 `weComSegmentId`，断言：

```javascript
const units = context.segmentTimelineItems([
  email('e1'), wecom('w1'), wecom('w2'), chatapp('c1'),
  wecom('w3'), ...Array.from({ length:16 }, (_, index) => wecom(`tail-${index + 1}`))
], 15);

assert.deepEqual(units.map(unit => unit.kind), [
  'item', 'wecom-segment', 'item', 'wecom-segment', 'wecom-segment'
]);
assert.deepEqual(units[1].items.map(item => item.payload.sourceId), ['w1', 'w2']);
assert.equal(units[3].items.length, 15);
assert.equal(units[4].items.length, 2);
assert.equal(context.weComSegmentId('contact-1', units[1].items),
  context.weComSegmentId('contact-1', units[1].items));
assert.notEqual(context.weComSegmentId('contact-1', units[1].items),
  context.weComSegmentId('contact-1', [...units[1].items].reverse()));
```

- [x] **Step 3: 运行测试确认 RED**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，缺少分段函数和段宿主，且仍存在逐条 `data-wecom-message-id`。

- [x] **Step 4: 实现最小分段模型**

在 `threadMessagesHtml(items)` 前增加：

```javascript
function segmentTimelineItems(items, maxWeComMessages = WECOM_SEGMENT_MESSAGE_LIMIT) {
  const units = [];
  let segment = [];
  const flush = () => {
    while (segment.length) {
      units.push({ kind:'wecom-segment', items:segment.splice(0, maxWeComMessages) });
    }
  };
  (items || []).forEach(item => {
    if (item?.type === 'message' && item?.payload?.channel === 'wecom') {
      segment.push(item);
      if (segment.length === maxWeComMessages) flush();
      return;
    }
    flush();
    units.push({ kind:'item', item });
  });
  flush();
  return units;
}
```

`threadMessagesHtml()` 改为遍历 render units。普通条目沿用原渲染；企业微信段只生成一个稳定 row 和一个 `.wecom-segment-host.pending`，其 `data-wecom-segment-id` 为稳定签名，消息 ID 列表保存在页面内存映射中，不写入 HTML。

- [x] **Step 5: 运行分段测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: 分段探针通过，原 Email、ChatApp、电话时间线测试继续通过。

- [x] **Step 6: 复核 Task 1 边界**

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs
git diff --stat -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs
```

停止条件：若普通渠道顺序或 call record row 发生变化，先修复分段 owner，不进入 Task 2。

### Task 2: 段级官方 OpenDataFrame

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Create: `demo/message-center-demo/src/test/resources/wecom-segment-frame-probe.mjs`
- Delete after replacement: `demo/message-center-demo/src/test/resources/wecom-progressive-rendering-probe.mjs`

**Interfaces:**
- Consumes: `segmentTimelineItems()` 产生的段 ID 和消息顺序。
- Produces: `mountWeComSegmentFrame(host, segment, detail, viewerAuthToken, contactPointId, generation, revealOnSettle): Promise<SegmentMountResult>`。
- Produces: `destroyWeComSegmentFrame(segmentKey): void`。
- Registry: `weComSegmentFrameRegistry: Map<string, SegmentFrameEntry>`，键为 `${contactPointId}:${segmentId}`。

- [x] **Step 1: 写失败的官方模板断言**

```java
assertContains(html, "wx:for=\\\"{{data.msgList}}\\\"");
assertContains(html, "wx:key=\\\"msgid\\\"");
assertContains(html, "message-id=\\\"{{item.msgid}}\\\"");
assertContains(html, "secret-key=\\\"{{item.secretKey}}\\\"");
assertContains(html, "open-type=\\\"viewMessage\\\"");
assertContains(html, "class=\\\"wecom-segment-item\\\"");
assertNotContains(html, "display-type=");
```

- [x] **Step 2: 写失败的段 Frame 探针**

探针构造两个段，第一段 3 条、第二段 2 条，并断言只创建两个 OpenDataFrame；同签名重挂载不增加创建次数；`handleMounted` 前 host 保持 `pending`；每个 `data.msgList` 数量和顺序正确。

- [x] **Step 3: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，当前 `mountWeComMessage()` 仍为每条消息创建 Frame。

- [x] **Step 4: 实现官方段模板**

模板必须与官方示例保持同构：

```javascript
template: `
  <view wx:for="{{data.msgList}}" wx:key="msgid"
    class="wecom-segment-item" data-index="{{index}}"
    bindclick="handleSegmentMessageClick">
    <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}"
      open-type="viewMessage" binderror="handleSegmentMessageError" />
  </view>
`,
style: `.wecom-segment-item { width:100%; overflow:visible; }`,
data: { msgList:references }
```

`handleSegmentMessageClick(event)` 只在可解析 `event.currentTarget.dataset.index` 时更新 entry 的 `activeMessageId`；`handleSegmentMessageError(event)` 保存结构化段错误，不假设事件含 `msgid`。

- [x] **Step 5: 替换逐条队列和 registry**

删除 `mountWeComMessage()`、`weComFrameRegistry`、`weComCreatingFrameKeys` 的逐条语义。`mountWeComTimelineMessages()` 改为遍历 `[data-wecom-segment-id]`，每个段调用一次 `mountWeComSegmentFrame()`。段签名相同且 host 身份相同则复用；否则先准备新实例，成功后再销毁旧实例。

- [x] **Step 6: 运行 Task 2 测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: 官方模板、段数量、复用、超时和取消测试通过；旧 progressive probe 被新段 probe 完整替代。

### Task 3: 段级缓存、失败和重试

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-contact-window-probe.mjs`
- Modify: `demo/message-center-demo/src/test/resources/wecom-stable-timeline-probe.mjs`

**Interfaces:**
- Produces: `setWeComSegmentStatus(host, message, failed): void`。
- Produces: `retryWeComSegment(segmentId): Promise<SegmentMountResult | undefined>`。
- Produces: `trimWeComSegmentFrames(contactPointId, protectedSegmentIds): void`。

- [x] **Step 1: 写失败测试**

覆盖以下行为：

```text
首次段加载失败 -> 只显示一个紧凑段级错误标识
已有段刷新失败 -> 保留旧段，不替换为空白或错误大框
点击重试 -> 只重建当前段
段签名未变化 -> 后台轮询不重建
联系人缓存淘汰 -> 销毁该联系人所有段 Frame
当前联系人活动段 > 15 -> 优先淘汰离开视口且最久未使用的段
三个联系人总段数 > 30 -> 淘汰非当前联系人最久未使用的段
```

- [x] **Step 2: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

- [x] **Step 3: 实现段级状态和重试**

`setWeComSegmentStatus()` 创建固定高度不超过 32px 的状态行；失败状态包含警告图标和段级重试按钮。`binderror` 只在能从事件安全解析消息 ID 时增加 `failedMessageIds`，否则设置 `segmentFailed=true`。不得生成段内逐条错误占位。

- [x] **Step 4: 接回联系人窗口生命周期**

更新 `stashCommittedWeComContactWindow()`、`commitWeComContactWindow()`、`invalidateWeComContactWindow()`、`trimWeComContactWindows()`、登录失效和 `pagehide` 清理：全部按段 registry 销毁实例。时间线协调器按 `data-wecom-segment-id` 保留相同段宿主 DOM 身份。

- [x] **Step 5: 运行缓存和稳定性测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: contact window、stable timeline、自动刷新和资源上限探针通过。

### Task 4: 详情 iframe 与大蓝框修复

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-inline-expansion-probe.mjs`

**Interfaces:**
- Consumes: 段 entry 的 `activeMessageId`。
- Produces: `openWeComInlinePreview(host, contactPointId, messageId, modal): boolean`。

- [x] **Step 1: 写失败测试**

断言企业微信段 article 不获得 `.msg.active`；`handleModal` 使用最近一次合法点击的 `activeMessageId`；详情 iframe 创建时隐藏，`load` 后显示，`error` 后删除并恢复段；无法确定消息 ID 时不创建错误详情框。

- [x] **Step 2: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComInlineExpansionIsBoundedAndIndependent test
```

- [x] **Step 3: 实现详情 load/error 生命周期**

```javascript
iframe.hidden = true;
iframe.onload = () => { iframe.hidden = false; };
iframe.onerror = () => { collapseWeComInlinePreview(key); };
```

`handleModal` 在 entry 没有合法 `activeMessageId` 时返回 `true`，让企业微信使用默认预览；只有能够确定段内消息时才调用父页面原位详情并返回 `false`。

- [x] **Step 4: 移除企业微信段大轮廓**

`threadMessagesHtml()` 不给企业微信段写入 `active` 类；CSS 增加 `.msg.wecom-segment-message.active { outline:none; }` 作为防御，但普通 Email、ChatApp 选中样式保持不变。

- [x] **Step 5: 运行详情测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

### Task 5: 全量验收与文档回写

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md`

- [x] **Step 1: 运行 focused test**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

- [x] **Step 2: 运行完整 Maven、OpenAPI 和打包门禁**

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
```

- [x] **Step 3: 运行 Git 边界检查**

```bash
git diff --check
git status --short
git diff --stat -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/src/test/resources docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md
```

- [x] **Step 4: 记录真实验收边界**

自动化通过后把计划状态更新为“自动化验收通过，真实企业微信授权浏览器待验收”。只有用户侧确认消息正文可见、点击详情可用、刷新无空框后，才能把设计标为完成。

执行记录（2026-08-10）：`UnifiedMessageStoreTest`、完整 `mvn -q test`、OpenAPI 40 个 operation 合同、`mvn -q -DskipTests package` 与目标文件 `git diff --check` 均通过。真实授权正文与详情无法由本地 fixture 代替，保留为部署后的停止条件。

### Task 6: OpenDataFrame 方向气泡恢复

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-segment-frame-probe.mjs`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

**Interfaces:**
- Consumes: `weComSegmentMessages: Map<string, UnifiedMessage[]>` 中每条消息已有的 `direction`。
- Produces: `weComReferencesForSegment(segmentId, referencesById): Array<{msgid, secretKey, direction}>`，只增加规范化后的 `inbound/outbound`，不增加正文。
- Produces: OpenDataFrame 模板 class `wecom-segment-row`、`wecom-segment-bubble`、`inbound`、`outbound`。

- [x] **Step 1: 写失败的方向投影与气泡模板探针**

在 `wecom-segment-frame-probe.mjs` 中把三条引用设为入站、出站、入站，并增加以下断言：

```javascript
assert.match(html, /class="wecom-segment-row \{\{item\.direction\}\}"/);
assert.match(html, /class="wecom-segment-bubble"/);
assert.match(html, /\.wecom-segment-row\.inbound \{ justify-content:flex-start; \}/);
assert.match(html, /\.wecom-segment-row\.outbound \{ justify-content:flex-end; \}/);
assert.match(html, /\.wecom-segment-bubble \{[^}]*max-width:78%;/);
assert.match(html, /\.wecom-segment-row\.outbound \.wecom-segment-bubble \{[^}]*background:#f4fbf7;/);
assert.deepEqual(Array.from(configs[0].data.msgList, item => item.direction),
  ['inbound', 'outbound', 'inbound']);
```

在 `UnifiedMessageStoreTest.frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中断言方向只由现有时间线投影：

```java
assertContains(html, "direction:message?.direction === 'outbound' ? 'outbound' : 'inbound'");
assertNotContains(html, "ww-open-message {");
assertNotContains(html, "display-type=");
```

- [x] **Step 2: 运行 focused test 确认 RED**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，提示缺少 `wecom-segment-row`、`wecom-segment-bubble` 和方向投影。

- [x] **Step 3: 实现方向展示引用**

将 `weComReferencesForSegment()` 改为只合并安全方向字段：

```javascript
function weComReferencesForSegment(segmentId, referencesById) {
  return (weComSegmentMessages.get(segmentId) || [])
    .map(message => {
      const reference = referencesById.get(weComMessageId(message));
      return reference ? {
        ...reference,
        direction:message?.direction === 'outbound' ? 'outbound' : 'inbound'
      } : null;
    })
    .filter(Boolean);
}
```

- [x] **Step 4: 实现 OpenDataFrame 内部气泡模板**

保持一个连续段一个 Frame，把模板改为：

```javascript
template: `
  <view wx:for="{{data.msgList}}" wx:key="msgid"
    class="wecom-segment-row {{item.direction}}" data-index="{{index}}"
    bindclick="handleSegmentMessageClick">
    <view class="wecom-segment-bubble">
      <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}"
        open-type="viewMessage" binderror="handleSegmentMessageError" />
    </view>
  </view>
`,
style: `
  .wecom-segment-row { box-sizing:border-box; display:flex; width:100%; min-height:36px; padding:3px 8px; }
  .wecom-segment-row.inbound { justify-content:flex-start; }
  .wecom-segment-row.outbound { justify-content:flex-end; }
  .wecom-segment-bubble { box-sizing:border-box; display:inline-block; max-width:78%; min-height:30px; padding:6px 9px; overflow:hidden; border:1px solid #d7dde7; border-radius:6px; background:#fff; }
  .wecom-segment-row.outbound .wecom-segment-bubble { border-color:#b7d4c6; background:#f4fbf7; }
`
```

禁止给 `ww-open-message` 本身写选择器；方向气泡只拥有布局和外观，不拥有正文。

- [x] **Step 5: 运行 focused test 确认 GREEN**

Run:

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS，方向序列、气泡 class、敏感字段防泄漏和既有 Frame 生命周期断言同时通过。

- [x] **Step 6: 完整验收并重新打包**

Run:

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
git diff --check -- src/main/java/com/crmforlogistics/messagecenter/App.java src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java src/test/resources/wecom-segment-frame-probe.mjs ../../docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md ../../docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md
```

Expected: Maven 全量测试 0 failures/0 errors、OpenAPI 40 operations、package exit 0、目标文件无 whitespace error。真实企业微信客户端必须补验入站/出站方向和气泡实际宽度。

### Task 7: 新消息绕过 viewer 自动刷新缓存

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Add: `demo/message-center-demo/src/test/resources/wecom-viewer-auto-refresh-probe.mjs`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

**Root cause:** `refreshInBackground()` 每 5 秒拉取时间线后，`refreshWeComViewerIfDue()` 仅按 `loadedAt` 的 60 秒窗口返回。新企业微信消息虽然已经进入时间线，却没有进入 viewer 的消息引用集合，因此新段宿主保持未挂载。

- [x] **Step 1: 写失败探针并确认 RED**

探针覆盖：最近加载且消息 ID 未变化时不刷新；最近加载但出现新 ID 时立即刷新并传入最新 ID；超过 60 秒时刷新。

执行结果：新增消息场景在旧实现中请求次数为 `0`，预期为 `1`，按预期失败。

- [x] **Step 2: 实现 ID 差集判定**

新增 `weComViewerNeedsMessages(viewer, messageIds)`，比较当前时间线段 ID 与 `viewer.detail.messages[].msgid`。仅当存在未加载 ID 时绕过 60 秒限制；调用 `loadWeComViewer()` 时显式传入当前 `messageIds`。

- [x] **Step 3: focused 回归**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComViewerRefreshesImmediatelyForNewTimelineMessageIds test
mvn -q -Dtest=UnifiedMessageStoreTest test
```

两条命令均通过。

- [x] **Step 4: 完整门禁和产物核验**

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
```

验收结果（2026-08-11）：完整 `mvn -q test` 通过；OpenAPI 合同通过并验证 40 个 operation；`mvn -q -DskipTests package` 通过；`git diff --check` 通过。产物 `demo/message-center-demo/target/message-center-demo-0.1.0.jar`，大小 809 KiB，SHA-256 `72d87dc5e98c95ff746e87bb606b55768c02c67e0c49cd57fde23a586e6edb56`。部署后仍需用真实授权环境观察 `system:auto-sync` 成功日志及新消息在 5 秒轮询内自动出现在时间线和气泡中。

### Task 8: 纯企业微信全屏与合并联系人均衡分段

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs`
- Modify: `demo/message-center-demo/src/test/resources/wecom-stable-timeline-probe.mjs`
- Modify: `demo/message-center-demo/src/test/resources/wecom-contact-window-probe.mjs`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

**Interfaces:**
- Produces: `weComLayoutMode(contact): 'standalone' | 'mixed'`，渠道唯一为企业微信时选择全屏模式。
- Produces: `balancedWeComSegmentSizes(messageCount, max)`，合并联系人以 6 条为上限均衡分组。
- Produces: `data-wecom-layout`，供宿主高度、稳定 DOM 复用、联系人窗口缓存和重试路径消费。
- Produces: `weComSegmentFrameHeight(host, messageCount)` 与 `resizeWeComStandaloneFrames()`；resize 只改变外层 Frame 高度。

- [x] **Step 1: 写失败的布局与分组探针**

覆盖纯企业微信与多渠道模式选择，以及 15～18 条连续企业微信消息的 `5/5/5`、`5/5/6`、`5/6/6`、`6/6/6` 分组。旧实现缺少布局模式和均衡分组，按预期失败。

- [x] **Step 2: 实现双布局 owner**

`threadMessagesHtml()` 只从联系人渠道集合确定布局：`standalone` 继续遵守单批 15 条的 viewer 边界，Frame 使用聊天区可视高度；`mixed` 在任意其他渠道处断段，并把每个企业微信连续段均衡切为最多 6 条。

- [x] **Step 3: 收紧父页面布局**

纯企业微信行和 Frame 使用聊天区全宽并去除线程内边距；合并联系人企业微信行改为单列并隐藏头像占位。两种模式都不修改 `ww-open-message` 内部 DOM、正文或字体。

- [x] **Step 4: 保持 Frame 生命周期稳定**

稳定时间线复用、联系人窗口提交和段级重试均保留 `data-wecom-layout`。窗口 resize 时只更新 `standalone` Frame 的外层高度，不重建 OpenDataFrame。

- [x] **Step 5: focused 回归**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComSegmentsAreStableAndBounded test
mvn -q -Dtest=UnifiedMessageStoreTest test
```

两条命令均通过。完整门禁、真实桌面/移动渲染和本任务新 JAR 信息在本次收尾执行后记录。

- [x] **Step 6: 完整门禁与新产物**

执行结果（2026-08-11）：

```text
mvn -q test                                      PASS（354 tests，0 failures，0 errors）
node contracts/openapi/message-center-v1.test.mjs PASS（40 operations）
mvn -q -DskipTests package                       PASS
git diff --check -- <Task 8 目标文件>             PASS
```

沙箱内首次执行完整 Maven 时，52 个需要本地 `HttpServer` 的测试因 `SocketException: Operation not permitted` 无法绑定回环端口；在沙箱外使用同一命令重跑后通过，未修改测试或跳过用例。

新产物：`demo/message-center-demo/target/message-center-demo-0.1.0.jar`，大小 `829570` bytes，生成时间 `2026-08-11 09:33:52 +0800`，SHA-256 `90bfd0d8ae77e731afb10549ea7cb2a87c6154933173aff5b782dd98c83146a4`。

当前环境列出了 Browser 插件，但插件实际入口 `scripts/browser-client.mjs` 缺失，因此无法完成桌面/移动截图和真实授权 OpenDataFrame 正文验收。部署后仍须验证：纯企业微信联系人一批一屏、合并联系人均衡分段、入站左对齐、跨渠道顺序以及 resize/轮询不重建已有 Frame。

### Task 9: 单一企业微信固定单 Frame 窗口

**Goal:** 单一企业微信联系人不再堆叠多个整屏 Frame；聊天区固定一个全宽官方 Frame，只展示当前最近 15 条消息。多联系方式联系人保持均衡分段，但企业微信段占满聊天区宽度。点击联系人时若 Frame 尚未挂载，自动发起 viewer 加载或重试。

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs`
- Modify: `demo/message-center-demo/src/test/resources/wecom-viewer-auto-refresh-probe.mjs`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

**Interfaces:**
- Produces: `standaloneWeComWindow(items, limit = WECOM_SEGMENT_MESSAGE_LIMIT)`，只返回时间线中最近 `limit` 条企业微信消息。
- Consumes: `threadMessagesHtml(..., layoutMode)`；`standalone` 把窗口作为唯一段，`mixed` 继续调用均衡分段。
- Produces: `weComViewerHasMountedSegments(viewer, root, contactPointId)`；近期 viewer 只有在目标 Frame 已真实挂载时才能命中 60 秒缓存。

- [x] **Step 1: 写 RED 合同**

分段探针断言 16 条单一企业微信消息只产生一个 15 条段，内容为第 2～16 条；静态合同断言所有 `.wecom-message-row` 的消息壳、宿主和 Frame 均为 `width:100%`。自动刷新探针断言 viewer 仍有效但 registry 没有 mounted Frame 时必须自动调用 `loadWeComViewer()`。

- [x] **Step 2: 实现单 Frame 投影**

新增 `standaloneWeComWindow()`；`threadMessagesHtml()` 在 `standalone` 下只生成一个 render unit，不改变后端 viewer session 的 15 条上限。已有 Frame 的段签名未变时继续复用，窗口消息变化时按现有原子替换流程重建唯一 Frame。

- [x] **Step 3: 实现全宽与自动挂载**

企业微信消息行的 `.msg.wecom-message`、`.wecom-segment-host`、`.wecom-segment-frame` 全部使用聊天内容区宽度。`refreshWeComViewerIfDue()` 同时检查消息 ID 差集和 mounted Frame 状态；缺少 mounted Frame 时绕过 60 秒缓存。

- [x] **Step 4: focused 与完整门禁**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComSegmentsAreStableAndBounded test
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComViewerRefreshesImmediatelyForNewTimelineMessageIds test
mvn -q -Dtest=UnifiedMessageStoreTest test
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
```

真实授权环境停止条件：单一企业微信联系人只有一个全宽 Frame；切换联系人无需点击“刷新企业微信显示”；多联系方式企业微信气泡分别贴近聊天区左右边界。

执行结果（2026-08-11）：两个 focused 测试、完整 `UnifiedMessageStoreTest`、完整 `mvn -q test`、OpenAPI 40 operations、`mvn -q -DskipTests package` 和目标文件 `git diff --check` 均通过。新 JAR 为 `demo/message-center-demo/target/message-center-demo-0.1.0.jar`，大小 `829913` bytes，SHA-256 `3ba08b7a14d6eb50c05be10c3cfdc3a913412581e8e47ea0aa4da9569244d891`。

本地服务已在 `http://127.0.0.1:18100` 启动。当前 Browser 运行时没有可用浏览器实例，因此没有桌面/移动截图；本地 fixture 也不能替代真实授权 OpenDataFrame 正文，仍需按上述三个停止条件在服务器验收。

### Task 10: 历史投影一致性与有界重挂载

**Root cause:** 历史页先按合并时间线生成 standalone 最近 15 条宿主，却只用本次旧页的消息 ID 创建 viewer，导致唯一 Frame 引用不完整；同时自动刷新用所有历史宿主判断 mounted，缺失 Frame 时每 5 秒重建 viewer session。首次修复若无条件改用 staging 最后 15 条，又会让 mixed 旧段失去批次并切开 `5/5/6`；registry 虚假 mounted 也会阻止真实重建。

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/resources/wecom-segmentation-probe.mjs`
- Modify: `demo/message-center-demo/src/test/resources/wecom-viewer-auto-refresh-probe.mjs`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

- [x] **Step 1: 写 RED 行为探针**

覆盖 `10 条旧消息 + 10 条当前消息 -> 唯一宿主绑定最近 15 条`；有效 viewer 丢失 Frame 时不创建新 session；连续后台 tick 遵守重挂载冷却；窗口外旧 pending 宿主不参与 mounted 判定。旧实现按预期在历史请求批次和重复 session 两处失败。

- [x] **Step 2: 修复布局相关历史批次与挂载 detail**

standalone 历史加载从 staging 唯一宿主收集最近 15 条；mixed 历史加载请求本次旧页 ID，并与已有 viewer 合并。所有加载路径挂载 `mergeWeComViewer()` 产出的 `viewer.detail`，保证 registry 中 mounted Frame 与 viewer 当前真相一致。

- [x] **Step 3: 实现完整段判定、stale registry 重建和有界退避**

当前 viewer 从最近段开始按完整段装入 15 条预算，`5/5/6` 取最近 `5+6=11` 条，不切成部分段。mounted 判定只检查引用完整覆盖的宿主；registry mounted 但 Frame DOM 丢失时销毁失效实例并重建。viewer 有效但 Frame 缺失时直接复用 detail 重挂载，失败退避为 5、10、20、40、60 秒并保持 60 秒上限。

- [x] **Step 4: focused 回归**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComSegmentsAreStableAndBounded+frontendWeComViewerRefreshesImmediatelyForNewTimelineMessageIds+frontendWeComHistoryPageRequestsRenderedViewerBatchAndBoundsWindowLifetime test
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComSegmentFramesUseOfficialTemplateAndReuseStableHosts test
mvn -q -Dtest=UnifiedMessageStoreTest test
```

三条 focused 命令均通过。

- [x] **Step 5: 完整门禁与新产物**

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
git diff --check -- <Task 10 目标文件>
```

执行结果（2026-08-11）：

```text
mvn -q test                                      PASS（354 tests，0 failures，0 errors）
node contracts/openapi/message-center-v1.test.mjs PASS（40 operations）
mvn -q -DskipTests package                       PASS
git diff --check -- <Task 10 目标文件>            PASS
```

完整 Maven 使用允许本机回环端口的环境运行，未修改或跳过测试。新产物：`demo/message-center-demo/target/message-center-demo-0.1.0.jar`，大小 `830465` bytes，生成时间 `2026-08-11 11:03:50 +0800`，SHA-256 `1c37e71c44d1990001989d6e90a6e3ca0ae4a5d30cc0ab080ec6c6e20817e63e`。

真实授权浏览器仍需验证：历史上滑后 standalone 唯一 Frame 仍包含完整最近 15 条；mixed 旧段可见且无部分段；组件挂载失败时不会每 5 秒创建新 session；stale registry 能真实重建 Frame。

## Execution Choice

本任务在当前会话使用 Inline Execution。原因是共享 `App.java` 含大量任务前未提交改动，主 agent 必须逐 hunk 复核，不能把同一文件交给并行 agent 修改。
