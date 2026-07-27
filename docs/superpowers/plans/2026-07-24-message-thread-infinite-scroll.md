# Message Thread Infinite Scroll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `demo/message-center-demo` 当前消息展示页默认每个联系人只加载最近 10 条消息，并在消息窗口向上滚到顶部时继续加载更早消息。

**Architecture:** 后端 `UnifiedMessageStore` 增加稳定的线程页模型，旧 `/api/threads` 返回 `{ items, nextCursor, messageCount, threadRevision }` 页对象；前端内嵌 JS 只保存当前联系人已加载页，并在 `#thread` 滚动到顶部附近时请求更早一页。旧 `thread(String)` 保持原行为供现有测试和调用链使用，分页语义贴近 OpenAPI v1 的 `MessagePage`。

**Tech Stack:** Java 17、JDK `HttpServer`、Gson、Maven、内嵌 HTML/CSS/JavaScript、`UnifiedMessageStoreTest` 自运行测试入口。

## Global Constraints

- 全程只使用中文说明和用户可见文案。
- 保护当前工作区已有未提交改动，尤其是 `App.java` 中企业微信路由、`bubbleText`、邮件发送校验，以及 `UnifiedMessageStore.java` 中企业微信消息读取。
- 不重写 React，不引入虚拟列表库，不改变联系人合并、拆分、未读、发送、附件预览和媒体代理语义。
- 默认线程页大小是 10，向上滚动每次加载更早 10 条。
- 后端拥有消息排序、cursor、limit、`nextCursor`、`messageCount` 和 `threadRevision` 真相；前端不得用数组下标或联系人列表旧快照伪造全局分页。
- 不使用深层 `offset` 分页。
- 修改同一文件前先查看当前 diff，避免覆盖用户改动。

---

## File Structure

- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
  - 负责新增旧运行面的线程分页 facade：`threadPage(String contactPointId, String cursor, int limit)`。
  - 保持 `thread(String contactPointId)` 返回完整正序时间线，避免破坏现有调用和测试。
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
  - 负责 `/api/threads` 参数解析和页对象返回。
  - 负责内嵌前端的每联系人线程状态、初始加载、向上滚动加载和滚动位置保持。
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`
  - 负责后端线程分页、cursor 稳定性和内嵌前端 JS 合同测试。
- Modify: `demo/message-center-demo/README.md`
  - 记录页面能力中消息线程懒加载行为。

---

### Task 1: 后端线程页模型

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `List<UnifiedMessage> thread(String contactPointId)` existing full ascending timeline.
- Consumes: `MessageRepository.unifiedTimeline(UUID userId, UUID contactId, MessageCursor cursor, int limit)` existing database-backed timeline query.
- Produces: `public ThreadPage threadPage(String contactPointId, String cursor, int limit) throws IOException`.
- Produces: `public static class ThreadPage { public List<UnifiedMessage> items; public String nextCursor; public int messageCount; public String threadRevision; }`.

- [ ] **Step 1: 写失败测试：首次只返回最近 10 条并给出 cursor**

Add this method call in `UnifiedMessageStoreTest.main` after `splitsLegacyEmailContactGroupWhenOnlyOnePointRemains();`:

```java
        threadPageReturnsRecentTenMessagesAndCursor();
```

Add this test method in `UnifiedMessageStoreTest` near the other thread/contact tests:

```java
    private static void threadPageReturnsRecentTenMessagesAndCursor() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-page-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        StringBuilder jsonl = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            jsonl.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"message-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        Files.writeString(chatData.resolve("messages.jsonl"), jsonl.toString(), StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedMessageStore.ThreadPage firstPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);

        assertEquals(10, firstPage.items.size());
        assertEquals("message-16", firstPage.items.getFirst().text);
        assertEquals("message-25", firstPage.items.getLast().text);
        assertTrue(firstPage.nextCursor != null && !firstPage.nextCursor.isBlank(),
                "first page must expose nextCursor for older messages");

        UnifiedMessageStore.ThreadPage secondPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", firstPage.nextCursor, 10);
        assertEquals(10, secondPage.items.size());
        assertEquals("message-6", secondPage.items.getFirst().text);
        assertEquals("message-15", secondPage.items.getLast().text);
        assertTrue(secondPage.nextCursor != null && !secondPage.nextCursor.isBlank(),
                "second page must expose nextCursor for the oldest remaining messages");

        UnifiedMessageStore.ThreadPage thirdPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", secondPage.nextCursor, 10);
        assertEquals(5, thirdPage.items.size());
        assertEquals("message-1", thirdPage.items.getFirst().text);
        assertEquals("message-5", thirdPage.items.getLast().text);
        assertNull(thirdPage.nextCursor, "oldest page must not expose nextCursor");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，错误包含 `cannot find symbol` 和 `threadPage` 或 `ThreadPage`，证明测试覆盖的是新行为。

- [ ] **Step 3: 写最小实现**

In `UnifiedMessageStore.java`, add imports:

```java
import java.util.Base64;
```

Add constants near the fields:

```java
    private static final int DEFAULT_THREAD_LIMIT = 10;
    private static final int MAX_THREAD_LIMIT = 50;
```

Add this page type and cursor type inside `UnifiedMessageStore`:

```java
    public static class ThreadPage {
        public final List<UnifiedMessage> items;
        public final String nextCursor;

        ThreadPage(List<UnifiedMessage> items, String nextCursor) {
            this.items = items;
            this.nextCursor = nextCursor;
        }
    }

    private record ThreadCursor(Instant timestamp, String messageId) {}
```

Add `threadPage` after the existing `thread(String contactPointId)` method:

```java
    public ThreadPage threadPage(String contactPointId, String cursor, int limit) throws IOException {
        int safeLimit = threadLimit(limit);
        if (databaseBacked()) {
            try {
                ThreadCursor decoded = decodeThreadCursor(cursor);
                MessageCursor messageCursor = decoded == null ? null
                        : new MessageCursor(decoded.timestamp(), UUID.fromString(decoded.messageId()));
                List<UnifiedMessage> fetched = messageRepository.unifiedTimeline(
                        userId, UUID.fromString(contactPointId), messageCursor, safeLimit + 1);
                return toThreadPage(fetched, safeLimit);
            } catch (Exception exception) {
                throw databaseFailure("Unable to query contact timeline page", exception);
            }
        }

        List<UnifiedMessage> all = thread(contactPointId);
        ThreadCursor decoded = decodeThreadCursor(cursor);
        int endExclusive = all.size();
        if (decoded != null) {
            endExclusive = 0;
            for (int i = 0; i < all.size(); i++) {
                if (compareMessageToCursor(all.get(i), decoded) >= 0) {
                    endExclusive = i;
                    break;
                }
            }
        }
        int startInclusive = Math.max(0, endExclusive - safeLimit);
        List<UnifiedMessage> items = new ArrayList<>(all.subList(startInclusive, endExclusive));
        String nextCursor = startInclusive > 0 && !items.isEmpty() ? encodeThreadCursor(items.getFirst()) : null;
        return new ThreadPage(items, nextCursor);
    }
```

Add helper methods near other private helpers:

```java
    private static int threadLimit(int limit) {
        if (limit <= 0) return DEFAULT_THREAD_LIMIT;
        return Math.max(1, Math.min(MAX_THREAD_LIMIT, limit));
    }

    private static ThreadPage toThreadPage(List<UnifiedMessage> fetched, int safeLimit) {
        boolean hasMore = fetched.size() > safeLimit;
        List<UnifiedMessage> items = hasMore
                ? new ArrayList<>(fetched.subList(1, fetched.size()))
                : new ArrayList<>(fetched);
        String nextCursor = hasMore && !items.isEmpty() ? encodeThreadCursor(items.getFirst()) : null;
        return new ThreadPage(items, nextCursor);
    }

    private static String encodeThreadCursor(UnifiedMessage message) {
        if (message == null || message.timestamp == null || message.timestamp.isBlank()) return null;
        String id = stableMessageId(message);
        if (id.isBlank()) return null;
        JsonObject object = new JsonObject();
        object.addProperty("timestamp", MessageTime.parseInstant(message.timestamp).toString());
        object.addProperty("id", id);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(object.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ThreadCursor decodeThreadCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            JsonObject object = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            String timestamp = JsonSupport.string(object, "timestamp");
            String id = JsonSupport.string(object, "id");
            if (timestamp.isBlank() || id.isBlank()) return null;
            return new ThreadCursor(MessageTime.parseInstant(timestamp), id);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid thread cursor");
        }
    }

    private static int compareMessageToCursor(UnifiedMessage message, ThreadCursor cursor) {
        int time = MessageTime.parseInstant(message.timestamp).compareTo(cursor.timestamp());
        if (time != 0) return time;
        return stableMessageId(message).compareTo(cursor.messageId());
    }

    private static String stableMessageId(UnifiedMessage message) {
        return ContactPointUtil.firstNonBlank(message.id, message.sourceId);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/UnifiedMessageStore.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: add paged message thread facade"
```

Expected: commit only contains `UnifiedMessageStore.java` and `UnifiedMessageStoreTest.java` changes from this task plus pre-existing user edits in those files only if they were already present and intentionally included by the user.

---

### Task 2: 旧 `/api/threads` 返回页对象

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `UnifiedMessageStore.threadPage(String contactPointId, String cursor, int limit)`.
- Produces: `GET /api/threads?contactPointId=<id>&limit=10&cursor=<cursor>` returns JSON object with `items` and `nextCursor`.
- Produces: `private static int intQuery(Map<String, String> query, String name, int defaultValue)`.

- [ ] **Step 1: 写失败测试：内嵌前端请求页对象而不是完整数组**

Add this method call in `UnifiedMessageStoreTest.main` after `rendersWebShellWithChineseCopyAndUnifiedSendActions();`:

```java
        rendersWebShellWithPagedThreadRequestContract();
```

Add this test method near the other `App.pageHtml()` tests:

```java
    private static void rendersWebShellWithPagedThreadRequestContract() {
        String html = App.pageHtml();

        assertContains(html, "const THREAD_PAGE_SIZE = 10;");
        assertContains(html, "const page = await api(threadPageUrl(id));");
        assertContains(html, "const messages = page.items || [];");
        assertContains(html, "nextCursor: page.nextCursor || null");
        assertContains(html, "function threadPageUrl(id, cursor = '')");
        assertContains(html, "'/api/threads?contactPointId=' + encodeURIComponent(id) + '&limit=' + THREAD_PAGE_SIZE");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，错误来自 `assertContains`，提示缺少 `THREAD_PAGE_SIZE` 或 `threadPageUrl`。

- [ ] **Step 3: 修改路由返回页对象**

In `App.java`, replace the current `/api/threads` branch:

```java
        if ("GET".equals(method) && "/api/threads".equals(path)) {
            writeJson(exchange, 200, store.thread(query(exchange).getOrDefault("contactPointId", "")));
            return;
        }
```

with:

```java
        if ("GET".equals(method) && "/api/threads".equals(path)) {
            Map<String, String> params = query(exchange);
            writeJson(exchange, 200, store.threadPage(
                    params.getOrDefault("contactPointId", ""),
                    params.getOrDefault("cursor", ""),
                    intQuery(params, "limit", 10)));
            return;
        }
```

Add this helper near the existing `json(JsonObject object, String name)` helper:

```java
    private static int intQuery(Map<String, String> query, String name, int defaultValue) {
        String value = query.get(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
```

- [ ] **Step 4: 添加前端页请求常量和 URL helper**

In `App.pageHtml()`, replace the current state declaration:

```javascript
    const state = { contacts: [], templates: [], capabilities: {}, selectedPointId: '', selectedMessageId: '', selectedChannel: '', selectedMode: 'text', mediaType: 'image', lastKey: '', contactsRenderKey:'', threadRenderKeyByContact:{}, detailCollapsed:false, profileDirty:false, profileSavedPointId:'', profileSavedTimer:null, selectedPointByChannel:{}, contactSnapshots:{}, unreadByContact:{}, isUserScrolling:false, pendingSilentRefresh:false };
```

with:

```javascript
    const THREAD_PAGE_SIZE = 10;
    const state = { contacts: [], templates: [], capabilities: {}, selectedPointId: '', selectedMessageId: '', selectedChannel: '', selectedMode: 'text', mediaType: 'image', lastKey: '', contactsRenderKey:'', threadRenderKeyByContact:{}, threadPages:{}, detailCollapsed:false, profileDirty:false, profileSavedPointId:'', profileSavedTimer:null, selectedPointByChannel:{}, contactSnapshots:{}, unreadByContact:{}, isUserScrolling:false, pendingSilentRefresh:false };
```

Add this helper after `requestId`:

```javascript
    function threadPageUrl(id, cursor = '') {
      let url = '/api/threads?contactPointId=' + encodeURIComponent(id) + '&limit=' + THREAD_PAGE_SIZE;
      if (cursor) url += '&cursor=' + encodeURIComponent(cursor);
      return url;
    }
```

- [ ] **Step 5: 修改 `loadThread` 读取页对象**

In `App.pageHtml()`, replace the first half of `loadThread` through `const key = ...`:

```javascript
      const contact = state.contacts.find(c => c.id === id);
      const messages = await api('/api/threads?contactPointId=' + encodeURIComponent(id));
      const key = threadRenderKey(contact, messages);
      if (keepScroll && key === state.threadRenderKeyByContact[id]) {
        return;
      }
      state.threadRenderKeyByContact[id] = key;
```

with:

```javascript
      const contact = state.contacts.find(c => c.id === id);
      const page = await api(threadPageUrl(id));
      const messages = page.items || [];
      state.threadPages[id] = {
        items: messages,
        nextCursor: page.nextCursor || null,
        isLoadingOlder: false,
        hasLoadedInitial: true
      };
      const key = threadRenderKey(contact, messages);
      if (keepScroll && key === state.threadRenderKeyByContact[id]) {
        return;
      }
      state.threadRenderKeyByContact[id] = key;
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: return paged thread responses"
```

Expected: commit only includes route/front-end contract changes and the corresponding test changes.

---

### Task 3: 向上滚动加载更早消息

**Files:**
- Modify: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`
- Modify: `demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java`

**Interfaces:**
- Consumes: `state.threadPages[id].items`, `state.threadPages[id].nextCursor`, `threadPageUrl(id, cursor)`.
- Produces: `loadOlderThreadMessages()` triggered from `#thread` scroll when `scrollTop <= 24`.
- Produces: `renderThreadMessages(contact, messages)` shared by initial render and older-page prepend.

- [ ] **Step 1: 写失败测试：页面绑定顶部滚动加载和滚动位置保持**

Add this method call in `UnifiedMessageStoreTest.main` after `rendersWebShellWithPagedThreadRequestContract();`:

```java
        rendersWebShellWithOlderThreadScrollLoader();
```

Add this test method near the other `App.pageHtml()` tests:

```java
    private static void rendersWebShellWithOlderThreadScrollLoader() {
        String html = App.pageHtml();

        assertContains(html, "loadOlderThreadMessages();");
        assertContains(html, "async function loadOlderThreadMessages()");
        assertContains(html, "if (!page || !page.nextCursor || page.isLoadingOlder) return;");
        assertContains(html, "const oldScrollHeight = threadEl.scrollHeight;");
        assertContains(html, "page.items = mergeThreadMessages([...(older.items || []), ...page.items]);");
        assertContains(html, "threadEl.scrollTop = threadEl.scrollHeight - oldScrollHeight + oldScrollTop;");
        assertContains(html, "function mergeThreadMessages(messages)");
        assertContains(html, "renderThreadMessages(contact, page.items);");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: FAIL，错误来自 `assertContains`，提示缺少 `loadOlderThreadMessages`。

- [ ] **Step 3: 拆出线程渲染函数**

In `App.pageHtml()`, extract the current `threadEl.innerHTML = messages.map(...)` block from `loadThread` into this function placed before `loadThread`:

```javascript
    function renderThreadMessages(contact, messages) {
      updateContactHeader(contact);
      const threadEl = $('thread');
      threadEl.innerHTML = messages.map(m => {
        const direction = m.direction === 'outbound' ? 'outbound' : 'inbound';
        const meta = [
          `<span>${esc(label(m.channel))}</span>`,
          m.timestamp ? `<span>${esc(timeText(m.timestamp))}</span>` : '',
          `<span class="status-icon ${esc(statusClass(m))}" title="${esc(statusText(m))}" aria-label="${esc(statusText(m))}"></span>`,
          hasMedia(m) ? `<button class="media-open-link" type="button" data-open-media="${esc(mediaUrl(m))}" data-file-name="${esc(m.fileName || 'attachment')}">打开附件</button>` : ''
        ].filter(Boolean).join('');
        const text = bubbleText(m);
        return `
          <div class="message-row ${esc(direction)}">
            <div class="msg-avatar"><span class="avatar-icon ${esc(m.channel || '')}"></span></div>
            <div class="msg-stack">
              <article class="msg ${esc(direction)} ${hasMedia(m) ? 'has-media' : ''} ${m.id === state.selectedMessageId ? 'active' : ''}" data-id="${esc(m.id)}">
                ${m.title ? `<div class="msg-title">${esc(m.title)}</div>` : ''}
                ${text ? `<div class="msg-text">${esc(text)}</div>` : ''}
                ${mediaPreviewHtml(m)}
              </article>
              <div class="msg-meta-line">${meta}</div>
            </div>
          </div>`;
      }).join('') || '<div class="empty">暂无消息</div>';
      document.querySelectorAll('.msg[data-id]').forEach(item => item.onclick = () => selectMessage(item.dataset.id));
      document.querySelectorAll('[data-open-media]').forEach(item => item.onclick = event => openAttachment(event, item.dataset.openMedia, item.dataset.fileName));
    }
```

Then in `loadThread`, replace the duplicated render block with:

```javascript
      renderThreadMessages(contact, messages);
```

- [ ] **Step 4: 添加合并去重 helper**

Add this function after `threadRenderKey`:

```javascript
    function mergeThreadMessages(messages) {
      const byId = new Map();
      messages.forEach(message => {
        const key = message.id || message.sourceId || `${message.timestamp || ''}:${message.channel || ''}:${message.text || message.summary || ''}`;
        byId.set(key, message);
      });
      return Array.from(byId.values()).sort((a, b) => {
        const time = new Date(a.timestamp || 0).getTime() - new Date(b.timestamp || 0).getTime();
        if (time !== 0) return time;
        return String(a.id || a.sourceId || '').localeCompare(String(b.id || b.sourceId || ''));
      });
    }
```

- [ ] **Step 5: 在滚动绑定中触发历史加载**

In `bindScrollSurfaces`, replace:

```javascript
        if (el) el.onscroll = () => markScrollSurfaceScrolling(el);
```

with:

```javascript
        if (el) el.onscroll = () => {
          markScrollSurfaceScrolling(el);
          if (id === 'thread') loadOlderThreadMessages();
        };
```

- [ ] **Step 6: 添加 `loadOlderThreadMessages`**

Add this function after `loadThread`:

```javascript
    async function loadOlderThreadMessages() {
      const id = state.selectedPointId;
      if (!id) return;
      const page = state.threadPages[id];
      if (!page || !page.nextCursor || page.isLoadingOlder) return;
      const threadEl = $('thread');
      if (!threadEl || threadEl.scrollTop > 24) return;
      page.isLoadingOlder = true;
      const oldScrollHeight = threadEl.scrollHeight;
      const oldScrollTop = threadEl.scrollTop;
      try {
        const older = await api(threadPageUrl(id, page.nextCursor));
        page.items = mergeThreadMessages([...(older.items || []), ...page.items]);
        page.nextCursor = older.nextCursor || null;
        const contact = state.contacts.find(c => c.id === id);
        state.threadRenderKeyByContact[id] = threadRenderKey(contact, page.items);
        renderThreadMessages(contact, page.items);
        requestAnimationFrame(() => {
          threadEl.scrollTop = threadEl.scrollHeight - oldScrollHeight + oldScrollTop;
        });
      } catch (err) {
        toast(`加载历史消息失败：${err.message}`);
      } finally {
        page.isLoadingOlder = false;
      }
    }
```

- [ ] **Step 7: 调整静默刷新保留已加载历史**

In `loadThread`, after reading the latest page, replace the `state.threadPages[id] = ...` block from Task 2 with:

```javascript
      const existing = state.threadPages[id];
      const merged = keepScroll && existing && existing.hasLoadedInitial
        ? mergeThreadMessages([...existing.items, ...messages])
        : messages;
      state.threadPages[id] = {
        items: merged,
        nextCursor: existing && keepScroll ? existing.nextCursor : (page.nextCursor || null),
        isLoadingOlder: false,
        hasLoadedInitial: true
      };
```

Then use `const messagesForRender = state.threadPages[id].items;` for `threadRenderKey` and `renderThreadMessages`:

```javascript
      const messagesForRender = state.threadPages[id].items;
      const key = threadRenderKey(contact, messagesForRender);
      if (keepScroll && key === state.threadRenderKeyByContact[id]) {
        return;
      }
      state.threadRenderKeyByContact[id] = key;
      renderThreadMessages(contact, messagesForRender);
```

Keep the existing bottom-distance scroll behavior:

```javascript
      if (keepScroll) requestAnimationFrame(() => threadEl.scrollTop = Math.max(0, threadEl.scrollHeight - threadEl.clientHeight - oldBottom));
      else requestAnimationFrame(() => threadEl.scrollTop = threadEl.scrollHeight);
```

- [ ] **Step 8: 运行测试确认通过**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS。

- [ ] **Step 9: 编译**

Run:

```bash
cd demo/message-center-demo && mvn -q test
```

Expected: PASS，无编译错误。

- [ ] **Step 10: 提交**

Run:

```bash
git diff --check -- demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git add demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java demo/message-center-demo/src/test/java/com/crmforlogistics/messagecenter/UnifiedMessageStoreTest.java
git commit -m "feat: load older messages on thread scroll"
```

Expected: commit only includes scroll loading behavior and tests for this task.

---

### Task 4: 文档与浏览器验收

**Files:**
- Modify: `demo/message-center-demo/README.md`

**Interfaces:**
- Consumes: Completed paged `/api/threads` endpoint and front-end scroll loader.
- Produces: README statement that message threads initially load the latest 10 messages and load older messages when scrolling upward.

- [ ] **Step 1: 写 README 变更**

In `demo/message-center-demo/README.md`, under `## 页面能力`, replace:

```markdown
- 邮件和 ChatApp 消息在同一个聊天时间线里按时间穿插。
```

with:

```markdown
- 邮件、ChatApp 和企业微信消息在同一个聊天时间线里按时间穿插。
- 选择联系人时默认只加载最近 10 条消息，向上滚到消息区顶部会继续按 10 条加载更早消息。
```

- [ ] **Step 2: 运行后端回归**

Run:

```bash
cd demo/message-center-demo && mvn -q -Dtest=UnifiedMessageStoreTest test
```

Expected: PASS。

- [ ] **Step 3: 启动 8100 验收服务**

Run:

```bash
cd demo/message-center-demo && WEB_PORT=8100 mvn -q exec:java "-Dexec.args=web"
```

Expected: 服务输出包含 `Message center demo started: http://localhost:8100`。保持该进程运行用于手工或 Playwright 验收。

- [ ] **Step 4: 浏览器验收**

Open:

```text
http://localhost:8100
```

Expected:

- 选择消息数超过 10 的联系人后，消息窗口首屏只出现最近 10 条。
- 消息区初始滚动到底部。
- 向上滚到顶部附近后加载更早 10 条。
- 加载后当前阅读位置不跳到顶部或底部。
- 多次上滚后最终不再请求更早页。
- 点击消息详情、打开附件、发送消息入口仍可用。

- [ ] **Step 5: 关闭验收服务并确认端口释放**

Stop the Maven web process with `Ctrl-C`, then run:

```bash
lsof -nP -iTCP:8100 -sTCP:LISTEN
```

Expected: no output。

- [ ] **Step 6: 提交 README**

Run:

```bash
git diff --check -- demo/message-center-demo/README.md
git add demo/message-center-demo/README.md
git commit -m "docs: document message thread lazy loading"
```

Expected: commit only includes README change from this task.

---

## Final Verification

- Run:

```bash
cd demo/message-center-demo && mvn -q test
```

Expected: PASS。

- Run:

```bash
git status --short
```

Expected: only unrelated pre-existing user changes remain, or a clean tree if the user chose to include those changes in earlier commits.

- Review:

```bash
git log --oneline --stat -5
```

Expected: recent commits or the current staged set cover only message thread pagination, scroll loading, and README documentation files; no unrelated files are staged.
