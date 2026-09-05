# WhatsApp 共享模板与变更审批验收记录

日期：2026-09-05

关联真源：[设计](../specs/2026-09-05-whatsapp-shared-template-approval-design.md)、[实施计划](../plans/2026-09-05-whatsapp-shared-template-approval.md)。本记录覆盖提交 `3847365` 至 `e45c8ce` 的九个 Task；`message_templates` 是唯一共享模板真源，渠道账号只提供当前操作者的私有凭证。

## 已完成的实现与专项验证

- Task 1 建立 V49 共享 schema、provider scope、变更申请、媒体绑定和迁移门禁，提交 `3847365`。
- Task 2 绑定 CAMS provider scope 并保护账号切换，运行：

  ```bash
  mvn -Dtest='WhatsAppProviderScopeServiceTest,ChannelAccountServiceTest,ChannelAccountOwnerIsolationTest' test
  ```

  结果：17 tests，0 failures，0 errors。

- Task 3 完成历史账号副本归并、共享同步与调度去重，运行：

  ```bash
  mvn -Dtest='WhatsAppTemplateScopeMigrationServiceTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppTemplateReconciliationSchedulerTest,ChatAppTemplateSyncServiceTest' test
  ```

  结果：31 tests，0 failures，0 errors。

- Task 4 至 Task 7 建立共享目录、新模板官方申请、媒体上传、普通用户审批状态机、管理员批准/拒绝/重试/直接执行，以及发送侧按当前账号 scope 投影；旧账号级模板管理路由已删除。对应提交依次为 `ac1ffee`、`cce6585`、`4971a10`、`028c65e`。
- Task 8 实现共享目录、我的申请、管理员审批与字段差异预览，专项前端测试运行 6 个文件、20 tests，均通过；提交 `e45c8ce`。

## 发布前验证

前端全量测试和生产构建在主工作区运行：

```bash
cd demo/message-center-spring/frontend
npm test
npm run build
```

结果：测试 source 29/29、UI 297/297 通过；生产构建退出码为 0。

后端生产包在主工作区运行：

```bash
cd demo/message-center-spring/backend
mvn -Pproduction clean package -DskipTests
```

结果：退出码为 0，生成 `backend/target/message-center.jar`。受当前 macOS shell 缺少 Java Runtime 影响，`jar tf` 不能运行；使用 `unzip -l backend/target/message-center.jar` 直接确认包内存在：

```text
BOOT-INF/classes/db/migration/V49__whatsapp_shared_template_approval.sql
```

前端部署包在主工作区生成并验证：

```bash
cd demo/message-center-spring
zip -qr frontend-dist-20260905-whatsapp-shared-template-approval.zip frontend/dist
unzip -t frontend-dist-20260905-whatsapp-shared-template-approval.zip
shasum -a 256 backend/target/message-center.jar frontend-dist-20260905-whatsapp-shared-template-approval.zip
```

`unzip -t` 无错误。SHA-256：

```text
d86c2d06dad860ae2f2f63e17178eff2aa61dfc3f9afecb82e3330c7629c5d3f  backend/target/message-center.jar
4c32aeb3da6fba593b46e22b4b772fadffa04d93f896166330ad5928e51fd9d1  frontend-dist-20260905-whatsapp-shared-template-approval.zip
```

## 浏览器验收

Chrome 在 `http://127.0.0.1:5173/templates` 完成桌面与 `390x844` 移动端检查。页面正常渲染“我的模板”“公共模板库”及空态，移动端文字未被遮挡；控制台没有应用错误。仅出现 React Router v7 future flag warning，不影响当前行为。移动端临时 viewport 已恢复默认值。

Task 9 首次验收时，本机后端未能启动到可登录状态，因此没有执行登录后普通用户申请、管理员批准/拒绝/重试和真实发送流程。当时工作区既有 `V48__backfill_user_channel_owners.sql` 使用 PostgreSQL 不支持的 `min(uuid)`，导致 Flyway 回滚。后续已将两处 UUID 聚合改为唯一候选集合的 `array_agg(...)[1]`，并在临时 PostgreSQL 17.5 数据库中按顺序执行 V1 至 V48；全部迁移成功。登录后审批流仍未重新执行，V49 和真实 CAMS 验收边界保持不变。

## 未闭合环境门禁

后端全量回归运行结果为 1024 tests、0 failures、25 errors、7 skipped。25 个 errors 均因 Testcontainers Java 客户端访问 Docker API 返回 400，容器 PostgreSQL 未启动；Docker CLI 可用但该 API 契约不可用。该环境故障使 V49 真实 PostgreSQL/Flyway 演练尚未完成。

真实 CAMS 测试账号、单一 `custSpaceId` 账号集和可启动的后端环境尚未提供，因此以下发布前实机验收必须在部署或修复后的本地 Docker 环境执行：

- 迁移回填、重复模板归并、官方同步及多 scope 阻断；
- 普通用户新建模板官方申请和既有模板变更仅生成审批申请；
- 管理员批准、拒绝、失败重试、直接执行，以及申请原账号失效时不替换凭证；
- 不同用户共享目录和使用模板，但联系人、消息、附件与渠道凭证仍隔离；
- 旧账号级 `/api/v1/channel-accounts/{accountId}/whatsapp/templates/**` 路由返回不存在。

在上述环境门禁闭合前，本轮状态是“代码、专项自动化、前端构建和制品校验完成；真实数据库、后端登录流与 CAMS 验收待完成”，不应宣称生产发布已完成。
