# chatapp 出站三件套归位实施计划（越界 1）

> 状态：**已执行（2026-09-20）**。执行记录与实际验证证据见文末第八节。
> 背景真源：`docs/代码板块地图.md` 第四节越界清单 #1；架构诊断见 `docs/learning/00-架构总图.md`。

## 一、目标与判据

**目标**：消除 `service/message` → `channel/chatapp` 的反向依赖，让"消息核心"层不再认识任何具体渠道。

**为什么这三件套属于 chatapp，而不是消息核心**（原代码证据）：

| 证据 | 位置 |
|---|---|
| `jobType` 全代码库唯一取值 `chatapp_send` | `MessageSendApplicationService.java:136`、`MessageOutboxWorker.java:199` |
| 租约 owner 前缀写死 `chatapp-outbox-` | `MessageOutboxWorker.java:66` |
| 异常类型直接是渠道网关内部类 | `MessageOutboxWorker.java:151`、`:161` |
| 校验错误码全为 `CHATAPP_*` | `MessageSendApplicationService.java:161`、`:165`、`:168` |
| 装配开关名自带渠道 | `MessageOutboxScheduler.java:10`（`app.chatapp-outbox-enabled`） |

**同时确认的边界**：email 走 `EmailSendService`、wecom 走 `WeComSendService`，两者均为同步直发，不进 outbox。
因此 outbox 是 chatapp 独占实现，**不存在"多渠道共享出站"语义**（与文档描述不一致，属文档滞后）。

## 二、改动清单

### A. 生产代码：移动 3 个文件（改 `package` 声明）

| 现路径 | 目标路径 |
|---|---|
| `service/message/MessageOutboxWorker.java` | `service/chatapp/outbox/MessageOutboxWorker.java` |
| `service/message/MessageOutboxScheduler.java` | `service/chatapp/outbox/MessageOutboxScheduler.java` |
| `service/message/MessageSendApplicationService.java` | `service/chatapp/outbox/MessageSendApplicationService.java` |

### B. 生产代码：修正 3 处 import

| 文件 | 行 | 改动 |
|---|---|---|
| `web/WhatsAppMessageController.java` | 5 | → `service.chatapp.outbox.MessageSendApplicationService` |
| `service/chatapp/ChatAppMessageApplicationService.java` | 10 | 同上 |
| `service/chatapp/ChatAppMediaApplicationService.java` | 6 | 同上 |

> 嵌套类型引用（`MessageSendApplicationService.MessageAccepted`、`.SendMessageCommand`）随 import 一起生效，无需逐处改。

### C. 测试代码：移动 3 个 + 修正 5 处 import

移动（跟随被测类归位）：

| 现路径 | 目标路径 |
|---|---|
| `test/.../service/message/MessageOutboxWorkerTest.java` | `test/.../service/chatapp/outbox/` |
| `test/.../service/message/MessageSendApplicationServiceTest.java` | 同上 |
| `test/.../service/message/ChatAppWorkerSchedulingTest.java` | 同上（类名本来就是 ChatApp，正好归位） |

修正 import：

| 文件 | 行 |
|---|---|
| `AppIntegrationTest.java` | 15 |
| `channel/chatapp/ChatAppControllerTest.java` | 7 |
| `service/chatapp/ChatAppOwnerProjectionTest.java` | 20 |
| `service/chatapp/ChatAppMediaApplicationServiceTest.java` | 6 |
| `service/chatapp/ChatAppMessageApplicationServiceTest.java` | 10 |

> **⚠️ 清单补漏（2026-09-20 复核新增，原清单遗漏 → 会直接编译失败）**
>
> `MessageSendApplicationServiceTest` 目前靠 **同包可见性**隐性引用 `TemplateMessageTextResolver`：
> 第 65/104/144/169/204/232/270/304/344 行均为 `new TemplateMessageTextResolver(...)`，**全文没有该类型的 import**。
> 而 `TemplateMessageTextResolver` 按清单留在 `service.message`。
> 因此该测试搬到 `service.chatapp.outbox` 后，**必须新增**：
> ```java
> import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
> ```
> 否则编译失败。清单 C 节的「5 处 import 修正」实际是 **6 处**。
>
> 复核结论：三个待搬测试类中**仅此一处**依赖同包可见性。`MessageOutboxWorkerTest` 全部引用均有 import；
> `ChatAppWorkerSchedulingTest` 引用的 `MessageOutboxWorker` / `MessageOutboxScheduler` 与它一起搬走，仍在同包。
> 反方向亦安全：留在 `service.message` 的 4 个测试（`MessageQueryServiceTest`、`ThreadServiceWeComTest`、
> `ThreadServiceTemplateRenderingTest`、`TemplateMessageTextResolverTest`）只引用留在原地的三个类，不引用搬迁类。

### D. 明确不动

- `MessageQueryService`、`ThreadService`、`TemplateMessageTextResolver` **留在** `service/message`（它们是真正的消息核心）
- 配置项 `app.chatapp-outbox-enabled` **不改名**（改名会连带 `application.yml`、测试属性、部署脚本，属额外风险，不在本轮）
- **不新增任何接口/抽象**（只有一个实现时不抽端口）
- 不改行为：纯搬迁 + import 修正

## 三、影响面核查（已完成只读验证）

| 检查项 | 结论 | 依据 |
|---|---|---|
| Spring 组件扫描 | 中性 | 目标包在根包 `com.crmforlogistics.messagecenter` 下，`@SpringBootApplication` 默认覆盖 |
| `InstallationImportApplication` 独立扫描 | 中性 | 其 `basePackages="com.crmforlogistics.messagecenter"`，排除的是 Controller/ControllerAdvice/App；新包仍在 base 内 |
| `@ConditionalOnProperty` 装配 | 不受影响 | 按属性名匹配，不按包路径 |
| MyBatis 扫描 | 不受影响 | `type-aliases-package` 只指向 `entity` |
| chatapp-only profile | 不受影响 | `application-chatapp-only.yml` 只关 wecom 开关 |
| 搬迁后依赖方向 | 全部合法 | `chatapp/outbox` → entity / mapper / infrastructure / conversation / message 均为「渠道 → 核心/基础设施」 |
| 日志配置 | 中性 | `application.yml` 的 `logging.level` 只配 `org.springframework.web.client` / `com.aliyun`，无 service 包级 logger |
| AOP / 切点 | 中性 | 全 `src/main` 无 `@Aspect`、无 `execution(...)` / `within(...)` 包级切点表达式 |
| Bean 名与 `@Qualifier` | 中性 | 无 `@Qualifier`、无 `getBean(String)`；`getBean(...)` 均为按类型（`App.java:26/43/45`）。默认 bean 名由类名派生，不含包名 |
| 测试资源 / fixtures | 中性 | `src/test/resources` 仅有 `application-test.yml`、`docker-java.properties`、`fixtures/chatapp/*`，无包路径绑定 |
| 部署脚本 / 前端 | 中性 | 全仓检索确认 `deploy/`、`frontend/` 均未引用这三个类的包路径或全限定名 |
| 外部契约（API / DB / 外部系统） | 不变 | JSON 按字段名序列化；`jobType=chatapp_send`、租约前缀 `chatapp-outbox-`、错误码 `CHATAPP_*` 均为字符串常量，不随包名变化 |

## 四、验收

**1. 编译**
```bash
cd demo/message-center-spring/backend
mvn -q -DskipTests compile
```

**2. 搬迁专项测试（3 个）**
```bash
mvn -q test -Dtest='MessageOutboxWorkerTest,MessageSendApplicationServiceTest,ChatAppWorkerSchedulingTest'
```

**3. 受影响调用方测试（3 个）**
```bash
mvn -q test -Dtest='ChatAppMessageApplicationServiceTest,ChatAppMediaApplicationServiceTest,ChatAppControllerTest'
```

**4. 越界自检（本轮完成判据）**

`service/message` 下对 `channel.chatapp` / `service.chatapp` 的 import 数必须为 **0**（用 IDE 或 ripgrep 复核，不要用 shell `grep`）。

**5. 集成测试（需 Docker，本机可能不可用）**
```bash
mvn -q test -Dtest=AppIntegrationTest
```
它断言 `app.chatapp-outbox-enabled=false` 时 `MessageOutboxScheduler` bean 不存在 —— 搬迁不改这条语义。

## 五、风险与停止条件

| 风险 | 等级 | 处置 |
|---|---|---|
| `WhatsAppMessageController` 在 web 层直接依赖该服务 | 低 | 仅改 import，签名与行为不变 |
| `MessageSendApplicationService` 还依赖 `service.aitopic.AiTopicActivityRecorder` | 低 | 搬迁后变为 `chatapp → aitopic`，方向合法（非核心→渠道），但属跨业务域，需确认可接受 |
| 测试类包名与真实归属不符 | 低 | 顺势归位 |
| 搬迁后同包可见性丢失 | **中** | `MessageSendApplicationService`（生产）与 `MessageSendApplicationServiceTest`（测试）都隐性引用 `TemplateMessageTextResolver`，各自需补 import（见 C 节补漏）；漏了直接编译失败 |
| 架构门禁基线未同步收窄 | 低 | 基线从 51 条缩到 40 条（`MessageOutboxWorker` 的 11 条消失），diff 必须与代码同批提交 |

**停止条件**：若发现 `service/message` 下存在本清单之外的新增 chatapp 引用，先补清单再动手。

## 六、完成定义

- 3 个生产类 + 3 个测试类归位，3 处生产 import + **6 处测试 import** 修正
- `service/message` 对 chatapp 依赖归零
- 第 2、3 组测试通过
- 无行为变更（纯搬迁）
- 单独一次提交，不混入 ③④ 的改动

## 七、本轮影响面（除三个类之外还会动到什么）

### 1. 架构门禁基线会收窄 —— 必须同批提交

`MessageOutboxWorker` 在基线里占 **11 条**（字段类型 + 2 个构造参数 + 方法调用 + 参数/返回类型，全部指向
`channel.chatapp.ChatAppOutboundGateway` / `ChatAppAccountCredentialsException`）。
搬迁后这 11 条不再产生，`FreezingArchRule` 会把它们从快照移除，基线 **51 条 → 40 条**（实测确认）。

- 这是 ② 完成的**硬证据**：迁移前后跑同一条命令，基线内容应精确少掉这 11 条、且**一条都不新增**。
- `archunit_store/50d47b56-…` 会产生 diff，**必须与代码改动同一批提交**，且单独 review。
- 不会产生新越界：`service.chatapp..` 在门禁白名单内，且该白名单条目是门禁建立时就按「同属 chatapp 板块」
  这一**域归属**理由写入的，**不是为本次搬迁临时放宽**。搬迁消除越界靠的是「归类正确」，不是「加白名单」。

### 2. 文档同步（6 份，属 ② 的完成定义）

| 文档 | 需改内容 |
|---|---|
| `docs/代码板块地图.md` | L61 发送链路、L66 Review 关注点（越界提示应删除）、L266 调度器归属表、L277 压测提示、L289 越界清单 #1（改为已修复）、以及第 4 节开头的越界计数 |
| `docs/代码阅读顺序.md` | L56 `service/message/` 文件清单、L74 调度器清单 |
| `docs/learning/00-架构总图.md` | L35/L36 发送链路、L47 调度器归属、L58 越界清单 #1 |
| `docs/learning/transferable/01-踩坑与经验.md` | **L21 把该越界当「架构债实例」，修完即失效，必须改写或换例** |
| `docs/learning/transferable/00-模式库.md` | L18 模式实例里的类归属表述 |
| `docs/superpowers/README.md` | 本计划索引状态：待确认 → 已执行 |

**不改**：`docs/superpowers/plans/2026-09-01-…`、`2026-09-04-…`、`2026-09-10-…` 里的历史路径 ——
那是历史实施记录，按 `AGENTS.md` 文档分层规则不得回写。

### 3. git 与提交边界

- 用 `git mv` 搬迁，保留文件历史（`git log --follow` 可用）。
- 已核实：三个生产类 + 三个测试类**当前均无未提交改动**，因此本次提交不会夹带 WIP。
- 工作区内另有大量无关 WIP 文件，提交时按路径显式 stage，**禁止 `git add .`**。
- 提交信息需体现这是**纯搬迁**，便于日后用 `--follow` 追溯。

### 4. 不会被影响的（已逐项验证，见第三节）

构建与运行时零行为变更：组件扫描、条件装配、MyBatis、日志、AOP、Bean 名、测试资源、部署脚本、
前端、HTTP/JSON 契约、DB 内容、外部系统调用 —— 全部中性。

---

## 八、执行记录（2026-09-20）

### 8.1 实际改动

| 动作 | 内容 |
|---|---|
| 搬迁 | 6 个文件（3 生产 + 3 测试）`service/message` → `service/chatapp/outbox` |
| 生产 import 修正 | **4 处**：3 个调用方 + `MessageSendApplicationService` 补 `TemplateMessageTextResolver` |
| 测试 import 修正 | **6 处**：5 个调用方 + `MessageSendApplicationServiceTest` 补 `TemplateMessageTextResolver` |
| 合计 import | **10 处**（比原清单多 2 处，见下） |

### 8.2 执行中修正的两处清单缺陷

原清单只写了 8 处 import 修正，实际是 **10 处**。两处漏项都会导致**编译失败**：

1. **生产** `MessageSendApplicationService` 靠同包可见性隐性引用 `TemplateMessageTextResolver`
   （第 38/48/59/72 行，无 import）—— 原清单未记录。
2. **测试** `MessageSendApplicationServiceTest` 同理（第 65/104/…/344 行，无 import）—— 计划文档 C 节补漏时已发现。

教训：搬包前必须逐类核对**同包可见性引用**，import 列表不等于依赖列表。

### 8.3 验证证据（全部实跑）

| 验收项 | 命令 | 结果 |
|---|---|---|
| 编译 | `mvn -o clean test-compile` | BUILD SUCCESS（501 主 + 341 测试） |
| 搬迁专项 + 调用方测试 | `mvn -o test -Dtest='MessageOutboxWorkerTest,MessageSendApplicationServiceTest,ChatAppWorkerSchedulingTest,ChatAppMessageApplicationServiceTest,ChatAppMediaApplicationServiceTest,ChatAppControllerTest'` | `Tests run: 44, Failures: 0, Errors: 0` |
| 越界归零 | ripgrep 全量检索 `service.message.*(三个类)` | 0 命中 |
| 门禁基线收窄 | `mvn -o test -Dtest=ArchitectureBoundaryTest` | 51 → **40** 条；与迁移前快照逐行 diff = **删除 11 行、新增 0 行** |
| 门禁仍然有效 | 临时探针 `service/todo/ArchUnitGateProbe`（字段依赖 `ChatAppOutboundGateway`） | `Failures: 1` + BUILD FAILURE + EXIT=1；删除探针后恢复绿 |

**未采用 `-Dfreeze.refreeze=true`**：它会无差别重冻当前全部违规，一旦夹带新越界会被静默吸收。
本次直接跑门禁，靠「构建通过 + 只减不增」证明没有新越界。收窄由 `FreezingArchRule` 自动完成。

### 8.4 未做与遗留

- **未提交**：父仓库 `~/workItem/CRMforLogistics/.git/worktrees/…/index.lock` 是 2026-09-17 的残留
  （0 字节、无 git 进程），挡住所有 git 写操作。改用普通 `mv` 搬迁 —— `git mv` 与 `mv`
  产生的提交逐字节相同（rename 是 diff 时按内容相似度推断的，不存进提交），因此无历史损失。
  **提交前需先清该锁**。
- `MessageSendApplicationService` 仍依赖 `service.aitopic.AiTopicActivityRecorder`，方向合法但跨业务域，保持现状。
- `AppIntegrationTest` 需 Docker；同语义（`app.chatapp-outbox-enabled=false` 时不装配 scheduler）
  已由 Docker-free 的 `ChatAppWorkerSchedulingTest` 覆盖。
