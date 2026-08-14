package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerHttpGateway;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComViewerService {
    private static final int MAX_ACTIVE_VIEWER_SESSIONS = 512;

    private final AppConfig config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComViewerHttpGateway gateway;
    private final WeComInstallationService installationService;
    private final ViewerAuditSink auditTrail;
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComCredentialProtector credentialProtector;
    private final WeComStartupGate startupGate;
    private final ConcurrentMap<String, CachedTicket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerAuth> viewerAuthTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerSession> viewerSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewedSession> viewedSessionsByToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionRateWindow> viewerSessionRates = new ConcurrentHashMap<>();
    private final AtomicLong sessionOrder = new AtomicLong();

    @Autowired
    public WeComViewerService(AppConfig config, WeComInstallationService installationService,
                              WeComViewerHttpGateway gateway, ViewerAuditSink audit,
                              WeComChatDataMessageMapper messageMapper,
                              WeComCredentialProtector credentialProtector,
                              WeComStartupGate startupGate) {
        this(config, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""),
                gateway, installationService, audit, messageMapper, credentialProtector, startupGate);
    }

    private WeComViewerService(AppConfig config, Clock clock, NonceSource nonceSource,
                               WeComViewerHttpGateway gateway, WeComInstallationService installationService,
                               ViewerAuditSink audit, WeComChatDataMessageMapper messageMapper,
                               WeComCredentialProtector credentialProtector,
                               WeComStartupGate startupGate) {
        this.config = config;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.gateway = gateway;
        this.installationService = installationService;
        this.auditTrail = Objects.requireNonNull(audit, "audit");
        this.messageMapper = messageMapper;
        this.credentialProtector = credentialProtector;
        this.startupGate = startupGate;
    }

    static WeComViewerService forTests(AppConfig config, Clock clock, NonceSource nonceSource,
                                       WeComViewerHttpGateway gateway,
                                       WeComInstallationService installationService,
                                       ViewerAuditSink audit,
                                       WeComChatDataMessageMapper messageMapper,
                                       WeComCredentialProtector credentialProtector,
                                       WeComStartupGate startupGate) {
        return new WeComViewerService(config, clock, nonceSource, gateway,
                installationService, audit, messageMapper, credentialProtector, startupGate);
    }

    public JsSdkConfig jsSdkConfig(String rawUrl) {
        try {
            startupGate.requireOpen();
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

    public JsSdkConfig jsSdkConfig(String rawUrl, String viewerAuthToken) {
        startupGate.requireOpen();
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond());
        ResolvedInstallation installation = requireBoundInstallation(auth);
        String url = canonicalAllowedUrl(rawUrl);
        SignatureBundle configSignature = signatureBundle("corp", url, installation);
        SignatureBundle agentSignature = signatureBundle("agent", url, installation);
        auditTrail.record("wecom.viewer.js_sdk_config", "success", auth.wecomUserId(), "", "");
        return new JsSdkConfig(installation.authCorpId(), installation.agentId(),
                List.of("selectExternalContact", "shareAppMessage", "wwapp.invokeJsApiByCallInfo"),
                configSignature, agentSignature);
    }

    public LoginExchangeResponse exchangeLoginCode(String code) {
        String userId = "";
        try {
            startupGate.requireOpen();
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
                                                   WeComLoginAttemptService.InstallationBinding binding) {
        String userId = "";
        try {
            startupGate.requireOpen();
            if (binding == null) {
                throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                        "企业微信授权安装存储不可用");
            }
            ResolvedInstallation resolved = installationService.resolveInstallation(
                    binding.suiteId(), binding.authCorpId());
            if (!resolved.installationId().equals(binding.installationId())
                    || resolved.version() != binding.version()) {
                throw new WeComException("WECOM_INSTALLATION_CHANGED", 403,
                        "企业微信授权安装已变化，请重新扫码");
            }
            WeComAuthorizationGateway.LoginIdentity identity = gateway.exchangeLoginIdentity(code);
            if (!identity.corpId().equals(binding.authCorpId())) {
                throw new WeComException("WECOM_LOGIN_CORP_MISMATCH", 403,
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
    public String requireViewerActor(String viewerAuthToken) {
        startupGate.requireOpen();
        return resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond()).wecomUserId();
    }

    public ViewerSyncContext viewerSyncContext(String viewerAuthToken) {
        startupGate.requireOpen();
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond());
        return new ViewerSyncContext(auth.wecomUserId(), resolveBoundInstallation(auth));
    }

    public ViewerSessionResponse createViewerSession(String contactPointId, String viewerAuthToken,
                                                     List<String> requestedMessageIds) {
        String wecomUserId = "";
        String viewerSessionId = "";
        try {
            startupGate.requireOpen();
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

    public ViewerSessionDetail viewerSession(String viewerSessionId, String viewerAuthToken) {
        String wecomUserId = "";
        ViewerSession session = null;
        try {
            startupGate.requireOpen();
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
            ResolvedInstallation installation = auth.installationId().isBlank()
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

    public void recordClientEvent(String eventType, String viewerSessionId, String viewerAuthToken) {
        startupGate.requireOpen();
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

    public void recordAccessDenied(String contactPointId, String viewerAuthToken) {
        startupGate.requireOpen();
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
                if (exception instanceof WeComException authorization) {
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
        if (!(exception instanceof WeComException authorization)) {
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

    private String resolveViewerAuth(String viewerAuthToken, long now) {
        return resolveViewerAuthRecord(viewerAuthToken, now).wecomUserId();
    }

    private ViewerAuth resolveViewerAuthRecord(String viewerAuthToken, long now) {
        requireBoundedSecurity(viewerAuthToken, "WeCom viewer auth token", 128);
        cleanupExpiredViewerAuth(now);
        ViewerAuth auth = viewerAuthTokens.get(viewerAuthToken);
        if (auth == null) {
            throw new SecurityException("WeCom viewer auth token is expired or missing");
        }
        if (!auth.installationId().isBlank()) requireBoundInstallation(auth);
        return auth;
    }

    private ResolvedInstallation requireBoundInstallation(ViewerAuth auth) {
        return resolveBoundInstallation(auth);
    }

    private ResolvedInstallation resolveBoundInstallation(ViewerAuth auth) {
        ResolvedInstallation resolved = installationService.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId());
        if (!resolved.installationId().equals(auth.installationId())
                || resolved.version() != auth.version()) {
            throw new WeComException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新扫码");
        }
        return resolved;
    }

    SignatureBundle signatureBundle(String ticketType, String rawUrl) {
        String ticket = ticket(ticketType);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = nonceSource.nextNonce();
        return new SignatureBundle(Long.toString(timestamp), nonce,
                makeSignature(ticket, canonicalAllowedUrl(rawUrl), timestamp, nonce));
    }

    private SignatureBundle signatureBundle(String ticketType, String rawUrl,
                                            ResolvedInstallation installation) {
        String ticket = ticket(ticketType, installation);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = nonceSource.nextNonce();
        return new SignatureBundle(Long.toString(timestamp), nonce,
                makeSignature(ticket, canonicalAllowedUrl(rawUrl), timestamp, nonce));
    }

    private String ticket(String ticketType, ResolvedInstallation installation) {
        long now = clock.instant().getEpochSecond();
        String cacheKey = installation.installationId() + ":" + installation.version() + ":" + ticketType;
        CachedTicket cached = tickets.get(cacheKey);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.ticket();
        }
        WeComViewerHttpGateway.TicketResponse response = "agent".equals(ticketType)
                ? gateway.fetchAgentJsapiTicket(installation) : gateway.fetchCorpJsapiTicket(installation);
        if (response.errcode() != 0 || response.ticket().isBlank()) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503, "无法获取企业微信 jsapi_ticket");
        }
        tickets.put(cacheKey, new CachedTicket(response.ticket(), now + response.expiresIn()));
        return response.ticket();
    }

    private String ticket(String ticketType) {
        long now = clock.instant().getEpochSecond();
        CachedTicket cached = tickets.get(ticketType);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.ticket();
        }
        WeComViewerHttpGateway.TicketResponse response = "agent".equals(ticketType)
                ? gateway.fetchAgentJsapiTicket() : gateway.fetchCorpJsapiTicket();
        if (response.errcode() != 0 || response.ticket().isBlank()) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "无法获取企业微信 " + ticketType + " jsapi_ticket: " + response.errmsg());
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
                                                   List<String> requestedMessageIds) {
        String expectedExternal = contactPointId.substring("wecom:".length());
        Set<String> requested = new HashSet<>(requestedMessageIds);
        Map<String, ViewerMessageCandidate> candidatesById = new LinkedHashMap<>();
        for (WeComChatDataMessageEntity entity : messageMapper.findByExternalUserid(expectedExternal)) {
            if (!wecomUserId.equals(entity.getUserid())) continue;
            String msgid = entity.getMsgid();
            if (!requested.contains(msgid)) continue;
            String secret = credentialProtector.revealSecretKey(entity.getSecretKey());
            if (msgid == null || msgid.isBlank() || msgid.length() > 256
                    || secret == null || secret.isBlank() || secret.length() > 1024) {
                continue;
            }
            long sendTime = entity.getSendTime() == null ? 0L : entity.getSendTime();
            ViewerMessageCandidate candidate = new ViewerMessageCandidate(sendTime, msgid, secret);
            ViewerMessageCandidate previous = candidatesById.get(msgid);
            if (previous == null || candidate.sendTime() >= previous.sendTime()) {
                candidatesById.put(msgid, candidate);
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
                .map(Map.Entry::getKey)
                .ifPresent(viewerAuthTokens::remove);
    }

    private void enforceViewerSessionLimit() {
        if (viewerSessions.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewerSessions.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(Map.Entry::getKey)
                .ifPresent(viewerSessions::remove);
    }

    private void enforceViewedSessionLimit() {
        if (viewedSessionsByToken.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewedSessionsByToken.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(Map.Entry::getKey)
                .ifPresent(viewedSessionsByToken::remove);
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
        if (scheme.isBlank() || host.isBlank() || !allowedOrigin(origin)) {
            throw new IllegalArgumentException("WeCom JSAPI URL origin is not allowed");
        }
        URI noFragment = URI.create(origin + Objects.toString(uri.getRawPath(), "")
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        return noFragment.toString();
    }

    private boolean allowedOrigin(String origin) {
        String raw = config.wecomAllowedJsapiOrigins();
        if (raw == null || raw.isBlank()) return false;
        for (String part : raw.split(",")) {
            if (origin.equals(part.trim())) return true;
        }
        return false;
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
        } catch (NoSuchAlgorithmException exception) {
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
}
