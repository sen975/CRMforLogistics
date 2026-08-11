# WhatsApp 消息中控 MVP 阻断收口实施计划

> 日期：2026-08-10
> 范围：只收口消息详情授权、模板审核状态、渠道设置权限与固定号码、凭据主密钥四个阻断项。

## 完成状态

- [x] 消息详情在返回正文前执行统一会话授权，越权返回 403。
- [x] 模板同步持久化 CAMS 真实审核状态和原因，发送只接受 `APPROVED`，历史消息仍可恢复模板正文。
- [x] 渠道设置统一要求管理员权限，ChatApp/WhatsApp 固定号码不可修改。
- [x] 凭据主密钥改为 fail-closed，启动必须提供 `CREDENTIAL_MASTER_KEY`。
- [x] 定向测试、全量非集成测试和 PostgreSQL 17.5 集成测试通过。

## 边界

- 不修改 `backend/src/main/resources/application-dev.yml`，也不处理其中既有凭据。
- 不扩展号码注册、多号码切换、轮询游标、旧 Webhook、SSE 或空库账号初始化。
- 保留工作区中所有既有修改；只修改本计划列出的调用链。
- 每项遵循 RED -> GREEN，定向测试通过后再进入下一项。

## 任务 1：消息详情服务端授权

1. 运行 `MessageControllerAuthorizationTest`，确认越权详情读取当前返回 200 而测试期望 403。
2. 在 `MessageController` 的消息详情查询中调用统一的 `ConversationAccessService`，以消息所属会话和渠道账号校验当前用户。
3. 重跑测试，验证 owner 可读、非 owner 返回 403、缺失消息保持原有语义。

验收命令：

```bash
mvn -q -Dtest=MessageControllerAuthorizationTest test
```

## 任务 2：模板同步保留真实审核状态

1. 为 CAMS `auditStatus` 到本地状态的映射增加失败测试：`pass/APPROVED`、`fail/REJECTED`、`auditing/PENDING`、`unaudit/SUSPENDED`、空值或未知值 `UNKNOWN`。
2. 在模板同步的核心映射中使用该标准化结果，禁止继续硬编码 `APPROVED`。
3. 将上游原始 `auditStatus` 与 `reason` 作为结构化 metadata 持久化；详情读取失败也必须更新审核状态，并保留已有正文。
4. 发送路径仍只接受 `APPROVED`，历史消息正文恢复不受模板当前审核状态影响。
5. 通过参数捕获和 PostgreSQL 冲突更新验证 mapper 收到的状态与 metadata。

验收命令：

```bash
mvn -q -Dtest=ChatAppTemplateSyncServiceTest test
```

## 任务 3：渠道设置管理员权限与固定号码

1. 增加接口授权测试，证明普通销售访问 `/api/channel-accounts/**` 被拒绝，管理员可访问。
2. 在服务端安全边界对该路径强制 `ROLE_ADMIN`。
3. 增加业务测试，证明管理员也不能通过设置接口修改 ChatApp/WhatsApp 的 `accountIdentifier`；名称等非号码信息保持可改。
4. 不把数据库凭据描述为 CAMS 运行时切换真源，当前运行时仍由 `AppConfig` 管理。

验收命令：

```bash
mvn -q -Dtest=ChannelSettingsControllerAuthorizationTest test
```

## 任务 4：凭据主密钥 fail-closed

1. 增加配置测试，证明主密钥为空时拒绝创建 `CredentialCipher`，合法密钥可以创建。
2. 删除固定开发主密钥回退，通过 Spring 配置读取 `credential.master-key`，并允许环境变量 `CREDENTIAL_MASTER_KEY` 映射进入该配置。
3. 给需要启动完整 Spring 容器的测试注入测试专用密钥；生产与开发配置文件不写固定值。
4. 在实施记录中明确 IDEA 启动必须设置 `CREDENTIAL_MASTER_KEY`。

验收命令：

```bash
mvn -q -Dtest=CredentialConfigTest test
```

## 总验收

```bash
mvn -q -Dtest='MessageControllerAuthorizationTest,ChatAppTemplateSyncServiceTest,ChannelSettingsControllerAuthorizationTest,CredentialConfigTest' test
mvn -q -Dtest='*Test,!AppIntegrationTest,!ChatAppIntegrationTest' test
mvn -q -Dapi.version=1.44 -Dapp.chatapp-outbox-enabled=false -Dtest=AppIntegrationTest test
git diff --check -- demo/message-center-spring docs/superpowers/plans/2026-08-10-whatsapp-mvp-blockers.md docs/superpowers/plans/2026-08-07-whatsapp-message-control-mvp.md
```

已知非本轮项：`application-dev.yml` 文件尾空行由既有修改产生，本轮保持不动并在验收结果中单独说明。`AppIntegrationTest` 启动时仍输出既有的 MyBatis mapper 重复扫描 warning；不影响本轮 6 个断言，但应在后续测试配置治理中消除。

## 实际验收结果

- `mvn -q -Dtest='MessageControllerAuthorizationTest,ChatAppTemplateSyncServiceTest,ChannelSettingsControllerAuthorizationTest,CredentialConfigTest,TemplateMessageTextResolverTest' test`：通过。
- `mvn -q -Dtest='*Test,!AppIntegrationTest,!ChatAppIntegrationTest' test`：通过。
- `mvn -q -Dapi.version=1.44 -Dapp.chatapp-outbox-enabled=false -Dtest=AppIntegrationTest test`：6/6 通过；PostgreSQL 17.5、Flyway V1-V8、模板状态冲突更新及正文保留均通过。
- `git diff --check`：本轮文件无新增空白错误；全路径检查仅保留用户明确要求不修改的 `application-dev.yml` 文件尾空行。
