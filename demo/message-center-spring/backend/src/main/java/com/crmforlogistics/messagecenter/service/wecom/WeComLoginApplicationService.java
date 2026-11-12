package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.response.WeComBindingResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComLoginResponse;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComLoginApplicationService {
    private static final Logger log = LoggerFactory.getLogger(WeComLoginApplicationService.class);
    private final AppConfig config;
    private final WeComLoginAttemptService attempts;
    private final WeComAuthorizationGateway gateway;
    private final WeComAccessTokenService accessTokens;
    private final WeComInstallationService installations;
    private final WeComUserBindingService bindings;
    private final AuthSessionService sessions;
    private final WeComPartyProfileService profiles;

    @Autowired
    public WeComLoginApplicationService(AppConfig config,
                                        WeComLoginAttemptService attempts,
                                        WeComAuthorizationGateway gateway,
                                        WeComAccessTokenService accessTokens,
                                        WeComUserBindingService bindings,
                                        AuthSessionService sessions,
                                        WeComInstallationService installations,
                                        WeComPartyProfileService profiles) {
        this.config = config;
        this.attempts = attempts;
        this.gateway = gateway;
        this.accessTokens = accessTokens;
        this.installations = installations;
        this.bindings = bindings;
        this.sessions = sessions;
        this.profiles = profiles;
    }

    public WeComLoginApplicationService(AppConfig config,
                                        WeComLoginAttemptService attempts,
                                        WeComAuthorizationGateway gateway,
                                        WeComAccessTokenService accessTokens,
                                        WeComUserBindingService bindings,
                                        AuthSessionService sessions,
                                        WeComInstallationService installations) {
        this(config, attempts, gateway, accessTokens, bindings, sessions, installations, null);
    }

    @Transactional
    public WeComLoginResponse exchange(ExchangeRequest request, RequestMetadata metadata) {
        require(request == null ? null : request.code(), "code");
        require(request == null ? null : request.state(), "state");
        return attempts.executeOnce(request.state(), WeComLoginAttemptService.Purpose.LOGIN, null,
                request.code(), context -> exchangeLogin(context, request.code(), metadata));
    }

    private WeComLoginResponse exchangeLogin(WeComLoginAttemptService.AttemptContext context,
                                             String code, RequestMetadata metadata) {
        Duration timeout = Duration.ofSeconds(config.wecomApiTimeoutSeconds());
        var installation = installations.resolveInstallation(context.installationBinding().suiteId(),
                context.installationBinding().authCorpId());
        WeComAuthorizationGateway.LoginIdentity upstream = gateway.getLoginIdentity(
                installation.authCorpId(), accessTokens.accessToken(installation, timeout), code, timeout);
        validateCorp(context, upstream.corpId());
        hydrateAuthorizedProfile(installation, upstream, timeout);
        var identity = new WeComUserBindingService.ResolvedIdentity(
                context.installationBinding().suiteId(), upstream.corpId(), upstream.userId(),
                upstream.userId(), context.installationBinding());
        var bound = bindings.resolveOrCreate(identity);
        String token = sessions.issue(bound.userId(), metadata == null ? null : metadata.remoteAddress(),
                metadata == null ? null : metadata.userAgent());
        return new WeComLoginResponse(token, bound.username(), bound.roles());
    }

    @Transactional
    public WeComBindingResponse exchangeBinding(UUID currentUserId, ExchangeRequest request,
                                                 RequestMetadata metadata) {
        require(request == null ? null : request.code(), "code");
        require(request == null ? null : request.state(), "state");
        return attempts.executeOnce(request.state(), WeComLoginAttemptService.Purpose.BIND, currentUserId,
                request.code(), context -> exchangeBinding(currentUserId, context, request.code()));
    }

    private WeComBindingResponse exchangeBinding(UUID currentUserId,
                                                  WeComLoginAttemptService.AttemptContext context,
                                                  String code) {
        Duration timeout = Duration.ofSeconds(config.wecomApiTimeoutSeconds());
        var installation = installations.resolveInstallation(context.installationBinding().suiteId(),
                context.installationBinding().authCorpId());
        WeComAuthorizationGateway.LoginIdentity upstream = gateway.getLoginIdentity(
                installation.authCorpId(), accessTokens.accessToken(installation, timeout), code, timeout);
        validateCorp(context, upstream.corpId());
        hydrateAuthorizedProfile(installation, upstream, timeout);
        var identity = new WeComUserBindingService.ResolvedIdentity(
                context.installationBinding().suiteId(), upstream.corpId(), upstream.userId(),
                upstream.userId(), context.installationBinding());
        var bound = bindings.bind(currentUserId, identity);
        return bindingResponse(bound);
    }

    public WeComBindingResponse bindingStatus(UUID currentUserId) {
        try {
            var bound = bindings.requireByUserId(currentUserId);
            refreshBoundProfile(bound);
            return bindingResponse(bound);
        } catch (WeComException exception) {
            if ("WECOM_USER_NOT_BOUND".equals(exception.code())) {
                return new WeComBindingResponse(currentUserId, null, null, null, false);
            }
            throw exception;
        }
    }

    private void refreshBoundProfile(WeComUserBindingService.BoundIdentity bound) {
        if (bound == null || bound.installationBinding() == null) return;
        try {
            var installation = installations.resolveInstallation(bound.suiteId(), bound.authCorpId());
            hydrateAuthorizedProfile(installation,
                    new WeComAuthorizationGateway.LoginIdentity(bound.authCorpId(), bound.wecomUserId()),
                    Duration.ofSeconds(config.wecomApiTimeoutSeconds()));
        } catch (RuntimeException failure) {
            log.warn("event=wecom.employee.profile_status_refresh_failed userId={} code={}",
                    bound.wecomUserId(), failure instanceof WeComException exception
                            ? exception.code() : "UNEXPECTED");
        }
    }

    @Transactional
    public void unbind(UUID currentUserId) {
        bindings.unbind(currentUserId);
    }

    private WeComBindingResponse bindingResponse(WeComUserBindingService.BoundIdentity bound) {
        String corpName = "";
        if (bound != null && bound.suiteId() != null && bound.authCorpId() != null) {
            var installation = installations.find(bound.suiteId(), bound.authCorpId());
            if (installation != null && installation.getCorpName() != null) {
                corpName = installation.getCorpName().trim();
            }
        }
        String displayName = "";
        if (profiles != null && bound != null && bound.installationBinding() != null) {
            displayName = profiles.displayNameFor(bound.installationBinding().installationId(),
                    "EMPLOYEE", bound.wecomUserId());
        }
        return new WeComBindingResponse(bound.userId(), bound.authCorpId(), bound.wecomUserId(),
                bound.provisioningSource(), true,
                displayName.isBlank() ? null : displayName,
                corpName.isBlank() ? null : corpName);
    }

    private void hydrateAuthorizedProfile(com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation installation,
                                         WeComAuthorizationGateway.LoginIdentity identity, Duration timeout) {
        if (profiles == null || identity == null || identity.userId() == null
                || identity.userId().isBlank()) return;
        String userTicket = identity.userTicket();
        try {
            if (userTicket != null && !userTicket.isBlank()) {
                var accessToken = accessTokens.accessToken(installation, timeout);
                var result = profiles.syncAuthorizedEmployee(installation, identity.userId(),
                        gateway.getUserDetail(accessToken, userTicket, timeout));
                logProfileSync(identity.userId(), "/cgi-bin/auth/getuserdetail", result);
                return;
            }
            var result = profiles.syncEmployee(installation, identity.userId(), timeout);
            logProfileSync(identity.userId(), "/cgi-bin/user/get", result);
        } catch (RuntimeException failure) {
            log.warn("event=wecom.employee.profile_authorization_failed userId={} code={}",
                    identity.userId(), failure instanceof WeComException exception
                            ? exception.code() : "UNEXPECTED");
            if (userTicket != null && !userTicket.isBlank()) {
                try {
                    var result = profiles.syncEmployee(installation, identity.userId(), timeout);
                    logProfileSync(identity.userId(), "/cgi-bin/user/get", result);
                } catch (RuntimeException fallbackFailure) {
                    log.warn("event=wecom.employee.profile_directory_fallback_failed userId={} code={}",
                            identity.userId(), fallbackFailure instanceof WeComException exception
                                    ? exception.code() : "UNEXPECTED");
                }
            }
        }
    }

    private static void logProfileSync(String userId, String path,
                                       WeComPartyProfileService.ProfileResult result) {
        if (result == null) {
            log.warn("event=wecom.employee.profile_sync_result userId={} path={} status=EMPTY avatarPresent=false",
                    userId, path);
            return;
        }
        log.info("event=wecom.employee.profile_sync_result userId={} path={} status={} avatarPresent={} errorCode={}",
                userId, path, result.profileStatus(),
                result.avatarUrl() != null && !result.avatarUrl().isBlank(),
                result.profileErrorCode() == null || result.profileErrorCode().isBlank()
                        ? "NONE" : result.profileErrorCode());
    }

    private static void validateCorp(WeComLoginAttemptService.AttemptContext context, String corpId) {
        if (context == null || context.installationBinding() == null
                || corpId == null || !corpId.equals(context.installationBinding().authCorpId())) {
            throw new WeComException("WECOM_LOGIN_CORP_MISMATCH", 403, "企业微信登录企业不匹配");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new WeComException("WECOM_LOGIN_REQUEST_INVALID", 400, name + "不能为空");
        }
    }

    public record ExchangeRequest(String code, String state) {}
    public record RequestMetadata(String remoteAddress, String userAgent) {}
}
