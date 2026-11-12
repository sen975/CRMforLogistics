# 阿里云 CAMS 供应商层独立 —— 实施计划与影响面（越界改造第 ④ 步）

> 状态：**已完成（2026-09-20）**。下文第一至八节是**执行前**的影响面分析（保留原始判断，便于对照）；
> 实际落地结果与偏差见文末「九、实施记录」。拍板结果：决策点 1 选 `infrastructure.cams`、2 保留原类名、
> 3 接口搬 `service.whatsapp.template`、4 留冻结记债、5 只做纯搬运。
> 背景真源：`docs/代码板块地图.md` 第四节；`docs/superpowers/plans/2026-09-20-chatapp-outbox-relocation.md`（②，已完成）；
> `docs/superpowers/plans/2026-09-20-channel-type-registry.md`（③，已完成）。
> 前置结论（2026-09-20 排查）：`service/whatsapp` → `channel/chatapp` **不是错用**，
> `normalizeChannelType()` 把 `whatsapp` 直接归一成 `chatapp`，两者底层都是**阿里云 CAMS**
> （ChatApp Message Service）。是命名把同一个供应商拆成了两个域。④ 就是把这个供应商层独立出来。

## 一、结论

1. **④ 的作用域很窄**：只解决「whatsapp 侧需要 CAMS 供应商原语」这一件事，
   能让架构门禁基线 **40 条 → 32 条**（消掉 8 条）。
2. **④ 不解决 `WhatsAppHistorySyncWorker → ChatAppMessageSyncService`（4 条）**。
   那是**业务依赖**（whatsapp 侧 worker 驱动 chatapp 侧同步服务），不是供应商依赖。
   必须单独决策，见第五节决策点 4。**不要指望 ④ 把 service.whatsapp 清干净。**
3. **目标包位置是唯一的关键决策**：必须放在 `channel` 之外。
   放 `channel/cams` 的话，`service.whatsapp → channel.cams` 照样触犯同一条门禁规则
   （规则约束的是 `service.. → channel..`，不区分渠道子包），**基线一条都不会少，
   而且新 FQN 会被判为「新增越界」当场把构建打红**。推荐 `infrastructure.cams`。
4. 顺带收益：`createClient` 的 AsyncClient 装配在 **6 个类里逐字重复**，
   默认 region/endpoint 两个字面量在 **2 处共出现 5 次**。这部分可选一并收敛。

## 二、基线现状（证据）

`src/test/resources/archunit_store/50d47b56-…`（40 条 / 5 个文件）：

| 文件 | 条数 | ④ 是否触及 |
|---|---|---|
| `service/aitopic/AiTopicInputService.java` | 21 | 否 |
| `service/account/AccountService.java` | 7 | 否 |
| `service/whatsapp/WhatsAppHistorySyncWorker.java` | 4 | **否**（业务依赖） |
| `service/whatsapp/template/WhatsAppProviderScopeService.java` | 5 | **是** |
| `service/whatsapp/template/PublicTemplateApplicationService.java` | 3 | **是** |
| **合计** | **40** | **可消 8** |

被引用的具体类型（从基线快照原文读出）：

- `channel.chatapp.ChatAppMessageSyncService`（HistorySyncWorker，4 条）
- `channel.chatapp.ChatAppAccountCredentialsResolver` / `ChatAppAccountCredentials` / `ChatAppAccountCredentialsException`（ProviderScopeService，5 条）
- `channel.chatapp.template.ChatAppPublicTemplateGateway`（PublicTemplateApplicationService，3 条）

## 三、迁移范围

### 3.1 真·CAMS 供应商原语（无业务语义，可移）

| # | 现位置 | 行数 | 为什么算供应商层 | 必需性 |
|---|---|---|---|---|
| 1 | `channel/chatapp/ChatAppAccountCredentials.java` | 11 | 6 个字段 `accessKeyId` / `accessKeySecret` / `custSpaceId` / `chatappFrom` / `region` / `endpoint` 全是 CAMS 概念（注释原文就写着 "Runtime credentials for one owned WhatsApp/CAMS account"） | **必需** |
| 2 | `channel/chatapp/ChatAppAccountCredentialsException.java` | 22 | 同上，错误码全 `CHATAPP_ACCOUNT_CREDENTIALS_*` | **必需** |
| 3 | `channel/chatapp/ChatAppAccountCredentialsResolver.java` | 91 | `resolve(ChannelAccountEntity)` + `resolveSpace(WhatsAppProviderScopeEntity)`；`DEFAULT_REGION` / `DEFAULT_ENDPOINT` 两个 CAMS 常量在这里 | **必需** |
| 4 | `channel/chatapp/ChatAppOssMediaUploader.java` | 115 | 阿里云 OSS 直传（HMAC-SHA1 手写签名），纯供应商 | 可选 |
| 5 | 6 处重复的 `createClient` / `createCredentialsProvider` | — | 见 3.3；收敛为 `CamsClientFactory` | 可选 |
| 6 | `AliyunWhatsAppOnboardingGateway` 内的 `ProviderCredentials` record + `globalProviderCredentials()` + `globalCredentials()` | 368-369、288-306 | whatsapp 侧**第二套** CAMS 凭证模型 | 可选 |

### 3.2 搬走会「误伤」的（**不要动**，是业务语义）

`ChatAppSendService`（消息组装）、`ChatAppMessageSyncService`（同步 + 游标投影）、
`ChatAppCapabilityProbe`（能力探测语义 + `ChatAppCapabilityReport`）、
`ChatAppPollingProjector`、`ChatAppOutboundMessageLinker`、
`AliyunChatAppBroadcastGateway`（群发语义）、`AliyunChatAppTemplateGateway`（模板生命周期）、
`AliyunChatAppPublicTemplateGateway`（模板网关实现）、`PublicTemplateResponseParser`、
`ChatAppTemplateSyncService`、`ChatAppSyncScheduler`、`ChatAppController`。
这些只是**恰好用到了 CAMS 凭证**，本身是业务实现。

### 3.3 重复装配的证据（决定第 5 项做不做）

同一个 `AsyncClient.builder().region(...).credentialsProvider(StaticCredentialProvider.create(Credential.builder()…)).overrideConfiguration(ClientOverrideConfiguration.create().setEndpointOverride(...)).build()` 在上述文件里各写了一遍：

| 文件 | 行 |
|---|---|
| `channel/chatapp/ChatAppSendService.java` | 181-198 |
| `channel/chatapp/ChatAppMessageSyncService.java` | 297-310 |
| `channel/chatapp/ChatAppCapabilityProbe.java` | 185-193 |
| `channel/chatapp/AliyunChatAppBroadcastGateway.java` | 325-333 |
| `channel/chatapp/template/AliyunChatAppTemplateGateway.java` | 525-535 |
| `service/whatsapp/AliyunWhatsAppOnboardingGateway.java` | 261-269 |

注意第 7 个形态不同：`channel/chatapp/template/AliyunChatAppPublicTemplateGateway.java:145-158`
用的是**旧版 `teaopenapi.Client`（RPC 风格 `Config`）**，不是 `AsyncClient`。
若做 `CamsClientFactory`，要么区分两种客户端形态，要么本轮不碰它。

## 四、逐文件影响清单

### 4.1 生产代码

**A. 移动（`channel/chatapp` → `infrastructure/cams`）**
3.1 表中 #1 #2 #3（必需）；#4 #5 #6 可选。

**B. 因类型换包必须补 import 的 `channel.chatapp` 类 —— 7 个**
（这些类原先与 `ChatAppAccountCredentials*` 同包，无需 import）

| 文件 | 引用点行号 |
|---|---|
| `channel/chatapp/AliyunChatAppOutboundGateway.java` | 21, 27, 39, 60, 77 |
| `channel/chatapp/AliyunChatAppBroadcastGateway.java` | 3-5, 46, 50, 58, 67, 80, 89, 189, 314 |
| `channel/chatapp/ChatAppSendService.java` | 3-5, 42, 47, 66, 95, 163, 181, 193 |
| `channel/chatapp/ChatAppMessageSyncService.java` | 3-5, 44, 52, 60, 134, 202, 261, 297, 307 |
| `channel/chatapp/ChatAppCapabilityProbe.java` | 3-4, 44, 50, 54, 85, 102, 119, 146, 185, 195 |
| `channel/chatapp/template/AliyunChatAppTemplateGateway.java` | 30-33, 85, 92, 110, 274, 280, 443, 483, 525, 532 |
| `channel/chatapp/template/AliyunChatAppPublicTemplateGateway.java` | 4-6, 41, 50, 75, 113, 182, 206 |

**C. 越界的「消费方」—— ④ 的目的所在**

| 文件 | 行 | 现状 | ④ 后 |
|---|---|---|---|
| `service/whatsapp/template/WhatsAppProviderScopeService.java` | 3, 4, 23, 27, 96-98 | → `channel.chatapp.*`，冻结 5 条 | → `infrastructure.cams.*`，**越界消除** |
| `service/whatsapp/template/PublicTemplateApplicationService.java` | 3, 字段, 调用 | → `channel.chatapp.template.ChatAppPublicTemplateGateway`，冻结 3 条 | 见决策点 3 |
| `service/channel/ChatAppCapabilityService.java` | 3, 4, 21, 26, 133 | → `channel.chatapp.*`，**在门禁白名单内，非越界** | 只需改 import 路径 |
| `service/chatapp/outbox/MessageOutboxWorker.java` | 4, 165 | → `...ChatAppAccountCredentialsException`，白名单内 | 只需改 import 路径 |

**D. 不在 ④ 范围（必须写清，避免误以为清干净了）**

| 文件 | 行 | 依赖 | 说明 |
|---|---|---|---|
| `service/whatsapp/WhatsAppHistorySyncWorker.java` | 3, 字段, 48 | `channel.chatapp.ChatAppMessageSyncService` | 业务依赖，4 条基线留在这里 |

### 4.2 测试代码（约 12 个文件）

**随被测类一起迁包 —— 1 个**
- `test/channel/chatapp/ChatAppAccountCredentialsResolverTest.java` → `test/infrastructure/cams/`
- （若迁 `ChatAppOssMediaUploader`：`test/channel/chatapp/ChatAppOssMediaUploaderTest.java` 同办）

**同包测试：需新增 import —— 8 个**

| 文件 | 行 |
|---|---|
| `test/channel/chatapp/AliyunChatAppBroadcastGatewayTest.java` | 36, 45 |
| `test/channel/chatapp/AliyunChatAppOutboundGatewayTest.java` | 26, 54, 62, 86, 95, 102, 103 |
| `test/channel/chatapp/ChatAppCapabilityProbeTest.java` | 30 |
| `test/channel/chatapp/ChatAppMessageSyncServiceTest.java` | 50, 54, 305 |
| `test/channel/chatapp/ChatAppSendServiceTest.java` | 39, 56 |
| `test/channel/chatapp/template/AliyunChatAppTemplateGatewayTest.java` | 25, 26, 27 |
| `test/channel/chatapp/template/PublicTemplateGatewayContractTest.java` | 6, 7, 214 |
| `test/channel/chatapp/ChatAppIntegrationTest.java`（迁移后需复核） | — |

**跨包测试：改 import 路径 —— 3 个**
- `test/service/whatsapp/template/WhatsAppProviderScopeServiceTest.java:3,4,5`
- `test/service/whatsapp/template/PublicTemplateApplicationServiceTest.java:3`
- `test/service/chatapp/outbox/MessageOutboxWorkerTest.java:4`

**不动 —— 3 个**
`test/service/channel/ChannelAccountServiceTest.java:3`、`test/service/channel/ChannelAccountOwnerIsolationTest.java:3`、
`test/service/whatsapp/WhatsAppHistorySyncWorkerTest.java:3`（都引 `ChatAppMessageSyncService`，不迁）

### 4.3 配置 / 构建（全部不动）

| 项 | 结论 |
|---|---|
| `pom.xml` | **不动**（不新增依赖，ArchUnit 1.3.0 已在） |
| `src/test/resources/archunit.properties` | **不动**（`allowStoreCreation=false` 保留；`allowStoreUpdate` 未显式设置，见风险 3） |
| `AppConfig` / `application*.yml` | **不动**（`aliyunAccessKeyId` / `aliyunAccessKeySecret` / `camsRegion` / `camsEndpoint` 四个字段位置与语义不变） |
| `@SpringBootApplication` 扫描范围 | **不动**（已核实：`App.java:15` 在 `com.crmforlogistics.messagecenter`，`infrastructure.cams` 是其子包） |

## 五、决策点（需要拍板）

**1. 目标包位置**
- ✅ **推荐 `com.crmforlogistics.messagecenter.infrastructure.cams`**
  —— 与已有 `infrastructure.CredentialCipher` / `MinioStorage` 同层，都是「外部系统适配」；
  在 `channel` 之外，基线才能真正收缩。
- ❌ `channel.cams` —— 基线一条都不消，且新 FQN 被判新增越界，构建当场变红。

**2. 类名**
- ✅ **推荐保留 `ChatAppAccountCredentials` / `…Exception` / `…Resolver` 原名**
  —— CAMS 全称就是 Aliyun ChatApp Message Service，`ChatApp` 在这里是供应商词，语义正确；
  且搬包已经要改 11 个文件的 import，再叠一次改名会显著放大 diff、抬高 review 成本。
- 备选：改名 `CamsCredentials` / `CamsCredentialsResolver`（概念更干净，但 diff 翻倍）。

**3. `ChatAppPublicTemplateGateway` 接口归属**
该接口只有 9 行，签名却是
`list(service.whatsapp.template.TemplateCredentialSource, service.whatsapp.template.PublicTemplateModels.Query)`
—— **契约的词汇完全由 whatsapp 域定义**，接口却放在 `channel.chatapp.template`。三选一：
- a) 搬到 `infrastructure.cams`（3 条基线同步消除）
- ✅ b) **搬到 `service.whatsapp.template`**（接口随消费方，实现 `AliyunChatAppPublicTemplateGateway` 留在 `channel.chatapp`）
   —— 契约归消费方所有更符合「业务域拥有合同、渠道只做协议映射」，且 3 条基线同样消除
- c) 不搬，3 条基线继续冻结

**4. `WhatsAppHistorySyncWorker → ChatAppMessageSyncService`（4 条）怎么办**
- ✅ a) **本轮留冻结**，单独记录为债务（它与供应商层无关，硬塞进 ④ 会让一个提交变成两件事）
- b) 把 `service.whatsapp..` 加入门禁白名单 —— 理由与 `service.chatapp` 相同（同属 CAMS 板块）；
  代价是放宽了 6100 行的 `service/whatsapp` 对 `channel.chatapp` 的**全部**未来依赖
- c) 把 `ChatAppMessageSyncService` 也搬到共享业务包 —— 能根治，但要看它是否被 chatapp 业务强耦合

**5. 是否同批做「供应商装配收敛」（`CamsClientFactory` 收 6 处重复 + whatsapp 侧第二套凭证模型）**
- a) 同批做（收益：删两套重复；代价：④ 从「纯搬运」变成「搬运 + 抽象」，风险上升）
- ✅ b) **先只做搬运**，`CamsClientFactory` 单独一批
  —— ② 的经验是纯搬运最安全（零抽象、可用编译 + 基线数量双向验证）；抽象引入后一旦出问题，很难区分是搬运错还是抽象错

## 六、风险

1. **包私有可见性陷阱（② 踩过，必须复用这个检查）**。
   `ChatAppCapabilityProbe(Function<…>)` 构造器、`ChatAppSendService(…, Supplier<AsyncClient>)` 构造器都是**包私有**。
   ② 当时预算是 8 处 import 修正，实际是 10 处 —— 差额就是这个坑。
   **验收必须靠 `mvn test-compile`，不能只按 import 列表数数。**

2. **基线「自动收缩」是沉默行为（有源码证据）**。
   `TextFileBasedViolationStore` 中 `default.allowStoreUpdate` 默认值为 `true`（源码常量 `ALLOW_STORE_UPDATE_DEFAULT = "true"`），
   项目未覆盖该配置；`FreezingArchRule.removeObsoleteViolationsFromStore()` 会在**跑测试时直接回写**
   `archunit_store/*` 文件，把不再发生的越界删掉。
   → 所以 ④ 提交前**必须**先跑一次门禁并 `git diff src/test/resources/archunit_store/`，
   确认 diff 里只有预期消失的 8 条、没有任何新增，再连同代码一起提交。
   否则基线漂移会静默混进 commit。

3. **两套 CAMS 凭证模型的语义差异，合并时别抹平**。
   `ChatAppAccountCredentials(accessKeyId, accessKeySecret, custSpaceId, chatappFrom, region, endpoint)`
   与 `AliyunWhatsAppOnboardingGateway.ProviderCredentials(accessKeyId, accessKeySecret, region, endpoint)`。
   在 `resolveSpace()` 里 `chatappFrom` 被显式置为 `""`（因为 provider scope 不代表某个发信号码），
   合并模型时必须保留这个「可空」语义 —— 否则会把 onboarding 链路误判成缺凭证。

4. **`InstallationImportApplication` 有自定义 `@ComponentScan`**（排除 Controller / ControllerAdvice / App）。
   resolver 是 `@Component`，迁移后仍在扫描范围内，但该入口需实测一次。

## 七、验收

1. 编译：`mvn -f demo/message-center-spring/backend/pom.xml -q -DskipTests compile`
   与 `… -DskipTests test-compile`（**这一步是包私有陷阱的唯一有效拦截**）
2. 门禁：`mvn … test -Dtest=ArchitectureBoundaryTest`
   → 断言基线剩 **32** 条（只做凭证三件套则是 35 条）
   → `git diff --stat src/test/resources/archunit_store/` 必须只有删除、无新增
3. 真实装配：`mvn … test -Dtest=AppIntegrationTest`
   —— 全项目唯一真实 `@SpringBootTest`，是唯一能暴露 `ChatAppAccountCredentialsResolver` 注入失败的用例
4. 定向：`mvn … test -Dtest='*ChatApp*,*WhatsApp*,*Channel*'`
5. 全量：`mvn … test`（上一轮基准 1428 passed；`AppIntegrationTest` 在 ② 时因 Docker 端口竞争单独跑才过，
   需按同样方式复跑）

## 八、未闭合

- `WhatsAppHistorySyncWorker → ChatAppMessageSyncService`（4 条）在 ④ 之后仍留在基线里，需要另开一条线。
- `AliyunChatAppPublicTemplateGateway` 用的旧版 `teaopenapi.Client` 与其余 6 处 `AsyncClient` 不是同一套装配，
  本轮不做统一。
- 6 处重复的 `AsyncClient` 装配（决策点 5 的 `CamsClientFactory`）与 whatsapp 侧第二套 CAMS 凭证模型未收敛。

---

## 九、实施记录（2026-09-20）

**结论：按推荐方案落地，纯搬运零抽象。门禁基线 40 → 32 条（删除 8 条、新增 0 条）。**

### 9.1 实际改动

| 动作 | 内容 |
|---|---|
| 搬迁 | `channel/chatapp/{ChatAppAccountCredentials, ChatAppAccountCredentialsException, ChatAppAccountCredentialsResolver}.java` → `infrastructure/cams/`（保留原类名） |
| 搬迁 | `channel/chatapp/template/ChatAppPublicTemplateGateway.java` → `service/whatsapp/template/`（接口随消费方；实现 `AliyunChatAppPublicTemplateGateway` 留在 `channel/chatapp/template/`） |
| 搬迁 | `test/channel/chatapp/ChatAppAccountCredentialsResolverTest.java` → `test/infrastructure/cams/` |
| 改包名 | 上述 5 个文件的 `package` 声明 |
| 批量改 FQN | 11 个文件（6 生产 + 5 测试）里 `channel.chatapp.ChatAppAccountCredentials*` 与 `channel.chatapp.template.ChatAppPublicTemplateGateway` 的全限定名 |
| 补 import | 6 个生产 + 6 个测试文件（原先同包，无需 import） |
| 未动 | `pom.xml` / `archunit.properties` / `AppConfig` / yml / `archunit_store` 的其余内容 |

### 9.2 与影响面预测的偏差（3 处，都值得记）

1. **`Page`/`Query` 不是同包引用那么简单。** 接口搬到 `service.whatsapp.template` 后，我顺手删掉了指向
   `PublicTemplateModels.Page/Query` 的 import —— 结果编译失败：它们是 `PublicTemplateModels` 的**嵌套类型**，
   即使同包也仍需要 import。**教训：同包 ≠ 免 import；只有顶层类型同包才免。**
2. **实际要补 import 的文件数是 12（6 生产 + 6 测试），不是预测的 7+8。** 与 ② 的教训同源：
   预测清单是按「引用类型名的文件」数的，而 4.1/B 列的是「生产侧」，测试侧的同包引用没被单独列出。
3. **`channel.chatapp` 的文件计数变了**：直属 15 → 12，含 `template/` 为 19 → 15；`infrastructure/` 4 → 7。
   `docs/代码板块地图.md` 与 `docs/代码阅读顺序.md` 里的文件数已按实测更正（旧值 25/43 本来就是概数）。

### 9.3 验收证据（本轮实际跑过）

| 验收 | 命令 | 结果 |
|---|---|---|
| 编译 | `mvn -o -DskipTests test-compile` | BUILD SUCCESS（**这一步拦下了 9.2 的第 1 条**） |
| 门禁 | `mvn -o test -Dtest=ArchitectureBoundaryTest` | Tests run: 1 / Failures: 0；快照 40 → 32 行 |
| 基线 diff | `diff /tmp/baseline_before_④ <快照>` | **只有 3 处删除块，共删 8 行，0 新增**，且删除项恰为 `PublicTemplateApplicationService`(3) + `WhatsAppProviderScopeService`(5) |
| 真实装配 | `mvn -o test -Dtest=AppIntegrationTest` | Tests run: 10 / Failures: 0 |
| 定向 | `mvn -o test -Dtest='*ChatApp*,*WhatsApp*,*Channel*,*PublicTemplate*,*MessageOutbox*'` | Tests run: 593 / Failures: 0 |
| 全量 | `mvn -o test` | 见 9.4 |

另外用静态证据闭环了风险 4：`InstallationImportApplication` 的 `@ComponentScan(basePackages = "com.crmforlogistics.messagecenter")`
覆盖 `infrastructure.cams`，resolver 仍是 `@Component`。该入口**全项目没有测试**，此条只有静态证据，无运行证据。

### 9.4 遗留

- 本轮改动**未提交**（父仓库 `index.lock` 仍在）。提交时基线快照必须与代码同批，且**不要 `git add .`**。
- 决策点 4/5 的债务已登记进 `docs/代码板块地图.md` 第四节与 `docs/superpowers/README.md`。
