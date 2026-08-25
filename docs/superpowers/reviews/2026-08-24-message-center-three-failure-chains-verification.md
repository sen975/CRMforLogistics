# 消息中心三条故障链路验收记录

## Task 1 基线

日期：2026-08-24

### Git 与 WIP 边界

- 当前 worktree：`/Users/z/workItem/CRMforLogistics-message-center-presplit-runtime`
- 当前分支：`codex/message-center-presplit-runtime`
- 基线提交：`e82f68dfb6c8224aa8adcfe47905319a5eb12ec5`
- worktree 原先因父仓 `/Users/z/workItem/CRMforLogistics` 临时不可达而报 `fatal: not a git repository: (null)`；父仓恢复后，`git worktree list --porcelain` 能列出该 worktree 和父 worktree。
- 当前存在大量用户 WIP（审计时：177 modified、4672 deleted、224 untracked）。本轮只新增计划、验收记录和下述 WebMvc 测试环境固定；未重置、删除或暂存任何既有 WIP。
- 与本轮目标重叠的既有 WIP：`WeComViewerService.java`、`ThreadPage.tsx` 已暂存，`WeComTimelineSegment.tsx` 未跟踪。后续实现必须在当前内容上增量完成。

### 已确认的 owner 和合同

- `WeComViewerService` 是 Viewer Session 的唯一 owner。当前 `storeViewerSession` 会调用 `removeViewerSessionsForToken`，因此同一 Viewer Token 新建 B 会删除 A；`viewerSession` 的按 ID 单次消费语义应保留。
- `readViewerMessages` 仍按当前登录绑定的 `wecomUserId` 过滤消息；这项数据权限不属于联系人 A/B 切换修复范围。
- `WeComTimelineSegment` 当前仅用 effect 内 `cancelled` 布尔值。旧请求已发出的 HTTP、SDK 初始化和 frame 回调仍可完成，尚无请求代次或 `AbortController`。
- `ThreadPage` 当前请求回调没有联系人代次隔离，旧 A 响应可在路由切到 B 后写入状态。

### 专项命令与结果

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=WeComViewerServiceTest,WeComViewerControllerTest,WeComViewerCredentialBoundaryTest,WeComViewerAuditTrailTest test
```

首次运行失败：`WeComViewerControllerTest` 9 项中 8 项返回 404，Controller 被 `@ConditionalOnProperty(app.wecom-enabled)` 排除而未注册。用以下命令验证后全部通过：

```bash
mvn -q -Dapp.wecom-enabled=true -Dtest=WeComViewerControllerTest test
```

为使 WebMvc slice 不再依赖外部配置，已将 `WeComViewerControllerTest` 固定为 `@WebMvcTest(..., properties = "app.wecom-enabled=true")`。随后原 Viewer 专项命令退出码为 0。

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest,EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
```

退出码为 0。输出中的附件存储、附件限制和 SMTP 失败栈来自用例显式注入的失败路径，不是套件失败。

```bash
cd demo/message-center-spring/frontend
npm run test:ui -- --run src/components/wecom/WeComTimelineSegment.test.tsx src/components/wecom/WeComConversationPanel.test.tsx src/pages/ThreadPage.wecom.test.tsx
```

退出码为 0，3 个文件、9 个测试通过。现有用例没有覆盖带 deferred 请求的 A -> B -> A 路由切换。

## 后续门禁

- Task 2 先新增并观察 A/B 并存、单 Session 消费、TTL 和容量淘汰的失败用例，再修改 `WeComViewerService`。
- Task 3 和 Task 4 分别新增 Viewer frame 和 ThreadPage 的延迟 A/B 请求回归用例，不能以当前 9 个前端基线用例代替。
- 未完成专项与全量回归前，不生成 Jar、前端部署包或发布物。

## 执行更新（2026-08-24）

### 已闭合的目标链路

- Viewer Session 已改为按 session ID 并存：读取或消费 A 只影响 A；仍校验 viewer token 归属、`wecomUserId` 数据权限、TTL、范围合同和单次消费。每个 token 的活动 session 上限在 service monitor 内按最旧创建顺序淘汰，且不删除刚创建的 session。
- `WeComTimelineSegment` 使用 effect generation 与 `AbortController` 隔离联系人切换；`ThreadPage` 对联系人请求、读取标记和状态回写使用独立 generation。前端回归覆盖 A -> B -> A、晚到失败以及晚到 SDK 初始化不能影响当前容器。
- ChatApp 将 `Success`、`Successful`、`Succeeded`、`OK` 归一化为 `submitted`；只有相同 durable event identity 才返回 `DUPLICATE_EVENT`，不将上游接受状态写成 `delivered`。
- 邮件链路保持 `imapUser > 环境变量 > account_identifier` 优先级；JavaMail/139 `errno=1310` 认证拒绝映射为 `EMAIL_IMAP_AUTHENTICATION_FAILED`，统一异常响应和定时任务仅保留脱敏诊断。`scripts/message-center-server-acceptance.sh` 为只读脚本，已通过 `bash -n`；本机没有 `shellcheck`。

### 本轮 WebMvc 测试上下文修复

根因是以下 Controller 同时受 `@ConditionalOnWeComEnabled` 和非空 `app.wecom-suite-id` 条件控制，而相关 WebMvc 测试只设置了 suite ID 或两者都未设置，导致测试请求落到静态资源处理器并返回 404。生产开关保持不变；仅测试上下文显式设定：

- `WeComControllerTest`
- `WeComCallbackCompatibilityTest`
- `WeComP0ControllerTest`
- `WeComP0ControllerSecurityTest`
- `WeComAuthControllerSecurityTest`

每个测试均使用 `app.wecom-enabled=true` 和 `app.wecom-suite-id=test`。其中 P0 安全测试先前虽然通过，却是在 Controller 未注册时由安全过滤器给出 401/403；现在验证的是实际注册 Controller 的安全边界。

先前 RED：

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=WeComControllerTest,WeComCallbackCompatibilityTest,WeComP0ControllerTest,WeComAuthControllerSecurityTest test
```

结果：21 项中 19 项失败，均为预期状态与 404 不符。

GREEN：

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=WeComControllerTest,WeComCallbackCompatibilityTest,WeComP0ControllerTest,WeComP0ControllerSecurityTest,WeComAuthControllerSecurityTest test
```

退出码为 0。

### 当前专项和全量门禁

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=WeComViewerServiceTest,WeComViewerControllerTest,WeComViewerCredentialBoundaryTest,WeComViewerAuditTrailTest,WeComControllerTest,WeComCallbackCompatibilityTest,WeComP0ControllerTest,WeComP0ControllerSecurityTest,WeComAuthControllerSecurityTest,ChatAppMessageStatusNormalizerTest,ChatAppPollingProjectorTest,ChatAppContactProjectionTest,EmailSyncServiceTest,EmailSyncSettingsDiagnosticTest,EmailControllerTest test
```

退出码为 0。日志中的邮件附件、SMTP 和 scheduler 异常是各测试显式注入的失败分支，并未导致测试失败。

```bash
cd demo/message-center-spring/frontend
npm test
```

退出码为 0：源码合同测试 29/29；Vitest 34 个文件、176 个测试全部通过。

```bash
cd demo/message-center-spring/backend
mvn -q test
```

已在受控外部执行环境中运行。结果为 753 项测试、0 failures、10 errors、6 skipped；10 个 error 全为 Testcontainers 初始化失败。Docker Desktop 的 Unix socket 可响应，但 daemon 返回 HTTP 400、空 `ServerVersion`、空 `OperatingSystem` 与零资源能力，因此容器无法启动。受影响套件包括 `AppIntegrationTest`、ChatApp/WhatsApp migration、mapper integration 及模板媒体集成测试。该问题不由本轮源码引入，且继续修改业务代码无法恢复容器环境。

### 未执行与发布边界

- 后端全量未通过，故未执行 `mvn -q -DskipTests package`，未生成 Jar 或 SHA-256。
- 依照统一门禁，未执行前端生产构建、未生成 `dist`、部署 ZIP 或 SHA-256，也未启动服务进行浏览器 A -> B -> A 实机验收。
- 139 若仍报 `errno=1310`，仍需有效 IMAP 客户端授权码；这是外部凭证/服务端认证阻断，不能报告为代码认证逻辑已完全解决。
