# WeCom Dual Authentication Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让浏览器登录使用独立企业微信应用凭证，同时保留代开发 Suite 凭证专用于会话展示、公钥注册和专区同步。

**Architecture:** `Config` 负责区分登录应用配置与代开发 Suite 配置；登录 attempt 仍绑定唯一 active 安装记录，但二维码返回独立登录应用的 CorpID/AgentID。登录 code 换取由 `WeComViewerService` 的 HTTP gateway 在配置完整时走登录应用 `gettoken`，viewer token、JS-SDK 会话、会话展示和公钥注册继续使用安装上下文及 Suite owner。

**Tech Stack:** JDK 17、Java `HttpClient`、Gson、JUnit 5、Maven。

## Global Constraints

- 代开发配置仍由 `WECOM_SUITE_ID/WECOM_SUITE_SECRET` 唯一拥有。
- 登录应用配置使用 `WECOM_LOGIN_CORP_ID/WECOM_LOGIN_SECRET/WECOM_LOGIN_AGENT_ID`，三项必须同时存在。
- 登录 attempt 必须继续绑定 `WECOM_LOGIN_AUTH_CORP_ID` 对应的 active 安装，浏览器不能提交 CorpID 覆盖绑定。
- 不记录 secret、access token、code 或 permanent code；不修改服务器、不提交代码。

### Task 1: 配置与 attempt 投影

**Files:** `Config.java`, `WeComLoginAttemptService.java`, `ConfigTest.java`, `WeComLoginAttemptServiceTest.java`

- [x] 写失败测试：读取三项登录应用配置；完整配置时 attempt 返回登录应用 CorpID/AgentID；安装绑定仍返回代开发安装上下文；部分配置结构化拒绝。
- [x] 运行对应 JUnit 测试确认因缺少 getter/投影而失败。
- [x] 实现配置 getter、完整性判断和 attempt 响应投影。
- [x] 运行对应测试确认通过。

### Task 2: 登录 code 换码选择登录应用 token

**Files:** `WeComViewerService.java`, `WeComViewerServiceTest.java`

- [x] 写失败测试：完整登录应用配置时 gateway 收到独立登录换码调用；代开发安装 token 路径仍由 viewer/JS-SDK 使用；未配置登录应用时保留现有自建应用兼容路径。
- [x] 运行测试确认失败。
- [x] 为 HTTP gateway 增加独立登录 token 缓存和 `gettoken(corpid,corpsecret)` 流程；绑定换码优先登录应用，缺少完整配置才回退已有路径。
- [x] 运行测试确认通过并检查敏感值未进入异常/审计。

### Task 3: 配置文档与完整门禁

**Files:** `config.example.env`, `README.md`, `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md`, `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`

- [x] 补充双认证配置说明和职责边界。
- [ ] 用 JDK 17 重跑单测、编译、打包、OpenAPI 合同测试、UnifiedMessageStoreTest 与 `git diff --check`（本机无 JDK，OpenAPI 与 diff check 已运行）。
- [ ] 核对 release JAR 重新生成，工作区不 stage、不 commit（需在具备 JDK 17 的构建机执行）。
