package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class WeComViewerService {
    private static final int MAX_ACTIVE_VIEWER_SESSIONS = 512;
    private static final int MAX_WECOM_HTTP_RESPONSE_BYTES = 1_048_576;
    private static final Duration WECOM_HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final Config config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComHttpGateway gateway;
    private final WeComAuthorizationStore authorizationStore;
    private final ViewerAuditSink auditTrail;
    private final ConcurrentMap<String, CachedTicket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerAuth> viewerAuthTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerSession> viewerSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewedSession> viewedSessionsByToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionRateWindow> viewerSessionRates = new ConcurrentHashMap<>();
    private final AtomicLong sessionOrder = new AtomicLong();

    public WeComViewerService(Config config, WeComAuthorizationStore authorizationStore,
                              WeComAccessTokenService accessTokens,
                              WeComAuthorizationGateway authorizationGateway,
                              ViewerAuditSink audit) {
        this(config, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""),
                new JdkWeComHttpGateway(config, accessTokens, authorizationGateway), authorizationStore, audit);
    }

    private WeComViewerService(Config config, Clock clock, NonceSource nonceSource,
                               WeComHttpGateway gateway, WeComAuthorizationStore authorizationStore,
                               ViewerAuditSink audit) {
        this.config = config;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.gateway = gateway;
        this.authorizationStore = authorizationStore;
        this.auditTrail = Objects.requireNonNull(audit, "audit");
    }

    static WeComViewerService forTests(Config config, Clock clock, NonceSource nonceSource,
                                       WeComHttpGateway gateway, WeComAuthorizationStore authorizationStore,
                                       ViewerAuditSink audit) {
        return new WeComViewerService(config, clock, nonceSource, gateway, authorizationStore, audit);
    }

    public JsSdkConfig jsSdkConfig(String rawUrl) throws Exception {
        try {
            requireBounded(config.wecomCorpId(), "WeCom corp id", 64);
            requireBounded(config.wecomAgentId(), "WeCom agent id", 32);
            String url = canonicalAllowedUrl(rawUrl);
            SignatureBundle configSignature = signatureBundle("corp", url);
            SignatureBundle agentSignature = signatureBundle("agent", url);
            JsSdkConfig result = new JsSdkConfig(config.wecomCorpId(), config.wecomAgentId(),
                    List.of("selectExternalContact", "shareAppMessage", "wwapp.invokeJsApiByCallInfo"),
                    configSignature, agentSignature);
            auditTrail.record("wecom.viewer.js_sdk_config", "success", "", "", "");
            return result;
        } catch (Exception exception) {
            recordFailure("wecom.viewer.js_sdk_config", "failed", "", "", "", exception);
            throw exception;
        }
    }

    public JsSdkConfig jsSdkConfig(String rawUrl, String viewerAuthToken) throws Exception {
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond());
        WeComAuthorizationStore.Installation installation = requireBoundInstallation(auth);
        String url = canonicalAllowedUrl(rawUrl);
        SignatureBundle configSignature = signatureBundle("corp", url, installation);
        SignatureBundle agentSignature = signatureBundle("agent", url, installation);
        auditTrail.record("wecom.viewer.js_sdk_config", "success", auth.wecomUserId(), "", "");
        return new JsSdkConfig(installation.authCorpId(), installation.agentId(),
                List.of("selectExternalContact", "shareAppMessage", "wwapp.invokeJsApiByCallInfo"),
                configSignature, agentSignature);
    }

    public LoginExchangeResponse exchangeLoginCode(String code) throws Exception {
        String userId = "";
        try {
            requireBounded(code, "WeCom login code", 512);
            userId = gateway.exchangeLoginCode(code);
            requireBounded(userId, "WeCom user id", 128);
            String token = UUID.randomUUID().toString().replace("-", "");
            long now = clock.instant().getEpochSecond();
            cleanupExpiredViewerAuth(now);
            enforceViewerAuthLimit();
            auditTrail.record("wecom.viewer.login_exchange", "success", userId, "", "");
            viewerAuthTokens.put(token, new ViewerAuth(token, userId,
                    now + config.wecomViewerAuthTtlSeconds(), sessionOrder.incrementAndGet(), "", 0L));
            return new LoginExchangeResponse(userId, token, config.wecomViewerAuthTtlSeconds());
        } catch (Exception exception) {
            recordFailure("wecom.viewer.login_exchange", "failed", userId, "", "", exception);
            throw exception;
        }
    }

    public LoginExchangeResponse exchangeLoginCode(String code,
                                                    WeComLoginAttemptService.InstallationBinding binding)
            throws Exception {
        String userId = "";
        try {
            if (authorizationStore == null || binding == null) {
                throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                        "企业微信授权安装存储不可用");
            }
            WeComAuthorizationStore.ResolvedInstallation resolved = authorizationStore.resolveActive(
                    binding.suiteId(), binding.authCorpId());
            if (!resolved.installation().installationId().equals(binding.installationId())
                    || resolved.installation().version() != binding.version()) {
                throw new WeComAuthorizationException("WECOM_INSTALLATION_CHANGED", 403,
                        "企业微信授权安装已变化，请重新扫码");
            }
            WeComAuthorizationGateway.LoginIdentity identity = gateway.exchangeLoginIdentity(code, resolved);
            if (!identity.corpId().equals(binding.authCorpId())) {
                throw new WeComAuthorizationException("WECOM_LOGIN_CORP_MISMATCH", 403,
                        "企业微信登录企业与当前授权企业不一致");
            }
            userId = identity.userId();
            requireBounded(userId, "WeCom user id", 128);
            String token = UUID.randomUUID().toString().replace("-", "");
            long now = clock.instant().getEpochSecond();
            cleanupExpiredViewerAuth(now);
            enforceViewerAuthLimit();
            auditTrail.record("wecom.viewer.login_exchange", "success", userId, "", "");
            viewerAuthTokens.put(token, new ViewerAuth(token, userId,
                    now + config.wecomViewerAuthTtlSeconds(), sessionOrder.incrementAndGet(),
                    binding.installationId(), binding.version()));
            return new LoginExchangeResponse(userId, token, config.wecomViewerAuthTtlSeconds());
        } catch (Exception exception) {
            recordFailure("wecom.viewer.login_exchange", "failed", userId, "", "", exception);
            throw exception;
        }
    }

    int activeViewerAuthTokenCount() {
        cleanupExpiredViewerAuth(clock.instant().getEpochSecond());
        return viewerAuthTokens.size();
    }

    /** Resolves a live viewer token to the actor used by downstream services. */
    public String requireViewerActor(String viewerAuthToken) throws Exception {
        return resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond()).wecomUserId();
    }

    public ViewerSyncContext viewerSyncContext(String viewerAuthToken) throws Exception {
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond());
        return new ViewerSyncContext(auth.wecomUserId(), resolveBoundInstallation(auth));
    }

    public ViewerSessionResponse createViewerSession(String contactPointId, String viewerAuthToken,
                                                     List<String> requestedMessageIds) throws Exception {
        String wecomUserId = "";
        String viewerSessionId = "";
        try {
            requireBounded(contactPointId, "WeCom contactPointId", 256);
            if (!contactPointId.startsWith("wecom:")) {
                throw new IllegalArgumentException("WeCom contactPointId is required");
            }
            long now = clock.instant().getEpochSecond();
            ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, now);
            wecomUserId = auth.wecomUserId();
            enforceViewerSessionRate(wecomUserId, now);
            cleanupExpiredSessions(now);
            enforceViewerSessionLimit();
            List<String> messageIds = validateRequestedMessageIds(requestedMessageIds);
            List<ViewerMessage> messages = readViewerMessages(contactPointId, wecomUserId, messageIds);
            if (messages.isEmpty()) {
                throw new SecurityException("WeCom conversation is not viewable by this session");
            }
            removeViewerSessionsForToken(viewerAuthToken);
            viewerSessionId = UUID.randomUUID().toString().replace("-", "");
            long expiresAt = now + config.wecomViewerSessionTtlSeconds();
            auditTrail.record("wecom.viewer.session_create", "success", wecomUserId,
                    contactPointId, viewerSessionId);
            viewerSessions.put(viewerSessionId, new ViewerSession(viewerSessionId, viewerAuthToken, contactPointId,
                    expiresAt, sessionOrder.incrementAndGet(), messages));
            return new ViewerSessionResponse(viewerSessionId, config.wecomViewerSessionTtlSeconds());
        } catch (Exception exception) {
            String result = exception instanceof RateLimitException ? "rate_limited"
                    : exception instanceof SecurityException ? "denied" : "failed";
            recordFailure("wecom.viewer.session_create", result, wecomUserId,
                    contactPointId, viewerSessionId, exception);
            throw exception;
        }
    }

    public ViewerSessionDetail viewerSession(String viewerSessionId, String viewerAuthToken) throws Exception {
        String wecomUserId = "";
        ViewerSession session = null;
        try {
            long now = clock.instant().getEpochSecond();
            requireBounded(viewerSessionId, "WeCom viewer session id", 64);
            ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, now);
            wecomUserId = auth.wecomUserId();
            cleanupExpiredSessions(now);
            session = viewerSessions.get(viewerSessionId);
            if (session == null) {
                throw new IllegalArgumentException("WeCom viewer session is expired or missing");
            }
            if (!session.viewerAuthToken().equals(viewerAuthToken)) {
                throw new SecurityException("WeCom viewer session belongs to another user");
            }
            if (!viewerSessions.remove(viewerSessionId, session)) {
                throw new IllegalArgumentException("WeCom viewer session is expired or missing");
            }
            try {
                auditTrail.record("wecom.viewer.session_read", "success", wecomUserId,
                        session.contactPointId(), viewerSessionId);
                removeViewedSessionsForToken(viewerAuthToken);
                enforceViewedSessionLimit();
                viewedSessionsByToken.put(viewerAuthToken, new ViewedSession(viewerSessionId,
                        session.expiresAtEpochSecond(), session.createdOrder()));
            } catch (Exception exception) {
                viewerSessions.putIfAbsent(viewerSessionId, session);
                throw exception;
            }
            WeComAuthorizationStore.Installation installation = auth.installationId().isBlank()
                    ? null : requireBoundInstallation(auth);
            return new ViewerSessionDetail(session.id(),
                    installation == null ? config.wecomCorpId() : installation.authCorpId(),
                    installation == null ? config.wecomAgentId() : installation.agentId(), session.messages());
        } catch (Exception exception) {
            String result = exception instanceof SecurityException ? "denied" : "failed";
            recordFailure("wecom.viewer.session_read", result, wecomUserId,
                    session == null ? "" : session.contactPointId(), viewerSessionId, exception);
            throw exception;
        }
    }

    public void recordClientEvent(String eventType, String viewerSessionId, String viewerAuthToken) throws Exception {
        if (!"component_error".equals(eventType)) {
            throw new IllegalArgumentException("WeCom viewer event type is invalid");
        }
        String wecomUserId = "";
        try {
            long now = clock.instant().getEpochSecond();
            requireBounded(viewerSessionId, "WeCom viewer session id", 64);
            wecomUserId = resolveViewerAuth(viewerAuthToken, now);
            cleanupExpiredSessions(now);
            ViewedSession viewed = viewedSessionsByToken.get(viewerAuthToken);
            if (viewed == null || !viewed.id().equals(viewerSessionId)) {
                throw new SecurityException("WeCom viewer event does not match the viewed session");
            }
            if (!viewedSessionsByToken.remove(viewerAuthToken, viewed)) {
                throw new SecurityException("WeCom viewer event does not match the viewed session");
            }
            try {
                auditTrail.record("wecom.viewer.component_error", "failed", wecomUserId, "", viewerSessionId);
            } catch (Exception exception) {
                viewedSessionsByToken.putIfAbsent(viewerAuthToken, viewed);
                throw exception;
            }
        } catch (Exception exception) {
            recordFailure("wecom.viewer.component_error", "failed", wecomUserId, "", viewerSessionId, exception);
            throw exception;
        }
    }

    public void recordAccessDenied(String contactPointId, String viewerAuthToken) throws Exception {
        String wecomUserId = "";
        try {
            wecomUserId = resolveViewerAuth(viewerAuthToken, clock.instant().getEpochSecond());
        } catch (SecurityException ignored) {
            // The denied audit event remains useful even when no valid viewer identity is available.
        }
        auditTrail.record("wecom.viewer.access_check", "denied", wecomUserId, contactPointId, "");
    }

    private void recordFailure(String action, String result, String wecomUserId,
                               String contactPointId, String viewerSessionId, Exception exception) {
        if ("wecom.viewer.login_exchange".equals(action)) {
            logLoginExchangeFailure(exception);
            try {
                if (exception instanceof WeComAuthorizationException authorization) {
                            auditTrail.recordDiagnostic(action, result, wecomUserId, contactPointId,
                            viewerSessionId, safeToken(authorization.code()),
                            authorization.upstreamErrcode(), authorization.upstreamPath(),
                            authorization.upstreamHttpStatus(), authorization.upstreamHint());
                } else {
                    auditTrail.recordDiagnostic(action, result, wecomUserId, contactPointId,
                            viewerSessionId, "WECOM_LOGIN_EXCHANGE_FAILED", null, null);
                }
            } catch (Exception auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            return;
        }
        try {
            auditTrail.record(action, result, wecomUserId, contactPointId, viewerSessionId);
        } catch (Exception auditFailure) {
            exception.addSuppressed(auditFailure);
        }
    }

    private static void logLoginExchangeFailure(Exception exception) {
        if (!(exception instanceof WeComAuthorizationException authorization)) {
            return;
        }
        StringBuilder event = new StringBuilder(
                "{\"event\":\"wecom.viewer.login_exchange\",\"status\":\"failed\",\"code\":\"")
                .append(safeToken(authorization.code())).append('"');
        if (authorization.upstreamErrcode() != null) {
            event.append(",\"upstreamErrcode\":").append(authorization.upstreamErrcode());
        }
        if (authorization.upstreamPath() != null) {
            event.append(",\"upstreamPath\":\"").append(authorization.upstreamPath()).append('"');
        }
        if (authorization.upstreamHttpStatus() != null) {
            event.append(",\"upstreamHttpStatus\":").append(authorization.upstreamHttpStatus());
        }
        if (authorization.upstreamHint() != null) {
            event.append(",\"upstreamHint\":\"").append(authorization.upstreamHint()).append('"');
        }
        System.err.println(event.append('}'));
    }

    private static String safeToken(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,128}") ? value : "WECOM_LOGIN_EXCHANGE_FAILED";
    }

    private String resolveViewerAuth(String viewerAuthToken, long now) throws Exception {
        return resolveViewerAuthRecord(viewerAuthToken, now).wecomUserId();
    }

    private ViewerAuth resolveViewerAuthRecord(String viewerAuthToken, long now) throws Exception {
        requireBoundedSecurity(viewerAuthToken, "WeCom viewer auth token", 128);
        cleanupExpiredViewerAuth(now);
        ViewerAuth auth = viewerAuthTokens.get(viewerAuthToken);
        if (auth == null) {
            throw new SecurityException("WeCom viewer auth token is expired or missing");
        }
        if (!auth.installationId().isBlank()) requireBoundInstallation(auth);
        return auth;
    }

    private WeComAuthorizationStore.Installation requireBoundInstallation(ViewerAuth auth) throws Exception {
        return resolveBoundInstallation(auth).installation();
    }

    private WeComAuthorizationStore.ResolvedInstallation resolveBoundInstallation(ViewerAuth auth) throws Exception {
        if (authorizationStore == null) {
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装存储不可用");
        }
        WeComAuthorizationStore.ResolvedInstallation resolved = authorizationStore.resolveActive(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId());
        WeComAuthorizationStore.Installation installation = resolved.installation();
        if (!installation.installationId().equals(auth.installationId())
                || installation.version() != auth.version()) {
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新扫码");
        }
        return resolved;
    }

    SignatureBundle signatureBundle(String ticketType, String rawUrl) throws Exception {
        String ticket = ticket(ticketType);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = nonceSource.nextNonce();
        return new SignatureBundle(Long.toString(timestamp), nonce,
                makeSignature(ticket, canonicalAllowedUrl(rawUrl), timestamp, nonce));
    }

    private SignatureBundle signatureBundle(String ticketType, String rawUrl,
                                              WeComAuthorizationStore.Installation installation) throws Exception {
        String ticket = ticket(ticketType, installation);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = nonceSource.nextNonce();
        return new SignatureBundle(Long.toString(timestamp), nonce,
                makeSignature(ticket, canonicalAllowedUrl(rawUrl), timestamp, nonce));
    }

    private String ticket(String ticketType, WeComAuthorizationStore.Installation installation) throws Exception {
        long now = clock.instant().getEpochSecond();
        String cacheKey = installation.installationId() + ":" + installation.version() + ":" + ticketType;
        CachedTicket cached = tickets.get(cacheKey);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.ticket();
        }
        WeComAuthorizationStore.ResolvedInstallation resolved = authorizationStore.resolveActive(
                installation.suiteId(), installation.authCorpId());
        TicketResponse response = "agent".equals(ticketType)
                ? gateway.fetchAgentJsapiTicket(resolved) : gateway.fetchCorpJsapiTicket(resolved);
        if (response.errcode() != 0 || response.ticket().isBlank()) {
            throw new IOException("Unable to fetch WeCom jsapi ticket");
        }
        tickets.put(cacheKey, new CachedTicket(response.ticket(), now + response.expiresIn()));
        return response.ticket();
    }

    private String ticket(String ticketType) throws Exception {
        long now = clock.instant().getEpochSecond();
        CachedTicket cached = tickets.get(ticketType);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.ticket();
        }
        TicketResponse response = "agent".equals(ticketType)
                ? gateway.fetchAgentJsapiTicket()
                : gateway.fetchCorpJsapiTicket();
        if (response.errcode() != 0 || response.ticket().isBlank()) {
            throw new IOException("Unable to fetch WeCom " + ticketType + " jsapi_ticket: " + response.errmsg());
        }
        tickets.put(ticketType, new CachedTicket(response.ticket(), now + response.expiresIn()));
        return response.ticket();
    }

    private List<String> validateRequestedMessageIds(List<String> requestedMessageIds) {
        if (requestedMessageIds == null || requestedMessageIds.isEmpty()
                || requestedMessageIds.size() > config.wecomViewerMaxMessages()) {
            throw new IllegalArgumentException("WeCom viewer messageIds must contain 1 to "
                    + config.wecomViewerMaxMessages() + " items");
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>(requestedMessageIds.size());
        for (String value : requestedMessageIds) {
            String msgid = value == null ? "" : value.trim();
            if (msgid.isBlank() || msgid.length() > 256 || !seen.add(msgid)) {
                throw new IllegalArgumentException("WeCom viewer messageIds contains an invalid or duplicate item");
            }
            result.add(msgid);
        }
        return List.copyOf(result);
    }

    private List<ViewerMessage> readViewerMessages(String contactPointId, String wecomUserId,
                                                    List<String> requestedMessageIds) throws IOException {
        java.nio.file.Path file = config.wecomDataFile();
        if (!java.nio.file.Files.exists(file)) {
            return List.of();
        }
        if (java.nio.file.Files.size(file) > config.mediaMaxBytes()) {
            throw new IOException("WeCom viewer data file is too large");
        }
        String expectedExternal = contactPointId.substring("wecom:".length());
        Set<String> requested = new HashSet<>(requestedMessageIds);
        Map<String, ViewerMessageCandidate> candidatesById = new LinkedHashMap<>();
        try (Stream<String> lines = java.nio.file.Files.lines(file, StandardCharsets.UTF_8)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    String external = JsonSupport.string(object, "external_userid");
                    if (!expectedExternal.equalsIgnoreCase(external)
                            || !wecomUserId.equals(viewerMessageOwner(object))) {
                        continue;
                    }
                    String msgid = JsonSupport.string(object, "msgid");
                    if (!requested.contains(msgid)) {
                        continue;
                    }
                    String secret = firstNonBlank(JsonSupport.string(object, "secret_key"),
                            JsonSupport.string(object, "secretKey"));
                    if (msgid.isBlank() || msgid.length() > 256
                            || secret.isBlank() || secret.length() > 1024) {
                        continue;
                    }
                    long sendTime = object.has("send_time") ? object.get("send_time").getAsLong() : 0L;
                    ViewerMessageCandidate candidate = new ViewerMessageCandidate(sendTime, msgid, secret);
                    ViewerMessageCandidate previous = candidatesById.get(msgid);
                    if (previous == null || candidate.sendTime() >= previous.sendTime()) {
                        candidatesById.put(msgid, candidate);
                    }
                } catch (RuntimeException ignored) {
                    // A malformed local demo row must not hide otherwise valid viewer references.
                }
            }
        }
        if (candidatesById.size() != requested.size()) {
            throw new SecurityException("WeCom conversation message is not viewable by this session");
        }
        List<ViewerMessage> result = new ArrayList<>(requestedMessageIds.size());
        for (String msgid : requestedMessageIds) {
            ViewerMessageCandidate candidate = candidatesById.get(msgid);
            result.add(new ViewerMessage(candidate.msgid(), candidate.secretKey()));
        }
        return List.copyOf(result);
    }

    private synchronized void enforceViewerSessionRate(String wecomUserId, long now) {
        viewerSessionRates.entrySet().removeIf(entry -> now - entry.getValue().windowStartedAt() >= 60);
        SessionRateWindow current = viewerSessionRates.get(wecomUserId);
        if (current == null || now - current.windowStartedAt() >= 60) {
            viewerSessionRates.put(wecomUserId, new SessionRateWindow(now, 1));
            return;
        }
        if (current.count() >= config.wecomViewerSessionRateLimit()) {
            throw new RateLimitException("WeCom viewer session rate limit exceeded");
        }
        viewerSessionRates.put(wecomUserId, new SessionRateWindow(current.windowStartedAt(), current.count() + 1));
    }

    private static String viewerMessageOwner(JsonObject object) {
        return firstNonBlank(JsonSupport.string(object, "wecom_userid"),
                JsonSupport.string(object, "wecomUserId"),
                JsonSupport.string(object, "userid"),
                JsonSupport.string(object, "UserId"));
    }

    private void cleanupExpiredViewerAuth(long now) {
        viewerAuthTokens.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private void cleanupExpiredSessions(long now) {
        viewerSessions.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
        viewedSessionsByToken.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private void removeViewerSessionsForToken(String viewerAuthToken) {
        viewerSessions.entrySet().removeIf(entry -> entry.getValue().viewerAuthToken().equals(viewerAuthToken));
    }

    private void removeViewedSessionsForToken(String viewerAuthToken) {
        viewedSessionsByToken.remove(viewerAuthToken);
    }

    private void enforceViewerAuthLimit() {
        if (viewerAuthTokens.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewerAuthTokens.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(java.util.Map.Entry::getKey)
                .ifPresent(viewerAuthTokens::remove);
    }

    private void enforceViewerSessionLimit() {
        if (viewerSessions.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewerSessions.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(java.util.Map.Entry::getKey)
                .ifPresent(viewerSessions::remove);
    }

    private void enforceViewedSessionLimit() {
        if (viewedSessionsByToken.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewedSessionsByToken.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(java.util.Map.Entry::getKey)
                .ifPresent(viewedSessionsByToken::remove);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    private static void requireBoundedSecurity(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new SecurityException(name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    private String canonicalAllowedUrl(String rawUrl) {
        requireBounded(rawUrl, "WeCom JSAPI URL", 2048);
        URI uri = URI.create(rawUrl == null ? "" : rawUrl);
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        int port = uri.getPort();
        String origin = scheme + "://" + host + (port >= 0 ? ":" + port : "");
        if (scheme.isBlank() || host.isBlank() || !config.wecomAllowedJsapiOrigins().contains(origin)) {
            throw new IllegalArgumentException("WeCom JSAPI URL origin is not allowed");
        }
        URI noFragment = URI.create(origin + Objects.toString(uri.getRawPath(), "")
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        return noFragment.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public static String makeSignature(String ticket, String url, long timestamp, String nonceStr) {
        String payload = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    public record SignatureBundle(String timestamp, String nonceStr, String signature) {}
    public record JsSdkConfig(String corpId, String agentId, List<String> jsApiList,
                              SignatureBundle configSignature, SignatureBundle agentConfigSignature) {}
    public record LoginExchangeResponse(String wecomUserId, String viewerAuthToken, int expiresIn) {}
    public record ViewerSessionResponse(String viewerSessionId, int expiresIn) {}
    public record ViewerSessionDetail(String viewerSessionId, String corpId, String agentId,
                                      List<ViewerMessage> messages) {}
    public record ViewerMessage(String msgid, String secretKey) {}
    public record ViewerSyncContext(String wecomUserId,
                                    WeComAuthorizationStore.ResolvedInstallation installation) {}
    public record TicketResponse(int errcode, String errmsg, String ticket, int expiresIn) {}
    public static final class RateLimitException extends IllegalStateException {
        RateLimitException(String message) {
            super(message);
        }
    }
    private record CachedTicket(String ticket, long expiresAtEpochSecond) {}
    private record ViewerMessageCandidate(long sendTime, String msgid, String secretKey) {}
    private record SessionRateWindow(long windowStartedAt, int count) {}
    private record ViewerAuth(String token, String wecomUserId, long expiresAtEpochSecond, long createdOrder,
                              String installationId, long version) {}
    private record ViewerSession(String id, String viewerAuthToken, String contactPointId,
                                 long expiresAtEpochSecond, long createdOrder, List<ViewerMessage> messages) {}
    private record ViewedSession(String id, long expiresAtEpochSecond, long createdOrder) {}

    interface NonceSource {
        String nextNonce();
    }

    interface WeComHttpGateway {
        TicketResponse fetchCorpJsapiTicket() throws Exception;
        TicketResponse fetchAgentJsapiTicket() throws Exception;
        String exchangeLoginCode(String code) throws Exception;
        default String exchangeLoginCode(String code, WeComAuthorizationStore.ResolvedInstallation installation)
                throws Exception {
            return exchangeLoginCode(code);
        }
        default WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(
                String code, WeComAuthorizationStore.ResolvedInstallation installation) throws Exception {
            return new WeComAuthorizationGateway.LoginIdentity(installation.installation().authCorpId(),
                    exchangeLoginCode(code, installation));
        }
        default TicketResponse fetchCorpJsapiTicket(WeComAuthorizationStore.ResolvedInstallation installation)
                throws Exception { return fetchCorpJsapiTicket(); }
        default TicketResponse fetchAgentJsapiTicket(WeComAuthorizationStore.ResolvedInstallation installation)
                throws Exception { return fetchAgentJsapiTicket(); }
    }

    static class StaticGateway implements WeComHttpGateway {
        private final String corpTicket;
        private final String agentTicket;
        private final String userId;

        StaticGateway(String corpTicket, String agentTicket, String userId) {
            this.corpTicket = corpTicket;
            this.agentTicket = agentTicket;
            this.userId = userId;
        }

        @Override public TicketResponse fetchCorpJsapiTicket() {
            return new TicketResponse(0, "ok", corpTicket, 7200);
        }

        @Override public TicketResponse fetchAgentJsapiTicket() {
            return new TicketResponse(0, "ok", agentTicket, 7200);
        }

        @Override public String exchangeLoginCode(String code) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("WeCom login code is required");
            }
            return userId;
        }
    }

    static class JdkWeComHttpGateway implements WeComHttpGateway {
        private final Config config;
        private final WeComAccessTokenService installationAccessTokens;
        private final HttpClient client;
        private final URI apiBase;
        private final WeComAuthorizationGateway authorizationGateway;
        private String accessToken;
        private long accessTokenExpiresAt;

        JdkWeComHttpGateway(Config config) {
            this(config, null);
        }

        JdkWeComHttpGateway(Config config, WeComAccessTokenService installationAccessTokens) {
            this(config, installationAccessTokens, null);
        }

        JdkWeComHttpGateway(Config config, WeComAccessTokenService installationAccessTokens,
                           WeComAuthorizationGateway authorizationGateway) {
            this(config, installationAccessTokens,
                    HttpClient.newBuilder().connectTimeout(WECOM_HTTP_TIMEOUT).build(),
                    URI.create(config.value("WECOM_API_BASE_URL", "https://qyapi.weixin.qq.com")),
                    authorizationGateway);
        }

        JdkWeComHttpGateway(Config config, WeComAccessTokenService installationAccessTokens,
                           HttpClient client, URI apiBase) {
            this(config, installationAccessTokens, client, apiBase, null);
        }

        JdkWeComHttpGateway(Config config, WeComAccessTokenService installationAccessTokens,
                           HttpClient client, URI apiBase,
                           WeComAuthorizationGateway authorizationGateway) {
            this.config = config;
            this.installationAccessTokens = installationAccessTokens;
            this.client = Objects.requireNonNull(client, "client");
            this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
            this.authorizationGateway = authorizationGateway;
        }

        @Override public TicketResponse fetchCorpJsapiTicket() throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/get_jsapi_ticket?access_token="
                    + queryParam(accessToken()));
        }

        @Override public TicketResponse fetchAgentJsapiTicket() throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/ticket/get?access_token="
                    + queryParam(accessToken()) + "&type=agent_config");
        }

        @Override public TicketResponse fetchCorpJsapiTicket(
                WeComAuthorizationStore.ResolvedInstallation installation) throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/get_jsapi_ticket?access_token="
                    + queryParam(accessToken(installation)));
        }

        @Override public TicketResponse fetchAgentJsapiTicket(
                WeComAuthorizationStore.ResolvedInstallation installation) throws Exception {
            return fetchTicket("https://qyapi.weixin.qq.com/cgi-bin/ticket/get?access_token="
                    + queryParam(accessToken(installation)) + "&type=agent_config");
        }

        @Override public String exchangeLoginCode(String code) throws Exception {
            throw new WeComAuthorizationException("WECOM_LOGIN_SUITE_NOT_CONFIGURED", 503,
                    "企业微信登录授权 Suite 网关不可用");
        }

        @Override public WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(
                String code, WeComAuthorizationStore.ResolvedInstallation installation) throws Exception {
            if (authorizationGateway == null) {
                throw new WeComAuthorizationException("WECOM_LOGIN_SUITE_NOT_CONFIGURED", 503,
                        "企业微信登录授权 Suite 网关不可用");
            }
            return authorizationGateway.getLoginIdentity(code);
        }

        private synchronized String accessToken() throws Exception {
            long now = System.currentTimeMillis() / 1000;
            if (accessToken != null && now < accessTokenExpiresAt - config.wecomTokenRefreshSkewSeconds()) {
                return accessToken;
            }
            if (config.wecomCorpId().isBlank() || config.wecomSecret().isBlank()) {
                throw new IOException("WeCom corp id or secret is not configured");
            }
            String url = apiBase.resolve("/cgi-bin/gettoken?corpid="
                    + queryParam(config.wecomCorpId()) + "&corpsecret="
                    + queryParam(config.wecomSecret())).toString();
            JsonObject body = getJson(url);
            int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
            if (errcode != 0) {
                throw new IOException("Unable to fetch WeCom access_token: " + JsonSupport.string(body, "errmsg"));
            }
            accessToken = JsonSupport.string(body, "access_token");
            if (accessToken.isBlank()) {
                throw new IOException("WeCom token response did not contain access_token");
            }
            int expiresIn = body.has("expires_in") ? body.get("expires_in").getAsInt() : 7200;
            accessTokenExpiresAt = now + expiresIn;
            return accessToken;
        }

        private synchronized String accessToken(WeComAuthorizationStore.ResolvedInstallation installation)
                throws Exception {
            if (installationAccessTokens == null) {
                throw new IOException("WeCom installation access token service is unavailable");
            }
            return installationAccessTokens.accessToken(installation);
        }

        private TicketResponse fetchTicket(String url) throws Exception {
            JsonObject body = getJson(url);
            int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
            return new TicketResponse(errcode, JsonSupport.string(body, "errmsg"),
                    JsonSupport.string(body, "ticket"),
                    body.has("expires_in") ? body.get("expires_in").getAsInt() : 7200);
        }

        private JsonObject getJson(String url) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(WECOM_HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("WeCom API returned HTTP " + response.statusCode());
                }
                byte[] bytes = body.readNBytes(MAX_WECOM_HTTP_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_WECOM_HTTP_RESPONSE_BYTES) {
                    throw new IOException("WeCom API response is too large");
                }
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }

        private static String queryParam(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
