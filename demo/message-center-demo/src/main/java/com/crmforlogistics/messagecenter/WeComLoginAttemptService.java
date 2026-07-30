package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WeComLoginAttemptService {
    private final Config config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final WeComViewerAuditTrail auditTrail;
    private final WeComAuthorizationStore authorizationStore;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    public WeComLoginAttemptService(Config config, WeComAuthorizationStore authorizationStore) {
        this(config, authorizationStore, Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    /** Compatibility constructor for callers that only need the route object; requests fail closed without a store. */
    public WeComLoginAttemptService(Config config) {
        this(config, null, Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""));
    }

    private WeComLoginAttemptService(Config config, WeComAuthorizationStore authorizationStore,
                                     Clock clock, NonceSource nonceSource) {
        this.config = config;
        this.authorizationStore = authorizationStore;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.auditTrail = new WeComViewerAuditTrail(config, clock);
    }

    static WeComLoginAttemptService forTests(Config config, WeComAuthorizationStore authorizationStore,
                                              Clock clock, NonceSource nonceSource) {
        return new WeComLoginAttemptService(config, authorizationStore, clock, nonceSource);
    }

    public synchronized LoginAttemptResponse createAttempt() throws Exception {
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        if (authorizationStore == null) {
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装存储不可用");
        }
        requireBounded(config.wecomSuiteId(), "企业微信 SuiteID", 128);
        if (config.wecomLoginAuthCorpId().isBlank()) {
            throw new WeComAuthorizationException("WECOM_LOGIN_INSTALLATION_NOT_SELECTED", 400,
                    "未配置登录首屏使用的授权企业");
        }
        WeComAuthorizationStore.Installation installation = authorizationStore.requireActive(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId());
        String redirectUri = config.wecomLoginRedirectUri();
        if (attempts.size() >= config.wecomLoginMaxPending()) {
            auditTrail.record("wecom.viewer.login_attempt_create", "rate_limited", "", "", "");
            throw new PendingLimitException("WeCom login attempt capacity exceeded");
        }
        String state = nonceSource.nextNonce();
        requireBounded(state, "WeCom login state", 128);
        if (state.length() < 16 || attempts.containsKey(state)) {
            throw new IllegalStateException("WeCom login state source returned an unsafe or duplicate value");
        }
        auditTrail.record("wecom.viewer.login_attempt_create", "success", "", "", "");
        attempts.put(state, new Attempt(now + config.wecomLoginAttemptTtlSeconds(),
                installation.installationId(), installation.version(), installation.suiteId(),
                installation.authCorpId(), installation.agentId()));
        return new LoginAttemptResponse(installation.authCorpId(), installation.agentId(), redirectUri,
                state, config.wecomLoginAttemptTtlSeconds());
    }

    public synchronized InstallationBinding consume(String state) throws Exception {
        requireBoundedSecurity(state, "WeCom login state", 128);
        long now = clock.instant().getEpochSecond();
        Attempt attempt = attempts.remove(state);
        if (attempt == null || now >= attempt.expiresAtEpochSecond()) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new SecurityException("WeCom login state is expired, missing, or already used");
        }
        if (authorizationStore == null) {
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装存储不可用");
        }
        WeComAuthorizationStore.Installation current = authorizationStore.requireActive(
                attempt.suiteId(), attempt.authCorpId());
        if (!current.installationId().equals(attempt.installationId())
                || current.version() != attempt.version()
                || !current.agentId().equals(attempt.agentId())) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新扫码");
        }
        auditTrail.record("wecom.viewer.login_attempt_consume", "success", "", "", "");
        return new InstallationBinding(current.installationId(), current.version(), current.suiteId(),
                current.authCorpId(), current.agentId());
    }

    private void cleanupExpired(long now) throws IOException {
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Attempt> entry = iterator.next();
            if (now >= entry.getValue().expiresAtEpochSecond()) {
                auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
                iterator.remove();
            }
        }
    }

    private static void requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + "不能为空，且长度不能超过" + maxLength + "个字符");
        }
    }

    private static void requireBoundedSecurity(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new SecurityException(
                    name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    public record LoginAttemptResponse(String corpId, String agentId, String redirectUri,
                                       String state, int expiresIn) {}

    public record InstallationBinding(String installationId, long version, String suiteId,
                                      String authCorpId, String agentId) {}

    private record Attempt(long expiresAtEpochSecond, String installationId, long version,
                           String suiteId, String authCorpId, String agentId) {}

    interface NonceSource {
        String nextNonce();
    }

    public static final class PendingLimitException extends IllegalStateException {
        PendingLimitException(String message) {
            super(message);
        }
    }
}
