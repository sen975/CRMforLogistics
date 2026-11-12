# 管理员平台工作台验收记录

本轮为 Task 7 的完整门禁与文档验收。门禁结论：**后端与前端全绿**；运行态验收已在重启后的后端上完成到 HTTP 层（见第二节）；真实 CAMS 外部调用与浏览器像素级渲染**未执行**，原因见文末。

## 一、已执行的命令与结果

| 命令 | 结果 |
| --- | --- |
| `cd demo/message-center-spring/backend && mvn test` | **BUILD SUCCESS**，1347 个测试，0 失败、0 错误、0 跳过 |
| `cd demo/message-center-spring/frontend && npm run test:ui` | 通过，71 个测试文件、327 个测试（16:52 复跑） |
| `cd demo/message-center-spring/frontend && npm run build` | 通过，TypeScript 检查与 Vite 生产构建完成（2.96s） |
| `git diff --check` | 通过，无空白错误 |

后端 1347 个测试包含 Testcontainers PostgreSQL 17.5 集成测试（本轮实际执行了两个 overview 聚合查询、媒体上传并发路径和全应用上下文启动），因此不再是「未执行 SQL 的桩测试」。

## 二、运行态验收（HTTP 层）

用户重启后端后，本轮在新进程上执行了运行态验收。仓库无浏览器自动化依赖（`frontend/package.json` 无 playwright / puppeteer / cypress），因此验收止于 HTTP 层，页面像素级渲染未验证。

**进程确认**

| 项 | 值 |
| --- | --- |
| 旧后端 | PID 7034，启动于 09:02:33，早于本轮代码，`target/classes` 不含 `/overview` |
| 新后端 | PID 17152，启动于 2026-09-16 16:57:00 |
| 前端 dev server | 5173（Vite） |

**已执行的命令与结果**

| 命令 | 结果 |
| --- | --- |
| `POST /api/auth/login` `{"username":"admin","password":"admin"}` | HTTP 200，返回 `token` / `username` / `roles` |
| `GET /api/admin/whatsapp/overview`（Bearer） | HTTP 200，1 个 scope：`displayName=cams-9dou6dubx2ww`、`status=READY`、`usableAccountCount=0`、`pendingApprovalCount=0`、`lastSyncStatus=SUCCESS`、`totalPendingApprovalCount=0` |
| `GET /api/admin/whatsapp/cams` | HTTP 200，`accessKeyIdMasked=LT****JR`、`endpoint=cams.ap-southeast-1.aliyuncs.com`、`source=database`、`version=1`；响应中**无任何 secret 字段** |
| `GET /api/admin/whatsapp/accounts` | HTTP 200，`maskedPhone=*********9485`、`version=5` |
| `GET /api/admin/whatsapp/cams/{scopeId}/accounts` | HTTP 200，返回同一账号 |
| `GET /api/v1/admin/whatsapp/template-change-requests?page=1&size=20` | HTTP 200，`{items:[],total:0,page:1,size:20}` |
| 无 token 访问上述 `/api/admin/**`、`/api/v1/admin/**` | 全部 HTTP 401 |
| 经 Vite 代理 `localhost:5173/api/admin/whatsapp/overview`（Bearer / 无 token） | HTTP 200（同样数据）/ HTTP 401 |

**结论与边界**

- 浏览器实际走的链路（Vite 代理 5173 → 后端 8107 → PostgreSQL）已打通：管理员工作台的四个查询接口在真实数据上返回正常，未认证一律 401，AccessKey 只回显掩码 ID，Secret 与完整手机号都不出网（对所有抓到的响应体做过 `*secret*` 字段扫描，无命中）。
- `lastSyncedAt` 在两次 `overview` 调用之间由 `08:57:39Z` 推进到 `08:58:50Z`，随后连续两次隔 15 秒采样保持不变。仓库中除三个控制器外没有 `AdminWhatsAppAccountSyncService` 的调用方，也没有对应的调度任务，因此这不是周期性同步，而是某次手动触发的同步；`lastSyncStatus=SUCCESS` 说明该次同步的成功路径确实落了库。**该次同步底层是否真的连到了 CAMS 外部接口，本轮未验证。**
- **未验证**：页面实际渲染。`/admin`、`/admin/platforms`、`/admin/whatsapp/accounts`、`/admin/whatsapp/template-approvals` 只确认了 dev server 返回 SPA 外壳（HTTP 200），没有确认组件挂载后的画面——无浏览器自动化，未截图。
- **未验证**：登录用的是 bootstrap 默认账号 `admin/admin`（`.env` 未覆盖 `ADMIN_USERNAME`/`ADMIN_PASSWORD`）。因此非管理员返回 403 这条只由单测覆盖，本轮没有真实非管理员账号可测。

## 三、本轮修复的三个真实缺陷

门禁第一次跑出来时后端不是绿的（1347 个测试、17 个错误）。逐个定位后确认是三类互相独立的问题，均已修复并复跑验证。

### 1. 测试上下文污染（本轮工作自身的回归，由 Task 6b-fixup 引入）

- 现象：`AppIntegrationTest`、`ContactMemoryEndToEndTest`、`WhatsAppTemplateMediaUploadIntegrationTest` 三个全应用上下文测试类的**每一个**测试都报错，共 17 个错误。
- 根因（取自 surefire 报告）：

  ```
  org.springframework.context.annotation.ConflictingBeanDefinitionException: Annotation-specified bean name
  'channelAccountMapper' for bean class [...mapper.ChannelAccountMapper] conflicts with existing,
  non-compatible bean definition of same name and class [null]
      at org.mybatis.spring.mapper.ClassPathMapperScanner.checkCandidate
  ```

- 机制：Task 6b-fixup 新建的 `AdminWhatsAppWorkbenchMapperIntegrationTest` 把嵌套 `@TestConfiguration` 放在 `com.crmforlogistics.messagecenter.mapper`，而该包在应用组件扫描范围内。嵌套配置里的 `@Bean channelAccountMapper` 被扫进全应用上下文，与 `MyBatisPlusConfig` 的 `@MapperScan("com.crmforlogistics.messagecenter.mapper")` 注册同名 bean 冲突，导致整个上下文加载失败。仓库既有约定是所有测试配置类放在 `com.crmforlogistics.messagecentertest.*`（该包不在扫描范围内），本文件是唯一例外。
- 修复：抽出为 `backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/AdminWhatsAppWorkbenchMapperTestConfiguration.java`，测试类改为引用它。
- 验证：`mvn test -Dtest=AppIntegrationTest` 修复前 9 个错误，修复后 0 个错误；全量 17 个错误降到 4 个（剩余 4 个即下面两类）。同时删除了 `target/test-classes` 下残留的旧嵌套配置 class 文件，否则它仍会留在测试 classpath 上。

### 2. 产品 SQL 缺陷：`display_name` 设为 NOT NULL 后，三个 `@Insert` 都没有写这一列

`V77__whatsapp_multi_cams_admin_workbench.sql:16` 把 `whatsapp_provider_scopes.display_name` 设为 `NOT NULL`（无默认值），但 `WhatsAppProviderScopeMapper` 有三处插入语句没有跟上：

| 语句 | 行 | 运行时调用方 |
| --- | --- | --- |
| `insertIgnore` | `WhatsAppProviderScopeMapper.java:76-80` | `WhatsAppProviderScopeService.bind():67`，其调用方含 `requireOwnedActive`、`requireAccount`、启动时的 `WhatsAppTemplateScopeMigrationService.migrate():60` |
| `insertBound` | `WhatsAppProviderScopeMapper.java:49-55` | `WhatsAppAuthorizationService:537` |
| `upsertAdminConfigured` | `WhatsAppProviderScopeMapper.java:86-96` | `WhatsAppCamsConfigService.resolveScopeForSync()`（环境变量 CustSpaceId 的兼容回退路径） |

- 现象（真实堆栈）：

  ```
  org.postgresql.util.PSQLException: ERROR: null value in column "display_name" of relation
  "whatsapp_provider_scopes" violates not-null constraint
    ... SQL: insert into whatsapp_provider_scopes (provider, external_scope_id, status) values (?, ?, 'READY') on conflict ...
      at ...WhatsAppProviderScopeMapper.insertIgnore-Inline
  ```

  它同时表现为两种症状：`WhatsAppTemplateMediaUploadIntegrationTest.staleProcessingConvergesToUnknownWithoutCallingGateway` 直接抛异常（ERROR）；`concurrentSameRequestCallsGatewayOnceOutsideTransaction` 则是工作线程内吞掉异常、发射端从未进入网关，断言在 `WhatsAppTemplateMediaUploadIntegrationTest.java:123` 失败。两者是同一个根因。
- 影响范围：新 scope 的首次插入必然失败；`ON CONFLICT` 命中已有 scope 时不走插入路径，所以管理员工作台新建 scope（走 `insertAdminScope`，本来就带 `display_name`）不受影响，但环境变量 CustSpaceId 的首次绑定、启动迁移和自助绑定路径会硬失败。
- 修复：三处均补上 `display_name`，取值 `external_scope_id`，与 V77 的回填口径（`COALESCE(NULLIF(display_name,''), external_scope_id)`）一致。
- 验证：`AppIntegrationTest`、`WhatsAppTemplateMediaUploadIntegrationTest`、`AdminWhatsAppWorkbenchMapperIntegrationTest`、`WhatsAppProviderScopeServiceTest`、`AdminWhatsAppAccountSyncServiceTest` 共 30 个测试 0 失败；随后全量 1347 个测试 0 失败。

### 3. 过时夹具：裸插 scope 行缺少 `display_name`

`AppIntegrationTest.java:112` 与 `:145` 用 `INSERT INTO whatsapp_provider_scopes (id, provider, external_scope_id)` 直接造数据，V77 之后必然违反 NOT NULL。已补 `display_name`，与分支上既有提交 “test: realign stale fixtures with the current schema and services” 是同一类修正。

## 四、本轮新增的功能证据（Task 6 交付物）

- `GET /api/admin/whatsapp/overview`（`AdminWhatsAppWorkbenchController.java:25`）是管理员首页的唯一数据源：服务端按 scope 聚合可用号码数、待审批数、最近测试/同步状态和错误码，并给出待审批总数。
- `AdminHomePage.tsx:11` 只保留一个 overview 查询，删除了原先的 `fetchAdminCams` 与审批队列查询；页面不再在浏览器里推断账号/模板/审批状态。
- 新增管理员路由：`/admin`、`/admin/platforms`、`/admin/whatsapp/accounts`、`/admin/whatsapp/template-approvals`。
- CAMS 配置按 `provider_scope_id` 管理，支持多实例、停用、版本校验和 Secret 留存更新；`/settings/channels` 只保留通用渠道设置并给出迁移提示。
- 审批改动的跨 scope 防护由 `WhatsAppTemplateChangeRequestServiceTest` 用 3 个测试钉住（跨 scope 批准不触达 provider、retry 走同一防护、同 scope 正常执行的对照）。

## 五、未执行

- **页面渲染未验收**。本轮已完成 HTTP 层运行态验收（见第二节），但无浏览器自动化可用，四个管理员路由挂载后的画面未确认，未截图。
- **真实 CAMS 凭证下的连接测试、号码同步和官方模板对账**：需要有效 AccessKey 与外部网络，界面上对应的连接测试/同步按钮只经过单测与集成测试覆盖（如 `WhatsAppCamsConfigServiceTest`）。第二节记录的那次 `lastSyncStatus=SUCCESS` 的手动同步，底层是否真的连到 CAMS 外部接口，本轮未确认。
- **非管理员 403 未在运行态验证**：dev 环境只有 bootstrap 默认的 `admin` 账号，403 仅由单测覆盖。
- 未做任何 MinIO 的外部调用验证。

## 六、已知遗留风险

- **共享模板目录与多 CAMS 的冲突**：`WhatsAppTemplateScopeMigrationService.migrate()` 在启动时要求 `whatsapp_provider_scopes` 中恰好一个企业 scope，检测到第二个就 `gate.fail("WHATSAPP_PROVIDER_SCOPE_MISMATCH", ...)`，共享模板接口随后以 503 失败。即管理员登记第二个 CAMS 后，共享模板目录会整体关闭。这是本分支多 CAMS 工作与既有单 scope 门禁之间的真实冲突，设计规格把「跨 scope 全局模板目录」明确排除在范围外（`2026-09-16-admin-platform-workbench-design.md:144`），本轮按现状记录，未改动门禁逻辑。
- `assertCompatible`（`WhatsAppProviderScopeService.java:88`）保留「所有 WhatsApp 账号必须属于同一个模板空间」的校验，这是既有设计（`2026-09-08-whatsapp-business-app-coexistence-multi-number-design.md:129` 明确要求保留该兼容校验）。管理员侧的 scope-first 账号路径走 `AdminWhatsAppAccountSyncService`，不经过 `bind()`，因此不受影响。

## 七、最终整支审查与修复

SDD 收尾门禁：对管理员工作台范围（22 个已跟踪文件的 diff + 19 个新文件全文，`.superpowers/sdd/review-final-admin-workbench.diff`，4187 行）派发了整支代码审查。结论 **With fixes**：0 Critical、3 Important、13 Minor。三条 Important 已修复并复跑门禁验证。审查同时确认：越权防护（`checkScope` 覆盖 assign/reclaim/transfer/history 四条路径）、控制器级管理员守卫、Secret 只在服务端加密且响应只回显掩码 ID，这三项都有能在生产代码被改坏时真正失败的测试钉住。

### 1. 同步失败状态永远落不了库（Important）

- 缺陷：[AdminWhatsAppAccountSyncService.java:76](demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/AdminWhatsAppAccountSyncService.java) 的 `sync(UUID,UUID)` 标了 `@Transactional`，catch 里写 `touchSyncResult(..., "FAILED", ...)` 后 rethrow；运行时异常触发回滚，FAILED 被一并撤销。只有三个管理员控制器调用它，全部经代理。
- 影响：管理员首页"同步失败：<CODE>"提示对真实同步失败**永远不会出现**。`test()` 因为不在事务里，"连接测试失败"是正常的——这种不对称掩盖了问题。原测试只断言了 SUCCESS。
- 修复：`sync` 不再 `@Transactional`，事务体移入 `performSync` 由 `TransactionTemplate` 包住；FAILED 写入放在 `execute(...)` **之外**的 catch 中，此时回滚与连接释放都已完成，而 MyBatis-Spring 对无 Spring 事务的 mapper 调用会强制提交，因此不依赖连接池的 `auto-commit` 配置。
- 被否掉的第一版修复：改用 `TransactionSynchronization.afterCompletion` 延迟写入。它只在连接池 autoCommit=true 时碰巧生效（依赖 JDBC"setAutoCommit(true) 即提交"这条语义和 Spring 的清理顺序），一旦有人配置 `auto-commit=false` 就静默丢失。也未改用 `REQUIRES_NEW`：`resolveScopeForSync()`（[WhatsAppCamsConfigService.java:124](demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/WhatsAppCamsConfigService.java#L124)）会在外层事务里 upsert 同一行，内层事务会自死锁。
- 证据：新增 `AdminWhatsAppAccountSyncServiceFailurePersistenceIntegrationTest` 与 `AdminWhatsAppAccountSyncServiceFailurePersistenceAutoCommitDisabledIntegrationTest`（后者显式设 `spring.datasource.hikari.auto-commit=false`，并真实 stub 网关抛异常）。把 afterCompletion 版本还原回去时，新测试 2/2 失败（`expected "FAILED" but was null`）；当前实现通过。

### 2. 审批页筛选只筛当前页，却显示未筛选的总数（Important）

- 缺陷：`AdminWhatsAppTemplateApprovalsPage.tsx` 在前端对已取回的一页做筛选，分页器却用服务端未筛选的 `total`——筛"已拒绝"会显示"暂无"而页码仍说有几十条，等于误导。
- 修复：`TemplateChangeRequestMapper.listForReview` / `countForReview` 增加可选 `status` 与 `search`（`ilike`，全部绑定参数，LEFT JOIN `message_templates` 与 `users`），**列表与计数同筛选**；控制器与前端透传，筛选值进入查询键，前端不再做客户端过滤。这也符合"前端不得推断服务端状态"的既有约束。

### 3. 注册第二个 CAMS 会把共享模板功能对所有 scope 打挂（Important，产品决策）

- 现状：`assertCompatible`（[WhatsAppProviderScopeService.java:88-98](demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppProviderScopeService.java#L88-L98)）只要发现任一 scope 不同就抛 409，而 `bind()` 会调它；启动时 `WhatsAppTemplateScopeMigrationService` 要求恰好一个企业 scope，否则门禁 BLOCKED（503）。前端原本没有任何拦截。
- 决定（用户选择）：保留既有门禁语义（跨 scope 全局模板目录明确不在范围内），改为在 `/admin/platforms` 创建第二个企业 scope 时弹二次确认，明确告知会停用所有平台的共享模板目录；**取消时不发起任何请求**。编辑路径与全新安装（列表为空）不受影响。

### 4. `ConfigRequest.toString()` 泄漏 AccessKey Secret（Minor，已顺手修）

`ConfigRequest` 是持有 `accessKeySecret` 的 record，自动生成的 `toString()` 会打印密钥。现在有人加一行 debug 日志就是泄密，违反"Secret 不得出现在任何响应、日志和前端状态"的约束。已改为固定 `[REDACTED]`。

### 复查修复后复跑的门禁

| 命令 | 结果 |
| --- | --- |
| 后端 `mvn test` | **BUILD SUCCESS**，1365 个测试，0 失败、0 错误、0 跳过 |
| 前端 `npm run test:ui` | 通过，71 个测试文件、332 个测试 |
| 前端 `npm run build` | 通过（3.18s） |
| `git diff --check` | 通过，无空白错误 |

> 第一节表格记录的是审查之前那一轮的门禁数字（后端 1347、前端 327），本轮修复后已更新为上表。

## 八、工作树边界

工作树原有的待办、联系人记忆、企业微信和应用配置改动未被回滚，也未使用 `git add .` 或任何破坏性 Git 命令。本轮改动分布在：

- `backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java`
- `backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/AdminWhatsAppWorkbenchMapperTestConfiguration.java`（新增）
- `backend/src/test/java/com/crmforlogistics/messagecenter/mapper/AdminWhatsAppWorkbenchMapperIntegrationTest.java`
- `backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`
- `demo/message-center-spring/README.md`
- 本文件

未创建 git commit（用户要求只在明确要求时提交）。
