# 用户私有渠道通讯录实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 WhatsApp（持久层规范值 `chatapp`）、邮件和电话建立按登录用户隔离的通讯录、渠道账号、消息时间轴和权限边界。

**Architecture:** 采用显式 `owner_user_id` 模型。渠道账号、联系人、电话记录和标签的 owner 由服务端认证上下文或后台处理的渠道账号确定；联系人应用服务负责身份建档、备注、标签、合并、拆分和删除门禁。现有企业微信绑定、授权、通讯录和会话合同保持不变。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring Security、MyBatis-Plus、PostgreSQL/Flyway、MinIO、Jakarta Mail、React 18、TypeScript、Ant Design、TanStack Query、Vitest。

## Global Constraints

- 产品界面使用“WhatsApp”，数据库/API 继续使用已有规范值 `chatapp`。
- 每个用户最多一个未解绑的 `chatapp` 账号和一个未解绑的 `email` 账号；业务页面不显示账号选择器。
- 电话不配置渠道账号、不提供拨号，只展示电话时间轴。
- 管理员只管理用户角色，不读取其他用户联系人、消息、附件、Topic、电话或渠道密钥。
- 新数据 owner 必须由服务端认证主体或后台渠道账号确定，不能接受请求体 owner。
- 相同邮箱/号码不跨用户或跨渠道自动合并；联系人合并仅限同一 owner。
- 仅 `manual` 且无消息、会话、通话、附件、Topic 或其他引用的联系人允许物理删除；不增加隐藏状态。
- 无法可靠归属的历史数据保留空 owner，但不进入任何业务 API，也不启动后续同步。
- 所有分页、搜索、附件、批处理、重试和外部同步都有明确上界；单账号或单消息失败不能阻塞其他任务。

---

### Task 1: Owner Schema 与迁移合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V47__user_channel_address_book_owner.sql`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/CallRecordEntity.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/UserChannelOwnerSchemaContractTest.java`

**Interfaces:**
- Produces `channel_accounts.owner_user_id`, `contacts.owner_user_id`, `call_records.owner_user_id/contact_id`, `contact_tags.owner_user_id`。
- Produces mapper methods `listByOwner(UUID, ...)`, `findByIdAndOwner(UUID, UUID)`, `findByOwnerAndScope(...)`, `countActiveByOwnerAndChannel(...)`。

- [ ] **Step 1: 写失败 schema 合同测试**

  读取 V47 SQL 和实体源码，断言 owner 外键、索引、唯一账号条件、标签 owner 唯一约束、电话字段和历史空值策略存在。运行：

  ```bash
  cd demo/message-center-spring/backend
  mvn -Dtest=UserChannelOwnerSchemaContractTest test
  ```

  预期：迁移和字段尚不存在，测试失败。

- [ ] **Step 2: 添加 V47 迁移**

  为四张表增加可空 UUID owner 字段和外键；为 `call_records` 增加 `contact_id`；创建 owner 查询索引；创建按 `(owner_user_id, channel_type)` 且只覆盖未解绑账号的唯一索引；将 `contact_tags` 唯一索引改为 owner 内唯一。迁移不得删除 `created_by`。

- [ ] **Step 3: 更新实体与 mapper SQL**

  实体增加字段和 getter/setter。所有新增 SQL 直接带 `owner_user_id = #{ownerId}`；禁止新增“先全库查询再 Java 过滤”的 mapper。为联系人身份查询增加作用域参数，为电话仓库增加 owner 与 contact 过滤。

- [ ] **Step 4: 运行 schema 合同测试**

  重跑上面的 Maven 命令，预期通过且没有编译 warning。

- [ ] **Step 5: 提交 Task 1**

  ```bash
  git add demo/message-center-spring/backend/src/main/resources/db/migration/V47__user_channel_address_book_owner.sql demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/UserChannelOwnerSchemaContractTest.java
  git commit -m "feat: add user ownership for channel address books"
  ```

### Task 2: Owner Context 与渠道账号自助管理

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChannelSettingsController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/CreateChannelAccountRequest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAccountSummary.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountOwnerIsolationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChannelAccountControllerOwnerSecurityTest.java`

**Interfaces:**
- `ChannelAccountService.list(UUID ownerId)`、`requireOwned(UUID ownerId, UUID accountId)`、`createOrBind(UUID ownerId, ...)`、`unbind(UUID ownerId, UUID accountId)`。
- `POST /api/channel-accounts` 创建当前用户账号；`POST /api/channel-accounts/{id}/unbind` 解绑；现有读取、更新、凭证和同步接口全部限定当前用户。
- 现有企业微信绑定/解绑接口与模型不改；本任务只收紧已有 `channel_accounts` 路径中的 `chatapp/email`。

- [ ] **Step 1: 写 owner 隔离和唯一账号失败测试**

  覆盖用户 A 不能读/改用户 B 账号、同 owner 第二个有效 chatapp/email 被拒绝、解绑后可新建、已解绑账号仍能读历史状态但不能同步/发送。

- [ ] **Step 2: 实现服务层 owner 校验**

  所有 `selectById` 改为 `findByIdAndOwner`；`list` 接受当前用户 UUID；创建和重新绑定在事务内检查唯一索引；凭证读取只返回脱敏值。

- [ ] **Step 3: 实现账号生命周期**

  使用受验证 DTO 和渠道专属配置字段白名单补齐创建/绑定/解绑接口。未产生消息的账号可停用；已有消息账号只允许解绑/停用。相同上游标识恢复原账号，不同标识创建新账号，不迁移旧联系人和消息。错误映射为 `CHANNEL_ACCOUNT_ALREADY_EXISTS`、`CHANNEL_ACCOUNT_INACTIVE`、`RESOURCE_NOT_FOUND`。

- [ ] **Step 4: 修改 Spring Security 边界**

  将 `/api/channel-accounts/**` 从管理员专属改为已登录用户；企业微信现有授权路径保持当前规则。前端权限不是安全边界，每个 controller 操作都必须把 `SecurityUtil.currentUserId()` 下传给 service。

- [ ] **Step 5: 运行专项测试并提交**

  ```bash
  mvn -Dtest='ChannelAccountOwnerIsolationTest,ChannelAccountControllerOwnerSecurityTest' test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChannelSettingsController.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/CreateChannelAccountRequest.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAccountSummary.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountOwnerIsolationTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChannelAccountControllerOwnerSecurityTest.java
  git commit -m "feat: isolate self-managed channel accounts by user"
  ```

### Task 3: 联系人应用服务与通讯录 API

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/CreateChannelContactRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookItem.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookPageResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactIdentityMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookServiceTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChannelAddressBookControllerTest.java`

**Interfaces:**
- `page(UUID ownerId, String channelType, String query, int page, int size)`。
- `createManual(UUID ownerId, CreateChannelContactRequest)`。
- `deleteManual(UUID ownerId, UUID contactId)`。
- `resolveOrCreateInbound(UUID ownerId, String channelType, UUID accountId, String address, String displayName)`。

- [ ] **Step 1: 写列表/建档/删除失败测试**

  覆盖分页上界、备注/昵称/地址搜索、最近联系排序、空消息人工联系人创建、重复地址冲突、跨 owner 不可见、有记录联系人删除拒绝。

- [ ] **Step 2: 实现标准化身份作用域**

  `chatapp/email` 使用 `channelAccountId` scope；电话使用 owner UUID scope；手动条目来源写入 `manual`。未配置账号时使用当前用户专属 pending scope，配置成功后事务迁移并解决唯一冲突。

- [ ] **Step 3: 实现通讯录投影**

  投影备注优先，其次渠道昵称/邮件显示名，最后真实邮箱或号码；返回其他已合并渠道类型、来源、最近联系时间、是否有活动和 `canDelete`。owner 过滤必须位于 SQL。

- [ ] **Step 4: 实现人工删除门禁**

  事务内检查消息、会话、通话、附件、Topic 和其他引用；存在任一记录抛出 `CONTACT_HAS_ACTIVITY`，不设置隐藏状态。

- [ ] **Step 5: 运行测试并提交**

  ```bash
  mvn -Dtest='ChannelAddressBookServiceTest,ChannelAddressBookControllerTest' test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/CreateChannelContactRequest.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookItem.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookPageResponse.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookServiceTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChannelAddressBookControllerTest.java
  git commit -m "feat: add owner-scoped channel address book API"
  ```

### Task 4: WhatsApp 入站/出站按 owner 建档

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolver.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOwnerProjectionTest.java`

**Interfaces:**
- 所有后台投影使用 `channelAccountId -> ownerUserId`；出站服务使用 `currentUserId` 解析唯一有效账号。
- `ChannelAddressBookService.resolveOrCreateInbound(owner, "chatapp", accountId, address, displayName)` 是唯一建档入口。

- [ ] **Step 1: 写跨用户隔离测试**

  同一号码经 A/B 账号入站产生两份联系人；A 的 webhook、消息和附件不能出现在 B；出站首次发送失败不创建成功消息。

- [ ] **Step 2: 改造 webhook 与轮询投影**

  从渠道账号读取 owner，拒绝 owner 为空账号；通过应用服务建档，保留渠道昵称更新但不覆盖备注；消息、会话和附件查询带 owner。

- [ ] **Step 3: 改造发送与模板路径**

  发送只解析当前用户唯一账号；模板、群发和媒体操作使用 `requireOwned`。将现有 WhatsApp 模板与媒体路径从管理员专属改为已登录用户，并以 owner 校验作为授权真相；群发仍保留其已有角色门禁且额外校验账号 owner。发送成功后再写真实会话和消息。

- [ ] **Step 4: 运行测试并提交**

  ```bash
  mvn -Dtest=ChatAppOwnerProjectionTest test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppOwnerProjectionTest.java
  git commit -m "feat: scope WhatsApp projection by account owner"
  ```

### Task 5: 邮件逐账号同步、发送与附件归属

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSendService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncScheduler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailOwnerIsolationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email/EmailSyncSchedulerOwnerTest.java`

**Interfaces:**
- `EmailSyncService.receiveLatest(UUID accountId, UUID ownerId)`。
- `EmailSendService.send(UUID ownerId, EmailSendCommand)`；禁止无 owner 的全局 `resolveEmailAccount()`。

- [ ] **Step 1: 写邮件 owner 与 scope 失败测试**

  覆盖 scheduler 逐个有效账号调用、同一邮箱在不同账号产生独立 identity、收信/发信消息带正确 owner、附件归属正确、单账号失败继续下一个账号。

- [ ] **Step 2: 改造同步配置解析**

  从 `channel_accounts` 解密对应账号配置；同步方法必须接收账号和 owner；`findDuplicate`、identity、conversation、message、attachment 全部按账号作用域处理。

- [ ] **Step 3: 改造发送链路**

  当前用户只解析唯一有效 email 账号；人工联系人首次发送创建 identity，SMTP 成功后持久化消息；失败保留可诊断状态，不伪造成功消息。

- [ ] **Step 4: 改造 scheduler 与手动同步 API**

  scheduler 查询全部有 owner 的有效邮件账号，逐账号隔离异常；手动同步接口校验当前用户拥有账号，管理员不能指定他人账号。

- [ ] **Step 5: 运行专项测试并提交**

  ```bash
  mvn -Dtest='EmailOwnerIsolationTest,EmailSyncSchedulerOwnerTest' test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/email demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/channel/ChannelAccountService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/email
  git commit -m "feat: sync email per owned account"
  ```

### Task 6: 电话 owner、电话通讯录与只读时间轴

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/ContactTimelineService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordOwnerIsolationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/CallRecordControllerOwnerSecurityTest.java`

**Interfaces:**
- `CallRecordService.create(UUID ownerId, CreateCallRecordCommand, InputStream)`。
- `CallRecordService.detail(UUID ownerId, UUID callRecordId)`。
- `CallRecordService.phoneRepository(UUID ownerId, query, cursor, limit)`。

- [ ] **Step 1: 写电话隔离测试**

  覆盖 `created_by` 无法单独授权、owner/contact 绑定、录音/转写/详情跨用户拒绝、电话仓库只返回当前用户。

- [ ] **Step 2: 实现 owner 写入与查询**

  创建记录从认证用户写 `owner_user_id/contact_id`；详情、重试、转写、备注、音频 session、音频流均使用 owner 版本的 service 方法。

- [ ] **Step 3: 接入电话通讯录与时间轴**

  电话身份按 owner + 标准化号码建档；时间轴只展示记录，不生成拨号或发送命令。

- [ ] **Step 4: 运行测试并提交**

  ```bash
  mvn -Dtest='CallRecordOwnerIsolationTest,CallRecordControllerOwnerSecurityTest' test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/CallRecordController.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/CallRecordControllerOwnerSecurityTest.java
  git commit -m "feat: isolate phone records by owner"
  ```

### Task 7: 标签、消息、附件与 Topic 全链路 owner 收紧

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageQueryService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicInputService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactOwnerTagIsolationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageOwnerIsolationTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicOwnerIsolationTest.java`

**Interfaces:**
- 所有读取接口接受 `ownerId` 或通过已验证联系人/账号派生 owner；不存在 `admin OR` 旁路。

- [ ] **Step 1: 写跨领域越权测试**

  覆盖标签列表/绑定、会话、消息、附件、Topic 查询和来源记录在跨 owner 时均返回 `RESOURCE_NOT_FOUND`。

- [ ] **Step 2: 收紧标签与联系人合并拆分**

  标签 owner 内唯一；合并/拆分先验证两个联系人同 owner，再调用现有 Topic 协调流程；跨 owner 不写任何数据。

- [ ] **Step 3: 收紧消息、附件与 Topic**

  repository 查询从 owner 条件起步；发送、详情、附件下载和 Topic 输入不接受仅凭 ID 的全库查询。

- [ ] **Step 4: 运行测试并提交**

  ```bash
  mvn -Dtest='ContactOwnerTagIsolationTest,MessageOwnerIsolationTest,AiTopicOwnerIsolationTest' test
  git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service
  git commit -m "feat: enforce owner isolation across messages and topics"
  ```

### Task 8: 历史数据迁移与对账命令

**Files:**
- Create: `demo/message-center-spring/backend/src/main/resources/db/migration/V48__backfill_user_channel_owners.sql`
- Create: `demo/message-center-spring/scripts/backfill-user-channel-owners.sql`
- Create: `demo/message-center-spring/scripts/verify-user-channel-owners.sql`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/UserChannelOwnerBackfillSchemaContractTest.java`
- Modify: `demo/message-center-spring/README.md`

**Interfaces:**
- 迁移输出账号、联系人、身份、会话、消息、电话和标签的总数/归属数/拆分数/冲突数/未归属数。
- `verify-user-channel-owners.sql` 只输出聚合计数和 digest，不输出业务正文或密钥。

- [ ] **Step 1: 写迁移 SQL 合同测试**

  断言迁移只按可审计账号 owner、消息账号和有效 `created_by` 回填；无法判断保持 NULL；旧邮件 scope 被按账号拆分；不根据昵称猜 owner。

- [ ] **Step 2: 实现可重复迁移**

  使用临时映射表和唯一键保护重复执行；对跨 owner 共用旧联系人复制联系人与 identity，按账号重连会话/消息；电话按 owner + 标准号码关联；标签按 owner 复制。

- [ ] **Step 3: 实现对账 SQL**

  输出迁移前后每类计数、空 owner 计数、重复 scope、跨 owner 引用和孤儿外键；SQL 不修改数据。

- [ ] **Step 4: 运行迁移合同测试并提交**

  ```bash
  mvn -Dtest=UserChannelOwnerBackfillSchemaContractTest test
  git add demo/message-center-spring/backend/src/main/resources/db/migration/V48__backfill_user_channel_owners.sql demo/message-center-spring/scripts/backfill-user-channel-owners.sql demo/message-center-spring/scripts/verify-user-channel-owners.sql demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/UserChannelOwnerBackfillSchemaContractTest.java demo/message-center-spring/README.md
  git commit -m "feat: backfill channel address book ownership"
  ```

### Task 9: 前端通讯录、导航与空时间轴

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useContacts.ts`
- Modify: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ConversationWorkspace.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/PhoneRepositoryPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.tsx`
- Create: `demo/message-center-spring/frontend/src/components/ChannelAddressBookForm.tsx`
- Create: `demo/message-center-spring/frontend/src/components/ChannelAddressBookList.tsx`
- Modify: `demo/message-center-spring/frontend/src/router.tsx`
- Test: `demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.test.tsx`
- Test: `demo/message-center-spring/frontend/src/components/ChannelAddressBookForm.test.tsx`
- Test: `demo/message-center-spring/frontend/src/components/AppLayout.channel-address-books.test.tsx`

**Interfaces:**
- `fetchChannelAddressBook(channel, params)`、`createManualChannelContact(channel, payload)`、`deleteManualChannelContact(contactId)`。
- 路由 `/address-book/chatapp`、`/address-book/email`、`/address-book/phone`；时间轴使用 `/conversations/contact/:contactId?channel=:channel&identityId=:identityId` 携带明确身份。

- [ ] **Step 1: 写页面失败测试**

  覆盖三个导航入口、搜索/分页、排序、展示名称和其他渠道徽标、人工新增字段、删除边界、点击跳转和电话无发送控件。

- [ ] **Step 2: 接入 API 类型和查询 hooks**

  使用 TanStack Query，query key 必须包含当前渠道和搜索参数；创建/删除后只失效当前用户对应的通讯录 query。

- [ ] **Step 3: 实现通讯录页面与表单**

  WhatsApp 表单只填号码，邮件只填邮箱，电话只填号码；无账号时允许保存但禁用发送并显示配置入口；展示备注优先和 `【其他渠道】` 徽标。

- [ ] **Step 4: 接入导航和点击跳转**

  WhatsApp 下拉增加通讯录，邮件按钮增加通讯录，电话下拉增加通讯录；点击邮件/WhatsApp 进入对应时间轴并预填发送区，电话只进入只读时间轴。

- [ ] **Step 5: 运行前端专项测试并提交**

  ```bash
  npm run test:ui -- src/pages/ChannelAddressBookPage.test.tsx src/components/ChannelAddressBookForm.test.tsx src/components/AppLayout.channel-address-books.test.tsx
  git add demo/message-center-spring/frontend/src/api demo/message-center-spring/frontend/src/hooks/useContacts.ts demo/message-center-spring/frontend/src/components/AppLayout.tsx demo/message-center-spring/frontend/src/pages/ChannelSettingsPage.tsx demo/message-center-spring/frontend/src/pages/ConversationWorkspace.tsx demo/message-center-spring/frontend/src/pages/PhoneRepositoryPage.tsx demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.tsx demo/message-center-spring/frontend/src/components/ChannelAddressBookForm.tsx demo/message-center-spring/frontend/src/components/ChannelAddressBookList.tsx demo/message-center-spring/frontend/src/router.tsx demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.test.tsx demo/message-center-spring/frontend/src/components/ChannelAddressBookForm.test.tsx demo/message-center-spring/frontend/src/components/AppLayout.channel-address-books.test.tsx
  git commit -m "feat: add channel address book pages"
  ```

### Task 10: 全量门禁、浏览器验收与发布制品

**Files:**
- Modify: `docs/superpowers/README.md`
- Create: `docs/superpowers/reviews/2026-09-04-user-channel-address-books-verification.md`
- Output: `demo/message-center-spring/backend/target/message-center.jar`
- Output: `demo/message-center-spring/frontend-dist-20260904-user-channel-address-books-r1.zip`

**Interfaces:**
- 产出迁移对账、测试结果、浏览器验收截图/记录、Jar/ZIP 路径和 SHA-256。

- [ ] **Step 1: 执行后端专项和全量测试**

  ```bash
  cd demo/message-center-spring/backend
  mvn -Dtest='UserChannelOwnerSchemaContractTest,ChannelAccountOwnerIsolationTest,ChannelAddressBookServiceTest,ChatAppOwnerProjectionTest,EmailOwnerIsolationTest,CallRecordOwnerIsolationTest,ContactOwnerTagIsolationTest,MessageOwnerIsolationTest,AiTopicOwnerIsolationTest,UserChannelOwnerBackfillSchemaContractTest' test
  mvn test
  ```

  记录通过数、失败数、warning 和需要服务器验证的项目。

- [ ] **Step 2: 执行前端专项和全量测试**

  ```bash
  cd demo/message-center-spring/frontend
  npm run test:ui -- src/pages/ChannelAddressBookPage.test.tsx src/components/ChannelAddressBookForm.test.tsx src/components/AppLayout.channel-address-books.test.tsx
  npm test
  ```

- [ ] **Step 3: 构建并校验制品**

  ```bash
  npm run build
  zip -qr ../frontend-dist-20260904-user-channel-address-books-r1.zip dist
  unzip -t ../frontend-dist-20260904-user-channel-address-books-r1.zip
  cd ../backend
  mvn -Pproduction clean package
  test -f target/message-center.jar
  jar tf target/message-center.jar | rg 'ChannelAddressBook|ChannelAccount|V47|V48'
  shasum -a 256 ../frontend-dist-20260904-user-channel-address-books-r1.zip target/message-center.jar
  ```

- [ ] **Step 4: 浏览器验收**

  使用现有浏览器测试方式在桌面和移动视口验证三个导航入口、搜索、空时间轴、预填发送区、电话只读、错误态、加载态和跨用户不可见。不要在本机启动企业微信服务作为本轮门禁。

- [ ] **Step 5: 回写验收文档与索引**

  在 verification 文档记录实际命令和结果；在 `docs/superpowers/README.md` 增加当前设计、实施和验收链接。索引更新只暂存本任务新增行，不吸入其他 WIP。

- [ ] **Step 6: 提交发布记录**

  ```bash
  git add docs/superpowers/README.md docs/superpowers/reviews/2026-09-04-user-channel-address-books-verification.md
  git commit -m "docs: verify user channel address books"
  ```

## Plan Self-Review

- Spec coverage：owner schema、唯一账号、企业微信不改、WhatsApp 入站/出站、邮件逐账号、电话只读、标签、消息/附件/Topic 隔离、人工建档、删除、迁移、异常、前端页面和发布门禁均有任务。
- Placeholder scan：无 `TBD`、`TODO`、`待定`、`类似 Task` 或“写测试但不说明内容”的步骤；每个任务都有真实路径、接口、测试命令和提交边界。
- Type consistency：统一使用 `chatapp`、`ownerId`、`channelAccountId`、`ChannelAddressBookService`、`ChannelAddressBookItem`、`RESOURCE_NOT_FOUND`、`CONTACT_HAS_ACTIVITY`。
- Boundary check：企业微信授权/解绑、通讯录和消息模型没有被重新设计；电话没有渠道账号；管理员没有业务数据旁路。
