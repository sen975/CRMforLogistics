package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-only WeCom substitute. It never calls WeCom or reads production
 * credentials; messages are seeded into the PostgreSQL chatdata table.
 */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnProperty(name = "app.local-dev-mode", havingValue = "true")
public class LocalWeComDevelopmentService {
    private static final String LOCAL_USER_ID = "local-wecom-user";
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ACTIVE_VIEWER_SESSIONS = 512;

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComCredentialProtector credentialProtector;
    private final WeComViewerReferenceLeaseRegistry referenceLeases;
    private final Map<String, Long> attempts = new ConcurrentHashMap<>();
    private final Map<String, LocalToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, LocalSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public LocalWeComDevelopmentService(AppConfig config, ObjectMapper objectMapper,
                                        WeComChatDataMessageMapper messageMapper,
                                        WeComCredentialProtector credentialProtector,
                                        WeComViewerReferenceLeaseRegistry referenceLeases) {
        this(config, objectMapper, messageMapper, credentialProtector, Clock.systemUTC(), referenceLeases);
    }

    LocalWeComDevelopmentService(AppConfig config, ObjectMapper objectMapper,
                                 WeComChatDataMessageMapper messageMapper,
                                 WeComCredentialProtector credentialProtector, Clock clock) {
        this(config, objectMapper, messageMapper, credentialProtector, clock,
                new WeComViewerReferenceLeaseRegistry(clock, MAX_ACTIVE_VIEWER_SESSIONS, 15));
    }

    private LocalWeComDevelopmentService(AppConfig config, ObjectMapper objectMapper,
                                         WeComChatDataMessageMapper messageMapper,
                                         WeComCredentialProtector credentialProtector, Clock clock,
                                         WeComViewerReferenceLeaseRegistry referenceLeases) {
        if (!config.localDevMode()) {
            throw new IllegalArgumentException("Local WeCom development mode is disabled");
        }
        this.config = config;
        this.objectMapper = objectMapper;
        this.messageMapper = messageMapper;
        this.credentialProtector = credentialProtector;
        this.clock = clock;
        this.referenceLeases = referenceLeases;
        seedTargetIfNeeded();
    }

    public WeComLoginAttemptService.LoginAttemptResponse createAttempt() {
        cleanup();
        if (attempts.size() >= config.wecomLoginMaxPending()) {
            throw new WeComLoginAttemptService.PendingLimitException("Local login attempt capacity exceeded");
        }
        String state = randomToken();
        attempts.put(state, loginAttemptExpiresAt());
        return new WeComLoginAttemptService.LoginAttemptResponse(
                "Local", "local-development", "local-agent", "http://localhost:8080/", state,
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
        storeToken(token, new LocalToken(LOCAL_USER_ID, viewerAuthExpiresAt()));
        return new WeComViewerService.LoginExchangeResponse(
                LOCAL_USER_ID, token, config.wecomViewerAuthTtlSeconds());
    }

    public WeComViewerService.LoginExchangeResponse issueViewerAuth(
            String wecomUserId, WeComLoginAttemptService.InstallationBinding binding) {
        if (wecomUserId == null || wecomUserId.isBlank() || wecomUserId.length() > 128) {
            throw new IllegalArgumentException("Local WeCom user id is required");
        }
        String token = randomToken();
        storeToken(token, new LocalToken(wecomUserId, viewerAuthExpiresAt()));
        return new WeComViewerService.LoginExchangeResponse(
                wecomUserId, token, config.wecomViewerAuthTtlSeconds());
    }

    public WeComChatDataSyncService.SyncResult sync(String viewerAuthToken) {
        requireToken(viewerAuthToken);
        List<JsonNode> rows = loadSourceRows();
        writeTarget(rows);
        return new WeComChatDataSyncService.SyncResult(rows.size() > 0 ? 1 : 0, rows.size(), 0);
    }

    public WeComViewerService.ViewerSessionResponse createSession(String contactPointId,
                                                                    String viewerAuthToken,
                                                                    List<String> requestedMessageIds) {
        requireToken(viewerAuthToken);
        if (contactPointId == null || !contactPointId.startsWith("wecom:")) {
            throw new IllegalArgumentException("WeCom contactPointId is required");
        }
        List<WeComViewerService.ViewerMessage> messages = messagesFor(contactPointId, requestedMessageIds);
        if (messages.isEmpty()) {
            throw new SecurityException("Local WeCom conversation is not available");
        }
        String sessionId = randomToken();
        long expiresAt = viewerSessionExpiresAt();
        storeSession(new LocalSession(sessionId, viewerAuthToken, contactPointId,
                expiresAt, messages));
        return new WeComViewerService.ViewerSessionResponse(sessionId, config.wecomViewerSessionTtlSeconds());
    }

    public WeComViewerService.ViewerSessionDetail readSession(String sessionId, String viewerAuthToken) {
        requireToken(viewerAuthToken);
        LocalSession session = sessions.get(sessionId);
        if (session == null || !session.viewerAuthToken().equals(viewerAuthToken)
                || clock.instant().getEpochSecond() >= session.expiresAtEpochSecond()) {
            throw new SecurityException("Local WeCom viewer session is expired or missing");
        }
        if (!sessions.remove(sessionId, session)) {
            throw new SecurityException("Local WeCom viewer session is expired or missing");
        }
        referenceLeases.release(sessionId);
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

    public Map<String, Object> jsSdkConfig(String rawUrl, String viewerAuthToken) {
        requireToken(viewerAuthToken);
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
            throw new IllegalArgumentException("Local WeCom JSAPI URL is required");
        }
        return jsSdkConfig();
    }

    private List<WeComViewerService.ViewerMessage> messagesFor(String contactPointId,
                                                                List<String> requestedMessageIds) {
        if (requestedMessageIds == null || requestedMessageIds.isEmpty()
                || requestedMessageIds.size() > config.wecomViewerMaxMessages()) {
            throw new IllegalArgumentException("Local WeCom messageIds must contain 1 to "
                    + config.wecomViewerMaxMessages() + " items");
        }
        Set<String> requested = new HashSet<>();
        for (String value : requestedMessageIds) {
            String msgid = value == null ? "" : value.trim();
            if (msgid.isBlank() || msgid.length() > 256 || !requested.add(msgid)) {
                throw new IllegalArgumentException("Local WeCom messageIds contains an invalid or duplicate item");
            }
        }
        String external = contactPointId.substring("wecom:".length());
        Map<String, LocalMessage> byId = new LinkedHashMap<>();
        for (JsonNode row : loadSourceRows()) {
            String rowExternal = text(row, "external_userid");
            String rowUser = text(row, "userid");
            String msgid = text(row, "msgid");
            String secret = firstNonBlank(text(row, "secret_key"), text(row, "secretKey"));
            if (!external.equals(rowExternal) || !LOCAL_USER_ID.equals(rowUser)
                    || !requested.contains(msgid) || secret.isBlank()) continue;
            long sendTime = number(row, "send_time");
            LocalMessage candidate = new LocalMessage(msgid, secret, sendTime);
            LocalMessage previous = byId.get(msgid);
            if (previous == null || candidate.sendTime() >= previous.sendTime()) byId.put(msgid, candidate);
        }
        List<WeComViewerService.ViewerMessage> result = new ArrayList<>();
        for (String msgid : requestedMessageIds) {
            LocalMessage item = byId.get(msgid);
            if (item == null) throw new SecurityException("Local WeCom message is not viewable");
            result.add(new WeComViewerService.ViewerMessage(item.msgid(), item.secretKey()));
        }
        return List.copyOf(result);
    }

    private List<JsonNode> loadSourceRows() {
        boolean fixture = "fixture".equals(config.localWeComDataSource());
        if (fixture) return fixtureRows();
        Path source = Path.of(config.localWeComDataFile());
        if (!Files.exists(source)) return List.of();
        try {
            if (Files.size(source) > MAX_SOURCE_BYTES) {
                throw new IOException("Local WeCom data file is too large");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<JsonNode> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode row = objectMapper.readTree(line);
                    if (!text(row, "msgid").isBlank() && !text(row, "external_userid").isBlank()
                            && !firstNonBlank(text(row, "secret_key"), text(row, "secretKey")).isBlank()) {
                        rows.add(row);
                    }
                } catch (RuntimeException | IOException ignored) {
                    // Ignore malformed local rows and keep valid fixtures available.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(rows);
    }

    private List<JsonNode> fixtureRows() {
        return List.of(
                parse("{\"msgid\":\"local-msg-001\",\"external_userid\":\"local-contact-001\",\"userid\":\"local-wecom-user\",\"send_time\":1735689600,\"msgtype\":\"text\",\"direction\":\"inbound\",\"secret_key\":\"local-secret-001\",\"text\":{\"content\":\"本地外部联系人发来的样例消息\"}}"),
                parse("{\"msgid\":\"local-msg-002\",\"external_userid\":\"local-contact-001\",\"userid\":\"local-wecom-user\",\"send_time\":1735689660,\"msgtype\":\"text\",\"direction\":\"outbound\",\"secret_key\":\"local-secret-002\",\"text\":{\"content\":\"本地员工发出的样例消息\"}}"),
                parse("{\"msgid\":\"local-msg-003\",\"external_userid\":\"local-contact-002\",\"userid\":\"local-wecom-user\",\"send_time\":1735689720,\"msgtype\":\"text\",\"direction\":\"inbound\",\"secret_key\":\"local-secret-003\",\"text\":{\"content\":\"第二个本地联系人\"}}")
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void seedTargetIfNeeded() {
        Long count = messageMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        if (count != null && count > 0) return;
        writeTarget(loadSourceRows());
    }

    private void writeTarget(List<JsonNode> rows) {
        for (JsonNode row : rows) {
            WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
            entity.setId(UUID.randomUUID());
            entity.setMsgid(text(row, "msgid"));
            entity.setSecretKey(credentialProtector.protectSecretKey(
                    firstNonBlank(text(row, "secret_key"), text(row, "secretKey"))));
            entity.setExternalUserid(text(row, "external_userid"));
            entity.setUserid(text(row, "userid"));
            entity.setSendTime(number(row, "send_time"));
            entity.setMsgtype(text(row, "msgtype"));
            entity.setDirection(text(row, "direction"));
            messageMapper.insertIgnore(entity);
        }
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
        for (Map.Entry<String, LocalToken> entry : tokens.entrySet()) {
            if (now >= entry.getValue().expiresAtEpochSecond() && tokens.remove(entry.getKey(), entry.getValue())) {
                removeSessionsForToken(entry.getKey());
            }
        }
        for (Map.Entry<String, LocalSession> entry : sessions.entrySet()) {
            if (now >= entry.getValue().expiresAtEpochSecond()
                    && sessions.remove(entry.getKey(), entry.getValue())) {
                referenceLeases.release(entry.getKey());
            }
        }
    }

    private void removeSessionsForToken(String viewerAuthToken) {
        for (Map.Entry<String, LocalSession> entry : sessions.entrySet()) {
            if (entry.getValue().viewerAuthToken().equals(viewerAuthToken)
                    && sessions.remove(entry.getKey(), entry.getValue())) {
                referenceLeases.release(entry.getKey());
            }
        }
    }

    private void enforceTokenLimit() {
        if (tokens.size() < MAX_ACTIVE_VIEWER_SESSIONS) return;
        tokens.entrySet().stream()
                .min(Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(LocalToken::expiresAtEpochSecond)))
                .ifPresent(entry -> {
                    if (tokens.remove(entry.getKey(), entry.getValue())) {
                        removeSessionsForToken(entry.getKey());
                    }
                });
    }

    private synchronized void storeToken(String token, LocalToken value) {
        cleanup();
        enforceTokenLimit();
        tokens.put(token, value);
    }

    private synchronized void storeSession(LocalSession session) {
        cleanup();
        removeSessionsForToken(session.viewerAuthToken());
        enforceSessionLimit();
        sessions.put(session.id(), session);
        try {
            referenceLeases.acquire(session.id(),
                    session.messages().stream().map(WeComViewerService.ViewerMessage::msgid).toList(),
                    session.expiresAtEpochSecond());
        } catch (RuntimeException exception) {
            sessions.remove(session.id(), session);
            throw exception;
        }
    }

    private void enforceSessionLimit() {
        if (sessions.size() < MAX_ACTIVE_VIEWER_SESSIONS) return;
        sessions.entrySet().stream()
                .min(Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(LocalSession::expiresAtEpochSecond)))
                .ifPresent(entry -> {
                    if (sessions.remove(entry.getKey(), entry.getValue())) {
                        referenceLeases.release(entry.getKey());
                    }
                });
    }

    private long loginAttemptExpiresAt() {
        return clock.instant().getEpochSecond() + config.wecomLoginAttemptTtlSeconds();
    }

    private long viewerAuthExpiresAt() {
        return clock.instant().getEpochSecond() + config.wecomViewerAuthTtlSeconds();
    }

    private long viewerSessionExpiresAt() {
        return clock.instant().getEpochSecond() + config.wecomViewerSessionTtlSeconds();
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long number(JsonNode object, String field) {
        try {
            return object.has(field) ? Math.max(0L, object.get(field).asLong()) : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String text(JsonNode object, String field) {
        try {
            return object.has(field) && object.get(field).isValueNode() ? object.get(field).asText() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record LocalToken(String userId, long expiresAtEpochSecond) {}
    private record LocalSession(String id, String viewerAuthToken, String contactPointId,
                                long expiresAtEpochSecond, List<WeComViewerService.ViewerMessage> messages) {}
    private record LocalMessage(String msgid, String secretKey, long sendTime) {}
}
