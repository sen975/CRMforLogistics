# WeCom ServiceApp Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct browser login to use the service-provider Login Authorization Suite while keeping conversation viewing bound to the delegated-app installation.

**Architecture:** The callback codec and authorization gateway accept two explicitly configured Suite identities but select each SuiteSecret by exact SuiteID. Login attempts project `ServiceApp + login SuiteID`; one-time code exchange uses the login Suite's cached suite_ticket and `/cgi-bin/service/auth/getuserinfo3rd`, then rejects a returned CorpID that differs from the installation bound to the state. The delegated Suite remains the sole owner of permanent_code, corp access tokens, JS-SDK signatures, viewer sessions, public-key registration, and chatdata sync.

**Tech Stack:** Java 17, JDK HttpClient, Gson, JUnit 5, embedded HttpServer, OpenAPI YAML, vanilla browser JavaScript.

## Global Constraints

- Compile and verify with OpenJDK 17 and Maven `release=17`.
- Never log or return SuiteSecret, suite_ticket, suite_access_token, login code, permanent_code, or private keys.
- `WECOM_LOGIN_AUTH_CORP_ID` remains the server-selected active installation; the browser cannot override it.
- Remove the rejected `WECOM_LOGIN_CORP_ID/WECOM_LOGIN_SECRET/WECOM_LOGIN_AGENT_ID` model.
- Do not commit or stage files.

---

### Task 1: ServiceApp configuration and login-attempt contract

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComLoginAttemptService.java`
- Modify: `demo/message-center-demo/contracts/openapi/message-center-v1.yaml`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/ConfigTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComLoginAttemptServiceTest.java`

**Interfaces:**
- Consumes: `WECOM_LOGIN_SUITE_ID`, `WECOM_LOGIN_SUITE_SECRET`, `WECOM_LOGIN_AUTH_CORP_ID`.
- Produces: `LoginAttemptResponse(loginType, appId, redirectUri, state, expiresIn)`; ServiceApp 不包含 AgentID。

- [ ] Add failing tests requiring complete login-Suite configuration and a `ServiceApp` attempt with no AgentID.
- [ ] Run the two test classes and confirm failure due to missing ServiceApp configuration/contract.
- [ ] Replace the rejected login application fields with login Suite fields and update OpenAPI.
- [ ] Re-run the two test classes and require success.

### Task 2: Dual-Suite callback and token cache

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComCallbackCodec.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComCallbackCodecTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: encrypted callback whose SuiteID is exactly the delegated Suite or login Suite.
- Produces: per-Suite ticket/token cache; login-Suite ticket never mutates installation state or triggers chatdata public-key registration.

- [ ] Add failing tests for decoding and accepting a login-Suite ticket and selecting its SuiteSecret.
- [ ] Run the three test classes and confirm exact-suite failures.
- [ ] Implement explicit allowed-Suite validation and per-Suite secret selection.
- [ ] Re-run the three test classes and require success.

### Task 3: ServiceApp identity exchange and installation binding

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationGateway.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComViewerService.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationGatewayTest.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComViewerServiceTest.java`

**Interfaces:**
- Consumes: one-time code and login Suite access token.
- Produces: `LoginIdentity(corpId, userId)` from `/cgi-bin/service/auth/getuserinfo3rd`; mismatch with bound installation returns 403.

- [ ] Add failing HTTP tests for the official GET request and CorpID mismatch rejection.
- [ ] Confirm failures occur because the old code calls `gettoken/auth/getuserinfo`.
- [ ] Implement bounded official identity exchange and wire the shared authorization gateway into the viewer.
- [ ] Re-run both test classes and require success.

### Task 4: Browser parameters, docs, and release verification

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`
- Modify: `docs/superpowers/specs/2026-07-27-message-center-login-wecom-oauth-design.md`
- Modify: `docs/superpowers/specs/2026-07-27-wecom-conversation-viewer-design.md`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: ServiceApp login-attempt response.
- Produces: `createWWLoginPanel` parameters with `login_type=ServiceApp`, login SuiteID as `appid`, and no `agentid`.

- [ ] Add failing rendered-HTML assertions for ServiceApp and absence of AgentID.
- [ ] Implement the browser parameter mapping and update configuration documentation.
- [ ] Run OpenJDK 17 `mvn -q clean test`, `test-compile`, executable store test, package, OpenAPI validation, and `git diff --check`.
- [ ] Rebuild `release/message-center/app/message-center.jar`, verify `App.class` and Java 17 major version 61, and do not stage or commit.
