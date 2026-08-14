package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAccessTokenService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final AppConfig config;
    private final Clock clock;
    private final WeComAuthorizationGateway gateway;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    @Autowired
    public WeComAccessTokenService(AppConfig config, WeComAuthorizationGateway gateway) {
        this(config, Clock.systemUTC(), gateway);
    }

    WeComAccessTokenService(AppConfig config, Clock clock, WeComAuthorizationGateway gateway) {
        this.config = config;
        this.clock = clock;
        this.gateway = gateway;
    }

    public String accessToken(ResolvedInstallation installation) {
        return accessToken(installation, DEFAULT_TIMEOUT);
    }

    public synchronized String accessToken(ResolvedInstallation installation, Duration timeout) {
        if (installation == null) {
            throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 503,
                    "企业微信授权安装凭证不可用");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游服务暂时不可用");
        }
        String cacheKey = installation.installationId() + ":" + installation.version();
        long now = clock.instant().getEpochSecond();
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && now < cached.expiresAtEpochSecond() - config.wecomTokenRefreshSkewSeconds()) {
            return cached.value();
        }
        WeComAuthorizationGateway.CorpTokenResponse response = gateway.getDevelopedAppToken(
                installation.authCorpId(), installation.permanentCode(), timeout);
        if (response.accessToken() == null || response.accessToken().isBlank()
                || response.accessToken().length() > 4096 || response.expiresIn() < 1) {
            throw new WeComException("WECOM_UPSTREAM_UNAVAILABLE", 503,
                    "企业微信上游响应缺少凭证");
        }
        String installationPrefix = installation.installationId() + ":";
        tokens.keySet().removeIf(key -> key.startsWith(installationPrefix) && !key.equals(cacheKey));
        tokens.put(cacheKey, new CachedToken(response.accessToken(), now + response.expiresIn()));
        return response.accessToken();
    }

    private record CachedToken(String value, long expiresAtEpochSecond) {}
}
