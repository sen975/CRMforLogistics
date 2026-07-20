# Message Center 完整开发总路线

**当前架构真源：** `docs/superpowers/specs/2026-07-20-message-center-modular-frontend-backend-architecture.md`

**当前数据真源：** `docs/superpowers/specs/2026-07-17-message-center-database-design.md`

**目标：** 在同一仓库内完成 React 前端、Spring Boot 后端、OpenAPI 合同、PostgreSQL、MinIO、多用户权限、事件 inbox、事务 outbox、旧数据导入和 Docker Compose 交付。

## 1. 当前进度

- Worktree：`/private/tmp/CRMforLogistics-message-center-postgres-minio`
- 分支：`codex/message-center-postgres-minio`
- Task 1–5 已完成、提交并独立复审通过。
- Task 6 初版提交：`6368bc6 feat: add local auth and access control`。
- Task 6 review 修复已完成并精确暂存，聚焦测试和 `mvn -q verify` 已通过，尚未创建修复提交。
- 架构设计提交：`8e275a1 docs: define modular message center architecture`。
- Task 7 独立 worktree：`/private/tmp/CRMforLogistics-message-center-task7`；因目标 package 已改变而暂停，未集成到主分支。

## 2. 当前有效计划

按以下顺序执行，不得跨阶段跳过门禁：

1. `docs/superpowers/plans/2026-07-20-message-center-modular-migration.md`
   - 闭合 Task 6 行为基线。
   - 建立 OpenAPI 合同。
   - 拆分 `backend` / `frontend`。
   - 按业务模块重组 Java package。
   - 切换 Spring Boot 3、Spring MVC、Spring Security。
   - 建立 React、Vite、TypeScript、TanStack Query、Ant Design 前端。
   - 删除 JDK HttpServer、旧 `/api/*` 和内嵌前端。
2. `docs/superpowers/plans/2026-07-20-message-center-postgres-minio-completion.md`
   - 在新结构下完成原 Task 7–12。
   - 闭合事件 inbox、outbox、MinIO、渠道 adapter、一次性导入、API/UI、备份恢复和最终验收。

旧计划 `docs/superpowers/plans/2026-07-17-message-center-postgres-minio.md` 只保留 Task 1–6 的历史合同和提交依据；其 Task 7–12 路径与框架不再执行。

## 3. 阶段门禁

### 阶段 A：Task 6 行为基线

- Task 6 review 修复独立提交。
- 聚焦测试、完整 Maven verify、diff check 通过。
- 独立复审无 Critical/Important。

### 阶段 B：模块化前后端迁移

- `backend` 和 `frontend` 可独立安装、测试和构建。
- OpenAPI v1 可生成 Java interface/model 和 TypeScript client。
- ArchUnit 阻断根包业务类和跨层反向依赖。
- Spring Security opaque session、CSRF、统一错误合同通过测试。
- React 三栏消息中心行为对账通过。
- JDK HttpServer、旧 `/api/*` 和内嵌 HTML/CSS/JavaScript 不存在。
- Nginx 是浏览器唯一入口。

### 阶段 C：PostgreSQL/MinIO 完整闭环

- 事件 inbox/outbox 幂等、并发领取、重试与 unknown submission 通过 Testcontainers。
- 附件只存 MinIO，所有读取先授权并审计。
- Email/ChatApp/WeCom adapter 只进入 inbox/outbox，不直接写 UI 文件真源。
- JSONL 只允许一次性导入，运行时扫描无写入路径。
- 登录、联系人、企业、统一时间线、发送、未读、同步、附件和权限拒绝在 8100 通过。

### 阶段 D：交付

- backend/frontend 多阶段镜像通过。
- Compose migration、health、backup、restore verify 通过。
- 8100 验收结束后无监听；8099 未被自动化测试占用。
- Git 只包含授权范围文件，每个提交只有一个目的。

## 4. 并行规则

- 只有文件 owner 完全不重叠、构建目录和 Git index 隔离时才允许并行。
- 并行任务必须使用独立 worktree 和独立分支。
- 不允许两个执行者在同一 Maven/Node 工程中同时写源文件或运行格式化。
- Task 7 的旧 package 实现不得直接 cherry-pick；迁移阶段完成后，只按新计划逐文件移植可复用逻辑和测试。

## 5. 提交规则

- 禁止 `git add .`。
- 精确 stage 当前任务文件。
- 行为改动、文件移动、框架切换、前端切换、部署接线分别提交。
- 文件移动提交优先保持内容不变，后续提交再改行为。
- 每项任务完成后记录 commit 范围、测试命令、结果和剩余风险。

## 6. 全局停止条件

- 需要改变已确认的 PostgreSQL/MinIO 单一真源、权限模型、AES-256-GCM 或 opaque session 方案。
- 需要保留旧 HttpServer 与 Spring MVC 双运行路径。
- OpenAPI 合同与当前产品行为冲突且会删除用户能力。
- 数据导入出现不可解释的数量、身份或附件哈希差异。
- 任何日志、测试、API 或 artifact 出现真实密钥、密码或完整签名 URL。
- 需要在 8099 运行自动化或代理测试。
