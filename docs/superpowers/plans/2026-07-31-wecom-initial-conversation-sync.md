# WeCom Initial Conversation Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline execution with test-first checkpoints.

**Goal:** Allow a logged-in user to run an initial enterprise WeChat conversation sync before any WeCom contact exists.

**Architecture:** Add a protected OpenAPI route that uses the existing viewer auth token and `WeComChatDataSyncService` installation-scoped sync. Add a toolbar action that runs the sync, refreshes contacts, and leaves the existing per-contact session/display route unchanged.

**Tech Stack:** JDK 17, `com.sun.net.httpserver.HttpServer`, Gson, JUnit 5, existing static HTML shell.

## Global Constraints

- The sync route requires `X-WeCom-Viewer-Auth` and never accepts a caller-supplied corp ID, installation ID, permanent code, or private key.
- The route keeps the existing `WeComChatDataSyncService` limits, audit events, cursor store, and decryption path.
- No mock messages or fake contacts are created.
- No public API deletion or database dependency is introduced.
- Existing session creation remains restricted to a readable `wecom:` contact point.

### Task 1: Contract and route

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

- [ ] Add a failing route test for `POST /api/v1/wecom/conversation-view/sync` with a valid viewer auth token and injected sync service; expect `200` and the sync result fields.
- [ ] Add a failing route test proving the route returns `WECOM_CHATDATA_NOT_CONFIGURED` when the sync service is absent.
- [ ] Implement the route by resolving `weComViewer.viewerSyncContext(viewerAuthToken(exchange))`, rejecting a missing sync service, calling `chatDataSync.sync(context)`, and returning its `pages`, `stored`, and `skipped` values.
- [ ] Run the focused route test and verify both success and structured failure.

### Task 2: First-sync UI action

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Test: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

- [ ] Add a toolbar button labelled `同步企业微信会话` beside the existing sync actions and assert the generated shell includes its ID and route.
- [ ] Add `syncWeCom()` that requires the current viewer auth, disables sync controls during the request, calls the new route with `X-WeCom-Viewer-Auth`, reports stored/skipped counts, and refreshes contacts and the selected thread.
- [ ] Wire the button during `enterMessageCenter()` initialization.
- [ ] Keep the existing `打开企业微信会话` button disabled until a real `wecom:` point appears after refresh.

### Task 3: Verification and handoff

**Files:**
- No new production files.

- [ ] Run focused tests, `mvn -q test-compile`, `mvn -q -DskipTests package`, OpenAPI contract validation, and `git diff --check`.
- [ ] Confirm the generated JAR is Java 17 (class major version 61).
- [ ] Start the local service on an unused port and verify the new route is reachable without exposing credentials in responses.
- [ ] Update the README endpoint list and usage flow to describe initial sync before contact selection.
