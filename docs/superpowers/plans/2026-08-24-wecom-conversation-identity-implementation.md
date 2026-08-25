# 企业微信会话身份统一实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业微信成员单聊、外部联系人单聊、客户群和企业内部群统一落入可审计的源会话合同，并让成员资料、企业名称和官方会话展示组件使用同一安装实例身份链路。

**Architecture:** `ResolvedInstallation -> WeComAccessTokenService` 是全部企业微信运行时 API 的唯一凭证入口。ChatData 先规范化为参与者、源会话、参与关系和消息引用，再由投影层生成 CRM 通用会话；HTTP/UI 只消费投影结果。客户群使用 `externalcontact/groupchat/list|get`，企业内部群使用 ChatData `get_group_chat`，禁止用客户群接口伪造内部群。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis-Plus、PostgreSQL/Flyway、React 18、TypeScript、Vite、Vitest、企业微信代开发安装实例 API、`@wecom/jssdk` 2.3.4。

## Global Constraints

- 运行时 token 只能由 `WeComAccessTokenService.accessToken(ResolvedInstallation)` 获取，禁止 `get_corp_token`、第三方登录接口和全局 secret 回退。
- `/cgi-bin/user/list_id` 永久禁止；成员发现使用 `department/list -> user/simplelist -> user/get`。
- 客户群只使用 `externalcontact/groupchat/list|get`；企业内部群只使用 ChatData `get_group_chat`。
- 每条上游消息必须成功落库或形成持久化失败事实；失败时不得推进游标。
- `GROUP` 会话不得伪造 `contact_identity_id`，普通成员只能查看自己参与的会话。
- 企业微信正文继续由官方会话展示组件按权限展示，CRM 只保存消息引用和受保护的 `secretKey`。
- 不手改 `dist` 或其他生成物；前端生成物只能通过 `npm run build` 产生。
- 任一旧凭证链路回归、游标提前推进、静默丢消息、越权或 viewer 空白都属于阻断问题。

---

### Task 1: 建立源会话与参与者数据库合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V23__wecom_conversation_identity.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ConversationEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComPartyEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComSourceConversationEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComSourceParticipantEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/WeComChatDataIngestFailureEntity.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComConversationSchemaContractTest.java`

**Interfaces:**
- `wecom_parties` 唯一键为 `(installation_id, party_type, provider_party_id)`，资料状态为 `READY|PARTIAL|DEGRADED|PENDING`。
- `wecom_source_conversations` 以 `(installation_id, provider_conversation_key)` 唯一标识 `DIRECT|GROUP` 会话；群聊的 `contact_identity_id` 必须为空。
- `wecom_source_conversation_participants` 以 `(source_conversation_id, party_id)` 为主键，保存 `OBSERVED|LEFT` 状态和首次/最后观察时间。
- `wecom_chatdata_messages` 改为以 `(installation_id, msgid)` 幂等，引用 `source_conversation_id` 和 `sender_party_id`，不再以 `external_userid` 作为 owner。
- `wecom_chatdata_ingest_failures` 保存安装、游标范围、受限 msgid 摘要、失败阶段、错误码、重试次数和解决时间，不保存正文或明文 secret。

- [x] **Step 1: 编写迁移和实体合同测试**。测试断言群会话允许空 `contact_identity_id`、消息幂等键包含安装 ID、失败表不包含正文列。
- [x] **Step 2: 运行失败测试**。先确认合同缺失会失败，再进入实现。
- [x] **Step 3: 编写 `V23__wecom_conversation_identity.sql`**。迁移使用显式约束和索引，不删除历史数据；旧字段保留兼容列。
- [x] **Step 4: 更新实体和 MyBatis 映射**，新源会话、参与者、失败事实和消息安装字段可读写。
- [x] **Step 5: 重新运行专项测试**。`WeComConversationSchemaContractTest` 已通过。
- [ ] **Step 6: 提交**：`git add demo/message-center-spring/backend/src/main/resources/db/migration/V23__wecom_conversation_identity.sql demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComConversationSchemaContractTest.java && git commit -m "feat: add wecom conversation identity schema"`。

### Task 2: 收紧代开发 token 和 API 准入边界

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComViewerHttpGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/RestWeComViewerHttpGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComApiClient.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComAccessTokenServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerCredentialBoundaryTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComApiAuditTrailTest.java`

**Interfaces:**
- 只保留 `fetchCorpJsapiTicket(ResolvedInstallation)`、`fetchAgentJsapiTicket(ResolvedInstallation)` 和 `exchangeLoginIdentity(ResolvedInstallation, code)`。
- `WeComApiClient` 的白名单测试必须覆盖 `/cgi-bin/message/send`、appchat、部门、标签、客户备注和客户群路径，并断言 token provider 收到同一 `ResolvedInstallation`。

- [x] **Step 1: 增加失败边界测试**，无安装参数的 ticket 请求被拒绝。
- [x] **Step 2: 运行专项 viewer 网关测试**，确认边界测试先红后绿。
- [x] **Step 3: 移除全局 `corpAccessToken()` 实现**，无安装入口不再读取全局 corp secret。
- [x] **Step 4: 补齐 API 路径准入断言**，客户群路径允许，`user/list_id` 拒绝。
- [x] **Step 5: 运行专项测试和静态扫描**：生产代码未发现 `get_corp_token`、`user/list_id` 或无安装实例 ticket 调用。
- [ ] **Step 6: 提交**：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom && git commit -m "fix: enforce wecom installation credential boundary"`。

### Task 3: 规范化 ChatData 消息、群聊和失败事务

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataMessageEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataNormalizer.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataNormalizerTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreTest.java`

**Interfaces:**
- `WeComChatDataNormalizer.normalize(ResolvedInstallation installation, RawChatDataMessage rawMessage)` 返回 `NormalizedWeComMessage`，包含 sender party、receiver parties、`DIRECT|GROUP`、稳定来源键和消息引用；`RawChatDataMessage` 至少包含 `msgid/sender/receiver_list/chatid/send_time/msgtype/service_encrypt_info`。
- 有 `chatid` 时始终解析为 `GROUP`；无 `chatid` 且只有一个接收者时按双方 party 规范排序生成 `DIRECT` 来源键；其他输入进入失败表。
- `publishPage` 的顺序固定为规范化、参与者 upsert、源会话 upsert、消息幂等写入、CRM 投影、失败事实写入、最后推进游标。

- [x] **Step 1: 编写成员单聊、内部群聊、缺 receiver 和重复 msgid 的失败测试**。
- [x] **Step 2: 运行专项测试，确认旧 `skipped` 语义的缺口并锁定回归。**
- [x] **Step 3: 实现规范化器和持久化失败记录**，返回 `stored|direct|group|duplicate|failed` 计数，同时保留旧字段兼容读取。
- [x] **Step 4: 将游标更新放到失败事实写入之后**，失败事实无法写入时抛错并阻止游标更新。
- [x] **Step 5: 运行专项测试并检查游标未提前推进**。
- [ ] **Step 6: 提交**：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataGateway.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComChatDataMessageEntity.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComChatDataMessageMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataNormalizerTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStoreTest.java && git commit -m "feat: normalize wecom chatdata conversations"`。

### Task 4: 接通成员、客户群和企业资料同步

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDirectoryService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComExternalContactService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComPartyProfileService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComPartyProfileServiceTest.java`

**Interfaces:**
- 成员同步按 `department/list -> user/simplelist -> user/get` 执行；不得新增 `user/list_id`。
- 客户群同步调用已准入的 `groupList/groupGet`；企业内部群资料只由 ChatData `get_group_chat` 进入源会话。
- 授权安装保存 `auth_corp_info.corp_name`；成员 party 资料更新 `display_name/avatar_url/profile_status/profile_error_code`。

- [x] **Step 1: 编写资料状态测试**：姓名/头像资料投影为 `READY|PARTIAL`，权限异常为 `DEGRADED`。
- [x] **Step 2: 运行专项测试确认当前 projector 仍把 userid 当显示名**。
- [x] **Step 3: 实现 `WeComPartyProfileService`**，使用现有 gateway 超时边界，错误只保存结构化错误码。
- [x] **Step 4: 将 corp name、party profile 和 ChatData 群源会话接入源事实表与 CRM 投影。**
- [x] **Step 5: 运行资料、投影和 ChatData 专项测试。**
- [ ] **Step 6: 提交**：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComAuthorizationGateway.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom && git commit -m "feat: sync wecom party profiles"`。

### Task 5: 更新 CRM 会话投影和访问合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageQueryServiceTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorGroupTest.java`

**Interfaces:**
- `GROUP` 投影返回 `provider_conversation_key/display_name/avatar_url/participants`，`contact_identity_id` 为空。
- `DIRECT` 成员单聊使用双方 party 来源键，不再要求 external party；方向由查看者和 sender party 关系计算，内部消息使用 `participant` 语义。
- 普通用户查询必须过滤为本人参与会话；审计权限查询必须写入审计记录。

- [x] **Step 1: 编写群会话投影测试**，群消息不创建 `contact_identity`。
- [x] **Step 2:** 迁移允许 `contact_identity_id` 为空并绑定 `source_conversation_id`。
- [x] **Step 3:** 更新 mapper 查询、排序、未读和成员单聊投影，禁止 UI 通过 userid 拼接名称。
- [x] **Step 4:** 运行 `WeComMessageProjectorTest,WeComConversationSchemaContractTest`。
- [ ] **Step 5: 提交**：`git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageQueryServiceTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorGroupTest.java && git commit -m "feat: project wecom group conversations"`。

### Task 6: 修复 viewer session 生命周期和安装参数

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComController.java`
- Modify: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts`
- Modify: `demo/message-center-spring/frontend/src/wecom/WeComFrameRegistry.ts`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Create: `demo/message-center-spring/frontend/src/hooks/useWeComViewer.test.ts`
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.test.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`

**Interfaces:**
- 同一 viewer token 允许有限数量未消费 session；新 session 不删除其他 session。
- 联系人切换销毁旧 frame，再按新的 `contactPointId/messageIds` 创建 frame；非企业微信渠道返回时恢复原滚动位置。
- 单独企业微信联系人使用完整时间轴区域，保留“在企业微信中打开”按钮，不显示 ChatApp 输入框。

- [x] **Step 1:** 编写整页容器、联系人渠道切换、错误重试和滚动恢复相关测试。
- [x] **Step 2:** 运行 viewer、整页容器和线程切换专项 UI 测试。
- [x] **Step 3:** frame registry 和 viewer hook 保持 SDK 单次初始化、按联系人独立 session 和清理旧 frame。
- [x] **Step 4:** panel/layout 使用 `flex: 1 1 auto; min-height: 0; width: 100%` 铺满时间轴，不嵌套输入框。
- [x] **Step 5:** `npm run test:source`、专项 `npm run test:ui` 和 `npm run build` 已通过。
- [ ] **Step 6: 提交**：`git add demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts demo/message-center-spring/frontend/src/wecom/WeComFrameRegistry.ts demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx demo/message-center-spring/frontend/src/pages/ThreadPage.tsx demo/message-center-spring/frontend/src/hooks/useWeComViewer.test.ts demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.test.tsx demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx && git commit -m "fix: isolate wecom viewer sessions by conversation"`。

### Task 7: 历史回补、接口门禁和实机验收

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComConversationBackfillService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComConversationBackfillServiceTest.java`
- Modify: `docs/superpowers/specs/2026-08-24-wecom-conversation-identity-design.md`
- Create: `docs/superpowers/reviews/2026-08-24-wecom-conversation-identity-readiness.md`

**Interfaces:**
- 回补使用独立游标，不修改在线增量游标；结果报告 `stored/direct/group/duplicate/failed`。
- 静态门禁扫描生产源码的所有 `/cgi-bin/` 路径；每条路径必须存在于设计白名单，`user/list_id` 必须不存在于生产调用。
- 实机验收只记录脱敏 `errcode/traceId/path`，不记录 token、secret、ticket 或正文。

- [x] **Step 1: 编写回补幂等和 5 天保留窗口测试**。
- [x] **Step 2:** `WeComConversationBackfillServiceTest` 已通过。
- [x] **Step 3:** 实现 dry-run、用户确认、限页、限时和独立游标编排上界。
- [ ] **Step 4:** 运行后端全量测试：`cd demo/message-center-spring/backend && mvn test`。
- [ ] **Step 5:** 运行前端全量门禁：`cd demo/message-center-spring/frontend && npm test && npm run build`。
- [x] **Step 6:** 执行静态 API 对账，禁止路径扫描通过。
- [x] **Step 6a:** 增加 `scripts/wecom-server-acceptance.sh` 服务器只读验收脚本。
- [ ] **Step 7:** 通过一个已授权安装执行最小实机调用：成员列表、成员详情、客户群列表/详情、应用消息、ChatData 内部群资料、JSAPI config；每个调用保存脱敏审计结果。
- [ ] **Step 8:** 仅在所有门禁通过后生成 jar 和前端 dist；记录 SHA-256、测试结果、数据库迁移版本和回滚备份路径。

## 发布停止条件

出现以下任一情况立即停止，不通过 fallback 绕过：安装实例 token 不是 `/cgi-bin/gettoken` 生成；`user/list_id` 被调用；客户群接口返回权限错误但系统把它当成空列表；成员/群消息被计为 `skipped` 且游标推进；群会话生成虚假联系人；viewer 切换出现空白或跨联系人内容；任一密钥或正文进入日志。
