# WeCom、邮件与电话转录 Review 修复验收

日期：2026-09-23

## 完成范围

- WeCom 回调匿名路由、验签/事件合同、请求体流式 1 MiB 上限，以及 installation 查询/事件入库故障统一返回可重试 503。
- 提醒、电话转录 lease 与 owner-scoped keyset/retry 幂等、Range 音频读取和 MinIO 失败补偿沿用计划 Task 1-6 的实现。
- IMAP OpenSSL 子进程总 deadline、literal/行/命令/总量界限；SMTP 投递状态 `PENDING -> SMTP_SENT -> SENT`，歧义转 `UNKNOWN`。
- `UNKNOWN` 记录不自动重发。每个新 submission 有独立 lease token 和 5 分钟租期；发送期间由单个有界心跳线程每 20 秒续租。只有 lease 过期的 `PENDING/SMTP_SENT` 才能转成 `UNKNOWN`，恢复仍按 owner 隔离且每批最多 100 条。状态写回校验 token 和未过期租约；终态停止租期。账号删除后仍使用 `owner_user_id` 保留查询路径（V94）；V95 回填 V93-V94 窗口内记录 owner，V96 为存量记录初始化 lease。
- SMTP/本地状态不确定统一返回 `503 EMAIL_SEND_OUTCOME_UNKNOWN`；OpenAPI 的 `/api/v1/email/messages` 已映射实际前端使用的 `/api/send/email` handler。

## 验收证据

- `mvn -q -Dmaven.test.skip=true compile`：通过。
- WeCom、提醒、邮件（含 lease）、电话/转录/MinIO 跨渠道专项：最近一次授权 Docker 的汇总运行 134 tests，0 failures / 0 skipped；真实 PostgreSQL 覆盖 V96、过期 token fencing、活跃 lease 保护、并发恢复以及提醒 mapper SQL。
- `mvn -q -Dtest='OpenSslImapClientTest,EmailSendServiceTest,EmailControllerTest,EmailSubmissionMapperContractTest,EmailSyncServiceTest,EmailAttachmentReaderTest' test`：通过；测试输出包含既有附件存储失败分支的预期 WARN/stack trace。
- `node --test demo/message-center-demo/contracts/openapi/message-center-v1.test.mjs`：通过，校验 46 个 OpenAPI operations。
- 前端 `npm run test:source`：37/37 通过；`npm run test:ui -- --maxWorkers=2`：77 个文件、394/394 通过；`npm run build`：通过。
- `git diff --check`：通过。没有暂存或提交文件。

## 新增验收：邮件 submission 恢复边界

- `mvn -q clean -Dtest='EmailSubmissionMapperContractTest,EmailSubmissionLeaseKeeperTest,EmailSendServiceTest,EmailSubmissionMapperSqlTest,EmailSubmissionOwnerMigrationTest' test`：通过；真实 PostgreSQL 覆盖 V96、过期 token fencing、仍有效 lease 不被回收、并发 recovery 对同一行只转换一次。
- `mvn -q -Dtest='EmailSendServiceTest,EmailSubmissionMapperContractTest' test`：通过；service 单测断言运行时只请求当前 owner、每次至多 100 条。
- V94 -> V95 回填修复了旧 submission owner 为空、无法进入 owner 查询/恢复的问题。
- `mvn -q -Dmaven.test.skip=true compile`：通过。

## Lease 运维边界

- lease 续期线程在数据库持续不可用超过租期或 JVM 长暂停时，活任务仍可能失去租约；状态写入会被 fencing 拒绝并返回 UNKNOWN。
- 若进程仍活着但发送线程永久阻塞，心跳会保持租约直到进程重启，因此此类卡死依赖请求超时与进程健康恢复。
- V96 增加 NOT NULL lease 字段；部署前停止旧版本邮件发送实例，再由新版本执行迁移并启动，避免旧实例以不含 lease token 的 SQL 写入。
- SMTP/IMAP、MinIO、FunASR 网络与凭据仍需部署环境验收；SMTP 本身不提供远端 exactly-once 语义，UNKNOWN 不会自动重发。

## 2026-09-28 续验：已有联系人邮箱发送边界

- 专项命令：`mvn -q -f /private/tmp/email-recipient-verification.fHqNbL/pom.xml -Dtest=EmailRecipientBoundaryTest,EmailRecipientDatabaseTest,EmailSendHttpBoundaryTest,AuthControllerSecurityTest,EmailSendServiceTest,EmailControllerTest,EmailAttachmentStoreTest,EmailOwnerIsolationTest,EmailSubmissionMapperContractTest,EmailSubmissionMapperSqlTest,MessageSendAssistantToolsTest,OutboundMessageServiceTest test`，沙箱外访问 Docker 后退出码 0；12 个测试类合计 102 tests、0 failures、0 errors、0 skipped。沙箱内同命令因 Docker socket `Operation not permitted` 失败，不计为测试断言失败。`mvn -q -f /private/tmp/email-recipient-verification.fHqNbL/pom.xml -Dmaven.test.skip=true compile` 退出码 0；`git diff --check` 退出码 0。
- 使用取消原仓库源码与测试过滤的临时 Maven 配置 `/private/tmp/email-recipient-verification.fHqNbL/pom.xml`，从 `demo/message-center-spring/backend` 执行 `mvn -q -f /private/tmp/email-recipient-verification.fHqNbL/pom.xml clean test`。真实 PostgreSQL 的 `EmailRecipientDatabaseTest` 3/3，覆盖 owner/账号/渠道/孤立与删除、合并身份筛选；已有联系人成功发送时，SMTP 回调内从已提交数据库读到 `PENDING` submission、`pending` 消息正文、主题、相同 Message-ID、未读标志为 false，回调后核对 `SENT`/`sent` 且身份总数不变。SMTP 在测试中为计数回调，不是提供商侧投递。
- `EmailRecipientBoundaryTest` 8/8、`EmailSendHttpBoundaryTest` 12/12、`AuthControllerSecurityTest` 11/11、`EmailSendServiceTest` 17/17，均无失败或跳过。拒绝路径覆盖未知、他人、孤立、其他账号、非 email 身份、无认证上下文及多/组收件地址；数据库拒绝路径确认 submission、conversation、message、attachment 和 SMTP 零新增/调用，附件输入不读取。HTTP JSON/multipart 与 AI 均通过同一发送服务；安全过滤链对三个发送 POST 路由的无认证请求返回 `AUTHENTICATION_REQUIRED`。
- 同一次独占全量运行共 2195 tests、3 failures、1 error、2 skipped，退出码 1，**未通过**。失败项为 `AssistantPendingActionServiceTest.aVanishedTargetIsRefusedByTheActionItselfNotByTheCandidateWindow`（错误码期望不符）、`MessageSendApplicationServiceTest.duplicateClientRequestCreatesOnePendingMessageAndOutbox`（断言失败）、`ContactMemoryEndToEndTest.lateInboundMessageBehindSuccessCursorIsStillSentToTheModel`（模型调用缺失）；错误项为 `ContactServiceBeanInstantiationTest.beanFactoryUsesTheAutowiredConstructorWhenOptionalTagMapperIsPresent`（测试 Bean 缺 `ChannelAccountMapper`）。这些不在本轮邮件链路，保持原状，不能据此声称全量通过。
- 尚未以真实 SMTP 提供商及 MinIO 验证发送和附件链路；需在具备授权凭据的部署环境，用当前用户已有联系人绑定邮箱完成可控发送，核对提交/消息/附件、提供商投递结果和界面状态。原有先落库后发送 WIP 与本轮变更重叠，未整体暂存或回滚。

## 剩余风险

- 未对真实 WeCom 企业回调做生产签名/加密样本回放；需使用正式脱敏 payload 或测试企业验证 provider 字段差异。
- SMTP 本身没有远端 exactly-once 语义；`UNKNOWN` 是刻意阻止盲目重发的核对状态，不代表已确认送达或失败。
- 生产 SMTP、IMAP、MinIO、FunASR 网络及凭据行为仍需部署环境验收。
- 当前 workspace 含大量并行用户 WIP，本轮未提交；集成时应按计划相关文件逐项选择，禁止整体暂存。
