# ChatApp 多号码销售绑定实施计划

> **供 agentic worker 使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐个实现本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 在单企业 Spring 消息中心中实现“一名销售一个独立账号、固定绑定一个 WhatsApp 发送号码”，由销售申请、平台管理员完成 CAMS 注册并分配，发送、收件、同步和会话权限始终按当前绑定号码生效，无需修改配置或重启服务。

**架构：** `users` 是销售和平台管理员的身份 owner，企业微信 `auth_corp_id + userId` 只是登录身份映射，不建模为企业工作区。`channel_accounts` 是 WABA 下发送号码的 owner，`chatapp_number_requests` 记录申请和注册状态，`channel_account_user_bindings` 记录当前及历史分配。CAMS SDK 只存在于 `channel/chatapp` adapter；服务、Controller、前端只消费内部结构化合同。

**技术栈：** Java 17、Spring Boot 3.4.5、Spring Security、MyBatis-Plus、PostgreSQL、Flyway、Aliyun CAMS SDK 5.0.5、Jackson、React 18、TypeScript 5.6、Vite 6、Ant Design 5、TanStack Query。

## 两份设计的整合结论

- `2026-08-06-whatsapp-mvp-and-expansion-design.md` 提供可靠消息底座：outbox、Webhook inbox、幂等、消息状态投影、模板生命周期、真实 capability/健康检查，以及 CAMS adapter 与业务 owner 分离。
- `2026-08-07-chatapp-number-assignment-design.md` 将旧文档的“单号码产品限制”改为“一个 WABA 下多号码、一名销售固定一个号码”，并定义申请、管理员注册、绑定、禁用和重新分配流程。
- 两份设计共同确认：一个企业、一个 ChatApp `CustSpaceId`、一个 WABA；不创建多租户 `EnterpriseWorkspace`，不为销售创建独立 WABA，不让销售直接调用 CAMS 注册。
- 当前实施只覆盖号码治理、可靠的一对一消息、模板和权限；群组/OBA、Flow、群发、商品、通话和号码迁移不进入完成门禁。当前真实账号的群组能力保持 `OBA_REQUIRED / provider code 131215` 关闭。

## 全局约束

- 当前工作区存在大量未提交用户改动；执行计划必须使用隔离 worktree，禁止回滚、覆盖或吸收无关改动。
- 不得在请求体、前端状态或 URL 中接受发送号码 `from` 或任意 `channelAccountId` 覆盖；服务端从当前用户的 active binding 解析号码。
- 一个销售最多一个 active ChatApp 绑定，一个号码最多一个 active 销售绑定；数据库唯一索引和事务同时提供约束。
- 验证码、AccessKey Secret、WABA 凭据和签名不写日志；验证码只作为本次验证命令参数存在，不落库。
- 所有 CAMS 请求设置连接/响应超时；验证码冷却、验证重试、分页、同步轮次和 outbox 重试都有上限。
- 所有业务失败使用统一 `ApiError(code, message, traceId, details)`，Controller 不捕获异常后返回裸字符串。
- 任何真实账号能力必须通过真实上游结果启用；mock、固定常量和“列表成功所以写入能力”不能替代真实探测。
- 群组、OBA、Flow、群发、商品、通话及多租户不在本计划范围。

---

## 文件结构与 owner

```text
service/auth
  CurrentUserService                 # 当前销售/管理员身份和角色
service/chatapp
  ChatAppNumberRequestService        # 申请与注册状态机
  ChatAppNumberAssignmentService     # 绑定、释放、禁用、重分配
  ChatAppAccountResolver             # 当前用户 -> active 号码
channel/chatapp
  ChatAppNumberRegistrationGateway   # 内部 CAMS 注册合同
  AliyunChatAppNumberRegistrationGateway
  ChatAppWebhookVerifier             # 外部回调验证
  ChatAppWebhookProjector            # 入站与状态投影
service/message
  MessageSendApplicationService      # 消息与 outbox 原子创建
  MessageOutboxWorker                # 有界领取和发送
web
  ChatAppNumberRequestController     # 销售 API
  ChatAppAdminController             # 管理员 API
  ChatAppWebhookController           # 公开但验签的 provider API
```

## 阶段 0：安全与身份前置

### Task 1: 撤出明文 CAMS 凭据并建立注册启用门禁

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/resources/application-dev.yml:20-32`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfigValidator.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/AppConfigValidatorTest.java`
- 创建：`docs/superpowers/runbooks/chatapp-credential-rotation.md`

**接口：**
- 产生 `AppConfig.chatappRegistrationEnabled()`、`AppConfig.chatappCredentialsRotated()` 和 `AppConfigValidator.validate(AppConfig)`。
- 运行时只从环境变量读取 `ALIYUN_ACCESS_KEY_ID`、`ALIYUN_ACCESS_KEY_SECRET`、`CHATAPP_CUST_SPACE_ID`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void registrationRequiresCredentialsAndCompletedRotation() {
    AppConfig config = config("", "", true, false);
    assertThatThrownBy(() -> new AppConfigValidator().validate(config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CHATAPP_CREDENTIALS_NOT_CONFIGURED");
}

@Test
void registrationFlagCannotBypassRotationGate() {
    AppConfig config = config("key", "secret", true, false);
    assertThatThrownBy(() -> new AppConfigValidator().validate(config))
        .hasMessageContaining("CHATAPP_CREDENTIAL_ROTATION_REQUIRED");
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=AppConfigValidatorTest test`

预期：失败，因为 validator 和配置字段尚不存在。

- [ ] **Step 3: 实现最小门禁**

```java
public void validate(AppConfig config) {
    if (isBlank(config.aliyunAccessKeyId()) || isBlank(config.aliyunAccessKeySecret())
            || isBlank(config.custSpaceId())) {
        throw new IllegalStateException("CHATAPP_CREDENTIALS_NOT_CONFIGURED");
    }
    if (config.chatappRegistrationEnabled() && !config.chatappCredentialsRotated()) {
        throw new IllegalStateException("CHATAPP_CREDENTIAL_ROTATION_REQUIRED");
    }
}
```

将 `application-dev.yml` 中的 AccessKey、Secret、CustSpaceId 和固定发送号码改为 `${ALIYUN_ACCESS_KEY_ID:}`、`${ALIYUN_ACCESS_KEY_SECRET:}`、`${CHATAPP_CUST_SPACE_ID:}`、`${CHATAPP_DEFAULT_FROM:}`；注册开关和轮换门禁默认 `false`。

- [ ] **Step 4: 运行测试与密钥扫描**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=AppConfigValidatorTest test`

预期：测试通过；`rg -n 'LTAI|aliyun-access-key-secret: [A-Za-z0-9]' src/main/resources` 无输出。

- [ ] **Step 5: 完成外部轮换**

在阿里云控制台撤销仓库中曾出现的 AccessKey，创建最小权限新凭据并写入部署环境。运行手册只记录轮换时间、权限范围、验证命令和回滚方式，不记录 Secret。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/application-dev.yml \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfigValidator.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/config/AppConfigValidatorTest.java \
  docs/superpowers/runbooks/chatapp-credential-rotation.md
git commit -m "security: remove static chatapp credentials"
```

### Task 2: 建立企业微信员工身份 owner

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/resources/db/migration/V8__wecom_user_identity.sql`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/UserEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/UserDetailsServiceImpl.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/CurrentUserService.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComUserIdentityGateway.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/LoginResponse.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/auth/CurrentUserServiceTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom/WeComUserIdentityGatewayTest.java`

**接口：**
- `CurrentUserService.requireUserId(): UUID`
- `CurrentUserService.requireRole(String role): void`
- `WeComUserIdentityGateway.resolve(String authCorpId, String code): WeComIdentity`
- `POST /api/auth/wecom/exchange` 返回 `userId`、显示名和角色。

- [ ] **Step 1: 写失败测试**

```java
@Test
void mapsProviderIdentityToOneSalesUserWithoutWorkspace() {
    WeComIdentity identity = gateway.resolve("corp-a", "code-1");
    UUID first = service.login(identity).userId();
    UUID second = service.login(identity).userId();
    assertThat(second).isEqualTo(first);
}

@Test
void disabledUserCannotReuseAValidWeComIdentity() {
    disable(userId);
    assertThatThrownBy(() -> service.login(identity))
        .hasMessageContaining("USER_DISABLED");
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=CurrentUserServiceTest,WeComUserIdentityGatewayTest test`

预期：用户字段、gateway 和登录映射尚不存在。

- [ ] **Step 3: 添加身份字段与唯一约束**

`V8` 增加 `users.wecom_auth_corp_id`、`users.wecom_user_id`，创建 `UNIQUE (wecom_auth_corp_id, wecom_user_id) WHERE deleted_at IS NULL`；不创建 `enterprise_workspaces`、tenant 表或企业 owner 外键。

- [ ] **Step 4: 实现当前用户与登录映射**

`CurrentUserService` 从 `Authentication.getName()` 读取 UUID，重新查询 `users` 并检查 `status='active'`。企业微信登录按 `(authCorpId,userId)` 查找唯一用户；普通销售默认 `agent`，只有已有 `admin` 角色的用户可治理号码。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=CurrentUserServiceTest,WeComUserIdentityGatewayTest,AppIntegrationTest test`

预期：身份唯一、禁用拒绝、角色返回和本地管理员入口均通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V8__wecom_user_identity.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/UserEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComUserIdentityGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/LoginResponse.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/auth \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/wecom
git commit -m "feat: bind users to wecom employee identities"
```

## 阶段 1：号码数据模型与管理员治理

### Task 3: 创建号码申请与绑定数据合同

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/resources/db/migration/V9__chatapp_number_governance.sql`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChatAppNumberRequestEntity.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountUserBindingEntity.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChatAppNumberRequestMapper.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountUserBindingMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberSchemaIT.java`

**接口：**
- 申请状态：`REQUESTED`, `CODE_SENT`, `PROVIDER_REGISTERED`, `PENDING_BINDING`, `BOUND`, `REJECTED`, `FAILED`, `EXPIRED`, `DISABLED`, `RELEASED`
- 绑定状态：`ACTIVE`, `RELEASED`
- `channel_accounts.phone_verification_status` 保存 CAMS `codeVerificationStatus` 的标准化值。

- [ ] **Step 1: 写失败数据库测试**

```java
@Test
void databaseRejectsTwoActiveNumbersForOneUser() {
    insertActiveBinding(userId, firstAccountId);
    assertThatThrownBy(() -> insertActiveBinding(userId, secondAccountId))
        .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void databaseRejectsTwoActiveUsersForOneNumber() {
    insertActiveBinding(firstUserId, accountId);
    assertThatThrownBy(() -> insertActiveBinding(secondUserId, accountId))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberSchemaIT test`

预期：迁移尚不存在。

- [ ] **Step 3: 添加迁移和索引**

```sql
CREATE UNIQUE INDEX ux_chatapp_active_binding_user
ON channel_account_user_bindings (user_id)
WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_chatapp_active_binding_account
ON channel_account_user_bindings (channel_account_id)
WHERE status = 'ACTIVE';
```

申请表对进行中状态的 `phone_number_normalized` 建立部分唯一索引。表中保存 `provider_request_id`、`provider_code`、脱敏 provider message、过期时间、冷却时间和尝试次数；不创建验证码列。

- [ ] **Step 4: 实现实体和 mapper**

所有写方法接收服务端已解析的 `UUID actorUserId`；状态转换不放在实体 setter、Controller 或 SQL 字符串中。`ChannelAccountEntity` 增加 `providerPhoneId`、`phoneVerificationStatus`、`providerStatus`、`disabledAt`。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberSchemaIT test`

预期：唯一绑定、并发申请、释放后重绑和软删除行为均通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V9__chatapp_number_governance.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberSchemaIT.java
git commit -m "feat: add chatapp number governance schema"
```

### Task 4: 实现 CAMS 号码注册 gateway

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppNumberRegistrationGateway.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppNumberRegistrationGateway.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppProviderException.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppNumberRegistrationGatewayTest.java`

**接口：**

```java
interface ChatAppNumberRegistrationGateway {
    VerificationCodeReceipt getVerifyCode(String phoneNumber, String method, String locale);
    RegistrationReceipt verifyAndRegister(String phoneNumber, String verifyCode);
    PhoneVerificationStatus getVerificationStatus(String phoneNumber);
}
```

SDK 映射固定为：

- `GetChatappVerifyCodeRequest`: `custSpaceId`, `phoneNumber`, `method`, `locale`
- `ChatappVerifyAndRegisterRequest`: `custSpaceId`, `phoneNumber`, `verifyCode`
- `GetPhoneNumberVerificationStatusRequest`: `custSpaceId`, `phoneNumber`
- 状态读取 `GetPhoneNumberVerificationStatusResponseBody.Data.codeVerificationStatus`

- [ ] **Step 1: 写失败契约测试**

```java
@Test
void verifyCodeMapsCustSpacePhoneAndMethod() {
    gateway.getVerifyCode("8613800000000", "SMS", "en_US");
    verify(cams).getChatappVerifyCode(argThat(r ->
        r.getCustSpaceId().equals("space-1")
        && r.getPhoneNumber().equals("8613800000000")
        && r.getMethod().equals("SMS")
        && r.getLocale().equals("en_US")));
}

@Test
void providerFailureKeepsCodeAndRequestIdButNotSecret() {
    assertThatThrownBy(() -> gateway.verifyAndRegister("8613800000000", "000000"))
        .isInstanceOf(ChatAppProviderException.class)
        .hasMessageNotContaining("secret");
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=AliyunChatAppNumberRegistrationGatewayTest test`

预期：gateway 和内部响应记录尚不存在。

- [ ] **Step 3: 实现 adapter**

adapter 只负责 SDK builder、十秒超时、响应解包和 `ChatAppProviderException(code,message,requestId,httpStatus,retryable)` 映射；成功注册只返回 provider receipt，不创建数据库账号。

- [ ] **Step 4: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=AliyunChatAppNumberRegistrationGatewayTest test`

预期：成功、空 body、限流、超时、失败码和 request id 映射全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppNumberRegistrationGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppNumberRegistrationGateway.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppProviderException.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/AliyunChatAppNumberRegistrationGatewayTest.java
git commit -m "feat: add cams number registration gateway"
```

### Task 5: 实现申请状态机与管理员分配服务

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberRequestService.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberAssignmentService.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppDomainException.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/audit/AuditLogService.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberRequestServiceTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppNumberAssignmentServiceTest.java`

**接口：**

```java
UUID requestNumber(UUID requesterId, String phoneNumber);
void sendCode(UUID adminId, UUID requestId, String method, String locale);
void verify(UUID adminId, UUID requestId, String verifyCode);
PhoneVerificationStatus refreshStatus(UUID adminId, UUID requestId);
void bind(UUID adminId, UUID requestId, UUID targetUserId);
void disable(UUID adminId, UUID channelAccountId, String reason);
void reassign(UUID adminId, UUID channelAccountId, UUID targetUserId, String reason);
```

- [ ] **Step 1: 写失败状态机测试**

```java
@Test
void salesRequestDoesNotCallProvider() {
    UUID requestId = service.requestNumber(salesId, "8613800000000");
    verifyNoInteractions(gateway);
    assertThat(repository.findById(requestId).getStatus()).isEqualTo("REQUESTED");
}

@Test
void verifyCannotSkipCodeSent() {
    assertThatThrownBy(() -> service.verify(adminId, requestId, "123456"))
        .hasMessageContaining("NUMBER_REQUEST_INVALID_STATE");
}

@Test
void reassignReleasesOldBindingAndKeepsHistory() {
    assignment.reassign(adminId, accountId, secondSalesId, "人员调整");
    assertThat(bindingMapper.activeForAccount(accountId).getUserId()).isEqualTo(secondSalesId);
    assertThat(bindingMapper.historyForAccount(accountId)).hasSize(2);
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberRequestServiceTest,ChatAppNumberAssignmentServiceTest test`

预期：状态机服务尚不存在。

- [ ] **Step 3: 实现事务状态转换**

只允许 `REQUESTED -> CODE_SENT -> PROVIDER_REGISTERED -> PENDING_BINDING -> BOUND`；失败、拒绝、过期、禁用和释放进入相应终态。验证码仅作为方法参数传给 gateway；同一号码使用数据库唯一约束和短事务锁，短信/语音冷却六十秒，最多五次验证尝试。

- [ ] **Step 4: 加入权限和审计**

销售只能创建和查询自己的申请；provider、绑定、禁用和重分配动作必须再次校验 `ROLE_ADMIN`。每次成功、拒绝、provider 失败和权限拒绝写 `audit_logs`，不记录号码验证码和消息正文。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberRequestServiceTest,ChatAppNumberAssignmentServiceTest test`

预期：状态转换、并发绑定、冷却、重试上限、禁用和审计均通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/audit \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp
git commit -m "feat: implement chatapp number request workflow"
```

### Task 6: 暴露销售和管理员 API

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppNumberRequestController.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppAdminController.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ChatAppNumberRequestBody.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ChatAppVerifyBody.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ChatAppBindingBody.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChatAppNumberRequestResponse.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppNumberRequestControllerTest.java`

**接口：**

```text
POST /api/chatapp/number-requests
GET  /api/chatapp/number-requests
GET  /api/admin/chatapp/number-requests
POST /api/admin/chatapp/number-requests/{id}/send-code
POST /api/admin/chatapp/number-requests/{id}/verify
GET  /api/admin/chatapp/number-requests/{id}/status
POST /api/admin/chatapp/number-requests/{id}/bind
POST /api/admin/chatapp/channel-accounts/{id}/disable
POST /api/admin/chatapp/channel-accounts/{id}/reassign
```

- [ ] **Step 1: 写失败 MockMvc 测试**

```java
@Test
void requestOwnerAlwaysComesFromAuthentication() throws Exception {
    mockMvc.perform(post("/api/chatapp/number-requests")
            .with(user(salesPrincipal))
            .contentType(APPLICATION_JSON)
            .content("{\"phoneNumber\":\"8613800000000\",\"requestedByUserId\":\"other\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestedByUserId").value(salesId.toString()));
}

@Test
void salesCannotTriggerVerification() throws Exception {
    mockMvc.perform(post("/api/admin/chatapp/number-requests/" + requestId + "/send-code")
            .with(user(salesPrincipal)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberRequestControllerTest test`

预期：路由和 DTO 尚不存在。

- [ ] **Step 3: 实现 Controller 与统一错误**

请求 DTO 只接收号码、验证方式、语言、验证码、目标销售和原因。Controller 仅做 Bean Validation 和 service 调用；`ChatAppDomainException`、`ChatAppProviderException` 分别映射稳定 HTTP 状态与 `ApiError`。

- [ ] **Step 4: 运行 HTTP 测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppNumberRequestControllerTest test`

预期：销售申请、管理员注册/查询/绑定/禁用/重分配和错误合同全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppNumberRequestController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppAdminController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChatAppNumberRequestResponse.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppNumberRequestControllerTest.java
git commit -m "feat: expose chatapp number governance APIs"
```

## 阶段 2：运行时号码归属与可靠消息

### Task 7: 用当前用户绑定解析发送号码

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolver.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendServiceTest.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppControllerTest.java`

**接口：**

```java
ChannelAccountEntity requireActiveAccount(UUID userId);
String requireBusinessNumber(UUID userId);
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void sendUsesBindingInsteadOfAppChatappFrom() {
    service.sendText(salesId, "8613900000000", "hello", "request-1");
    verify(cams).send(argThat(r -> r.getFrom().equals("8613711111111")));
}

@Test
void requestBodyCannotOverrideSender() throws Exception {
    mockMvc.perform(post("/api/chatapp/send/text").with(user(salesPrincipal))
            .contentType(APPLICATION_JSON)
            .content("{\"to\":\"8613900000000\",\"text\":\"hello\",\"from\":\"attacker\"}"))
        .andExpect(status().isAccepted());
    verify(cams).send(argThat(r -> r.getFrom().equals(boundNumber)));
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppSendServiceTest,ChatAppControllerTest test`

预期：当前实现仍读取 `app.chatapp-from`。

- [ ] **Step 3: 实现 resolver 和发送合同**

发送方法改为 `sendText(UUID userId, String to, String text, String clientRequestId)`；模板和媒体发送同样接收 userId。账号不存在、绑定释放、`auth_status != active` 或 `disabled_at != null` 时抛出 `CHANNEL_ACCOUNT_NOT_READY`。

- [ ] **Step 4: 修改 Controller 与媒体路径**

Controller 通过 `CurrentUserService.requireUserId()` 取得 actor，不读取 `from` 和 `channelAccountId`。成功返回 `202 Accepted` 与本地消息 ID，异常交给全局 handler。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppSendServiceTest,ChatAppControllerTest test`

预期：绑定号码生效、未绑定/禁用拒绝、请求字段覆盖无效。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppAccountResolver.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSendService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp
git commit -m "feat: resolve chatapp sender from user binding"
```

### Task 8: 接通 outbox、幂等和发送状态

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationService.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/MessageOutboxWorker.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageStatusEventEntity.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageStatusEventMapper.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageSendApplicationServiceTest.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageOutboxWorkerTest.java`

**接口：**

```java
MessageAccepted accept(SendMessageCommand command, UUID actorUserId);
void claimAndProcess(int batchSize);
void projectStatus(UUID channelAccountId, String providerMessageId, String status, String reason);
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void duplicateClientRequestCreatesOneMessageAndOutbox() {
    service.accept(command("request-1"), salesId);
    service.accept(command("request-1"), salesId);
    assertThat(messageRepository.countByClientRequestId(accountId, "request-1")).isEqualTo(1);
    assertThat(outboxRepository.countByMessage(messageId)).isEqualTo(1);
}

@Test
void timeoutBecomesDeliveryUnknownWithoutBlindRetry() {
    worker.process(outboxId, new ProviderTimeoutException());
    assertThat(messageRepository.status(messageId)).isEqualTo("delivery_unknown");
    verify(cams, times(1)).send(any());
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=MessageSendApplicationServiceTest,MessageOutboxWorkerTest test`

预期：当前发送直接调用 CAMS。

- [ ] **Step 3: 实现事务写入与有界 worker**

同一事务写 `messages`、`outbox_jobs` 和初始 `pending` 状态；按 `(channel_account_id, client_request_id)` 返回已有消息。worker 使用租约、批次上限、最大尝试次数、指数退避和抖动；超时进入 `delivery_unknown`，人工处置前不自动重发。

- [ ] **Step 4: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=MessageSendApplicationServiceTest,MessageOutboxWorkerTest test`

预期：重复请求、成功、明确拒绝、限流、超时和最大重试全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageStatusEventEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageStatusEventMapper.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message
git commit -m "feat: route chatapp sends through reliable outbox"
```

### Task 9: 将消息和模板同步改为多号码隔离

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppSyncScheduler.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChannelSyncCursorEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/SyncCursorMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppController.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncServiceTest.java`
- 修改：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppTemplateSyncServiceTest.java`

**接口：**

```java
SyncResultRecord syncMessages(UUID channelAccountId);
SyncResultRecord syncTemplates(UUID channelAccountId);
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void syncUsesRequestedNumberAndCursor() {
    service.syncMessages(accountB.getId());
    verify(cams).list(argThat(r -> r.getBusinessNumber().equals(accountB.getAccountIdentifier())));
    verify(cursor).find(accountB.getId(), "chatapp_message");
    verify(cursor, never()).find(accountA.getId(), "chatapp_message");
}

@Test
void schedulerProcessesAllActiveAccounts() {
    scheduler.syncMessages();
    verify(messageSync).syncMessages(accountA.getId());
    verify(messageSync).syncMessages(accountB.getId());
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppMessageSyncServiceTest,ChatAppTemplateSyncServiceTest test`

预期：当前 service 和 scheduler 仍使用 `limit 1`。

- [ ] **Step 3: 实现账号参数化和单飞**

删除全部 `limit 1` 账号选择。手动同步接口把 path account ID 传入 service；scheduler 遍历全部 active ChatApp 账号。每个账号独立更新 `sync_status`、`last_synced_at` 和 cursor，同一账号同一同步类型只能单飞。

- [ ] **Step 4: 修复模板对账**

稳定键为 `channelAccountId + providerTemplateId + languageCode`。使用 upsert 保存 provider 原始状态、拒绝原因、组件 JSON 和最后成功快照；分页中途失败不得把截断集合当成全量，不得固定写 `APPROVED`。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppMessageSyncServiceTest,ChatAppTemplateSyncServiceTest,ChatAppIntegrationTest test`

预期：两个号码同步、游标隔离、模板增改删和失败保留旧快照全部通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/chatapp \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/channel/chatapp
git commit -m "feat: isolate chatapp sync by channel account"
```

## 阶段 3：Webhook、归属和权限

### Task 10: 建立 webhook inbox 和统一 projector

**文件：**
- 创建：`demo/message-center-spring/backend/src/main/resources/db/migration/V10__chatapp_webhook_inbox.sql`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelEventEntity.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelEventMapper.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookVerifier.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java`
- 创建：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookControllerTest.java`

**接口：**

```java
WebhookReceipt accept(String signature, String timestamp, String rawBody);
ProjectionResult project(UUID eventId);
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void publicWebhookStillRejectsInvalidSignature() throws Exception {
    mockMvc.perform(post("/api/v1/webhooks/chatapp")
            .contentType(APPLICATION_JSON)
            .header("X-CAMS-Signature", "bad")
            .content(validPayload))
        .andExpect(status().isUnauthorized());
}

@Test
void duplicateProviderEventCreatesOneMessage() {
    service.accept(validEvent("event-1"));
    service.accept(validEvent("event-1"));
    assertThat(messageRepository.countByProviderEvent("event-1")).isEqualTo(1);
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppWebhookControllerTest test`

预期：当前 webhook 没有验签、收件箱或幂等。

- [ ] **Step 3: 实现验签、收件箱和资源上界**

校验请求体最大 1 MiB、时间窗口、签名和业务号码映射；脱敏 payload 写 `channel_events`，provider event ID 唯一。重复事件返回已接收。`SecurityConfig` 只放行 `/api/v1/webhooks/chatapp`，其他 `/api/**` 仍需登录。

- [ ] **Step 4: 统一入站和轮询投影**

事件按业务号码找到 `channel_account_id`，再按 active binding 设置新会话 `assigned_user_id`；provider message ID 幂等创建 contact identity、conversation、message 和状态历史。轮询同步调用同一个 projector。

- [ ] **Step 5: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppWebhookControllerTest,ChatAppMessageSyncServiceTest test`

预期：合法回调可达，坏签名/过期/超限被拒绝，重复事件只生成一条消息。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/resources/db/migration/V10__chatapp_webhook_inbox.sql \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelEventEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelEventMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookVerifier.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppWebhookProjector.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ChatAppWebhookControllerTest.java
git commit -m "feat: add verified chatapp webhook inbox"
```

### Task 11: 修复会话按号码归属与销售可见性

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadVisibilityTest.java`

**接口：**

```java
ThreadResponse threadPage(UUID userId, UUID contactId, String channelType,
                          UUID channelAccountId, String cursor, int limit);
boolean canAccess(UUID userId, UUID conversationId);
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void salesASeesOnlyAccountAConversation() {
    assertThat(service.threadPage(salesA, contactId, "chatapp", null, null, 20)
        .messages()).extracting(MessageResponse::channelAccountId)
        .containsOnly(accountA);
}

@Test
void reassignmentDoesNotRewriteHistoricalMessages() {
    assignment.reassign(admin, accountA, salesB, "交接");
    assertThat(messageRepository.find(messageId).getChannelAccountId()).isEqualTo(accountA);
    assertThat(messageRepository.find(messageId).getCreatedByUserId()).isEqualTo(salesA);
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ThreadVisibilityTest test`

预期：当前 `ThreadService` 仍按渠道类型 `selectOne`。

- [ ] **Step 3: 实现账号维度查询**

销售先由 resolver 得到 active account，再查询该账号会话；管理员查询全部账号。发送前重新校验 active binding 和会话访问。绑定释放或重分配只改变后续发送和当前访问，不更新历史 `channel_account_id`、`assigned_user_id` 或 `created_by_user_id`。

- [ ] **Step 4: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ThreadVisibilityTest,ChatAppIntegrationTest test`

预期：销售 A 无法读写销售 B 会话，管理员可看全量，历史审计不变。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadVisibilityTest.java
git commit -m "feat: enforce chatapp conversation ownership"
```

## 阶段 4：模板、前端与验收

### Task 12: 完成模板生命周期和按号码发送校验

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java`
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateResponse.java`
- 创建：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java`

**接口：**

```text
POST /api/v1/channel-accounts/{accountId}/whatsapp/templates
GET  /api/v1/channel-accounts/{accountId}/whatsapp/templates
PUT  /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}
DELETE /api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}
POST /api/v1/channel-accounts/{accountId}/whatsapp/templates/sync
```

- [ ] **Step 1: 写失败测试**

```java
@Test
void unapprovedTemplateCannotBeSent() {
    assertThatThrownBy(() -> service.requireApproved(accountId, "welcome", "en_US"))
        .hasMessageContaining("TEMPLATE_NOT_APPROVED");
}

@Test
void createStoresSubmittedInsteadOfApproved() {
    service.create(adminId, accountId, command("UTILITY"));
    assertThat(repository.status(accountId, "welcome", "en_US")).isEqualTo("submitted");
}
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppTemplateServiceTest test`

预期：当前同步固定写 `APPROVED`。

- [ ] **Step 3: 实现模板合同**

只允许 `UTILITY` 和 `MARKETING`；支持 BODY 变量示例、媒体 HEADER、FOOTER 和常用按钮。保存标准化状态、provider 原始状态、拒绝原因、组件 JSON、语言和最后同步时间。模板治理需要管理员角色，发送只允许当前绑定账号下的 approved 语言版本。

- [ ] **Step 4: 运行测试**

运行：`cd demo/message-center-spring/backend && ./mvnw -q -Dtest=ChatAppTemplateServiceTest,ChatAppTemplateSyncServiceTest test`

预期：提交、批准、拒绝、修改、删除、失败保留旧快照和账号隔离全部通过。

- [ ] **Step 5: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/TemplateEntity.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/TemplateResponse.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateServiceTest.java
git commit -m "feat: manage chatapp template lifecycle"
```

### Task 13: 接入销售申请页、管理员治理页和发送状态

**文件：**
- 修改：`demo/message-center-spring/frontend/src/api/endpoints.ts`
- 修改：`demo/message-center-spring/frontend/src/api/types.ts`
- 创建：`demo/message-center-spring/frontend/src/pages/ChatAppNumberRequestPage.tsx`
- 创建：`demo/message-center-spring/frontend/src/pages/ChatAppNumberAdminPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/pages/SendPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- 修改：`demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- 修改：`demo/message-center-spring/frontend/src/router.tsx`
- 修改：`demo/message-center-spring/frontend/package.json`
- 创建：`demo/message-center-spring/frontend/src/pages/__tests__/ChatAppNumberRequestPage.test.tsx`

**接口：**

```ts
createChatAppNumberRequest(phoneNumber: string): Promise<ChatAppNumberRequest>;
fetchMyChatAppNumberRequests(): Promise<ChatAppNumberRequest[]>;
fetchAdminChatAppNumberRequests(): Promise<ChatAppNumberRequest[]>;
sendChatAppVerifyCode(id: string, method: 'SMS' | 'VOICE'): Promise<void>;
verifyChatAppNumber(id: string, verifyCode: string): Promise<void>;
bindChatAppNumber(id: string, targetUserId: string): Promise<void>;
```

- [ ] **Step 1: 写失败前端测试**

```tsx
it('submits a request without exposing provider credentials', async () => {
  render(<ChatAppNumberRequestPage />);
  await userEvent.type(screen.getByLabelText('WhatsApp 号码'), '8613800000000');
  await userEvent.click(screen.getByRole('button', { name: '提交申请' }));
  expect(api.createChatAppNumberRequest).toHaveBeenCalledWith('8613800000000');
  expect(screen.queryByText(/AccessKey|Secret|CustSpaceId/)).not.toBeInTheDocument();
});
```

- [ ] **Step 2: 运行失败测试**

运行：`cd demo/message-center-spring/frontend && npm test -- --run`

预期：测试脚本、API 和页面尚不存在。

- [ ] **Step 3: 添加测试工具和销售页面**

增加 Vitest、Testing Library 和 jsdom。销售页只显示申请表单、状态、失败原因、过期时间和等待管理员处理；销售界面没有验证码、绑定、禁用或重分配控件。

- [ ] **Step 4: 实现管理员页与消息状态**

管理员页提供申请列表、发送验证码、校验注册、查询状态、选择销售、绑定、禁用和重分配。`SendPage` 显示当前绑定号码的只读标签，不允许编辑 `from`。模板选择器过滤未批准版本，消息展示 `pending/submitted/delivered/read/failed/delivery_unknown`。

- [ ] **Step 5: 运行测试和构建**

运行：`cd demo/message-center-spring/frontend && npm test -- --run && npm run build`

预期：页面测试、TypeScript 和 Vite 构建全部通过。

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/frontend/package.json \
  demo/message-center-spring/frontend/package-lock.json \
  demo/message-center-spring/frontend/src/api \
  demo/message-center-spring/frontend/src/pages \
  demo/message-center-spring/frontend/src/components/AppLayout.tsx \
  demo/message-center-spring/frontend/src/router.tsx
git commit -m "feat: add chatapp number governance UI"
```

### Task 14: 文档、真实账号门禁和全量验收

**文件：**
- 修改：`docs/superpowers/specs/2026-08-06-whatsapp-mvp-and-expansion-design.md`
- 修改：`docs/superpowers/specs/2026-08-07-chatapp-number-assignment-design.md`
- 创建：`docs/superpowers/acceptance/2026-08-07-chatapp-multi-number-acceptance.md`
- 修改：`demo/message-center-spring/README.md`

**接口：**
- 文档记录唯一企业边界、号码申请状态机、权限矩阵、真实账号门禁和群组关闭原因。

- [ ] **Step 1: 写验收矩阵**

```text
销售 A 申请号码 -> 管理员发验证码 -> 管理员校验注册 -> 查询状态 -> 绑定销售 A
销售 A 发送 -> CAMS From=号码 A
销售 B 发送 -> CAMS From=号码 B
销售 A 读取 -> 看不到号码 B 会话
管理员重分配 -> 历史消息 channel_account_id 和 created_by_user_id 不变
重复 webhook/clientRequestId -> 业务消息各一条
验证码、Secret、签名 -> 日志和普通响应中不存在
```

- [ ] **Step 2: 运行后端完整门禁**

运行：`cd demo/message-center-spring/backend && ./mvnw -q clean verify`

预期：编译、单元测试、集成测试、Flyway 迁移和安全测试通过，不带 warning。

- [ ] **Step 3: 运行前端完整门禁**

运行：`cd demo/message-center-spring/frontend && npm test -- --run && npm run build`

预期：测试和构建通过；浏览器检查桌面与移动视口的申请、审核、发送、错误和空态，无布局重叠。

- [ ] **Step 4: 执行真实 CAMS 门禁**

使用已轮换的测试凭据和两个测试号码验证：

1. `GetChatappVerifyCode`、`ChatappVerifyAndRegister`、`GetPhoneNumberVerificationStatus` 字段和状态映射。
2. 两个号码分别绑定两个销售并同时同步，游标、模板和消息不交叉。
3. 一对一文本、模板、媒体发送和状态回执。
4. 合法 webhook、重复事件、超时和 provider 错误。
5. 禁止群组写入；当前账号必须返回 `CAPABILITY_NOT_AVAILABLE`、`OBA_REQUIRED`、`131215`。

- [ ] **Step 5: 更新文档并提交**

```bash
git add docs/superpowers/specs/2026-08-06-whatsapp-mvp-and-expansion-design.md \
  docs/superpowers/specs/2026-08-07-chatapp-number-assignment-design.md \
  docs/superpowers/acceptance/2026-08-07-chatapp-multi-number-acceptance.md \
  demo/message-center-spring/README.md
git commit -m "docs: record chatapp multi-number acceptance"
```

## 完成定义

- 明文凭据已从配置撤出并完成外部轮换，注册开关由环境门禁控制。
- 每名销售有独立用户身份；每名销售和每个号码都满足 active binding 唯一约束。
- 管理员完成验证码、注册、状态查询、绑定、禁用和重分配；销售只能申请和查看自己的申请。
- 发送号码由服务端绑定解析，两个号码可并行发送和同步；请求体不能覆盖号码。
- Webhook 验签、inbox、幂等和统一 projector 生效；outbox 对重复请求和上游超时有明确状态。
- 一对一会话按号码和销售权限隔离，管理员可查看全部；历史消息审计不因重分配改写。
- `UTILITY`/`MARKETING` 模板按审核状态发送，拒绝、删除和同步失败不会伪装成批准。
- 后端、前端、数据库、安全和真实 CAMS 门禁均有可复核命令或实机证据。

群组/OBA、Flow、群发、商品、通话和多租户不属于本计划完成条件。
