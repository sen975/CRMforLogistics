# 会话图片与电话记录实时展示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 ChatApp 图片在消息气泡中真实显示，并让电话记录按方向布局且在上传和转录期间自动更新。

**Architecture:** 后端从 `attachments` 生成安全的附件 DTO，并按附件 ID 代理 MinIO 内容；前端通过鉴权 blob 请求渲染图片。电话记录上传后立即刷新，且只在活动转录存在时执行 2 秒轮询。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis-Plus、JUnit 5、React 18、TypeScript、TanStack Query、Ant Design、Vitest

## Global Constraints

- 不向前端暴露 MinIO object key 或 Cams/OSS 临时 URL。
- 不修改 ChatApp 模板同步和通话转录状态机。
- 所有生产改动先有失败测试。
- 前端最终必须使用真实浏览器验收。

---

### Task 1: 后端附件投影与媒体代理

**Files:**
- Create: `backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageAttachmentResponse.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AttachmentMapper.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java`
- Modify: `backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadServiceTest.java`
- Test: `backend/src/test/java/com/crmforlogistics/messagecenter/web/MediaControllerTest.java`

- [ ] 写失败测试：线程响应包含 ready 附件，媒体控制器按附件 UUID 授权读取并返回真实 MIME。
- [ ] 运行 `mvn -Dtest=ThreadServiceTest,MediaControllerTest test`，确认因附件合同缺失而失败。
- [ ] 实现附件 DTO、mapper 查询、响应投影和安全媒体读取。
- [ ] 重跑目标测试并确认通过。

### Task 2: 前端真实图片气泡

**Files:**
- Create: `frontend/src/components/MessageMedia.tsx`
- Modify: `frontend/src/components/MessageBubble.tsx`
- Modify: `frontend/src/api/types.ts`
- Test: `frontend/src/components/MessageBubble.test.tsx`

- [ ] 写失败测试：带 image 附件的消息最终渲染 `img`，下载失败显示图标降级。
- [ ] 运行 `npm run test:ui -- MessageBubble.test.tsx`，确认真实图片尚未渲染。
- [ ] 实现鉴权 blob 加载、URL 释放、固定缩略图尺寸和预览。
- [ ] 重跑 UI 测试并确认通过。

### Task 3: 电话方向与活动期更新

**Files:**
- Create: `frontend/src/utils/callRecordTimeline.ts`
- Modify: `frontend/src/pages/ThreadPage.tsx`
- Modify: `frontend/src/components/SendForm.tsx`
- Test: `frontend/src/utils/callRecordTimeline.test.ts`
- Test: `frontend/test/call-record-side-panel.test.mjs`

- [ ] 写失败测试：`outbound` 判定为右侧，`queued/processing` 开启轮询，上传成功回调接入时间线刷新。
- [ ] 运行目标测试，确认当前布局和回调接线不满足。
- [ ] 实现方向样式、上传后立即刷新和 2 秒活动期轮询。
- [ ] 重跑目标测试并确认通过。

### Task 4: 集成验收与文档回写

- [ ] 运行 `npm test`、`npm run build`。
- [ ] 运行后端本轮目标测试及受影响消息服务测试。
- [ ] 使用浏览器验证图片真实显示、呼出电话右对齐、上传后即时出现和状态自动变化。
- [ ] 运行两个改动范围的 `git diff --check`，并回填实际结果与未闭合风险。
