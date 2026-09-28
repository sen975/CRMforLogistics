# WhatsApp CAMS 回调地址管理验收记录

## 范围

本轮按实施计划逐个 Task 内联完成：数据库合同、CAMS SDK 写入 adapter、管理员 API/审计、webhook 验签和多账号路由、管理员前端、OpenAPI 与架构门禁。

## 已完成证据

后端专项门禁：

```text
mvn -q -Dtest=WhatsAppCallbackSchemaContractTest,AliyunWhatsAppCallbackGatewayTest,WhatsAppCallbackConfigServiceTest,WhatsAppCallbackConfigControllerTest,ChatAppWebhookInboxServiceTest,ChatAppWebhookVerifierTest,ArchitectureBoundaryTest test
```

结果：通过；7 个测试类共 17 个测试，0 failures、0 errors、0 skipped。

后端生产编译：

```text
mvn -q -DskipTests compile
```

结果：通过。

前端专项测试和构建：

```text
npm test -- --run src/pages/AdminPlatformPages.test.tsx src/pages/ChannelSettingsPage.test.tsx src/components/whatsapp/AdminWhatsAppCallbackPanel.test.tsx
npm run build
```

结果：3 个 Vitest 文件共 40 个 UI 测试通过；TypeScript/Vite 生产构建通过。`npm test` 外层脚本对参数转发产生 npm warning，但最终测试进程退出码为 0。

OpenAPI 合同：

```text
node --test message-center-v1.test.mjs
```

结果：通过，校验 43 个操作；新增三条 `/api/admin/whatsapp/cams/{scopeId}/callbacks*` 路由，并将其列为唯一允许的非 `/api/v1` 当前合同。

架构边界：

- `web`/`dto` 不依赖 CAMS SDK。
- 回调 controller 不解析 provider 凭证。
- webhook projector 不执行号码路由；号码路由由 inbox service owner 负责。
- gateway 接口归位到 `service.whatsapp`，阿里云 SDK 实现留在 `channel.chatapp` adapter。

## 行为结论

- 号码级写入调用 `UpdatePhoneWebhook`，服务端解析 `CustSpaceId`、标准化号码和凭证；浏览器只能提交 URL、flag、expectedVersion。
- 账户级写入调用 `UpdateAccountWebhook`，请求不包含上行消息 URL。
- URL 只接受 HTTPS，配置使用乐观版本；provider 失败保留旧期望值并记录失败状态与审计。
- provider 回读 API 未被确认，`providerState` 固定投影为 `UNKNOWN`；provider RequestId 仅保存于结果/审计字段，不返回浏览器。
- webhook 先验签再解析 JSON，保留 1 MiB body 上限和幂等落库；入站按 `To`，状态回执按 `From` 解析业务号码并唯一匹配 active 账号。未知或不唯一号码返回 `CHATAPP_WEBHOOK_ACCOUNT_UNRESOLVED`，不再固定单账号回退。
- 普通销售不会看到回调配置面板；账户级面板不渲染上行消息 URL。

## 未完成门禁

真实 CAMS 测试号码验收尚未执行。原因是该门禁需要授权的部署环境、已备案公网 HTTPS 回调地址、专用测试号码，以及确认过的 HMAC 字段样例。本地工作区虽然存在开发环境凭证文件，但本轮不以其触发真实 provider 写入，也不把凭证或完整号码写入文档、日志或测试产物。

部署环境需要补做：

1. 使用专用测试号码调用号码级和账户级更新，记录脱敏 scope、号码后四位、时间、结果和 provider RequestId。
2. 从新 HTTPS 地址验证入站消息和状态回执均能通过 HMAC 验签并路由到正确账号。
3. 确认旧 URL 不再收到回调，并检查 provider 实际字段命名与签名算法。

## Git 边界

本轮未提交 Git，也未回滚用户已有修改。`git diff --check` 已通过；工作区仍存在与本任务无关的用户/其他任务 WIP，交付时不得使用 `git add .` 或覆盖这些文件。
