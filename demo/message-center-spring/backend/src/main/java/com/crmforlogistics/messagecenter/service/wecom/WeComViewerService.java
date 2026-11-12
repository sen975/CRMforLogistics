package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerHttpGateway;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
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
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComViewerService {
    private static final int MAX_ACTIVE_VIEWER_SESSIONS = 512;
    private static final int MAX_ACTIVE_VIEWER_SESSIONS_PER_TOKEN = 32;

    private final AppConfig config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComViewerHttpGateway gateway;
    private final WeComInstallationService installationService;
    private final ViewerAuditSink auditTrail;
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComCredentialProtector credentialProtector;
    private final WeComStartupGate startupGate;
    private final WeComViewerReferenceLeaseRegistry referenceLeases;
    private final ConcurrentMap<String, CachedTicket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerAuth> viewerAuthTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewerSession> viewerSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ViewedSession> viewedSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionRateWindow> viewerSessionRates = new ConcurrentHashMap<>();
    private final AtomicLong sessionOrder = new AtomicLong();
    private final ConcurrentMap<String, Long> clientEventKeys = new ConcurrentHashMap<>();

    @Autowired
    public WeComViewerService(AppConfig config, WeComInstallationService installationService,
                              WeComViewerHttpGateway gateway, ViewerAuditSink audit,
                              WeComChatDataMessageMapper messageMapper,
                              WeComCredentialProtector credentialProtector,
                              WeComStartupGate startupGate,
                              WeComViewerReferenceLeaseRegistry referenceLeases) {
        this(config, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""),
                gateway, installationService, audit, messageMapper, credentialProtector, startupGate,
                referenceLeases);
    }

    private WeComViewerService(AppConfig config, Clock clock, NonceSource nonceSource,
                               WeComViewerHttpGateway gateway, WeComInstallationService installationService,
                               ViewerAuditSink audit, WeComChatDataMessageMapper messageMapper,
                               WeComCredentialProtector credentialProtector,
                               WeComStartupGate startupGate,
                               WeComViewerReferenceLeaseRegistry referenceLeases) {
        this.config = config;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.gateway = gateway;
        this.installationService = installationService;
        this.auditTrail = Objects.requireNonNull(audit, "audit");
        this.messageMapper = messageMapper;
        this.credentialProtector = credentialProtector;
        this.startupGate = startupGate;
        this.referenceLeases = Objects.requireNonNull(referenceLeases, "referenceLeases");
    }

    static WeComViewerService forTests(AppConfig config, Clock clock, NonceSource nonceSource,
                                       WeComViewerHttpGateway gateway,
                                       WeComInstallationService installationService,
                                       ViewerAuditSink audit,
                                       WeComChatDataMessageMapper messageMapper,
                                       WeComCredentialProtector credentialProtector,
                                       WeComStartupGate startupGate) {
        return new WeComViewerService(config, clock, nonceSource, gateway,
                installationService, audit, messageMapper, credentialProtector, startupGate,
                new WeComViewerReferenceLeaseRegistry(clock, MAX_ACTIVE_VIEWER_SESSIONS, 15));
    }

    public JsSdkConfig jsSdkConfig(String rawUrl) {
        throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                "企业微信展示凭证必须绑定授权安装实例");
    }

    public JsSdkConfig jsSdkConfig(String rawUrl, String viewerAuthToken) {
        startupGate.requireOpen();
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, clock.instant().getEpochSecond());
        ResolvedInstallation installation = requireBoundInstallation(auth);
        String url = canonicalAllowedUrl(rawUrl);
        SignatureBundle configSignature = signatureBundle("corp", url, installation);
        SignatureBundle agentSignature = signatureBundle("agent", url, installation);
        recordAudit("wecom.viewer.js_sdk_config", "success", auth.wecomUserId(), "", "");
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
            storeViewerAuth(new ViewerAuth(token, userId,
                    now + config.wecomViewerAuthTtlSeconds(), sessionOrder.incrementAndGet(), "", 0L));
            recordAudit("wecom.viewer.login_exchange", "success", userId, "", "");
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
            WeComAuthorizationGateway.LoginIdentity identity = gateway.exchangeLoginIdentity(resolved, code);
            if (!identity.corpId().equals(binding.authCorpId())) {
                throw new WeComException("WECOM_LOGIN_CORP_MISMATCH", 403,
                        "企业微信登录企业与当前授权企业不一致");
            }
            userId = identity.userId();
            requireBounded(userId, "WeCom user id", 128);
            String token = UUID.randomUUID().toString().replace("-", "");
            long now = clock.instant().getEpochSecond();
            storeViewerAuth(new ViewerAuth(token, userId,
                    now + config.wecomViewerAuthTtlSeconds(), sessionOrder.incrementAndGet(),
                    binding.installationId(), binding.version()));
            recordAudit("wecom.viewer.login_exchange", "success", userId, "", "");
            return new LoginExchangeResponse(userId, token, config.wecomViewerAuthTtlSeconds());
        } catch (Exception exception) {
            recordFailure("wecom.viewer.login_exchange", "failed", userId, "", "", exception);
            throw exception;
        }
    }

    public LoginExchangeResponse issueViewerAuth(
            String wecomUserId, WeComLoginAttemptService.InstallationBinding binding) {
        try {
            startupGate.requireOpen();
            requireBounded(wecomUserId, "WeCom user id", 128);
            ResolvedInstallation resolved = validateInstallationBinding(binding);
            long now = clock.instant().getEpochSecond();
            String token = UUID.randomUUID().toString().replace("-", "");
            storeViewerAuth(new ViewerAuth(token, wecomUserId,
                    now + config.wecomViewerAuthTtlSeconds(), sessionOrder.incrementAndGet(),
                    resolved.installationId(), resolved.version()));
            recordAudit("wecom.viewer.bootstrap", "success", wecomUserId, "", "");
            return new LoginExchangeResponse(wecomUserId, token, config.wecomViewerAuthTtlSeconds());
        } catch (Exception exception) {
            recordFailure("wecom.viewer.bootstrap", "failed", wecomUserId, "", "", exception);
            throw exception;
        }
    }

    private ResolvedInstallation validateInstallationBinding(
            WeComLoginAttemptService.InstallationBinding binding) {
        if (binding == null) {
            throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装存储不可用");
        }
        ResolvedInstallation resolved = installationService.resolveInstallation(
                binding.suiteId(), binding.authCorpId());
        if (!resolved.installationId().equals(binding.installationId())
                || resolved.version() != binding.version()) {
            throw new WeComException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新获取展示凭证");
        }
        return resolved;
    }

    synchronized int activeViewerAuthTokenCount() {
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
            List<String> messageIds = validateRequestedMessageIds(requestedMessageIds);
            List<ViewerMessage> messages = readViewerMessages(contactPointId, wecomUserId, messageIds);
            if (messages.isEmpty()) {
                throw new SecurityException("WeCom conversation is not viewable by this session");
            }
            enforceViewerSessionRate(wecomUserId, now);
            viewerSessionId = UUID.randomUUID().toString().replace("-", "");
            long expiresAt = now + config.wecomViewerSessionTtlSeconds();
            ViewerSession viewerSession = new ViewerSession(viewerSessionId, viewerAuthToken, contactPointId,
                    expiresAt, sessionOrder.incrementAndGet(), messages);
            storeViewerSession(viewerSession);
            recordAudit("wecom.viewer.session_create", "success", wecomUserId,
                    contactPointId, viewerSessionId);
            return new ViewerSessionResponse(viewerSessionId, config.wecomViewerSessionTtlSeconds());
        } catch (Exception exception) {
            String result = exception instanceof RateLimitException ? "rate_limited"
                    : exception instanceof SecurityException ? "denied" : "failed";
            recordFailure("wecom.viewer.session_create", result, wecomUserId,
                    contactPointId, viewerSessionId, exception);
            throw exception;
        }
    }

    public ViewerSessionResponse createViewerSession(UUID crmUserId, String targetType, UUID targetId,
                                                     String viewerAuthToken, List<String> requestedMessageIds) {
        if (crmUserId == null || targetId == null || targetType == null) {
            throw new IllegalArgumentException("WeCom viewer target is required");
        }
        String contactPointId = "wecom-target:" + targetType + ":" + targetId;
        long now = clock.instant().getEpochSecond();
        ViewerAuth auth = resolveViewerAuthRecord(viewerAuthToken, now);
        List<WeComChatDataMessageEntity> rows = "WECOM_GROUP".equals(targetType)
                ? messageMapper.findViewableByGroupTarget(targetId, crmUserId)
                : "CONTACT".equals(targetType)
                    ? messageMapper.findViewableByContactTarget(targetId, crmUserId)
                    : List.of();
        Set<String> requested = new HashSet<>(validateRequestedMessageIds(requestedMessageIds));
        List<ViewerMessage> messages = rows.stream()
                .filter(row -> requested.contains(row.getMsgid()))
                .map(row -> new ViewerMessage(row.getMsgid(), credentialProtector.revealSecretKey(row.getSecretKey())))
                .filter(item -> item.secretKey() != null && !item.secretKey().isBlank())
                .toList();
        if (messages.size() != requested.size()) {
            throw new SecurityException("WeCom conversation is not viewable by this session");
        }
        enforceViewerSessionRate(auth.wecomUserId(), now);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        ViewerSession session = new ViewerSession(sessionId, viewerAuthToken, contactPointId,
                now + config.wecomViewerSessionTtlSeconds(), sessionOrder.incrementAndGet(), messages);
        storeViewerSession(session);
        recordAudit("wecom.viewer.session_create", "success", auth.wecomUserId(), contactPointId, sessionId);
        return new ViewerSessionResponse(sessionId, config.wecomViewerSessionTtlSeconds());
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
            referenceLeases.release(viewerSessionId);
            recordAudit("wecom.viewer.session_read", "success", wecomUserId,
                    session.contactPointId(), viewerSessionId);
            storeViewedSession(viewerAuthToken, new ViewedSession(viewerSessionId,
                    session.expiresAtEpochSecond(), session.createdOrder()));
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
        recordClientEvent(eventType, "legacy", "legacy", 0L, viewerSessionId, "component_error", viewerAuthToken);
    }

    public void recordClientEvent(String eventType, String eventKey, String stage, long generation,
                                  String viewerSessionId, String errorCategory, String viewerAuthToken) {
        startupGate.requireOpen();
        if (!"component_error".equals(eventType)) {
            throw new IllegalArgumentException("WeCom viewer event type is invalid");
        }
        if (eventKey == null || eventKey.isBlank() || stage == null || stage.isBlank()
                || errorCategory == null || errorCategory.isBlank() || generation < 0) {
            throw new IllegalArgumentException("WeCom viewer event metadata is invalid");
        }
        String wecomUserId = "";
        try {
            cleanupClientEventKeys(clock.instant().getEpochSecond());
            String dedupeKey = viewerAuthToken + "\u0000" + eventKey;
            if (clientEventKeys.putIfAbsent(dedupeKey, clock.instant().getEpochSecond() + 300) != null) return;
            if (viewerSessionId == null || viewerSessionId.isBlank()) return;
            long now = clock.instant().getEpochSecond();
            requireBounded(viewerSessionId, "WeCom viewer session id", 64);
            wecomUserId = resolveViewerAuth(viewerAuthToken, now);
            cleanupExpiredSessions(now);
            String viewedSessionKey = viewedSessionKey(viewerAuthToken, viewerSessionId);
            ViewedSession viewed = viewedSessions.get(viewedSessionKey);
            if (viewed == null || !viewed.id().equals(viewerSessionId)) {
                throw new SecurityException("WeCom viewer event does not match the viewed session");
            }
            if (!viewedSessions.remove(viewedSessionKey, viewed)) {
                throw new SecurityException("WeCom viewer event does not match the viewed session");
            }
            recordAudit("wecom.viewer.component_error", "failed", wecomUserId, "", viewerSessionId);
        } catch (Exception exception) {
            recordFailure("wecom.viewer.component_error", "failed", wecomUserId, "", viewerSessionId, exception);
            throw exception;
        }
    }

    private void cleanupClientEventKeys(long now) {
        clientEventKeys.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public void recordAccessDenied(String contactPointId, String viewerAuthToken) {
        startupGate.requireOpen();
        String wecomUserId = "";
        try {
            wecomUserId = resolveViewerAuth(viewerAuthToken, clock.instant().getEpochSecond());
        } catch (SecurityException ignored) {
            // The denied audit event remains useful even when no valid viewer identity is available.
        }
        recordAudit("wecom.viewer.access_check", "denied", wecomUserId, contactPointId, "");
    }

    public Set<String> leasedMessageIds() {
        cleanupExpiredSessions(clock.instant().getEpochSecond());
        return referenceLeases.leasedMessageIds();
    }

    private void recordAudit(String action, String result, String wecomUserId,
                             String contactPointId, String viewerSessionId) {
        try {
            auditTrail.record(action, result, wecomUserId, contactPointId, viewerSessionId);
        } catch (Exception ignored) {
            // Viewer audit is best effort and must not change the viewer result.
        }
    }

    private void recordFailure(String action, String result, String wecomUserId,
                               String contactPointId, String viewerSessionId, Exception exception) {
        if ("wecom.viewer.login_exchange".equals(action)) {
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
        List<WeComChatDataMessageEntity> rows = new ArrayList<>();
        for (WeComChatDataMessageEntity entity : messageMapper.findByExternalUserid(expectedExternal)) {
            if (wecomUserId.equals(entity.getUserid())) rows.add(entity);
        }
        addRows(rows, messageMapper.findViewableByExternalUserid(expectedExternal, wecomUserId));
        addRows(rows, messageMapper.findViewableByContactParty(expectedExternal, wecomUserId));
        for (WeComChatDataMessageEntity entity : rows) {
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

    private static void addRows(List<WeComChatDataMessageEntity> target,
                                Collection<WeComChatDataMessageEntity> rows) {
        if (rows != null) target.addAll(rows);
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

    private synchronized void storeViewerAuth(ViewerAuth auth) {
        cleanupExpiredViewerAuth(clock.instant().getEpochSecond());
        enforceViewerAuthLimit();
        viewerAuthTokens.put(auth.token(), auth);
    }

    private synchronized void storeViewerSession(ViewerSession session) {
        cleanupExpiredSessions(clock.instant().getEpochSecond());
        while (viewerSessions.size() >= MAX_ACTIVE_VIEWER_SESSIONS) {
            removeOldestViewerSession();
        }
        while (activeViewerSessionCount(session.viewerAuthToken())
                >= MAX_ACTIVE_VIEWER_SESSIONS_PER_TOKEN) {
            removeOldestViewerSessionForToken(session.viewerAuthToken());
        }
        viewerSessions.put(session.id(), session);
        try {
            referenceLeases.acquire(session.id(),
                    session.messages().stream().map(ViewerMessage::msgid).toList(),
                    session.expiresAtEpochSecond());
        } catch (RuntimeException exception) {
            viewerSessions.remove(session.id(), session);
            throw exception;
        }
    }

    private synchronized void storeViewedSession(String viewerAuthToken, ViewedSession session) {
        enforceViewedSessionLimit();
        viewedSessions.put(viewedSessionKey(viewerAuthToken, session.id()), session);
    }

    private void cleanupExpiredViewerAuth(long now) {
        for (Map.Entry<String, ViewerAuth> entry : viewerAuthTokens.entrySet()) {
            if (now >= entry.getValue().expiresAtEpochSecond()
                    && viewerAuthTokens.remove(entry.getKey(), entry.getValue())) {
                removeViewerSessionsForToken(entry.getKey());
                removeViewedSessionsForToken(entry.getKey());
            }
        }
    }

    private void cleanupExpiredSessions(long now) {
        for (Map.Entry<String, ViewerSession> entry : viewerSessions.entrySet()) {
            if (now >= entry.getValue().expiresAtEpochSecond()
                    && viewerSessions.remove(entry.getKey(), entry.getValue())) {
                referenceLeases.release(entry.getKey());
            }
        }
        viewedSessions.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private void removeViewerSessionsForToken(String viewerAuthToken) {
        for (Map.Entry<String, ViewerSession> entry : viewerSessions.entrySet()) {
            if (entry.getValue().viewerAuthToken().equals(viewerAuthToken)
                    && viewerSessions.remove(entry.getKey(), entry.getValue())) {
                referenceLeases.release(entry.getKey());
            }
        }
    }

    private void removeViewedSessionsForToken(String viewerAuthToken) {
        String prefix = viewerAuthToken + '\u0000';
        viewedSessions.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void enforceViewerAuthLimit() {
        if (viewerAuthTokens.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewerAuthTokens.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .ifPresent(entry -> {
                    if (viewerAuthTokens.remove(entry.getKey(), entry.getValue())) {
                        removeViewerSessionsForToken(entry.getKey());
                        removeViewedSessionsForToken(entry.getKey());
                    }
                });
    }

    private void enforceViewerSessionLimit() {
        if (viewerSessions.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        removeOldestViewerSession();
    }

    private void removeOldestViewerSession() {
        viewerSessions.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .ifPresent(entry -> {
                    if (viewerSessions.remove(entry.getKey(), entry.getValue())) {
                        referenceLeases.release(entry.getKey());
                    }
                });
    }

    private long activeViewerSessionCount(String viewerAuthToken) {
        return viewerSessions.values().stream()
                .filter(session -> session.viewerAuthToken().equals(viewerAuthToken))
                .count();
    }

    private void removeOldestViewerSessionForToken(String viewerAuthToken) {
        viewerSessions.entrySet().stream()
                .filter(entry -> entry.getValue().viewerAuthToken().equals(viewerAuthToken))
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .ifPresent(entry -> {
                    if (viewerSessions.remove(entry.getKey(), entry.getValue())) {
                        referenceLeases.release(entry.getKey());
                    }
                });
    }

    private void enforceViewedSessionLimit() {
        if (viewedSessions.size() < MAX_ACTIVE_VIEWER_SESSIONS) {
            return;
        }
        viewedSessions.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()))
                .map(Map.Entry::getKey)
                .ifPresent(viewedSessions::remove);
    }

    private static String viewedSessionKey(String viewerAuthToken, String viewerSessionId) {
        return viewerAuthToken + '\u0000' + viewerSessionId;
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
