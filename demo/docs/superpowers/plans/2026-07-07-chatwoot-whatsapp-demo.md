# Chatwoot WhatsApp Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone local demo that tests the Chatwoot-style WhatsApp one-to-one flow with mock mode, real API mode, webhook capture, and local message search.

**Architecture:** Keep Chatwoot behavior in a small core package, keep HTTP as a thin adapter, and keep the browser UI as an operator console. The demo defaults to mock mode so the end-to-end flow can be tested before real Chatwoot credentials are available.

**Tech Stack:** Python standard library HTTP server, SQLite, vanilla HTML/CSS/JavaScript, `unittest`.

---

### Task 1: Core Contract And Storage

**Files:**
- Create: `chatwoot-whatsapp-demo/chatwoot_demo/config.py`
- Create: `chatwoot-whatsapp-demo/chatwoot_demo/store.py`
- Test: `chatwoot-whatsapp-demo/tests/test_core.py`

- [x] Write failing tests for config redaction, SQLite event storage, contact upsert, and message search.
- [x] Run tests and confirm they fail because the core package does not exist.
- [ ] Implement minimal core code and rerun tests.

### Task 2: Chatwoot Client

**Files:**
- Create: `chatwoot-whatsapp-demo/chatwoot_demo/client.py`
- Test: `chatwoot-whatsapp-demo/tests/test_client.py`

- [x] Write failing tests for mock create contact, create conversation, send text, send WhatsApp template, and real HTTP request construction.
- [x] Run tests and confirm they fail because the client package does not exist.
- [ ] Implement mock and real clients and rerun tests.

### Task 3: HTTP App And UI

**Files:**
- Create: `chatwoot-whatsapp-demo/app.py`
- Create: `chatwoot-whatsapp-demo/static/index.html`
- Create: `chatwoot-whatsapp-demo/static/app.js`
- Create: `chatwoot-whatsapp-demo/static/styles.css`
- Test: `chatwoot-whatsapp-demo/tests/test_app.py`

- [ ] Write API tests for status, send flow, template flow, webhook ingest, and message listing.
- [ ] Implement HTTP routes and vanilla UI.
- [ ] Run all tests.

### Task 4: Runtime Verification

**Files:**
- Create: `chatwoot-whatsapp-demo/README.md`
- Create: `chatwoot-whatsapp-demo/config.example.json`

- [ ] Add run and configuration docs.
- [ ] Start the local server.
- [ ] Verify the page loads and the mock send/webhook flow works.
