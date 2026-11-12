# CAMS WhatsApp 内嵌注册员工自助绑定验收记录（历史）

日期：2026-09-09

> 文档状态：已被 2026-09-10《WhatsApp 管理员配置与销售账号分配》设计、计划和验收记录取代。本文只保留当时的实现证据，不定义当前产品入口或验收标准；当前版本已禁用相关自助路由。

## 验收范围

本次验收覆盖 WhatsApp 自助绑定运行时的本地合同：

- 管理员 `ADMIN_API_WABA` 与员工 `BUSINESS_APP_COEXISTENCE` 使用后端启动 profile。
- 企业 API 新号码使用 `api-phone-operations`，不启动 Meta WABA 弹窗。
- 账号查询、owner-only 解绑、历史同步和操作投影已接线。
- 前端只接受 Meta 官方来源事件，不信任 CAMS 控制台 origin；不展示密钥、token、验证码和完整号码。

## 已通过

前端源代码合同测试通过，37/37：

```text
npm run test:source
```

前端 UI 测试通过，62 个测试文件、269 个测试：

```text
npm run test:ui
```

前端生产构建通过：

```text
npm run build
```

专项自助绑定合同测试通过：

```text
node --test test/whatsapp-self-service-contract.test.mjs test/whatsapp-shared-path-contract.test.mjs
```

后端 WhatsApp 授权、号码操作、账号生命周期和历史同步专项测试通过；后端编译通过：

```text
mvn -q -Dtest=WhatsAppAuthorizationServiceTest,WhatsAppAccountLifecycleServiceTest,WhatsAppAccountControllerTest,WhatsAppHistorySyncWorkerTest,WhatsAppPhoneNumberServiceTest,WhatsAppPhoneNumberControllerTest test
mvn -q -DskipTests compile
```

## 本轮实现要点

- Business App 共存和管理员 WABA 授权在创建 attempt 后按 `startupProfile.appId/configId` 延迟加载 Meta SDK，并只使用 `facebook.com` 官方来源事件。
- API-only 号码入口单独显示为“绑定企业 API 电话”，调用添加号码、确认发送验证码和一次性验证接口；不会启动 Meta SDK。
- 管理员专用“绑定企业 API/WABA”按钮由当前角色控制；普通员工不能看到该入口。
- `ADMIN_API_WABA` 只建立企业 scope；管理员需要再次走 API 电话入口绑定自己的号码，账号 owner 仍是当前登录管理员。

## 未执行与已知风险

- 未执行真实 CAMS/Meta 实机门禁：当前工作区没有可安全使用的真实凭据和登记 HTTPS 域名，因此不能声称 Embedded Signup、WABA 绑定或号码注册已实机通过。
- 更广的后端全量回归仍可能包含工作区已有用户 WIP 失败；已知 `ChannelAccountServiceTest.rejectsCreatingActiveChatAppAccountWithoutAllCredentials` 仍期待旧的通用账号错误码，不属于本轮 WhatsApp 自助绑定改动。
- 本轮执行 `mvn -q test` 的结果为 1128 个测试中 4 个断言失败、33 个错误；错误主要来自本机 Docker/Testcontainers 和网络 socket 不可用，另有既有 Email/AI Topic/模板权限 WIP 断言失败。WhatsApp 自助绑定专项测试未失败。
- 不在本轮改动生成的 ZIP、数据库本地数据或其他用户 WIP；不提交 Git。
