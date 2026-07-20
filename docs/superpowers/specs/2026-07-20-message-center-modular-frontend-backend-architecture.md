# Message Center 模块化前后端分离架构设计

**日期：** 2026-07-20
**状态：** 已确认，等待书面复核
**适用范围：** `demo/message-center-demo`

## 1. 背景与问题

当前 Java 代码全部位于 `com.crmforlogistics.messagecenter` 单一包中，共 37 个生产文件、约 8477 行。`App.java` 约 1616 行，同时拥有启动、HTTP 路由、错误处理和内嵌 HTML/CSS/JavaScript；`UnifiedMessageStore.java` 约 937 行，同时承担查询 facade 和旧文件真源职责。Controller、application service、domain model、repository、JDBC、外部渠道 adapter 和 UI 没有物理边界。

该结构导致人工审计无法从目录判断 owner、依赖方向和数据流，也会使后续 PostgreSQL、MinIO、权限、outbox 和多人协作继续堆叠在根包。仅创建全局 `controller/`、`service/`、`repository/`、`model/` 目录仍会把不同业务域的大量类集中在一起，不能解决完整调用链难追踪的问题。

## 2. 已确认目标

- 同一 Git 仓库内建立独立 `backend` 和 `frontend` 工程。
- 后端使用 Java 17、Spring Boot 3、Spring MVC 和 Spring Security。
- 后端按业务模块分包，每个模块内部再按 web、application、domain、infrastructure 分层。
- 前端使用 React、Vite、TypeScript、TanStack Query 和 Ant Design 5。
- OpenAPI v1 是前后端唯一 HTTP 合同真源。
- opaque Session Token 原文只进入安全 Cookie，数据库只保存 SHA-256。
- PostgreSQL 和 MinIO 继续作为唯一运行真源；不恢复 JSONL 运行时路径。
- 前后端独立构建、独立容器，由 Compose 统一管理。
- 用户入口保持 8099；自动化和人工代理验收只能使用 8100，结束后关闭。
- 完成切换后删除 JDK `HttpServer`、旧 `/api/*` 路由和 `App.java` 内嵌前端，不保留双路径。

## 3. 非目标

- 不拆成两个 Git 仓库。
- 不引入 Chatwoot、Redis、RocketMQ、Elasticsearch、pgvector 或 JWT。
- 不在本轮引入微服务、服务网格、GraphQL 或多租户。
- 不改变 PostgreSQL/MinIO 数据真源、AES-256-GCM 渠道密钥方案和现有消息中心核心产品行为。
- 不通过兼容 adapter 同时维护旧 HttpServer 与 Spring MVC。

## 4. 仓库结构

```text
demo/message-center-demo/
├── contracts/
│   └── openapi/
│       └── message-center-v1.yaml
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/crmforlogistics/messagecenter/
│       │   │   ├── bootstrap/
│       │   │   ├── shared/
│       │   │   ├── audit/
│       │   │   ├── auth/
│       │   │   ├── contact/
│       │   │   ├── company/
│       │   │   ├── messaging/
│       │   │   ├── channel/
│       │   │   ├── media/
│       │   │   └── importjob/
│       │   └── resources/
│       │       └── db/migration/
│       └── test/
├── frontend/
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── src/
│       ├── app/
│       ├── pages/
│       ├── widgets/
│       ├── features/
│       ├── entities/
│       ├── shared/
│       └── generated/
├── deploy/
│   └── nginx.conf
└── compose.yaml
```

`frontend/src/generated` 和 `backend/target/generated-sources` 是构建生成物，只能由 OpenAPI 生成命令更新，禁止手改。生成物不作为业务真源。

## 5. 后端模块与唯一 Owner

| 模块 | 唯一职责 | 主要 Owner |
|---|---|---|
| `bootstrap` | Spring Boot 启动、Bean 装配、CLI 命令 | 应用组合根 |
| `shared` | 配置、数据库技术设施、JSON、通用错误、trace | 仅技术能力，不允许放业务模型 |
| `audit` | 审计事件、递归脱敏、审计持久化 | 审计真相 |
| `auth` | 用户、角色、登录、Session、Spring Security principal | 身份认证真相 |
| `contact` | 联系人、渠道身份、标签、合并、拆分 | 联系人真相 |
| `company` | 企业、企业联系人关系、电话纪要 | 企业 CRM 真相 |
| `messaging` | 会话、消息、参与人、状态、未读、会话数据权限、发送命令、事件 inbox、outbox 和 worker | 消息与会话真相 |
| `channel` | 渠道账号、模板、同步游标、Email/ChatApp/WeCom adapter | 渠道集成真相 |
| `media` | 附件元数据、MinIO 对象、授权下载 | 二进制附件真相 |
| `importjob` | 一次性旧数据导入、批次、对账 | 导入过程真相 |

会话数据范围属于 `messaging`，不属于 `auth`。`auth` 只提供已认证用户和角色；`messaging.application` 根据 assignee、团队主管和显式 grant 判断会话读写权限。`media.application` 必须调用 `messaging` 提供的授权端口后才能读取 MinIO。

## 6. 模块内分层

每个有 HTTP 入口的业务模块采用一致结构：

```text
messaging/
├── web/             # Spring MVC Controller、OpenAPI 映射、HTTP DTO 转换
├── application/     # Use case、事务、权限、审计和跨端口编排
├── domain/          # 实体、值对象、领域错误、Repository Port
└── infrastructure/  # JDBC、MinIO/渠道实现、event/outbox worker
```

强制依赖方向：

```text
web → application → domain
infrastructure → domain
bootstrap → 各模块的公开装配入口
```

- `domain` 不依赖 Spring、JDBC、HTTP、MinIO、CAMS 或文件系统。
- Controller 只做认证主体读取、参数映射、application use case 调用和响应映射。
- `@Transactional` 边界位于 application service 或明确的基础设施事务 owner，不放在 Controller。
- JDBC repository 只实现 domain port，不拥有权限、用户可见状态或错误文案。
- 外部渠道 adapter 不直接写业务表，只通过事件 inbox、outbox 或 application port 接入。
- 根包禁止放业务类；仅允许 Spring Boot 应用入口或 `package-info.java`。
- ArchUnit 测试阻断跨层反向依赖、模块私有实现泄漏和根包继续堆类。

## 7. 前端结构

React 前端不强套后端 MVC，采用 feature-oriented 分层：

| 目录 | 职责 |
|---|---|
| `app` | Router、QueryClient、主题、认证 Provider、错误边界 |
| `pages` | 登录页、消息中心页等路由级组合 |
| `widgets` | 三栏消息中心、消息输入区、资料栏等大型页面区域 |
| `features` | 登录、退出、发送、同步、联系人编辑、合并拆分、账号选择 |
| `entities` | contact、company、conversation、message、channel-account 的展示模型 |
| `shared` | 生成 client 包装、通用 UI、日期、错误映射、SSE transport |
| `generated` | OpenAPI 生成 TypeScript client，禁止手改 |

联系人、会话、消息、未读、账号和同步状态由 TanStack Query 管理，后端仍是唯一真相。当前选中项、右栏展开、输入框内容和图片预览等纯 UI 状态留在 React 局部状态。禁止把完整服务端数据复制到第二个全局 store。

Ant Design 负责表单、弹窗、标签、上传、反馈和基础可访问性；三栏布局、消息气泡、媒体预览和联系人卡片保持项目自定义组件与现有产品视觉方向。

## 8. OpenAPI 合同

- `contracts/openapi/message-center-v1.yaml` 是 REST、错误响应、认证 Cookie、分页 cursor、媒体响应和 SSE 事件 envelope 的唯一合同。
- API 固定使用 `/api/v1` 前缀。
- Java API interface/model 生成到 `backend/target/generated-sources/openapi`。
- TypeScript client/model 生成到 `frontend/src/generated`。
- 后端 controller 实现生成接口或 delegate，不从实现反向生成合同。
- SSE 使用版本化 envelope：`type`、`version`、`resourceId`、`sequence`、`occurredAt`、`payload`。
- 构建门禁验证 OpenAPI lint、生成成功和生成物无漂移。

## 9. 运行与网络边界

```text
Browser
  → frontend/Nginx
    → /                 React 静态资源
    → /api/v1/*         backend Spring MVC
    → /api/v1/events    backend SSE
    → /api/v1/attachments/{id}/content  backend 授权媒体流
```

- 用户运行时宿主端口为 `${MESSAGE_CENTER_PORT:-8099}`，映射到 frontend/Nginx。
- 自动化验收设置 `MESSAGE_CENTER_PORT=8100`，不得使用 8099。
- backend 只在 Compose 内部网络监听，不默认映射到宿主机。
- Vite 开发服务器代理 `/api/v1` 到本地 backend，保持浏览器同源调用模型。
- PostgreSQL 和 MinIO 只供 backend、migration、backup/restore 服务访问。

## 10. 认证与授权

- 使用 Spring Security FilterChain 集成现有 opaque session。
- 登录成功后 token 原文只写入 `HttpOnly`、`Secure`、`SameSite` Cookie；数据库只存 SHA-256。
- 不使用 JWT，不引入第二套 Spring Session schema。
- 修改类请求启用 CSRF；生产环境同源运行，不开放泛化 CORS。
- 会话权限顺序保持：active user → 当前 assignee → active team supervisor → active grant → deny。
- admin 不拥有隐式敏感原文权限。
- Controller 和前端路由不拥有授权真相；application service 在真正的数据访问和事务边界执行权限检查。

## 11. 数据流、事件与错误

发送事务顺序固定为：

```text
认证 → 会话发送权限 → pending message → status event → outbox → audit → commit
```

外部渠道调用只由有界 worker 消费 outbox 后执行。入站 adapter 先写脱敏事件 inbox，再由 projector 进入业务表。所有 worker 必须有批次、lease、最大重试、退避上限、超时和 dead 状态。

REST 错误统一返回：

```json
{
  "code": "CONVERSATION_ACCESS_DENIED",
  "message": "Unable to access this conversation",
  "traceId": "opaque-trace-id",
  "fieldErrors": []
}
```

错误响应和日志不得包含堆栈、SQL、数据库凭据、MinIO 密钥、渠道密钥或完整签名 URL。前端使用统一错误映射、Ant Design feedback 和路由级 Error Boundary，不从异常文本推导业务状态。

SSE 只通知资源变化。前端根据事件类型和资源 ID 精确更新或失效 TanStack Query 缓存；SSE 不成为第二数据真源。

## 12. 媒体边界

- 前端只访问 `/api/v1/attachments/{id}/content`。
- backend 查询附件元数据和所属消息/会话，执行会话授权并记录敏感读取审计，然后从 MinIO 流式返回。
- 浏览器不直连 MinIO、OSS 或 CAMS。
- OSS/CAMS 下载只允许在入站 attachment worker 或显式迁移任务中发生。
- 文件大小、内容类型、响应超时和并发流数量必须有显式上限。

## 13. 迁移顺序

1. 提交已验证的 Task 6 安全修复，形成行为基线。
2. 新增 OpenAPI v1 合同和前后端构建骨架。
3. 将 Maven 工程、Java 源码、测试和 Flyway migration 移入 `backend`。
4. 按业务模块重新分包，将 package-private 跨文件类型拆为明确的公开合同文件。
5. 引入 Spring Boot 3、Spring MVC、Spring Security 和 ArchUnit，完成 `/api/v1` controller。
6. 建立 React/Vite/TypeScript 前端，按 feature 分层迁移现有三栏 UI 和交互。
7. React 功能对账通过后，删除 JDK `HttpServer`、旧 `/api/*` 路由和 `App.java` 内嵌 HTML/CSS/JavaScript。
8. 拆分 backend/frontend 镜像，Nginx 作为唯一浏览器入口。
9. 完成结构、合同、后端、前端、Compose 和 8100 端到端门禁。
10. Task 7 在新的 `messaging` 模块恢复；当前独立 worktree 保留为未集成工作证据，不直接把旧 package 布局提交到主功能分支。

迁移不保留旧新双运行路径。结构移动、框架切换、前端切换分别提交，避免行为改动与大规模文件移动混成一个不可审查 diff。

## 14. 测试与审计门禁

### 合同

- OpenAPI lint。
- Java API 生成。
- TypeScript client 生成。
- `/api/v1` 路径和错误 envelope 契约测试。

### 后端

- domain/application 单元测试。
- ArchUnit 模块和依赖方向测试。
- Spring MVC 参数、错误和 Spring Security 测试。
- PostgreSQL 17.5 Testcontainers repository/事务/权限/并发测试。
- MinIO Testcontainers 或 Compose 附件集成测试。

### 前端

- ESLint 零 warning。
- TypeScript `strict` 类型检查。
- Vitest 与 React Testing Library 组件/交互测试。
- TanStack Query、SSE 缓存更新和权限错误测试。
- Vite 生产构建。

### 端到端

- 使用 Playwright 在 8100 验证登录、退出、联系人、企业关系、统一时间线、发送 pending/状态、未读、同步、权限拒绝、附件预览和 SSE。
- 验收结束停止 frontend/backend，并确认 8100 无监听。
- 不在 8099 运行代理自动化测试。

### Git 与人工审计

- 每个提交只有一个目的：行为、结构移动、框架切换、前端切换或部署接线。
- 禁止 `git add .`；只精确 stage 当前任务文件。
- warning、生成物漂移、旧路由残留、根包业务类和跨层反向依赖均视为失败。
- 结构性提交必须提供文件映射表，人工 reviewer 可以从旧路径定位到新模块 owner。

## 15. 完成定义

- `backend` 和 `frontend` 可独立安装、测试、构建和生成合同代码。
- 浏览器只访问 frontend/Nginx，同源代理到 backend。
- Java 根包无业务类，所有业务模块通过 ArchUnit 门禁。
- `App.java` 内嵌前端和 JDK `HttpServer` 已删除。
- 旧 `/api/*` 路由不存在，OpenAPI v1 是唯一 HTTP 合同。
- PostgreSQL/MinIO、权限、审计、outbox、导入和媒体边界保持闭合。
- 后端、前端、Compose 和 8100 端到端验收通过。
