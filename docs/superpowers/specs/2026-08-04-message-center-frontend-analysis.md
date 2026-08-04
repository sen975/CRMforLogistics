# Frontend Analysis for React SPA Migration

Date: 2026-08-04
Source: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java` -- `pageHtml()` method (lines 779--2989)

## 1. Page Layout

The single-page app renders two top-level states:

**State A: WeCom Login Screen** (`id="wecomLoginScreen"`)
- Full-viewport centered card with the brand name "统一消息中心"
- A WeCom OAuth2 QR code login panel rendered via the WeCom JS-SDK `ww.createWWLoginPanel()`
- Status text line (`id="wecomLoginStatus"`) and a retry button (`id="wecomLoginRetry"`)
- In `LOCAL_DEV_MODE`, this screen is hidden and the shell is immediately shown

**State B: Shell (main workspace)** (`id="shell"`)
- CSS Grid layout with 3 columns and 2 rows
- Column widths: `320px / minmax(430px, 1fr) / var(--detail-width)` where `--detail-width` defaults to `360px`
- When the detail pane is collapsed, `--detail-width` shrinks to `48px`
- Row 1: A top bar spanning all 3 columns
- Row 2: Three panes

```
+------------------------------------------------------------------------------+
|  Row 1: Top Bar (64px, spans all 3 columns)                                  |
|  [brand | contactCount] [threadTitle | syncBtns | refreshBtn] [empty area]  |
+---------------------+---------------------------+----------------------------+
| Row 2, Col 1        | Row 2, Col 2              | Row 2, Col 3              |
| Contacts Pane        | Thread Pane               | Detail Pane               |
| +------------------+ | +-----------------------+ | +------------------------+ |
| | searchInput      | | | thread (scrollable)   | | | [detailToggleBtn]     | |
| +------------------+ | | message/call cards    | | | contact detail /       | |
| | contacts (list)  | | +-----------------------+ | | message detail /       | |
| | contact items    | | | composer (260px)      | | | callRecord detail      | |
| | (scrollable)     | | | channel tabs + forms  | | | (scrollable)           | |
| +------------------+ | +-----------------------+ | +------------------------+ |
+---------------------+---------------------------+----------------------------+
```

**Modals (portals):**
- `#profileModal` -- Edit contact nickname/tags (420px wide modal)
- `#previewImageModal` -- Full-screen image preview with close button
- `#wecomOpenModal` -- iframe modal for WeCom conversation detail (960x720 max)
- `#toast` -- Fixed-position bottom-right notification toast

**Responsive:** At `max-width: 980px`, the grid collapses to a single column with stacked panes. Each pane has `max-height: 58vh`.

## 2. Component Tree

### 2.1 WeComLoginScreen

- **Renders:** A branded login card with QR code placeholder, status text, and retry button.
- **States:**
  - **Loading QR:** "正在准备二维码" / "正在加载企业微信登录组件" in status text, loading placeholder in panel.
  - **QR Ready:** WeCom SDK renders the QR code `<iframe>` into `#wwLoginPanel`.
  - **Scanning:** Status shows "请在企业微信中确认登录".
  - **Logging in:** Status shows "正在进入消息中心".
  - **Error:** Status shows error message with retry button visible.

### 2.2 Shell (master layout container)

An `onclick` handler on `#notifyBtn` calls `enableNotifications()`.

### 2.3 Top Bar (`.workspace-topbar`)

- Left section (`.workspace-section`): Brand name "统一消息中心" + contact count (`#contactCount`, text like "0 个联系人") + notify button.
- Center section (`.workspace-center`):
  - Thread title (`#threadTitle`): displays selected contact's `displayName` or "选择联系人".
  - Edit profile button (`#editProfileBtn`, hidden when no contact selected) with an edit icon.
  - Subtitle (`#threadSub`): contact point summary or placeholder "邮件和 ChatApp 按时间穿插显示".
  - Right-side actions: three sync buttons (`#syncEmailBtn`, `#syncChatBtn`, `#syncWeComBtn`) + refresh button (`#refreshBtn`).
- Right section (`.workspace-detail`): empty (used to balance the collapsed/expanded detail pane alignment).

### 2.4 Contacts Pane

- **Search bar:** `<input id="searchInput">` with placeholder "搜索联系人、邮箱、号码". Fires `renderContacts()` on each `input` event. Filtering is done by running `JSON.stringify(contact).toLowerCase().includes(term)`.
- **Contact list** (`#contacts`): scrollable list of `.contact` divs.

#### Contact Item (`.contact`)
- **Renders:**
  - Avatar button with channel-type icon (email/ChatApp/WeCom icon via CSS pseudo-elements).
  - Display name (`.contact-name`).
  - Point summary (`.contact-points`) -- joined values from `contact.points`.
  - Last message text (`.contact-last`).
  - Unread indicator: red dot (`.contact-unread-dot`) for 1 unread, or red badge (`.contact-unread-count`) with count for >1.
- **States:**
  - **Active** (`.contact.active`): blue-tinted background when `contact.id === state.selectedPointId`.
  - **Unread**: has dot or count badge when `state.unreadByContact[contact.id] > 0`.
  - **Empty:** "暂无联系人" when no contacts.
- **Interactions:**
  - `click` on row: calls `selectContact(id)`.
  - `dragstart`: sets `dataTransfer` with the contact's `id`.
  - `dragover`: `preventDefault()` to allow drop.
  - `drop`: calls merge API with source and target IDs.
- **Avatar click** (`.avatar[data-avatar]`): no handler registered; purely decorative.

### 2.5 Thread Pane

- **Thread container** (`#thread`): scrollable area displaying messages and call records.
- **Empty state:** "左侧选择一个联系人".
- **Composer** (`#composer`): 260px fixed-height area at the bottom, always rendered, shown/hidden based on contact selection.

#### Message Row (`.message-row`)
- **Renders:**
  - Avatar icon (channel-specific: email envelope, ChatApp speech bubble, WeCom dots).
  - Message bubble (`.msg`):
    - Title (`.msg-title`) if present.
    - Body text (`.msg-text`) if present.
    - Media preview (`.msg-media`) if `mediaUrl` or `objectKey` is present.
  - Meta line (`.msg-meta-line`): channel label, timestamp, status icon, "打开附件" button.
- **Direction variants:**
  - `inbound`: avatar on left, bubble on left.
  - `outbound` (`.message-row.outbound`): avatar on right, bubble on right, green-tinted background (`.msg.outbound`).
- **States:**
  - **Active** (`.msg.active`): blue outline when `m.id === state.selectedMessageId`.
  - **has-media** (`.msg.has-media`): width-capped at `min(320px, 100%)`.
- **Interactions:**
  - `click` on `.msg[data-id]`: calls `selectMessage(id)`.
  - `click` on `.call-card[data-call-record-id]`: calls `openCallRecordDetail(id)`.
  - `click` on `[data-open-media]`: calls `openAttachment()`.

#### Media Preview (`.msg-media`)
- **Renders:**
  - Loading placeholder ("正在拉取图片") until loaded.
  - **Image type:** `<img>` with `onload`/`onerror` handlers. Click triggers `openImagePreview()`.
  - **Video type:** `<video>` with controls, `onloadedmetadata`/`onerror`.
  - **Other:** File name display with file icon.
- **States:**
  - `.loaded` (loaded): hides the loading placeholder.
  - `.failed` (media error): shows "附件暂不可用" message, hides preview elements.

#### Call Record Card (`.call-card`)
- **Renders:**
  - Phone icon (CSS-styled rotated border).
  - Call direction ("呼入电话" / "呼出电话") and state badge ("排队中" / "转录中" / "已完成" / "失败").
  - Phone label, duration.
  - Meta line: "电话记录" + timestamp.
- **Interaction:** `click`/`keydown Enter/Space` calls `openCallRecordDetail(id)`.

#### Composer (`.composer`)
- Empty when no contact is selected.
- Channel tabs: dynamically generated from `contact.channels` (email, chatapp, wecom), plus a "电话记录" tab.
- Content panel (`#sendPanel`) re-rendered based on `state.selectedChannel`.

##### Composer: Email Tab
- **Renders:** Recipient `<select>` (populated from contact's email points), subject `<input>`, body `<textarea>`, "发送邮件" button.
- **Interactions:**
  - `sendEmail` onclick on the primary button.
  - Account select dropdown changes update `state.selectedPointByChannel`.

##### Composer: ChatApp Tab
- Sub-mode tabs: text, template, image, video, document via `state.selectedMode`.
- **Text mode:**
  - Recipient `<select>`, message `<textarea>`, emoji panel (`.emoji-panel` -- hidden/shown via `.open` class), toolbar with emoji/template/image/video/document icon buttons, "发送 WhatsApp" button.
  - Enter key without Ctrl sends message.
  - Emoji button toggles the emoji panel; clicking an emoji inserts it at cursor position.
- **Template mode:**
  - Recipient `<select>`, template `<select>`, dynamic placeholder fields (`#tplFields`, 3-column grid), mode switcher buttons, "发送模板" button.
- **Image/Video/Document mode:**
  - Recipient `<select>`, caption `<textarea>`, file drop zone with hidden file `<input>`, mode switcher buttons, "发送附件" button.
  - Clicking the file drop zone opens the file browser.

##### Composer: WeCom Tab
- **Renders:** WeCom account display, "打开企业微信会话" button, viewer container (`#wecomViewerContainer`).
- **Interaction:** Button click loads WeCom session via API, then either:
  - In `LOCAL_DEV_MODE`: renders local sample messages.
  - In production: initializes WeCom OpenData frame factory with message list.

##### Composer: CallRecord Tab
- **Renders:** File `<input>` (MP3 only), direction `<select>` (呼入/呼出), datetime-local `<input>`, phone point `<select>`, progress bar, status text, "上传录音" button.
- **Interaction:** Button click triggers `uploadCallRecord()` which uses `XMLHttpRequest` with `FormData` (not `fetch`), with upload progress tracking.

### 2.6 Detail Pane

- **Header:** Collapse toggle button (`#detailToggleBtn`).
- **Content** (`#detail`): scrollable, re-rendered based on context.

#### Detail: Contact View
- **Renders:**
  - Section title "联系人资料".
  - Nickname (`contact.remark` or "未设置").
  - Tags (`.tag-pill` items or "未设置").
  - Account list: each account rendered as an `.account-item` with value, channel label, and split button.
- **States:**
  - **Empty:** "点击一个联系人查看资料".
- **Interactions:**
  - Split button (`.account-split-button`): calls `splitAccount(pointId)`.

#### Detail: Message View
- **Renders:** Key-value pairs for channel, direction, timestamp, from, to, status, title, body text, and raw JSON.
- **Activated by:** clicking a message bubble in the thread.

#### Detail: Call Record View
- **Renders:**
  - Title with direction and state badge.
  - Update error line.
  - KV pairs: phone number, occurrence time, duration, original file name.
  - Audio player (`<audio id="callAudio">`).
  - Audio error message area.
  - Failure reason (if transcription failed).
  - Full transcription text.
  - Time segments.
  - Revision history.
  - Revision textarea + "保存修订" button.
  - "重新转录" button (if failed and retryable).
- **Polling:** For `queued` or `processing` states, polls every 3s with exponential backoff (up to 30s), max 5 consecutive failures before stopping.
- **Audio session:** Session renewed every 240s (`CALL_AUDIO_RENEW_MS`). On audio error, tries to recover once by renewing the session.
- **Visibility handling:** When tab becomes hidden, stops audio renewal; on tab visible, renews session.

### 2.7 Modals

#### Profile Modal (`#profileModal`)
- **Renders:** Nickname input, tags input, save/cancel buttons, save state indicator.
- **States:**
  - **Dirty:** "未保存" shown in save state.
  - **Saving:** "保存中", buttons disabled.
  - **Error:** "保存失败".
- **Interactions:**
  - Cancel/close: reset dirty flag, hide modal.
  - Save: calls `POST /api/contact-groups/profile`.
  - Enter key in either input triggers save.
  - Click backdrop closes modal.

#### Image Preview Modal (`#previewImageModal`)
- **Renders:** Full-viewport backdrop with centered image and close button.
- **Interaction:** Close button or Escape key or backdrop click hides modal and clears `src`.

#### WeCom Open Modal (`#wecomOpenModal`)
- **Renders:** iframe loading an HTTPS URL for WeCom conversation detail.
- **Interaction:** Backdrop click or Escape key clears iframe and hides modal.

### 2.8 Toast

- **Renders:** Fixed-position toast at bottom-right, auto-hides after 2.6 seconds.
- **Usage:** Called throughout the app for success/error notifications.

## 3. API Surface

### 3.1 `api(url, options)` -- standard fetch wrapper
Returns `response.json()`. Throws on non-2xx with `{status, code, message}` shape.

### 3.2 `viewerApi(url, options)` -- authenticated fetch wrapper
Same as `api()` but injects `X-WeCom-Viewer-Auth` header from `currentWeComAuth().viewerAuthToken`. Returns parsed JSON (or `{}` for 204). Throws on non-2xx.

### 3.3 Endpoints

| # | Method | Path | Initiator | Request Body | Response Fields Used | Updates |
|---|--------|------|-----------|-------------|---------------------|---------|
| 1 | GET | `/api/channel-capabilities` | `enterMessageCenter()` | -- | `[{channel, ...}]` stored in `state.capabilities` | Enables channel-specific UI |
| 2 | GET | `/api/templates` | `enterMessageCenter()`, `refreshTemplates()`, `syncChatApp()` | -- | `[{templateCode, templateName, languageCode, placeholders[]}]` | Stored in `state.templates`, used for template dropdown and placeholder fields |
| 3 | GET | `/api/contacts` | `loadContacts()` | -- | `[{id, displayName, remark, tags[], channels[], points[{id, channel, value, type, label}], lastTime, messageCount, lastText, lastDirection, lastChannel, source}]` | Stored in `state.contacts`, drives contacts list, header, detail, composer |
| 4 | GET | `/api/v1/contacts/{id}/timeline?limit=10&cursor=` | `loadThread()`, `loadOlderThreadMessages()` | -- (viewerApi) | `{items[{type, sortId, occurredAt, payload:{id, channel, direction, timestamp, title, text, summary, status, mediaType, mediaUrl, objectKey, fileName, from, to, state, version, durationSeconds, phonePointId, transcriptionState}}], nextCursor, itemCount, threadRevision}` | Renders thread messages |
| 5 | GET | `/api/messages?id={id}` | `selectMessage()` | -- | `{id, channel, direction, timestamp, from, to, status, title, bodyText, text, raw}` | Renders message detail in detail pane |
| 6 | GET | `/api/media?id={id}` | `openAttachment()`, `mediaPreviewHtml()` | -- | Binary blob with `Content-Type` and `Content-Disposition` headers | Download or inline-open attachment |
| 7 | POST | `/api/send/email` | `sendEmail()` | `{to, subject, body}` | -- | Clears form, refreshes all |
| 8 | POST | `/api/send/chatapp` | `sendChatText()`, `sendTemplate()` | `{mode:"text"|"template", to, text?, templateCode?, templateName?, languageCode?, templateParamsJson?, clientRequestId}` | -- | Clears form, refreshes all |
| 9 | POST | `/api/send/chatapp-media` | `sendMedia()` | FormData: `{to, mediaType, caption, clientRequestId, file}` | -- | Refreshes all |
| 10 | POST | `/api/contact-groups/merge` | Drag-and-drop on contacts list | `{primaryPointId, mergedPointId}` | -- | Refreshes selected contact views |
| 11 | POST | `/api/contact-groups/split` | Split button in detail pane | `{primaryPointId, pointToSplit}` | -- | Refreshes selected contact views |
| 12 | POST | `/api/contact-groups/profile` | `saveContactProfile()` | `{contactPointId, nickname, tags}` | -- | Refreshes all, closes modal |
| 13 | POST | `/api/sync/email` | `syncEmail()` via `runSync()` | `{}` | `{saved, skipped}` | Toast message, refreshes all |
| 14 | POST | `/api/sync/chatapp` | `syncChatApp()` via `runSync()` | `{}` | `{saved, updated, pages, durationMillis, templatesSaved, mediaCached, mediaQueued, mediaFailed, mediaFailures[{reason}]}` | Toast message, refreshes templates and all |
| 15 | POST | `/api/v1/wecom/conversation-view/sync` | `syncWeCom()` | `{}` (viewerApi) | `{stored, skipped}` | Toast message, refreshes all |
| 16 | POST | `/api/v1/wecom/login/attempts` | `initWeComLogin()` | `{}` | `{loginType, appId, redirectUri, state}` | Stored in `state.wecomLoginAttempt`, used for WWLoginPanel config |
| 17 | POST | `/api/v1/wecom/login/exchange` | `completeWeComLogin()` | `{code, state}` | `{viewerAuthToken, expiresIn}` | Stored in `state.wecomAuth` and `state.wecomAuthExpiresAt` |
| 18 | GET | `/api/v1/wecom/conversation-view/sessions/{id}` | `loadWeComViewer()` | -- (viewerApi) | `{viewerSessionId, messages[{msgid, secretKey}]}` | Used to render WeCom OpenData frame |
| 19 | POST | `/api/v1/wecom/conversation-view/sessions` | `loadWeComViewer()` | `{contactPointId, viewerAuthToken}` | `{viewerSessionId}` | Session ID used to fetch detail |
| 20 | GET | `/api/v1/wecom/js-sdk-config?url=` | `loadWeComViewer()` | -- (viewerApi) | `{corpId, agentId, configSignature, agentConfigSignature, jsApiList}` | Used for `ww.register()` and `ww.initOpenData()` |
| 21 | POST | `/api/v1/wecom/conversation-view/events` | `reportWeComViewerEvent()` | `{eventType, viewerSessionId}` | -- | Fire-and-forget (just catches errors) |
| 22 | GET | `/api/v1/call-records/{id}` | `openCallRecordDetail()`, `refreshCallRecordDetail()` | -- (viewerApi) | `{id, direction, phonePointId, occurredAt, version, transcription:{state, result:{originalText, segments[{startSeconds, endSeconds, text}]}, error:{message, retryable}}, revisions[{id, text, editedAt, editedBy}], currentRevisionId, audio:{durationSeconds, originalFileName}}` | Renders call detail panel |
| 23 | POST | `/api/v1/call-records/{id}/retry` | `retryCallRecord()` | `{clientRequestId}` (viewerApi) | Updated call record object | Re-renders call detail |
| 24 | PATCH | `/api/v1/call-records/{id}/transcript` | `reviseCallRecord()` | `{text, expectedVersion}` (viewerApi) | Updated call record object | Re-renders call detail, refreshes thread |
| 25 | POST | `/api/v1/call-records/{id}/audio-sessions` | `renewCallAudioSession()` | -- (viewerApi) | -- | Sets `state.callAudioSessionReady = true`, sets audio `src` |
| 26 | GET | `/api/v1/call-records/{id}/audio` | Audio element `src` attribute | -- (viewerApi) | Audio binary | Played in `<audio>` element |
| 27 | POST | `/api/v1/contacts/{contactId}/call-records` | `uploadCallRecord()` (XHR) | FormData: `{direction, occurredAt, clientRequestId, phonePointId?, file}` (viewerApi) | `{state}` | Refreshes thread |
| 28 | GET | `/events` | `connectEvents()` (EventSource) | -- (SSE) | See Section 4 | Triggers background refresh |

Note: `get` is called on `POST /api/v1/wecom/conversation-view/sessions` returning `{viewerSessionId}` which is then used in `GET /api/v1/wecom/conversation-view/sessions/{id}`. The POST response shape is `{viewerSessionId}`.

## 4. SSE Events

The `EventSource` connects to `GET /events`.

### 4.1 Connection lifecycle
- Established in `connectEvents()` called from `enterMessageCenter()`.
- Closed and re-created on each `connectEvents()` call.
- Server sends `: connected\n\n` on connect and `: heartbeat\n\n` every 25 seconds (both are SSE comments, ignored by browser `EventSource`).
- Server send errors (IOException) cause client removal from server-side list.

### 4.2 Event Types (as handled by client)

#### `message` event (default/unnamed)
- **Server-side triggers:**
  - `publish(UnifiedMessage)` -- sends `data: <UnifiedMessage JSON>\n\n`
  - `publish(CallRecordEvent)` -- sends `data: {"type":"callRecord","callRecordId":"...","contactAnchorPointId":"...","state":"...","version":...}\n\n`
- **Client handler:** `state.eventSource.onmessage = () => refreshInBackground(true)`
- **Effect:** Calls `refreshAll(true)` which loads contacts silently, then reloads thread if a contact is selected. Silent mode means: if contacts render key hasn't changed, skip re-render; if the user is currently scrolling, defer the refresh (`pendingSilentRefresh = true`).

#### `templates-changed` event (named)
- **Server-side trigger:** `publishTemplatesChanged(int count)`
- **Server payload:** `event: templates-changed\ndata: {"count": N}\n\n`
- **Client handler:** `state.eventSource.addEventListener('templates-changed', async () => { await refreshTemplates(); })`
- **Effect:** Re-fetches `GET /api/templates`, stores in `state.templates`. If currently on the ChatApp template compositor, re-renders the composer.

**Assumption:** The `count` field in the templates-changed event payload is not read by the client; it is only received but ignored. The client unconditionally re-fetches all templates.

## 5. Data Flow

### 5.1 Central State Object

All client state lives in a single `state` object (not a reactive store -- mutation is manual, followed by imperative DOM updates):

```javascript
const state = {
  contacts: [],              // Array from GET /api/contacts
  templates: [],             // Array from GET /api/templates
  capabilities: {},          // Map<channel, object> from GET /api/channel-capabilities
  selectedPointId: '',       // Currently selected contact ID
  selectedMessageId: '',     // Currently selected message ID (for detail pane)
  selectedCallRecordId: '',  // Currently selected call record ID
  selectedChannel: '',       // Current composer tab ('email'|'chatapp'|'wecom'|'callRecord')
  selectedMode: 'text',      // ChatApp sub-mode ('text'|'template'|'image'|'video'|'document')
  lastKey: '',               // Last contacts change key (for detecting new messages)
  contactsRenderKey: '',     // Render cache key for contacts (prevents unnecessary re-render)
  threadRenderKeyByContact: {}, // Map<contactId, renderKey> -- thread render cache
  threadPages: {},           // Map<contactId, {items[], nextCursor, isLoadingOlder, hasLoadedInitial, pageItemCount, threadRevision}>
  threadPageAccessOrder: [], // LRU list of contact IDs for page cache eviction
  threadLoadSeqByContact: {}, // Monotonic sequence per contact to discard stale loads
  threadTouchY: 0,           // Touch Y position for scroll-up-to-load-older detection
  detailCollapsed: false,    // Detail pane collapse state
  profileDirty: false,       // Whether profile modal has unsaved changes
  profileSavedPointId: '',   // Contact ID that was just saved (used to skip stale refreshes)
  profileSavedTimer: null,   // Timer handle (unused in current code)
  selectedPointByChannel: {}, // Map<channel, pointId> -- remembers which account was selected per channel
  contactSnapshots: {},      // Map<contactId, {lastTime, messageCount, lastDirection, lastChannel}>
  unreadByContact: {},       // Map<contactId, count> -- unread message counts
  isUserScrolling: false,    // True if any scroll surface is being scrolled
  pendingSilentRefresh: false, // True if a refresh was deferred due to scrolling
  wecomLoginAttempt: null,   // Login attempt object from POST /api/v1/wecom/login/attempts
  wecomAuth: null,           // Auth object from POST /api/v1/wecom/login/exchange
  wecomAuthExpiresAt: 0,     // Expiration timestamp
  viewerReloginPromise: null, // Deduplication for re-login flow
  messageCenterInitialized: false, // Whether event handlers have been bound
  eventSource: null,         // EventSource instance
  refreshTimer: null,        // setInterval handle for periodic refresh
  callDetail: null,          // Current call record detail object
  callDetailGeneration: 0,   // Monotonic generation counter (invalidates stale detail loads)
  callDetailPollTimer: null, // setTimeout handle for call detail polling
  callDetailPollFailures: 0, // Consecutive failure count
  callAudioRenewTimer: null, // setInterval handle for audio session renewal
  callAudioRecovered: false, // Whether audio recovery has been attempted
  callAudioSessionReady: false, // Whether a valid audio session exists
};
```

### 5.2 Data Flow Patterns

**Pattern 1: Server → State → DOM (contacts)**
```
GET /api/contacts → state.contacts → renderContacts() → innerHTML of #contacts + #contactCount
```
Deduplication: `contactRenderKey()` computes a hash of all contact fields. If silent refresh and key unchanged, skip re-render but still update `contactSnapshots`. If key changed, re-render full list.

**Pattern 2: Server → State → DOM (thread messages)**
```
GET /api/v1/contacts/{id}/timeline → state.threadPages[id] → renderThreadMessages() → innerHTML of #thread
```
Deduplication: `threadRenderKey()` computes a hash of contact header + all message fields. If `keepScroll` is true and key unchanged, skip re-render but preserve scroll position. Also `state.threadLoadSeqByContact[id]` discards responses for stale loads (race conditions).

**Pattern 3: Server → State → DOM (message detail)**
```
GET /api/messages?id={id} → (no state storage) → renderMessageDetail(m) → innerHTML of #detail
```
Note: message detail is NOT stored in state -- it is fetched fresh on each click and rendered directly.

**Pattern 4: User Action → API → Refresh All → DOM**
```
POST /api/send/email → refreshAll(false) → loadContacts + loadThread → re-render contacts + thread
```

**Pattern 5: SSE → Background Refresh**
```
EventSource message → refreshInBackground(true) → refreshAll(true) → (silent) loadContacts + loadThread
```
Silent mode means: if user is scrolling, defer; if data unchanged, skip re-render. New message detection uses `state.lastKey` comparison.

### 5.3 Caching and Invalidation

- **Contact list:** Rendered only when `contactRenderKey()` differs from `state.contactsRenderKey`. Individual field changes trigger full re-render.
- **Thread pages:** Cached in `state.threadPages{}` map. LRU eviction keeps max `THREAD_PAGE_CACHE_LIMIT` (20) entries. The currently selected contact's page is never evicted.
- **Thread messages:** Max `THREAD_PAGE_MAX_MESSAGES` (200) items per page. Older items are trimmed from the front.
- **Older messages (pagination):** Loaded on scroll-to-top or wheel-up or swipe-down. Merged into existing page via `mergeThreadMessages()` which deduplicates by `type:sortId` key, keeping the last occurrence.
- **Render keys:** Used for both contacts and thread to avoid unnecessary DOM mutations during silent refreshes.
- **Load sequence numbers:** `state.threadLoadSeqByContact[id]` is incremented before each `loadThread()` call. The response handler checks if the seq matches the current value; if not, the response is discarded (stale closure protection).
- **Call detail generation:** `state.callDetailGeneration` is incremented on each `clearCallDetailActivity()`, serving a similar stale-protection role for call record loads and polls.

### 5.4 Unread Message Tracking

- On each `loadContacts()`, `reconcileUnreadContacts()` compares current `messageCount` and `lastTime` against previous snapshots (`state.contactSnapshots`).
- If the contact is not currently selected and has new inbound messages (`lastDirection !== 'outbound'`), increment `state.unreadByContact[id]`.
- When a contact is selected, `clearContactUnread(id)` removes the entry.
- The delta is calculated as `Math.max(0, current.messageCount - previous.messageCount)`, with a minimum increment of 1 if the time advanced.
- Capped at 99.

## 6. Interaction Flows

### 6.1 Login Flow

**Production mode:**
1. Page loads. `initWeComLogin()` is called.
2. `POST /api/v1/wecom/login/attempts` returns `{loginType, appId, redirectUri, state}`.
3. WeCom SDK (`ww.createWWLoginPanel`) renders a QR code into `#wwLoginPanel`.
4. Status updates: "正在准备二维码" → "请使用企业微信扫码登录" → "请在企业微信中确认登录".
5. User scans QR code in WeCom app.
6. `onLoginSuccess({code})` fires → `completeWeComLogin(code)`.
7. `POST /api/v1/wecom/login/exchange` with `{code, state}` returns `{viewerAuthToken, expiresIn}`.
8. Auth stored in `state.wecomAuth`, expiration computed as `Date.now() + expiresIn * 1000`.
9. Login screen hidden, shell shown.
10. `enterMessageCenter()` is called:
    - Fetches channel capabilities, templates, contacts.
    - Binds event handlers (buttons, scroll, keyboard, visibility).
    - Connects EventSource.
    - Starts 5-second refresh interval.

**Local dev mode (`LOCAL_DEV_MODE = true`):**
1. Login screen is hidden immediately, shell is shown.
2. A login attempt is still created via `POST /api/v1/wecom/login/attempts`.
3. `completeWeComLogin('local-development-code')` is called directly.
4. `enterMessageCenter()` proceeds normally.

**Auth expiration:**
- `currentWeComAuth()` checks if `Date.now() >= state.wecomAuthExpiresAt` and throws `WECOM_VIEWER_AUTH_EXPIRED` if so.
- `handleViewerAuthFailure()` catches 401/403 responses and calls `returnToWeComLogin()`.
- `returnToWeComLogin()` resets auth state, closes EventSource, stops refresh timer, clears call detail activity, shows login screen, and restarts the login flow.
- Multiple concurrent 401s are deduplicated via `state.viewerReloginPromise`.

### 6.2 View Contacts List

1. `loadContacts(false)` fetches `GET /api/contacts`.
2. `state.contacts` is populated.
3. `renderContacts()`:
   - Applies search filter from `#searchInput` (case-insensitive JSON substring match).
   - Renders each contact as a `.contact` div.
   - Active contact gets `.active` class.
   - Unread contacts get badge/dot.
4. Typing in `#searchInput` triggers `renderContacts()` on each `input` event (debounced by DOM input event timing, no artificial debounce).
5. The contact count in the top bar is updated.

### 6.3 View a Conversation Thread

1. User clicks a contact in the list. `selectContact(id)` fires.
2. If switching contacts: clear message/call selection, close profile modal, clear call detail activity.
3. `state.selectedPointId = id`, `state.selectedMessageId = ''`, `clearContactUnread(id)`.
4. `state.selectedChannel` is set to first matching channel from `contact.channels`.
5. `renderContacts()` re-renders to highlight the active contact.
6. `loadThread(id, false)`:
   - Increments `threadLoadSeqByContact[id]`.
   - Fetches `GET /api/v1/contacts/{id}/timeline?limit=10`.
   - On response: checks load sequence, merges messages, updates `state.threadPages[id]`, limits page size, updates LRU cache.
   - `renderThreadMessages()` builds DOM: message rows with direction classes, media previews, meta lines.
   - Scrolls to bottom (`threadEl.scrollTop = threadEl.scrollHeight`).
7. `renderComposer()` renders the composer with appropriate channel tabs.
8. `renderContactDetail(contact)` renders contact info in the detail pane.

**Infinite scroll (load older messages):**
1. When `#thread` scroll top is near 0 (<24px) OR user scrolls up via wheel or touch, `loadOlderThreadMessages()` fires.
2. Checks `nextCursor` and `isLoadingOlder` to prevent duplicate loads.
3. Fetches next page via `viewerApi(threadPageUrl(id, page.nextCursor))`.
4. On response: checks thread revision. If revision changed, reloads entire thread (discard cache). Otherwise merges older items to front.
5. Preserves scroll position by compensating for added height.

**Scroll debouncing:**
- Scroll surfaces get a `.scrolling` class and `state.isUserScrolling = true`.
- After 800ms of inactivity, scrolling state is cleared.
- While scrolling, silent refreshes are deferred (`state.pendingSilentRefresh = true`).

### 6.4 Send an Email

1. Select a contact with an email channel. Composer shows email tab.
2. Select recipient from dropdown (populated from `contact.points` filtered by `channel === 'email'`).
3. Enter subject and body.
4. Click "发送邮件" or press Enter.
5. `sendEmail()` validates `to` and `subject` are non-empty.
6. `POST /api/send/email` with `{to, subject, body}`.
7. On success: clear body, show toast "邮件已发送", call `refreshAll(false)`.

### 6.5 Send a ChatApp Message (text mode)

1. Select a contact with a ChatApp channel. Composer shows ChatApp tab.
2. Default mode is text. Can switch to template/image/video/document via icon buttons.
3. Enter message text. Can insert emoji via the emoji panel.
4. Press Enter (without Ctrl) or click "发送 WhatsApp".
5. `sendChatText()`: `POST /api/send/chatapp` with `{mode:'text', to, text, clientRequestId}`.
6. On success: clear text, toast, refresh all.

### 6.6 Send a ChatApp Template

1. Switch to template mode. Template dropdown populated from `state.templates`.
2. Dynamic placeholder fields render based on selected template's `placeholders` array.
3. Fill in placeholder values.
4. Click "发送模板".
5. `sendTemplate()`: `POST /api/send/chatapp` with `{mode:'template', to, templateCode, templateName, languageCode, templateParamsJson, clientRequestId}`.

### 6.7 Send ChatApp Media

1. Switch to image/video/document mode.
2. Select file by clicking the drop zone (a `<label>` for a hidden `<input type="file">`).
3. Optionally add a caption.
4. Click "发送附件".
5. `sendMedia()`: `POST /api/send/chatapp-media` as `FormData` with `{to, mediaType, caption, clientRequestId, file}`.

### 6.8 Upload Call Record

1. Switch composer to "电话记录" tab.
2. Select MP3 file (validated: `.mp3` extension, max `CALL_MAX_AUDIO_BYTES` = 100 MiB).
3. Select direction (呼入/呼出), occurrence time (datetime-local picker), phone point (if multiple phone numbers).
4. Click "上传录音".
5. `uploadCallRecord()` uses `XMLHttpRequest` (not `fetch`) to support upload progress:
   - `POST /api/v1/contacts/{contactId}/call-records` as `FormData`.
   - `xhr.upload.onprogress` updates the `<progress>` bar and status text.
   - On success: sets progress to 100, shows state, refreshes thread.
   - On error: shows error in status text and toast.

### 6.9 Merge Contacts (Drag-and-Drop)

1. Drag one contact row to another.
2. `ondrop` handler calls `POST /api/contact-groups/merge` with `{primaryPointId: target, mergedPointId: source}`.
3. `state.selectedPointId` is set to the target.
4. `refreshSelectedContactViews(false)` re-fetches contacts, thread, and composer.
5. Toast "联系人已合并".

### 6.10 Split Contact Account

1. In the detail pane, click the split icon button next to an account.
2. `splitAccount(pointId)` calls `POST /api/contact-groups/split` with `{primaryPointId: state.selectedPointId, pointToSplit: pointId}`.
3. Clears profile dirty state, selected message.
4. Refreshes selected contact views.
5. Toast "账号已拆分".

### 6.11 Edit Contact Profile

1. Click the edit pencil icon next to the thread title (visible when a contact is selected).
2. `openProfileModal()` populates inputs with current `remark` and `tags` (joined with Chinese comma "，"), shows modal, focuses nickname input.
3. Edit nickname and/or tags. `oninput` fires `markProfileDirty()` which sets `state.profileDirty = true` and shows "未保存".
4. Click "保存" or press Enter.
5. `saveContactProfile()`: `POST /api/contact-groups/profile` with `{contactPointId, nickname, tags}`.
6. On success: `refreshAll(false)`, re-render composer, close modal, toast "联系人资料已保存".
7. Cancel/close: reset dirty flag, hide modal.

### 6.12 View Message Detail

1. Click a message bubble in the thread.
2. `selectMessage(id)` sets `state.selectedMessageId = id`, toggles `.active` class on all messages, clears call detail.
3. Fetches `GET /api/messages?id={id}`.
4. `renderMessageDetail(m)` renders the detail pane with KV pairs and raw JSON.

### 6.13 View Call Record Detail

1. Click a call record card in the thread.
2. `openCallRecordDetail(id)` clears previous call detail, sets `state.selectedCallRecordId = id`, increments `state.callDetailGeneration`.
3. Shows "正在加载电话记录" placeholder.
4. Fetches `GET /api/v1/call-records/{id}`.
5. `renderCallRecordDetail(detail)` renders full detail panel with audio player, transcription, segments, revisions.
6. If transcription is `queued` or `processing`, starts polling (`scheduleCallDetailPoll`).
7. Calls `renewCallAudioSession()` to get an audio session. On success, starts periodic renewal (`startCallAudioRenewal()`).

**Polling details:**
- Polls `GET /api/v1/call-records/{id}` periodically.
- Delay starts at `CALL_DETAIL_POLL_MS` (3s), doubles with each failure up to `CALL_DETAIL_POLL_MAX_MS` (30s).
- After `CALL_DETAIL_POLL_MAX_FAILURES` (5) consecutive failures, stops.
- On each poll: updates detail panel (preserving audio element to avoid interrupting playback), refreshes thread if state changed to completed/failed.
- 401/403 on poll triggers re-login flow.

**Audio session renewal:**
- `POST /api/v1/call-records/{id}/audio-sessions` must be called to obtain a playback token.
- Session renewed every `CALL_AUDIO_RENEW_MS` (240s).
- If the `<audio>` element errors, attempts one recovery by renewing the session and reloading the audio (preserving `currentTime`).
- When the page visibility changes to hidden, stops renewal; when visible again, renews.

### 6.14 Revise Call Transcription

1. In call record detail, edit the text in the "修订转录" textarea.
2. Click "保存修订".
3. `reviseCallRecord()`: `PATCH /api/v1/call-records/{id}/transcript` with `{text, expectedVersion: detail.version}`.
4. On success: re-renders call detail, refreshes thread, toast "转录修订已保存".

### 6.15 Retry Call Transcription

1. If a call record's transcription failed with `error.retryable = true`, a "重新转录" button appears.
2. `retryCallRecord()`: `POST /api/v1/call-records/{id}/retry` with `{clientRequestId}`.
3. On success: re-renders detail, starts polling, refreshes thread, toast "已重新加入转录队列".

### 6.16 View WeCom Session

1. Select the WeCom tab in the composer (available for contacts with WeCom channel).
2. Click "打开企业微信会话".
3. `loadWeComViewer()`:
   - Creates session: `POST /api/v1/wecom/conversation-view/sessions`.
   - Fetches session detail: `GET /api/v1/wecom/conversation-view/sessions/{id}`.
   - In production: gets JS-SDK config, initializes WeCom SDK, creates OpenData frame with message list.
   - In local dev: renders sample messages.
4. The WeCom OpenData frame renders each message using `<ww-open-message>` components.
5. Component errors trigger re-login flow if auth expired, otherwise show error and audit via `POST /api/v1/wecom/conversation-view/events`.

### 6.17 Background Refresh

- Every 5 seconds, `refreshInBackground(true)` fires.
- Calls `loadContacts(true)` (silent) and if a contact is selected, `loadThread(id, true)` (keepScroll).
- If the user is currently scrolling (`state.isUserScrolling`), the refresh is deferred until scrolling stops.
- Auth failures during refresh trigger re-login.
- SSE events also trigger the same background refresh path.

## Key Architecture Observations

1. **No framework:** The entire SPA is imperative vanilla JavaScript with manual DOM manipulation via `innerHTML` and `document.querySelectorAll`.
2. **No virtual DOM diffing:** Render keys (`contactsRenderKey`, `threadRenderKeyByContact`) are manually computed strings used to skip re-renders when data hasn't changed.
3. **No client-side routing:** All navigation is done via state flags (`selectedPointId`, `selectedMessageId`, `selectedCallRecordId`, `selectedChannel`, `selectedMode`).
4. **Two API client variants:** `api()` for unauthenticated endpoints and `viewerApi()` which injects the WeCom viewer auth token. The distinction is hardcoded per endpoint.
5. **XHR for file upload:** Call record upload uses raw `XMLHttpRequest` instead of `fetch` to support upload progress events. All other uploads use `fetch`.
6. **Race condition handling:**
   - `threadLoadSeqByContact` for thread loads.
   - `callDetailGeneration` for call detail loads and polls.
7. **Scroll position preservation:** Thread scroll is preserved across refreshes by computing `oldBottom` (distance from scroll bottom) and restoring it after re-render.
8. **Auth flow is intertwined with UI:** The login screen and main shell are mutually exclusive DOM states, toggled by `hidden` attributes. Auth token is stored in `state.wecomAuth` and checked via `currentWeComAuth()` which throws synchronously if expired.
9. **Contact unread badge system** is purely client-side: it tracks deltas between consecutive `contactSnapshots` and only increments for inbound messages when the contact is not selected.
