package com.crmforlogistics.emaildemo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public class InboxWebServer {
    private final MailConfig config;
    private final InboxStore inboxStore;
    private HttpServer server;

    public InboxWebServer(MailConfig config, InboxStore inboxStore) {
        this.config = config;
        this.inboxStore = inboxStore;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.webPort()), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/contacts", this::handleContacts);
        server.createContext("/api/threads", this::handleThreads);
        server.createContext("/api/messages", this::handleMessages);
        server.createContext("/api/contact-groups", this::handleContactGroups);
        server.createContext("/api/send", this::handleSend);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[INFO] Mail conversation page: http://localhost:" + config.webPort());
    }

    public void startAndBlock() throws Exception {
        start();
        new CountDownLatch(1).await();
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method Not Allowed");
            return;
        }
        send(exchange, 200, "text/html; charset=UTF-8", pageHtml());
    }

    private void handleContacts(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=UTF-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            List<ContactRecord> contacts = inboxStore.contacts();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < contacts.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(contactJson(contacts.get(i)));
            }
            json.append(']');
            send(exchange, 200, "application/json; charset=UTF-8", json.toString());
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=UTF-8", "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=UTF-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/threads/";
            if (!path.startsWith(prefix)) {
                send(exchange, 404, "application/json; charset=UTF-8", "{\"error\":\"Not Found\"}");
                return;
            }
            String email = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
            List<InboxRecord> records = inboxStore.listByContact(email);
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < records.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(messageJson(records.get(i), false));
            }
            json.append(']');
            send(exchange, 200, "application/json; charset=UTF-8", json.toString());
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=UTF-8", "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=UTF-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/messages/";
            if (!path.startsWith(prefix)) {
                send(exchange, 404, "application/json; charset=UTF-8", "{\"error\":\"Not Found\"}");
                return;
            }
            String id = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
            InboxRecord record = inboxStore.findById(id);
            if (record == null) {
                send(exchange, 404, "application/json; charset=UTF-8", "{\"error\":\"Not Found\"}");
                return;
            }
            send(exchange, 200, "application/json; charset=UTF-8", messageJson(record, true));
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=UTF-8", "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleContactGroups(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(method)) {
                String prefix = "/api/contact-groups/";
                if (!path.startsWith(prefix)) {
                    send(exchange, 404, "application/json; charset=UTF-8", "{\"error\":\"Not Found\"}");
                    return;
                }
                String email = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
                send(exchange, 200, "application/json; charset=UTF-8", contactGroupJson(email, inboxStore.contactGroup(email)));
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                send(exchange, 405, "application/json; charset=UTF-8", "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> payload = InboxStore.parseFlatJson(body);
            if (path.endsWith("/merge")) {
                inboxStore.mergeContacts(payload.getOrDefault("primaryEmail", ""), payload.getOrDefault("mergedEmail", ""));
                send(exchange, 200, "application/json; charset=UTF-8", "{\"ok\":true}");
                return;
            }
            if (path.endsWith("/split")) {
                inboxStore.splitContact(payload.getOrDefault("primaryEmail", ""), payload.getOrDefault("email", ""));
                send(exchange, 200, "application/json; charset=UTF-8", "{\"ok\":true}");
                return;
            }
            send(exchange, 404, "application/json; charset=UTF-8", "{\"error\":\"Not Found\"}");
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=UTF-8", "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=UTF-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> payload = InboxStore.parseFlatJson(body);
            String to = payload.getOrDefault("to", "").trim();
            String subject = payload.getOrDefault("subject", "").trim();
            String text = payload.getOrDefault("bodyText", "").trim();
            if (to.isBlank() || subject.isBlank()) {
                send(exchange, 400, "application/json; charset=UTF-8", "{\"error\":\"to and subject are required\"}");
                return;
            }
            String messageId = new SmtpMailer(config).send(to, subject, text.isBlank() ? subject : text);
            inboxStore.appendOutgoing(to, subject, text.isBlank() ? subject : text, messageId);
            send(exchange, 200, "application/json; charset=UTF-8", "{\"ok\":true,\"messageId\":\"" + jsonEscape(messageId) + "\"}");
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=UTF-8", "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String contactJson(ContactRecord contact) {
        return "{"
                + "\"email\":\"" + jsonEscape(contact.email()) + "\","
                + "\"name\":\"" + jsonEscape(contact.name()) + "\","
                + "\"lastSubject\":\"" + jsonEscape(contact.lastSubject()) + "\","
                + "\"lastTime\":\"" + jsonEscape(contact.lastTime()) + "\","
                + "\"messageCount\":" + contact.messageCount()
                + "}";
    }

    private static String messageJson(InboxRecord record, boolean includeBody) {
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append("\"id\":\"").append(jsonEscape(record.id())).append("\",")
                .append("\"storedAt\":\"").append(jsonEscape(record.storedAt())).append("\",")
                .append("\"direction\":\"").append(jsonEscape(record.direction())).append("\",")
                .append("\"contactEmail\":\"").append(jsonEscape(record.contactEmail())).append("\",")
                .append("\"contactName\":\"").append(jsonEscape(record.contactName())).append("\",")
                .append("\"from\":\"").append(jsonEscape(record.from())).append("\",")
                .append("\"to\":\"").append(jsonEscape(record.to())).append("\",")
                .append("\"subject\":\"").append(jsonEscape(record.subject())).append("\",")
                .append("\"sentDate\":\"").append(jsonEscape(record.sentDate())).append("\",")
                .append("\"summary\":\"").append(jsonEscape(record.summary())).append("\",")
                .append("\"messageId\":\"").append(jsonEscape(record.messageId())).append("\"");
        if (includeBody) {
            json.append(",\"bodyText\":\"").append(jsonEscape(record.bodyText())).append("\"");
        }
        json.append('}');
        return json.toString();
    }

    private static String contactGroupJson(String primaryEmail, List<String> emails) {
        StringBuilder json = new StringBuilder("{\"primaryEmail\":\"")
                .append(jsonEscape(primaryEmail))
                .append("\",\"emails\":[");
        for (int i = 0; i < emails.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(jsonEscape(emails.get(i))).append('"');
        }
        json.append("]}");
        return json.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String pageHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Mail Conversation Demo</title>
                  <style>
                    * { box-sizing: border-box; }
                    html, body { width: 100%; height: 100%; overflow: hidden; }
                    body { margin: 0; font-family: Arial, "Microsoft YaHei", sans-serif; color: #182230; background: #eef2f7; }
                    button, input, textarea { font: inherit; }
                    button { border: 0; cursor: pointer; }
                    .app { display: grid; grid-template-columns: 340px minmax(420px, 1fr) 420px; width: 100vw; height: 100vh; overflow: hidden; }
                    .contacts, .chat, .detail { min-height: 0; min-width: 0; }
                    .contacts { display: grid; grid-template-rows: 58px 1fr; background: #f7f9fc; border-right: 1px solid #d9e0ea; overflow: hidden; }
                    .contacts-header { display: flex; align-items: center; justify-content: space-between; padding: 0 16px; }
                    .contacts-header h1 { margin: 0; font-size: 18px; font-weight: 700; }
                    .refresh { width: 34px; height: 34px; border-radius: 8px; background: #e7effa; color: #175cd3; }
                    .contacts-list { min-height: 0; overflow-y: scroll; overflow-x: hidden; scrollbar-gutter: stable; }
                    .contact { display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; gap: 10px; align-items: center; width: 100%; padding: 12px 14px; border-left: 3px solid transparent; background: transparent; text-align: left; }
                    .contact:hover, .contact.active, .contact.drag-over { background: #e8edf5; border-left-color: #2e90fa; }
                    .contact-main { min-width: 0; text-align: left; background: transparent; padding: 0; }
                    .avatar { width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center; background: linear-gradient(135deg, #d6e9ff, #f5d7ff); color: #175cd3; font-weight: 800; border: 1px solid #c7ddf7; }
                    .name { font-size: 15px; font-weight: 700; color: #101828; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .preview { margin-top: 4px; color: #667085; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .time { color: #98a2b3; font-size: 12px; white-space: nowrap; }
                    .chat { display: grid; grid-template-rows: 58px minmax(0, 1fr) auto; background: #f3f6fb; overflow: hidden; }
                    .chat-header { display: flex; align-items: center; justify-content: space-between; padding: 0 18px; border-bottom: 1px solid #d9e0ea; background: rgba(255,255,255,.72); }
                    .chat-title { min-width: 0; }
                    .chat-title strong { display: block; font-size: 17px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .chat-title span { display: block; color: #667085; font-size: 12px; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .thread { min-height: 0; padding: 24px 18px; overflow-y: scroll; overflow-x: hidden; scrollbar-gutter: stable; }
                    .bubble-row { display: flex; margin: 10px 0; }
                    .bubble-row.out { justify-content: flex-end; }
                    .bubble { max-width: min(560px, 78%); border-radius: 14px; padding: 11px 14px; box-shadow: 0 1px 2px rgba(16,24,40,.08); text-align: left; }
                    .bubble.in { background: #fff; color: #101828; }
                    .bubble.out { background: #bfe3ff; color: #062c41; }
                    .bubble.active { outline: 2px solid #2e90fa; }
                    .bubble-subject { font-weight: 700; line-height: 1.45; overflow-wrap: anywhere; }
                    .bubble-meta { color: #667085; font-size: 12px; margin-top: 6px; }
                    .composer { border-top: 1px solid #d9e0ea; background: #fff; padding: 12px 16px; max-height: 216px; overflow-y: scroll; scrollbar-gutter: stable; }
                    .composer input, .composer textarea { width: 100%; border: 1px solid #d0d5dd; border-radius: 10px; padding: 10px 12px; outline: none; }
                    .composer input:focus, .composer textarea:focus { border-color: #2e90fa; box-shadow: 0 0 0 3px rgba(46,144,250,.16); }
                    .composer textarea { height: 72px; resize: vertical; min-height: 48px; max-height: 120px; margin-top: 8px; }
                    .composer-actions { display: flex; gap: 12px; justify-content: space-between; align-items: center; margin-top: 8px; color: #667085; font-size: 12px; }
                    .send-status { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                    .send { flex: 0 0 auto; background: #1570ef; color: #fff; border-radius: 10px; padding: 9px 16px; font-weight: 700; }
                    .detail { background: #fff; border-left: 1px solid #d9e0ea; display: grid; grid-template-rows: 58px minmax(0, 1fr); overflow: hidden; }
                    .detail-header { display: flex; align-items: center; padding: 0 18px; border-bottom: 1px solid #d9e0ea; font-weight: 700; }
                    .detail-body { min-height: 0; overflow-y: scroll; overflow-x: hidden; padding: 20px; scrollbar-gutter: stable; }
                    .detail-body h2 { margin: 0 0 14px; font-size: 20px; line-height: 1.35; overflow-wrap: anywhere; }
                    .kv { color: #667085; font-size: 13px; line-height: 1.8; margin-bottom: 16px; overflow-wrap: anywhere; }
                    pre { white-space: pre-wrap; word-break: break-word; font: 14px/1.7 Consolas, "Microsoft YaHei", monospace; background: #f8fafc; border: 1px solid #e4e9f1; padding: 16px; border-radius: 10px; }
                    .group-panel { position: fixed; left: 356px; top: 70px; width: min(360px, calc(100vw - 374px)); max-height: min(460px, calc(100vh - 96px)); overflow-y: auto; background: #fff; border: 1px solid #d9e0ea; border-radius: 10px; box-shadow: 0 18px 42px rgba(16,24,40,.18); padding: 14px; z-index: 20; }
                    .group-panel.hidden { display: none; }
                    .group-head { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-bottom: 10px; }
                    .group-head strong { overflow-wrap: anywhere; }
                    .group-close { width: 30px; height: 30px; border-radius: 8px; background: #eef2f7; color: #344054; }
                    .group-email { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 8px 0; border-top: 1px solid #edf1f6; }
                    .group-email span { overflow-wrap: anywhere; color: #344054; font-size: 13px; }
                    .group-email em { color: #667085; font-size: 12px; font-style: normal; }
                    .group-email button { border-radius: 8px; padding: 7px 10px; background: #fee4e2; color: #b42318; }
                    .empty { color: #667085; padding: 22px; }
                    .error { color: #b42318; }
                    @media (max-width: 1100px) {
                      .app { grid-template-columns: 300px minmax(0, 1fr); }
                      .detail { display: none; }
                    }
                    @media (max-width: 760px) {
                      .app { display: grid; grid-template-columns: 1fr; grid-template-rows: 34vh 66vh; }
                      .contacts { border-right: 0; border-bottom: 1px solid #d9e0ea; }
                      .chat { grid-template-rows: 52px minmax(0, 1fr) auto; }
                      .composer { max-height: 190px; }
                      .group-panel { left: 18px; top: 48px; width: min(360px, calc(100vw - 36px)); }
                    }
                  </style>
                </head>
                <body>
                  <div class="app">
                    <aside class="contacts">
                      <div class="contacts-header">
                        <h1>Messages</h1>
                        <button class="refresh" onclick="loadContacts()" title="Refresh">R</button>
                      </div>
                      <div class="contacts-list" id="contacts"><div class="empty">Loading...</div></div>
                    </aside>

                    <main class="chat">
                      <div class="chat-header">
                        <div class="chat-title">
                          <strong id="chatName">Select a contact</strong>
                          <span id="chatEmail">Each contact has an email conversation.</span>
                        </div>
                      </div>
                      <div class="thread" id="thread"><div class="empty">Select a contact to view email subjects.</div></div>
                      <form class="composer" id="composer">
                        <input id="subject" placeholder="Email subject" autocomplete="off">
                        <textarea id="bodyText" placeholder="Email body"></textarea>
                        <div class="composer-actions">
                          <span class="send-status" id="sendStatus">Choose a contact before sending.</span>
                          <button class="send" type="submit">Send email</button>
                        </div>
                      </form>
                    </main>

                    <section class="detail">
                      <div class="detail-header">Email content</div>
                      <div class="detail-body" id="detail"><div class="empty">Click an email subject to view the body.</div></div>
                    </section>
                  </div>
                  <div class="group-panel hidden" id="groupPanel"></div>

                  <script>
                    let contacts = [];
                    let activeEmail = '';
                    let activeMessageId = '';
                    let draggedEmail = '';

                    async function loadContacts(options = {}) {
                      const box = document.getElementById('contacts');
                      const previousScrollTop = box.scrollTop;
                      const preserveScroll = options.preserveScroll !== false;
                      const res = await fetch('/api/contacts');
                      contacts = await res.json();
                      if (!contacts.length) {
                        box.innerHTML = '<div class="empty">No contacts yet. Run receive or watch-web first.</div>';
                        return;
                      }
                      box.innerHTML = contacts.map(c => `
                        <div class="contact ${c.email === activeEmail ? 'active' : ''}" data-email="${escapeAttr(c.email)}" draggable="true">
                          <button class="avatar" type="button" title="View merged mailboxes">${escapeHtml(initials(c.name || c.email))}</button>
                          <button class="contact-main" type="button">
                            <div class="name">${escapeHtml(c.name || c.email)}</div>
                            <div class="preview">${escapeHtml(c.lastSubject || '(No subject)')}</div>
                          </button>
                          <div class="time">${escapeHtml(shortTime(c.lastTime))}</div>
                        </div>
                      `).join('');
                      document.querySelectorAll('.contact').forEach(el => {
                        el.querySelector('.contact-main').addEventListener('click', () => selectContact(el.dataset.email));
                        el.querySelector('.avatar').addEventListener('click', event => {
                          event.stopPropagation();
                          showContactGroup(el.dataset.email);
                        });
                        el.addEventListener('dragstart', event => {
                          draggedEmail = el.dataset.email;
                          event.dataTransfer.effectAllowed = 'move';
                          event.dataTransfer.setData('text/plain', draggedEmail);
                        });
                        el.addEventListener('dragover', event => {
                          event.preventDefault();
                          if (draggedEmail && draggedEmail !== el.dataset.email) {
                            el.classList.add('drag-over');
                            event.dataTransfer.dropEffect = 'move';
                          }
                        });
                        el.addEventListener('dragleave', () => el.classList.remove('drag-over'));
                        el.addEventListener('drop', async event => {
                          event.preventDefault();
                          el.classList.remove('drag-over');
                          const sourceEmail = event.dataTransfer.getData('text/plain') || draggedEmail;
                          await mergeContact(el.dataset.email, sourceEmail);
                        });
                      });
                      if (preserveScroll) {
                        box.scrollTop = previousScrollTop;
                      }
                      if (!activeEmail && contacts[0]) {
                        selectContact(contacts[0].email);
                      }
                    }

                    async function mergeContact(primaryEmail, mergedEmail) {
                      if (!primaryEmail || !mergedEmail || primaryEmail === mergedEmail) return;
                      await fetch('/api/contact-groups/merge', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ primaryEmail, mergedEmail })
                      });
                      if (activeEmail === mergedEmail || activeEmail === primaryEmail) {
                        activeEmail = primaryEmail;
                      }
                      await loadContacts({ preserveScroll: true });
                      await selectContact(activeEmail || primaryEmail);
                      await showContactGroup(primaryEmail);
                    }

                    async function showContactGroup(email) {
                      const panel = document.getElementById('groupPanel');
                      const res = await fetch('/api/contact-groups/' + encodeURIComponent(email));
                      const group = await res.json();
                      const emails = group.emails || [email];
                      panel.classList.remove('hidden');
                      panel.innerHTML = `
                        <div class="group-head">
                          <strong>${escapeHtml(email)}</strong>
                          <button class="group-close" type="button" title="Close">X</button>
                        </div>
                        ${emails.map((item, index) => `
                          <div class="group-email">
                            <span>${escapeHtml(item)}</span>
                            ${index === 0 ? '<em>Primary</em>' : `<button type="button" data-email="${escapeAttr(item)}">Split</button>`}
                          </div>
                        `).join('')}
                      `;
                      panel.querySelector('.group-close').addEventListener('click', () => panel.classList.add('hidden'));
                      panel.querySelectorAll('.group-email button').forEach(button => {
                        button.addEventListener('click', async () => {
                          await splitContact(email, button.dataset.email);
                        });
                      });
                    }

                    async function splitContact(primaryEmail, email) {
                      await fetch('/api/contact-groups/split', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ primaryEmail, email })
                      });
                      await loadContacts({ preserveScroll: true });
                      if (activeEmail === primaryEmail) {
                        await loadThread(primaryEmail, { preserveScroll: true, scrollToBottom: false });
                      }
                      await showContactGroup(primaryEmail);
                    }

                    async function selectContact(email) {
                      activeEmail = email;
                      activeMessageId = '';
                      const contact = contacts.find(c => c.email === email) || { email };
                      document.getElementById('chatName').textContent = contact.name || email;
                      document.getElementById('chatEmail').textContent = email;
                      document.getElementById('sendStatus').textContent = 'Sending to ' + email;
                      await loadThread(email, { scrollToBottom: true });
                      await loadContacts({ preserveScroll: true });
                    }

                    async function loadThread(email, options = {}) {
                      const thread = document.getElementById('thread');
                      const previousScrollTop = thread.scrollTop;
                      const preserveScroll = options.preserveScroll === true;
                      const shouldScrollToBottom = options.scrollToBottom !== false;
                      const res = await fetch('/api/threads/' + encodeURIComponent(email));
                      const messages = await res.json();
                      if (!messages.length) {
                        thread.innerHTML = '<div class="empty">No email for this contact yet.</div>';
                        return;
                      }
                      thread.innerHTML = messages.map(m => `
                        <div class="bubble-row ${m.direction === 'out' ? 'out' : 'in'}">
                          <button class="bubble ${m.direction === 'out' ? 'out' : 'in'} ${m.id === activeMessageId ? 'active' : ''}" data-id="${escapeAttr(m.id)}">
                            <div class="bubble-subject">${escapeHtml(m.subject || '(No subject)')}</div>
                            <div class="bubble-meta">${m.direction === 'out' ? 'Sent' : 'Received'} - ${escapeHtml(shortTime(m.sentDate || m.storedAt))}</div>
                          </button>
                        </div>
                      `).join('');
                      document.querySelectorAll('.bubble').forEach(el => {
                        el.addEventListener('click', () => loadDetail(el.dataset.id));
                      });
                      if (preserveScroll) {
                        thread.scrollTop = previousScrollTop;
                      } else if (shouldScrollToBottom) {
                        thread.scrollTop = thread.scrollHeight;
                      }
                    }

                    function markActiveMessage() {
                      document.querySelectorAll('.bubble').forEach(el => {
                        el.classList.toggle('active', el.dataset.id === activeMessageId);
                      });
                    }

                    async function loadDetail(id) {
                      activeMessageId = id;
                      markActiveMessage();
                      const res = await fetch('/api/messages/' + encodeURIComponent(id));
                      const m = await res.json();
                      document.getElementById('detail').innerHTML = `
                        <h2>${escapeHtml(m.subject || '(No subject)')}</h2>
                        <div class="kv">
                          Direction: ${m.direction === 'out' ? 'Sent' : 'Received'}<br>
                          From: ${escapeHtml(m.from || '(Unknown)')}<br>
                          To: ${escapeHtml(m.to || '(Unknown)')}<br>
                          Time: ${escapeHtml(m.sentDate || m.storedAt || '')}
                        </div>
                        <pre>${escapeHtml(m.bodyText || '(No displayable body)')}</pre>
                      `;
                    }

                    document.getElementById('composer').addEventListener('submit', async event => {
                      event.preventDefault();
                      const subject = document.getElementById('subject').value.trim();
                      const bodyText = document.getElementById('bodyText').value.trim();
                      const status = document.getElementById('sendStatus');
                      if (!activeEmail) {
                        status.innerHTML = '<span class="error">Select a contact first.</span>';
                        return;
                      }
                      if (!subject) {
                        status.innerHTML = '<span class="error">Enter an email subject.</span>';
                        return;
                      }
                      status.textContent = 'Sending...';
                      const res = await fetch('/api/send', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ to: activeEmail, subject, bodyText })
                      });
                      const result = await res.json();
                      if (!res.ok || result.error) {
                        status.innerHTML = '<span class="error">' + escapeHtml(result.error || 'Send failed') + '</span>';
                        return;
                      }
                      document.getElementById('subject').value = '';
                      document.getElementById('bodyText').value = '';
                      status.textContent = 'Sent. The email is shown in this conversation.';
                      await loadThread(activeEmail, { scrollToBottom: true });
                      await loadContacts({ preserveScroll: true });
                    });

                    function initials(value) {
                      const text = String(value || '?').trim();
                      return text.slice(0, 2).toUpperCase();
                    }

                    function shortTime(value) {
                      if (!value) return '';
                      return String(value).replace(' CST ', ' ').slice(0, 16);
                    }

                    function escapeHtml(value) {
                      return String(value)
                        .replaceAll('&', '&amp;')
                        .replaceAll('<', '&lt;')
                        .replaceAll('>', '&gt;')
                        .replaceAll('"', '&quot;')
                        .replaceAll("'", '&#39;');
                    }

                    function escapeAttr(value) {
                      return escapeHtml(value).replaceAll('`', '&#96;');
                    }

                    loadContacts();
                    setInterval(() => {
                      loadContacts({ preserveScroll: true });
                      if (activeEmail) loadThread(activeEmail, { preserveScroll: true, scrollToBottom: false });
                    }, 5000);
                  </script>
                </body>
                </html>
                """;
    }
}

