package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComInstallationService {
    private final WeComInstallationMapper mapper;
    private final WeComAuthorizationGateway gateway;

    public WeComInstallationService(WeComInstallationMapper mapper,
                                     WeComAuthorizationGateway gateway) {
        this.mapper = mapper;
        this.gateway = gateway;
    }

    public void handleCallback(WeComCallbackCodec.DecodedCallback callback)
            throws WeComException {
        switch (callback.infoType()) {
            case "suite_ticket" -> {
                gateway.acceptSuiteTicket(callback.suiteId(),
                        callback.suiteTicket(), callback.timestamp());
                recordTicketTimestamp(callback.suiteId(), callback.timestamp());
            }
            case "create_auth" -> {
                var perm = gateway.getPermanentCode(callback.authCode());
                var info = gateway.getAuthInfo(perm.authCorpId(), perm.permanentCode());
                for (var agent : info.agents()) {
                    upsert(callback.suiteId(), perm.authCorpId(),
                            agent.agentId(), perm.permanentCode());
                }
            }
            case "change_auth" -> {
                var existing = findActive(callback.suiteId(), callback.authCorpId());
                if (existing != null) {
                    var info = gateway.getAuthInfo(callback.authCorpId(),
                            existing.getPermanentCode());
                    for (var agent : info.agents()) {
                        upsert(callback.suiteId(), callback.authCorpId(),
                                agent.agentId(), existing.getPermanentCode());
                    }
                }
            }
            case "cancel_auth" -> revoke(callback.suiteId(), callback.authCorpId());
            default -> throw new WeComException("WECOM_CALLBACK_UNKNOWN_INFOTYPE",
                    400, "未知的 InfoType: " + callback.infoType());
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
