package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.callrecord.CallAudioSessionService;
import com.crmforlogistics.messagecenter.callrecord.CallRecordEvent;
import com.crmforlogistics.messagecenter.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.callrecord.CallRecordHttpAdapter;
import com.crmforlogistics.messagecenter.callrecord.CallRecordRuntime;
import com.crmforlogistics.messagecenter.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.callrecord.PhoneRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.net.URLEncoder;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson SSE_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int WECOM_VIEWER_REQUEST_MAX_BYTES = 4096;

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
            ChatAppTemplateSynchronizer templates = ChatAppTemplateSynchronizer.create(config);
            ChatAppHistorySyncService syncService = new ChatAppHistorySyncService(config, templates);
            try (ChatAppMessageSynchronizer synchronizer =
                         ChatAppMessageSynchronizer.create(config, syncService)) {
                System.out.println(GSON.toJson(synchronizer.sync().result()));
            }
            return;
        }
        if ("sync-templates".equalsIgnoreCase(command)) {
            System.out.println(GSON.toJson(new ChatAppHistorySyncService(config).syncTemplates()));
            return;
        }
        if ("wecom-access-token".equalsIgnoreCase(command)) {
            String authCorpId = requiredOption(args, "--auth-corp-id");
            System.out.println(fetchWeComAccessToken(config, authCorpId));
            return;
        }
        if ("wecom-debug-access-token".equalsIgnoreCase(command)) {
            if (args.length != 1) {
                throw new IllegalArgumentException("Usage: wecom-debug-access-token");
            }
            System.out.println(fetchDebugWeComAccessToken(config));
            return;
        }
        System.out.println("Usage: ./message-center-demo.ps1 web|bootstrap-admin|contacts|receive|sync|sync-templates|wecom-access-token --auth-corp-id <企业ID>|wecom-debug-access-token");
    }

    static String fetchWeComAccessToken(Config config, String authCorpId) throws Exception {
        WeComAuthorizationStore authorizationStore = new WeComAuthorizationStore(config);
        WeComAccessTokenService accessTokens = new WeComAccessTokenService(
                config, new WeComAuthorizationGateway(config));
        return fetchWeComAccessToken(config, authCorpId, authorizationStore, accessTokens);
    }

    static String fetchWeComAccessToken(Config config, String authCorpId,
                                        WeComAuthorizationStore authorizationStore,
                                        WeComAccessTokenService accessTokens) throws Exception {
        if (config.wecomSuiteId().isBlank()) {
            throw new IllegalArgumentException("WECOM_SUITE_ID is required");
        }
        if (authCorpId == null || authCorpId.isBlank()) {
            throw new IllegalArgumentException("--auth-corp-id is required");
        }
        WeComAuthorizationStore.ResolvedInstallation installation = authorizationStore.resolveActive(
                config.wecomSuiteId(), authCorpId.trim());
        return accessTokens.accessToken(installation);
    }

    static String fetchDebugWeComAccessToken(Config config) throws Exception {
        WeComAuthorizationGateway gateway = new WeComAuthorizationGateway(config);
        return fetchDebugWeComAccessToken(config, gateway::getDevelopedAppToken);
    }

    static String fetchDebugWeComAccessToken(Config config,
                                             WeComAccessTokenService.DevelopedAppTokenProvider provider)
            throws Exception {
        String corpId = config.value("WECOM_DEBUG_CORP_ID", config.wecomCorpId()).trim();
        String corpSecret = config.value("WECOM_DEBUG_CORP_SECRET",
                config.value("WECOM_PERMANENT_CODE", config.wecomSecret())).trim();
        if (corpId.isBlank()) {
            throw new IllegalArgumentException("WECOM_DEBUG_CORP_ID or WECOM_CORP_ID is required");
        }
        if (corpSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "WECOM_DEBUG_CORP_SECRET, WECOM_PERMANENT_CODE or WECOM_SECRET is required");
        }
        WeComAuthorizationGateway.CorpTokenResponse response = provider.fetch(
                corpId, corpSecret, Duration.ofSeconds(10));
        if (response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("企业微信未返回 access_token");
        }
        return response.accessToken();
    }

    private static String requiredOption(String[] args, String option) {
        if (args.length != 3 || !option.equals(args[1]) || args[2].isBlank()) {
            throw new IllegalArgumentException(
                    "Usage: wecom-access-token --auth-corp-id <企业ID>");
        }
        return args[2].trim();
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
        LocalWeComDevelopmentService localWeCom = config.localDevMode()
                ? new LocalWeComDevelopmentService(config) : null;
        MailSender mailSender = new MailSender(config);
        mailSender.reconcile(100);
        ChatAppHistoryStore chatAppHistoryStore = new ChatAppHistoryStore(config);
        ChatAppSender chatAppSender = new ChatAppSender(config, chatAppHistoryStore);
        EmailSyncService emailSyncService = new EmailSyncService(config);
        ChatAppTemplateSynchronizer templateSynchronizer = ChatAppTemplateSynchronizer.create(config);
        ChatAppHistorySyncService chatAppSyncService = new ChatAppHistorySyncService(
                config, chatAppHistoryStore, new TemplateStore(config.chatappTemplateFile()),
                ChatAppHistorySyncService.defaultMediaCacher(config), templateSynchronizer,
                () -> AliyunChatAppMessageGateway.open(config));
        ChatAppMessageSynchronizer messageSynchronizer =
                ChatAppMessageSynchronizer.create(config, chatAppSyncService);
        ChatAppMessageSyncRuntime messageRuntime =
                ChatAppMessageSyncRuntime.open(config, messageSynchronizer);
        WeComReceiver weComReceiver = new WeComReceiver(config);
        WeComAuthorizationStore authorizationStore = null;
        if (Files.exists(config.credentialMasterKeyFile())) {
            authorizationStore = new WeComAuthorizationStore(config);
        }
        WeComAuthorizationService authorizationService = null;
        WeComAuthorizationGateway authorizationGateway = null;
        WeComCallbackCodec callbackCodec = null;
        if (!config.wecomSuiteId().isBlank()) {
            callbackCodec = new WeComCallbackCodec(config);
            if (authorizationStore != null) {
                authorizationGateway = new WeComAuthorizationGateway(config);
            }
        }
        WeComAccessTokenService accessTokens = authorizationGateway == null
                ? null : new WeComAccessTokenService(config, authorizationGateway);
        WeComChatDataPublicKeyRegistrar publicKeyRegistrar = WeComChatDataPublicKeyRegistrar.open(
                config, authorizationStore, accessTokens);
        if (authorizationGateway != null) {
            authorizationService = new WeComAuthorizationService(config, authorizationStore,
                    authorizationGateway, publicKeyRegistrar);
        }
        WeComViewerService weComViewer = accessTokens == null
                ? new WeComViewerService(config, authorizationStore)
                : new WeComViewerService(config, authorizationStore, accessTokens, authorizationGateway);
        WeComLoginAttemptService weComLoginAttempts = new WeComLoginAttemptService(config, authorizationStore);
        WeComChatDataSyncService chatDataSync = accessTokens == null ? null
                : new WeComChatDataSyncService(config, new WeComChatDataGateway(config, accessTokens));
        WeComChatDataSyncRuntime weComChatDataRuntime =
                WeComChatDataSyncRuntime.open(config, authorizationStore, chatDataSync);
        WeComDailySummaryRuntime dailySummary = WeComDailySummaryRuntime.open(
                config, authorizationStore, accessTokens);
        EventHub events = new EventHub();
        CallRecordRuntime callRuntime = CallRecordRuntime.open(config, store, events::publish);
        CallRecordHttpAdapter callRecordHttp = null;
        if (callRuntime.available()) {
            ContactTimelineService timeline = new ContactTimelineService(store, callRuntime.service());
            PhoneRepository phoneRepository = new PhoneRepository(callRuntime.service(), store);
            CallAudioSessionService audioSessions = new CallAudioSessionService(config);
            callRecordHttp = new CallRecordHttpAdapter(
                    config, callRuntime.service(), timeline, callRuntime.audioStore(),
                    audioSessions, localWeCom == null ? weComViewer::requireViewerActor : localWeCom::requireViewerActor,
                    phoneRepository, store);
        }
        WeComAuthorizationService finalAuthorizationService = authorizationService;
        WeComChatDataPublicKeyRegistrar finalPublicKeyRegistrar = publicKeyRegistrar;
        WeComCallbackCodec finalCallbackCodec = callbackCodec;
        WeComChatDataSyncService finalChatDataSync = chatDataSync;
        LocalWeComDevelopmentService finalLocalWeCom = localWeCom;
        ChatAppTemplateSyncRuntime templateRuntime = ChatAppTemplateSyncRuntime.open(
                config, templateSynchronizer, events::publishTemplatesChanged);
        CallRecordHttpAdapter finalCallRecordHttp = callRecordHttp;
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(config.webBindAddress(), config.webPort()), 0);
        } catch (Exception startupFailure) {
            closeCallRuntime(callRuntime);
            dailySummary.close();
            weComChatDataRuntime.close();
            events.close();
            throw startupFailure;
        }
        server.createContext("/", exchange -> {
            try {
                if (finalCallRecordHttp != null && finalCallRecordHttp.handle(exchange)) return;
                if (finalCallRecordHttp == null && isCallRecordPath(exchange)) {
                    CallRecordException failure = callRuntime.startupFailure();
                    writeCallRuntimeError(exchange, failure);
                    return;
                }
                route(exchange, config, store, mailSender, chatAppSender, emailSyncService,
                        messageSynchronizer, weComReceiver, weComViewer, weComLoginAttempts,
                        finalChatDataSync, finalLocalWeCom, finalCallbackCodec, finalAuthorizationService, events);
            } catch (Exception ex) {
                writeRouteError(exchange, ex);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        try {
            server.start();
            templateRuntime.start();
            messageRuntime.start();
            weComChatDataRuntime.start();
        } catch (Exception startupFailure) {
            server.stop(0);
            closeCallRuntime(callRuntime);
            messageRuntime.close();
            templateRuntime.close();
            dailySummary.close();
            weComChatDataRuntime.close();
            events.close();
            throw startupFailure;
        }
        WeComDailySummaryRuntime finalDailySummary = dailySummary;
        CallRecordRuntime finalCallRuntime = callRuntime;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            messageRuntime.close();
            templateRuntime.close();
            weComChatDataRuntime.close();
            closeCallRuntime(finalCallRuntime);
            finalDailySummary.close();
            events.close();
            server.stop(0);
            if (finalAuthorizationService != null) finalAuthorizationService.close();
            finalPublicKeyRegistrar.close();
        }, "message-center-shutdown"));
        System.out.println("Message center demo started: http://localhost:" + config.webPort());
        System.out.println("ChatApp webhook endpoint: http://localhost:" + config.webPort() + "/webhook/chatapp");
    }

    private static boolean isCallRecordPath(HttpExchange exchange) {
        return CallRecordHttpAdapter.matchesRoute(exchange.getRequestURI().getRawPath());
    }

    private static void closeCallRuntime(CallRecordRuntime runtime) {
        try {
            runtime.close();
        } catch (CallRecordException exception) {
            System.err.println("call_record_shutdown_failed code=" + exception.code());
        }
    }

    private static void writeCallRuntimeError(HttpExchange exchange,
                                               CallRecordException failure) throws IOException {
        CallRecordException error = failure == null
                ? new CallRecordException("CALL_RECORD_UNAVAILABLE", 503,
                "电话记录服务不可用", false) : failure;
        writeJson(exchange, error.httpStatus(), Map.of(
                "code", error.code(),
                "message", error.getMessage() == null ? "" : error.getMessage(),
                "traceId", java.util.UUID.randomUUID().toString(),
                "context", Map.of()));
    }

    private static void route(HttpExchange exchange, Config config, UnifiedMessageStore store, MailSender mailSender,
                              ChatAppSender chatAppSender, EmailSyncService emailSyncService,
                              ChatAppMessageSynchronizer messageSynchronizer, WeComReceiver weComReceiver,
                              WeComViewerService weComViewer, WeComLoginAttemptService weComLoginAttempts,
                              WeComChatDataSyncService chatDataSync,
                              LocalWeComDevelopmentService localWeCom,
                              WeComCallbackCodec callbackCodec, WeComAuthorizationService authorizationService,
                              EventHub events) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String weComPath = path;
        if ("GET".equals(method) && "/".equals(path)) {
            writeHtml(exchange, pageHtml(config.localDevMode()));
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
        String emailAttachmentPrefix = path.startsWith("/api/v1/email/attachments/")
                ? "/api/v1/email/attachments/"
                : path.startsWith("/api/email/attachments/") ? "/api/email/attachments/" : "";
        if (!emailAttachmentPrefix.isBlank()) {
            if (!"GET".equals(method)) {
                writeJson(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED", "message", "GET required"));
                return;
            }
            String viewerToken = viewerAuthToken(exchange);
            if (viewerToken.isBlank()) {
                writeJson(exchange, 401, Map.of("code", "EMAIL_ATTACHMENT_VIEWER_REQUIRED", "message", "viewer auth required"));
                return;
            }
            try {
                if (localWeCom == null) weComViewer.requireViewerActor(viewerToken);
                else localWeCom.requireViewerActor(viewerToken);
            } catch (SecurityException exception) {
                writeJson(exchange, 401, Map.of("code", "EMAIL_ATTACHMENT_VIEWER_REQUIRED", "message", "viewer auth required"));
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                writeJson(exchange, 400, Map.of("code", "EMAIL_ATTACHMENT_PATH_INVALID", "message", "query parameters are not allowed"));
                return;
            }
            String[] segments = path.substring(emailAttachmentPrefix.length()).split("/", -1);
            if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
                writeJson(exchange, 404, Map.of("code", "EMAIL_ATTACHMENT_NOT_FOUND", "message", "attachment not found"));
                return;
            }
            String messageId = segments[0];
            String attachmentId = segments[1];
            UnifiedMessage message = store.findMessage(messageId);
            if (message == null) message = store.findMessage("email:" + messageId);
            EmailAttachment attachment = message == null ? null : message.attachments.stream()
                    .filter(item -> attachmentId.equals(item.id()) && "stored".equals(item.state()))
                    .findFirst().orElse(null);
            if (attachment == null) {
                writeJson(exchange, 404, Map.of("code", "EMAIL_ATTACHMENT_NOT_FOUND", "message", "attachment not found"));
                return;
            }
            try (InputStream input = new EmailAttachmentStore(config).open(messageId, attachmentId)) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/octet-stream");
                headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + percentEncodedFileName(attachment.fileName()));
                headers.set("Content-Length", Long.toString(attachment.sizeBytes()));
                headers.set("Cache-Control", "private, no-store");
                headers.set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, attachment.sizeBytes());
                try (OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
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
        if ("POST".equals(method) && ("/api/send/email".equals(path) || "/api/v1/email/messages".equals(path))) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                throw new EmailMultipartException("EMAIL_MULTIPART_REQUIRED", 400);
            }
            UnifiedMessage message = mailSender.send(new EmailMultipartParser(config).parse(exchange));
            events.publish(message);
            writeJson(exchange, 200, "/api/v1/email/messages".equals(path) ? emailMessageProjection(message) : message);
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
            SyncResult result = messageSynchronizer.sync().result();
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
        boolean weComAuthorizationCallback = "/api/v1/wecom/authorization/callback".equals(weComPath)
                || "/hook_path".equals(weComPath);
        if ("GET".equals(method) && weComAuthorizationCallback) {
            if (callbackCodec == null) {
                throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                        "企业微信授权服务尚未配置");
            }
            Map<String, String> params = query(exchange);
            String echo = callbackCodec.verifyAndDecryptEcho(
                    params.getOrDefault("msg_signature", ""), params.getOrDefault("timestamp", ""),
                    params.getOrDefault("nonce", ""), params.getOrDefault("echostr", ""));
            writeText(exchange, 200, echo);
            return;
        }
        if ("POST".equals(method) && weComAuthorizationCallback) {
            if (callbackCodec == null || authorizationService == null) {
                throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                        "企业微信授权服务尚未配置");
            }
            Map<String, String> params = query(exchange);
            byte[] bytes = exchange.getRequestBody().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) {
                throw new IllegalArgumentException("授权回调请求体过大");
            }
            WeComCallbackCodec.DecodedCallback callback = callbackCodec.decode(
                    params.getOrDefault("msg_signature", ""), params.getOrDefault("timestamp", ""),
                    params.getOrDefault("nonce", ""), new String(bytes, StandardCharsets.UTF_8));
            WeComAuthorizationService.CallbackAck ack = authorizationService.handle(callback);
            if (!ack.success()) {
                throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                        "企业微信授权事件队列已满");
            }
            writeText(exchange, 200, "success");
            return;
        }
        if ("GET".equals(method) && "/api/v1/wecom/js-sdk-config".equals(weComPath)) {
            if (localWeCom != null) {
                localWeCom.requireViewerActor(viewerAuthToken(exchange));
                writeJson(exchange, 200, localWeCom.jsSdkConfig());
                return;
            }
            writeJson(exchange, 200, weComViewer.jsSdkConfig(
                    query(exchange).getOrDefault("url", ""), viewerAuthToken(exchange)));
            return;
        }
        if ("POST".equals(method) && "/api/v1/wecom/login/attempts".equals(weComPath)) {
            readViewerJson(exchange);
            writeJson(exchange, 200, localWeCom == null
                    ? weComLoginAttempts.createAttempt() : localWeCom.createAttempt());
            return;
        }
        if ("POST".equals(method) && "/api/v1/wecom/login/exchange".equals(weComPath)) {
            JsonObject body = readViewerJson(exchange, "code", "state");
            if (localWeCom != null) {
                writeJson(exchange, 200, localWeCom.exchange(json(body, "code"), json(body, "state")));
                return;
            }
            WeComLoginAttemptService.InstallationBinding binding = weComLoginAttempts.consume(json(body, "state"));
            writeJson(exchange, 200, weComViewer.exchangeLoginCode(json(body, "code"), binding));
            return;
        }
        if ("POST".equals(method) && "/api/v1/wecom/conversation-view/sync".equals(weComPath)) {
            readViewerJson(exchange);
            String viewerAuthToken = viewerAuthToken(exchange);
            if (localWeCom != null) {
                writeJson(exchange, 200, localWeCom.sync(viewerAuthToken));
                return;
            }
            WeComViewerService.ViewerSyncContext syncContext = weComViewer.viewerSyncContext(viewerAuthToken);
            if (chatDataSync == null) {
                throw new WeComChatDataException("WECOM_CHATDATA_NOT_CONFIGURED", 503,
                        "企业微信会话同步尚未配置");
            }
            WeComChatDataSyncService.SyncResult result = chatDataSync.sync(syncContext);
            writeJson(exchange, 200, result);
            return;
        }
        if ("POST".equals(method) && "/api/v1/wecom/conversation-view/sessions".equals(weComPath)) {
            JsonObject body = readViewerJson(exchange, "conversationId", "contactPointId", "viewerAuthToken", "messageIds");
            String contactPointId = json(body, "contactPointId");
            String viewerAuthToken = json(body, "viewerAuthToken");
            List<String> messageIds = jsonStringList(body, "messageIds", 15);
            if (localWeCom != null) {
                requireReadableContactPoint(store, contactPointId);
                writeJson(exchange, 200, localWeCom.createSession(contactPointId, viewerAuthToken, messageIds));
                return;
            }
            try {
                requireReadableContactPoint(store, contactPointId);
            } catch (SecurityException denied) {
                try {
                    weComViewer.recordAccessDenied(contactPointId, viewerAuthToken);
                } catch (Exception auditFailure) {
                    denied.addSuppressed(auditFailure);
                }
                throw denied;
            }
            writeJson(exchange, 200, weComViewer.createViewerSession(
                    contactPointId, viewerAuthToken, messageIds));
            return;
        }
        if ("GET".equals(method) && weComPath.startsWith("/api/v1/wecom/conversation-view/sessions/")) {
            String sessionId = weComPath.substring("/api/v1/wecom/conversation-view/sessions/".length());
            writeJson(exchange, 200, localWeCom == null
                    ? weComViewer.viewerSession(sessionId, viewerAuthToken(exchange))
                    : localWeCom.readSession(sessionId, viewerAuthToken(exchange)));
            return;
        }
        if ("POST".equals(method) && "/api/v1/wecom/conversation-view/events".equals(weComPath)) {
            JsonObject body = readViewerJson(exchange, "eventType", "viewerSessionId");
            if (localWeCom == null) {
                weComViewer.recordClientEvent(json(body, "eventType"), json(body, "viewerSessionId"),
                        viewerAuthToken(exchange));
            } else {
                localWeCom.recordClientEvent(json(body, "eventType"), json(body, "viewerSessionId"),
                        viewerAuthToken(exchange));
            }
            writeJson(exchange, 202, Map.of("accepted", true));
            return;
        }
        writeJson(exchange, 404, Map.of("error", "NotFound", "message", path));
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              ChatAppMessageSynchronizer messageSynchronizer) throws Exception {
        EventHub events = new EventHub();
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        try {
            route(exchange, config, store, new MailSender(config),
                    new ChatAppSender(config, historyStore), new EmailSyncService(config),
                    messageSynchronizer, new WeComReceiver(config), null, null,
                    null, null, null, null, events);
        } catch (Exception exception) {
            writeRouteError(exchange, exception);
        } finally {
            events.close();
        }
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              WeComViewerService weComViewer) throws Exception {
        routeForTests(exchange, config, store, weComViewer, null);
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              WeComViewerService weComViewer,
                              WeComLoginAttemptService weComLoginAttempts) throws Exception {
        routeForTests(exchange, config, store, weComViewer, weComLoginAttempts, null, null, null);
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              WeComViewerService weComViewer,
                              WeComLoginAttemptService weComLoginAttempts,
                              WeComChatDataSyncService chatDataSync) throws Exception {
        routeForTests(exchange, config, store, weComViewer, weComLoginAttempts,
                chatDataSync, null, null);
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              WeComViewerService weComViewer,
                              WeComLoginAttemptService weComLoginAttempts,
                              WeComCallbackCodec callbackCodec,
                              WeComAuthorizationService authorizationService) throws Exception {
        routeForTests(exchange, config, store, weComViewer, weComLoginAttempts,
                null, callbackCodec, authorizationService);
    }

    static void routeForTests(HttpExchange exchange, Config config, UnifiedMessageStore store,
                              WeComViewerService weComViewer,
                              WeComLoginAttemptService weComLoginAttempts,
                              WeComChatDataSyncService chatDataSync,
                              WeComCallbackCodec callbackCodec,
                              WeComAuthorizationService authorizationService) throws Exception {
        EventHub events = new EventHub();
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        ChatAppHistorySyncService syncService = new ChatAppHistorySyncService(
                config, historyStore, new TemplateStore(config.chatappTemplateFile()));
        try (ChatAppMessageSynchronizer messageSynchronizer =
                     ChatAppMessageSynchronizer.create(config, syncService)) {
            LocalWeComDevelopmentService localWeCom = config.localDevMode()
                    ? new LocalWeComDevelopmentService(config) : null;
            route(exchange, config, store, new MailSender(config),
                    new ChatAppSender(config, historyStore), new EmailSyncService(config), messageSynchronizer,
                    new WeComReceiver(config), weComViewer, weComLoginAttempts, chatDataSync, localWeCom, callbackCodec,
                    authorizationService, events);
        } catch (Exception exception) {
            writeRouteError(exchange, exception);
        } finally {
            events.close();
        }
    }

    private static void writeRouteError(HttpExchange exchange, Exception exception) throws IOException {
        int status;
        String code;
        if (exception instanceof WeComChatDataException chatDataException) {
            status = chatDataException.httpStatus();
            code = chatDataException.code();
        } else if (exception instanceof WeComAuthorizationException authorizationException) {
            status = authorizationException.httpStatus();
            code = authorizationException.code();
        } else if (exception instanceof WeComViewerService.RateLimitException
                || exception instanceof WeComLoginAttemptService.PendingLimitException) {
            status = 429;
            code = "RATE_LIMITED";
        } else if (exception instanceof EmailMultipartException multipartException) {
            status = multipartException.status();
            code = multipartException.code();
        } else if (exception instanceof EmailSendException emailSendException) {
            code = emailSendException.errorCode();
            status = code.equals("EMAIL_ATTACHMENT_STORAGE_FULL") ? 507
                    : code.equals("EMAIL_ATTACHMENT_COUNT_LIMIT")
                    || code.equals("EMAIL_ATTACHMENT_SIZE_LIMIT") ? 413 : 500;
        } else if (exception instanceof SecurityException) {
            status = 403;
            code = "FORBIDDEN";
        } else if (exception instanceof IllegalArgumentException) {
            status = 400;
            code = "INVALID_REQUEST";
        } else {
            status = 500;
            code = "INTERNAL_ERROR";
        }
        writeJson(exchange, status, Map.of(
                "code", code,
                "message", exception.getMessage() == null ? "" : exception.getMessage(),
                "traceId", java.util.UUID.randomUUID().toString(),
                "fieldErrors", List.of()));
    }

    private static void requireReadableContactPoint(UnifiedMessageStore store, String contactPointId) throws IOException {
        if (contactPointId == null || contactPointId.isBlank()) {
            throw new SecurityException("WeCom contact point is not readable");
        }
        for (UnifiedContact contact : store.contacts()) {
            for (ContactPoint point : contact.points) {
                if (contactPointId.equals(point.id)) {
                    return;
                }
            }
        }
        throw new SecurityException("WeCom contact point is not readable");
    }

    private static String viewerAuthToken(HttpExchange exchange) {
        return ContactPointUtil.firstNonBlank(exchange.getRequestHeaders().getFirst("X-WeCom-Viewer-Auth"));
    }

    private static JsonObject emailMessageProjection(UnifiedMessage message) {
        JsonObject projection = new JsonObject();
        projection.addProperty("messageId", ContactPointUtil.firstNonBlank(message.sourceId, message.id));
        projection.addProperty("status", "sent");
        projection.addProperty("to", message.to == null ? "" : message.to);
        projection.addProperty("subject", message.title == null ? "" : message.title);
        projection.addProperty("body", message.bodyText == null ? "" : message.bodyText);
        com.google.gson.JsonArray attachments = new com.google.gson.JsonArray();
        for (EmailAttachment attachment : message.attachments == null ? List.<EmailAttachment>of() : message.attachments) {
            JsonObject item = new JsonObject();
            item.addProperty("id", attachment.id());
            item.addProperty("fileName", attachment.fileName());
            item.addProperty("mimeType", attachment.mimeType());
            item.addProperty("sizeBytes", attachment.sizeBytes());
            item.addProperty("state", attachment.state());
            if (attachment.errorCode() == null) item.add("errorCode", com.google.gson.JsonNull.INSTANCE);
            else item.addProperty("errorCode", attachment.errorCode());
            attachments.add(item);
        }
        projection.add("attachments", attachments);
        return projection;
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

    private static JsonObject readViewerJson(HttpExchange exchange, String... allowedFields) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(WECOM_VIEWER_REQUEST_MAX_BYTES + 1);
        if (bytes.length > WECOM_VIEWER_REQUEST_MAX_BYTES) {
            throw new IllegalArgumentException("WeCom viewer request body is too large");
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        JsonObject body = raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        List<String> allowed = List.of(allowedFields);
        for (String field : body.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unexpected WeCom viewer request field: " + field);
            }
        }
        return body;
    }

    private static String json(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static List<String> jsonStringList(JsonObject object, String key, int maxItems) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be a JSON array");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty() || array.size() > maxItems) {
            throw new IllegalArgumentException(key + " must contain 1 to " + maxItems + " items");
        }
        List<String> result = new ArrayList<>(array.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " items must be strings");
            }
            String item = element.getAsString().trim();
            if (item.isBlank() || item.length() > 256 || !seen.add(item)) {
                throw new IllegalArgumentException(key + " contains an invalid or duplicate item");
            }
            result.add(item);
        }
        return List.copyOf(result);
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

    private static void writeText(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
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

    private static String percentEncodedFileName(String value) {
        return URLEncoder.encode(safeHeaderFileName(value), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "%2F");
    }

    static String pageHtml() {
        return pageHtml(false);
    }

    static String pageHtml(boolean localDevMode) {
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
	    .thread.wecom-standalone-thread { padding:0; }
	    .message-row { display:grid; grid-template-columns:42px minmax(0, 76%); align-items:start; gap:10px; margin:0 0 12px; }
	    .message-row.outbound { grid-template-columns:minmax(0, 76%) 42px; justify-content:end; }
	    .message-row.outbound .msg-avatar { order:2; }
	    .message-row.outbound .msg-stack { order:1; align-items:flex-end; }
	    .message-row.wecom-message-row { grid-template-columns:minmax(0,1fr); gap:0; }
	    .message-row.wecom-message-row .msg-avatar { display:none; }
	    .message-row.wecom-message-row .msg-stack { width:100%; }
	    .message-row.wecom-message-row .msg.wecom-message, .message-row.wecom-message-row .wecom-segment-host, .message-row.wecom-message-row .wecom-segment-frame { width:100%; max-width:none; min-width:0; }
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
	    .call-row { display:grid; grid-template-columns:42px minmax(0, 76%); align-items:start; gap:10px; margin:0 0 12px; }
	    .call-row.outbound { grid-template-columns:minmax(0, 76%) 42px; justify-content:end; }
	    .call-row.outbound .msg-avatar { order:2; }
	    .call-row.outbound .msg-stack { order:1; align-items:flex-end; }
	    .call-card { width:min(320px, 100%); min-width:0; padding:10px 11px; border:1px solid #b8c5d8; border-left:3px solid var(--accent); border-radius:6px; background:#fff; cursor:pointer; text-align:left; }
	    .call-card:hover { background:#f7faff; }
	    .call-card.active { outline:2px solid var(--accent); outline-offset:1px; }
	    .call-card-head { display:flex; align-items:center; justify-content:space-between; gap:12px; }
	    .call-card-title { display:flex; align-items:center; gap:7px; min-width:0; font-weight:700; font-size:12px; }
	    .call-phone-icon { position:relative; width:15px; height:15px; flex:0 0 auto; border:2px solid var(--accent); border-top-color:transparent; border-right-color:transparent; border-radius:3px 3px 3px 8px; transform:rotate(-38deg); }
	    .call-state { flex:0 0 auto; color:var(--muted); font-size:11px; font-weight:700; }
	    .call-state.completed { color:#047857; }
	    .call-state.failed { color:var(--danger); }
	    .call-card-body { margin-top:7px; color:var(--muted); font-size:12px; display:flex; gap:8px; flex-wrap:wrap; }
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
	    .call-upload-form { display:grid; gap:9px; }
	    .call-upload-grid { display:grid; grid-template-columns:minmax(180px,1.25fr) minmax(110px,.6fr) minmax(180px,.9fr) minmax(150px,.8fr); gap:10px; align-items:end; }
	    .call-upload-actions { display:grid; grid-template-columns:minmax(160px,1fr) auto; gap:12px; align-items:center; }
	    .call-upload-progress { width:100%; height:8px; accent-color:var(--accent); }
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
    .wecom-viewer-panel { display:grid; gap:10px; }
    .wecom-viewer-actions { display:flex; justify-content:flex-end; gap:8px; align-items:center; }
    .wecom-viewer-status { min-height:18px; color:var(--muted); font-size:12px; }
    .msg.wecom-message { width:fit-content; max-width:min(560px,100%); min-width:0; padding:0; border:0; background:transparent; box-shadow:none; overflow:visible; cursor:default; }
    .wecom-segment-host { display:inline-grid; gap:4px; width:fit-content; max-width:100%; min-height:0; }
    .wecom-segment-host.pending { display:inline-grid; }
    .message-row:has(.wecom-segment-host.pending) { position:fixed; left:-100000px; top:0; width:min(760px,calc(100vw - 32px)); opacity:0; pointer-events:none; }
    .wecom-contact-window { position:fixed; left:-100000px; top:0; width:min(760px,calc(100vw - 32px)); opacity:0; pointer-events:none; contain:layout style paint; }
    .wecom-segment-frame { display:grid; box-sizing:border-box; width:min(560px,100%); min-width:240px; min-height:36px; overflow:visible; border:0; background:transparent; font-size:13px; }
    .wecom-segment-frame iframe { width:100%; height:100%; display:block; border:0; }
    .message-row.wecom-standalone-row { margin-bottom:0; }
    .wecom-standalone-row .msg.wecom-message, .wecom-standalone-row .wecom-segment-host, .wecom-standalone-row .wecom-segment-frame { width:100%; max-width:none; min-width:0; }
    .wecom-standalone-row .msg-meta-line { display:none; }
    .wecom-segment-host.expanded { width:min(560px,100%); }
    .wecom-segment-host.expanded > .wecom-segment-frame { display:none; }
    .wecom-message-preview { box-sizing:border-box; display:grid; grid-template-rows:auto minmax(0,auto); gap:8px; width:min(560px,100%); padding:12px; border:1px solid var(--line); border-radius:6px; background:#fff; }
    .wecom-message-preview-toolbar { min-height:24px; display:flex; align-items:center; justify-content:flex-end; }
    .wecom-message-collapse { width:24px; height:24px; padding:0; display:grid; place-items:center; border:0; background:transparent; }
    .wecom-message-preview-content { display:grid; place-items:center; width:100%; min-width:0; min-height:0; overflow:hidden; }
    .wecom-message-preview-content iframe { display:block; width:100%; height:100%; border:0; }
    .msg.wecom-segment-message.active { outline:none; }
    .wecom-segment-status { display:flex; align-items:center; gap:6px; height:32px; padding:4px 7px; color:var(--muted); font-size:11px; }
    .wecom-segment-status.error { color:var(--danger); background:#fff8f5; }
    .wecom-message-retry { width:22px; height:22px; padding:0; display:grid; place-items:center; border:0; background:transparent; color:var(--danger); }
    .wecom-message-retry:hover { background:#ffede8; }
    .wecom-message-warning { width:12px; height:12px; display:inline-grid; place-items:center; border:1px solid currentColor; border-radius:50%; font-size:9px; line-height:1; }
    .wecom-render-spinner, .wecom-contact-spinner { width:12px; height:12px; border:2px solid #d8def0; border-top-color:var(--accent); border-radius:50%; animation:wecom-spin .8s linear infinite; display:inline-block; flex:0 0 auto; }
    .wecom-contact-spinner { margin-left:5px; vertical-align:-2px; }
    @keyframes wecom-spin { to { transform:rotate(360deg); } }
    .wecom-login-screen { min-height:100vh; display:grid; place-items:center; padding:24px; background:#f7f7f8; }
    .wecom-login-screen[hidden], .shell[hidden] { display:none; }
    .wecom-login-card { width:min(392px,100%); min-width:0; display:grid; justify-items:center; gap:14px; padding:28px 24px; background:#fff; border:1px solid var(--line); border-radius:12px; box-shadow:0 18px 50px rgba(32,33,36,.10); }
    .wecom-login-brand { font-size:20px; font-weight:750; }
    .wecom-login-panel { width:min(320px,100%); min-width:0; min-height:380px; max-width:100%; overflow:hidden; }
    .wecom-login-status { min-height:18px; text-align:center; }
    .wecom-open-modal { width:min(960px,calc(100vw - 40px)); height:min(720px,calc(100vh - 40px)); padding:0; overflow:hidden; }
    .wecom-open-modal iframe { width:100%; height:100%; border:0; display:block; }
    .profile-save-state { min-width:68px; text-align:right; }
    .tag-preview { display:flex; gap:6px; flex-wrap:wrap; }
    .tag-pill { color:#047857; background:#ecfdf3; border:1px solid #9ed8b8; border-radius:999px; padding:2px 7px; font-size:11px; }
    .message-detail-panel { margin-top:18px; border-top:1px solid var(--line); padding-top:12px; }
    .message-detail-standalone { margin-top:0; border-top:0; padding-top:0; }
	.call-detail { display:grid; gap:14px; }
	.call-detail-title { display:flex; align-items:center; justify-content:space-between; gap:10px; font-weight:750; font-size:15px; }
	.call-detail audio { width:100%; }
	.call-detail-section { display:grid; gap:8px; }
	.call-detail-section h3 { margin:0; font-size:13px; }
	.transcript-segment { display:grid; grid-template-columns:72px minmax(0,1fr); gap:8px; padding:8px 0; border-bottom:1px solid var(--hairline); font-size:12px; line-height:1.45; }
	.transcript-time { color:var(--muted); font-variant-numeric:tabular-nums; }
	.call-transcript-text { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.5; font-size:13px; }
	.call-revision-form { display:grid; gap:8px; }
	.call-revision-form textarea { width:100%; min-height:112px; resize:vertical; border:1px solid var(--line); border-radius:4px; padding:9px; }
	.call-detail-actions { display:flex; justify-content:flex-end; gap:8px; flex-wrap:wrap; }
	.call-audio-error { color:var(--danger); font-size:12px; min-height:18px; }
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
	      .message-row, .message-row.outbound, .call-row, .call-row.outbound { grid-template-columns:42px minmax(0, 1fr); justify-content:stretch; }
	      .message-row.outbound .msg-avatar, .message-row.outbound .msg-stack, .call-row.outbound .msg-avatar, .call-row.outbound .msg-stack { order:initial; align-items:flex-start; }
	      .grid2, .composer-context, .media-editor, .template-form #tplFields, .call-upload-grid, .call-upload-actions { grid-template-columns:1fr; }
	      .file-drop { border-left:0; border-top:1px solid var(--hairline); min-height:58px; }
	    }
  </style>
</head>
<body>
  <section class="wecom-login-screen" id="wecomLoginScreen"__LOCAL_LOGIN_HIDDEN__>
    <div class="wecom-login-card">
      <div class="wecom-login-brand">统一消息中心</div>
      <div class="small">使用企业微信扫码后查看会话消息</div>
      <div class="wecom-login-panel" id="wwLoginPanel"><div class="empty">正在加载企业微信登录组件</div></div>
      <div class="wecom-login-status small" id="wecomLoginStatus" role="status">正在准备二维码</div>
      <button id="wecomLoginRetry" type="button" hidden>重新加载二维码</button>
    </div>
  </section>
  <main class="shell" id="shell"__LOCAL_SHELL_HIDDEN__>
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
          <div class="sync-actions"><button id="syncEmailBtn">收取邮件</button><button id="syncChatBtn">同步 WhatsApp</button><button id="syncWeComBtn">立即同步企业微信</button></div>
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
  <div class="modal-backdrop" id="wecomOpenModal" hidden>
    <div class="profile-modal wecom-open-modal" role="dialog" aria-modal="true" aria-label="企业微信会话详情">
      <iframe id="wecomOpenFrame" title="企业微信会话详情"></iframe>
    </div>
  </div>
  <div class="toast" id="toast"></div>
  <script>
    const THREAD_PAGE_SIZE = 15;
    const THREAD_PAGE_CACHE_LIMIT = 20;
    const THREAD_PAGE_MAX_MESSAGES = 200;
	const LOCAL_DEV_MODE = __LOCAL_DEV_MODE__;
	const CALL_AUDIO_RENEW_MS = 240000;
	const CALL_DETAIL_POLL_MS = 3000;
	const CALL_DETAIL_POLL_MAX_MS = 30000;
	const CALL_DETAIL_POLL_MAX_FAILURES = 5;
	const CALL_MAX_AUDIO_BYTES = 104857600;
    const WECOM_SDK_SRC = 'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js';
    const WECOM_JWXWORK_SRC = 'https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js';
    const WECOM_LOGIN_EXPIRED_MARKERS = ['42006','42003','40029','Missing open sid'];
    const WECOM_VIEWER_AUTO_REFRESH_MS = 60000;
    const WECOM_VIEWER_MOUNT_RETRY_BASE_MS = 5000;
    const WECOM_VIEWER_MOUNT_RETRY_MAX_MS = 60000;
    const WECOM_RENDER_CONCURRENCY = 4;
    const WECOM_VIEWPORT_COMMIT_MIN = 5;
    const WECOM_VIEWPORT_COMMIT_MAX = 8;
    const WECOM_ACTIVE_FRAME_LIMIT = 30;
    const WECOM_ACTIVE_CONTACT_SEGMENT_LIMIT = 15;
    const WECOM_RENDER_QUEUE_LIMIT = 15;
    const WECOM_SEGMENT_MESSAGE_LIMIT = 15;
    const WECOM_MIXED_SEGMENT_MAX = 6;
    const WECOM_CONTACT_WINDOW_LIMIT = 3;
    const WECOM_EXPANDED_PREVIEW_LIMIT = 15;
    const WECOM_PREVIEW_MIN_HEIGHT = 120;
    const WECOM_PREVIEW_DEFAULT_HEIGHT = 360;
    const WECOM_PREVIEW_MAX_HEIGHT = 560;
    let weComSdkLoadPromise = null;
    let weComJwxworkLoadPromise = null;
    const weComViewerLoadPromises = new Map();
    const weComViewerMountPromises = new Map();
    const weComViewerMountRetryState = new Map();
    let weComTimelineViewer = null;
	let weComRenderGeneration = 0;
	const weComSegmentFrameRegistry = new Map();
	const weComContactWindows = new Map();
	let weComCommittedContactPointId = '';
	let weComPreparingContactPointId = '';
	const weComRenderQueue = [];
	let weComActiveRenderJobs = 0;
	const weComCreatingSegmentKeys = new Map();
    const weComSegmentMessages = new Map();
    const weComExpandedPreviews = new Map();
    let weComExpandedPreviewSequence = 0;
	const state = { contacts: [], templates: [], capabilities: {}, selectedPointId: '', selectedMessageId: '', selectedCallRecordId:'', selectedChannel: '', selectedMode: 'text', mediaType: 'image', emailAttachments: [], lastKey: '', contactsRenderKey:'', threadRenderKeyByContact:{}, threadPages:{}, threadPageAccessOrder:[], threadLoadSeqByContact:{}, threadTouchY:0, detailCollapsed:false, profileDirty:false, profileSavedPointId:'', profileSavedTimer:null, selectedPointByChannel:{}, contactSnapshots:{}, unreadByContact:{}, isUserScrolling:false, pendingSilentRefresh:false, wecomLoginAttempt:null, wecomAuth:null, wecomAuthExpiresAt:0, viewerReloginPromise:null, messageCenterInitialized:false, eventSource:null, refreshTimer:null, callDetail:null, callDetailGeneration:0, callDetailPollTimer:null, callDetailPollFailures:0, callAudioRenewTimer:null, callAudioRecovered:false, callAudioSessionReady:false };
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
      if (!response.ok) {
        const error = new Error(data.message || data.code || data.error || response.statusText);
        error.code = data.code || '';
        error.status = response.status;
        throw error;
      }
      return data;
    };
	const viewerApi = async (url, options = {}) => {
	  const login = currentWeComAuth();
	  const headers = { ...(options.headers || {}), 'X-WeCom-Viewer-Auth': login.viewerAuthToken };
	  const response = await fetch(url, { ...options, headers });
	  const text = response.status === 204 ? '' : await response.text();
	  let data = {};
	  if (text) {
		try { data = JSON.parse(text); }
		catch (error) { throw new Error('电话记录服务返回了无效响应'); }
	  }
	  if (!response.ok) {
		const error = new Error(data.message || data.code || response.statusText);
		error.code = data.code || '';
		error.status = response.status;
		throw error;
	  }
	  return data;
	};
    const postJson = (url, body) => api(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) });
    const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
    const timeText = value => value ? new Date(value).toLocaleString() : '';
    const initials = name => (name || '?').trim().slice(0, 2).toUpperCase();
    const requestId = () => (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    function threadPageUrl(id, cursor = '') {
      let url = '/api/v1/contacts/' + encodeURIComponent(id) + '/timeline?limit=' + THREAD_PAGE_SIZE;
      if (cursor) url += '&cursor=' + encodeURIComponent(cursor);
      return url;
    }
    function rememberThreadPageAccess(id) {
      if (!id) return;
      state.threadPageAccessOrder = state.threadPageAccessOrder.filter(item => item !== id);
      state.threadPageAccessOrder.push(id);
      trimThreadPageCache();
    }
    function trimThreadPageCache() {
      const seen = new Set();
      const liveIds = [];
      state.threadPageAccessOrder.forEach(id => {
        if (!id || !state.threadPages[id] || seen.has(id)) return;
        seen.add(id);
        liveIds.push(id);
      });
      while (liveIds.length > THREAD_PAGE_CACHE_LIMIT) {
        const index = liveIds.findIndex(id => id !== state.selectedPointId);
        const evicted = liveIds.splice(index >= 0 ? index : 0, 1)[0];
        delete state.threadPages[evicted];
        delete state.threadRenderKeyByContact[evicted];
      }
      state.threadPageAccessOrder = liveIds;
    }
    function limitThreadPageMessages(page) {
      if (!page || !Array.isArray(page.items)) return page;
      if (page.items.length > THREAD_PAGE_MAX_MESSAGES) {
        page.items = page.items.slice(page.items.length - THREAD_PAGE_MAX_MESSAGES);
        page.nextCursor = null;
      }
      if (page.items.length >= THREAD_PAGE_MAX_MESSAGES && page.nextCursor) page.nextCursor = null;
      return page;
    }
    function formatDuration(millis) {
      return `${(Number(millis || 0) / 1000).toFixed(1)}s`;
    }
    const toast = text => { const el=$('toast'); el.textContent=text; el.style.display='block'; clearTimeout(window.toastTimer); window.toastTimer=setTimeout(()=>el.style.display='none',2600); };

    function setWeComLoginStatus(message, retryable = false) {
      $('wecomLoginStatus').textContent = message;
      $('wecomLoginRetry').hidden = !retryable;
    }

    async function initWeComLogin() {
      if (window.top !== window.self) throw new Error('企业微信登录和会话组件必须运行在顶层页面');
      if (LOCAL_DEV_MODE) {
        $('wecomLoginScreen').hidden = true;
        $('shell').hidden = false;
        const attempt = await postJson('/api/v1/wecom/login/attempts', {});
        state.wecomLoginAttempt = attempt;
        await completeWeComLogin('local-development-code');
        return;
      }
      $('shell').hidden = true;
      $('wecomLoginScreen').hidden = false;
      $('wwLoginPanel').innerHTML = '<div class="empty">正在准备登录配置</div>';
      setWeComLoginStatus('正在准备二维码');
      const attempt = await postJson('/api/v1/wecom/login/attempts', {});
      state.wecomLoginAttempt = attempt;
      $('wwLoginPanel').innerHTML = '<div class="empty">正在加载企业微信登录组件</div>';
      const ww = await loadWeComSdk();
      $('wwLoginPanel').innerHTML = '';
      ww.createWWLoginPanel({
        el: '#wwLoginPanel',
        params: {
          login_type: attempt.loginType,
          appid: attempt.appId,
          redirect_uri: attempt.redirectUri,
          state: attempt.state,
          redirect_type: 'callback',
          panel_size: 'small',
          lang: 'zh'
        },
        onCheckWeComLogin({ isWeComLogin }) {
          setWeComLoginStatus(isWeComLogin ? '请在企业微信中确认登录' : '请使用企业微信扫码登录');
        },
        onLoginSuccess({ code }) {
          completeWeComLogin(code)
            .catch(error => setWeComLoginStatus(`登录失败：${error.message}`, true));
        },
        onLoginFail({ errCode, errMsg }) {
          setWeComLoginStatus(`企业微信登录失败：${errMsg || errCode || '未知错误'}`, true);
        }
      });
    }

    async function completeWeComLogin(code) {
      const attempt = state.wecomLoginAttempt;
      if (!attempt?.state || !code) throw new Error('企业微信登录结果无效，请重新扫码');
      setWeComLoginStatus('正在进入消息中心');
      const login = await postJson('/api/v1/wecom/login/exchange', { code, state:attempt.state });
      state.wecomLoginAttempt = null;
      state.wecomAuth = login;
      state.wecomAuthExpiresAt = Date.now() + Number(login.expiresIn || 0) * 1000;
      $('wwLoginPanel').innerHTML = '';
      $('wecomLoginScreen').hidden = true;
      $('shell').hidden = false;
      if (!LOCAL_DEV_MODE) {
        Promise.all([loadWeComSdk(), loadWeComJwxwork()])
          .catch(error => console.warn('企业微信会话组件预加载失败', error));
      }
      try {
        await enterMessageCenter();
      } catch (error) {
        state.wecomAuth = null;
        state.wecomAuthExpiresAt = 0;
        $('shell').hidden = !LOCAL_DEV_MODE;
        $('wecomLoginScreen').hidden = LOCAL_DEV_MODE;
        throw error;
      }
    }

	function currentWeComAuth() {
	  if (!state.wecomAuth?.viewerAuthToken || Date.now() >= state.wecomAuthExpiresAt) {
		const error = new Error('企业微信登录已过期，请重新扫码');
		error.code = 'WECOM_VIEWER_AUTH_EXPIRED';
		error.status = 401;
		throw error;
	  }
	  return state.wecomAuth;
	}

	async function returnToWeComLogin(message) {
      state.wecomAuth = null;
      state.wecomAuthExpiresAt = 0;
      state.wecomLoginAttempt = null;
      weComTimelineViewer = null;
      cancelWeComRenderWork();
      clearWeComInlinePreviews();
      weComSegmentFrameRegistry.forEach(entry => entry.instance?.destroy?.());
      weComSegmentFrameRegistry.clear();
      weComViewerLoadPromises.clear();
      weComViewerMountPromises.clear();
      weComViewerMountRetryState.clear();
      weComContactWindows.forEach(windowState => windowState.container?.remove?.());
      weComContactWindows.clear();
      weComCommittedContactPointId = '';
      weComPreparingContactPointId = '';
      if (state.eventSource) state.eventSource.close();
      state.eventSource = null;
      if (state.refreshTimer) clearInterval(state.refreshTimer);
      state.refreshTimer = null;
	  clearCallDetailActivity();
      $('shell').hidden = true;
      $('wecomLoginScreen').hidden = false;
      setWeComLoginStatus(message || '请重新扫码登录');
	  await initWeComLogin();
	}

	async function handleViewerAuthFailure(error) {
	  if (!isWeComLoginExpired(error)) return false;
	  if (!state.viewerReloginPromise) {
		state.viewerReloginPromise = returnToWeComLogin('企业微信登录已失效，请重新扫码')
		  .catch(loginError => setWeComLoginStatus(`重新登录失败：${loginError.message}`, true))
		  .finally(() => { state.viewerReloginPromise = null; });
	  }
	  await state.viewerReloginPromise;
	  return true;
	}

    async function enterMessageCenter() {
      const caps = await api('/api/channel-capabilities');
      caps.forEach(item => state.capabilities[item.channel] = item);
      state.templates = await api('/api/templates');
      await loadContacts(false);
      if (!state.messageCenterInitialized) {
        state.messageCenterInitialized = true;
        $('refreshBtn').onclick = () => refreshAll(false);
        $('syncEmailBtn').onclick = syncEmail;
        $('syncChatBtn').onclick = syncChatApp;
        $('syncWeComBtn').onclick = syncWeCom;
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
        $('wecomOpenModal').onclick = event => { if (event.target === $('wecomOpenModal')) closeWeComModal(); };
        document.addEventListener('keydown', event => {
          if (event.key === 'Escape') {
            closeImagePreview();
            closeWeComModal();
          }
        });
		document.addEventListener('visibilitychange', handleCallAudioVisibility);
        $('notifyBtn').onclick = enableNotifications;
      }
      connectEvents();
      if (state.refreshTimer) clearInterval(state.refreshTimer);
	  state.refreshTimer = setInterval(() => refreshInBackground(true), 5000);
	}

	async function refreshAll(silent) {
	      if (silent && isUserScrolling()) {
	        state.pendingSilentRefresh = true;
	        return;
	      }
	      await loadContacts(silent);
	  if (state.selectedPointId) await loadThread(state.selectedPointId, true);
	}

	async function refreshInBackground(silent) {
	  try {
		await refreshAll(silent);
		await refreshWeComViewerIfDue();
	  } catch (error) {
		if (!(await handleViewerAuthFailure(error))) toast(`刷新失败：${error.message}`);
	  }
	}

	    async function refreshSelectedContactViews(silent) {
	      await refreshAll(silent);
	      const contact = selectedContact();
	      if (contact && ![...contact.channels, 'wecom', 'callRecord'].includes(state.selectedChannel)) {
	        state.selectedChannel = contact.channels[0] || '';
	      }
	      renderComposer();
	    }

    async function refreshTemplates() {
      state.templates = await api('/api/templates');
      if (state.selectedChannel === 'chatapp' && state.selectedMode === 'template') {
        renderComposer();
      }
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

	    async function syncWeCom() {
	      const button = $('syncWeComBtn');
	      if (button) button.disabled = true;
	      try {
	        const login = currentWeComAuth();
	        const result = await api('/api/v1/wecom/conversation-view/sync', {
	          method: 'POST',
	          headers: { 'Content-Type': 'application/json', 'X-WeCom-Viewer-Auth': login.viewerAuthToken },
	          body: '{}'
	        });
		        toast(`企业微信同步完成：${result.stored || 0} 条，跳过 ${result.skipped || 0} 条`);
		        await refreshAll(false);
		        weComTimelineViewer = null;
		        await refreshWeComViewerIfDue();
	      } catch (error) {
	        if (isWeComLoginExpired(error)) {
	          await returnToWeComLogin('企业微信登录已失效，请重新扫码');
	          return;
	        }
	        toast(`企业微信同步失败：${error.message}`);
	      } finally {
	        if (button) button.disabled = false;
	      }
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
	      const lockDetail = state.profileDirty || state.selectedMessageId || state.selectedCallRecordId || profileModalOpen() || detailEditing();
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
        if (id === 'thread' && el) {
          el.onwheel = event => { if (event.deltaY < 0) loadOlderThreadMessages(); };
          el.ontouchstart = event => { state.threadTouchY = event.touches?.[0]?.clientY || 0; };
          el.ontouchmove = event => {
            const y = event.touches?.[0]?.clientY || 0;
            if (y > state.threadTouchY + 8) loadOlderThreadMessages();
            state.threadTouchY = y;
          };
        }
      });
    }

    function nextThreadLoadSeq(id) {
      state.threadLoadSeqByContact[id] = currentThreadLoadSeq(id) + 1;
      return state.threadLoadSeqByContact[id];
    }

    function currentThreadLoadSeq(id) {
      return state.threadLoadSeqByContact[id] || 0;
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
      return channel === 'email' ? '邮件' : channel === 'chatapp' ? 'WhatsApp' : channel === 'wecom' ? '企业微信' : channel === 'callRecord' ? '电话记录' : channel;
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

    async function openEmailAttachment(messageId, attachment) {
      try {
        const auth = currentWeComAuth();
        const response = await fetch('/api/email/attachments/' + encodeURIComponent(messageId) + '/' + encodeURIComponent(attachment.id), {
          headers: { 'X-WeCom-Viewer-Auth': auth.viewerAuthToken }, cache:'no-store'
        });
        if (!response.ok) throw new Error(response.statusText || attachment.errorCode || '附件暂不可用');
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        downloadBlob(url, attachment.fileName || 'attachment');
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch (error) {
        toast(`附件下载失败：${error.message}`);
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

    function bubbleText(m) {
      if (m.channel === 'email') return '';
      return m.text || m.summary || '';
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

    function getOrCreateWeComContactWindow(contactPointId) {
      pruneExpiredWeComContactWindows();
      let windowState = weComContactWindows.get(contactPointId);
      if (windowState) {
        windowState.lastUsed = Date.now();
        return windowState;
      }
      const container = document.createElement('div');
      container.className = 'wecom-contact-window';
      container.dataset.contactPointId = contactPointId;
      document.body.appendChild(container);
      windowState = {
        contactPointId,
        container,
        viewer:null,
        ready:false,
        committed:false,
        generation:0,
        lastUsed:Date.now()
      };
      weComContactWindows.set(contactPointId, windowState);
      return windowState;
    }

    function trimWeComContactWindows() {
      while (weComContactWindows.size > WECOM_CONTACT_WINDOW_LIMIT) {
        const candidate = Array.from(weComContactWindows.values())
          .filter(item => item.contactPointId !== state.selectedPointId
            && item.contactPointId !== weComCommittedContactPointId)
          .sort((left, right) => left.lastUsed - right.lastUsed)[0];
        if (!candidate) break;
        Array.from(weComSegmentFrameRegistry.entries())
          .filter(([, entry]) => entry.contactPointId === candidate.contactPointId)
          .forEach(([key, entry]) => {
            collapseWeComInlinePreviewForHost(entry.host);
            entry.instance?.destroy?.();
            weComSegmentFrameRegistry.delete(key);
          });
        clearWeComInlinePreviews(candidate.contactPointId);
        weComViewerMountRetryState.delete(candidate.contactPointId);
        candidate.container.remove();
        weComContactWindows.delete(candidate.contactPointId);
      }
    }

    function pruneExpiredWeComContactWindows() {
      const now = Date.now();
      Array.from(weComContactWindows.values()).forEach(windowState => {
        if (!windowState.viewer?.expiresAt || now < windowState.viewer.expiresAt) return;
        invalidateWeComContactWindow(windowState.contactPointId);
      });
    }

    function retainRecentWeComFrames(contactPointId, limit = WECOM_VIEWPORT_COMMIT_MAX) {
      const windowState = weComContactWindows.get(contactPointId);
      if (!windowState) return;
      const segmentIds = Array.from(windowState.container.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]'))
        .map(host => host.dataset.wecomSegmentId || '')
        .filter(Boolean);
      const retained = new Set(segmentIds.slice(-limit));
      Array.from(weComSegmentFrameRegistry.entries())
        .filter(([, entry]) => entry.contactPointId === contactPointId)
        .forEach(([key, entry]) => {
          const segmentId = key.slice(contactPointId.length + 1);
          if (retained.has(segmentId)) return;
          collapseWeComInlinePreviewForHost(entry.host);
          entry.instance?.destroy?.();
          entry.host?.replaceChildren?.();
          entry.host?.classList?.add('pending');
          weComSegmentFrameRegistry.delete(key);
        });
      if (windowState.viewer?.detail?.messages) {
        const retainedMessageIds = new Set(Array.from(retained)
          .flatMap(segmentId => (weComSegmentMessages.get(segmentId) || []).map(weComMessageId))
          .filter(Boolean));
        windowState.viewer.detail.messages = windowState.viewer.detail.messages
          .filter(message => retainedMessageIds.has(message.msgid));
        windowState.viewer.loadedAt = 0;
      }
    }

    function invalidateWeComContactWindow(contactPointId) {
      clearWeComInlinePreviews(contactPointId);
      weComViewerMountRetryState.delete(contactPointId);
      Array.from(weComSegmentFrameRegistry.entries())
        .filter(([, entry]) => entry.contactPointId === contactPointId)
        .forEach(([key, entry]) => {
          entry.instance?.destroy?.();
          entry.host?.replaceChildren?.();
          entry.host?.classList?.add?.('pending');
          weComSegmentFrameRegistry.delete(key);
        });
      if (weComTimelineViewer?.contactPointId === contactPointId) {
        weComTimelineViewer = null;
      }
      const windowState = weComContactWindows.get(contactPointId);
      if (!windowState) return;
      windowState.viewer = null;
      windowState.ready = false;
      if (contactPointId !== weComCommittedContactPointId) {
        windowState.container.replaceChildren();
        windowState.container.remove();
        weComContactWindows.delete(contactPointId);
      }
    }

    function weComContactWindowUsable(windowState) {
      if (!windowState?.ready) return false;
      return !windowState.viewer?.expiresAt || Date.now() < windowState.viewer.expiresAt;
    }

    function stashCommittedWeComContactWindow() {
      if (!weComCommittedContactPointId) return;
      const current = weComContactWindows.get(weComCommittedContactPointId);
      const threadEl = $('thread');
      if (!current || !threadEl || !threadEl.children.length) return;
      current.container.replaceChildren(...Array.from(threadEl.children));
      current.committed = false;
      current.lastUsed = Date.now();
      retainRecentWeComFrames(current.contactPointId);
    }

    function commitWeComContactWindow(contactPointId, windowState) {
      if (!windowState || windowState.contactPointId !== contactPointId || !windowState.ready) return false;
      const threadEl = $('thread');
      if (!threadEl) return false;
      if (weComCommittedContactPointId && weComCommittedContactPointId !== contactPointId) {
        stashCommittedWeComContactWindow();
      } else if (weComCommittedContactPointId !== contactPointId) {
        threadEl.replaceChildren();
      }
      if (windowState.container.children.length) {
        threadEl.replaceChildren(...Array.from(windowState.container.children));
      }
      threadEl.classList?.toggle?.('wecom-standalone-thread',
        weComLayoutMode(selectedContact()) === 'standalone');
      windowState.committed = true;
      windowState.lastUsed = Date.now();
      weComCommittedContactPointId = contactPointId;
      if (weComPreparingContactPointId === contactPointId) weComPreparingContactPointId = '';
      weComTimelineViewer = windowState.viewer;
      bindThreadInteractions(threadEl);
      setWeComTitleLoading(false);
      if (!LOCAL_DEV_MODE && windowState.viewer) {
        mountWeComTimelineMessages(windowState.viewer.detail, windowState.viewer.viewerAuthToken,
          contactPointId, threadEl, windowState.generation, false)
          .catch(error => toast(`企业微信消息后台加载失败：${error.message}`));
      }
      resizeWeComStandaloneFrames(threadEl);
      requestAnimationFrame(() => threadEl.scrollTop = threadEl.scrollHeight);
      trimWeComContactWindows();
      return true;
    }

    async function selectContact(id) {
      const changed = state.selectedPointId !== id;
      const generation = changed ? cancelWeComRenderWork() : weComRenderGeneration;
      if (changed) {
        state.profileDirty = false;
        state.profileSavedPointId = '';
        closeProfileModal();
		clearCallDetailActivity();
      }
      state.selectedPointId = id;
      state.selectedMessageId = '';
      clearContactUnread(id);
      const contact = state.contacts.find(c => c.id === id);
      if (contact) state.selectedChannel = contact.channels.includes(state.selectedChannel) ? state.selectedChannel : contact.channels[0];
      renderContacts();
      updateContactHeader(contact);
      const point = wecomPoint(contact);
      let cachedWindow = point ? weComContactWindows.get(id) : null;
      if (cachedWindow && !weComContactWindowUsable(cachedWindow)) {
        invalidateWeComContactWindow(id);
        cachedWindow = null;
      }
      if (cachedWindow?.ready && cachedWindow.container.children.length) {
        commitWeComContactWindow(id, cachedWindow);
        renderComposer();
        renderContactDetail(contact);
        await refreshWeComViewerIfDue();
        return;
      }
      if (point && changed) {
        const windowState = getOrCreateWeComContactWindow(id);
        weComPreparingContactPointId = id;
        windowState.ready = false;
        windowState.generation = generation;
        windowState.container.replaceChildren();
        setWeComTitleLoading(true);
        await loadThread(id, false, { target:windowState.container, restoreViewer:false });
        if (generation !== weComRenderGeneration || state.selectedPointId !== id) return;
        renderComposer();
        renderContactDetail(contact);
        await loadWeComViewer(contact, { automatic:true, windowState, generation });
        if (generation !== weComRenderGeneration || state.selectedPointId !== id) return;
        if (!windowState.ready) {
          setWeComTitleLoading(false);
          return;
        }
        commitWeComContactWindow(id, windowState);
        return;
      }
      if (weComCommittedContactPointId && weComCommittedContactPointId !== id) {
        stashCommittedWeComContactWindow();
        weComCommittedContactPointId = '';
        weComTimelineViewer = null;
      }
      weComPreparingContactPointId = '';
      await loadThread(id, false);
      renderComposer();
      renderContactDetail(contact);
      await refreshWeComViewerIfDue();
    }

    function timelineItemKey(item) {
      return `${item?.type || 'message'}:${item?.sortId || item?.payload?.id || ''}`;
    }

    function timelineItemFingerprint(item) {
      const message = item?.payload || {};
      return [item?.occurredAt || '', message.id || '', message.direction || '',
        message.status || '', message.statusTimestamp || '', message.title || '',
        message.text || '', message.summary || '', message.mediaUrl || '',
        message.state || '', message.version || 0].join('~');
    }

    function weComLayoutMode(contact) {
      const channels = new Set([
        ...(contact?.channels || []),
        ...(contact?.points || []).map(point => point?.channel)
      ].filter(Boolean));
      return channels.size === 1 && channels.has('wecom') ? 'standalone' : 'mixed';
    }

    function balancedWeComSegmentSizes(messageCount, maxWeComMessages = WECOM_MIXED_SEGMENT_MAX) {
      const total = Math.max(0, Number(messageCount) || 0);
      const max = Math.max(1, Number(maxWeComMessages) || WECOM_MIXED_SEGMENT_MAX);
      if (!total) return [];
      const groupCount = Math.ceil(total / max);
      const base = Math.floor(total / groupCount);
      const largerGroups = total % groupCount;
      return Array.from({ length:groupCount }, (_, index) =>
        base + (index >= groupCount - largerGroups ? 1 : 0));
    }

    function standaloneWeComWindow(items, maxWeComMessages = WECOM_SEGMENT_MESSAGE_LIMIT) {
      const limit = Math.max(1, Number(maxWeComMessages) || WECOM_SEGMENT_MESSAGE_LIMIT);
      return (items || [])
        .filter(item => item?.type === 'message' && item?.payload?.channel === 'wecom')
        .slice(-limit);
    }

    function segmentTimelineItems(items, maxWeComMessages = WECOM_SEGMENT_MESSAGE_LIMIT,
        balanced = false) {
      const limit = Math.max(1, Number(maxWeComMessages) || WECOM_SEGMENT_MESSAGE_LIMIT);
      const units = [];
      let segment = [];
      const flush = () => {
        if (balanced) {
          balancedWeComSegmentSizes(segment.length, limit).forEach(size =>
            units.push({ kind:'wecom-segment', items:segment.splice(0, size) }));
          return;
        }
        while (segment.length) units.push({ kind:'wecom-segment', items:segment.splice(0, limit) });
      };
      (items || []).forEach(item => {
        if (item?.type === 'message' && item?.payload?.channel === 'wecom') {
          segment.push(item);
          if (!balanced && segment.length === limit) flush();
          return;
        }
        flush();
        units.push({ kind:'item', item });
      });
      flush();
      return units;
    }

    function weComSegmentId(contactPointId, messages) {
      const input = `${String(contactPointId || '')}|${(messages || []).map(item => {
        const payload = item?.payload || {};
        return String(payload.sourceId || payload.id || '').replace(/^wecom:/, '');
      }).join('|')}`;
      let hash = 2166136261;
      for (let index = 0; index < input.length; index += 1) {
        hash ^= input.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
      }
      return `wecom-segment-${(hash >>> 0).toString(16).padStart(8, '0')}`;
    }

    function reconcileThreadMessages(contact, items, target) {
      const layoutMode = weComLayoutMode(contact);
      const markup = threadMessagesHtml(items, contact?.id || state.selectedPointId || '', layoutMode);
      if (!target?.querySelectorAll || !document.createElement || !document.createDocumentFragment) {
        target.innerHTML = markup;
        pruneDisconnectedWeComInlinePreviews();
        pruneDisconnectedWeComSegmentFrames();
        return [];
      }
      const staging = document.createElement('div');
      staging.innerHTML = markup;
      const rows = commitThreadRows(target, Array.from(staging.children));
      pruneDisconnectedWeComInlinePreviews();
      pruneDisconnectedWeComSegmentFrames();
      return rows;
    }

    function commitThreadRows(target, rows) {
      const existing = new Map(Array.from(target.querySelectorAll(':scope > [data-timeline-key]'))
        .map(row => [row.dataset.timelineKey, row]));
      const fragment = document.createDocumentFragment();
      rows.forEach(next => {
        const current = existing.get(next.dataset.timelineKey);
        if (!current) {
          fragment.appendChild(next);
          return;
        }
        existing.delete(next.dataset.timelineKey);
        const isWeCom = Boolean(current.querySelector('[data-wecom-segment-id]'));
        if (!isWeCom && current.dataset.renderFingerprint !== next.dataset.renderFingerprint) {
          fragment.appendChild(next);
          return;
        }
        current.className = next.className;
        current.dataset.renderFingerprint = next.dataset.renderFingerprint;
        const currentWeComHost = current.querySelector('[data-wecom-segment-id]');
        const nextWeComHost = next.querySelector('[data-wecom-segment-id]');
        if (currentWeComHost && nextWeComHost) {
          currentWeComHost.dataset.wecomLayout = nextWeComHost.dataset.wecomLayout || 'mixed';
          const frame = currentWeComHost.querySelector('.wecom-segment-frame');
          if (frame) {
            const messages = weComSegmentMessages.get(currentWeComHost.dataset.wecomSegmentId || '') || [];
            frame.style.height = `${weComSegmentFrameHeight(currentWeComHost, messages.length)}px`;
          }
        }
        const currentMessage = current.querySelector('.msg[data-id]');
        const nextMessage = next.querySelector('.msg[data-id]');
        if (currentMessage && nextMessage) currentMessage.className = nextMessage.className;
        const currentMeta = current.querySelector('.msg-meta-line');
        const nextMeta = next.querySelector('.msg-meta-line');
        if (currentMeta && nextMeta) {
          currentMeta.replaceChildren(...Array.from(nextMeta.childNodes));
        }
        fragment.appendChild(current);
      });
      target.replaceChildren(fragment);
      return Array.from(target.children);
    }

    function threadMessagesHtml(items, contactPointId = state.selectedPointId || '',
        layoutMode = 'mixed') {
      const standalone = layoutMode === 'standalone';
      const segmentLimit = standalone ? WECOM_SEGMENT_MESSAGE_LIMIT : WECOM_MIXED_SEGMENT_MAX;
      const renderItems = standalone ? standaloneWeComWindow(items, segmentLimit) : items;
      return segmentTimelineItems(renderItems, segmentLimit, !standalone).map(unit => {
        if (unit.kind === 'wecom-segment') {
          const segmentId = weComSegmentId(contactPointId, unit.items);
          const segmentItems = unit.items.map(item => item.payload || {});
          weComSegmentMessages.set(segmentId, segmentItems);
          const first = segmentItems[0] || {};
          const timelineKey = `wecom-segment:${segmentId}`;
          const fingerprint = unit.items.map(timelineItemFingerprint).join('|');
          const direction = first.direction === 'outbound' ? 'outbound' : 'inbound';
          const meta = [
            '<span>企业微信</span>',
            segmentItems.length > 1 ? `<span>${esc(segmentItems.length)} 条连续消息</span>` : '',
            first.timestamp ? `<span>${esc(timeText(first.timestamp))}</span>` : ''
          ].filter(Boolean).join('');
          return `
            <div class="message-row ${esc(direction)} wecom-message-row${standalone ? ' wecom-standalone-row' : ''}" data-timeline-key="${esc(timelineKey)}" data-render-fingerprint="${esc(fingerprint)}">
              <div class="msg-avatar"><span class="avatar-icon wecom"></span></div>
              <div class="msg-stack">
                <article class="msg ${esc(direction)} wecom-message wecom-segment-message">
                  <div class="wecom-segment-host pending" data-wecom-segment-id="${esc(segmentId)}" data-wecom-layout="${esc(layoutMode)}"></div>
                </article>
                <div class="msg-meta-line">${meta}</div>
              </div>
            </div>`;
        }
        const item = unit.item;
        const timelineKey = timelineItemKey(item);
        const fingerprint = timelineItemFingerprint(item);
        if (item.type === 'callRecord') {
          return renderCallRecordCard(item).replace(/^\s*<div /,
            `<div data-timeline-key=\"${esc(timelineKey)}\" data-render-fingerprint=\"${esc(fingerprint)}\" `);
        }
        const m = item.payload || {};
        const isWeCom = m.channel === 'wecom';
        const direction = m.direction === 'outbound' ? 'outbound' : 'inbound';
        const meta = [
          `<span>${esc(label(m.channel))}</span>`,
          m.timestamp ? `<span>${esc(timeText(m.timestamp))}</span>` : '',
          `<span class=\"status-icon ${esc(statusClass(m))}\" title=\"${esc(statusText(m))}\" aria-label=\"${esc(statusText(m))}\"></span>`,
          hasMedia(m) ? `<button class=\"media-open-link\" type=\"button\" data-open-media=\"${esc(mediaUrl(m))}\" data-file-name=\"${esc(m.fileName || 'attachment')}\">打开附件</button>` : ''
        ].filter(Boolean).join('');
        const text = bubbleText(m);
        const body = `${m.title ? `<div class=\"msg-title\">${esc(m.title)}</div>` : ''}
                ${text ? `<div class=\"msg-text\">${esc(text)}</div>` : ''}
                ${mediaPreviewHtml(m)}`;
        return `
          <div class=\"message-row ${esc(direction)}\" data-timeline-key=\"${esc(timelineKey)}\" data-render-fingerprint=\"${esc(fingerprint)}\">
            <div class=\"msg-avatar\"><span class=\"avatar-icon ${esc(m.channel || '')}\"></span></div>
            <div class=\"msg-stack\">
              <article class=\"msg ${esc(direction)} ${isWeCom ? 'wecom-message' : ''} ${hasMedia(m) ? 'has-media' : ''} ${m.id === state.selectedMessageId ? 'active' : ''}\" data-id=\"${esc(m.id)}\">
                ${body}
              </article>
              <div class=\"msg-meta-line\">${meta}</div>
            </div>
          </div>`;
      }).join('') || '<div class=\"empty\">暂无消息</div>';
    }

    function bindThreadInteractions(threadEl) {
      threadEl.querySelectorAll('.msg[data-id]').forEach(item => item.onclick = () => selectMessage(item.dataset.id));
	  threadEl.querySelectorAll('.call-card[data-call-record-id]').forEach(item => {
		item.onclick = () => openCallRecordDetail(item.dataset.callRecordId);
		item.onkeydown = event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openCallRecordDetail(item.dataset.callRecordId); } };
	  });
      threadEl.querySelectorAll('[data-open-media]').forEach(item => item.onclick = event => openAttachment(event, item.dataset.openMedia, item.dataset.fileName));
    }

    function renderThreadMessages(contact, items, target = $('thread'), options = {}) {
      if (target === $('thread')) updateContactHeader(contact);
      const threadEl = target;
	  threadEl.classList?.toggle?.('wecom-standalone-thread',
	    weComLayoutMode(contact) === 'standalone');
	  reconcileThreadMessages(contact, items, threadEl);
      bindThreadInteractions(threadEl);
      if (options.restoreViewer !== false && target === $('thread')) restoreWeComTimelineMessages(threadEl);
    }

    function weComMessageId(message) {
      const sourceId = String(message?.sourceId || '').trim();
      if (sourceId) return sourceId;
      return String(message?.id || '').replace(/^wecom:/, '').trim();
    }

    function restoreWeComTimelineMessages(root = $('thread')) {
      const viewer = weComTimelineViewer;
      if (!viewer || state.selectedPointId !== viewer.contactPointId) return;
      if (LOCAL_DEV_MODE) {
        mountLocalWeComTimelineMessages(viewer.detail, viewer.contactPointId, root);
        return;
      }
      mountWeComTimelineMessages(viewer.detail, viewer.viewerAuthToken, viewer.contactPointId,
        root, weComRenderGeneration)
        .catch(error => toast(`企业微信消息恢复失败：${error.message}`));
    }

	function callRecordStateText(value) {
	  return value === 'queued' ? '排队中' : value === 'processing' ? '转录中' : value === 'completed' ? '已完成' : value === 'failed' ? '失败' : value || '未知状态';
	}

	function callDirectionText(value) {
	  return value === 'outbound' ? '呼出' : value === 'inbound' ? '呼入' : '方向未指定';
	}

	function callPhoneLabel(phonePointId) {
	  const contact = selectedContact();
	  const point = (contact?.points || []).find(item => item.id === phonePointId && item.channel === 'phone' && item.type === 'phone' && item.id.startsWith('phone:'));
	  return point ? (point.label || point.value || '未指定号码') : '未指定号码';
	}

	function callDurationText(seconds) {
	  const value = Number(seconds || 0);
	  if (!Number.isFinite(value) || value <= 0) return '';
	  const minutes = Math.floor(value / 60);
	  const remainder = Math.floor(value % 60);
	  return minutes ? `${minutes}分${String(remainder).padStart(2, '0')}秒` : `${remainder}秒`;
	}

	function renderCallRecordCard(item) {
	  const call = item.payload || {};
	  const direction = call.direction === 'outbound' ? 'outbound' : 'inbound';
	  const details = [callPhoneLabel(call.phonePointId), callDurationText(call.durationSeconds)].filter(Boolean);
	  return `
		<div class="call-row ${esc(direction)}">
		  <div class="msg-avatar"><span class="call-phone-icon" aria-hidden="true"></span></div>
		  <div class="msg-stack">
			<article class="call-card ${call.id === state.selectedCallRecordId ? 'active' : ''}" data-call-record-id="${esc(call.id)}" tabindex="0" role="button" aria-label="查看${esc(callDirectionText(call.direction))}电话记录">
			  <div class="call-card-head"><div class="call-card-title"><span>${esc(callDirectionText(call.direction))}电话</span></div><span class="call-state ${esc(call.state || '')}">${esc(callRecordStateText(call.state))}</span></div>
			  <div class="call-card-body">${details.map(value => `<span>${esc(value)}</span>`).join('')}</div>
			</article>
			<div class="msg-meta-line"><span>电话记录</span><span>${esc(timeText(call.occurredAt || item.occurredAt))}</span></div>
		  </div>
		</div>`;
	}

    async function loadThread(id, keepScroll, options = {}) {
      const preparingWindow = id === weComPreparingContactPointId ? weComContactWindows.get(id) : null;
      const threadEl = options.target || preparingWindow?.container || $('thread');
      const oldBottom = threadEl.scrollHeight - threadEl.scrollTop - threadEl.clientHeight;
      const contact = state.contacts.find(c => c.id === id);
      const requestSeq = nextThreadLoadSeq(id);
	  const page = await viewerApi(threadPageUrl(id));
      if (state.selectedPointId !== id || currentThreadLoadSeq(id) !== requestSeq) return;
	  const items = page.items || [];
      const existing = state.threadPages[id];
	  const pageItemCount = Number(page.itemCount);
      const threadRevision = String(page.threadRevision || '');
	  if (!Number.isFinite(pageItemCount) || !threadRevision) throw new Error('时间线页合同缺少版本信息');
      const previousThreadRevision = existing ? existing.threadRevision || '' : '';
      const shouldKeepLoadedThread = keepScroll && existing && existing.hasLoadedInitial && previousThreadRevision === threadRevision;
      const merged = shouldKeepLoadedThread
		? mergeThreadMessages([...existing.items, ...items])
		: items;
      state.threadPages[id] = {
        items: merged,
        nextCursor: page.nextCursor || null,
        isLoadingOlder: false,
        hasLoadedInitial: true,
		pageItemCount,
        threadRevision: page.threadRevision,
      };
      if (shouldKeepLoadedThread && existing.nextCursor) state.threadPages[id].nextCursor = existing.nextCursor;
	  if (shouldKeepLoadedThread && !existing.nextCursor && pageItemCount <= merged.length) state.threadPages[id].nextCursor = null;
      limitThreadPageMessages(state.threadPages[id]);
      rememberThreadPageAccess(id);
      const messagesForRender = state.threadPages[id].items;
      const key = threadRenderKey(contact, messagesForRender);
      if (keepScroll && key === state.threadRenderKeyByContact[id]) {
        return;
      }
      state.threadRenderKeyByContact[id] = key;
      renderThreadMessages(contact, messagesForRender, threadEl, { restoreViewer:options.restoreViewer });
      if (keepScroll) requestAnimationFrame(() => threadEl.scrollTop = Math.max(0, threadEl.scrollHeight - threadEl.clientHeight - oldBottom));
      else if (threadEl === $('thread')) requestAnimationFrame(() => threadEl.scrollTop = threadEl.scrollHeight);
    }

    async function loadOlderThreadMessages() {
      const id = state.selectedPointId;
      if (!id) return;
      const page = state.threadPages[id];
      if (!page || !page.nextCursor || page.isLoadingOlder) return;
      const threadEl = $('thread');
      if (!threadEl || threadEl.scrollTop > 24) return;
      const requestSeq = currentThreadLoadSeq(id);
      page.isLoadingOlder = true;
      const oldScrollHeight = threadEl.scrollHeight;
      const oldScrollTop = threadEl.scrollTop;
      try {
		const older = await viewerApi(threadPageUrl(id, page.nextCursor));
        if (state.selectedPointId !== id || state.threadPages[id] !== page || currentThreadLoadSeq(id) !== requestSeq) return;
        if ((older.threadRevision || '') !== (page.threadRevision || '')) {
          await loadThread(id, false);
          return;
        }
        const contact = state.contacts.find(c => c.id === id);
        page.items = mergeThreadMessages([...(older.items || []), ...page.items]);
        page.nextCursor = older.nextCursor || null;
        limitThreadPageMessages(page);
        rememberThreadPageAccess(id);
        state.threadRenderKeyByContact[id] = threadRenderKey(contact, page.items);
        const staging = document.createElement('div');
        staging.className = 'wecom-contact-window';
        document.body.appendChild(staging);
        renderThreadMessages(contact, page.items, staging, { restoreViewer:false });
        const historyWeComMessageIds = weComHistoryViewerMessageIds(contact, older.items || [], staging);
        try {
          if (historyWeComMessageIds.length && wecomPoint(contact)) {
            await loadWeComViewer(contact, {
              automatic:true,
              messageIds:historyWeComMessageIds,
              root:staging,
              generation:weComRenderGeneration
            });
          }
          commitThreadRows(threadEl, Array.from(staging.children));
          bindThreadInteractions(threadEl);
        } finally {
          staging.remove();
        }
        requestAnimationFrame(() => {
          threadEl.scrollTop = threadEl.scrollHeight - oldScrollHeight + oldScrollTop;
        });
      } catch (err) {
        toast(`加载历史消息失败：${err.message}`);
      } finally {
        page.isLoadingOlder = false;
      }
    }

	function threadRenderKey(contact, items) {
      const header = contact ? `${contact.id || ''}:${contact.displayName || ''}:${pointSummary(contact)}` : '';
	  return header + '::' + items.map(item => {
		const m = item.payload || {};
		return [
		item.type || '',
		item.sortId || '',
		item.occurredAt || '',
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
		m.fileName || '',
		m.state || '',
		m.version || 0,
		m.durationSeconds || 0,
		m.phonePointId || ''
		].join('~');
	  }).join('|');
    }

    function mergeThreadMessages(messages) {
      const seen = new Set();
      const indexByKey = new Map();
      const merged = [];
	  messages.forEach(message => {
		const key = `${message.type || ''}:${message.sortId || ''}`;
        if (!key) { merged.push(message); return; }
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
	  clearCallDetailActivity();
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
      const attachmentHtml = (m.attachments || []).map(attachment => attachment.state === 'stored'
        ? `<button type="button" class="attachment-download" data-email-attachment-id="${esc(attachment.id)}">${esc(attachment.fileName)}（${formatBytes(attachment.sizeBytes)}）</button>`
        : `<div class="attachment-rejected">${esc(attachment.fileName)}：${esc(attachment.errorCode || '附件不可用')}</div>`).join('');
      const html = `
        <div class="kv"><div class="small">渠道</div><div>${esc(label(m.channel))}</div></div>
        <div class="kv"><div class="small">方向</div><div>${esc(m.direction)}</div></div>
        <div class="kv"><div class="small">时间</div><div>${esc(timeText(m.timestamp))}</div></div>
        <div class="kv"><div class="small">From</div><div>${esc(m.from || '')}</div></div>
        <div class="kv"><div class="small">To</div><div>${esc(m.to || '')}</div></div>
        ${m.status ? `<div class="kv"><div class="small">状态</div><div>${esc(m.status)}</div></div>` : ''}
        ${m.title ? `<div class="kv"><div class="small">主题</div><div>${esc(m.title)}</div></div>` : ''}
        <h3>内容</h3><div class="msg-text">${esc(m.bodyText || m.text || '')}</div>
        ${attachmentHtml ? `<h3>附件</h3><div class="attachment-list">${attachmentHtml}</div>` : ''}
        ${m.raw ? `<h3>Raw</h3><pre>${esc(m.raw)}</pre>` : ''}`;
      $('detail').innerHTML = `<div class="message-detail-panel message-detail-standalone" id="messageDetailPanel">${html}</div>`;
      document.querySelectorAll('[data-email-attachment-id]').forEach(button => {
        const attachment = (m.attachments || []).find(item => item.id === button.dataset.emailAttachmentId);
        if (attachment) button.onclick = () => openEmailAttachment(m.sourceId || m.id.replace(/^email:/, ''), attachment);
      });
    }

""").append("""

	function clearCallDetailActivity(clearSelection = true) {
	  state.callDetailGeneration += 1;
	  const audio = $('callAudio');
	  if (audio) {
		audio.pause();
		audio.removeAttribute('src');
		audio.load();
	  }
	  if (state.callDetailPollTimer) clearTimeout(state.callDetailPollTimer);
	  if (state.callAudioRenewTimer) clearInterval(state.callAudioRenewTimer);
	  state.callDetailPollTimer = null;
	  state.callDetailPollFailures = 0;
	  state.callAudioRenewTimer = null;
	  state.callAudioRecovered = false;
	  state.callAudioSessionReady = false;
	  state.callDetail = null;
	  if (clearSelection) state.selectedCallRecordId = '';
	}

	function isCurrentCallDetail(callRecordId, generation = state.callDetailGeneration) {
	  return state.selectedCallRecordId === callRecordId && state.callDetailGeneration === generation;
	}

	async function openCallRecordDetail(callRecordId) {
	  clearCallDetailActivity();
	  state.selectedMessageId = '';
	  state.selectedCallRecordId = callRecordId;
	  const generation = state.callDetailGeneration;
	  document.querySelectorAll('.msg').forEach(item => item.classList.remove('active'));
	  document.querySelectorAll('.call-card').forEach(item => item.classList.toggle('active', item.dataset.callRecordId === callRecordId));
	  $('detail').innerHTML = '<div class="empty">正在加载电话记录</div>';
	  try {
		const detail = await viewerApi('/api/v1/call-records/' + encodeURIComponent(callRecordId));
		if (!isCurrentCallDetail(callRecordId, generation)) return;
		state.callDetail = detail;
		renderCallRecordDetail(detail);
		scheduleCallDetailPoll(detail, generation);
		try {
		  const renewed = await renewCallAudioSession(callRecordId, generation);
		  if (renewed) startCallAudioRenewal(callRecordId, generation);
		} catch (audioError) {
		  const target = isCurrentCallDetail(callRecordId, generation) ? $('callAudioError') : null;
		  if (target) target.textContent = `播放授权失败：${audioError.message}`;
		}
	  } catch (err) {
		if (isCurrentCallDetail(callRecordId, generation)) $('detail').innerHTML = `<div class="empty">电话记录加载失败：${esc(err.message)}</div>`;
	  }
	}

	function renderCallRecordDetail(detail, preservedAudio = null) {
	  const transcription = detail.transcription || {};
	  const result = transcription.result || {};
	  const segments = Array.isArray(result.segments) ? result.segments : [];
	  const revisions = Array.isArray(detail.revisions) ? detail.revisions : [];
	  const current = revisions.find(item => item.id === detail.currentRevisionId);
	  const editableText = current?.text || result.originalText || '';
	  const audioPath = '/api/v1/call-records/' + encodeURIComponent(detail.id) + '/audio';
	  const audioSrc = state.callAudioSessionReady ? ` src="${esc(audioPath)}"` : '';
	  const segmentHtml = segments.length ? segments.map(segment => `
		<div class="transcript-segment"><div class="transcript-time">${esc(segmentTime(segment.startSeconds))}–${esc(segmentTime(segment.endSeconds))}</div><div>${esc(segment.text)}</div></div>`).join('') : '<div class="small">暂无分段</div>';
	  const revisionHtml = revisions.length ? revisions.map(revision => `
		<div class="transcript-segment"><div class="transcript-time">${esc(timeText(revision.editedAt))}</div><div>${esc(revision.text)}<div class="small">${esc(revision.editedBy || '')}</div></div></div>`).join('') : '<div class="small">暂无人工修订</div>';
	  const error = transcription.error || {};
	  $('detail').innerHTML = `
		<div class="call-detail" id="callDetailPanel">
		  <div class="call-detail-title"><span>${esc(callDirectionText(detail.direction))}电话</span><span class="call-state ${esc(transcription.state || '')}" id="callDetailState">${esc(callRecordStateText(transcription.state))}</span></div>
		  <div class="small call-detail-update-error" id="callDetailUpdateError"></div>
		  <div>
			<div class="kv"><div class="small">号码</div><div>${esc(callPhoneLabel(detail.phonePointId))}</div></div>
			<div class="kv"><div class="small">通话时间</div><div>${esc(timeText(detail.occurredAt))}</div></div>
			<div class="kv"><div class="small">时长</div><div>${esc(callDurationText(detail.audio?.durationSeconds) || '读取中')}</div></div>
			<div class="kv"><div class="small">文件</div><div>${esc(detail.audio?.originalFileName || '')}</div></div>
		  </div>
		  <div class="call-detail-section"><h3>录音</h3><audio id="callAudio" controls preload="metadata"${audioSrc}></audio><div class="call-audio-error" id="callAudioError"></div></div>
		  ${error.message ? `<div class="call-detail-section"><h3>失败原因</h3><div>${esc(error.message)}</div></div>` : ''}
		  <div class="call-detail-section"><h3>完整转录</h3><div class="call-transcript-text">${esc(result.originalText || '转录尚未完成')}</div></div>
		  <div class="call-detail-section"><h3>时间分段</h3>${segmentHtml}</div>
		  <div class="call-detail-section"><h3>修订记录</h3>${revisionHtml}</div>
		  <div class="call-revision-form"><label class="small" for="callRevisionText">修订转录</label><textarea id="callRevisionText">${esc(editableText)}</textarea></div>
		  <div class="call-detail-actions">
			${transcription.state === 'failed' && error.retryable ? '<button id="retryCallRecord" type="button">重新转录</button>' : ''}
			<button class="primary" id="reviseCallRecord" type="button" ${editableText ? '' : 'disabled'}>保存修订</button>
		  </div>
		</div>`;
	  const audioPlaceholder = $('callAudio');
	  if (preservedAudio && audioPlaceholder && audioPlaceholder !== preservedAudio) {
		audioPlaceholder.replaceWith(preservedAudio);
	  }
	  bindCallAudioRecovery(detail.id, state.callDetailGeneration);
	  const retry = $('retryCallRecord');
	  if (retry) retry.onclick = retryCallRecord;
	  const revise = $('reviseCallRecord');
	  if (revise) revise.onclick = reviseCallRecord;
	}

	function segmentTime(seconds) {
	  const value = Math.max(0, Number(seconds || 0));
	  const minutes = Math.floor(value / 60);
	  return `${String(minutes).padStart(2, '0')}:${String(Math.floor(value % 60)).padStart(2, '0')}`;
	}

	function scheduleCallDetailPoll(detail, generation = state.callDetailGeneration) {
	  if (state.callDetailPollTimer) clearTimeout(state.callDetailPollTimer);
	  state.callDetailPollTimer = null;
	  const status = detail?.transcription?.state;
	  if (!['queued', 'processing'].includes(status) || !isCurrentCallDetail(detail.id, generation)) return;
	  const delay = Math.min(CALL_DETAIL_POLL_MAX_MS,
		CALL_DETAIL_POLL_MS * (2 ** Math.max(0, state.callDetailPollFailures - 1)));
	  state.callDetailPollTimer = setTimeout(() => refreshCallRecordDetail(detail.id, generation), delay);
	}

	function updateCallRecordDetail(detail) {
	  const previous = state.callDetail;
	  const existingAudio = previous?.id === detail?.id
		&& $('detail').innerHTML.includes('id="callDetailPanel"') ? $('callAudio') : null;
	  state.callDetail = detail;
	  renderCallRecordDetail(detail, existingAudio);
	}

	async function refreshCallRecordDetail(callRecordId, generation = state.callDetailGeneration) {
	  const contactId = state.selectedPointId;
	  if (state.callDetailPollTimer) clearTimeout(state.callDetailPollTimer);
	  state.callDetailPollTimer = null;
	  try {
		const detail = await viewerApi('/api/v1/call-records/' + encodeURIComponent(callRecordId));
		if (!isCurrentCallDetail(callRecordId, generation) || state.selectedPointId !== contactId) return;
		state.callDetailPollFailures = 0;
		updateCallRecordDetail(detail);
		if (!['queued', 'processing'].includes(detail.transcription?.state) && contactId) await loadThread(contactId, true);
		scheduleCallDetailPoll(detail, generation);
	  } catch (err) {
		if (isCurrentCallDetail(callRecordId, generation) && state.selectedPointId === contactId) {
		  if (err.status === 401 || err.status === 403) {
			state.callDetailPollFailures = CALL_DETAIL_POLL_MAX_FAILURES;
			await handleViewerAuthFailure(err);
			return;
		  }
		  state.callDetailPollFailures = Math.min(
			CALL_DETAIL_POLL_MAX_FAILURES, state.callDetailPollFailures + 1);
		  const target = $('callDetailUpdateError');
		  if (state.callDetailPollFailures >= CALL_DETAIL_POLL_MAX_FAILURES) {
			if (target) target.textContent = `详情更新失败，已停止自动更新：${err.message}`;
			return;
		  }
		  if (target) target.textContent = `详情更新失败，稍后重试：${err.message}`;
		  scheduleCallDetailPoll(state.callDetail || { id:callRecordId, transcription:{ state:'queued' } }, generation);
		}
	  }
	}

	async function renewCallAudioSession(callRecordId, generation = state.callDetailGeneration) {
	  await viewerApi('/api/v1/call-records/' + encodeURIComponent(callRecordId) + '/audio-sessions', { method:'POST' });
	  if (!isCurrentCallDetail(callRecordId, generation)) return false;
	  state.callAudioSessionReady = true;
	  const audio = $('callAudio');
	  if (audio && !audio.getAttribute('src')) audio.setAttribute('src', '/api/v1/call-records/' + encodeURIComponent(callRecordId) + '/audio');
	  bindCallAudioRecovery(callRecordId, generation);
	  return true;
	}

	function startCallAudioRenewal(callRecordId, generation = state.callDetailGeneration) {
	  if (document.visibilityState === 'hidden' || !isCurrentCallDetail(callRecordId, generation)) return;
	  if (state.callAudioRenewTimer) clearInterval(state.callAudioRenewTimer);
	  state.callAudioRenewTimer = null;
	  state.callAudioRenewTimer = setInterval(() => renewCallAudioSession(callRecordId, generation).catch(err => {
		const target = isCurrentCallDetail(callRecordId, generation) ? $('callAudioError') : null;
		if (target) target.textContent = `播放授权续期失败：${err.message}`;
	  }), CALL_AUDIO_RENEW_MS);
	}

	function handleCallAudioVisibility() {
	  const callRecordId = state.selectedCallRecordId;
	  const generation = state.callDetailGeneration;
	  if (!callRecordId) return;
	  if (document.visibilityState === 'hidden') {
		if (state.callAudioRenewTimer) clearInterval(state.callAudioRenewTimer);
		state.callAudioRenewTimer = null;
		return;
	  }
	  renewCallAudioSession(callRecordId, generation)
		.then(renewed => { if (renewed) startCallAudioRenewal(callRecordId, generation); })
		.catch(err => {
		  const target = isCurrentCallDetail(callRecordId, generation) ? $('callAudioError') : null;
		  if (target) target.textContent = `播放授权续期失败：${err.message}`;
		});
	}

	function bindCallAudioRecovery(callRecordId, generation = state.callDetailGeneration) {
	  const audio = $('callAudio');
	  if (!audio) return;
	  audio.onerror = async () => {
		if (!isCurrentCallDetail(callRecordId, generation)) return;
		const target = $('callAudioError');
		if (state.callAudioRecovered) {
		  if (target) target.textContent = '录音加载失败，请稍后重试';
		  return;
		}
		state.callAudioRecovered = true;
		const currentTime = Number(audio.currentTime || 0);
		try {
		  const renewed = await renewCallAudioSession(callRecordId, generation);
		  if (!renewed || !isCurrentCallDetail(callRecordId, generation)) return;
		  audio.addEventListener('loadedmetadata', () => {
			if (!isCurrentCallDetail(callRecordId, generation)) return;
			audio.currentTime = Math.min(currentTime, Number.isFinite(audio.duration) ? audio.duration : currentTime);
			audio.play().catch(() => {});
		  }, { once:true });
		  audio.load();
		} catch (err) {
		  if (isCurrentCallDetail(callRecordId, generation) && target) target.textContent = `录音恢复失败：${err.message}`;
		}
	  };
	}

	async function retryCallRecord() {
	  const detail = state.callDetail;
	  if (!detail || detail.id !== state.selectedCallRecordId) return;
	  const callRecordId = detail.id;
	  const contactId = state.selectedPointId;
	  const button = $('retryCallRecord');
	  if (button) button.disabled = true;
	  try {
		const updated = await viewerApi('/api/v1/call-records/' + encodeURIComponent(callRecordId) + '/retry', {
		  method:'POST', headers:{ 'Content-Type':'application/json' }, body:JSON.stringify({ clientRequestId:requestId() })
		});
		if (state.selectedCallRecordId !== callRecordId || state.selectedPointId !== contactId) return;
		state.callDetail = updated;
		renderCallRecordDetail(updated);
		scheduleCallDetailPoll(updated);
		await loadThread(contactId, true);
		toast('已重新加入转录队列');
	  } catch (err) {
		if (state.selectedCallRecordId !== callRecordId || state.selectedPointId !== contactId) return;
		toast(`重新转录失败：${err.message}`);
		if (button) button.disabled = false;
	  }
	}

	async function reviseCallRecord() {
	  const detail = state.callDetail;
	  const text = $('callRevisionText')?.value.trim() || '';
	  if (!detail || detail.id !== state.selectedCallRecordId || !text) return;
	  const callRecordId = detail.id;
	  const contactId = state.selectedPointId;
	  const button = $('reviseCallRecord');
	  if (button) button.disabled = true;
	  try {
		const updated = await viewerApi('/api/v1/call-records/' + encodeURIComponent(callRecordId) + '/transcript', {
		  method:'PATCH', headers:{ 'Content-Type':'application/json' }, body:JSON.stringify({ text, expectedVersion:detail.version })
		});
		if (state.selectedCallRecordId !== callRecordId || state.selectedPointId !== contactId) return;
		state.callDetail = updated;
		renderCallRecordDetail(updated);
		await loadThread(contactId, true);
		toast('转录修订已保存');
	  } catch (err) {
		if (state.selectedCallRecordId !== callRecordId || state.selectedPointId !== contactId) return;
		toast(`保存修订失败：${err.message}`);
		if (button) button.disabled = false;
	  }
	}

    function toggleDetailPane() {
      state.detailCollapsed = !state.detailCollapsed;
      $('shell').classList.toggle('detail-collapsed', state.detailCollapsed);
    }

    function renderComposer() {
      const contact = selectedContact();
      if (!contact) {
        $('composer').innerHTML = '<div class="composer-tabs"><button class="active" data-channel="phoneRepository">电话仓库</button></div><div id="sendPanel"></div>';
        renderPhoneRepositoryPanel();
        return;
      }
	  const channels = [...contact.channels.filter(channel => ['email', 'chatapp', 'wecom'].includes(channel)), 'wecom'].filter((v,i,a)=>a.indexOf(v)===i);
	  if (![...channels, 'callRecord', 'phoneRepository'].includes(state.selectedChannel)) state.selectedChannel = channels[0];
      $('composer').innerHTML = `
		<div class="composer-tabs">${channels.map(ch => `<button class="${state.selectedChannel===ch?'active':''}" data-channel="${esc(ch)}">${esc(label(ch))}</button>`).join('')}<button class="${state.selectedChannel==='callRecord'?'active':''}" data-channel="callRecord">电话记录</button><button class="${state.selectedChannel==='phoneRepository'?'active':''}" data-channel="phoneRepository">电话仓库</button></div>
        <div id="sendPanel"></div>`;
      document.querySelectorAll('[data-channel]').forEach(btn => btn.onclick = () => { state.selectedChannel = btn.dataset.channel; renderComposer(); });
      if (state.selectedChannel === 'phoneRepository') renderPhoneRepositoryPanel();
      else renderSendPanel(contact);
    }

    async function renderPhoneRepositoryPanel() {
      const panel = $('sendPanel');
      if (!panel) return;
      panel.innerHTML = '<div class="phone-repository-panel"><div class="field"><label for="phoneRepositoryQuery">搜索电话、联系人或备注</label><input id="phoneRepositoryQuery" type="search" maxlength="512"><button id="phoneRepositorySearch" type="button">查询</button></div><div id="phoneRepositoryItems" class="call-repository-items">加载中</div></div>';
      const load = async () => {
        const items = $('phoneRepositoryItems');
        try {
          const page = await viewerApi('/api/v1/phone-repository?limit=50&query=' + encodeURIComponent($('phoneRepositoryQuery')?.value || ''));
          items.innerHTML = page.items?.length ? page.items.map(item => `<article class="call-card" data-call-record-id="${esc(item.id)}" tabindex="0"><div class="call-card-title">${esc(item.contactDisplayName)} · ${esc(item.phonePointId)}</div><div class="small">${esc(item.note || '')} · ${esc(item.transcriptionState)}</div></article>`).join('') : '<div class="empty">暂无电话记录</div>';
          items.querySelectorAll('[data-call-record-id]').forEach(item => item.onclick = () => openCallRecordDetail(item.dataset.callRecordId));
        } catch (error) { items.textContent = error.message || '电话仓库加载失败'; }
      };
      $('phoneRepositorySearch').onclick = load;
      await load();
    }

	function phonePoints(contact) {
	  return (contact?.points || []).filter(point => point.channel === 'phone' && point.type === 'phone' && point.id.startsWith('phone:'));
	}

	function localDateTimeValue(date = new Date()) {
	  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
	  return local.toISOString().slice(0, 16);
	}

	function renderCallRecordUploadPanel(contact) {
	  const points = phonePoints(contact);
	  const options = points.map(point => `<option value="${esc(point.id)}">${esc(point.label || point.value)}</option>`).join('');
	  const contactOptions = (state.contacts || []).map(item => `<option value="${esc(item.id)}">${esc(item.displayName || item.id)}</option>`).join('');
	  $('sendPanel').innerHTML = `
		<div class="call-upload-form">
		  <div class="field"><label for="callContactId">联系人</label><input id="callContactId" list="callContactOptions" value="${esc(contact?.id || state.selectedPointId || '')}" maxlength="512"><datalist id="callContactOptions">${contactOptions}</datalist></div>
		  <div class="field"><label for="callContactName">新联系人名称（新建时填写）</label><input id="callContactName" maxlength="512" value="${esc(contact?.displayName || '')}"></div>
		  <div class="call-upload-grid">
			<div class="field"><label for="callFile">MP3 录音</label><input id="callFile" type="file" accept="audio/mpeg,.mp3"></div>
			<div class="field"><label for="callDirection">方向</label><select id="callDirection"><option value="">选择方向</option><option value="inbound">呼入</option><option value="outbound">呼出</option></select></div>
			<div class="field"><label for="callOccurredAt">通话时间</label><input id="callOccurredAt" type="datetime-local" value="${esc(localDateTimeValue())}"></div>
			<div class="field"><label for="callPhonePoint">绑定号码</label><input id="callPhonePoint" list="callPhoneOptions" value="${esc(points[0]?.id || '')}" maxlength="64"><datalist id="callPhoneOptions">${options}</datalist>${points.length === 0 ? '<div class="small">未绑定电话，可手动填写</div>' : ''}</div>
		  </div>
		  <div class="field"><label for="callNote">备注（可选）</label><textarea id="callNote" maxlength="4000" rows="3"></textarea></div>
		  <div class="call-upload-actions"><div><progress class="call-upload-progress" id="callUploadProgress" max="100" value="0"></progress><div class="small" id="callUploadStatus">最大 100 MiB，最长 2 小时</div></div><button class="primary" id="uploadCallRecordButton" type="button" aria-label="上传电话录音">上传录音</button></div>
		</div>`;
	  $('uploadCallRecordButton').onclick = uploadCallRecord;
	}

	function uploadCallRecord() {
	  const contactId = $('callContactId')?.value?.trim() || state.selectedPointId;
	  const file = $('callFile')?.files?.[0];
	  const direction = $('callDirection')?.value || '';
	  const occurredAtValue = $('callOccurredAt')?.value || '';
	  const phonePointId = $('callPhonePoint')?.value?.trim() || '';
	  if (!contactId || !file) { toast('请选择 MP3 录音'); return; }
	  if (!/\\.mp3$/i.test(file.name || '')) { toast('只支持 MP3 录音'); return; }
	  if (Number(file.size || 0) > CALL_MAX_AUDIO_BYTES) { toast('MP3 文件不能超过 100 MiB'); return; }
	  if (!direction) { toast('请选择呼入或呼出'); return; }
	  if (!occurredAtValue || Number.isNaN(new Date(occurredAtValue).getTime())) { toast('请选择有效的通话时间'); return; }
	  if (!contactId) { toast('请选择或填写联系人'); return; }
	  if (!phonePointId) { toast('请选择或填写绑定号码'); return; }
	  let viewerAuthToken;
	  try { viewerAuthToken = currentWeComAuth().viewerAuthToken; }
	  catch (err) { toast(err.message); return; }
	  const knownContact = (state.contacts || []).find(item => item.id === contactId);
	  const knownPhone = knownContact && phonePoints(knownContact).some(point => point.id === phonePointId);
	  if (!knownContact || !knownPhone) {
		const button = $('uploadCallRecordButton');
		const status = $('callUploadStatus');
		button.disabled = true;
		status.textContent = '正在绑定联系人和号码';
		return viewerApi('/api/v1/phone-contacts', {
		  method:'POST', headers:{'Content-Type':'application/json'},
		  body:JSON.stringify({ contactId:knownContact?.id || '', contactName:$('callContactName')?.value?.trim() || contactId, phoneNumber:phonePointId.replace(/^phone:/, '') })
		}).then(binding => {
		  const projection = knownContact || { id:binding.contactId, displayName:binding.displayName, channels:['phone'], points:[] };
		  projection.id = binding.contactId;
		  projection.points = [...(projection.points || []).filter(point => point.id !== binding.phonePointId), { id:binding.phonePointId, channel:'phone', type:'phone', value:binding.phonePointId.slice(6), label:binding.phonePointId.slice(6) }];
		  if (!knownContact) state.contacts = [...(state.contacts || []), projection];
		  $('callContactId').value = binding.contactId;
		  $('callPhonePoint').value = binding.phonePointId;
		  return uploadCallRecord();
		}).catch(error => { button.disabled = false; status.textContent = '绑定失败'; toast(`联系人绑定失败：${error.message}`); return undefined; });
	  }

	  const form = new FormData();
	  form.append('direction', direction);
	  form.append('occurredAt', new Date(occurredAtValue).toISOString());
	  form.append('clientRequestId', requestId());
	  form.append('phonePointId', phonePointId);
	  const note = $('callNote')?.value || '';
	  if (note.trim()) form.append('note', note);
	  form.append('file', file);

	  const progress = $('callUploadProgress');
	  const status = $('callUploadStatus');
	  const button = $('uploadCallRecordButton');
	  const xhr = new XMLHttpRequest();
	  button.disabled = true;
	  status.textContent = '正在上传';
	  xhr.open('POST', '/api/v1/contacts/' + encodeURIComponent(contactId) + '/call-records');
	  xhr.setRequestHeader('X-WeCom-Viewer-Auth', viewerAuthToken);
	  xhr.upload.onprogress = event => {
		if (!event.lengthComputable) return;
		progress.value = Math.min(100, Math.round(event.loaded * 100 / event.total));
		status.textContent = `正在上传 ${progress.value}%`;
	  };
	  xhr.onload = async () => {
		button.disabled = false;
		let response = {};
		try { response = JSON.parse(xhr.responseText || '{}'); } catch (ignored) {}
		if (xhr.status < 200 || xhr.status >= 300) {
		  status.textContent = '上传失败';
		  toast(`电话录音上传失败：${response.message || response.code || xhr.statusText}`);
		  return;
		}
		progress.value = 100;
		status.textContent = callRecordStateText(response.state || 'queued');
		if (state.selectedPointId === contactId) await loadThread(contactId, true);
		toast('电话录音已加入转录队列');
	  };
	  xhr.onerror = () => { button.disabled = false; status.textContent = '上传失败'; toast('电话录音上传失败：网络不可用'); };
	  xhr.onabort = () => { button.disabled = false; status.textContent = '上传已取消'; };
	  xhr.send(form);
	  return xhr;
	}

    function wecomPoint(contact) {
      return sendPointForChannel(contact, 'wecom');
    }

    function currentWeComMessageIds(contactPointId, root = $('thread')) {
      if (!contactPointId || state.selectedPointId !== contactPointId) return [];
      const segments = Array.from(root.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]'))
        .map(host => (weComSegmentMessages.get(host.dataset.wecomSegmentId || '') || [])
          .map(weComMessageId)
          .filter(Boolean)
          .filter((id, index, all) => all.indexOf(id) === index))
        .filter(ids => ids.length);
      const selected = [];
      for (let index = segments.length - 1; index >= 0; index--) {
        const ids = segments[index];
        if (ids.length > WECOM_SEGMENT_MESSAGE_LIMIT) {
          return ids.slice(-WECOM_SEGMENT_MESSAGE_LIMIT);
        }
        if (selected.length + ids.length > WECOM_SEGMENT_MESSAGE_LIMIT) break;
        selected.unshift(...ids);
      }
      return selected;
    }

    function weComMessageIdsFromTimelineItems(items) {
      return (items || [])
        .filter(item => item?.type === 'message' && item?.payload?.channel === 'wecom')
        .map(item => weComMessageId(item.payload))
        .filter(Boolean)
        .filter((id, index, all) => all.indexOf(id) === index)
        .slice(-15);
    }

    function weComHistoryViewerMessageIds(contact, olderItems, root) {
      if (weComLayoutMode(contact) === 'standalone') {
        return currentWeComMessageIds(contact?.id || '', root);
      }
      return weComMessageIdsFromTimelineItems(olderItems);
    }

    function mergeWeComViewer(existing, next) {
      if (!existing || existing.contactPointId !== next.contactPointId) return next;
      const messages = new Map((existing.detail?.messages || []).map(message => [message.msgid, message]));
      (next.detail?.messages || []).forEach(message => {
        messages.delete(message.msgid);
        messages.set(message.msgid, message);
      });
      return {
        ...next,
        detail:{ ...next.detail, messages:Array.from(messages.values()).slice(-WECOM_ACTIVE_FRAME_LIMIT) },
        expiresAt:Math.max(existing.expiresAt || 0, next.expiresAt || 0)
      };
    }

    function weComViewerNeedsMessages(viewer, messageIds) {
      const loaded = new Set((viewer?.detail?.messages || []).map(message => message.msgid));
      return messageIds.some(messageId => !loaded.has(messageId));
    }

    function weComSegmentMessagesCovered(segmentId, loaded) {
      const messages = weComSegmentMessages.get(segmentId) || [];
      return messages.length > 0
        && messages.every(message => loaded.has(weComMessageId(message)));
    }

    function weComViewerRenderableHosts(viewer, root) {
      if (!viewer || !root?.querySelectorAll) return [];
      const loaded = new Set((viewer.detail?.messages || []).map(message => message.msgid));
      if (!loaded.size) return [];
      return Array.from(root.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]'))
        .filter(host => weComSegmentMessagesCovered(host.dataset.wecomSegmentId || '', loaded));
    }

    function weComViewerHasMountedSegments(viewer, root, contactPointId) {
      if (!viewer || !root?.querySelectorAll) return false;
      const hosts = weComViewerRenderableHosts(viewer, root);
      if (!hosts.length) return true;
      if (LOCAL_DEV_MODE) return hosts.every(host => !host.classList?.contains?.('pending'));
      return hosts.every(host => {
        const segmentId = host.dataset.wecomSegmentId || '';
        const entry = weComSegmentFrameRegistry.get(`${contactPointId}:${segmentId}`);
        return entry?.host === host && entry.status === 'mounted'
          && !host.classList?.contains?.('pending')
          && Boolean(host.querySelector?.('.wecom-segment-frame'));
      });
    }

    function deferWeComViewerMountRetry(contactPointId) {
      const previous = weComViewerMountRetryState.get(contactPointId);
      const attempt = Math.min(16, Number(previous?.attempt || 0) + 1);
      const delay = Math.min(WECOM_VIEWER_MOUNT_RETRY_MAX_MS,
        WECOM_VIEWER_MOUNT_RETRY_BASE_MS * (2 ** Math.max(0, attempt - 1)));
      weComViewerMountRetryState.set(contactPointId, { attempt, nextAt:Date.now() + delay });
    }

    function remountWeComViewer(viewer, root, contactPointId, generation) {
      if (!viewer || !root) return Promise.resolve([]);
      const existing = weComViewerMountPromises.get(contactPointId);
      if (existing?.generation === generation) return existing.promise;
      const task = Promise.resolve().then(async () => {
        if (LOCAL_DEV_MODE) {
          mountLocalWeComTimelineMessages(viewer.detail, contactPointId, root);
        } else {
          await mountWeComTimelineMessages(viewer.detail, viewer.viewerAuthToken,
            contactPointId, root, generation);
        }
        if (generation !== weComRenderGeneration || state.selectedPointId !== contactPointId) return [];
        if (weComViewerHasMountedSegments(viewer, root, contactPointId)) {
          weComViewerMountRetryState.delete(contactPointId);
        } else {
          deferWeComViewerMountRetry(contactPointId);
        }
        return [];
      }).catch(error => {
        if (generation === weComRenderGeneration && state.selectedPointId === contactPointId) {
          deferWeComViewerMountRetry(contactPointId);
        }
        throw error;
      }).finally(() => {
        if (weComViewerMountPromises.get(contactPointId)?.promise === task) {
          weComViewerMountPromises.delete(contactPointId);
        }
      });
      weComViewerMountPromises.set(contactPointId, { generation, promise:task });
      return task;
    }

    function refreshWeComViewerIfDue() {
      pruneExpiredWeComContactWindows();
      const contact = selectedContact();
      if (!contact || !wecomPoint(contact)) return Promise.resolve();
      const preparingWindow = contact.id === weComPreparingContactPointId
        ? weComContactWindows.get(contact.id) : null;
      let viewer = preparingWindow?.viewer
        || (weComTimelineViewer?.contactPointId === contact.id ? weComTimelineViewer : null);
      if (viewer?.expiresAt && Date.now() >= viewer.expiresAt) {
        invalidateWeComContactWindow(contact.id);
        viewer = null;
      }
      const root = preparingWindow?.container || $('thread');
      const messageIds = currentWeComMessageIds(contact.id, root);
      const needsMessages = weComViewerNeedsMessages(viewer, messageIds);
      const needsMountedSegments = !weComViewerHasMountedSegments(viewer, root, contact.id);
      if (viewer && !needsMessages && needsMountedSegments) {
        const retry = weComViewerMountRetryState.get(contact.id);
        if (retry?.nextAt && Date.now() < retry.nextAt) return Promise.resolve();
        return remountWeComViewer(viewer, root, contact.id, weComRenderGeneration);
      }
      if (!needsMountedSegments) weComViewerMountRetryState.delete(contact.id);
      if (!needsMessages && !needsMountedSegments && viewer?.loadedAt
          && Date.now() - viewer.loadedAt < WECOM_VIEWER_AUTO_REFRESH_MS) {
        return Promise.resolve();
      }
      return loadWeComViewer(contact, {
        automatic:true,
        windowState:preparingWindow,
        generation:weComRenderGeneration,
        messageIds
      });
    }

    function weComViewerRefreshText(viewer) {
      if (!viewer?.loadedAt) return '正在等待自动刷新';
      return `最近刷新 ${new Date(viewer.loadedAt).toLocaleTimeString()}`;
    }

    function renderWeComViewerPanel(contact) {
      const point = wecomPoint(contact);
      const disabled = !point ? 'disabled' : '';
      const viewer = weComTimelineViewer?.contactPointId === contact?.id ? weComTimelineViewer : null;
      const loaded = Boolean(viewer);
      $('sendPanel').innerHTML = `
        <div class="wecom-viewer-panel">
          <div class="readonly-row">
            <div class="small">企业微信账号</div>
            <div class="readonly-value">${esc(point?.value || point?.id || '无可用账号')}</div>
          </div>
          <div class="wecom-viewer-actions">
            <button class="primary" id="openWeComViewer" type="button" ${disabled}>${loaded ? '刷新企业微信显示' : '加载企业微信消息'}</button>
          </div>
          <div class="wecom-viewer-status" id="wecomViewerStatus">${loaded ? weComViewerRefreshText(viewer) : '将自动加载企业微信消息'}</div>
        </div>`;
      const button = $('openWeComViewer');
      if (button) button.onclick = () => loadWeComViewer(contact);
    }

    async function loadWeComViewer(contact) {
      const options = { ...(arguments[1] || {}) };
      if (options.generation == null) options.generation = weComRenderGeneration;
      if (!options.root) options.root = options.windowState?.container || $('thread');
      if (!options.messageIds) options.messageIds = currentWeComMessageIds(contact?.id || '', options.root);
      const key = `${contact?.id || ''}:${options.generation}:${options.messageIds.join(',')}`;
      const existing = weComViewerLoadPromises.get(key);
      if (existing?.generation === options.generation) return existing.promise;
      const task = loadWeComViewerNow(contact, options);
      weComViewerLoadPromises.set(key, { generation:options.generation, promise:task });
      try {
        return await task;
      } finally {
        if (weComViewerLoadPromises.get(key)?.promise === task) weComViewerLoadPromises.delete(key);
      }
    }

    async function loadWeComViewerNow(contact, { automatic = false, windowState = null,
        generation = weComRenderGeneration, root = windowState?.container || $('thread'),
        messageIds = currentWeComMessageIds(contact?.id || '', root) } = {}) {
      const point = wecomPoint(contact);
      if (!point) {
        if (!automatic) toast('当前联系人没有企业微信账号');
        return;
      }
      const button = $('openWeComViewer');
      const status = $('wecomViewerStatus');
      if (!messageIds.length) {
        if (status && status.isConnected) status.textContent = '暂无企业微信消息';
        if (windowState) {
          windowState.viewer = null;
          windowState.ready = true;
        }
        return;
      }
      if (button) button.disabled = true;
      if (status) status.textContent = automatic ? '正在自动刷新企业微信消息' : '正在同步企业微信会话';
      try {
        const login = currentWeComAuth();
        const created = await postJson('/api/v1/wecom/conversation-view/sessions', {
          contactPointId: point.id,
          viewerAuthToken: login.viewerAuthToken,
          messageIds
        });
        const detail = await api('/api/v1/wecom/conversation-view/sessions/' + encodeURIComponent(created.viewerSessionId), {
          headers: { 'X-WeCom-Viewer-Auth': login.viewerAuthToken }
        });
        if (generation !== weComRenderGeneration || state.selectedPointId !== contact.id) return;
        const loadedAt = Date.now();
        const expiresAt = loadedAt + Math.max(1, Number(created.expiresIn || 300)) * 1000;
        detail.messages = (detail.messages || []).map(message => ({
          ...message,
          viewerSessionId:detail.viewerSessionId
        }));
        if (LOCAL_DEV_MODE) {
          const viewer = mergeWeComViewer(windowState?.viewer || weComTimelineViewer, {
            contactPointId:contact.id, detail, viewerAuthToken:'', loadedAt, expiresAt
          });
          if (windowState) {
            windowState.viewer = viewer;
            await prepareWeComContactWindow(contact.id, viewer.detail, '', windowState, generation);
          } else {
            if (!automatic) resetWeComContactFrames(contact.id, root);
            weComTimelineViewer = viewer;
            mountLocalWeComTimelineMessages(viewer.detail, contact.id, root);
          }
        } else {
          const currentUrl = window.location.href.split('#')[0];
          const config = await api('/api/v1/wecom/js-sdk-config?url=' + encodeURIComponent(currentUrl), {
            headers: { 'X-WeCom-Viewer-Auth': login.viewerAuthToken }
          });
          await ensureWeComViewerSdk(config);
          if (generation !== weComRenderGeneration || state.selectedPointId !== contact.id) return;
          const viewer = mergeWeComViewer(windowState?.viewer || weComTimelineViewer, {
            contactPointId:contact.id,
            detail,
            viewerAuthToken:login.viewerAuthToken,
            loadedAt,
            expiresAt
          });
          if (windowState) {
            windowState.viewer = viewer;
            await prepareWeComContactWindow(contact.id, viewer.detail, login.viewerAuthToken, windowState, generation);
          } else {
            if (!automatic) resetWeComContactFrames(contact.id, root);
            weComTimelineViewer = viewer;
            await mountWeComTimelineMessages(viewer.detail, login.viewerAuthToken, contact.id, root, generation);
          }
        }
        if (status && status.isConnected) {
          const count = detail.messages?.length || 0;
          status.textContent = `${weComViewerRefreshText(windowState?.viewer || weComTimelineViewer)} · ${count} 条消息`;
        }
        if (button && button.isConnected) button.textContent = '刷新企业微信显示';
      } catch (err) {
        if (isWeComLoginExpired(err)
            || String(err?.message || '').includes('企业微信登录已过期')) {
          await returnToWeComLogin('企业微信登录已失效，请重新扫码');
          return;
        }
        if (status && status.isConnected) status.textContent = `企业微信消息加载失败：${err.message}`;
        if (windowState && generation === weComRenderGeneration) {
          setWeComViewerLoadFailureStatuses(windowState.container, contact.id);
          windowState.ready = true;
        } else if (root) {
          setWeComViewerLoadFailureStatuses(root, contact.id);
        }
        if (!automatic) {
          if (weComTimelineViewer?.contactPointId === contact.id) weComTimelineViewer = null;
          toast(`企业微信会话加载失败：${err.message}`);
        }
      } finally {
        if (button && button.isConnected) button.disabled = false;
      }
    }

    async function ensureWeComViewerSdk(config) {
      await loadWeComSdk();
      await loadWeComJwxwork();
      ww.register({
        corpId: config.corpId,
        agentId: config.agentId,
        jsApiList: config.jsApiList || ['wwapp.invokeJsApiByCallInfo'],
        async getConfigSignature() { return config.configSignature; },
        async getAgentConfigSignature() { return config.agentConfigSignature; }
      });
      await ww.initOpenData();
    }

    function loadWeComSdk() {
      if (window.ww) return Promise.resolve(window.ww);
      if (weComSdkLoadPromise) return weComSdkLoadPromise;
      weComSdkLoadPromise = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        let settled = false;
        let timer;
        const fail = message => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          script.onload = null;
          script.onerror = null;
          script.remove();
          reject(new Error(message));
        };
        timer = setTimeout(() => fail('企业微信 JS-SDK 加载超时'), 10000);
        script.src = WECOM_SDK_SRC;
        script.async = true;
        script.onload = () => {
          if (settled) return;
          if (!window.ww) {
            fail('企业微信 JS-SDK 未加载');
            return;
          }
          settled = true;
          clearTimeout(timer);
          resolve(window.ww);
        };
        script.onerror = () => fail('企业微信 JS-SDK 加载失败');
        document.head.appendChild(script);
      }).catch(error => {
        weComSdkLoadPromise = null;
        throw error;
      });
      return weComSdkLoadPromise;
    }

    function loadWeComJwxwork() {
      if (weComJwxworkLoadPromise) return weComJwxworkLoadPromise;
      weComJwxworkLoadPromise = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        let settled = false;
        let timer;
        const fail = message => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          script.onload = null;
          script.onerror = null;
          script.remove();
          reject(new Error(message));
        };
        timer = setTimeout(() => fail('企业微信会话组件脚本加载超时'), 10000);
        script.src = WECOM_JWXWORK_SRC;
        script.async = true;
        script.onload = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve(true);
        };
        script.onerror = () => fail('企业微信会话组件脚本加载失败');
        document.head.appendChild(script);
      }).catch(error => {
        weComJwxworkLoadPromise = null;
        throw error;
      });
      return weComJwxworkLoadPromise;
    }

    function openWeComModal({ modalUrl, modalSize }) {
      if (!modalUrl) throw new Error('企业微信预览地址无效');
      const previewUrl = new URL(modalUrl, window.location.href);
      if (previewUrl.protocol !== 'https:') throw new Error('企业微信预览地址必须使用 HTTPS');
      const modal = $('wecomOpenModal');
      const frame = $('wecomOpenFrame');
      const panel = modal.firstElementChild;
      panel.style.width = '';
      panel.style.height = '';
      if (modalSize?.width) panel.style.width = `${Math.min(Number(modalSize.width), 960)}px`;
      if (modalSize?.height) panel.style.height = `${Math.min(Number(modalSize.height), 720)}px`;
      frame.src = previewUrl.toString();
      modal.hidden = false;
    }

    function closeWeComModal() {
      $('wecomOpenFrame').src = 'about:blank';
      $('wecomOpenModal').hidden = true;
    }

	function isWeComLoginExpired(error) {
	  const detail = error?.detail || error || {};
	  const text = `${detail.errCode || ''} ${detail.errMsg || ''} ${detail.message || error?.message || ''}`;
	  return error?.status === 401 || error?.status === 403
		|| error?.code === 'WECOM_VIEWER_AUTH_EXPIRED'
		|| WECOM_LOGIN_EXPIRED_MARKERS.some(marker => text.includes(marker))
		|| text.includes('viewer auth token is expired or missing');
    }

    function weComPreviewHeight(modalSize) {
      const suggested = Number(modalSize?.height);
      let height = Number.isFinite(suggested) && suggested > 0
        ? suggested : WECOM_PREVIEW_DEFAULT_HEIGHT;
      height = Math.min(WECOM_PREVIEW_MAX_HEIGHT, Math.max(WECOM_PREVIEW_MIN_HEIGHT, height));
      if (window.matchMedia?.('(max-width: 640px)')?.matches) {
        const viewportLimit = Math.max(WECOM_PREVIEW_MIN_HEIGHT, Math.floor(window.innerHeight * 0.6));
        height = Math.min(height, viewportLimit);
      }
      return height;
    }

    function weComPreviewInViewport(entry) {
      const rect = entry.wrapper?.getBoundingClientRect?.();
      return Boolean(rect && rect.bottom > 0 && rect.top < window.innerHeight);
    }

    function collapseWeComInlinePreview(key) {
      const entry = weComExpandedPreviews.get(key);
      if (!entry) return;
      if (entry.iframe) {
        entry.iframe.onload = null;
        entry.iframe.onerror = null;
        entry.iframe.src = 'about:blank';
      }
      entry.wrapper?.remove?.();
      entry.host?.classList?.remove('expanded');
      weComExpandedPreviews.delete(key);
    }

    function clearWeComInlinePreviews(contactPointId) {
      Array.from(weComExpandedPreviews.entries())
        .filter(([, entry]) => !contactPointId || entry.contactPointId === contactPointId)
        .forEach(([key]) => collapseWeComInlinePreview(key));
    }

    function collapseWeComInlinePreviewForHost(host) {
      Array.from(weComExpandedPreviews.entries())
        .filter(([, entry]) => entry.host === host)
        .forEach(([key]) => collapseWeComInlinePreview(key));
    }

    function pruneDisconnectedWeComInlinePreviews() {
      Array.from(weComExpandedPreviews.entries())
        .filter(([, entry]) => !entry.host?.isConnected)
        .forEach(([key]) => collapseWeComInlinePreview(key));
    }

    function pruneDisconnectedWeComSegmentFrames() {
      Array.from(weComSegmentFrameRegistry.entries())
        .filter(([, entry]) => !entry.host?.isConnected)
        .forEach(([key, entry]) => {
          collapseWeComInlinePreviewForHost(entry.host);
          entry.instance?.destroy?.();
          weComSegmentFrameRegistry.delete(key);
        });
    }

    function trimWeComExpandedPreviews(contactPointId) {
      const entries = Array.from(weComExpandedPreviews.entries())
        .filter(([, entry]) => entry.contactPointId === contactPointId)
        .sort((left, right) => left[1].openedAt - right[1].openedAt);
      if (entries.length < WECOM_EXPANDED_PREVIEW_LIMIT) return;
      const candidate = entries.find(([, entry]) => !weComPreviewInViewport(entry)) || entries[0];
      if (candidate) collapseWeComInlinePreview(candidate[0]);
    }

    function openWeComInlinePreview(host, contactPointId, messageId, { modalUrl, modalSize } = {}) {
      if (!host || !contactPointId || !messageId || !modalUrl) return false;
      let previewUrl;
      try {
        previewUrl = new URL(modalUrl, window.location.href);
      } catch (error) {
        return false;
      }
      if (previewUrl.protocol !== 'https:') return false;
      const key = `${contactPointId}:${messageId}`;
      const existing = weComExpandedPreviews.get(key);
      if (existing?.host === host) return true;
      let wrapper = null;
      let iframe = null;
      try {
        wrapper = document.createElement('div');
        wrapper.className = 'wecom-message-preview';
        const toolbar = document.createElement('div');
        toolbar.className = 'wecom-message-preview-toolbar';
        const collapse = document.createElement('button');
        collapse.type = 'button';
        collapse.className = 'wecom-message-collapse';
        collapse.title = '收起企业微信消息';
        collapse.setAttribute('aria-label', '收起企业微信消息');
        const closeMark = document.createElement('span');
        closeMark.className = 'close-mark';
        collapse.appendChild(closeMark);
        collapse.onclick = event => {
          event.stopPropagation();
          collapseWeComInlinePreview(key);
        };
        toolbar.appendChild(collapse);
        const content = document.createElement('div');
        content.className = 'wecom-message-preview-content';
        content.style.height = `${weComPreviewHeight(modalSize)}px`;
        iframe = document.createElement('iframe');
        iframe.title = '企业微信会话详情';
        iframe.referrerPolicy = 'no-referrer-when-downgrade';
        iframe.hidden = true;
        iframe.onload = () => { iframe.hidden = false; };
        iframe.onerror = () => collapseWeComInlinePreview(key);
        iframe.src = previewUrl.toString();
        content.appendChild(iframe);
        wrapper.append(toolbar, content);
        host.appendChild(wrapper);
        host.classList.add('expanded');
        if (existing) collapseWeComInlinePreview(key);
        else trimWeComExpandedPreviews(contactPointId);
        weComExpandedPreviews.set(key, {
          host, wrapper, iframe, contactPointId, messageId, openedAt: ++weComExpandedPreviewSequence
        });
        return true;
      } catch (error) {
        if (iframe) iframe.src = 'about:blank';
        wrapper?.remove?.();
        host.classList.remove('expanded');
        if (weComExpandedPreviews.get(key)?.host === host) {
          weComExpandedPreviews.delete(key);
        }
        return false;
      }
    }

""").append("""

    function setWeComViewerLoadFailureStatuses(root, contactPointId) {
      root?.querySelectorAll?.('.wecom-segment-host[data-wecom-segment-id]').forEach(host => {
        const segmentId = host.dataset.wecomSegmentId || '';
        const existing = weComSegmentFrameRegistry.get(`${contactPointId}:${segmentId}`);
        if (existing?.host === host && existing.status === 'mounted') return;
        setWeComSegmentStatus(host, '消息加载失败', true);
      });
    }

    function setWeComSegmentStatus(host, message, failed = false, reveal = true) {
      collapseWeComInlinePreviewForHost(host);
      host.replaceChildren();
      if (reveal) host.classList.remove('pending');
      const frame = document.createElement('div');
      frame.className = 'wecom-segment-frame';
      const status = document.createElement('div');
      status.className = `wecom-segment-status${failed ? ' error' : ''}`;
      if (failed) {
        const warning = document.createElement('span');
        warning.className = 'wecom-message-warning';
        warning.textContent = '!';
        const text = document.createElement('span');
        text.textContent = message;
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'wecom-message-retry';
        retry.title = '重试加载';
        retry.setAttribute('aria-label', '重试加载企业微信消息');
        retry.textContent = '↻';
        retry.onclick = event => {
          event.stopPropagation();
          retryWeComSegment(host.dataset.wecomSegmentId || '');
        };
        status.append(warning, text, retry);
      } else {
        status.textContent = message;
      }
      frame.appendChild(status);
      host.appendChild(frame);
    }

    function setWeComTitleLoading(loading) {
      const title = $('threadTitleText');
      if (!title) return;
      title.querySelector?.('.wecom-contact-spinner')?.remove();
      if (!loading) return;
      const spinner = document.createElement('span');
      spinner.className = 'wecom-contact-spinner';
      spinner.title = '正在准备企业微信消息';
      spinner.setAttribute('aria-label', '正在准备企业微信消息');
      title.appendChild(spinner);
    }

    function weComSegmentFrameHeight(host, messageCount) {
      const naturalHeight = Math.max(36, Number(messageCount || 1) * 36);
      if (host?.dataset?.wecomLayout !== 'standalone') return naturalHeight;
      const viewportHeight = Number($('thread')?.clientHeight || 0);
      return viewportHeight > 0 ? Math.max(36, viewportHeight) : Math.max(360, naturalHeight);
    }

    function resizeWeComStandaloneFrames(root = document) {
      root?.querySelectorAll?.('.wecom-segment-host[data-wecom-layout="standalone"]').forEach(host => {
        const frame = host.querySelector('.wecom-segment-frame');
        if (!frame) return;
        const messages = weComSegmentMessages.get(host.dataset.wecomSegmentId || '') || [];
        frame.style.height = `${weComSegmentFrameHeight(host, messages.length)}px`;
      });
    }

    function createWeComSegmentFrame(host, messageCount) {
      host.replaceChildren();
      const frame = document.createElement('div');
      frame.className = 'wecom-segment-frame';
      frame.style.height = `${weComSegmentFrameHeight(host, messageCount)}px`;
      host.appendChild(frame);
      return { frame };
    }

    function resetWeComContactFrames(contactPointId, root = $('thread')) {
      clearWeComInlinePreviews(contactPointId);
      Array.from(weComSegmentFrameRegistry.entries())
        .filter(([, entry]) => entry.contactPointId === contactPointId)
        .forEach(([key, entry]) => {
          entry.instance?.destroy?.();
          weComSegmentFrameRegistry.delete(key);
        });
      root?.querySelectorAll?.('.wecom-segment-host[data-wecom-segment-id]').forEach(host => {
        host.replaceChildren();
        host.classList.add('pending');
      });
    }

    function cancelWeComRenderWork() {
      weComRenderGeneration += 1;
      weComCreatingSegmentKeys.forEach(instance => instance?.cancel?.());
      weComCreatingSegmentKeys.clear();
      weComRenderQueue.splice(0).forEach(entry => entry.resolve({ status:'cancelled' }));
      return weComRenderGeneration;
    }

    function weComErrorField(error, key) {
      try {
        const nested = error?.detail && typeof error.detail === 'object' ? error.detail : null;
        const value = error?.[key] ?? nested?.[key];
        if (typeof value === 'string') return value.slice(0, 256);
        if (typeof value === 'number' && Number.isFinite(value)) return String(value);
        if (typeof value === 'boolean') return String(value);
      } catch (ignored) {
        // SDK 错误对象可能包含循环引用或异常 getter，诊断本身不能再次抛错。
      }
      return '';
    }

    function weComComponentDiagnostic(error, source, segmentId, messageCount) {
      const diagnostic = {
        source:String(source || 'unknown').slice(0, 32),
        segmentId:String(segmentId || '').slice(0, 128),
        messageCount:Math.max(0, Number(messageCount) || 0)
      };
      ['name', 'message', 'errCode', 'errMsg', 'code', 'type', 'stage', 'status'].forEach(key => {
        const value = weComErrorField(error, key);
        if (value) diagnostic[key] = value;
      });
      return diagnostic;
    }

    function logWeComComponentError(error, source, segmentId, messageCount) {
      const diagnostic = weComComponentDiagnostic(error, source, segmentId, messageCount);
      globalThis.console?.warn?.('[wecom-viewer/component-error]', diagnostic);
      return diagnostic;
    }

    function logWeComFrameStage(stage, segmentId, messageCount) {
      globalThis.console?.info?.('[wecom-viewer/frame]', {
        stage:String(stage || 'unknown').slice(0, 32),
        segmentId:String(segmentId || '').slice(0, 128),
        messageCount:Math.max(0, Number(messageCount) || 0)
      });
    }

    function handleWeComComponentError(error, detail, viewerAuthToken, host, settle, reveal = true,
        invalidateMounted = null, source = 'unknown', segmentId = '', messageCount = 0) {
      logWeComComponentError(error, source, segmentId, messageCount);
      if (isWeComLoginExpired(error)) {
        returnToWeComLogin('企业微信登录已失效，请重新扫码')
          .catch(loginError => setWeComLoginStatus(loginError.message, true));
        settle?.({ status:'expired', host });
        return;
      }
      const settledNow = settle?.({ status:'failed', host }) !== false;
      if (!settledNow && invalidateMounted?.() === false) return;
      if (settledNow && host?.isConnected) setWeComSegmentStatus(host, '消息加载失败', true, reveal);
      reportWeComViewerEvent('component_error', detail.viewerSessionId, viewerAuthToken);
    }

    function pumpWeComRenderQueue(concurrency = WECOM_RENDER_CONCURRENCY) {
      const limit = Math.min(WECOM_RENDER_CONCURRENCY, Math.max(1, concurrency));
      while (weComActiveRenderJobs < limit && weComRenderQueue.length) {
        const entry = weComRenderQueue.shift();
        if (entry.generation !== weComRenderGeneration) {
          entry.resolve({ status:'cancelled' });
          continue;
        }
        weComActiveRenderJobs++;
        Promise.resolve()
          .then(entry.job)
          .catch(error => ({ status:'failed', error }))
          .then(entry.resolve)
          .finally(() => {
            weComActiveRenderJobs--;
            pumpWeComRenderQueue();
          });
      }
    }

    async function runWeComRenderQueue(jobs, concurrency = WECOM_RENDER_CONCURRENCY,
        generation = weComRenderGeneration) {
      const stale = weComRenderQueue.filter(entry => entry.generation !== weComRenderGeneration);
      stale.forEach(entry => entry.resolve({ status:'cancelled' }));
      for (let index = weComRenderQueue.length - 1; index >= 0; index--) {
        if (weComRenderQueue[index].generation !== weComRenderGeneration) {
          weComRenderQueue.splice(index, 1);
        }
      }
      const tasks = jobs.map(job => new Promise(resolve => {
        if (weComRenderQueue.length >= WECOM_RENDER_QUEUE_LIMIT) {
          resolve({ status:'cancelled' });
          return;
        }
        weComRenderQueue.push({ job, resolve, generation });
        pumpWeComRenderQueue(concurrency);
      }));
      return Promise.all(tasks);
    }

    function trimWeComSegmentFrames(contactPointId, protectedIds = []) {
      const protectedKeys = new Set(protectedIds.map(id => `${contactPointId}:${id}`));
      while (Array.from(weComSegmentFrameRegistry.values())
        .filter(entry => entry.contactPointId === contactPointId).length > WECOM_ACTIVE_CONTACT_SEGMENT_LIMIT) {
        const candidate = Array.from(weComSegmentFrameRegistry.entries())
          .filter(([key, entry]) => entry.contactPointId === contactPointId && !protectedKeys.has(key))
          .sort((left, right) => left[1].lastUsed - right[1].lastUsed)[0];
        if (!candidate) break;
        const [key, entry] = candidate;
        collapseWeComInlinePreviewForHost(entry.host);
        entry.instance?.destroy?.();
        entry.host?.replaceChildren?.();
        entry.host?.classList?.add?.('pending');
        weComSegmentFrameRegistry.delete(key);
      }
      while (weComSegmentFrameRegistry.size > WECOM_ACTIVE_FRAME_LIMIT) {
        const candidate = Array.from(weComSegmentFrameRegistry.entries())
          .filter(([key]) => !protectedKeys.has(key))
          .sort((left, right) => left[1].lastUsed - right[1].lastUsed)[0];
        if (!candidate) break;
        const [key, entry] = candidate;
        collapseWeComInlinePreviewForHost(entry.host);
        entry.instance?.destroy?.();
        if (entry.host?.isConnected && entry.contactPointId !== state.selectedPointId) {
          entry.host.replaceChildren();
          entry.host.classList.add('pending');
        }
        weComSegmentFrameRegistry.delete(key);
      }
    }

    function protectedWeComSegmentKeys() {
      const hosts = Array.from($('thread')?.querySelectorAll?.('.wecom-segment-host[data-wecom-segment-id]') || []).slice(-8);
      return new Set(hosts.map(host => `${state.selectedPointId}:${host.dataset.wecomSegmentId || ''}`));
    }

    function reserveWeComSegmentCapacity(contactPointId, registryKey) {
      const protectedKeys = protectedWeComSegmentKeys();
      protectedKeys.add(registryKey);
      const contactCreatingCount = () => Array.from(weComCreatingSegmentKeys.values())
        .filter(entry => entry?.contactPointId === contactPointId).length;
      while (Array.from(weComSegmentFrameRegistry.values())
        .filter(entry => entry.contactPointId === contactPointId).length
        + contactCreatingCount() >= WECOM_ACTIVE_CONTACT_SEGMENT_LIMIT) {
        const candidate = Array.from(weComSegmentFrameRegistry.entries())
          .filter(([key, entry]) => entry.contactPointId === contactPointId && !protectedKeys.has(key))
          .sort((left, right) => left[1].lastUsed - right[1].lastUsed)[0];
        if (!candidate) return false;
        const [key, entry] = candidate;
        collapseWeComInlinePreviewForHost(entry.host);
        entry.instance?.destroy?.();
        entry.host?.replaceChildren?.();
        entry.host?.classList?.add?.('pending');
        weComSegmentFrameRegistry.delete(key);
      }
      while (weComSegmentFrameRegistry.size + weComCreatingSegmentKeys.size >= WECOM_ACTIVE_FRAME_LIMIT) {
        const candidate = Array.from(weComSegmentFrameRegistry.entries())
          .filter(([key]) => !protectedKeys.has(key))
          .sort((left, right) => left[1].lastUsed - right[1].lastUsed)[0];
        if (!candidate) return false;
        const [key, entry] = candidate;
        collapseWeComInlinePreviewForHost(entry.host);
        entry.instance?.destroy?.();
        if (entry.host?.isConnected) {
          entry.host.replaceChildren();
          entry.host.classList.add('pending');
        }
        weComSegmentFrameRegistry.delete(key);
      }
      weComCreatingSegmentKeys.set(registryKey, { contactPointId });
      return true;
    }

    function mountWeComSegmentFrame(host, segmentId, references, detail, viewerAuthToken, contactPointId, generation,
        revealOnSettle = true) {
      const registryKey = `${contactPointId}:${segmentId}`;
      if (generation !== weComRenderGeneration) {
        return Promise.resolve({ status:'cancelled', host });
      }
      let existing = weComSegmentFrameRegistry.get(registryKey);
      if (existing?.host === host && existing.status === 'mounted'
          && host.querySelector?.('.wecom-segment-frame')) {
        existing.lastUsed = Date.now();
        host.classList.remove('pending');
        return Promise.resolve({ status:'mounted', host, reused:true });
      }
      if (existing?.host === host && existing.status === 'mounted') {
        collapseWeComInlinePreviewForHost(host);
        existing.instance?.destroy?.();
        weComSegmentFrameRegistry.delete(registryKey);
        existing = null;
      }
      if (!references?.length) {
        setWeComSegmentStatus(host, '该消息段不在本次展示窗口', true, revealOnSettle);
        return Promise.resolve({ status:'failed', host, error:new Error('企业微信消息段缺少展示引用') });
      }
      if (!reserveWeComSegmentCapacity(contactPointId, registryKey)) {
        setWeComSegmentStatus(host, '消息加载失败', true, revealOnSettle);
        return Promise.resolve({ status:'failed', host, error:new Error('企业微信消息组件已达到上限') });
      }
      host.classList.add('pending');
      return new Promise(resolve => {
        let settled = false;
        let instance = null;
        let factory;
        let frame;
        let activeMessageId = '';
        let timer = setTimeout(() => {
          if (generation !== weComRenderGeneration) {
            settle({ status:'cancelled', host });
            return;
          }
          if (host?.isConnected) {
            setWeComSegmentStatus(host, '消息加载超时', true, revealOnSettle);
          }
          logWeComComponentError(new Error('企业微信消息渲染超时'),
            'frame_timeout', segmentId, references.length);
          settle({ status:'failed', host, error:new Error('企业微信消息渲染超时') });
        }, 15000);
      const settle = result => {
          if (settled) return false;
          settled = true;
          clearTimeout(timer);
          weComCreatingSegmentKeys.delete(registryKey);
          if (result.status !== 'mounted') {
            instance?.destroy?.();
            instance = null;
          }
          if (generation !== weComRenderGeneration) {
            instance?.destroy?.();
            resolve({ status:'cancelled', host });
            return true;
          }
          if (result.status === 'mounted') {
            collapseWeComInlinePreviewForHost(existing?.host || host);
            if (existing?.instance && existing.instance !== instance) existing.instance.destroy?.();
            host.classList.remove('pending');
            weComSegmentFrameRegistry.set(registryKey, {
              host, instance, status:'mounted', lastUsed:Date.now(), contactPointId,
              segmentId, messageIds:references.map(item => item.msgid), activeMessageId
            });
          }
          resolve(result);
          return true;
        };
        const invalidateMounted = () => {
          const mounted = weComSegmentFrameRegistry.get(registryKey);
          if (mounted?.instance !== instance) return false;
          collapseWeComInlinePreviewForHost(mounted.host);
          instance?.destroy?.();
          weComSegmentFrameRegistry.delete(registryKey);
          if (mounted.host?.isConnected) {
            setWeComSegmentStatus(mounted.host, '消息加载失败', true, revealOnSettle);
          }
          return true;
        };
        const handleComponentError = (error, source) => handleWeComComponentError(
          error, detail, viewerAuthToken, host, settle, revealOnSettle, invalidateMounted,
          source, segmentId, references.length);
        try {
          logWeComFrameStage('create_start', segmentId, references.length);
          factory = ww.createOpenDataFrameFactory();
          frame = createWeComSegmentFrame(host, references.length).frame;
          instance = factory.createOpenDataFrame({
            el: frame,
            template: `
              <view wx:for="{{data.msgList}}" wx:key="msgid"
                class="wecom-segment-row {{item.direction}}" data-index="{{index}}"
                bindclick="handleSegmentMessageClick">
                <view class="wecom-segment-bubble">
                  <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}"
                    open-type="viewMessage" binderror="handleSegmentMessageError" />
                </view>
              </view>
            `,
            style: `
              .wecom-segment-row { box-sizing:border-box; display:flex; width:100%; min-height:36px; padding:3px 8px; }
              .wecom-segment-row.inbound { justify-content:flex-start; }
              .wecom-segment-row.outbound { justify-content:flex-end; }
              .wecom-segment-bubble { box-sizing:border-box; display:inline-block; max-width:78%; min-height:30px; padding:6px 9px; overflow:hidden; border:1px solid #d7dde7; border-radius:6px; background:#fff; }
              .wecom-segment-row.outbound .wecom-segment-bubble { border-color:#b7d4c6; background:#f4fbf7; }
            `,
            data: { msgList:references },
            methods: {
              handleSegmentMessageClick(event) {
                const index = Number(event?.currentTarget?.dataset?.index);
                if (!Number.isInteger(index) || !references[index]) return;
                activeMessageId = references[index].msgid;
                const entry = weComSegmentFrameRegistry.get(registryKey);
                if (entry?.instance === instance) entry.activeMessageId = activeMessageId;
              },
              handleSegmentMessageError(error) {
                if (generation !== weComRenderGeneration) {
                  settle({ status:'cancelled', host });
                  return;
                }
                handleComponentError(error, 'message_binderror');
              }
            },
            handleMounted() {
              logWeComFrameStage('mounted', segmentId, references.length);
              settle({ status:'mounted', host });
            },
            handleModal({ modalUrl, modalSize }) {
              if (!activeMessageId) return true;
              return !openWeComInlinePreview(host, contactPointId, activeMessageId, { modalUrl, modalSize });
            },
            error(error) {
              if (generation !== weComRenderGeneration) {
                settle({ status:'cancelled', host });
                return;
              }
              handleComponentError(error, 'frame_error');
            }
          });
          instance.contactPointId = contactPointId;
          weComCreatingSegmentKeys.set(registryKey, instance);
          instance.cancel = () => settle({ status:'cancelled', host });
        } catch (error) {
          handleComponentError(error, 'frame_create_error');
        }
      });
    }

    function weComReferencesForSegment(segmentId, referencesById) {
      return (weComSegmentMessages.get(segmentId) || [])
        .map(message => {
          const reference = referencesById.get(weComMessageId(message));
          return reference ? {
            ...reference,
            direction:message?.direction === 'outbound' ? 'outbound' : 'inbound'
          } : null;
        })
        .filter(Boolean);
    }

    async function prepareWeComContactWindow(contactPointId, detail, viewerAuthToken) {
      const windowState = arguments[3] || getOrCreateWeComContactWindow(contactPointId);
      const generation = arguments[4] ?? windowState.generation ?? weComRenderGeneration;
      if (LOCAL_DEV_MODE) {
        mountLocalWeComTimelineMessages(detail, contactPointId, windowState.container);
        windowState.ready = generation === weComRenderGeneration;
        return [];
      }
      const results = await mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId,
        windowState.container, generation, true);
      windowState.ready = generation === weComRenderGeneration
        && results.every(result => result.status !== 'cancelled');
      return results;
    }

    async function mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId,
        root = $('thread'), generation = ++weComRenderGeneration, initialOnly = false) {
      if (generation !== weComRenderGeneration) return [];
      const hosts = Array.from(root.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]'));
      const references = new Map((detail.messages || []).map(item => [item.msgid, item]));
      const renderable = hosts.filter(host =>
        weComSegmentMessagesCovered(host.dataset.wecomSegmentId || '', references));
      if (!renderable.length) return [];
      renderable.forEach(host => {
        const key = `${contactPointId}:${host.dataset.wecomSegmentId || ''}`;
        const existing = weComSegmentFrameRegistry.get(key);
        if (existing?.host !== host || existing.status !== 'mounted') host.classList.add('pending');
      });
      setWeComTitleLoading(true);
      const initialCount = Math.min(WECOM_VIEWPORT_COMMIT_MAX, renderable.length);
      const initialHosts = renderable.slice(renderable.length - initialCount);
      const remainingHosts = renderable.slice(0, renderable.length - initialCount).reverse();
      const job = (host, revealOnSettle) => () => {
        const segmentId = host.dataset.wecomSegmentId || '';
        return mountWeComSegmentFrame(host, segmentId,
          weComReferencesForSegment(segmentId, references), detail, viewerAuthToken, contactPointId,
          generation, revealOnSettle);
      };
      const initialResults = await runWeComRenderQueue(
        initialHosts.map(host => job(host, false)), WECOM_RENDER_CONCURRENCY, generation);
      if (generation !== weComRenderGeneration || state.selectedPointId !== contactPointId) return initialResults;
      initialResults.forEach(result => result.host?.classList.remove('pending'));
      if (root === $('thread')) setWeComTitleLoading(false);
      if (initialOnly) return initialResults;
      runWeComRenderQueue(remainingHosts.map(host => async () => {
        const result = await job(host, true)();
        if (generation === weComRenderGeneration && state.selectedPointId === contactPointId) {
          result.host?.classList.remove('pending');
        }
        return result;
      }), WECOM_RENDER_CONCURRENCY, generation).then(() => {
        if (generation === weComRenderGeneration) {
          trimWeComSegmentFrames(contactPointId, initialHosts.map(host => host.dataset.wecomSegmentId || ''));
        }
      });
      return initialResults;
    }

    async function retryWeComSegment(segmentId) {
      const viewer = weComTimelineViewer;
      if (!viewer || !segmentId || viewer.contactPointId !== state.selectedPointId) return;
      const host = Array.from(document.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]'))
        .find(item => item.dataset.wecomSegmentId === segmentId);
      const referencesById = new Map((viewer.detail.messages || []).map(item => [item.msgid, item]));
      const references = weComReferencesForSegment(segmentId, referencesById);
      if (!host || !weComSegmentMessagesCovered(segmentId, referencesById)) return;
      const key = `${viewer.contactPointId}:${segmentId}`;
      const stagingHost = document.createElement('div');
      stagingHost.className = 'wecom-contact-window';
      stagingHost.dataset.wecomSegmentId = segmentId;
      stagingHost.dataset.wecomLayout = host.dataset.wecomLayout || 'mixed';
      document.body.appendChild(stagingHost);
      setWeComTitleLoading(true);
      try {
        const [result] = await runWeComRenderQueue([
          () => mountWeComSegmentFrame(stagingHost, segmentId, references, viewer.detail,
            viewer.viewerAuthToken, viewer.contactPointId, weComRenderGeneration, false)
        ], WECOM_RENDER_CONCURRENCY, weComRenderGeneration);
        if (result?.status === 'mounted') {
          host.replaceChildren(...Array.from(stagingHost.childNodes));
          host.classList.remove('pending');
          const entry = weComSegmentFrameRegistry.get(key);
          if (entry) entry.host = host;
        }
        return result;
      } finally {
        stagingHost.remove();
        setWeComTitleLoading(false);
      }
    }

    function mountLocalWeComTimelineMessages(detail, contactPointId, root = $('thread')) {
      if (state.selectedPointId !== contactPointId) return;
      const references = new Map((detail.messages || []).map(item => [item.msgid, item]));
      root.querySelectorAll('.wecom-segment-host[data-wecom-segment-id]').forEach(host => {
        const segmentId = host.dataset.wecomSegmentId || '';
        const count = weComSegmentMessagesCovered(segmentId, references)
          ? (weComSegmentMessages.get(segmentId) || []).length : 0;
        setWeComSegmentStatus(host, count ? `本地企业微信样例消息 · ${count} 条` : '该消息段不在本次展示窗口');
      });
    }

    function reportWeComViewerEvent(eventType, viewerSessionId, viewerAuthToken) {
      api('/api/v1/wecom/conversation-view/events', {
        method:'POST',
        headers:{ 'Content-Type':'application/json', 'X-WeCom-Viewer-Auth':viewerAuthToken },
        body:JSON.stringify({ eventType, viewerSessionId })
      })
        .catch(() => toast('企业微信组件错误审计失败'));
    }

    function renderSendPanel(contact) {
      const panel = $('sendPanel');
      const point = sendPointForChannel(contact, state.selectedChannel);
	  if (state.selectedChannel === 'callRecord') {
		renderCallRecordUploadPanel(contact);
		return;
	  }
      if (state.selectedChannel === 'email') {
        panel.innerHTML = `
          <div class="composer-form email-form">
            <div class="composer-context">
              ${accountSelectHtml(contact, 'email', 'emailTo', '收件人')}
              <div class="field"><label>主题</label><input id="emailSubject"></div>
            </div>
            <div class="composer-editor"><textarea id="emailBody" rows="4" placeholder="请输入邮件正文"></textarea></div>
            <div class="email-attachments-field">
              <label class="file-drop" for="emailAttachments">选择附件（最多 16 个，合计 20 MiB）</label>
              <input class="sr-only" id="emailAttachments" type="file" multiple>
              <div id="emailAttachmentList" class="attachment-list"></div>
            </div>
            <div class="composer-toolbar">
              <div class="tool-cluster"></div>
              <button class="primary" id="sendEmail">发送邮件</button>
            </div>
          </div>`;
        bindAccountSelect(contact);
        $('emailAttachments').onchange = event => {
          state.emailAttachments = state.emailAttachments.concat(Array.from(event.target.files || []));
          renderEmailAttachmentList();
          event.target.value = '';
        };
        renderEmailAttachmentList();
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
      renderWeComViewerPanel(contact);
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
      const to = $('emailTo').value.trim();
      const subject = $('emailSubject').value.trim();
      if (!to) { toast('请选择收件人'); return; }
      if (!subject) { toast('请输入邮件主题'); return; }
      const totalBytes = state.emailAttachments.reduce((total, file) => total + file.size, 0);
      if (state.emailAttachments.length > 16) { toast('附件数量不能超过 16 个'); return; }
      if (totalBytes > 20971520) { toast('附件总大小不能超过 20 MiB'); return; }
      const button = $('sendEmail');
      button.disabled = true;
      try {
        const form = new FormData();
        form.append('to', to);
        form.append('subject', subject);
        form.append('body', $('emailBody').value);
        state.emailAttachments.forEach(file => form.append('file', file, file.name));
        await fetch('/api/send/email', { method:'POST', body:form }).then(async response => {
          const data = await response.json();
          if (!response.ok) throw Object.assign(new Error(data.message || data.code || response.statusText), { status:response.status, code:data.code });
          return data;
        });
        $('emailBody').value = '';
        state.emailAttachments = [];
        renderEmailAttachmentList();
        toast('邮件已发送');
        await refreshAll(false);
      } catch (err) {
        toast(`邮件发送失败：${err.message}`);
      } finally {
        button.disabled = false;
      }
    }

    function renderEmailAttachmentList() {
      const list = $('emailAttachmentList');
      if (!list) return;
      const total = state.emailAttachments.reduce((sum, file) => sum + file.size, 0);
      list.innerHTML = state.emailAttachments.map((file, index) =>
        `<div class="attachment-row"><span>${esc(file.name)}（${formatBytes(file.size)}）</span><button type="button" class="icon-button" data-remove-email-attachment="${index}" aria-label="移除附件">×</button></div>`
      ).join('') + `<div class="small">${state.emailAttachments.length}/16，${formatBytes(total)}/20 MiB</div>`;
      list.querySelectorAll('[data-remove-email-attachment]').forEach(button => {
        button.onclick = () => { state.emailAttachments.splice(Number(button.dataset.removeEmailAttachment), 1); renderEmailAttachmentList(); };
      });
    }

    function formatBytes(value) {
      const bytes = Number(value || 0);
      if (bytes < 1024) return `${bytes} B`;
      if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KiB`;
      return `${(bytes / 1048576).toFixed(1)} MiB`;
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
      if (state.eventSource) state.eventSource.close();
      try {
        state.eventSource = new EventSource('/events');
        state.eventSource.onmessage = () => refreshInBackground(true);
        state.eventSource.addEventListener('templates-changed', async () => {
          try {
            await refreshTemplates();
          } catch (error) {
            console.error('template refresh failed', error);
          }
        });
      } catch (error) {
        state.eventSource = null;
      }
    }

    function clearWeComRendererState() {
      cancelWeComRenderWork();
      clearWeComInlinePreviews();
      weComSegmentFrameRegistry.forEach(entry => entry.instance?.destroy?.());
      weComSegmentFrameRegistry.clear();
      weComViewerMountPromises.clear();
      weComViewerMountRetryState.clear();
      weComContactWindows.forEach(windowState => {
        windowState.viewer = null;
        windowState.container?.remove?.();
      });
      weComContactWindows.clear();
      weComTimelineViewer = null;
      weComCommittedContactPointId = '';
      weComPreparingContactPointId = '';
    }

    function handleWeComPageHide(event) {
      if (!event?.persisted) clearWeComRendererState();
    }

    async function enableNotifications() {
      if (!('Notification' in window)) { toast('浏览器不支持系统通知'); return; }
      const result = await Notification.requestPermission();
      toast(result === 'granted' ? '提醒已开启' : '提醒未开启');
    }

    const showWeComLoginError = error => {
      $('wwLoginPanel').innerHTML = '<div class="empty">登录配置不可用</div>';
      setWeComLoginStatus(error.message, true);
    };
    window.addEventListener?.('pagehide', handleWeComPageHide);
    window.addEventListener?.('resize', () => resizeWeComStandaloneFrames(), { passive:true });
    $('wecomLoginRetry').onclick = () => initWeComLogin().catch(showWeComLoginError);
    initWeComLogin().catch(showWeComLoginError);
  </script>
</body>
</html>
""").toString()
                .replace("__LOCAL_DEV_MODE__", Boolean.toString(localDevMode))
                .replace("__LOCAL_LOGIN_HIDDEN__", localDevMode ? " hidden" : "")
                .replace("__LOCAL_SHELL_HIDDEN__", localDevMode ? "" : " hidden");
    }

    private static class EventHub {
        private static final byte[] CONNECTED_EVENT = ": connected\n\n".getBytes(StandardCharsets.UTF_8);
        private static final byte[] HEARTBEAT_EVENT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
        private final List<EventClient> clients = new CopyOnWriteArrayList<>();
        private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "message-center-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });

        EventHub() {
            heartbeatExecutor.scheduleAtFixedRate(this::heartbeat, 25, 25, TimeUnit.SECONDS);
        }

        void connect(HttpExchange exchange) throws IOException {
            EventClient client = new EventClient(exchange);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            try {
                exchange.sendResponseHeaders(200, 0);
                clients.add(client);
                client.write(CONNECTED_EVENT);
            } catch (IOException ex) {
                removeClient(client);
                throw ex;
            }
        }

        void publish(UnifiedMessage message) {
            if (message == null) {
                return;
            }
            String payload = "data: " + GSON.toJson(message) + "\n\n";
            publishBytes(payload.getBytes(StandardCharsets.UTF_8));
        }

        void publishTemplatesChanged(int count) {
            String payload = "event: templates-changed\n"
                    + "data: " + SSE_GSON.toJson(Map.of("count", count)) + "\n\n";
            publishBytes(payload.getBytes(StandardCharsets.UTF_8));
        }

        void publish(CallRecordEvent event) {
            if (event == null) return;
            String payload = "data: " + GSON.toJson(Map.of(
                    "type", "callRecord",
                    "callRecordId", event.callRecordId(),
                    "contactAnchorPointId", event.contactAnchorPointId(),
                    "state", event.state(),
                    "version", event.version())) + "\n\n";
            publishBytes(payload.getBytes(StandardCharsets.UTF_8));
        }

        private void heartbeat() {
            publishBytes(HEARTBEAT_EVENT);
        }

        private void publishBytes(byte[] bytes) {
            for (EventClient client : clients) {
                try {
                    client.write(bytes);
                } catch (IOException ex) {
                    removeClient(client);
                }
            }
        }

        void close() {
            heartbeatExecutor.shutdownNow();
            for (EventClient client : clients) {
                removeClient(client);
            }
        }

        private void removeClient(EventClient client) {
            clients.remove(client);
            client.close();
        }

        private static class EventClient {
            private final HttpExchange exchange;
            private boolean closed;

            EventClient(HttpExchange exchange) {
                this.exchange = exchange;
            }

            synchronized void write(byte[] bytes) throws IOException {
                if (closed) {
                    throw new IOException("SSE client is closed");
                }
                try {
                    OutputStream response = exchange.getResponseBody();
                    response.write(bytes);
                    response.flush();
                } catch (IOException ex) {
                    closeLocked();
                    throw ex;
                }
            }

            synchronized void close() {
                closeLocked();
            }

            private void closeLocked() {
                if (closed) {
                    return;
                }
                closed = true;
                exchange.close();
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
