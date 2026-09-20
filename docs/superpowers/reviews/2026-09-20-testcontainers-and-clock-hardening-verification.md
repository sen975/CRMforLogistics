# Testcontainers 容错统一与 Clock 注入收口验收记录

本轮收口三项工程卫生问题（P0 时间炸弹 + 裸 `@Testcontainers` + 时钟不可注入），
结论：**后端全量 1428 跑 / 0 失败 / 0 错误 / 0 跳过 / `BUILD SUCCESS`**，生产代码仅一处语义等价改造。

## 一、背景与病根

三项其实是同一条线：**时间不确定 → 测试只能凑合 → 写死绝对值 → 到点必挂**；而 Docker 抖动把偶发失败放大成构建红。

| 项 | 症状 | 病根 |
| --- | --- | --- |
| P0 时间炸弹 | `ContactMemoryConsolidationServiceTest` 自 2026-09-20 12:00 起**永久失败** | fixture 把 `expiresAt` 写成绝对值 `2026-09-20T04:00:00Z`，而判定基准是真实墙钟 `Instant.now()` |
| 裸 `@Testcontainers` | Docker 抖动 → 集成测试报 **error**，构建变红 | 26 个容器测试类里只有 3 个加了 `disabledWithoutDocker = true`，其余 23 个是裸注解 |
| 时钟不可注入 | 该测试只能靠「相对运行时刻」凑合，无法用固定时钟 | `ContactMemoryConsolidationService` 直接调 `Instant.now()`，而同包 `ContactMemoryScheduler` 早有 `Clock` 注入点 |

关于第二项的历史成本：本仓历次验收记录里反复出现「N errors 均来自 Docker/Testcontainers 不可用，
不作为通过依据」这类结论（见 2026-08-26 / 2026-09-02 / 2026-09-03 / 2026-09-04 / 2026-09-09 多份记录），
累积的无效排查成本直接来自这一个注解差异。

## 二、改动

### 2.1 P0：改为注入 `Clock`（根治，而非把常量改远）

生产类 `service/contactmemory/ContactMemoryConsolidationService.java`：

- 新增 `private final Clock clock`；
- 公开无参构造 `@Autowired → this(Clock.systemUTC())`，另留包私有 `ContactMemoryConsolidationService(Clock)`，
  **对齐本仓既有约定**（`ContactMemoryScheduler`、`WeComApiAuditTrail`、`WeComAccessTokenService` 等 20+ 类同款形态）；
- 判定基准 `Instant.now()` → `clock.instant()`。

测试类 `ContactMemoryConsolidationServiceTest.java`：

- `private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)`；
- fixture 的 `expiresAt = NOW.plus(Duration.ofDays(1))`，过期用例 `NOW.minus(Duration.ofDays(1))`；
- 删除死常量 `NOW`（原 `2026-09-11T04:00:00Z`，全文只出现定义处一次）。

**为什么不是「改成相对 `observedAt`」**：判定基准是注入时钟，而生产侧
`ContactMemoryMutationService:103` 写的是 `now.plus(observationTtlDays, DAYS)`，即「写入时刻 + TTL」。
改成相对 `observedAt` 只是把炸弹推到下个月。现在测试结果与真实墙钟**彻底解耦**。

### 2.2 容器测试统一加 `disabledWithoutDocker = true`

23 个裸 `@Testcontainers` 改为 `@Testcontainers(disabledWithoutDocker = true)`，全库 26 个容器测试类达成一致。
语义变化：Docker 不可用从 **error** 变 **skip**，不再把「环境不具备」误报成「代码有问题」。

**代价（必须随文档一起交代）**：CI 上 Docker 挂掉时这批集成测试会**静默跳过**而 `BUILD SUCCESS` 照旧 —— 即假绿风险。
因此**验收不能只看过不过，必须显式核对 `Skipped` 计数**。该约定已写入 `docs/代码板块地图.md` 第六节。

## 三、验收（全部实跑）

| 命令 | 结果 |
| --- | --- |
| `mvn clean test -Dtest='ContactMemoryConsolidationServiceTest,ContactMemoryWorkerTest'` | Tests run: **15**, Failures: 0, Errors: 0 |
| `mvn clean test`（全量） | Tests run: **1428**, Failures: **0**, Errors: **0**, Skipped: **0**, **BUILD SUCCESS**（2m23s） |
| `AppIntegrationTest`（真实 `@SpringBootTest` + Testcontainers） | Tests run: **10**, Failures: 0, Errors: 0 |

后两项是本轮的关键证据：

- `AppIntegrationTest` 10/10 = **双构造器 + `@Autowired` 能被 Spring 正确装配的实证**（不是推理）；
- `Skipped: 0` = Docker 可用时这些集成测试**照常真跑**，加开关不影响正常路径。

## 四、过程中排除的两个假信号

1. **`mvn ... | tail -30; echo $?` 拿到的是 `tail` 的退出码**，打出 `EXIT: 0` 但编译其实已失败。
   真实退出码必须 `mvn ... > /tmp/x.log 2>&1; echo "EXIT=$?"`。
2. **IDE 与 Maven 抢 `target/`**：`mvn test-compile` 刚成功，紧接 `mvn test` 却报
   `Unresolved compilation problem: clock cannot be resolved`（JDT 措辞 → 该 class 由 IDE 写出），
   Maven 增量编译又判定「没变」跳过。改用 `mvn clean test` 后真实编译错误立刻暴露。

（另有一项属本轮操作失误，非项目问题：同一文件的多处 `Edit` 并行发出时只有一个真正落盘，
其余被静默吞掉；已改为同一文件逐处串行或整文件 `Write`。）

## 五、未闭合

- **架构门禁仍是单向规则**：`channel → service` 方向从未被约束。实测 17 个文件存在该依赖，
  其中 `channel/email/EmailSyncService`（729 行）同时编排 4 个业务域。病根是 **email 板块没有 `service.email`**，
  修它需把该文件拆成「渠道适配」+「业务编排」两半、并把 3 个错位 Controller 归回 `web/`。
  **规模远大于本轮，应单独规划。**
- `demo/message-center-demo` 模块另有 **7 个裸 `@Testcontainers`**，属独立工程，未随本轮统一。
- `channel` 包内其余 8 个 `wecom` 实体、`ChatAppMessageSyncService` 混住的业务逻辑、
  6 处重复 HTTP 客户端装配 —— 见 `plans/2026-09-20-baseline-to-zero.md` 第十节。
- 全量绿是**本机 Docker 可用**的前提下取得的；`Skipped: 0` 即为此提供旁证，但本记录不代表
  CI 环境一定同样执行了全部容器测试。
