package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Local-only WeCom substitute. It never calls WeCom or reads production credentials. */
public final class LocalWeComDevelopmentService {
    private static final String LOCAL_USER_ID = "local-wecom-user";
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;

    private final Config config;
    private final Clock clock;
    private final Map<String, Long> attempts = new ConcurrentHashMap<>();
    private final Map<String, LocalToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, LocalSession> sessions = new ConcurrentHashMap<>();

    public LocalWeComDevelopmentService(Config config) throws IOException {
        this(config, Clock.systemUTC());
    }

    LocalWeComDevelopmentService(Config config, Clock clock) throws IOException {
        if (!config.localDevMode()) {
            throw new IllegalArgumentException("Local WeCom development mode is disabled");
        }
        this.config = config;
        this.clock = clock;
        seedTargetIfNeeded();
    }

    public WeComLoginAttemptService.LoginAttemptResponse createAttempt() throws IOException {
        cleanup();
        if (attempts.size() >= config.wecomLoginMaxPending()) {
            throw new WeComLoginAttemptService.PendingLimitException("Local login attempt capacity exceeded");
        }
        String state = randomToken();
        attempts.put(state, expiresAt());
        return new WeComLoginAttemptService.LoginAttemptResponse(
                "Local", "local-development", "http://localhost:" + config.webPort() + "/", state,
                config.wecomLoginAttemptTtlSeconds());
    }

    public WeComViewerService.LoginExchangeResponse exchange(String code, String state) {
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new IllegalArgumentException("Local login code is required");
        }
        if (state == null || state.isBlank() || state.length() > 128) {
            throw new SecurityException("Local login state is required");
        }
        Long expiry = attempts.remove(state);
        if (expiry == null || clock.instant().getEpochSecond() >= expiry) {
            throw new SecurityException("Local login state is expired, missing, or already used");
        }
        String token = randomToken();
        tokens.put(token, new LocalToken(LOCAL_USER_ID, expiresAt()));
        return new WeComViewerService.LoginExchangeResponse(
                LOCAL_USER_ID, token, config.wecomViewerSessionTtlSeconds());
    }

    public WeComChatDataSyncService.SyncResult sync(String viewerAuthToken) throws IOException {
        requireToken(viewerAuthToken);
        List<JsonObject> rows = loadSourceRows();
        writeTarget(rows);
        return new WeComChatDataSyncService.SyncResult(rows.size() > 0 ? 1 : 0, rows.size(), 0);
    }

    public WeComViewerService.ViewerSessionResponse createSession(String contactPointId,
                                                                    String viewerAuthToken)
            throws IOException {
        requireToken(viewerAuthToken);
        if (contactPointId == null || !contactPointId.startsWith("wecom:")) {
            throw new IllegalArgumentException("WeCom contactPointId is required");
        }
        List<WeComViewerService.ViewerMessage> messages = messagesFor(contactPointId);
        if (messages.isEmpty()) {
            throw new SecurityException("Local WeCom conversation is not available");
        }
        sessions.entrySet().removeIf(entry -> entry.getValue().viewerAuthToken().equals(viewerAuthToken));
        String sessionId = randomToken();
        sessions.put(sessionId, new LocalSession(sessionId, viewerAuthToken, contactPointId,
                expiresAt(), messages));
        return new WeComViewerService.ViewerSessionResponse(sessionId, config.wecomViewerSessionTtlSeconds());
    }

    public WeComViewerService.ViewerSessionDetail readSession(String sessionId, String viewerAuthToken) {
        requireToken(viewerAuthToken);
        LocalSession session = sessions.remove(sessionId);
        if (session == null || !session.viewerAuthToken().equals(viewerAuthToken)
                || clock.instant().getEpochSecond() >= session.expiresAtEpochSecond()) {
            throw new SecurityException("Local WeCom viewer session is expired or missing");
        }
        return new WeComViewerService.ViewerSessionDetail(session.id(), "local-corp", "local-agent",
                session.messages());
    }

    public String requireViewerActor(String viewerAuthToken) {
        return requireToken(viewerAuthToken).userId();
    }

    public void recordClientEvent(String eventType, String sessionId, String viewerAuthToken) {
        if (!"component_error".equals(eventType)) {
            throw new IllegalArgumentException("Local viewer event type is invalid");
        }
        requireToken(viewerAuthToken);
    }

    public Map<String, Object> jsSdkConfig() {
        return Map.of("localDevMode", true, "corpId", "local-corp", "agentId", "local-agent",
                "jsApiList", List.of());
    }

    private List<WeComViewerService.ViewerMessage> messagesFor(String contactPointId) throws IOException {
        String external = contactPointId.substring("wecom:".length());
        Map<String, LocalMessage> byId = new LinkedHashMap<>();
        for (JsonObject row : loadSourceRows()) {
            String rowExternal = text(row, "external_userid");
            String msgid = text(row, "msgid");
            String secret = firstNonBlank(text(row, "secret_key"), text(row, "secretKey"));
            if (!external.equals(rowExternal) || msgid.isBlank() || secret.isBlank()) continue;
            long sendTime = number(row, "send_time");
            LocalMessage candidate = new LocalMessage(msgid, secret, sendTime);
            LocalMessage previous = byId.get(msgid);
            if (previous == null || candidate.sendTime() >= previous.sendTime()) byId.put(msgid, candidate);
        }
        List<LocalMessage> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparingLong(LocalMessage::sendTime).thenComparing(LocalMessage::msgid));
        int from = Math.max(0, ordered.size() - config.wecomViewerMaxMessages());
        List<WeComViewerService.ViewerMessage> result = new ArrayList<>();
        for (int i = from; i < ordered.size(); i++) {
            LocalMessage item = ordered.get(i);
            result.add(new WeComViewerService.ViewerMessage(item.msgid(), item.secretKey()));
        }
        return List.copyOf(result);
    }

    private List<JsonObject> loadSourceRows() throws IOException {
        boolean fixture = "fixture".equals(config.localWeComDataSource());
        if (fixture) return fixtureRows();
        Path source = config.localWeComDataFile();
        if (!Files.exists(source)) return List.of();
        if (Files.size(source) > MAX_SOURCE_BYTES) throw new IOException("Local WeCom data file is too large");
        List<JsonObject> rows = new ArrayList<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (!text(row, "msgid").isBlank() && !text(row, "external_userid").isBlank()
                        && !firstNonBlank(text(row, "secret_key"), text(row, "secretKey")).isBlank()) {
                    rows.add(row);
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed local rows and keep valid fixtures available.
            }
        }
        return List.copyOf(rows);
    }

    private static List<JsonObject> fixtureRows() {
        return List.of(
                JsonParser.parseString("{\"msgid\":\"local-msg-001\",\"external_userid\":\"local-contact-001\",\"userid\":\"local-wecom-user\",\"send_time\":1735689600,\"msgtype\":\"text\",\"origin\":3,\"secret_key\":\"local-secret-001\",\"text\":{\"content\":\"本地企业微信样例消息\"}}").getAsJsonObject(),
                JsonParser.parseString("{\"msgid\":\"local-msg-002\",\"external_userid\":\"local-contact-001\",\"userid\":\"local-wecom-user\",\"send_time\":1735689660,\"msgtype\":\"text\",\"origin\":3,\"secret_key\":\"local-secret-002\",\"text\":{\"content\":\"用于验证会话展示流程\"}}").getAsJsonObject(),
                JsonParser.parseString("{\"msgid\":\"local-msg-003\",\"external_userid\":\"local-contact-002\",\"userid\":\"local-wecom-user\",\"send_time\":1735689720,\"msgtype\":\"text\",\"origin\":3,\"secret_key\":\"local-secret-003\",\"text\":{\"content\":\"第二个本地联系人\"}}").getAsJsonObject()
        );
    }

    private void seedTargetIfNeeded() throws IOException {
        Path target = config.wecomDataFile();
        if (Files.exists(target) && Files.size(target) > 0) return;
        writeTarget(loadSourceRows());
    }

    private void writeTarget(List<JsonObject> rows) throws IOException {
        Path target = config.wecomDataFile();
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Local WeCom data directory is invalid");
        Files.createDirectories(parent);
        StringBuilder output = new StringBuilder();
        for (JsonObject row : rows) output.append(row).append('\n');
        Files.writeString(target, output.toString(), StandardCharsets.UTF_8);
    }

    private LocalToken requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new SecurityException("Local viewer auth token is required");
        }
        LocalToken value = tokens.get(token);
        if (value == null || clock.instant().getEpochSecond() >= value.expiresAtEpochSecond()) {
            tokens.remove(token, value);
            throw new SecurityException("Local viewer auth token is expired or missing");
        }
        return value;
    }

    private void cleanup() {
        long now = clock.instant().getEpochSecond();
        attempts.entrySet().removeIf(entry -> now >= entry.getValue());
        tokens.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
        sessions.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private long expiresAt() { return clock.instant().getEpochSecond() + config.wecomViewerSessionTtlSeconds(); }
    private static String randomToken() { return UUID.randomUUID().toString().replace("-", ""); }
    private static long number(JsonObject object, String field) {
        try { return object.has(field) ? Math.max(0L, object.get(field).getAsLong()) : 0L; }
        catch (RuntimeException ignored) { return 0L; }
    }
    private static String text(JsonObject object, String field) {
        try { return object.has(field) && object.get(field).isJsonPrimitive() ? object.get(field).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }
    private static String firstNonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }

    private record LocalToken(String userId, long expiresAtEpochSecond) {}
    private record LocalSession(String id, String viewerAuthToken, String contactPointId,
                                long expiresAtEpochSecond, List<WeComViewerService.ViewerMessage> messages) {}
    private record LocalMessage(String msgid, String secretKey, long sendTime) {}
}
