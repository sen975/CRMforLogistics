# 助手授权读取边界实施计划

> **For agentic workers:** Execute each task inline with a focused test/compile checkpoint. Steps use checkbox syntax for tracking.

**Goal:** 确保助手对任何业务数据的读取严格继承当前登录用户既有权限，并且只向模型输出经领域 owner 授权后的显式最小投影。

**Architecture:** 认证用户 ID 从 HTTP 安全上下文一路显式传入工具和领域 owner。领域服务/授权查询是唯一授权真源；候选集只用于消歧，不构成授权。各 owner 返回有界 DTO，助手工具禁止直接访问 Mapper；授权失败收敛为不泄露资源存在性的错误。沿用现有权限语义，不新增权限，不引入 RLS。

**Tech Stack:** Java 21, Spring Boot, MyBatis, PostgreSQL, JUnit 5, Mockito, ArchUnit, Maven。

## Global Constraints

- 助手严格继承当前用户在系统内已有的数据权限，不新增用户、团队、共享或管理员权限。
- 身份只来自 `SecurityUtil.currentUserId()`；请求体、工具参数、历史、摘要和模型输出不得覆盖用户身份。
- 每次数据读取或写入都由领域 owner 重新授权；候选 ID 不是授权凭证。
- 授权后生成显式、有界 DTO/record；禁止将 Entity 或无范围 Mapper 行直接序列化给模型。
- 不把账号密码、访问令牌、原始邮箱/手机号、渠道账号凭证交给模型；发送地址由服务端解析。
- 不改变既有会话权限、联系人权限、待办 owner、AI Topic owner 和数据保留规则；不引入数据库 RLS。
- 每个 Task 单独专项测试和编译、只提交任务涉及的文件；不运行全量回归，直到最后一个 Task。
- 运行和验收必须区分失败与 skipped；Docker/外部服务不可用时记录明确边界，不以跳过冒充通过。

---

## 文件与所有权地图

| 文件/目录 | 本计划中的职责 |
|---|---|
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java` | 助手专用的授权渠道身份投影与 owner-scoped 账号标签解析；普通联系人列表/详情行为不变 |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactIdentityMapper.java` | 联系人身份的授权 SQL；已有 `findByContactIdAndOwner` 是授权查询入口 |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ContactCandidateProvider.java`、`ContactBriefProvider.java` | 助手联系人检索与简报的最小投影，保持候选和简报字段集明确 |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/` | MCP 工具协议适配；只能调用 owner service/provider，不允许直接依赖 Mapper |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageQueryService.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/ContactTimelineService.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComSummaryReadService.java` | 消息、时间线、企微摘要的授权读取 owner |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contactmemory/ContactMemoryQueryService.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/todo/TodoItemService.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/OutboundMessageService.java` | 记忆、待办、话题、出站目标各自的 owner |
| `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantAuditService.java` | 助手审计写入；不得持久化用户原话或参数原始值 |
| `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/architecture/AssistantToolAuthorizationArchitectureTest.java` | 助手工具不得直依赖 Mapper 的硬门禁 |
| `docs/superpowers/specs/2026-09-28-assistant-authorization-boundary-design.md` | 本计划的设计真源 |
| `docs/superpowers/README.md` | 内部真源索引，仅追加本设计和本计划的索引行，不覆盖既有 WIP |

不得因本计划而顺手改无关渠道、模板或前端代码。

---

### Task 1: 助手候选与简报投影授权渠道身份

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ContactBriefProvider.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ContactBrief.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactServiceAuthorizationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/ContactBriefProviderTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactAssistantToolsTest.java`

**Interfaces:**
- Consumes: `ContactMapper.findAccessibleById(contactId, userId, isAdmin)`、`ContactIdentityMapper.findByContactIdAndOwner(contactId, userId)` 和 owner-scoped `ChannelAccountMapper.findByIdAndOwner`。
- Produces: `ContactService.listAuthorizedChannelProfiles(userId, contactId)` 返回有界的 `channelType/identityValue/displayName/accountLabel`；候选和 `ContactBrief` 同时派生 `channelTypes` 与 `channels`。普通联系人 API 不变。

- [x] **Step 1: 先写失败测试。** `ContactServiceAuthorizationTest` 断言 owner-scoped identity、账号标签解析、有界投影和无范围查询不被调用；`ContactBriefProviderTest` 与 `ContactAssistantToolsTest` 锁住 `channels` 白名单和禁止内部字段。
- [x] **Step 2: 运行目标用例确认失败。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactServiceAuthorizationTest test
```

预期：新增助手简报渠道类型断言失败，普通联系人列表/详情原有断言仍通过。

- [x] **Step 3: 最小修复。** 在联系人 owner 新增 `listAuthorizedChannelProfiles(userId, contactId)`，调用现有 owner SQL，按上限投影渠道身份；`identityScope` 仅用于当前用户账号标签解析，不进入输出；候选与简报从同一投影派生 `channels` 与 `channelTypes`。未改普通联系人 API 的身份查询。
- [x] **Step 4: 运行联系人授权专项测试。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactServiceAuthorizationTest,ContactServicePhoneBindingTest,ContactBriefProviderTest,ContactAssistantToolsTest test
```

预期：普通联系人 API 行为保持，助手候选/简报包含 owner-scoped 的有界渠道身份消歧投影；不含 identityScope、normalizedValue、数据库 ID 或凭证。

- [x] **Step 5: 验收闭合。** 编译、联系人专项测试、候选 provider 投影测试和文档一致性检查已完成。本轮不自动提交；若用户明确要求提交，再仅 stage 本任务文件，建议 commit message：`feat(assistant): project authorized contact channels`。

---

### Task 2: 固化联系人、记忆和电话时间线的重新授权

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ContactCandidateProvider.java`（仅在审计发现候选权限 SQL 与联系人列表不一致时）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ContactBriefProvider.java`（仅在测试发现授权前查询或越权字段投影时）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactTimelineAssistantTools.java`（只做错误转译/owner 调用接线，不复制 SQL）
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/ContactBriefProviderTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactAssistantToolsTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/ContactTimelineServiceTest.java`

**Interfaces:**
- Consumes: `ContactMapper.listForUser`、`ContactMapper.findAccessibleById`、`ContactTimelineService.timeline(userId, contactId, cursor, limit)`、owner-scoped memory/tag 查询。
- Produces: 联系人简报在授权失败时不读取记忆表；时间线每次基于当前 userId 读取授权身份、消息和通话，不接受候选集作为权限。

- [ ] **Step 1: 增加拒绝路径测试。** 在 `ContactBriefProviderTest` 钉住联系人不可访问时不调用 `listStableContext`、人工标签和状态查询；在 `ContactTimelineServiceTest` 钉住联系人身份查询用 `(contactId, userId)`，并且无 owner identity 时不读取 messages/call records。
- [ ] **Step 2: 增加助手引用重新授权测试。** 在 `ContactAssistantToolsTest` 与 `ContactTimelineAssistantTools` 对应现有测试中，用曾经有效的 contactRef 调用，owner service 返回不存在/无权限；断言返回统一不可枚举错误，候选历史不能让请求成功。
- [ ] **Step 3: 运行失败测试，再检查生产调用链。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactBriefProviderTest,ContactAssistantToolsTest,ContactTimelineServiceTest test
```

预期：测试必须实际覆盖 service 的授权调用顺序，不以只测 ref 解析替代 owner 授权断言。

- [ ] **Step 4: 仅修复审计证据证实的缺口。** 继续复用既有 owner service 和 SQL；不在助手工具里新增 `isAdmin`、团队成员或授权表谓词。保证简报仍只输出 `ContactBrief` 允许字段，通话和消息正文遵守各工具既有读取合同。
- [ ] **Step 5: 重跑 Task 2 专项测试并提交。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactBriefProviderTest,ContactAssistantToolsTest,ContactTimelineServiceTest test
```

commit message：`test(assistant): enforce owner checks for contact reads`。

---

### Task 3: 会话、消息与企业微信摘要读取权限回归

**Files:**
- Modify only if a failing authorization contract test proves a gap: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/ConversationCandidateProvider.java`
- Modify only if needed: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ConversationAssistantTools.java`
- Modify only if needed: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/MessageAssistantTools.java`
- Modify only if needed: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageQueryService.java`
- Modify only if needed: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComSummaryReadService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageQueryServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/MessageAssistantToolsTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/ConversationCandidateProviderTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComSummaryReadServiceTest.java`

**Interfaces:**
- Consumes: `ConversationMapper.listUnified(userId, ...)`, `MessageQueryService.getMessage(messageId, userId)`, `WeComSummaryReadService.read(userId, sourceConversationId, days)`。
- Produces: 会话候选不包含正文；消息原文只能由 `message.read` 对当前用户可见的单条消息返回；企微摘要按当前用户可访问群会话查询。

- [ ] **Step 1: 写越权与字段投影测试。** 断言会话搜索结果不含 `lastText`、provider key 和身份值；消息 owner 查询无结果且企微 fallback 不可访问时不能返回正文；企微摘要用另一用户 ID 时拒绝或空结果，按 owner service 既有合同断言。
- [ ] **Step 2: 先运行新增测试确认缺口是否真实。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=MessageQueryServiceTest,MessageAssistantToolsTest,ConversationCandidateProviderTest,WeComSummaryReadServiceTest test
```

预期：如果既有路径已满足授权，测试应通过，不做无必要重构；如果失败，记录失败查询具体缺少的授权谓词后再修。

- [ ] **Step 3: 如测试失败，修复在领域 owner。** `service.assistant.mcp` 只做错误转译和显式投影，不增加 Mapper 直查或平行权限 SQL。对“不存在/无权”统一用中性外部文案。
- [ ] **Step 4: 重跑本 Task 专项测试。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=MessageQueryServiceTest,MessageAssistantToolsTest,ConversationCandidateProviderTest,WeComSummaryReadServiceTest test
```

- [ ] **Step 5: 提交本 Task 的新增测试和确有必要的 owner 修复。** commit message：`test(assistant): verify scoped conversation and message reads`。

---

### Task 4: 记忆、待办、AI Topic 和出站目标 owner 回归

**Files:**
- Modify only on failing evidence: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactMemoryAssistantTools.java`
- Modify only on failing evidence: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/TodoAssistantTools.java`
- Modify only on failing evidence: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/AiTopicAssistantTools.java`
- Modify only on failing evidence: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/MessageSendAssistantTools.java`
- Modify only on failing evidence: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/OutboundMessageService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactMemoryAssistantToolsTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/TodoAssistantToolsTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/AiTopicAssistantToolsTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/mcp/MessageSendAssistantToolsTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/OutboundMessageServiceTest.java`

**Interfaces:**
- Consumes: 当前 `ContactMemoryRecomputeService`、`TodoItemService`、`AiTopicService`、`OutboundMessageService` 的 user-scoped 方法。
- Produces: 重算只允许联系人创建者；待办只允许 owner；Topic 只使用当前服务既有 owner 规则；发信参数不接受原始收件地址且服务端按当前用户解析渠道身份。

- [ ] **Step 1: 增加各 owner 的拒绝测试。** 记忆以非 creator 用户调用重算时不触发 worker；待办查询/修改他人 ID 返回 not-found 口径；Topic 读取/修改其他 owner ID 被拒绝；发送工具 schema 和处理器不接收 `to`、邮箱、手机号或渠道账号 ID，且 `OutboundMessageService` 使用调用用户做联系人/身份/账号解析。
- [ ] **Step 2: 运行专项测试确认状态。**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactMemoryAssistantToolsTest,TodoAssistantToolsTest,AiTopicAssistantToolsTest,MessageSendAssistantToolsTest,OutboundMessageServiceTest test
```

- [ ] **Step 3: 只修复存在的 owner 绕过。** 不为统一形式而改已经通过的领域合同；不降低用户确认策略，不改变消息实际发送目标来源。
- [ ] **Step 4: 重跑专项测试并提交。** commit message：`test(assistant): enforce owner isolation for write-adjacent tools`。

---

### Task 5: 隐私安全的助手审计与统一不可枚举授权错误

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/AssistantAuditService.java`
- Modify if schema-level change is required: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/AssistantActionAuditEntity.java` and assistant audit migration/mapper
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ToolExecutionException.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/ContactAssistantTools.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/assistant/mcp/MessageAssistantTools.java`
- Modify: other tool adapters only where they leak a different not-found/forbidden code or detail
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/assistant/AssistantAuditServiceTest.java`
- Test: all modified tool adapter tests

**Interfaces:**
- Consumes: `AssistantAuditService.Entry` and existing assistant audit table contract.
- Produces: persisted audit data does not store user utterance or raw argument values; tool authorization failures use one stable non-enumerating code/message while syntactically invalid references remain `INVALID_ARGUMENT`.

- [ ] **Step 1: Add failing audit redaction tests.** Given a send tool audit entry with a synthetic email and body, assert serialized audit parameters contain neither raw address nor body; given a user utterance with a synthetic phone number or access-token-shaped secret, assert the persisted utterance is absent and a bounded SHA-256 digest plus character count is retained. Keep action name, policy, outcome and canonical argument digest available for traceability; do not treat hashes of low-entropy values as anonymization.
- [ ] **Step 2: Add stable error contract tests.** For a syntactically valid but inaccessible contact/message/topic, assert error code is `FORBIDDEN_OR_NOT_FOUND`, response text does not reveal whether the row exists, and the underlying exception message/ID is absent. For malformed ref assert the existing `INVALID_ARGUMENT` code and a safe user-facing message.
- [ ] **Step 3: Inspect schema widths and API contracts before implementation.** If audit field semantics change but column types stay compatible, no migration is needed. If a new digest/redacted field is required, add a forward-only Flyway migration and SQL contract test; never rewrite installed migration history.
- [ ] **Step 4: Implement privacy-safe audit normalization before serialization.** Persist no utterance text; store only bounded character count and SHA-256 digest in an explicitly documented audit representation. Store argument key names with value type/omission markers plus the existing canonical digest; do not persist raw values, bodies, addresses, credentials or full prompt. Do not log raw values on audit failure.
- [ ] **Step 5: Implement common external authorization error mapping.** Keep domain exceptions intact internally; at tool adapter boundary map not-found/forbidden to the stable non-enumerating code/message. Do not map malformed input or provider outage to authorization failure.
- [ ] **Step 6: Run assistant audit and adapter tests.**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=AssistantAuditServiceTest,ContactAssistantToolsTest,MessageAssistantToolsTest,ContactTimelineAssistantToolsTest,ConversationAssistantToolsTest,MessageSendAssistantToolsTest,AiTopicAssistantToolsTest,TodoAssistantToolsTest test
```

Surefire selectors are exact test class names verified in the repository. Verify the reported test count is non-zero.

- [ ] **Step 7: Commit only the audit/error files, tests, and any required migration.** commit message: `fix(assistant): redact audit data and normalize access failures`。

---

### Task 6: 架构门禁禁止助手工具直接访问 Mapper

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/architecture/AssistantToolAuthorizationArchitectureTest.java`
- Modify only if rule integration is cleaner: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/architecture/ArchitectureBoundaryTest.java`
- Modify this plan/design only if code audit finds an intentional existing exception; exceptions require explicit owner justification and test scope.

**Interfaces:**
- Consumes: production package `com.crmforlogistics.messagecenter.service.assistant.mcp..` and Mapper package `com.crmforlogistics.messagecenter.mapper..`.
- Produces: a normal JUnit Jupiter `@Test` hard gate that fails if any production class in assistant MCP tools depends on a Mapper type; test classes are excluded from ArchUnit import.

- [ ] **Step 1: Add a direct dependency assertion.** Create the test file listed above. Import production classes with `ClassFileImporter` and `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`; assert `noClasses().that().resideInAPackage("com.crmforlogistics.messagecenter.service.assistant.mcp..").should().dependOnClassesThat().resideInAPackage("com.crmforlogistics.messagecenter.mapper..")`.
- [ ] **Step 2: Run the architecture test and confirm it is actually discovered.**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=AssistantToolAuthorizationArchitectureTest test
```

Expected: non-zero test count and pass. If current production tool classes violate the rule, move only the direct query into the corresponding owner service; do not whitelist it in ArchUnit.

- [ ] **Step 3: Add regression assertions for the tool projection contracts.** Extend existing tool tests to assert allowed key sets and ensure `userId`, raw address and unrequested message body are absent from every relevant projection.
- [ ] **Step 4: Run architecture and MCP registry tests.**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=AssistantToolAuthorizationArchitectureTest,ToolRegistryTest,AssistantActionPolicyTest test
```

- [ ] **Step 5: Commit architecture gate and tests.** commit message: `test(architecture): forbid mapper access from assistant tools`。

---

### Task 7: 文档回写与最终权限验收

**Files:**
- Modify: `docs/superpowers/specs/2026-09-28-assistant-authorization-boundary-design.md` (implementation status, verified owner map, deviations only)
- Modify: `docs/superpowers/README.md` (append implementation plan and final verification links)
- Create: `docs/superpowers/reviews/2026-09-28-assistant-authorization-boundary-verification.md`

**Interfaces:**
- Consumes: completed Tasks 1–6, test output, migration/SQL contract output, current git diff.
- Produces: evidence-backed verification record separating passed, failed, skipped and external/manual checks.

- [ ] **Step 1: Run the consolidated assistant authorization suite.**

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest=ContactServiceAuthorizationTest,ContactServicePhoneBindingTest,ContactBriefProviderTest,ContactAssistantToolsTest,ContactTimelineServiceTest,ContactTimelineAssistantToolsTest,MessageQueryServiceTest,MessageAssistantToolsTest,ConversationAssistantToolsTest,ConversationCandidateProviderTest,WeComSummaryReadServiceTest,ContactMemoryAssistantToolsTest,TodoAssistantToolsTest,AiTopicAssistantToolsTest,MessageSendAssistantToolsTest,OutboundMessageServiceTest,AssistantAuditServiceTest,AssistantToolAuthorizationArchitectureTest,ToolRegistryTest,AssistantActionPolicyTest test
```

The selector list above was checked against repository test sources; the two explicitly marked `Create` tests are added by Tasks 3 and 6. Never accept `Tests run: 0` as success.

- [ ] **Step 2: Run production compile and the integration boundary.**

```bash
cd demo/message-center-spring/backend
mvn -q -DskipTests compile
mvn -q -Dtest=AppIntegrationTest test
```

Expected: production compile succeeds; Spring integration test has no failure and no unreviewed skip. If Docker is unavailable, capture the exact skip reason and run the repository's non-Docker Spring context test that exercises `AssistantController` and the tool registry.

- [ ] **Step 3: Run diff and migration review.**

```bash
git diff --check
git status --short
git diff --stat
```

Inspect staged and unstaged paths; do not stage unrelated user WIP or use `git add .`.

- [ ] **Step 4: Write verification record and update index.** Include exact command/output summaries, pass/fail/skip counts, any migration version, risks not closed, and external checks not run. Keep only authorization-boundary scope.
- [ ] **Step 5: Commit documentation separately.** Stage only the three documented paths; commit message: `docs: record assistant authorization verification`.

---

## 计划自审

- 设计 §2 范围与非目标：Tasks 1–7 覆盖，不新增权限、不引入 RLS、不改数据保留。
- 设计 §3 当前风险：Task 1 给助手新增独立授权渠道投影，普通联系人 API 的旧可见语义留给独立审计。
- 设计 §4 身份、owner、候选非授权、投影和最小字段：Tasks 2–6 覆盖。
- 设计 §5 所有助手域 owner：Tasks 2–4 分别覆盖联系人/记忆/电话、会话/消息/企微、待办/Topic/出站。
- 设计 §6 错误与引用重验：Tasks 2–5 覆盖；错误码兼容冲突不得静默决定，需先核对前端和 API 合同。
- 设计 §7 隐私与审计：Task 5 覆盖；当前审计存原话和原始参数的事实被列为需先修风险。
- 设计 §8 权限矩阵和架构门禁：Tasks 1–6 覆盖本人、共享规则、管理员、撤销、删除/合并、投影键和直接 Mapper 依赖。
- 设计 §9 风险：Task 3/4 只按失败证据改动已有 owner 路径，避免重写已正确合同。
- 占位符扫描：测试类路径均已对照现有源码核对；`ConversationCandidateProviderTest` 和 `AssistantToolAuthorizationArchitectureTest` 是明确的新建文件。
- 错误码合同：无效引用沿用 `INVALID_ARGUMENT`；仅不可访问或不存在资源使用新增的 `FORBIDDEN_OR_NOT_FOUND`。不得把 provider、校验或状态错误改映射成授权错误。
- 审计合同：原话与参数原始值不落库；保留结构化操作元数据和参数摘要，摘要不能被描述为匿名化保证。

## 停止条件

- 若现有代码、已确认设计与真实权限合同冲突，暂停该 Task，只报告具体 SQL/服务/测试证据和推荐取舍。
- 若发现要删除公开 API、改写既有迁移、改变团队/管理员权限或接受无意义兼容，停止等待用户确认。
- 若专项测试失败，先确认可复现调用链与差异后再修改；不得因“长期权限”目标顺手扩大到无关入口。
- 每个 Task 结束时汇报精确提交、专项命令结果和剩余风险；最终 Task 才执行合并权限回归。
