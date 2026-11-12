# 冻结基线收口到 0 —— 影响面（越界改造第 ⑤ 步）

> 状态：**已实施完成（2026-09-20）**，基线 51 → 0，冻结机制已拆。实施记录见第九节。
> 背景真源：`docs/代码板块地图.md` 第四节；已完成步骤 ② `2026-09-20-chatapp-outbox-relocation.md`、
> ③ `2026-09-20-channel-type-registry.md`、④ `2026-09-20-cams-vendor-layer.md`。
> 前置：④ 结束后基线 **32 条 / 3 个文件**。本轮要清的就是这 3 个文件。

## 一、结论

1. **剩下 32 条只有两个病根，一次能清到 0**，且两处都是**文件搬家、不引入任何抽象**：

   | 病根 | 条数 | 措施 |
   |---|---|---|
   | A. 两个 wecom 实体住在 `channel/wecom/`，被业务域直接引用 | **28** | 搬到 `entity/` |
   | B. `WhatsAppHistorySyncWorker` 跨板块驱动 `channel.chatapp` 适配器 | **4** | 搬到 `service/chatapp/` |

2. **病根 A 不是「要不要把实体搬出渠道包」的设计题，而是项目自己早就定过的题。**
   `entity/` 包里**已经有 7 个 WeCom 实体**（`WeComPartyEntity`、`WeComSourceConversationEntity`、
   `WeComSourceParticipantEntity`、`WeComExternalGroupSyncEntity`、`WeComGroupNameRefreshJobEntity`、
   `WeComChatDataIngestFailureEntity`、`WeComUserNotificationEntity`），`channel/wecom/` 里还留着 10 个。
   而且 `application.yml:14-15` 的 `mybatis-plus.type-aliases-package` **只声明了 `…entity`**
   —— 项目对「实体住哪」本来就有单一真源，放 `channel/wecom` 的这 10 个压根不在别名扫描范围内。
   搬这 2 个 = 向既有约定对齐，不是发明新分层。

3. **病根 B 有现成判例。** `service/chatapp` 里已经有 3 个同型 worker
   （`ChatAppWebhookRetryWorker`、`ChatAppBroadcastWorker`、`MessageOutboxWorker`），
   ② 已经就「chatapp 专属的 job worker 该住 `service.chatapp`」立过判例。这个 worker 消费的是
   CAMS 侧同步适配器，属同一批机器。搬到 `service.chatapp` 后它在白名单内，规则天然放行 ——
   注意这**不是**「搬进白名单躲规则」，`service.chatapp` 之所以在名单里，正是因为它与
   `channel.chatapp` 同属一个板块（见门禁注释逐条理由）。

4. **清到 0 之后必须做收口动作，否则会留一个语义模糊状态。**
   `FreezingArchRule` 在基线为 0 时等于一条普通规则，但 `archunit_store/` 空文件 +
   `allowStoreCreation=false` 的组合行为需要实测（**已实测，结论见文末风险清单第 3 条**）。真正的收口是**最后把
   `FreezingArchRule.freeze(...)` 换回普通 `noClasses()...` 规则、删掉 `archunit_store/`
   与 `allowStoreCreation` 属性** —— 冻结机制是「过渡脚手架」，债还完就该拆（见决策点 4）。

5. **两条明确不做**（写出来是为了防止误以为这轮把架构清干净了）：
   - 其余 **8 个 wecom 实体**留在 `channel/wecom`：它们只被 `channel.wecom` / `service.wecom` 使用，
     `service.wecom` 在白名单内、`channel` 包不受此规则约束，**当前 0 违规**。要动它们等于动
     ~50 个文件（`service/wecom` 几乎全包 + 8 个 mapper + 一批测试），属于另一条线。
   - `channel` 包内混着的业务逻辑（门禁注释里点名的「另一条改造线」）。

## 二、基线现状（32 条明细，逐条读出）

`src/test/resources/archunit_store/50d47b56-8e3a-4c2d-bf89-f6d251329982`：

### 2.1 `service/aitopic/AiTopicInputService.java` — 21 条

全部是调用 `channel.wecom.WeComMessageSummaryJobEntity` 的 3 个 getter（`getId` / `getSendTime` / `getSummary`），
分布在 3 个方法：

| 方法 | 行 | 条数 |
|---|---|---|
| `collectContactCandidates(UUID, UUID, Optional, List)` | 109-114 | 7 |
| `collect(OwnerRef, UUID, Optional)` | 131-136 | 7 |
| `addArchivedMergedContactSources(UUID, List, Optional)` | 165-170 | 7 |

该文件的 channel 依赖只有 **1 条 import**（`AiTopicInputService.java:17`）。

### 2.2 `service/account/AccountService.java` — 7 条

全部在同一个方法 `storedWeComAvatar(WeComUserBindingEntity)` 里：方法参数类型 1 条 +
调用 `getAuthCorpId()` / `getSuiteId()` / `getWecomUserId()` 各 2 次（308、312 行）。

该文件的 channel 依赖同样只有 **1 条 import**（`AccountService.java:5`）。

### 2.3 `service/whatsapp/WhatsAppHistorySyncWorker.java` — 4 条

构造函数 2 条（含包私有那个）+ 字段 1 条 + `process()` 第 48 行调用 1 条，
引用类型都是 `channel.chatapp.ChatAppMessageSyncService`。

> 2.1 + 2.2 = 28，+ 2.3 的 4 = **32**。

## 三、改动一：两个 wecom 实体 → `entity/`（消 28 条）

### 3.1 搬什么

| 现位置 | 行数 | 落点 |
|---|---|---|
| `channel/wecom/WeComUserBindingEntity.java` | 57 | `entity/WeComUserBindingEntity.java` |
| `channel/wecom/WeComMessageSummaryJobEntity.java` | 83 | `entity/WeComMessageSummaryJobEntity.java` |

两个类都是纯 MyBatis-Plus POJO（`@TableName` + `@TableId` + getter/setter），**无任何类型级或注解级依赖**
（不 import `channel.*`，不 import 任何项目内类型）。所以搬包只改 `package` 那一行，**逻辑零改动**。

### 3.2 引用面（实测，共 15 个文件，不含快照）

**`WeComUserBindingEntity` — 8 个**

| 类别 | 文件 |
|---|---|
| 待搬本体 | `channel/wecom/WeComUserBindingEntity.java` |
| 生产（改 import） | `mapper/WeComUserBindingMapper.java:4`（`BaseMapper<…>` + 7 个返回该类型的方法） |
| 生产（改 import） | `service/wecom/WeComUserBindingService.java:10 处引用` |
| 生产（改 import） | `service/wecom/WeComUserNotificationService.java:3 处` |
| 生产（改 import） | `service/account/AccountService.java:2 处` ← **越界点** |
| 测试（改 import） | `test/service/account/AccountServiceProfileTest.java` |
| 测试（改 import） | `test/service/wecom/WeComUserBindingServiceTest.java` |
| 测试（改 import） | `test/service/wecom/WeComUserNotificationServiceTest.java` |

**`WeComMessageSummaryJobEntity` — 7 个**

| 类别 | 文件 |
|---|---|
| 待搬本体 | `channel/wecom/WeComMessageSummaryJobEntity.java` |
| 生产（改 import） | `mapper/WeComMessageSummaryJobMapper.java:4`（`BaseMapper<…>` + 13 个返回该类型的方法） |
| 生产（改 import） | `service/wecom/MyBatisWeComMessageSummaryRepository.java:6 处` |
| 生产（改 import） | `service/aitopic/AiTopicInputService.java:4 处` ← **越界点** |
| 测试（改 import） | `test/service/wecom/WeComMessageSummaryRepositoryTest.java` |
| 测试（改 import） | `test/service/aitopic/AiTopicInputServiceTest.java` |
| 测试（改 import） | `test/service/aitopic/AiTopicInputServiceMergeReconciliationTest.java` |

**汇总**：15 个文件 = 2 个本体（改 `package`）+ **13 个改 import**（生产 7 / 测试 6）。
两个实体的引用集合**互不相交**，没有文件同时引两者。

**同包兄弟类需要补 import 的有几个？—— 0 个。**
实测 `channel/wecom/` 下**除了实体自身，没有任何类引用这两个实体**，所以
② 和 ④ 都踩过的「同包引用不计入 import 清单」陷阱**这次不会出现**。
（仍要跑 `test-compile` 兜底，不靠推理。）

### 3.3 MyBatis 侧为什么是安全的（逐项核过）

| 关注点 | 实测结论 |
|---|---|
| Mapper XML / `resultType` 写死 FQN？ | `src/main/resources` 里 `channel.wecom.` 与两个实体名**零命中**；项目是注解 SQL（`@Select`/`@Update`），无 XML |
| 反射 / 字符串形式 FQN？ | 全仓库 grep `"com.crmforlogistics.messagecenter.channel` **零命中** |
| `type-aliases-package` 副作用？ | 别名只扫 `…entity`。搬进去后这两个实体**首次**进入别名范围；`entity/` 下无同名简单类，**无别名冲突**。别名是加法，不影响既有映射 |
| 表名 / Flyway？ | `@TableName` 值不变（`wecom_user_bindings` / `wecom_message_summary_jobs`），DB 无关 |
| Spring 装配？ | 两个实体不是 bean，不参与扫描 |
| `map-underscore-to-camel-case: true` | 列→属性映射按运行时反射，与包名无关 |

### 3.4 备选方案（不推荐，仅记录为什么否掉）

**不搬实体，改成在业务域侧用窄记录（record）承接**，例如 `AccountService.storedWeComAvatar(WeComAvatarSource)`、
`AiTopicInputService` 用 `SummaryItem` 投影 —— 让业务域彻底看不见 DB 实体。
- 优点：语义上更干净，业务域不该见实体。
- 否掉理由：① 项目现状是业务域**直接消费**实体（`entity/` 里 7 个 WeCom 实体已被 `service/wecom` 等直接使用），
  单给这 2 个加一层记录会造成同类不一致；② 要同时改调用方（在 `service/wecom`，白名单内）与 28 条调用点，
  改动面比搬包大一档；③ 本轮目标是「清掉门禁债」，不是重划实体可见性边界。**记为后续可选优化。**

## 四、改动二：`WhatsAppHistorySyncWorker` 归位（消 4 条）

### 4.1 事实

这 59 行的类：`@Component` + `@Scheduled(fixedDelay = 5000)`，消费 `whatsapp_history_sync_jobs` 表
（租约 + 重试状态机，`MAX_ATTEMPTS = 3`），执行体是
`sync.runOwnedAccount(ownerUserId, channelAccountId, now-30d, now, 50, false)`。

- 生产侧**无任何调用方**（只有 `@Scheduled` 驱动）。
- 表由 `service/whatsapp/WhatsAppAccountLifecycleService.requestHistorySync()` 入队。
- 全仓库引用它的只有它自己的测试与基线快照。

### 4.2 三个方案

| 方案 | 效果 | 代价 | 判定 |
|---|---|---|---|
| **a) 搬到 `service/chatapp/`** | 4 条消除；在白名单内但**同板块归属成立** | 与入队方 `service.whatsapp` 分居两包 | ✅ **推荐**，与 ② 判例同型 |
| b) 搬到 `channel/chatapp/` | 4 条消除（规则不管 `channel` 内部） | 把「定时调度 + 租约/重试状态机」塞进渠道包，加重门禁注释自己点名的「channel 混业务逻辑」问题 | ❌ 加重已承认的病 |
| c) 原地不动，在 `service.whatsapp` 定义窄端口、由 `channel.chatapp` 实现 | 4 条消除（DIP） | 新增 1 接口 + 1 实现 + 装配；且**不能复用** ③ 的 `ChannelType.syncAccount(ownerId, accountId)` —— 那个方法走的是 `runAccount(accountId)`（无时间窗、无 `dryRun`），与 `runOwnedAccount(…, now-30d, now, 50, false)` **不是同一个操作**，硬套会改行为 | ❌ 收益/成本不划算 |
| d) 把 `service.whatsapp..` 加进白名单 | 4 条消除 | 一次性放开 33 个文件（约 6100 行）对 `channel.chatapp` 的**全部未来依赖**；门禁注释明确写「不要为了让测试过而把包加进白名单」 | ❌ |

### 4.3 为什么 a 不是「搬进白名单躲规则」

两条证据：
1. 门禁白名单的准入标准是「同属一个板块」或「职责本身就是协调渠道」，`service.chatapp` 属前者；
2. 如果这个 worker 今天从零写，它消费一张 job 表、驱动 CAMS 同步适配器 —— 按项目既有布局
   （`service/chatapp` 下 3 个同型 worker）它本来就会落在 `service/chatapp`。
   它现在在 `service.whatsapp` 的唯一原因是 ④ 已确认的**历史命名拆分**（whatsapp 与 chatapp
   底层同为阿里云 CAMS，`whatsapp` 只是别名）。

搬完后 `service/whatsapp` 对 `channel` 的依赖 = **0 条**，这是 ④ 想做而做不到的收尾。

> 类名保留 `WhatsAppHistorySyncWorker`（改名的收益只是好看，却会和 `whatsapp_history_sync_jobs`
> 表名、`WhatsAppHistorySyncJobEntity`、`WhatsAppHistorySyncJobMapper` 的命名一起漂）。

## 五、逐文件影响清单（执行清单）

### 5.1 生产代码

| 动作 | 文件 | 说明 |
|---|---|---|
| 移动 + 改 `package` | `channel/wecom/WeComUserBindingEntity.java` → `entity/` | 2 个文件 |
| 移动 + 改 `package` | `channel/wecom/WeComMessageSummaryJobEntity.java` → `entity/` | 同上 |
| 改 import | `mapper/WeComUserBindingMapper.java:4` | |
| 改 import | `mapper/WeComMessageSummaryJobMapper.java:4` | |
| 改 import | `service/wecom/WeComUserBindingService.java` | 白名单内，仅改路径 |
| 改 import | `service/wecom/WeComUserNotificationService.java` | 同上 |
| 改 import | `service/wecom/MyBatisWeComMessageSummaryRepository.java` | 同上 |
| 改 import | `service/account/AccountService.java:5` | **越界消除** |
| 改 import | `service/aitopic/AiTopicInputService.java:17` | **越界消除** |
| 移动 + 改 `package` | `service/whatsapp/WhatsAppHistorySyncWorker.java` → `service/chatapp/` | **越界消除** |

**生产侧合计 10 个文件**（4 移动 + 6 改 import；其中 2 个文件既是移动又只需改 package 行）。
零 import 修补的连带文件 —— 因为没有任何其他类引用这两个实体和这个 worker。

### 5.2 测试代码

| 动作 | 文件 |
|---|---|
| 移动 + 改 `package` | `test/service/whatsapp/WhatsAppHistorySyncWorkerTest.java` → `test/service/chatapp/` |
| 改 import | `test/service/account/AccountServiceProfileTest.java` |
| 改 import | `test/service/wecom/WeComUserBindingServiceTest.java` |
| 改 import | `test/service/wecom/WeComUserNotificationServiceTest.java` |
| 改 import | `test/service/wecom/WeComMessageSummaryRepositoryTest.java` |
| 改 import | `test/service/aitopic/AiTopicInputServiceTest.java` |
| 改 import | `test/service/aitopic/AiTopicInputServiceMergeReconciliationTest.java` |

**测试侧合计 7 个文件。** `WhatsAppHistorySyncWorkerTest` 用
`ApplicationContextRunner.withUserConfiguration(WhatsAppHistorySyncWorker.class)`，不依赖包位置，
但**包私有构造器 `(jobs, sync, workerId)` 被测试直接调用** → 测试必须跟着搬，否则编译失败。

### 5.3 配置 / 构建 / SQL（全不动）

| 项 | 结论 |
|---|---|
| `pom.xml` | 不动 |
| `archunit.properties` | 本轮不动（收口动作在决策点 4） |
| `application.yml` | **不动**（`type-aliases-package` 已是 `…entity`，搬进去正好落进扫描范围） |
| `App.java` / `InstallationImportApplication.java` | 不动（扫描根都是 `com.crmforlogistics.messagecenter`） |
| Flyway / DDL | 不动（表名不变） |
| `archunit_store/*` | **会被自动改写**（预期 32 → 0），必须单独 diff |

### 5.4 文档（实施时回写）

`docs/代码板块地图.md`（4.1 基线表重建 + 新增修复记录 + 文件数按实测更正）、
`docs/learning/00-架构总图.md`（第 68 行那条债务标记为已清）、
`docs/代码阅读顺序.md`、`docs/superpowers/README.md`。
顺带发现 **`代码板块地图.md:227` 记的 `service/whatsapp/`（15）是旧值**，
实测为 **33**（直接 12 + `template/` 21），执行时一并更正。

## 六、决策点（需要拍板）

**1. 两个实体是否同批迁**
- ✅ **推荐同批** —— 同一病根（实体住错包），分两批只会让基线经历 21 条 / 7 条两个中间态，无收益。
- 备选：只迁 `WeComMessageSummaryJobEntity`（21 条，改动面小），`WeComUserBindingEntity` 留下 —— 不推荐。

**2. worker 落哪**
- ✅ **推荐 `service/chatapp/`**（见 4.2/4.3）
- 备选：`channel/chatapp/`（不推荐，见 4.2 b）、原地 + 端口（不推荐，见 4.2 c）

**3. 其余 8 个 wecom 实体**
- ✅ **推荐本轮不动，记为债务**（理由见第一节第 5 条；它们当前 0 违规）。
- 备选：同批全迁 —— 约 50 文件，属另一条线，不建议混进「基线清零」这一批。

**4. 基线归零后是否把冻结规则换成硬规则**（这一步不做，就等于留了个语义模糊的终点）
- ✅ **推荐做**：把 `FreezingArchRule.freeze(noClasses()...)` 换回普通 `noClasses()...`，
  删 `archunit_store/`，并在 `archunit.properties` 里去掉 `allowStoreCreation`（已无 store 可建）。
  同时把类注释里「为什么用 FreezingArchRule」一节改写成「债已还完，冻结机制已拆」的历史说明。
- 备选：保留 `FreezingArchRule` + **空的** `archunit_store/` —— 行为等价但多一层无意义机制，
  且空快照文件的存在会让人误以为还剩债。

## 七、风险

1. **包私有可见性陷阱（② ④ 都踩过）**：本轮实测**不存在**同包兄弟类引用（`channel/wecom` 无其他引用者），
   且 `WhatsAppHistorySyncWorker` 的 3 参构造器是包私有、被测试直接调用 —— 测试必须同批搬。
   **验收仍以 `mvn -DskipTests test-compile` 为准，不靠数 import。**

2. **基线自动收缩是沉默写盘（源码已证）**：`ALLOW_STORE_UPDATE_DEFAULT = "true"`，
   跑门禁时 `FreezingArchRule.removeObsoleteViolationsFromStore()` 会直接回写快照文件。
   → 提交前必须 `git diff src/test/resources/archunit_store/`，确认**只有删除、零新增**；
   **绝不 `git add .`**。

3. ~~**基线归零 + `allowStoreCreation=false` 的边界行为**：需实测。~~
   **已实测并闭合（2026-09-20 补充，探针直接调 `store.save(rule, emptyList())`）**：
   快照文件被**截断为 0 字节、但不删除**，`stored.rules` 索引条目**保留**（`contains()=true`），
   因此**不会报错** —— 原因是 `getStoredRulesFile()` 检查的是索引文件、不是快照文件。
   并且空快照**不会**放行新违规（走 `removeObsoleteViolationsFromStoreAndReturnNewViolations`，
   新违规仍被 `filterOutKnownViolations` 全部报出）。结论：留空快照「不坏但无用」，
   按决策点 4 拆掉仍是正确收口。
   附：`allowStoreCreation` **默认是 `false`**（源码常量 + 官方文档 + 探针三重确认）；
   本计划前文若出现「默认 `true`」的表述，以本条为准。

4. **`AppIntegrationTest` 是全项目唯一真实 `@SpringBootTest`**，是唯一能暴露装配问题的用例；
   ② 时它在全量回归里撞过 Docker/端口竞争（`PSQLException EOFException`），单独跑 10/10 通过。
   本轮仍按「单独跑 + 全量跑」两次取证。

5. **`service/wecom` 是本次改动最集中处（4 个文件）**，但它在门禁白名单内、且只改 import 路径 ——
   风险等级低；`WeComUserBindingServiceTest` / `WeComMessageSummaryRepositoryTest` 覆盖到位。

## 八、验收

```bash
cd demo/message-center-spring/backend
# 1) 编译（包私有陷阱的唯一有效拦截）
mvn -o -DskipTests test-compile
# 2) 门禁 —— 预期快照 32 → 0
cp src/test/resources/archunit_store/50d47b56-8e3a-4c2d-bf89-f6d251329982 /tmp/baseline_before_⑤
mvn -o test -Dtest=ArchitectureBoundaryTest
diff /tmp/baseline_before_⑤ src/test/resources/archunit_store/50d47b56-8e3a-4c2d-bf89-f6d251329982  # 只删不加
# 3) 真实装配
mvn -o test -Dtest=AppIntegrationTest
# 4) 定向（含越界点与 wecom 面）
mvn -o test -Dtest='*WeCom*,*AiTopic*,*AccountService*,*WhatsApp*,*ChatApp*'
# 5) 全量
mvn -o test     # 上一轮基准 1428 passed
```

**验收硬指标**：① `test-compile` BUILD SUCCESS；② 快照 diff 删除 32 行、新增 0 行；
③ `AppIntegrationTest` 10/10；④ 全量 0 failure。
**归零后追加一步**（决策点 4）：拆掉 `FreezingArchRule` 再跑一次门禁，
并用一次**故意注入的越界探针**确认**普通规则**同样能让构建变红（门禁没被拆空）。

## 九、实施记录（2026-09-20 已完成）

**决策**：1 同批迁 ✅ / 2 worker 落 `service/chatapp/` ✅（按推荐）/ 3 其余 8 个实体不动 ✅ / 4 拆掉冻结机制 ✅。

### 实际改动

| 动作 | 文件 |
|---|---|
| 移动 + 改 `package` | `channel/wecom/WeComUserBindingEntity.java` → `entity/` |
| 移动 + 改 `package` | `channel/wecom/WeComMessageSummaryJobEntity.java` → `entity/` |
| 移动 + 改 `package` | `service/whatsapp/WhatsAppHistorySyncWorker.java` → `service/chatapp/` |
| 移动 + 改 `package` | `test/service/whatsapp/WhatsAppHistorySyncWorkerTest.java` → `test/service/chatapp/` |
| 改 import（生产 7） | `mapper/WeComUserBindingMapper`、`mapper/WeComMessageSummaryJobMapper`、`service/wecom/WeComUserBindingService`、`service/wecom/WeComUserNotificationService`、`service/wecom/MyBatisWeComMessageSummaryRepository`、`service/account/AccountService`、`service/aitopic/AiTopicInputService` |
| 改 import（测试 6） | `AccountServiceProfileTest`、`WeComUserBindingServiceTest`、`WeComUserNotificationServiceTest`、`WeComMessageSummaryRepositoryTest`、`AiTopicInputServiceTest`、`AiTopicInputServiceMergeReconciliationTest` |
| 改写为硬规则 | `architecture/ArchitectureBoundaryTest.java`（去 `FreezingArchRule`、重写类注释、`.because` 文案） |
| 删除 | `src/test/resources/archunit.properties`、`src/test/resources/archunit_store/`（2 个文件 + 目录） |

**预测 vs 实际：全中。** 13 个文件改 import（预测 13）、同包兄弟类需补 import 0 个（预测 0）、`test-compile` 一次通过无额外修补。
`pom.xml` / `application.yml` / `App.java` / `InstallationImportApplication` / Flyway 全未动。

### 验收（全部实跑）

| 项 | 结果 |
|---|---|
| `mvn -o -DskipTests test-compile` | BUILD SUCCESS（一次过） |
| `-Dtest=ArchitectureBoundaryTest`（冻结态） | Tests run: 1 / Failures: 0；快照 **32 → 0**，删 32 / 增 0 |
| **越界探针复验** | 临时注入 `service/account/GateProbeTemporary` 依赖 `channel.chatapp.ChatAppMessageSyncService` → `Failures: 1` + **BUILD FAILURE**，越界点精确报出；探针已删；`archunit_store/` **未被重建** |
| `-Dtest=ArchitectureBoundaryTest`（硬规则态） | Tests run: 1 / Failures: 0 |
| `-Dtest=AppIntegrationTest` | 10/10（真实 Spring 装配，`service.chatapp` 注入 OK） |
| 定向 `*WeCom*,*AiTopic*,*AccountService*,*WhatsApp*,*ChatApp*,*Architecture*` | 989 跑，1 error = `ChatAppBroadcastPersistenceIntegrationTest` 的 Testcontainers Postgres 连接失败（**环境性**，单独复跑 11/11 通过） |
| 全量 `mvn -o test` | 1428 跑，**1427 通过**，1 失败见下 |

### 全量里那 1 个失败：与 ⑤ 无关，是已提交代码里的时间炸弹

`service/contactmemory/ContactMemoryConsolidationServiceTest.oneEvidenceInFirstRunAndIndependentEvidenceInSecondRunPromoteFact`
断言 `facts()` 有 1 个元素、实际 0 个。根因在该测试文件 **第 227 行**（fixture helper）：

```java
observation.setExpiresAt(Instant.parse("2026-09-20T04:00:00Z"));   // = 北京时间 2026-09-20 12:00:00
```

证据链：
1. ④ 那次全量回归（11:32 CST = 03:32 UTC，日志 `/tmp/mvn_full.log`）该测试 **7/7 通过** —— 当时 04:00Z 尚未到；
2. 本轮全量回归（12:00 CST = 04:00 UTC）该测试 **确定性失败**（单独复跑两次均失败，非 flake）；
3. 当前 UTC = 2026-09-20 04:02:56，已过期；
4. 该文件 `git status` **干净**（既不在本次改动里，也不在用户 WIP 里），mtime 2026-09-15，是**已提交**代码；
5. ⑤ 的改动集与 `service/contactmemory` **零交集**。

→ 这是「fixture 里写死绝对时间、日期一到必挂」的经典缺陷，**从今天 12:00 起会永久失败**。
修复很轻（把该 fixture 的 `expiresAt` 改为远未来或相对于 `observedAt`），但它**不在本轮调用链内**，且涉及该测试的 TTL 语义取法，
故**未擅自改动**，等确认。

**已修复（2026-09-20 下午，独立一轮）** —— 走的是**注入 `Clock`** 这条根治路线，而不是「把常量改远一点」：

1. `ContactMemoryConsolidationService` 加 `private final Clock clock`，公开无参构造
   `@Autowired → this(Clock.systemUTC())`，另留包私有 `ContactMemoryConsolidationService(Clock)`，
   判定从 `Instant.now()` 改为 `clock.instant()`（对齐 `ContactMemoryScheduler` 既有约定）。
2. 测试类改用 `private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)`，
   fixture 的 `expiresAt` 写成 `NOW.plus(Duration.ofDays(1))`、过期用例写 `NOW.minus(Duration.ofDays(1))`。

**为什么不是「改成相对 `observedAt`」**：判定基准是注入时钟、且生产侧
`ContactMemoryMutationService:103` 写的是 `now.plus(ttlDays, DAYS)`，即「写入时刻 + TTL」。
改成相对 `observedAt` 只是把炸弹推到下个月。**测试结果现在与真实墙钟彻底解耦**。

### 文档回写

`docs/代码板块地图.md`（第四节重写：4.1 归零记录 + 4.2 硬规则；板块 7/8 文件数与 review 关注点；定时任务总表补 `WhatsAppHistorySyncWorker`）、
`docs/learning/00-架构总图.md`（越界章节改为「已清零」、最近结构变更、定时任务表）、
`docs/代码阅读顺序.md`（**顺带按实测更新了多个陈旧计数**：entity 27→61、WeCom 实体 6→9、mapper 39→66、chatapp 43→44、whatsapp 模板 15→21、callrecord 8→10、WeCom 76→85）、
`docs/superpowers/README.md`、本文件。

## 十、未闭合

**本段（2026-09-20 下午）新收掉的两项**（原属「门禁盲区/工程卫生」）：

- ✅ **`ContactMemoryConsolidationServiceTest` 时间炸弹** → 已修（见第九节末：注入 `Clock` + 固定时钟）。
- ✅ **裸 `@Testcontainers` 放大偶发失败** → 已统一：26 个容器测试类全部改为 `@Testcontainers(disabledWithoutDocker = true)`
  （原本只有 3 个带此开关），Docker 不可用由「error」变「skip」，不再把环境故障变成构建红。
  **代价已写进 `docs/代码板块地图.md` 第六节**：验收必须显式核对 `Skipped` 计数，防止 CI 假绿。
- ✅ **`ContactMemoryConsolidationService` 无法注入固定时钟** → 已收（同上，改 `clock.instant()`）。
- 本轮验收：`mvn clean test` → **1428 跑 / 0 失败 / 0 错误 / 0 跳过 / BUILD SUCCESS**（2m23s）；
  其中 `AppIntegrationTest` **10/10** 是双构造器 + `@Autowired` 能被 Spring 正确装配的实证。

**仍未闭合**：

- **其余 8 个 `channel/wecom` 实体**仍在渠道包内（当前 0 违规，记为债务；要动约 50 个文件）。
- `channel` 包内混着的业务逻辑（如 `ChatAppMessageSyncService` 同时持有 SDK 装配与对账语义）。
- 6 处重复的 HTTP 客户端装配（`CamsClientFactory`）与 whatsapp 侧第二套 CAMS 凭证模型未收敛。
- `service.whatsapp` / `service.chatapp` 的命名拆分未统一（③ 只在渠道注册表里收口成一份定义）。
- **门禁是单向规则**：`channel → service` 这侧从未被约束。实测 17 个文件存在该方向依赖，
  其中 `channel/email/EmailSyncService` 同时编排 4 个业务域（`service.event` / `service.aitopic` /
  `service.contactmemory` / `service.wecom`）。病根是 **email 板块没有对应的 `service.email`**，
  反向依赖无处可去。修它要把 `EmailSyncService`(729 行) 拆成「渠道适配」+「业务编排」两半，
  Controller 归 `web/`（另有 3 个 Controller 错位在 channel 包），**应单独规划，不并入本轮**。
- **`demo/message-center-demo` 模块另有 7 个裸 `@Testcontainers`** —— 那是独立工程，未随本轮统一。
- ②③④⑤ 及本段的改动仍未提交（`index.lock` 已消失，可正常 `git add <path>`；但仓库内混有 60+ 无关前端 WIP，
  **禁止 `git add .`**）。
