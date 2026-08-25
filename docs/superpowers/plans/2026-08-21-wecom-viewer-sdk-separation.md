# 企业微信会话 SDK 分离实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让企业微信会话展示只使用官方 `@wecom/jssdk` 提供的 OpenData API，同时保留登录面板 SDK 和官方页面脚本的各自职责。

**Architecture:** 登录面板继续由 `wecom-jssdk-2.3.4.js` 和 `window.ww.createWWLoginPanel` 驱动。会话展示改为从 `@wecom/jssdk` 模块导入受类型约束的 `ww`，先加载官方要求的 `jwxwork-1.0.0.js` 页面脚本，再由模块 `ww` 依序 `register`、`initOpenData`、`createOpenDataFrameFactory`。后端只继续签发现有 js-sdk 配置和会话凭证，本轮不改 Jar、Nginx 或数据库。

**Tech Stack:** React 18、TypeScript、Vite、Vitest、`@wecom/jssdk`、企业微信 `jwxwork-1.0.0.js`。

## Global Constraints

- 不从 `window.ww`、登录 SDK 或其兜底对象取得会话展示 API。
- `jwxwork-1.0.0.js` 保留为会话展示页面脚本，但不承担模块 `ww` 的来源。
- 不修改后端源码、Jar、Nginx、数据库和企业微信鉴权参数。
- 不输出或写入任何企业微信密钥、token、会话展示明文。
- 不覆盖当前工作区其他未提交文件。

---

### Task 1: 固化 SDK 边界与加载失败行为

**Files:**
- Modify: `demo/message-center-spring/frontend/src/wecom/wecomSdk.test.ts`
- Modify: `demo/message-center-spring/frontend/test/wecom-contract.test.mjs`

**Interfaces:**
- Consumes: `loadWeComSdk(): Promise<WeComLoginSdk>` 与 `loadWeComViewerSdk(): Promise<WeComViewerSdk>`。
- Produces: 登录 SDK 与会话 SDK 不可互换的测试合同。

- [ ] **Step 1: 写入失败测试**

将当前“`jwxwork` 替换全局 `window.ww`”测试改为：即使 `window.ww` 只有登录用的 `createWWLoginPanel`，`loadWeComViewerSdk()` 仍加载 `jwxwork-1.0.0.js`，并且只能在模块 mock 的 `ww.register`、`ww.initOpenData`、`ww.createOpenDataFrameFactory` 均存在时成功。

- [ ] **Step 2: 运行并确认当前实现失败**

Run: `npm run test:ui -- src/wecom/wecomSdk.test.ts`

Expected: 当前实现仍从 `window.ww ?? sdk` 返回对象，新的模块来源断言失败。

- [ ] **Step 3: 更新源码合同测试**

断言 `wecomSdk.ts` 导入 `@wecom/jssdk`，保留两个官方 URL，并禁止 `window.ww ?? sdk`、`window.ww?.createOpenDataFrameFactory` 等会话展示兜底表达式。

- [ ] **Step 4: 运行合同测试**

Run: `node --test test/wecom-contract.test.mjs`

Expected: 当前源码合同失败，指出会话 SDK 尚未从模块导入。

### Task 2: 实现独立的官方会话 SDK 加载器

**Files:**
- Modify: `demo/message-center-spring/frontend/package.json`
- Modify: `demo/message-center-spring/frontend/package-lock.json`
- Modify: `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`

**Interfaces:**
- Consumes: `@wecom/jssdk` 导出的 `ww` 与页面脚本 URL。
- Produces: `loadWeComSdk(): Promise<WeComLoginSdk>`，`loadWeComViewerSdk(): Promise<WeComViewerSdk>`。

- [ ] **Step 1: 锁定依赖**

在前端工作目录执行：

```bash
npm install --save-exact @wecom/jssdk
```

确认 `package.json` 和 `package-lock.json` 记录相同的精确版本，且没有升级无关依赖。

- [ ] **Step 2: 分离类型与来源**

把登录类型缩小到只含 `createWWLoginPanel`，把 `register`、`initOpenData`、`createOpenDataFrameFactory` 放进仅由 `@wecom/jssdk` 导出的 `WeComViewerSdk` 类型。`loadWeComViewerSdk()` 只负责幂等加载 `jwxwork-1.0.0.js` 并返回模块 `ww`；禁止调用 `loadWeComSdk()`，禁止读取 `window.ww`。

- [ ] **Step 3: 保持调用方只消费会话类型**

在 `useWeComViewer.ts` 将缓存和准备结果改为 `WeComViewerSdk`，保持现有 `register -> initOpenData -> createOpenDataFrameFactory` 顺序及 `/api/v1/wecom/js-sdk-config` 请求不变。

- [ ] **Step 4: 运行定向测试**

Run: `npm run test:ui -- src/wecom/wecomSdk.test.ts src/components/wecom/WeComTimelineSegment.test.tsx`

Expected: PASS。

### Task 3: 构建、打包与部署交付验证

**Files:**
- Create: `demo/message-center-spring/frontend/frontend-dist-20260821-wecom-sdk-separation.zip`
- Create: `demo/message-center-spring/frontend/frontend-dist-20260821-wecom-sdk-separation.zip.sha256`

**Interfaces:**
- Consumes: Task 1-2 的前端源码与锁定依赖。
- Produces: 可部署的前端 `dist` 压缩包与 SHA-256 校验文件。

- [ ] **Step 1: 跑完整前端测试**

Run: `npm test`

Expected: 全部 source contract 与 UI 测试通过。

- [ ] **Step 2: 构建生产产物**

Run: `npm run build`

Expected: `tsc -b && vite build` 成功，`dist/index.html` 存在。

- [ ] **Step 3: 创建可验证 ZIP**

使用 `zip -qr` 打包 `dist/`，随后执行 `unzip -t` 和 `sha256sum`；归档内文件必须逐个与当前 `dist/` 一致。

- [ ] **Step 4: 发布后验收边界**

仅部署新的前端 ZIP，不替换 `message-center.jar`。浏览器 Network 应观察到 `jwxwork-1.0.0.js`、`/api/v1/wecom/js-sdk-config`、`/api/v1/wecom/conversation-view/bootstrap`；若仍空白，收集 Console 的 `sdk-init` / `frame-create-error` 和这三个请求的状态，不以重新换 Jar 作为修复手段。

## Self-Review

- 范围覆盖：依赖、SDK 来源、类型、调用方、单元/合同测试、构建和部署均有任务。
- 排除范围明确：后端 Jar、Nginx、数据库、密钥及现有其他前端 WIP 不触碰。
- 风险收口：测试禁止登录 SDK 充当会话 SDK，构建前先锁依赖，部署后有可观测请求清单。
