package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        if ("bootstrap-admin".equalsIgnoreCase(command)) {
            BootstrapResult result = bootstrapAdmin(config);
            System.out.println(GSON.toJson(Map.of(
                    "created", result.created(),
                    "userId", result.userId() == null ? "" : result.userId().toString(),
                    "code", result.code())));
            return;
        }
        if ("contacts".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new UnifiedMessageStore(config).contacts()));
            return;
        }
        if ("receive".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new EmailSyncService(config).receiveLatest()));
            return;
        }
        if ("sync".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new ChatAppHistorySyncService(config).syncMessages()));
            return;
        }
        if ("sync-templates".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new ChatAppHistorySyncService(config).syncTemplates()));
            return;
        }
        System.out.println("Usage: ./message-center-demo.ps1 web|bootstrap-admin|contacts|receive|sync|sync-templates");
    }

    static BootstrapResult bootstrapAdmin(Config config) throws Exception {
        try (Database database = Database.open(config)) {
            database.migrate();
            SessionService sessions = new SessionService(
                    new JdbcAuthRepository(database),
                    PasswordHasher.argon2id(),
                    new AuditService(database),
                    Clock.systemUTC(),
                    Duration.ofHours(8));
            return sessions.bootstrapAdmin(config.bootstrapAdminUsername(),
                    () -> readSecretChars(config.bootstrapAdminPasswordFile()));
        }
    }

    static char[] readSecretChars(Path path) throws IOException {
        final int maximumCharacters = 1024;
        char[] buffer = new char[maximumCharacters + 1];
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            int count = 0;
            while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count > maximumCharacters || reader.read() >= 0) {
                throw new IOException("Bootstrap admin password file is too large");
            }
            while (count > 0 && (buffer[count - 1] == '\n' || buffer[count - 1] == '\r')) count--;
            if (count == 0) throw new IOException("Bootstrap admin password file is empty");
            return Arrays.copyOf(buffer, count);
        } catch (IOException exception) {
            throw new IOException("Unable to read bootstrap admin password file: " + path, exception);
        } finally {
            Arrays.fill(buffer, '\0');
        }
    }

    private static void startWeb(Config config) throws Exception {
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        MailSender mailSender = new MailSender(config);
        ChatAppSender chatAppSender = new ChatAppSender(config);
        EmailSyncService emailSyncService = new EmailSyncService(config);
        ChatAppHistorySyncService chatAppSyncService = new ChatAppHistorySyncService(config);
        EventHub events = new EventHub();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.webPort()), 0);
        server.createContext("/", exchange -> {
            try {
                route(exchange, config, store, mailSender, chatAppSender, emailSyncService, chatAppSyncService, events);
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
                              ChatAppSender chatAppSender, EmailSyncService emailSyncService,
                              ChatAppHistorySyncService chatAppSyncService, EventHub events) throws Exception {
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
            Map<String, String> params = query(exchange);
            writeJson(exchange, 200, store.threadPage(
                    params.getOrDefault("contactPointId", ""),
                    params.getOrDefault("cursor", ""),
                    intQuery(params, "limit", 10)));
            return;
        }
        if ("GET".equals(method) && "/api/messages".equals(path)) {
            writeJson(exchange, 200, store.findMessage(query(exchange).getOrDefault("id", "")));
            return;
        }
        if ("GET".equals(method) && "/api/media".equals(path)) {
            UnifiedMessage message = store.findMessage(query(exchange).getOrDefault("id", ""));
            try {
                writeMedia(exchange, new MediaGateway(config).fetch(message));
            } catch (MediaGateway.MediaUnavailableException ex) {
                writeJson(exchange, 410, Map.of("error", "MediaUnavailable", "message", ex.getMessage()));
            }
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
        if ("POST".equals(method) && "/api/contact-groups/remark".equals(path)) {
            JsonObject body = readJson(exchange);
            store.updateContactRemark(json(body, "contactPointId"), json(body, "remark"));
            writeJson(exchange, 200, store.contacts());
            return;
        }
        if ("POST".equals(method) && "/api/contact-groups/profile".equals(path)) {
            JsonObject body = readJson(exchange);
            store.updateContactProfile(json(body, "contactPointId"), json(body, "nickname"), splitTags(json(body, "tags")));
            writeJson(exchange, 200, store.contacts());
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
        if ("POST".equals(method) && "/api/sync/email".equals(path)) {
            SyncResult result = emailSyncService.receiveLatest();
            events.publish(syncEvent("email", result.message));
            writeJson(exchange, 200, result);
            return;
        }
        if ("POST".equals(method) && "/api/sync/chatapp".equals(path)) {
            SyncResult result = chatAppSyncService.syncMessages();
            events.publish(syncEvent("chatapp", result.message));
            writeJson(exchange, 200, result);
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

    private static UnifiedMessage syncEvent(String channel, String messageText) {
        UnifiedMessage message = new UnifiedMessage();
        message.id = "sync:" + channel + ":" + java.util.UUID.randomUUID();
        message.channel = channel;
        message.direction = "status";
        message.timestamp = java.time.Instant.now().toString();
        message.text = ContactPointUtil.firstNonBlank(messageText, channel + " sync finished");
        return message;
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

    private static int intQuery(Map<String, String> query, String name, int defaultValue) {
        String value = query.get(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static List<String> splitTags(String raw) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        for (String part : raw.split("[,，\\n]")) {
            String tag = part.trim();
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return new ArrayList<>(tags);
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

    private static void writeMedia(HttpExchange exchange, MediaGateway.MediaResponse media) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", media.contentType);
        headers.set("Cache-Control", "private, max-age=3600");
        headers.set("Content-Disposition", "inline; filename=\"" + safeHeaderFileName(media.fileName) + "\"");
        exchange.sendResponseHeaders(200, media.bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(media.bytes);
        }
    }

    private static String safeHeaderFileName(String value) {
        return ContactPointUtil.firstNonBlank(value, "attachment")
                .replace("\\", "_")
                .replace("\"", "")
                .replace("\r", "")
                .replace("\n", "");
    }

    static String pageHtml() {
        return new StringBuilder().append("""
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>统一消息中心</title>
  <style>
	    :root {
	      --ink:#202124; --muted:#667085; --line:#d7dde7; --hairline:#e8edf4; --panel:#ffffff; --soft:#f7f7f8;
	      --email:#2563eb; --chat:#0f9f6e; --wecom:#7c3aed; --accent:#002FA7; --warn:#b45309; --danger:#c2410c;
	    }
	    * { box-sizing:border-box; }
	    body { margin:0; color:var(--ink); background:var(--soft); font-family:"Helvetica Neue", "Segoe UI", Arial, sans-serif; letter-spacing:0; }
	    button, input, textarea, select { font:inherit; }
	    button { border:1px solid var(--line); background:#fff; color:var(--ink); border-radius:4px; padding:7px 10px; cursor:pointer; font-size:13px; }
	    button.primary { background:var(--accent); border-color:var(--accent); color:#fff; }
	    button.ghost { background:transparent; }
	    button:disabled { opacity:.48; cursor:not-allowed; }
	    a { color:var(--accent); }
	    .shell { --detail-width:360px; height:100vh; min-height:640px; display:grid; grid-template-columns:320px minmax(430px, 1fr) var(--detail-width); grid-template-rows:64px minmax(0, 1fr); gap:1px; background:var(--line); transition:grid-template-columns .24s ease; }
	    .shell.detail-collapsed { --detail-width:48px; }
	    .workspace-topbar { grid-column:1 / -1; display:grid; grid-template-columns:320px minmax(430px, 1fr) var(--detail-width); align-items:center; background:#fff; border-bottom:1px solid var(--line); transition:grid-template-columns .24s ease; }
	    .workspace-section { min-width:0; height:100%; display:flex; align-items:center; justify-content:space-between; gap:14px; padding:0 18px; }
	    .workspace-center { justify-content:space-between; }
	    .workspace-detail { justify-content:flex-end; }
	    .workspace-title { min-width:0; }
	    .workspace-title .brand, .workspace-title .small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
	    .thread-title-row { display:flex; align-items:center; gap:6px; min-width:0; }
	    .thread-title-row .brand { min-width:0; }
	    .thread-edit-button { width:26px; height:26px; padding:0; border-color:transparent; background:transparent; border-radius:50%; flex:0 0 auto; }
	    .thread-edit-button:hover { border-color:var(--hairline); background:#f7faff; }
	    .contact-points-line { color:var(--muted); font-size:12px; margin-top:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
	    .workspace-actions { display:flex; align-items:center; justify-content:flex-end; gap:10px; flex-wrap:nowrap; }
	    .sync-actions, .utility-actions { display:flex; align-items:center; gap:6px; min-width:0; }
	    .utility-actions { flex:0 0 auto; }
	    .pane { grid-row:2; background:var(--panel); min-height:0; display:flex; flex-direction:column; }
	    .contacts-pane { grid-column:1; }
	    .thread-pane { grid-column:2; }
	    .detail-pane { grid-column:3; min-width:0; overflow:hidden; transition:width .24s ease; }
	    .detail-header { min-height:52px; flex:0 0 auto; display:flex; align-items:center; justify-content:flex-end; gap:8px; padding:0 14px; border-bottom:1px solid var(--line); background:#fff; }
	    .detail-title { min-width:0; }
	    .detail-content { transition:opacity .18s ease, transform .24s ease; }
	    .detail-collapsed .detail-title, .detail-collapsed .detail-content, .detail-collapsed .workspace-detail .workspace-title { opacity:0; pointer-events:none; transform:translateX(10px); }
	    .detail-toggle { width:32px; height:32px; padding:0; display:grid; place-items:center; flex:0 0 auto; }
	    .collapse-mark { position:relative; width:14px; height:14px; display:block; }
	    .collapse-mark::before, .collapse-mark::after { content:""; position:absolute; left:4px; top:2px; width:7px; height:7px; border-left:2px solid var(--muted); border-bottom:2px solid var(--muted); transform:rotate(45deg); }
	    .detail-collapsed .collapse-mark::before { transform:rotate(225deg); left:1px; }
	    .topbar { min-height:44px; flex:0 0 auto; display:flex; align-items:center; justify-content:space-between; gap:12px; padding:0 16px; border-bottom:1px solid var(--line); }
	    .brand { font-weight:750; }
	    .small { color:var(--muted); font-size:12px; }
	    .search { padding:10px 12px; border-bottom:1px solid var(--line); }
	    .search input, .field input, .field textarea, .field select { width:100%; border:1px solid var(--line); border-radius:4px; padding:7px 9px; background:#fff; min-width:0; font-size:13px; }
	    .field textarea { resize:vertical; min-height:38px; max-height:82px; }
	    .contact-list, .thread, .detail, .composer.compact { scrollbar-width:thin; scrollbar-color:transparent transparent; scrollbar-gutter:stable; }
	    .contact-list::-webkit-scrollbar, .thread::-webkit-scrollbar, .detail::-webkit-scrollbar, .composer.compact::-webkit-scrollbar { width:6px; height:6px; }
	    .contact-list::-webkit-scrollbar-track, .thread::-webkit-scrollbar-track, .detail::-webkit-scrollbar-track, .composer.compact::-webkit-scrollbar-track { background:transparent; }
	    .contact-list::-webkit-scrollbar-thumb, .thread::-webkit-scrollbar-thumb, .detail::-webkit-scrollbar-thumb, .composer.compact::-webkit-scrollbar-thumb { background:transparent; border-radius:999px; }
	    .contact-list.scrolling, .thread.scrolling, .detail.scrolling, .composer.compact.scrolling { scrollbar-color:rgba(102,112,133,.34) transparent; }
	    .contact-list.scrolling::-webkit-scrollbar-thumb, .thread.scrolling::-webkit-scrollbar-thumb, .detail.scrolling::-webkit-scrollbar-thumb, .composer.compact.scrolling::-webkit-scrollbar-thumb { background:rgba(102,112,133,.34); border-radius:999px; }
	    .contact-list { overflow:auto; min-height:0; padding:4px 0; }
	    .contact { position:relative; display:grid; grid-template-columns:38px 1fr; gap:9px; padding:8px 12px; cursor:pointer; min-height:58px; align-items:center; }
	    .contact > div { min-width:0; }
	    .contact:hover { background:#f8fbff; }
	    .contact.active { background:#e8f1ff; }
	    .contact-unread-dot { position:absolute; right:10px; top:8px; min-width:8px; height:8px; padding:0; border-radius:999px; background:#E4002B; border:2px solid #fff; box-shadow:0 0 0 1px rgba(228,0,43,.18); }
	    .contact-unread-count { position:absolute; right:8px; top:6px; min-width:16px; height:16px; padding:0 4px; border-radius:999px; display:grid; place-items:center; background:#E4002B; border:2px solid #fff; color:#fff; font-size:10px; line-height:1; font-weight:700; }
	    .avatar { width:38px; height:38px; border-radius:50%; display:grid; place-items:center; color:var(--muted); font-weight:750; border:1px solid var(--line); padding:0; background:#eef3fb; }
	    .contact-name { font-weight:700; font-size:13px; line-height:1.25; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
	    .contact-points { margin-top:2px; color:var(--muted); font-size:11px; line-height:1.25; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
	    .contact-last { margin-top:2px; color:var(--muted); font-size:11px; line-height:1.25; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
	    .chips { display:flex; gap:5px; flex-wrap:wrap; margin-top:7px; }
	    .chip { font-size:11px; padding:2px 6px; border-radius:999px; color:#fff; }
	    .chip.email { background:var(--email); } .chip.chatapp { background:var(--chat); } .chip.wecom { background:var(--wecom); }
	    .thread { flex:1 1 auto; min-height:0; overflow:auto; padding:14px 16px; background:#fbfcff; }
	    .message-row { display:grid; grid-template-columns:42px minmax(0, 76%); align-items:start; gap:10px; margin:0 0 12px; }
	    .message-row.outbound { grid-template-columns:minmax(0, 76%) 42px; justify-content:end; }
	    .message-row.outbound .msg-avatar { order:2; }
	    .message-row.outbound .msg-stack { order:1; align-items:flex-end; }
	    .msg-stack { min-width:0; display:flex; flex-direction:column; align-items:flex-start; }
	    .msg-avatar { width:42px; height:42px; border-radius:50%; display:grid; place-items:center; background:#eef3fb; border:1px solid var(--line); color:var(--muted); font-size:11px; font-weight:700; margin-top:0; }
	    .avatar-icon { position:relative; width:22px; height:22px; display:block; }
	    .avatar-icon.email { border:2px solid var(--email); border-radius:3px; }
	    .avatar-icon.email::before { content:""; position:absolute; left:2px; right:2px; top:5px; height:8px; border-top:2px solid var(--email); transform:skewY(-30deg); }
	    .avatar-icon.email::after { content:""; position:absolute; left:5px; right:5px; bottom:4px; border-bottom:2px solid var(--email); }
	    .avatar-icon.chatapp { border:2px solid var(--chat); border-radius:50%; }
	    .avatar-icon.chatapp::after { content:""; position:absolute; right:0; bottom:-4px; width:8px; height:8px; border-right:2px solid var(--chat); border-bottom:2px solid var(--chat); transform:rotate(18deg); background:#eef3fb; }
	    .avatar-icon.wecom { border:2px solid var(--wecom); border-radius:50%; }
	    .avatar-icon.wecom::before { content:""; position:absolute; width:5px; height:5px; left:5px; top:8px; background:var(--wecom); border-radius:50%; box-shadow:7px 0 0 var(--wecom); }
	    .msg { width:fit-content; max-width:100%; min-width:0; padding:7px 9px; border:1px solid var(--line); border-radius:6px; background:#fff; cursor:pointer; font-size:12px; }
	    .msg.has-media { width:min(320px, 100%); }
	    .msg.outbound { border-color:#b7d4c6; background:#f4fbf7; }
	    .msg.active { outline:2px solid var(--accent); outline-offset:1px; }
	    .msg-title { font-weight:700; margin-bottom:4px; overflow-wrap:anywhere; font-size:12px; }
	    .msg-text { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.38; font-size:12px; }
	    .msg-media { position:relative; margin-top:7px; width:100%; max-width:320px; min-height:42px; overflow:hidden; border:1px solid var(--hairline); border-radius:6px; background:#fff; }
	    .msg-media.media-visual { aspect-ratio:var(--media-ratio, 4 / 3); min-height:0; }
	    .msg-media-trigger { width:100%; height:100%; padding:0; border:0; border-radius:0; display:block; background:#fff; overflow:hidden; }
	    .msg-media-preview { display:block; width:100%; height:100%; max-height:none; object-fit:cover; background:#fff; cursor:zoom-in; }
	    .msg-media-loading { position:absolute; inset:0; min-height:42px; display:grid; place-items:center; color:var(--muted); font-size:11px; background:#fff; pointer-events:none; }
	    .msg-media.loaded .msg-media-loading { display:none; }
	    .msg-media-file { min-height:42px; display:flex; align-items:center; gap:8px; padding:9px; color:var(--muted); font-size:12px; }
	    .msg-media-unavailable { display:none; padding:9px; color:var(--muted); font-size:11px; }
	    .msg-media.failed .msg-media-preview, .msg-media.failed .msg-media-file, .msg-media.failed .msg-media-loading { display:none; }
	    .msg-media.failed .msg-media-unavailable { display:block; }
	    .msg-meta-line { margin-top:4px; color:var(--muted); font-size:11px; line-height:1.3; display:flex; gap:7px; flex-wrap:wrap; align-items:center; }
	    .msg-meta-line a, .media-open-link { font-size:11px; }
	    .media-open-link { border:0; background:transparent; color:var(--accent); padding:0; border-radius:0; cursor:pointer; }
	    .media-open-link:disabled { color:var(--muted); }
	    .status-icon { position:relative; width:14px; height:14px; display:inline-block; border:1.5px solid var(--muted); border-radius:50%; flex:0 0 auto; }
	    .status-icon.sent::after, .status-icon.read::after, .status-icon.success::after { content:""; position:absolute; left:3px; top:3px; width:6px; height:3px; border-left:1.8px solid var(--muted); border-bottom:1.8px solid var(--muted); transform:rotate(-45deg); }
	    .status-icon.failed { border-color:var(--danger); }
	    .status-icon.failed::before, .status-icon.failed::after { content:""; position:absolute; left:3px; right:3px; top:6px; height:1.8px; background:var(--danger); transform:rotate(45deg); }
	    .status-icon.failed::after { transform:rotate(-45deg); }
	    .status { color:var(--muted); }
	    .composer.compact { flex:0 0 auto; height:260px; overflow:auto; border-top:1px solid var(--line); background:#fff; padding:12px 24px 10px; }
	    .composer-tabs { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:8px; }
	    .composer-tabs button { padding:7px 14px; font-size:13px; }
	    .composer-tabs button.active { border-color:var(--accent); color:var(--accent); background:#f5f8ff; }
	    .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
	    .composer-form { display:grid; gap:8px; align-items:end; }
	    .composer-context { display:grid; grid-template-columns:minmax(160px, .55fr) minmax(220px, 1fr); gap:10px; align-items:end; }
	    .recipient-field { min-width:0; }
	    .recipient-control { display:block; }
	    .recipient-control select { color:var(--muted); appearance:none; -webkit-appearance:none; background:#fff; }
	    .composer-editor { border:1px solid var(--line); border-radius:8px; height:96px; min-height:96px; background:#fff; display:flex; align-items:stretch; overflow:hidden; }
	    .composer-editor textarea { width:100%; height:100%; min-height:0; max-height:none; border:0; resize:none; padding:11px 12px; font-size:13px; outline:none; }
	    .template-form #tplFields { display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:8px; }
	    .media-editor { display:grid; grid-template-columns:1fr minmax(180px, .45fr); gap:0; align-items:stretch; }
	    .file-drop { display:flex; align-items:center; justify-content:center; gap:9px; min-height:92px; border-left:1px solid var(--hairline); color:var(--muted); font-size:12px; cursor:pointer; padding:12px; }
	    .sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
	    .composer-toolbar { display:flex; align-items:center; justify-content:space-between; gap:10px; }
	    .tool-cluster { display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
	    .icon-button { width:34px; height:34px; padding:0; display:grid; place-items:center; border-radius:50%; background:#fff; }
	    .tool-icon { position:relative; width:18px; height:18px; display:block; color:var(--muted); }
	    .tool-icon.emoji::before { content:""; position:absolute; inset:1px; border:2px solid currentColor; border-radius:50%; }
	    .tool-icon.emoji::after { content:""; position:absolute; left:5px; right:5px; bottom:5px; height:4px; border-bottom:2px solid currentColor; border-radius:50%; }
	    .tool-icon.file::before { content:""; position:absolute; inset:2px 4px; border:2px solid currentColor; border-radius:2px; }
	    .tool-icon.file::after { content:""; position:absolute; right:4px; top:2px; border-left:6px solid transparent; border-bottom:6px solid currentColor; }
	    .tool-icon.image::before { content:""; position:absolute; inset:2px; border:2px solid currentColor; border-radius:3px; }
	    .tool-icon.image::after { content:""; position:absolute; left:5px; right:4px; bottom:5px; height:6px; border-left:2px solid currentColor; border-bottom:2px solid currentColor; transform:skewX(-30deg); }
	    .tool-icon.video::before { content:""; position:absolute; left:2px; top:4px; width:10px; height:10px; border:2px solid currentColor; border-radius:2px; }
	    .tool-icon.video::after { content:""; position:absolute; right:1px; top:6px; border-left:7px solid currentColor; border-top:5px solid transparent; border-bottom:5px solid transparent; }
	    .tool-icon.template::before { content:""; position:absolute; inset:2px; border:2px solid currentColor; border-radius:3px; }
	    .tool-icon.template::after { content:""; position:absolute; left:6px; right:5px; top:7px; border-top:2px solid currentColor; box-shadow:0 5px 0 currentColor; }
	    .tool-icon.plus::before, .tool-icon.plus::after { content:""; position:absolute; background:currentColor; left:3px; right:3px; top:8px; height:2px; }
	    .tool-icon.plus::after { transform:rotate(90deg); }
	    .tool-icon.refresh::before { content:""; position:absolute; inset:3px; border:2px solid currentColor; border-left-color:transparent; border-radius:50%; }
	    .tool-icon.refresh::after { content:""; position:absolute; right:1px; top:2px; border-left:6px solid currentColor; border-top:4px solid transparent; border-bottom:4px solid transparent; transform:rotate(-38deg); }
	    .tool-icon.edit::before { content:""; position:absolute; left:4px; top:9px; width:11px; height:3px; background:currentColor; border-radius:2px; transform:rotate(-45deg); transform-origin:center; }
	    .tool-icon.edit::after { content:""; position:absolute; left:3px; bottom:3px; width:11px; height:4px; border-left:2px solid currentColor; border-bottom:2px solid currentColor; transform:skewX(-20deg); }
	    .tool-icon.split::before, .tool-icon.split::after { content:""; position:absolute; left:3px; right:3px; height:2px; background:currentColor; border-radius:2px; }
	    .tool-icon.split::before { top:5px; transform:rotate(-18deg); transform-origin:left center; }
	    .tool-icon.split::after { bottom:5px; transform:rotate(18deg); transform-origin:left center; }
	    .field { margin-bottom:0; }
	    .field label { display:block; color:var(--muted); font-size:11px; margin-bottom:4px; }
	    .actions { display:flex; justify-content:flex-end; gap:8px; align-items:center; }
	    .top-actions { display:flex; gap:6px; align-items:center; flex-wrap:wrap; justify-content:flex-end; }
    .emoji-panel { display:none; gap:6px; flex-wrap:wrap; margin-bottom:10px; }
    .emoji-panel.open { display:flex; }
    .emoji-panel button { width:34px; height:34px; padding:0; }
    .detail { overflow:auto; padding:16px; min-height:0; }
    .contact-detail-panel { display:grid; gap:12px; }
    .profile-readonly { gap:14px; }
    .profile-readonly-title { font-weight:750; font-size:15px; padding-bottom:2px; }
    .profile-readonly-grid { display:grid; gap:2px; }
    .readonly-row { display:grid; gap:5px; padding:9px 0; border-bottom:1px solid var(--hairline); }
    .readonly-value { font-size:13px; line-height:1.42; overflow-wrap:anywhere; }
    .profile-tags-readonly { display:flex; gap:6px; flex-wrap:wrap; min-height:22px; align-items:center; }
    .account-list { display:grid; gap:8px; }
    .account-item { display:flex; justify-content:space-between; gap:8px; align-items:center; padding:8px; border:1px solid var(--hairline); border-radius:6px; overflow-wrap:anywhere; }
    .account-actions { display:flex; gap:6px; align-items:center; flex:0 0 auto; }
    .account-split-button { width:30px; height:30px; flex:0 0 auto; }
    .profile-save-state { min-width:68px; text-align:right; }
    .tag-preview { display:flex; gap:6px; flex-wrap:wrap; }
    .tag-pill { color:#047857; background:#ecfdf3; border:1px solid #9ed8b8; border-radius:999px; padding:2px 7px; font-size:11px; }
    .message-detail-panel { margin-top:18px; border-top:1px solid var(--line); padding-top:12px; }
    .message-detail-standalone { margin-top:0; border-top:0; padding-top:0; }
    .kv { display:grid; grid-template-columns:92px 1fr; gap:8px; padding:7px 0; border-bottom:1px solid #edf0f5; }
    pre { white-space:pre-wrap; overflow:auto; background:#101828; color:#e5e7eb; border-radius:8px; padding:12px; max-height:260px; }
    .toast { position:fixed; right:18px; bottom:18px; background:#202124; color:white; padding:10px 12px; border-radius:8px; box-shadow:0 12px 28px rgba(0,0,0,.22); display:none; z-index:20; }
    .modal-backdrop[hidden] { display:none; }
    .modal-backdrop { position:fixed; inset:0; z-index:30; display:grid; place-items:center; padding:20px; background:rgba(32,33,36,.28); }
    .profile-modal { width:min(420px, 100%); background:#fff; border:1px solid var(--line); border-radius:8px; box-shadow:0 18px 48px rgba(32,33,36,.22); padding:16px; display:grid; gap:14px; }
    .image-preview-backdrop[hidden] { display:none; }
    .image-preview-backdrop { position:fixed; inset:0; z-index:40; display:grid; place-items:center; padding:28px; background:rgba(32,33,36,.72); }
    .image-preview-frame { position:relative; max-width:94vw; max-height:90vh; display:grid; place-items:center; padding:10px; border:1px solid var(--line); border-radius:8px; background:#fff; }
    .image-preview-frame img { display:block; max-width:calc(94vw - 20px); max-height:calc(90vh - 20px); object-fit:contain; background:#fff; }
    .image-preview-close { position:absolute; right:8px; top:8px; z-index:1; box-shadow:0 1px 4px rgba(32,33,36,.16); }
    .modal-head { display:flex; align-items:center; justify-content:space-between; gap:12px; }
    .modal-title { font-weight:750; }
    .modal-actions { display:flex; justify-content:flex-end; align-items:center; gap:8px; }
    .close-mark { position:relative; width:15px; height:15px; display:block; color:var(--muted); }
    .close-mark::before, .close-mark::after { content:""; position:absolute; left:2px; right:2px; top:7px; height:2px; background:currentColor; transform:rotate(45deg); }
    .close-mark::after { transform:rotate(-45deg); }
    .point { display:flex; justify-content:space-between; gap:10px; align-items:center; padding:10px 0; border-bottom:1px solid #edf0f5; overflow-wrap:anywhere; }
    .empty { color:var(--muted); padding:24px; text-align:center; }
    @media (max-width: 980px) {
	      .shell { height:auto; min-height:100vh; grid-template-columns:1fr; grid-template-rows:auto; }
	      .shell.detail-collapsed { --detail-width:auto; }
	      .workspace-topbar { grid-column:1; grid-template-columns:1fr; }
	      .workspace-section { min-height:48px; }
	      .pane { grid-column:1; grid-row:auto; min-height:360px; }
	      .contact-list, .thread, .detail { max-height:58vh; }
	      .message-row, .message-row.outbound { grid-template-columns:42px minmax(0, 1fr); justify-content:stretch; }
	      .message-row.outbound .msg-avatar, .message-row.outbound .msg-stack { order:initial; align-items:flex-start; }
	      .grid2, .composer-context, .media-editor, .template-form #tplFields { grid-template-columns:1fr; }
	      .file-drop { border-left:0; border-top:1px solid var(--hairline); min-height:58px; }
	    }
  </style>
</head>
<body>
  <main class="shell" id="shell">
    <header class="workspace-topbar">
      <div class="workspace-section">
        <div class="workspace-title"><div class="brand">统一消息中心</div><div class="small" id="contactCount">0 个联系人</div></div>
        <button id="notifyBtn">提醒</button>
      </div>
      <div class="workspace-section workspace-center">
        <div class="workspace-title">
          <div class="thread-title-row" id="threadTitle">
            <div class="brand" id="threadTitleText">选择联系人</div>
            <button class="thread-edit-button" id="editProfileBtn" type="button" title="编辑联系人资料" aria-label="编辑联系人资料" hidden><span class="tool-icon edit"></span></button>
          </div>
          <div class="contact-points-line" id="threadSub">邮件和 ChatApp 按时间穿插显示</div>
        </div>
        <div class="workspace-actions">
          <div class="sync-actions"><button id="syncEmailBtn">收取邮件</button><button id="syncChatBtn">同步 WhatsApp</button></div>
          <div class="utility-actions"><button class="icon-button" id="refreshBtn" type="button" title="刷新" aria-label="刷新"><span class="tool-icon refresh"></span></button></div>
        </div>
      </div>
      <div class="workspace-section workspace-detail"></div>
    </header>
    <section class="pane contacts-pane">
      <div class="search"><input id="searchInput" placeholder="搜索联系人、邮箱、号码"></div>
      <div class="contact-list" id="contacts"></div>
    </section>
    <section class="pane thread-pane">
      <div class="thread" id="thread"><div class="empty">左侧选择一个联系人</div></div>
      <div class="composer compact" id="composer"></div>
    </section>
    <aside class="pane detail-pane">
      <div class="detail-header">
        <button class="detail-toggle" id="detailToggleBtn" title="收起/展开" aria-label="收起/展开"><span class="collapse-mark"></span></button>
      </div>
      <div class="detail detail-content" id="detail"><div class="empty">点击一个联系人查看资料</div></div>
    </aside>
  </main>
  <div class="modal-backdrop" id="profileModal" hidden>
    <div class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profileModalTitle">
      <div class="modal-head">
        <div class="modal-title" id="profileModalTitle">编辑联系人资料</div>
        <button class="icon-button" id="profileCloseBtn" type="button" title="关闭" aria-label="关闭"><span class="close-mark"></span></button>
      </div>
      <div class="field"><label>自定义昵称</label><input id="profileNicknameInput" placeholder="输入昵称"></div>
      <div class="field"><label>自定义标签</label><input id="profileTagsInput" placeholder="多个标签用逗号分隔"></div>
      <div class="modal-actions"><span class="small profile-save-state" id="profileSaveState"></span><button class="ghost" id="profileCancelBtn" type="button">取消</button><button class="primary" id="profileSaveBtn" type="button">保存</button></div>
    </div>
  </div>
  <div class="image-preview-backdrop" id="previewImageModal" hidden>
    <div class="image-preview-frame" role="dialog" aria-modal="true" aria-label="图片预览">
      <button class="icon-button image-preview-close" id="previewImageCloseBtn" type="button" title="关闭" aria-label="关闭"><span class="close-mark"></span></button>
      <img id="previewImageEl" alt="图片预览">
    </div>
  </div>
  <div class="toast" id="toast"></div>
  <script>
    const THREAD_PAGE_SIZE = 10;
    const state = { contacts: [], templates: [], capabilities: {}, selectedPointId: '', selectedMessageId: '', selectedChannel: '', selectedMode: 'text', mediaType: 'image', lastKey: '', contactsRenderKey:'', threadRenderKeyByContact:{}, threadPages:{}, detailCollapsed:false, profileDirty:false, profileSavedPointId:'', profileSavedTimer:null, selectedPointByChannel:{}, contactSnapshots:{}, unreadByContact:{}, isUserScrolling:false, pendingSilentRefresh:false };
    const emojiSet = [
      '😀','😃','😄','😁','😆','😂','🤣','😊','🙂','😉','😍','😘',
      '😎','🤔','😅','😇','🥳','😢','😭','😡','😤','😴','🤝','👏',
      '👍','👎','👌','🙏','💪','👀','✅','☑️','❌','⚠️','❗','❓',
      '💬','📞','📧','📎','📄','🧾','📦','🚚','🚢','✈️','📍','⏱️',
      '💰','💳','📅','📝','🔎','🔔','🎉','❤️'
    ];
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
    function threadPageUrl(id, cursor = '') {
      let url = '/api/threads?contactPointId=' + encodeURIComponent(id) + '&limit=' + THREAD_PAGE_SIZE;
      if (cursor) url += '&cursor=' + encodeURIComponent(cursor);
      return url;
    }
    function formatDuration(millis) {
      return `${(Number(millis || 0) / 1000).toFixed(1)}s`;
    }
    const toast = text => { const el=$('toast'); el.textContent=text; el.style.display='block'; clearTimeout(window.toastTimer); window.toastTimer=setTimeout(()=>el.style.display='none',2600); };

    async function init() {
      const caps = await api('/api/channel-capabilities');
      caps.forEach(item => state.capabilities[item.channel] = item);
	      state.templates = await api('/api/templates');
	      await loadContacts(false);
	      $('refreshBtn').onclick = () => refreshAll(false);
	      $('syncEmailBtn').onclick = syncEmail;
	      $('syncChatBtn').onclick = syncChatApp;
	      $('searchInput').oninput = renderContacts;
	      bindScrollSurfaces();
	      $('detailToggleBtn').onclick = toggleDetailPane;
	      $('editProfileBtn').onclick = openProfileModal;
	      $('profileCloseBtn').onclick = closeProfileModal;
	      $('profileCancelBtn').onclick = closeProfileModal;
	      $('profileSaveBtn').onclick = saveContactProfile;
	      $('profileNicknameInput').oninput = markProfileDirty;
	      $('profileTagsInput').oninput = markProfileDirty;
	      $('profileNicknameInput').onkeydown = profileEnterSave;
	      $('profileTagsInput').onkeydown = profileEnterSave;
	      $('profileModal').onclick = event => { if (event.target === $('profileModal')) closeProfileModal(); };
	      $('previewImageCloseBtn').onclick = closeImagePreview;
	      $('previewImageModal').onclick = event => { if (event.target === $('previewImageModal')) closeImagePreview(); };
	      document.addEventListener('keydown', event => { if (event.key === 'Escape') closeImagePreview(); });
      $('notifyBtn').onclick = enableNotifications;
      connectEvents();
      setInterval(() => refreshAll(true), 5000);
    }

	    async function refreshAll(silent) {
	      if (silent && isUserScrolling()) {
	        state.pendingSilentRefresh = true;
	        return;
	      }
	      await loadContacts(silent);
	      if (state.selectedPointId) await loadThread(state.selectedPointId, true);
	    }

	    async function refreshSelectedContactViews(silent) {
	      await refreshAll(silent);
	      const contact = selectedContact();
	      if (contact && !contact.channels.includes(state.selectedChannel)) {
	        state.selectedChannel = contact.channels[0] || '';
	      }
	      renderComposer();
	    }

	    async function syncEmail() {
	      await runSync('/api/sync/email', '正在收取邮件', result => `邮件收取完成：新增 ${result.saved || 0}，跳过 ${result.skipped || 0}`);
	    }

	    async function syncChatApp() {
	      await runSync('/api/sync/chatapp', '正在同步 WhatsApp', async result => {
	        state.templates = await api('/api/templates');
	        if (result.mediaFailures?.length) console.table(result.mediaFailures);
	        const reason = result.mediaFailures?.[0]?.reason ? `，首条原因 ${result.mediaFailures?.[0]?.reason}` : '';
	        return `WhatsApp 同步完成：新增 ${result.saved || 0}，刷新 ${result.updated || 0}，页 ${result.pages || 0}，耗时 ${formatDuration(result.durationMillis)}，模板 ${result.templatesSaved || 0}，附件 ${result.mediaCached || 0}，排队 ${result.mediaQueued || 0}，失败 ${result.mediaFailed || 0}${reason}`;
	      });
	    }

	    async function runSync(url, pendingText, doneText) {
	      const buttons = [$('syncEmailBtn'), $('syncChatBtn'), $('refreshBtn')];
	      buttons.forEach(btn => btn.disabled = true);
	      toast(pendingText);
	      try {
	        const result = await postJson(url, {});
	        const text = await doneText(result);
	        toast(text);
	        await refreshAll(false);
	      } catch (err) {
	        toast(err.message);
	      } finally {
	        buttons.forEach(btn => btn.disabled = false);
	      }
	    }

	    async function loadContacts(silent) {
      const listEl = $('contacts');
      const top = listEl.scrollTop;
      const lockDetail = state.profileDirty || state.selectedMessageId || profileModalOpen() || detailEditing();
      const contacts = await api('/api/contacts');
      if (silent) reconcileUnreadContacts(contacts);
      state.contacts = contacts;
      const key = contactRenderKey(contacts);
      const notifyKey = contacts.map(c => `${c.id}:${c.lastTime}:${c.messageCount}`).join('|');
      if (silent && key === state.contactsRenderKey) {
        updateContactSnapshots(contacts);
        state.lastKey = notifyKey;
        return;
      }
      updateContactSnapshots(contacts);
      if (silent && state.lastKey && notifyKey !== state.lastKey) {
        toast('有新消息');
        if ('Notification' in window && Notification.permission === 'granted') {
          new Notification('统一消息中心', { body:'有新消息' });
        }
      }
      state.lastKey = notifyKey;
      state.contactsRenderKey = key;
      renderContacts();
      if (state.selectedPointId && !lockDetail) renderContactDetail(selectedContact());
      requestAnimationFrame(() => listEl.scrollTop = top);
    }

    function contactRenderKey(contacts) {
      return contacts.map(c => [
        c.id || '',
        c.displayName || '',
        c.remark || '',
        (c.tags || []).join(','),
        (c.channels || []).join(','),
        (c.points || []).map(p => `${p.id || ''}:${p.channel || ''}:${p.value || ''}`).join(','),
        c.lastTime || '',
        c.messageCount || 0,
        c.lastText || '',
        c.lastDirection || '',
        c.lastChannel || '',
        state.unreadByContact[c.id] || 0
      ].join('~')).join('|');
    }

    function reconcileUnreadContacts(contacts) {
      contacts.forEach(c => {
        const previous = state.contactSnapshots[c.id];
        if (!previous) return;
        if (c.id === state.selectedPointId) {
          clearContactUnread(c.id);
          return;
        }
        const countDelta = Math.max(0, Number(c.messageCount || 0) - Number(previous.messageCount || 0));
        const advanced = countDelta > 0 || (!!c.lastTime && c.lastTime !== previous.lastTime);
        if (c.id !== state.selectedPointId && advanced && isUnreadSource(c)) {
          state.unreadByContact[c.id] = Math.min(99, (state.unreadByContact[c.id] || 0) + Math.max(1, countDelta));
        }
      });
    }

    function updateContactSnapshots(contacts) {
      state.contactSnapshots = {};
      contacts.forEach(c => {
        state.contactSnapshots[c.id] = {
          lastTime:c.lastTime || '',
          messageCount:Number(c.messageCount || 0),
          lastDirection:c.lastDirection || '',
          lastChannel:c.lastChannel || ''
        };
      });
    }

    function isUnreadSource(contact) {
      const direction = String(contact.lastDirection || '').toLowerCase();
      return direction !== 'outbound' && direction !== 'out' && direction !== 'status';
    }

    function clearContactUnread(id) {
      if (id) delete state.unreadByContact[id];
    }

    function bindScrollSurfaces() {
      ['contacts', 'thread', 'detail', 'composer'].forEach(id => {
        const el = $(id);
        if (el) el.onscroll = () => {
          markScrollSurfaceScrolling(el);
          if (id === 'thread') loadOlderThreadMessages();
        };
      });
    }

    function markScrollSurfaceScrolling(el) {
      if (!el) return;
      state.isUserScrolling = true;
      el.classList.add('scrolling');
      clearTimeout(el._scrollTimer);
      el._scrollTimer = setTimeout(() => finishScrollSurfaceScrolling(el), 800);
    }

    function finishScrollSurfaceScrolling(el) {
      if (!el) return;
      el.classList.remove('scrolling');
      state.isUserScrolling = ['contacts', 'thread', 'detail', 'composer'].some(id => {
        const surface = $(id);
        return surface && surface.classList.contains('scrolling');
      });
      if (!state.isUserScrolling && state.pendingSilentRefresh) {
        state.pendingSilentRefresh = false;
        refreshAll(true);
      }
    }

    function isUserScrolling() {
      return !!state.isUserScrolling;
    }

    function renderContacts() {
      const term = $('searchInput').value.trim().toLowerCase();
      const filtered = state.contacts.filter(c => !term || JSON.stringify(c).toLowerCase().includes(term));
      $('contactCount').textContent = `${filtered.length} 个联系人`;
      $('contacts').innerHTML = filtered.map(c => `
        <div class="contact ${c.id === state.selectedPointId ? 'active' : ''} ${state.unreadByContact[c.id] ? 'unread' : ''}" draggable="true" data-id="${esc(c.id)}" data-unread="${state.unreadByContact[c.id] || 0}">
          <button class="avatar" data-avatar="${esc(c.id)}" title="联系方式"><span class="avatar-icon ${esc(primaryChannel(c))}"></span></button>
          <div>
            <div class="contact-name">${esc(c.displayName)}</div>
            <div class="contact-points">${esc(pointSummary(c))}</div>
            <div class="contact-last">${esc(c.lastText || '')}</div>
          </div>
          ${state.unreadByContact[c.id] ? `<span class="${state.unreadByContact[c.id] > 1 ? 'contact-unread-count' : 'contact-unread-dot'}" title="有未读消息" aria-label="有未读消息">${state.unreadByContact[c.id] > 1 ? esc(state.unreadByContact[c.id]) : ''}</span>` : ''}
        </div>`).join('') || '<div class="empty">暂无联系人</div>';
      document.querySelectorAll('.contact').forEach(row => {
        row.onclick = () => selectContact(row.dataset.id);
        row.ondragstart = event => event.dataTransfer.setData('text/plain', row.dataset.id);
        row.ondragover = event => event.preventDefault();
        row.ondrop = async event => {
          event.preventDefault();
          const source = event.dataTransfer.getData('text/plain');
          const target = row.dataset.id;
          if (source && target && source !== target) {
            await postJson('/api/contact-groups/merge', { primaryPointId: target, mergedPointId: source });
            state.selectedPointId = target;
            await refreshSelectedContactViews(false);
            toast('联系人已合并');
          }
        };
      });
    }

    function label(channel) {
      return channel === 'email' ? '邮件' : channel === 'chatapp' ? 'WhatsApp' : channel === 'wecom' ? '企业微信' : channel;
    }

    function primaryChannel(contact) {
      return contact && contact.channels && contact.channels.length ? contact.channels[0] : 'email';
    }

    function pointSummary(contact) {
      if (!contact || !contact.points) return '';
      return contact.points.map(p => p.value || p.id).filter(Boolean).join(' / ');
    }

    function directionText(direction) {
      return direction === 'outbound' ? '已发送' : direction === 'status' ? '状态' : '已接收';
    }

    function statusText(message) {
      return message.status || directionText(message.direction);
    }

    function statusClass(message) {
      const value = statusText(message).toLowerCase();
      if (value.includes('fail') || value.includes('失败')) return 'failed';
      if (value.includes('read') || value.includes('已读')) return 'read';
      if (value.includes('success')) return 'success';
      return message.direction === 'outbound' ? 'sent' : 'read';
    }

	    function hasMedia(message) {
	      return !!(message && (message.mediaUrl || message.objectKey));
	    }
""").append("""

	    function mediaUrl(message) {
      return '/api/media?id=' + encodeURIComponent(message.id || message.sourceId || '');
    }

    async function openAttachment(event, url, fallbackName) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      const button = event && event.currentTarget ? event.currentTarget : null;
      if (button) button.disabled = true;
      try {
        const response = await fetch(url, { cache:'no-store' });
        if (!response.ok) {
          let message = response.statusText || '附件暂不可用';
          try {
            const data = await response.json();
            message = data.message || data.error || message;
          } catch (ignored) {}
          throw new Error(message);
        }
        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const contentType = response.headers.get('content-type') || blob.type || '';
        const fileName = fileNameFromDisposition(response.headers.get('content-disposition')) || fallbackName || 'attachment';
        if (shouldOpenInline(contentType)) {
          const opened = window.open(objectUrl, '_blank', 'noopener');
          if (!opened) downloadBlob(objectUrl, fileName);
        } else {
          downloadBlob(objectUrl, fileName);
        }
        setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
      } catch (err) {
        toast(`附件打开失败：${err.message}`);
      } finally {
        if (button) button.disabled = false;
      }
    }

    function shouldOpenInline(contentType) {
      const type = String(contentType || '').toLowerCase();
      return type.startsWith('image/') || type.startsWith('video/') || type === 'application/pdf' || type.startsWith('text/');
    }

    function downloadBlob(url, fileName) {
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName || 'attachment';
      document.body.appendChild(link);
      link.click();
      link.remove();
    }

    function fileNameFromDisposition(value) {
      const header = String(value || '');
      const encoded = header.match(/filename\\*=UTF-8''([^;]+)/i);
      if (encoded) return decodeURIComponent(encoded[1]);
      const plain = header.match(/filename="?([^";]+)"?/i);
      return plain ? plain[1] : '';
    }

    function mediaLoaded(el) {
      const box = el && el.closest ? el.closest('.msg-media') : null;
      if (box) {
        const width = el.naturalWidth || el.videoWidth || 0;
        const height = el.naturalHeight || el.videoHeight || 0;
        if (width > 0 && height > 0) box.style.setProperty('--media-ratio', `${width} / ${height}`);
        box.classList.remove('failed');
        box.classList.add('loaded');
      }
    }

    function mediaFailed(el) {
      const box = el && el.closest ? el.closest('.msg-media') : null;
      if (box) {
        box.classList.remove('loaded');
        box.classList.add('failed');
      }
    }

    function openImagePreview(event, url, name) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      if (!url) return;
      const image = $('previewImageEl');
      image.src = url;
      image.alt = name || '图片预览';
      $('previewImageModal').hidden = false;
    }

    function closeImagePreview() {
      const modal = $('previewImageModal');
      if (!modal || modal.hidden) return;
      modal.hidden = true;
      const image = $('previewImageEl');
      image.removeAttribute('src');
      image.alt = '图片预览';
    }

    function mediaPreviewHtml(m) {
      if (!hasMedia(m)) return '';
      const url = mediaUrl(m);
      const type = String(m.mediaType || '').toLowerCase();
      const name = esc(m.fileName || '附件');
      const fail = '<div class="msg-media-unavailable">附件暂不可用</div>';
      const loading = '<div class="msg-media-loading">正在拉取图片</div>';
      if (type === 'image') {
        return `<div class="msg-media media-visual">${loading}<button class="msg-media-trigger" type="button" data-preview-image="${esc(url)}" data-file-name="${name}" onclick="openImagePreview(event, this.dataset.previewImage, this.dataset.fileName)"><img class="msg-media-preview" src="${esc(url)}" alt="${name}" loading="lazy" onload="mediaLoaded(this)" onerror="mediaFailed(this)"></button>${fail}</div>`;
      }
      if (type === 'video') {
        return `<div class="msg-media media-visual">${loading}<video class="msg-media-preview" src="${esc(url)}" controls preload="metadata" onloadedmetadata="mediaLoaded(this)" onerror="mediaFailed(this)"></video>${fail}</div>`;
      }
      return `<div class="msg-media"><div class="msg-media-file"><span class="tool-icon file"></span><span>${name}</span></div>${fail}</div>`;
    }

    function selectedContact() {
      return state.contacts.find(c => c.id === state.selectedPointId);
    }

    function detailEditing() {
      const active = document.activeElement;
      return !!(active && active.closest && active.closest('#profileModal')
        && ['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName));
    }

    function profileModalOpen() {
      const modal = $('profileModal');
      return !!(modal && !modal.hidden);
    }

    function markProfileDirty() {
      state.profileDirty = true;
      state.profileSavedPointId = '';
      const status = $('profileSaveState');
      if (status) status.textContent = '未保存';
    }

    function profileEnterSave(event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        saveContactProfile();
      }
    }

    async function selectContact(id) {
      if (state.selectedPointId !== id) {
        state.profileDirty = false;
        state.profileSavedPointId = '';
        closeProfileModal();
      }
      state.selectedPointId = id;
      state.selectedMessageId = '';
      clearContactUnread(id);
      const contact = state.contacts.find(c => c.id === id);
      if (contact) state.selectedChannel = contact.channels.includes(state.selectedChannel) ? state.selectedChannel : contact.channels[0];
      renderContacts();
      await loadThread(id, false);
      renderComposer();
      renderContactDetail(contact);
    }

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
        return `
          <div class="message-row ${esc(direction)}">
            <div class="msg-avatar"><span class="avatar-icon ${esc(m.channel || '')}"></span></div>
            <div class="msg-stack">
              <article class="msg ${esc(direction)} ${hasMedia(m) ? 'has-media' : ''} ${m.id === state.selectedMessageId ? 'active' : ''}" data-id="${esc(m.id)}">
                ${m.title ? `<div class="msg-title">${esc(m.title)}</div>` : ''}
                <div class="msg-text">${esc(m.text || m.summary || '')}</div>
                ${mediaPreviewHtml(m)}
              </article>
              <div class="msg-meta-line">${meta}</div>
            </div>
          </div>`;
      }).join('') || '<div class="empty">暂无消息</div>';
      document.querySelectorAll('.msg[data-id]').forEach(item => item.onclick = () => selectMessage(item.dataset.id));
      document.querySelectorAll('[data-open-media]').forEach(item => item.onclick = event => openAttachment(event, item.dataset.openMedia, item.dataset.fileName));
    }

    async function loadThread(id, keepScroll) {
      const threadEl = $('thread');
      const oldBottom = threadEl.scrollHeight - threadEl.scrollTop - threadEl.clientHeight;
      const contact = state.contacts.find(c => c.id === id);
      const page = await api(threadPageUrl(id));
      const messages = page.items || [];
      const existing = state.threadPages[id];
      const contactMessageCount = Number(contact?.messageCount || 0);
      const previousContactMessageCount = existing ? existing.contactMessageCount || 0 : 0;
      const shouldKeepLoadedThread = keepScroll && existing && existing.hasLoadedInitial && previousContactMessageCount === contactMessageCount;
      const merged = shouldKeepLoadedThread
        ? mergeThreadMessages([...existing.items, ...messages])
        : messages;
      state.threadPages[id] = {
        items: merged,
        nextCursor: page.nextCursor || null,
        isLoadingOlder: false,
        hasLoadedInitial: true,
        contactMessageCount,
      };
      if (shouldKeepLoadedThread && existing.nextCursor) state.threadPages[id].nextCursor = existing.nextCursor;
      if (shouldKeepLoadedThread && !existing.nextCursor && contactMessageCount <= merged.length) state.threadPages[id].nextCursor = null;
      const messagesForRender = state.threadPages[id].items;
      const key = threadRenderKey(contact, messagesForRender);
      if (keepScroll && key === state.threadRenderKeyByContact[id]) {
        return;
      }
      state.threadRenderKeyByContact[id] = key;
      renderThreadMessages(contact, messagesForRender);
      if (keepScroll) requestAnimationFrame(() => threadEl.scrollTop = Math.max(0, threadEl.scrollHeight - threadEl.clientHeight - oldBottom));
      else requestAnimationFrame(() => threadEl.scrollTop = threadEl.scrollHeight);
    }

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
        if (state.selectedPointId !== id || state.threadPages[id] !== page) return;
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

    function threadRenderKey(contact, messages) {
      const header = contact ? `${contact.id || ''}:${contact.displayName || ''}:${pointSummary(contact)}` : '';
      return header + '::' + messages.map(m => [
        m.id || '',
        m.timestamp || '',
        m.status || '',
        m.statusTimestamp || '',
        m.title || '',
        m.text || '',
        m.summary || '',
        m.mediaType || '',
        m.mediaUrl || '',
        m.objectKey || '',
        m.fileName || ''
      ].join('~')).join('|');
    }

    function mergeThreadMessages(messages) {
      const seen = new Set();
      const indexByKey = new Map();
      const merged = [];
      messages.forEach(message => {
        const key = message.id || message.sourceId || `${message.timestamp || ''}:${message.channel || ''}:${message.text || message.summary || ''}`;
        if (seen.has(key)) {
          merged[indexByKey.get(key)] = message;
          return;
        }
        seen.add(key);
        indexByKey.set(key, merged.length);
        merged.push(message);
      });
      return merged;
    }

    function updateContactHeader(contact) {
      $('threadTitleText').textContent = contact ? contact.displayName : '选择联系人';
      $('editProfileBtn').hidden = !contact;
      $('threadSub').textContent = contact ? pointSummary(contact) : '邮件和 ChatApp 按时间穿插显示';
    }

    async function selectMessage(id) {
      state.selectedMessageId = id;
      document.querySelectorAll('.msg').forEach(item => item.classList.toggle('active', item.dataset.id === id));
      const m = await api('/api/messages?id=' + encodeURIComponent(id));
      renderMessageDetail(m);
    }

    function sendPointForChannel(contact, channel) {
      const points = (contact && contact.points ? contact.points : []).filter(p => p.channel === channel);
      const selected = state.selectedPointByChannel[channel] || '';
      return points.find(p => p.id === selected) || points[0] || null;
    }

    function accountSelectHtml(contact, channel, inputId, labelText) {
      const points = (contact && contact.points ? contact.points : []).filter(p => p.channel === channel);
      const point = sendPointForChannel(contact, channel);
      const options = points.length
        ? points.map(p => `<option value="${esc(p.value || p.id)}" data-point-id="${esc(p.id)}" ${p.id === point?.id ? 'selected' : ''}>${esc(p.value || p.id)}</option>`).join('')
        : '<option value="">无可用账号</option>';
      return `
        <div class="field recipient-field">
          <label>${esc(labelText)}</label>
          <div class="recipient-control">
            <select id="${esc(inputId)}" data-account-select="${esc(channel)}" ${points.length <= 1 ? 'disabled' : ''}>${options}</select>
          </div>
        </div>`;
    }

    function bindAccountSelect(contact) {
      document.querySelectorAll('[data-account-select]').forEach(select => {
        select.onchange = () => {
          const pointId = select.selectedOptions[0]?.dataset.pointId || '';
          const point = (contact && contact.points ? contact.points : []).find(p => p.id === pointId);
          if (point) state.selectedPointByChannel[point.channel] = point.id;
        };
      });
    }

    function renderContactDetail(contact) {
      if (!contact) {
        $('detail').innerHTML = '<div class="empty">点击一个联系人查看资料</div>';
        return;
      }
      const accountRows = contact.points.map(p => {
        return `
              <div class="account-item" data-account-id="${esc(p.id)}" data-account-channel="${esc(p.channel)}">
                <div><div class="readonly-value">${esc(p.value || p.id)}</div><div class="small">${esc(label(p.channel))}</div></div>
                <div class="account-actions">
                  <button class="icon-button account-split-button" data-split-account="${esc(p.id)}" type="button" title="拆分账号" aria-label="拆分账号" ${contact.points.length<=1?'disabled':''}><span class="tool-icon split"></span></button>
                </div>
              </div>`;
      }).join('');
      const tags = contact.tags || [];
      const tagHtml = tags.length
        ? tags.map(tag => `<span class="tag-pill">${esc(tag)}</span>`).join('')
        : '<span class="small">未设置</span>';
      $('detail').innerHTML = `
        <div class="contact-detail-panel profile-readonly" id="contactDetailPanel">
          <div class="profile-readonly-title">联系人资料</div>
          <div class="profile-readonly-grid">
            <div class="readonly-row"><div class="small">自定义昵称</div><div class="readonly-value">${esc(contact.remark || '未设置')}</div></div>
            <div class="readonly-row"><div class="small">自定义标签</div><div class="profile-tags-readonly">${tagHtml}</div></div>
          </div>
          <div>
            <div class="small">融合账号</div>
            <div class="account-list">${accountRows}</div>
          </div>
        </div>`;
      document.querySelectorAll('[data-split-account]').forEach(btn => btn.onclick = () => splitAccount(btn.dataset.splitAccount));
    }

    async function splitAccount(pointId) {
      if (!state.selectedPointId || !pointId) return;
      document.querySelectorAll('[data-split-account]').forEach(btn => btn.disabled = true);
      try {
        await postJson('/api/contact-groups/split', { primaryPointId:state.selectedPointId, pointToSplit:pointId });
        state.profileDirty = false;
        state.profileSavedPointId = '';
        state.selectedMessageId = '';
        await refreshSelectedContactViews(false);
        toast('账号已拆分');
      } catch (err) {
        toast(err.message);
        const contact = selectedContact();
        if (contact) renderContactDetail(contact);
      }
    }

    function openProfileModal() {
      const contact = selectedContact();
      if (!contact) return;
      $('profileNicknameInput').value = contact.remark || '';
      $('profileTagsInput').value = (contact.tags || []).join('，');
      const status = $('profileSaveState');
      if (status) status.textContent = '';
      state.profileDirty = false;
      $('profileModal').hidden = false;
      requestAnimationFrame(() => $('profileNicknameInput').focus());
    }

    function closeProfileModal() {
      const modal = $('profileModal');
      if (!modal || modal.hidden) return;
      modal.hidden = true;
      state.profileDirty = false;
      const status = $('profileSaveState');
      if (status) status.textContent = '';
    }

    async function saveContactProfile() {
      if (!state.selectedPointId) return;
      const button = $('profileSaveBtn');
      const status = $('profileSaveState');
      if (button) button.disabled = true;
      if (status) status.textContent = '保存中';
      try {
        await postJson('/api/contact-groups/profile', { contactPointId:state.selectedPointId, nickname:$('profileNicknameInput').value, tags:$('profileTagsInput').value });
        state.profileDirty = false;
        state.profileSavedPointId = state.selectedPointId;
        await refreshAll(false);
        renderComposer();
        closeProfileModal();
        toast('联系人资料已保存');
      } catch (err) {
        if (status) status.textContent = '保存失败';
        toast(err.message);
      } finally {
        if (button) button.disabled = false;
      }
    }

    function renderMessageDetail(m) {
      const html = `
        <div class="kv"><div class="small">渠道</div><div>${esc(label(m.channel))}</div></div>
        <div class="kv"><div class="small">方向</div><div>${esc(m.direction)}</div></div>
        <div class="kv"><div class="small">时间</div><div>${esc(timeText(m.timestamp))}</div></div>
        <div class="kv"><div class="small">From</div><div>${esc(m.from || '')}</div></div>
        <div class="kv"><div class="small">To</div><div>${esc(m.to || '')}</div></div>
        ${m.status ? `<div class="kv"><div class="small">状态</div><div>${esc(m.status)}</div></div>` : ''}
        ${m.title ? `<div class="kv"><div class="small">主题</div><div>${esc(m.title)}</div></div>` : ''}
        <h3>内容</h3><div class="msg-text">${esc(m.bodyText || m.text || '')}</div>
        ${m.raw ? `<h3>Raw</h3><pre>${esc(m.raw)}</pre>` : ''}`;
      $('detail').innerHTML = `<div class="message-detail-panel message-detail-standalone" id="messageDetailPanel">${html}</div>`;
    }

    function toggleDetailPane() {
      state.detailCollapsed = !state.detailCollapsed;
      $('shell').classList.toggle('detail-collapsed', state.detailCollapsed);
    }

    function renderComposer() {
      const contact = selectedContact();
      if (!contact) { $('composer').innerHTML = ''; return; }
      const channels = [...contact.channels, 'wecom'].filter((v,i,a)=>a.indexOf(v)===i);
      if (!channels.includes(state.selectedChannel)) state.selectedChannel = channels[0];
      $('composer').innerHTML = `
        <div class="composer-tabs">${channels.map(ch => `<button class="${state.selectedChannel===ch?'active':''}" data-channel="${esc(ch)}" ${ch==='wecom'?'disabled':''}>${esc(label(ch))}</button>`).join('')}</div>
        <div id="sendPanel"></div>`;
      document.querySelectorAll('[data-channel]').forEach(btn => btn.onclick = () => { state.selectedChannel = btn.dataset.channel; renderComposer(); });
      renderSendPanel(contact);
    }

    function renderSendPanel(contact) {
      const panel = $('sendPanel');
      const point = sendPointForChannel(contact, state.selectedChannel);
      if (state.selectedChannel === 'email') {
        panel.innerHTML = `
          <div class="composer-form email-form">
            <div class="composer-context">
              ${accountSelectHtml(contact, 'email', 'emailTo', '收件人')}
              <div class="field"><label>主题</label><input id="emailSubject"></div>
            </div>
            <div class="composer-editor"><textarea id="emailBody" rows="4" placeholder="请输入邮件正文"></textarea></div>
            <div class="composer-toolbar">
              <div class="tool-cluster"></div>
              <button class="primary" id="sendEmail">发送邮件</button>
            </div>
          </div>`;
        bindAccountSelect(contact);
        $('sendEmail').onclick = sendEmail;
        return;
      }
      if (state.selectedChannel === 'chatapp') {
        const modes = ['text', 'template', 'image', 'video', 'document'];
        if (!modes.includes(state.selectedMode)) state.selectedMode = 'text';
        panel.innerHTML = `
          <div id="chatModePanel"></div>`;
        renderChatMode(state.selectedMode, point?.value || '');
        return;
      }
      panel.innerHTML = '<div class="empty">企业微信 API 接入位已预留</div>';
    }

    function renderChatMode(mode, to) {
      state.selectedMode = mode;
      const panel = $('chatModePanel');
      if (mode === 'text') {
        panel.innerHTML = `
          <div class="composer-form chat-text-form">
            <div class="composer-context">
              ${accountSelectHtml(selectedContact(), 'chatapp', 'chatTo', '收件号码')}
            </div>
            <div class="composer-editor"><textarea id="chatText" rows="4" placeholder="请输入消息"></textarea></div>
            <div class="emoji-panel" id="emojiPanel">${emojiSet.map(item => `<button data-emoji="${esc(item)}">${esc(item)}</button>`).join('')}</div>
            <div class="composer-toolbar">
              <div class="tool-cluster">
                <button class="icon-button" id="emojiBtn" type="button" title="表情" aria-label="表情"><span class="tool-icon emoji"></span></button>
                <button class="icon-button" data-tool-mode="template" type="button" title="模板" aria-label="模板"><span class="tool-icon template"></span></button>
                <button class="icon-button" data-tool-mode="image" type="button" title="图片" aria-label="图片"><span class="tool-icon image"></span></button>
                <button class="icon-button" data-tool-mode="video" type="button" title="视频" aria-label="视频"><span class="tool-icon video"></span></button>
                <button class="icon-button" data-tool-mode="document" type="button" title="文件" aria-label="文件"><span class="tool-icon file"></span></button>
              </div>
              <button class="primary" id="sendChatText">发送 WhatsApp</button>
            </div>
          </div>`;
        $('chatText').onkeydown = e => { if (e.key === 'Enter' && !e.ctrlKey) { e.preventDefault(); sendChatText(); } };
        $('emojiBtn').onclick = () => $('emojiPanel').classList.toggle('open');
        document.querySelectorAll('[data-emoji]').forEach(btn => btn.onclick = () => appendToTextarea('chatText', btn.dataset.emoji));
        document.querySelectorAll('[data-tool-mode]').forEach(btn => btn.onclick = () => selectChatMode(btn.dataset.toolMode, to));
        bindAccountSelect(selectedContact());
        $('sendChatText').onclick = sendChatText;
        return;
      }
      if (mode === 'template') {
        if (!state.templates.length) {
          panel.innerHTML = '<div class="empty">模板库为空</div>';
          return;
        }
        panel.innerHTML = `
          <div class="composer-form template-form">
            <div class="composer-context">
              ${accountSelectHtml(selectedContact(), 'chatapp', 'tplTo', '收件号码')}
              <div class="field"><label>模板</label><select id="tplSelect">${state.templates.map(t => `<option value="${esc(t.templateCode)}">${esc(t.templateName || t.templateCode)} / ${esc(t.languageCode || '')}</option>`).join('')}</select></div>
            </div>
            <div id="tplFields"></div>
            <div class="composer-toolbar">
              <div class="tool-cluster">${modeToolButtons('template')}</div>
              <button class="primary" id="sendTemplate">发送模板</button>
            </div>
          </div>`;
        $('tplSelect').onchange = renderTemplateFields;
        $('sendTemplate').onclick = sendTemplate;
        document.querySelectorAll('[data-tool-mode]').forEach(btn => btn.onclick = () => selectChatMode(btn.dataset.toolMode, to));
        bindAccountSelect(selectedContact());
        renderTemplateFields();
        return;
      }
      state.mediaType = mode;
      panel.innerHTML = `
        <div class="composer-form media-form">
          <div class="composer-context">
            ${accountSelectHtml(selectedContact(), 'chatapp', 'mediaTo', '收件号码')}
          </div>
          <div class="composer-editor media-editor">
            <textarea id="mediaCaption" rows="4" placeholder="说明"></textarea>
            <label class="file-drop" for="mediaFile"><span class="tool-icon ${esc(mediaIcon(mode))}"></span><span id="mediaFileName">选择${esc(labelMedia(mode))}</span></label>
            <input class="sr-only" id="mediaFile" type="file">
          </div>
          <div class="composer-toolbar">
            <div class="tool-cluster">${modeToolButtons(mode)}</div>
            <button class="primary" id="sendMedia">发送附件</button>
          </div>
        </div>`;
      $('mediaFile').onchange = () => {
        const file = $('mediaFile').files[0];
        $('mediaFileName').textContent = file ? file.name : `选择${labelMedia(mode)}`;
      };
      document.querySelectorAll('[data-tool-mode]').forEach(btn => btn.onclick = () => selectChatMode(btn.dataset.toolMode, to));
      bindAccountSelect(selectedContact());
      $('sendMedia').onclick = sendMedia;
    }

    function selectChatMode(mode, to) {
      state.selectedMode = mode;
      renderChatMode(mode, to);
    }

    function modeLabel(mode) {
      return mode === 'text' ? '文本' : mode === 'template' ? '模板' : mode === 'image' ? '图片' : mode === 'video' ? '视频' : '文件';
    }

    function modeToolButtons(activeMode) {
      return ['text','template','image','video','document']
        .filter(mode => mode !== activeMode)
        .map(mode => `<button class="icon-button" data-tool-mode="${esc(mode)}" type="button" title="${esc(modeLabel(mode))}" aria-label="${esc(modeLabel(mode))}"><span class="tool-icon ${esc(mode === 'text' ? 'plus' : mediaIcon(mode))}"></span></button>`)
        .join('');
    }

    function renderTemplateFields() {
      const tpl = state.templates.find(t => t.templateCode === $('tplSelect').value) || {};
      const keys = tpl.placeholders && tpl.placeholders.length ? tpl.placeholders : ['text'];
      $('tplFields').innerHTML = keys.map(k => `<div class="field"><label>${esc(k)}</label><input data-param="${esc(k)}"></div>`).join('');
    }

    function labelMedia(mode) {
      return mode === 'image' ? '图片' : mode === 'video' ? '视频' : '文件';
    }

    function mediaIcon(mode) {
      return mode === 'image' ? 'image' : mode === 'video' ? 'video' : mode === 'template' ? 'template' : 'file';
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
""").toString();
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
