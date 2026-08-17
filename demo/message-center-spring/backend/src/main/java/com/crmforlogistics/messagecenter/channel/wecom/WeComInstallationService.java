package com.crmforlogistics.messagecenter.channel.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComCredentialProtector;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** Owns installation persistence and credential access, not callback orchestration. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComInstallationService {
    private final WeComInstallationMapper mapper;
    private final WeComAuthorizationGateway gateway;
    private final WeComCredentialProtector credentialProtector;

    public WeComInstallationService(WeComInstallationMapper mapper,
                                    WeComAuthorizationGateway gateway,
                                    WeComCredentialProtector credentialProtector) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.credentialProtector = credentialProtector;
    }

    public WeComInstallationEntity find(String suiteId, String authCorpId) {
        if (suiteId == null || suiteId.isBlank() || authCorpId == null || authCorpId.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<WeComInstallationEntity>()
                .eq(WeComInstallationEntity::getSuiteId, suiteId)
                .eq(WeComInstallationEntity::getAuthCorpId, authCorpId)
                .isNull(WeComInstallationEntity::getDeletedAt));
    }

    public void applyActiveForEvent(WeComCallbackCodec.DecodedCallback callback,
                                    String authCorpId, String agentId, String permanentCode,
                                    String eventId, long expectedVersion) {
        requireMutation(callback, authCorpId, eventId, expectedVersion);
        WeComInstallationEntity current = find(callback.suiteId(), authCorpId);
        if (isReplay(current, eventId)) return;
        rejectStale(current, callback.timestamp(), "ACTIVE");
        String protectedCode = credentialProtector.protectPermanentCode(permanentCode);
        if (current == null) {
            if (expectedVersion != 0) throw versionConflict();
            WeComInstallationEntity entity = new WeComInstallationEntity();
            entity.setSuiteId(callback.suiteId());
            entity.setAuthCorpId(authCorpId);
            entity.setAgentId(required(agentId, "agentId", 32));
            entity.setPermanentCode(protectedCode);
            entity.setAuthStatus("ACTIVE");
            entity.setAuthorizedAt(Instant.now());
            entity.setLastAuthorizationEventId(eventId);
            entity.setLastAuthorizationEventAt(callback.timestamp());
            entity.setVersion(1L);
            if (mapper.insert(entity) != 1) throw versionConflict();
            return;
        }
        long version = version(current);
        if (version != expectedVersion || mapper.updateForEvent(current.getId(),
                required(agentId, "agentId", 32), protectedCode, "ACTIVE", Instant.now(),
                eventId, callback.timestamp(), expectedVersion) != 1) {
            throw versionConflict();
        }
    }

    public void revokeForEvent(WeComCallbackCodec.DecodedCallback callback,
                               WeComInstallationEntity installation,
                               String eventId, long expectedVersion) {
        requireMutation(callback, callback.authCorpId(), eventId, expectedVersion);
        WeComInstallationEntity current = find(callback.suiteId(), callback.authCorpId());
        if (isReplay(current, eventId)) return;
        rejectStale(current, callback.timestamp(), "REVOKED");
        if (current == null || installation == null || current.getId() == null
                || !current.getId().equals(installation.getId())
                || version(current) != expectedVersion
                || mapper.updateStatusForEvent(current.getId(), "REVOKED", eventId,
                callback.timestamp(), expectedVersion) != 1) {
            throw versionConflict();
        }
    }

    public WeComInstallationEntity resolveActive(String suiteId, String authCorpId) {
        WeComInstallationEntity entity = mapper.findActive(suiteId, authCorpId);
        if (entity == null) {
            throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 404,
                    "未找到企业微信安装记录");
        }
        return entity;
    }

    public String accessToken(String suiteId, String authCorpId) {
        WeComInstallationEntity installation = resolveActive(suiteId, authCorpId);
        var token = gateway.getCorpToken(authCorpId,
                credentialProtector.revealPermanentCode(installation.getPermanentCode()));
        return token.accessToken();
    }

    public ResolvedInstallation resolveInstallation(String suiteId, String authCorpId) {
        WeComInstallationEntity entity = resolveActive(suiteId, authCorpId);
        return resolved(entity);
    }

    public ResolvedInstallation resolveRefreshable(String suiteId, String authCorpId) {
        WeComInstallationEntity entity = find(suiteId, authCorpId);
        if (entity == null) {
            throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 403,
                    "未找到企业微信授权安装记录");
        }
        if ("REVOKED".equals(entity.getAuthStatus())) {
            throw new WeComException("WECOM_INSTALLATION_INACTIVE", 403,
                    "企业微信授权安装已撤销或失效");
        }
        return resolved(entity);
    }

    private ResolvedInstallation resolved(WeComInstallationEntity entity) {
        String installationId = entity.getId() == null ? "" : entity.getId().toString();
        return new ResolvedInstallation(installationId, entity.getSuiteId(), entity.getAuthCorpId(),
                entity.getAgentId(), credentialProtector.revealPermanentCode(entity.getPermanentCode()),
                version(entity));
    }

    private static boolean isReplay(WeComInstallationEntity current, String eventId) {
        return current != null && eventId.equals(current.getLastAuthorizationEventId());
    }

    private static void rejectStale(WeComInstallationEntity current, Instant eventAt,
                                    String targetStatus) {
        if (current != null && current.getLastAuthorizationEventAt() != null
                && (eventAt.isBefore(current.getLastAuthorizationEventAt())
                || (eventAt.equals(current.getLastAuthorizationEventAt())
                && "ACTIVE".equals(targetStatus)
                && "REVOKED".equals(current.getAuthStatus())))) {
            throw new WeComException("WECOM_AUTHORIZATION_EVENT_STALE", 409,
                    "企业微信授权事件早于当前安装状态");
        }
    }

    private static long version(WeComInstallationEntity entity) {
        return entity.getVersion() == null ? 0L : entity.getVersion();
    }

    private static void requireMutation(WeComCallbackCodec.DecodedCallback callback,
                                        String authCorpId, String eventId, long expectedVersion) {
        if (callback == null || callback.timestamp() == null || expectedVersion < 0
                || eventId == null || !eventId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("authorization mutation is invalid");
        }
        required(callback.suiteId(), "suiteId", 128);
        required(authCorpId, "authCorpId", 128);
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static WeComException versionConflict() {
        return new WeComException("WECOM_AUTHORIZATION_VERSION_CONFLICT", 409,
                "企业微信授权安装版本已变化，请重试");
    }
}
