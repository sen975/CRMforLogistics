# Grouped Top Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将消息中心顶部入口按 WhatsApp、企业微信、电话和系统设置分组，并保持现有权限、路由和账号操作行为。

**Architecture:** 在 `AppLayout` 中用 Ant Design `Dropdown` 作为分组导航适配层，菜单项只映射现有路由或现有回调，不新增业务状态。企业微信保留直接跳转按钮；系统级账号抽屉和退出登录通过菜单项复用现有状态与 `logout`。

**Tech Stack:** React、React Router、Ant Design、Vitest、Testing Library、Vite。

## Global Constraints

- 保持现有路由不变。
- 群发继续受 `canBroadcast` 控制。
- 企业微信、模板、渠道设置继续受管理员权限控制。
- 桌面端支持 hover/键盘，移动端支持 click。
- 不修改与顶部导航无关的业务组件。

---

### Task 1: 导航行为测试

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.test.tsx`

**Interfaces:**
- 验证 `AppLayout` 输出四个一级分组及权限过滤。
- 验证菜单项可见并触发对应路由或账号/退出操作。

- [ ] **Step 1: 增加管理员导航分组断言**

断言页面显示 `WhatsApp`、`企业微信`、`电话`、`系统设置`，而不是平铺的旧入口。

- [ ] **Step 2: 增加菜单交互断言**

通过 `userEvent` 打开系统设置和 WhatsApp 菜单，验证 `发送`、`Topic 仓库`、`模板`、`群发` 菜单项出现。

- [ ] **Step 3: 增加权限断言**

将 mock 用户改为非管理员且不可群发，验证管理员菜单和群发菜单项不显示，但系统设置与发送仍存在。

- [ ] **Step 4: 运行测试确认旧实现失败**

运行：`npm run test:ui -- src/components/AppLayout.test.tsx`

预期：新增断言失败，因为当前组件仍平铺旧按钮。

### Task 2: 实现分组导航

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`

**Interfaces:**
- 使用 `Dropdown` 与 `MenuProps` 构造 WhatsApp、电话、系统设置菜单。
- 菜单项点击调用 `navigate(path)`、`setAccountOpen(true)` 或 `logout()`。

- [ ] **Step 1: 替换平铺入口**

保留现有 `navigationButton` 作为一级入口样式，新增 `navigationDropdown(label, icon, items)`，桌面端使用 `trigger={['hover', 'click']}`，移动端使用 `trigger={['click']}`。

- [ ] **Step 2: 按权限构造菜单**

WhatsApp 菜单包含模板和可选群发；企业微信保留直接按钮；电话菜单包含电话仓库；系统设置包含发送、Topic 仓库、可选渠道设置、账号和退出登录。

- [ ] **Step 3: 保持移动端和详情栏按钮**

继续保留联系人入口、右侧栏开关、用户名显示规则，不改变详情面板逻辑。

- [ ] **Step 4: 运行定向测试**

运行：`npm run test:ui -- src/components/AppLayout.test.tsx`

预期：全部通过。

### Task 3: 构建验收

**Files:**
- No source changes.

- [ ] **Step 1: 运行生产构建**

运行：`npm run build`

预期：TypeScript 检查和 Vite 构建均成功。

- [ ] **Step 2: 检查变更边界**

运行：`git diff --stat -- demo/message-center-spring/frontend/src/components/AppLayout.tsx demo/message-center-spring/frontend/src/components/AppLayout.test.tsx`

确认本次源码变更只涉及导航组件和其测试。
