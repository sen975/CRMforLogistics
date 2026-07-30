package com.crmforlogistics.messagecenter;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached owner for delegated-installation application access tokens. */
public final class WeComAccessTokenService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final Config config;
    private final Clock clock;
    private final CorpTokenProvider provider;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public WeComAccessTokenService(Config config, WeComAuthorizationGateway gateway) {
        this(config, Clock.systemUTC(), gateway::getCorpToken);
    }

    private WeComAccessTokenService(Config config, Clock clock, CorpTokenProvider provider) {
        this.config = config;
        this.clock = clock;
        this.provider = provider;
    }

    static WeComAccessTokenService forTests(Config config, Clock clock, CorpTokenProvider provider) {
        return new WeComAccessTokenService(config, clock, provider);
    }

    public synchronized String accessToken(WeComAuthorizationStore.ResolvedInstallation installation)
            throws WeComAuthorizationException {
        return accessToken(installation, DEFAULT_TIMEOUT);
    }

    public synchronized String accessToken(WeComAuthorizationStore.ResolvedInstallation installation,
                                           Duration timeout)
            throws WeComAuthorizationException {
        if (installation == null || installation.installation() == null) {
            throw new WeComAuthorizationException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装凭证不可用");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用");
        }
        WeComAuthorizationStore.Installation metadata = installation.installation();
        String cacheKey = metadata.installationId() + ":" + metadata.version();
        long now = clock.instant().getEpochSecond();
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.value();
        }
        WeComAuthorizationGateway.CorpTokenResponse response = provider.fetch(
                metadata.authCorpId(), installation.permanentCode(), timeout);
        if (response.accessToken() == null || response.accessToken().isBlank()
                || response.accessToken().length() > 4096 || response.expiresIn() < 1) {
            throw new WeComAuthorizationException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游响应缺少凭证");
        }
        String installationPrefix = metadata.installationId() + ":";
        tokens.keySet().removeIf(key -> key.startsWith(installationPrefix) && !key.equals(cacheKey));
        tokens.put(cacheKey, new CachedToken(response.accessToken(), now + response.expiresIn()));
        return response.accessToken();
    }

    interface CorpTokenProvider {
        WeComAuthorizationGateway.CorpTokenResponse fetch(String authCorpId, String permanentCode,
                                                           Duration timeout)
                throws WeComAuthorizationException;
    }

    private record CachedToken(String value, long expiresAtEpochSecond) {}
}
