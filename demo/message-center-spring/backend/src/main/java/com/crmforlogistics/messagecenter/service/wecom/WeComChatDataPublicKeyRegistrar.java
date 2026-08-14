package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataPublicKeyGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-chatdata-public-key-auto-register:false}' == 'true'")
public class WeComChatDataPublicKeyRegistrar {
    private static final Logger log = LoggerFactory.getLogger(WeComChatDataPublicKeyRegistrar.class);
    private final AppConfig config;
    private final WeComInstallationService installationService;
    private final WeComAccessTokenService accessTokens;
    private final WeComChatDataPublicKeyRegistrationStore registrationStore;
    private final WeComChatDataPublicKeyGateway gateway;
    private final WeComStartupGate startupGate;
    private volatile WeComChatDataCrypto.PublicKeyMaterial cachedMaterial;

    public WeComChatDataPublicKeyRegistrar(AppConfig config,
                                           WeComInstallationService installationService,
                                           WeComAccessTokenService accessTokens,
                                           WeComChatDataPublicKeyRegistrationStore registrationStore,
                                           WeComChatDataPublicKeyGateway gateway,
                                           WeComStartupGate startupGate) {
        this.config = config;
        this.installationService = installationService;
        this.accessTokens = accessTokens;
        this.registrationStore = registrationStore;
        this.gateway = gateway;
        this.startupGate = startupGate;
    }

    @Scheduled(fixedDelayString = "${app.wecom-chatdata-auto-sync-interval-seconds:60}000",
            initialDelay = 5000)
    public void registerIfNeeded() {
        try {
            startupGate.requireOpen();
            ResolvedInstallation installation = installationService.resolveInstallation(
                    config.wecomSuiteId(), config.wecomLoginAuthCorpId());
            WeComChatDataCrypto.PublicKeyMaterial material = material();
            if (registrationStore.isRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256())) {
                return;
            }
            String accessToken = accessTokens.accessToken(installation);
            gateway.register(accessToken, material);
            registrationStore.markRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256());
        } catch (Exception e) {
            log.warn("WeCom chatdata public key registration failed", e);
        }
    }

    private WeComChatDataCrypto.PublicKeyMaterial material() {
        WeComChatDataCrypto.PublicKeyMaterial current = cachedMaterial;
        if (current != null) return current;
        current = new WeComChatDataCrypto(config).publicKeyMaterial();
        cachedMaterial = current;
        return current;
    }
}
