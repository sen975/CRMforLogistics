# 企业微信会话容器铺满时间轴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让切换到企业微信后的会话展示容器完整填满右侧时间轴区域，同时保留刷新和唤起企业微信按钮。

**Architecture:** `ThreadPage` 继续负责右侧区域的 flex 外壳；`WeComConversationPanel` 负责整页三段式布局；`WeComTimelineSegment` 在 standalone 模式下逐级填充可用尺寸，mixed 模式维持原有消息条布局。只调整布局合同，不改 API、SDK 初始化或后端 Jar。

**Tech Stack:** React 18, TypeScript, Ant Design, Vitest, Vite.

## Global Constraints

- 企业微信会话页保留顶部刷新按钮和底部“在企业微信中打开”按钮。
- 非企业微信混合时间线行为不变。
- 不引入固定 viewport、高度魔法数字或额外滚动容器来掩盖尺寸问题。
- 生成的 `dist` 只通过 `npm run build` 产生。

### Task 1: Layout regression tests

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx`

- [x] **Step 1: Add assertions for full-size middle region and SDK host**

在会话面板测试中断言中间区域具备 `flex: 1 1 auto`、`minHeight: 0`、`minWidth: 0` 且没有内缩 padding；在 standalone segment 测试中断言 segment 根节点、frame wrapper 和 host 都使用全宽全高。

- [x] **Step 2: Run focused tests and verify they fail**

```bash
npm run test:ui -- src/components/wecom/WeComConversationPanel.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx
```

Expected: FAIL because the current middle wrapper has padding and the standalone frame wrapper does not explicitly expose full height.

### Task 2: Full-size standalone layout

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`

- [x] **Step 1: Make the conversation middle region the sole filling flex child**

给 `WeComConversationPanel` 的中间区域增加 `minWidth: 0`，移除会话内容的 `padding: 12px`，让 SDK 内容从区域边缘开始填充；保留顶部标题、刷新按钮和底部唤起按钮。

- [x] **Step 2: Make standalone frame wrappers explicitly fill their parent**

让 standalone 模式下 segment 根节点、frame wrapper 和 host 都设置 `width: 100%; height: 100%; min-width: 0; min-height: 0`，host 使用 `overflow: hidden`，避免 SDK 宿主再生成一个缩窄的内部滚动视口。mixed 模式保留原有按消息条数高度和滚动行为。

- [x] **Step 3: Run focused tests and verify they pass**

```bash
npm run test:ui -- src/components/wecom/WeComConversationPanel.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx
```

Expected: all focused tests pass.

### Task 3: Build and package

**Files:**
- Generated: `demo/message-center-spring/frontend/dist/`
- Create: `demo/message-center-spring/frontend/frontend-dist-20260822-wecom-full-height-r1.zip`
- Create: `demo/message-center-spring/frontend/frontend-dist-20260822-wecom-full-height-r1.zip.sha256`

- [x] **Step 1: Run complete frontend verification**

```bash
npm run test:source
npm run test:ui
npm run build
```

Expected: source tests 29/29, UI tests include all existing tests with zero failures, and Vite exits 0.

- [x] **Step 2: Package only `dist/` and verify archive integrity**

```bash
zip -qr frontend-dist-20260822-wecom-full-height-r1.zip dist
sha256sum frontend-dist-20260822-wecom-full-height-r1.zip > frontend-dist-20260822-wecom-full-height-r1.zip.sha256
unzip -t frontend-dist-20260822-wecom-full-height-r1.zip
```

Expected: archive contains only the `dist/` root and `unzip -t` reports no errors.
