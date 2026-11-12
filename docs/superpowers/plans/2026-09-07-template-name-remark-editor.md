# 模板名称与备注编辑器实施计划

> **For agentic workers:** 按项目快速模式在当前会话内逐项执行，每项先专项测试，再精确暂存并独立提交。

**Goal:** 将已有模板的官方名称改为纯文本展示，并在其下方提供可随变更申请保存的业务备注输入框。

**Architecture:** 编辑器只负责展示官方名称和收集备注；模板内容仍走现有共享模板变更申请状态机。备注作为本地字段随 MODIFY 申请传递，管理员直执行或审批执行成功后写回共享模板，不发送给 WhatsApp。

**Tech Stack:** React + Ant Design + Vitest；Spring Boot + MyBatis + JUnit/Mockito。

## Global Constraints

- 官方模板名称创建后不可修改。
- 普通用户修改模板必须经过管理员审批，管理员可直接执行。
- 备注是系统本地业务字段，不提交给上游 WhatsApp。
- 只运行本任务专项测试，不吸入其他工作区 WIP。

### Task 1: 编辑器展示与备注提交

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Test: `demo/message-center-spring/frontend/src/components/templates/TemplateEditorDrawer.test.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`

- [ ] 先增加测试：已有模板名称渲染为文本，备注输入框初始化并随提交回调。
- [ ] 运行 Vitest 专项测试确认失败。
- [ ] 实现编辑器字段与提交回调。
- [ ] 运行专项测试确认通过。
- [ ] 精确暂存本任务差异并独立提交。

### Task 2: 备注审批执行落库

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestServiceTest.java`

- [ ] 增加 MODIFY 备注写回的失败测试。
- [ ] 实现成功执行后写回 remark，保留上游名称不变。
- [ ] 运行后端专项测试并精确提交。
