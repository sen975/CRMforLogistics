# WhatsApp 管理员配置与销售账号分配验收记录

日期：2026-09-11

## 文档状态

当前验收记录，对应设计
`specs/2026-09-10-whatsapp-admin-managed-account-assignment-design.md`
和实施计划
`plans/2026-09-10-whatsapp-admin-managed-account-assignment.md`。

## 验收范围

- 管理员从 CAMS 已配置 scope 只读同步已有 WhatsApp 电话。
- 管理员分配、收回、转交账号时使用版本和数据库锁，避免并发双分配。
- 转交后保留历史会话只读访问；消息稳定关联账号并保留实际发送者。
- 异步发送在 provider 调用前执行 owner/version fencing。
- 前端渠道设置页只保留管理员托管账号入口，销售只显示自己的账号摘要。
- 旧 Embedded Signup、员工自助绑定、API 电话注册 HTTP 路由统一禁用。

## 已通过

后端 Task 6 自助路由专项：

```bash
mvn -q -Dtest=WhatsAppSelfServiceDisabledControllerTest,WhatsAppAuthorizationControllerTest,WhatsAppPhoneNumberControllerTest test
```

命令退出码为 `0`。在已认证上下文中，授权 attempt、授权完成、候选号码选择、电话操作创建、取码、验证和列表均返回 HTTP `410`、
`WHATSAPP_SELF_SERVICE_DISABLED`；即使请求体为空或路径 ID 格式非法，也不会被参数校验提前截成 `400`；
mock service 无交互。

后端管理员分配、同步、历史和发送专项：

```bash
mvn -q -Dtest=WhatsAppSelfServiceDisabledControllerTest,WhatsAppAuthorizationControllerTest,WhatsAppPhoneNumberControllerTest,AdminWhatsAppPhoneNumberServiceTest,AdminWhatsAppAccountSyncServiceTest,ChatAppBroadcastWorkerTest test
mvn -q -DskipTests compile
```

两条命令均退出码为 `0`。

前端：

```bash
npm test
node test/whatsapp-admin-assignment-contract.test.mjs
node test/whatsapp-admin-managed-ui-contract.test.mjs
npm run build
```

`npm test` 的源代码合同测试为 37/37，通过；UI 测试为 62 个文件、271/271，通过；两个管理员账号合同测试通过；
生产构建退出码为 `0`。

工作区格式检查：

```bash
git diff --check
```

通过。

## 关键实现结果

- 管理员接口统一为 `/api/admin/whatsapp/accounts`，支持同步、分配、收回、转交和分配历史。
- 同步只导入 `ACTIVE + VERIFIED` 号码，按标准化号码复用稳定 `channel_account_id`，号码从 CAMS 消失时只标记不可用。
- 分配请求必须提供目标销售、原因和 `expectedVersion`；同一有效号码只能分配给一个销售。
- 收回不删除 CAMS 资源、不清空加密凭据、不删除消息和会话。
- 账号转交为新销售补齐历史会话授权，原销售已有只读授权保持不变。
- 已提交 provider 消息继续按稳定账号回写；未提交且版本或 owner 变化的异步任务以
  `ACCOUNT_REASSIGNED` 终止。
- 管理员页面只显示脱敏号码；前端不再提供 Embedded Signup、授权 attempt 或 API 电话注册入口。
- 旧自助路由保留代码和历史表用于兼容识别，但当前版本不会创建状态机记录或调用 CAMS。

## 全量回归边界

```bash
mvn test
```

全量结果为：1192 个测试，4 个断言失败，35 个错误，7 个跳过。失败不来自本轮 WhatsApp 管理员托管专项：

- 既有 `EmailOwnerIsolationTest` 仍断言旧的 `messageMapper.insert` 调用；
- 既有 `ChannelAccountServiceTest` 仍期待 `CHANNEL_ACCOUNT_INCOMPLETE_CREDENTIALS`；
- 既有 `ContactGroupServiceSplitAuthorizationTest` 断言失败；
- 既有 `WeComSensitiveConfigTest` 断言失败。

35 个错误主要是 Testcontainers 无法连接本机 Docker socket（`Operation not permitted`），影响需要 PostgreSQL/MinIO
容器的集成测试和部分 WebMvc 上下文。当前环境没有可用 Docker，因此不能把这些错误归因于本轮 WhatsApp 代码，也不能声称后端全量回归通过。

## 未执行门禁

- 未使用真实 CAMS 凭据执行线上只读同步；本地专项只验证 gateway/service 合同。
- 浏览器已确认本地页面能打开，但当前没有管理员和销售登录会话，因此未完成角色化页面点击验收。
- 未删除旧自助 Controller、service、表或迁移；如需破坏性清理，应另开任务并单独确认。
