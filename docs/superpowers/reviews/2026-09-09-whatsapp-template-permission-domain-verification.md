# WhatsApp 账号模板权限分流验收记录

日期：2026-09-09

## 验收范围

本次只验收 WhatsApp 模板权限域分流：

- `API_ONLY` 使用企业共享模板域，变更继续走管理员审批。
- `BUSINESS_APP_COEXISTENCE` 使用当前员工拥有的账号私有模板域，owner 可直接申请、修改、调整发送权限和删除。
- 模板查询、同步、发送解析和群发模板查询按权限域隔离。

WhatsApp/CAMS 账号绑定、Embedded Signup、WABA scope、号码添加和验证码注册不属于本次验收。

## 已通过

后端模板专项测试通过：

```text
mvn -q -Dtest=WhatsAppTemplatePermissionDomainSchemaTest,WhatsAppTemplateApplicationServiceTest,WhatsAppSharedTemplateCatalogServiceTest,WhatsAppTemplateChangeRequestServiceTest,TemplateMessageTextResolverTest,ChatAppTemplateServiceTest,ChatAppBroadcastApplicationServiceTest test
```

后端编译通过：

```text
mvn -q -DskipTests compile
```

前端模板页面测试通过，4/4：

```text
npm run test:ui -- --run src/pages/TemplatesPage.test.tsx
```

模板管理 UI 契约测试通过：

```text
node --test test/template-management-ui-contract.test.mjs
```

前端生产构建通过：

```text
npm run build
```

## 已知非本计划失败

更广的 `whatsapp-shared-path-contract.test.mjs` 套件有 1 个既有失败：

```text
uses embedded signup completion instead of phone migration verification
```

该断言校验 WhatsApp 绑定前端接口签名，当前用户 WIP 已将 `createWhatsAppAuthorizationAttempt` 改为需要 `onboardingMode`，旧断言仍要求无参数签名。绑定链路明确排除在本计划之外，因此本次不修改该测试、授权服务或 CAMS Gateway。

计划列出的 `test/whatsapp-template-permission-domain.test.mjs` 当前不存在；模板相关覆盖使用现有 `template-management-ui-contract.test.mjs`、`TemplatesPage.test.tsx` 及后端专项测试完成。

## 未执行门禁

真实 CAMS/WhatsApp 上游模板 API 的申请、修改、发送权限、删除及跨账号隔离，仍需在用户本地配置真实凭证的环境中实机验证；本记录不将本地 mock/单元测试描述为上游实机通过。
