# 企业微信通讯录/昵称与邮件同步修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** 让企业微信联系人名称、通讯录错误状态和邮件同步链路可诊断且可恢复，并用生产侧证据确认是否需要重新打包后端。

**Architecture:** 企业微信凭证继续由 `WeComAccessTokenService` 统一使用代开发 `/cgi-bin/gettoken` 链路；通讯录仍由只读目录 API 提供，不在前端伪造联系人。昵称回填由会话投影 owner 负责，邮件同步由现有 `EmailSyncService` 负责，生产开关通过 Spring 配置显式控制。

**Tech Stack:** Spring Boot 3、MyBatis-Plus、PostgreSQL、React、TanStack Query、JUnit 5、Vitest。

## Global Constraints

- 不回退企业微信 suite token 链路，不把永久授权码或密钥写入日志、前端或构建产物。
- 先取得生产环境变量、`wecom_api_audit` 和数据库现状，再决定代码改动。
- 后端修改必须先写失败测试；前端修改必须保留请求错误可见性。
- 不删除或覆盖用户现有未提交改动；生产包只在专项测试和构建通过后生成。

---

### Task 1: 生产链路诊断

**Files:**
- Read: `/proc/<pid>/environ`、`message-center.log`、PostgreSQL `wecom_api_audit`、`channel_accounts`、`contact_identities`
- No source changes

- [ ] 执行脱敏诊断，确认 `APP_EMAIL_SYNC_ENABLED`、IMAP 配置、企业微信 API 错误码和当前联系人名称。
- [ ] 手动调用 `POST /api/email/sync`，把响应和同一时间段日志关联起来。
- [ ] 停止条件：若邮件返回 IMAP 认证/TLS 错误或企业微信审计为 40001/48002，先修复生产配置/权限，不进入代码猜测。

### Task 2: 企业微信昵称回填回归

**Files:**
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComExternalContactService.java`

- [ ] 先增加失败测试：重复消息命中已有 identity 时，仍刷新可用昵称；查询失败时记录结构化 warning 但不阻断消息入库。
- [ ] 在 Maven 可访问本地依赖缓存后运行 `mvn -q -Dtest=WeComMessageProjectorTest test`，确认测试先失败。
- [ ] 在重复消息分支调用已有 identity 的名称刷新逻辑；保持消息去重，不重复插入消息或联系人。
- [ ] 对 `displayNameForSync` 的 `WeComException` 记录错误码、路径和企业/外部联系人摘要，继续返回空名称以保证同步可用。
- [ ] 重新运行专项测试并确认通过。

### Task 3: 通讯录错误可见性与响应解析

**Files:**
- Test: `demo/message-center-spring/frontend/src/components/wecom/WeComDirectoryPanel.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComDirectoryPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/wecomProviderData.ts`（仅在真实响应结构需要时）

- [ ] 先增加失败测试：目录 API 返回错误时显示错误码/重试入口；成功返回 `userlist` 时显示成员名称而非只显示 ID。
- [ ] 运行 `npm test -- --run src/components/wecom/WeComDirectoryPanel.test.tsx`，确认测试先失败。
- [ ] 在目录面板渲染 query error，并保留重试；不把失败误显示成“暂无数据”。
- [ ] 重新运行组件测试和前端构建。

### Task 4: 邮件同步闭环

**Files:**
- Modify only if Task 1 证明源码缺陷: `demo/message-center-spring/backend/src/main/.../channel/email/EmailSyncService.java` 或配置文档
- Test only if source changes are required

- [x] 已确认前端配置保存到 `channel_accounts.encrypted_config`，原邮件同步服务未读取该字段，导致配置页填写内容不生效。
- [x] `EmailSyncSettings` 已实现渠道账号配置优先、启动环境配置回退；IMAP、SSL、文件夹和 139 OpenSSL 路径统一使用有效配置。
- [x] 配置保存时忽略空字段和秘密字段的 `***` 遮罩值，避免重复打开配置页后覆盖真实密码。
- [x] 已增加回归测试并通过邮箱专项测试；生产验证仍需替换新 Jar 后执行手动同步。

### Task 5: 集成验收与发布包

**Files:**
- Build outputs only: `demo/message-center-spring/backend/target/message-center.jar` and release copy `message-center.jar`

- [x] 运行后端邮箱专项测试和后端构建。
- [x] 记录新 Jar SHA-256；前端本轮未改动，不需要重新构建前端。
- [ ] 生产替换 Jar 后验证企业微信目录、昵称回填、邮件手动同步和定时同步开关；任一链路仍失败时停止发布，不宣称完成。
- [ ] 生产部署后验证企业微信目录、昵称回填、邮件手动同步和定时同步开关；任一链路仍失败时停止发布，不宣称完成。
