# WhatsApp 账号模板权限分流实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 实现企业 WhatsApp Business API 共享模板审批与独立 WhatsApp Business App 账号私有模板直改两条权限链路。

**架构：** 模板权限域由服务端根据当前用户拥有的 `channel_accounts.onboarding_mode` 推导；`API_ONLY` 账号只进入企业共享模板服务，已有模板变更保留管理员审批；`BUSINESS_APP_COEXISTENCE` 账号只进入账号私有模板服务，申请、修改、停用、删除直接调用对应账号上游能力。两类模板在数据库、查询、缓存和发送入口隔离，浏览器不能指定其他账号或权限域。

**技术栈：** Spring Boot、Java 17、MyBatis-Plus、PostgreSQL/Flyway、React/TypeScript、Ant Design、现有 WhatsApp 模板 Gateway。

## 全局约束

- 普通员工不能绑定或更换企业 API/WABA；只能在企业 API 下添加自己的电话号码，或绑定自己的 Business App。
- `API_ONLY` 模板属于企业 API 共享域；已有模板修改、发送权限、停用和删除需要管理员审批。
- `BUSINESS_APP_COEXISTENCE` 模板绑定当前员工的 `channel_account_id`，由 owner 直接执行，不经过管理员。
- 模板读取、写入、同步、发送必须由服务端从当前账号推导权限域，不信任请求体的账号、scope 或 owner 字段。
- 不使用 CAMS 控制台私有接口，不把共存账号实现成 Migration/短信验证码流程。
- 不清理或覆盖现有用户 WIP，不提交 Git。

## Task 1：模板权限域数据合同

**文件：**
- 新建：`demo/message-center-spring/backend/src/main/resources/db/migration/V58__whatsapp_template_permission_domains.sql`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/TemplateMapper.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/WhatsAppTemplatePermissionDomainSchemaTest.java`

**步骤：**
- [x] 先写 schema/entity 失败测试：Business App 模板必须有 `channel_account_id`，企业 API 模板必须为共享域；同一账号、模板 ID、语言不能重复；两个域不能互相查询。
- [x] 运行专项测试确认按预期失败。
- [x] 添加 `template_domain` 字段和约束：`ENTERPRISE_API` / `EMPLOYEE_BUSINESS_APP`；旧模板按 `provider_scope_id` 回填为 `ENTERPRISE_API`，无法证明归属的记录保持不可发送。
- [x] 为 mapper 增加共享域查询和账号私有域查询，所有 upsert/find/send 查询必须带 domain 与 account 条件。
- [x] 运行 schema/mapper 专项测试。

## Task 2：后端模板权限服务分流

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateChangeRequestService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppSharedTemplateCatalogService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateReconciliationService.java`
- 新建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppPrivateTemplateService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateController.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppPrivateTemplateServiceTest.java`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WhatsAppTemplateControllerPermissionDomainTest.java`

**步骤：**
- [x] 先写失败测试：API_ONLY 只能进入共享服务；Business App owner 可直接执行私有模板操作；其他员工、管理员代操作、跨域模板 ID 全部拒绝；私有模板不创建审批申请。
- [x] 运行专项测试确认失败。
- [x] 实现 `requireOwnedBusinessAppAccount`、`requireEnterpriseApiAccount`，新增私有模板 CRUD/同步入口；复用现有 Gateway 和审计/幂等机制。
- [x] 共享模板服务明确拒绝 Business App 模板，变更申请服务明确拒绝私有模板。
- [x] 运行后端专项测试和编译。

## Task 3：前端模板域切换

**文件：**
- 修改：`demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/api/endpoints.ts`
- 修改：`demo/message-center-spring/frontend/src/api/types.ts`
- 测试：`demo/message-center-spring/frontend/src/pages/TemplatesPage.test.tsx`
- 测试：`demo/message-center-spring/frontend/test/whatsapp-template-permission-domain.test.mjs`

**步骤：**
- [x] 先写失败测试：API_ONLY 账号显示共享模板和审批入口；Business App 账号显示私有模板且不显示审批入口；页面不接受任意 accountId/domain 覆盖。
- [x] 运行专项测试确认失败。
- [x] 使用后端返回的账号域投影选择接口，不把账号 ID 放进权限结论；私有模板操作显示直接确认状态，共享模板保留申请状态。
- [x] 运行前端专项测试和构建。

## Task 4：范围边界（不执行）

WhatsApp 账号绑定、CAMS Embedded Signup、WABA scope 建立、号码添加与验证码注册不属于本模板权限计划。不得在本 Task 修改以下文件或其测试：

- `WhatsAppAuthorizationService`、`WhatsAppAuthorizationController`
- `WhatsAppOnboardingGateway`、`AliyunWhatsAppOnboardingGateway`
- `WhatsAppProviderScopeMapper`
- `WhatsAppPhoneNumberService` 及其 API

这些工作仅由 `docs/superpowers/plans/2026-09-09-whatsapp-cams-embedded-signup-self-service-runtime.md` 负责。模板代码只消费已有的 `channel_accounts.onboarding_mode` 和 `provider_scope_id` 投影，不拥有绑定、授权或号码注册语义。

- [x] 已明确排除绑定流程，后续不在本计划执行。

## Task 5：模板验收与文档回写

**文件：**
- 修改：`docs/superpowers/README.md`
- 新建：`docs/superpowers/reviews/2026-09-09-whatsapp-template-permission-domain-verification.md`

**步骤：**
- [x] 运行模板域后端专项测试、模板页面专项测试和前端构建。
- [x] 记录实际通过项、已有 WIP 失败和模板上游 API 实机门禁；不把 CAMS 账号绑定门禁计入本计划。
- [x] 检查 `git diff --check` 与未提交文件边界，不提交 Git。

## 停止条件

- CAMS 或 Meta 实际合同与设计字段不一致时，停止绑定链路实现，不用猜测字段。
- 数据库无法同时表达共享模板和账号私有模板时，停止业务层绕过。
- 任一跨用户/跨域测试失败时，不继续 UI 扩展。
