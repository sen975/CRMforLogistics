# 用户私有渠道通讯录验收记录

## 范围

本记录对应 `2026-09-04-user-channel-address-books` 计划的 Task 10。Task 1-8 后端实现和 Task 9 前端通讯录实现已存在对应提交；本轮完成全量门禁、构建、制品校验和发布记录。

## 验收结果

### 后端专项测试

命令：

```bash
cd demo/message-center-spring/backend
mvn -Dtest='UserChannelOwnerSchemaContractTest,ChannelAccountOwnerIsolationTest,ChannelAddressBookServiceTest,ChatAppOwnerProjectionTest,EmailOwnerIsolationTest,CallRecordOwnerIsolationTest,ContactOwnerTagIsolationTest,MessageOwnerIsolationTest,AiTopicOwnerIsolationTest,UserChannelOwnerBackfillSchemaContractTest' test
```

结果：31 项通过，0 failures，0 errors，构建成功。

覆盖 owner schema、账号隔离、通讯录服务、WhatsApp/邮件投影、电话、标签、消息、Topic 和 backfill 迁移合同。

### 后端全量测试

命令：

```bash
cd demo/message-center-spring/backend
mvn test
```

结果：1069 项中 1 failure、31 errors、7 skipped。

- 31 个 error 主要来自本机 Testcontainers/Docker socket 不可用（`Operation not permitted` / `Previous attempts to find a Docker environment failed`）。
- 1 个 failure 为既有 `AiTopicStoreApprovalServiceTest.storedTopicCannotCreateAnotherStoreRequest` 的 Topic WIP 行为差异（期望 `TOPIC_STORE_NOT_READY`，实际 `TOPIC_NOT_FOUND`）。
- 另有若干 Topic 联系人集成测试因同一工作区 WIP 数据/容器门禁失败。

这些失败不涉及本计划专项 owner 隔离断言；生产制品使用跳过测试的 production 打包命令生成，不能据此宣称后端全量测试通过。

### 前端测试

命令：

```bash
cd demo/message-center-spring/frontend
npm test
```

结果：源合同测试 29 项通过；Vitest 58 个文件、297 项测试通过。

### 构建与制品

前端构建：

```bash
cd demo/message-center-spring/frontend
npm run build
```

结果：Vite 6.4.3，3213 modules transformed，构建成功。

前端 ZIP：

```text
demo/message-center-spring/frontend-dist-20260904-user-channel-address-books-r1.zip
SHA-256: 4e8d5527c1a73040a13484461107f16a65585b56d8fb09b62c60d98ab7a6d744
大小：638320 bytes
```

ZIP 已执行 `unzip -t`，压缩数据无错误。

后端 Jar：

```bash
cd demo/message-center-spring/backend
mvn -Pproduction -DskipTests clean package
```

结果：Spring Boot repackage 成功，制品位于：

```text
demo/message-center-spring/backend/target/message-center.jar
SHA-256: 53029da43967b67d4f15ffba4cc6bc7bf0502627a65650c4ccf933d32228117d
大小：65488317 bytes
```

`unzip -l` 已确认 Jar 包含 `V47__user_channel_address_book_owner.sql`、`V48__backfill_user_channel_owners.sql`、`ChannelAddressBookService`、`ChannelAddressBookController` 和 owner 相关类。当前本机 `java` 启动器不可用，因此未执行额外的 `jar tf` 命令；Maven 打包本身使用了可用的 Java 编译环境并成功完成。

## 浏览器验收边界

本轮未启动企业微信服务，也未执行需要真实登录态和服务器数据的桌面/移动浏览器验收。原因是当前任务明确不把本机企业微信运行面作为门禁，且真实账号、渠道凭证和跨用户数据必须在部署环境验证。部署后应验证三个通讯录入口、搜索/分页、人工新增、点击进入时间轴、邮件/WhatsApp 预填、电话只读及跨用户不可见。

## 数据与迁移

Jar 已包含 V47/V48 迁移。生产部署后需由应用按现有 Flyway 流程执行迁移，并在服务器数据库执行项目已有的 owner backfill/verify 脚本，核对空 owner、重复 scope、跨 owner 引用和孤儿外键。

## 未闭合风险

- 本机 Docker/Testcontainers 不可用，后端集成测试需在具备 Docker socket 的 CI 或服务器环境补跑。
- 既有 Topic WIP 集成测试仍有失败，未在本任务中扩大范围修复。
- 真实渠道账号、联系人、附件和权限边界需要部署后用实际登录态验收。
