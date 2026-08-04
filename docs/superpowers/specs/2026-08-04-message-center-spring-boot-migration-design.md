# message-center Spring Boot MVC 重构设计

## 目标

将 `demo/message-center-demo`（纯 JDK 17 + 手写 DI + com.sun.net.httpserver.HttpServer）重构为 Spring Boot 3.4.x MVC 架构，同时保留所有现有功能和交互逻辑。

## 技术栈

| 层级 | 选择 | 说明 |
|------|------|------|
| 语言 | Java 17 | 最低兼容要求 |
| 框架 | Spring Boot 3.4.x + Spring MVC (Tomcat) | 同步编程模型，现有 JDBC 代码零改动复用 |
| 安全 | Spring Security | 替换手写 SessionService/AccessControlService |
| ORM | MyBatis-Plus + XML Mapper | 替换 raw JDBC Jdbc*Repository |
| 数据库 | PostgreSQL | 已有 schema + Flyway V1-V5 |
| 文件存储 | MinIO | 替换文件存储（媒体附件、通话录音） |
| 数据库迁移 | Flyway | 复用现有迁移脚本 |
| JSON | Jackson | Spring Boot 默认，替换 Gson |
| 部署 | Docker Compose 单机私有化 | 已有 compose.yaml |
| 前端 | React + TypeScript + Vite + Ant Design + TanStack Query + React Router + Axios | 保证与现有 UI/UX 完全一致 |

## 项目结构

```
demo/message-center-spring/
├── backend/                         # Spring Boot
│   ├── pom.xml
│   ├── compose.yaml                 # PostgreSQL + MinIO
│   ├── secrets/
│   └── src/main/java/.../messagecenter/
│       ├── App.java                 # @SpringBootApplication
│       ├── config/
│       │   ├── AppConfig.java       # @ConfigurationProperties("app")
│       │   ├── CorsConfig.java
│       │   ├── SecurityConfig.java
│       │   └── MinioConfig.java
│       ├── web/                     # @RestController
│       │   ├── ContactController.java
│       │   ├── ThreadController.java
│       │   ├── MessageController.java
│       │   ├── SendController.java
│       │   ├── SyncController.java
│       │   ├── TemplateController.java
│       │   ├── ChannelController.java
│       │   └── WebhookController.java
│       ├── service/                 # @Service
│       │   ├── contact/
│       │   ├── message/
│       │   ├── chatapp/
│       │   ├── auth/
│       │   └── event/
│       ├── repository/              # MyBatis-Plus Mapper
│       ├── entity/                  # 数据库表映射
│       ├── dto/                     # 请求/响应 DTO
│       └── infrastructure/
│           ├── MinioStorage.java
│           ├── EventHub.java
│           └── FlywayMigration.java
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/            # 复用老项目 Flyway 脚本
└── frontend/                        # React SPA
    ├── vite.config.ts
    └── src/
        ├── api/                     # Axios 封装
        ├── components/
        ├── pages/
        │   ├── ContactsPage.tsx
        │   ├── ThreadPage.tsx
        │   ├── MessagesPage.tsx
        │   ├── SendPage.tsx
        │   └── TemplatesPage.tsx
        ├── hooks/                   # TanStack Query hooks
        ├── router.tsx
        └── App.tsx
```

## 组件迁移映射

### 生命周期管理 — Runtime → Spring Bean

现有 4 个手写 Runtime（ChatAppMessageSyncRuntime、ChatAppTemplateSyncRuntime、WeComDailySummaryRuntime、CallRecordRuntime）迁移为 Spring Bean：

- `start()` → `@PostConstruct` 或 `InitializingBean.afterPropertiesSet()`
- 定时调度 → `@Scheduled(fixedRate = ...)` 替代 `ScheduledExecutorService`
- `close()` → `@PreDestroy` 或 `@Bean(destroyMethod = "close")`
- `@ConditionalOnProperty` 替代 `null` 判断控制条件化激活

### 数据访问 — raw JDBC → MyBatis-Plus

| 当前 | 迁移后 |
|------|--------|
| `JdbcMessageRepository` | `MessageMapper extends BaseMapper<MessageEntity>` |
| `JdbcContactRepository` | `ContactMapper` |
| `JdbcChannelAccountRepository` | `ChannelAccountMapper` |
| `JdbcCompanyRepository` | `CompanyMapper` |
| `Database` (自管理 HikariCP) | 删除 — Spring Boot 自动配置 DataSource |

### HTTP 路由 — if/else 链 → @RestController

200+ 行手写路由 (`if (method, path)`) 拆为独立 Controller，每个端点一个 `@GetMapping`/`@PostMapping`。`writeJson`/`writeRouteError` → `@ExceptionHandler` + `@ControllerAdvice` 全局错误处理。

### SSE — 手写 EventHub → SseEmitter

Spring MVC 原生 `SseEmitter` 替代手写 `CopyOnWriteArrayList<EventClient>` + 25s heartbeat。

### 配置 — Config.java → @ConfigurationProperties

100+ accessor 方法和 `.env` 文件解析改为 `application.yml` + `@ConfigurationProperties("app")` record。

### 文件存储迁移

| 当前 | 迁移目标 |
|------|----------|
| JSONL 文件 (contacts/messages/contact-groups) | PostgreSQL |
| `data/templates.json` | PostgreSQL `templates` 表 |
| `data/media-cache/` | MinIO |
| `data/call-records/audio/` | MinIO |
| `data/call-records/records/` | PostgreSQL `call_records` 表 |
| JSONL 锁文件 | 废弃 — 数据库事务 |
| WeCom JSONL 授权/审计 | PostgreSQL（后续阶段） |

## 分阶段迁移

### 第一阶段（本次）
1. Spring Boot 框架搭建 + MyBatis-Plus 替换 JDBC Repository
2. 联系人、统一消息、线程/会话核心 API
3. Spring Security 基础认证
4. MinIO 接入（媒体附件）
5. Flyway 迁移整合
6. 前端 React SPA 复刻当前 UI

### 后续阶段
- ChatApp sync 子系统
- WeCom viewer/授权子系统
- callrecord 通话录音子系统
- wecom-chatdata-zone-program

## 前端兼容性约束

- 前端功能和交互逻辑必须与当前 `App.java` 中的嵌入式页面完全一致
- 实施前端前，先输出前端分析文档（页面结构、组件树、数据流、SSE 事件）供确认

## 部署

开发环境：`docker compose up` 启动 PostgreSQL + MinIO，Spring Boot 本地运行，Vite dev server 代理 `/api/*`。生产：`vite build` 输出到 `backend/src/main/resources/static/`。
