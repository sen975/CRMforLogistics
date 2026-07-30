# 企业微信官方每日会话摘要实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. 用户明确要求不提交、不暂存，因此每个任务以测试和 `git diff --check` 收口，不执行 `git commit`。

**Goal:** 在同一个企业微信专区程序中增加固定能力 `conversation_daily_summary`，调用官方会话摘要模型，并由 8107 每天生成 A-B/A-C 各一条摘要持久化记录，同时保持 `conversation_viewer_sync` 原有行为。

**Architecture:** 专区程序使用两个编译期固定 ability ID；viewer 能力只调用 `sync_msg`，摘要能力只调用 `create_summary_task`/`get_summary_result`。8107 只持有消息引用元数据，按北京时间自然日分组并通过 PostgreSQL 任务表幂等调度，最终表只保存摘要和覆盖统计。

**Tech Stack:** JDK 17、Maven、企业微信 Java SDK 1.4.0、Fastjson2、Java HttpClient、Gson、PostgreSQL、Flyway、JUnit 5、Testcontainers。

## Global Constraints

- 一个应用只关联一个专区程序；该程序只允许 `conversation_viewer_sync` 和 `conversation_daily_summary` 两个固定能力。
- 不使用 Qwen、llama.cpp 或模型权重；只调用企业微信官方会话摘要模型。
- 不把原始正文、语音转写、附件、`secret_key` 或完整官方响应新增到数据库、日志或前端；现有 viewer 为渲染组件而保存的最小 `secret_key` JSONL 不变，摘要链路不复制它。
- 每天北京时间 `00:05` 处理前一自然日；单个员工-外部联系人每天最多一条最终摘要。
- viewer 没有 PostgreSQL 也能运行；摘要关闭时不打开数据库、不启动 worker。
- 官方输入最多 1000 条、约 30000 UTF-8 字符；分批最多 32 批，模型等待最长 24 小时。
- 保留用户现有无关改动；不整体回滚、不停止 8067、不提交、不执行 `git add`。

---

### Task 1: 专区程序双能力入口

**Files**

- Modify `demo/wecom-chatdata-zone-program/src/main/java/com/crmforlogistics/wecomchatdata/AbilityIdMatcher.java`
- Create `demo/wecom-chatdata-zone-program/src/main/java/com/crmforlogistics/wecomchatdata/AbilityDispatcher.java`
- Create `demo/wecom-chatdata-zone-program/src/main/java/com/crmforlogistics/wecomchatdata/SummaryAbility.java`
- Modify `demo/wecom-chatdata-zone-program/overlay/src/main/java/mytype/mycom/mygroup/DemoCallProgramHandler.java`
- Modify `demo/wecom-chatdata-zone-program/build-image.sh`
- Test `AbilityMatcherTest`, new `SummaryAbilityTest` and `AbilityDispatcherTest`

**Interfaces**

```java
AbilityDispatcher.dispatch(String abilityId, String data, SdkInvoker sdk)
SummaryAbility.process(String rawInput, SdkInvoker sdk)
```

- [ ] 写红灯测试：两个固定 ID 可匹配，旧 ID/空/超长 ID 拒绝；summary submit 只能调用 `create_summary_task`，poll 只能调用 `get_summary_result`；未知字段、重复 msgid、超过 1000 条和密钥回显失败。
- [ ] 运行 `cd demo/wecom-chatdata-zone-program && mvn -q -Dtest=AbilityIdMatcherTest,SummaryAbilityTest,AbilityDispatcherTest test`，预期因新类不存在而失败。
- [ ] 实现常量白名单 `conversation_viewer_sync`、`conversation_daily_summary`。viewer 分支保留 `sync_msg`；summary 分支只允许两个官方 SDK apiName。构建脚本检查 JAR 中同时包含两个 ID，不再读取单值 ability 环境变量。
- [ ] 重跑上述专项测试，确认 PASS，输出不含 `secret_key`。

### Task 2: 官方摘要 Gateway 与配置

**Files**

- Create `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComSummaryGateway.java`
- Create `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComSummaryException.java`
- Modify `Config.java`、`config.example.env`
- Test new `WeComSummaryGatewayTest` and `ConfigTest`

**Interfaces**

```java
SubmitResult submit(ResolvedInstallation installation,
                    List<MessageReference> messages, Duration timeout)
PollResult poll(ResolvedInstallation installation,
                String jobId, Duration timeout)
record MessageReference(String msgid, String secretKey) {}
```

- [ ] 用本地 HttpServer 写红灯测试，断言请求地址、`program_id`、`ability_id=conversation_daily_summary`、submit/poll request_data、非 2xx、外层错误、响应超过 1 MiB、摘要超过 64 KiB、非法状态和超时。
- [ ] 运行 `cd demo/message-center-demo && mvn -q -Dtest=WeComSummaryGatewayTest,ConfigTest test`，预期失败。
- [ ] 实现 gateway。submit 使用 `operation=submit` 和 msg_list；poll 使用 `operation=poll`、jobid 和空数组；拒绝未知字段，不记录请求/响应。增加摘要开关、能力 ID、00:05、拆批、退避、20 次瞬时失败和 24 小时等待配置。
- [ ] 重跑测试并执行 `git diff --check -- demo/message-center-demo/src/main/java demo/message-center-demo/src/test/java`。

### Task 3: 引用分组和有界拆批

**Files**

- Create `WeComDailySummaryBatcher.java`
- Modify `WeComChatDataStore.java`
- Test new `WeComDailySummaryBatcherTest` and existing store tests

**Interfaces**

```java
List<ConversationDay> load(Instant fromInclusive, Instant toExclusive);
List<SummaryBatch> split(ConversationDay day, int maxBatches);
```

- [ ] 写固定 Clock 测试：北京时间跨日、双向消息合并、A-B/A-C 分开、群聊/员工内部会话跳过、稳定排序、1000 条边界和 32 批上限。
- [ ] 实现只读取 JSONL 中 msgid、secret_key、userid、external_userid、send_time、msgtype；不读取或新增正文。按 `ZoneId.of("Asia/Shanghai")` 计算前一日。
- [ ] 先按 1000 条切批；官方输入过长错误 790040 时二分当前批次；单条仍失败标记 partial/failed，不伪造摘要。
- [ ] 运行 `cd demo/message-center-demo && mvn -q -Dtest=WeComDailySummaryBatcherTest,WeComChatDataStoreTest test`。

### Task 4: Flyway 表和 Repository

**Files**

- Create `src/main/resources/db/migration/V5__wecom_daily_summary.sql`
- Create `WeComDailySummaryRepository.java`、`JdbcWeComDailySummaryRepository.java`
- Test `JdbcWeComDailySummaryRepositoryIT.java`
- Modify `DatabaseSchemaIT.java`

**Interfaces**

```java
void ensureDailyJob(DailySummaryKey key, List<String> msgidDigests, Instant now);
Optional<LeasedSummaryJob> leaseNext(String owner, Instant now, Duration lease);
void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt);
void markCompleted(UUID jobId, String summary, Coverage coverage, Instant now);
void markRetry(UUID jobId, String code, Instant nextAttemptAt);
void markFailed(UUID jobId, String code, String state, Instant now);
```

- [ ] 写 Testcontainers 红灯 IT：V5 建表、分组唯一键、状态约束、租约领取、重复 ensure 幂等和完成摘要唯一写入；断言没有正文、密钥或消息 ID 列表字段。
- [ ] 运行 `cd demo/message-center-demo && mvn -q -Dtest=JdbcWeComDailySummaryRepositoryIT,DatabaseSchemaIT test`；Docker 不可用时只记录阻断，不伪造通过。
- [ ] 实现 jobs 表（任务键、批次、不可逆引用摘要、官方 jobid、状态、租约、错误码）和 summaries 表（唯一分组键、摘要、覆盖统计、完整性状态）。SQL 参数绑定，租约事务使用 `FOR UPDATE SKIP LOCKED`。
- [ ] 重跑数据库 IT。

### Task 5: 每日调度和摘要任务编排

**Files**

- Create `WeComDailySummaryService.java`
- Create `WeComDailySummaryScheduler.java`
- Test `WeComDailySummaryServiceTest.java`、`WeComDailySummarySchedulerTest.java`

**Interfaces**

```java
DailyRunResult run(Instant now) throws Exception;
void tick(Instant now);
void close();
```

- [ ] 写固定 Clock/fake Gateway/fake Repository 红灯测试：00:05 只建前一日任务、重复不重复、submit 保存 jobid、poll 0/1/2、重启恢复、24 小时截止和瞬时错误最多 20 次。
- [ ] 实现 service 只消费 batcher/gateway/repository，不接收 HTTP exchange；scheduler 使用有界 ScheduledExecutorService，按 Asia/Shanghai 计算下一个 00:05，每分钟扫描租约，退避上限 900 秒。
- [ ] 配置关闭或数据库不可用时不启动；摘要完成后将同一分组批次摘要按时间顺序合并为一条最终记录。
- [ ] 运行 `cd demo/message-center-demo && mvn -q -Dtest=WeComDailySummaryServiceTest,WeComDailySummarySchedulerTest test`。

### Task 6: App 接线、文档和镜像

**Files**

- Modify `App.java`、`Config.java`
- Modify `demo/wecom-chatdata-zone-program/README.md`
- Modify `demo/message-center-demo/README.md`、`config.example.env`
- Modify the daily summary spec and viewer-sync plan to remove single-ability wording

- [ ] 写生命周期测试：摘要关闭不打开 Database，viewer 仍能构造；摘要开启但数据库不可用结构化失败；scheduler/HTTP/database 按顺序关闭。
- [ ] 在 `startWeb` 中仅在摘要开启时打开 Database/migrate，创建 repository/service/scheduler；保留现有 viewer route 和无数据库路径；不改 8067/OpenAPI。
- [ ] 文档写入双能力协议、同一 program_id、官方模型关联、私钥 chmod 600、启动命令 `/app/start`。
- [ ] 重建并验证镜像：

```bash
cd demo/wecom-chatdata-zone-program
mvn -q test
./prepare-official-sdk.sh /private/tmp/java_demo_src_1.4.0.tar.gz
./build-image.sh
tar -tf target/wecom-chatdata-zone-program-linux-amd64.tar | rg 'app/start|app/wecom-chatdata-zone-program.jar|usr/lib/libWeWorkSpecSDK.so'
cd ../message-center-demo
mvn -q test
```

预期 rootfs 是新的 `docker export` tar，JAR 同时含两个固定 ID，不含 `session_archive_analysis`。

### Task 7: 全部门禁和真实部署边界

**Files:** 无新增文件，除非门禁发现本计划范围内缺陷。

- [ ] 运行主工程：

```bash
cd demo/message-center-demo
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
cd ../..
git diff --check
git status --short
```

- [ ] 不执行 `git add`、`git commit`，不停止 8067。
- [ ] 服务器上传新 rootfs tar，企业微信后台在同一程序关联两个能力并选择官方摘要模型；8107 复用现有 `WECOM_CHATDATA_PROGRAM_ID`，新增摘要配置后重启。
- [ ] 真实验收 A-B/A-C 前一日会话各一组：viewer 仍展示原文；00:05 后数据库各一条摘要；日志和数据库不出现正文或密钥。未完成企业微信审核/权限/模型队列时，只报告本地门禁通过。
