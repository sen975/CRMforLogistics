# 企业微信单 Frame 历史消息缓存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 同一联系人切换和向上加载历史企业微信消息时复用一个 OpenDataFrame，只为尚未缓存的 `msgid` 读取一次性 session，并保证旧请求不会覆盖当前联系人。

**Architecture:** 保留后端一次性 session 和现有时间线 cursor。前端在当前 viewer token 生命周期内维护有上限的 `contactPointId + msgid -> secretKey` 内存缓存；`WeComConversationPanel` 持有稳定 frame，通过官方 SDK 2.3.4 的 `setData` 更新 `msgList`。混合时间线中的 `WeComTimelineSegment` 继续使用现有 registry，不与单联系人整页 frame 共享生命周期。

**Tech Stack:** React 18, TypeScript 5.6, `@wecom/jssdk@2.3.4`, Vitest, Testing Library, Vite.

## Global Constraints

- 不把 viewer session id、secretKey 或企业微信凭据写入 localStorage、IndexedDB、URL、日志或持久化数据库。
- `msgid -> secretKey` 缓存只存在内存，viewer token 变化、退出登录或缓存过期时清空。
- 单次 `POST /v1/wecom/conversation-view/sessions` 的 `messageIds` 不超过后端 `wecomViewerMaxMessages`（当前默认 15）。
- frame 更新只使用官方 SDK 公开的 `setData`、`handleUpdated` 和 `dispose`；不调用未声明的私有方法。
- A -> B -> A 的旧请求不得写入当前联系人状态；所有异步回调必须通过 generation 和 `AbortSignal` 校验。
- 历史消息仍通过 `fetchThread(cursor, limit)` 获取；viewer 缓存不能伪造未从时间线加载的消息。
- 每个任务先写失败测试，再实现最小代码；每个任务只运行其专项测试和 TypeScript 检查。

## File Map

- Create: `demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.ts` — 有界、带 TTL、按 viewer token 作用域的内存缓存。
- Test: `demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.test.ts` — 缓存命中、失效、上限和 token 隔离。
- Modify: `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts` — 对齐 `@wecom/jssdk@2.3.4` 的 frame 类型。
- Modify: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts` — 暴露按 cache miss 获取消息的接口，批量调用一次性 session。
- Test: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.test.ts` — cache hit、批量 miss、token 变化和 abort。
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.tsx` — 单联系人稳定 frame 的创建、清空、更新、超时和错误显示。
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx` — 保持 frame host 稳定，把联系人/历史消息交给 frame controller。
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.test.tsx` — A -> B、A -> B -> A、更新失败和无报错占位保护。
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx` — 仅保留混合时间线 segment 的 registry 生命周期，并适配扩展后的 SDK 类型。
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx` — 保留现有混合时间线回归，补充 `setData` 类型兼容断言。
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx` — 明确企业微信面板始终收到当前联系人已加载的全部 `wecomItems`，并保持历史分页游标和滚动位置。
- Test: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx` — 历史页合并后传给面板的消息集合和联系人切换 generation。

---

### Task 1: 建立有界 viewer 消息缓存

**Files:**
- Create: `demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.ts`
- Test: `demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.test.ts`

**Interfaces:**
- Produces `WeComViewerMessageCache` with `get(token, contactPointId, msgid)`, `set(token, contactPointId, message)`, `setMany(...)`, `clearToken(token)`, `clearAll()`, `size()`。
- `get` 命中时更新 LRU 时间；超过容量时删除最旧条目；超过 TTL 的条目视为 miss 并删除。
- 缓存只存 `{ msgid, secretKey }`，不存 session id、不存完整消息正文。

- [ ] **Step 1: 写失败测试**

```ts
it('隔离 token 和 contactPointId，并在命中时返回 secretKey', () => {
  const cache = new WeComViewerMessageCache({ maxEntries: 2, ttlMs: 60_000, now: () => 100 });
  cache.set('token-a', 'wecom:contact-a', { msgid: 'm1', secretKey: 's1' });
  expect(cache.get('token-a', 'wecom:contact-a', 'm1')).toEqual({ msgid: 'm1', secretKey: 's1' });
  expect(cache.get('token-a', 'wecom:contact-b', 'm1')).toBeUndefined();
  expect(cache.get('token-b', 'wecom:contact-a', 'm1')).toBeUndefined();
});

it('TTL 到期和容量超限会淘汰条目', () => {
  let now = 0;
  const cache = new WeComViewerMessageCache({ maxEntries: 2, ttlMs: 10, now: () => now });
  cache.set('t', 'c', { msgid: 'm1', secretKey: 's1' });
  cache.set('t', 'c', { msgid: 'm2', secretKey: 's2' });
  expect(cache.get('t', 'c', 'm1')).toEqual({ msgid: 'm1', secretKey: 's1' });
  cache.set('t', 'c', { msgid: 'm3', secretKey: 's3' });
  expect(cache.get('t', 'c', 'm2')).toBeUndefined();
  now = 11;
  expect(cache.get('t', 'c', 'm1')).toBeUndefined();
});
```

- [ ] **Step 2: 运行专项测试确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/wecom/WeComViewerMessageCache.test.ts`

Expected: FAIL because `WeComViewerMessageCache` does not exist.

- [ ] **Step 3: 实现最小缓存**

使用 `Map<string, Entry>`，键格式为 `${token}\0${contactPointId}\0${msgid}`；每次 `get`/`set` 更新单调序号，清理过期条目后按最小序号淘汰。默认容量设为 512 条、TTL 15 分钟，并允许测试注入 `now`。

- [ ] **Step 4: 运行专项测试确认通过**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/wecom/WeComViewerMessageCache.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交独立变更**

```bash
git add demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.ts \
  demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.test.ts
git commit -m "feat: add bounded wecom viewer message cache"
```

### Task 2: 对齐 SDK 类型并让 viewer 只读取 cache miss

**Files:**
- Modify: `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`
- Test: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.test.ts`

**Interfaces:**
- `WeComOpenDataFrame` 增加 `data: Record<string, unknown>` 和 `setData(partialData): Promise<void>`。
- `WeComOpenDataFrameOptions` 增加 `handleUpdated?: () => void`，并保留现有 `handleMounted`/`error` 兼容字段。
- `WeComViewerHandle.prepareMessages(contactPointId, messageIds, signal?)` 返回 `{ sdk, viewerAuthToken, messages }`；不向调用方暴露可复用的 session id。
- `prepareMessages` 先返回缓存消息，再把 miss 按 `wecomViewerMaxMessages` 分块创建并读取 session；成功读取后写入缓存。

- [ ] **Step 1: 写失败测试**

```ts
it('全部命中缓存时不创建 session', async () => {
  // 预置 m1 的 secretKey，调用 prepareMessages(['m1'])。
  // 断言 createWeComViewerSession 和 fetchWeComViewerSession 均未调用。
});

it('cache miss 按 15 条以内分批读取并合并为原顺序', async () => {
  // 传入 16 个 msgid，断言 session create 调用 2 次，返回消息仍按输入顺序排列。
});

it('viewer token 变化后不复用旧 token 的 secretKey', async () => {
  // 先用 token-a 读取 m1，再切换 token-b，断言 token-b 仍创建 session。
});
```

- [ ] **Step 2: 运行专项测试确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/hooks/useWeComViewer.test.ts`

Expected: FAIL，因为新接口和缓存接线尚不存在。

- [ ] **Step 3: 实现缓存接线**

在 hook 内维护模块级或 hook 级单例缓存，并在 `crmToken/wecomViewerAuthToken` 变化时调用 `clearToken`/`clearAll`。对每个 miss 分块调用现有 `createWeComViewerSession` + `fetchWeComViewerSession`，读取 detail 后立即写缓存；所有请求继续传递 `AbortSignal`。批次包含不可读消息时，先将该批拆成单条请求，保留可读结果并把不可读项作为结构化失败返回，不能让整屏静默空白。

- [ ] **Step 4: 运行专项测试和类型检查**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/hooks/useWeComViewer.test.ts && npx tsc -b --pretty false`

Expected: PASS，且 `tsc` 无错误。

- [ ] **Step 5: 提交独立变更**

```bash
git add demo/message-center-spring/frontend/src/wecom/wecomSdk.ts \
  demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts \
  demo/message-center-spring/frontend/src/hooks/useWeComViewer.test.ts
git commit -m "feat: cache wecom viewer message references"
```

### Task 3: 引入稳定单联系人 frame

**Files:**
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.tsx`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx`

**Interfaces:**
- `WeComConversationFrame` 接收 `contactPointId`, `items`, `viewer` 和由 Ant Design `theme.useToken()` 生成的边框/背景样式值，组件实例在 props 变化时不卸载。
- 首次渲染调用 `prepareMessages` 并创建一次 `OpenDataFrame`；后续联系人或历史页变化只调用 `setData`。
- `generationRef`、`AbortController` 和 `activeContactPointRef` 保证旧请求不能更新当前 frame。
- 更新前调用 `frame.setData({ msgList: [] })` 清空旧内容；更新成功后调用 `frame.setData({ msgList })`，等待 `handleUpdated` 或超时后显示明确错误。

- [ ] **Step 1: 写失败测试**

```tsx
it('A 到 B 只创建一个 frame，并显示 B 的文字数据', async () => {
  // SDK factory 的 createOpenDataFrame 记录次数；viewer 返回 A/B 两组消息。
  // rerender contactPointId 后断言 create 次数为 1，第二次调用 setData 的 msgList 为 B。
});

it('A 到 B 到 A 时旧请求不能覆盖最终 A，且 A 命中缓存不创建 session', async () => {
  // 乱序 resolve A/B 请求，断言最终 setData 只有最终 A 的数据。
});

it('setData 或更新回调失败时显示错误而不是永久占位符', async () => {
  // 让 setData reject 或不触发更新回调，断言出现“企业微信消息加载失败”和重试按钮。
});
```

- [ ] **Step 2: 运行专项测试确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComConversationFrame.test.tsx`

Expected: FAIL，因为稳定 frame 组件尚不存在。

- [ ] **Step 3: 实现 frame controller**

创建稳定 host 和 frame ref；只在 SDK 首次准备完成后调用 `createOpenDataFrame`。frame options 的模板继续使用 `ww-open-message`，`handleMounted` 设置 mounted 状态，`handleUpdated` 清除 update timeout。联系人切换时先 abort、递增 generation、清空数据，再为当前联系人加载 cache miss。组件卸载时只执行一次 `dispose`。

- [ ] **Step 4: 接入面板且保持布局**

让 `WeComConversationPanel` 保留 header、刷新按钮和底部“在企业微信中打开”，中间区域渲染 `WeComConversationFrame`。不要给 frame 子组件增加基于 `contactPointId` 的 React `key`，确保切换联系人不会卸载 frame。

- [ ] **Step 5: 运行专项测试和类型检查**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComConversationFrame.test.tsx src/components/wecom/WeComConversationPanel.test.tsx && npx tsc -b --pretty false`

Expected: PASS，且 `tsc` 无错误。

- [ ] **Step 6: 提交独立变更**

```bash
git add demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.tsx \
  demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.test.tsx \
  demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx
git commit -m "feat: reuse one wecom conversation frame"
```

### Task 4: 保持混合时间线和历史分页合同

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`

**Interfaces:**
- 混合时间线仍按原 registry key 创建/释放 segment，不使用单联系人 frame 的缓存状态。
- `ThreadPage` 的 `weComItems` 始终从当前 `allItems` 过滤 `channelType === 'wecom' && sourceId`，历史页 prepend 后自动传入完整集合。
- 联系人变更时只接受当前 generation 的分页响应；加载历史页后不重置滚动位置。

- [ ] **Step 1: 写失败测试**

```tsx
it('加载更早一页后，企业微信面板收到合并后的全部 msgid', async () => {
  // 首页返回 m3,m4，nextCursor 返回 m1,m2；触发顶部滚动后断言 panel props 包含 m1..m4。
});

it('联系人切换期间返回的旧历史页不会写入新联系人', async () => {
  // 延迟 A 的分页响应，切换到 B 后 resolve A，断言 B 列表不含 A 的消息。
});
```

- [ ] **Step 2: 运行专项测试确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx`

Expected: 新增断言先失败；现有混合时间线测试不得因 SDK 类型扩展而失败。

- [ ] **Step 3: 实现最小接线**

确认历史分页 prepend 使用唯一 `id/sourceId` 去重并保留时间顺序；不要把 `segmentWeComTimeline` 的混合模式改成单 frame。将扩展后的 `setData` 类型仅用于新单联系人组件，现有 segment 继续在 release 时 dispose。

- [ ] **Step 4: 运行专项测试**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx`

Expected: PASS。

- [ ] **Step 5: 提交独立变更**

```bash
git add demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx \
  demo/message-center-spring/frontend/src/pages/ThreadPage.tsx \
  demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx \
  demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx
git commit -m "test: preserve wecom history pagination and mixed timeline"
```

### Task 5: 全量前端验收和构建产物核对

**Files:**
- No source changes unless a failing test identifies a scoped defect.
- Verify: `demo/message-center-spring/frontend/dist/` is generated only by the build command.

- [ ] **Step 1: 运行全部前端测试**

Run: `cd demo/message-center-spring/frontend && npm test`

Expected: `test:source` 和 `test:ui` 均 PASS。

- [ ] **Step 2: 运行生产构建**

Run: `cd demo/message-center-spring/frontend && npm run build`

Expected: `tsc -b` 无错误，Vite 输出 `dist/index.html` 和 assets。

- [ ] **Step 3: 核对构建产物引用**

Run: `cd demo/message-center-spring/frontend && test -s dist/index.html && find dist/assets -type f | wc -l && grep -oE '/assets/[^" ]+\.js' dist/index.html`

Expected: `index.html` 非空、assets 数量大于 0，入口脚本存在。

- [ ] **Step 4: 浏览器验收场景**

在已登录环境依次执行：

1. 联系人 A -> 企业微信面板，确认文字渲染完成。
2. A -> B，确认没有永久占位符，frame 创建次数不增加。
3. B -> A，确认 A 使用缓存快速恢复。
4. 在 A 面板滚动到顶部，加载更早一页，确认旧消息出现在上方且滚动位置不跳动。
5. 切换到普通时间线再切回企业微信，确认 frame 仍能正常显示；手动刷新页面后确认缓存清空且可重新读取。

- [ ] **Step 5: 记录部署边界**

记录本地构建 hash、入口 asset 名称和测试输出。后端无需因为该前端缓存方案更换 Jar；但服务器若仍运行旧员工会话权限修复 Jar，必须单独部署已确认的后端 SHA-256 `32e0def8de66acee4405ddc2f43775f6437f032e632b9d5cc5289933653db637`，再进行实机验收。

## Self-Review Checklist

- 历史消息由 `ThreadPage` cursor 提供，缓存只处理已加载的 `msgid`，没有把两个 owner 混在一起。
- 单联系人 frame 使用官方 `setData`；混合时间线 registry 保持原有生命周期，避免无关回归。
- 一次性 session id 不会进入缓存；token 变化、TTL、容量和 abort 都有测试任务。
- A -> B -> A、批次超过 15 条、单条失败、更新超时和刷新后重新读取均有明确验收入口。
- 生产构建、全量测试和浏览器切换场景均列出命令或步骤；没有依赖服务器权限的本地假验收。
