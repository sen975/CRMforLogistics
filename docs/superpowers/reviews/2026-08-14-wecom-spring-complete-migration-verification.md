# 企业微信 Spring 完整迁移验收记录

**验收日期：** 2026-08-17

**验收范围：** `demo/message-center-spring` 的企业微信登录、绑定、授权、凭据、chatdata 投影、viewer API、OpenDataFrame 前端接线、配置与模块隔离。

**当前结论：** 企业微信范围自动化验收通过；全仓门禁仍被一个非企业微信 ChatApp WIP 测试错误阻断；真实企业微信环境尚未验收。因此当前可以继续部署前联调，但不能宣称迁移最终完成或生产就绪。

## 1. 环境前提

- Java 17、Maven、Node.js `v26.5.0`。
- Docker Desktop `4.82.0`，Docker Engine `29.6.1`，服务端 API `1.55`，最低 API `1.40`。
- 项目使用 Testcontainers `1.20.6`。该版本的 docker-java 在当前 Docker 上默认请求 API `1.32` 会收到 HTTP 400；真实容器测试必须向 Maven 传入 JVM system property `-Dapi.version=1.44`。
- `DOCKER_API_VERSION=1.44` 对本项目当前 docker-java 调用链无效，不能替代上述 Maven 参数。

## 2. 后端验证

### 2.1 企业微信专项

```bash
cd demo/message-center-spring/backend
mvn -q -Dapi.version=1.44 -Dtest='*WeCom*Test' test
```

结果：退出码 `0`。`WeComChatDataTransactionIntegrationTest` 使用真实 PostgreSQL 容器运行 2 项测试，`errors=0`、`failures=0`、`skipped=0`，没有因 Docker 不可用而跳过。

专项验证期间补充运行：

```bash
mvn -q -Dtest=WeComModuleIsolationTest test
mvn -q -Dapi.version=1.44 -Dtest=AppIntegrationTest test
```

结果：两条命令退出码均为 `0`。前者验证关闭企业微信模块时不会注册 WeCom Controller/Service；后者验证未配置 suite ID 的完整 Spring 上下文可以启动。

### 2.2 后端全量

```bash
cd demo/message-center-spring/backend
mvn -q -Dapi.version=1.44 test
```

结果：退出码非 `0`；`Tests run: 654, Failures: 0, Errors: 1, Skipped: 0`。

唯一错误：

```text
com.crmforlogistics.messagecenter.mapper.ChatAppPeerReconciliationMapperSqlTest
insertMessage(ChatAppPeerReconciliationMapperSqlTest.java:172)
PostgreSQL cannot infer the SQL type for java.time.Instant
```

分级：全仓门禁阻断，但不属于企业微信迁移回归。该测试及其 ChatApp 实现属于用户正在进行的 WIP，本轮遵守 Git 和范围边界，没有修改它。

### 2.3 打包

```bash
cd demo/message-center-spring/backend
mvn -q -DskipTests package
```

结果：退出码 `0`，生成 `target/message-center-1.0.0-SNAPSHOT.jar`。该结果只证明编译与打包成功，不覆盖上面的全量测试阻断。

## 3. 前端验证

### 3.1 企业微信专项

```bash
cd demo/message-center-spring/frontend
npx vitest run src/components/wecom src/wecom src/pages/LoginPage.test.tsx src/pages/ThreadPage.wecom.test.tsx
```

结果：7 个测试文件、20 项测试全部通过。

### 3.2 全量测试与生产构建

```bash
npm test
npm run build
```

结果：

- source contract：28/28 通过。
- Vitest UI：26 个测试文件、151 项测试全部通过。
- Vite 生产构建：退出码 `0`，生成 `dist/`。

Node 26 的 jsdom 环境没有提供可用的 `window.localStorage`，验收中已在测试专用 `test/setup.ts` 添加内存实现；生产代码和浏览器持久化合同没有因此改变。

## 4. 仓库与敏感字段门禁

```bash
cd ../../..
git diff --check
```

结果：退出码 `0`，没有空白错误。

```bash
rg -n 'permanentCode|secretKey|access_token|suite_ticket' \
  demo/message-center-spring/backend/src/main/java \
  | rg 'log\.|System\.(out|err)|body\(|Map\.of'
```

结果：无命中。

```bash
rg -n \
  'wecom-(suite-secret|login-suite-secret|secret|token|encoding-aes-key): [^$]' \
  demo/message-center-spring/backend/src/main/resources
```

结果：无命中。tracked YAML 中没有硬编码上述企业微信敏感配置。

## 5. 浏览器 QA

已在以下视口进行真实渲染和交互检查：

- 桌面：`1440 × 900`。
- 移动端：`390 × 844`。

已检查：

- 密码登录与企业微信官方登录入口并列显示。
- 后端不可用时展示紧凑、可重试的错误态。
- 桌面和移动端未发现布局重叠；移动端没有额外横向或纵向页面滚动。
- 登录容器的 `box-sizing`、页面默认边距和全局样式接线已验证。

证据边界：本次交互检查没有保存独立截图 artifact，也没有保存独立 console transcript，因此不能把截图或 console 记录列为发布证据。Task 12 的“截图路径”要求当前未满足，后续真实企业微信验收时必须补齐。

## 6. 真实企业微信环境待验收

以下能力不能用 fixture 或空凭据伪造成功，当前均标记为“需要真实企业微信环境”：

- 企业微信官方二维码展示、扫码回调、一次性 state 交换和首次扫码自动建号。
- 已有密码账号扫码绑定、重复交换幂等、解绑限制和后续扫码登录。
- 官方 `ww-open-message` / OpenDataFrame 加载真实原文、详情 modal 及建议尺寸。
- 授权回调后向企业微信上游注册 chatdata 公钥，并验证定时补偿。
- 真实 `conversation_viewer_sync` 数据、方向、统一联系人时间线、SSE 自动加入新消息和 cursor 推进。
- 真实部署域名下的 JS-SDK origin、回调 URL、Nginx 转发与 Cookie/安全头行为。

真实环境验收需要至少提供并正确配置：

- `CREDENTIAL_MASTER_KEY`。
- `WECOM_SUITE_ID`、`WECOM_SUITE_SECRET`、`WECOM_TOKEN`、`WECOM_ENCODING_AES_KEY`。
- `WECOM_LOGIN_SUITE_ID`、`WECOM_LOGIN_SUITE_SECRET`、`WECOM_LOGIN_AUTH_CORP_ID`、`WECOM_LOGIN_REDIRECT_URI`。
- `WECOM_CHATDATA_PROGRAM_ID`、`WECOM_CHATDATA_ABILITY_ID`、`WECOM_CHATDATA_PRIVATE_KEY_FILE`、正确的公钥版本。
- `WECOM_ALLOWED_JSAPI_ORIGINS` 以及企业微信后台已登记的可信域名和回调路径。

仓库历史中如果曾出现真实 secret、token 或 AES key，部署前必须在企业微信后台轮换，不能只从当前 YAML 删除。

## 7. Task 12 暴露并修复的问题

- 事务集成测试只导入事务管理器但没有启用事务注解代理，导致投影失败后 chatdata 引用未回滚；已导入 `TransactionAutoConfiguration` 并用真实 PostgreSQL 验证回滚。
- `WeComChatDataMessageMapper` 的自定义 INSERT 绕开了 ID 生成，导致 UUID 策略告警；正式同步和本地 fixture 现在都在写入前显式生成 UUID，并由测试断言。
- `WeComAuthController` 在 suite ID 为空或企业微信模块关闭时仍会注册，导致完整应用缺少依赖而启动失败；现在同时受模块开关和 suite ID 条件约束。
- Node 26 测试环境缺少 `localStorage`；已添加测试专用有界内存实现。

## 8. Git 边界与最终决定

- 当前目录是 linked worktree，分支为 `codex/message-center-presplit-runtime`。
- 工作区和暂存区同时存在大量用户 WIP；Task 12 没有执行 `git add`、`git restore --staged`、`git reset`、`git commit` 或任何笼统暂存操作。
- 本验收文档和设计状态更新仅保留在工作区，等待用户后续精确暂存。

最终决定：企业微信迁移的本地专项自动化证据已经闭合，但全仓测试和真实企业微信证据尚未同时闭合。修复或明确豁免 ChatApp WIP 测试，并完成第 6 节实机验证、截图和 console 证据后，才能把设计状态改为“自动化验收完成，真实企业微信环境验收完成”并宣称迁移最终完成。
