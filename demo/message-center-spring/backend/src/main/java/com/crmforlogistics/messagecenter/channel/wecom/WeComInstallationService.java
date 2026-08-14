package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationAuditTrail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComInstallationService {
    private final WeComInstallationMapper mapper;
    private final WeComAuthorizationGateway gateway;
    private final WeComAuthorizationAuditTrail auditTrail;
    private final Map<String, Instant> latestCancellation = new ConcurrentHashMap<>();
    private final Object installationMutationLock = new Object();

    public WeComInstallationService(WeComInstallationMapper mapper,
                                     WeComAuthorizationGateway gateway,
                                     WeComAuthorizationAuditTrail auditTrail) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.auditTrail = auditTrail;
    }

    public void handleCallback(WeComCallbackCodec.DecodedCallback callback)
            throws WeComException {
        if (callback == null || callback.infoType() == null || callback.infoType().isBlank()) {
            throw new WeComException("WECOM_CALLBACK_INVALID", 400,
                    "企业微信授权回调缺少 InfoType");
        }
        String affectedCorpId = callback.authCorpId();
        try {
            switch (callback.infoType()) {
                case "suite_ticket" -> {
                    gateway.acceptSuiteTicket(callback.suiteId(),
                            callback.suiteTicket(), callback.timestamp());
                    recordTicketTimestamp(callback.suiteId(), callback.timestamp());
                }
                case "create_auth" -> {
                    var perm = gateway.getPermanentCode(callback.authCode());
                    affectedCorpId = perm.authCorpId();
                    var info = gateway.getAuthInfo(perm.authCorpId(), perm.permanentCode());
                    if (!perm.authCorpId().equals(info.authCorpId())) {
                        throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500,
                                "企业微信授权信息不一致");
                    }
                    synchronized (installationMutationLock) {
                        if (cancelledAtOrAfter(callback.suiteId(), perm.authCorpId(), callback.timestamp())) {
                            return;
                        }
                        for (var agent : info.agents()) {
                            upsert(callback.suiteId(), perm.authCorpId(),
                                    agent.agentId(), perm.permanentCode());
                        }
                    }
                }
                case "change_auth" -> {
                    var existing = findActive(callback.suiteId(), callback.authCorpId());
                    if (existing != null) {
                        var info = gateway.getAuthInfo(callback.authCorpId(),
                                existing.getPermanentCode());
                        synchronized (installationMutationLock) {
                            if (cancelledAtOrAfter(callback.suiteId(), callback.authCorpId(),
                                    callback.timestamp())) {
                                return;
                            }
                            for (var agent : info.agents()) {
                                upsert(callback.suiteId(), callback.authCorpId(),
                                        agent.agentId(), existing.getPermanentCode());
                            }
                        }
                    }
                }
                case "reset_permanent_code" -> {
                    var perm = gateway.getPermanentCode(callback.authCode());
                    affectedCorpId = perm.authCorpId();
                    var info = gateway.getAuthInfo(perm.authCorpId(), perm.permanentCode());
                    if (!perm.authCorpId().equals(info.authCorpId())) {
                        throw new WeComException("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500,
                                "企业微信授权信息不一致");
                    }
                    synchronized (installationMutationLock) {
                        if (cancelledAtOrAfter(callback.suiteId(), perm.authCorpId(), callback.timestamp())) {
                            return;
                        }
                        for (var agent : info.agents()) {
                            upsert(callback.suiteId(), perm.authCorpId(),
                                    agent.agentId(), perm.permanentCode());
                        }
                    }
                }
                case "cancel_auth" -> {
                    synchronized (installationMutationLock) {
                        latestCancellation.merge(installationKey(callback.suiteId(), callback.authCorpId()),
                                callback.timestamp(), (left, right) -> left.isAfter(right) ? left : right);
                        revoke(callback.suiteId(), callback.authCorpId());
                    }
                }
                default -> throw new WeComException("WECOM_CALLBACK_UNKNOWN_INFOTYPE",
                        400, "未知的 InfoType: " + callback.infoType());
            }
            recordAudit(callback, affectedCorpId, "succeeded", null);
        } catch (WeComException failure) {
            markFailedIfNeeded(callback, affectedCorpId, callback.timestamp());
            recordAudit(callback, affectedCorpId, "failed", failure);
            throw failure;
        }
    }

    private boolean cancelledAtOrAfter(String suiteId, String authCorpId, Instant eventTimestamp) {
        Instant cancelledAt = latestCancellation.get(installationKey(suiteId, authCorpId));
        return cancelledAt != null && !cancelledAt.isBefore(eventTimestamp);
    }

    private static String installationKey(String suiteId, String authCorpId) {
        return suiteId + "\u0000" + authCorpId;
    }

    private void recordAudit(WeComCallbackCodec.DecodedCallback callback, String authCorpId,
                             String result, WeComException failure) {
        try {
            auditTrail.record(callback, authCorpId, result, failure);
        } catch (RuntimeException auditFailure) {
            // Audit failures must never poison installation state or callback ack.
        }
    }

    private void markFailedIfNeeded(WeComCallbackCodec.DecodedCallback callback,
                                    String authCorpId, Instant eventTimestamp) {
        if (authCorpId == null || authCorpId.isBlank()) return;
        try {
            synchronized (installationMutationLock) {
                if (cancelledAtOrAfter(callback.suiteId(), authCorpId, eventTimestamp)) return;
                var existing = findActive(callback.suiteId(), authCorpId);
                if (existing != null) {
                    existing.setAuthStatus("FAILED");
                    existing.setUpdatedAt(Instant.now());
                    mapper.updateById(existing);
                }
            }
        } catch (RuntimeException ignored) {
            // Failure marking must not mask the original callback error.
        }
    }

    public WeComInstallationEntity resolveActive(String suiteId, String authCorpId)
            throws WeComException {
        var entity = mapper.findActive(suiteId, authCorpId);
        if (entity == null) {
            throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 404,
                    "未找到企业微信安装记录");
        }
        return entity;
    }

    public String accessToken(String suiteId, String authCorpId)
            throws WeComException {
        var installation = resolveActive(suiteId, authCorpId);
        var token = gateway.getCorpToken(authCorpId,
                installation.getPermanentCode());
        return token.accessToken();
    }

    public ResolvedInstallation resolveInstallation(String suiteId, String authCorpId)
            throws WeComException {
        var entity = mapper.findActive(suiteId, authCorpId);
        if (entity == null) {
            throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 404,
                    "未找到企业微信安装记录");
        }
        String installationId = entity.getId() == null ? "" : entity.getId().toString();
        long version = entity.getVersion() == null || entity.getVersion() < 1 ? 1 : entity.getVersion();
        return new ResolvedInstallation(installationId, entity.getSuiteId(), entity.getAuthCorpId(),
                entity.getAgentId(), entity.getPermanentCode(), version);
    }

    private WeComInstallationEntity findActive(String suiteId, String authCorpId) {
        return mapper.findActive(suiteId, authCorpId);
    }

    private void upsert(String suiteId, String authCorpId, String agentId, String permanentCode) {
        var existing = findActive(suiteId, authCorpId);
        if (existing != null) {
            existing.setAgentId(agentId);
            existing.setPermanentCode(permanentCode);
            existing.setAuthStatus("ACTIVE");
            existing.setAuthorizedAt(Instant.now());
            existing.setUpdatedAt(Instant.now());
            mapper.updateById(existing);
        } else {
            var entity = new WeComInstallationEntity();
            entity.setSuiteId(suiteId);
            entity.setAuthCorpId(authCorpId);
            entity.setAgentId(agentId);
            entity.setPermanentCode(permanentCode);
            entity.setAuthStatus("ACTIVE");
            entity.setAuthorizedAt(Instant.now());
            mapper.insert(entity);
        }
    }

    private void revoke(String suiteId, String authCorpId) {
        var existing = findActive(suiteId, authCorpId);
        if (existing != null) {
            existing.setAuthStatus("REVOKED");
            existing.setUpdatedAt(Instant.now());
            mapper.updateById(existing);
        }
    }

    private void recordTicketTimestamp(String suiteId, Instant timestamp) {
        if (suiteId == null || suiteId.isBlank()) return;
        var entities = mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WeComInstallationEntity>()
                        .eq(WeComInstallationEntity::getSuiteId, suiteId)
                        .eq(WeComInstallationEntity::getAuthStatus, "ACTIVE")
                        .isNull(WeComInstallationEntity::getDeletedAt));
        for (var entity : entities) {
            entity.setLastSuiteTicketAt(timestamp);
            mapper.updateById(entity);
        }
    }
}
