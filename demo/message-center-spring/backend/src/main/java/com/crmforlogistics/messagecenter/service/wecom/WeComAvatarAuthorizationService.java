package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns the short-lived OAuth2 state used to authorize the bound employee's avatar. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAvatarAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(WeComAvatarAuthorizationService.class);
    static final int ATTEMPT_TTL_SECONDS = 300;
    private static final int TERMINAL_RETENTION_SECONDS = 300;
    private static final String CALLBACK_PATH = "/api/public/wecom-avatar/oauth/callback";

    private final AppConfig config;
    private final WeComUserBindingService bindings;
    private final WeComInstallationService installations;
    private final WeComAuthorizationGateway gateway;
    private final WeComAccessTokenService accessTokens;
    private final WeComPartyProfileService profiles;
    private final Clock clock;
    private final NonceSource nonces;
    private final Map<String, Attempt> attemptsById = new LinkedHashMap<>();
    private final Map<String, Attempt> attemptsByState = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> sourceWindows = new LinkedHashMap<>();

    @Autowired
    public WeComAvatarAuthorizationService(AppConfig config,
                                           WeComUserBindingService bindings,
                                           WeComInstallationService installations,
                                           WeComAuthorizationGateway gateway,
                                           WeComAccessTokenService accessTokens,
                                           WeComPartyProfileService profiles) {
        this(config, bindings, installations, gateway, accessTokens, profiles,
                Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""));
    }

    private WeComAvatarAuthorizationService(AppConfig config,
                                            WeComUserBindingService bindings,
                                            WeComInstallationService installations,
                                            WeComAuthorizationGateway gateway,
                                            WeComAccessTokenService accessTokens,
                                            WeComPartyProfileService profiles,
                                            Clock clock,
                                            NonceSource nonces) {
        this.config = Objects.requireNonNull(config);
        this.bindings = Objects.requireNonNull(bindings);
        this.installations = Objects.requireNonNull(installations);
        this.gateway = Objects.requireNonNull(gateway);
        this.accessTokens = Objects.requireNonNull(accessTokens);
        this.profiles = Objects.requireNonNull(profiles);
        this.clock = Objects.requireNonNull(clock);
        this.nonces = Objects.requireNonNull(nonces);
    }

    static WeComAvatarAuthorizationService forTests(AppConfig config,
                                                     WeComUserBindingService bindings,
                                                     WeComInstallationService installations,
                                                     WeComAuthorizationGateway gateway,
                                                     WeComAccessTokenService accessTokens,
                                                     WeComPartyProfileService profiles,
                                                     Clock clock,
                                                     NonceSource nonces) {
        return new WeComAvatarAuthorizationService(config, bindings, installations, gateway,
                accessTokens, profiles, clock, nonces);
    }

    public synchronized AuthorizationAttempt create(UUID userId, String remoteAddress) {
        if (userId == null) throw error("WECOM_AVATAR_AUTH_BINDING_REQUIRED", 401, "当前登录用户不可用");
        long now = clock.instant().getEpochSecond();
        cleanup(now);
        if (!admit(remoteAddress, now)) {
            throw error("WECOM_AVATAR_AUTH_RATE_LIMITED", 429, "头像授权请求过于频繁，请稍后重试");
        }
        for (Attempt attempt : attemptsById.values()) {
            if (attempt.userId.equals(userId) && attempt.status == Status.PENDING && now < attempt.expiresAt) {
                return projection(attempt);
            }
        }
        int capacity = Math.max(1, config.wecomLoginMaxPending());
        if (activeAttemptCount() >= capacity) {
            throw error("WECOM_AVATAR_AUTH_RATE_LIMITED", 429, "头像授权请求已满，请稍后重试");
        }

        WeComUserBindingService.BoundIdentity bound;
        try {
            bound = bindings.requireByUserId(userId);
        } catch (WeComException failure) {
            if ("WECOM_USER_NOT_BOUND".equals(failure.code())) {
                throw error("WECOM_AVATAR_AUTH_BINDING_REQUIRED", 409, "账号尚未绑定企业微信");
            }
            throw failure;
        }
        ResolvedInstallation installation = installations.resolveInstallation(bound.suiteId(), bound.authCorpId());
        require(installation.agentId(), "WECOM_AVATAR_AUTH_INSTALLATION_CHANGED", "企业微信安装缺少 AgentID");
        require(bound.wecomUserId(), "WECOM_AVATAR_AUTH_BINDING_REQUIRED", "企业微信成员绑定不可用");

        String authorizationId = nonce("authorization ID");
        String state = nonce("OAuth state");
        long expiresAt = now + ATTEMPT_TTL_SECONDS;
        Attempt attempt = new Attempt(authorizationId, state, userId, installation.installationId(),
                installation.version(), installation.suiteId(), installation.authCorpId(),
                installation.agentId(), bound.wecomUserId(), expiresAt,
                authorizationUrl(installation, state), Status.PENDING, null, 0);
        attemptsById.put(authorizationId, attempt);
        attemptsByState.put(state, attempt);
        log(attempt, "ATTEMPT_CREATE", "PENDING", null);
        return projection(attempt);
    }

    public synchronized AuthorizationStatus status(UUID userId, String authorizationId) {
        requireId(authorizationId);
        Attempt attempt = attemptsById.get(authorizationId);
        if (attempt == null || userId == null || !attempt.userId.equals(userId)) {
            throw error("WECOM_AVATAR_AUTH_STATE_INVALID", 404, "头像授权状态不存在或已失效");
        }
        long now = clock.instant().getEpochSecond();
        if (attempt.status == Status.PENDING && now >= attempt.expiresAt) {
            attempt.status = Status.EXPIRED;
            attempt.errorCode = "WECOM_AVATAR_AUTH_STATE_INVALID";
            attempt.completedAt = now;
        }
        return new AuthorizationStatus(attempt.authorizationId, attempt.status, attempt.errorCode);
    }

    public CallbackResult complete(String code, String state) {
        requireCallbackValue(code);
        requireCallbackValue(state);
        Attempt attempt;
        synchronized (this) {
            long now = clock.instant().getEpochSecond();
            cleanup(now);
            attempt = attemptsByState.get(state);
            if (attempt == null) {
                throw error("WECOM_AVATAR_AUTH_STATE_INVALID", 404, "头像授权状态不存在或已失效");
            }
            if (attempt.status != Status.PENDING) return callbackProjection(attempt);
            if (now >= attempt.expiresAt) {
                return expire(attempt, now);
            }
            if (attempt.claimed) return callbackProjection(attempt);
            attempt.claimed = true;
        }

        return exchange(attempt, code);
    }

    private CallbackResult exchange(Attempt attempt, String code) {
        ResolvedInstallation installation;
        try {
            installation = installations.resolveInstallation(attempt.suiteId, attempt.authCorpId);
        } catch (RuntimeException failure) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_INSTALLATION_CHANGED",
                    "IDENTITY_EXCHANGE");
        }
        if (!sameInstallation(attempt, installation)) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_INSTALLATION_CHANGED",
                    "IDENTITY_EXCHANGE");
        }
        try {
            WeComUserBindingService.BoundIdentity current = bindings.requireByUserId(attempt.userId);
            if (!sameBinding(attempt, current)) {
                return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_IDENTITY_MISMATCH",
                        "IDENTITY_EXCHANGE");
            }
        } catch (RuntimeException failure) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_IDENTITY_MISMATCH",
                    "IDENTITY_EXCHANGE");
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, config.wecomApiTimeoutSeconds()));
        String accessToken;
        WeComAuthorizationGateway.LoginIdentity identity;
        try {
            accessToken = accessTokens.accessToken(installation, timeout);
            identity = gateway.getLoginIdentity(
                    attempt.authCorpId, accessToken, code, timeout);
        } catch (RuntimeException failure) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_UPSTREAM_FAILED",
                    "IDENTITY_EXCHANGE");
        }
        if (identity == null
                || !attempt.authCorpId.equals(identity.corpId())
                || !attempt.wecomUserId.equals(identity.userId())) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_IDENTITY_MISMATCH",
                    "IDENTITY_EXCHANGE");
        }
        if (identity.userTicket() == null || identity.userTicket().isBlank()) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_TICKET_MISSING",
                    "IDENTITY_EXCHANGE");
        }

        JsonNode detail;
        try {
            detail = gateway.getUserDetail(accessToken, identity.userTicket(), timeout);
        } catch (RuntimeException failure) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_UPSTREAM_FAILED",
                    "PROFILE_EXCHANGE");
        }
        if (detail == null || !detail.isObject()
                || !attempt.wecomUserId.equals(detail.path("userid").asText(""))) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_IDENTITY_MISMATCH",
                    "PROFILE_EXCHANGE");
        }
        if (avatar(detail).isBlank()) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_PROFILE_EMPTY",
                    "PROFILE_EXCHANGE");
        }

        WeComPartyProfileService.ProfileResult profile;
        try {
            profile = profiles.syncAuthorizedEmployee(installation, attempt.wecomUserId, detail);
        } catch (RuntimeException failure) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_UPSTREAM_FAILED",
                    "PROFILE_PERSIST");
        }
        if (profile == null || profile.avatarUrl() == null || profile.avatarUrl().isBlank()) {
            return finish(attempt, Status.FAILED, "WECOM_AVATAR_AUTH_PROFILE_EMPTY",
                    "PROFILE_PERSIST");
        }
        return finish(attempt, Status.SUCCEEDED, null, "PROFILE_PERSIST");
    }

    private synchronized CallbackResult finish(Attempt attempt, Status status,
                                                String errorCode, String stage) {
        if (attempt.status != Status.PENDING) return callbackProjection(attempt);
        attempt.status = status;
        attempt.errorCode = errorCode;
        attempt.completedAt = clock.instant().getEpochSecond();
        attempt.claimed = false;
        log(attempt, stage, status.name(), errorCode);
        return callbackProjection(attempt);
    }

    private synchronized CallbackResult expire(Attempt attempt, long now) {
        if (attempt.status == Status.PENDING) {
            attempt.status = Status.EXPIRED;
            attempt.errorCode = "WECOM_AVATAR_AUTH_STATE_INVALID";
            attempt.completedAt = now;
            attempt.claimed = false;
            log(attempt, "IDENTITY_EXCHANGE", "EXPIRED", attempt.errorCode);
        }
        return callbackProjection(attempt);
    }

    private static boolean sameInstallation(Attempt attempt, ResolvedInstallation current) {
        return current != null
                && Objects.equals(attempt.installationId, current.installationId())
                && attempt.installationVersion == current.version()
                && Objects.equals(attempt.suiteId, current.suiteId())
                && Objects.equals(attempt.authCorpId, current.authCorpId())
                && Objects.equals(attempt.agentId, current.agentId());
    }

    private static boolean sameBinding(Attempt attempt, WeComUserBindingService.BoundIdentity current) {
        return current != null
                && Objects.equals(attempt.userId, current.userId())
                && Objects.equals(attempt.suiteId, current.suiteId())
                && Objects.equals(attempt.authCorpId, current.authCorpId())
                && Objects.equals(attempt.wecomUserId, current.wecomUserId());
    }

    private static String avatar(JsonNode detail) {
        String avatar = detail.path("avatar").asText("").trim();
        return avatar.isBlank() ? detail.path("thumb_avatar").asText("").trim() : avatar;
    }

    private String authorizationUrl(ResolvedInstallation installation, String state) {
        String callback = callbackUri();
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + encode(installation.authCorpId())
                + "&redirect_uri=" + encode(callback)
                + "&response_type=code"
                + "&scope=snsapi_privateinfo"
                + "&state=" + encode(state)
                + "&agentid=" + encode(installation.agentId())
                + "#wechat_redirect";
    }

    private String callbackUri() {
        try {
            URI configured = URI.create(config.wecomLoginRedirectUri());
            if (!"https".equalsIgnoreCase(configured.getScheme()) || configured.getHost() == null
                    || configured.getRawAuthority() == null) {
                throw new IllegalArgumentException("WeCom login redirect URI must be absolute HTTPS");
            }
            return new URI("https", configured.getRawAuthority(), CALLBACK_PATH, null, null).toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException failure) {
            throw error("WECOM_AVATAR_AUTH_REDIRECT_INVALID", 500,
                    "企业微信头像授权回调地址配置无效");
        }
    }

    private boolean admit(String remoteAddress, long now) {
        String source = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        int sourceCapacity = Math.max(1, config.wecomLoginMaxPending());
        if (!sourceWindows.containsKey(source) && sourceWindows.size() >= sourceCapacity) {
            sourceWindows.remove(sourceWindows.keySet().iterator().next());
        }
        int configuredLimit = config.wecomLoginAttemptRateLimitPerMinute();
        int limit = configuredLimit <= 0 ? 20 : configuredLimit;
        Deque<Long> window = sourceWindows.computeIfAbsent(source, ignored -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= 60) window.removeFirst();
        if (window.size() >= limit) return false;
        window.addLast(now);
        return true;
    }

    private int activeAttemptCount() {
        int count = 0;
        for (Attempt attempt : attemptsById.values()) {
            if (attempt.status == Status.PENDING) count++;
        }
        return count;
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Attempt>> iterator = attemptsById.entrySet().iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next().getValue();
            if (attempt.status == Status.PENDING && now >= attempt.expiresAt) {
                attempt.status = Status.EXPIRED;
                attempt.errorCode = "WECOM_AVATAR_AUTH_STATE_INVALID";
                attempt.completedAt = now;
            } else if (attempt.status != Status.PENDING
                    && now >= Math.max(attempt.expiresAt, attempt.completedAt) + TERMINAL_RETENTION_SECONDS) {
                attemptsByState.remove(attempt.state);
                iterator.remove();
            }
        }
        sourceWindows.values().removeIf(window -> {
            while (!window.isEmpty() && now - window.peekFirst() >= 60) window.removeFirst();
            return window.isEmpty();
        });
    }

    private AuthorizationAttempt projection(Attempt attempt) {
        int remaining = (int) Math.max(0, attempt.expiresAt - clock.instant().getEpochSecond());
        return new AuthorizationAttempt(attempt.authorizationId, attempt.authorizationUrl,
                attempt.status, remaining);
    }

    private static CallbackResult callbackProjection(Attempt attempt) {
        return new CallbackResult(attempt.status, attempt.errorCode);
    }

    private String nonce(String name) {
        String value = nonces.nextNonce();
        if (value == null || value.length() < 32 || value.length() > 128
                || attemptsById.containsKey(value) || attemptsByState.containsKey(value)) {
            throw new IllegalStateException(name + " source returned an unsafe or duplicate value");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void require(String value, String code, String message) {
        if (value == null || value.isBlank() || value.length() > 256) throw error(code, 409, message);
    }

    private static void requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw error("WECOM_AVATAR_AUTH_STATE_INVALID", 404, "头像授权状态不存在或已失效");
        }
    }

    private static void requireCallbackValue(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw error("WECOM_AVATAR_AUTH_STATE_INVALID", 400, "头像授权回调参数无效");
        }
    }

    private static void log(Attempt attempt, String stage, String status, String errorCode) {
        log.info("event=wecom.avatar_authorization stage={} status={} authorizationDigest={} installationId={} errorCode={}",
                stage, status, digest(attempt.authorizationId), attempt.installationId,
                errorCode == null || errorCode.isBlank() ? "NONE" : errorCode);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static WeComException error(String code, int status, String message) {
        return new WeComException(code, status, message);
    }

    public record AuthorizationAttempt(String authorizationId, String authorizationUrl,
                                       Status status, int expiresIn) {}

    public record AuthorizationStatus(String authorizationId, Status status, String errorCode) {}

    public record CallbackResult(Status status, String errorCode) {}

    public enum Status { PENDING, SUCCEEDED, FAILED, EXPIRED }

    interface NonceSource {
        String nextNonce();
    }

    private static final class Attempt {
        private final String authorizationId;
        private final String state;
        private final UUID userId;
        private final String installationId;
        private final long installationVersion;
        private final String suiteId;
        private final String authCorpId;
        private final String agentId;
        private final String wecomUserId;
        private final long expiresAt;
        private final String authorizationUrl;
        private Status status;
        private String errorCode;
        private long completedAt;
        private boolean claimed;

        private Attempt(String authorizationId, String state, UUID userId,
                        String installationId, long installationVersion,
                        String suiteId, String authCorpId, String agentId, String wecomUserId,
                        long expiresAt, String authorizationUrl, Status status,
                        String errorCode, long completedAt) {
            this.authorizationId = authorizationId;
            this.state = state;
            this.userId = userId;
            this.installationId = installationId;
            this.installationVersion = installationVersion;
            this.suiteId = suiteId;
            this.authCorpId = authCorpId;
            this.agentId = agentId;
            this.wecomUserId = wecomUserId;
            this.expiresAt = expiresAt;
            this.authorizationUrl = authorizationUrl;
            this.status = status;
            this.errorCode = errorCode;
            this.completedAt = completedAt;
        }
    }
}
