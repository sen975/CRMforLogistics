# 待办日历与企业微信提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有 React + Spring 消息中心中增加可本地持久化的月历待办工作台，并通过后端调用企业微信官方 API 发送提醒。

**Architecture:** Spring `todo_items` 表和 `TodoItemService` 负责按用户隔离的待办 CRUD；`TodoCalendarPage.tsx` 通过 API 读取与修改待办，`todoStore.ts` 仅保留旧浏览器缓存的一次性迁移和提醒记录；Spring `WeComSendService` 根据当前账号绑定身份获取 token 并调用官方发送接口。路由与 AppLayout 仅做接线。

**Tech Stack:** React 18, TypeScript, React Router 6, Ant Design 5, Vitest + Testing Library, CSS。

## Global Constraints

- 前端不得接触企业微信密钥；发送必须经 `/api/wecom/send-todo-reminder` 后端边界。
- 待办日期使用本地时区 `YYYY-MM-DD`；旧版 localStorage 数据损坏时降级为空集合。
- 页面视觉使用暖纸色、墨线和橙红批注色，保持键盘焦点、响应式布局和 reduced-motion。
- 不修改用户已有的 `demo/message-center-spring/README.md` 与未跟踪 `message-center-spring/`。

### Task 1: 建立待办数据 owner

**Files:**
- Create: `demo/message-center-spring/frontend/src/todos/todoStore.ts`
- Test: `demo/message-center-spring/frontend/src/todos/todoStore.test.ts`

**Interfaces:**
- Produces `TodoItem`, `ReminderRecord`, `loadTodos()`, `saveTodos()`, `getTodosForDate()`, `createTodo()`, `toggleTodo()`, `removeTodo()`, `saveReminderRecord()`。

- [ ] 编写测试：覆盖日期筛选、创建/切换/删除、本地存储损坏降级和提醒记录最多 10 条。
- [ ] 运行 `npm run test:ui -- src/todos/todoStore.test.ts`，确认测试先失败。
- [ ] 实现纯函数与 localStorage 适配；生成 id 使用 `crypto.randomUUID` 不可用时的时间随机回退。
- [ ] 重新运行同一测试并确认通过。

### Task 2: 实现纸张风格日历页面

**Files:**
- Create: `demo/message-center-spring/frontend/src/pages/TodoCalendarPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/TodoCalendarPage.css`
- Test: `demo/message-center-spring/frontend/src/pages/TodoCalendarPage.test.tsx`

**Interfaces:**
- Consumes Task 1 store functions。
- Produces default export `TodoCalendarPage`，支持日期点击、新增待办、完成/删除和官方 API 提醒。

- [ ] 编写交互测试：点击日期切换标题；打开表单填写标题/时间并保存；勾选完成；发送提醒后出现官方 API 返回结果。
- [ ] 运行页面测试确认先失败。
- [ ] 使用原生日期计算生成 6×7 月历，任务数量以点标记；右侧详情按 `selectedDate` 筛选。
- [ ] 添加必填标题校验、空态说明、最近发送记录与操作反馈。
- [ ] 编写纸张工作台 CSS：暖灰背景、米白纸面、手绘圈选、虚线分隔、移动端单列布局和 reduced-motion。
- [ ] 运行页面测试确认通过。

### Task 3: 路由与导航接线

**Files:**
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`

**Interfaces:**
- Adds `/todo-calendar` route and “待办日历” navigation button using existing icon/navigation patterns。

- [ ] 写路由测试断言路径渲染页面并受现有 AuthGuard 保护。
- [ ] 增加 lazy import、导航入口和日历图标；不改变现有菜单语义。
- [ ] 运行路由测试与 TypeScript 构建。

### Task 4: 回归验收

**Files:**
- Modify: `demo/message-center-spring/frontend/src/global.css` only if global focus/reset is required。

- [ ] 运行 `npm run test:source`。
- [ ] 运行 `npm run test:ui`。
- [ ] 运行 `npm run build`。
- [ ] 使用本地预览检查桌面与窄屏：日期点击、表单、完成状态、官方 API 发送、空态和键盘焦点。
- [ ] 复核 `git status --short`，只保留本任务新增/修改文件，用户已有改动不纳入 stage。
