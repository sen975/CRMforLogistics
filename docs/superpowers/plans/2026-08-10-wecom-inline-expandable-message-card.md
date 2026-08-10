# 企业微信单行卡片与原位展开 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信时间线消息改为约 15 个中文字宽的居中单行卡片，并允许每个联系人最多 15 条消息使用官方建议高度在原位置同时展开。

**Architecture:** 现有 `ww-open-message` 继续拥有折叠态正文展示，改用官方 `display-type="text"`。点击产生的 `modalUrl` 不再进入全局弹窗，而由页面级 `weComExpandedPreviews` registry 在原消息宿主内创建详情 iframe；该 registry 与现有 OpenDataFrame、联系人窗口和登录生命周期共同清理。

**Tech Stack:** Java 17、内嵌 HTML/CSS/JavaScript、企业微信 JSSDK 2.3.4、`jwxwork-1.0.0.js`、JUnit 5、Node `vm` 行为探针、Maven、Browser 插件。

## Global Constraints

- 企业微信正文不暴露给父页面；“15 字”实现为 `15em` 视觉宽度，不做 JavaScript 字符截断。
- 折叠态使用 `display-type="text"`、13px 字号、20px 行高、单行省略，并保留 8px 纵向和 12px 横向内边距。
- 展开态必须原位显示，内容高度使用官方 `modalSize.height`，默认 360px，桌面限制为 120px 至 560px，移动端额外限制为视口高度的 60%。
- 同一联系人最多同时展开 15 条；先淘汰最早展开且离开视口的详情，否则淘汰最早展开的详情。
- `modalUrl` 只能存在于当前页面内存和 iframe `src`，不得进入日志、审计、后端、DOM data 属性或持久化缓存。
- 时间线刷新必须复用现有企业微信宿主；不修改同步、viewer session、OpenAPI、专区程序、Email、ChatApp、电话记录或 Spring 版前端。
- 不回滚或吸入工作区其他改动；每次暂存前使用路径级 diff 确认本任务边界。

---

### Task 1: 折叠态文本卡片合同

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:1165-1172`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:3663-3689`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java:2192-2279`

**Interfaces:**
- Consumes: 现有 `mountWeComMessage(host, reference, detail, viewerAuthToken, contactPointId, generation, revealOnSettle)`。
- Produces: 折叠模板 `ww-open-message display-type="text" open-type="viewMessage"`。
- Produces: `.wecom-message-frame` 为 `15em + 24px` 宽、36px 高的居中边框容器。

- [ ] **Step 1: 写折叠态失败测试**

在 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 中将旧的 `280×40` 断言替换为：

```java
assertContains(html, "display-type=\"text\"");
assertContains(html, "open-type=\"viewMessage\"");
assertContains(html, "width:calc(15em + 24px); height:36px;");
assertContains(html, "padding:8px 12px;");
assertContains(html, "white-space:nowrap;");
assertContains(html, "text-overflow:ellipsis;");
assertContains(html, "font-size:13px; line-height:20px; text-align:center;");
assertNotContains(html, "width:280px; height:40px;");
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，至少报告缺少 `display-type="text"`，并仍找到 `width:280px; height:40px;`。

- [ ] **Step 3: 实现最小折叠样式**

将外层 frame 改为明确尺寸和居中布局：

```css
.wecom-message-frame {
  display:grid;
  place-items:center;
  box-sizing:border-box;
  width:calc(15em + 24px);
  height:36px;
  max-width:100%;
  min-width:0;
  overflow:hidden;
  border:1px solid var(--line);
  border-radius:6px;
  background:#fff;
  font-size:13px;
}
```

将 OpenDataFrame 模板和内部样式改为：

```javascript
template: `
  <view class="message-item">
    <ww-open-message message-id="{{data.message.msgid}}" secret-key="{{data.message.secretKey}}"
      display-type="text" open-type="viewMessage" binderror="handleMessageError" />
  </view>
`,
style: `.message-item { box-sizing:content-box; display:flex; align-items:center; justify-content:center; width:15em; height:20px; padding:8px 12px; overflow:hidden; } ww-open-message { display:block; width:15em; max-width:15em; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; font-size:13px; line-height:20px; text-align:center; }`,
```

- [ ] **Step 4: 运行测试确认 GREEN**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS，且没有新增 warning。

- [ ] **Step 5: 复核本任务 diff**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git diff -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
```

Expected: 无格式错误；只接受折叠卡片与对应断言的新增 hunk，不回滚文件内既有改动。

---

### Task 2: 原位展开 registry 与尺寸策略

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:1165-1188`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:1318-1345`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:3410-3428`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:3484-3780`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
- Create: `demo/message-center-demo/src/test/resources/wecom-inline-expansion-probe.mjs`

**Interfaces:**
- Produces: `const WECOM_EXPANDED_PREVIEW_LIMIT = 15`。
- Produces: `const weComExpandedPreviews = new Map()`，键为 `${contactPointId}:${messageId}`。
- Produces: `weComPreviewHeight(modalSize): number`。
- Produces: `openWeComInlinePreview(host, contactPointId, messageId, modal): boolean`。
- Produces: `collapseWeComInlinePreview(key): void`、`clearWeComInlinePreviews(contactPointId?): void`。
- Consumes: `handleModal({ modalUrl, modalSize })`、宿主 `data-wecom-message-id` 和现有稳定 DOM 协调。

- [ ] **Step 1: 写原位展开失败断言**

在 `frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions()` 增加：

```java
assertContains(html, "const WECOM_EXPANDED_PREVIEW_LIMIT = 15;");
assertContains(html, "const weComExpandedPreviews = new Map();");
assertContains(html, "function weComPreviewHeight(modalSize)");
assertContains(html, "function openWeComInlinePreview(host, contactPointId, messageId,");
assertContains(html, "function collapseWeComInlinePreview(key)");
assertContains(html, "openWeComInlinePreview(host, contactPointId, reference.msgid,");
assertContains(html, "className = 'wecom-message-collapse'");
assertContains(html, "setAttribute('aria-label', '收起企业微信消息')");
assertNotContains(html, "openWeComModal({ modalUrl, modalSize });\n              return false;");
```

新增测试入口：

```java
@Test
void frontendWeComInlineExpansionIsBoundedAndIndependent() throws Exception {
    Path dir = Files.createTempDirectory("message-center-wecom-inline-expansion-test");
    Path html = dir.resolve("page.html");
    Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
    Path probe = Path.of(getClass().getResource("/wecom-inline-expansion-probe.mjs").toURI());
    Process process = new ProcessBuilder("node", probe.toString(), html.toString())
            .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("wecom inline expansion probe timed out");
    }
    if (process.exitValue() != 0) {
        throw new AssertionError("wecom inline expansion probe failed:\n" + output);
    }
}
```

- [ ] **Step 2: 创建失败的 Node 行为探针**

`wecom-inline-expansion-probe.mjs` 从页面脚本提取 `weComPreviewHeight` 至 `openWeComInlinePreview` 的完整函数区间，在最小假 DOM 中执行并断言：

```javascript
assert.equal(context.weComPreviewHeight({ height:80 }), 120);
assert.equal(context.weComPreviewHeight({ height:900 }), 560);
assert.equal(context.weComPreviewHeight({}), 360);

assert.equal(context.openWeComInlinePreview(hosts[0], 'contact-1', 'm1', {
  modalUrl:'https://work.weixin.qq.com/preview/1', modalSize:{ height:240 }
}), true);
assert.equal(context.openWeComInlinePreview(hosts[1], 'contact-1', 'm2', {
  modalUrl:'https://work.weixin.qq.com/preview/2', modalSize:{ height:320 }
}), true);
assert.equal(context.weComExpandedPreviews.size, 2,
  'two messages must remain expanded independently');
assert.equal(hosts[0].classList.contains('expanded'), true);
assert.equal(hosts[1].classList.contains('expanded'), true);

for (let index = 3; index <= 16; index++) {
  context.openWeComInlinePreview(hosts[index - 1], 'contact-1', `m${index}`, {
    modalUrl:`https://work.weixin.qq.com/preview/${index}`,
    modalSize:{ height:300 }
  });
}
assert.equal(context.weComExpandedPreviews.size, 15);
assert.equal(hosts[0].classList.contains('expanded'), false,
  'the oldest offscreen preview must be collapsed first');

assert.equal(context.openWeComInlinePreview(hosts[16], 'contact-1', 'bad', {
  modalUrl:'http://example.com/insecure', modalSize:{ height:300 }
}), false);
assert.equal(hosts[16].children.length, 1,
  'invalid preview URL must leave the folded frame intact');
```

探针还必须将 `matchMedia('(max-width: 640px)')` 设为命中、`innerHeight=600`，断言高度上限为 360px；调用收起按钮后断言 iframe `src` 变为 `about:blank`、wrapper 被删除、另一条仍保持展开。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，缺少展开常量、函数和探针所需行为。

- [ ] **Step 4: 实现详情尺寸与原位 DOM**

增加常量和 registry：

```javascript
const WECOM_EXPANDED_PREVIEW_LIMIT = 15;
const WECOM_PREVIEW_MIN_HEIGHT = 120;
const WECOM_PREVIEW_DEFAULT_HEIGHT = 360;
const WECOM_PREVIEW_MAX_HEIGHT = 560;
const weComExpandedPreviews = new Map();
```

实现高度策略：

```javascript
function weComPreviewHeight(modalSize) {
  const suggested = Number(modalSize?.height);
  let height = Number.isFinite(suggested) && suggested > 0
    ? suggested : WECOM_PREVIEW_DEFAULT_HEIGHT;
  height = Math.min(WECOM_PREVIEW_MAX_HEIGHT, Math.max(WECOM_PREVIEW_MIN_HEIGHT, height));
  if (window.matchMedia?.('(max-width: 640px)')?.matches) {
    const viewportLimit = Math.max(WECOM_PREVIEW_MIN_HEIGHT, Math.floor(window.innerHeight * 0.6));
    height = Math.min(height, viewportLimit);
  }
  return height;
}
```

`openWeComInlinePreview()` 必须校验 HTTPS，创建 `.wecom-message-preview`、独立 `.wecom-message-preview-toolbar`、图标按钮和 `.wecom-message-preview-content > iframe`，设置 `iframe.referrerPolicy = 'no-referrer-when-downgrade'`，并只将 URL 写入 `iframe.src`。成功后给 host 增加 `expanded` 类并写入 registry；失败时删除新节点并保持折叠 frame 可见。

- [ ] **Step 5: 实现多条上限和确定性淘汰**

在创建新详情前获取同联系人 entry，按 `openedAt` 升序排列。使用以下可见性判断选择淘汰对象：

```javascript
function weComPreviewInViewport(entry) {
  const rect = entry.wrapper?.getBoundingClientRect?.();
  return Boolean(rect && rect.bottom > 0 && rect.top < window.innerHeight);
}
```

当已有 15 条时，先选择 `!weComPreviewInViewport(entry)` 的最早 entry；没有离开视口的候选时选择全体最早 entry。`collapseWeComInlinePreview()` 必须将 iframe 导航到 `about:blank`、删除 wrapper、移除 `expanded` 类并删除 map entry，但不得销毁同一消息的折叠 OpenDataFrame。

- [ ] **Step 6: 将 handleModal 改为原位展开**

替换现有全局弹窗调用：

```javascript
handleModal({ modalUrl, modalSize }) {
  openWeComInlinePreview(host, contactPointId, reference.msgid, { modalUrl, modalSize });
  return false;
},
```

保留 `openWeComModal()` 供其他潜在入口使用，但企业微信时间线不得调用它。

- [ ] **Step 7: 实现居中展开样式**

增加明确的布局合同：

```css
.wecom-message-host.expanded { width:min(560px,100%); }
.wecom-message-host.expanded > .wecom-message-frame { display:none; }
.wecom-message-preview { box-sizing:border-box; display:grid; gap:8px; width:min(560px,100%); padding:12px; border:1px solid var(--line); border-radius:6px; background:#fff; }
.wecom-message-preview-toolbar { min-height:24px; display:flex; align-items:center; justify-content:flex-end; }
.wecom-message-collapse { width:24px; height:24px; padding:0; display:grid; place-items:center; border:0; background:transparent; }
.wecom-message-preview-content { display:grid; place-items:center; width:100%; min-width:0; overflow:hidden; }
.wecom-message-preview-content iframe { display:block; width:100%; height:100%; border:0; }
```

移动端保持 12px 内边距，wrapper 不得超过时间线宽度，不新增横向滚动。

- [ ] **Step 8: 运行测试确认 GREEN**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS；Node 探针输出 `wecom inline expansion behavior ok`，且已有渐进渲染和稳定时间线探针继续通过。

---

### Task 3: 生命周期清理与刷新稳定性

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:1480-1535`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:2060-2140`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:2280-2300`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:3492-3625`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java:3760-3810`
- Test: `demo/message-center-demo/src/test/resources/wecom-inline-expansion-probe.mjs`
- Test: `demo/message-center-demo/src/test/resources/wecom-stable-timeline-probe.mjs`

**Interfaces:**
- Consumes: Task 2 的 `collapseWeComInlinePreview()` 和 `clearWeComInlinePreviews()`。
- Produces: `pruneDisconnectedWeComInlinePreviews(): void`。
- Produces: OpenDataFrame eviction、联系人失效、登录失效和页面卸载共用的详情清理行为。

- [ ] **Step 1: 扩展失败探针覆盖清理路径**

在原位展开探针中覆盖：

```javascript
context.clearWeComInlinePreviews('contact-1');
assert.equal(context.weComExpandedPreviews.size, 1,
  'clearing one contact must preserve another contact');

detachedHost.isConnected = false;
context.pruneDisconnectedWeComInlinePreviews();
assert.equal(context.weComExpandedPreviews.has('contact-2:detached'), false);
assert.equal(detachedIframe.src, 'about:blank');
```

扩展稳定时间线探针：先给复用的企业微信 host 增加 `expanded` 类和 preview 子节点，再执行 `reconcileThreadMessages()`，断言 host 和 preview 子节点对象身份都不变。

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，缺少断开宿主清理或时间线刷新未保留 preview。

- [ ] **Step 3: 接入所有资源释放入口**

在以下入口调用相应清理函数：

- `returnToWeComLogin()`：`clearWeComInlinePreviews()`；
- `invalidateWeComContactWindow(contactPointId)`：`clearWeComInlinePreviews(contactPointId)`；
- `resetWeComContactFrames(contactPointId)`：`clearWeComInlinePreviews(contactPointId)`；
- `trimWeComFrameRegistry()` 和 `reserveWeComFrameCapacity()` 淘汰单条前：`collapseWeComInlinePreview(key)`；
- `retryWeComMessage(messageId)` 销毁旧 frame 前：收起对应 key；
- `reconcileThreadMessages()` 完成 DOM 提交后：`pruneDisconnectedWeComInlinePreviews()`；
- `beforeunload`：`clearWeComInlinePreviews()`。

清理函数不得输出 URL、密钥或完整 entry。

- [ ] **Step 4: 保持展开 DOM 的刷新复用**

保留现有企业微信分支只更新外层 class、元信息和选中态的行为。不得用新 `innerHTML` 或 `replaceChildren()` 替换相同稳定键的企业微信 host；只有消息从当前时间线彻底删除后，`pruneDisconnectedWeComInlinePreviews()` 才释放详情。

- [ ] **Step 5: 运行测试确认 GREEN**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS；展开详情跨静默刷新保留，淘汰与登录清理后 map 为空、iframe 均为 `about:blank`。

---

### Task 4: 文档、完整验收与打包

**Files:**
- Modify: `demo/message-center-demo/README.md:455-456`
- Modify: `docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md`

**Interfaces:**
- Consumes: Tasks 1 至 3 的最终 UI 行为。
- Produces: 用户运行说明、规格状态、自动化证据、浏览器证据和可部署 JAR。

- [ ] **Step 1: 回写 README 与规格状态**

在 README 企业微信前端说明后增加：默认消息使用约 15 字宽的单行官方文本组件；点击后在时间线原位展开；同一联系人最多 15 条；详情高度由官方建议值决定并有移动/桌面上限；切换、淘汰和重新登录会释放详情 iframe。将规格状态更新为“已实施，已验收”必须等到后续所有门禁通过。

- [ ] **Step 2: 运行完整自动化验收**

Run:

```bash
cd demo/message-center-demo
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
```

Expected: 全部 PASS，无 warning、超时或 Node 探针失败。

- [ ] **Step 3: 启动隔离本地服务**

Run:

```bash
cd demo/message-center-demo
WEB_PORT=18109 LOCAL_DEV_MODE=true LOCAL_WECOM_DATA_SOURCE=fixture mvn -q exec:java -Dexec.args=web
```

Expected: 服务持续运行于 `http://127.0.0.1:18109/`；若端口被占用，使用 18110 并记录实际 URL。

- [ ] **Step 4: 使用 Browser 做桌面与移动验收**

Browser 必须检查页面身份、非空 DOM、无框架错误层、控制台 error/warn、截图和目标交互。桌面使用约 1440×900，移动使用约 390×844；至少验证两条企业微信消息同时展开、内容四向不贴边、收起一条不影响另一条、展开后无重叠和横向滚动、静默刷新不重建展开宿主。

真实企业微信组件若在本地 fixture 中不可用，浏览器只验证可达的静态与本地投影状态，并明确记录真实 `handleModal` 交互仍需在企业微信授权环境验收；不得伪造通过。

- [ ] **Step 5: 打包和边界复核**

Run:

```bash
cd demo/message-center-demo
mvn -q -DskipTests package
cd ../..
git diff --check -- demo/message-center-demo docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md
git status --short
```

Expected: `target/message-center-demo-*.jar` 和项目既有分发 JAR 生成成功；diff check 通过；状态报告区分本任务文件与既有工作区改动。

- [ ] **Step 6: 提交本任务文件**

只有在路径级 diff 证明没有吸入无关 hunk 时才执行：

```bash
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java \
  demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java \
  demo/message-center-demo/src/test/resources/wecom-inline-expansion-probe.mjs \
  demo/message-center-demo/src/test/resources/wecom-stable-timeline-probe.mjs \
  demo/message-center-demo/README.md \
  docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md \
  docs/superpowers/plans/2026-08-10-wecom-inline-expandable-message-card.md
git diff --cached --check
git diff --cached --name-only
git commit -m "feat: add inline expandable WeCom cards"
```

若同一文件存在无法分离的用户改动，停止提交，只保留已验证工作区改动并在交接中列出，不使用 `git add .` 或交互式回滚。

---

## Self-Review

- Spec coverage: 单行 `15em`、双向居中、四向内边距、原位展开、官方高度、多条展开、每联系人 15 条上限、确定性淘汰、刷新稳定、失败恢复、敏感 URL 边界、桌面/移动验收和平台停止条件均有对应任务。
- Placeholder scan: 计划不包含未命名实现步骤、模糊错误处理或未定义接口。
- Type consistency: `weComExpandedPreviews`、`openWeComInlinePreview()`、`collapseWeComInlinePreview()`、`clearWeComInlinePreviews()` 和 `pruneDisconnectedWeComInlinePreviews()` 在 Task 2 定义，并由 Task 3 的清理入口复用。
- Scope: 不改后端合同、专区程序和 Spring 版；全局 modal 保留但不再由时间线调用。
