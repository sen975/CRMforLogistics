package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComLoginAttemptService {
    private final AppConfig config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final ViewerAuditSink auditTrail;
    private final WeComInstallationService installationService;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    @Autowired
    public WeComLoginAttemptService(AppConfig config, WeComInstallationService installationService,
                                    ViewerAuditSink audit) {
        this(config, installationService, Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""), audit);
    }

    private WeComLoginAttemptService(AppConfig config, WeComInstallationService installationService,
                                     Clock clock, NonceSource nonceSource, ViewerAuditSink audit) {
        this.config = config;
        this.installationService = installationService;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.auditTrail = Objects.requireNonNull(audit, "audit");
    }

    static WeComLoginAttemptService forTests(AppConfig config, WeComInstallationService installationService,
                                              Clock clock, NonceSource nonceSource, ViewerAuditSink audit) {
        return new WeComLoginAttemptService(config, installationService, clock, nonceSource, audit);
    }

    public synchronized LoginAttemptResponse createAttempt() {
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        requireBounded(config.wecomSuiteId(), "企业微信 SuiteID", 128);
        if (config.wecomLoginAuthCorpId().isBlank()) {
            throw new WeComException("WECOM_LOGIN_INSTALLATION_NOT_SELECTED", 400,
                    "未配置登录首屏使用的授权企业");
        }
        boolean anyLoginSuite = !config.wecomLoginSuiteId().isBlank()
                || !config.wecomLoginSuiteSecret().isBlank();
        boolean completeLoginSuite = !config.wecomLoginSuiteId().isBlank()
                && !config.wecomLoginSuiteSecret().isBlank();
        if (anyLoginSuite && !completeLoginSuite) {
            throw new WeComException("WECOM_LOGIN_SUITE_INCOMPLETE", 400,
                    "企业微信登录授权 Suite 配置必须同时提供 SuiteID 和 SuiteSecret");
        }
        if (!completeLoginSuite) {
            throw new WeComException("WECOM_LOGIN_SUITE_NOT_CONFIGURED", 503,
                    "企业微信登录授权 Suite 尚未配置");
        }
        ResolvedInstallation installation = installationService.resolveInstallation(
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
        return new LoginAttemptResponse("ServiceApp", config.wecomLoginSuiteId(), redirectUri,
                state, config.wecomLoginAttemptTtlSeconds());
    }

    public synchronized InstallationBinding consume(String state) {
        requireBoundedSecurity(state, "WeCom login state", 128);
        long now = clock.instant().getEpochSecond();
        Attempt attempt = attempts.remove(state);
        if (attempt == null || now >= attempt.expiresAtEpochSecond()) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new SecurityException("WeCom login state is expired, missing, or already used");
        }
        ResolvedInstallation current = installationService.resolveInstallation(
                attempt.suiteId(), attempt.authCorpId());
        if (!current.installationId().equals(attempt.installationId())
                || current.version() != attempt.version()
                || !current.agentId().equals(attempt.agentId())) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new WeComException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新扫码");
        }
        auditTrail.record("wecom.viewer.login_attempt_consume", "success", "", "", "");
        return new InstallationBinding(current.installationId(), current.version(), current.suiteId(),
                current.authCorpId(), current.agentId());
    }

    private void cleanupExpired(long now) {
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

    public record LoginAttemptResponse(String loginType, String appId, String redirectUri,
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
