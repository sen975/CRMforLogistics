# WeCom Chatdata Upstream Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the safe upstream `hint` from `sync_call_program` failures and, only when explicitly enabled, emit the bounded complete `errmsg` to server stderr for debugging.

**Architecture:** `Config` owns the strict, default-off diagnostics flag. `WeComChatDataGateway` remains the sole owner of upstream response parsing: it validates and bounds `errmsg`, extracts only the safe hint for `WeComChatDataException`, and optionally serializes a fixed-field diagnostic event with Gson to stderr. Audit, HTTP, and browser layers continue consuming only the exception's safe fields.

**Tech Stack:** Java 17, Gson, JUnit 5, Maven.

## Global Constraints

- Default `WECOM_CHATDATA_DIAGNOSTICS=false`.
- Complete upstream `errmsg` is never persisted in ordinary audit, HTTP responses, browser state, or business data.
- Diagnostic `errmsg` is accepted only as a string of at most 4096 UTF-8 bytes.
- Extract `hint: [A-Za-z0-9_-]{1,128}` only; invalid or absent hints become empty/null.
- Diagnostic output is one Gson-generated JSON line containing only `event`, `path`, `httpStatus`, `errcode`, and `errmsg`.
- Diagnostic output must not contain tokens, request bodies, `response_data`, keys/ciphertext, user IDs, or contact IDs.

---

### Task 1: Add failing gateway diagnostics tests

**Files:**
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/WeComChatDataGatewayTest.java`

- [x] **Step 1: Write tests for safe hint propagation and diagnostic mode.** Add tests that return a non-zero outer `errcode` with `errmsg` containing `hint: [trace123]`, assert `upstreamHint()` is `trace123`, capture stderr when `WECOM_CHATDATA_DIAGNOSTICS=true` and assert one JSON event contains the full errmsg and fixed metadata, and assert the default/false configuration emits no errmsg.

- [x] **Step 2: Add boundary tests.** Cover missing `errmsg`, non-string `errmsg`, an over-4096-UTF-8-byte errmsg, malformed/overlong hints, and assert stable `WECOM_CHATDATA_PROGRAM_ERROR` with no untrusted text leaked. Include secrets in response/request fixtures and assert they are absent from stderr.

- [x] **Step 3: Run the focused tests and verify they fail for the missing behavior.**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataGatewayTest test`

Expected: the new hint/diagnostics assertions fail because the gateway currently discards outer `errmsg`.

### Task 2: Implement configuration and gateway parsing

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/Config.java`
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/WeComChatDataGateway.java`

- [x] **Step 1: Add strict configuration accessor.** Implement `wecomChatDataDiagnostics()` using the existing `strictBoolean("WECOM_CHATDATA_DIAGNOSTICS", false)` helper.

- [x] **Step 2: Parse and bound upstream errmsg.** For non-zero outer and inner program errors, read only a string `errmsg` no larger than 4096 UTF-8 bytes. Extract the first case-sensitive `hint: [A-Za-z0-9_-]{1,128}` and pass it through the existing `programError(... upstreamHint ...)` constructor. Treat missing, invalid, or oversized values as absent without changing the stable error contract.

- [x] **Step 3: Emit diagnostics conditionally.** When the flag is true and a valid bounded errmsg exists, serialize a fixed-field Gson object to `System.err` as one line. Catch any logging failure so it cannot replace the main exception. Never include the request URI, token, request JSON, response_data, or message payload.

- [x] **Step 4: Run the focused tests and verify they pass.**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataGatewayTest test`

Expected: PASS.

### Task 3: Document configuration and run repository gates

**Files:**
- Modify: `demo/message-center-demo/config.example.env`
- Modify: `demo/message-center-demo/README.md`

- [x] **Step 1: Document the default-off flag and operational handling.** Explain that enabling it is temporary server-side debugging, that safe hints remain available in ordinary audit, and that complete errmsg is stderr-only and bounded.

- [x] **Step 2: Run targeted audit and full verification.**

Run: `cd demo/message-center-demo && mvn -q -Dtest=WeComChatDataGatewayTest,WeComViewerAuditTrailTest test`

Run: `cd demo/message-center-demo && mvn -q test`

Run: `cd demo/message-center-demo && mvn -q -DskipTests package`

Run: `git diff --check`

Expected: all commands pass with no whitespace errors; unrelated dirty files remain untouched.

### Self-review checklist

- [x] Every design requirement has a test or an explicit unchanged-path assertion.
- [x] No complete errmsg reaches audit, HTTP, browser, or persistent data paths.
- [x] The configuration accessor and all gateway call sites use the same exact name and semantics.
- [x] Diagnostics JSON is generated structurally and has no unbounded content.
