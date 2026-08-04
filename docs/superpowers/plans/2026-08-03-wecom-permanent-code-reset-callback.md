# WeCom Permanent Code Reset Callback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely consume the official `reset_permanent_code` callback, replace the encrypted delegated-app Secret, and move the existing `/hook_path` Suite instruction callback from 8067 to 8107.

**Architecture:** `WeComCallbackCodec` already authenticates the Suite instruction callback and parses `InfoType` and `AuthCode`. `WeComAuthorizationService` will validate and enqueue the reset event, exchange its one-time `AuthCode` through the existing gateway, require a matching existing installation, validate current authorization information with the new Secret, and atomically upsert the encrypted credential so the installation version invalidates old access-token cache entries. `App` will route both the versioned contract path and the existing official `/hook_path` ingress into this single handler; deployment changes only the Nginx upstream for `/hook_path`.

**Tech Stack:** JDK 17, Java `HttpServer`, JUnit 5, Maven, AES-256-GCM credential store.

## Global Constraints

- Never log or return `AuthCode`, `permanent_code`, access tokens, Suite tickets, signatures, or ciphertext.
- Keep the public `/hook_path` URL stable while moving only its Nginx upstream from 8067 to 8107.
- Do not restore the deleted simulated `/webhook/wecom` or unversioned `/api/wecom/*` aliases.
- Do not add an HTTP credential-import endpoint or a second installation owner.
- Keep the callback acknowledgement under the existing bounded asynchronous queue contract.
- Do not commit or stage unrelated dirty-worktree changes.

---

### Task 1: Consume `reset_permanent_code`

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationServiceTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComAuthorizationService.java`
- Modify: `docs/superpowers/specs/2026-07-28-wecom-delegated-authorization-installation-design.md`

**Interfaces:**
- Consumes: `WeComCallbackCodec.DecodedCallback`, `WeComAuthorizationClient.getPermanentCode(String)`, `WeComAuthorizationStore.resolveRefreshable(String, String)`.
- Produces: an updated `ACTIVE` installation with the same `installationId`, a new encrypted permanent code, and an incremented `version`.

- [x] **Step 1: Write the failing service tests**

Add tests proving that a delegated-suite `reset_permanent_code` with a nonblank `AuthCode` updates an existing installation, uses the new Secret for `getAuthInfo`, increments its version, triggers public-key registration, and does not create an unknown installation. Add validation tests proving that a blank `AuthCode` or a reset event from the login Suite is rejected before queueing.

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComAuthorizationServiceTest test
```

Expected: failure because `reset_permanent_code` is currently acknowledged as an unknown event and never calls `getPermanentCode`.

- [x] **Step 3: Implement the minimal reset branch**

Extend `handle(...)` to accept only a delegated-suite reset event with a nonblank `AuthCode`. In `process(...)`, call `getPermanentCode`, require an existing non-revoked installation for the returned CorpID, call `getAuthInfo` with the newly returned Secret, verify CorpID consistency, preserve cancellation ordering, upsert the new encrypted credential, and trigger public-key registration. Do not retain the cleartext credential beyond the call stack.

- [x] **Step 4: Run focused and full authorization tests**

Run:

```bash
cd demo/message-center-demo
mvn -q -Dtest=WeComAuthorizationServiceTest,WeComAuthorizationCallbackRouteTest,WeComCallbackCodecTest test
```

Expected: all selected tests pass with no errors or warnings.

- [x] **Step 5: Update the delegated authorization truth document**

Document the official reset flow, the 10-minute `AuthCode` lifetime, instruction-callback ownership, installation matching, encryption, version invalidation, and the fact that application business callbacks remain owned by 8067.

- [x] **Step 6: Run release gates and build the JDK 17 artifact**

Run the project test, test-compile, standalone message-store acceptance, package, OpenAPI contract, `git diff --check`, class-version, and SHA-256 checks. Copy only the verified JAR to `demo/message-center-demo/release/message-center/app/message-center.jar`.

### Task 2: Move the Stable `/hook_path` Ingress to 8107

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComAuthorizationCallbackRouteTest.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/README.md`

**Interfaces:**
- Consumes: official GET verification and encrypted POST callbacks at `/hook_path`.
- Produces: the same verified plain-text echo and `success` acknowledgement as the versioned authorization callback.

- [x] **Step 1: Add failing GET and POST route tests for `/hook_path`**

Use the existing encrypted callback fixtures and assert that both methods have identical authentication, decoding, response body, and status behavior to `/api/v1/wecom/authorization/callback`.

- [x] **Step 2: Verify RED**

Run `mvn -q -Dtest=WeComAuthorizationCallbackRouteTest test` and expect `/hook_path` to return 404.

- [x] **Step 3: Route `/hook_path` through the authorization callback owner**

Use one shared path predicate in `App`; do not duplicate crypto or authorization logic and do not expose any legacy simulation route.

- [x] **Step 4: Document the Nginx cutover**

Keep the enterprise WeCom URL unchanged and replace only `proxy_pass http://127.0.0.1:8067` with `proxy_pass http://127.0.0.1:8107` for the exact `/hook_path` location after the new JAR is running. Require `nginx -t`, reload, callback GET verification, a fresh reset event, and installation-version evidence before retiring 8067.
