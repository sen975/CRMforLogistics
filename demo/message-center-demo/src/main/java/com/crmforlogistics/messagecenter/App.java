package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class App {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        Config config = Config.load(java.nio.file.Path.of(".env"));
        String command = args.length == 0 ? "web" : args[0];
        if ("web".equalsIgnoreCase(command)) {
            startWeb(config);
            return;
        }
        if ("contacts".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new UnifiedMessageStore(config).contacts()));
            return;
        }
        System.out.println("Usage: ./message-center-demo.ps1 web");
    }

    private static void startWeb(Config config) throws Exception {
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        MailSender mailSender = new MailSender(config);
        ChatAppSender chatAppSender = new ChatAppSender(config);
        EventHub events = new EventHub();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.webPort()), 0);
        server.createContext("/", exchange -> {
            try {
                route(exchange, config, store, mailSender, chatAppSender, events);
            } catch (Exception ex) {
                writeJson(exchange, 500, Map.of("error", ex.getClass().getSimpleName(),
                        "message", ex.getMessage() == null ? "" : ex.getMessage()));
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Message center demo started: http://localhost:" + config.webPort());
        System.out.println("ChatApp webhook endpoint: http://localhost:" + config.webPort() + "/webhook/chatapp");
        System.out.println("WeCom webhook placeholder: http://localhost:" + config.webPort() + "/webhook/wecom");
    }

    private static void route(HttpExchange exchange, Config config, UnifiedMessageStore store, MailSender mailSender,
                              ChatAppSender chatAppSender, EventHub events) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/".equals(path)) {
            writeHtml(exchange, pageHtml());
            return;
        }
        if ("GET".equals(method) && "/events".equals(path)) {
            events.connect(exchange);
            return;
        }
        if ("GET".equals(method) && "/api/contacts".equals(path)) {
            writeJson(exchange, 200, store.contacts());
            return;
        }
        if ("GET".equals(method) && "/api/threads".equals(path)) {
            writeJson(exchange, 200, store.thread(query(exchange).getOrDefault("contactPointId", "")));
            return;
        }
        if ("GET".equals(method) && "/api/messages".equals(path)) {
            writeJson(exchange, 200, store.findMessage(query(exchange).getOrDefault("id", "")));
            return;
        }
        if ("GET".equals(method) && "/api/contact-groups".equals(path)) {
            writeJson(exchange, 200, store.contactGroup(query(exchange).getOrDefault("contactPointId", "")));
            return;
        }
        if ("GET".equals(method) && "/api/templates".equals(path)) {
            writeJson(exchange, 200, store.templates());
            return;
        }
        if ("GET".equals(method) && "/api/channel-capabilities".equals(path)) {
            writeJson(exchange, 200, List.of(store.channelCapability("email"),
                    store.channelCapability("chatapp"), store.channelCapability("wecom")));
            return;
        }
        if ("POST".equals(method) && "/api/contact-groups/merge".equals(path)) {
            JsonObject body = readJson(exchange);
            store.mergeContacts(json(body, "primaryPointId"), json(body, "mergedPointId"));
            writeJson(exchange, 200, store.contacts());
            return;
        }
        if ("POST".equals(method) && "/api/contact-groups/split".equals(path)) {
            JsonObject body = readJson(exchange);
            store.splitContact(json(body, "primaryPointId"), json(body, "pointToSplit"));
            writeJson(exchange, 200, store.contactGroup(json(body, "primaryPointId")));
            return;
        }
        if ("POST".equals(method) && "/api/send/email".equals(path)) {
            JsonObject body = readJson(exchange);
            UnifiedMessage message = mailSender.send(json(body, "to"), json(body, "subject"), json(body, "body"));
            events.publish(message);
            writeJson(exchange, 200, message);
            return;
        }
        if ("POST".equals(method) && "/api/send/chatapp".equals(path)) {
            JsonObject body = readJson(exchange);
            String mode = json(body, "mode");
            UnifiedMessage message;
            if ("template".equals(mode)) {
                message = chatAppSender.sendTemplate(json(body, "to"), json(body, "templateCode"),
                        json(body, "templateName"), json(body, "languageCode"),
                        TemplateStore.parseParams(json(body, "templateParamsJson")), json(body, "clientRequestId"));
            } else {
                message = chatAppSender.sendText(json(body, "to"), json(body, "text"), json(body, "clientRequestId"));
            }
            events.publish(message);
            writeJson(exchange, 200, message);
            return;
        }
        if ("POST".equals(method) && "/api/send/chatapp-media".equals(path)) {
            MultipartForm form = MultipartForm.parse(exchange, config.mediaMaxBytes());
            UnifiedMessage message = chatAppSender.sendMedia(form.fields.get("to"), form.fields.get("mediaType"),
                    form.file, form.fields.getOrDefault("caption", ""), form.fields.getOrDefault("clientRequestId", ""));
            events.publish(message);
            writeJson(exchange, 200, message);
            return;
        }
        if ("POST".equals(method) && "/webhook/chatapp".equals(path)) {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            UnifiedMessage message = chatAppSender.appendWebhook(raw);
            events.publish(message);
            writeJson(exchange, 200, Map.of("ok", true));
            return;
        }
        if ("POST".equals(method) && "/webhook/wecom".equals(path)) {
            writeJson(exchange, 501, Map.of("error", "WeComReserved",
                    "message", "企业微信 API 接入位已预留，当前 demo 未启用。"));
            return;
        }
        writeJson(exchange, 404, Map.of("error", "NotFound", "message", path));
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String json(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void writeHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static String pageHtml() {
        return """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>统一消息中心</title>
  <style>
    :root {
      --ink:#202124; --muted:#667085; --line:#d7dde7; --panel:#ffffff; --soft:#f4f7fb;
      --email:#2563eb; --chat:#0f9f6e; --wecom:#7c3aed; --warn:#b45309; --danger:#c2410c;
    }
    * { box-sizing:border-box; }
    body { margin:0; color:var(--ink); background:#e9eef5; font-family:Inter, "Segoe UI", Arial, sans-serif; letter-spacing:0; }
    button, input, textarea, select { font:inherit; }
    button { border:1px solid var(--line); background:#fff; color:var(--ink); border-radius:6px; padding:8px 10px; cursor:pointer; }
    button.primary { background:#1d4ed8; border-color:#1d4ed8; color:#fff; }
    button.ghost { background:transparent; }
    button:disabled { opacity:.48; cursor:not-allowed; }
    a { color:#1d4ed8; }
    .shell { height:100vh; min-height:680px; display:grid; grid-template-columns:320px minmax(430px, 1fr) 360px; gap:1px; background:var(--line); }
    .pane { background:var(--panel); min-height:0; display:flex; flex-direction:column; }
    .topbar { height:56px; flex:0 0 auto; display:flex; align-items:center; justify-content:space-between; gap:12px; padding:0 16px; border-bottom:1px solid var(--line); }
    .brand { font-weight:750; }
    .small { color:var(--muted); font-size:12px; }
    .search { padding:10px 12px; border-bottom:1px solid var(--line); }
    .search input, .field input, .field textarea, .field select { width:100%; border:1px solid var(--line); border-radius:6px; padding:9px 10px; background:#fff; min-width:0; }
    .field textarea { resize:vertical; min-height:76px; max-height:180px; }
    .contact-list { overflow:auto; min-height:0; }
    .contact { display:grid; grid-template-columns:42px 1fr; gap:10px; padding:12px; border-bottom:1px solid #edf0f5; cursor:pointer; }
    .contact:hover { background:#f8fbff; }
    .contact.active { background:#eef5ff; }
    .avatar { width:42px; height:42px; border-radius:50%; display:grid; place-items:center; color:#fff; font-weight:750; border:0; padding:0; background:linear-gradient(135deg,#1d4ed8,#0f9f6e); }
    .contact-name { font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .contact-last { margin-top:4px; color:var(--muted); font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .chips { display:flex; gap:5px; flex-wrap:wrap; margin-top:7px; }
    .chip { font-size:11px; padding:2px 6px; border-radius:999px; color:#fff; }
    .chip.email { background:var(--email); } .chip.chatapp { background:var(--chat); } .chip.wecom { background:var(--wecom); }
    .thread { flex:1 1 auto; min-height:0; overflow:auto; padding:18px; background:linear-gradient(#fbfcff,#f5f7fb); }
    .msg { max-width:76%; margin:0 0 12px; padding:10px 12px; border:1px solid var(--line); border-radius:8px; background:#fff; cursor:pointer; }
    .msg.outbound { margin-left:auto; border-color:#b7d4c6; background:#f0fbf6; }
    .msg.active { outline:2px solid #1d4ed8; }
    .msg-head { display:flex; gap:8px; justify-content:space-between; color:var(--muted); font-size:11px; margin-bottom:5px; }
    .msg-title { font-weight:700; margin-bottom:5px; overflow-wrap:anywhere; }
    .msg-text { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.45; }
    .status { margin-top:7px; color:#0f766e; font-size:11px; }
    .composer { flex:0 0 auto; border-top:1px solid var(--line); background:#fff; padding:12px; }
    .tabs { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
    .tabs button.active { border-color:#1d4ed8; color:#1d4ed8; background:#eff6ff; }
    .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
    .field { margin-bottom:10px; }
    .field label { display:block; color:var(--muted); font-size:12px; margin-bottom:5px; }
    .actions { display:flex; justify-content:flex-end; gap:8px; align-items:center; }
    .tools { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
    .tools button.active { background:#ecfdf3; color:#047857; border-color:#9ed8b8; }
    .emoji-panel { display:none; gap:6px; flex-wrap:wrap; margin-bottom:10px; }
    .emoji-panel.open { display:flex; }
    .emoji-panel button { width:34px; height:34px; padding:0; }
    .detail { overflow:auto; padding:16px; min-height:0; }
    .kv { display:grid; grid-template-columns:92px 1fr; gap:8px; padding:7px 0; border-bottom:1px solid #edf0f5; }
    pre { white-space:pre-wrap; overflow:auto; background:#101828; color:#e5e7eb; border-radius:8px; padding:12px; max-height:260px; }
    .toast { position:fixed; right:18px; bottom:18px; background:#202124; color:white; padding:10px 12px; border-radius:8px; box-shadow:0 12px 28px rgba(0,0,0,.22); display:none; z-index:20; }
    .modal { position:fixed; inset:0; background:rgba(15,23,42,.36); display:none; align-items:center; justify-content:center; z-index:30; }
    .dialog { width:min(520px, calc(100vw - 28px)); max-height:80vh; overflow:auto; background:#fff; border-radius:8px; padding:16px; box-shadow:0 24px 64px rgba(15,23,42,.28); }
    .point { display:flex; justify-content:space-between; gap:10px; align-items:center; padding:10px 0; border-bottom:1px solid #edf0f5; overflow-wrap:anywhere; }
    .empty { color:var(--muted); padding:24px; text-align:center; }
    @media (max-width: 980px) {
      .shell { height:auto; min-height:100vh; grid-template-columns:1fr; }
      .pane { min-height:360px; }
      .contact-list, .thread, .detail { max-height:58vh; }
      .msg { max-width:92%; }
      .grid2 { grid-template-columns:1fr; }
    }
  </style>
</head>
<body>
  <main class="shell">
    <section class="pane">
      <div class="topbar"><div><div class="brand">统一消息中心</div><div class="small" id="contactCount">0 个联系人</div></div><button id="notifyBtn">提醒</button></div>
      <div class="search"><input id="searchInput" placeholder="搜索联系人、邮箱、号码"></div>
      <div class="contact-list" id="contacts"></div>
    </section>
    <section class="pane">
      <div class="topbar"><div><div class="brand" id="threadTitle">选择联系人</div><div class="small" id="threadSub">邮件和 ChatApp 按时间穿插显示</div></div><button id="refreshBtn">刷新</button></div>
      <div class="thread" id="thread"><div class="empty">左侧选择一个联系人</div></div>
      <div class="composer" id="composer"></div>
    </section>
    <aside class="pane">
      <div class="topbar"><div class="brand">消息详情</div></div>
      <div class="detail" id="detail"><div class="empty">点击一条消息查看完整内容</div></div>
    </aside>
  </main>
  <div class="toast" id="toast"></div>
  <div class="modal" id="modal"><div class="dialog"><div class="topbar" style="padding:0 0 12px;border-bottom:0"><div class="brand">联系方式</div><button id="closeModal">关闭</button></div><div id="modalBody"></div></div></div>
  <script>
    const state = { contacts: [], templates: [], capabilities: {}, selectedPointId: '', selectedMessageId: '', selectedChannel: '', mediaType: 'image', lastKey: '' };
    const emojiSet = ['😀','😊','👍','🙏','✅','📦','🚚','📄','📍','⏱️','💬','📎'];
    const $ = id => document.getElementById(id);
    const api = async (url, options = {}) => {
      const response = await fetch(url, options);
      const data = await response.json();
      if (!response.ok) throw new Error(data.message || data.error || response.statusText);
      return data;
    };
    const postJson = (url, body) => api(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) });
    const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
    const timeText = value => value ? new Date(value).toLocaleString() : '';
    const initials = name => (name || '?').trim().slice(0, 2).toUpperCase();
    const requestId = () => (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const toast = text => { const el=$('toast'); el.textContent=text; el.style.display='block'; clearTimeout(window.toastTimer); window.toastTimer=setTimeout(()=>el.style.display='none',2600); };

    async function init() {
      const caps = await api('/api/channel-capabilities');
      caps.forEach(item => state.capabilities[item.channel] = item);
      state.templates = await api('/api/templates');
      await loadContacts(false);
      $('refreshBtn').onclick = () => refreshAll(false);
      $('searchInput').oninput = renderContacts;
      $('notifyBtn').onclick = enableNotifications;
      $('closeModal').onclick = () => $('modal').style.display = 'none';
      connectEvents();
      setInterval(() => refreshAll(true), 5000);
    }

    async function refreshAll(silent) {
      await loadContacts(silent);
      if (state.selectedPointId) await loadThread(state.selectedPointId, true);
    }

    async function loadContacts(silent) {
      const listEl = $('contacts');
      const top = listEl.scrollTop;
      state.contacts = await api('/api/contacts');
      const key = state.contacts.map(c => `${c.id}:${c.lastTime}:${c.messageCount}`).join('|');
      if (silent && state.lastKey && key !== state.lastKey) {
        toast('有新消息');
        if ('Notification' in window && Notification.permission === 'granted') {
          new Notification('统一消息中心', { body:'有新消息' });
        }
      }
      state.lastKey = key;
      renderContacts();
      requestAnimationFrame(() => listEl.scrollTop = top);
    }

    function renderContacts() {
      const term = $('searchInput').value.trim().toLowerCase();
      const filtered = state.contacts.filter(c => !term || JSON.stringify(c).toLowerCase().includes(term));
      $('contactCount').textContent = `${filtered.length} 个联系人`;
      $('contacts').innerHTML = filtered.map(c => `
        <div class="contact ${c.id === state.selectedPointId ? 'active' : ''}" draggable="true" data-id="${esc(c.id)}">
          <button class="avatar" data-avatar="${esc(c.id)}">${esc(initials(c.displayName))}</button>
          <div>
            <div class="contact-name">${esc(c.displayName)}</div>
            <div class="contact-last">${esc(c.lastText || '')}</div>
            <div class="chips">${c.channels.map(ch => `<span class="chip ${esc(ch)}">${esc(label(ch))}</span>`).join('')}</div>
          </div>
        </div>`).join('') || '<div class="empty">暂无联系人</div>';
      document.querySelectorAll('.contact').forEach(row => {
        row.onclick = event => { if (!event.target.closest('[data-avatar]')) selectContact(row.dataset.id); };
        row.ondragstart = event => event.dataTransfer.setData('text/plain', row.dataset.id);
        row.ondragover = event => event.preventDefault();
        row.ondrop = async event => {
          event.preventDefault();
          const source = event.dataTransfer.getData('text/plain');
          const target = row.dataset.id;
          if (source && target && source !== target) {
            await postJson('/api/contact-groups/merge', { primaryPointId: target, mergedPointId: source });
            state.selectedPointId = target;
            await refreshAll(false);
            toast('联系人已合并');
          }
        };
      });
      document.querySelectorAll('[data-avatar]').forEach(btn => btn.onclick = event => { event.stopPropagation(); openContactModal(btn.dataset.avatar); });
    }

    function label(channel) {
      return channel === 'email' ? '邮件' : channel === 'chatapp' ? 'WhatsApp' : channel === 'wecom' ? '企业微信' : channel;
    }

    function selectedContact() {
      return state.contacts.find(c => c.id === state.selectedPointId);
    }

    async function selectContact(id) {
      state.selectedPointId = id;
      state.selectedMessageId = '';
      const contact = state.contacts.find(c => c.id === id);
      if (contact) state.selectedChannel = contact.channels.includes(state.selectedChannel) ? state.selectedChannel : contact.channels[0];
      renderContacts();
      await loadThread(id, false);
      renderComposer();
      $('detail').innerHTML = '<div class="empty">点击一条消息查看完整内容</div>';
    }

    async function loadThread(id, keepScroll) {
      const threadEl = $('thread');
      const oldBottom = threadEl.scrollHeight - threadEl.scrollTop - threadEl.clientHeight;
      const contact = state.contacts.find(c => c.id === id);
      $('threadTitle').textContent = contact ? contact.displayName : '消息';
      $('threadSub').textContent = contact ? contact.points.map(p => p.value).join(' / ') : '';
      const messages = await api('/api/threads?contactPointId=' + encodeURIComponent(id));
      threadEl.innerHTML = messages.map(m => `
        <article class="msg ${esc(m.direction)} ${m.id === state.selectedMessageId ? 'active' : ''}" data-id="${esc(m.id)}">
          <div class="msg-head"><span>${esc(label(m.channel))}</span><span>${esc(timeText(m.timestamp))}</span></div>
          ${m.title ? `<div class="msg-title">${esc(m.title)}</div>` : ''}
          <div class="msg-text">${esc(m.text || m.summary || '')}</div>
          ${m.mediaUrl ? `<div class="status"><a href="${esc(m.mediaUrl)}" target="_blank">打开附件</a></div>` : ''}
          ${m.status ? `<div class="status">${esc(m.status)}</div>` : ''}
        </article>`).join('') || '<div class="empty">暂无消息</div>';
      document.querySelectorAll('.msg').forEach(item => item.onclick = () => selectMessage(item.dataset.id));
      if (keepScroll) requestAnimationFrame(() => threadEl.scrollTop = Math.max(0, threadEl.scrollHeight - threadEl.clientHeight - oldBottom));
      else requestAnimationFrame(() => threadEl.scrollTop = threadEl.scrollHeight);
    }

    async function selectMessage(id) {
      state.selectedMessageId = id;
      document.querySelectorAll('.msg').forEach(item => item.classList.toggle('active', item.dataset.id === id));
      const m = await api('/api/messages?id=' + encodeURIComponent(id));
      $('detail').innerHTML = `
        <div class="kv"><div class="small">渠道</div><div>${esc(label(m.channel))}</div></div>
        <div class="kv"><div class="small">方向</div><div>${esc(m.direction)}</div></div>
        <div class="kv"><div class="small">时间</div><div>${esc(timeText(m.timestamp))}</div></div>
        <div class="kv"><div class="small">From</div><div>${esc(m.from || '')}</div></div>
        <div class="kv"><div class="small">To</div><div>${esc(m.to || '')}</div></div>
        ${m.status ? `<div class="kv"><div class="small">状态</div><div>${esc(m.status)}</div></div>` : ''}
        ${m.title ? `<div class="kv"><div class="small">主题</div><div>${esc(m.title)}</div></div>` : ''}
        <h3>内容</h3><div class="msg-text">${esc(m.bodyText || m.text || '')}</div>
        ${m.raw ? `<h3>Raw</h3><pre>${esc(m.raw)}</pre>` : ''}`;
    }

    function renderComposer() {
      const contact = selectedContact();
      if (!contact) { $('composer').innerHTML = ''; return; }
      const channels = [...contact.channels, 'wecom'].filter((v,i,a)=>a.indexOf(v)===i);
      if (!channels.includes(state.selectedChannel)) state.selectedChannel = channels[0];
      $('composer').innerHTML = `
        <div class="tabs">${channels.map(ch => `<button class="${state.selectedChannel===ch?'active':''}" data-channel="${esc(ch)}" ${ch==='wecom'?'disabled':''}>${esc(label(ch))}</button>`).join('')}</div>
        <div id="sendPanel"></div>`;
      document.querySelectorAll('[data-channel]').forEach(btn => btn.onclick = () => { state.selectedChannel = btn.dataset.channel; renderComposer(); });
      renderSendPanel(contact);
    }

    function renderSendPanel(contact) {
      const panel = $('sendPanel');
      const point = contact.points.find(p => p.channel === state.selectedChannel);
      if (state.selectedChannel === 'email') {
        panel.innerHTML = `
          <div class="field"><label>收件人</label><input id="emailTo" value="${esc(point?.value || '')}"></div>
          <div class="field"><label>主题</label><input id="emailSubject"></div>
          <div class="field"><label>正文</label><textarea id="emailBody"></textarea></div>
          <div class="actions"><button class="primary" id="sendEmail">发送邮件</button></div>`;
        $('sendEmail').onclick = sendEmail;
        return;
      }
      if (state.selectedChannel === 'chatapp') {
        panel.innerHTML = `
          <div class="tools"><button class="active" data-mode="text">文本</button><button data-mode="template">模板</button><button data-mode="image">图片</button><button data-mode="video">视频</button><button data-mode="document">文件</button></div>
          <div id="chatModePanel"></div>`;
        document.querySelectorAll('[data-mode]').forEach(btn => btn.onclick = () => {
          document.querySelectorAll('[data-mode]').forEach(b=>b.classList.remove('active'));
          btn.classList.add('active');
          renderChatMode(btn.dataset.mode, point?.value || '');
        });
        renderChatMode('text', point?.value || '');
        return;
      }
      panel.innerHTML = '<div class="empty">企业微信 API 接入位已预留</div>';
    }

    function renderChatMode(mode, to) {
      const panel = $('chatModePanel');
      if (mode === 'text') {
        panel.innerHTML = `
          <div class="field"><label>收件号码</label><input id="chatTo" value="${esc(to)}"></div>
          <div class="field"><label>消息</label><textarea id="chatText"></textarea></div>
          <div class="emoji-panel" id="emojiPanel">${emojiSet.map(item => `<button data-emoji="${esc(item)}">${esc(item)}</button>`).join('')}</div>
          <div class="actions"><button id="emojiBtn" type="button">表情</button><button class="primary" id="sendChatText">发送 WhatsApp</button></div>`;
        $('chatText').onkeydown = e => { if (e.key === 'Enter' && !e.ctrlKey) { e.preventDefault(); sendChatText(); } };
        $('emojiBtn').onclick = () => $('emojiPanel').classList.toggle('open');
        document.querySelectorAll('[data-emoji]').forEach(btn => btn.onclick = () => appendToTextarea('chatText', btn.dataset.emoji));
        $('sendChatText').onclick = sendChatText;
        return;
      }
      if (mode === 'template') {
        if (!state.templates.length) {
          panel.innerHTML = '<div class="empty">模板库为空</div>';
          return;
        }
        panel.innerHTML = `
          <div class="grid2"><div class="field"><label>收件号码</label><input id="tplTo" value="${esc(to)}"></div><div class="field"><label>模板</label><select id="tplSelect">${state.templates.map(t => `<option value="${esc(t.templateCode)}">${esc(t.templateName || t.templateCode)} / ${esc(t.languageCode || '')}</option>`).join('')}</select></div></div>
          <div id="tplFields"></div>
          <div class="actions"><button class="primary" id="sendTemplate">发送模板</button></div>`;
        $('tplSelect').onchange = renderTemplateFields;
        $('sendTemplate').onclick = sendTemplate;
        renderTemplateFields();
        return;
      }
      state.mediaType = mode;
      panel.innerHTML = `
        <div class="grid2"><div class="field"><label>收件号码</label><input id="mediaTo" value="${esc(to)}"></div><div class="field"><label>${esc(labelMedia(mode))}</label><input id="mediaFile" type="file"></div></div>
        <div class="field"><label>说明</label><textarea id="mediaCaption"></textarea></div>
        <div class="actions"><button class="primary" id="sendMedia">发送附件</button></div>`;
      $('sendMedia').onclick = sendMedia;
    }

    function renderTemplateFields() {
      const tpl = state.templates.find(t => t.templateCode === $('tplSelect').value) || {};
      const keys = tpl.placeholders && tpl.placeholders.length ? tpl.placeholders : ['text'];
      $('tplFields').innerHTML = keys.map(k => `<div class="field"><label>${esc(k)}</label><input data-param="${esc(k)}"></div>`).join('');
    }

    function labelMedia(mode) {
      return mode === 'image' ? '图片' : mode === 'video' ? '视频' : '文件';
    }

    function appendToTextarea(id, text) {
      const field = $(id);
      const start = field.selectionStart || field.value.length;
      const end = field.selectionEnd || field.value.length;
      field.value = field.value.slice(0, start) + text + field.value.slice(end);
      field.focus();
      field.selectionStart = field.selectionEnd = start + text.length;
    }

    async function sendEmail() {
      await postJson('/api/send/email', { to:$('emailTo').value, subject:$('emailSubject').value, body:$('emailBody').value });
      $('emailBody').value = '';
      toast('邮件已发送');
      await refreshAll(false);
    }

    async function sendChatText() {
      await postJson('/api/send/chatapp', { mode:'text', to:$('chatTo').value, text:$('chatText').value, clientRequestId: requestId() });
      $('chatText').value = '';
      toast('WhatsApp 已发送');
      await refreshAll(false);
    }

    async function sendTemplate() {
      const tpl = state.templates.find(t => t.templateCode === $('tplSelect').value) || {};
      const params = {};
      document.querySelectorAll('[data-param]').forEach(input => params[input.dataset.param] = input.value);
      await postJson('/api/send/chatapp', { mode:'template', to:$('tplTo').value, templateCode:tpl.templateCode, templateName:tpl.templateName, languageCode:tpl.languageCode, templateParamsJson:JSON.stringify(params), clientRequestId: requestId() });
      toast('模板消息已发送');
      await refreshAll(false);
    }

    async function sendMedia() {
      const file = $('mediaFile').files[0];
      if (!file) { toast('请选择文件'); return; }
      const form = new FormData();
      form.append('to', $('mediaTo').value);
      form.append('mediaType', state.mediaType);
      form.append('caption', $('mediaCaption').value);
      form.append('clientRequestId', requestId());
      form.append('file', file);
      await api('/api/send/chatapp-media', { method:'POST', body:form });
      toast('附件已发送');
      await refreshAll(false);
    }

    async function openContactModal(id) {
      const points = await api('/api/contact-groups?contactPointId=' + encodeURIComponent(id));
      $('modalBody').innerHTML = points.map(p => `<div class="point"><div>${esc(p)}</div><button data-split="${esc(p)}" ${points.length<=1?'disabled':''}>分开</button></div>`).join('');
      document.querySelectorAll('[data-split]').forEach(btn => btn.onclick = async () => {
        await postJson('/api/contact-groups/split', { primaryPointId:id, pointToSplit:btn.dataset.split });
        $('modal').style.display='none';
        await refreshAll(false);
        toast('联系方式已分开');
      });
      $('modal').style.display = 'flex';
    }

    function connectEvents() {
      try {
        const source = new EventSource('/events');
        source.onmessage = async () => { toast('有新消息'); await refreshAll(true); };
      } catch (e) {}
    }

    async function enableNotifications() {
      if (!('Notification' in window)) { toast('浏览器不支持系统通知'); return; }
      const result = await Notification.requestPermission();
      toast(result === 'granted' ? '提醒已开启' : '提醒未开启');
    }

    init().catch(err => toast(err.message));
  </script>
</body>
</html>
""";
    }

    private static class EventHub {
        private final List<HttpExchange> clients = new CopyOnWriteArrayList<>();

        void connect(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            clients.add(exchange);
            exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }

        void publish(UnifiedMessage message) {
            if (message == null) {
                return;
            }
            String payload = "data: " + GSON.toJson(message) + "\n\n";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            for (HttpExchange client : clients) {
                try {
                    client.getResponseBody().write(bytes);
                    client.getResponseBody().flush();
                } catch (IOException ex) {
                    clients.remove(client);
                    client.close();
                }
            }
        }
    }

    private static class MultipartForm {
        final Map<String, String> fields = new LinkedHashMap<>();
        ChatAppSender.UploadedFile file;

        static MultipartForm parse(HttpExchange exchange, long maxBytes) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("boundary=")) {
                throw new IllegalArgumentException("multipart/form-data is required");
            }
            String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + "boundary=".length())
                    .replace("\"", "").trim();
            byte[] bytes = readBody(exchange.getRequestBody(), maxBytes);
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            MultipartForm form = new MultipartForm();
            for (String part : raw.split(java.util.regex.Pattern.quote(boundary))) {
                if (part.isBlank() || part.equals("--\r\n") || part.equals("--")) {
                    continue;
                }
                int split = part.indexOf("\r\n\r\n");
                if (split < 0) {
                    continue;
                }
                String headers = part.substring(0, split);
                String body = part.substring(split + 4);
                if (body.endsWith("\r\n")) {
                    body = body.substring(0, body.length() - 2);
                }
                if (body.endsWith("--")) {
                    body = body.substring(0, body.length() - 2);
                }
                String name = headerParam(headers, "name");
                String filename = headerParam(headers, "filename");
                if (name.isBlank()) {
                    continue;
                }
                if (!filename.isBlank()) {
                    ChatAppSender.UploadedFile uploaded = new ChatAppSender.UploadedFile();
                    uploaded.fileName = filename;
                    uploaded.contentType = contentTypeHeader(headers);
                    uploaded.bytes = body.getBytes(StandardCharsets.ISO_8859_1);
                    form.file = uploaded;
                } else {
                    form.fields.put(name, new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
                }
            }
            if (form.file == null) {
                form.file = new ChatAppSender.UploadedFile();
                form.file.bytes = new byte[0];
            }
            return form;
        }

        private static byte[] readBody(InputStream input, long maxBytes) throws IOException {
            long limit = Math.max(1, maxBytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IllegalArgumentException("upload size exceeds MEDIA_MAX_BYTES");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }

        private static String headerParam(String headers, String name) {
            String marker = name + "=\"";
            int start = headers.indexOf(marker);
            if (start < 0) {
                return "";
            }
            start += marker.length();
            int end = headers.indexOf('"', start);
            return end < 0 ? "" : headers.substring(start, end);
        }

        private static String contentTypeHeader(String headers) {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-type:")) {
                    return line.substring("content-type:".length()).trim();
                }
            }
            return "";
        }
    }
}
