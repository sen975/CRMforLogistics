# 企业微信连续消息段渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 修复企业微信消息空白和点击无变化问题，同时保持 Email、ChatApp、电话记录与企业微信消息的统一时间顺序。

**Architecture:** 父页面先把时间线划分为普通条目和最大连续企业微信消息段；连续段超过 15 条时继续切块。每个段块创建一个遵循企业微信官方示例的 OpenDataFrame，模板用 `wx:for` 渲染段内消息。段 Frame 在 `handleMounted` 前隐藏，按稳定段签名复用，并由父页面负责段级失败、重试、详情 iframe 和资源清理。

**Tech Stack:** Java 17、内嵌 HTML/CSS/JavaScript、企业微信 JSSDK 2.3.4、`jwxwork-1.0.0.js`、JUnit 5、Node `vm` 行为探针、Maven。

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

- [ ] **Step 1: 写失败的静态合同断言**

在 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中替换旧逐条 Frame 断言：

```java
assertContains(html, "const WECOM_SEGMENT_MESSAGE_LIMIT = 15;");
assertContains(html, "function segmentTimelineItems(items,");
assertContains(html, "data-wecom-segment-id");
assertNotContains(html, "display-type=\\\"text\\\"");
assertNotContains(html, "ww-open-message {");
assertNotContains(html, "data-wecom-message-id");
```

- [ ] **Step 2: 新增失败的分段行为探针**

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

- [ ] **Step 3: 运行测试确认 RED**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，缺少分段函数和段宿主，且仍存在逐条 `data-wecom-message-id`。

- [ ] **Step 4: 实现最小分段模型**

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

- [ ] **Step 5: 运行分段测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: 分段探针通过，原 Email、ChatApp、电话时间线测试继续通过。

- [ ] **Step 6: 复核 Task 1 边界**

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

- [ ] **Step 1: 写失败的官方模板断言**

```java
assertContains(html, "wx:for=\\\"{{data.msgList}}\\\"");
assertContains(html, "wx:key=\\\"msgid\\\"");
assertContains(html, "message-id=\\\"{{item.msgid}}\\\"");
assertContains(html, "secret-key=\\\"{{item.secretKey}}\\\"");
assertContains(html, "open-type=\\\"viewMessage\\\"");
assertContains(html, "class=\\\"wecom-segment-item\\\"");
assertNotContains(html, "display-type=");
```

- [ ] **Step 2: 写失败的段 Frame 探针**

探针构造两个段，第一段 3 条、第二段 2 条，并断言只创建两个 OpenDataFrame；同签名重挂载不增加创建次数；`handleMounted` 前 host 保持 `pending`；每个 `data.msgList` 数量和顺序正确。

- [ ] **Step 3: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，当前 `mountWeComMessage()` 仍为每条消息创建 Frame。

- [ ] **Step 4: 实现官方段模板**

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

- [ ] **Step 5: 替换逐条队列和 registry**

删除 `mountWeComMessage()`、`weComFrameRegistry`、`weComCreatingFrameKeys` 的逐条语义。`mountWeComTimelineMessages()` 改为遍历 `[data-wecom-segment-id]`，每个段调用一次 `mountWeComSegmentFrame()`。段签名相同且 host 身份相同则复用；否则先准备新实例，成功后再销毁旧实例。

- [ ] **Step 6: 运行 Task 2 测试确认 GREEN**

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

- [ ] **Step 1: 写失败测试**

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

- [ ] **Step 2: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

- [ ] **Step 3: 实现段级状态和重试**

`setWeComSegmentStatus()` 创建固定高度不超过 32px 的状态行；失败状态包含警告图标和段级重试按钮。`binderror` 只在能从事件安全解析消息 ID 时增加 `failedMessageIds`，否则设置 `segmentFailed=true`。不得生成段内逐条错误占位。

- [ ] **Step 4: 接回联系人窗口生命周期**

更新 `stashCommittedWeComContactWindow()`、`commitWeComContactWindow()`、`invalidateWeComContactWindow()`、`trimWeComContactWindows()`、登录失效和 `pagehide` 清理：全部按段 registry 销毁实例。时间线协调器按 `data-wecom-segment-id` 保留相同段宿主 DOM 身份。

- [ ] **Step 5: 运行缓存和稳定性测试确认 GREEN**

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

- [ ] **Step 1: 写失败测试**

断言企业微信段 article 不获得 `.msg.active`；`handleModal` 使用最近一次合法点击的 `activeMessageId`；详情 iframe 创建时隐藏，`load` 后显示，`error` 后删除并恢复段；无法确定消息 ID 时不创建错误详情框。

- [ ] **Step 2: 运行测试确认 RED**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest#frontendWeComInlineExpansionIsBoundedAndIndependent test
```

- [ ] **Step 3: 实现详情 load/error 生命周期**

```javascript
iframe.hidden = true;
iframe.onload = () => { iframe.hidden = false; };
iframe.onerror = () => { collapseWeComInlinePreview(key); };
```

`handleModal` 在 entry 没有合法 `activeMessageId` 时返回 `true`，让企业微信使用默认预览；只有能够确定段内消息时才调用父页面原位详情并返回 `false`。

- [ ] **Step 4: 移除企业微信段大轮廓**

`threadMessagesHtml()` 不给企业微信段写入 `active` 类；CSS 增加 `.msg.wecom-segment-message.active { outline:none; }` 作为防御，但普通 Email、ChatApp 选中样式保持不变。

- [ ] **Step 5: 运行详情测试确认 GREEN**

```bash
mvn -q -Dtest=UnifiedMessageStoreTest test
```

### Task 5: 全量验收与文档回写

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md`

- [ ] **Step 1: 运行 focused test**

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

- [ ] **Step 2: 运行完整 Maven、OpenAPI 和打包门禁**

```bash
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
```

- [ ] **Step 3: 运行 Git 边界检查**

```bash
git diff --check
git status --short
git diff --stat -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java demo/message-center-demo/src/test/resources docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md
```

- [ ] **Step 4: 记录真实验收边界**

自动化通过后把计划状态更新为“自动化验收通过，真实企业微信授权浏览器待验收”。只有用户侧确认消息正文可见、点击详情可用、刷新无空框后，才能把设计标为完成。

## Execution Choice

本任务在当前会话使用 Inline Execution。原因是共享 `App.java` 含大量任务前未提交改动，主 agent 必须逐 hunk 复核，不能把同一文件交给并行 agent 修改。
